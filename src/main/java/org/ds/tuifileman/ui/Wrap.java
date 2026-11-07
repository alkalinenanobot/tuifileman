package org.ds.tuifileman.ui;

import java.util.Arrays;

/**
 * Splits one display line into screen rows. A row breaks after the last space that still fits, as long as that
 * leaves the row at least half full; otherwise (a long path, a minified line) it breaks hard at the width, so a
 * row never holds more than {@code width} columns and no character is ever dropped. Rows after the first can be
 * indented (see {@link #hangingIndent}); they then have that many columns less.
 */
public final class Wrap {
    private Wrap() { }

    /** Start index of every row of {@code text}; the first is 0 and there is always at least one row. */
    public static int[] rows(String text, int width) {
        return rows(text, width, 0);
    }

    /**
     * Like {@link #rows(String, int)}, but every row after the first is drawn {@code indent} columns to the right
     * and holds that many characters less. The indent is limited to half the width, so a row always has room.
     */
    public static int[] rows(String text, int width, int indent) {
        int w = Math.max(1, width);
        int rest = w - shift(indent, w);
        int length = text.length();
        if (length <= w) {
            return new int[] {0};
        }
        int[] starts = new int[length / rest + 2];
        int count = 0;
        int start = 0;
        while (true) {
            starts[count++] = start;
            int cap = count == 1 ? w : rest;
            if (length - start <= cap) {
                return Arrays.copyOf(starts, count);
            }
            int end = start + cap;
            for (int i = end; i > start + cap / 2; i--) {
                if (text.charAt(i - 1) == ' ') {
                    end = i;
                    break;
                }
            }
            if (count == starts.length) {
                starts = Arrays.copyOf(starts, count * 2);
            }
            start = end;
        }
    }

    /** The columns a continuation row is really indented by at {@code width}: {@code indent}, at most half the width. */
    public static int shift(int indent, int width) {
        return Math.max(0, Math.min(indent, Math.max(1, width) / 2));
    }

    /** Number of rows of {@code text} at {@code width}. */
    public static int count(String text, int width) {
        return text.length() <= Math.max(1, width) ? 1 : rows(text, width).length;
    }

    /** Number of rows of {@code text} at {@code width} with the continuation rows indented by {@code indent}. */
    public static int count(String text, int width, int indent) {
        return text.length() <= Math.max(1, width) ? 1 : rows(text, width, indent).length;
    }

    /**
     * Columns a wrapped continuation of {@code text} lines up with: its leading spaces plus a list marker ({@code "- "},
     * {@code "* "}, {@code "+ "}, a bullet or {@code "12. "} / {@code "3) "}) and the spaces after it, so the text of
     * a list item wraps under its own first character. A line without either has no indent.
     */
    public static int hangingIndent(String text) {
        int length = text.length();
        int i = 0;
        while (i < length && text.charAt(i) == ' ') {
            i++;
        }
        int marker = i;
        if (i < length && "-*+\u2022".indexOf(text.charAt(i)) >= 0) {
            marker = i + 1;
        } else {
            int digits = i;
            while (digits < length && digits - i < 9 && Character.isDigit(text.charAt(digits))) {
                digits++;
            }
            if (digits > i && digits < length && (text.charAt(digits) == '.' || text.charAt(digits) == ')')) {
                marker = digits + 1;
            }
        }
        if (marker > i && marker < length && text.charAt(marker) == ' ') {
            while (marker < length && text.charAt(marker) == ' ') {
                marker++;
            }
            return marker;
        }
        return i;
    }

    /** End index (exclusive) of row {@code row}. */
    public static int end(int[] starts, int row, int length) {
        return row + 1 < starts.length ? starts[row + 1] : length;
    }

    /** The row that holds character {@code index}. */
    public static int rowOf(int[] starts, int index) {
        int row = Arrays.binarySearch(starts, index);
        return row >= 0 ? row : -row - 2;
    }
}
