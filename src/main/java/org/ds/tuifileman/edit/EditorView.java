package org.ds.tuifileman.edit;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.screen.Screen;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.edit.TextBuffer.Pos;
import org.ds.tuifileman.ui.Draw;
import org.ds.tuifileman.ui.LineInput;
import org.ds.tuifileman.ui.Theme;
import org.ds.tuifileman.ui.Wrap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The F4 text editor: a full-screen editor with selection, undo/redo, find/replace and clipboard, working
 * on a {@link TextBuffer} that keeps the file's encoding and line endings.
 */
public final class EditorView {
    private static final int WHEEL_LINES = 3;
    private static String clipboard = "";

    private final Screen screen;
    private final Theme theme;
    private final Path path;
    private final TextBuffer buffer;
    private final int tabSize;
    private final boolean useTabs;
    private final boolean autoIndent;

    private Pos cursor = new Pos(0, 0);
    private Pos anchor;
    private int wantCol = -1;
    private int top;
    private int left;
    private int topRow;
    private boolean follow = true;
    private boolean lineNumbers;
    private boolean wrap;
    private boolean burst;
    private int saves;
    private String message = "";
    private String lastFind = "";
    private String lastReplace = "";
    private String promptLabel;
    private LineInput promptInput;

    private EditorView(Screen screen, Theme theme, Path path, TextBuffer buffer, Config config) {
        this.screen = screen;
        this.theme = theme;
        this.path = path;
        this.buffer = buffer;
        this.tabSize = Math.max(1, Math.min(16, config.number("editor.tab_size", 4)));
        this.useTabs = config.flag("editor.use_tabs", false);
        this.autoIndent = config.flag("editor.auto_indent", true);
        this.lineNumbers = config.flag("editor.line_numbers", true);
        this.wrap = config.flag("editor.wrap", true);
    }

    /** Edits the file (created when it does not exist and {@code create} is set); returns a status message. */
    public static String edit(Screen screen, Theme theme, Path path, boolean create) {
        return edit(screen, theme, path, create, Config.get());
    }

