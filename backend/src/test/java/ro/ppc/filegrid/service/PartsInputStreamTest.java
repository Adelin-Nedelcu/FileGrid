package ro.ppc.filegrid.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartsInputStreamTest {

    @Test
    void concateneazaPartileInOrdine() throws IOException {
        byte[][] parts = {{1, 2, 3}, {4, 5}, {6}};

        try (InputStream in = new PartsInputStream(n -> new ByteArrayInputStream(parts[n - 1]), 3)) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3, 4, 5, 6);
        }
    }

    @Test
    void deschidePartileDoarCandAjungeLaEle() throws IOException {
        List<Integer> opened = new ArrayList<>();

        try (InputStream in = new PartsInputStream(n -> {
            opened.add(n);
            return new ByteArrayInputStream(new byte[]{(byte) n});
        }, 5)) {
            assertThat(in.read()).isEqualTo(1);
        }

        assertThat(opened).containsExactly(1);
    }

    @Test
    void parteLipsaInMijloculCitiriiDevineIOException() {
        assertThatThrownBy(() -> {
            try (InputStream in = new PartsInputStream(n -> {
                if (n == 2) {
                    throw new IllegalStateException("lipsa");
                }
                return new ByteArrayInputStream(new byte[]{7});
            }, 2)) {
                in.readAllBytes();
            }
        }).isInstanceOf(IOException.class).hasMessageContaining("Partea 2");
    }
}
