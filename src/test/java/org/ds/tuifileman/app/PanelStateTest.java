package org.ds.tuifileman.app;

import static org.ds.tuifileman.Fixtures.dir;
import static org.ds.tuifileman.Fixtures.file;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.model.EntrySort;
import org.ds.tuifileman.model.FileEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PanelStateTest {
    private static final int ROWS = 5;

    @TempDir
    Path root;

    private PanelState panel() throws Exception {
        dir(root, "beta");
        dir(root, "Alpha");
        file(root, "zeta.txt", "z");
        file(root, "apple.txt", "aa");
        file(root, "Banana.txt", "bbb");
        file(root, "ice-cream.txt", "i");
        dir(root, "sub");
        PanelState state = new PanelState(root);
        assertNull(state.refresh());
        return state;
    }

    private static List<String> names(PanelState state) {
        return state.entries().stream().map(FileEntry::name).toList();
    }

    private static List<String> names(List<FileEntry> entries) {
        return entries.stream().map(FileEntry::name).toList();
    }

    @Test
    void cursorIsClampedAndTheViewScrolls() throws Exception {
        PanelState state = panel();
        state.moveCursor(-5, ROWS);
        assertEquals(0, state.cursor());
        state.moveCursor(100, ROWS);
        assertEquals(state.entries().size() - 1, state.cursor());
        assertEquals(state.cursor() - ROWS + 1, state.first(), "the cursor is the last visible row");
        state.cursorTo(0, ROWS);
        assertEquals(0, state.first());
        state.cursorTo(3, ROWS);
        assertEquals(0, state.first(), "already visible, no scrolling");
        state.cursorTo(5, ROWS);
        assertEquals(1, state.first());
    }

    @Test
    void goingUpScrollsTheLeftDirectoryIntoView() throws Exception {
        for (int i = 0; i < 40; i++) {
            dir(root, String.format("d%02d", i));
        }
        PanelState state = new PanelState(root.resolve("d35"));
        assertNull(state.refresh());
        state.ensureVisible(ROWS);
        assertNull(state.goUp());
        assertEquals("d35", state.current().name());
        assertTrue(state.first() <= state.cursor() && state.cursor() < state.first() + ROWS,
                "cursor " + state.cursor() + " must be inside the view starting at " + state.first());
        assertEquals(state.cursor() - ROWS / 2, state.first(), "roughly centred");
    }

    @Test
    void marksSkipTheParentEntryTrackSizesAndDecideTheTargets() throws Exception {
        PanelState state = panel();
        assertEquals(7, state.markedOrAll().size(), "without marks everything but ..");
        assertFalse(names(state.markedOrAll()).contains(".."));
        assertTrue(state.targets().isEmpty(), "the cursor starts on ..");
        state.selectName("beta", ROWS);
        assertEquals(List.of("beta"), names(state.targets()), "no marks: the entry under the cursor");

        state.cursorTo(0, ROWS);
        state.toggleMark(ROWS, true);
        assertEquals(0, state.markedCount(), "the .. entry cannot be marked");
        assertEquals(0, state.cursor(), "nothing happens on ..");
        state.selectName("apple.txt", ROWS);
        state.toggleMark(ROWS, true);
        state.toggleMark(ROWS, false);
        state.selectName("zeta.txt", ROWS);
        state.toggleMark(ROWS, false);
        assertEquals(List.of("apple.txt", "Banana.txt", "zeta.txt"), names(state.markedOrAll()));
        assertEquals(6, state.markedSize(), "aa + bbb + z");
        state.selectName("sub", ROWS);
        assertEquals(List.of("apple.txt", "Banana.txt", "zeta.txt"), names(state.targets()), "marks win over the cursor");
        state.selectName("zeta.txt", ROWS);
        state.toggleMark(ROWS, false);
        assertFalse(state.isMarked(state.current()));
        state.clearMarks();
        assertEquals(0, state.markedCount());
    }

    @Test
    void invertMarksFlipsEveryEntryExceptTheParentAndOptionallySkipsDirectories() throws Exception {
        PanelState state = panel();
        state.selectName("apple.txt", ROWS);
        state.toggleMark(ROWS, false);
        state.invertMarks(true);
        assertFalse(state.markedOrAll().isEmpty());
        assertTrue(state.markedOrAll().stream().noneMatch(e -> e.name().equals("apple.txt")));
        assertTrue(state.markedOrAll().stream().anyMatch(FileEntry::directory), "directories are inverted too");
        assertTrue(state.markedOrAll().stream().noneMatch(e -> e.name().equals("..")));
        state.invertMarks(true);
        assertEquals(List.of("apple.txt"), names(state.markedOrAll()));
        state.clearMarks();
        state.invertMarks(false);
        assertTrue(state.markedOrAll().stream().noneMatch(FileEntry::directory), "files only");
        assertEquals(state.markedCount(), state.markedOrAll().size());
    }

    @Test
    void marksSurviveARefreshAndVanishWithTheirFile() throws Exception {
        PanelState state = panel();
        state.selectName("apple.txt", ROWS);
        state.toggleMark(ROWS, false);
        state.selectName("zeta.txt", ROWS);
        state.toggleMark(ROWS, false);
        Files.delete(root.resolve("zeta.txt"));
        assertNull(state.refresh());
        assertEquals(1, state.markedCount());
        assertTrue(state.isMarked(state.entries().stream().filter(e -> e.name().equals("apple.txt")).findFirst().orElseThrow()));
    }

    @Test
    void quickSearchPrefersAPrefixThenContainsStepsThroughMatchesAndReportsNoMatch() throws Exception {
        PanelState state = panel();
        state.searchAppend('a', ROWS);
        assertEquals("Alpha", state.current().name());
        assertTrue(state.searching());
        state.searchStep(1, ROWS);
        assertEquals("apple.txt", state.current().name());
        state.searchStep(1, ROWS);
        assertEquals("apple.txt", state.current().name(), "no further prefix match: Banana only contains the letter");
        state.searchStep(-1, ROWS);
        assertEquals("Alpha", state.current().name());
        state.searchAppend('p', ROWS);
        assertEquals("apple.txt", state.current().name(), "'ap' is a prefix of apple.txt and only inside Alpha");
        state.searchClear();
        assertFalse(state.searching());

        state.searchAppend('c', ROWS);
        state.searchAppend('r', ROWS);
        assertEquals("ice-cream.txt", state.current().name(), "no prefix match: falls back to contains");
        state.searchAppend('x', ROWS);
        assertFalse(state.searchMatched());
        state.searchBackspace(ROWS);
        assertTrue(state.searchMatched());
        state.searchBackspace(ROWS);
        state.searchBackspace(ROWS);
        assertFalse(state.searching());
        assertEquals("", state.searchText());
    }

    @Test
    void enterAndGoUpKeepTheCursorOnWhereYouCameFromAndTheParentEntryGoesUpToo() throws Exception {
        PanelState state = panel();
        file(root, "sub/inner.txt", "i");
        state.selectName("zeta.txt", ROWS);
        assertEquals("zeta.txt is not a directory", state.enter());
        assertEquals(root, state.directory());

        state.selectName("sub", ROWS);
        assertNull(state.enter());
        assertEquals(root.resolve("sub"), state.directory());
        assertEquals(List.of("..", "inner.txt"), names(state));
        assertEquals(0, state.cursor());
        assertNull(state.goUp());
        assertEquals(root, state.directory());
        assertEquals("sub", state.current().name());

        state.selectName("Alpha", ROWS);
        assertNull(state.enter());
        assertEquals("..", state.current().name());
        assertNull(state.enter());
        assertEquals(root, state.directory());
        assertEquals("Alpha", state.current().name());
    }

    @Test
    void emptyAndMissingFoldersAreHandled() throws Exception {
        dir(root, "empty");
        PanelState empty = new PanelState(root.resolve("empty"));
        empty.refresh();
        assertEquals(List.of(".."), names(empty), "an empty folder still has the parent entry");
        assertTrue(empty.targets().isEmpty());
        assertTrue(empty.markedOrAll().isEmpty());

        PanelState state = panel();
        String error = state.goTo(root.resolve("nope"));
        assertNotNull(error);
        assertTrue(error.startsWith("Cannot open "), error);
        assertEquals(root, state.directory(), "a missing folder keeps the current one");
        assertFalse(state.entries().isEmpty());

        file(root, "sub/x.txt", "x");
        state.selectName("sub", ROWS);
        state.enter();
        try (var walk = Files.walk(root.resolve("sub"))) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        assertNotNull(state.refresh(), "a vanished folder is reported");
        assertTrue(state.entries().isEmpty());
        assertNull(state.current());
    }

    @Test
    void foldersComeFirstAndEverySortKeyOrdersTheFilesWithinThem() throws Exception {
        PanelState state = panel();
        assertEquals(List.of("..", "Alpha", "beta", "sub", "apple.txt", "Banana.txt", "ice-cream.txt", "zeta.txt"),
                names(state), "by name ignoring case");
        state.sortBy(EntrySort.Key.SIZE, ROWS);
        // Folders have no size: they keep name order. Equal sizes go by name.
        assertEquals(List.of("..", "Alpha", "beta", "sub", "Banana.txt", "apple.txt", "ice-cream.txt", "zeta.txt"),
                names(state), "size, biggest first");
        state.sortBy(EntrySort.Key.SIZE, ROWS);
        assertEquals(List.of("..", "Alpha", "beta", "sub", "ice-cream.txt", "zeta.txt", "apple.txt", "Banana.txt"),
                names(state), "the same key again reverses");
        state.sortBy(EntrySort.Key.NAME, ROWS);
        state.sortBy(EntrySort.Key.NAME, ROWS);
        assertEquals(List.of("..", "sub", "beta", "Alpha", "zeta.txt", "ice-cream.txt", "Banana.txt", "apple.txt"),
                names(state), "name reversed");

        Path folder = dir(root, "mixed");
        file(folder, "b.md", "b");
        file(folder, "a.txt", "a");
        file(folder, "c", "c");
        file(folder, ".hidden", "h");
        file(folder, "archive.tar.GZ", "g");
        dir(folder, "z.d");
        dir(folder, "a.x");
        PanelState mixed = new PanelState(folder);
        mixed.refresh();
        mixed.sortBy(EntrySort.Key.EXTENSION, ROWS);
        assertEquals(List.of("..", "a.x", "z.d", ".hidden", "c", "archive.tar.GZ", "b.md", "a.txt"), names(mixed),
                "extension: names without one first");
    }

    @Test
    void sortingKeepsCursorAndMarksAndStaysForTheNextFolder() throws Exception {
        PanelState state = panel();
        file(root, "sub/small.txt", "s");
        file(root, "sub/large.txt", "large");
        state.selectName("apple.txt", ROWS);
        state.toggleMark(ROWS, false);
        state.selectName("zeta.txt", ROWS);
        state.sortBy(EntrySort.Key.SIZE, ROWS);
        assertEquals("zeta.txt", state.current().name());
        assertEquals(List.of("apple.txt"), names(state.targets()));
        state.selectName("sub", ROWS);
        assertNull(state.enter());
        assertEquals(List.of("..", "large.txt", "small.txt"), names(state));
    }
}
