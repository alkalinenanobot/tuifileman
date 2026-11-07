package org.ds.tuifileman.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.ArrayList;
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

    @Test
    void onWindowsTheAclAttributesStreamsAndCreationTimeStay() throws Exception {
        Assumptions.assumeTrue(WindowsFiles.available(), "Windows only");
        Path file = dir.resolve("kept.txt");
        Files.writeString(file, "old");
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        AclEntry extra = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(file))
                .setPermissions(AclEntryPermission.WRITE_NAMED_ATTRS).build();
        List<AclEntry> entries = new ArrayList<>(acl.getAcl());
        entries.add(0, extra);
        acl.setAcl(entries);
        Files.setAttribute(file, "dos:hidden", true);
        Files.getFileAttributeView(file, UserDefinedFileAttributeView.class)
                .write("note", StandardCharsets.UTF_8.encode("stream"));
        FileTime created = FileTime.fromMillis(1_500_000_000_000L);
        Files.getFileAttributeView(file, BasicFileAttributeView.class).setTimes(null, null, created);

        AtomicFile.write(file, "new".getBytes(StandardCharsets.UTF_8));

        assertEquals("new", Files.readString(file));
        assertTrue(acl.getAcl().contains(extra), "the explicit ACL entry is kept: " + acl.getAcl());
        assertEquals(true, Files.getAttribute(file, "dos:hidden"));
        UserDefinedFileAttributeView streams = Files.getFileAttributeView(file, UserDefinedFileAttributeView.class);
        ByteBuffer note = ByteBuffer.allocate(streams.size("note"));
        streams.read("note", note);
        assertEquals("stream", new String(note.array(), StandardCharsets.UTF_8));
        assertEquals(created, Files.readAttributes(file, BasicFileAttributes.class).creationTime());
        assertEquals(List.of("kept.txt"), names());
    }

    @Test
    void onPosixThePermissionsStayAndAHardLinkKeepsSharingTheContent() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "POSIX only");
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "old");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        Path script = dir.resolve("run.sh");
        Files.writeString(script, "old");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));
        for (Path file : List.of(secret, script)) {
            var before = Files.getPosixFilePermissions(file);
            AtomicFile.write(file, "new".getBytes(StandardCharsets.UTF_8));
            assertEquals("new", Files.readString(file));
            assertEquals(before, Files.getPosixFilePermissions(file), file.toString());
        }

        Path other = Files.createLink(dir.resolve("other-name.txt"), secret);
        AtomicFile.write(secret, "linked".getBytes(StandardCharsets.UTF_8));
        assertEquals("linked", Files.readString(other), "both names still show one file");
        assertTrue(Files.isSameFile(secret, other));
    }

    @Test
    void onLinuxAnAclAndUserAttributesStay() throws Exception {
        Assumptions.assumeTrue(LinuxXattrs.LINUX, "Linux only");
        Path file = dir.resolve("shared.txt");
        Files.writeString(file, "old");
        Files.getFileAttributeView(file, UserDefinedFileAttributeView.class)
                .write("note", StandardCharsets.UTF_8.encode("kept"));
        // numeric user and group: they need not exist; setfacl is only the test's way to make an ACL
        Process setfacl;
        try {
            setfacl = new ProcessBuilder("setfacl", "-m", "u:54321:r--,g:54322:rw-", file.toString())
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.abort("setfacl is not installed");
            return;
        }
        String output = new String(setfacl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assumptions.assumeTrue(setfacl.waitFor() == 0, "no ACL support here: " + output);
        String before = acl(file);
        Assumptions.assumeTrue(before.contains("user:54321:r--"), before);

        AtomicFile.write(file, "new".getBytes(StandardCharsets.UTF_8));

        assertEquals("new", Files.readString(file));
        assertEquals(before, acl(file));
        UserDefinedFileAttributeView view = Files.getFileAttributeView(file, UserDefinedFileAttributeView.class);
        ByteBuffer note = ByteBuffer.allocate(view.size("note"));
        view.read("note", note);
        assertEquals("kept", new String(note.array(), StandardCharsets.UTF_8));
        assertEquals(List.of("shared.txt"), names());
    }

    private static String acl(Path file) throws Exception {
        Process getfacl = new ProcessBuilder("getfacl", "-n", "--omit-header", file.toString())
                .redirectErrorStream(true).start();
        String output = new String(getfacl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        getfacl.waitFor();
        return output;
    }
}
