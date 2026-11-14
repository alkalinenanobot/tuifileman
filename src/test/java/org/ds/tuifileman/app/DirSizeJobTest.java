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
}
