package org.ds.tuifileman.view;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.screen.Screen;
import org.ds.tuifileman.compare.TextCompare;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.model.FileEntry;
import org.ds.tuifileman.ui.Draw;
import org.ds.tuifileman.ui.LineInput;
import org.ds.tuifileman.ui.Theme;
import org.ds.tuifileman.ui.Wrap;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Full-screen read-only viewer (F3): text with horizontal panning and a hex dump, for files of any size
 * (only the visible part is read). Search, go to offset and mouse wheel work in both modes.
 */
public final class FileView implements Closeable {
    private static final long FAR_LINE_COUNT = 1L << 30;
    private static final int SNIFF_BYTES = 64 * 1024;
    private static final long MAX_CONVERT_BYTES = 64L * 1024 * 1024;

    private final Screen screen;
    private final Theme theme;
    private final Path path;
    private final long fileSize;
    private final ByteSource fileSource;
    private final ByteSource textSource;
    private final ByteWindow hexWindow;
    private final ByteWindow textWindow;
    private final LineReader reader;
    private final String encoding;
    private final boolean textAvailable;
    private final boolean converted;

    private boolean textMode;
    private long textTop;
    private long hexTop;
    private int hscroll;
    private int bytesPerRow = 16;
    private String message = "";
    private String lastQuery = "";
    private long matchStart = -1;
    private int matchLength;
    private long lastTopFor = -1;
    private long lastTop;
    private boolean lineNumbers;
    private boolean wrap;
    private int textRow;
    private Pos lastWrapPos;
    private int lastWrapH;
    private int lastWrapWidth;
    private long lastWrapSize = -1;
    private boolean backToImage;
    private boolean wantImage;
    private boolean pdf;
    private long anchorPos;
    private long anchorLines;
    private int gutterDigits = 4;
    private int textX;

    private String promptLabel;
    private LineInput promptInput;

    private FileView(Screen screen, Theme theme, Path path, long fileSize, ByteSource fileSource,
                     ByteSource textSource, Charset charset, long dataStart, String encoding,
                     boolean textAvailable, boolean converted, boolean startInText) {
        this.screen = screen;
        this.theme = theme;
        this.path = path;
        this.fileSize = fileSize;
        this.fileSource = fileSource;
        this.textSource = textSource;
        this.hexWindow = new ByteWindow(fileSource);
        this.textWindow = new ByteWindow(textSource);
        this.reader = new LineReader(textWindow, charset, dataStart);
        this.encoding = encoding;
        this.textAvailable = textAvailable;
        this.converted = converted;
        this.textMode = startInText;
        this.textTop = dataStart;
    }

    /** A place in the wrapped text: the line starting at {@code top} and the screen row of it ({@code row}). */
    private record Pos(long top, int row) {
        boolean after(Pos other) {
            return top > other.top || top == other.top && row > other.row;
        }
    }

    /** Shows the file with the settings of the user's config; returns a status message for the caller. */
    public static String show(Screen screen, Theme theme, Path path) {
        return show(screen, theme, path, Config.get(), "");
    }

    /** Like {@link #show(Screen, Theme, Path)} with {@code search} as the text of the find prompt (F7, F3/N). */
    public static String show(Screen screen, Theme theme, Path path, String search) {
        return show(screen, theme, path, Config.get(), search);
    }

    /** Shows the file; returns a status message for the caller. */
    public static String show(Screen screen, Theme theme, Path path, Config config) {
        return show(screen, theme, path, config, "");
    }

