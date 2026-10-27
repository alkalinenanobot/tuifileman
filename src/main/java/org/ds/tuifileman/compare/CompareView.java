package org.ds.tuifileman.compare;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.screen.Screen;
import org.ds.tuifileman.model.FileEntry;
import org.ds.tuifileman.ui.Theme;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Full-screen, read-only side-by-side compare of two files with its own input loop: a line diff for
 * text and a hex view for binary files (text files can be switched to hex with B).
 */
public final class CompareView {
    private final Screen screen;
    private final Theme theme;
    private final Path leftPath;
    private final Path rightPath;

    private TextCompare text;
    private BinaryCompare binary;
    private boolean textAllowed;
    private boolean textMode;
    private boolean ignoreWhitespace;
    private boolean ignoreCase;
    private String message = "";

    private int top;
    private int hscroll;
    private int curHunk = -1;

    private long binTop;
    private long markStart = -1;
    private long markEnd;
    private int bytesPerRow = 16;

    private CompareView(Screen screen, Theme theme, Path leftPath, Path rightPath) {
        this.screen = screen;
        this.theme = theme;
        this.leftPath = leftPath;
        this.rightPath = rightPath;
    }

    /** Compares the files; returns a status message for the caller. */
    public static String show(Screen screen, Theme theme, Path left, Path right) {
        try {
            long mismatch = Files.mismatch(left, right);
            if (mismatch < 0) {
                return "Files are identical (" + FileEntry.humanSize(Files.size(left)) + ")";
            }
            CompareView view = new CompareView(screen, theme, left, right);
            try {
                view.run();
            } finally {
                view.close();
            }
            return "Compared " + left.getFileName() + " with " + right.getFileName();
        } catch (IOException | RuntimeException e) {
            return "Compare failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void close() {
        if (binary != null) {
            try {
                binary.close();
            } catch (IOException ignored) { }
        }
    }

    private void run() throws IOException {
        textAllowed = TextCompare.bothText(leftPath, rightPath);
        if (textAllowed) {
            text = TextCompare.load(leftPath, rightPath);
            text.compute(false, false);
            textMode = true;
            if (text.hunkStarts().length > 0) {
                jumpHunk(0);
            }
        } else {
            openBinary();
        }
        while (true) {
            draw();
            KeyStroke key = nextKey();
            message = "";
            if (!handle(key)) {
                return;
            }
        }
    }

    private KeyStroke nextKey() {
        while (true) {
            try {
                var resized = screen.doResizeIfNecessary();
                KeyStroke key = screen.pollInput();
                if (key != null) {
                    return key;
                }
                if (resized != null) {
                    draw();
                } else {
                    Thread.sleep(25);
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new KeyStroke(KeyType.Escape);
            }
        }
    }

    /** Returns false to close the view. */
    private boolean handle(KeyStroke key) throws IOException {
        int h = bodyHeight();
        if (key.getKeyType() == KeyType.EOF || key.getKeyType() == KeyType.Escape) {
            return false;
        }
        if (key instanceof MouseAction mouse) {
            switch (mouse.getActionType()) {
                case SCROLL_UP -> scroll(-3, h);
                case SCROLL_DOWN -> scroll(3, h);
                default -> { }
            }
            return true;
        }
        switch (key.getKeyType()) {
            case ArrowUp -> scroll(-1, h);
            case ArrowDown -> scroll(1, h);
            case PageUp -> scroll(-h + 1, h);
            case PageDown -> scroll(h - 1, h);
            case Home -> scrollTo(0, h);
            case End -> scrollTo(Long.MAX_VALUE / 4, h);
            case ArrowLeft -> hscroll = Math.max(0, hscroll - 8);
            case ArrowRight -> hscroll += 8;
            case F10 -> {
                return false;
            }
            case Character -> {
                if (key.isCtrlDown() || key.isAltDown()) {
                    return true;
                }
                switch (Character.toLowerCase(key.getCharacter())) {
                    case 'q' -> {
                        return false;
                    }
                    case 'n' -> step(1, h);
                    case 'p' -> step(-1, h);
                    case 'w' -> toggleOption(true);
                    case 'c' -> toggleOption(false);
                    case 'b' -> switchMode(false);
                    case 't' -> switchMode(true);
                    default -> { }
                }
            }
            default -> { }
        }
        return true;
    }

    private void scroll(long delta, int h) throws IOException {
        if (textMode) {
            top = (int) Math.max(0, Math.min((long) top + delta, Math.max(0, text.rows().size() - h)));
        } else {
            binTop += delta * bytesPerRow;
            clampBinary(h);
        }
    }

    private void scrollTo(long position, int h) throws IOException {
        if (textMode) {
            top = (int) Math.max(0, Math.min(position, Math.max(0, text.rows().size() - h)));
        } else {
            binTop = position == 0 ? 0 : Long.MAX_VALUE / 4;
            clampBinary(h);
        }
    }

    private void clampBinary(int h) {
        long lastRow = Math.max(0, (binary.maxSize() - 1) / bytesPerRow);
        long maxTop = Math.max(0, lastRow - (h - 1)) * bytesPerRow;
        binTop = Math.max(0, Math.min(binTop / bytesPerRow * bytesPerRow, maxTop));
    }

    private void step(int direction, int h) throws IOException {
        if (textMode) {
            stepHunk(direction, h);
        } else {
            stepBinary(direction, h);
        }
    }

    private void stepHunk(int direction, int h) {
        int[] starts = text.hunkStarts();
        if (starts.length == 0) {
            message = "No differences";
            return;
        }
        boolean currentVisible = curHunk >= 0 && curHunk < starts.length
                && starts[curHunk] >= top && starts[curHunk] < top + h;
        int target = -1;
        if (currentVisible) {
            target = curHunk + direction;
        } else if (direction > 0) {
            for (int i = 0; i < starts.length; i++) {
                if (starts[i] >= top) {
                    target = i;
                    break;
                }
            }
        } else {
            for (int i = starts.length - 1; i >= 0; i--) {
                if (starts[i] < top) {
                    target = i;
                    break;
                }
            }
        }
        if (target < 0 || target >= starts.length) {
            message = direction > 0 ? "No more differences below" : "No more differences above";
            return;
        }
        jumpHunk(target);
    }

    private void jumpHunk(int index) {
        curHunk = index;
        int h = bodyHeight();
        int start = text.hunkStarts()[index];
        top = Math.max(0, Math.min(start - h / 3, Math.max(0, text.rows().size() - h)));
    }

    private void stepBinary(int direction, int h) throws IOException {
        long found;
        if (markStart < 0) {
            found = direction > 0 ? binary.find(binTop, true) : binary.findBack(binTop, true);
        } else if (direction > 0) {
            found = markEnd >= binary.maxSize() ? -1 : binary.find(markEnd, true);
        } else {
            found = markStart == 0 ? -1 : binary.findBack(markStart - 1, true);
            if (found >= 0) {
                found = binary.runStart(found);
            }
        }
        if (found < 0) {
            message = direction > 0 ? "No more differences below" : "No more differences above";
            return;
        }
        jumpBinary(found);
    }

    private void jumpBinary(long offset) throws IOException {
        markStart = offset;
        markEnd = binary.runEnd(offset);
        binTop = offset - (long) (bodyHeight() / 3) * bytesPerRow;
        clampBinary(bodyHeight());
    }

    private void toggleOption(boolean whitespace) {
        if (!textMode) {
            return;
        }
        int keep = text.rows().isEmpty() || top >= text.rows().size() ? 0 : anchorLine(top);
        if (whitespace) {
            ignoreWhitespace = !ignoreWhitespace;
        } else {
            ignoreCase = !ignoreCase;
        }
        text.compute(ignoreWhitespace, ignoreCase);
        top = 0;
        List<TextCompare.Row> rows = text.rows();
        for (int i = 0; i < rows.size(); i++) {
            int left = rows.get(i).left();
            if (left >= keep) {
                top = i;
                break;
            }
        }
        curHunk = -1;
        message = (whitespace ? "Ignore whitespace: " + onOff(ignoreWhitespace)
                : "Ignore case: " + onOff(ignoreCase))
                + (text.hunkStarts().length == 0 ? " - no differences" : "");
    }

    private int anchorLine(int row) {
        for (int i = row; i < text.rows().size(); i++) {
            if (text.rows().get(i).left() >= 0) {
                return text.rows().get(i).left();
            }
        }
        return text.leftCount();
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private void switchMode(boolean toText) throws IOException {
        if (toText == textMode) {
            return;
        }
        if (toText) {
            if (!textAllowed) {
                message = "Not text files";
                return;
            }
            textMode = true;
            return;
        }
        openBinary();
        textMode = false;
    }

    private void openBinary() throws IOException {
        if (binary == null) {
            binary = new BinaryCompare(leftPath, rightPath);
            long first = binary.find(0, true);
            if (first >= 0) {
                jumpBinary(first);
            }
        }
        textMode = false;
    }

    private int bodyHeight() {
        return Math.max(1, screen.getTerminalSize().getRows() - 3);
    }

    // ---- drawing ----

    private void draw() {
        TextGraphics g = screen.newTextGraphics();
        int w = g.getSize().getColumns();
        int h = g.getSize().getRows();
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.text());
        g.fill(' ');
        if (w >= 40 && h >= 6) {
            try {
                if (textMode) {
                    drawText(g, w, h);
                } else {
                    drawBinary(g, w, h);
                }
            } catch (IOException e) {
                message = "Read error: " + e.getMessage();
                cell(g, 0, h - 2, w, " " + message, theme.error(), theme.appBackground());
            }
        } else {
            cell(g, 0, 0, w, "Window too small", theme.accent(), theme.appBackground());
        }
        screen.setCursorPosition(null);
        try {
            screen.refresh();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void drawText(TextGraphics g, int w, int h) {
        int body = h - 3;
        List<TextCompare.Row> rows = text.rows();
        top = Math.max(0, Math.min(top, Math.max(0, rows.size() - body)));
        int sideW = (w - 1) / 2;
        int rightX = sideW + 1;
        int rightW = w - rightX;
        int digits = Math.max(3, Integer.toString(Math.max(text.leftCount(), text.rightCount())).length());

        header(g, 0, sideW, leftPath, text.leftEncoding());
        header(g, rightX, rightW, rightPath, text.rightEncoding());
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.border());
        for (int y = 0; y < body; y++) {
            g.putString(new TerminalPosition(sideW, y + 1), "│");
        }

        int[] starts = text.hunkStarts();
        int curStart = curHunk >= 0 && curHunk < starts.length ? starts[curHunk] : -1;
        int curEnd = curStart;
        while (curStart >= 0 && curEnd < rows.size() && rows.get(curEnd).type() != TextCompare.RowType.SAME) {
            curEnd++;
        }
        for (int y = 0; y < body; y++) {
            int index = top + y;
            if (index >= rows.size()) {
                break;
            }
            TextCompare.Row row = rows.get(index);
            boolean current = curStart >= 0 && index >= curStart && index < curEnd;
            String leftText = row.left() >= 0 ? expand(text.leftLine(row.left())) : null;
            String rightText = row.right() >= 0 ? expand(text.rightLine(row.right())) : null;
            int[] range = null;
            if (row.type() == TextCompare.RowType.CHANGED) {
                range = changedRange(leftText, rightText);
            }
            drawSide(g, 0, y + 1, sideW, digits, row.left(), leftText, row.type(), true, current, range);
            drawSide(g, rightX, y + 1, rightW, digits, row.right(), rightText, row.type(), false, current,
                    range == null ? null : new int[] {range[2], range[3]});
        }

        String info;
        if (!message.isEmpty()) {
            info = message;
        } else if (text.hunkStarts().length == 0) {
            info = "No line differences"
                    + (ignoreWhitespace || ignoreCase ? " with current options" : " (line endings or encoding differ)");
        } else {
            info = "Diff " + Math.max(0, curHunk + 1) + "/" + starts.length
                    + "   ~" + text.changed() + " changed  -" + text.removed() + " only left  +"
                    + text.added() + " only right";
        }
        if (!rows.isEmpty()) {
            TextCompare.Row first = rows.get(top);
            info += "   L" + (first.left() + 1) + " R" + (first.right() + 1);
        }
        cell(g, 0, h - 2, w, " " + info, theme.muted(), theme.panelBackground());
        cell(g, 0, h - 1, w, " N/P next/prev diff   Up/Down PgUp/PgDn Home/End scroll   Left/Right pan   W ws:"
                + onOff(ignoreWhitespace) + "  C case:" + onOff(ignoreCase)
                + "   B hex   Esc close", theme.text(), theme.appBackground());
    }

    private void header(TextGraphics g, int x, int width, Path path, String encoding) {
        String label = " " + shorten(path.toString(), Math.max(10, width - encoding.length() - 6)) + "  [" + encoding + "]";
        cell(g, x, 0, width, label, theme.headerText(), theme.headerBackground());
    }

    private void drawSide(TextGraphics g, int x, int y, int width, int digits, int lineIndex, String line,
                          TextCompare.RowType type, boolean leftSide, boolean current, int[] range) {
        boolean present = line != null;
        TextColor bg;
        TextColor strong;
        char marker = ' ';
        switch (type) {
            case CHANGED -> {
                bg = theme.changed();
                strong = theme.changedStrong();
                marker = '~';
            }
            case LEFT_ONLY -> {
                bg = leftSide ? theme.removed() : theme.filler();
                strong = theme.removedStrong();
                marker = leftSide ? '-' : ' ';
            }
            case RIGHT_ONLY -> {
                bg = leftSide ? theme.filler() : theme.added();
                strong = theme.addedStrong();
                marker = leftSide ? ' ' : '+';
            }
            default -> {
                bg = theme.appBackground();
                strong = bg;
            }
        }
        TextColor gutterBg = current ? theme.selection() : theme.panelBackground();
        String number = present ? Integer.toString(lineIndex + 1) : "";
        cell(g, x, y, digits + 2, " ".repeat(Math.max(0, digits - number.length())) + number + marker + " ",
                current ? theme.accent() : theme.muted(), gutterBg);
        int textX = x + digits + 2;
        int textW = width - digits - 2;
        if (textW <= 0) {
            return;
        }
        cell(g, textX, y, textW, "", theme.text(), bg);
        if (!present) {
            return;
        }
        int start = Math.min(hscroll, line.length());
        int end = Math.min(line.length(), hscroll + textW);
        int hl0 = range == null ? -1 : range[0];
        int hl1 = range == null ? -1 : range[1];
        int[] cuts = {start, clamp(hl0, start, end), clamp(hl1, start, end), end};
        if (hl0 < 0) {
            cuts = new int[] {start, end, end, end};
        }
        draw(g, textX, y, line, start, cuts[1], theme.text(), bg, start);
        draw(g, textX, y, line, cuts[1], cuts[2], theme.text(), strong, start);
        draw(g, textX, y, line, cuts[2], cuts[3], theme.text(), bg, start);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private static void draw(TextGraphics g, int baseX, int y, String line, int from, int to,
                             TextColor fg, TextColor bg, int origin) {
        if (to <= from) {
            return;
        }
        g.setForegroundColor(fg);
        g.setBackgroundColor(bg);
        g.putString(new TerminalPosition(baseX + from - origin, y), line.substring(from, to));
    }

    /** {leftFrom, leftTo, rightFrom, rightTo}: the part between the common prefix and suffix. */
    private static int[] changedRange(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int prefix = 0;
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) {
            suffix++;
        }
        return new int[] {prefix, a.length() - suffix, prefix, b.length() - suffix};
    }

    static String expand(String s) {
        boolean plain = true;
        for (int i = 0; i < s.length() && plain; i++) {
            char c = s.charAt(i);
            plain = c != '\t' && !Character.isISOControl(c);
        }
        if (plain) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                int n = 4 - (out.length() % 4);
                out.append(" ".repeat(n));
            } else {
                out.append(Character.isISOControl(c) ? '·' : c);
            }
        }
        return out.toString();
    }

    private void drawBinary(TextGraphics g, int w, int h) throws IOException {
        int body = h - 3;
        int sideW = (w - 1) / 2;
        int rightX = sideW + 1;
        int rightW = w - rightX;
        long max = binary.maxSize();
        int offsetDigits = max > 0xFFFFFFFFL ? 12 : 8;
        bytesPerRow = 4;
        for (int candidate : new int[] {16, 12, 8}) {
            if (offsetDigits + 2 + 4 * candidate <= Math.min(sideW, rightW)) {
                bytesPerRow = candidate;
                break;
            }
        }
        clampBinary(body);

        header(g, 0, sideW, leftPath, FileEntry.humanSize(binary.leftSize()));
        header(g, rightX, rightW, rightPath, FileEntry.humanSize(binary.rightSize()));
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.border());
        for (int y = 0; y < body; y++) {
            g.putString(new TerminalPosition(sideW, y + 1), "│");
        }

        byte[] a = new byte[bytesPerRow];
        byte[] b = new byte[bytesPerRow];
        for (int y = 0; y < body; y++) {
            long offset = binTop + (long) y * bytesPerRow;
            if (offset >= max) {
                break;
            }
            int na = binary.read(true, offset, a, bytesPerRow);
            int nb = binary.read(false, offset, b, bytesPerRow);
            drawHexSide(g, 0, y + 1, offsetDigits, offset, a, na, b, nb);
            drawHexSide(g, rightX, y + 1, offsetDigits, offset, b, nb, a, na);
        }

        String info = message;
        if (info.isEmpty()) {
            info = "Offset 0x" + String.format("%0" + offsetDigits + "X", binTop)
                    + "   " + (max == 0 ? 100 : Math.min(100, (binTop + (long) body * bytesPerRow) * 100 / max)) + "%";
            if (binary.leftSize() != binary.rightSize()) {
                info += "   sizes differ";
            }
            if (markStart >= 0) {
                info += "   diff at 0x" + Long.toHexString(markStart).toUpperCase() + " (" + (markEnd - markStart)
                        + " bytes)";
            }
        }
        cell(g, 0, h - 2, w, " " + info, theme.muted(), theme.panelBackground());
        cell(g, 0, h - 1, w, " N/P next/prev diff   Up/Down PgUp/PgDn Home/End scroll"
                + (textAllowed ? "   T text view" : "") + "   Esc close", theme.text(), theme.appBackground());
    }

