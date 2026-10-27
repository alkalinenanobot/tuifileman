package org.ds.tuifileman.view;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.Terminal;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.model.FileEntry;
import org.ds.tuifileman.ui.Draw;
import org.ds.tuifileman.ui.Theme;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Image preview in the F3 viewer. The external tool {@code chafa} turns the picture into coloured
 * character cells for the current window size; the ANSI output is parsed into Lanterna cells, so the
 * picture is part of the normal screen and works in every terminal.
 */
final class ImageView {
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "svg", "tif", "tiff", "qoi",
            "bmp");
    private static final int TIMEOUT_SECONDS = 20;
    /** The cell grid chafa sizes a sixel picture on: a size of c x r cells is c*10 x r*20 pixels. */
    private static final int CHAFA_CELL_WIDTH = 10;
    private static final int CHAFA_CELL_HEIGHT = 20;
    private static final String CHAFA_HINT = "Install chafa (https://hpjansson.com/chafa/) or set image.chafa in";

    private final Screen screen;
    private final Theme theme;
    private final Path path;
    private final String chafa;
    private final String symbols;
    private final ImageInfo.Info info;
    private final PreviewSource preview;

    private String hint;
    private String notice = "";
    private boolean sixel;
    private boolean sixelDrawn;
    private byte[] sixelData = new byte[0];
    private int sixelCols;
    private int sixelRows;
    private List<List<TextCharacter>> picture = List.of();
    private String error;
    private int renderedCols = -1;
    private int renderedRows = -1;
    private final String cellSetting = Config.get().text("image.cell_size", "auto");
    /** Keys read while waiting for the terminal to report its cell size; handled before new input. */
    private final Deque<KeyStroke> pending = new ArrayDeque<>();
    private int cellWidth = CellSize.DEFAULT_WIDTH;
    private int cellHeight = CellSize.DEFAULT_HEIGHT;

    private ImageView(Screen screen, Theme theme, Path path, String chafa, String symbols, boolean sixel,
                      PreviewSource preview) {
        this.screen = screen;
        this.theme = theme;
        this.path = path;
        this.chafa = chafa;
        this.symbols = symbols;
        this.sixel = sixel;
        this.preview = preview;
        this.info = preview == null ? ImageInfo.read(path) : null;
    }

    static boolean supports(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** Shows the image; returns a status message, or null when the user asked for the raw (hex) view. */
    static String show(Screen screen, Theme theme, Path path) {
        return show(screen, theme, path, null);
    }

    /** Shows the image, or the current position of a PDF or video; null means "switch to the other view". */
    static String show(Screen screen, Theme theme, Path path, PreviewSource preview) {
        Config config = Config.get();
        ImageView view = new ImageView(screen, theme, path, Tools.resolve(config.text("image.chafa", "chafa").strip()),
                config.text("image.symbols", "half+quad+space").strip(),
                "sixel".equalsIgnoreCase(config.text("image.format", "sixel").strip()), preview);
        try {
            String result = view.run();
            view.wipe();
            return result;
        } catch (IOException e) {
            view.wipe();
            return "View failed: " + e.getMessage();
        }
    }

    /** Sixel pixels survive ordinary cell updates, so after one was shown the whole screen is rewritten. */
    private void wipe() {
        if (sixelDrawn) {
            try {
                screen.clear();
                screen.refresh(Screen.RefreshType.COMPLETE);
            } catch (IOException ignored) { }
            sixelDrawn = false;
        }
    }

    private String run() throws IOException {
        long size = Files.size(path);
        while (true) {
            draw(size);
            while (true) {
                var resized = screen.doResizeIfNecessary();
                KeyStroke key = pending.poll();
                if (key == null) {
                    key = screen.pollInput();
                }
                if (key == null) {
                    if (resized != null) {
                        break;
                    }
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "Viewed " + path.getFileName();
                    }
                    continue;
                }
                KeyType type = key.getKeyType();
                notice = "";
                if (preview != null) {
                    int target = pageKey(key);
                    if (target > 0) {
                        if (target != preview.position()) {
                            preview.setPosition(target);
                            renderedCols = -1;
                        }
                        break;
                    }
                    if (target < 0) {
                        break;
                    }
                }
                if (type == KeyType.EOF || type == KeyType.Escape || type == KeyType.F10) {
                    return "Viewed " + path.getFileName();
                }
                if (type == KeyType.Tab) {
                    return null;
                }
                if (type == KeyType.Character && !key.isCtrlDown() && !key.isAltDown()) {
                    char c = Character.toLowerCase(key.getCharacter());
                    if (c == 'q') {
                        return "Viewed " + path.getFileName();
                    }
                    if (c == 'h') {
                        return null;
                    }
                    if (c == 'p' && preview != null && preview.canPlay()) {
                        String result = preview.play(screen);
                        notice = result == null ? "" : result;
                        renderedCols = -1;
                        sixelDrawn = true;
                        break;
                    }
                    if (c == 's') {
                        sixel = !sixel;
                        renderedCols = -1;
                        break;
                    }
                }
            }
        }
    }

    /** Navigation keys of a PDF or video: the new position, 0 when the key is not one, -1 to just redraw. */
    private int pageKey(KeyStroke key) throws IOException {
        int pages = preview.count();
        int page = preview.position();
        int next = pages > 0 ? Math.min(pages, page + 1) : page + 1;
        int previous = Math.max(1, page - 1);
        switch (key.getKeyType()) {
            case PageDown, ArrowRight, ArrowDown -> {
                return next;
            }
            case PageUp, ArrowLeft, ArrowUp, Backspace -> {
                return previous;
            }
            case Home -> {
                return 1;
            }
            case End -> {
                return pages > 0 ? pages : page;
            }
            case Character -> {
                if (key.isCtrlDown() || key.isAltDown()) {
                    return 0;
                }
                char c = Character.toLowerCase(key.getCharacter());
                if (c == ' ' || c == 'n') {
                    return next;
                }
                if (c == 'p' && !preview.canPlay()) {
                    return previous;
                }
                if (c == 'g' && pages != 1) {
                    int target = askPage();
                    return target > 0 ? target : -1;
                }
                return 0;
            }
            default -> {
                return 0;
            }
        }
    }

    /** Small prompt on the footer line; returns the page number, or 0 when cancelled. */
    private int askPage() throws IOException {
        StringBuilder digits = new StringBuilder();
        int pages = preview.count();
        while (true) {
            TextGraphics g = screen.newTextGraphics();
            int w = g.getSize().getColumns();
            int h = g.getSize().getRows();
            Draw.cell(g, 0, h - 1, w, " Go to " + preview.positionLabel().split(" ")[0]
                    + (pages > 0 ? " (1-" + pages + ")" : "") + ": " + digits + "_",
                    theme.accent(), theme.appBackground());
            screen.refresh();
            KeyStroke key = screen.pollInput();
            if (key == null) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
                continue;
            }
            KeyType type = key.getKeyType();
            if (type == KeyType.Enter) {
                if (digits.length() == 0 || digits.length() > 6) {
                    return 0;
                }
                int value = Integer.parseInt(digits.toString());
                return pages > 0 ? Math.max(1, Math.min(pages, value)) : Math.max(1, value);
            }
            if (type == KeyType.Escape || type == KeyType.EOF) {
                return 0;
            }
            if (type == KeyType.Backspace && digits.length() > 0) {
                digits.setLength(digits.length() - 1);
            } else if (type == KeyType.Character && Character.isDigit(key.getCharacter()) && digits.length() < 6) {
                digits.append(key.getCharacter());
            }
        }
    }

    private static String part(String text) {
        return text == null || text.isEmpty() ? "" : "   " + text;
    }

    private void draw(long fileSize) {
        TextGraphics g = screen.newTextGraphics();
        int w = g.getSize().getColumns();
        int h = g.getSize().getRows();
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.text());
        g.fill(' ');
        int cols = w;
        int rows = Math.max(1, h - 3);
        if (w >= 30 && h >= 6) {
            if (cols != renderedCols || rows != renderedRows) {
                render(cols, rows);
            }
            Draw.cell(g, 0, 0, w, " " + Draw.shorten(path.toString(), Math.max(10, w - 30)) + "  ["
                    + (preview != null ? preview.kind() : "image") + ", " + FileEntry.humanSize(fileSize) + "]",
                    theme.headerText(), theme.headerBackground());
            if (error != null) {
                drawInfo(g, w, rows, fileSize);
            } else {
                paint(g, w, rows);
            }
            String rendering = error != null ? "no preview"
                    : sixel ? "sixel " + sixelCols + "x" + sixelRows + " cells"
                    : picture.stream().mapToInt(List::size).max().orElse(0) + "x" + picture.size() + " cells "
                    + symbols;
            String what = preview != null
                    ? " " + preview.kind() + part(preview.positionLabel()) + part(preview.describe())
                    : " Image   " + info.summary();
            Draw.cell(g, 0, h - 2, w, what + "   " + (notice.isEmpty() ? rendering : notice), theme.muted(),
                    theme.panelBackground());
            String keys = preview != null
                    ? " " + (preview.navigationHint().isEmpty() ? "" : preview.navigationHint() + "   ")
                    + (preview.canPlay() ? "P play   " : "") + preview.tabHint()
                    : " Tab hex view";
            Draw.cell(g, 0, h - 1, w, keys
                    + (error == null ? "   S " + (sixel ? "block characters" : "sixel pixels") : "") + "   Esc close",
                    theme.text(), theme.appBackground());
        } else {
            Draw.cell(g, 0, 0, w, "Window too small", theme.accent(), theme.appBackground());
        }
        screen.setCursorPosition(null);
        try {
            screen.refresh(sixelDrawn || sixel ? Screen.RefreshType.COMPLETE : Screen.RefreshType.AUTOMATIC);
            sixelDrawn = false;
            if (sixel && error == null && w >= 30 && h >= 6) {
                drawSixel(w, h - 3);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** What the header tells, shown instead of the picture when it cannot be rendered. */
    private void drawInfo(TextGraphics g, int w, int bodyRows, long fileSize) {
        List<String[]> rows = new ArrayList<>();
        if (preview != null) {
            rows.addAll(preview.infoRows());
        } else {
            rows.add(new String[] {"Format", info.format()});
            String size = info.size();
            if (info.width() > 0 && info.height() > 0) {
                size += String.format(Locale.ROOT, "  (%.2f megapixels)", info.width() * (double) info.height() / 1e6);
            }
            rows.add(new String[] {"Size", size});
            if (!info.color().isEmpty()) {
                rows.add(new String[] {"Colour", info.color()});
            }
            for (String note : info.notes()) {
                rows.add(new String[] {"", note});
            }
        }
        rows.add(new String[] {"File", FileEntry.humanSize(fileSize) + String.format(Locale.ROOT, "  (%,d bytes)", fileSize)});
        try {
            rows.add(new String[] {"Modified", java.time.LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(), java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))});
        } catch (IOException ignored) { }
        List<String> reason = new ArrayList<>();
        reason.add("No preview: " + error);
        if (hint != null) {
            reason.addAll(List.of(hint.split("\n")));
            reason.add(Config.defaultFile().toString());
        }
        if (preview != null && preview.infoError() != null && !error.equals(preview.infoError())) {
            reason.add("Info: " + preview.infoError());
        }
        int limit = Math.max(4, bodyRows - reason.size() - 1);
        int y = 2;
        for (String[] row : rows) {
            if (y >= limit) {
                break;
            }
            Draw.cell(g, 2, y, 11, row[0], theme.muted(), theme.appBackground());
            Draw.cell(g, 13, y, Math.max(0, w - 15), row[1], theme.text(), theme.appBackground());
            y++;
        }
        y++;
        for (String line : reason) {
            if (y < bodyRows) {
                Draw.cell(g, 2, y++, Math.max(0, w - 4), line, theme.warning(), theme.appBackground());
            }
        }
    }

    /** Writes the sixel picture straight to the terminal, centred in the body, after the cells are flushed. */
    private void drawSixel(int w, int bodyRows) throws IOException {
        if (!(screen instanceof TerminalScreen terminalScreen) || sixelData.length == 0) {
            return;
        }
        Terminal terminal = terminalScreen.getTerminal();
        int x0 = Math.max(0, (w - sixelCols) / 2);
        int y0 = 1 + Math.max(0, (bodyRows - sixelRows) / 2);
        terminal.setCursorPosition(x0, y0);
        terminal.flush();
        System.out.write(sixelData);
        System.out.flush();
        terminal.setCursorPosition(0, 0);
        terminal.flush();
        sixelDrawn = true;
    }

    private void paint(TextGraphics g, int w, int bodyRows) {
        if (sixel) {
            return;
        }
        int width = picture.stream().mapToInt(List::size).max().orElse(0);
        int x0 = Math.max(0, (w - width) / 2);
        int y0 = 1 + Math.max(0, (bodyRows - picture.size()) / 2);
        for (int row = 0; row < picture.size() && row < bodyRows; row++) {
            List<TextCharacter> cells = picture.get(row);
            for (int col = 0; col < cells.size() && x0 + col < w; col++) {
                g.setCharacter(x0 + col, y0 + row, cells.get(col));
            }
        }
    }

    private void render(int cols, int rows) {
        renderedCols = cols;
        renderedRows = rows;
        error = null;
        hint = null;
        picture = List.of();
        sixelData = new byte[0];
        if (sixel) {
            try {
                int[] cell = CellSize.detect(screen, cellSetting, pending);
                cellWidth = cell[0];
                cellHeight = cell[1];
            } catch (IOException e) {
                cellWidth = CellSize.DEFAULT_WIDTH;
                cellHeight = CellSize.DEFAULT_HEIGHT;
            }
        }
        if (chafa.isEmpty()) {
            error = "image.chafa is empty in the configuration";
            hint = CHAFA_HINT;
            return;
        }
        try {
            Path source = path;
            if (preview != null) {
                try {
                    source = preview.render(Math.max(300, Math.min(3000, rows * cellHeight)));
                } catch (PreviewSource.ToolMissing e) {
                    error = e.getMessage();
                    hint = preview.installHint();
                    return;
                }
            }
            // chafa counts cells of 10x20 pixels; ask for as many of those as the real cells cover
            byte[] data = sixel
                    ? runChafa(Math.max(1, cols * cellWidth / CHAFA_CELL_WIDTH),
                            Math.max(1, rows * cellHeight / CHAFA_CELL_HEIGHT), source)
                    : runChafa(cols, rows, source);
            if (sixel) {
                sixelData = sixelPicture(data);
                if (sixelData.length == 0) {
                    error = "chafa produced no sixel output for " + path.getFileName();
                }
            } else {
                picture = parse(new String(data, StandardCharsets.UTF_8), theme.text(), theme.appBackground());
                if (picture.isEmpty()) {
                    error = "chafa produced no output for " + path.getFileName();
                }
            }
        } catch (IOException e) {
            error = "cannot start '" + chafa + "' (installed? on PATH?)";
            hint = CHAFA_HINT;
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /**
     * Keeps the DCS...ST sequence, drops the trailing newline and reads the picture size from the raster
     * attributes ({@code "1;1;width;height}) and converts it to cells of the terminal's real size.
     */
    private byte[] sixelPicture(byte[] data) {
        int end = data.length;
        while (end > 0 && (data[end - 1] == '\n' || data[end - 1] == '\r')) {
            end--;
        }
        byte[] trimmed = java.util.Arrays.copyOf(data, end);
        String head = new String(trimmed, 0, Math.min(trimmed.length, 80), StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"\\d+;\\d+;(\\d+);(\\d+)").matcher(head);
        if (trimmed.length < 8 || !m.find()) {
            return new byte[0];
        }
        sixelCols = (Integer.parseInt(m.group(1)) + cellWidth - 1) / cellWidth;
        sixelRows = (Integer.parseInt(m.group(2)) + cellHeight - 1) / cellHeight;
        return trimmed;
    }

    private byte[] runChafa(int cols, int rows, Path source) throws IOException {
        List<String> command = new ArrayList<>(List.of(chafa, "-f", sixel ? "sixels" : "symbols", "--probe", "off",
                "--polite", "on", "--animate", "off", "-s", cols + "x" + rows));
        if (!sixel) {
            command.addAll(List.of("-c", "full", "--symbols", symbols, "--bg", hex(theme.appBackground())));
        }
        command.add(source.toString());
        Process process = new ProcessBuilder(command).start();
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        try {
            byte[] data = out.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                String message = new String(err.getNow(new byte[0]), StandardCharsets.UTF_8).strip();
                throw new IllegalStateException("chafa failed: " + (message.isEmpty() ? "exit " + process.exitValue()
                        : message.lines().findFirst().orElse(message)));
            }
            return data;
        } catch (TimeoutException e) {
            throw new IllegalStateException("chafa did not finish in " + TIMEOUT_SECONDS + " s");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } finally {
            process.destroy();
        }
    }

    private static byte[] read(java.io.InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String hex(TextColor color) {
        if (color instanceof TextColor.RGB rgb) {
            return String.format("%02X%02X%02X", rgb.getRed(), rgb.getGreen(), rgb.getBlue());
        }
        return "000000";
    }

    /** Parses ANSI text (SGR colours and newlines only) into rows of cells. */
    static List<List<TextCharacter>> parse(String ansi, TextColor defaultFg, TextColor defaultBg) {
        List<List<TextCharacter>> rows = new ArrayList<>();
        List<TextCharacter> row = new ArrayList<>();
        TextColor fg = defaultFg;
        TextColor bg = defaultBg;
        int i = 0;
        while (i < ansi.length()) {
            char c = ansi.charAt(i);
            if (c == 0x1B) {
                if (i + 1 < ansi.length() && ansi.charAt(i + 1) == '[') {
                    int end = i + 2;
                    while (end < ansi.length() && (ansi.charAt(end) < 0x40 || ansi.charAt(end) > 0x7E)) {
                        end++;
                    }
                    if (end < ansi.length() && ansi.charAt(end) == 'm') {
                        TextColor[] colors = applySgr(ansi.substring(i + 2, end), fg, bg, defaultFg, defaultBg);
                        fg = colors[0];
                        bg = colors[1];
                    }
                    i = end + 1;
                } else {
                    i += 2;
                }
            } else if (c == '\n') {
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else if (c == '\r' || c < 0x20) {
                i++;
            } else {
                row.add(new TextCharacter(Character.isSurrogate(c) ? ' ' : c, fg, bg));
                i++;
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        while (!rows.isEmpty() && rows.get(rows.size() - 1).isEmpty()) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    private static TextColor[] applySgr(String params, TextColor fg, TextColor bg, TextColor defaultFg,
                                        TextColor defaultBg) {
        String[] parts = params.isEmpty() ? new String[] {"0"} : params.split(";", -1);
        for (int p = 0; p < parts.length; p++) {
            int code = parts[p].isEmpty() ? 0 : parseInt(parts[p]);
            switch (code) {
                case 0 -> {
                    fg = defaultFg;
                    bg = defaultBg;
                }
                case 39 -> fg = defaultFg;
                case 49 -> bg = defaultBg;
                case 38, 48 -> {
                    TextColor color = null;
                    if (p + 1 < parts.length && "2".equals(parts[p + 1]) && p + 4 < parts.length) {
                        color = new TextColor.RGB(parseInt(parts[p + 2]) & 255, parseInt(parts[p + 3]) & 255,
                                parseInt(parts[p + 4]) & 255);
                        p += 4;
                    } else if (p + 2 < parts.length && "5".equals(parts[p + 1])) {
                        color = new TextColor.Indexed(parseInt(parts[p + 2]) & 255);
                        p += 2;
                    }
                    if (color != null) {
                        if (code == 38) {
                            fg = color;
                        } else {
                            bg = color;
                        }
                    }
                }
                default -> { }
            }
        }
        return new TextColor[] {fg, bg};
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
