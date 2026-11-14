package ro.ppc.filegrid.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class VerifiedCopy {

    static final int BLOCK = 64 * 1024;

    private VerifiedCopy() {
    }

    public record Result(String sha256, long bytes) {
    }

    public static Result copy(InputStream in, OutputStream out, String expectedSha256) throws IOException {
        MessageDigest digest = HashingInputStream.sha256();
        byte[] current = new byte[BLOCK];
        byte[] next = new byte[BLOCK];
        long total = 0;

        int currentLen = in.readNBytes(current, 0, BLOCK);
        digest.update(current, 0, currentLen);
        total += currentLen;

        while (true) {
            int nextLen = in.readNBytes(next, 0, BLOCK);
            if (nextLen == 0) {
                break;
            }
            digest.update(next, 0, nextLen);
            total += nextLen;
            out.write(current, 0, currentLen);
            byte[] swap = current;
            current = next;
            next = swap;
            currentLen = nextLen;
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actual)) {
            throw new IntegrityException("SHA-256 nu corespunde: asteptat " + expectedSha256 + ", calculat " + actual);
        }
        out.write(current, 0, currentLen);
        out.flush();
        return new Result(actual, total);
    }
}
