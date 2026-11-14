package ro.ppc.filegrid.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID>, JpaSpecificationExecutor<StoredFile> {

    Optional<StoredFile> findByCategoryAndIdempotencyKey(DataCategory category, String idempotencyKey);
}
