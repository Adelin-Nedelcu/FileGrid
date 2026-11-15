package ro.ppc.filegrid.simulator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

public final class Meters {

    public enum Kind {
        CASNIC(1.1, new double[]{0.35, 0.3, 0.28, 0.27, 0.28, 0.35, 0.6, 0.9, 0.85, 0.6, 0.5, 0.5,
                0.55, 0.55, 0.5, 0.5, 0.6, 0.85, 1.0, 1.0, 0.95, 0.8, 0.6, 0.45}),
        COMERCIAL(6.5, new double[]{0.2, 0.2, 0.2, 0.2, 0.2, 0.25, 0.4, 0.7, 0.95, 1.0, 1.0, 1.0,
                0.95, 1.0, 1.0, 0.95, 0.9, 0.8, 0.6, 0.45, 0.35, 0.3, 0.25, 0.2}),
        INDUSTRIAL(48.0, new double[]{0.75, 0.75, 0.75, 0.75, 0.75, 0.8, 0.95, 1.0, 1.0, 1.0, 1.0, 1.0,
                0.95, 1.0, 1.0, 1.0, 1.0, 0.95, 0.9, 0.85, 0.8, 0.8, 0.75, 0.75});

        final double peakKw;
        final double[] hourly;

        Kind(double peakKw, double[] hourly) {
            this.peakKw = peakKw;
            this.hourly = hourly;
        }
    }

    public record Meter(int index, String id, String region, Kind kind, double scale, double risk) {
    }

    private static final String[] REGIONS = {"B", "IF", "GR", "CT", "TL", "CL", "IL", "TM", "AR", "CS", "HD"};
    private static final int[] REGION_WEIGHTS = {30, 8, 4, 12, 4, 5, 5, 14, 8, 5, 5};
    private static final double[] REGION_RISK = {1, 1, 1.5, 1, 3, 1.5, 1, 1, 1, 2.5, 2};
    private static final int FAULTY_METERS_PERCENT = 3;
    private static final double FAULTY_METER_RISK = 8;
    private static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
    private static final long SAMPLE_STEP_MILLIS = 15 * 60 * 1000L;

    private final SecretKeySpec masterKey;
    private final ConcurrentHashMap<Integer, Meter> meters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> keys = new ConcurrentHashMap<>();

    public Meters(String masterKeyBase64) {
        this.masterKey = new SecretKeySpec(Base64.getDecoder().decode(masterKeyBase64.trim()), "HmacSHA256");
    }

    public Meter meter(int index) {
        return meters.computeIfAbsent(index, Meters::create);
    }

    public String accessKey(Meter meter) {
        return keys.computeIfAbsent(meter.index(), i -> derive(meter.id()));
    }

    private static Meter create(int index) {
        SplittableRandom random = new SplittableRandom(0x5EEDL + index);
        int pick = random.nextInt(100);
        Kind kind = pick < 80 ? Kind.CASNIC : pick < 95 ? Kind.COMERCIAL : Kind.INDUSTRIAL;
        int roll = random.nextInt(100);
        int regionIndex = REGIONS.length - 1;
        for (int i = 0, sum = 0; i < REGIONS.length; i++) {
            sum += REGION_WEIGHTS[i];
            if (roll < sum) {
                regionIndex = i;
                break;
            }
        }
        double scale = 0.6 + random.nextDouble() * 0.9;
        double risk = REGION_RISK[regionIndex] * (random.nextInt(100) < FAULTY_METERS_PERCENT ? FAULTY_METER_RISK : 1);
        return new Meter(index, String.format(Locale.ROOT, "CNT-%06d", index + 1), REGIONS[regionIndex], kind, scale, risk);
    }

    private String derive(String meterId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(masterKey);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(meterId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] payload(Meter meter, long readingMillis) {
        SplittableRandom random = new SplittableRandom(meter.index() * 1_000_003L ^ readingMillis);
        int shape = random.nextInt(100);
        int samples = shape < 70 ? 24 + random.nextInt(73) : shape < 95 ? 96 + random.nextInt(105) : 200 + random.nextInt(201);
        int anomaly = random.nextInt(1000);
        double risk = meter.risk();
        boolean outage = anomaly < 5 * risk;
        boolean sag = anomaly >= 5 * risk && anomaly < 8 * risk;
        boolean spike = anomaly >= 8 * risk && anomaly < 10 * risk;
        int anomalyStart = random.nextInt(samples);
        int anomalyLength = 1 + random.nextInt(4);

        StringBuilder json = new StringBuilder(samples * 120 + 256);
        json.append("{\"meterId\":\"").append(meter.id())
                .append("\",\"region\":\"").append(meter.region())
                .append("\",\"consumerType\":\"").append(meter.kind())
                .append("\",\"readingAt\":\"").append(Instant.ofEpochMilli(readingMillis))
                .append("\",\"firmware\":\"2.4.").append(meter.index() % 7)
                .append("\",\"totalActiveEnergyKwh\":");
        number(json, readingMillis / 3_600_000.0 * meter.kind().peakKw * meter.scale() * 0.00045, 3);
        json.append(",\"samples\":[");

        for (int i = 0; i < samples; i++) {
            long at = readingMillis - (long) (samples - 1 - i) * SAMPLE_STEP_MILLIS;
            ZonedDateTime local = Instant.ofEpochMilli(at).atZone(ZONE);
            double weekend = local.getDayOfWeek().getValue() >= 6 && meter.kind() != Kind.CASNIC ? 0.55 : 1.0;
            double kw = meter.kind().peakKw * meter.scale() * meter.kind().hourly[local.getHour()] * weekend
                    * (0.85 + random.nextDouble() * 0.3);
            double voltage = 230 + (random.nextDouble() - 0.5) * 8;
            boolean inAnomaly = i >= anomalyStart && i < anomalyStart + anomalyLength;
            if (inAnomaly && outage) {
                kw = 0;
                voltage = 0;
            } else if (inAnomaly && sag) {
                voltage = 187 + random.nextDouble() * 10;
            } else if (inAnomaly && spike) {
                kw *= 6 + random.nextDouble() * 4;
            }
            double current = voltage == 0 ? 0 : kw * 1000 / (voltage * 0.95);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"t\":\"").append(Instant.ofEpochMilli(at)).append("\",\"activeKw\":");
            number(json, kw, 3);
            json.append(",\"reactiveKvar\":");
            number(json, kw * (0.18 + random.nextDouble() * 0.12), 3);
            json.append(",\"voltageV\":");
            number(json, voltage, 1);
            json.append(",\"currentA\":");
            number(json, current, 2);
            json.append(",\"frequencyHz\":");
            number(json, voltage == 0 ? 0 : 50 + (random.nextDouble() - 0.5) * 0.06, 2);
            json.append('}');
        }
        json.append("]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void number(StringBuilder out, double value, int decimals) {
        long factor = decimals == 1 ? 10 : decimals == 2 ? 100 : 1000;
        long scaled = Math.round(value * factor);
        if (scaled < 0) {
            out.append('-');
            scaled = -scaled;
        }
        out.append(scaled / factor).append('.');
        String fraction = Long.toString(scaled % factor);
        for (int i = fraction.length(); i < decimals; i++) {
            out.append('0');
        }
        out.append(fraction);
    }
}
