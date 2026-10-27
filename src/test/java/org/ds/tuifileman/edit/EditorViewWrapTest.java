package org.ds.tuifileman.edit;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import org.ds.tuifileman.ui.Wrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real editor on a virtual 40x12 terminal (9 text rows) and reads the frame drawn before the last key. */
class EditorViewWrapTest {
    private static final int COLUMNS = 40;
    private static final int ROWS = 12;
    private static final int BODY = ROWS - 3;
    /** Columns of text per row without a gutter: one less than the width, the cursor needs the last one. */
    private static final int WIDTH = COLUMNS - 1;
    private static final String NO_NUMBERS = "editor.line_numbers = false";

    @TempDir
    Path dir;

    /** Hands out the keys one by one with an idle poll before each, so the editor draws between the keys. */
    private static final class Scripted extends TerminalScreen {
        private final Deque<KeyStroke> keys = new ArrayDeque<>();
        private boolean offered;

        Scripted(DefaultVirtualTerminal terminal) throws IOException {
            super(terminal);
        }

        @Override
        public KeyStroke pollInput() {
            if (!offered) {
                offered = true;
                return null;
            }
            offered = false;
            return keys.isEmpty() ? new KeyStroke(KeyType.EOF) : keys.poll();
        }
    }

    private record Frame(List<String> rows, TerminalPosition cursor, TerminalScreen screen) { }

