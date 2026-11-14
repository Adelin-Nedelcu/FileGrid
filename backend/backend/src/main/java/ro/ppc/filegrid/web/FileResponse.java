package ro.ppc.filegrid.web;

import ro.ppc.filegrid.domain.StoredFile;

import java.time.Instant;
import java.util.UUID;

public record FileResponse(
        UUID id,
        String category,
        String storage,
        String name,
        String contentType,
        Long sizeBytes,
        String sha256,
        boolean encrypted,
        String sourceId,
        String region,
        String description,
        Instant createdAt,
        Instant storedAt,
        Instant lastVerifiedAt,
        Boolean lastVerificationOk,
        boolean duplicate
) {
    public static FileResponse from(StoredFile f) {
        return from(f, false);
    }

    public static FileResponse from(StoredFile f, boolean duplicate) {
        return new FileResponse(f.getId(), f.getCategory().name(), f.getStorageType().name(), f.getOriginalName(),
                f.getContentType(), f.getSizeBytes(), f.getSha256(), f.isEncrypted(), f.getSourceId(), f.getRegion(),
                f.getDescription(), f.getCreatedAt(), f.getStoredAt(), f.getLastVerifiedAt(), f.getLastVerificationOk(),
                duplicate);
    }
}
