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
import ro.ppc.filegrid.security.AccessPolicy;
import ro.ppc.filegrid.security.ForbiddenException;
import ro.ppc.filegrid.storage.ObjectNotFoundException;
import ro.ppc.filegrid.storage.StorageBackend;
import ro.ppc.filegrid.storage.StorageRegistry;
import ro.ppc.filegrid.storage.StorageUnavailableException;

import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final StoredFileRepository repository;
    private final StorageRegistry storages;
    private final FileGridProperties props;
    private final CategoryBulkheads bulkheads;
    private final EncryptionService encryption;
    private final FileMetrics metrics;
    private final AuditLog audit;
    private final AccessPolicy access;
    private final StoredContent content;
    private final FilePartStore parts;
    private final CatalogText catalogText;

    public FileService(StoredFileRepository repository, StorageRegistry storages, FileGridProperties props,
                       CategoryBulkheads bulkheads, EncryptionService encryption, FileMetrics metrics, AuditLog audit,
                       AccessPolicy access, StoredContent content, FilePartStore parts, CatalogText catalogText) {
        this.parts = parts;
        this.catalogText = catalogText;
        this.repository = repository;
        this.storages = storages;
        this.props = props;
        this.bulkheads = bulkheads;
        this.encryption = encryption;
        this.metrics = metrics;
        this.audit = audit;
        this.access = access;
        this.content = content;
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
        access.requireWrite(cmd.category());
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
            log.info("Stocat {} {} -> {}:{} ({} bytes, sha256={}, utilizator={})", cmd.category(), saved.getId(),
                    backend.type(), saved.getStorageKey(), saved.getSizeBytes(), saved.getSha256(), saved.getUploadedBy());
            return new UploadResult(saved, false);
        } catch (RuntimeException e) {
            RuntimeException reported = hashing.sourceFailed() && !(e instanceof UploadInterruptedException)
                    ? new UploadInterruptedException("Transferul de la client s-a intrerupt.", e)
                    : e;
            boolean clientSide = reported instanceof UploadInterruptedException;
            metrics.record(sample, cmd.category(), backend.type(), "write",
                    clientSide ? FileMetrics.OUTCOME_CLIENT_ABORT : FileMetrics.OUTCOME_ERROR, hashing.count());
            failQuietly(file, reported);
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
        StoredFile file = new StoredFile(id, cmd.category(), policy.storage(), StoredContent.newKey(cmd.category(), id),
                cmd.originalName(), cmd.contentType());
        file.setIdempotencyKey(key);
        file.setSourceId(blankToNull(cmd.sourceId()));
        file.setRegion(blankToNull(cmd.region()));
        catalogText.apply(file, cmd.originalName(), blankToNull(cmd.description()));
        file.setUploadedBy(access.currentUser());
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
        catalogText.apply(existing, cmd.originalName(), blankToNull(cmd.description()));
        existing.setUploadedBy(access.currentUser());
        try {
            return new Reservation(repository.save(existing), false);
        } catch (OptimisticLockingFailureException e) {
            throw new UploadInProgressException(existing.getIdempotencyKey());
        }
    }

    private void failQuietly(StoredFile file, RuntimeException cause) {
        log.warn("Upload esuat {} {}: {}", file.getCategory(), file.getId(), cause.getMessage());
        try {
            file.markFailed(cause.getMessage());
            repository.save(file);
        } catch (RuntimeException e) {
            log.error("Nu pot marca {} ca FAILED: {}", file.getId(), e.getMessage());
        }
        content.deleteQuietly(file);
    }

    public StoredFile get(UUID id) {
        StoredFile file = repository.findById(id)
                .filter(f -> f.getStatus() == FileStatus.STORED)
                .orElseThrow(() -> new FileRecordNotFoundException(id));
        try {
            access.requireRead(file.getCategory());
        } catch (ForbiddenException e) {
            if (props.policyFor(file.getCategory()).auditAccess()) {
                audit.record(file, "ACCESS_DENIED", false, e.getMessage());
            }
            throw e;
        }
        return file;
    }

    public Page<StoredFile> search(Collection<DataCategory> categories, String text, String region, Pageable pageable) {
        Set<DataCategory> readable = access.readable(access.currentRoles());
        Collection<DataCategory> selected;
        if (categories == null || categories.isEmpty()) {
            selected = readable;
        } else {
            categories.forEach(access::requireRead);
            selected = categories;
        }
        if (selected.isEmpty()) {
            return Page.empty(pageable);
        }
        List<String> tokens = text == null ? List.of() : encryption.queryTokens(text);
        Specification<StoredFile> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), FileStatus.STORED));
            predicates.add(root.get("category").in(selected));
            if (text != null && !text.isBlank()) {
                String like = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.and(
                        cb.notLike(root.get("originalName"), EncryptionService.TEXT_PREFIX + "%"),
                        cb.or(cb.like(cb.lower(root.get("originalName")), like),
                                cb.like(cb.lower(root.get("description")), like))));
                any.add(cb.like(cb.lower(root.get("sourceId")), like));
                if (!tokens.isEmpty()) {
                    any.add(cb.and(tokens.stream()
                            .map(token -> cb.like(root.get("searchTokens"), "% " + token + " %"))
                            .toArray(Predicate[]::new)));
                }
                predicates.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (region != null && !region.isBlank()) {
                predicates.add(cb.equal(root.get("region"), region));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable);
    }

    public void download(UUID id, DownloadTarget target) throws IOException {
        download(id, null, target);
    }

    public void download(UUID id, ByteRange range, DownloadTarget target) throws IOException {
        StoredFile file = get(id);
        CategoryPolicy policy = props.policyFor(file.getCategory());
        Timer.Sample sample = metrics.start();
        InputStream stream = range == null ? openContentMeasured(file, sample, "read") : InputStream.nullInputStream();
        TrackingOutputStream out = null;
        try (stream) {
            out = new TrackingOutputStream(target.begin(file));
            long bytes = range == null
                    ? VerifiedCopy.copy(stream, out, file.getSha256()).bytes()
                    : copyRange(file, range, out);
            metrics.record(sample, file.getCategory(), file.getStorageType(), "read", FileMetrics.OUTCOME_SUCCESS, bytes);
            if (policy.auditAccess()) {
                audit.record(file, "DOWNLOAD", true,
                        range == null ? null : "Reluare: bytes " + range.start() + "-" + range.end() + " din " + file.getSizeBytes());
            }
        } catch (StorageUnavailableException e) {
            metrics.record(sample, file.getCategory(), file.getStorageType(), "read", FileMetrics.OUTCOME_ERROR, 0);
            throw e;
        } catch (IntegrityException e) {
            onIntegrityFailure(file, sample, "read", e);
            throw e;
        } catch (IOException e) {
            if (isDamagedContent(e)) {
                IntegrityException integrity = new IntegrityException(damageDescription(e), e);
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

    private long copyRange(StoredFile file, ByteRange range, OutputStream out) throws IOException {
        try {
            if (!file.isMultipart()) {
                try (InputStream in = content.open(file)) {
                    VerifiedCopy.copy(in, new WindowOutputStream(out, range.start(), range.length()), file.getSha256());
                }
                return range.length();
            }
            Map<Integer, String> hashes = new HashMap<>();
            parts.list(file.getId()).forEach(part -> hashes.put(part.number(), part.sha256()));
            long partSize = file.getPartSize();
            int first = (int) (range.start() / partSize) + 1;
            int last = (int) (range.end() / partSize) + 1;
            for (int partNumber = first; partNumber <= last; partNumber++) {
                long partStart = (partNumber - 1) * partSize;
                long partEnd = partStart + file.expectedPartSize(partNumber) - 1;
                long skip = Math.max(0, range.start() - partStart);
                long length = Math.min(range.end(), partEnd) - partStart - skip + 1;
                try (InputStream in = content.openPart(file, partNumber)) {
                    VerifiedCopy.copy(in, new WindowOutputStream(out, skip, length), hashes.get(partNumber));
                }
            }
            return range.length();
        } catch (ObjectNotFoundException e) {
            throw new IOException("O parte a fisierului lipseste din storage", e);
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
        try (InputStream stream = openContentMeasured(file, sample, "verify")) {
            VerifiedCopy.Result result = VerifiedCopy.copy(stream, OutputStream.nullOutputStream(), null);
            actualSha = result.sha256();
            actualSize = result.bytes();
            match = actualSha.equalsIgnoreCase(file.getSha256()) && actualSize.equals(file.getSizeBytes());
            detail = match ? "Continut identic cu cel stocat la upload" : "Continutul difera de cel stocat la upload";
        } catch (ObjectMissingException e) {
            detail = e.getMessage();
            alreadyRecorded = true;
        } catch (IOException e) {
            if (!isDamagedContent(e)) {
                metrics.record(sample, file.getCategory(), file.getStorageType(), "verify", FileMetrics.OUTCOME_ERROR, 0);
                throw new StorageUnavailableException(file.getStorageType(), "citirea s-a intrerupt: " + e.getMessage(), e);
            }
            detail = damageDescription(e);
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
        try {
            return content.open(file);
        } catch (ObjectNotFoundException e) {
            ObjectMissingException missing = new ObjectMissingException(
                    "Fisierul confirmat lipseste din storage-ul " + file.getStorageType(), e);
            onIntegrityFailure(file, sample, operation, missing);
            throw missing;
        } catch (StorageUnavailableException e) {
            metrics.record(sample, file.getCategory(), file.getStorageType(), operation, FileMetrics.OUTCOME_ERROR, 0);
            throw e;
        }
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

    private static boolean isDamagedContent(Throwable e) {
        return hasCause(e, AEADBadTagException.class) || hasCause(e, ObjectNotFoundException.class);
    }

    private static String damageDescription(Throwable e) {
        return hasCause(e, ObjectNotFoundException.class)
                ? "O parte a fisierului lipseste din storage"
                : "Continutul criptat a fost modificat (tag GCM invalid)";
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
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
