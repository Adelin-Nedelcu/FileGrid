package ro.ppc.filegrid.service;

public class UploadInterruptedException extends RuntimeException {

    public UploadInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
