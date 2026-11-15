package ro.ppc.filegrid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.storage.StorageBackend;
import ro.ppc.filegrid.storage.StorageRegistry;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Component
public class StoredContent {

    private static final Logger log = LoggerFactory.getLogger(StoredContent.class);
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final StorageRegistry storages;
    private final EncryptionService encryption;

    public StoredContent(StorageRegistry storages, EncryptionService encryption) {
        this.storages = storages;
        this.encryption = encryption;
    }

    public static String newKey(DataCategory category, UUID id) {
        return category.name().toLowerCase(Locale.ROOT) + "/" + KEY_DATE.format(Instant.now()) + "/" + id;
    }

    public static String partKey(StoredFile file, int partNumber) {
        return file.getStorageKey() + "/part-" + String.format("%05d", partNumber);
    }

    public InputStream open(StoredFile file) {
        if (file.isMultipart()) {
            return new PartsInputStream(partNumber -> openPart(file, partNumber), file.getPartCount());
        }
        InputStream raw = storages.get(file.getStorageType()).read(file.getStorageKey());
        return file.isEncrypted() ? encryption.decrypt(raw, file.getEncryptionIv()) : raw;
    }

    public InputStream openPart(StoredFile file, int partNumber) {
        InputStream raw = storages.get(file.getStorageType()).read(partKey(file, partNumber));
        return file.isEncrypted()
                ? encryption.decrypt(raw, encryption.partIv(file.getEncryptionIv(), partNumber))
                : raw;
    }

    public void deleteQuietly(StoredFile file) {
        StorageBackend backend = storages.get(file.getStorageType());
        if (!file.isMultipart()) {
            deleteQuietly(backend, file.getStorageKey());
            return;
        }
        for (int partNumber = 1; partNumber <= file.getPartCount(); partNumber++) {
            deleteQuietly(backend, partKey(file, partNumber));
        }
    }

    public void deletePartQuietly(StoredFile file, int partNumber) {
        deleteQuietly(storages.get(file.getStorageType()), partKey(file, partNumber));
    }

    private static void deleteQuietly(StorageBackend backend, String key) {
        try {
            backend.delete(key);
        } catch (RuntimeException e) {
            log.debug("Stergerea obiectului {} a esuat: {}", key, e.getMessage());
        }
    }
}
