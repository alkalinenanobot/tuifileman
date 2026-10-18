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
    private final int tabSize = Math.max(1, Math.min(16, Config.get().number("editor.tab_size", 4)));
    private final boolean useTabs = Config.get().flag("editor.use_tabs", false);
    private final boolean autoIndent = Config.get().flag("editor.auto_indent", true);

    private Pos cursor = new Pos(0, 0);
    private Pos anchor;
    private int wantCol = -1;
    private int top;
    private int left;
    private boolean follow = true;
    private boolean lineNumbers = Config.get().flag("editor.line_numbers", true);
    private boolean burst;
    private int saves;
    private int textX;
    private String message = "";
    private String lastFind = "";
    private String lastReplace = "";
    private String promptLabel;
    private LineInput promptInput;

    private EditorView(Screen screen, Theme theme, Path path, TextBuffer buffer) {
        this.screen = screen;
        this.theme = theme;
        this.path = path;
        this.buffer = buffer;
    }

    /** Edits the file (created when it does not exist and {@code create} is set); returns a status message. */
    public static String edit(Screen screen, Theme theme, Path path, boolean create) {
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
        EditorView view = new EditorView(screen, theme, path, buffer);
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
                top = Math.max(0, top - page);
                vertical(-page, shift);
            }
            case PageDown -> {
                top = Math.min(Math.max(0, buffer.lineCount() - 1), top + page);
                vertical(page, shift);
            }
            case Home -> go(ctrl ? new Pos(0, 0) : smartHome(), shift, false);
            case End -> go(ctrl ? buffer.end() : new Pos(cursor.line(), buffer.line(cursor.line()).length()), shift, false);
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

    private void vertical(int delta, boolean extend) {
        if (wantCol < 0) {
            wantCol = visualCol(buffer.line(cursor.line()), cursor.col());
        }
        int line = cursor.line() + delta;
        if (line < 0) {
            go(new Pos(0, 0), extend, true);
        } else if (line >= buffer.lineCount()) {
            go(buffer.end(), extend, true);
        } else {
            go(new Pos(line, colFromVisual(buffer.line(line), wantCol)), extend, true);
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
                top = Math.max(0, top - WHEEL_LINES);
                follow = false;
            }
            case SCROLL_DOWN -> {
                top = Math.min(Math.max(0, buffer.lineCount() - 1), top + WHEEL_LINES);
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
        int line = Math.max(0, Math.min(buffer.lineCount() - 1, top + at.getRow() - 1));
        return new Pos(line, colFromVisual(buffer.line(line), Math.max(0, left + at.getColumn() - textX)));
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

    // ---- drawing ----

    private void ensureVisible(int body, int textWidth) {
        if (cursor.line() < top) {
            top = cursor.line();
        } else if (cursor.line() >= top + body) {
            top = cursor.line() - body + 1;
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
            textX = lineNumbers ? digits + 1 : 0;
            int textWidth = Math.max(1, w - textX);
            if (follow) {
                ensureVisible(body, textWidth);
            }
            top = Math.max(0, Math.min(top, buffer.lineCount() - 1));
            drawHeader(g, w);
            for (int y = 0; y < body && top + y < buffer.lineCount(); y++) {
                drawLine(g, y + 1, top + y, digits, textWidth);
            }
            drawFooter(g, w, h);
            int row = cursor.line() - top;
            int column = visualCol(buffer.line(cursor.line()), cursor.col()) - left;
            if (promptLabel == null && row >= 0 && row < body && column >= 0 && column < textWidth) {
                cursorAt = new TerminalPosition(textX + column, row + 1);
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

    private void drawLine(TextGraphics g, int y, int index, int digits, int textWidth) {
        boolean current = index == cursor.line();
        var background = current ? theme.panelBackground() : theme.appBackground();
        if (lineNumbers) {
            String number = Integer.toString(index + 1);
            Draw.cell(g, 0, y, textX, " ".repeat(Math.max(0, digits - number.length())) + number + "│",
                    current ? theme.accent() : theme.muted(), theme.appBackground());
        }
        String line = buffer.line(index);
        String shown = Draw.expand(line);
        String visible = left < shown.length() ? shown.substring(left, Math.min(shown.length(), left + textWidth)) : "";
        Draw.cell(g, textX, y, textWidth, visible, theme.text(), background);
        if (!hasSelection() || index < selStart().line() || index > selEnd().line()) {
            return;
        }
        int a = index == selStart().line() ? selStart().col() : 0;
        int b = index == selEnd().line() ? selEnd().col() : line.length();
        int from = Math.max(visualCol(line, a), left);
        int to = Math.min(visualCol(line, b), Math.min(shown.length(), left + textWidth));
        if (to > from) {
            Draw.cell(g, textX + from - left, y, to - from, shown.substring(from, to), theme.text(), theme.selection());
        }
        if (index < selEnd().line() && shown.length() >= left && shown.length() < left + textWidth) {
            Draw.cell(g, textX + shown.length() - left, y, 1, " ", theme.text(), theme.selection());
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
                + "   Ctrl+L numbers   Esc close", theme.text(), theme.appBackground());
    }
}
