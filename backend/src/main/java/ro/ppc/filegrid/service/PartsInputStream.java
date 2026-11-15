package ro.ppc.filegrid.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.IntFunction;

public class PartsInputStream extends InputStream {

    private final IntFunction<InputStream> opener;
    private final int partCount;
    private int nextPart = 1;
    private InputStream current;

    public PartsInputStream(IntFunction<InputStream> opener, int partCount) {
        this.opener = opener;
        this.partCount = partCount;
        this.current = opener.apply(nextPart++);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        while (current != null) {
            int n = current.read(buffer, offset, length);
            if (n >= 0) {
                return n;
            }
            advance();
        }
        return -1;
    }

    private void advance() throws IOException {
        current.close();
        current = null;
        if (nextPart <= partCount) {
            try {
                current = opener.apply(nextPart++);
            } catch (RuntimeException e) {
                throw new IOException("Partea " + (nextPart - 1) + " nu poate fi citita: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
