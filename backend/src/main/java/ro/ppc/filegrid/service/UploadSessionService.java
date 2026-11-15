package ro.ppc.filegrid.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.FileStatus;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.domain.StoredFileRepository;
import ro.ppc.filegrid.security.AccessPolicy;
import ro.ppc.filegrid.storage.ObjectNotFoundException;
import ro.ppc.filegrid.storage.StorageBackend;
import ro.ppc.filegrid.storage.StorageRegistry;
import ro.ppc.filegrid.storage.StorageUnavailableException;

import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

@Service
public class UploadSessionService {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionService.class);
    private static final String KEY_PREFIX = "upload:";

    private final StoredFileRepository repository;
    private final FilePartStore parts;
    private final StorageRegistry storages;
    private final StoredContent content;
    private final FileGridProperties props;
    private final CategoryBulkheads bulkheads;
    private final EncryptionService encryption;
    private final FileMetrics metrics;
    private final AuditLog audit;
    private final AccessPolicy access;
    private final JdbcTemplate jdbc;
    private final CatalogText catalogText;
    private final Map<DataCategory, AtomicLong> stalled = new EnumMap<>(DataCategory.class);

    public UploadSessionService(StoredFileRepository repository, FilePartStore parts, StorageRegistry storages,
                                StoredContent content, FileGridProperties props, CategoryBulkheads bulkheads,
                                EncryptionService encryption, FileMetrics metrics, AuditLog audit, AccessPolicy access,
                                JdbcTemplate jdbc, MeterRegistry meters, CatalogText catalogText) {
        this.catalogText = catalogText;
        this.repository = repository;
        this.parts = parts;
        this.storages = storages;
        this.content = content;
        this.props = props;
        this.bulkheads = bulkheads;
        this.encryption = encryption;
        this.metrics = metrics;
        this.audit = audit;
        this.access = access;
        this.jdbc = jdbc;
        for (DataCategory category : DataCategory.values()) {
            AtomicLong value = new AtomicLong();
            stalled.put(category, value);
            Gauge.builder("filegrid.uploads.stalled", value, AtomicLong::get)
                    .description("Upload-uri reluabile incepute, fara nicio parte noua in ultimele minute")
                    .tag("category", category.name())
                    .register(meters);
        }
    }

    public record CreateCommand(DataCategory category, String name, String contentType, long size,
                                String clientKey, String region, String description) {
    }

    public record Session(StoredFile file, List<Integer> receivedParts) {
    }

    public record PartReceipt(int partNumber, long sizeBytes, String sha256) {
    }

    public Session create(CreateCommand cmd) {
        access.requireWrite(cmd.category());
        CategoryPolicy policy = props.policyFor(cmd.category());
        if (cmd.size() > policy.maxFileSize().toBytes()) {
            throw new PolicyViolationException(HttpStatus.CONTENT_TOO_LARGE, "Fisierul are " + cmd.size()
                    + " bytes; maximul pentru " + cmd.category() + " este " + policy.maxFileSize() + ".");
        }
        long partSize = props.upload().chunkSize().toBytes();
        int partCount = Math.toIntExact(Math.ceilDiv(cmd.size(), partSize));
        String owner = access.currentUser();
        String key = KEY_PREFIX + owner + ":" + cmd.clientKey();

        Optional<StoredFile> existing = repository.findByCategoryAndIdempotencyKey(cmd.category(), key);
        if (existing.isPresent()) {
            return resumeOrRestart(existing.get(), cmd, policy, partCount, partSize);
        }

        UUID id = UUID.randomUUID();
        StoredFile file = new StoredFile(id, cmd.category(), policy.storage(), StoredContent.newKey(cmd.category(), id),
                cmd.name(), cmd.contentType());
        file.setIdempotencyKey(key);
        file.setUploadedBy(owner);
        describe(file, cmd, policy, partCount, partSize);
        try {
            StoredFile saved = repository.save(file);
            log.info("Upload reluabil inceput {} {} ({} bytes in {} parti, utilizator={})", cmd.category(), id,
                    cmd.size(), partCount, owner);
            return new Session(saved, List.of());
        } catch (DataIntegrityViolationException race) {
            StoredFile winner = repository.findByCategoryAndIdempotencyKey(cmd.category(), key).orElseThrow(() -> race);
            return new Session(winner, receivedParts(winner));
        }
    }

    private Session resumeOrRestart(StoredFile file, CreateCommand cmd, CategoryPolicy policy, int partCount, long partSize) {
        if (file.getStatus() == FileStatus.STORED) {
            return new Session(file, List.of());
        }
        boolean sameLayout = file.getStatus() == FileStatus.PENDING && file.isMultipart()
                && file.getSizeBytes() == cmd.size() && file.getPartSize() == partSize
                && file.getStorageType() == policy.storage() && file.isEncrypted() == policy.encryptAtRest();
        if (sameLayout) {
            List<Integer> received = receivedParts(file);
            log.info("Upload reluat {} {}: {} din {} parti deja primite", file.getCategory(), file.getId(),
                    received.size(), file.getPartCount());
            return new Session(file, received);
        }
        discardParts(file);
        file.restartAttempt(policy.storage(), cmd.name(), cmd.contentType());
        describe(file, cmd, policy, partCount, partSize);
        return new Session(repository.save(file), List.of());
    }

    private void describe(StoredFile file, CreateCommand cmd, CategoryPolicy policy, int partCount, long partSize) {
        file.beginMultipart(cmd.size(), partCount, partSize);
        file.setEncryption(policy.encryptAtRest() ? encryption.newIv() : null);
        file.setRegion(blankToNull(cmd.region()));
        catalogText.apply(file, cmd.name(), blankToNull(cmd.description()));
    }

    public Session status(UUID id) {
        StoredFile file = owned(id);
        return new Session(file, file.getStatus() == FileStatus.PENDING ? receivedParts(file) : List.of());
    }

    public PartReceipt uploadPart(UUID id, int partNumber, long contentLength, String declaredSha256, InputStream body) {
        StoredFile file = pending(id);
        if (partNumber < 1 || partNumber > file.getPartCount()) {
            throw new UploadSessionException(HttpStatus.BAD_REQUEST,
                    "Partea " + partNumber + " nu exista; fisierul are " + file.getPartCount() + " parti.");
        }
        long expected = file.expectedPartSize(partNumber);
        if (contentLength != expected) {
            throw new UploadSessionException(HttpStatus.BAD_REQUEST,
                    "Partea " + partNumber + " trebuie sa aiba " + expected + " bytes, nu " + contentLength + ".");
        }

        StorageBackend backend = storages.get(file.getStorageType());
        String key = StoredContent.partKey(file, partNumber);
        try (CategoryBulkheads.Permit ignored = bulkheads.acquire(file.getCategory())) {
            Timer.Sample sample = metrics.start();
            HashingInputStream hashing = new HashingInputStream(body);
            InputStream toStore = file.isEncrypted()
                    ? encryption.encrypt(hashing, encryption.partIv(file.getEncryptionIv(), partNumber))
                    : hashing;
            long storedLength = expected + (file.isEncrypted() ? EncryptionService.TAG_BYTES : 0);
            try {
                backend.write(key, toStore, storedLength, "application/octet-stream");
                if (hashing.count() != expected) {
                    throw new UploadInterruptedException("S-au primit " + hashing.count() + " din " + expected
                            + " bytes pentru partea " + partNumber + ".", null);
                }
                String sha256 = hashing.sha256Hex();
                if (declaredSha256 != null && !declaredSha256.isBlank() && !declaredSha256.equalsIgnoreCase(sha256)) {
                    throw new UploadSessionException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Partea " + partNumber + " a ajuns modificata (SHA-256 diferit); retrimiteti-o.");
                }
                parts.save(file.getId(), partNumber, expected, sha256);
                metrics.record(sample, file.getCategory(), backend.type(), "write", FileMetrics.OUTCOME_SUCCESS, expected);
                return new PartReceipt(partNumber, expected, sha256);
            } catch (RuntimeException e) {
                RuntimeException reported = hashing.sourceFailed() && !(e instanceof UploadInterruptedException)
                        ? new UploadInterruptedException("Transferul partii " + partNumber + " s-a intrerupt.", e)
                        : e;
                boolean clientSide = reported instanceof UploadInterruptedException || reported instanceof UploadSessionException;
                metrics.record(sample, file.getCategory(), backend.type(), "write",
                        clientSide ? FileMetrics.OUTCOME_CLIENT_ABORT : FileMetrics.OUTCOME_ERROR, hashing.count());
                content.deletePartQuietly(file, partNumber);
                parts.delete(file.getId(), partNumber);
                throw reported;
            }
        }
    }

    public StoredFile complete(UUID id) {
        StoredFile file = owned(id);
        if (file.getStatus() == FileStatus.STORED) {
            return file;
        }
        requirePending(file);

        Map<Integer, FilePartStore.Part> received = new HashMap<>();
        parts.list(id).forEach(part -> received.put(part.number(), part));
        List<Integer> missing = IntStream.rangeClosed(1, file.getPartCount())
                .filter(n -> !received.containsKey(n)).boxed().toList();
        if (!missing.isEmpty()) {
            throw new UploadSessionException(HttpStatus.CONFLICT, "Lipsesc " + missing.size() + " parti.", missing);
        }

        Timer.Sample sample = metrics.start();
        MessageDigest whole = HashingInputStream.sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        for (int partNumber = 1; partNumber <= file.getPartCount(); partNumber++) {
            FilePartStore.Part expected = received.get(partNumber);
            MessageDigest partDigest = HashingInputStream.sha256();
            long partBytes = 0;
            try (InputStream in = content.openPart(file, partNumber)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    partDigest.update(buffer, 0, n);
                    whole.update(buffer, 0, n);
                    partBytes += n;
                }
            } catch (ObjectNotFoundException e) {
                throw damagedPart(file, partNumber);
            } catch (StorageUnavailableException e) {
                metrics.record(sample, file.getCategory(), file.getStorageType(), "assemble", FileMetrics.OUTCOME_ERROR, 0);
                throw e;
            } catch (IOException e) {
                if (e.getCause() instanceof AEADBadTagException) {
                    throw damagedPart(file, partNumber);
                }
                metrics.record(sample, file.getCategory(), file.getStorageType(), "assemble", FileMetrics.OUTCOME_ERROR, 0);
                throw new StorageUnavailableException(file.getStorageType(), "citirea partii " + partNumber
                        + " s-a intrerupt: " + e.getMessage(), e);
            }
            if (partBytes != expected.sizeBytes()
                    || !HexFormat.of().formatHex(partDigest.digest()).equalsIgnoreCase(expected.sha256())) {
                throw damagedPart(file, partNumber);
            }
            total += partBytes;
        }

        file.markStored(total, HexFormat.of().formatHex(whole.digest()));
        StoredFile saved;
        try {
            saved = repository.save(file);
        } catch (OptimisticLockingFailureException e) {
            StoredFile current = repository.findById(id).orElseThrow(() -> new FileRecordNotFoundException(id));
            if (current.getStatus() == FileStatus.STORED) {
                return current;
            }
            throw new UploadInProgressException(file.getIdempotencyKey());
        }
        metrics.record(sample, file.getCategory(), file.getStorageType(), "assemble", FileMetrics.OUTCOME_SUCCESS, total);
        if (props.policyFor(file.getCategory()).auditAccess()) {
            audit.record(saved, "UPLOAD", true, null);
        }
        log.info("Stocat {} {} -> {}:{} ({} bytes in {} parti, sha256={}, utilizator={})", saved.getCategory(),
                saved.getId(), saved.getStorageType(), saved.getStorageKey(), saved.getSizeBytes(), saved.getPartCount(),
                saved.getSha256(), saved.getUploadedBy());
        return saved;
    }

    public void abort(UUID id) {
        StoredFile file = owned(id);
        if (file.getStatus() != FileStatus.PENDING) {
            return;
        }
        discardParts(file);
        file.markFailed("Anulat de utilizator");
        repository.save(file);
        log.info("Upload reluabil anulat {} {}", file.getCategory(), file.getId());
    }

    @Scheduled(fixedDelayString = "${filegrid.upload.expiry-check-interval:10m}", initialDelayString = "1m")
    void expireAbandoned() {
        Instant cutoff = Instant.now().minus(props.upload().sessionTtl());
        for (StoredFile file : repository.findByStatusAndPartCountIsNotNullAndCreatedAtBefore(FileStatus.PENDING, cutoff)) {
            try {
                discardParts(file);
                file.markFailed("Expirat: upload nefinalizat in " + props.upload().sessionTtl());
                repository.save(file);
                log.warn("Upload reluabil expirat {} {}", file.getCategory(), file.getId());
            } catch (RuntimeException e) {
                log.warn("Nu pot curata upload-ul expirat {}: {}", file.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${filegrid.upload.stalled-check-interval:30s}", initialDelayString = "15s")
    void refreshStalledGauge() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(props.upload().stalledAfter()));
        Map<DataCategory, Long> counts = new EnumMap<>(DataCategory.class);
        try {
            jdbc.query("""
                    SELECT f.category, count(*) FROM stored_file f
                    WHERE f.status = 'PENDING' AND f.part_count IS NOT NULL
                      AND coalesce((SELECT max(p.created_at) FROM stored_file_part p WHERE p.file_id = f.id), f.created_at) < ?
                    GROUP BY f.category""",
                    rs -> {
                        counts.put(DataCategory.valueOf(rs.getString(1)), rs.getLong(2));
                    }, cutoff);
        } catch (RuntimeException e) {
            log.debug("Nu pot numara upload-urile blocate: {}", e.getMessage());
            return;
        }
        stalled.forEach((category, value) -> value.set(counts.getOrDefault(category, 0L)));
    }

    private UploadSessionException damagedPart(StoredFile file, int partNumber) {
        log.warn("Partea {} a upload-ului {} este deteriorata in storage; trebuie retrimisa", partNumber, file.getId());
        content.deletePartQuietly(file, partNumber);
        parts.delete(file.getId(), partNumber);
        return new UploadSessionException(HttpStatus.CONFLICT,
                "Partea " + partNumber + " este deteriorata in storage si trebuie retrimisa.", List.of(partNumber));
    }

    private StoredFile owned(UUID id) {
        StoredFile file = repository.findById(id)
                .filter(StoredFile::isMultipart)
                .orElseThrow(() -> new UploadSessionException(HttpStatus.NOT_FOUND, "Sesiunea de upload " + id + " nu exista."));
        access.requireOwner(file.getUploadedBy());
        return file;
    }

    private StoredFile pending(UUID id) {
        StoredFile file = owned(id);
        requirePending(file);
        return file;
    }

    private static void requirePending(StoredFile file) {
        if (file.getStatus() == FileStatus.STORED) {
            throw new UploadSessionException(HttpStatus.CONFLICT, "Upload-ul este deja finalizat.");
        }
        if (file.getStatus() == FileStatus.FAILED) {
            throw new UploadSessionException(HttpStatus.GONE, "Sesiunea de upload a fost anulata sau a expirat.");
        }
    }

    private List<Integer> receivedParts(StoredFile file) {
        List<Integer> numbers = new ArrayList<>();
        parts.list(file.getId()).forEach(part -> numbers.add(part.number()));
        return numbers;
    }

    private void discardParts(StoredFile file) {
        if (file.isMultipart()) {
            content.deleteQuietly(file);
        }
        parts.deleteAll(file.getId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
