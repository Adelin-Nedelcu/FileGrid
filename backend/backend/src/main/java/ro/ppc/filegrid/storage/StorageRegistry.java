package ro.ppc.filegrid.storage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StorageType;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class StorageRegistry {

    private static final Logger log = LoggerFactory.getLogger(StorageRegistry.class);

    private final Map<StorageType, StorageBackend> backends = new EnumMap<>(StorageType.class);
    private final Map<StorageType, StorageHealth> health = new ConcurrentHashMap<>();

    public StorageRegistry(List<StorageBackend> all, FileGridProperties props, MeterRegistry meters) {
        all.forEach(b -> backends.put(b.type(), b));
        for (StorageType type : StorageType.values()) {
            if (!backends.containsKey(type)) {
                throw new IllegalStateException("Nu exista implementare pentru storage-ul " + type);
            }
            health.put(type, new StorageHealth(false, null));
            Gauge.builder("filegrid.storage.up", health, h -> h.get(type).up() ? 1 : 0)
                    .description("1 daca storage-ul raspunde, 0 altfel")
                    .tag("storage", type.name())
                    .register(meters);
        }
        validatePolicies(props);
    }

    public StorageBackend get(StorageType type) {
        return backends.get(type);
    }

    public Map<StorageType, StorageHealth> health() {
        return Map.copyOf(health);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${filegrid.storage.health-check-interval:10s}")
    void refreshHealth() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            backends.values().forEach(b -> executor.submit(() -> checkOne(b)));
        }
    }

    private void checkOne(StorageBackend backend) {
        boolean up = backend.ping();
        StorageHealth previous = health.get(backend.type());
        if (previous != null && previous.checkedAt() != null && previous.up() != up) {
            if (up) {
                log.info("Storage {} este din nou disponibil", backend.type());
            } else {
                log.warn("Storage {} NU raspunde", backend.type());
            }
        }
        health.put(backend.type(), new StorageHealth(up, Instant.now()));
    }

    private void validatePolicies(FileGridProperties props) {
        DatabaseStorageBackend db = (DatabaseStorageBackend) backends.get(StorageType.DATABASE);
        props.policies().forEach((DataCategory category, CategoryPolicy policy) -> {
            if (policy.storage() == StorageType.DATABASE && policy.maxFileSize().toBytes() > db.maxObjectSize()) {
                throw new IllegalStateException("Politica " + category + " trimite fisiere de pana la "
                        + policy.maxFileSize() + " in storage-ul DATABASE, dar limita lui e "
                        + db.maxObjectSize() + " bytes. Alege alt storage sau micsoreaza max-file-size.");
            }
            log.info("Politica {} -> storage={}, max={}, criptare={}, idempotenta={}, audit={}, concurenta={}",
                    category, policy.storage(), policy.maxFileSize(), policy.encryptAtRest(),
                    policy.requireIdempotencyKey(), policy.auditAccess(), policy.maxConcurrentUploads());
        });
    }

    public record StorageHealth(boolean up, Instant checkedAt) {
    }
}
