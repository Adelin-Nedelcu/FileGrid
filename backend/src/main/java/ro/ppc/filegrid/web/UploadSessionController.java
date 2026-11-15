package ro.ppc.filegrid.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.FileStatus;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.service.UploadSessionService;
import ro.ppc.filegrid.service.UploadSessionService.CreateCommand;
import ro.ppc.filegrid.service.UploadSessionService.PartReceipt;
import ro.ppc.filegrid.service.UploadSessionService.Session;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/uploads")
public class UploadSessionController {

    private final UploadSessionService sessions;
    private final FileViews views;

    public UploadSessionController(UploadSessionService sessions, FileViews views) {
        this.views = views;
        this.sessions = sessions;
    }

    public record CreateRequest(
            @NotNull DataCategory category,
            @NotBlank String name,
            @Positive long size,
            String contentType,
            String region,
            String description,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{8,128}") String clientKey
    ) {
    }

    public record SessionResponse(UUID id, boolean completed, long partSize, int partCount,
                                  List<Integer> receivedParts, FileResponse file) {
        static SessionResponse from(Session session, FileViews views) {
            StoredFile file = session.file();
            boolean completed = file.getStatus() == FileStatus.STORED;
            return new SessionResponse(file.getId(), completed, file.getPartSize(), file.getPartCount(),
                    session.receivedParts(), completed ? views.of(file, true) : null);
        }
    }

    @PostMapping
    public SessionResponse create(@Valid @RequestBody CreateRequest request) {
        String contentType = request.contentType() == null || request.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : request.contentType();
        return SessionResponse.from(sessions.create(new CreateCommand(request.category(),
                FileController.sanitizeFileName(request.name()), contentType, request.size(), request.clientKey(),
                request.region(), request.description())), views);
    }

    @GetMapping("/{id}")
    public SessionResponse status(@PathVariable UUID id) {
        return SessionResponse.from(sessions.status(id), views);
    }

    @PutMapping(path = "/{id}/parts/{partNumber}", consumes = MediaType.ALL_VALUE)
    public PartReceipt uploadPart(@PathVariable UUID id, @PathVariable int partNumber,
                                  @RequestHeader(value = "X-Part-SHA256", required = false) String sha256,
                                  HttpServletRequest request) throws IOException {
        return sessions.uploadPart(id, partNumber, request.getContentLengthLong(), sha256, request.getInputStream());
    }

    @PostMapping("/{id}/complete")
    public FileResponse complete(@PathVariable UUID id) {
        return views.of(sessions.complete(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> abort(@PathVariable UUID id) {
        sessions.abort(id);
        return ResponseEntity.noContent().build();
    }
}
