package ro.ppc.filegrid.stats;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StorageType;
import ro.ppc.filegrid.service.EncryptionService;
import ro.ppc.filegrid.storage.StorageBackend;
import ro.ppc.filegrid.storage.StorageRegistry;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class StorageBenchmark {

    private static final Logger log = LoggerFactory.getLogger(StorageBenchmark.class);

    record Profile(int files, int sizeBytes) {
    }

    private static final Map<DataCategory, Profile> PROFILES = new EnumMap<>(Map.of(
            DataCategory.METER_READING, new Profile(60, 20 * 1024),
            DataCategory.INCIDENT_RECORDING, new Profile(15, 5 * 1024 * 1024),
            DataCategory.CONNECTION_DOCUMENT, new Profile(15, 2 * 1024 * 1024),
            DataCategory.DRONE_VIDEO, new Profile(6, 32 * 1024 * 1024)));

    private final StorageRegistry storages;
    private final FileGridProperties props;
    private final EncryptionService encryption;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private static final long LOCK_ID = 0x46_47_42_45_4E_43_48L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final TransactionTemplate transactions;

    public StorageBenchmark(StorageRegistry storages, FileGridProperties props, EncryptionService encryption,
                            JdbcTemplate jdbc, MeterRegistry meters, TransactionTemplate transactions) {
        this.transactions = transactions;
        this.storages = storages;
        this.props = props;
        this.encryption = encryption;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public static class AlreadyRunning extends RuntimeException {
        AlreadyRunning() {
            super("O masuratoare este deja in curs.");
        }
    }

    public UUID run() {
        if (!running.compareAndSet(false, true)) {
            throw new AlreadyRunning();
        }
        try {
            return transactions.execute(status -> {
                Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_ID);
                if (!Boolean.TRUE.equals(locked)) {
                    throw new AlreadyRunning();
                }
                return measureAll();
            });
        } finally {
            running.set(false);
        }
    }

    private UUID measureAll() {
        UUID runId = UUID.randomUUID();
        log.info("Masuratoare storage {} pornita", runId);
        for (DataCategory category : DataCategory.values()) {
            Profile profile = PROFILES.get(category);
            byte[] payload = new byte[profile.sizeBytes()];
            new SplittableRandom(category.ordinal() + 1).nextBytes(payload);
            boolean encrypt = props.policyFor(category).encryptAtRest();
            long dbLimit = props.storage().database().maxObjectSize().toBytes();
            for (StorageType type : StorageType.values()) {
                if (type == StorageType.DATABASE && props.policyFor(category).maxFileSize().toBytes() > dbLimit) {
                    skip(runId, category, type, payload.length);
                    continue;
                }
                measure(runId, category, profile, payload, encrypt, storages.get(type));
            }
        }
        log.info("Masuratoare storage {} terminata", runId);
        return runId;
    }

    private void skip(UUID runId, DataCategory category, StorageType type, int size) {
        jdbc.update("""
                INSERT INTO storage_benchmark (run_id, category, storage_type, files, file_size_bytes, errors, measured_at)
                VALUES (?, ?, ?, 0, ?, 0, ?)""",
                runId, category.name(), type.name(), size, Timestamp.from(Instant.now()));
    }

    private void measure(UUID runId, DataCategory category, Profile profile, byte[] payload, boolean encrypt,
                         StorageBackend backend) {
        String prefix = "benchmark/" + runId + "/" + category.name().toLowerCase() + "/";
        byte[] expected = sha256(payload);
        try {
            roundTrip(backend, prefix + "warmup", payload, encrypt, expected);
        } catch (RuntimeException | java.io.IOException ignored) {
        }

        List<Double> writes = new ArrayList<>();
        List<Double> reads = new ArrayList<>();
        int errors = 0;
        String firstError = null;
        for (int i = 0; i < profile.files(); i++) {
            try {
                double[] ms = roundTrip(backend, prefix + i, payload, encrypt, expected);
                writes.add(ms[0]);
                reads.add(ms[1]);
                record(category, backend.type(), "write", ms[0]);
                record(category, backend.type(), "read", ms[1]);
            } catch (RuntimeException | java.io.IOException e) {
                errors++;
                if (firstError == null) {
                    firstError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
        }
        double megabytes = payload.length / (1024.0 * 1024.0);
        jdbc.update("""
                INSERT INTO storage_benchmark (run_id, category, storage_type, files, file_size_bytes, write_p50_ms,
                                               write_p95_ms, read_p50_ms, read_p95_ms, write_mb_per_s, read_mb_per_s,
                                               errors, error_detail, measured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                runId, category.name(), backend.type().name(), profile.files(), payload.length,
                percentile(writes, 0.5), percentile(writes, 0.95), percentile(reads, 0.5), percentile(reads, 0.95),
                throughput(writes, megabytes), throughput(reads, megabytes), errors,
                firstError == null ? null : firstError.substring(0, Math.min(firstError.length(), 500)),
                Timestamp.from(Instant.now()));
    }

    private double[] roundTrip(StorageBackend backend, String key, byte[] payload, boolean encrypt, byte[] expected)
            throws java.io.IOException {
        byte[] iv = encrypt ? encryption.newIv() : null;
        try {
            long started = System.nanoTime();
            InputStream source = new ByteArrayInputStream(payload);
            backend.write(key, iv != null ? encryption.encrypt(source, iv) : source,
                    payload.length + (iv != null ? EncryptionService.TAG_BYTES : 0), "application/octet-stream");
            long written = System.nanoTime();
            MessageDigest digest = digest();
            byte[] buffer = new byte[64 * 1024];
            InputStream raw = backend.read(key);
            try (InputStream in = iv != null ? encryption.decrypt(raw, iv) : raw) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            long read = System.nanoTime();
            if (!MessageDigest.isEqual(expected, digest.digest())) {
                throw new java.io.IOException("continutul citit difera de cel scris");
            }
            return new double[]{(written - started) / 1_000_000.0, (read - written) / 1_000_000.0};
        } finally {
            try {
                backend.delete(key);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void record(DataCategory category, StorageType storage, String operation, double millis) {
        Timer.builder("filegrid.benchmark.operation")
                .description("Durata operatiilor din masuratoarea comparativa a storage-urilor")
                .tag("category", category.name())
                .tag("storage", storage.name())
                .tag("operation", operation)
                .publishPercentileHistogram()
                .register(meters)
                .record(Duration.ofNanos((long) (millis * 1_000_000)));
    }

    private static Double percentile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(q * sorted.size()) - 1));
    }

    private static Double throughput(List<Double> millis, double megabytesEach) {
        double total = millis.stream().mapToDouble(Double::doubleValue).sum();
        return millis.isEmpty() || total <= 0 ? null : megabytesEach * millis.size() / (total / 1000.0);
    }

    private static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
