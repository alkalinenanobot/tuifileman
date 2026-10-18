package org.ds.tuifileman.view;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads format, dimensions and colour depth from image headers without decoding the picture and
 * without Java's image libraries. Used for the image info page when no preview can be rendered.
 */
final class ImageInfo {
    /** What the header says; {@code width}/{@code height} are 0 and {@code color} is empty when unknown. */
    record Info(String format, int width, int height, String color, List<String> notes) {
        String size() {
            return width > 0 && height > 0 ? width + " x " + height + " pixels" : "unknown";
        }

        String summary() {
            StringBuilder sb = new StringBuilder(format);
            if (width > 0 && height > 0) {
                sb.append(' ').append(width).append('x').append(height);
            }
            if (!color.isEmpty()) {
                sb.append(' ').append(color);
            }
            return sb.toString();
        }
    }

    private ImageInfo() { }

    /** Never throws: an unreadable or unknown header gives the format from the file extension and no size. */
    static Info read(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            Reader in = new Reader(file);
            Info info = parse(in, extension);
            if (info != null) {
                return info;
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to the extension guess
        }
        return new Info(extension.isEmpty() ? "unknown" : extension.toUpperCase(Locale.ROOT) + " (header not recognised)",
                0, 0, "", List.of());
    }

    private static Info parse(Reader in, String extension) throws IOException {
        byte[] h = in.at(0, 32);
        if (h.length >= 26 && h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return png(in, h);
        }
        if (h.length >= 4 && (h[0] & 255) == 0xFF && (h[1] & 255) == 0xD8) {
            return jpeg(in);
        }
        if (h.length >= 11 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F') {
            int flags = h[10] & 255;
            List<String> notes = new ArrayList<>();
            String color = (flags & 0x80) != 0 ? "indexed, " + (2 << (flags & 7)) + " colours" : "indexed";
            notes.add("GIF" + new String(h, 3, 3, StandardCharsets.US_ASCII) + "; animation frames are not counted");
            return new Info("GIF", le16(h, 6), le16(h, 8), color, notes);
        }
        if (h.length >= 26 && h[0] == 'B' && h[1] == 'M') {
            int dib = le32(h, 14);
            int w = dib == 12 ? le16(h, 18) : le32(h, 18);
            int ht = dib == 12 ? le16(h, 20) : Math.abs(le32(h, 22));
            int bpp = dib == 12 ? le16(h, 24) : le16(h, 28);
            return new Info("BMP", w, ht, bpp + "-bit", List.of());
        }
        if (h.length >= 30 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E') {
            return webp(h);
        }
        if (h.length >= 14 && h[0] == 'q' && h[1] == 'o' && h[2] == 'i' && h[3] == 'f') {
            int channels = h[12] & 255;
            return new Info("QOI", be32(h, 4), be32(h, 8), channels * 8 + "-bit " + (channels == 4 ? "RGBA" : "RGB"),
                    List.of((h[13] & 255) == 0 ? "sRGB with linear alpha" : "all channels linear"));
        }
        if (h.length >= 8 && ((h[0] == 'I' && h[1] == 'I' && h[2] == 42 && h[3] == 0)
                || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && h[3] == 42))) {
            return tiff(in, h);
        }
        if ("svg".equals(extension)) {
            return svg(in);
        }
        return null;
    }

    private static Info png(Reader in, byte[] h) throws IOException {
        int depth = h[24] & 255;
        int type = h[25] & 255;
        int channels = switch (type) {
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 1;
        };
        String model = switch (type) {
            case 0 -> "grayscale";
            case 2 -> "RGB";
            case 3 -> "indexed palette";
            case 4 -> "grayscale + alpha";
            case 6 -> "RGBA";
            default -> "type " + type;
        };
        String color = (type == 3 ? depth : depth * channels) + "-bit " + model;
        List<String> notes = new ArrayList<>();
        if ((h[28] & 255) != 0) {
            notes.add("interlaced (Adam7)");
        }
        long pos = 33;
        for (int i = 0; i < 64; i++) {
            byte[] chunk = in.at(pos, 12);
            if (chunk.length < 8) {
                break;
            }
            String kind = new String(chunk, 4, 4, StandardCharsets.US_ASCII);
            if (kind.equals("acTL") && chunk.length >= 12) {
                notes.add("animated PNG, " + be32(chunk, 8) + " frames");
            }
            if (kind.equals("IDAT") || kind.equals("IEND")) {
                break;
            }
            pos += 12L + be32(chunk, 0);
        }
        return new Info("PNG", be32(h, 16), be32(h, 20), color, notes);
    }

    private static Info jpeg(Reader in) throws IOException {
        long pos = 2;
        for (int i = 0; i < 300; i++) {
            byte[] m = in.at(pos, 4);
            if (m.length < 2 || (m[0] & 255) != 0xFF) {
                break;
            }
            int marker = m[1] & 255;
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
                continue;
            }
            if (m.length < 4) {
                break;
            }
            boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (frame) {
                byte[] d = in.at(pos + 4, 6);
                if (d.length < 6) {
                    break;
                }
                int precision = d[0] & 255;
                int components = d[5] & 255;
                String model = switch (components) {
                    case 1 -> "grayscale";
                    case 3 -> "YCbCr colour";
                    case 4 -> "CMYK";
                    default -> components + " components";
                };
                List<String> notes = new ArrayList<>();
                if (marker == 0xC2 || marker == 0xC6 || marker == 0xCA || marker == 0xCE) {
                    notes.add("progressive");
                }
                if (marker >= 0xC9) {
                    notes.add("arithmetic coding");
                }
                return new Info("JPEG", be16(d, 3), be16(d, 1), precision * components + "-bit " + model, notes);
            }
            pos += 2L + be16(m, 2);
        }
        return null;
    }

    private static Info webp(byte[] h) {
        String kind = new String(h, 12, 4, StandardCharsets.US_ASCII);
        switch (kind) {
            case "VP8 " -> {
                return new Info("WebP", le16(h, 26) & 0x3FFF, le16(h, 28) & 0x3FFF, "lossy", List.of());
            }
            case "VP8L" -> {
                int w = 1 + (((h[22] & 0x3F) << 8) | (h[21] & 255));
                int ht = 1 + (((h[24] & 0x0F) << 10) | ((h[23] & 255) << 2) | ((h[22] & 0xC0) >> 6));
                return new Info("WebP", w, ht, "lossless", List.of());
            }
            case "VP8X" -> {
                int flags = h[20] & 255;
                List<String> notes = new ArrayList<>();
                if ((flags & 0x02) != 0) {
                    notes.add("animated");
                }
                return new Info("WebP", 1 + le24(h, 24), 1 + le24(h, 27), (flags & 0x10) != 0 ? "with alpha" : "", notes);
            }
            default -> {
                return new Info("WebP", 0, 0, "", List.of());
            }
        }
    }

    private static Info tiff(Reader in, byte[] h) throws IOException {
        boolean little = h[0] == 'I';
        long ifd = u32(h, 4, little);
        byte[] count = in.at(ifd, 2);
        if (count.length < 2) {
            return null;
        }
        int entries = Math.min(u16(count, 0, little), 256);
        byte[] table = in.at(ifd + 2, entries * 12);
        int w = 0;
        int ht = 0;
        int bits = 0;
        int samples = 1;
        for (int i = 0; i + 12 <= table.length; i += 12) {
            int tag = u16(table, i, little);
            int type = u16(table, i + 2, little);
            long n = u32(table, i + 4, little);
            long value = type == 3 ? u16(table, i + 8, little) : u32(table, i + 8, little);
            switch (tag) {
                case 256 -> w = (int) value;
                case 257 -> ht = (int) value;
                case 258 -> {
                    if (n > 1) {
                        byte[] first = in.at(u32(table, i + 8, little), 2);
                        bits = first.length == 2 ? u16(first, 0, little) : 0;
                    } else {
                        bits = (int) value;
                    }
                }
                case 277 -> samples = (int) value;
                default -> { }
            }
        }
        return new Info("TIFF", w, ht, bits > 0 ? bits * samples + "-bit" : "", List.of());
    }

    private static Info svg(Reader in) throws IOException {
        String head = new String(in.at(0, 65536), StandardCharsets.UTF_8);
        Matcher tag = Pattern.compile("<svg\\b[^>]*>", Pattern.DOTALL).matcher(head);
        if (!tag.find()) {
            return null;
        }
        String attrs = tag.group();
        int w = dimension(attrs, "width");
        int ht = dimension(attrs, "height");
        Matcher box = Pattern.compile("viewBox\\s*=\\s*[\"']\\s*[-\\d.]+[ ,]+[-\\d.]+[ ,]+([\\d.]+)[ ,]+([\\d.]+)").matcher(attrs);
        List<String> notes = new ArrayList<>();
        if (box.find()) {
            if (w == 0 || ht == 0) {
                w = (int) Math.round(Double.parseDouble(box.group(1)));
                ht = (int) Math.round(Double.parseDouble(box.group(2)));
                notes.add("size taken from the viewBox");
            }
        }
        notes.add("vector image, scales to any size");
        return new Info("SVG", w, ht, "", notes);
    }

    private static int dimension(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*[\"']\\s*([\\d.]+)\\s*(px)?\\s*[\"']").matcher(attrs);
        if (m.find()) {
            try {
                return (int) Math.round(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int be16(byte[] b, int o) {
        return ((b[o] & 255) << 8) | (b[o + 1] & 255);
    }

    private static int be32(byte[] b, int o) {
        return (be16(b, o) << 16) | be16(b, o + 2);
    }

    private static int le16(byte[] b, int o) {
        return ((b[o + 1] & 255) << 8) | (b[o] & 255);
    }

    private static int le24(byte[] b, int o) {
        return ((b[o + 2] & 255) << 16) | le16(b, o);
    }

    private static int le32(byte[] b, int o) {
        return (le16(b, o + 2) << 16) | le16(b, o);
    }

    private static int u16(byte[] b, int o, boolean little) {
        return little ? le16(b, o) : be16(b, o);
    }

    private static long u32(byte[] b, int o, boolean little) {
        return (little ? le32(b, o) : be32(b, o)) & 0xFFFFFFFFL;
    }

    /** Random access reads that return fewer bytes (or none) at the end of the file. */
    private static final class Reader {
        private final RandomAccessFile file;

        Reader(RandomAccessFile file) {
            this.file = file;
        }

        byte[] at(long pos, int count) throws IOException {
            if (pos < 0 || pos >= file.length()) {
                return new byte[0];
            }
            byte[] buffer = new byte[(int) Math.min(count, file.length() - pos)];
            file.seek(pos);
            file.readFully(buffer);
            return buffer;
        }
    }
}
