package org.ds.tuifileman.model;

import static org.ds.tuifileman.Fixtures.directoryLink;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryListingAttributesTest {
    @Test
    void unixShowsTheMode(@TempDir Path dir) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path script = Files.writeString(dir.resolve("run.sh"), "x");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));
        Path data = Files.writeString(dir.resolve("data.txt"), "x");
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rw-r--r--"));

        var entries = DirectoryListing.list(dir);
        assertEquals("rw-r--r--", entries.stream().filter(e -> e.name().equals("data.txt")).findFirst().orElseThrow().attributes());
        assertEquals("rwxr-x---", entries.stream().filter(e -> e.name().equals("run.sh")).findFirst().orElseThrow().attributes());
        assertTrue(entries.stream().filter(e -> e.name().equals("..")).findFirst().orElseThrow().attributes().isEmpty());
    }

    @Test
    void windowsShowsDosFlagsAndListsThroughJunctions(@TempDir Path dir) throws Exception {
        assumeFalse(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path real = Files.createDirectory(dir.resolve("real"));
        Path secret = Files.writeString(real.resolve("secret.txt"), "12345");
        Files.setAttribute(secret, "dos:hidden", true);
        Files.setAttribute(secret, "dos:readonly", true);
        Files.setAttribute(secret, "dos:archive", false);
        Files.createDirectory(real.resolve("sub"));
        Path junction = directoryLink(dir.resolve("junction"), real);
        try {
            List<FileEntry> top = DirectoryListing.list(dir);
            FileEntry link = top.stream().filter(e -> e.name().equals("junction")).findFirst().orElseThrow();
            assertTrue(link.link());
            assertTrue(link.directory());

            // A junction as the listed directory itself: its contents, with the flags of each entry.
            List<FileEntry> inside = DirectoryListing.list(junction);
            assertEquals(List.of("..", "sub", "secret.txt"), inside.stream().map(FileEntry::name).toList());
            FileEntry file = inside.get(2);
            assertEquals("rh--", file.attributes());
            assertEquals(5, file.size());
            assertFalse(file.directory());
            assertTrue(inside.get(1).directory());
        } finally {
            Files.setAttribute(secret, "dos:readonly", false);
            Files.delete(junction);
        }
    }
}