    private Frame run(String text, String config, KeyStroke... keys) throws Exception {
        Path file = Files.writeString(dir.resolve("doc.txt"), text);
        Scripted screen = new Scripted(new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS)));
        screen.startScreen();
        for (KeyStroke key : keys) {
            screen.keys.add(key);
        }
        screen.keys.add(new KeyStroke(KeyType.Escape));
        EditorView.edit(screen, Theme.mc(), file, false, Config.parse(config));
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < ROWS; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < COLUMNS; x++) {
                row.append(screen.getFrontCharacter(new TerminalPosition(x, y)).getCharacter());
            }
            rows.add(row.toString().stripTrailing());
        }
        return new Frame(rows, screen.getCursorPosition(), screen);
    }

    private static String words(String prefix, int count) {
        List<String> words = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            words.add(String.format("%s%02d", prefix, i));
        }
        return String.join(" ", words);
    }

    /** What the screen must show for {@code text}: its rows without the trailing space of a row broken at a space. */
    private static List<String> rowsOf(String text, int width) {
        int[] starts = Wrap.rows(text, width);
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < starts.length; row++) {
            rows.add(text.substring(starts[row], Wrap.end(starts, row, text.length())).stripTrailing());
        }
        return rows;
    }

    private static KeyStroke key(KeyType type) {
        return new KeyStroke(type);
    }

    private static KeyStroke[] repeat(KeyType type, int times) {
        KeyStroke[] keys = new KeyStroke[times];
        for (int i = 0; i < times; i++) {
            keys[i] = key(type);
        }
        return keys;
    }

    private static KeyStroke[] concat(KeyStroke[] first, KeyStroke... more) {
        KeyStroke[] all = new KeyStroke[first.length + more.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(more, 0, all, first.length, more.length);
        return all;
    }

    private static final String LONG = words("w", 30);
    private static final String DOC = LONG + "\nshort\n" + words("v", 30) + "\n";

    @Test
    void aLongLineIsShownCompletelyOverSeveralRows() throws Exception {
        List<String> expected = rowsOf(LONG, WIDTH);
        assertTrue(expected.size() >= 3);
        Frame frame = run(DOC, NO_NUMBERS);
        assertEquals(expected, frame.rows().subList(1, 1 + expected.size()));
        assertEquals("short", frame.rows().get(1 + expected.size()));
    }

    @Test
    void theLineNumberIsOnTheFirstRowOfALineOnly() throws Exception {
        Frame frame = run(DOC, "editor.line_numbers = true");
        int rows = rowsOf(LONG, WIDTH - 5).size();
        assertEquals("   1│", frame.rows().get(1).substring(0, 5));
        for (int row = 1; row < rows; row++) {
            assertEquals("    │", frame.rows().get(1 + row).substring(0, 5));
        }
        assertEquals("   2│short", frame.rows().get(1 + rows));
        List<String> text = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            text.add(frame.rows().get(1 + row).substring(5));
        }
        assertEquals(LONG, String.join(" ", text));
    }

    @Test
    void upAndDownMoveByScreenRowAndKeepTheColumn() throws Exception {
        int rows = rowsOf(LONG, WIDTH).size();
        for (int down = 0; down < rows; down++) {
            Frame frame = run(DOC, NO_NUMBERS, concat(repeat(KeyType.ArrowRight, 3), repeat(KeyType.ArrowDown, down)));
            assertEquals(new TerminalPosition(3, 1 + down), frame.cursor(), "after " + down + " Down");
        }
        Frame frame = run(DOC, NO_NUMBERS, concat(repeat(KeyType.ArrowRight, 3), repeat(KeyType.ArrowDown, rows + 1)));
        assertEquals(new TerminalPosition(3, 1 + rows + 1), frame.cursor(), "the next lines follow the wrapped one");
        Frame back = run(DOC, NO_NUMBERS, concat(concat(repeat(KeyType.ArrowRight, 3),
                repeat(KeyType.ArrowDown, rows - 1)), repeat(KeyType.ArrowUp, rows - 1)));
        assertEquals(new TerminalPosition(3, 1), back.cursor());
    }

    @Test
    void homeAndEndGoToTheStartAndEndOfTheScreenRow() throws Exception {
        List<String> rows = rowsOf(LONG, WIDTH);
        Frame end = run(DOC, NO_NUMBERS, key(KeyType.ArrowDown), key(KeyType.End));
        assertEquals(new TerminalPosition(rows.get(1).length(), 2), end.cursor(),
                "End stops at the end of the row (before the space it was broken at)");
        Frame home = run(DOC, NO_NUMBERS, key(KeyType.ArrowDown), key(KeyType.ArrowRight), key(KeyType.ArrowRight),
                key(KeyType.Home));
        assertEquals(new TerminalPosition(0, 2), home.cursor(), "Home goes to the start of the screen row");
        Frame firstRow = run(DOC, NO_NUMBERS, key(KeyType.End));
        assertEquals(new TerminalPosition(rows.get(0).length(), 1), firstRow.cursor());
        Frame lastRow = run(DOC, NO_NUMBERS, concat(repeat(KeyType.ArrowDown, rows.size() - 1), key(KeyType.End)));
        assertEquals(new TerminalPosition(rows.get(rows.size() - 1).length(), rows.size()), lastRow.cursor(),
                "End of the last row is the end of the line");
    }

    @Test
    void pageDownAndTheEndOfTheDocumentScrollByScreenRows() throws Exception {
        String single = words("x", 300);
        List<String> expected = rowsOf(single, WIDTH);
        assertTrue(expected.size() > 3 * BODY);
        int page = BODY - 1;
        Frame down = run(single, NO_NUMBERS, key(KeyType.PageDown));
        assertEquals(expected.subList(page, page + BODY), down.rows().subList(1, 1 + BODY));
        assertEquals(new TerminalPosition(0, 1), down.cursor(), "the cursor keeps its row on the screen");
        Frame twice = run(single, NO_NUMBERS, key(KeyType.PageDown), key(KeyType.PageDown), key(KeyType.PageUp));
        assertEquals(expected.subList(page, page + BODY), twice.rows().subList(1, 1 + BODY));
        Frame end = run(single, NO_NUMBERS, new KeyStroke(KeyType.End, true, false));
        assertEquals(expected.subList(expected.size() - BODY, expected.size()), end.rows().subList(1, 1 + BODY));
        assertEquals(new TerminalPosition(expected.get(expected.size() - 1).length(), BODY), end.cursor());
    }

    @Test
    void theMouseWheelAndClicksWorkOnScreenRows() throws Exception {
        String single = words("x", 300);
        List<String> expected = rowsOf(single, WIDTH);
        MouseAction wheel = new MouseAction(MouseActionType.SCROLL_DOWN, 5, new TerminalPosition(3, 3));
        Frame scrolled = run(single, NO_NUMBERS, wheel);
        assertEquals(expected.subList(3, 3 + BODY), scrolled.rows().subList(1, 1 + BODY));
        int[] starts = Wrap.rows(single, WIDTH);
        MouseAction click = new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(5, 3));
        Frame clicked = run(single, NO_NUMBERS, click);
        assertEquals(new TerminalPosition(5, 3), clicked.cursor());
        assertTrue(clicked.rows().get(ROWS - 2).contains("Col " + (starts[2] + 5 + 1) + " "), clicked.rows().get(ROWS - 2));
    }

    @Test
    void altWSwitchesWrapOffAndOn() throws Exception {
        KeyStroke altW = new KeyStroke('w', false, true);
        Frame off = run(DOC, NO_NUMBERS, altW);
        assertEquals(LONG.substring(0, COLUMNS).stripTrailing(), off.rows().get(1));
        assertEquals("short", off.rows().get(2));
        Frame on = run(DOC, NO_NUMBERS, altW, altW);
        assertEquals(rowsOf(LONG, WIDTH).get(0), on.rows().get(1));
        assertNotEquals("short", on.rows().get(2));
        Frame configured = run(DOC, NO_NUMBERS + "\neditor.wrap = false");
        assertEquals(LONG.substring(0, COLUMNS).stripTrailing(), configured.rows().get(1));
    }

    @Test
    void aSelectionIsHighlightedOnEveryRowItCovers() throws Exception {
        Theme theme = Theme.mc();
        Frame frame = run(DOC, NO_NUMBERS, new KeyStroke(KeyType.ArrowDown, false, false, true),
                new KeyStroke(KeyType.ArrowDown, false, false, true));
        assertEquals(new TerminalPosition(0, 3), frame.cursor());
        for (int y = 1; y <= 2; y++) {
            for (int x : new int[] {0, rowsOf(LONG, WIDTH).get(y - 1).length() - 1}) {
                assertEquals(theme.selection(),
                        frame.screen().getFrontCharacter(new TerminalPosition(x, y)).getBackgroundColor(),
                        "cell " + x + "," + y);
            }
        }
        assertNotEquals(theme.selection(),
                frame.screen().getFrontCharacter(new TerminalPosition(0, 3)).getBackgroundColor());
    }
}
