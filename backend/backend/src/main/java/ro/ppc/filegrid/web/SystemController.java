package ro.ppc.filegrid.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StorageType;
import ro.ppc.filegrid.storage.StorageRegistry;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final StorageRegistry storages;
    private final FileGridProperties props;

    public SystemController(StorageRegistry storages, FileGridProperties props) {
        this.storages = storages;
        this.props = props;
    }

    public record StorageStatus(boolean up, Instant checkedAt) {
    }

    public record PolicyView(String storage, long maxFileSizeBytes, int maxConcurrentUploads,
                             boolean encryptAtRest, boolean requireIdempotencyKey, boolean auditAccess) {
        static PolicyView from(CategoryPolicy p) {
            return new PolicyView(p.storage().name(), p.maxFileSize().toBytes(), p.maxConcurrentUploads(),
                    p.encryptAtRest(), p.requireIdempotencyKey(), p.auditAccess());
        }
    }

    public record SystemStatus(Map<StorageType, StorageStatus> storages, Map<DataCategory, PolicyView> policies) {
    }

    @GetMapping("/status")
    public SystemStatus status() {
        Map<StorageType, StorageStatus> health = new TreeMap<>();
        storages.health().forEach((type, h) -> health.put(type, new StorageStatus(h.up(), h.checkedAt())));
        Map<DataCategory, PolicyView> policies = new TreeMap<>();
        props.policies().forEach((category, policy) -> policies.put(category, PolicyView.from(policy)));
        return new SystemStatus(health, policies);
    }
}
