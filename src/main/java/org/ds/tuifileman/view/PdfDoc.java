package org.ds.tuifileman.view;

import org.ds.tuifileman.config.Config;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A PDF opened for the F3 viewer. Everything is done by the optional Poppler tools: {@code pdfinfo} for the
 * metadata, {@code pdftoppm} to render a page (shown through the image path) and {@code pdftotext} for the
 * text view. Nothing here throws when a tool is missing at construction; the callers show the reason instead.
 */
final class PdfDoc implements PreviewSource, AutoCloseable {
    private static final String INSTALL = "Poppler installed? on PATH?";
    private static final int INFO_TIMEOUT = 15;
    private static final int RENDER_TIMEOUT = 40;
    private static final int TEXT_TIMEOUT = 120;
    private static final long MAX_TEXT = 64L << 20;
    private static final List<String> SHOWN = List.of("Title", "Author", "Subject", "Keywords", "Creator", "Producer",
            "CreationDate", "ModDate", "Pages", "Page size", "Encrypted", "PDF version");

    /** Current page, 1-based; kept between the page view and the text view. */
    int page = 1;

    private final Path path;
    private final String pdftoppm;
    private final String pdftotext;
    private final String pdfinfo;
    private final Map<String, String> info = new LinkedHashMap<>();
    private String infoError;
    private int pages;
    private Path tempDir;
    private int renderedPage = -1;
    private int renderedHeight = -1;
    private byte[] text;
    private long[] pageOffsets = new long[0];
    private boolean hasText;

    private PdfDoc(Path path) {
        Config config = Config.get();
        this.path = path;
        this.pdftoppm = Tools.resolve(config.text("pdf.pdftoppm", "pdftoppm").strip());
        this.pdftotext = Tools.resolve(config.text("pdf.pdftotext", "pdftotext").strip());
        this.pdfinfo = Tools.resolve(config.text("pdf.pdfinfo", "pdfinfo").strip());
        readInfo();
    }

    static boolean supports(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    static PdfDoc open(Path path) {
        return new PdfDoc(path);
    }

    /** Page count, or 0 when it could not be read. */
    int pages() {
        return pages;
    }

    @Override
    public String kind() {
        return "PDF";
    }

    @Override
    public int position() {
        return page;
    }

    @Override
    public void setPosition(int position) {
        page = position;
    }

    @Override
    public int count() {
        return pages;
    }

    @Override
    public String positionLabel() {
        return "page " + page + (pages > 0 ? "/" + pages : "");
    }

    @Override
    public String describe() {
        return pageSize();
    }

    @Override
    public String navigationHint() {
        return "Space/PgDn next page   PgUp prev   Home/End   G go to page";
    }

    @Override
    public String tabHint() {
        return "Tab text";
    }

    @Override
    public Path render(int pixelHeight) {
        return renderPage(page, pixelHeight);
    }

    @Override
    public String installHint() {
        return "Install Poppler (pdftoppm, pdftotext, pdfinfo) or set pdf.pdftoppm in";
    }

    /** Why the metadata is missing, or null. */
    @Override
    public String infoError() {
        return infoError;
    }

    /** Metadata rows (label, value) for the info page. */
    @Override
    public List<String[]> infoRows() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Format", "PDF"});
        for (String key : SHOWN) {
            String value = info.get(key);
            if (value != null && !value.isEmpty()) {
                rows.add(new String[] {key, value});
            }
        }
        return rows;
    }

    /** Short description for the status line, such as "595x842 pts". */
    String pageSize() {
        String size = info.getOrDefault("Page size", "");
        int pts = size.indexOf(" pts");
        return pts > 0 ? size.substring(0, pts).replace(" x ", "x") + " pts" : size;
    }

