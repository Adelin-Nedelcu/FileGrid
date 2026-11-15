package ro.ppc.filegrid.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class HashingInputStream extends FilterInputStream {

    private final MessageDigest digest;
    private long count;
    private boolean sourceFailed;

    public HashingInputStream(InputStream in) {
        super(in);
        this.digest = sha256();
    }

    @Override
    public int read() throws IOException {
        int b;
        try {
            b = super.read();
        } catch (IOException e) {
            sourceFailed = true;
            throw e;
        }
        if (b >= 0) {
            digest.update((byte) b);
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n;
        try {
            n = super.read(buf, off, len);
        } catch (IOException e) {
            sourceFailed = true;
            throw e;
        }
        if (n > 0) {
            digest.update(buf, off, n);
            count += n;
        }
        return n;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    public long count() {
        return count;
    }

    public boolean sourceFailed() {
        return sourceFailed;
    }

    public String sha256Hex() {
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
