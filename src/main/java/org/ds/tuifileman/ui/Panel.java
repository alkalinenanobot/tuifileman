package org.ds.tuifileman.ui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import org.ds.tuifileman.app.PanelState;
import org.ds.tuifileman.model.EntrySort;
import org.ds.tuifileman.model.FileEntry;
import java.util.List;

/** Renders one file panel. Layout: border, title, path, column header,
 * entries, blank, footer. The column headers are clickable: one sorts by that column, the same again reverses.
 * The extension has no column of its own; its header sits at the right end of the name column. */
public final class Panel {
    private static final String EXTENSION_LABEL = ".EXT";

    private Panel() {}

    /** Screen bounds of a panel. */
    public record Rect(int x, int y, int w, int h) {
        public int visibleRows() {
            return h - 9;
        }

        public int headerRow() {
            return y + 4;
        }

        public int firstEntryRow() {
            return y + 6;
        }
    }

    /** Where the columns are (x &lt; 0: not shown at this width); drawing and header clicks share it. */
    private record Columns(int nameX, int nameWidth, int extensionX, int sizeX, int dateX, int attributeX,
                           int attributeWidth, int right) { }

    private static Columns columns(List<FileEntry> entries, Rect rect) {
        int attributeWidth = 4;
        for (FileEntry entry : entries) {
            attributeWidth = Math.max(attributeWidth, entry.attributes().length());
        }
        boolean showAttributes = rect.w() >= 68 + attributeWidth;
        boolean showDate = rect.w() >= 58;
        int end = rect.x() + rect.w() - 2;
        int attributeX = end - attributeWidth;
        if (showAttributes) {
            end = attributeX - 1;
        }
        int dateX = end - 16;
        if (showDate) {
            end = dateX - 1;
        }
        int sizeX = end - 9;
        int nameX = rect.x() + 2;
        int nameWidth = sizeX - rect.x() - 4;
        // One cell is kept free behind the label for the sort arrow, so the label does not move when it appears.
        int extensionX = nameX + nameWidth - EXTENSION_LABEL.length() - 1;
        return new Columns(nameX, nameWidth, extensionX, sizeX, showDate ? dateX : -1,
                showAttributes ? attributeX : -1, attributeWidth, rect.x() + rect.w() - 1);
    }

    /** The sort key of the column header at a screen position, or null when the position is not on one. */
    public static EntrySort.Key headerAt(PanelState state, Rect rect, int column, int row) {
        Columns c = columns(state.entries(), rect);
        if (row != rect.headerRow() || column <= rect.x() || column >= c.right()) {
            return null;
        }
        if (c.attributeX() >= 0 && column >= c.attributeX()) {
            return EntrySort.Key.ATTRIBUTES;
        }
        if (c.dateX() >= 0 && column >= c.dateX()) {
            return EntrySort.Key.MODIFIED;
        }
        if (column >= c.sizeX()) {
            return EntrySort.Key.SIZE;
        }
        return column >= c.extensionX() ? EntrySort.Key.EXTENSION : EntrySort.Key.NAME;
    }

