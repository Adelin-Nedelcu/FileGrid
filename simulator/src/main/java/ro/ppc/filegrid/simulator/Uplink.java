package ro.ppc.filegrid.simulator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class Uplink {

    private enum Outcome { CONFIRMED, DUPLICATE, REJECTED, RETRY }

    private record Attempt(Outcome outcome, long retryAfterMillis, String detail) {
    }

    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final URI endpoint;
    private final Path caCertificate;
    private final Meters meters;
    private final Semaphore permits;
    private final long maxPending;
    private volatile HttpClient client;
    private volatile FileTime trustVersion;
    private volatile boolean running = true;
    private volatile long lastProblemLogged;

    final LongAdder generated = new LongAdder();
    final LongAdder confirmed = new LongAdder();
    final LongAdder duplicates = new LongAdder();
    final LongAdder retries = new LongAdder();
    final LongAdder rejected = new LongAdder();
    final LongAdder lost = new LongAdder();
    final AtomicLong pending = new AtomicLong();

    public Uplink(String targetUrl, Path caCertificate, Meters meters, int maxConcurrency, long maxPending) {
        this.endpoint = URI.create(targetUrl.replaceAll("/+$", "") + "/api/meter-readings");
        this.caCertificate = caCertificate;
        this.meters = meters;
        this.permits = new Semaphore(maxConcurrency);
        this.maxPending = maxPending;
    }

    public boolean refreshTrust() {
        try {
            if (caCertificate == null) {
                if (client == null) {
                    client = newClient(SSLContext.getDefault());
                }
                return true;
            }
            if (!Files.isRegularFile(caCertificate)) {
                return false;
            }
            FileTime version = Files.getLastModifiedTime(caCertificate);
            if (version.equals(trustVersion)) {
                return true;
            }
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null);
            try (InputStream in = Files.newInputStream(caCertificate)) {
                int i = 0;
                for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    store.setCertificateEntry("gateway-" + i++, certificate);
                }
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            client = newClient(context);
            trustVersion = version;
            Log.info("Certificatul gateway-ului a fost incarcat din " + caCertificate);
            return true;
        } catch (IOException | GeneralSecurityException e) {
            Log.warn("Nu pot incarca certificatul gateway-ului: " + e.getMessage());
            return false;
        }
    }

    private static HttpClient newClient(SSLContext context) {
        return HttpClient.newBuilder()
                .sslContext(context)
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public void submit(Meters.Meter meter, long readingMillis) {
        generated.increment();
        if (pending.get() >= maxPending) {
            lost.increment();
            problem("MEMORIA CONTOARELOR E PLINA (" + maxPending + " citiri in asteptare): citirea "
                    + meter.id() + " " + Instant.ofEpochMilli(readingMillis) + " s-a pierdut");
            return;
        }
        pending.incrementAndGet();
        Thread.startVirtualThread(() -> deliver(meter, readingMillis));
    }

    public void stop() {
        running = false;
    }

    private void deliver(Meters.Meter meter, long readingMillis) {
        int attempt = 0;
        try {
            while (running) {
                Attempt result;
                permits.acquire();
                try {
                    result = send(meter, readingMillis);
                } finally {
                    permits.release();
                }
                switch (result.outcome()) {
                    case CONFIRMED -> {
                        confirmed.increment();
                        return;
                    }
                    case DUPLICATE -> {
                        duplicates.increment();
                        return;
                    }
                    case REJECTED -> {
                        rejected.increment();
                        problem("Citirea " + meter.id() + " a fost respinsa definitiv: " + result.detail());
                        return;
                    }
                    case RETRY -> {
                        retries.increment();
                        problem("Citire neconfirmata (" + result.detail() + "); o pastrez si reincerc");
                        long backoff = Math.min(MAX_BACKOFF_MILLIS, 500L << Math.min(attempt, 10));
                        long delay = Math.max(backoff, result.retryAfterMillis())
                                + ThreadLocalRandom.current().nextLong(500);
                        attempt++;
                        Thread.sleep(delay);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pending.decrementAndGet();
        }
    }

    private Attempt send(Meters.Meter meter, long readingMillis) throws InterruptedException {
        HttpClient http = client;
        if (http == null) {
            return new Attempt(Outcome.RETRY, 2000, "certificatul gateway-ului nu e inca disponibil");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("X-Meter-Id", meter.id())
                .header("X-Meter-Key", meters.accessKey(meter))
                .header("X-Region", meter.region())
                .header("Idempotency-Key", Instant.ofEpochMilli(readingMillis).toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(Meters.payload(meter, readingMillis)))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return switch (status) {
                case 201 -> new Attempt(Outcome.CONFIRMED, 0, null);
                case 200 -> new Attempt(Outcome.DUPLICATE, 0, null);
                case 400, 413, 415, 422 -> new Attempt(Outcome.REJECTED, 0, "HTTP " + status);
                default -> new Attempt(Outcome.RETRY, retryAfterMillis(response), "HTTP " + status);
            };
        } catch (IOException e) {
            return new Attempt(Outcome.RETRY, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        try {
            return response.headers().firstValue("Retry-After").map(v -> Long.parseLong(v.trim()) * 1000).orElse(0L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void problem(String message) {
        long now = System.currentTimeMillis();
        if (now - lastProblemLogged > 10_000) {
            lastProblemLogged = now;
            Log.warn(message);
        }
    }
}
