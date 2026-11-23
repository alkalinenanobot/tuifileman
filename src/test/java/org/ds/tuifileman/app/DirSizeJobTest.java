package org.ds.tuifileman.app;

import static org.ds.tuifileman.Fixtures.dir;
import static org.ds.tuifileman.Fixtures.entries;
import static org.ds.tuifileman.Fixtures.file;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.model.EntrySort;
import org.ds.tuifileman.model.FileEntry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirSizeJobTest {
    @TempDir
    Path root;

    @Test
    void addsUpEveryFileBelowAFolder() throws Exception {
        file(root, "tree/a.txt", "12345");
        file(root, "tree/deep/b.txt", "1234567890");
        file(root, "tree/deep/deeper/c.txt", "123");
        dir(root, "tree/empty");
        DirSizeJob job = new DirSizeJob(entries(root.resolve("tree")));
        job.run();
        assertTrue(job.isDone());
        DirSizeJob.Result result = job.results().get(0);
        assertEquals(18, result.bytes());
        assertEquals(3, result.files());
        assertEquals(3, result.folders(), "deep, deeper, empty");
        assertEquals(0, result.unreadable());
        assertEquals(18L, job.sizes().get(root.resolve("tree")));
    }

    @Test
    void anEmptyFolderIsZeroBytes() throws Exception {
        dir(root, "empty");
        DirSizeJob job = new DirSizeJob(entries(root.resolve("empty")));
        job.run();
        assertEquals(0L, job.sizes().get(root.resolve("empty")));
    }

    @Test
    void aCancelledRunReportsNoUnfinishedFolder() throws Exception {
        file(root, "a/x.txt", "x");
        DirSizeJob job = new DirSizeJob(entries(root.resolve("a")));
        job.cancel();
        job.run();
        assertTrue(job.isDone());
        assertTrue(job.results().isEmpty());
    }

    @Test
    void panelShowsTheMeasuredSizesAndKeepsTheCursor() throws Exception {
        file(root, "big/f.bin", "x".repeat(2000));
        file(root, "small/f.bin", "x");
        file(root, "mid/f.bin", "x".repeat(100));
        file(root, "plain.txt", "abc");
        PanelState state = new PanelState(root);
        assertNull(state.refresh());
        state.selectName("mid", 10);
        assertEquals("<DIR>", state.current().displaySize());

        DirSizeJob job = new DirSizeJob(state.entries().stream().filter(FileEntry::directory)
                .filter(e -> !e.name().equals("..")).toList());
        job.run();
        state.showSizes(job.sizes(), 10);

        assertEquals("mid", state.current().name(), "the cursor stays on its entry");
        assertTrue(state.current().sized());
        assertEquals(100, state.current().size());
        assertFalse(state.entries().stream().filter(e -> e.name().equals("plain.txt")).findFirst().get().sized());

        state.sortBy(EntrySort.Key.SIZE, 10); // biggest first, folders included now
        List<String> names = state.entries().stream().map(FileEntry::name).toList();
        assertEquals(List.of("..", "big", "mid", "small", "plain.txt"), names);
    }

    @Test
    void markedFoldersCountOnceMeasured() throws Exception {
        file(root, "d/f.bin", "x".repeat(40));
        file(root, "f.txt", "x".repeat(2));
        PanelState state = new PanelState(root);
        assertNull(state.refresh());
        state.invertMarks(true);
        assertEquals(2, state.markedSize(), "an unmeasured folder adds nothing");
        state.showSizes(java.util.Map.of(root.resolve("d"), 40L), 10);
        assertEquals(42, state.markedSize());
    }

    @Test
    void measuresManyFoldersInParallelAndKeepsTheirOrder() throws Exception {
        List<Path> given = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String folder = String.format("dir-%02d", i);
            file(root, folder + "/top.bin", "x".repeat(i + 1));
            if (i % 3 == 0) {
                file(root, folder + "/a/b/nested.bin", "y".repeat(10));
            }
            given.add(root.resolve(folder));
        }
        java.util.Collections.reverse(given);
        DirSizeJob job = new DirSizeJob(entries(given.toArray(Path[]::new)));
        job.run();

        List<DirSizeJob.Result> results = job.results();
        assertEquals(given.size(), results.size());
        for (int i = 0; i < given.size(); i++) {
            Path expected = given.get(i);
            int index = 39 - i;
            long bytes = (index + 1) + (index % 3 == 0 ? 10 : 0);
            assertEquals(expected, results.get(i).folder(), "results stay in the order given");
            assertEquals(bytes, results.get(i).bytes());
            assertEquals(index % 3 == 0 ? 2 : 1, results.get(i).files());
            assertEquals(index % 3 == 0 ? 2 : 0, results.get(i).folders());
        }
    }

    @Test
    void aFailingFolderIsAProblemAndTheOthersAreMeasured() throws Exception {
        List<FileEntry> given = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String folder = String.format("dir-%02d", i);
            for (int j = 0; j < 20; j++) {
                file(root, folder + "/s" + (j % 4) + "/t" + (j % 3) + "/f" + j + ".bin", "x".repeat(5));
            }
            given.add(entries(root.resolve(folder)).get(0));
        }
        // Reading any attribute of a closed zip file system throws ClosedFileSystemException, an unchecked failure
        // that the listing does not swallow. Given last, it is forked last and joined first.
        Path broken;
        try (java.nio.file.FileSystem zip = java.nio.file.FileSystems.newFileSystem(root.resolve("a.zip"),
                java.util.Map.of("create", "true"))) {
            broken = java.nio.file.Files.createDirectories(zip.getPath("/inside/sub"));
        }
        given.add(new FileEntry("sub", broken, true, 0, java.nio.file.attribute.FileTime.fromMillis(0)));
        DirSizeJob job = new DirSizeJob(given);

        job.run();

        assertTrue(job.isDone());
        List<DirSizeJob.Result> results = job.results();
        assertEquals(30, results.size(), "every healthy folder was measured before the job claimed to be done");
        for (DirSizeJob.Result result : results) {
            assertEquals(100, result.bytes());
            assertEquals(20, result.files());
        }
        assertEquals(List.of(new Job.Problem(broken.toString(), "ClosedFileSystemException")), job.errors());
        assertTrue(job.summary().endsWith(", 1 problem"), job.summary());
    }
}
