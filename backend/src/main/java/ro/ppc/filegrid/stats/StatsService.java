package ro.ppc.filegrid.stats;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StorageType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class StatsService {

    private static final Locale RO = Locale.forLanguageTag("ro");
    private static final int BENCHMARK_RUNS = 3;
    private static final List<DataCategory> STORM_PEAKS = List.of(DataCategory.INCIDENT_RECORDING, DataCategory.DRONE_VIDEO);
    private static final double TOLERANCE = 0.15;

    private static final Map<DataCategory, String> LABELS = Map.of(
            DataCategory.METER_READING, "Citiri contoare",
            DataCategory.INCIDENT_RECORDING, "Înregistrări de avarie",
            DataCategory.CONNECTION_DOCUMENT, "Documente de racordare",
            DataCategory.DRONE_VIDEO, "Video-uri dronă");

    private static final Map<DataCategory, String> CRITERIA = Map.of(
            DataCategory.METER_READING, "Multe fișiere mici: contează costul fix per fișier (scrierea, mediana).",
            DataCategory.INCIDENT_RECORDING, "Trebuie să fie disponibile imediat: contează scrierea plus citirea (mediana).",
            DataCategory.CONNECTION_DOCUMENT, "Volum moderat, conținut criptat: contează scrierea plus citirea (mediana).",
            DataCategory.DRONE_VIDEO, "Fișiere foarte mari: contează debitul la scriere (MB/s).");

    private static final Map<StorageType, String> TRAITS = Map.of(
            StorageType.S3, "S3 refolosește conexiunile HTTP și scrie în flux, deci nu are un cost fix mare per fișier.",
            StorageType.DATABASE, "Baza de date scrie un fișier mic printr-o singură inserare pe o conexiune deja deschisă, dar ține fiecare fișier integral în memorie.",
            StorageType.FTP, "FTP transferă în flux, iar FileGrid refolosește conexiunile deschise, dar fiecare fișier are nevoie de o conexiune de date separată și de o redenumire la final.");

    private static final Map<String, String> REGIONS = Map.ofEntries(
            Map.entry("B", "București"), Map.entry("IF", "Ilfov"), Map.entry("GR", "Giurgiu"),
            Map.entry("CT", "Constanța"), Map.entry("TL", "Tulcea"), Map.entry("CL", "Călărași"),
            Map.entry("IL", "Ialomița"), Map.entry("TM", "Timiș"), Map.entry("AR", "Arad"),
            Map.entry("CS", "Caraș-Severin"), Map.entry("HD", "Hunedoara"));

    private static final String LOAD_CTE = """
            WITH f AS (
                SELECT category, status, size_bytes, date_trunc('minute', created_at) AS minute,
                       extract(epoch FROM (stored_at - created_at)) * 1000 AS ms
                FROM stored_file WHERE created_at >= ?),
            per_minute AS (SELECT category, minute, count(*) AS n FROM f GROUP BY category, minute),
            baseline AS (SELECT category, percentile_cont(0.5) WITHIN GROUP (ORDER BY n) AS median
                         FROM per_minute GROUP BY category),
            classified AS (SELECT p.category, p.minute, p.n >= greatest(b.median * 3, b.median + 10) AS peak
                           FROM per_minute p JOIN baseline b ON b.category = p.category),
            peak_minutes AS (SELECT category, minute FROM classified WHERE peak)
            """;

    private final JdbcTemplate jdbc;
    private final FileGridProperties props;

    public StatsService(JdbcTemplate jdbc, FileGridProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public record StorageMeasurement(String storage, int files, long fileSizeBytes, Double writeP50Ms, Double writeP95Ms,
                                     Double readP50Ms, Double readP95Ms, Double writeMbPerS, Double readMbPerS,
                                     int errors, String note, boolean eligible) {
    }

    public record CategoryFit(DataCategory category, String currentStorage, String fastestStorage,
                              String recommendedStorage, String criterion, String verdict,
                              List<StorageMeasurement> measurements) {
    }

    public record LiveUsage(DataCategory category, String storage, long files, long bytes, Double p50Ms, Double p95Ms,
                            long failed) {
    }

    public record StorageSection(Instant measuredAt, int runs, List<CategoryFit> fit, List<LiveUsage> live,
                                 List<String> summary) {
    }

    public record LoadPhase(long minutes, long stored, long failed, long bytes, double filesPerMinute, Double p50Ms,
                            Double p95Ms) {
    }

    public record CategoryLoad(DataCategory category, LoadPhase normal, LoadPhase peak) {
    }

    public record LoadSection(List<CategoryLoad> categories, List<String> conclusions) {
    }

    public record RegionStat(String region, String name, long readings, long meters, Double avgKw, long outages,
                             long sags, long spikes, double anomaliesPer1000, long incidents, long droneVideos) {
    }

    public record ConsumerStat(String type, long readings, long meters, Double avgKw, Double maxKw) {
    }

    public record MeterIssue(String meterId, String region, String consumerType, long readings, long outages,
                             long sags, long spikes, long affected) {
    }

    public record SilentMeter(String meterId, String region, Instant lastSeen) {
    }

    public record NetworkSection(long readingsAnalyzed, List<RegionStat> regions, List<ConsumerStat> consumers,
                                 List<MeterIssue> problemMeters, long silentMeters, List<SilentMeter> silentSample,
                                 List<String> conclusions) {
    }

    public record BucketCategory(long stored, long failed, Double p95Ms, boolean peak) {
    }

    public record Bucket(LocalDateTime start, Map<DataCategory, BucketCategory> categories, long stored, long failed,
                         boolean peak) {
    }

    public record TimelineSection(String unit, List<Bucket> buckets, List<String> conclusions) {
    }

    public record Stats(int hours, Instant generatedAt, StorageSection storage, LoadSection load,
                        TimelineSection timeline, NetworkSection network) {
    }

    private record Unit(String code, Duration step, String sql, String busiest, String typical, String plural,
                        String single) {
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
    private static final LocalDateTime ORIGIN = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    public Stats stats(int hours) {
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofHours(hours)));
        return new Stats(hours, Instant.now(), storage(since), load(since), timeline(hours), network(since));
    }

    private static Unit unit(int hours) {
        if (hours <= 2) {
            return new Unit("5min", Duration.ofMinutes(5), "5 minutes", "cel mai încărcat interval de 5 minute",
                    "într-un interval de 5 minute obișnuit", "intervale de 5 minute", "interval de 5 minute");
        }
        if (hours <= 12) {
            return new Unit("30min", Duration.ofMinutes(30), "30 minutes", "cea mai încărcată jumătate de oră",
                    "într-o jumătate de oră obișnuită", "jumătăți de oră", "jumătate de oră");
        }
        if (hours <= 48) {
            return new Unit("hour", Duration.ofHours(1), "1 hour", "cea mai încărcată oră",
                    "într-o oră obișnuită", "ore", "oră");
        }
        return new Unit("day", Duration.ofDays(1), "1 day", "cea mai încărcată zi", "într-o zi obișnuită", "zile", "zi");
    }

    private TimelineSection timeline(int hours) {
        Unit unit = unit(hours);
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime first = floor(now.minusHours(hours), unit.step());
        Map<LocalDateTime, Map<DataCategory, long[]>> counts = new TreeMap<>();
        Map<LocalDateTime, Map<DataCategory, Double>> p95 = new TreeMap<>();
        for (LocalDateTime t = first; !t.isAfter(now); t = t.plus(unit.step())) {
            counts.put(t, new EnumMap<>(DataCategory.class));
            p95.put(t, new EnumMap<>(DataCategory.class));
        }
        jdbc.query("""
                SELECT date_bin(?::interval, created_at AT TIME ZONE 'Europe/Bucharest', TIMESTAMP '2000-01-01'), category,
                       count(*) FILTER (WHERE status = 'STORED'), count(*) FILTER (WHERE status = 'FAILED'),
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM (stored_at - created_at)) * 1000)
                           FILTER (WHERE status = 'STORED')
                FROM stored_file WHERE created_at >= ?
                GROUP BY 1, 2""", rs -> {
            LocalDateTime start = rs.getObject(1, LocalDateTime.class);
            DataCategory category = DataCategory.valueOf(rs.getString(2));
            counts.computeIfAbsent(start, s -> new EnumMap<>(DataCategory.class))
                    .put(category, new long[]{rs.getLong(3), rs.getLong(4)});
            Double value = number(rs, 5);
            if (value != null) {
                p95.computeIfAbsent(start, s -> new EnumMap<>(DataCategory.class)).put(category, value);
            }
        }, unit.sql(), Timestamp.from(first.atZone(ZONE).toInstant()));

        Map<DataCategory, Double> medians = new EnumMap<>(DataCategory.class);
        Map<DataCategory, Double> thresholds = new EnumMap<>(DataCategory.class);
        for (DataCategory category : DataCategory.values()) {
            Double found = median(counts.entrySet().stream()
                    .filter(e -> e.getKey().plus(unit.step()).isBefore(now))
                    .map(e -> (double) e.getValue().getOrDefault(category, new long[2])[0]).toList());
            double median = found == null ? 0 : found;
            medians.put(category, median);
            thresholds.put(category, Math.max(median * 3, median + 5));
        }

        List<Bucket> buckets = new ArrayList<>();
        counts.forEach((start, perCategory) -> {
            Map<DataCategory, BucketCategory> view = new EnumMap<>(DataCategory.class);
            long stored = 0;
            long failed = 0;
            boolean anyPeak = false;
            for (DataCategory category : DataCategory.values()) {
                long[] c = perCategory.getOrDefault(category, new long[2]);
                boolean peak = c[0] >= thresholds.get(category);
                anyPeak |= peak;
                stored += c[0];
                failed += c[1];
                view.put(category, new BucketCategory(c[0], c[1], p95.get(start).get(category), peak));
            }
            buckets.add(new Bucket(start, view, stored, failed, anyPeak));
        });
        List<Bucket> complete = buckets.stream().filter(b -> b.start().plus(unit.step()).isBefore(now)).toList();
        return new TimelineSection(unit.code(), buckets, timelineConclusions(unit, buckets, complete, medians));
    }

    private static List<String> timelineConclusions(Unit unit, List<Bucket> buckets, List<Bucket> complete,
                                                    Map<DataCategory, Double> medians) {
        List<String> out = new ArrayList<>();
        if (complete.size() < 3) {
            out.add("Intervalul ales are prea puține " + unit.plural() + " pentru o comparație. Alegeți un interval mai lung.");
            return out;
        }
        for (DataCategory category : DataCategory.values()) {
            List<Bucket> withData = buckets.stream().filter(b -> b.categories().get(category).stored() > 0).toList();
            boolean completeData = complete.stream().anyMatch(b -> b.categories().get(category).stored() > 0);
            if (withData.isEmpty() || (!completeData && withData.stream().noneMatch(b -> b.categories().get(category).peak()))) {
                continue;
            }
            Bucket busiest = withData.stream().max(Comparator.comparingLong(b -> b.categories().get(category).stored()))
                    .orElseThrow();
            BucketCategory top = busiest.categories().get(category);
            double typical = medians.get(category);
            String label = LABELS.get(category);
            if (!top.peak()) {
                long min = complete.stream().mapToLong(b -> b.categories().get(category).stored()).min().orElse(0);
                long max = complete.stream().mapToLong(b -> b.categories().get(category).stored()).max().orElse(0);
                out.add("„" + label + "”: fără vârfuri; între " + files(min) + " și " + files(max) + " pe "
                        + unit.single() + " (de obicei " + files(Math.round(typical)) + ").");
                continue;
            }
            StringBuilder text = new StringBuilder("„").append(label).append("”: ").append(unit.busiest()).append(" (")
                    .append(format(busiest.start(), unit)).append(") a avut ").append(files(top.stored()));
            if (typical >= 1) {
                text.append(", față de ").append(files(Math.round(typical))).append(' ').append(unit.typical())
                        .append(" (de ").append(decimal(top.stored() / typical)).append(" ori mai mult). ");
            } else {
                text.append(", deși ").append(unit.typical()).append(" categoria e aproape inactivă. ");
            }
            Double normalP95 = median(buckets.stream()
                    .filter(b -> !b.categories().get(category).peak() && b.categories().get(category).p95Ms() != null)
                    .map(b -> b.categories().get(category).p95Ms()).toList());
            if (top.p95Ms() != null && normalP95 != null && normalP95 > 0) {
                text.append("Durata p95: ").append(millis(normalP95)).append(" în perioadele obișnuite, ")
                        .append(millis(top.p95Ms())).append(" la vârf. ");
            }
            long attempts = top.stored() + top.failed();
            if (attempts > 0 && (double) top.failed() / attempts > 0.01) {
                text.append(percent((double) top.failed() / attempts))
                        .append(" dintre încărcări au eșuat la vârf: capacitatea trebuie mărită.");
            } else if (top.p95Ms() != null && normalP95 != null && normalP95 > 0 && top.p95Ms() / normalP95 > 2) {
                text.append("Nu s-a pierdut nimic, dar latența s-a dublat: categoria e aproape de limită.");
            } else {
                text.append("Vârful a fost absorbit fără eșecuri.");
            }
            out.add(text.toString());

            for (DataCategory other : DataCategory.values()) {
                if (other == category) {
                    continue;
                }
                Double during = median(buckets.stream().filter(b -> b.categories().get(category).peak())
                        .map(b -> b.categories().get(other).p95Ms()).filter(v -> v != null).toList());
                Double outside = median(buckets.stream().filter(b -> !b.categories().get(category).peak())
                        .map(b -> b.categories().get(other).p95Ms()).filter(v -> v != null).toList());
                if (during == null || outside == null || outside <= 0) {
                    continue;
                }
                double ratio = during / outside;
                out.add("În perioadele de vârf ale categoriei „" + label + "”, „" + LABELS.get(other) + "” a avut p95 "
                        + millis(during) + ", față de " + millis(outside) + " în rest: "
                        + (ratio <= 1.5 ? "neafectată." : "încetinită de " + decimal(ratio) + " ori."));
            }
        }
        if (out.isEmpty()) {
            out.add("Nu există fișiere în intervalul ales.");
        }
        return out;
    }

    private static String files(long n) {
        if (n == 1) {
            return "1 fișier";
        }
        long rest = n % 100;
        return String.format(RO, "%,d", n) + (n != 0 && (rest == 0 || rest >= 20) ? " de fișiere" : " fișiere");
    }

    private static LocalDateTime floor(LocalDateTime t, Duration step) {
        long minutes = Duration.between(ORIGIN, t).toMinutes();
        long stepMinutes = step.toMinutes();
        return ORIGIN.plusMinutes(Math.floorDiv(minutes, stepMinutes) * stepMinutes);
    }

    private static String format(LocalDateTime start, Unit unit) {
        if (unit.step().equals(Duration.ofDays(1))) {
            return start.format(DAY);
        }
        return start.format(TIME) + "–" + start.plus(unit.step()).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    private StorageSection storage(Timestamp since) {
        Map<DataCategory, List<StorageMeasurement>> measured = new EnumMap<>(DataCategory.class);
        Instant[] measuredAt = new Instant[1];
        int[] runs = new int[1];
        long dbLimit = props.storage().database().maxObjectSize().toBytes();
        jdbc.query("""
                WITH recent AS (
                    SELECT run_id FROM storage_benchmark GROUP BY run_id ORDER BY max(measured_at) DESC LIMIT ?),
                latest_size AS (
                    SELECT DISTINCT ON (category) category, file_size_bytes FROM storage_benchmark
                    ORDER BY category, measured_at DESC)
                SELECT b.category, b.storage_type, sum(b.files), max(b.file_size_bytes), avg(b.write_p50_ms),
                       avg(b.write_p95_ms), avg(b.read_p50_ms), avg(b.read_p95_ms), avg(b.write_mb_per_s),
                       avg(b.read_mb_per_s), sum(b.errors), max(b.error_detail), max(b.measured_at),
                       count(DISTINCT b.run_id)
                FROM storage_benchmark b
                JOIN latest_size l ON l.category = b.category AND l.file_size_bytes = b.file_size_bytes
                WHERE b.run_id IN (SELECT run_id FROM recent)
                GROUP BY b.category, b.storage_type
                ORDER BY b.category, b.storage_type""", rs -> {
            DataCategory category = DataCategory.valueOf(rs.getString(1));
            StorageType storage = StorageType.valueOf(rs.getString(2));
            int errors = rs.getInt(11);
            boolean tooLarge = storage == StorageType.DATABASE
                    && props.policyFor(category).maxFileSize().toBytes() > dbLimit;
            String note = errors > 0 ? "Erori la măsurătoare: " + rs.getString(12)
                    : tooLarge ? "Exclus: categoria admite fișiere peste limita de " + dbLimit / (1024 * 1024)
                    + " MB per obiect a bazei de date." : null;
            measured.computeIfAbsent(category, c -> new ArrayList<>()).add(new StorageMeasurement(storage.name(),
                    rs.getInt(3), rs.getLong(4), number(rs, 5), number(rs, 6), number(rs, 7), number(rs, 8),
                    number(rs, 9), number(rs, 10), errors, note, errors == 0 && !tooLarge));
            Instant at = rs.getTimestamp(13).toInstant();
            measuredAt[0] = measuredAt[0] == null || at.isAfter(measuredAt[0]) ? at : measuredAt[0];
            runs[0] = Math.max(runs[0], rs.getInt(14));
        }, BENCHMARK_RUNS);

        Map<DataCategory, StorageType> recommended = recommend(measured);
        List<CategoryFit> fit = new ArrayList<>();
        for (DataCategory category : DataCategory.values()) {
            fit.add(fit(category, props.policyFor(category), measured.getOrDefault(category, List.of()), recommended));
        }
        List<String> summary = summary(measured, recommended);

        List<LiveUsage> live = jdbc.query("""
                SELECT category, storage_type,
                       count(*) FILTER (WHERE status = 'STORED'),
                       coalesce(sum(size_bytes) FILTER (WHERE status = 'STORED'), 0),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (stored_at - created_at)) * 1000)
                           FILTER (WHERE status = 'STORED'),
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM (stored_at - created_at)) * 1000)
                           FILTER (WHERE status = 'STORED'),
                       count(*) FILTER (WHERE status = 'FAILED')
                FROM stored_file WHERE created_at >= ?
                GROUP BY category, storage_type ORDER BY category, storage_type""",
                (rs, i) -> new LiveUsage(DataCategory.valueOf(rs.getString(1)), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), number(rs, 5), number(rs, 6), rs.getLong(7)), since);
        return new StorageSection(measuredAt[0], runs[0], fit, live, summary);
    }

    private static List<StorageMeasurement> ranked(DataCategory category, List<StorageMeasurement> measurements) {
        return measurements.stream()
                .filter(m -> m.eligible() && score(category, m) != null)
                .sorted(Comparator.comparingDouble(m -> score(category, m)))
                .toList();
    }

    private Map<DataCategory, StorageType> recommend(Map<DataCategory, List<StorageMeasurement>> measured) {
        Map<DataCategory, Map<StorageType, Double>> penalties = penalties(measured);
        if (penalties.size() < DataCategory.values().length) {
            return Map.of();
        }
        Map<DataCategory, StorageType> best = new EnumMap<>(DataCategory.class);
        double[] bestCost = {Double.MAX_VALUE};
        search(DataCategory.values(), 0, penalties, new EnumMap<>(DataCategory.class), 0, best, bestCost);
        if (best.isEmpty()) {
            return best;
        }
        Map<DataCategory, StorageType> current = new EnumMap<>(DataCategory.class);
        double currentCost = 0;
        for (DataCategory category : DataCategory.values()) {
            StorageType storage = props.policyFor(category).storage();
            Double penalty = penalties.get(category).get(storage);
            if (penalty == null) {
                return best;
            }
            current.put(category, storage);
            currentCost += penalty;
        }
        return acceptable(current) && currentCost <= bestCost[0] * (1 + TOLERANCE) ? current : best;
    }

    private static boolean acceptable(Map<DataCategory, StorageType> assignment) {
        if (assignment.values().stream().distinct().count() != StorageType.values().length) {
            return false;
        }
        return STORM_PEAKS.stream().map(assignment::get).distinct().count() == STORM_PEAKS.size();
    }

    private static Map<DataCategory, Map<StorageType, Double>> penalties(Map<DataCategory, List<StorageMeasurement>> measured) {
        Map<DataCategory, Map<StorageType, Double>> penalties = new EnumMap<>(DataCategory.class);
        measured.forEach((category, measurements) -> {
            List<StorageMeasurement> ranked = ranked(category, measurements);
            if (ranked.isEmpty()) {
                return;
            }
            double fastest = score(category, ranked.getFirst());
            Map<StorageType, Double> options = new EnumMap<>(StorageType.class);
            ranked.forEach(m -> options.put(StorageType.valueOf(m.storage()), score(category, m) / fastest));
            penalties.put(category, options);
        });
        return penalties;
    }

    private static void search(DataCategory[] categories, int index, Map<DataCategory, Map<StorageType, Double>> penalties,
                               Map<DataCategory, StorageType> current, double cost,
                               Map<DataCategory, StorageType> best, double[] bestCost) {
        if (cost >= bestCost[0]) {
            return;
        }
        if (index == categories.length) {
            if (acceptable(current)) {
                bestCost[0] = cost;
                best.clear();
                best.putAll(current);
            }
            return;
        }
        DataCategory category = categories[index];
        for (Map.Entry<StorageType, Double> option : penalties.get(category).entrySet()) {
            current.put(category, option.getKey());
            search(categories, index + 1, penalties, current, cost + option.getValue(), best, bestCost);
            current.remove(category);
        }
    }

    private List<String> summary(Map<DataCategory, List<StorageMeasurement>> measured,
                                 Map<DataCategory, StorageType> recommended) {
        if (measured.isEmpty()) {
            return List.of("Nu există încă măsurători. Rulați măsurătoarea ca să comparați storage-urile.");
        }
        if (recommended.isEmpty()) {
            return List.of("Nu există nicio combinație care să folosească toate cele trei storage-uri cu storage-urile "
                    + "care au trecut măsurătoarea. Verificați erorile din tabelele de mai jos.");
        }
        Map<DataCategory, Map<StorageType, Double>> penalties = penalties(measured);
        List<String> out = new ArrayList<>();
        out.add("Configurația recomandată: " + recommended.entrySet().stream()
                .map(e -> LABELS.get(e.getKey()) + " → " + e.getValue())
                .collect(Collectors.joining(", ")) + ". Toate cele trei storage-uri rămân folosite, cum cere tema. "
                + "Înregistrările de avarie și video-urile nu împart același storage, pentru că ambele au vârf în același "
                + "moment, după furtuni, iar măsurătoarea (fișier cu fișier) nu poate surprinde concurența dintre ele. "
                + "Dintre combinațiile care respectă aceste reguli, aceasta e cea mai rapidă sau la mai puțin de 15% de ea; "
                + "la egalitate se păstrează politica actuală, ca să nu se mute date din cauza variației măsurătorii.");

        List<String> sacrificed = new ArrayList<>();
        recommended.forEach((category, storage) -> {
            double penalty = penalties.get(category).get(storage);
            if (penalty > 1 + TOLERANCE) {
                sacrificed.add(LABELS.get(category) + " pe " + storage + " (de " + decimal(penalty)
                        + " ori mai lent decât cel mai rapid storage)");
            }
        });
        if (!sacrificed.isEmpty()) {
            out.add("Compromisul acceptat pentru aceste reguli: " + String.join("; ", sacrificed) + ".");
        }

        List<String> changes = new ArrayList<>();
        boolean currentMeasured = true;
        double currentCost = 0;
        double recommendedCost = 0;
        for (DataCategory category : DataCategory.values()) {
            StorageType current = props.policyFor(category).storage();
            Double penalty = penalties.get(category).get(current);
            recommendedCost += penalties.get(category).get(recommended.get(category));
            if (penalty == null) {
                currentMeasured = false;
            } else {
                currentCost += penalty;
            }
            if (current != recommended.get(category)) {
                changes.add(LABELS.get(category) + ": " + current + " → " + recommended.get(category));
            }
        }
        if (changes.isEmpty()) {
            out.add("Politica actuală din filegrid-policies.yml coincide cu configurația recomandată.");
        } else if (currentMeasured && currentCost <= recommendedCost * (1 + TOLERANCE)) {
            out.add("Politica actuală diferă (" + String.join("; ", changes) + "), dar e la mai puțin de 15% de "
                    + "configurația recomandată, adică în marja de variație a măsurătorii, deci poate rămâne.");
        } else if (currentMeasured) {
            out.add("Politica actuală e în medie cu " + percent(currentCost / recommendedCost - 1)
                    + " mai lentă decât cea recomandată. De schimbat în filegrid-policies.yml: " + String.join("; ", changes) + ".");
        } else {
            out.add("Politica actuală folosește un storage care nu a trecut măsurătoarea. De schimbat în "
                    + "filegrid-policies.yml: " + String.join("; ", changes) + ".");
        }
        return out;
    }

    private CategoryFit fit(DataCategory category, CategoryPolicy policy, List<StorageMeasurement> measurements,
                            Map<DataCategory, StorageType> assignment) {
        StorageType recommendedType = assignment.get(category);
        String current = policy.storage().name();
        if (measurements.isEmpty()) {
            return new CategoryFit(category, current, null, null, CRITERIA.get(category),
                    "Nu există încă măsurători comparative pentru această categorie.", measurements);
        }
        List<StorageMeasurement> ranked = ranked(category, measurements);
        if (ranked.isEmpty()) {
            return new CategoryFit(category, current, null, null, CRITERIA.get(category),
                    "Niciun storage nu a trecut măsurătoarea fără erori.", measurements);
        }
        StorageMeasurement fastest = ranked.getFirst();
        StringBuilder verdict = new StringBuilder("Cel mai rapid: ").append(fastest.storage())
                .append(" (").append(metric(category, fastest)).append(")");
        if (ranked.size() > 1) {
            verdict.append(", față de ").append(ranked.stream().skip(1)
                    .map(m -> m.storage() + " (" + metric(category, m) + ")")
                    .collect(Collectors.joining(" și ")));
        }
        verdict.append(". ").append(TRAITS.get(StorageType.valueOf(fastest.storage()))).append(' ');

        Optional<StorageMeasurement> recommended = recommendedType == null ? Optional.empty()
                : ranked.stream().filter(m -> m.storage().equals(recommendedType.name())).findFirst();
        StorageMeasurement target = recommended.orElse(fastest);
        double slower = score(category, target) / score(category, fastest);
        if (!target.storage().equals(fastest.storage()) && slower <= 1 + TOLERANCE) {
            verdict.append("Recomandat: ").append(target.storage()).append(", la mai puțin de 15% de cel mai rapid, ")
                    .append("adică în marja de variație a măsurătorii. ");
        } else if (!target.storage().equals(fastest.storage())) {
            Optional<DataCategory> partner = STORM_PEAKS.contains(category)
                    ? STORM_PEAKS.stream().filter(other -> other != category
                    && fastest.storage().equals(String.valueOf(assignment.get(other)))).findFirst()
                    : Optional.empty();
            verdict.append("Recomandat totuși: ").append(target.storage()).append(", de ").append(decimal(slower))
                    .append(" ori mai lent, ")
                    .append(partner.map(other -> "ca să nu împartă " + fastest.storage() + " cu „" + LABELS.get(other)
                            + "”, care are vârf în același moment (după furtuni). ")
                            .orElse("ca toate cele trei storage-uri să rămână folosite. "));
        } else {
            verdict.append("Recomandat: ").append(target.storage()).append(". ");
        }

        Optional<StorageMeasurement> currentMeasured = ranked.stream().filter(m -> m.storage().equals(current)).findFirst();
        if (target.storage().equals(current)) {
            verdict.append("Politica actuală (").append(current).append(") corespunde recomandării.");
        } else if (currentMeasured.isEmpty()) {
            verdict.append("Politica actuală (").append(current).append(") nu a trecut măsurătoarea.");
        } else {
            double ratio = score(category, currentMeasured.get()) / score(category, target);
            if (ratio < 1) {
                verdict.append("Politica actuală (").append(current).append(") e mai rapidă pentru această categorie, ")
                        .append("dar ar lăsa un storage nefolosit dacă toate categoriile ar alege doar după viteză.");
            } else if (ratio < 1 + TOLERANCE) {
                verdict.append("Politica actuală (").append(current)
                        .append(") e la mai puțin de 15% de recomandare, deci poate rămâne.");
            } else {
                verdict.append("Politica actuală folosește ").append(current).append(", de ").append(decimal(ratio))
                        .append(" ori mai lent decât ").append(target.storage()).append(" pe acest criteriu.");
            }
        }
        return new CategoryFit(category, current, fastest.storage(), target.storage(), CRITERIA.get(category),
                verdict.toString(), measurements);
    }

    private static Double score(DataCategory category, StorageMeasurement m) {
        return switch (category) {
            case METER_READING -> m.writeP50Ms();
            case DRONE_VIDEO -> m.writeMbPerS() == null || m.writeMbPerS() <= 0 ? null : 1 / m.writeMbPerS();
            default -> m.writeP50Ms() == null || m.readP50Ms() == null ? null : m.writeP50Ms() + m.readP50Ms();
        };
    }

    private static String metric(DataCategory category, StorageMeasurement m) {
        return switch (category) {
            case METER_READING -> "scriere " + millis(m.writeP50Ms());
            case DRONE_VIDEO -> decimal(m.writeMbPerS()) + " MB/s";
            default -> "scriere + citire " + millis(m.writeP50Ms() + m.readP50Ms());
        };
    }

    private LoadSection load(Timestamp since) {
        Map<DataCategory, LoadPhase[]> phases = new EnumMap<>(DataCategory.class);
        jdbc.query(LOAD_CTE + """
                SELECT f.category, c.peak, count(DISTINCT f.minute),
                       count(*) FILTER (WHERE f.status = 'STORED'),
                       count(*) FILTER (WHERE f.status = 'FAILED'),
                       coalesce(sum(f.size_bytes) FILTER (WHERE f.status = 'STORED'), 0),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY f.ms) FILTER (WHERE f.status = 'STORED'),
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY f.ms) FILTER (WHERE f.status = 'STORED')
                FROM f JOIN classified c ON c.category = f.category AND c.minute = f.minute
                GROUP BY f.category, c.peak""", rs -> {
            long minutes = rs.getLong(3);
            long stored = rs.getLong(4);
            LoadPhase phase = new LoadPhase(minutes, stored, rs.getLong(5), rs.getLong(6),
                    minutes == 0 ? 0 : (double) stored / minutes, number(rs, 7), number(rs, 8));
            phases.computeIfAbsent(DataCategory.valueOf(rs.getString(1)), c -> new LoadPhase[2])[rs.getBoolean(2) ? 1 : 0] = phase;
        }, since);

        List<CategoryLoad> categories = new ArrayList<>();
        List<String> conclusions = new ArrayList<>();
        for (DataCategory category : DataCategory.values()) {
            LoadPhase[] pair = phases.getOrDefault(category, new LoadPhase[2]);
            categories.add(new CategoryLoad(category, pair[0], pair[1]));
            if (pair[1] != null) {
                conclusions.add(peakConclusion(category, pair[0], pair[1]));
            }
        }

        jdbc.query(LOAD_CTE + """
                SELECT x.category, f.category,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY f.ms) FILTER (WHERE pk.minute IS NOT NULL),
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY f.ms) FILTER (WHERE pk.minute IS NULL),
                       count(*) FILTER (WHERE pk.minute IS NOT NULL)
                FROM (SELECT DISTINCT category FROM peak_minutes) x
                JOIN f ON f.category <> x.category AND f.status = 'STORED'
                LEFT JOIN peak_minutes pk ON pk.category = x.category AND pk.minute = f.minute
                GROUP BY x.category, f.category ORDER BY x.category, f.category""", rs -> {
            Double during = number(rs, 3);
            Double outside = number(rs, 4);
            if (rs.getLong(5) < 5 || during == null || outside == null || outside <= 0) {
                return;
            }
            double ratio = during / outside;
            conclusions.add("În timpul vârfului de „" + LABELS.get(DataCategory.valueOf(rs.getString(1))) + "”, categoria „"
                    + LABELS.get(DataCategory.valueOf(rs.getString(2))) + "” a avut durata p95 de " + millis(during)
                    + ", față de " + millis(outside) + " în rest: "
                    + (ratio <= 1.5 ? "nu a fost afectată." : "a fost încetinită de " + decimal(ratio) + " ori."));
        }, since);

        if (conclusions.isEmpty()) {
            conclusions.add("Niciun vârf în intervalul ales. Un vârf înseamnă un minut cu cel puțin de 3 ori volumul "
                    + "median al categoriei. Alegeți un interval mai lung sau porniți generatorul de furtună.");
        }
        return new LoadSection(categories, conclusions);
    }

    private static String peakConclusion(DataCategory category, LoadPhase normal, LoadPhase peak) {
        StringBuilder text = new StringBuilder("„").append(LABELS.get(category)).append("”: vârf ");
        if (normal != null && normal.filesPerMinute() > 0) {
            text.append("de ").append(decimal(peak.filesPerMinute() / normal.filesPerMinute())).append(" ori peste normal (")
                    .append(decimal(normal.filesPerMinute())).append(" → ").append(decimal(peak.filesPerMinute()))
                    .append(" fișiere/min");
        } else {
            text.append("(").append(decimal(peak.filesPerMinute())).append(" fișiere/min");
        }
        text.append(", timp de ").append(peak.minutes()).append(" min). ");
        long attempts = peak.stored() + peak.failed();
        double failedShare = attempts == 0 ? 0 : (double) peak.failed() / attempts;
        Double ratio = normal != null && normal.p95Ms() != null && peak.p95Ms() != null && normal.p95Ms() > 0
                ? peak.p95Ms() / normal.p95Ms() : null;
        if (ratio != null) {
            text.append("Durata p95: ").append(millis(normal.p95Ms())).append(" → ").append(millis(peak.p95Ms())).append(". ");
        }
        if (failedShare > 0.01) {
            text.append(percent(failedShare)).append(" dintre încărcări au eșuat la vârf: capacitatea categoriei trebuie "
                    + "mărită (instanțe de backend sau max-concurrent-uploads).");
        } else if (ratio != null && ratio > 2) {
            text.append("Nu s-a pierdut nimic, dar latența a crescut de ").append(decimal(ratio))
                    .append(" ori: categoria e aproape de limită.");
        } else {
            text.append("Vârful a fost absorbit fără eșecuri și fără degradare vizibilă.");
        }
        return text.toString();
    }

    private NetworkSection network(Timestamp since) {
        Map<String, long[]> uploads = new TreeMap<>();
        jdbc.query("""
                SELECT coalesce(region, '?'), category, count(*) FROM stored_file
                WHERE status = 'STORED' AND created_at >= ? AND category IN ('INCIDENT_RECORDING', 'DRONE_VIDEO')
                GROUP BY 1, 2""", rs -> {
            uploads.computeIfAbsent(rs.getString(1), r -> new long[2])["INCIDENT_RECORDING".equals(rs.getString(2)) ? 0 : 1] = rs.getLong(3);
        }, since);

        Map<String, RegionStat> regions = new LinkedHashMap<>();
        long[] totals = new long[3];
        jdbc.query("""
                SELECT coalesce(region, '?'), count(*), count(DISTINCT meter_id), avg(avg_kw),
                       count(*) FILTER (WHERE outage_samples > 0), count(*) FILTER (WHERE sag_samples > 0),
                       count(*) FILTER (WHERE spike_samples > 0),
                       count(*) FILTER (WHERE outage_samples > 0 OR sag_samples > 0 OR spike_samples > 0)
                FROM meter_reading_stat WHERE parsed AND reading_at >= ? GROUP BY 1""", rs -> {
            String code = rs.getString(1);
            long readings = rs.getLong(2);
            long anomalous = rs.getLong(8);
            long[] manual = uploads.getOrDefault(code, new long[2]);
            regions.put(code, new RegionStat(code, regionLabel(code), readings, rs.getLong(3), number(rs, 4),
                    rs.getLong(5), rs.getLong(6), rs.getLong(7), readings == 0 ? 0 : anomalous * 1000.0 / readings,
                    manual[0], manual[1]));
            totals[0] += readings;
            totals[1] += anomalous;
            totals[2] += rs.getLong(3);
        }, since);
        uploads.forEach((code, manual) -> regions.computeIfAbsent(code, c -> new RegionStat(c, regionLabel(c),
                0, 0, null, 0, 0, 0, 0, manual[0], manual[1])));
        List<RegionStat> regionList = regions.values().stream()
                .sorted(Comparator.comparingDouble(RegionStat::anomaliesPer1000).reversed()
                        .thenComparing(Comparator.comparingLong(RegionStat::incidents).reversed()))
                .toList();

        List<ConsumerStat> consumers = jdbc.query("""
                SELECT coalesce(consumer_type, '?'), count(*), count(DISTINCT meter_id), avg(avg_kw), max(max_kw)
                FROM meter_reading_stat WHERE parsed AND reading_at >= ? GROUP BY 1 ORDER BY 4 DESC NULLS LAST""",
                (rs, i) -> new ConsumerStat(rs.getString(1), rs.getLong(2), rs.getLong(3), number(rs, 4), number(rs, 5)),
                since);

        List<MeterIssue> problemMeters = jdbc.query("""
                SELECT meter_id, max(region), max(consumer_type), count(*),
                       count(*) FILTER (WHERE outage_samples > 0), count(*) FILTER (WHERE sag_samples > 0),
                       count(*) FILTER (WHERE spike_samples > 0),
                       count(*) FILTER (WHERE outage_samples > 0 OR sag_samples > 0 OR spike_samples > 0)
                FROM meter_reading_stat WHERE parsed AND reading_at >= ? AND meter_id IS NOT NULL
                GROUP BY meter_id
                HAVING count(*) FILTER (WHERE outage_samples > 0 OR sag_samples > 0 OR spike_samples > 0)
                       >= greatest(3, count(*) * 0.05)
                ORDER BY 8::float / count(*) DESC
                LIMIT 10""",
                (rs, i) -> new MeterIssue(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)), since);

        List<SilentMeter> silent = jdbc.query("""
                SELECT source_id, max(region), max(created_at) FROM stored_file
                WHERE category = 'METER_READING' AND status = 'STORED' AND created_at >= ? AND source_id IS NOT NULL
                GROUP BY source_id
                HAVING count(*) >= 3 AND now() - max(created_at)
                       > greatest(interval '2 minutes', 3 * (max(created_at) - min(created_at)) / (count(*) - 1))
                ORDER BY 3""",
                (rs, i) -> new SilentMeter(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), since);

        List<String> conclusions = networkConclusions(regionList, consumers, problemMeters, silent.size(), totals);
        return new NetworkSection(totals[0], regionList, consumers, problemMeters, silent.size(),
                silent.stream().limit(10).toList(), conclusions);
    }

    private static List<String> networkConclusions(List<RegionStat> regions, List<ConsumerStat> consumers,
                                                   List<MeterIssue> problemMeters, int silent, long[] totals) {
        List<String> out = new ArrayList<>();
        double average = totals[0] == 0 ? 0 : totals[1] * 1000.0 / totals[0];
        Optional<RegionStat> worst = regions.stream().filter(r -> r.readings() >= 50)
                .max(Comparator.comparingDouble(RegionStat::anomaliesPer1000));
        if (worst.isPresent() && average > 0) {
            RegionStat r = worst.get();
            if (r.anomaliesPer1000() >= average * 1.5) {
                String kind = r.outages() >= r.sags() && r.outages() >= r.spikes() ? "întreruperile de alimentare"
                        : r.sags() >= r.spikes() ? "tensiunea sub limita de 207 V" : "vârfurile bruște de consum";
                out.add(r.name() + " are " + decimal(r.anomaliesPer1000()) + " citiri cu anomalii la 1 000, de "
                        + decimal(r.anomaliesPer1000() / average) + " ori media rețelei (" + decimal(average)
                        + "). Predomină " + kind + ": zona merită verificată în teren înaintea celorlalte.");
            } else {
                out.add("Anomaliile din citiri sunt distribuite uniform între zone (media: " + decimal(average)
                        + " la 1 000 de citiri); nicio zonă nu iese în evidență.");
            }
        }
        if (!problemMeters.isEmpty()) {
            MeterIssue m = problemMeters.getFirst();
            out.add(problemMeters.size() + (problemMeters.size() == 10 ? " sau mai multe" : "")
                    + " contoare au anomalii în cel puțin 5% din citiri. Cel mai afectat: " + m.meterId() + " ("
                    + regionLabel(m.region()) + "), cu " + m.affected() + " din " + m.readings()
                    + " citiri afectate. Când anomaliile se repetă la același contor, "
                    + "cauza e locală (branșament sau contor), nu rețeaua.");
        }
        if (silent > 0) {
            out.add(totals[2] > 0 && silent >= totals[2] * 0.8
                    ? "Aproape toate contoarele (" + silent + ") au încetat să transmită: cel mai probabil e o problemă "
                    + "de comunicație sau fluxul e oprit, nu o defecțiune a contoarelor."
                    : silent + " contoare nu au mai transmis de cel puțin trei intervale de citire. Citirile lor lipsesc "
                    + "de la facturare până revin; verificați comunicația cu ele.");
        }
        long incidents = regions.stream().mapToLong(RegionStat::incidents).sum();
        if (incidents > 0) {
            RegionStat top = regions.stream().max(Comparator.comparingLong(RegionStat::incidents)).orElseThrow();
            StringBuilder text = new StringBuilder("Cele mai multe avarii: ").append(top.name()).append(" (")
                    .append(top.incidents()).append(", ").append(percent((double) top.incidents() / incidents))
                    .append(" din total).");
            if (average > 0 && top.anomaliesPer1000() > average * 1.2) {
                text.append(" Aceeași zonă are și rata de anomalii din citiri peste medie, deci cele două surse se confirmă.");
            }
            out.add(text.toString());
            String uninspected = regions.stream().filter(r -> r.incidents() > 0 && r.droneVideos() == 0)
                    .map(RegionStat::name).collect(Collectors.joining(", "));
            if (!uninspected.isEmpty()) {
                out.add("Zone cu avarii, dar fără nicio inspecție cu drona în interval: " + uninspected + ".");
            }
        }
        Optional<ConsumerStat> industrial = consumers.stream().filter(c -> c.type().equals("INDUSTRIAL")).findFirst();
        Optional<ConsumerStat> household = consumers.stream().filter(c -> c.type().equals("CASNIC")).findFirst();
        long meters = consumers.stream().mapToLong(ConsumerStat::meters).sum();
        if (industrial.isPresent() && household.isPresent() && meters > 0
                && industrial.get().avgKw() != null && household.get().avgKw() != null) {
            out.add("Consumatorii industriali sunt " + percent((double) industrial.get().meters() / meters)
                    + " din contoare, dar au o sarcină medie de " + decimal(industrial.get().avgKw()) + " kW, față de "
                    + decimal(household.get().avgKw()) + " kW la cei casnici: o citire pierdută de la ei cântărește mult mai mult la facturare.");
        }
        if (out.isEmpty()) {
            out.add("Nu există încă destule date în intervalul ales.");
        }
        return out;
    }

    private static String regionLabel(String code) {
        return code == null || code.equals("?") ? "zonă nespecificată" : REGIONS.getOrDefault(code, code);
    }

    private static Double number(ResultSet rs, int column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String decimal(double value) {
        return String.format(RO, Math.abs(value) >= 100 ? "%.0f" : "%.1f", value);
    }

    private static String millis(double value) {
        return value >= 1000 ? decimal(value / 1000) + " s" : decimal(value) + " ms";
    }

    private static String percent(double share) {
        return decimal(share * 100) + "%";
    }
}
