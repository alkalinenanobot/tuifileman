package org.ds.tuifileman.app;

import static org.ds.tuifileman.Fixtures.BASE;
import static org.ds.tuifileman.Fixtures.dir;
import static org.ds.tuifileman.Fixtures.file;
import static org.ds.tuifileman.Fixtures.modified;
import static org.ds.tuifileman.Fixtures.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.compare.DirCompare;
import org.ds.tuifileman.compare.DirCompare.Item;
import org.ds.tuifileman.compare.DirCompare.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncJobTest {
    @TempDir
    Path root;

    private Path left;
    private Path right;

    private void setUp() throws Exception {
        left = dir(root, "left");
        right = dir(root, "right");
    }

    private SyncJob sync(Mode mode) throws Exception {
        List<Item> items = DirCompare.scan(left, right, true, false, new AtomicBoolean(), new AtomicInteger());
        DirCompare.apply(items, mode);
        SyncJob job = new SyncJob(left, right, items);
        job.run();
        return job;
    }

    @Test
    void mergeCopiesBothWaysAndKeepsTimes() throws Exception {
        setUp();
        file(left, "only-left.txt", "L", 5_000);
        file(right, "only-right.txt", "R", 6_000);
        file(left, "newer.txt", "new", 100_000);
        file(right, "newer.txt", "old", 0);
        file(left, "deep/er/x.txt", "X", 7_000);
        SyncJob job = sync(Mode.MERGE);
        assertTrue(job.isDone());
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        Map<String, String> expected = Map.of("only-left.txt", "L", "only-right.txt", "R", "newer.txt", "new",
                "deep", "<dir>", "deep/er", "<dir>", "deep/er/x.txt", "X");
        assertEquals(expected, tree(left));
        assertEquals(expected, tree(right));
        assertEquals(BASE + 5_000, modified(right.resolve("only-left.txt")));
        assertEquals(BASE + 100_000, modified(right.resolve("newer.txt")));
        assertEquals(4, job.copied());
        assertEquals(0, job.deleted());
    }

    @Test
    void mirrorDeletesWhatIsNotOnTheSourceSideFolderContentFirst() throws Exception {
        setUp();
        file(left, "keep.txt", "K");
        file(right, "keep.txt", "K");
        file(left, "changed.txt", "now");
        file(right, "changed.txt", "was!");
        file(right, "stale/inner/old.txt", "o");
        file(right, "stale.txt", "s");
        SyncJob job = sync(Mode.MIRROR_TO_RIGHT);
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(tree(left), tree(right));
        assertEquals("now", Files.readString(right.resolve("changed.txt")));
        assertEquals(4, job.deleted(), "stale, stale/inner, stale/inner/old.txt and stale.txt");
    }

    @Test
    void newFoldersExistBeforeTheirFilesAreCopiedInParallel() throws Exception {
        // Enough folders for the parallel folder creation; a file copied before its new folder was created would
        // create that folder itself and make the folder's own creation fail as "appeared".
        setUp();
        for (int a = 0; a < 10; a++) {
            for (int b = 0; b < 8; b++) {
                for (int f = 0; f < 5; f++) {
                    file(left, "a" + a + "/b" + b + "/f" + f + ".txt", a + "-" + b + "-" + f);
                }
            }
        }
        SyncJob job = sync(Mode.MIRROR_TO_RIGHT);
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(tree(left), tree(right));
        assertEquals(400, job.copied());
        assertEquals(490, job.filesDone(), "90 folders and 400 files");
    }

    @Test
    void deletesRunInParallelLevelsWithFolderContentFirst() throws Exception {
        // Enough items for the parallel path; a folder deleted before its content would fail as "not empty".
        setUp();
        file(left, "keep/k.txt", "K");
        file(right, "keep/k.txt", "K");
        for (int a = 0; a < 10; a++) {
            file(right, "keep/stale" + a + ".txt", "s");
            for (int b = 0; b < 4; b++) {
                for (int f = 0; f < 3; f++) {
                    file(right, "gone" + a + "/b" + b + "/f" + f + ".txt", "x");
                }
            }
        }
        SyncJob job = sync(Mode.MIRROR_TO_RIGHT);
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(tree(left), tree(right));
        assertEquals(180, job.deleted(), "10 stale files, 10 + 40 folders and 120 files below them");
    }

    @Test
    void noTemporaryFilesAreLeftBehind() throws Exception {
        setUp();
        file(left, "a.bin", "x".repeat(100_000));
        sync(Mode.MERGE);
        assertTrue(tree(right).keySet().stream().noneMatch(name -> name.endsWith(".tmp")));
    }

    @Test
    void aUserFileNamedLikeTheTemporaryFileIsKept() throws Exception {
        setUp();
        file(left, "a.txt", "A");
        file(left, "a.txt.tfm-sync.tmp", "my notes");
        file(right, "a.txt.tfm-sync.tmp", "my notes");
        SyncJob job = sync(Mode.MERGE);
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(Map.of("a.txt", "A", "a.txt.tfm-sync.tmp", "my notes"), tree(right));
    }

    @Test
    void aTargetThatAppearsAfterTheScanIsNotReplaced() throws Exception {
        // On a case-insensitive side "foo" lands on an existing "Foo"; the scan saw no "foo" there.
        setUp();
        file(left, "foo.txt", "from left");
        file(left, "docs/b.txt", "b");
        List<Item> items = DirCompare.scan(left, right, true, false, new AtomicBoolean(), new AtomicInteger());
        DirCompare.apply(items, Mode.MERGE);
        file(right, "foo.txt", "already there");
        file(right, "docs/b.txt", "already there too");
        SyncJob job = new SyncJob(left, right, items);
        job.run();
        assertEquals("already there", Files.readString(right.resolve("foo.txt")));
        assertEquals("already there too", Files.readString(right.resolve("docs/b.txt")));
        assertEquals(3, job.errors().size(), job.errors().toString());
    }

    @Test
    void readOnlyTargetsAreReplacedAndDeleted() throws Exception {
        setUp();
        file(left, "f.txt", "new", 100_000);
        Path locked = file(right, "f.txt", "old", 0);
        Files.setAttribute(locked, "dos:readonly", true);
        Path doomed = file(right, "gone.txt", "g");
        Files.setAttribute(doomed, "dos:readonly", true);
        SyncJob job = sync(Mode.MIRROR_TO_RIGHT);
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals("new", Files.readString(right.resolve("f.txt")));
        assertFalse(Files.exists(doomed));
    }

    @Test
    void cancelBeforeRunDoesNothing() throws Exception {
        setUp();
        file(left, "a.txt", "A");
        List<Item> items = DirCompare.scan(left, right, true, false, new AtomicBoolean(), new AtomicInteger());
        DirCompare.apply(items, Mode.MERGE);
        SyncJob job = new SyncJob(left, right, items);
        job.cancel();
        job.run();
        assertTrue(job.isCancelled());
        assertTrue(job.summary().startsWith("Cancelled."));
        assertTrue(tree(right).isEmpty());
    }

    @Test
    void aFailureIsReportedAndTheRestStillRuns() throws Exception {
        setUp();
        file(left, "a.txt", "A");
        file(left, "b.txt", "B");
        List<Item> items = DirCompare.scan(left, right, true, false, new AtomicBoolean(), new AtomicInteger());
        DirCompare.apply(items, Mode.MERGE);
        Files.delete(left.resolve("a.txt"));
        SyncJob job = new SyncJob(left, right, items);
        job.run();
        assertEquals(1, job.errors().size());
        assertEquals(1, job.copied());
        assertEquals("B", Files.readString(right.resolve("b.txt")));
        assertTrue(job.summary().contains("1 error"));
    }

    @Test
    void summaryCountsCopiedAndDeleted() throws Exception {
        setUp();
        file(left, "a.txt", "A");
        file(right, "b.txt", "B");
        SyncJob job = sync(Mode.MIRROR_TO_RIGHT);
        assertTrue(job.summary().startsWith("Synchronized: 1 copied, 1 deleted"), job.summary());
    }
}
