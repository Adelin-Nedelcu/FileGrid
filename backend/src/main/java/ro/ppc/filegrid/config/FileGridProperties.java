package ro.ppc.filegrid.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;
import ro.ppc.filegrid.domain.DataCategory;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "filegrid")
public record FileGridProperties(
        @NotEmpty Map<DataCategory, @Valid CategoryPolicy> policies,
        @Valid Storage storage,
        @DefaultValue Encryption encryption,
        @DefaultValue @Valid Upload upload,
        @Valid Security security
) {

    public FileGridProperties {
        Set<DataCategory> missing = EnumSet.allOf(DataCategory.class);
        if (policies != null) {
            missing.removeAll(policies.keySet());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Lipseste politica pentru categoriile " + missing
                    + ". Verifica config/filegrid-policies.yml (FILEGRID_POLICY_FILE).");
        }
    }

    public CategoryPolicy policyFor(DataCategory category) {
        return policies.get(category);
    }

    public record Storage(@Valid S3 s3, @Valid Ftp ftp, @Valid Database database) {
    }

    public record S3(
            @NotBlank String endpoint,
            @DefaultValue("eu-central-1") String region,
            @NotBlank String bucket,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("60s") Duration socketTimeout,
            @DefaultValue("64") int maxConnections
    ) {
    }

    public record Ftp(
            @NotBlank String host,
            @DefaultValue("21") int port,
            @NotBlank String username,
            @NotBlank String password,
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("60s") Duration dataTimeout,
            @DefaultValue("80") int maxConnections,
            @DefaultValue("5s") Duration poolWait,
            @DefaultValue("60s") Duration maxIdle
    ) {
    }

    public record Database(
            @NotBlank String url,
            @NotBlank String username,
            @NotBlank String password,
            @DefaultValue("20") int maxPoolSize,
            @DefaultValue("3s") Duration connectionTimeout,
            @DefaultValue("50MB") DataSize maxObjectSize
    ) {
    }

    public record Encryption(String key) {
    }

    public record Upload(
            @DefaultValue("2s") Duration bulkheadWait,
            @DefaultValue("2m") Duration stalePendingAfter,
            @DefaultValue("5MB") DataSize chunkSize,
            @DefaultValue("24h") Duration sessionTtl,
            @DefaultValue("10m") Duration stalledAfter
    ) {
    }

    public record Security(
            @NotBlank String jwtSecret,
            @NotBlank String meterMasterKey,
            @DefaultValue("8h") Duration sessionDuration,
            @DefaultValue("true") boolean secureCookie,
            @NotEmpty Map<String, @Valid User> users
    ) {
    }

    public record User(
            String displayName,
            @NotEmpty Set<String> roles,
            @NotBlank String password
    ) {
    }
}
