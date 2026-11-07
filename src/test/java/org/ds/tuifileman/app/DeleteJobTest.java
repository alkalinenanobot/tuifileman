package org.ds.tuifileman.app;

import static org.ds.tuifileman.Fixtures.dir;
import static org.ds.tuifileman.Fixtures.entries;
import static org.ds.tuifileman.Fixtures.file;
import static org.ds.tuifileman.Fixtures.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeleteJobTest {
    @TempDir
    Path root;

    @Test
    void deletesFilesAndWholeTrees() throws Exception {
        file(root, "a.txt", "A");
        file(root, "tree/b.txt", "B");
        file(root, "tree/deep/c.txt", "C");
        file(root, "keep.txt", "K");
        dir(root, "empty");
        DeleteJob job = new DeleteJob(entries(root.resolve("a.txt"), root.resolve("tree"), root.resolve("empty")));
        job.run();
        assertTrue(job.isDone());
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertEquals(Map.of("keep.txt", "K"), tree(root));
    }

    @Test
    void dryRunCountsButDeletesNothing() throws Exception {
        file(root, "a.txt", "A");
        file(root, "tree/b.txt", "B");
        file(root, "tree/deep/c.txt", "C");
        Map<String, String> before = tree(root);
        DeleteJob job = new DeleteJob(entries(root.resolve("a.txt"), root.resolve("tree")));
        job.setDryRun(true);
        job.run();
        assertEquals(before, tree(root));
        assertEquals(2, job.planned().size());
        DeleteJob.Planned file = job.planned().get(0);
        assertEquals(root.resolve("a.txt"), file.target());
        assertFalse(file.directory());
        assertEquals(1, file.items());
        DeleteJob.Planned tree = job.planned().get(1);
        assertTrue(tree.directory());
        assertEquals(4, tree.items(), "tree, b.txt, deep, c.txt");
    }

    @Test
    void readOnlyFilesAreDeletedToo() throws Exception {
        Path locked = file(root, "locked.txt", "L");
        Files.setAttribute(locked, "dos:readonly", true);
        DeleteJob job = new DeleteJob(entries(locked));
        job.run();
        assertTrue(job.errors().isEmpty(), job.errors().toString());
        assertFalse(Files.exists(locked));
    }

    @Test
    void cancelBeforeRunKeepsEverything() throws Exception {
        file(root, "a.txt", "A");
        DeleteJob job = new DeleteJob(entries(root.resolve("a.txt")));
        job.cancel();
        job.run();
        assertTrue(job.isCancelled());
        assertEquals(Map.of("a.txt", "A"), tree(root));
    }
}
