package ro.ppc.filegrid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

@ConfigurationProperties(prefix = "filegrid.stats")
public record StatsProperties(
        @DefaultValue({"ADMIN", "DISPECERAT"}) Set<String> viewRoles,
        @DefaultValue("ADMIN") Set<String> benchmarkRoles
) {
}
