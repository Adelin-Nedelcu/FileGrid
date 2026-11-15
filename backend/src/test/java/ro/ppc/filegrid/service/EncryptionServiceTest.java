package ro.ppc.filegrid.service;

import org.junit.jupiter.api.Test;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StorageType;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    private final EncryptionService encryption = new EncryptionService(props(Base64.getEncoder().encodeToString(new byte[32])));

    @Test
    void cripteazaSiDecripteazaIdentic() throws IOException {
        byte[] plain = "CNP 1900101123456 - cerere racordare".getBytes(StandardCharsets.UTF_8);
        byte[] iv = encryption.newIv();

        byte[] encrypted = encryption.encrypt(new ByteArrayInputStream(plain), iv).readAllBytes();
        byte[] decrypted = encryption.decrypt(new ByteArrayInputStream(encrypted), iv).readAllBytes();

        assertThat(encrypted).hasSize(plain.length + EncryptionService.TAG_BYTES);
        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1)).doesNotContain("1900101123456");
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void detecteazaContinutCriptatModificat() throws IOException {
        byte[] plain = new byte[10_000];
        byte[] iv = encryption.newIv();
        byte[] encrypted = encryption.encrypt(new ByteArrayInputStream(plain), iv).readAllBytes();
        byte[] tampered = Arrays.copyOf(encrypted, encrypted.length);
        tampered[42] ^= 1;

        assertThatThrownBy(() -> encryption.decrypt(new ByteArrayInputStream(tampered), iv).readAllBytes())
                .isInstanceOf(IOException.class)
                .hasRootCauseInstanceOf(javax.crypto.AEADBadTagException.class);
    }

    @Test
    void refuzaPornireaFaraCheieCandPoliticaCereCriptare() {
        assertThatThrownBy(() -> new EncryptionService(props("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILEGRID_ENCRYPTION_KEY");
    }

    private static FileGridProperties props(String key) {
        Map<DataCategory, CategoryPolicy> policies = new EnumMap<>(DataCategory.class);
        for (DataCategory c : DataCategory.values()) {
            policies.put(c, new CategoryPolicy(StorageType.S3, DataSize.ofMegabytes(10), 10,
                    c == DataCategory.CONNECTION_DOCUMENT, false, false, Set.of("ADMIN"), Set.of("ADMIN")));
        }
        return new FileGridProperties(policies, null, new FileGridProperties.Encryption(key), null, null);
    }

    @Test
    void fiecareParteAreIvPropriuSiSeDecripteazaDoarCuAlSau() throws IOException {
        byte[] plain = new byte[4096];
        byte[] base = encryption.newIv();
        byte[] iv1 = encryption.partIv(base, 1);
        byte[] iv2 = encryption.partIv(base, 2);

        byte[] encrypted = encryption.encrypt(new ByteArrayInputStream(plain), iv1).readAllBytes();

        assertThat(iv1).isNotEqualTo(iv2);
        assertThat(encryption.decrypt(new ByteArrayInputStream(encrypted), iv1).readAllBytes()).isEqualTo(plain);
        assertThatThrownBy(() -> encryption.decrypt(new ByteArrayInputStream(encrypted), iv2).readAllBytes())
                .isInstanceOf(IOException.class);
    }
}
