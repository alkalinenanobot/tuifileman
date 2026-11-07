package org.ds.tuifileman.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mouse selection and the indent of wrapped list items in the viewer, on a virtual 40x12 terminal. */
class FileViewSelectionTest {
    private static final int COLUMNS = 40;
    private static final int ROWS = 12;
    /** The gutter of the default four-digit line numbers: four digits and the bar. */
    private static final int TEXT_X = 5;
    private static final String NUMBERS = "viewer.line_numbers = true\nviewer.wrap = true";
    private static final String NO_NUMBERS = "viewer.line_numbers = false\nviewer.wrap = true";

    @TempDir
    Path dir;
    private final List<String> copied = new ArrayList<>();
    private Predicate<String> savedClipboard;
    private TerminalScreen screen;

    @BeforeEach
    void captureClipboard() {
        savedClipboard = FileView.clipboard;
        FileView.clipboard = text -> copied.add(text);
    }

    @AfterEach
    void restoreClipboard() {
        FileView.clipboard = savedClipboard;
    }

    private static MouseAction mouse(MouseActionType type, int button, int x, int y) {
        return new MouseAction(type, button, new TerminalPosition(x, y));
    }

    private static List<KeyStroke> drag(int x0, int y0, int x1, int y1) {
        return List.of(mouse(MouseActionType.CLICK_DOWN, 1, x0, y0), mouse(MouseActionType.DRAG, 1, x1, y1),
                mouse(MouseActionType.CLICK_RELEASE, 0, x1, y1));
    }

    /** Shows the file, plays the input and closes with q; the screen keeps the last frame drawn before the q. */
    private List<String> show(String text, String config, List<KeyStroke> input) throws Exception {
        return show("doc.txt", text, config, input);
    }

    private List<String> show(String name, String text, String config, List<KeyStroke> input) throws Exception {
        Path file = Files.writeString(dir.resolve(name), text);
        screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS)));
        screen.startScreen();
        var terminal = (DefaultVirtualTerminal) screen.getTerminal();
        for (KeyStroke key : input) {
            terminal.addInput(key);
        }
        terminal.addInput(new KeyStroke('q', false, false));
        FileView.show(screen, Theme.mc(), file, Config.parse(config));
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < ROWS; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < COLUMNS; x++) {
                row.append(screen.getFrontCharacter(new TerminalPosition(x, y)).getCharacter());
            }
            rows.add(row.toString().stripTrailing());
        }
        return rows;
    }

    private boolean selected(int x, int y) {
        return Theme.mc().selection().equals(screen.getFrontCharacter(new TerminalPosition(x, y)).getBackgroundColor());
    }

    @Test
    void aMultiLineSelectionCopiesTheTextWithoutTheLineNumbersAndNeverHighlightsTheGutter() throws Exception {
        List<String> rows = show("alpha beta\ngamma delta\nepsilon\n", NUMBERS, drag(TEXT_X + 2, 1, TEXT_X + 3, 3));
        assertEquals(List.of("pha beta\ngamma delta\neps"), copied);
        assertTrue(rows.get(1).startsWith("   1│alpha beta"), rows.get(1));
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < TEXT_X; x++) {
                assertTrue(!selected(x, y), "gutter cell " + x + "," + y);
            }
        }
        assertTrue(!selected(TEXT_X + 1, 1) && selected(TEXT_X + 2, 1), "starts at the pressed character");
        assertTrue(selected(TEXT_X + 2, 3) && !selected(TEXT_X + 3, 3), "ends before the released column");
    }

    @Test
    void aSelectionInTheIndentOfAWrappedRowStartsWithTheText() throws Exception {
        String words = String.join(" ", java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> String.format("w%02d", i)).toList());
        List<String> rows = show("- " + words + "\n", NO_NUMBERS, drag(0, 2, COLUMNS - 1, 2));
        String row = rows.get(2);
        assertTrue(row.startsWith("  w"), "continuation rows are indented under the text: '" + row + "'");
        assertEquals(1, copied.size());
        assertEquals(row.strip(), copied.get(0).strip());
        assertTrue(!copied.get(0).startsWith(" "), "the indent is not part of the text");
    }

    @Test
    void aTabIsCopiedAsATabEvenWhenTheSelectionStartsInsideIt() throws Exception {
        // "a\tb" shows as "a   b"; columns 2..5 cover the second half of the tab and the b.
        show("a\tb\n", NUMBERS, drag(TEXT_X + 2, 1, TEXT_X + 5, 1));
        assertEquals(List.of("\tb"), copied);
    }

    @Test
    void aClickWithoutDraggingCopiesNothingAndEscapeClearsASelectionBeforeClosing() throws Exception {
        show("alpha\n", NUMBERS, List.of(mouse(MouseActionType.CLICK_DOWN, 1, TEXT_X + 1, 1),
                mouse(MouseActionType.CLICK_RELEASE, 0, TEXT_X + 1, 1)));
        assertEquals(List.of(), copied);

        List<KeyStroke> input = new ArrayList<>(drag(TEXT_X, 1, TEXT_X + 3, 1));
        input.add(new KeyStroke(KeyType.Escape));
        List<String> rows = show("alpha\n", NUMBERS, input);
        assertTrue(rows.get(1).contains("alpha"), "still open after one Escape");
        assertTrue(!selected(TEXT_X, 1), "the selection is gone");
        assertNotEquals(List.of(), copied);
    }

    @Test
    void aWrappedListItemKeepsItsIndentInTheRawAndTheRenderedView() throws Exception {
        String words = String.join(" ", java.util.stream.IntStream.rangeClosed(1, 30).mapToObj(i -> String.format("w%02d", i)).toList());
        List<String> raw = show("1. " + words + "\n", NO_NUMBERS, List.of());
        assertTrue(raw.get(1).startsWith("1. w01"), raw.get(1));
        assertTrue(raw.get(2).startsWith("   w") && !raw.get(2).startsWith("    "), "'" + raw.get(2) + "'");
        assertTrue(raw.get(3).startsWith("   w"), "'" + raw.get(3) + "'");

        List<String> rows = show("doc.md", "* " + words + "\n  * nested " + words + "\n", NO_NUMBERS, List.of());
        String joined = String.join("\n", rows);
        assertTrue(rows.get(1).startsWith("\u2022 w01"), joined);
        assertTrue(rows.get(2).startsWith("  w") && !rows.get(2).startsWith("   "), joined);
        int nested = rows.indexOf(rows.stream().filter(r -> r.contains("nested")).findFirst().orElseThrow());
        assertTrue(rows.get(nested).startsWith("  \u2022 nested"), joined);
        assertTrue(rows.get(nested + 1).startsWith("    w") && !rows.get(nested + 1).startsWith("     "), joined);
    }
}
