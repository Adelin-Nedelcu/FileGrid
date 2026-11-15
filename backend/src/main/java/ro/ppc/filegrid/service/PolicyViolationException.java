package ro.ppc.filegrid.service;

import org.springframework.http.HttpStatus;

public class PolicyViolationException extends RuntimeException {

    private final HttpStatus status;

    public PolicyViolationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
