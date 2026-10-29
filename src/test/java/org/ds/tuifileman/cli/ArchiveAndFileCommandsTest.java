package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code cat}, archive paths, {@code find} excludes and the two-file {@code diff}, as a script sees them. */
class ArchiveAndFileCommandsTest {
    @TempDir
    Path dir;

    private Path zip(String name, String... pairs) throws Exception {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < pairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pairs[i]));
                zip.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    // ---- cat --------------------------------------------------------------------------------------------------

    @Test
    void catWritesFilesAndArchiveEntriesUnchangedToStdout() throws Exception {
        Path text = Cmd.file(dir, "a.txt", "one\ntwo");
        Path archive = zip("pack.zip", "BuildID.txt", "id 42\n", "dir/x.txt", "deep");
        Cmd.Result result = Cmd.run("cat", text.toString(), archive + "!/BuildID.txt", archive + "!\\dir\\x.txt");
        assertEquals(0, result.exit(), result.err());
        assertEquals("one\ntwoid 42\ndeep", result.out(), "the bytes, one file after the other");
        assertEquals("", result.err());
    }

    @Test
    void catReportsWhatItCannotReadAndKeepsGoing() throws Exception {
        Path text = Cmd.file(dir, "a.txt", "ok");
        Path archive = zip("pack.zip", "f.txt", "x");
        Cmd.Result partial = Cmd.run("cat", dir.resolve("nope.txt").toString(), text.toString());
        assertEquals(3, partial.exit(), "some files were written, one failed");
        assertEquals("ok", partial.out());
        assertTrue(partial.err().contains("no such file"), partial.err());
        Cmd.Result none = Cmd.run("cat", archive + "!/missing.txt");
        assertEquals(4, none.exit());
        assertEquals("", none.out());
        assertEquals(4, Cmd.run("cat", dir.toString()).exit(), "a folder is not a file");
        assertEquals(2, Cmd.run("cat").exit(), "cat needs a file");
    }

    // ---- find -------------------------------------------------------------------------------------------------

    @Test
    void findExcludeAndGitignoreLeaveEntriesOut() throws Exception {
        Cmd.file(dir, "proj/src/bom.ini", "x");
        Cmd.file(dir, "proj/.git/bom.ini", "x");
        Cmd.file(dir, "proj/build/bom.ini", "x");
        Cmd.file(dir, "proj/.gitignore", "build/\n");
        Path proj = dir.resolve("proj");
        assertEquals(3, Cmd.run("find", "-q", "-N", "bom.ini", proj.toString()).outLines().size());
        assertEquals(List.of(proj.resolve("build/bom.ini").toString(), proj.resolve("src/bom.ini").toString()),
                Cmd.run("find", "-q", "--sort", "-N", "bom.ini", "-x", ".git", proj.toString()).outLines());
        assertEquals(List.of(proj.resolve("src/bom.ini").toString()),
                Cmd.run("find", "-q", "-N", "bom.ini", "-x", ".git", "-g", proj.toString()).outLines());
        assertEquals(List.of(proj.resolve("src/bom.ini").toString()),
                Cmd.run("find", "-q", "-N", "bom.ini", "-x", ".git", "-x", "build", proj.toString()).outLines());
    }

    @Test
    void findSearchesASingleArchive() throws Exception {
        Path archive = zip("pack.zip", "BuildID.txt", "id 42\n", "dir/notes.txt", "none");
        Cmd.Result names = Cmd.run("find", "-q", "--sort", archive.toString());
        assertEquals(0, names.exit(), names.err());
        // the zip file system names a folder "dir/", with the slash
        assertEquals(List.of(archive + "!/BuildID.txt", archive + "!/dir/", archive + "!/dir/notes.txt"), names.outLines());
        Cmd.Result text = Cmd.run("find", "-q", "-T", "ID 42", archive.toString());
        assertEquals(List.of(archive + "!/BuildID.txt:1: id 42"), text.outLines());
        Cmd.Result inside = Cmd.run("find", "-q", "--sort", archive + "!/dir");
        assertEquals(List.of(archive + "!/dir/notes.txt"), inside.outLines());
    }

    @Test
    void findOnAFileThatIsNoArchiveIsStillAUsageError() throws Exception {
        Path text = Cmd.file(dir, "a.txt", "plain");
        Cmd.Result result = Cmd.run("find", "-q", text.toString());
        assertEquals(2, result.exit());
        assertTrue(result.err().contains("neither"), result.err());
    }

    // ---- copy / move out of an archive ------------------------------------------------------------------------

    @Test
    void copyTakesEntriesOfAnArchiveAsSources() throws Exception {
        Path archive = zip("pack.zip", "a.txt", "A", "b.txt", "B", "dir/c.log", "C");
        Path out = dir.resolve("out");
        Cmd.Result one = Cmd.run("copy", "-q", archive + "!/a.txt", out.toString());
        assertEquals(0, one.exit(), one.err());
        assertEquals("A", Files.readString(out.resolve("a.txt")));
        Cmd.Result many = Cmd.run("copy", "-q", archive + "!/*.txt", out.toString(), "-c", "overwrite");
        assertEquals(0, many.exit(), many.err());
        assertEquals("B", Files.readString(out.resolve("b.txt")));
        Cmd.Result folder = Cmd.run("copy", "-q", archive + "!/dir", out.toString());
        assertEquals(0, folder.exit(), folder.err());
        assertEquals("C", Files.readString(out.resolve("dir/c.log")));
        Cmd.Result plan = Cmd.run("copy", "-n", "-c", "overwrite", archive + "!/a.txt", out.toString());
        assertTrue(plan.out().contains(archive + "!/a.txt"), "the plan names the archive: " + plan.out());
    }

    @Test
    void moveRenameAndDeleteRefuseArchiveEntries() throws Exception {
        Path archive = zip("pack.zip", "a.txt", "A");
        Path out = Files.createDirectories(dir.resolve("out"));
        for (String command : List.of("move", "delete")) {
            Cmd.Result result = command.equals("move")
                    ? Cmd.run(command, "-q", archive + "!/a.txt", out.toString())
                    : Cmd.run(command, "-q", "-y", archive + "!/a.txt");
            assertEquals(2, result.exit(), command + ": " + result.err());
            assertTrue(result.err().contains("read-only"), result.err());
        }
        assertEquals(2, Cmd.run("rename", "-q", "-n", "-m", "x", archive + "!/a.txt").exit());
        assertFalse(Files.exists(out.resolve("a.txt")));
        assertTrue(Files.exists(archive));
    }

    // ---- diff -------------------------------------------------------------------------------------------------

    @Test
    void diffOfTwoFilesPrintsAUnifiedDiffAndExitsOne() throws Exception {
        Path a = Cmd.file(dir, "a.txt", "one\ntwo\nthree\n");
        Path b = Cmd.file(dir, "b.txt", "one\n2\nthree\n");
        Cmd.Result result = Cmd.run("diff", a.toString(), b.toString());
        assertEquals(1, result.exit(), result.err());
        assertEquals(List.of("--- " + a, "+++ " + b, "@@ -1,3 +1,3 @@", " one", "-two", "+2", " three"), result.outLines());
        assertEquals("", result.err());
        Cmd.Result none = Cmd.run("diff", "-f", "none", a.toString(), b.toString());
        assertEquals(1, none.exit());
        assertEquals("", none.out());
        assertEquals(0, Cmd.run("diff", a.toString(), a.toString()).exit());
    }

    @Test
    void diffOfTwoFilesHonoursContextAndIgnoreOptions() throws Exception {
        Path a = Cmd.file(dir, "a.txt", "1\n2\n3\n4\n5\n");
        Path b = Cmd.file(dir, "b.txt", "1\n2\nX\n4\n5\n");
        assertEquals(List.of("@@ -3 +3 @@", "-3", "+X"),
                Cmd.run("diff", "-U", "0", a.toString(), b.toString()).outLines().subList(2, 5));
        Path c = Cmd.file(dir, "c.txt", "hello   world\n");
        Path d = Cmd.file(dir, "d.txt", "HELLO world\n");
        assertEquals(1, Cmd.run("diff", c.toString(), d.toString()).exit());
        assertEquals(1, Cmd.run("diff", "-w", c.toString(), d.toString()).exit());
        assertEquals(1, Cmd.run("diff", "-i", c.toString(), d.toString()).exit());
        assertEquals(0, Cmd.run("diff", "-w", "-i", c.toString(), d.toString()).exit());
        assertEquals(2, Cmd.run("diff", "-U", "-1", a.toString(), b.toString()).exit());
    }

    @Test
    void textThatDiffersOnlyInLineEndingsIsStillDifferent() throws Exception {
        Path a = Cmd.file(dir, "a.txt", "one\ntwo\n");
        Path b = Cmd.file(dir, "b.txt", "one\r\ntwo\r\n");
        Cmd.Result result = Cmd.run("diff", a.toString(), b.toString());
        assertEquals(1, result.exit());
        assertTrue(result.out().contains("differ only in line endings"), result.out());
        assertEquals(0, Cmd.run("diff", "-w", a.toString(), b.toString()).exit());
    }

    @Test
    void binaryFilesOnlySayWhetherTheyDiffer() throws Exception {
        Path a = dir.resolve("a.bin");
        Path b = dir.resolve("b.bin");
        Files.write(a, new byte[] {1, 0, 2, 3});
        Files.write(b, new byte[] {1, 0, 2, 4});
        Cmd.Result result = Cmd.run("diff", a.toString(), b.toString());
        assertEquals(1, result.exit());
        assertEquals(1, result.outLines().size(), result.out());
        assertTrue(result.outLines().get(0).contains("differ"), result.out());
        Files.copy(a, dir.resolve("c.bin"));
        assertEquals(0, Cmd.run("diff", a.toString(), dir.resolve("c.bin").toString()).exit());
    }

    @Test
    void diffReadsArchiveEntriesAndRefusesAFolderAgainstAFile() throws Exception {
        Path archive = zip("pack.zip", "a.txt", "one\ntwo\n");
        Path b = Cmd.file(dir, "b.txt", "one\n2\n");
        Cmd.Result result = Cmd.run("diff", archive + "!/a.txt", b.toString());
        assertEquals(1, result.exit(), result.err());
        assertEquals("--- " + archive + "!/a.txt", result.outLines().get(0));
        assertEquals(2, Cmd.run("diff", dir.toString(), b.toString()).exit());
        assertEquals(2, Cmd.run("diff", "-f", "json", b.toString(), b.toString()).exit());
        assertEquals(2, Cmd.run("diff", "-U", "1", dir.toString(), dir.toString()).exit(),
                "context lines mean nothing for folders");
    }

    @Test
    void contentModeNamesDifferentFilesDifferentWhateverTheirTimes() throws Exception {
        Cmd.file(dir, "l/x.txt", "abc");
        Cmd.file(dir, "r/x.txt", "abcdef");
        Files.setLastModifiedTime(dir.resolve("l/x.txt"), java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        Files.setLastModifiedTime(dir.resolve("r/x.txt"), java.nio.file.attribute.FileTime.fromMillis(1_500_000_000_000L));
        Cmd.Result byTime = Cmd.run("diff", "-q", dir.resolve("l").toString(), dir.resolve("r").toString());
        assertTrue(byTime.out().startsWith("right-newer"), byTime.out());
        Cmd.Result byContent = Cmd.run("diff", "-q", "-c", dir.resolve("l").toString(), dir.resolve("r").toString());
        assertTrue(byContent.out().startsWith("different"), byContent.out());
    }
}
