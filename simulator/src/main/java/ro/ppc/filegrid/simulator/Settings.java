package ro.ppc.filegrid.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Properties;

public record Settings(int meters, long intervalMillis, boolean peak, int peakMultiplier) {

    public static final Settings DEFAULT = new Settings(100, 30_000, false, 10);

    public long effectiveIntervalMillis() {
        return peak ? Math.max(200, intervalMillis / peakMultiplier) : intervalMillis;
    }

    public double readingsPerSecond() {
        return meters * 1000.0 / effectiveIntervalMillis();
    }

    public String describe() {
        return String.format(Locale.ROOT, "profil=%s contoare=%d interval=%.1fs (%.1f citiri/s)",
                peak ? "peak" : "normal", meters, effectiveIntervalMillis() / 1000.0, readingsPerSecond());
    }

    public static final class Source {

        private final Path file;
        private FileTime loadedVersion;
        private Settings current = DEFAULT;

        public Source(Path file) {
            this.file = file;
        }

        public Settings current() {
            return current;
        }

        public boolean refresh() {
            try {
                if (!Files.isRegularFile(file)) {
                    return false;
                }
                FileTime version = Files.getLastModifiedTime(file);
                if (version.equals(loadedVersion)) {
                    return false;
                }
                Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
                Settings loaded = parse(properties);
                loadedVersion = version;
                boolean changed = !loaded.equals(current);
                current = loaded;
                return changed;
            } catch (IOException | IllegalArgumentException e) {
                Log.warn("Configuratia " + file + " nu poate fi citita (" + e.getMessage() + "); pastrez valorile curente");
                return false;
            }
        }

        private static Settings parse(Properties p) {
            int meters = positive(p, "meters", DEFAULT.meters());
            double intervalSeconds = Double.parseDouble(p.getProperty("interval-seconds", "30").trim());
            if (intervalSeconds <= 0) {
                throw new IllegalArgumentException("interval-seconds trebuie sa fie pozitiv");
            }
            String profile = p.getProperty("profile", "normal").trim().toLowerCase(Locale.ROOT);
            if (!profile.equals("normal") && !profile.equals("peak")) {
                throw new IllegalArgumentException("profile trebuie sa fie normal sau peak");
            }
            int multiplier = positive(p, "peak-multiplier", DEFAULT.peakMultiplier());
            return new Settings(meters, Math.round(intervalSeconds * 1000), profile.equals("peak"), multiplier);
        }

        private static int positive(Properties p, String key, int fallback) {
            int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
            if (value <= 0) {
                throw new IllegalArgumentException(key + " trebuie sa fie pozitiv");
            }
            return value;
        }
    }
}
