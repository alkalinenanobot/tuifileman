package org.ds.tuifileman.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParallelTest {
    @TempDir
    Path root;

    @Test
    void aPathThatDoesNotExistYetIsJudgedByItsParent() {
        assertEquals(Parallel.storage(root), Parallel.storage(root.resolve("not/yet/there")));
    }

    @Test
    void threadsAreAlwaysPositiveAndTheForcedValueWins() {
        assertTrue(Parallel.threads(root) >= 1);
        assertTrue(Parallel.threads(root, root.resolve("x")) >= 1);
        System.setProperty("tfm.threads", "3");
        try {
            assertEquals(3, Parallel.threads(root));
        } finally {
            System.clearProperty("tfm.threads");
        }
    }
}
