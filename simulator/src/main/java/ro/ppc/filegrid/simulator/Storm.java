package ro.ppc.filegrid.simulator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class Storm {

    private static final String[] CAUSES = {
            "Declansare protectie linie 110 kV", "Conductor rupt de vant", "Scurtcircuit bara 20 kV",
            "Descarcare atmosferica", "Copac cazut pe linie", "Defect transformator"};
    private static final String[] DOCUMENTS = {"cerere_racordare", "act_identitate", "aviz_tehnic", "certificat_urbanism"};
    private static final String[] ALL_REGIONS = {"B", "IF", "GR", "CT", "TL", "CL", "IL", "TM", "AR", "CS", "HD"};

    private record Config(double normalPerMinute, int multiplier, int normalSeconds, int stormSeconds,
                          int minSizeKb, int maxSizeKb, int maxConcurrency, String[] regions, int[] weights,
                          double documentsPerMinute, int documentMinKb, int documentMaxKb) {
    }

    private static final class Phase {
        final String name;
        final LongAdder sent = new LongAdder();
        final LongAdder stored = new LongAdder();
        final LongAdder duplicates = new LongAdder();
        final LongAdder throttled = new LongAdder();
        final LongAdder unavailable = new LongAdder();
        final LongAdder retries = new LongAdder();
        final LongAdder lost = new LongAdder();
        final LongAdder bytes = new LongAdder();
        final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        Phase(String name) {
            this.name = name;
        }

        String summary(int seconds) {
            List<Long> sorted = sorted();
            return String.format(Locale.ROOT,
                    "%-20s trimise=%d stocate=%d (%.1f/min, %.1f MB) p50=%s p95=%s | 429=%d 503=%d reincercari=%d pierdute=%d",
                    name, sent.sum(), stored.sum() + duplicates.sum(), (stored.sum() + duplicates.sum()) * 60.0 / seconds,
                    bytes.sum() / 1048576.0, label(percentile(sorted, 0.5)), label(percentile(sorted, 0.95)),
                    throttled.sum(), unavailable.sum(), retries.sum(), lost.sum());
        }

        List<Long> sorted() {
            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            return sorted;
        }

        static Long percentile(List<Long> sorted, double q) {
            return sorted.isEmpty() ? null : sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(q * sorted.size()) - 1));
        }

        static String label(Long millis) {
            return millis == null ? "-" : millis + "ms";
        }
    }

    private record Item(String name, String region, String sourceId, String description, int size, long seed) {
    }

    private final URI base;
    private final HttpClient login;
    private final HttpClient uploads;
    private final Config config;
    private final Semaphore permits;
    private final AtomicLong pending = new AtomicLong();
    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private final String csrf = UUID.randomUUID().toString();

    private Storm(String target, Path caCertificate, Config config) throws IOException, GeneralSecurityException {
        this.base = URI.create(target.replaceAll("/+$", ""));
        this.config = config;
        this.permits = new Semaphore(config.maxConcurrency());
        SSLContext tls = trust(caCertificate);
        this.login = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build();
        this.uploads = HttpClient.newBuilder()
                .sslContext(tls)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public static void main(String[] args) throws Exception {
        String user = env("STORM_USER", "dispecer");
        String password = env("STORM_PASSWORD", "");
        if (password.isBlank()) {
            Log.warn("STORM_PASSWORD lipseste; parola contului " + user + " este in .env");
            System.exit(2);
        }
        Config config = load(Path.of(env("STORM_CONFIG", "/config/storm.properties")));
        String ca = env("SIM_CA_CERT", "");
        Storm storm = new Storm(env("SIM_TARGET_URL", "https://gateway"), ca.isBlank() ? null : Path.of(ca), config);
        String incidentSession = storm.login(user, password);

        String documentSession = null;
        if (config.documentsPerMinute() > 0) {
            String docUser = env("STORM_DOCS_USER", "racordari");
            String docPassword = env("STORM_DOCS_PASSWORD", "");
            if (docPassword.isBlank()) {
                Log.warn("STORM_DOCS_PASSWORD lipseste; documentele de racordare nu vor fi trimise in paralel");
            } else {
                documentSession = storm.login(docUser, docPassword);
            }
        }

        double stormPerMinute = config.normalPerMinute() * config.multiplier();
        Log.info(String.format(Locale.ROOT, "Furtuna %s: %ds normal (%.0f avarii/min), apoi %ds furtuna (%.0f avarii/min), fisiere de %d-%d KB",
                storm.runId, config.normalSeconds(), config.normalPerMinute(), config.stormSeconds(), stormPerMinute,
                config.minSizeKb(), config.maxSizeKb()));
        if (documentSession != null) {
            Log.info(String.format(Locale.ROOT, "In paralel: %.0f documente de racordare/min, constant, ca martor pentru izolarea categoriilor",
                    config.documentsPerMinute()));
        }

        Phase normal = new Phase("AVARII normal");
        Phase peak = new Phase("AVARII furtuna");
        Phase docsNormal = new Phase("DOCUMENTE normal");
        Phase docsPeak = new Phase("DOCUMENTE furtuna");

        Thread documents = null;
        if (documentSession != null) {
            String session = documentSession;
            documents = Thread.startVirtualThread(() -> {
                try {
                    storm.run(docsNormal, "CONNECTION_DOCUMENT", session, config.documentsPerMinute(), config.normalSeconds(),
                            storm::document);
                    storm.run(docsPeak, "CONNECTION_DOCUMENT", session, config.documentsPerMinute(), config.stormSeconds(),
                            storm::document);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        storm.run(normal, "INCIDENT_RECORDING", incidentSession, config.normalPerMinute(), config.normalSeconds(), storm::incident);
        storm.run(peak, "INCIDENT_RECORDING", incidentSession, stormPerMinute, config.stormSeconds(), storm::incident);
        if (documents != null) {
            documents.join();
        }

        long deadline = System.currentTimeMillis() + 180_000;
        while (storm.pending.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        Log.info("Rezultat:");
        Log.info(normal.summary(config.normalSeconds()));
        Log.info(peak.summary(config.stormSeconds()));
        long lost = normal.lost.sum() + peak.lost.sum() + storm.pending.get();
        if (documentSession != null) {
            Log.info(docsNormal.summary(config.normalSeconds()));
            Log.info(docsPeak.summary(config.stormSeconds()));
            lost += docsNormal.lost.sum() + docsPeak.lost.sum();
            Long before = Phase.percentile(docsNormal.sorted(), 0.95);
            Long during = Phase.percentile(docsPeak.sorted(), 0.95);
            if (before != null && during != null && before > 0) {
                double ratio = (double) during / before;
                Log.info(String.format(Locale.ROOT, "Documentele de racordare: p95 %dms inainte, %dms in timpul furtunii (x%.1f) - %s",
                        before, during, ratio, ratio <= 1.5 ? "neafectate de furtuna" : "INCETINITE de furtuna"));
            }
        }
        Log.info(lost == 0
                ? "Toate fisierele au fost confirmate de FileGrid. Comparati categoriile in pagina Statistici."
                : lost + " fisiere NU au fost confirmate.");
        System.exit(lost == 0 ? 0 : 1);
    }

    @FunctionalInterface
    private interface Generator {
        Item create(SplittableRandom random);
    }

    private Item incident(SplittableRandom random) {
        String region = pickRegion(random);
        String station = String.format(Locale.ROOT, "ST-%s-%03d", region, 1 + random.nextInt(40));
        String name = "avarie_" + station + "_" + Instant.now().toString().replace(':', '-') + ".dat";
        int size = (config.minSizeKb() + random.nextInt(config.maxSizeKb() - config.minSizeKb() + 1)) * 1024;
        return new Item(name, region, station, CAUSES[random.nextInt(CAUSES.length)] + ", statia " + station, size,
                random.nextLong());
    }

    private Item document(SplittableRandom random) {
        String region = ALL_REGIONS[random.nextInt(ALL_REGIONS.length)];
        String name = DOCUMENTS[random.nextInt(DOCUMENTS.length)] + "_" + String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
                + ".pdf";
        int size = (config.documentMinKb() + random.nextInt(config.documentMaxKb() - config.documentMinKb() + 1)) * 1024;
        return new Item(name, region, null, "Document de test trimis de generatorul de furtuna", size, random.nextLong());
    }

    private void run(Phase phase, String category, String session, double perMinute, int seconds, Generator generator)
            throws InterruptedException {
        if (seconds <= 0 || perMinute <= 0) {
            return;
        }
        long started = System.currentTimeMillis();
        long end = started + seconds * 1000L;
        double intervalMillis = 60_000.0 / perMinute;
        long nextReport = started + 10_000;
        for (int i = 0; ; i++) {
            long due = started + Math.round(i * intervalMillis);
            if (due >= end) {
                break;
            }
            long wait = due - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            int sequence = i;
            pending.incrementAndGet();
            phase.sent.increment();
            Thread.startVirtualThread(() -> deliver(phase, category, session, sequence, generator));
            if (System.currentTimeMillis() >= nextReport) {
                nextReport += 10_000;
                Log.info(phase.summary((int) Math.max(1, (System.currentTimeMillis() - started) / 1000)));
            }
        }
        long remaining = end - System.currentTimeMillis();
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private void deliver(Phase phase, String category, String session, int sequence, Generator generator) {
        try {
            SplittableRandom random = new SplittableRandom(runId.hashCode() * 31L + phase.name.hashCode() + sequence);
            Item item = generator.create(random);
            StringBuilder query = new StringBuilder("/api/files?category=").append(category)
                    .append("&name=").append(encode(item.name()))
                    .append("&region=").append(item.region())
                    .append("&description=").append(encode(item.description()));
            if (item.sourceId() != null) {
                query.append("&sourceId=").append(encode(item.sourceId()));
            }
            URI uri = URI.create(base + query.toString());
            String key = "furtuna-" + runId + "-" + phase.name.replace(' ', '-') + "-" + sequence;

            for (int attempt = 0; attempt < 30; attempt++) {
                long started = System.nanoTime();
                int status;
                long retryAfter = 0;
                permits.acquire();
                try {
                    byte[] body = new byte[item.size()];
                    new SplittableRandom(item.seed()).nextBytes(body);
                    HttpResponse<Void> response = uploads.send(HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/octet-stream")
                            .header("Idempotency-Key", key)
                            .header("Cookie", session + "; XSRF-TOKEN=" + csrf)
                            .header("X-XSRF-TOKEN", csrf)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(), HttpResponse.BodyHandlers.discarding());
                    status = response.statusCode();
                    retryAfter = response.headers().firstValue("Retry-After").map(Storm::seconds).orElse(0L);
                } catch (IOException e) {
                    status = 0;
                } finally {
                    permits.release();
                }
                if (status == 201 || status == 200) {
                    (status == 201 ? phase.stored : phase.duplicates).increment();
                    phase.bytes.add(item.size());
                    phase.latencies.add((System.nanoTime() - started) / 1_000_000);
                    return;
                }
                if (status == 429) {
                    phase.throttled.increment();
                } else if (status == 503) {
                    phase.unavailable.increment();
                } else if (status != 0 && status != 409 && status != 502 && status != 504) {
                    Log.warn("Fisierul " + item.name() + " a fost respins definitiv: HTTP " + status);
                    phase.lost.increment();
                    return;
                }
                phase.retries.increment();
                Thread.sleep(Math.max(retryAfter * 1000, Math.min(15_000, 500L << Math.min(attempt, 5))));
            }
            phase.lost.increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pending.decrementAndGet();
        }
    }

    private String login(String user, String password) throws IOException, InterruptedException {
        CookieManager cookies = new CookieManager();
        HttpClient client = HttpClient.newBuilder()
                .sslContext(login.sslContext())
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        for (int attempt = 0; ; attempt++) {
            try {
                client.send(HttpRequest.newBuilder(base.resolve("/api/auth/session")).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                String loginToken = cookie(cookies, "XSRF-TOKEN");
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", loginToken)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + user + "\",\"password\":\""
                                + password.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    Log.info("Autentificat ca " + user);
                    return "FILEGRID_SESSION=" + cookie(cookies, "FILEGRID_SESSION");
                }
                if (response.statusCode() == 401) {
                    Log.warn("Autentificare esuata pentru " + user + ": verificati parola din .env");
                    System.exit(2);
                }
                throw new IOException("HTTP " + response.statusCode());
            } catch (IOException e) {
                if (attempt >= 10) {
                    throw e;
                }
                Log.warn("Nu ma pot autentifica inca (" + e.getMessage() + "); reincerc");
                Thread.sleep(3000);
            }
        }
    }

    private static String cookie(CookieManager cookies, String name) throws IOException {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name)).map(HttpCookie::getValue).findFirst()
                .orElseThrow(() -> new IOException("gateway-ul nu a trimis cookie-ul " + name));
    }

    private String pickRegion(SplittableRandom random) {
        int total = 0;
        for (int weight : config.weights()) {
            total += weight;
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < config.regions().length; i++) {
            roll -= config.weights()[i];
            if (roll < 0) {
                return config.regions()[i];
            }
        }
        return config.regions()[0];
    }

    private static Config load(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        String[] entries = p.getProperty("regions", "CT:40,TL:30,CL:15,IL:10,B:5").split(",");
        String[] regions = new String[entries.length];
        int[] weights = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            String[] pair = entries[i].trim().split(":");
            regions[i] = pair[0].trim().toUpperCase(Locale.ROOT);
            weights[i] = pair.length > 1 ? Math.max(1, Integer.parseInt(pair[1].trim())) : 1;
        }
        int minKb = Math.max(1, number(p, "min-size-kb", 1024));
        int docMinKb = Math.max(1, number(p, "document-min-size-kb", 100));
        return new Config(
                Math.max(0.1, Double.parseDouble(p.getProperty("normal-per-minute", "6").trim())),
                Math.max(1, number(p, "multiplier", 10)),
                Math.max(0, number(p, "normal-seconds", 60)),
                Math.max(1, number(p, "storm-seconds", 120)),
                minKb,
                Math.max(minKb, number(p, "max-size-kb", 10240)),
                Math.max(1, number(p, "max-concurrency", 16)),
                regions, weights,
                Math.max(0, Double.parseDouble(p.getProperty("documents-per-minute", "6").trim())),
                docMinKb,
                Math.max(docMinKb, number(p, "document-max-size-kb", 2048)));
    }

    private static int number(Properties p, String key, int fallback) {
        return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
    }

    private static SSLContext trust(Path caCertificate) throws IOException, GeneralSecurityException {
        if (caCertificate == null) {
            return SSLContext.getDefault();
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        try (InputStream in = Files.newInputStream(caCertificate)) {
            int i = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("gateway-" + i++, certificate);
            }
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static long seconds(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
