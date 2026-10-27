package org.ds.tuifileman.app;

import static org.ds.tuifileman.Fixtures.dir;
import static org.ds.tuifileman.Fixtures.file;
import static org.ds.tuifileman.Fixtures.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.app.Renamer.Applied;
import org.ds.tuifileman.app.Renamer.Move;
import org.ds.tuifileman.app.Renamer.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenamerTest {
    @TempDir
    Path folder;

    @Test
    void simpleRename() throws Exception {
        file(folder, "a.txt", "A");
        Result result = Renamer.apply(List.of(new Move(folder.resolve("a.txt"), "b.txt")));
        assertTrue(result.problems().isEmpty());
        assertEquals(Map.of("b.txt", "A"), tree(folder));
    }

    @Test
    void chainShiftWorksWhenEachTargetIsTheNextSource() throws Exception {
        for (int i = 1; i <= 3; i++) {
            file(folder, i + ".txt", "c" + i);
        }
        Result result = Renamer.apply(List.of(
                new Move(folder.resolve("1.txt"), "2.txt"), new Move(folder.resolve("2.txt"), "3.txt"),
                new Move(folder.resolve("3.txt"), "4.txt")));
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(3, result.applied().size());
        assertEquals(Map.of("2.txt", "c1", "3.txt", "c2", "4.txt", "c3"), tree(folder));
    }

    @Test
    void swapTwoNames() throws Exception {
        file(folder, "a.txt", "A");
        file(folder, "b.txt", "B");
        Result result = Renamer.apply(List.of(
                new Move(folder.resolve("a.txt"), "b.txt"), new Move(folder.resolve("b.txt"), "a.txt")));
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(Map.of("a.txt", "B", "b.txt", "A"), tree(folder));
    }

    @Test
    void caseOnlyRename() throws Exception {
        file(folder, "readme.txt", "R");
        Result result = Renamer.apply(List.of(new Move(folder.resolve("readme.txt"), "README.txt")));
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(Map.of("README.txt", "R"), tree(folder));
    }

    @Test
    void foldersAreRenamedToo() throws Exception {
        file(folder, "d/inner.txt", "I");
        Renamer.apply(List.of(new Move(folder.resolve("d"), "e")));
        assertEquals(Map.of("e", "<dir>", "e/inner.txt", "I"), tree(folder));
    }

    @Test
    void undoRestoresTheOriginalNamesEvenForAShift() throws Exception {
        for (int i = 1; i <= 3; i++) {
            file(folder, i + ".txt", "c" + i);
        }
        Map<String, String> before = tree(folder);
        Result result = Renamer.apply(List.of(
                new Move(folder.resolve("1.txt"), "2.txt"), new Move(folder.resolve("2.txt"), "3.txt"),
                new Move(folder.resolve("3.txt"), "4.txt")));
        assertFalse(before.equals(tree(folder)));
        Result undone = Renamer.apply(Renamer.undoMoves(result.applied()));
        assertTrue(undone.problems().isEmpty(), undone.problems().toString());
        assertEquals(before, tree(folder));
    }

    @Test
    void aFailingMoveKeepsTheOriginalNameAndTheOthersStillRun() throws Exception {
        file(folder, "a.txt", "A");
        file(folder, "b.txt", "B");
        dir(folder, "blocked");
        file(folder, "taken.txt", "T");
        // a -> taken.txt fails because the file exists and is not part of the batch; b -> c works
        Result result = Renamer.apply(List.of(
                new Move(folder.resolve("a.txt"), "taken.txt"), new Move(folder.resolve("b.txt"), "c.txt")));
        assertEquals(1, result.problems().size());
        assertEquals(1, result.applied().size());
        assertEquals(new Applied(folder.resolve("b.txt"), folder.resolve("c.txt")), result.applied().get(0));
        assertEquals("A", Files.readString(folder.resolve("a.txt")));
        assertEquals("T", Files.readString(folder.resolve("taken.txt")));
    }

    @Test
    void undoMovesAreInReverseOrder() {
        Path a = folder.resolve("a");
        Path b = folder.resolve("b");
        List<Move> undo = Renamer.undoMoves(List.of(new Applied(a, folder.resolve("x")), new Applied(b, folder.resolve("y"))));
        assertEquals(List.of(new Move(folder.resolve("y"), "b"), new Move(folder.resolve("x"), "a")), undo);
    }
}
