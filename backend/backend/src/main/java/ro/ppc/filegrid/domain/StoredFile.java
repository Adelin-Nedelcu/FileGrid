package ro.ppc.filegrid.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stored_file")
public class StoredFile {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DataCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_type", nullable = false, length = 16)
    private StorageType storageType;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FileStatus status;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(nullable = false)
    private boolean encrypted;

    @Column(name = "encryption_iv")
    private byte[] encryptionIv;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "source_id", length = 128)
    private String sourceId;

    @Column(length = 64)
    private String region;

    @Column(length = 1000)
    private String description;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "stored_at")
    private Instant storedAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "last_verification_ok")
    private Boolean lastVerificationOk;

    @Version
    private Long version;

    protected StoredFile() {
    }

    public StoredFile(UUID id, DataCategory category, StorageType storageType, String storageKey,
                      String originalName, String contentType) {
        this.id = id;
        this.category = category;
        this.storageType = storageType;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.status = FileStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markStored(long sizeBytes, String sha256) {
        this.status = FileStatus.STORED;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storedAt = Instant.now();
        this.errorMessage = null;
    }

    public void markFailed(String error) {
        this.status = FileStatus.FAILED;
        this.errorMessage = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }

    public void restartAttempt(StorageType storageType, String originalName, String contentType) {
        this.storageType = storageType;
        this.status = FileStatus.PENDING;
        this.originalName = originalName;
        this.contentType = contentType;
        this.createdAt = Instant.now();
        this.errorMessage = null;
    }

    public void recordVerification(boolean ok) {
        this.lastVerifiedAt = Instant.now();
        this.lastVerificationOk = ok;
    }

    public UUID getId() { return id; }
    public DataCategory getCategory() { return category; }
    public StorageType getStorageType() { return storageType; }
    public String getStorageKey() { return storageKey; }
    public FileStatus getStatus() { return status; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public boolean isEncrypted() { return encrypted; }
    public byte[] getEncryptionIv() { return encryptionIv; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSourceId() { return sourceId; }
    public String getRegion() { return region; }
    public String getDescription() { return description; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStoredAt() { return storedAt; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public Boolean getLastVerificationOk() { return lastVerificationOk; }

    public void setEncryption(byte[] iv) {
        this.encrypted = iv != null;
        this.encryptionIv = iv;
    }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public void setRegion(String region) { this.region = region; }
    public void setDescription(String description) { this.description = description; }
}
