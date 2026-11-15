package ro.ppc.filegrid.simulator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class Log {

    private Log() {
    }

    static void info(String message) {
        System.out.println(Instant.now().truncatedTo(ChronoUnit.SECONDS) + "  INFO " + message);
    }

    static void warn(String message) {
        System.out.println(Instant.now().truncatedTo(ChronoUnit.SECONDS) + "  WARN " + message);
    }
}