    private static String show(Screen screen, Theme theme, Path path, Config config, String search) {
        PdfDoc pdf = PdfDoc.supports(path) ? PdfDoc.open(path) : null;
        PreviewSource source = pdf != null ? pdf : MediaDoc.supports(path) ? MediaDoc.open(path) : null;
        boolean image = source != null || ImageView.supports(path);
        try {
            while (true) {
                if (image) {
                    String result = ImageView.show(screen, theme, path, source);
                    if (result != null) {
                        return result;
                    }
                }
                try (FileView view = pdf != null ? openPdfText(screen, theme, path, pdf) : open(screen, theme, path)) {
                    if (view == null) {
                        return "View: " + path.getFileName() + " is empty";
                    }
                    view.lastQuery = search;
                    view.lineNumbers = config.flag("viewer.line_numbers", true);
                    view.wrap = config.flag("viewer.wrap", true);
                    view.backToImage = image;
                    view.run();
                    if (!view.wantImage) {
                        return "Viewed " + path.getFileName();
                    }
                    if (view.pdf) {
                        pdf.page = pdf.pageAtTextOffset(view.textTop);
                    }
                } catch (IOException | RuntimeException e) {
                    return "View failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        } finally {
            if (source != null) {
                source.close();
            }
        }
    }

    /** The text of a PDF positioned at the current page; when the text cannot be extracted, the raw file with a note. */
    private static FileView openPdfText(Screen screen, Theme theme, Path path, PdfDoc pdf) throws IOException {
        String problem;
        try {
            byte[] text = pdf.text();
            ByteSource file = ByteSource.open(path);
            FileView view = new FileView(screen, theme, path, Files.size(path), file, ByteSource.of(text),
                    StandardCharsets.UTF_8, 0, "PDF text", true, true, true);
            view.pdf = true;
            view.textTop = view.reader.lineStart(pdf.textOffsetOfPage(pdf.page));
            if (!pdf.hasText()) {
                view.message = "This PDF has no extractable text (a scan?)";
            }
            return view;
        } catch (PreviewSource.ToolMissing e) {
            problem = e.getMessage() + "; set pdf.pdftotext in " + Config.defaultFile();
        } catch (IllegalStateException e) {
            problem = e.getMessage();
        }
        FileView view = open(screen, theme, path);
        if (view != null) {
            view.message = "No text view: " + problem;
        }
        return view;
    }

    private static FileView open(Screen screen, Theme theme, Path path) throws IOException {
        long size = Files.size(path);
        if (size == 0) {
            return null;
        }
        byte[] head = new byte[(int) Math.min(size, SNIFF_BYTES)];
        try (InputStream in = Files.newInputStream(path)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length) {
                head = java.util.Arrays.copyOf(head, read);
            }
        }
        ByteSource file = ByteSource.open(path);
        try {
            boolean utf16 = head.length >= 2
                    && ((head[0] == (byte) 0xFF && head[1] == (byte) 0xFE)
                    || (head[0] == (byte) 0xFE && head[1] == (byte) 0xFF));
            if (utf16) {
                if (size > MAX_CONVERT_BYTES) {
                    return new FileView(screen, theme, path, size, file, file, StandardCharsets.UTF_8, 0,
                            "UTF-16", false, false, false);
                }
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_16);
                return new FileView(screen, theme, path, size, file, ByteSource.of(text.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8, 0, "UTF-16", true, true, true);
            }
            boolean binary = looksBinary(head);
            long dataStart = 0;
            Charset charset = StandardCharsets.UTF_8;
            String name;
            if (head.length >= 3 && head[0] == (byte) 0xEF && head[1] == (byte) 0xBB && head[2] == (byte) 0xBF) {
                dataStart = 3;
                name = "UTF-8 BOM";
            } else if (isAscii(head)) {
                name = "ASCII";
            } else if (isUtf8(head, size == head.length)) {
                name = "UTF-8";
            } else {
                charset = TextCompare.fallbackCharset();
                name = charset.displayName();
            }
            return new FileView(screen, theme, path, size, file, file, charset, dataStart, name, true, false, !binary);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    private static boolean looksBinary(byte[] head) {
        int control = 0;
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
            if (b >= 0 && b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != '\f' && b != 0x08 && b != 0x1B) {
                control++;
            }
        }
        return control * 20L > head.length;
    }

    private static boolean isAscii(byte[] head) {
        for (byte b : head) {
            if (b < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUtf8(byte[] head, boolean complete) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return !decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.length + 1), complete).isError();
    }

    @Override
    public void close() throws IOException {
        fileSource.close();
        if (textSource != fileSource) {
            textSource.close();
        }
    }

    private void run() throws IOException {
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
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new KeyStroke(KeyType.Escape);
            }
        }
    }

