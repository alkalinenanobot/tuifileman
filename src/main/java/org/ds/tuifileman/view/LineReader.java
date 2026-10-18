package org.ds.tuifileman.view;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Splits a byte source into lines on demand, so any file size can be paged without an index. Lines are
 * ended by LF (a CR before it is dropped); a line without a terminator within {@link #CAP} bytes is cut
 * there so that huge single-line files stay navigable.
 */
final class LineReader {
    static final int CAP = 32 * 1024;

    /** A decoded line, the offset it starts at and the offset of the next line. */
    record Line(String text, long start, long next) { }

    private final ByteWindow window;
    private final Charset charset;
    private final boolean utf8;
    private final long dataStart;

    LineReader(ByteWindow window, Charset charset, long dataStart) {
        this.window = window;
        this.charset = charset;
        this.utf8 = StandardCharsets.UTF_8.equals(charset);
        this.dataStart = dataStart;
    }

    Charset charset() {
        return charset;
    }

    long dataStart() {
        return dataStart;
    }

    Line read(long pos) throws IOException {
        long size = window.size();
        long limit = Math.min(size, pos + CAP);
        long p = pos;
        while (p < limit && window.at(p) != '\n') {
            p++;
        }
        long next;
        boolean terminated = p < size && window.at(p) == '\n';
        if (terminated) {
            next = p + 1;
        } else if (p >= size) {
            next = size;
        } else {
            if (utf8) {
                while (p > pos + 1 && (window.at(p) & 0xC0) == 0x80) {
                    p--;
                }
            }
            next = p;
        }
        int end = (int) (p - pos);
        if (terminated && end > 0 && window.at(pos + end - 1) == '\r') {
            end--;
        }
        return new Line(new String(window.copy(pos, end), charset), pos, next);
    }

    /** Start of the line that ends right before {@code top}. */
    long previous(long top) throws IOException {
        if (top <= dataStart) {
            return dataStart;
        }
        long limit = Math.max(dataStart, top - CAP);
        long p = top - 1;
        if (window.at(p) == '\n') {
            p--;
        }
        while (p >= limit && window.at(p) != '\n') {
            p--;
        }
        long start = Math.max(p + 1, limit);
        if (utf8 && start == limit && start > dataStart) {
            while (start < top - 1 && (window.at(start) & 0xC0) == 0x80) {
                start++;
            }
        }
        return start;
    }

    /** Moves {@code lines} lines up from {@code from}, stopping at the start of the data. */
    long back(long from, int lines) throws IOException {
        long pos = from;
        for (int i = 0; i < lines && pos > dataStart; i++) {
            pos = previous(pos);
        }
        return pos;
    }

    /** Start of the line containing {@code offset}. */
    long lineStart(long offset) throws IOException {
        long pos = Math.max(dataStart, Math.min(offset, window.size()));
        if (pos <= dataStart) {
            return dataStart;
        }
        long limit = Math.max(dataStart, pos - CAP);
        long p = pos - 1;
        while (p >= limit && window.at(p) != '\n') {
            p--;
        }
        return Math.max(p + 1, limit);
    }
}
