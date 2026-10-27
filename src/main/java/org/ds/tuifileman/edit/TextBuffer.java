package org.ds.tuifileman.edit;

import org.ds.tuifileman.compare.TextCompare;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The document of the F4 editor: lines of text in memory, the encoding and line ending the file came with
 * (kept when saving) and an undo history. All text handed in or out uses {@code \n} as the line break.
 */
public final class TextBuffer {
    public static final long MAX_BYTES = 32L << 20;
    private static final int MAX_UNDO_GROUPS = 10_000;
    private static final int BINARY_SNIFF = 8192;

    /** A position: zero-based line and the character offset in it. */
    public record Pos(int line, int col) implements Comparable<Pos> {
        @Override
        public int compareTo(Pos other) {
            return line != other.line ? Integer.compare(line, other.line) : Integer.compare(col, other.col);
        }
    }

    /** The file holds binary data and cannot be edited as text. */
    public static final class NotTextException extends IOException {
        NotTextException(String message) {
            super(message);
        }
    }

    private record Edit(Pos from, String removed, String inserted) { }

    private final List<String> lines = new ArrayList<>();
    private final ArrayDeque<List<Edit>> undoStack = new ArrayDeque<>();
    private final ArrayDeque<List<Edit>> redoStack = new ArrayDeque<>();
    private Charset charset = StandardCharsets.UTF_8;
    private boolean bom;
    private String eol = System.lineSeparator();
    private boolean finalNewline = true;
    private boolean mixedEol;
    private int groupDepth;
    private List<Edit> openGroup;
    private boolean sealed;
    private List<Edit> savedTop;
    private long diskModified = -1;
    private long diskSize = -1;

    private TextBuffer() {
        lines.add("");
    }

    /** An empty document for a file that does not exist yet. */
    public static TextBuffer empty() {
        return new TextBuffer();
    }

