package ro.ppc.filegrid.stats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.domain.StoredFileRepository;
import ro.ppc.filegrid.service.StoredContent;
import ro.ppc.filegrid.storage.ObjectNotFoundException;
import ro.ppc.filegrid.storage.StorageUnavailableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class MeterReadingAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MeterReadingAnalyzer.class);
    private static final int BATCH = 100;
    private static final int MAX_BATCHES = 50;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final double SAG_BELOW_VOLTS = 207.0;
    private static final double SPIKE_FACTOR = 3.0;
    private static final int SPIKE_WINDOW = 6;

    private final JdbcTemplate jdbc;
    private final StoredFileRepository repository;
    private final StoredContent content;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public MeterReadingAnalyzer(JdbcTemplate jdbc, StoredFileRepository repository, StoredContent content,
                                MeterRegistry meters, TransactionTemplate transactions) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.repository = repository;
        this.content = content;
        this.meters = meters;
    }

    record Summary(String meterId, String region, String consumerType, Instant readingAt, int samples,
                   Double avgKw, Double maxKw, Double minVoltage, int outages, int sags, int spikes) {
    }

    @Scheduled(fixedDelayString = "${filegrid.stats.analysis-interval:5s}", initialDelayString = "20s")
    void analyzePending() {
        try {
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                Integer claimed = transactions.execute(status -> {
                    List<UUID> ids = jdbc.queryForList("""
                            SELECT f.id FROM stored_file f
                            LEFT JOIN meter_reading_stat s ON s.file_id = f.id
                            WHERE f.category = 'METER_READING' AND f.status = 'STORED' AND s.file_id IS NULL
                            ORDER BY f.created_at DESC LIMIT ?
                            FOR UPDATE OF f SKIP LOCKED""", UUID.class, BATCH);
                    for (StoredFile file : repository.findAllById(ids)) {
                        analyze(file);
                    }
                    return ids.size();
                });
                if (claimed == null || claimed < BATCH) {
                    return;
                }
            }
        } catch (StorageUnavailableException e) {
            log.debug("Analiza citirilor amanata: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Analiza citirilor a esuat: {}", e.getMessage());
        }
    }

    private void analyze(StoredFile file) {
        Summary summary;
        try (InputStream in = content.open(file)) {
            summary = summarize(json.readTree(in.readNBytes(MAX_BYTES)), file);
        } catch (IOException | ObjectNotFoundException | JacksonException e) {
            summary = null;
        }
        if (summary == null) {
            jdbc.update("""
                    INSERT INTO meter_reading_stat (file_id, meter_id, region, reading_at, parsed, analyzed_at)
                    VALUES (?, ?, ?, ?, false, ?) ON CONFLICT (file_id) DO NOTHING""",
                    file.getId(), file.getSourceId(), file.getRegion(), Timestamp.from(file.getCreatedAt()),
                    Timestamp.from(Instant.now()));
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO meter_reading_stat (file_id, meter_id, region, consumer_type, reading_at, samples, avg_kw,
                                                max_kw, min_voltage, outage_samples, sag_samples, spike_samples,
                                                parsed, analyzed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?) ON CONFLICT (file_id) DO NOTHING""",
                file.getId(), summary.meterId(), summary.region(), summary.consumerType(),
                Timestamp.from(summary.readingAt()), summary.samples(), summary.avgKw(), summary.maxKw(),
                summary.minVoltage(), summary.outages(), summary.sags(), summary.spikes(), Timestamp.from(Instant.now()));
        if (inserted == 1) {
            String region = summary.region() == null ? "necunoscut" : summary.region();
            Counter.builder("filegrid.meter.readings.analyzed")
                    .description("Citiri de contor al caror continut a fost analizat")
                    .tag("region", region).register(meters).increment();
            countAnomaly(region, "intrerupere", summary.outages());
            countAnomaly(region, "tensiune_scazuta", summary.sags());
            countAnomaly(region, "varf_consum", summary.spikes());
        }
    }

    private void countAnomaly(String region, String kind, int samples) {
        if (samples > 0) {
            Counter.builder("filegrid.meter.anomalies")
                    .description("Citiri de contor in care s-a gasit o anomalie")
                    .tag("region", region).tag("kind", kind).register(meters).increment();
        }
    }

    Summary summarize(JsonNode root, StoredFile file) {
        JsonNode samples = root.path("samples");
        if (!root.isObject() || !samples.isArray()) {
            return null;
        }
        int count = samples.size();
        double[] kw = new double[count];
        double sumKw = 0;
        double maxKw = 0;
        double minVoltage = Double.MAX_VALUE;
        int outages = 0;
        int sags = 0;
        for (int i = 0; i < count; i++) {
            JsonNode sample = samples.get(i);
            kw[i] = sample.path("activeKw").asDouble(0);
            double voltage = sample.path("voltageV").asDouble(230);
            sumKw += kw[i];
            maxKw = Math.max(maxKw, kw[i]);
            minVoltage = Math.min(minVoltage, voltage);
            if (voltage < 1) {
                outages++;
            } else if (voltage < SAG_BELOW_VOLTS) {
                sags++;
            }
        }
        int spikes = 0;
        for (int i = 0; i < count; i++) {
            int from = Math.max(0, i - SPIKE_WINDOW);
            int to = Math.min(count, i + SPIKE_WINDOW + 1);
            double[] window = Arrays.copyOfRange(kw, from, to);
            Arrays.sort(window);
            double median = window[window.length / 2];
            if (median > 0 && kw[i] > median * SPIKE_FACTOR) {
                spikes++;
            }
        }
        return new Summary(
                text(root, "meterId", file.getSourceId()),
                text(root, "region", file.getRegion()),
                text(root, "consumerType", null),
                instant(root.path("readingAt").asString(""), file.getCreatedAt()),
                count,
                count == 0 ? null : sumKw / count,
                count == 0 ? null : maxKw,
                count == 0 ? null : minVoltage,
                outages, sags, spikes);
    }

    private static String text(JsonNode root, String field, String fallback) {
        String value = root.path(field).asString("");
        return value.isBlank() ? fallback : value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static Instant instant(String value, Instant fallback) {
        try {
            return value.isBlank() ? fallback : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
