package ro.ppc.filegrid.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.domain.DataCategory;

@Component
public class SecurityMetrics {

    private final MeterRegistry registry;

    public SecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (DataCategory category : DataCategory.values()) {
            deniedCounter(category);
        }
        failureCounter("user");
        failureCounter("meter");
    }

    public void authenticationFailed(String type) {
        failureCounter(type).increment();
    }

    public void accessDenied(DataCategory category) {
        deniedCounter(category).increment();
    }

    private Counter failureCounter(String type) {
        return Counter.builder("filegrid.auth.failures")
                .description("Autentificari esuate")
                .tag("type", type)
                .register(registry);
    }

    private Counter deniedCounter(DataCategory category) {
        return Counter.builder("filegrid.access.denied")
                .description("Incercari de acces la o categorie fara rolul necesar")
                .tag("category", category.name())
                .register(registry);
    }
}
