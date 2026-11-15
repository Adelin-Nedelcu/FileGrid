package ro.ppc.filegrid.service;

import java.io.IOException;
import java.io.OutputStream;

final class WindowOutputStream extends OutputStream {

    private final OutputStream delegate;
    private long skip;
    private long remaining;

    WindowOutputStream(OutputStream delegate, long skip, long length) {
        this.delegate = delegate;
        this.skip = skip;
        this.remaining = length;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (skip > 0) {
            int skipped = (int) Math.min(skip, length);
            skip -= skipped;
            offset += skipped;
            length -= skipped;
        }
        int allowed = (int) Math.min(length, remaining);
        if (allowed > 0) {
            delegate.write(buffer, offset, allowed);
            remaining -= allowed;
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }
}
