package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code rename}: plans, safety, the undo log and the chain, swap and case-only renames of the engine. */
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
    void renamesWithCounterAndDigits() throws Exception {
        make("a.txt");
        make("b.txt");
        make("c.txt");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "-N", "img_[C]", "--digits", "3", "--start", "7",
                glob());
        assertEquals(0, result.exit(), result.err());
        assertEquals(List.of("img_007.txt", "img_008.txt", "img_009.txt"), names());
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
    void searchReplaceAndCaseWork() throws Exception {
        make("Hello World.TXT");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "-s", " ", "-r", "_", "--case", "lower",
                dir.resolve("Hello World.TXT").toString());
        assertEquals(0, result.exit(), result.err());
        assertEquals(List.of("hello_world.txt"), names());
    }

    @Test
    void aCaseOnlyRenameWorks() throws Exception {
        make("abc.txt");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "--case", "upper", "--case-scope", "name",
                dir.resolve("abc.txt").toString());
        assertEquals(0, result.exit(), result.err());
        assertEquals(List.of("ABC.txt"), names());
    }

    @Test
    void theCounterShiftsAChainOfNames() throws Exception {
        // 1 -> 2, 2 -> 3, 3 -> 4: every target is another item's current name
        make("1.txt");
        make("2.txt");
        make("3.txt");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "-N", "[C]", "--start", "2", dir.resolve("1.txt").toString(),
                dir.resolve("2.txt").toString(), dir.resolve("3.txt").toString());
        assertEquals(0, result.exit(), result.err());
        assertEquals(List.of("2.txt", "3.txt", "4.txt"), names());
        assertEquals("1.txt", Files.readString(dir.resolve("2.txt")), "contents moved with the names");
        assertEquals("3.txt", Files.readString(dir.resolve("4.txt")));
    }

    @Test
    void twoNamesCanBeSwapped() throws Exception {
        make("1.txt");
        make("2.txt");
        Cmd.Result result = Cmd.run("rename", "-q", "-y", "-N", "[C]", "--start", "2", "--step", "-1",
                dir.resolve("1.txt").toString(), dir.resolve("2.txt").toString());
        assertEquals(0, result.exit(), result.err());
        assertEquals("1.txt", Files.readString(dir.resolve("2.txt")));
        assertEquals("2.txt", Files.readString(dir.resolve("1.txt")));
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
