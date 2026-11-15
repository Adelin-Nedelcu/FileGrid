package ro.ppc.filegrid.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class CategoryBulkheads {

    private final Map<DataCategory, Semaphore> semaphores = new EnumMap<>(DataCategory.class);
    private final Map<DataCategory, Counter> rejected = new EnumMap<>(DataCategory.class);
    private final Duration maxWait;

    public CategoryBulkheads(FileGridProperties props, MeterRegistry meters) {
        this.maxWait = props.upload().bulkheadWait();
        props.policies().forEach((category, policy) -> {
            int limit = policy.maxConcurrentUploads();
            Semaphore semaphore = new Semaphore(limit, true);
            semaphores.put(category, semaphore);
            Gauge.builder("filegrid.bulkhead.in_use", semaphore, s -> limit - s.availablePermits())
                    .description("Scrieri in curs pentru categorie")
                    .tag("category", category.name())
                    .register(meters);
            Gauge.builder("filegrid.bulkhead.limit", () -> limit)
                    .tag("category", category.name())
                    .register(meters);
            rejected.put(category, Counter.builder("filegrid.bulkhead.rejected")
                    .description("Cereri respinse (429) pentru ca categoria era la capacitate maxima")
                    .tag("category", category.name())
                    .register(meters));
        });
    }

    public Permit acquire(DataCategory category) {
        Semaphore semaphore = semaphores.get(category);
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            rejected.get(category).increment();
            throw new CapacityExceededException(category);
        }
        return semaphore::release;
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
