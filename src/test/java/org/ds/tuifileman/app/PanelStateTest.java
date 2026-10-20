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

    @Test
    void listingHasParentFirstThenFoldersThenFilesIgnoringCase() throws Exception {
        assertEquals(List.of("..", "Alpha", "beta", "sub", "apple.txt", "Banana.txt", "ice-cream.txt", "zeta.txt"),
                names(panel()));
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
    void marksSkipTheParentEntryAndTrackSizes() throws Exception {
        PanelState state = panel();
        state.toggleMark(ROWS, true);
        assertEquals(0, state.markedCount(), "the .. entry cannot be marked");
        assertEquals(0, state.cursor(), "nothing happens on ..");
        state.selectName("apple.txt", ROWS);
        state.toggleMark(ROWS, true);
        state.toggleMark(ROWS, false);
        state.selectName("zeta.txt", ROWS);
        state.toggleMark(ROWS, false);
        assertEquals(List.of("apple.txt", "Banana.txt", "zeta.txt"),
                state.markedOrAll().stream().map(FileEntry::name).toList());
        assertEquals(6, state.markedSize(), "aa + bbb + z");
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
        assertEquals(List.of("apple.txt"), state.markedOrAll().stream().map(FileEntry::name).toList());
        state.clearMarks();
        state.invertMarks(false);
        assertTrue(state.markedOrAll().stream().noneMatch(FileEntry::directory), "files only");
        assertEquals(state.markedCount(), state.markedOrAll().size());
    }

    @Test
    void targetsAreTheMarkedEntriesOrTheOneUnderTheCursor() throws Exception {
        PanelState state = panel();
        assertTrue(state.targets().isEmpty(), "the cursor starts on ..");
        state.selectName("beta", ROWS);
        assertEquals(List.of("beta"), state.targets().stream().map(FileEntry::name).toList());
        state.selectName("apple.txt", ROWS);
        state.toggleMark(ROWS, false);
        state.selectName("sub", ROWS);
        assertEquals(List.of("apple.txt"), state.targets().stream().map(FileEntry::name).toList(),
                "marks win over the cursor");
    }

    @Test
    void markedOrAllFallsBackToEverythingExceptTheParent() throws Exception {
        PanelState state = panel();
        assertEquals(7, state.markedOrAll().size());
        assertFalse(state.markedOrAll().stream().anyMatch(entry -> entry.name().equals("..")));
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
    void quickSearchPrefersAPrefixOverAContainsMatch() throws Exception {
        PanelState state = panel();
        state.searchAppend('a', ROWS);
        assertEquals("Alpha", state.current().name());
        state.searchAppend('p', ROWS);
        assertEquals("apple.txt", state.current().name(), "'ap' is a prefix of apple.txt and only inside Alpha");
        assertTrue(state.searching());
        assertTrue(state.searchMatched());
    }

    @Test
    void quickSearchFallsBackToContainsAndReportsNoMatch() throws Exception {
        PanelState state = panel();
        state.searchAppend('c', ROWS);
        state.searchAppend('r', ROWS);
        assertEquals("ice-cream.txt", state.current().name());
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
    void quickSearchStepsThroughTheMatches() throws Exception {
        PanelState state = panel();
        state.searchAppend('a', ROWS);
        assertEquals("Alpha", state.current().name());
        state.searchStep(1, ROWS);
        assertEquals("apple.txt", state.current().name());
        state.searchStep(1, ROWS);
        assertEquals("apple.txt", state.current().name(), "no further prefix match: Banana only contains the letter");
        state.searchStep(-1, ROWS);
        assertEquals("Alpha", state.current().name());
        state.searchClear();
        assertFalse(state.searching());
    }

    @Test
    void enterAndGoUpKeepTheCursorOnWhereYouCameFrom() throws Exception {
        PanelState state = panel();
        file(root, "sub/inner.txt", "i");
        state.selectName("sub", ROWS);
        assertNull(state.enter());
        assertEquals(root.resolve("sub"), state.directory());
        assertEquals(List.of("..", "inner.txt"), names(state));
        assertEquals(0, state.cursor());
        assertNull(state.goUp());
        assertEquals(root, state.directory());
        assertEquals("sub", state.current().name());
    }

    @Test
    void sortBySizeStartsBiggestFirstAndTheSameKeyReverses() throws Exception {
        PanelState state = panel();
        state.sortBy(EntrySort.Key.SIZE, ROWS);
        // Folders have no size: they keep name order. Equal sizes go by name.
        assertEquals(List.of("..", "Alpha", "beta", "sub", "Banana.txt", "apple.txt", "ice-cream.txt", "zeta.txt"),
                names(state));
        state.sortBy(EntrySort.Key.SIZE, ROWS);
        assertEquals(List.of("..", "Alpha", "beta", "sub", "ice-cream.txt", "zeta.txt", "apple.txt", "Banana.txt"),
                names(state));
        state.sortBy(EntrySort.Key.NAME, ROWS);
        assertEquals(List.of("..", "Alpha", "beta", "sub", "apple.txt", "Banana.txt", "ice-cream.txt", "zeta.txt"),
                names(state));
        state.sortBy(EntrySort.Key.NAME, ROWS);
        assertEquals(List.of("..", "sub", "beta", "Alpha", "zeta.txt", "ice-cream.txt", "Banana.txt", "apple.txt"),
                names(state));
    }

    @Test
    void sortByExtensionPutsNamesWithoutOneFirst() throws Exception {
        Path folder = dir(root, "mixed");
        file(folder, "b.md", "b");
        file(folder, "a.txt", "a");
        file(folder, "c", "c");
        file(folder, ".hidden", "h");
        file(folder, "archive.tar.GZ", "g");
        dir(folder, "z.d");
        dir(folder, "a.x");
        PanelState state = new PanelState(folder);
        state.refresh();
        state.sortBy(EntrySort.Key.EXTENSION, ROWS);
        assertEquals(List.of("..", "a.x", "z.d", ".hidden", "c", "archive.tar.GZ", "b.md", "a.txt"), names(state));
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
        assertEquals(List.of("apple.txt"), state.targets().stream().map(FileEntry::name).toList());
        state.selectName("sub", ROWS);
        assertNull(state.enter());
        assertEquals(List.of("..", "large.txt", "small.txt"), names(state));
    }

    @Test
    void enteringTheParentEntryGoesUpToo() throws Exception {
        PanelState state = panel();
        state.selectName("Alpha", ROWS);
        state.enter();
        assertEquals("..", state.current().name());
        assertNull(state.enter());
        assertEquals(root, state.directory());
        assertEquals("Alpha", state.current().name());
    }

    @Test
    void enteringAFileIsRefused() throws Exception {
        PanelState state = panel();
        state.selectName("zeta.txt", ROWS);
        assertEquals("zeta.txt is not a directory", state.enter());
        assertEquals(root, state.directory());
    }

    @Test
    void goToAMissingFolderKeepsTheCurrentOne() throws Exception {
        PanelState state = panel();
        String error = state.goTo(root.resolve("nope"));
        assertNotNull(error);
        assertTrue(error.startsWith("Cannot open "), error);
        assertEquals(root, state.directory());
        assertFalse(state.entries().isEmpty());
    }

    @Test
    void refreshReportsAVanishedFolder() throws Exception {
        PanelState state = panel();
        file(root, "sub/x.txt", "x");
        state.selectName("sub", ROWS);
        state.enter();
        try (var walk = Files.walk(root.resolve("sub"))) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        assertEquals("Directory no longer exists", state.refresh());
        assertTrue(state.entries().isEmpty());
        assertNull(state.current());
    }

    @Test
    void anEmptyFolderStillHasTheParentEntry() throws Exception {
        dir(root, "empty");
        PanelState state = new PanelState(root.resolve("empty"));
        state.refresh();
        assertEquals(List.of(".."), names(state));
        assertTrue(state.targets().isEmpty());
        assertTrue(state.markedOrAll().isEmpty());
    }
}
