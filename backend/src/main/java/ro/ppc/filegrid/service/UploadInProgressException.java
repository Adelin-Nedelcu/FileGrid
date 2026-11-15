package ro.ppc.filegrid.service;

public class UploadInProgressException extends RuntimeException {

    public UploadInProgressException(String idempotencyKey) {
        super("Un upload cu Idempotency-Key '" + idempotencyKey + "' este deja in curs.");
    }
}
