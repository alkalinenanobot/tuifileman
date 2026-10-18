package org.ds.tuifileman.compare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Line based comparison of two text files, shaped for a side-by-side view. */
public final class TextCompare {
    /** Larger files are only compared as binary. */
    public static final long MAX_TEXT_BYTES = 32L * 1024 * 1024;
    private static final int SNIFF_BYTES = 8192;

    public enum RowType { SAME, CHANGED, LEFT_ONLY, RIGHT_ONLY }

    /** One display row; left/right are line indexes or -1 when that side has no line here. */
    public record Row(RowType type, int left, int right) { }

    private final String[] leftLines;
    private final String[] rightLines;
    private final String leftEncoding;
    private final String rightEncoding;
    private List<Row> rows = List.of();
    private int[] hunkStarts = new int[0];
    private int added;
    private int removed;
    private int changed;

    private TextCompare(Decoded left, Decoded right) {
        this.leftLines = left.lines;
        this.rightLines = right.lines;
        this.leftEncoding = left.encoding;
        this.rightEncoding = right.encoding;
    }

    /** True when both files look like text and are small enough for the line diff. */
    public static boolean bothText(Path left, Path right) throws IOException {
        return looksLikeText(left) && looksLikeText(right);
    }

    private static boolean looksLikeText(Path file) throws IOException {
        if (Files.size(file) > MAX_TEXT_BYTES) {
            return false;
        }
        byte[] head = new byte[SNIFF_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read >= 2 && isUtf16Bom(head)) {
            return true;
        }
        for (int i = 0; i < read; i++) {
            if (head[i] == 0) {
                return false;
            }
        }
        return true;
    }

    public static TextCompare load(Path left, Path right) throws IOException {
        return new TextCompare(decode(Files.readAllBytes(left)), decode(Files.readAllBytes(right)));
    }

    /** Runs the diff; call again after changing the options. */
    public void compute(boolean ignoreWhitespace, boolean ignoreCase) {
        Map<String, Integer> ids = new HashMap<>();
        int[] a = ids(leftLines, ids, ignoreWhitespace, ignoreCase);
        int[] b = ids(rightLines, ids, ignoreWhitespace, ignoreCase);
        boolean[][] marks = LineDiff.diff(a, b);
        boolean[] changedA = marks[0];
        boolean[] changedB = marks[1];

        List<Row> result = new ArrayList<>(Math.max(a.length, b.length) + 16);
        added = 0;
        removed = 0;
        changed = 0;
        int i = 0;
        int j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && !changedA[i] && !changedB[j]) {
                result.add(new Row(RowType.SAME, i++, j++));
                continue;
            }
            int i2 = i;
            while (i2 < a.length && changedA[i2]) {
                i2++;
            }
            int j2 = j;
            while (j2 < b.length && changedB[j2]) {
                j2++;
            }
            int na = i2 - i;
            int nb = j2 - j;
            if (na == 0 && nb == 0) {
                break;
            }
            int paired = Math.min(na, nb);
            for (int t = 0; t < paired; t++) {
                result.add(new Row(RowType.CHANGED, i + t, j + t));
            }
            for (int t = paired; t < na; t++) {
                result.add(new Row(RowType.LEFT_ONLY, i + t, -1));
            }
            for (int t = paired; t < nb; t++) {
                result.add(new Row(RowType.RIGHT_ONLY, -1, j + t));
            }
            changed += paired;
            removed += na - paired;
            added += nb - paired;
            i = i2;
            j = j2;
        }
        rows = result;
        int[] starts = new int[result.size()];
        int count = 0;
        for (int r = 0; r < result.size(); r++) {
            boolean different = result.get(r).type() != RowType.SAME;
            if (different && (r == 0 || result.get(r - 1).type() == RowType.SAME)) {
                starts[count++] = r;
            }
        }
        hunkStarts = Arrays.copyOf(starts, count);
    }

    private static int[] ids(String[] lines, Map<String, Integer> ids, boolean ignoreWs, boolean ignoreCase) {
        int[] result = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            String key = lines[i];
            if (ignoreWs) {
                key = collapseWhitespace(key);
            }
            if (ignoreCase) {
                key = key.toLowerCase();
            }
            Integer id = ids.get(key);
            if (id == null) {
                id = ids.size();
                ids.put(key, id);
            }
            result[i] = id;
        }
        return result;
    }

    static String collapseWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    public List<Row> rows() {
        return rows;
    }

    /** Row indexes where a run of differing rows starts. */
    public int[] hunkStarts() {
        return hunkStarts;
    }

    public String leftLine(int index) {
        return leftLines[index];
    }

    public String rightLine(int index) {
        return rightLines[index];
    }

    public int leftCount() {
        return leftLines.length;
    }

    public int rightCount() {
        return rightLines.length;
    }

    public String leftEncoding() {
        return leftEncoding;
    }

    public String rightEncoding() {
        return rightEncoding;
    }

    public int added() {
        return added;
    }

    public int removed() {
        return removed;
    }

    public int changed() {
        return changed;
    }

    private record Decoded(String[] lines, String encoding) { }

    private static boolean isUtf16Bom(byte[] b) {
        return (b[0] == (byte) 0xFF && b[1] == (byte) 0xFE) || (b[0] == (byte) 0xFE && b[1] == (byte) 0xFF);
    }

    private static Decoded decode(byte[] bytes) {
        String text;
        String encoding;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            encoding = "UTF-8 BOM";
        } else if (bytes.length >= 2 && isUtf16Bom(bytes)) {
            text = new String(bytes, StandardCharsets.UTF_16);
            encoding = "UTF-16";
        } else {
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                encoding = "UTF-8";
            } catch (CharacterCodingException e) {
                Charset fallback = fallbackCharset();
                text = new String(bytes, fallback);
                encoding = fallback.displayName();
            }
        }
        return new Decoded(splitLines(text), encoding);
    }

    public static Charset fallbackCharset() {
        try {
            return Charset.forName(System.getProperty("native.encoding", "windows-1252"));
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    static String[] splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < length) {
            lines.add(text.substring(start));
        }
        return lines.toArray(new String[0]);
    }
}
