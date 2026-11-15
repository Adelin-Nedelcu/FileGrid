package ro.ppc.filegrid.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.service.FileService;
import ro.ppc.filegrid.service.FileService.UploadCommand;
import ro.ppc.filegrid.service.FileService.UploadResult;
import ro.ppc.filegrid.service.PolicyViolationException;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/meter-readings")
public class MeterReadingController {

    private static final Pattern METER_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern REGION_CODE = Pattern.compile("[A-Z]{1,3}");

    private final FileService files;
    private final FileViews views;

    public MeterReadingController(FileService files, FileViews views) {
        this.views = views;
        this.files = files;
    }

    @PostMapping(consumes = MediaType.ALL_VALUE)
    public ResponseEntity<FileResponse> submit(
            @RequestHeader("X-Meter-Id") String meterId,
            @RequestHeader("Idempotency-Key") String readingKey,
            @RequestHeader(value = "X-Region", required = false) String region,
            HttpServletRequest request) throws IOException {

        if (!METER_ID.matcher(meterId).matches()) {
            throw new PolicyViolationException(HttpStatus.BAD_REQUEST, "X-Meter-Id invalid.");
        }
        if (region != null && !REGION_CODE.matcher(region).matches()) {
            throw new PolicyViolationException(HttpStatus.BAD_REQUEST, "X-Region trebuie sa fie codul judetului (ex. B, CT, TM).");
        }
        String contentType = request.getContentType() != null ? request.getContentType() : MediaType.APPLICATION_JSON_VALUE;
        String extension = contentType.contains("csv") ? ".csv" : contentType.contains("xml") ? ".xml" : ".json";
        String idempotencyKey = meterId + "/" + readingKey;
        String name = "citire_" + meterId + "_" + readingKey.replaceAll("[^A-Za-z0-9._-]", "-") + extension;

        UploadResult result = files.upload(new UploadCommand(DataCategory.METER_READING, name, contentType,
                request.getContentLengthLong(), idempotencyKey, meterId, region, null), request.getInputStream());

        FileResponse body = views.of(result.file(), result.duplicate());
        if (result.duplicate()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/files/" + body.id())).body(body);
    }
}
