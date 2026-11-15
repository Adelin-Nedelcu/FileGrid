package ro.ppc.filegrid.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ro.ppc.filegrid.security.ForbiddenException;
import ro.ppc.filegrid.security.LoginFailedException;
import ro.ppc.filegrid.service.CapacityExceededException;
import ro.ppc.filegrid.service.FileRecordNotFoundException;
import ro.ppc.filegrid.service.IntegrityException;
import ro.ppc.filegrid.service.PolicyViolationException;
import ro.ppc.filegrid.service.UploadInProgressException;
import ro.ppc.filegrid.service.UploadInterruptedException;
import ro.ppc.filegrid.service.UploadSessionException;
import ro.ppc.filegrid.storage.StorageUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PolicyViolationException.class)
    ResponseEntity<ProblemDetail> policy(PolicyViolationException e) {
        return problem(e.getStatus(), "Cerere respinsa de politica", e.getMessage(), null);
    }

    @ExceptionHandler(FileRecordNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(FileRecordNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Fisier inexistent", e.getMessage(), null);
    }

    @ExceptionHandler(LoginFailedException.class)
    ResponseEntity<ProblemDetail> loginFailed(LoginFailedException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Autentificare esuata", e.getMessage(), null);
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ProblemDetail> forbidden(ForbiddenException e) {
        log.warn("Acces refuzat: {}", e.getMessage());
        return problem(HttpStatus.FORBIDDEN, "Acces interzis", e.getMessage(), null);
    }

    @ExceptionHandler(UploadSessionException.class)
    ResponseEntity<ProblemDetail> uploadSession(UploadSessionException e) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        body.setTitle("Upload reluabil");
        if (!e.getMissingParts().isEmpty()) {
            body.setProperty("missingParts", e.getMissingParts());
        }
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    @ExceptionHandler(CapacityExceededException.class)
    ResponseEntity<ProblemDetail> capacity(CapacityExceededException e) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Categorie la capacitate maxima", e.getMessage(), "2");
    }

    @ExceptionHandler(UploadInProgressException.class)
    ResponseEntity<ProblemDetail> inProgress(UploadInProgressException e) {
        return problem(HttpStatus.CONFLICT, "Upload in curs", e.getMessage(), "2");
    }

    @ExceptionHandler(UploadInterruptedException.class)
    ResponseEntity<ProblemDetail> interrupted(UploadInterruptedException e) {
        return problem(HttpStatus.BAD_REQUEST, "Transfer incomplet", e.getMessage(), null);
    }

    @ExceptionHandler(StorageUnavailableException.class)
    ResponseEntity<ProblemDetail> storageDown(StorageUnavailableException e) {
        log.warn("Storage indisponibil: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Storage indisponibil",
                "Storage-ul " + e.getStorage() + " nu raspunde momentan. Celelalte categorii functioneaza normal; reincercati.", "5");
    }

    @ExceptionHandler(IntegrityException.class)
    ResponseEntity<ProblemDetail> integrity(IntegrityException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Integritate compromisa", e.getMessage(), null);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, String retryAfter) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(body);
    }
}