    private void readInfo() {
        try {
            Tools.Output out = Tools.run(List.of(pdfinfo, path.toString()), INFO_TIMEOUT, 1 << 20, "pdfinfo", INSTALL);
            for (String line : new String(out.data(), StandardCharsets.UTF_8).split("\\R")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    info.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
                }
            }
            try {
                pages = Integer.parseInt(info.getOrDefault("Pages", "0"));
            } catch (NumberFormatException e) {
                pages = 0;
            }
        } catch (RuntimeException e) {
            infoError = e.getMessage();
        }
    }

    /** Renders one page to a PNG with the given height in pixels; the file stays valid until the next render. */
    Path renderPage(int number, int pixelHeight) {
        try {
            if (tempDir == null) {
                tempDir = Files.createTempDirectory("tuifileman-pdf");
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot create a temporary folder: " + e.getMessage());
        }
        Path png = tempDir.resolve("page.png");
        if (number == renderedPage && pixelHeight == renderedHeight && Files.exists(png)) {
            return png;
        }
        Tools.run(List.of(pdftoppm, "-png", "-f", String.valueOf(number), "-l", String.valueOf(number), "-scale-to-y",
                String.valueOf(pixelHeight), "-scale-to-x", "-1", "-singlefile", path.toString(),
                tempDir.resolve("page").toString()), RENDER_TIMEOUT, 1 << 20, "pdftoppm", INSTALL);
        if (!Files.exists(png)) {
            throw new IllegalStateException("pdftoppm produced no image for page " + number);
        }
        renderedPage = number;
        renderedHeight = pixelHeight;
        return png;
    }

    /** The extracted text as UTF-8, each page introduced by a separator line; throws when it cannot be read. */
    byte[] text() {
        if (text != null) {
            return text;
        }
        Tools.Output out = Tools.run(List.of(pdftotext, "-layout", "-enc", "UTF-8", path.toString(), "-"), TEXT_TIMEOUT,
                MAX_TEXT, "pdftotext", INSTALL);
        String all = new String(out.data(), StandardCharsets.UTF_8);
        String[] parts = all.split("\f", -1);
        int count = parts.length > 1 && parts[parts.length - 1].isBlank() ? parts.length - 1 : parts.length;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(out.data().length + count * 32);
        long[] offsets = new long[count];
        boolean any = false;
        for (int i = 0; i < count; i++) {
            offsets[i] = bytes.size();
            bytes.writeBytes(("\u2500\u2500\u2500\u2500 page " + (i + 1) + (pages > 0 ? " of " + pages : "")
                    + " \u2500\u2500\u2500\u2500\n").getBytes(StandardCharsets.UTF_8));
            bytes.writeBytes(parts[i].getBytes(StandardCharsets.UTF_8));
            if (!parts[i].endsWith("\n")) {
                bytes.write('\n');
            }
            any |= !parts[i].isBlank();
        }
        if (out.truncated()) {
            bytes.writeBytes("\n[text cut off after 64 MB]\n".getBytes(StandardCharsets.UTF_8));
        }
        hasText = any;
        pageOffsets = offsets;
        text = bytes.toByteArray();
        return text;
    }

    /** False when the PDF holds no extractable text (for example a scan); valid after {@link #text()}. */
    boolean hasText() {
        return hasText;
    }

    /** Offset in the text of the separator line that starts a page (1-based). */
    long textOffsetOfPage(int number) {
        return pageOffsets.length == 0 ? 0 : pageOffsets[Math.max(0, Math.min(pageOffsets.length, number) - 1)];
    }

    /** The page that contains a text offset (1-based). */
    int pageAtTextOffset(long offset) {
        int result = 1;
        for (int i = 0; i < pageOffsets.length; i++) {
            if (pageOffsets[i] <= offset) {
                result = i + 1;
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (tempDir != null) {
            try (var files = Files.list(tempDir)) {
                files.forEach(f -> f.toFile().delete());
            } catch (IOException ignored) { }
            tempDir.toFile().delete();
            tempDir = null;
        }
    }
}
