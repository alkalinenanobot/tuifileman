package org.ds.tuifileman.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real viewer on a virtual 40x12 terminal (9 text rows) and reads the last frame it drew. */
class FileViewWrapTest {
    private static final int COLUMNS = 40;
    private static final int ROWS = 12;
    private static final int BODY = ROWS - 3;

    @TempDir
    Path dir;
    Path file;
    /** What the screen must show for the document with wrapping and no gutter, one entry per screen row. */
    List<String> expected = new ArrayList<>();

    @BeforeEach
    void document() throws Exception {
        // Four lines of 20 four-character words: 8 words (40 columns) fill a row, so every line is 3 rows.
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= 4; line++) {
            List<String> words = new ArrayList<>();
            for (int word = 1; word <= 20; word++) {
                words.add(String.format("%dx%02d", line, word));
            }
            text.append(String.join(" ", words)).append('\n');
            expected.add(String.join(" ", words.subList(0, 8)));
            expected.add(String.join(" ", words.subList(8, 16)));
            expected.add(String.join(" ", words.subList(16, 20)));
        }
        text.append('\n').append("end\n");
        expected.add("");
        expected.add("end");
        file = Files.writeString(dir.resolve("doc.txt"), text.toString());
    }

    private TerminalScreen open() throws Exception {
        TerminalScreen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS)));
        screen.startScreen();
        return screen;
    }

    /** Sends the keys, closes the viewer with Escape and returns the screen rows of the last frame drawn. */
    private List<String> show(String config, KeyStroke... keys) throws Exception {
        TerminalScreen screen = open();
        var terminal = (DefaultVirtualTerminal) screen.getTerminal();
        for (KeyStroke key : keys) {
            terminal.addInput(key);
        }
        terminal.addInput(new KeyStroke(KeyType.Escape));
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

    private static final String NO_NUMBERS = "viewer.line_numbers = false\nviewer.wrap = true";

    private void assertTopRow(List<String> screen, int top) {
        assertEquals(expected.subList(top, top + BODY), screen.subList(1, 1 + BODY), "first shown row " + top);
    }

    @Test
    void aLongLineIsShownCompletelyOverSeveralRows() throws Exception {
        assertTopRow(show(NO_NUMBERS), 0);
    }

    @Test
    void scrollingMovesOneScreenRowAtATimeAndStopsAtTheLastFullPage() throws Exception {
        int last = expected.size() - BODY;
        for (int down = 0; down <= last + 3; down++) {
            assertTopRow(show(NO_NUMBERS, repeat(KeyType.ArrowDown, down)), Math.min(down, last));
        }
        assertTopRow(show(NO_NUMBERS, key(KeyType.End)), last);
        assertTopRow(show(NO_NUMBERS, key(KeyType.End), key(KeyType.Home)), 0);
        assertTopRow(show(NO_NUMBERS, key(KeyType.End), key(KeyType.ArrowUp), key(KeyType.ArrowUp)), last - 2);
        assertTopRow(show(NO_NUMBERS, key(KeyType.PageDown)), Math.min(BODY - 1, last));
        assertTopRow(show(NO_NUMBERS, key(KeyType.End), key(KeyType.PageUp)), Math.max(0, last - (BODY - 1)));
    }

    @Test
    void numbersAppearOnlyOnTheFirstRowOfALine() throws Exception {
        List<String> screen = show("viewer.line_numbers = true\nviewer.wrap = true");
        assertEquals("   1│", screen.get(1).substring(0, 5));
        assertEquals("    │", screen.get(2).substring(0, 5));
        assertEquals("    │", screen.get(3).substring(0, 5));
        assertEquals("   2│", screen.get(4).substring(0, 5));
        // Narrower text area: 35 columns, so 7 words per row; the words of line 1 still come in order.
        String joined = String.join(" ", screen.subList(1, 4).stream().map(row -> row.substring(5).strip()).toList());
        assertTrue(joined.startsWith("1x01 1x02 1x03 1x04 1x05 1x06 1x07 1x08") && joined.endsWith("1x19 1x20"), joined);
    }

    @Test
    void withWrapOffALongLineIsPannedInsteadAndWTogglesIt() throws Exception {
        List<String> off = show("viewer.line_numbers = false\nviewer.wrap = false");
        assertEquals("1x01 1x02 1x03 1x04 1x05 1x06 1x07 1x08", off.get(1));
        assertEquals("2x01 2x02 2x03 2x04 2x05 2x06 2x07 2x08", off.get(2));
        List<String> toggled = show("viewer.line_numbers = false\nviewer.wrap = false",
                new KeyStroke('w', false, false));
        assertTopRow(toggled, 0);
        List<String> back = show(NO_NUMBERS, new KeyStroke('w', false, false));
        assertEquals(off.subList(1, BODY + 1), back.subList(1, BODY + 1));
    }

    @Test
    void aMatchOnAWrappedRowIsHighlightedAndScrolledIntoView() throws Exception {
        TerminalScreen screen = open();
        var terminal = (DefaultVirtualTerminal) screen.getTerminal();
        terminal.addInput(new KeyStroke('f', false, false));
        for (char c : "4x15".toCharArray()) {
            terminal.addInput(new KeyStroke(c, false, false));
        }
        terminal.addInput(key(KeyType.Enter));
        terminal.addInput(key(KeyType.Escape));
        Theme theme = Theme.mc();
        FileView.show(screen, theme, file, Config.parse(NO_NUMBERS));
        int hitRow = -1;
        int hitColumn = -1;
        for (int y = 1; y <= BODY; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < COLUMNS; x++) {
                row.append(screen.getFrontCharacter(new TerminalPosition(x, y)).getCharacter());
            }
            int at = row.indexOf("4x15");
            if (at >= 0) {
                hitRow = y;
                hitColumn = at;
            }
        }
        assertTrue(hitRow > 0, "the match is on screen");
        for (int i = 0; i < 4; i++) {
            TextCharacter cell = screen.getFrontCharacter(new TerminalPosition(hitColumn + i, hitRow));
            assertEquals(theme.match(), cell.getBackgroundColor(), "match cell " + i);
        }
        assertTrue(!theme.match().equals(screen.getFrontCharacter(new TerminalPosition(hitColumn - 1, hitRow)).getBackgroundColor()));
    }
}
