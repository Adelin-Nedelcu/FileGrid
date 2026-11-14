package ro.ppc.filegrid.storage;

import ro.ppc.filegrid.domain.StorageType;

import java.io.InputStream;

public interface StorageBackend {

    StorageType type();

    void write(String key, InputStream content, long length, String contentType);

    InputStream read(String key);

    void delete(String key);

    boolean ping();

    default boolean requiresKnownLength() {
        return false;
    }
}
