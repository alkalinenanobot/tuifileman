package org.ds.tuifileman.view;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** One cached 256 KB block over a {@link ByteSource}, with byte access and search. */
final class ByteWindow {
    static final long CANCELLED = Long.MIN_VALUE;
    private static final int BLOCK = 256 * 1024;
    private static final int CANCEL_CHECK_MASK = 0x3FFFFF;

    private final ByteSource source;
    private final long size;
    private final byte[] data = new byte[BLOCK];
    private long start = -1;
    private int length;

    ByteWindow(ByteSource source) {
        this.source = source;
        this.size = source.size();
    }

    long size() {
        return size;
    }

    /** The unsigned byte at {@code pos}, or -1 outside the data. */
    int at(long pos) throws IOException {
        if (pos < 0 || pos >= size) {
            return -1;
        }
        if (pos < start || pos >= start + length) {
            start = pos - pos % BLOCK;
            length = source.read(start, data, 0, (int) Math.min(BLOCK, size - start));
            if (length <= 0) {
                throw new IOException("read failed at offset " + start);
            }
        }
        return data[(int) (pos - start)] & 0xFF;
    }

    /** Number of {@code value} bytes in {@code [from, to)}. */
    long count(long from, long to, byte value) throws IOException {
        long n = 0;
        long pos = Math.max(0, from);
        long end = Math.min(to, size);
        while (pos < end) {
            at(pos);
            int i = (int) (pos - start);
            int stop = (int) Math.min(length, end - start);
            for (; i < stop; i++) {
                if (data[i] == value) {
                    n++;
                }
            }
            pos = start + stop;
        }
        return n;
    }

    byte[] copy(long from, int count) throws IOException {
        long available = Math.max(0, Math.min(count, size - from));
        byte[] out = new byte[(int) available];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) at(from + i);
        }
        return out;
    }

    private static int fold(int b) {
        return b >= 'A' && b <= 'Z' ? b + 32 : b;
    }

    private boolean matches(long pos, int[] folded) throws IOException {
        for (int j = 0; j < folded.length; j++) {
            if (fold(at(pos + j)) != folded[j]) {
                return false;
            }
        }
        return true;
    }

    private static int[] fold(byte[] needle) {
        int[] out = new int[needle.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = fold(needle[i] & 0xFF);
        }
        return out;
    }

    /**
     * First match starting in [from, end - needle.length], ignoring ASCII case; -1 when there is none or
     * {@link #CANCELLED} when the cancel check fired.
     */
    long findForward(long from, long end, byte[] needle, BooleanSupplier cancelled) throws IOException {
        int[] folded = fold(needle);
        long last = Math.min(end, size) - folded.length;
        long steps = 0;
        for (long p = Math.max(0, from); p <= last; p++) {
            if ((++steps & CANCEL_CHECK_MASK) == 0 && cancelled.getAsBoolean()) {
                return CANCELLED;
            }
            if (fold(at(p)) == folded[0] && matches(p, folded)) {
                return p;
            }
        }
        return -1;
    }

    /** Last match starting in [begin, from], ignoring ASCII case; -1 when there is none or cancelled. */
    long findBackward(long from, long begin, byte[] needle, BooleanSupplier cancelled) throws IOException {
        int[] folded = fold(needle);
        long steps = 0;
        for (long p = Math.min(from, size - folded.length); p >= Math.max(0, begin); p--) {
            if ((++steps & CANCEL_CHECK_MASK) == 0 && cancelled.getAsBoolean()) {
                return CANCELLED;
            }
            if (fold(at(p)) == folded[0] && matches(p, folded)) {
                return p;
            }
        }
        return -1;
    }
}
