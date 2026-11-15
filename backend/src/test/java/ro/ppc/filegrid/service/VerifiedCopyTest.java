package ro.ppc.filegrid.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedCopyTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, VerifiedCopy.BLOCK - 1, VerifiedCopy.BLOCK, VerifiedCopy.BLOCK + 1, 3 * VerifiedCopy.BLOCK + 17})
    void copiazaIdenticSiCalculeazaHashul(int size) throws Exception {
        byte[] data = randomBytes(size);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        VerifiedCopy.Result result = VerifiedCopy.copy(new ByteArrayInputStream(data), out, sha256(data));

        assertThat(out.toByteArray()).isEqualTo(data);
        assertThat(result.bytes()).isEqualTo(size);
        assertThat(result.sha256()).isEqualTo(sha256(data));
    }

    @Test
    void fisierCoruptNuAjungeNiciodataCompletLaClient() {
        byte[] original = randomBytes(5 * VerifiedCopy.BLOCK);
        byte[] corupt = original.clone();
        corupt[123_456] ^= 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> VerifiedCopy.copy(new ByteArrayInputStream(corupt), out, sha256(original)))
                .isInstanceOf(IntegrityException.class);
        assertThat(out.size()).isLessThan(original.length);
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