    public static void render(
            TextGraphics g, Theme theme, PanelState state, Rect rect, boolean active) {
        TextColor edge = active ? theme.accent() : theme.border();
        g.setBackgroundColor(theme.panelBackground());
        g.fillRectangle(new TerminalPosition(rect.x(), rect.y()),
                new TerminalSize(rect.w(), rect.h()), ' ');
        box(g, rect, edge, theme.appBackground());

        boolean inArchive = state.isVirtual();
        String root = inArchive ? "ARCHIVE" : state.directory().getRoot() == null
                ? "" : state.directory().getRoot().toString().replaceAll("[\\\\/]+$", "");
        String label = inArchive
                ? "  " + org.ds.tuifileman.fs.Archives.origin(state.directory()).getFileName()
                : state.volumeLabel().isBlank() ? "" : "  " + state.volumeLabel();
        text(g, rect.x() + 2, rect.y(), rect.w() - 4,
                " " + (root + label).toUpperCase().strip() + " ",
                edge, theme.appBackground());
        text(g, rect.x() + 2, rect.y() + 2, rect.w() - 4,
                shorten(state.displayPath(), rect.w() - 4),
                theme.text(), theme.panelBackground());

        List<FileEntry> entries = state.entries();
        Columns c = columns(entries, rect);
        boolean showDate = c.dateX() >= 0;
        boolean showAttributes = c.attributeX() >= 0;
        int nameWidth = c.nameWidth();
        int sizeX = c.sizeX();
        int dateX = c.dateX();
        int attributeX = c.attributeX();
        int attributeWidth = c.attributeWidth();
        EntrySort sort = state.sort();
        int headerRow = rect.headerRow();
        header(g, theme, sort, EntrySort.Key.NAME, c.nameX(), headerRow, "NAME");
        header(g, theme, sort, EntrySort.Key.EXTENSION, c.extensionX(), headerRow, EXTENSION_LABEL);
        header(g, theme, sort, EntrySort.Key.SIZE, sizeX, headerRow, "SIZE");
        if (showDate) {
            header(g, theme, sort, EntrySort.Key.MODIFIED, dateX, headerRow, "MODIFIED");
        }
        if (showAttributes) {
            header(g, theme, sort, EntrySort.Key.ATTRIBUTES, attributeX, headerRow, attributeWidth > 4 ? "MODE" : "ATTR");
        }

        int visible = rect.visibleRows();
        state.ensureVisible(visible);
        int first = state.first();
        for (int i = first; i < Math.min(entries.size(), first + visible); i++) {
            FileEntry entry = entries.get(i);
            int row = rect.firstEntryRow() + i - first;
            boolean highlighted = i == state.cursor();
            boolean marked = state.isMarked(entry);
            TextColor rowBg = highlighted ? theme.selection() : theme.panelBackground();
            TextColor rowFg = marked
                    ? theme.warning()
                    : highlighted
                    ? theme.text()
                    : (entry.directory() ? theme.accent() : theme.text());
            text(g, rect.x() + 1, row, rect.w() - 2, " ".repeat(rect.w() - 2), theme.text(), rowBg);
            text(g, rect.x() + 2, row, nameWidth,
                    (highlighted && active ? ">" : " ") + (marked ? "*" : " ") + entry.name()
                            + (entry.directory() && !entry.name().equals("..") ? "/" : ""),
                    rowFg, rowBg);
            text(g, sizeX, row, 9, entry.displaySize(),
                    highlighted ? theme.text() : theme.muted(), rowBg);
            if (showDate) {
                text(g, dateX, row, 16, entry.displayModified(),
                        highlighted ? theme.text() : theme.muted(), rowBg);
            }
            if (showAttributes) {
                text(g, attributeX, row, attributeWidth, entry.attributes(),
                        highlighted ? theme.text() : theme.muted(), rowBg);
            }
        }

        FileEntry current = state.current();
        String free = state.freeSpace() >= 0
                ? FileEntry.humanSize(state.freeSpace()) + " free"
                : "";
        String footer = state.searching()
                ? "Search: " + state.searchText() + "\u258c" + (state.searchMatched() ? "" : "  (no match)")
                : state.listError() != null
                ? state.listError()
                : state.markedCount() > 0
                ? state.markedCount() + " selected  /  " + FileEntry.humanSize(state.markedSize())
                        + (free.isEmpty() ? "" : "  |  " + free)
                : (current == null ? "empty" : entries.size() - hasParentLink(entries) + " items"
                + "  /  " + current.name()) + (free.isEmpty() ? "" : "  |  " + free);
        text(g, rect.x() + 2, rect.y() + rect.h() - 2, rect.w() - 4,
                footer, state.searching() ? (state.searchMatched() ? theme.accent() : theme.muted()) : theme.muted(),
                theme.panelBackground());
    }

    /** The sorted column's header is in the accent colour with an arrow: up ascending, down descending. */
    private static void header(TextGraphics g, Theme theme, EntrySort sort, EntrySort.Key key, int x, int y,
                               String label) {
        boolean sorted = sort.key() == key;
        String shown = sorted ? label + (sort.descending() ? "\u25bc" : "\u25b2") : label;
        text(g, x, y, shown.length(), shown, sorted ? theme.accent() : theme.muted(), theme.panelBackground());
    }

    /** Replaces the middle of a too long path with "..." keeping the start and the tail. */
    static String shorten(String path, int width) {
        if (path.length() <= width || width < 8) {
            return path;
        }
        int head = (width - 3) / 3;
        int tail = width - 3 - head;
        return path.substring(0, head) + "..." + path.substring(path.length() - tail);
    }

    private static int hasParentLink(List<FileEntry> entries) {
        return !entries.isEmpty() && "..".equals(entries.get(0).name()) ? 1 : 0;
    }

    private static void box(TextGraphics g, Rect rect, TextColor edge, TextColor background) {
        text(g, rect.x(), rect.y(), rect.w(),
                "\u256d" + "\u2500".repeat(rect.w() - 2) + "\u256e", edge, background);
        text(g, rect.x(), rect.y() + rect.h() - 1, rect.w(),
                "\u2570" + "\u2500".repeat(rect.w() - 2) + "\u256f", edge, background);
        for (int row = rect.y() + 1; row < rect.y() + rect.h() - 1; row++) {
            text(g, rect.x(), row, 1, "\u2502", edge, background);
            text(g, rect.x() + rect.w() - 1, row, 1, "\u2502", edge, background);
        }
    }

    private static void text(TextGraphics g, int x, int y, int width,
                             String value, TextColor foreground, TextColor background) {
        if (width <= 0 || x < 0 || y < 0 || y >= g.getSize().getRows()) {
            return;
        }
        int limit = Math.min(width, g.getSize().getColumns() - x);
        if (limit <= 0) {
            return;
        }
        String clipped = value.length() <= limit ? value : value.substring(0, limit);
        g.setForegroundColor(foreground);
        g.setBackgroundColor(background);
        g.putString(x, y, clipped);
    }
}
