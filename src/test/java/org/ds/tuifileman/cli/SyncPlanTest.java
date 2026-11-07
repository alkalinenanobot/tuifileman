package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.ds.tuifileman.Fixtures;
import org.ds.tuifileman.UnreadableFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a stored plan may and may not do to the trees as they are now, and how a scan reacts to unreadable rules. */
class SyncPlanTest {
    @TempDir
    Path dir;

    private Path left() throws IOException {
        return Fixtures.dir(dir, "a");
    }

    private Path right() throws IOException {
        return Fixtures.dir(dir, "b");
    }

    private Cmd.Result applyPlan(String mode, String... lines) throws Exception {
        Path plan = Cmd.file(dir, "plan.jsonl", String.join("\n", lines) + "\n");
        return Cmd.run("sync", "-q", "-y", "-m", mode, "-p", plan.toString(), left().toString(), right().toString());
    }

    private boolean caseInsensitive() throws IOException {
        Cmd.file(dir, "probe", "x");
        return Files.exists(dir.resolve("PROBE"));
    }

    // ---- stale plans ------------------------------------------------------------------------------------------

    @Test
    void aPlanThatSaysOnlyRightNeverDeletesAFileThatNowExistsOnBothSides() throws Exception {
        Cmd.file(dir, "b/extra.txt", "right");
        Cmd.file(dir, "a/extra.txt", "left, created after the plan was made");
        Cmd.Result byMode = applyPlan("mirror-right", "{\"path\":\"extra.txt\",\"status\":\"only-right\"}");
        assertEquals(2, byMode.exit(), byMode.err());
        assertTrue(byMode.err().contains("out of date"), byMode.err());
        assertEquals("right", Files.readString(dir.resolve("b/extra.txt")));
        Cmd.Result explicit = applyPlan("merge",
                "{\"path\":\"extra.txt\",\"status\":\"only-right\",\"action\":\"delete-right\"}");
        assertEquals(2, explicit.exit(), explicit.err());
        assertEquals("right", Files.readString(dir.resolve("b/extra.txt")));
    }

    @Test
    void aPlanThatSaysOnlyLeftNeverDeletesAFileThatNowExistsOnBothSides() throws Exception {
        Cmd.file(dir, "a/extra.txt", "left");
        Cmd.file(dir, "b/extra.txt", "right, created after the plan was made");
        Cmd.Result result = applyPlan("mirror-left", "{\"path\":\"extra.txt\",\"status\":\"only-left\"}");
        assertEquals(2, result.exit(), result.err());
        assertEquals("left", Files.readString(dir.resolve("a/extra.txt")));
    }

    // ---- links in the path ------------------------------------------------------------------------------------

