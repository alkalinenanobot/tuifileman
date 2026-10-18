package org.ds.tuifileman.view;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/** Random-access read source: a file on disk or a byte array in memory. */
interface ByteSource extends Closeable {
    long size();

    /** Reads up to {@code len} bytes at {@code pos}; returns the number read (0 at the end). */
    int read(long pos, byte[] dst, int off, int len) throws IOException;

    static ByteSource open(Path path) throws IOException {
        java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ);
        long size = channel.size();
        return new ByteSource() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public int read(long pos, byte[] dst, int off, int len) throws IOException {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(dst, off, len);
                int total = 0;
                while (buffer.hasRemaining()) {
                    int n = channel.read(buffer, pos + total);
                    if (n <= 0) {
                        break;
                    }
                    total += n;
                }
                return total;
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    static ByteSource of(byte[] bytes) {
        return new ByteSource() {
            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public int read(long pos, byte[] dst, int off, int len) {
                int n = (int) Math.max(0, Math.min(len, bytes.length - pos));
                System.arraycopy(bytes, (int) pos, dst, off, n);
                return n;
            }

            @Override
            public void close() { }
        };
    }
}
