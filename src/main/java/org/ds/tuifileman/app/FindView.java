package org.ds.tuifileman.app;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.screen.Screen;
import org.ds.tuifileman.model.FileEntry;
import org.ds.tuifileman.search.FileSearch;
import org.ds.tuifileman.search.FileSearch.Hit;
import org.ds.tuifileman.ui.Theme;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen list of the hits of a {@link FileSearch}, filled while the search runs on its own threads.
 * Like {@link SyncView} it has its own input loop; what to do with a hit is up to the caller.
 */
final class FindView {
    enum Result {
        /** Leave; the search is stopped. */
        CLOSE,
        /** Show {@link #selected} in the panel; the search is stopped. */
        GO,
        /** View {@link #selected}, then call {@link #run} again: the search goes on meanwhile. */
        VIEW,
        /** Ask for another search; the search is stopped. */
        NEW
    }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Screen screen;
    private final Theme theme;
    private final FileSearch.Query query;
    private final FileSearch search;
    private final List<Hit> hits = new ArrayList<>();
    private boolean started;
    private boolean finished;
    private int cursor;
    private int top;
    private String message = "";
    private long lastDraw;
    private long startNanos;
    private long endNanos;
    private Hit selected;

    FindView(Screen screen, Theme theme, FileSearch.Query query) {
        this.screen = screen;
        this.theme = theme;
        this.query = query;
        this.search = new FileSearch(query);
    }

    /** The hit that {@link Result#GO} or {@link Result#VIEW} refers to. */
    Hit selected() {
        return selected;
    }

    Result run() {
        if (!started) {
            started = true;
            startNanos = System.nanoTime();
            Thread worker = new Thread(search::run, "find");
            worker.setDaemon(true);
            worker.start();
        }
        while (true) {
            collect();
            draw();
            KeyStroke key = nextKey();
            if (key == null) {
                continue;
            }
            message = "";
            Result result = handle(key);
            if (result != null) {
                if (result != Result.VIEW) {
                    search.cancel();
                }
                return result;
            }
        }
    }

    /** Takes over the new hits; once the search is over the list is put in path order. */
    private void collect() {
        boolean done = search.isDone();
        for (Hit hit; (hit = search.poll()) != null; ) {
            hits.add(hit);
        }
        if (done && !finished) {
            finished = true;
            endNanos = System.nanoTime();
            Hit current = current();
            hits.sort(FileSearch.BY_PATH);
            if (current != null) {
                cursor = Math.max(0, hits.indexOf(current));
            }
        }
    }

    private Hit current() {
        return cursor >= 0 && cursor < hits.size() ? hits.get(cursor) : null;
    }

    // ---- input ----

    private KeyStroke nextKey() {
        while (true) {
            try {
                var resized = screen.doResizeIfNecessary();
                KeyStroke key = screen.pollInput();
                if (key != null) {
                    return key;
                }
                boolean searching = !finished;
                if (searching && search.isDone()) {
                    return null;
                }
                if (resized != null || searching && System.nanoTime() - lastDraw > 120_000_000L) {
                    return null;
                }
                Thread.sleep(25);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new KeyStroke(KeyType.Escape);
            }
        }
    }

    private Result handle(KeyStroke key) {
        int body = bodyHeight();
        if (key.getKeyType() == KeyType.EOF) {
            return Result.CLOSE;
        }
        if (key instanceof MouseAction mouse) {
            switch (mouse.getActionType()) {
                case SCROLL_UP -> move(-3, body);
                case SCROLL_DOWN -> move(3, body);
                case CLICK_DOWN -> {
                    int index = top + mouse.getPosition().getRow() - 1;
                    if (mouse.getPosition().getRow() >= 1 && mouse.getPosition().getRow() <= body && index < hits.size()) {
                        cursor = index;
                    }
                }
                default -> { }
            }
            return null;
        }
        switch (key.getKeyType()) {
            case Escape -> {
                if (!finished && !search.isCancelled()) {
                    search.cancel();
                    message = "Stopping...";
                    return null;
                }
                return Result.CLOSE;
            }
            case ArrowUp -> move(-1, body);
            case ArrowDown -> move(1, body);
            case PageUp -> move(-body + 1, body);
            case PageDown -> move(body - 1, body);
            case Home -> move(-hits.size(), body);
            case End -> move(hits.size(), body);
            case Enter -> {
                selected = current();
                if (selected == null) {
                    message = "Nothing to go to";
                    return null;
                }
                return Result.GO;
            }
            case F3 -> {
                selected = current();
                if (selected == null || selected.directory()) {
                    message = selected == null ? "Nothing to view" : "View: " + selected.name() + " is a folder";
                    return null;
                }
                return Result.VIEW;
            }
            case F7 -> {
                return Result.NEW;
            }
            case Character -> {
                if (!key.isCtrlDown() && !key.isAltDown() && Character.toLowerCase(key.getCharacter()) == 'n') {
                    return Result.NEW;
                }
            }
            default -> { }
        }
        return null;
    }

    private void move(int delta, int body) {
        if (hits.isEmpty()) {
            return;
        }
        cursor = Math.max(0, Math.min(hits.size() - 1, cursor + delta));
        if (cursor < top) {
            top = cursor;
        } else if (cursor >= top + body) {
            top = cursor - body + 1;
        }
    }