    /** Like {@link #edit(Screen, Theme, Path, boolean)} with the settings of {@code config}. */
    public static String edit(Screen screen, Theme theme, Path path, boolean create, Config config) {
        TextBuffer buffer;
        String note = "";
        try {
            if (Files.isDirectory(path)) {
                return "Edit: " + path.getFileName() + " is a directory";
            }
            if (Files.exists(path)) {
                buffer = TextBuffer.load(path);
                if (!Files.isWritable(path)) {
                    note = "Read-only file: saving will fail";
                } else if (buffer.mixedLineEndings()) {
                    note = "Mixed line endings: all will be saved as " + buffer.lineEnding();
                }
            } else if (create) {
                buffer = TextBuffer.empty();
                note = "New file";
            } else {
                return "Edit: " + path.getFileName() + " does not exist";
            }
        } catch (IOException | RuntimeException e) {
            return "Edit failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        EditorView view = new EditorView(screen, theme, path, buffer, config);
        view.message = note;
        try {
            view.run();
        } catch (UncheckedIOException e) {
            return "Edit failed: " + e.getMessage();
        }
        return view.saves > 0 ? "Edited " + path.getFileName() + " (saved)" : "Edit: " + path.getFileName() + " unchanged";
    }

    // ---- main loop ----

    private void run() {
        while (true) {
            KeyStroke key = nextKey();
            if (!handle(key)) {
                return;
            }
        }
    }

    /** Waits for a key; the screen is only redrawn when no key is waiting, which keeps pasting fast. */
    private KeyStroke nextKey() {
        try {
            KeyStroke key = screen.pollInput();
            if (key != null) {
                burst = true;
                return key;
            }
            burst = false;
            draw();
            while (true) {
                var resized = screen.doResizeIfNecessary();
                key = screen.pollInput();
                if (key != null) {
                    return key;
                }
                if (resized != null) {
                    draw();
                } else {
                    Thread.sleep(25);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new KeyStroke(KeyType.EOF);
        }
    }

    private int bodyHeight() {
        return Math.max(1, screen.getTerminalSize().getRows() - 3);
    }

    /** Returns false to close the editor. */
    private boolean handle(KeyStroke key) {
        message = "";
        if (key instanceof MouseAction mouse) {
            mouse(mouse);
            return true;
        }
        follow = true;
        boolean shift = key.isShiftDown();
        boolean ctrl = key.isCtrlDown();
        int page = Math.max(1, bodyHeight() - 1);
        switch (key.getKeyType()) {
            case EOF -> {
                return false;
            }
            case Escape, F10 -> {
                return !confirmClose();
            }
            case F2 -> save();
            case F7 -> find();
            case F3 -> findNext(!shift);
            case ArrowLeft -> {
                if (ctrl) {
                    go(wordLeft(cursor), shift, false);
                } else if (!shift && hasSelection()) {
                    go(selStart(), false, false);
                } else {
                    go(stepLeft(cursor), shift, false);
                }
            }
            case ArrowRight -> {
                if (ctrl) {
                    go(wordRight(cursor), shift, false);
                } else if (!shift && hasSelection()) {
                    go(selEnd(), false, false);
                } else {
                    go(stepRight(cursor), shift, false);
                }
            }
            case ArrowUp -> vertical(-1, shift);
            case ArrowDown -> vertical(1, shift);
            case PageUp -> {
                scrollBy(-page);
                vertical(-page, shift);
            }
            case PageDown -> {
                scrollBy(page);
                vertical(page, shift);
            }
            case Home -> go(ctrl ? new Pos(0, 0) : home(), shift, false);
            case End -> go(ctrl ? buffer.end() : rowEnd(), shift, false);
            case Enter -> newline();
            case Backspace -> backspace();
            case Delete -> {
                if (shift) {
                    cut();
                } else {
                    delete();
                }
            }
            case Insert -> {
                if (shift) {
                    paste();
                } else if (ctrl) {
                    copy();
                }
            }
            case Tab -> {
                if (shift) {
                    indentLines(false);
                } else {
                    tab();
                }
            }
            case ReverseTab -> indentLines(false);
            case Character -> {
                return character(key);
            }
            default -> { }
        }
        return true;
    }

    private boolean character(KeyStroke key) {
        char c = key.getCharacter();
        if (c == '\n') {
            newline();
            return true;
        }
        if (key.isCtrlDown() && !key.isAltDown()) {
            switch (Character.toLowerCase(c)) {
                case 'a' -> {
                    anchor = new Pos(0, 0);
                    cursor = buffer.end();
                }
                case 'c' -> copy();
                case 'x' -> cut();
                case 'v' -> paste();
                case 'z' -> undoRedo(true);
                case 'y' -> undoRedo(false);
                case 's' -> save();
                case 'f' -> find();
                case 'r' -> replaceInteractive();
                case 'g' -> goToLine();
                case 'k' -> deleteLine();
                case 'l' -> {
                    lineNumbers = !lineNumbers;
                    message = lineNumbers ? "" : "Line numbers off";
                }
                case 'q' -> {
                    return !confirmClose();
                }
                default -> { }
            }
            return true;
        }
        if (key.isAltDown() && !key.isCtrlDown()) {
            if (Character.toLowerCase(c) == 'w') {
                wrap = !wrap;
                left = 0;
                wantCol = -1;
                message = wrap ? "Wrap on" : "Wrap off";
            }
            return true;
        }
        if (c >= ' ') {
            insert(String.valueOf(c));
        }
        return true;
    }

    // ---- selection and movement ----

    private boolean hasSelection() {
        return anchor != null && !anchor.equals(cursor);
    }

    private Pos selStart() {
        return anchor == null || cursor.compareTo(anchor) <= 0 ? cursor : anchor;
    }

    private Pos selEnd() {
        return anchor == null || cursor.compareTo(anchor) >= 0 ? cursor : anchor;
    }

    private String selectedText() {
        return hasSelection() ? buffer.text(selStart(), selEnd()) : "";
    }

    private void go(Pos target, boolean extend, boolean keepWantCol) {
        buffer.breakUndo();
        if (extend) {
            if (anchor == null) {
                anchor = cursor;
            }
        } else {
            anchor = null;
        }
        cursor = buffer.clamp(target);
        if (!keepWantCol) {
            wantCol = -1;
        }
    }

    /** Moves the cursor {@code delta} screen rows (buffer lines when wrapping is off). */
    private void vertical(int delta, boolean extend) {
        Spot here = spotOf(cursor);
        if (wantCol < 0) {
            String line = buffer.line(cursor.line());
            wantCol = visualCol(line, cursor.col()) - rows(Draw.expand(line))[here.row()];
        }
        Spot to = move(here, delta);
        if (to == null) {
            go(delta < 0 ? new Pos(0, 0) : buffer.end(), extend, true);
        } else {
            go(posIn(to, wantCol), extend, true);
        }
    }

    private Pos stepLeft(Pos p) {
        if (p.col() > 0) {
            String line = buffer.line(p.line());
            int n = p.col() > 1 && Character.isLowSurrogate(line.charAt(p.col() - 1))
                    && Character.isHighSurrogate(line.charAt(p.col() - 2)) ? 2 : 1;
            return new Pos(p.line(), p.col() - n);
        }
        return p.line() > 0 ? new Pos(p.line() - 1, buffer.line(p.line() - 1).length()) : p;
    }

    private Pos stepRight(Pos p) {
        String line = buffer.line(p.line());
        if (p.col() < line.length()) {
            int n = Character.isHighSurrogate(line.charAt(p.col())) && p.col() + 1 < line.length()
                    && Character.isLowSurrogate(line.charAt(p.col() + 1)) ? 2 : 1;
            return new Pos(p.line(), p.col() + n);
        }
        return p.line() + 1 < buffer.lineCount() ? new Pos(p.line() + 1, 0) : p;
    }

    private Pos wordLeft(Pos p) {
        String line = buffer.line(p.line());
        int c = p.col();
        if (c == 0) {
            return stepLeft(p);
        }
        while (c > 0 && Character.isWhitespace(line.charAt(c - 1))) {
            c--;
        }
        if (c > 0) {
            boolean word = isWord(line.charAt(c - 1));
            while (c > 0 && !Character.isWhitespace(line.charAt(c - 1)) && isWord(line.charAt(c - 1)) == word) {
                c--;
            }
        }
        return new Pos(p.line(), c);
    }

    private Pos wordRight(Pos p) {
        String line = buffer.line(p.line());
        int c = p.col();
        if (c >= line.length()) {
            return stepRight(p);
        }
        boolean word = isWord(line.charAt(c));
        while (c < line.length() && !Character.isWhitespace(line.charAt(c)) && isWord(line.charAt(c)) == word) {
            c++;
        }
        while (c < line.length() && Character.isWhitespace(line.charAt(c))) {
            c++;
        }
        return new Pos(p.line(), c);
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Home goes to the first non-blank character, and to the line start when already there. */
    private Pos smartHome() {
        String line = buffer.line(cursor.line());
        int first = 0;
        while (first < line.length() && (line.charAt(first) == ' ' || line.charAt(first) == '\t')) {
            first++;
        }
        return new Pos(cursor.line(), cursor.col() == first || first == line.length() ? 0 : first);
    }

    /** Home goes to the start of the screen row; on the first row of a line it is {@link #smartHome()}. */
    private Pos home() {
        Spot here = spotOf(cursor);
        return here.row() == 0 ? smartHome() : posIn(here, 0);
    }

    /**
     * End goes to the end of the screen row. A row that was broken after a space ends before that space; the
     * position behind it belongs to the next row.
     */
    private Pos rowEnd() {
        return posIn(spotOf(cursor), Integer.MAX_VALUE / 2);
    }

    // ---- editing commands ----

    private void insert(String text) {
        Pos from = hasSelection() ? selStart() : cursor;
        Pos to = hasSelection() ? selEnd() : cursor;
        cursor = buffer.replace(from, to, text);
        anchor = null;
        wantCol = -1;
    }

    private void newline() {
        String indent = "";
        if (autoIndent && !burst) {
            String line = buffer.line(selStart().line());
            int end = Math.min(line.length(), selStart().col());
            int i = 0;
            while (i < end && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            indent = line.substring(0, i);
        }
        insert("\n" + indent);
    }

    private void backspace() {
        if (hasSelection()) {
            insert("");
            return;
        }
        Pos before = stepLeft(cursor);
        if (!before.equals(cursor)) {
            cursor = buffer.replace(before, cursor, "");
            wantCol = -1;
        }
    }

    private void delete() {
        if (hasSelection()) {
            insert("");
            return;
        }
        Pos after = stepRight(cursor);
        if (!after.equals(cursor)) {
            buffer.replace(cursor, after, "");
            wantCol = -1;
        }
    }

    private void deleteLine() {
        int line = cursor.line();
        int last = buffer.lineCount() - 1;
        if (line < last) {
            buffer.replace(new Pos(line, 0), new Pos(line + 1, 0), "");
        } else if (line > 0) {
            buffer.replace(new Pos(line - 1, buffer.line(line - 1).length()), buffer.end(), "");
            line--;
        } else {
            buffer.replace(new Pos(0, 0), buffer.end(), "");
        }
        buffer.breakUndo();
        cursor = buffer.clamp(new Pos(line, cursor.col()));
        anchor = null;
        wantCol = -1;
    }

    private void tab() {
        if (hasSelection() && selStart().line() != selEnd().line()) {
            indentLines(true);
            return;
        }
        if (useTabs) {
            insert("\t");
        } else {
            int column = visualCol(buffer.line(cursor.line()), cursor.col());
            insert(" ".repeat(tabSize - column % tabSize));
        }
    }

    /** Indents or unindents the selected lines (or the cursor line) as one undo step. */
    private void indentLines(boolean indent) {
        int first = selStart().line();
        int last = selEnd().line();
        if (hasSelection() && selEnd().col() == 0 && last > first) {
            last--;
        }
        String prefix = useTabs ? "\t" : " ".repeat(tabSize);
        int[] shift = new int[last - first + 1];
        buffer.beginGroup();
        try {
            for (int l = first; l <= last; l++) {
                String line = buffer.line(l);
                if (indent) {
                    if (!line.isEmpty()) {
                        buffer.replace(new Pos(l, 0), new Pos(l, 0), prefix);
                        shift[l - first] = prefix.length();
                    }
                } else {
                    int remove = 0;
                    if (line.startsWith("\t")) {
                        remove = 1;
                    } else {
                        while (remove < tabSize && remove < line.length() && line.charAt(remove) == ' ') {
                            remove++;
                        }
                    }
                    if (remove > 0) {
                        buffer.replace(new Pos(l, 0), new Pos(l, remove), "");
                        shift[l - first] = -remove;
                    }
                }
            }
        } finally {
            buffer.endGroup();
        }
        cursor = shifted(cursor, first, shift);
        if (anchor != null) {
            anchor = shifted(anchor, first, shift);
        }
        wantCol = -1;
    }

    private Pos shifted(Pos p, int first, int[] shift) {
        int i = p.line() - first;
        if (i < 0 || i >= shift.length) {
            return p;
        }
        return buffer.clamp(new Pos(p.line(), Math.max(0, p.col() + shift[i])));
    }

    private void undoRedo(boolean undo) {
        Pos where = undo ? buffer.undo() : buffer.redo();
        if (where == null) {
            message = undo ? "Nothing to undo" : "Nothing to redo";
            return;
        }
        cursor = buffer.clamp(where);
        anchor = null;
        wantCol = -1;
    }

    // ---- clipboard ----

    private void copy() {
        String text = selectedText();
        if (text.isEmpty()) {
            message = "Nothing selected";
            return;
        }
        clipboard = text;
        SystemClipboard.set(text);
        message = "Copied " + text.length() + " characters";
    }

    private void cut() {
        if (!hasSelection()) {
            message = "Nothing selected";
            return;
        }
        copy();
        insert("");
    }

    private void paste() {
        String text = SystemClipboard.get();
        if (text == null || text.isEmpty()) {
            text = clipboard;
        }
        if (text.isEmpty()) {
            message = "Clipboard is empty";
            return;
        }
        insert(text);
    }

    // ---- search ----

    private static boolean hasUpper(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isUpperCase(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String seed() {
        String selected = selectedText();
        return !selected.isEmpty() && selected.indexOf('\n') < 0 ? selected : lastFind;
    }

    private void find() {
        String query = prompt("Find (matches case only when it has capitals): ", seed());
        if (query == null || query.isEmpty()) {
            return;
        }
        lastFind = query;
        findNext(true);
    }

    private void findNext(boolean forward) {
        if (lastFind.isEmpty()) {
            find();
            return;
        }
        Pos from = forward ? selEnd() : selStart();
        Pos hit = buffer.find(lastFind, from, forward, hasUpper(lastFind));
        if (hit == null) {
            message = "Not found: " + lastFind;
            return;
        }
        boolean wrapped = forward ? hit.compareTo(from) < 0 : hit.compareTo(from) > 0;
        anchor = hit;
        cursor = new Pos(hit.line(), hit.col() + lastFind.length());
        wantCol = -1;
        message = wrapped ? "Search wrapped" : "";
    }

    private void replaceInteractive() {
        String find = prompt("Replace: ", seed());
        if (find == null || find.isEmpty()) {
            return;
        }
        lastFind = find;
        String with = prompt("Replace \"" + Draw.shorten(find, 30) + "\" with: ", lastReplace);
        if (with == null) {
            return;
        }
        lastReplace = with;
        boolean matchCase = hasUpper(find);
        Pos from = hasSelection() ? selStart() : cursor;
        int count = 0;
        boolean all = false;
        buffer.beginGroup();
        try {
            while (true) {
                Pos hit = buffer.find(find, from, true, matchCase);
                if (hit == null || hit.compareTo(from) < 0) {
                    break;
                }
                Pos hitEnd = new Pos(hit.line(), hit.col() + find.length());
                if (!all) {
                    anchor = hit;
                    cursor = hitEnd;
                    follow = true;
                    char answer = choose("Replace?  (Y)es  (N)o  (A)ll  Esc stops: ", "yna");
                    if (answer == 0) {
                        break;
                    }
                    if (answer == 'n') {
                        from = hitEnd;
                        continue;
                    }
                    all = answer == 'a';
                }
                from = buffer.replace(hit, hitEnd, with);
                cursor = from;
                anchor = null;
                count++;
            }
        } finally {
            buffer.endGroup();
        }
        anchor = null;
        wantCol = -1;
        message = count == 0 ? "No replacements (searches from the cursor down)"
                : "Replaced " + count + (count == 1 ? " occurrence" : " occurrences");
    }

    private void goToLine() {
        String answer = prompt("Go to line[:column]: ", "");
        if (answer == null || answer.isBlank()) {
            return;
        }
        try {
            String[] parts = answer.strip().split("[:,]");
            int line = Integer.parseInt(parts[0].strip()) - 1;
            int col = parts.length > 1 ? Integer.parseInt(parts[1].strip()) - 1 : 0;
            go(new Pos(Math.max(0, line), Math.max(0, col)), false, false);
        } catch (NumberFormatException e) {
            message = "Not a line number: " + answer;
        }
    }

    // ---- saving ----

    private boolean save() {
        try {
            if (buffer.changedOnDisk(path)
                    && choose("The file changed on disk. Overwrite?  (Y)es  (N)o: ", "yn") != 'y') {
                message = "Not saved";
                return false;
            }
            if (!buffer.canEncode()) {
                if (choose("Some characters do not fit " + buffer.encodingName() + ". Save as UTF-8?  (Y)es  (N)o: ",
                        "yn") != 'y') {
                    message = "Not saved";
                    return false;
                }
                buffer.useUtf8();
            }
            int bytes = buffer.save(path);
            saves++;
            message = "Saved " + buffer.lineCount() + " lines, " + bytes + " bytes";
            return true;
        } catch (IOException | RuntimeException e) {
            message = "Save failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            return false;
        }
    }

    /** True when the editor may close: nothing unsaved, saved now, or the changes are thrown away. */
    private boolean confirmClose() {
        if (!buffer.dirty()) {
            return true;
        }
        char answer = choose("Save changes?  (Y)es  (N)o, discard  Esc keeps editing: ", "yn");
        return answer == 'y' ? save() : answer == 'n';
    }

    // ---- mouse ----

    private void mouse(MouseAction action) {
        TerminalPosition at = action.getPosition();
        switch (action.getActionType()) {
            case SCROLL_UP -> {
                scrollBy(-WHEEL_LINES);
                follow = false;
            }
            case SCROLL_DOWN -> {
                scrollBy(WHEEL_LINES);
                follow = false;
            }
            case CLICK_DOWN -> {
                if (at.getRow() >= 1 && at.getRow() <= bodyHeight()) {
                    go(positionAt(at), false, false);
                    anchor = cursor;
                    follow = true;
                }
            }
            case DRAG -> {
                if (anchor != null) {
                    go(positionAt(new TerminalPosition(at.getColumn(),
                            Math.max(1, Math.min(bodyHeight(), at.getRow())))), true, false);
                    follow = true;
                }
            }
            default -> { }
        }
    }

    private Pos positionAt(TerminalPosition at) {
        Spot spot = moveClamped(topSpot(), at.getRow() - 1);
        return posIn(spot, Math.max(0, (wrap ? 0 : left) + at.getColumn() - gutter()));
    }

    // ---- prompts ----

    private String prompt(String label, String initial) {
        promptLabel = label;
        promptInput = new LineInput(initial);
        try {
            while (true) {
                KeyStroke key = nextKey();
                switch (key.getKeyType()) {
                    case Enter -> {
                        return promptInput.text();
                    }
                    case Escape, EOF -> {
                        return null;
                    }
                    default -> promptInput.handle(key);
                }
            }
        } finally {
            promptLabel = null;
            promptInput = null;
        }
    }

    /** One-key question; returns the lower-case key, or 0 on Esc. */
    private char choose(String label, String keys) {
        promptLabel = label;
        promptInput = null;
        try {
            while (true) {
                KeyStroke key = nextKey();
                if (key.getKeyType() == KeyType.Escape || key.getKeyType() == KeyType.EOF) {
                    return 0;
                }
                if (key.getKeyType() == KeyType.Character && !key.isCtrlDown()) {
                    char c = Character.toLowerCase(key.getCharacter());
                    if (keys.indexOf(c) >= 0) {
                        return c;
                    }
                }
            }
        } finally {
            promptLabel = null;
        }
    }

    // ---- columns ----

    private static int visualCol(String line, int col) {
        return Draw.expand(line.substring(0, Math.min(col, line.length()))).length();
    }

    /** The character offset whose screen column is nearest to {@code visual} (tabs and controls expanded). */
    private static int colFromVisual(String line, int visual) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            int next = width + (line.charAt(i) == '\t' ? 4 - width % 4 : 1);
            if (next > visual) {
                return (visual - width) * 2 < next - width ? i : i + 1;
            }
            width = next;
        }
        return line.length();
    }

    // ---- screen rows ----

    /** A screen row: row {@code row} (always 0 without wrapping) of buffer line {@code line}. */
    private record Spot(int line, int row) {
        boolean before(Spot other) {
            return line < other.line || line == other.line && row < other.row;
        }
    }

    /** Width of the line number gutter, 0 when the numbers are off. */
    private int gutter() {
        return lineNumbers ? Math.max(4, Integer.toString(buffer.lineCount()).length()) + 1 : 0;
    }

    /** Columns of text per row: one less than the text area, so the cursor fits behind the last character. */
    private int wrapWidth() {
        return Math.max(1, screen.getTerminalSize().getColumns() - gutter() - 1);
    }

    /** Start index of every screen row of an expanded line. */
    private int[] rows(String shown) {
        return wrap ? Wrap.rows(shown, wrapWidth()) : new int[] {0};
    }

    private int rowCount(int line) {
        return wrap ? Wrap.count(Draw.expand(buffer.line(line)), wrapWidth()) : 1;
    }

    private Spot spotOf(Pos p) {
        if (!wrap) {
            return new Spot(p.line(), 0);
        }
        String line = buffer.line(p.line());
        return new Spot(p.line(), Wrap.rowOf(Wrap.rows(Draw.expand(line), wrapWidth()), visualCol(line, p.col())));
    }

    /** The first row on screen, brought back into the document (it may be stale after edits or a resize). */
    private Spot topSpot() {
        top = Math.max(0, Math.min(top, buffer.lineCount() - 1));
        topRow = Math.max(0, Math.min(topRow, rowCount(top) - 1));
        return new Spot(top, topRow);
    }

    private void setTop(Spot spot) {
        top = spot.line();
        topRow = spot.row();
    }

    private void scrollBy(int rows) {
        setTop(moveClamped(topSpot(), rows));
    }

    /** The row {@code delta} rows from {@code from}, or null when that is before the start or after the end. */
    private Spot move(Spot from, int delta) {
        int line = from.line();
        int row = Math.min(from.row(), rowCount(line) - 1);
        int remaining = delta;
        while (remaining > 0) {
            int below = rowCount(line) - 1 - row;
            if (remaining <= below) {
                return new Spot(line, row + remaining);
            }
            remaining -= below + 1;
            if (++line >= buffer.lineCount()) {
                return null;
            }
            row = 0;
        }
        while (remaining < 0) {
            if (-remaining <= row) {
                return new Spot(line, row + remaining);
            }
            remaining += row + 1;
            if (--line < 0) {
                return null;
            }
            row = rowCount(line) - 1;
        }
        return new Spot(line, row);
    }

    private Spot moveClamped(Spot from, int delta) {
        Spot to = move(from, delta);
        if (to != null) {
            return to;
        }
        if (delta < 0) {
            return new Spot(0, 0);
        }
        int last = buffer.lineCount() - 1;
        return new Spot(last, rowCount(last) - 1);
    }

    /** Screen rows from {@code from} down to {@code to}; -1 when that is {@code limit} or more, or {@code to} is above. */
    private int distance(Spot from, Spot to, int limit) {
        if (to.before(from)) {
            return -1;
        }
        int steps = 0;
        int row = from.row();
        for (int line = from.line(); line < to.line(); line++) {
            steps += rowCount(line) - row;
            row = 0;
            if (steps >= limit) {
                return -1;
            }
        }
        steps += to.row() - row;
        return steps < limit ? steps : -1;
    }

    /**
     * The position {@code rowColumn} columns into a screen row (counted from the start of the line when wrapping
     * is off); past the end of the row it is the end of it.
     */
    private Pos posIn(Spot spot, int rowColumn) {
        String line = buffer.line(spot.line());
        String shown = Draw.expand(line);
        int[] starts = rows(shown);
        int row = Math.min(spot.row(), starts.length - 1);
        int end = Wrap.end(starts, row, shown.length());
        int visual = Math.min(starts[row] + rowColumn, row == starts.length - 1 ? shown.length() : end - 1);
        return new Pos(spot.line(), colFromVisual(line, visual));
    }

    // ---- drawing ----

    private void ensureVisible(int body, int textWidth) {
        Spot at = spotOf(cursor);
        Spot first = topSpot();
        if (at.before(first)) {
            setTop(at);
        } else if (distance(first, at, body) < 0) {
            setTop(moveClamped(at, 1 - body));
        }
        if (wrap) {
            left = 0;
            return;
        }
        int column = visualCol(buffer.line(cursor.line()), cursor.col());
        if (column < left) {
            left = column;
        } else if (column >= left + textWidth) {
            left = column - textWidth + 1;
        }
        if (column < textWidth) {
            left = 0;
        }
    }

    private void draw() {
        TextGraphics g = screen.newTextGraphics();
        int w = g.getSize().getColumns();
        int h = g.getSize().getRows();
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.text());
        g.fill(' ');
        TerminalPosition cursorAt = null;
        if (w >= 30 && h >= 6) {
            int body = h - 3;
            int digits = Math.max(4, Integer.toString(buffer.lineCount()).length());
            int textWidth = Math.max(1, w - gutter());
            if (follow) {
                ensureVisible(body, textWidth);
            }
            Spot first = topSpot();
            drawHeader(g, w);
            drawBody(g, first, body, digits, textWidth);
            drawFooter(g, w, h);
            Spot at = spotOf(cursor);
            int row = distance(first, at, body);
            if (promptLabel == null && row >= 0) {
                String line = buffer.line(cursor.line());
                int column = visualCol(line, cursor.col()) - (wrap ? rows(Draw.expand(line))[at.row()] : left);
                if (column >= 0 && column < textWidth) {
                    cursorAt = new TerminalPosition(gutter() + column, row + 1);
                }
            }
        } else {
            Draw.cell(g, 0, 0, w, "Window too small", theme.accent(), theme.appBackground());
        }
        screen.setCursorPosition(cursorAt);
        try {
            screen.refresh();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void drawHeader(TextGraphics g, int w) {
        String tail = "  [Edit, " + buffer.encodingName() + ", " + buffer.lineEnding()
                + (buffer.dirty() ? ", modified" : "") + "]";
        Draw.cell(g, 0, 0, w, " " + Draw.shorten(path.toString(), Math.max(10, w - tail.length() - 2)) + tail,
                theme.headerText(), theme.headerBackground());
    }

    private void drawBody(TextGraphics g, Spot first, int body, int digits, int textWidth) {
        int line = first.line();
        int row = first.row();
        for (int y = 0; y < body && line < buffer.lineCount(); line++, row = 0) {
            String shown = Draw.expand(buffer.line(line));
            int[] starts = rows(shown);
            for (; row < starts.length && y < body; row++, y++) {
                drawRow(g, y + 1, line, row, shown, starts, digits, textWidth);
            }
        }
    }

    /** Draws screen row {@code row} of buffer line {@code index}; {@code starts} are the row starts of {@code shown}. */
    private void drawRow(TextGraphics g, int y, int index, int row, String shown, int[] starts, int digits,
                         int textWidth) {
        int textX = gutter();
        boolean current = index == cursor.line();
        var background = current ? theme.panelBackground() : theme.appBackground();
        if (lineNumbers) {
            String number = row == 0 ? Integer.toString(index + 1) : "";
            Draw.cell(g, 0, y, textX, " ".repeat(Math.max(0, digits - number.length())) + number + "│",
                    current ? theme.accent() : theme.muted(), theme.appBackground());
        }
        int from = wrap ? starts[row] : left;
        int to = wrap ? Wrap.end(starts, row, shown.length()) : Math.min(shown.length(), left + textWidth);
        Draw.cell(g, textX, y, textWidth, from < to ? shown.substring(from, to) : "", theme.text(), background);
        if (!hasSelection() || index < selStart().line() || index > selEnd().line()) {
            return;
        }
        String line = buffer.line(index);
        int a = index == selStart().line() ? selStart().col() : 0;
        int b = index == selEnd().line() ? selEnd().col() : line.length();
        int selFrom = Math.max(visualCol(line, a), from);
        int selTo = Math.min(visualCol(line, b), to);
        if (selTo > selFrom) {
            Draw.cell(g, textX + selFrom - from, y, selTo - selFrom, shown.substring(selFrom, selTo), theme.text(),
                    theme.selection());
        }
        if (index < selEnd().line() && row == starts.length - 1 && shown.length() >= from
                && shown.length() < from + textWidth) {
            Draw.cell(g, textX + shown.length() - from, y, 1, " ", theme.text(), theme.selection());
        }
    }

    private void drawFooter(TextGraphics g, int w, int h) {
        if (promptLabel != null && promptInput != null) {
            promptInput.draw(g, h - 2, w, promptLabel, theme.accent(), theme.panelBackground());
        } else if (promptLabel != null) {
            Draw.cell(g, 0, h - 2, w, " " + promptLabel, theme.accent(), theme.panelBackground());
        } else if (!message.isEmpty()) {
            Draw.cell(g, 0, h - 2, w, " " + message, theme.warning(), theme.panelBackground());
        } else {
            Draw.cell(g, 0, h - 2, w, String.format(Locale.ROOT, " Ln %d/%d   Col %d   %s   %s%s",
                    cursor.line() + 1, buffer.lineCount(), visualCol(buffer.line(cursor.line()), cursor.col()) + 1,
                    buffer.encodingName(), buffer.lineEnding(), hasSelection() ? "   " + selectedText().length()
                            + " selected" : ""), theme.muted(), theme.panelBackground());
        }
        Draw.cell(g, 0, h - 1, w, " F2 save   F7 find   F3 next   Ctrl+R replace   Ctrl+G go to   Ctrl+Z/Y undo/redo"
                + "   Ctrl+L numbers   Alt+W wrap   Esc close", theme.text(), theme.appBackground());
    }
}