    private int bodyHeight() {
        return Math.max(1, screen.getTerminalSize().getRows() - 3);
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
            case PageUp -> scroll(-(h - 1), h);
            case PageDown -> scroll(h - 1, h);
            case Home -> scrollToEdge(false, h);
            case End -> scrollToEdge(true, h);
            case ArrowLeft -> {
                if (!(textMode && wrap)) {
                    hscroll = Math.max(0, hscroll - 8);
                }
            }
            case ArrowRight -> {
                if (!(textMode && wrap)) {
                    hscroll += 8;
                }
            }
            case Tab -> {
                if (backToImage && !(pdf && textMode)) {
                    wantImage = true;
                    return false;
                }
                switchMode(!textMode);
            }
            case F7 -> startSearch();
            case F3 -> search(!key.isShiftDown());
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
                    case 'h' -> switchMode(false);
                    case 't' -> switchMode(true);
                    case '/', 'f' -> startSearch();
                    case 'n' -> search(true);
                    case 'p' -> search(false);
                    case 'g' -> goTo();
                    case ' ' -> scroll(h - 1, h);
                    case 'l' -> {
                        lineNumbers = !lineNumbers;
                        message = lineNumbers ? "" : "Line numbers off";
                    }
                    case 'w' -> toggleWrap();
                    default -> { }
                }
            }
            default -> { }
        }
        return true;
    }

    // ---- navigation ----

    private void scroll(int lines, int h) throws IOException {
        if (textMode && wrap) {
            Pos at = new Pos(textTop, textRow);
            if (lines > 0) {
                Pos limit = lastPos(h);
                for (int i = 0; i < lines; i++) {
                    Pos next = advance(at);
                    if (next == null || next.after(limit)) {
                        break;
                    }
                    at = next;
                }
            } else {
                at = moveUp(at, -lines);
            }
            textTop = at.top();
            textRow = at.row();
        } else if (textMode) {
            long limit = lastTop(h);
            if (lines > 0) {
                for (int i = 0; i < lines; i++) {
                    long next = reader.read(textTop).next();
                    if (next > limit || next >= textWindow.size()) {
                        break;
                    }
                    textTop = next;
                }
            } else {
                textTop = reader.back(textTop, -lines);
            }
        } else {
            hexTop = clampHex(hexTop + (long) lines * bytesPerRow, h);
        }
    }

    private void scrollToEdge(boolean end, int h) throws IOException {
        if (textMode && wrap) {
            Pos at = end ? lastPos(h) : new Pos(reader.dataStart(), 0);
            textTop = at.top();
            textRow = at.row();
        } else if (textMode) {
            textTop = end ? lastTop(h) : reader.dataStart();
        } else {
            hexTop = end ? clampHex(Long.MAX_VALUE / 4, h) : 0;
        }
    }

    /** The first line of the last full page. */
    private long lastTop(int h) throws IOException {
        long key = h * 1_000_000_007L + textWindow.size();
        if (lastTopFor != key) {
            lastTop = reader.back(textWindow.size(), h);
            lastTopFor = key;
        }
        return lastTop;
    }

    private long clampHex(long top, int h) {
        long lastRow = Math.max(0, (fileSize - 1) / bytesPerRow);
        long maxTop = Math.max(0, lastRow - (h - 1)) * bytesPerRow;
        return Math.max(0, Math.min(top / bytesPerRow * bytesPerRow, maxTop));
    }

    private void switchMode(boolean toText) throws IOException {
        if (toText == textMode) {
            return;
        }
        matchStart = -1;
        if (toText) {
            if (!textAvailable) {
                message = "Text view is not available for this file";
                return;
            }
            textTop = reader.lineStart(converted ? proportional(hexTop, fileSize, textWindow.size()) : hexTop);
            textRow = 0;
            textMode = true;
        } else {
            hexTop = (converted ? proportional(textTop, textWindow.size(), fileSize) : textTop);
            hexTop = clampHex(hexTop, bodyHeight());
            textMode = false;
        }
    }

    private static long proportional(long pos, long from, long to) {
        return from <= 0 ? 0 : (long) ((double) pos / from * to);
    }

    private void goTo() throws IOException {
        String input = prompt("Go to (offset, 0xHEX or NN%): ", "");
        if (input == null || input.isBlank()) {
            return;
        }
        long size = textMode ? textWindow.size() : fileSize;
        long target;
        try {
            String s = input.trim().toLowerCase(Locale.ROOT);
            if (s.endsWith("%")) {
                target = (long) (Double.parseDouble(s.substring(0, s.length() - 1)) / 100.0 * size);
            } else if (s.startsWith("0x")) {
                target = Long.parseLong(s.substring(2), 16);
            } else {
                target = Long.parseLong(s);
            }
        } catch (NumberFormatException e) {
            message = "Not a number: " + input;
            return;
        }
        target = Math.max(0, Math.min(target, size - 1));
        showAt(target, false);
    }

    /** Scrolls so that {@code offset} is visible; with {@code context} a third of a page is kept above it. */
    private void showAt(long offset, boolean context) throws IOException {
        int h = bodyHeight();
        if (textMode && wrap) {
            long start = reader.lineStart(offset);
            LineReader.Line line = reader.read(start);
            int index = Math.min(line.text().length(), new String(
                    textWindow.copy(start, (int) Math.min(offset - start, LineReader.CAP)), reader.charset()).length());
            int visual = Draw.expand(line.text(), index, index).from();
            int row = Wrap.rowOf(Wrap.rows(Draw.expand(line.text()), textWidth()), visual);
            Pos at = moveUp(new Pos(start, row), context ? h / 3 : 0);
            Pos last = lastPos(h);
            at = at.after(last) ? last : at;
            textTop = at.top();
            textRow = at.row();
        } else if (textMode) {
            long start = reader.lineStart(offset);
            textTop = Math.min(reader.back(start, context ? h / 3 : 0), lastTop(h));
        } else {
            hexTop = clampHex(offset / bytesPerRow * bytesPerRow - (context ? (long) (h / 3) * bytesPerRow : 0), h);
        }
    }

    // ---- wrapping ----

    private void toggleWrap() {
        if (!textMode) {
            message = "Wrap applies to the text view";
            return;
        }
        wrap = !wrap;
        textRow = 0;
        hscroll = 0;
        message = wrap ? "Wrap on" : "Wrap off";
    }

    /** Columns available to the text: the screen width without the line number gutter. */
    private int textWidth() {
        return Math.max(1, screen.getTerminalSize().getColumns() - (lineNumbers ? gutterDigits + 1 : 0));
    }

    private int rowCount(LineReader.Line line) {
        return Wrap.count(Draw.expand(line.text()), textWidth());
    }

    /** The next screen row, or null at the end of the text. */
    private Pos advance(Pos from) throws IOException {
        LineReader.Line line = reader.read(from.top());
        if (from.row() + 1 < rowCount(line)) {
            return new Pos(from.top(), from.row() + 1);
        }
        return line.next() >= textWindow.size() ? null : new Pos(line.next(), 0);
    }

    /** {@code rows} screen rows up from {@code from}, stopping at the start of the text. */
    private Pos moveUp(Pos from, int rows) throws IOException {
        long top = from.top();
        int row = from.row();
        for (int i = 0; i < rows; i++) {
            if (row > 0) {
                row--;
            } else if (top > reader.dataStart()) {
                top = reader.previous(top);
                row = rowCount(reader.read(top)) - 1;
            } else {
                break;
            }
        }
        return new Pos(top, row);
    }

    /** The first screen row of the last full page of {@code h} rows. */
    private Pos lastPos(int h) throws IOException {
        int width = textWidth();
        long size = textWindow.size();
        if (lastWrapPos == null || lastWrapH != h || lastWrapWidth != width || lastWrapSize != size) {
            Pos result = new Pos(reader.dataStart(), 0);
            long top = size;
            int need = h;
            while (need > 0 && top > reader.dataStart()) {
                long previous = reader.previous(top);
                int rows = rowCount(reader.read(previous));
                if (rows >= need) {
                    result = new Pos(previous, rows - need);
                    break;
                }
                need -= rows;
                top = previous;
            }
            lastWrapPos = result;
            lastWrapH = h;
            lastWrapWidth = width;
            lastWrapSize = size;
        }
        return lastWrapPos;
    }

    /** Keeps the first shown row inside the text: not below the last full page, not past the rows of its line. */
    private void clampTop(int body) throws IOException {
        if (!wrap) {
            textRow = 0;
            textTop = Math.max(reader.dataStart(), Math.min(textTop, lastTop(body)));
            return;
        }
        Pos last = lastPos(body);
        Pos at = new Pos(Math.max(reader.dataStart(), textTop), Math.max(0, textRow));
        if (at.after(last)) {
            at = last;
        } else {
            at = new Pos(at.top(), Math.min(at.row(), rowCount(reader.read(at.top())) - 1));
        }
        textTop = at.top();
        textRow = at.row();
    }

    // ---- search ----

    private void startSearch() throws IOException {
        String query = prompt("Find (text, or 0xHEX bytes): ", lastQuery);
        if (query == null || query.isEmpty()) {
            return;
        }
        lastQuery = query;
        matchStart = -1;
        search(true);
    }

    private byte[] needle() {
        String q = lastQuery.trim();
        if (q.regionMatches(true, 0, "0x", 0, 2)) {
            String hex = q.substring(2).replace(" ", "");
            if (hex.isEmpty() || hex.length() % 2 != 0) {
                return null;
            }
            byte[] out = new byte[hex.length() / 2];
            try {
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return out;
        }
        return lastQuery.getBytes(textMode ? reader.charset() : StandardCharsets.UTF_8);
    }

    private void search(boolean forward) throws IOException {
        if (lastQuery.isEmpty()) {
            startSearch();
            return;
        }
        byte[] needle = needle();
        if (needle == null || needle.length == 0) {
            message = "Bad hex bytes: " + lastQuery;
            return;
        }
        ByteWindow window = textMode ? textWindow : hexWindow;
        long size = window.size();
        long current = matchStart >= 0 ? matchStart : (textMode ? textTop : hexTop);
        message = "Searching... Esc cancels";
        draw();
        java.util.function.BooleanSupplier cancelled = () -> {
            try {
                KeyStroke key = screen.pollInput();
                return key != null && key.getKeyType() == KeyType.Escape;
            } catch (IOException e) {
                return false;
            }
        };
        boolean wrapped = false;
        long hit;
        if (forward) {
            hit = window.findForward(matchStart >= 0 ? current + 1 : current, size, needle, cancelled);
            if (hit == -1) {
                wrapped = true;
                hit = window.findForward(0, size, needle, cancelled);
            }
        } else {
            hit = window.findBackward(current - 1, 0, needle, cancelled);
            if (hit == -1) {
                wrapped = true;
                hit = window.findBackward(size, 0, needle, cancelled);
            }
        }
        if (hit == ByteWindow.CANCELLED) {
            message = "Search cancelled";
        } else if (hit < 0) {
            message = "Not found: " + lastQuery;
        } else {
            matchStart = hit;
            matchLength = needle.length;
            showAt(hit, true);
            message = (wrapped ? "Search wrapped - " : "") + "Found at 0x" + Long.toHexString(hit).toUpperCase();
        }
    }

    // ---- prompt ----

    private String prompt(String label, String initial) {
        promptLabel = label;
        promptInput = new LineInput(initial);
        try {
            while (true) {
                draw();
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

    // ---- drawing ----

    private void draw() {
        TextGraphics g = screen.newTextGraphics();
        int w = g.getSize().getColumns();
        int h = g.getSize().getRows();
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.text());
        g.fill(' ');
        if (w >= 30 && h >= 6) {
            try {
                if (textMode) {
                    drawText(g, w, h);
                } else {
                    drawHex(g, w, h);
                }
            } catch (IOException e) {
                Draw.cell(g, 0, h - 2, w, " Read error: " + e.getMessage(), theme.error(), theme.appBackground());
            }
        } else {
            Draw.cell(g, 0, 0, w, "Window too small", theme.accent(), theme.appBackground());
        }
        screen.setCursorPosition(null);
        try {
            screen.refresh();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void header(TextGraphics g, int w, String detail) {
        String tail = "  [" + detail + "]";
        Draw.cell(g, 0, 0, w, " " + Draw.shorten(path.toString(), Math.max(10, w - tail.length() - 2)) + tail,
                theme.headerText(), theme.headerBackground());
    }

    private void footer(TextGraphics g, int w, int h, String status, String keys) {
        if (promptInput != null) {
            promptInput.draw(g, h - 2, w, promptLabel, theme.accent(), theme.panelBackground());
        } else {
            Draw.cell(g, 0, h - 2, w, " " + (message.isEmpty() ? status : message), theme.muted(), theme.panelBackground());
        }
        Draw.cell(g, 0, h - 1, w, " " + keys, theme.text(), theme.appBackground());
    }

    private void drawText(TextGraphics g, int w, int h) throws IOException {
        int body = h - 3;
        clampTop(body);
        header(g, w, encoding + (converted ? ", converted" : "") + ", " + FileEntry.humanSize(fileSize));
        textX = 0;
        long topNumber = -1;
        if (lineNumbers) {
            topNumber = linesBefore(textTop);
            int digits = Math.max(gutterDigits, Long.toString(Math.max(0, topNumber) + body + 1).length());
            if (digits != gutterDigits) {
                // A wider gutter leaves less width for the text, so the rows of every line change.
                gutterDigits = digits;
                if (wrap) {
                    clampTop(body);
                    topNumber = linesBefore(textTop);
                }
            }
            textX = gutterDigits + 1;
        }
        int width = w - textX;
        long pos = textTop;
        int y = 0;
        boolean firstLine = true;
        while (y < body && pos < textWindow.size()) {
            LineReader.Line line = reader.read(pos);
            int from = -1;
            int to = -1;
            if (matchStart >= line.start() && matchStart < Math.max(line.next(), line.start() + 1)) {
                from = new String(textWindow.copy(line.start(), (int) (matchStart - line.start())), reader.charset())
                        .length();
                to = from + new String(textWindow.copy(matchStart, matchLength), reader.charset()).length();
            }
            Draw.Expanded shown = Draw.expand(line.text(), from, to);
            String text = shown.text();
            if (wrap) {
                int[] starts = Wrap.rows(text, width);
                int row = firstLine ? Math.min(textRow, starts.length - 1) : 0;
                for (; row < starts.length && y < body; row++, y++) {
                    if (lineNumbers) {
                        drawGutter(g, y + 1, line.start(), row > 0);
                    }
                    drawSlice(g, y + 1, text, starts[row], Wrap.end(starts, row, text.length()), shown.from(), shown.to());
                }
            } else {
                if (lineNumbers) {
                    drawGutter(g, y + 1, line.start(), false);
                }
                int start = Math.min(hscroll, text.length());
                drawSlice(g, y + 1, text, start, Math.min(text.length(), hscroll + width), shown.from(), shown.to());
                y++;
            }
            pos = line.next();
            firstLine = false;
        }
        long size = textWindow.size();
        boolean atEnd = wrap ? !lastPos(body).after(new Pos(textTop, textRow)) : pos >= size;
        String status = "Text   offset 0x" + Long.toHexString(textTop).toUpperCase() + "   "
                + (atEnd ? 100 : textTop * 100 / size) + "%"
                + (topNumber > 0 ? "   line " + topNumber : "")
                + (wrap ? "   wrap" : hscroll > 0 ? "   column " + (hscroll + 1) : "");
        footer(g, w, h, status, "Up/Down PgUp/PgDn/Space Home/End scroll" + (wrap ? "" : "   Left/Right pan")
                + "   F7 find   F3/N next   P prev   G goto   L lines   W wrap   "
                + (backToImage && !pdf ? "Tab image" : "Tab hex") + "   Esc close");
    }

    /** Number of line feeds before {@code pos}, counted from the nearest remembered position; -1 when too far. */
    private long linesBefore(long pos) throws IOException {
        if (Math.abs(pos - anchorPos) > FAR_LINE_COUNT) {
            return -1;
        }
        if (pos >= anchorPos) {
            anchorLines += textWindow.count(anchorPos, pos, (byte) '\n');
        } else {
            anchorLines -= textWindow.count(pos, anchorPos, (byte) '\n');
        }
        anchorPos = pos;
        return anchorLines + 1;
    }

    private void drawGutter(TextGraphics g, int y, long lineStart, boolean continuation) throws IOException {
        boolean realStart = !continuation && (lineStart <= reader.dataStart() || textWindow.at(lineStart - 1) == '\n');
        String number = "";
        if (realStart) {
            long n = linesBefore(lineStart);
            number = n < 0 ? "?" : Long.toString(n);
        }
        Draw.cell(g, 0, y, textX, " ".repeat(Math.max(0, gutterDigits - number.length())) + number + "│",
                theme.muted(), theme.appBackground());
    }

    /** Draws {@code text[start, end)} at the text column; {@code [from, to)} (when {@code from >= 0}) is highlighted. */
    private void drawSlice(TextGraphics g, int y, String text, int start, int end, int from, int to) {
        if (end <= start) {
            return;
        }
        int hl0 = Math.max(start, Math.min(from, end));
        int hl1 = Math.max(start, Math.min(to, end));
        boolean highlight = from >= 0 && hl1 > hl0;
        piece(g, y, text, start, highlight ? hl0 : end, theme.appBackground(), start);
        if (highlight) {
            piece(g, y, text, hl0, hl1, theme.match(), start);
            piece(g, y, text, hl1, end, theme.appBackground(), start);
        }
    }

    private void piece(TextGraphics g, int y, String text, int from, int to, TextColor bg, int origin) {
        if (to <= from) {
            return;
        }
        g.setForegroundColor(theme.text());
        g.setBackgroundColor(bg);
        g.putString(new TerminalPosition(textX + from - origin, y), text.substring(from, to));
    }

    private void drawHex(TextGraphics g, int w, int h) throws IOException {
        int body = h - 3;
        int offsetDigits = fileSize > 0xFFFFFFFFL ? 12 : 8;
        bytesPerRow = 4;
        for (int candidate : new int[] {16, 8}) {
            if (offsetDigits + 2 + candidate * 4 + (candidate == 16 ? 1 : 0) <= w) {
                bytesPerRow = candidate;
                break;
            }
        }
        hexTop = clampHex(hexTop, body);
        header(g, w, FileEntry.humanSize(fileSize) + ", hex");
        byte[] row = new byte[bytesPerRow];
        for (int y = 0; y < body; y++) {
            long offset = hexTop + (long) y * bytesPerRow;
            if (offset >= fileSize) {
                break;
            }
            int count = (int) Math.min(bytesPerRow, fileSize - offset);
            for (int i = 0; i < count; i++) {
                row[i] = (byte) hexWindow.at(offset + i);
            }
            drawHexRow(g, y + 1, offsetDigits, offset, row, count);
        }
        long shownEnd = Math.min(fileSize, hexTop + (long) body * bytesPerRow);
        String status = "Hex   offset 0x" + String.format("%0" + offsetDigits + "X", hexTop) + "   "
                + (shownEnd >= fileSize ? 100 : hexTop * 100 / fileSize) + "%";
        footer(g, w, h, status, "Up/Down PgUp/PgDn Home/End scroll   F7 find   F3/N next   P prev"
                + "   G goto" + (backToImage ? "   Tab image" : textAvailable ? "   Tab text" : "") + "   Esc close");
    }

    private void drawHexRow(TextGraphics g, int y, int offsetDigits, long offset, byte[] row, int count) {
        Draw.cell(g, 0, y, offsetDigits + 1, String.format("%0" + offsetDigits + "X ", offset),
                theme.muted(), theme.appBackground());
        int hexX = offsetDigits + 1;
        int asciiX = hexX + bytesPerRow * 3 + (bytesPerRow == 16 ? 1 : 0) + 1;
        for (int i = 0; i < count; i++) {
            long position = offset + i;
            boolean inMatch = matchStart >= 0 && position >= matchStart && position < matchStart + matchLength;
            int x = hexX + i * 3 + (bytesPerRow == 16 && i >= 8 ? 1 : 0);
            int value = row[i] & 0xFF;
            g.setForegroundColor(inMatch ? theme.text() : value == 0 ? theme.muted() : theme.text());
            g.setBackgroundColor(inMatch ? theme.match() : theme.appBackground());
            g.putString(new TerminalPosition(x, y), String.format("%02X", value));
            g.putString(new TerminalPosition(asciiX + i, y), value >= 0x20 && value < 0x7F ? String.valueOf((char) value) : "·");
        }
    }
}
