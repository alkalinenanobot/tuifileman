package org.ds.tuifileman.app;

import static org.ds.tuifileman.Fixtures.BASE;
import static org.ds.tuifileman.Fixtures.dir;
import static org.ds.tuifileman.Fixtures.directoryLink;
import static org.ds.tuifileman.Fixtures.entries;
import static org.ds.tuifileman.Fixtures.file;
import static org.ds.tuifileman.Fixtures.modified;
import static org.ds.tuifileman.Fixtures.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.app.CopyJob.Conflict;
import org.ds.tuifileman.app.CopyJob.ConflictResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyJobTest {
    @TempDir
    Path root;

    private Path src() throws Exception {
        return dir(root, "src");
    }

    private Path dst() throws Exception {
        return dir(root, "dst");
    }

    private static ConflictResolver always(Conflict answer) {
        return (source, existing) -> answer;
    }

    private CopyJob run(boolean move, ConflictResolver resolver, Path... sources) throws Exception {
        CopyJob job = new CopyJob(entries(sources), dst(), move, resolver);
        job.run();
        assertTrue(job.isDone());
        return job;
    }

    @Test
    void copiesFilesAndFolderTreesWithTheirTimes() throws Exception {
        file(src(), "a.txt", "A", 1_000);
        file(src(), "tree/b.txt", "B", 2_000);
        file(src(), "tree/deeper/c.txt", "C", 3_000);
        dir(src(), "tree/empty");
        CopyJob job = run(false, always(Conflict.CANCEL), src().resolve("a.txt"), src().resolve("tree"));
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(Map.of("a.txt", "A", "tree", "<dir>", "tree/b.txt", "B", "tree/deeper", "<dir>",
                "tree/deeper/c.txt", "C", "tree/empty", "<dir>"), tree(dst()));
        assertEquals(BASE + 3_000, modified(dst().resolve("tree/deeper/c.txt")));
        assertEquals(BASE + 1_000, modified(dst().resolve("a.txt")));
        assertTrue(Files.exists(src().resolve("a.txt")), "a copy keeps the source");
        assertEquals(3, job.bytesDone());
    }

    @Test
    void moveRemovesTheSourcesAfterwards() throws Exception {
        file(src(), "a.txt", "A");
        file(src(), "tree/b.txt", "B");
        CopyJob job = run(true, always(Conflict.CANCEL), src().resolve("a.txt"), src().resolve("tree"));
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(Map.of("a.txt", "A", "tree", "<dir>", "tree/b.txt", "B"), tree(dst()));
        assertTrue(tree(src()).isEmpty());
        assertTrue(job.summary().startsWith("Moved "));
    }

    @Test
    void movingOntoAnExistingFolderMergesAndKeepsUnrelatedFiles() throws Exception {
        file(src(), "tree/new.txt", "N");
        file(dst(), "tree/other.txt", "O");
        run(true, always(Conflict.CANCEL), src().resolve("tree"));
        assertEquals(Map.of("tree", "<dir>", "tree/new.txt", "N", "tree/other.txt", "O"), tree(dst()));
        assertTrue(tree(src()).isEmpty());
    }

    @Test
    void moveKeepsTheSourceWhenAFolderCannotBeCreated() throws Exception {
        file(src(), "tree/a.txt", "new");
        dir(src(), "tree/empty");
        file(dst(), "tree/a.txt", "old");
        Path blocked = dst().resolve("tree/empty");
        // Asked after planning: a file now blocks the folder "empty" from being created.
        CopyJob job = run(true, (source, existing) -> {
            try {
                Files.writeString(blocked, "blocker");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return Conflict.OVERWRITE;
        }, src().resolve("tree"));
        assertEquals(1, job.errors().size(), job.errors().toString());
        assertTrue(Files.isDirectory(src().resolve("tree/empty")), "the only copy of the folder stays");
        assertEquals("new", Files.readString(src().resolve("tree/a.txt")));
    }

    @Test
    void aTargetFolderLinkedBackToTheSourceNeverDestroysIt() throws Exception {
        file(src(), "tree/a.txt", "A");
        file(src(), "tree/sub/b.txt", "B");
        directoryLink(dst().resolve("tree"), src().resolve("tree"));
        for (boolean move : new boolean[] {false, true}) {
            for (Conflict answer : new Conflict[] {Conflict.OVERWRITE_ALL, Conflict.KEEP_BOTH_ALL}) {
                CopyJob job = run(move, always(answer), src().resolve("tree"));
                assertEquals("Cannot copy a folder into itself", job.errors().get(0).reason());
                assertEquals(Map.of("tree", "<dir>", "tree/a.txt", "A", "tree/sub", "<dir>", "tree/sub/b.txt", "B"),
                        tree(src()), "move=" + move + " " + answer);
            }
        }
    }

    @Test
    void aNestedFolderLinkedBackToTheSourceKeepsTheSource() throws Exception {
        file(src(), "tree/sub/b.txt", "B");
        dir(dst(), "tree");
        directoryLink(dst().resolve("tree/sub"), src().resolve("tree/sub"));
        CopyJob job = run(true, always(Conflict.KEEP_BOTH_ALL), src().resolve("tree"));
        assertEquals("Cannot copy a folder into itself", job.errors().get(0).reason());
        assertEquals(Map.of("tree", "<dir>", "tree/sub", "<dir>", "tree/sub/b.txt", "B"), tree(src()));
    }

    @Test
    void aDestinationLinkedToTheSourceFolderKeepsEveryFile() throws Exception {
        file(src(), "a.txt", "A");
        Path alias = directoryLink(root.resolve("alias"), src());
        CopyJob job = new CopyJob(entries(src().resolve("a.txt")), alias, false, always(Conflict.OVERWRITE));
        job.run();
        assertEquals("Source and destination are the same", job.errors().get(0).reason());
        assertEquals("A", Files.readString(src().resolve("a.txt")));
    }

    @Test
    void aTargetFolderLinkedElsewhereIsWrittenThrough() throws Exception {
        file(src(), "tree/a.txt", "A");
        Path elsewhere = dir(root, "elsewhere");
        directoryLink(dst().resolve("tree"), elsewhere);
        CopyJob job = run(false, always(Conflict.CANCEL), src().resolve("tree"));
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(Map.of("a.txt", "A"), tree(elsewhere));
    }

    @Test
    void aTargetThatAppearsAfterPlanningIsNeverOverwritten() throws Exception {
        // On a case-insensitive target "README" and "readme" (or "Docs" and "docs") end up at one path; the
        // first one written must survive and a move must keep the sources.
        file(src(), "tree/conflict.txt", "new");
        file(src(), "tree/b.txt", "mine");
        file(src(), "tree/sub/c.txt", "C");
        file(dst(), "tree/conflict.txt", "old");
        Path b = dst().resolve("tree/b.txt");
        Path sub = dst().resolve("tree/sub");
        CopyJob job = run(true, (source, existing) -> {
            try {
                Files.writeString(b, "written first");
                Files.createDirectory(sub);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return Conflict.OVERWRITE;
        }, src().resolve("tree"));
        assertEquals("written first", Files.readString(b));
        assertEquals(2, job.errors().size(), job.errors().toString());
        assertTrue(job.errors().stream().allMatch(p -> p.reason().startsWith("Target already exists")),
                job.errors().toString());
        assertEquals(Map.of("tree", "<dir>", "tree/conflict.txt", "new", "tree/b.txt", "mine", "tree/sub", "<dir>",
                "tree/sub/c.txt", "C"), tree(src()), "a move keeps every source");
    }

    @Test
    void conflictOverwrite() throws Exception {
        file(src(), "a.txt", "new");
        file(dst(), "a.txt", "old");
        CopyJob job = run(false, always(Conflict.OVERWRITE), src().resolve("a.txt"));
        assertEquals("new", Files.readString(dst().resolve("a.txt")));
        assertEquals(0, job.skipped());
    }

    @Test
    void conflictSkip() throws Exception {
        file(src(), "a.txt", "new");
        file(dst(), "a.txt", "old");
        CopyJob job = run(false, always(Conflict.SKIP), src().resolve("a.txt"));
        assertEquals("old", Files.readString(dst().resolve("a.txt")));
        assertEquals(1, job.skipped());
        assertTrue(job.summary().contains("1 skipped"));
    }

    @Test
    void conflictKeepBothNumbersTheNewCopy() throws Exception {
        file(src(), "a.txt", "new");
        file(dst(), "a.txt", "old");
        file(dst(), "a (1).txt", "older");
        run(false, always(Conflict.KEEP_BOTH), src().resolve("a.txt"));
        assertEquals(Map.of("a.txt", "old", "a (1).txt", "older", "a (2).txt", "new"), tree(dst()));
    }

    @Test
    void conflictCancelStopsTheWholeJobBeforeWritingAnything() throws Exception {
        file(src(), "a.txt", "A");
        file(src(), "b.txt", "B");
        file(dst(), "b.txt", "old");
        CopyJob job = run(false, always(Conflict.CANCEL), src().resolve("a.txt"), src().resolve("b.txt"));
        assertTrue(job.isCancelled());
        assertEquals(Map.of("b.txt", "old"), tree(dst()));
    }

    @Test
    void anAllAnswerIsAskedOnlyOnce() throws Exception {
        file(src(), "a.txt", "A");
        file(src(), "b.txt", "B");
        file(src(), "c.txt", "C");
        file(dst(), "a.txt", "x");
        file(dst(), "b.txt", "x");
        file(dst(), "c.txt", "x");
        List<String> asked = new ArrayList<>();
        ConflictResolver resolver = (source, existing) -> {
            asked.add(source.getFileName().toString());
            return Conflict.OVERWRITE_ALL;
        };
        run(false, resolver, src().resolve("a.txt"), src().resolve("b.txt"), src().resolve("c.txt"));
        assertEquals(List.of("a.txt"), asked, "conflicts are asked in path order");
        assertEquals(Map.of("a.txt", "A", "b.txt", "B", "c.txt", "C"), tree(dst()));
    }

    @Test
    void skipAllAndKeepBothAll() throws Exception {
        file(src(), "a.txt", "A");
        file(src(), "b.txt", "B");
        file(dst(), "a.txt", "x");
        file(dst(), "b.txt", "x");
        CopyJob skip = run(false, always(Conflict.SKIP_ALL), src().resolve("a.txt"), src().resolve("b.txt"));
        assertEquals(2, skip.skipped());
        assertEquals(Map.of("a.txt", "x", "b.txt", "x"), tree(dst()));
        run(false, always(Conflict.KEEP_BOTH_ALL), src().resolve("a.txt"), src().resolve("b.txt"));
        assertEquals(Map.of("a.txt", "x", "b.txt", "x", "a (1).txt", "A", "b (1).txt", "B"), tree(dst()));
    }

    @Test
    void overwriteOnlyWhenTheSourceIsNewer() throws Exception {
        file(src(), "newer.txt", "S", 60_000);
        file(dst(), "newer.txt", "D", 0);
        file(src(), "older.txt", "S", 0);
        file(dst(), "older.txt", "D", 60_000);
        file(src(), "same.txt", "S", 1_000);
        file(dst(), "same.txt", "D", 0);
        CopyJob job = run(false, always(Conflict.OVERWRITE_OLDER),
                src().resolve("newer.txt"), src().resolve("older.txt"), src().resolve("same.txt"));
        assertEquals("S", Files.readString(dst().resolve("newer.txt")));
        assertEquals("D", Files.readString(dst().resolve("older.txt")));
        assertEquals("D", Files.readString(dst().resolve("same.txt")), "within the 2 s tolerance");
        assertEquals(2, job.skipped());
    }

    @Test
    void theToleranceOfOverwriteOlderCanBeChanged() throws Exception {
        file(src(), "a.txt", "S", 30_000);
        file(dst(), "a.txt", "D", 0);
        CopyJob loose = new CopyJob(entries(src().resolve("a.txt")), dst(), false, always(Conflict.OVERWRITE_OLDER));
        loose.setTimeTolerance(60_000);
        loose.run();
        assertEquals("D", Files.readString(dst().resolve("a.txt")), "30 s apart is within 60 s");
        CopyJob exact = new CopyJob(entries(src().resolve("a.txt")), dst(), false, always(Conflict.OVERWRITE_OLDER));
        exact.setTimeTolerance(0);
        exact.run();
        assertEquals("S", Files.readString(dst().resolve("a.txt")));
    }

    @Test
    void overwriteBySizeRules() throws Exception {
        file(src(), "a.txt", "12345");
        file(dst(), "a.txt", "123");
        file(src(), "b.txt", "123");
        file(dst(), "b.txt", "12345");
        file(src(), "c.txt", "123");
        file(dst(), "c.txt", "abc");
        Path[] all = {src().resolve("a.txt"), src().resolve("b.txt"), src().resolve("c.txt")};

        run(false, always(Conflict.OVERWRITE_SIZE_DIFFERS), all);
        assertEquals(Map.of("a.txt", "12345", "b.txt", "123", "c.txt", "abc"), tree(dst()));

        // "smaller" and "larger" describe the file that would be replaced
        file(dst(), "a.txt", "123");
        file(dst(), "b.txt", "12345");
        run(false, always(Conflict.OVERWRITE_SMALLER), all);
        assertEquals(Map.of("a.txt", "12345", "b.txt", "12345", "c.txt", "abc"), tree(dst()));

        file(dst(), "a.txt", "123");
        file(dst(), "b.txt", "12345");
        run(false, always(Conflict.OVERWRITE_LARGER), all);
        assertEquals(Map.of("a.txt", "123", "b.txt", "123", "c.txt", "abc"), tree(dst()));
    }

    @Test
    void dryRunPlansWithoutWriting() throws Exception {
        file(src(), "a.txt", "AAAA");
        file(src(), "tree/b.txt", "BB");
        file(src(), "tree/c.txt", "C");
        file(dst(), "tree/c.txt", "x");
        file(src(), "s.txt", "S");
        file(dst(), "s.txt", "x");
        Map<String, String> before = tree(dst());
        CopyJob job = new CopyJob(entries(src().resolve("a.txt"), src().resolve("tree"), src().resolve("s.txt")), dst(),
                false, (source, existing) -> source.getFileName().toString().equals("s.txt")
                        ? Conflict.SKIP : Conflict.KEEP_BOTH);
        job.setDryRun(true);
        job.run();
        assertEquals(before, tree(dst()), "nothing written");
        Map<String, String> planned = new java.util.TreeMap<>();
        for (CopyJob.Planned p : job.planned()) {
            planned.put(p.source().getFileName() + ":" + p.action(), p.target().getFileName().toString());
        }
        assertEquals(Map.of("a.txt:copy", "a.txt", "b.txt:copy", "b.txt", "c.txt:keep-both", "c (1).txt",
                "s.txt:skip", "s.txt"), planned);
        assertEquals(1, job.skipped());
    }

    @Test
    void dryRunMoveOnTheSameVolumeIsASingleRename() throws Exception {
        file(src(), "tree/b.txt", "B");
        CopyJob job = new CopyJob(entries(src().resolve("tree")), dst(), true, always(Conflict.CANCEL));
        job.setDryRun(true);
        job.run();
        assertEquals(1, job.planned().size());
        assertEquals("move", job.planned().get(0).action());
        assertTrue(Files.exists(src().resolve("tree/b.txt")));
    }

    @Test
    void aSingleItemCanBeCopiedUnderANewName() throws Exception {
        file(src(), "a.txt", "A", 1_000);
        CopyJob job = new CopyJob(entries(src().resolve("a.txt")), src(), false, always(Conflict.CANCEL));
        job.setTargetName("b.txt");
        job.run();
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(Map.of("a.txt", "A", "b.txt", "A"), tree(src()));
        assertEquals(BASE + 1_000, modified(src().resolve("b.txt")));
    }

    @Test
    void aSingleItemCanBeMovedUnderANewNameAndFoldersToo() throws Exception {
        file(src(), "a.txt", "A");
        file(src(), "tree/b.txt", "B");
        CopyJob job = new CopyJob(entries(src().resolve("a.txt")), src(), true, always(Conflict.CANCEL));
        job.setTargetName("c.txt");
        job.run();
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        job = new CopyJob(entries(src().resolve("tree")), dst(), true, always(Conflict.CANCEL));
        job.setTargetName("forest");
        job.run();
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(Map.of("c.txt", "A"), tree(src()));
        assertEquals(Map.of("forest", "<dir>", "forest/b.txt", "B"), tree(dst()));
    }

    @Test
    void renamingOntoAnExistingFileAsksAndOverwrites() throws Exception {
        file(src(), "a.txt", "A");
        file(src(), "b.txt", "old");
        CopyJob job = new CopyJob(entries(src().resolve("a.txt")), src(), false, always(Conflict.OVERWRITE));
        job.setTargetName("b.txt");
        job.run();
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals("A", Files.readString(src().resolve("b.txt")));
    }

    @Test
    void aCaseOnlyRenameByMoveWorks() throws Exception {
        file(src(), "a.txt", "A");
        CopyJob job = new CopyJob(entries(src().resolve("a.txt")), src(), true, always(Conflict.CANCEL));
        job.setTargetName("A.txt");
        job.run();
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        try (var names = Files.list(src())) {
            assertEquals(List.of("A.txt"), names.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aTargetNameIsIgnoredForSeveralSources() throws Exception {
        file(src(), "a.txt", "A");
        file(src(), "b.txt", "B");
        CopyJob job = new CopyJob(entries(src().resolve("a.txt"), src().resolve("b.txt")), dst(), false,
                always(Conflict.CANCEL));
        job.setTargetName("x.txt");
        job.run();
        assertEquals(Map.of("a.txt", "A", "b.txt", "B"), tree(dst()));
    }

    @Test
    void aFolderCannotBeCopiedIntoItself() throws Exception {
        file(src(), "tree/b.txt", "B");
        CopyJob job = new CopyJob(entries(src().resolve("tree")), src().resolve("tree"), false, always(Conflict.CANCEL));
        job.run();
        assertEquals(1, job.errors().size());
        assertEquals("Cannot copy a folder into itself", job.errors().get(0).reason());
    }

    @Test
    void copyingOntoItselfIsRejected() throws Exception {
        file(src(), "a.txt", "A");
        CopyJob job = new CopyJob(entries(src().resolve("a.txt")), src(), false, always(Conflict.OVERWRITE));
        job.run();
        assertEquals("Source and destination are the same", job.errors().get(0).reason());
        assertEquals("A", Files.readString(src().resolve("a.txt")));
    }

    @Test
    void aFileCannotReplaceAFolderOfTheSameName() throws Exception {
        file(src(), "x", "file");
        dir(dst(), "x");
        CopyJob job = run(false, always(Conflict.OVERWRITE), src().resolve("x"));
        assertEquals("A folder with this name already exists", job.errors().get(0).reason());
        assertTrue(Files.isDirectory(dst().resolve("x")));
    }

    @Test
    void aVanishedSourceIsReportedNotFatal() throws Exception {
        file(src(), "a.txt", "A");
        file(src(), "b.txt", "B");
        var list = entries(src().resolve("a.txt"), src().resolve("b.txt"));
        Files.delete(src().resolve("a.txt"));
        CopyJob job = new CopyJob(list, dst(), false, always(Conflict.CANCEL));
        job.run();
        assertEquals(1, job.errors().size());
        assertEquals(Map.of("b.txt", "B"), tree(dst()));
        assertFalse(job.isCancelled());
    }

    @Test
    void emptyFilesAreCopied() throws Exception {
        file(src(), "empty.txt", "");
        run(false, always(Conflict.CANCEL), src().resolve("empty.txt"));
        assertEquals(Map.of("empty.txt", ""), tree(dst()));
    }

    @Test
    void bigFilesAreCopiedIntact() throws Exception {
        byte[] data = new byte[9 * 1024 * 1024 + 123];
        new java.util.Random(7).nextBytes(data);
        Files.write(src().resolve("big.bin"), data);
        CopyJob job = run(false, always(Conflict.CANCEL), src().resolve("big.bin"));
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(data.length, job.bytesDone());
        assertTrue(java.util.Arrays.equals(data, Files.readAllBytes(dst().resolve("big.bin"))));
    }
}
