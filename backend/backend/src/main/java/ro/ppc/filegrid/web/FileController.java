package ro.ppc.filegrid.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.service.FileService;
import ro.ppc.filegrid.service.FileService.UploadCommand;
import ro.ppc.filegrid.service.FileService.UploadResult;
import ro.ppc.filegrid.service.FileService.VerificationResult;
import ro.ppc.filegrid.service.IntegrityException;
import ro.ppc.filegrid.service.PolicyViolationException;
import ro.ppc.filegrid.storage.StorageUnavailableException;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

    public static final String SHA256_HEADER = "X-FileGrid-SHA256";
    public static final String STORAGE_HEADER = "X-FileGrid-Storage";

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping(consumes = MediaType.ALL_VALUE)
    public ResponseEntity<FileResponse> upload(
            @RequestParam DataCategory category,
            @RequestParam String name,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String sourceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) throws IOException {

        String contentType = request.getContentType() != null ? request.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        UploadResult result = files.upload(new UploadCommand(category, sanitizeFileName(name), contentType,
                request.getContentLengthLong(), idempotencyKey, sourceId, region, description), request.getInputStream());

        FileResponse body = FileResponse.from(result.file(), result.duplicate());
        if (result.duplicate()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/files/" + body.id())).body(body);
    }

    @GetMapping
    public PageResponse<FileResponse> search(
            @RequestParam(required = false) DataCategory category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(files.search(category, q, region, pageable), FileResponse::from);
    }

    @GetMapping("/{id}")
    public FileResponse get(@PathVariable UUID id) {
        return FileResponse.from(files.get(id));
    }

    @GetMapping("/{id}/content")
    public void download(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        try {
            streamFile(id, response);
        } catch (IntegrityException | StorageUnavailableException e) {
            if (response.isCommitted()) {
                throw new IOException("Transfer intrerupt: " + e.getMessage(), e);
            }
            response.reset();
            response.setHeader(RequestIdFilter.HEADER, MDC.get(RequestIdFilter.MDC_KEY));
            throw e;
        }
    }

    private void streamFile(UUID id, HttpServletResponse response) throws IOException {
        files.download(id, file -> {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(file.getContentType() != null ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setContentLengthLong(file.getSizeBytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(file.getOriginalName(), StandardCharsets.UTF_8).build().toString());
            response.setHeader(SHA256_HEADER, file.getSha256());
            response.setHeader(STORAGE_HEADER, file.getStorageType().name());
            response.setHeader("X-Content-Type-Options", "nosniff");
            return response.getOutputStream();
        });
    }

    @PostMapping("/{id}/verify")
    public VerificationResult verify(@PathVariable UUID id) {
        return files.verify(id);
    }

    static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new PolicyViolationException(HttpStatus.BAD_REQUEST, "Numele fisierului este invalid.");
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
