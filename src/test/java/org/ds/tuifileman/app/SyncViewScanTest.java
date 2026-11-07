package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scan state of the sync view, driven without a terminal (only the screen-free parts are touched). */
class SyncViewScanTest {
    @TempDir
    Path temp;

    private static void await(SyncView view) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (view.scanning()) {
            assertTrue(System.nanoTime() < deadline, "the scan did not finish");
            Thread.sleep(5);
        }
    }

    @Test
    void aFailedRescanNeverLeavesThePreviousResultExecutable() throws Exception {
        Path left = Fixtures.dir(temp, "left");
        Path right = Fixtures.dir(temp, "right");
        Fixtures.file(left, "new.txt", "n");
        Fixtures.file(right, "extra.txt", "e");
        SyncView view = new SyncView(null, null, left, right);

        view.startScan();
        await(view);
        view.pollScan();
        assertNull(view.scanError());
        assertEquals(2, view.items().size());
        assertEquals(2, view.copyCount(), "merge copies both one-sided files");

        Files.delete(right.resolve("extra.txt"));
        Files.delete(right);
        view.startScan();
        assertTrue(view.items().isEmpty(), "the old items are gone the moment a new scan starts");
        assertEquals(0, view.copyCount());
        assertEquals(0, view.deleteCount());
        await(view);
        view.pollScan();
        assertNotNull(view.scanError(), "the failure is kept, not shown as 'directories are equal'");
        assertTrue(view.items().isEmpty());
        assertEquals(0, view.copyCount() + view.deleteCount(), "nothing is left to execute");
    }
}