    private void drawHexSide(TextGraphics g, int x, int y, int offsetDigits, long offset,
                             byte[] mine, int mineCount, byte[] other, int otherCount) {
        cell(g, x, y, offsetDigits + 1, String.format("%0" + offsetDigits + "X ", offset),
                theme.muted(), theme.appBackground());
        int hexX = x + offsetDigits + 1;
        int asciiX = hexX + bytesPerRow * 3 + 1;
        for (int i = 0; i < bytesPerRow; i++) {
            boolean present = i < mineCount;
            boolean differs = present ? i >= otherCount || mine[i] != other[i] : i < otherCount;
            long position = offset + i;
            boolean inMark = markStart >= 0 && position >= markStart && position < markEnd;
            TextColor bg = !differs ? theme.appBackground() : inMark ? theme.match() : theme.changedStrong();
            if (!present) {
                bg = differs ? theme.filler() : theme.appBackground();
            }
            String hex = present ? String.format("%02X", mine[i] & 0xFF) : "  ";
            char c = present && mine[i] >= 0x20 && mine[i] < 0x7F ? (char) mine[i] : present ? '.' : ' ';
            TextColor fg = differs || !present ? theme.text() : theme.muted();
            g.setForegroundColor(fg);
            g.setBackgroundColor(bg);
            g.putString(new TerminalPosition(hexX + i * 3, y), hex);
            g.putString(new TerminalPosition(asciiX + i, y), String.valueOf(c));
        }
    }

    /** Draws text clipped and padded to exactly {@code width} columns. */
    private static void cell(TextGraphics g, int x, int y, int width, String value, TextColor fg, TextColor bg) {
        if (width <= 0) {
            return;
        }
        String clipped = value.length() > width ? value.substring(0, width) : value;
        StringBuilder padded = new StringBuilder(width).append(clipped);
        while (padded.length() < width) {
            padded.append(' ');
        }
        g.setForegroundColor(fg);
        g.setBackgroundColor(bg);
        g.putString(new TerminalPosition(x, y), padded.toString());
    }

    private static String shorten(String path, int width) {
        return path.length() <= width ? path : "…" + path.substring(path.length() - width + 1);
    }
}
