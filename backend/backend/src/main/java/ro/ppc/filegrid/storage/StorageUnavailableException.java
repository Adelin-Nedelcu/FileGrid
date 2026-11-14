package ro.ppc.filegrid.storage;

import ro.ppc.filegrid.domain.StorageType;

public class StorageUnavailableException extends RuntimeException {

    private final StorageType storage;

    public StorageUnavailableException(StorageType storage, String message, Throwable cause) {
        super(storage + ": " + message, cause);
        this.storage = storage;
    }

    public StorageType getStorage() {
        return storage;
    }
}