    private int bodyHeight() {
        return Math.max(1, screen.getTerminalSize().getRows() - 4);
    }

    // ---- drawing ----

    private void draw() {
        lastDraw = System.nanoTime();
        TextGraphics g = screen.newTextGraphics();
        int w = g.getSize().getColumns();
        int h = g.getSize().getRows();
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.text());
        g.fill(' ');
        if (w < 40 || h < 8) {
            cell(g, 0, 0, w, "Window too small", theme.accent(), theme.appBackground());
        } else {
            drawView(g, w, h);
        }
        screen.setCursorPosition(null);
        try {
            screen.refresh();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void drawView(TextGraphics g, int w, int h) {
        cell(g, 0, 0, w, " " + shorten(header(), w - 2), theme.headerText(), theme.headerBackground());

        int body = h - 4;
        if (hits.isEmpty()) {
            cell(g, 0, 2, w, finished ? (search.error() != null ? "  " + search.error() : "  Nothing found")
                    : "  Searching...", search.error() != null ? theme.error() : theme.muted(), theme.appBackground());
        }
        cursor = Math.max(0, Math.min(cursor, Math.max(0, hits.size() - 1)));
        top = Math.max(0, Math.min(top, Math.max(0, hits.size() - body)));
        if (cursor < top) {
            top = cursor;
        } else if (cursor >= top + body) {
            top = cursor - body + 1;
        }
        boolean date = w >= 70;
        int nameWidth = w - 1 - 10 - (date ? 17 : 0);
        String rootPrefix = query.root().toString();
        if (!rootPrefix.endsWith(java.io.File.separator)) {
            rootPrefix += java.io.File.separator;
        }
        for (int y = 0; y < body && top + y < hits.size(); y++) {
            int index = top + y;
            Hit hit = hits.get(index);
            TextColor bg = index == cursor ? theme.selection() : theme.appBackground();
            // The search folder is in the header, so rows show the path below it; the end (the file name) stays
            // visible when it has to be cut, and the name is the one bold, bright part of the row.
            String full = hit.path();
            String relative = full.startsWith(rootPrefix) ? full.substring(rootPrefix.length()) : full;
            String shown = shorten(relative, nameWidth - 1);
            cell(g, 0, y + 1, nameWidth + 1, " " + shown, theme.muted(), bg);
            int nameLength = Math.min(hit.name().length(), shown.length());
            g.setForegroundColor(hit.directory() ? theme.accent() : theme.text());
            g.setBackgroundColor(bg);
            g.enableModifiers(SGR.BOLD);
            g.putString(new TerminalPosition(1 + shown.length() - nameLength, y + 1),
                    hit.name().substring(hit.name().length() - nameLength));
            g.disableModifiers(SGR.BOLD);
            String size = hit.directory() ? "<DIR>" : FileEntry.humanSize(hit.size());
            cell(g, nameWidth + 1, y + 1, w - nameWidth - 1, " ".repeat(Math.max(0, 9 - size.length())) + size
                    + (date ? " " + DATE.format(Instant.ofEpochMilli(hit.modified())) : ""),
                    hit.directory() ? theme.accent() : theme.text(), bg);
        }

        Hit current = current();
        String detail = current == null ? "" : current.line() > 0
                ? " line " + current.line() + ": " + current.snippet() : " " + current.path();
        cell(g, 0, h - 3, w, detail, theme.muted(), theme.panelBackground());
        cell(g, 0, h - 2, w, " " + (message.isEmpty() ? status() : message), finished && search.truncated()
                ? theme.warning() : theme.text(), theme.appBackground());
        cell(g, 0, h - 1, w, " Enter go to   F3 view   F7/N new search   Esc "
                + (finished ? "close" : "stop"), theme.muted(), theme.appBackground());
    }

    private String header() {
        StringBuilder text = new StringBuilder("Find ").append(query.names().isBlank() ? "*" : query.names().strip());
        if (!query.text().isEmpty()) {
            text.append("  containing \"").append(query.text()).append('"')
                    .append(query.matchCase() ? " (match case)" : "");
        }
        text.append("  in ").append(query.root());
        if (query.inArchives()) {
            text.append("  + archives");
        }
        if (query.maxDepth() != FileSearch.UNLIMITED) {
            text.append("  depth ").append(query.maxDepth());
        }
        return text.toString();
    }

    private String status() {
        StringBuilder text = new StringBuilder();
        text.append(search.hitCount()).append(search.hitCount() == 1 ? " match" : " matches");
        text.append(", ").append(search.examined()).append(" items examined");
        if (finished) {
            text.append(String.format(" in %.1f s", (endNanos - startNanos) / 1e9));
            if (search.truncated()) {
                text.append(" - stopped at ").append(FileSearch.MAX_HITS).append(" matches");
            } else if (search.isCancelled()) {
                text.append(" - stopped");
            }
        } else {
            text.append("   ").append(search.current());
        }
        if (search.unreadable() > 0) {
            text.append("   (").append(search.unreadable()).append(" unreadable)");
        }
        return text.toString();
    }

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
        return path.length() <= width ? path : "\u2026" + path.substring(path.length() - Math.max(1, width) + 1);
    }
}
