package org.ds.tuifileman.view;

import java.util.Arrays;
import java.util.List;
import org.ds.tuifileman.ui.TextStyle;

/**
 * Lays a table out as aligned text columns: cells joined by {@code │}, the header in bold followed by a
 * {@code ─┼─} rule. Every cell is one line of styled text; columns are as wide as their widest cell.
 */
final class TableLayout {
    enum Align { LEFT, CENTER, RIGHT }

    private static final String SEPARATOR = " \u2502 ";
    private static final String RULE_SEPARATOR = "\u2500\u253C\u2500";

    private TableLayout() { }

    /**
     * Writes the table into {@code out} as a block of its own. {@code headerRows} leading rows are bold and the
     * last of them is followed by a rule; {@code aligns} may be shorter than the widest row (the rest is left).
     */
    static void write(TextOut out, List<List<TextOut.Result>> rows, int headerRows, List<Align> aligns) {
        int columns = 0;
        for (List<TextOut.Result> row : rows) {
            columns = Math.max(columns, row.size());
        }
        if (columns == 0) {
            return;
        }
        int[] widths = new int[columns];
        for (List<TextOut.Result> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], row.get(c).text().length());
            }
        }
        out.breaks(2);
        for (int r = 0; r < rows.size(); r++) {
            out.breaks(1);
            out.styled(line(rows.get(r), widths, aligns, r < headerRows));
            if (headerRows > 0 && r == headerRows - 1) {
                out.breaks(1);
                out.styled(rule(widths));
            }
        }
        out.breaks(2);
    }

    private static TextOut.Result line(List<TextOut.Result> row, int[] widths, List<Align> aligns, boolean header) {
        StringBuilder text = new StringBuilder();
        byte[] styles = new byte[0];
        // Empty cells at the end (what a colspan leaves behind) are not drawn, so no line ends in a bare separator.
        int last = row.size() - 1;
        while (last > 0 && row.get(last).isEmpty()) {
            last--;
        }
        last = Math.min(last, widths.length - 1);
        for (int c = 0; c <= last; c++) {
            TextOut.Result cell = c < row.size() ? row.get(c) : TextOut.Result.EMPTY;
            Align align = c < aligns.size() && aligns.get(c) != null ? aligns.get(c) : Align.LEFT;
            int gap = widths[c] - cell.text().length();
            int before = align == Align.RIGHT ? gap : align == Align.CENTER ? gap / 2 : 0;
            // Nothing after the last drawn column: lines never end in padding.
            int after = c == last ? 0 : gap - before;
            if (c > 0) {
                styles = append(styles, text, SEPARATOR, (byte) TextStyle.MUTED);
            }
            styles = append(styles, text, " ".repeat(before), (byte) 0);
            byte[] cellStyles = cell.styles();
            if (header) {
                cellStyles = Arrays.copyOf(cellStyles, cellStyles.length);
                for (int i = 0; i < cellStyles.length; i++) {
                    cellStyles[i] = TextStyle.combine(cellStyles[i], TextStyle.BOLD);
                }
            }
            int from = styles.length;
            styles = Arrays.copyOf(styles, from + cell.text().length());
            System.arraycopy(cellStyles, 0, styles, from, cell.text().length());
            text.append(cell.text());
            styles = append(styles, text, " ".repeat(after), (byte) 0);
        }
        return new TextOut.Result(text.toString(), styles);
    }

    private static TextOut.Result rule(int[] widths) {
        StringBuilder text = new StringBuilder();
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) {
                text.append(RULE_SEPARATOR);
            }
            text.append("\u2500".repeat(Math.max(1, widths[c])));
        }
        byte[] styles = new byte[text.length()];
        Arrays.fill(styles, (byte) TextStyle.MUTED);
        return new TextOut.Result(text.toString(), styles);
    }

    private static byte[] append(byte[] styles, StringBuilder text, String s, byte style) {
        int from = styles.length;
        text.append(s);
        styles = Arrays.copyOf(styles, from + s.length());
        Arrays.fill(styles, from, styles.length, style);
        return styles;
    }
}
