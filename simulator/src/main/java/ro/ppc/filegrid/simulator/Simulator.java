package ro.ppc.filegrid.simulator;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

public final class Simulator {

    private static final long TICK_MILLIS = 100;
    private static final long RELOAD_MILLIS = 3_000;
    private static final long REPORT_MILLIS = 10_000;

    private Simulator() {
    }

    public static void main(String[] args) throws InterruptedException {
        String target = env("SIM_TARGET_URL", "https://gateway");
        String caCertificate = env("SIM_CA_CERT", "");
        String masterKey = env("METER_MASTER_KEY", "");
        if (masterKey.isBlank()) {
            Log.warn("METER_MASTER_KEY lipseste; ruleaza start.ps1 sau start.sh ca sa fie generata in .env");
            System.exit(2);
        }

        Settings.Source settings = new Settings.Source(Path.of(env("SIM_CONFIG", "/config/simulator.properties")));
        settings.refresh();
        Meters meters = new Meters(masterKey);
        Uplink uplink = new Uplink(target, caCertificate.isBlank() ? null : Path.of(caCertificate), meters,
                Integer.parseInt(env("SIM_MAX_CONCURRENCY", "64")), Long.parseLong(env("SIM_MAX_PENDING", "1000000")));

        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            main.interrupt();
            long deadline = System.currentTimeMillis() + 8_000;
            while (uplink.pending.get() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
            uplink.stop();
            Log.info("Oprire. " + totals(uplink));
        }));

        Log.info("Simulator pornit -> " + target + " | " + settings.current().describe());
        Settings active = settings.current();
        long[] lastSlot = slots(active, System.currentTimeMillis());
        long nextReload = 0;
        long nextReport = System.currentTimeMillis() + REPORT_MILLIS;
        long confirmedAtLastReport = 0;

        try {
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= nextReload) {
                    nextReload = now + RELOAD_MILLIS;
                    uplink.refreshTrust();
                    if (settings.refresh()) {
                        active = settings.current();
                        lastSlot = slots(active, now);
                        Log.info("Configuratie noua: " + active.describe());
                    }
                }

                long interval = active.effectiveIntervalMillis();
                for (int i = 0; i < active.meters(); i++) {
                    long offset = interval * i / active.meters();
                    long slot = Math.floorDiv(now - offset, interval);
                    if (slot > lastSlot[i]) {
                        lastSlot[i] = slot;
                        uplink.submit(meters.meter(i), slot * interval + offset);
                    }
                }

                if (now >= nextReport) {
                    long delivered = uplink.confirmed.sum() + uplink.duplicates.sum();
                    Log.info(String.format(Locale.ROOT, "%s | %s | %.1f confirmate/s",
                            active.describe(), totals(uplink), (delivered - confirmedAtLastReport) * 1000.0 / REPORT_MILLIS));
                    confirmedAtLastReport = delivered;
                    nextReport = now + REPORT_MILLIS;
                }
                Thread.sleep(TICK_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long[] slots(Settings settings, long now) {
        long[] slots = new long[settings.meters()];
        long interval = settings.effectiveIntervalMillis();
        Arrays.setAll(slots, i -> Math.floorDiv(now - interval * i / settings.meters(), interval));
        return slots;
    }

    private static String totals(Uplink uplink) {
        return "generate=" + uplink.generated.sum() + " confirmate=" + uplink.confirmed.sum()
                + " duplicate=" + uplink.duplicates.sum() + " reincercari=" + uplink.retries.sum()
                + " in_asteptare=" + uplink.pending.get() + " respinse=" + uplink.rejected.sum()
                + " pierdute=" + uplink.lost.sum();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
