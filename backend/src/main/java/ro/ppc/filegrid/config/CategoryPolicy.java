package ro.ppc.filegrid.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import ro.ppc.filegrid.domain.StorageType;

import java.util.Set;

public record CategoryPolicy(
        @NotNull StorageType storage,
        @NotNull DataSize maxFileSize,
        @DefaultValue("20") @Positive int maxConcurrentUploads,
        @DefaultValue("false") boolean encryptAtRest,
        @DefaultValue("false") boolean requireIdempotencyKey,
        @DefaultValue("false") boolean auditAccess,
        @NotEmpty Set<String> readRoles,
        @NotEmpty Set<String> writeRoles
) {
}
