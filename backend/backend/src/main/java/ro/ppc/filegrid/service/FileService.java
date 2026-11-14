package ro.ppc.filegrid.service;

import io.micrometer.core.instrument.Timer;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.FileStatus;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.domain.StoredFileRepository;
import ro.ppc.filegrid.storage.ObjectNotFoundException;
import ro.ppc.filegrid.storage.StorageBackend;
import ro.ppc.filegrid.storage.StorageRegistry;
import ro.ppc.filegrid.storage.StorageUnavailableException;

import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final StoredFileRepository repository;
    private final StorageRegistry storages;
    private final FileGridProperties props;
    private final CategoryBulkheads bulkheads;
    private final EncryptionService encryption;
    private final FileMetrics metrics;
    private final AuditLog audit;

    public FileService(StoredFileRepository repository, StorageRegistry storages, FileGridProperties props,
                       CategoryBulkheads bulkheads, EncryptionService encryption, FileMetrics metrics, AuditLog audit) {
        this.repository = repository;
        this.storages = storages;
        this.props = props;
        this.bulkheads = bulkheads;
        this.encryption = encryption;
        this.metrics = metrics;
        this.audit = audit;
    }

    public record UploadCommand(DataCategory category, String originalName, String contentType, long contentLength,
                                String idempotencyKey, String sourceId, String region, String description) {
    }

    public record UploadResult(StoredFile file, boolean duplicate) {
    }

    public record VerificationResult(UUID fileId, boolean match, String expectedSha256, String actualSha256,
                                     Long expectedSize, Long actualSize, String storage, long durationMs,
                                     Instant verifiedAt, String detail) {
    }

    @FunctionalInterface
    public interface DownloadTarget {
        OutputStream begin(StoredFile file) throws IOException;
    }

    public UploadResult upload(UploadCommand cmd, InputStream body) {
        CategoryPolicy policy = props.policyFor(cmd.category());
        checkPolicy(cmd, policy);
        StorageBackend backend = storages.get(policy.storage());
        if (backend.requiresKnownLength() && cmd.contentLength() < 0) {
            throw new PolicyViolationException(HttpStatus.LENGTH_REQUIRED,
                    "Storage-ul " + policy.storage() + " necesita header-ul Content-Length.");
        }

        try (CategoryBulkheads.Permit ignored = bulkheads.acquire(cmd.category())) {
            Reservation reservation = reserve(cmd, policy);
            if (reservation.duplicate()) {
                metrics.duplicate(cmd.category());
                log.info("Duplicat ignorat: {} key={} -> {}", cmd.category(), cmd.idempotencyKey(), reservation.file().getId());
                return new UploadResult(reservation.file(), true);
            }
            return write(reservation.file(), cmd, policy, backend, body);
        }
    }

    private UploadResult write(StoredFile file, UploadCommand cmd, CategoryPolicy policy,
                               StorageBackend backend, InputStream body) {
        Timer.Sample sample = metrics.start();
        byte[] iv = policy.encryptAtRest() ? encryption.newIv() : null;
        file.setEncryption(iv);

        HashingInputStream hashing = new HashingInputStream(body);
        InputStream toStore = iv != null ? encryption.encrypt(hashing, iv) : hashing;
        long storedLength = cmd.contentLength() < 0 ? -1
                : cmd.contentLength() + (iv != null ? EncryptionService.TAG_BYTES : 0);

        try {
            backend.write(file.getStorageKey(), toStore, storedLength, cmd.contentType());
            if (cmd.contentLength() >= 0 && hashing.count() != cmd.contentLength()) {
                throw new UploadInterruptedException("S-au primit " + hashing.count() + " din "
                        + cmd.contentLength() + " bytes anuntati.", null);
            }
            file.markStored(hashing.count(), hashing.sha256Hex());
            StoredFile saved = repository.save(file);
            metrics.record(sample, cmd.category(), backend.type(), "write", FileMetrics.OUTCOME_SUCCESS, hashing.count());
            if (policy.auditAccess()) {
                audit.record(saved, "UPLOAD", true, null);
            }
            log.info("Stocat {} {} -> {}:{} ({} bytes, sha256={})", cmd.category(), saved.getId(),
                    backend.type(), saved.getStorageKey(), saved.getSizeBytes(), saved.getSha256());
            return new UploadResult(saved, false);
        } catch (RuntimeException e) {
            RuntimeException reported = hashing.sourceFailed() && !(e instanceof UploadInterruptedException)
                    ? new UploadInterruptedException("Transferul de la client s-a intrerupt.", e)
                    : e;
            boolean clientSide = reported instanceof UploadInterruptedException;
            metrics.record(sample, cmd.category(), backend.type(), "write",
                    clientSide ? FileMetrics.OUTCOME_CLIENT_ABORT : FileMetrics.OUTCOME_ERROR, hashing.count());
            failQuietly(file, backend, reported);
            throw reported;
        }
    }

    private void checkPolicy(UploadCommand cmd, CategoryPolicy policy) {
        if (cmd.contentLength() > policy.maxFileSize().toBytes()) {
            throw new PolicyViolationException(HttpStatus.CONTENT_TOO_LARGE, "Fisierul are " + cmd.contentLength()
                    + " bytes; maximul pentru " + cmd.category() + " este " + policy.maxFileSize() + ".");
        }
        if (policy.requireIdempotencyKey() && (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank())) {
            throw new PolicyViolationException(HttpStatus.BAD_REQUEST,
                    "Categoria " + cmd.category() + " cere header-ul Idempotency-Key.");
        }
    }

    private record Reservation(StoredFile file, boolean duplicate) {
    }

    private Reservation reserve(UploadCommand cmd, CategoryPolicy policy) {
        String key = blankToNull(cmd.idempotencyKey());
        if (key != null) {
            Optional<StoredFile> existing = repository.findByCategoryAndIdempotencyKey(cmd.category(), key);
            if (existing.isPresent()) {
                return resume(existing.get(), cmd, policy);
            }
        }

        UUID id = UUID.randomUUID();
        StoredFile file = new StoredFile(id, cmd.category(), policy.storage(), storageKey(cmd.category(), id),
                cmd.originalName(), cmd.contentType());
        file.setIdempotencyKey(key);
        file.setSourceId(blankToNull(cmd.sourceId()));
        file.setRegion(blankToNull(cmd.region()));
        file.setDescription(blankToNull(cmd.description()));
        try {
            return new Reservation(repository.save(file), false);
        } catch (DataIntegrityViolationException race) {
            StoredFile winner = repository.findByCategoryAndIdempotencyKey(cmd.category(), key)
                    .orElseThrow(() -> race);
            if (winner.getStatus() == FileStatus.STORED) {
                return new Reservation(winner, true);
            }
            throw new UploadInProgressException(key);
        }
    }

    private Reservation resume(StoredFile existing, UploadCommand cmd, CategoryPolicy policy) {
        Instant staleBefore = Instant.now().minus(props.upload().stalePendingAfter());
        switch (existing.getStatus()) {
            case STORED -> {
                return new Reservation(existing, true);
            }
            case PENDING -> {
                if (existing.getCreatedAt().isAfter(staleBefore)) {
                    throw new UploadInProgressException(existing.getIdempotencyKey());
                }
                log.warn("Reiau upload-ul intrerupt {} (PENDING din {})", existing.getId(), existing.getCreatedAt());
            }
            case FAILED -> log.info("Reiau upload-ul esuat {}", existing.getId());
        }
        existing.restartAttempt(policy.storage(), cmd.originalName(), cmd.contentType());
        try {
            return new Reservation(repository.save(existing), false);
        } catch (OptimisticLockingFailureException e) {
            throw new UploadInProgressException(existing.getIdempotencyKey());
        }
    }

    private static String storageKey(DataCategory category, UUID id) {
        return category.name().toLowerCase(Locale.ROOT) + "/" + KEY_DATE.format(Instant.now()) + "/" + id;
    }

    private void failQuietly(StoredFile file, StorageBackend backend, RuntimeException cause) {
        log.warn("Upload esuat {} {}: {}", file.getCategory(), file.getId(), cause.getMessage());
        try {
            file.markFailed(cause.getMessage());
            repository.save(file);
        } catch (RuntimeException e) {
            log.error("Nu pot marca {} ca FAILED: {}", file.getId(), e.getMessage());
        }
        try {
            backend.delete(file.getStorageKey());
        } catch (RuntimeException e) {
            log.debug("Curatarea obiectului partial {} a esuat: {}", file.getStorageKey(), e.getMessage());
        }
    }

    public StoredFile get(UUID id) {
        return repository.findById(id)
                .filter(f -> f.getStatus() == FileStatus.STORED)
                .orElseThrow(() -> new FileRecordNotFoundException(id));
    }

    public Page<StoredFile> search(DataCategory category, String text, String region, Pageable pageable) {
        Specification<StoredFile> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), FileStatus.STORED));
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (text != null && !text.isBlank()) {
                String like = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("originalName")), like),
                        cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(root.get("sourceId")), like)));
            }
            if (region != null && !region.isBlank()) {
                predicates.add(cb.equal(root.get("region"), region));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable);
    }

    public void download(UUID id, DownloadTarget target) throws IOException {
        StoredFile file = get(id);
        CategoryPolicy policy = props.policyFor(file.getCategory());
        Timer.Sample sample = metrics.start();
        InputStream content = openContentMeasured(file, sample, "read");
        TrackingOutputStream out = null;
        try (content) {
            out = new TrackingOutputStream(target.begin(file));
            VerifiedCopy.Result result = VerifiedCopy.copy(content, out, file.getSha256());
            metrics.record(sample, file.getCategory(), file.getStorageType(), "read", FileMetrics.OUTCOME_SUCCESS, result.bytes());
            if (policy.auditAccess()) {
                audit.record(file, "DOWNLOAD", true, null);
            }
        } catch (IntegrityException e) {
            onIntegrityFailure(file, sample, "read", e);
            throw e;
        } catch (IOException e) {
            if (isTamperedCiphertext(e)) {
                IntegrityException integrity = new IntegrityException("Continutul criptat a fost modificat (tag GCM invalid)", e);
                onIntegrityFailure(file, sample, "read", integrity);
                throw integrity;
            }
            boolean clientSide = out != null && out.failed();
            metrics.record(sample, file.getCategory(), file.getStorageType(), "read",
                    clientSide ? FileMetrics.OUTCOME_CLIENT_ABORT : FileMetrics.OUTCOME_ERROR, 0);
            if (clientSide) {
                throw e;
            }
            throw new StorageUnavailableException(file.getStorageType(), "citirea s-a intrerupt: " + e.getMessage(), e);
        }
    }

    public VerificationResult verify(UUID id) {
        StoredFile file = get(id);
        CategoryPolicy policy = props.policyFor(file.getCategory());
        Timer.Sample sample = metrics.start();
        long started = System.nanoTime();
        String actualSha = null;
        Long actualSize = null;
        String detail;
        boolean match = false;
        boolean alreadyRecorded = false;
        try (InputStream content = openContentMeasured(file, sample, "verify")) {
            VerifiedCopy.Result result = VerifiedCopy.copy(content, OutputStream.nullOutputStream(), null);
            actualSha = result.sha256();
            actualSize = result.bytes();
            match = actualSha.equalsIgnoreCase(file.getSha256()) && actualSize.equals(file.getSizeBytes());
            detail = match ? "Continut identic cu cel stocat la upload" : "Continutul difera de cel stocat la upload";
        } catch (ObjectMissingException e) {
            detail = e.getMessage();
            alreadyRecorded = true;
        } catch (IOException e) {
            if (!isTamperedCiphertext(e)) {
                metrics.record(sample, file.getCategory(), file.getStorageType(), "verify", FileMetrics.OUTCOME_ERROR, 0);
                throw new StorageUnavailableException(file.getStorageType(), "citirea s-a intrerupt: " + e.getMessage(), e);
            }
            detail = "Continutul criptat a fost modificat (tag GCM invalid)";
        }

        if (match) {
            metrics.record(sample, file.getCategory(), file.getStorageType(), "verify", FileMetrics.OUTCOME_SUCCESS, actualSize);
        } else if (!alreadyRecorded) {
            onIntegrityFailure(file, sample, "verify", new IntegrityException(detail));
        }
        file.recordVerification(match);
        StoredFile saved = repository.save(file);
        if (policy.auditAccess()) {
            audit.record(saved, "VERIFY", match, detail);
        }
        return new VerificationResult(file.getId(), match, file.getSha256(), actualSha, file.getSizeBytes(), actualSize,
                file.getStorageType().name(), (System.nanoTime() - started) / 1_000_000, saved.getLastVerifiedAt(), detail);
    }

    private InputStream openContentMeasured(StoredFile file, Timer.Sample sample, String operation) {
        StorageBackend backend = storages.get(file.getStorageType());
        InputStream raw;
        try {
            raw = backend.read(file.getStorageKey());
        } catch (ObjectNotFoundException e) {
            ObjectMissingException missing = new ObjectMissingException(
                    "Fisierul confirmat lipseste din storage-ul " + file.getStorageType(), e);
            onIntegrityFailure(file, sample, operation, missing);
            throw missing;
        } catch (StorageUnavailableException e) {
            metrics.record(sample, file.getCategory(), file.getStorageType(), operation, FileMetrics.OUTCOME_ERROR, 0);
            throw e;
        }
        return file.isEncrypted() ? encryption.decrypt(raw, file.getEncryptionIv()) : raw;
    }

    public static class ObjectMissingException extends IntegrityException {
        ObjectMissingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void onIntegrityFailure(StoredFile file, Timer.Sample sample, String operation, IntegrityException e) {
        log.error("INTEGRITATE compromisa pentru {} ({} / {}): {}", file.getId(), file.getCategory(),
                file.getStorageType(), e.getMessage());
        metrics.integrityFailure(file.getCategory(), file.getStorageType());
        metrics.record(sample, file.getCategory(), file.getStorageType(), operation, FileMetrics.OUTCOME_INTEGRITY, 0);
    }

    private static boolean isTamperedCiphertext(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AEADBadTagException) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private boolean failed;

        TrackingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        boolean failed() {
            return failed;
        }

        @Override
        public void write(int b) throws IOException {
            try {
                delegate.write(b);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                delegate.write(b, off, len);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                delegate.flush();
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }
    }
}
