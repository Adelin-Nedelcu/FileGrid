package ro.ppc.filegrid.security;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StorageType;

import java.time.Duration;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MeterKeysTest {

    private final MeterKeys keys = new MeterKeys(props(new byte[32]));

    @Test
    void fiecareContorAreCheieProprie() {
        assertThat(keys.derive("CT-000001")).isNotEqualTo(keys.derive("CT-000002"));
        assertThat(keys.derive("CT-000001")).isEqualTo(keys.derive("CT-000001"));
    }

    @Test
    void cheiaUnuiContorNuMergePentruAltul() {
        String key = keys.derive("CT-000001");

        assertThat(keys.matches("CT-000001", key)).isTrue();
        assertThat(keys.matches("CT-000002", key)).isFalse();
        assertThat(keys.matches("CT-000001", null)).isFalse();
        assertThat(keys.matches(null, key)).isFalse();
    }

    @Test
    void altaCheieMasterProduceAlteChei() {
        byte[] other = new byte[32];
        other[0] = 1;

        assertThat(new MeterKeys(props(other)).derive("CT-000001")).isNotEqualTo(keys.derive("CT-000001"));
    }

    private static FileGridProperties props(byte[] masterKey) {
        Map<DataCategory, CategoryPolicy> policies = new EnumMap<>(DataCategory.class);
        for (DataCategory category : DataCategory.values()) {
            policies.put(category, new CategoryPolicy(StorageType.S3, DataSize.ofMegabytes(10), 10,
                    false, false, false, Set.of("ADMIN"), Set.of("ADMIN")));
        }
        FileGridProperties.Security security = new FileGridProperties.Security(
                Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(masterKey),
                Duration.ofHours(8), true,
                Map.of("admin", new FileGridProperties.User("Admin", Set.of("ADMIN"), "parola")));
        return new FileGridProperties(policies, null, new FileGridProperties.Encryption(""), null, security);
    }
}
