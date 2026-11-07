package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code rename}: plans, safety and the undo log. The engine's chain, swap and case-only cases are in app/RenamerTest. */
class RenameContractTest {
    @TempDir
    Path dir;

    @TempDir
    Path logs;

    private String glob() {
        return dir + java.io.File.separator + "*.txt";
    }

    private Path make(String name) throws Exception {
        return Cmd.file(dir, name, name);
    }

    private List<String> names() throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void dryRunListsTheRenamesAndChangesNothing() throws Exception {
        make("a.txt");
        make("b.txt");
        Cmd.Result result = Cmd.run("rename", "-n", "-q", "-N", "doc_[C]", glob());
        assertEquals(0, result.exit(), result.err());
        assertTrue(result.out().contains("doc_1.txt") && result.out().contains("doc_2.txt"));
        assertEquals(List.of("a.txt", "b.txt"), names());
    }

    @Test
    void aRealRunNeedsYesWhenThereIsNoTerminal() throws Exception {
        make("a.txt");
        Cmd.Result result = Cmd.run("rename", "-q", "-N", "x", dir.resolve("a.txt").toString());
        assertEquals(2, result.exit());
        assertEquals(List.of("a.txt"), names());
    }

    @Test
    void aClashChangesNothing() throws Exception {
        make("a.txt");
        make("b.txt");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "-N", "same", glob());
        assertEquals(2, result.exit());
        assertEquals(List.of("a.txt", "b.txt"), names());
    }

    @Test
    void anInvalidNameChangesNothing() throws Exception {
        make("a.txt");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "-N", "a?b", dir.resolve("a.txt").toString());
        assertEquals(2, result.exit());
        assertEquals(List.of("a.txt"), names());
    }

    @Test
    void itemsFromTwoFoldersAreRefused() throws Exception {
        Path one = Cmd.file(dir, "one/a.txt", "a");
        Path two = Cmd.file(dir, "two/b.txt", "b");
        assertEquals(2, Cmd.run("rename", "-q", "-y", "-N", "x[C]", one.toString(), two.toString()).exit());
    }

    @Test
    void maskCounterSearchReplaceAndCaseRenameTheFiles() throws Exception {
        make("a.txt");
        make("b.txt");
        make("c.txt");
        make("Hello World.TXT");
        Cmd.Result counted = Cmd.run("rename", "-q", "-y", "-N", "img_[C]", "--digits", "3", "--start", "7",
                dir.resolve("a.txt").toString(), dir.resolve("b.txt").toString(), dir.resolve("c.txt").toString());
        assertEquals(0, counted.exit(), counted.err());
        Cmd.Result replaced = Cmd.run("rename", "-q", "-y", "-s", " ", "-r", "_", "--case", "lower",
                dir.resolve("Hello World.TXT").toString());
        assertEquals(0, replaced.exit(), replaced.err());
        assertEquals(List.of("hello_world.txt", "img_007.txt", "img_008.txt", "img_009.txt"), names());
    }

    @Test
    void theUndoLogRevertsAShift() throws Exception {
        make("1.txt");
        make("2.txt");
        make("3.txt");
        Path log = logs.resolve("undo.jsonl");
        Cmd.Result run = Cmd.run("rename", "-q", "-y", "-N", "[C]", "--start", "2", "-u", log.toString(),
                dir.resolve("1.txt").toString(), dir.resolve("2.txt").toString(), dir.resolve("3.txt").toString());
        assertEquals(0, run.exit(), run.err());
        assertEquals(List.of("2.txt", "3.txt", "4.txt"), names());
        Cmd.Result undo = Cmd.run("rename", "-q", "-y", "--undo", log.toString());
        assertEquals(0, undo.exit(), undo.err());
        assertEquals(List.of("1.txt", "2.txt", "3.txt"), names());
        assertEquals("1.txt", Files.readString(dir.resolve("1.txt")));
        assertEquals("3.txt", Files.readString(dir.resolve("3.txt")));
    }

    @Test
    void anExistingUndoLogIsNeverOverwritten() throws Exception {
        make("a.txt");
        Path log = Cmd.file(logs, "undo.jsonl", "keep me");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "-N", "b", "-u", log.toString(), dir.resolve("a.txt").toString());
        assertEquals(2, result.exit());
        assertEquals("keep me", Files.readString(log));
        assertEquals(List.of("a.txt"), names());
    }

    @Test
    void jsonResultCarriesTheCounts() throws Exception {
        make("a.txt");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "-f", "jsonl", "-N", "b", dir.resolve("a.txt").toString());
        assertEquals(0, result.exit(), result.err());
        assertEquals("result", result.lastJson().get("kind"));
        assertEquals("rename", result.lastJson().get("command"));
        assertEquals(1, ((Number) result.lastJson().get("renamed")).intValue());
        assertFalse(result.lastJson().containsKey("stats"));
    }
}
