package org.ds.tuifileman.app;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.screen.Screen;
import org.ds.tuifileman.compare.DirCompare;
import org.ds.tuifileman.compare.DirCompare.Action;
import org.ds.tuifileman.compare.DirCompare.Item;
import org.ds.tuifileman.compare.DirCompare.Mode;
import org.ds.tuifileman.compare.DirCompare.Side;
import org.ds.tuifileman.compare.DirCompare.Status;
import org.ds.tuifileman.compare.TimeTolerance;
import org.ds.tuifileman.ignore.IgnoreMatcher;
import org.ds.tuifileman.model.FileEntry;
import org.ds.tuifileman.ui.Theme;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full-screen directory synchronisation view with its own input loop. The user reviews the proposed
 * action per item and returns {@link Result#EXECUTE}; running the actions is up to the caller.
 */
final class SyncView {
    enum Result { CLOSE, EXECUTE }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final String IGNORED_MESSAGE = "Ignored by the .gitignore rules - it is never copied or deleted";

    /** One background scan; the view only looks at it from the UI thread. */
    private static final class Scan {
        final AtomicBoolean cancel = new AtomicBoolean();
        final AtomicInteger progress = new AtomicInteger();
        final Queue<DirCompare.Unreadable> unreadable = new ConcurrentLinkedQueue<>();
        volatile List<Item> result;
        volatile String error;
        volatile boolean done;
    }

    private final Screen screen;
    private final Theme theme;
    private final Path leftRoot;
    private final Path rightRoot;

    private Scan scan;
    private List<Item> items = List.of();
    private List<Item> visible = List.of();
    private boolean recursive = true;
    private boolean contents;
    private boolean showEqual;
    private boolean gitignore;
    private boolean showIgnored;
    private int cursor;
    private int top;
    private String message = "";
    private long lastDraw;

    private int toRight;
    private int toLeft;
    private int deletions;
    private long bytesRight;
    private long bytesLeft;
    private int equalCount;
    private int ignoredCount;

    SyncView(Screen screen, Theme theme, Path leftRoot, Path rightRoot) {
        this.screen = screen;
        this.theme = theme;
        this.leftRoot = leftRoot;
        this.rightRoot = rightRoot;
    }

    Path leftRoot() {
        return leftRoot;
    }

    Path rightRoot() {
        return rightRoot;
    }

    List<Item> items() {
        return items;
    }

    int copyCount() {
        return toRight + toLeft;
    }

    int deleteCount() {
        return deletions;
    }

    /** Human readable description of the pending actions, for the confirmation dialog. */
    String describe() {
        StringBuilder text = new StringBuilder();
        text.append("Copy ").append(toRight).append(" to the right (")
                .append(FileEntry.humanSize(bytesRight)).append(")\n");
        text.append("Copy ").append(toLeft).append(" to the left (")
                .append(FileEntry.humanSize(bytesLeft)).append(")");
        if (deletions > 0) {
            text.append("\nDELETE ").append(deletions).append(deletions == 1 ? " item" : " items");
        }
        return text.toString();
    }

    Result run() {
        if (scan == null) {
            startScan();
        }
        while (true) {
            draw();
            KeyStroke key = nextKey();
            if (key == null) {
                continue;
            }
            message = "";
            Result result = handle(key);
            if (result != null) {
                return result;
            }
        }
    }

    // ---- scanning ----

    private void startScan() {
        if (scan != null) {
            scan.cancel.set(true);
        }
        Scan task = new Scan();
        scan = task;
        boolean obeyGitignore = gitignore;
        Thread thread = new Thread(() -> {
            try {
                IgnoreMatcher ignore = obeyGitignore
                        ? IgnoreMatcher.git(List.of(), true, leftRoot, rightRoot) : IgnoreMatcher.NONE;
                task.result = DirCompare.scan(leftRoot, rightRoot, recursive, contents, task.cancel, task.progress,
                        ignore, new AtomicInteger(), relative -> false, TimeTolerance.configured(), task.unreadable);
            } catch (IOException | RuntimeException e) {
                task.error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                task.done = true;
            }
        }, "sync-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean scanning() {
        return scan != null && !scan.done;
    }

    /** Takes over a finished scan: default actions, filter, cursor to the top. */
    private void pollScan() {
        if (scan != null && scan.done && scan.result != null && !scan.cancel.get()) {
            items = scan.result;
            scan.result = null;
            DirCompare.apply(items, Mode.MERGE);
            rebuild(null);
            cursor = 0;
            top = 0;
            DirCompare.Unreadable first = scan.unreadable.peek();
            message = first != null
                    ? scan.unreadable.size() + " unreadable, not compared (" + first.path() + ": " + first.reason() + ")"
                    : items.isEmpty() ? "The directories are identical" : "";
        }
    }

    private void rebuild(Item keep) {
        List<Item> list = new ArrayList<>(items.size());
        equalCount = 0;
        ignoredCount = 0;
        for (Item item : items) {
            if (item.status() == Status.SAME) {
                equalCount++;
            }
            if (item.status() == Status.IGNORED) {
                ignoredCount++;
            }
            boolean hidden = item.status() == Status.SAME && !showEqual
                    || item.status() == Status.IGNORED && !showIgnored;
            if (!hidden) {
                list.add(item);
            }
        }
        visible = list;
        if (keep != null) {
            int index = visible.indexOf(keep);
            cursor = index >= 0 ? index : Math.min(cursor, Math.max(0, visible.size() - 1));
        }
        recount();
    }

    private void recount() {
        toRight = 0;
        toLeft = 0;
        deletions = 0;
        bytesRight = 0;
        bytesLeft = 0;
        for (Item item : items) {
            switch (item.action()) {
                case TO_RIGHT -> {
                    toRight++;
                    bytesRight += item.directory() ? 0 : item.left().size();
                }
                case TO_LEFT -> {
                    toLeft++;
                    bytesLeft += item.directory() ? 0 : item.right().size();
                }
                case DELETE_LEFT, DELETE_RIGHT -> deletions++;
                default -> { }
            }
        }
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
                boolean wasScanning = scanning();
                if (scan != null && scan.done && scan.result != null) {
                    return null;
                }
                long now = System.nanoTime();
                if (resized != null || wasScanning && now - lastDraw > 150_000_000L) {
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
        if (key.getKeyType() == KeyType.EOF || key.getKeyType() == KeyType.Escape) {
            if (scan != null) {
                scan.cancel.set(true);
            }
            return Result.CLOSE;
        }
        if (scanning()) {
            return null;
        }
        if (key instanceof MouseAction mouse) {
            switch (mouse.getActionType()) {
                case SCROLL_UP -> move(-3, body);
                case SCROLL_DOWN -> move(3, body);
                case CLICK_DOWN -> {
                    int index = top + mouse.getPosition().getRow() - 1;
                    if (mouse.getPosition().getRow() >= 1 && index < visible.size()) {
                        cursor = index;
                    }
                }
                default -> { }
            }
            return null;
        }
        switch (key.getKeyType()) {
            case ArrowUp -> move(-1, body);
            case ArrowDown -> move(1, body);
            case PageUp -> move(-body + 1, body);
            case PageDown -> move(body - 1, body);
            case Home -> move(-visible.size(), body);
            case End -> move(visible.size(), body);
            case ArrowRight -> choose(Action.TO_RIGHT);
            case ArrowLeft -> choose(Action.TO_LEFT);
            case Delete -> chooseDelete();
            case Enter, F5 -> {
                return execute();
            }
            case Character -> {
                if (key.isCtrlDown() || key.isAltDown()) {
                    return null;
                }
                return handleCharacter(key.getCharacter());
            }
            default -> { }
        }
        return null;
    }

    private Result handleCharacter(char c) {
        switch (Character.toLowerCase(c)) {
            case 'q' -> {
                if (scan != null) {
                    scan.cancel.set(true);
                }
                return Result.CLOSE;
            }
            case '>' -> choose(Action.TO_RIGHT);
            case '<' -> choose(Action.TO_LEFT);
            case ' ' -> toggleProposed();
            case '1' -> setMode(Mode.MERGE, "Merge: newer and missing files are copied to the other side");
            case '2' -> setMode(Mode.MIRROR_TO_RIGHT, "Mirror left to right: right-only items will be DELETED");
            case '3' -> setMode(Mode.MIRROR_TO_LEFT, "Mirror right to left: left-only items will be DELETED");
            case '0' -> setMode(Mode.CLEAR, "All actions cleared");
            case 'e' -> {
                showEqual = !showEqual;
                rebuild(current());
                message = "Show equal items: " + (showEqual ? "on" : "off");
            }
            case 'r' -> {
                recursive = !recursive;
                startScan();
            }
            case 'g' -> {
                gitignore = !gitignore;
                startScan();
            }
            case 'i' -> {
                showIgnored = !showIgnored;
                rebuild(current());
                message = gitignore ? "Show ignored items: " + (showIgnored ? "on" : "off")
                        : "Nothing is ignored - G turns the .gitignore rules on";
            }
            case 'c' -> {
                contents = !contents;
                startScan();
            }
            default -> { }
        }
        return null;
    }

    private Item current() {
        return cursor >= 0 && cursor < visible.size() ? visible.get(cursor) : null;
    }

    private void move(int delta, int body) {
        if (visible.isEmpty()) {
            return;
        }
        cursor = Math.max(0, Math.min(visible.size() - 1, cursor + delta));
        if (cursor < top) {
            top = cursor;
        } else if (cursor >= top + body) {
            top = cursor - body + 1;
        }
    }

    private void choose(Action wanted) {
        Item item = current();
        if (item == null) {
            return;
        }
        if (!item.allows(wanted)) {
            message = item.status() == Status.IGNORED ? IGNORED_MESSAGE
                    : item.status() == Status.TYPE_MISMATCH
                    ? "A file and a folder with the same name - resolve it by hand"
                    : "Nothing on that side to copy from";
            return;
        }
        assign(item, item.action() == wanted ? Action.NONE : wanted);
    }

    private void chooseDelete() {
        Item item = current();
        if (item == null) {
            return;
        }
        Action wanted = item.status() == Status.ONLY_LEFT ? Action.DELETE_LEFT
                : item.status() == Status.ONLY_RIGHT ? Action.DELETE_RIGHT : null;
        if (wanted == null) {
            message = item.status() == Status.IGNORED ? IGNORED_MESSAGE
                    : "Delete works on items that exist on one side only (modes 2 and 3 mirror a whole side)";
            return;
        }
        assign(item, item.action() == wanted ? Action.NONE : wanted);
    }

    private void toggleProposed() {
        Item item = current();
        if (item == null) {
            return;
        }
        if (item.status() == Status.IGNORED) {
            message = IGNORED_MESSAGE;
            return;
        }
        if (item.action() != Action.NONE) {
            assign(item, Action.NONE);
            return;
        }
        Action proposed = DirCompare.proposed(item, Mode.MERGE);
        if (proposed == Action.NONE) {
            message = "No default direction for this item - use Left/Right";
        } else {
            assign(item, proposed);
        }
    }

    /** A folder's action also applies to everything below it, so a whole folder can be skipped at once. */
    private void assign(Item item, Action action) {
        item.setAction(action);
        if (item.directory()) {
            String prefix = item.relative() + "/";
            for (Item other : items) {
                if (other.relative().startsWith(prefix)) {
                    other.setAction(other.allows(action) ? action : Action.NONE);
                }
            }
        }
        recount();
    }

    private void setMode(Mode mode, String text) {
        DirCompare.apply(items, mode);
        recount();
        message = text;
    }

    private Result execute() {
        if (copyCount() + deletions == 0) {
            message = "Nothing to do - choose actions first";
            return null;
        }
        return Result.EXECUTE;
    }

    private int bodyHeight() {
        return Math.max(1, screen.getTerminalSize().getRows() - 4);
    }

    // ---- drawing ----

    private void draw() {
        pollScan();
        lastDraw = System.nanoTime();
        TextGraphics g = screen.newTextGraphics();
        int w = g.getSize().getColumns();
        int h = g.getSize().getRows();
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.text());
        g.fill(' ');
        if (w < 60 || h < 8) {
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
        int half = (w - 3) / 2;
        int rightX = half + 3;
        int rightW = w - rightX;
        cell(g, 0, 0, half, " " + shorten(leftRoot.toString(), half - 2), theme.headerText(), theme.headerBackground());
        cell(g, half, 0, 3, "", theme.headerText(), theme.headerBackground());
        cell(g, rightX, 0, rightW, " " + shorten(rightRoot.toString(), rightW - 2), theme.headerText(), theme.headerBackground());

        if (scanning()) {
            cell(g, 0, 2, w, "  Scanning... " + scan.progress.get() + " entries examined   (Esc cancels)",
                    theme.text(), theme.appBackground());
        } else if (scan != null && scan.error != null) {
            cell(g, 0, 2, w, "  Scan failed: " + scan.error, theme.error(), theme.appBackground());
        } else {
            drawRows(g, w, h, half, rightX, rightW);
        }

        String info;
        if (!message.isEmpty()) {
            info = message;
        } else if (scanning()) {
            info = "";
        } else {
            info = visible.size() + " items" + (equalCount > 0 && !showEqual ? " (" + equalCount + " equal hidden)" : "")
                    + (ignoredCount > 0 && !showIgnored ? " (" + ignoredCount + " ignored hidden)" : "")
                    + "   \u2192 " + toRight + " (" + FileEntry.humanSize(bytesRight) + ")"
                    + "   \u2190 " + toLeft + " (" + FileEntry.humanSize(bytesLeft) + ")"
                    + (deletions > 0 ? "   delete " + deletions : "");
        }
        cell(g, 0, h - 3, w, " " + info, deletions > 0 && message.isEmpty() ? theme.warning() : theme.muted(),
                theme.panelBackground());
        cell(g, 0, h - 2, w, " R recursive: " + (recursive ? "on" : "off") + "   C content: "
                + (contents ? "on" : "off") + "   E equal: " + (showEqual ? "on" : "off")
                + "   G gitignore: " + (gitignore ? "on" : "off") + "   I ignored: " + (showIgnored ? "on" : "off")
                + "   1 merge  2 mirror \u2192  3 mirror \u2190  0 clear", theme.text(), theme.appBackground());
        cell(g, 0, h - 1, w, " Left/Right set direction   Space default   Del delete one-sided item"
                + "   Enter/F5 run   Esc close", theme.muted(), theme.appBackground());
    }

    private void drawRows(TextGraphics g, int w, int h, int half, int rightX, int rightW) {
        int body = h - 4;
        if (visible.isEmpty()) {
            cell(g, 0, 2, w, "  Nothing to synchronize - the directories are equal"
                    + (equalCount > 0 ? " (E shows the equal items)" : ""), theme.success(), theme.appBackground());
            return;
        }
        cursor = Math.max(0, Math.min(cursor, visible.size() - 1));
        top = Math.max(0, Math.min(top, Math.max(0, visible.size() - body)));
        if (cursor < top) {
            top = cursor;
        } else if (cursor >= top + body) {
            top = cursor - body + 1;
        }
        for (int y = 0; y < body; y++) {
            int index = top + y;
            if (index >= visible.size()) {
                break;
            }
            Item item = visible.get(index);
            TextColor bg = index == cursor ? theme.selection() : theme.appBackground();
            TextColor[] colors = colors(item);
            cell(g, 0, y + 1, half, sideText(item, item.left(), half), colors[0], bg);
            cell(g, half, y + 1, 3, glyph(item), actionColor(item), bg);
            cell(g, rightX, y + 1, rightW, sideText(item, item.right(), rightW), colors[1], bg);
        }
    }

    private TextColor[] colors(Item item) {
        return switch (item.status()) {
            case ONLY_LEFT -> new TextColor[] {theme.accent(), theme.muted()};
            case ONLY_RIGHT -> new TextColor[] {theme.muted(), theme.accent()};
            case LEFT_NEWER -> new TextColor[] {theme.success(), theme.muted()};
            case RIGHT_NEWER -> new TextColor[] {theme.muted(), theme.success()};
            case DIFFERENT -> new TextColor[] {theme.warning(), theme.warning()};
            case TYPE_MISMATCH -> new TextColor[] {theme.error(), theme.error()};
            case SAME, IGNORED -> new TextColor[] {theme.muted(), theme.muted()};
        };
    }

    private TextColor actionColor(Item item) {
        return switch (item.action()) {
            case TO_LEFT, TO_RIGHT -> theme.accent();
            case DELETE_LEFT, DELETE_RIGHT -> theme.error();
            case NONE -> theme.muted();
        };
    }

    private static String glyph(Item item) {
        return switch (item.action()) {
            case TO_RIGHT -> " \u2192 ";
            case TO_LEFT -> " \u2190 ";
            case DELETE_LEFT -> "\u2717  ";
            case DELETE_RIGHT -> "  \u2717";
            case NONE -> item.status() == Status.SAME ? " = "
                    : item.status() == Status.TYPE_MISMATCH ? " ! "
                    : item.status() == Status.IGNORED ? " i " : " \u00b7 ";
        };
    }

    private static String sideText(Item item, Side side, int width) {
        if (side == null) {
            return "";
        }
        boolean date = width >= 50;
        int nameWidth = width - 1 - 10 - (date ? 17 : 0);
        String name = (item.relative() + (side.directory() ? "/" : "")).replace('/', java.io.File.separatorChar);
        if (name.length() > nameWidth) {
            name = "\u2026" + name.substring(name.length() - nameWidth + 1);
        }
        String size = side.directory() ? "<DIR>" : FileEntry.humanSize(side.size());
        StringBuilder text = new StringBuilder(" ").append(name);
        while (text.length() < 1 + nameWidth) {
            text.append(' ');
        }
        text.append(' ').append(" ".repeat(Math.max(0, 9 - size.length()))).append(size);
        if (date) {
            text.append(' ').append(DATE.format(Instant.ofEpochMilli(side.modified())));
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
        return path.length() <= width ? path : "\u2026" + path.substring(path.length() - width + 1);
    }
}
