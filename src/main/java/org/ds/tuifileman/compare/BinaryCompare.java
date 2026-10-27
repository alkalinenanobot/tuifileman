package org.ds.tuifileman.compare;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Byte-for-byte comparison of two files of any size; positions past the shorter file differ. */
public final class BinaryCompare implements Closeable {
    private static final int CHUNK = 1 << 16;

    private final FileChannel left;
    private final FileChannel right;
    private final long leftSize;
    private final long rightSize;
    private final ByteBuffer bufA = ByteBuffer.allocate(CHUNK);
    private final ByteBuffer bufB = ByteBuffer.allocate(CHUNK);

    public BinaryCompare(Path leftPath, Path rightPath) throws IOException {
        FileChannel l = FileChannel.open(leftPath, StandardOpenOption.READ);
        FileChannel r;
        try {
            r = FileChannel.open(rightPath, StandardOpenOption.READ);
        } catch (IOException e) {
            l.close();
            throw e;
        }
        left = l;
        right = r;
        leftSize = l.size();
        rightSize = r.size();
    }

    public long leftSize() {
        return leftSize;
    }

    public long rightSize() {
        return rightSize;
    }

    public long maxSize() {
        return Math.max(leftSize, rightSize);
    }

    /** Reads up to {@code length} bytes of one side at {@code offset}; returns the count read. */
    public int read(boolean leftSide, long offset, byte[] target, int length) throws IOException {
        long size = leftSide ? leftSize : rightSize;
        if (offset >= size) {
            return 0;
        }
        int want = (int) Math.min(length, size - offset);
        ByteBuffer buffer = ByteBuffer.wrap(target, 0, want);
        FileChannel channel = leftSide ? left : right;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, offset + buffer.position());
            if (n < 0) {
                break;
            }
        }
        return buffer.position();
    }

    /** First offset at or after {@code from} whose "differs" state equals {@code different}; -1 if none. */
    public long find(long from, boolean different) throws IOException {
        long min = Math.min(leftSize, rightSize);
        long pos = Math.max(0, from);
        while (pos < min) {
            int len = (int) Math.min(CHUNK, min - pos);
            fill(pos, len);
            byte[] a = bufA.array();
            byte[] b = bufB.array();
            for (int i = 0; i < len; i++) {
                if ((a[i] != b[i]) == different) {
                    return pos + i;
                }
            }
            pos += len;
        }
        return different && pos < maxSize() ? pos : -1;
    }

    /** Last offset at or before {@code from} whose "differs" state equals {@code different}; -1 if none. */
    public long findBack(long from, boolean different) throws IOException {
        long min = Math.min(leftSize, rightSize);
        long pos = Math.min(from, maxSize() - 1);
        if (pos >= min) {
            if (different) {
                return pos;
            }
            pos = min - 1;
        }
        while (pos >= 0) {
            long start = Math.max(0, pos - CHUNK + 1);
            int len = (int) (pos - start + 1);
            fill(start, len);
            byte[] a = bufA.array();
            byte[] b = bufB.array();
            for (int i = len - 1; i >= 0; i--) {
                if ((a[i] != b[i]) == different) {
                    return start + i;
                }
            }
            pos = start - 1;
        }
        return -1;
    }

    /** Offset just past the differing run that contains (or follows) {@code offset}. */
    public long runEnd(long offset) throws IOException {
        long same = find(offset, false);
        return same < 0 ? maxSize() : same;
    }

    /** First offset of the differing run containing {@code offset}. */
    public long runStart(long offset) throws IOException {
        return findBack(offset, false) + 1;
    }

    private void fill(long pos, int len) throws IOException {
        read(true, pos, bufA.array(), len);
        read(false, pos, bufB.array(), len);
    }

    @Override
    public void close() throws IOException {
        try {
            left.close();
        } finally {
            right.close();
        }
    }
}
