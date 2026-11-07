package org.ds.tuifileman.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextBufferTest {
    @TempDir
    Path dir;

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private byte[] roundTrip(byte[] original) throws Exception {
        Path file = dir.resolve("file.txt");
        Files.write(file, original);
        TextBuffer.load(file).save(file);
        return Files.readAllBytes(file);
    }

    @Test
    void savingAnUntouchedFileWritesBackTheSameBytes() throws Exception {
        byte[] utf8Bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] utf16Bom = {(byte) 0xFF, (byte) 0xFE};
        byte[][] files = {
            new byte[0],
            "\n".getBytes(StandardCharsets.UTF_8),
            "\r\n".getBytes(StandardCharsets.UTF_8),
            "\r".getBytes(StandardCharsets.UTF_8),
            "a".getBytes(StandardCharsets.UTF_8),
            "a\n".getBytes(StandardCharsets.UTF_8),
            "a\n\n".getBytes(StandardCharsets.UTF_8),
            "one\r\ntwo\r\n\r\nthree".getBytes(StandardCharsets.UTF_8),
            concat(utf8Bom, "h\u00E9llo\n".getBytes(StandardCharsets.UTF_8)),
            concat(utf16Bom, "hi\r\nthere\r\n".getBytes(StandardCharsets.UTF_16LE)),
        };
        for (byte[] original : files) {
            assertArrayEquals(original, roundTrip(original), "round trip of " + java.util.Arrays.toString(original));
        }
    }

    @Test
    void aDocumentEmptiedByEditingSavesAsAnEmptyFile() throws Exception {
        for (String text : new String[] {"a\n", "a\r\nb"}) {
            Path file = dir.resolve("edited.txt");
            Files.writeString(file, text);
            TextBuffer buffer = TextBuffer.load(file);
            buffer.replace(new TextBuffer.Pos(0, 0), buffer.end(), "");
            buffer.save(file);
            assertEquals(0, Files.size(file), "emptied " + text.replace("\r", "\\r").replace("\n", "\\n"));
        }
        assertEquals(0, TextBuffer.of("").encode().length);
    }

    @Test
    void invalidUtf8AfterAByteOrderMarkIsSavedWithoutTheMark() throws Exception {
        byte[] data = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', (byte) 0xE9, 'b', '\n'};
        Path file = dir.resolve("bad.txt");
        Files.write(file, data);
        TextBuffer buffer = TextBuffer.load(file);
        assertFalse(buffer.encodingName().contains("BOM"), buffer.encodingName());
        buffer.save(file);
        byte[] saved = Files.readAllBytes(file);
        assertFalse(saved.length >= 3 && saved[0] == (byte) 0xEF && saved[1] == (byte) 0xBB && saved[2] == (byte) 0xBF,
                "no byte order mark in front of fallback-encoded text");
        assertTrue(saved.length >= 4 && saved[0] == 'a', "the text itself is kept");
    }

    @Test
    void saveReplacesTheFileThroughALinkWithoutBreakingTheLink() throws Exception {
        Path real = dir.resolve("real.txt");
        Files.writeString(real, "old\n");
        Path link = dir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, real);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here: " + e);
        }
        TextBuffer buffer = TextBuffer.load(link);
        buffer.replace(new TextBuffer.Pos(0, 3), new TextBuffer.Pos(0, 3), "!");
        buffer.save(link);
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("old!\n", Files.readString(real).replace("\r\n", "\n"));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(2, files.count(), "no temporary file");
        }
    }
}