    private Path link(Path link, Path target) throws Exception {
        try {
            return Fixtures.directoryLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "cannot create a folder link here: " + e.getMessage());
            return null;
        }
    }

    @Test
    void aPlanPathThroughALinkedFolderIsRejectedOnEitherSide() throws Exception {
        Path outside = Fixtures.dir(dir, "outside");
        Cmd.file(dir, "outside/foo.so", "precious");
        Cmd.file(dir, "a/lib/foo.so", "x");
        Fixtures.dir(dir, "b");
        link(dir.resolve("b/lib"), outside);
        Cmd.Result viaRight = applyPlan("merge", "{\"path\":\"lib/foo.so\",\"action\":\"delete-right\"}");
        assertEquals(2, viaRight.exit(), viaRight.err());
        assertTrue(viaRight.err().contains("passes through a link"), viaRight.err());
        assertEquals("precious", Files.readString(outside.resolve("foo.so")));

        Path outside2 = Fixtures.dir(dir, "outside2");
        Cmd.file(dir, "outside2/bar.so", "precious");
        Cmd.file(dir, "b/other/bar.so", "x");
        Fixtures.dir(dir, "a");
        link(dir.resolve("a/other"), outside2);
        Cmd.Result viaLeft = applyPlan("merge", "{\"path\":\"other/bar.so\",\"action\":\"delete-left\"}");
        assertEquals(2, viaLeft.exit(), viaLeft.err());
        assertTrue(viaLeft.err().contains("passes through a link"), viaLeft.err());
        assertEquals("precious", Files.readString(outside2.resolve("bar.so")));
    }

    @Test
    void planPathsInPlainFoldersStillWorkAndAMissingParentIsFine() throws Exception {
        Cmd.file(dir, "a/sub/one.txt", "1");
        Cmd.file(dir, "a/sub/two.txt", "2");
        Cmd.file(dir, "a/new/deep/three.txt", "3");
        Fixtures.dir(dir, "b");
        Cmd.Result result = applyPlan("merge",
                "{\"path\":\"sub/one.txt\",\"action\":\"to-right\"}",
                "{\"path\":\"sub/two.txt\",\"action\":\"to-right\"}",
                "{\"path\":\"new/deep/three.txt\",\"action\":\"to-right\"}");
        assertEquals(0, result.exit(), result.err());
        assertEquals("1", Files.readString(dir.resolve("b/sub/one.txt")));
        assertEquals("2", Files.readString(dir.resolve("b/sub/two.txt")));
        assertEquals("3", Files.readString(dir.resolve("b/new/deep/three.txt")));
    }

    // ---- duplicate and case-different paths -------------------------------------------------------------------

    @Test
    void anExactDuplicatePathIsAUsageError() throws Exception {
        Cmd.file(dir, "a/f.txt", "f");
        Fixtures.dir(dir, "b");
        Cmd.Result result = applyPlan("merge",
                "{\"path\":\"f.txt\",\"action\":\"to-right\"}",
                "{\"path\":\"f.txt\",\"action\":\"to-right\"}");
        assertEquals(2, result.exit(), result.err());
        assertTrue(result.err().contains("plan line 2: duplicate path"), result.err());
        assertFalse(Files.exists(dir.resolve("b/f.txt")));
    }

    @Test
    void planPathsDifferingOnlyInCaseAreTwoFilesOnACaseSensitiveFileSystem() throws Exception {
        assumeFalse(caseInsensitive(), "needs a case-sensitive file system");
        Cmd.file(dir, "a/Foo.txt", "upper");
        Cmd.file(dir, "a/foo.txt", "lower");
        Fixtures.dir(dir, "b");
        Cmd.Result result = applyPlan("merge",
                "{\"path\":\"Foo.txt\",\"action\":\"to-right\"}",
                "{\"path\":\"foo.txt\",\"action\":\"to-right\"}");
        assertEquals(0, result.exit(), result.err());
        assertEquals("upper", Files.readString(dir.resolve("b/Foo.txt")));
        assertEquals("lower", Files.readString(dir.resolve("b/foo.txt")));
    }

    @Test
    void planPathsDifferingOnlyInCaseAreOneFileOnACaseInsensitiveFileSystem() throws Exception {
        assumeTrue(caseInsensitive(), "needs a case-insensitive file system");
        Cmd.file(dir, "a/Foo.txt", "upper");
        Fixtures.dir(dir, "b");
        Cmd.Result result = applyPlan("merge",
                "{\"path\":\"Foo.txt\",\"action\":\"to-right\"}",
                "{\"path\":\"foo.txt\",\"action\":\"to-right\"}");
        assertEquals(2, result.exit(), result.err());
        assertTrue(result.err().contains("plan line 2: duplicate path"), result.err());
        assertFalse(Files.exists(dir.resolve("b/Foo.txt")));
    }

    // ---- unreadable rules -------------------------------------------------------------------------------------

    @Test
    void anUnreadableNestedGitignoreStopsTheSyncBeforeAnythingIsCopiedOrDeleted() throws Exception {
        Cmd.file(dir, "a/new.txt", "would be copied");
        Cmd.file(dir, "a/sub/.gitignore", "*.keep\n");
        Cmd.file(dir, "a/sub/x.txt", "x");
        Cmd.file(dir, "b/sub/x.txt", "x");
        Cmd.file(dir, "b/sub/protected.keep", "protected by the rules that cannot be read");
        Path rules = dir.resolve("a/sub/.gitignore");
        try (AutoCloseable locked = UnreadableFile.lock(rules)) {
            assumeTrue(locked != null, "the user can read any file (root)");
            Cmd.Result result = Cmd.run("sync", "-q", "-y", "-g", "-m", "mirror-right",
                    left().toString(), right().toString());
            assertEquals(4, result.exit(), result.err());
            assertTrue(result.err().contains(".gitignore"), result.err());
            assertTrue(result.err().contains("cannot read"), result.err());
        }
        assertTrue(Files.exists(dir.resolve("b/sub/protected.keep")), "rules that vanished must not delete");
        assertFalse(Files.exists(dir.resolve("b/new.txt")), "nothing is copied either");
    }
}
