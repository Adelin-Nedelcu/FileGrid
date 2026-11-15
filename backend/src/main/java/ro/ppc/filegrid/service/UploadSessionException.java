package ro.ppc.filegrid.service;

import org.springframework.http.HttpStatus;

import java.util.List;

public class UploadSessionException extends RuntimeException {

    private final HttpStatus status;
    private final List<Integer> missingParts;

    public UploadSessionException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public UploadSessionException(HttpStatus status, String message, List<Integer> missingParts) {
        super(message);
        this.status = status;
        this.missingParts = List.copyOf(missingParts);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<Integer> getMissingParts() {
        return missingParts;
    }
}
