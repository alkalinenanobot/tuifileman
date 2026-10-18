package org.ds.tuifileman.view;

import java.util.Arrays;

/**
 * Splits one display line into screen rows. A row breaks after the last space that still fits, as long as that
 * leaves the row at least half full; otherwise (a long path, a minified line) it breaks hard at the width, so a
 * row never holds more than {@code width} columns and no character is ever dropped.
 */
final class Wrap {
    private Wrap() { }

    /** Start index of every row of {@code text}; the first is 0 and there is always at least one row. */
    static int[] rows(String text, int width) {
        int w = Math.max(1, width);
        int length = text.length();
        if (length <= w) {
            return new int[] {0};
        }
        int[] starts = new int[length / w + 2];
        int count = 0;
        int start = 0;
        while (true) {
            starts[count++] = start;
            if (length - start <= w) {
                return Arrays.copyOf(starts, count);
            }
            int end = start + w;
            for (int i = end; i > start + w / 2; i--) {
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

    /** Number of rows of {@code text} at {@code width}. */
    static int count(String text, int width) {
        return text.length() <= Math.max(1, width) ? 1 : rows(text, width).length;
    }

    /** End index (exclusive) of row {@code row}. */
    static int end(int[] starts, int row, int length) {
        return row + 1 < starts.length ? starts[row + 1] : length;
    }

    /** The row that holds character {@code index}. */
    static int rowOf(int[] starts, int index) {
        int row = Arrays.binarySearch(starts, index);
        return row >= 0 ? row : -row - 2;
    }
}
