package ro.ppc.filegrid.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ByteRange(long start, long end) {

    private static final Pattern SINGLE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    public long length() {
        return end - start + 1;
    }

    public static ByteRange parse(String header, long size) {
        if (header == null) {
            return null;
        }
        Matcher m = SINGLE.matcher(header.trim());
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
            return null;
        }
        try {
            if (m.group(1).isEmpty()) {
                long suffix = Long.parseLong(m.group(2));
                if (suffix == 0 || size == 0) {
                    throw new Unsatisfiable(size);
                }
                return new ByteRange(Math.max(0, size - suffix), size - 1);
            }
            long start = Long.parseLong(m.group(1));
            long end = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
            if (start >= size || end < start) {
                throw new Unsatisfiable(size);
            }
            return new ByteRange(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static class Unsatisfiable extends RuntimeException {
        private final long size;

        Unsatisfiable(long size) {
            super("Interval in afara fisierului");
            this.size = size;
        }

        public long size() {
            return size;
        }
    }
}
