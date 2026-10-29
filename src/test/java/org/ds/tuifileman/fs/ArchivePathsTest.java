package org.ds.tuifileman.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivePathsTest {
    @TempDir
    Path dir;

    @AfterEach
    void close() {
        Archives.closeAll();
    }

    private static byte[] zipBytes(String... pairs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < pairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pairs[i]));
                zip.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Path zip(String name, String... pairs) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, zipBytes(pairs));
        return file;
    }

    @Test
    void anEntryIsReadThroughTheArchiveSpelling() throws Exception {
        Path archive = zip("a.zip", "dir/f.txt", "hello");
        for (String separator : new String[] {"!/", "!\\"}) {
            Path entry = ArchivePaths.resolve(archive + separator + "dir" + separator.substring(1) + "f.txt");
            assertTrue(Archives.isVirtual(entry), separator);
            assertEquals("hello", Files.readString(entry));
        }
        assertEquals(archive + "!/dir/f.txt", Archives.display(ArchivePaths.resolve(archive + "!/dir/f.txt")));
    }

    @Test
    void theArchiveItselfAndAFolderInsideAreAddressable() throws Exception {
        Path archive = zip("a.zip", "dir/f.txt", "hello");
        assertTrue(Files.isDirectory(ArchivePaths.resolve(archive + "!")));
        assertTrue(Files.isDirectory(ArchivePaths.resolve(archive + "!/dir")));
    }

    @Test
    void aMissingEntryResolvesToAPathThatDoesNotExist() throws Exception {
        Path archive = zip("a.zip", "f.txt", "x");
        assertFalse(Files.exists(ArchivePaths.resolve(archive + "!/nope.txt")));
    }

    @Test
    void anArchiveInsideAnArchiveIsOpened() throws Exception {
        byte[] inner = zipBytes("deep.txt", "deeper");
        Path outer = dir.resolve("outer.zip");
        try (OutputStream out = Files.newOutputStream(outer); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("in/inner.zip"));
            zip.write(inner);
            zip.closeEntry();
        }
        assertEquals("deeper", Files.readString(ArchivePaths.resolve(outer + "!/in/inner.zip!/deep.txt")));
    }

    @Test
    void anythingElseIsAPlainPath() throws Exception {
        Path plain = Files.writeString(dir.resolve("a!b.txt"), "x");
        assertEquals(plain, ArchivePaths.resolve(plain.toString()), "a ! inside a name is not a separator");
        assertEquals(Path.of("no-such.zip!/x"), ArchivePaths.resolve("no-such.zip!/x"),
                "the part before the ! is not a file");
    }

    @Test
    void aFileThatIsNotAnArchiveBeforeTheBangIsAnError() throws Exception {
        Path text = Files.writeString(dir.resolve("t.txt"), "not a zip");
        assertThrows(IOException.class, () -> ArchivePaths.resolve(text + "!/x"));
    }
}
