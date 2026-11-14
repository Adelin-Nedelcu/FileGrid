package ro.ppc.filegrid.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StorageType;

import java.time.Duration;

@Component
public class FileMetrics {

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";
    public static final String OUTCOME_CLIENT_ABORT = "client_abort";
    public static final String OUTCOME_INTEGRITY = "integrity_error";

    private final MeterRegistry registry;

    public FileMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void record(Timer.Sample sample, DataCategory category, StorageType storage,
                       String operation, String outcome, long bytes) {
        sample.stop(Timer.builder("filegrid.storage.operation")
                .description("Durata operatiilor pe storage")
                .tag("category", category.name())
                .tag("storage", storage.name())
                .tag("operation", operation)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(30))
                .register(registry));
        if (bytes > 0) {
            DistributionSummary.builder("filegrid.storage.transferred")
                    .description("Bytes transferati catre/din storage")
                    .baseUnit("bytes")
                    .tag("category", category.name())
                    .tag("storage", storage.name())
                    .tag("operation", operation)
                    .register(registry)
                    .record(bytes);
        }
    }

    public void integrityFailure(DataCategory category, StorageType storage) {
        Counter.builder("filegrid.integrity.failures")
                .description("Fisiere al caror continut nu mai corespunde hash-ului salvat")
                .tag("category", category.name())
                .tag("storage", storage.name())
                .register(registry)
                .increment();
    }

    public void duplicate(DataCategory category) {
        Counter.builder("filegrid.duplicates")
                .description("Reincercari recunoscute dupa Idempotency-Key si nestocate a doua oara")
                .tag("category", category.name())
                .register(registry)
                .increment();
    }
}
