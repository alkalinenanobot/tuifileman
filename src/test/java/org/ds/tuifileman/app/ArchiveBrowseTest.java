package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.model.DirectoryListing;
import org.ds.tuifileman.model.FileEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveBrowseTest {
    @TempDir
    Path root;

    private Path zip(String name, String... namesAndContents) throws Exception {
        Path file = root.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                if (!namesAndContents[i].endsWith("/")) {
                    zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return file;
    }

    private static List<String> names(PanelState panel) {
        return panel.entries().stream().map(FileEntry::name).toList();
    }

    @Test
    void panelBrowsesInsideAndLeavesBackToTheArchiveFile() throws Exception {
        Path archive = zip("a.zip", "top.txt", "T", "dir/b.txt", "BB", "dir/sub/c.txt", "CCC");
        Files.writeString(root.resolve("other.txt"), "x");
        PanelState panel = new PanelState(root);
        assertNull(panel.refresh());
        panel.selectName("a.zip", 20);

        assertNull(panel.openArchive(archive));
        assertTrue(panel.isVirtual());
        assertEquals(List.of("..", "dir", "top.txt"), names(panel));
        assertTrue(panel.displayPath().endsWith("a.zip!/"), panel.displayPath());

        panel.selectName("dir", 20);
        assertNull(panel.enter());
        assertEquals(List.of("..", "sub", "b.txt"), names(panel));
        assertEquals(2, panel.entries().get(2).size());

        assertNull(panel.goUp());
        assertEquals("dir", panel.current().name());
        assertNull(panel.goUp());
        assertEquals("a.zip", panel.current().name());
        assertFalse(panel.isVirtual());

        assertNull(panel.openArchive(archive));
        assertEquals("..", panel.current().name());
        assertNull(panel.enter());
        assertFalse(panel.isVirtual());
        assertEquals("a.zip", panel.current().name(), "the .. entry of the archive root leaves it too");
    }

    @Test
    void revealShowsAFileOfARealFolderAndOneInsideAnArchive() throws Exception {
        Files.createDirectories(root.resolve("real"));
        Files.writeString(root.resolve("real/x.txt"), "x");
        Files.writeString(root.resolve("real/y.txt"), "y");
        zip("a.zip", "top.txt", "T", "dir/sub/c.txt", "C", "dir/sub/d.txt", "D");
        PanelState panel = new PanelState(root.resolve("real"));
        panel.refresh();

        assertNull(panel.reveal(null, root.toString(), "real", 20));
        assertEquals("real", panel.current().name());

        assertNull(panel.reveal(root.resolve("a.zip").toString(), "/dir/sub", "d.txt", 20));
        assertTrue(panel.isVirtual());
        assertEquals("d.txt", panel.current().name());
        assertTrue(panel.displayPath().endsWith("a.zip!/dir/sub"), panel.displayPath());

        assertNull(panel.reveal(root.resolve("a.zip").toString(), "/", "top.txt", 20));
        assertEquals("top.txt", panel.current().name());

        assertNull(panel.reveal(null, root.resolve("real").toString(), "y.txt", 20));
        assertFalse(panel.isVirtual());
        assertEquals("y.txt", panel.current().name());
    }

    @Test
    void twoPanelsShareOneOpenArchiveAndTheLastToLeaveReleasesIt() throws Exception {
        Path archive = zip("a.zip", "top.txt", "T");
        PanelState left = new PanelState(root);
        PanelState right = new PanelState(root);
        left.refresh();
        right.refresh();
        assertNull(left.openArchive(archive));
        assertNull(right.openArchive(archive));
        Path inside = left.directory();
        assertNull(left.goTo(root));
        assertTrue(inside.getFileSystem().isOpen(), "the other panel still shows it");
        assertEquals(List.of("..", "top.txt"), names(right));
        assertNull(right.goTo(root));
        assertFalse(inside.getFileSystem().isOpen());
        Files.delete(archive); // a Windows lock would refuse
    }

    @Test
    void archiveInsideAnArchiveOpens() throws Exception {
        Path inner = zip("inner.zip", "deep.txt", "D");
        Path outer = zip("outer.zip", "x.txt", "X");
        // Build outer.zip with inner.zip in it.
        try (OutputStream out = Files.newOutputStream(outer); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("inner.zip"));
            zip.write(Files.readAllBytes(inner));
            zip.closeEntry();
        }
        PanelState panel = new PanelState(root);
        panel.refresh();
        assertNull(panel.openArchive(outer));
        panel.selectName("inner.zip", 20);
        assertNull(panel.openArchive(panel.current().path()));
        assertEquals(List.of("..", "deep.txt"), names(panel));
        assertEquals(root.toRealPath(), Archives.realDirectory(panel.directory()).toRealPath());
        assertNull(panel.goUp());
        assertEquals("inner.zip", panel.current().name());
        assertNull(panel.goUp());
        assertEquals("outer.zip", panel.current().name());
    }

    @Test
    void anInvalidArchiveIsReportedAndThePanelStays() throws Exception {
        Path bogus = root.resolve("fake.zip");
        Files.writeString(bogus, "this is not a zip");
        PanelState panel = new PanelState(root);
        panel.refresh();
        String error = panel.openArchive(bogus);
        assertNotNull(error);
        assertTrue(error.startsWith("Cannot open fake.zip"), error);
        assertFalse(panel.isVirtual());
        assertTrue(names(panel).contains("fake.zip"));
    }

    @Test
    void copyOutOfAnArchiveKeepsContentAndTree() throws Exception {
        Path archive = zip("a.zip", "top.txt", "T", "dir/b.txt", "BB", "dir/sub/c.txt", "CCC", "dir/empty/", "");
        PanelState panel = new PanelState(root);
        panel.refresh();
        assertNull(panel.openArchive(archive));
        panel.selectName("top.txt", 20);
        panel.toggleMark(20, false);
        panel.selectName("dir", 20);
        panel.toggleMark(20, false);
        Path out = Files.createDirectories(root.resolve("out"));
        CopyJob job = new CopyJob(panel.targets(), out, false, (source, existing) -> CopyJob.Conflict.CANCEL);
        job.run();
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals("T", Files.readString(out.resolve("top.txt")));
        assertEquals("BB", Files.readString(out.resolve("dir/b.txt")));
        assertEquals("CCC", Files.readString(out.resolve("dir/sub/c.txt")));
        assertTrue(Files.isDirectory(out.resolve("dir/empty")));
        assertEquals(6, job.bytesDone());
    }

    @Test
    void extractGivesARealFileAndCleansUp() throws Exception {
        Path archive = zip("a.zip", "dir/b.txt", "BB");
        Path inside = Archives.open(archive).resolve("dir/b.txt");
        Path temp;
        try (Archives.Extracted file = Archives.extract(inside)) {
            temp = file.path();
            assertFalse(Archives.isVirtual(temp));
            assertEquals("b.txt", temp.getFileName().toString());
            assertEquals("BB", Files.readString(temp));
        }
        assertFalse(Files.exists(temp));
        Archives.release(inside);
        try (Archives.Extracted plain = Archives.extract(archive)) {
            assertEquals(archive, plain.path());
        }
        assertTrue(Files.exists(archive), "a real file is never deleted");
    }

    @Test
    void extractRefusesEntryNamesThatWouldEscapeTheTempFolder() throws Exception {
        // "dir/ok.txt" has a slash, so the backslash names are not repaired into folders (see ZipNames).
        Path archive = zip("evil.zip", "dir/ok.txt", "ok", "..\\..\\evil.txt", "x", "C:\\Users\\x\\evil.txt", "y");
        Path rootPath = Archives.open(archive);
        try {
            List<Path> evil;
            try (var stream = Files.list(rootPath)) {
                evil = stream.filter(p -> p.getFileName().toString().indexOf('\\') >= 0).toList();
            }
            assertEquals(2, evil.size());
            for (Path entry : evil) {
                IOException refused = assertThrows(IOException.class, () -> Archives.extract(entry));
                assertTrue(refused.getMessage().startsWith("unsafe archive entry name"), refused.getMessage());
                assertThrows(IOException.class, () -> Archives.extractKept(entry));
            }
            assertFalse(Files.exists(root.resolve("evil.txt")));
        } finally {
            Archives.release(rootPath);
        }
    }

    @Test
    void anInnerArchiveIsExtractedOnceAndItsCopyDiesWithTheLastReference() throws Exception {
        Path inner = zip("inner.zip", "deep.txt", "D");
        Path outer = root.resolve("outer.zip");
        try (OutputStream out = Files.newOutputStream(outer); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("inner.zip"));
            zip.write(Files.readAllBytes(inner));
            zip.closeEntry();
        }
        Path outerRoot = Archives.open(outer);
        try {
            Path first = Archives.open(outerRoot.resolve("inner.zip"));
            Path second = Archives.open(outerRoot.resolve("inner.zip"));
            assertSame(first.getFileSystem(), second.getFileSystem(), "the same temporary copy is reused");
            Path copy = Path.of(first.getFileSystem().toString()); // the zip provider names its file system by the file
            assertTrue(Files.exists(copy));
            Archives.release(first);
            assertTrue(Files.exists(copy), "one reference is left");
            Archives.release(second);
            assertFalse(Files.exists(copy));
            assertFalse(Files.exists(copy.getParent()), "the folder of the copy goes too");

            Path again = Archives.open(outerRoot.resolve("inner.zip"));
            Path secondCopy = Path.of(again.getFileSystem().toString());
            assertTrue(Files.exists(secondCopy));
            Archives.closeAll();
            assertFalse(Files.exists(secondCopy), "closeAll deletes the copies too");
        } finally {
            Archives.release(outerRoot);
        }
    }

    @Test
    void listingOfEntriesWithoutExplicitFolders() throws Exception {
        Path archive = zip("a.zip", "a/b/c.txt", "C");
        Path rootPath = Archives.open(archive);
        try {
            List<FileEntry> top = DirectoryListing.list(rootPath);
            assertEquals(List.of("..", "a"), top.stream().map(FileEntry::name).toList());
            assertTrue(top.get(1).directory());
            assertEquals(root.toRealPath(), top.get(0).path().toRealPath());
        } finally {
            Archives.release(rootPath);
        }
    }

    @Test
    void backslashSeparatorsOfWindowsToolsAreTreatedAsFolders() throws Exception {
        Path archive = zip("win.zip", "app\\", "", "app\\sub\\", "", "app\\sub\\c.txt", "CCC", "app\\b.txt", "B");
        Path rootPath = Archives.open(archive);
        try {
            assertEquals(List.of("..", "app"), DirectoryListing.list(rootPath).stream().map(FileEntry::name).toList());
            Path app = rootPath.resolve("app");
            assertEquals(List.of("..", "sub", "b.txt"),
                    DirectoryListing.list(app).stream().map(FileEntry::name).toList());
            assertEquals("CCC", Files.readString(app.resolve("sub").resolve("c.txt")));
        } finally {
            Archives.release(rootPath);
        }
    }
}