    public static TextBuffer load(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_BYTES) {
            throw new IOException("larger than " + (MAX_BYTES >> 20) + " MB - use F3 to view it");
        }
        TextBuffer buffer = new TextBuffer();
        buffer.decode(Files.readAllBytes(path));
        buffer.stamp(path);
        return buffer;
    }

    /** Builds a document from text (tests and tools); line breaks of any kind are accepted. */
    public static TextBuffer of(String text) {
        TextBuffer buffer = new TextBuffer();
        buffer.split(text);
        return buffer;
    }

    private void decode(byte[] data) throws IOException {
        int offset = 0;
        if (data.length >= 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            bom = true;
            offset = 2;
        } else if (data.length >= 2 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            bom = true;
            offset = 2;
        } else if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) {
            bom = true;
            offset = 3;
        } else {
            for (int i = 0; i < Math.min(data.length, BINARY_SNIFF); i++) {
                if (data[i] == 0) {
                    throw new NotTextException("looks like a binary file - use F3 for the hex view");
                }
            }
        }
        ByteBuffer bytes = ByteBuffer.wrap(data, offset, data.length - offset);
        String text;
        try {
            text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
        } catch (CharacterCodingException e) {
            charset = TextCompare.fallbackCharset();
            text = new String(data, offset, data.length - offset, charset);
        }
        split(text);
    }

    private void split(String text) {
        lines.clear();
        int crlf = 0;
        int lf = 0;
        int cr = 0;
        int start = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(text.substring(start, i));
                lf++;
                start = i + 1;
            } else if (c == '\r') {
                lines.add(text.substring(start, i));
                if (i + 1 < length && text.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
                start = i + 1;
            }
        }
        finalNewline = length == 0 || start == length;
        if (start < length || lines.isEmpty()) {
            lines.add(text.substring(start));
        }
        mixedEol = (crlf > 0 ? 1 : 0) + (lf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0) > 1;
        if (crlf > 0 && crlf >= lf && crlf >= cr) {
            eol = "\r\n";
        } else if (lf > 0 && lf >= cr) {
            eol = "\n";
        } else if (cr > 0) {
            eol = "\r";
        }
    }

    // ---- information ----

    public int lineCount() {
        return lines.size();
    }

    public String line(int index) {
        return lines.get(index);
    }

    public Pos end() {
        return new Pos(lines.size() - 1, lines.get(lines.size() - 1).length());
    }

    public String encodingName() {
        if (charset.equals(StandardCharsets.UTF_8)) {
            return bom ? "UTF-8 BOM" : "UTF-8";
        }
        if (charset.equals(StandardCharsets.UTF_16LE)) {
            return "UTF-16 LE";
        }
        if (charset.equals(StandardCharsets.UTF_16BE)) {
            return "UTF-16 BE";
        }
        return charset.displayName();
    }

    public String lineEnding() {
        return eol.equals("\r\n") ? "CRLF" : eol.equals("\n") ? "LF" : "CR";
    }

    /** The file had more than one kind of line ending; they are all written as {@link #lineEnding()}. */
    public boolean mixedLineEndings() {
        return mixedEol;
    }

    public boolean dirty() {
        return undoStack.peek() != savedTop;
    }

    public Pos clamp(Pos pos) {
        int line = Math.max(0, Math.min(pos.line(), lines.size() - 1));
        int col = Math.max(0, Math.min(pos.col(), lines.get(line).length()));
        return new Pos(line, col);
    }

    /** The text between two positions (in either order). */
    public String text(Pos a, Pos b) {
        Pos from = clamp(a);
        Pos to = clamp(b);
        if (to.compareTo(from) < 0) {
            Pos swap = from;
            from = to;
            to = swap;
        }
        if (from.line() == to.line()) {
            return lines.get(from.line()).substring(from.col(), to.col());
        }
        StringBuilder out = new StringBuilder(lines.get(from.line()).substring(from.col()));
        for (int i = from.line() + 1; i < to.line(); i++) {
            out.append('\n').append(lines.get(i));
        }
        return out.append('\n').append(lines.get(to.line()), 0, to.col()).toString();
    }

    public String fullText() {
        return String.join("\n", lines);
    }

    /** Where text inserted at {@code from} ends. */
    public static Pos endOf(Pos from, String text) {
        int newlines = 0;
        int last = -1;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            newlines++;
            last = i;
        }
        return newlines == 0 ? new Pos(from.line(), from.col() + text.length())
                : new Pos(from.line() + newlines, text.length() - last - 1);
    }

    // ---- editing ----

    /** Replaces the range [a, b) by {@code text}; returns the position after the inserted text. */
    public Pos replace(Pos a, Pos b, String text) {
        Pos from = clamp(a);
        Pos to = clamp(b);
        if (to.compareTo(from) < 0) {
            Pos swap = from;
            from = to;
            to = swap;
        }
        String inserted = text.indexOf('\r') >= 0 ? text.replace("\r\n", "\n").replace('\r', '\n') : text;
        String removed = text(from, to);
        if (removed.isEmpty() && inserted.isEmpty()) {
            return from;
        }
        Pos end = apply(from, to, inserted);
        record(new Edit(from, removed, inserted));
        return end;
    }

    private Pos apply(Pos from, Pos to, String text) {
        String head = lines.get(from.line()).substring(0, from.col());
        String tail = lines.get(to.line()).substring(to.col());
        if (text.indexOf('\n') < 0) {
            lines.subList(from.line() + 1, to.line() + 1).clear();
            lines.set(from.line(), head + text + tail);
            return new Pos(from.line(), head.length() + text.length());
        }
        String[] parts = text.split("\n", -1);
        List<String> fresh = new ArrayList<>(parts.length);
        fresh.add(head + parts[0]);
        for (int i = 1; i < parts.length - 1; i++) {
            fresh.add(parts[i]);
        }
        String last = parts[parts.length - 1];
        fresh.add(last + tail);
        List<String> range = lines.subList(from.line(), to.line() + 1);
        range.clear();
        range.addAll(fresh);
        return new Pos(from.line() + parts.length - 1, last.length());
    }

    private void record(Edit edit) {
        redoStack.clear();
        if (groupDepth > 0) {
            openGroup.add(edit);
            return;
        }
        List<Edit> top = undoStack.peek();
        if (!sealed && top != null && top.size() == 1) {
            Edit merged = merge(top.get(0), edit);
            if (merged != null) {
                top.set(0, merged);
                return;
            }
        }
        pushGroup(new ArrayList<>(List.of(edit)));
    }

    private void pushGroup(List<Edit> group) {
        undoStack.push(group);
        while (undoStack.size() > MAX_UNDO_GROUPS) {
            undoStack.removeLast();
        }
        sealed = false;
    }

    /** Typing, Backspace and Delete in a row become one undo step. */
    private static Edit merge(Edit prev, Edit next) {
        boolean singleLines = prev.inserted().indexOf('\n') < 0 && prev.removed().indexOf('\n') < 0
                && next.inserted().indexOf('\n') < 0 && next.removed().indexOf('\n') < 0;
        if (!singleLines) {
            return null;
        }
        if (prev.removed().isEmpty() && next.removed().isEmpty() && !prev.inserted().isEmpty()
                && !next.inserted().isEmpty() && next.from().equals(endOf(prev.from(), prev.inserted()))) {
            return new Edit(prev.from(), "", prev.inserted() + next.inserted());
        }
        if (prev.inserted().isEmpty() && next.inserted().isEmpty() && !prev.removed().isEmpty()
                && !next.removed().isEmpty()) {
            if (endOf(next.from(), next.removed()).equals(prev.from())) {
                return new Edit(next.from(), next.removed() + prev.removed(), "");
            }
            if (next.from().equals(prev.from())) {
                return new Edit(prev.from(), prev.removed() + next.removed(), "");
            }
        }
        return null;
    }

    /** Makes the next change a new undo step (after moving the cursor or a command). */
    public void breakUndo() {
        sealed = true;
    }

    /** Edits between begin and end form one undo step. */
    public void beginGroup() {
        if (groupDepth++ == 0) {
            openGroup = new ArrayList<>();
        }
    }

    public void endGroup() {
        if (--groupDepth == 0) {
            if (!openGroup.isEmpty()) {
                pushGroup(openGroup);
            }
            openGroup = null;
            sealed = true;
        }
    }

    /** Undoes one step; returns the cursor position afterwards, or null when there is nothing to undo. */
    public Pos undo() {
        List<Edit> group = undoStack.poll();
        if (group == null) {
            return null;
        }
        Pos cursor = new Pos(0, 0);
        for (int i = group.size() - 1; i >= 0; i--) {
            Edit e = group.get(i);
            apply(e.from(), endOf(e.from(), e.inserted()), e.removed());
            cursor = endOf(e.from(), e.removed());
        }
        redoStack.push(group);
        sealed = true;
        return cursor;
    }

    public Pos redo() {
        List<Edit> group = redoStack.poll();
        if (group == null) {
            return null;
        }
        Pos cursor = new Pos(0, 0);
        for (Edit e : group) {
            apply(e.from(), endOf(e.from(), e.removed()), e.inserted());
            cursor = endOf(e.from(), e.inserted());
        }
        undoStack.push(group);
        sealed = true;
        return cursor;
    }

    // ---- searching ----

    /**
     * Finds {@code query} within a line, starting at or after {@code from} (or ending at or before it when
     * going backwards); wraps around the document. Returns the start of the match or null.
     */
    public Pos find(String query, Pos from, boolean forward, boolean matchCase) {
        if (query.isEmpty()) {
            return null;
        }
        int count = lines.size();
        Pos start = clamp(from);
        for (int step = 0; step <= count; step++) {
            int line = forward ? (start.line() + step) % count : ((start.line() - step) % count + count) % count;
            String text = lines.get(line);
            int hit;
            if (forward) {
                hit = indexOf(text, query, step == 0 ? start.col() : 0, matchCase);
            } else {
                int limit = step == 0 ? start.col() : text.length();
                hit = lastIndexOf(text, query, limit - query.length(), matchCase);
            }
            if (hit >= 0) {
                return new Pos(line, hit);
            }
        }
        return null;
    }

    private static int indexOf(String text, String query, int from, boolean matchCase) {
        if (matchCase) {
            return text.indexOf(query, from);
        }
        for (int i = Math.max(0, from); i + query.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, query, 0, query.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(String text, String query, int from, boolean matchCase) {
        if (matchCase) {
            return from < 0 ? -1 : text.lastIndexOf(query, from);
        }
        for (int i = Math.min(from, text.length() - query.length()); i >= 0; i--) {
            if (text.regionMatches(true, i, query, 0, query.length())) {
                return i;
            }
        }
        return -1;
    }

    // ---- saving ----

    /** Whether every character can be stored in the current encoding. */
    public boolean canEncode() {
        var encoder = charset.newEncoder();
        for (String line : lines) {
            if (!encoder.canEncode(line)) {
                return false;
            }
        }
        return true;
    }

    /** Switches to UTF-8 (without BOM unless the file had one) - for characters the old encoding lacks. */
    public void useUtf8() {
        if (!charset.equals(StandardCharsets.UTF_8)) {
            bom = false;
        }
        charset = StandardCharsets.UTF_8;
    }

    /** The file on disk was changed (or removed) by someone else since it was loaded or last saved. */
    public boolean changedOnDisk(Path path) {
        if (diskModified < 0) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis() != diskModified || Files.size(path) != diskSize;
        } catch (IOException e) {
            return true;
        }
    }

    /** Writes the document; returns the number of bytes written. */
    public int save(Path path) throws IOException {
        byte[] bytes = encode();
        Files.write(path, bytes);
        sealed = true;
        savedTop = undoStack.peek();
        stamp(path);
        return bytes.length;
    }

    byte[] encode() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                text.append(eol);
            }
            text.append(lines.get(i));
        }
        boolean empty = lines.size() == 1 && lines.get(0).isEmpty();
        if (finalNewline && !empty) {
            text.append(eol);
        }
        ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.remaining() + 3);
        if (bom) {
            if (charset.equals(StandardCharsets.UTF_16LE)) {
                out.write(0xFF);
                out.write(0xFE);
            } else if (charset.equals(StandardCharsets.UTF_16BE)) {
                out.write(0xFE);
                out.write(0xFF);
            } else {
                out.write(0xEF);
                out.write(0xBB);
                out.write(0xBF);
            }
        }
        out.write(encoded.array(), encoded.arrayOffset() + encoded.position(), encoded.remaining());
        return out.toByteArray();
    }

    private void stamp(Path path) {
        try {
            diskModified = Files.getLastModifiedTime(path).toMillis();
            diskSize = Files.size(path);
        } catch (IOException e) {
            diskModified = -1;
            diskSize = -1;
        }
    }
}
