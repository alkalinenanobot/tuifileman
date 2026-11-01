package org.ds.tuifileman.view;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Builds the styled plain text of a rendered HTML or Markdown document. Inline text is collapsed to single spaces,
 * blocks are separated by a requested number of line breaks (1 = new line, 2 = blank line, never more), and every
 * line starts with the current indent (block quotes, list items, code). Nothing is written for a break until text
 * follows, so a document never starts or ends with blank lines. Every character carries a {@link TextStyle} byte.
 */
final class TextOut {

    /** The finished text and one {@link TextStyle} byte per character. */
    record Result(String text, byte[] styles) {
        static final Result EMPTY = new Result("", new byte[0]);

        boolean isEmpty() {
            return text.isEmpty();
        }

        /** One style byte per UTF-8 byte of the text, the way the viewer reads its converted text. */
        byte[] byteStyles() {
            int size = 0;
            for (int i = 0; i < text.length(); i++) {
                size += utf8Length(text, i);
            }
            byte[] out = new byte[size];
            int at = 0;
            for (int i = 0; i < text.length(); i++) {
                int n = utf8Length(text, i);
                Arrays.fill(out, at, at + n, styles[i]);
                at += n;
            }
            return out;
        }

        /** The same text on one line: each run of line feeds becomes one space (a table cell), styles kept. */
        Result oneLine() {
            String trimmed = text.stripTrailing();
            StringBuilder sb = new StringBuilder(trimmed.length());
            byte[] out = new byte[trimmed.length()];
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '\n') {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                        out[sb.length()] = 0;
                        sb.append(' ');
                    }
                    continue;
                }
                out[sb.length()] = styles[i];
                sb.append(c);
            }
            return new Result(sb.toString(), Arrays.copyOf(out, sb.length()));
        }

        /** UTF-8 length of the character at {@code i}; a low surrogate counts 0 (the pair is counted at the high one). */
        static int utf8Length(String text, int i) {
            char c = text.charAt(i);
            if (c < 0x80) {
                return 1;
            }
            if (c < 0x800) {
                return 2;
            }
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                return 4;
            }
            return Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(text.charAt(i - 1)) ? 0 : 3;
        }
    }

    private final StringBuilder sb = new StringBuilder();
    private byte[] styles = new byte[256];
    private final Deque<String> indents = new ArrayDeque<>();
    private final Deque<Byte> styleStack = new ArrayDeque<>();
    private String indent = "";
    private String bullet;
    private byte style;
    private byte lastStyle;
    private boolean lineStart = true;
    private boolean pendingSpace;
    private int pendingBreaks;
    private int lineBegin;
    private int leadLength;

    /** Inline text: runs of whitespace become one space; leading space at a line start is dropped. */
    void text(String s) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (Character.isWhitespace(s.charAt(i))) {
                pendingSpace = true;
                i++;
                continue;
            }
            int j = i;
            while (j < n && !Character.isWhitespace(s.charAt(j))) {
                j++;
            }
            begin();
            put(s, i, j, style);
            lastStyle = style;
            i = j;
        }
    }

    /** Preformatted text (code blocks): line breaks and spaces are kept, tabs become four spaces. */
    void literal(String s) {
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1);
        int end = lines.length;
        if (end > 1 && lines[end - 1].isEmpty()) {
            end--;
        }
        for (int k = 0; k < end; k++) {
            if (k > 0) {
                pendingBreaks++;
            }
            if (!lines[k].isEmpty()) {
                begin();
                put(lines[k], 0, lines[k].length(), style);
                lastStyle = style;
            }
        }
    }

    /** Text that already carries its own styles (a table row), written as it is on the current line. */
    void styled(Result piece) {
        if (piece.isEmpty()) {
            return;
        }
        begin();
        int at = sb.length();
        put(piece.text(), 0, piece.text().length(), (byte) 0);
        System.arraycopy(piece.styles(), 0, styles, at, piece.text().length());
        lastStyle = piece.styles()[piece.styles().length - 1];
    }

    /** Asks for at least {@code n} line breaks before the next text. */
    void breaks(int n) {
        if (sb.length() > 0 || bullet != null) {
            pendingBreaks = Math.max(pendingBreaks, Math.min(n, 2));
        }
    }

    /** A forced line break inside a paragraph (a second one in a row gives a blank line). */
    void br() {
        if (sb.length() > 0) {
            pendingBreaks = Math.min(2, pendingBreaks + 1);
        }
    }

    void space() {
        pendingSpace = true;
    }

    /** Text written from now until {@link #popStyle()} gets {@code add}: a colour role and/or flags of {@link TextStyle}. */
    void pushStyle(int add) {
        styleStack.push(style);
        style = TextStyle.combine(style, add);
    }

    void popStyle() {
        style = styleStack.pop();
    }

    /** Starts a list item: the next line begins with the indent plus {@code marker} instead of the plain indent. */
    void bullet(String marker) {
        bullet = indent + marker;
    }

    /** Writes a marker nobody followed with text (an empty list item). */
    void flushBullet() {
        if (bullet != null) {
            begin();
        }
    }

    void pushIndent(String add) {
        indents.push(indent);
        indent = indent + add;
    }

    void popIndent() {
        indent = indents.pop();
    }

    /** Characters written on the current line after its indent. */
    int lineWidth() {
        return lineStart ? 0 : sb.length() - lineBegin - leadLength;
    }

    /** The finished text, one line feed at the end; empty when nothing was written. */
    Result result() {
        int end = sb.length();
        while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return Result.EMPTY;
        }
        byte[] out = Arrays.copyOf(styles, end + 1);
        out[end] = 0;
        return new Result(sb.substring(0, end) + "\n", out);
    }

    private void begin() {
        if (pendingBreaks > 0 && sb.length() > 0) {
            int end = sb.length();
            while (end > 0 && sb.charAt(end - 1) == ' ') {
                end--;
            }
            sb.setLength(end);
            for (int i = 0; i < pendingBreaks; i++) {
                put("\n", 0, 1, (byte) 0);
            }
            lineStart = true;
        }
        pendingBreaks = 0;
        if (lineStart) {
            String lead = bullet != null ? bullet : indent;
            bullet = null;
            lineBegin = sb.length();
            put(lead, 0, lead.length(), (byte) 0);
            leadLength = lead.length();
            lineStart = false;
        } else if (pendingSpace) {
            // A space inside a run of one style (a struck-through phrase) carries that style, any other is plain.
            put(" ", 0, 1, lastStyle == style ? style : (byte) 0);
        }
        pendingSpace = false;
    }

    private void put(CharSequence s, int from, int to, byte st) {
        int at = sb.length();
        sb.append(s, from, to);
        if (styles.length < sb.length()) {
            styles = Arrays.copyOf(styles, Math.max(sb.length() + 1, styles.length * 2));
        }
        Arrays.fill(styles, at, sb.length(), st);
    }
}
