package org.ds.tuifileman.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileTest {
    @TempDir
    Path dir;

    private List<String> names() throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writeReplacesTheContentAndCreatesMissingFiles() throws Exception {
        Path file = dir.resolve("a.txt");
        AtomicFile.write(file, new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(file), "created");
        AtomicFile.write(file, new byte[] {9});
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(file), "replaced");
        assertEquals(List.of("a.txt"), names(), "no temporary file is left");
    }

    @Test
    void aNameAtTheFileSystemLengthLimitCanStillBeSaved() throws Exception {
        Path file = dir.resolve("n".repeat(250));
        Files.writeString(file, "old");
        AtomicFile.write(file, new byte[] {1});
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(file));
    }

    @Test
    void failedWriteKeepsTheOldFileAndLeavesNoTemporaryFile() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("target"));
        Files.writeString(folder.resolve("inner.txt"), "keep");
        assertThrows(IOException.class, () -> AtomicFile.write(folder, new byte[] {1}), "a folder cannot be replaced by a file");
        assertEquals("keep", Files.readString(folder.resolve("inner.txt")));
        assertEquals(List.of("target"), names());

        Path file = dir.resolve("b.txt");
        Files.writeString(file, "old");
        assertThrows(IOException.class, () -> AtomicFile.write(dir.resolve("missing").resolve("b.txt"), new byte[] {1}),
                "missing parent folder");
        assertEquals("old", Files.readString(file));
        assertEquals(List.of("b.txt", "target"), names());
    }

    @Test
    void failureWhileProducingTheBytesKeepsTheOldFile() throws Exception {
        Path file = dir.resolve("c.txt");
        Files.writeString(file, "old");
        // U+D800 alone cannot be encoded: the writer fails after the temporary file was created.
        assertThrows(IOException.class, () -> AtomicFile.writeLines(file, List.of("fine", "bad \uD800"), StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(file));
        assertEquals(List.of("c.txt"), names());
    }

    @Test
    void writeLinesIsByteForByteWhatFilesWriteProduces() throws Exception {
        List<String> lines = List.of("first", "", "ünï — \u20AC", "last");
        for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE)) {
            Path expected = dir.resolve("expected");
            Path actual = dir.resolve("actual");
            Files.write(expected, lines, charset);
            AtomicFile.writeLines(actual, lines, charset);
            assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(actual), charset.name());
        }
    }

    @Test
    void aSymbolicLinkIsFollowedNotReplaced() throws Exception {
        Path real = dir.resolve("real.txt");
        Files.writeString(real, "old");
        Path link = dir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("no symbolic links here: " + e);
        }
        AtomicFile.write(link, "new".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(link), "the link is still a link");
        assertEquals("new", Files.readString(real));
    }
}
