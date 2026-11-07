package org.ds.tuifileman.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G in the F3 viewer: a plain number is a line, :N / 0xHEX / NN% an offset; driven on a virtual 40x12 terminal. */
class FileViewGoToTest {
    private static final int COLUMNS = 40;
    private static final int ROWS = 12;
    private static final int LINES = 100;
    private static final Config CONFIG = Config.parse("viewer.line_numbers = false\nviewer.wrap = true\nviewer.highlight = false");

    @TempDir
    Path dir;

    /** "line 001\n" ... "line 100\n": 9 bytes each. */
    private static String document(String eol) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= LINES; i++) {
            sb.append(String.format("line %03d", i)).append(eol);
        }
        return sb.toString();
    }

    private static List<KeyStroke> goTo(String text) {
        List<KeyStroke> keys = new ArrayList<>();
        keys.add(new KeyStroke('g', false, false));
        for (char c : text.toCharArray()) {
            keys.add(new KeyStroke(c, false, false));
        }
        keys.add(new KeyStroke(KeyType.Enter));
        return keys;
    }

    private static List<KeyStroke> keys(List<KeyStroke>... parts) {
        List<KeyStroke> all = new ArrayList<>();
        for (List<KeyStroke> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private TerminalScreen show(String content, List<KeyStroke> keys) throws Exception {
        Path file = Files.write(dir.resolve("doc.txt"), content.getBytes(StandardCharsets.UTF_8));
        TerminalScreen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS)));
        screen.startScreen();
        var terminal = (DefaultVirtualTerminal) screen.getTerminal();
        for (KeyStroke key : keys) {
            terminal.addInput(key);
        }
        terminal.addInput(new KeyStroke(KeyType.Escape));
        FileView.show(screen, Theme.mc(), file, CONFIG);
        return screen;
    }

    private static String row(TerminalScreen screen, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < COLUMNS; x++) {
            sb.append(screen.getFrontCharacter(new TerminalPosition(x, y)).getCharacter());
        }
        return sb.toString().stripTrailing();
    }

    @Test
    void everyInputFormReachesItsLineOrOffset() throws Exception {
        for (String input : new String[] {"50", "  50 ", "l50", "line 50", "L50", "Line  50"}) {
            assertEquals("line 050", row(show(document("\n"), goTo(input)), 1), input);
        }
        assertEquals("line 001", row(show(document("\n"), keys(goTo("50"), goTo("1"))), 1));
        assertEquals("line 003", row(show(document("\n"), goTo(":20")), 1), "byte 20 is inside line 3");
        assertEquals("line 003", row(show(document("\n"), goTo(": 20")), 1));
        assertEquals("line 003", row(show(document("\n"), goTo(":0x14")), 1));
        assertEquals("line 003", row(show(document("\n"), goTo("0x14")), 1));
        assertEquals("line 051", row(show(document("\n"), goTo("50%")), 1));
        assertEquals("line 020", row(show(document("\n"), goTo("20")), 1), "without the colon it is line 20");
    }

    @Test
    void jumpsBackAndForwardFromWhereTheCountWasLeft() throws Exception {
        String doc = document("\n");
        // Forward from the counted position, backward from it, and from the start when the target is near the top.
        assertEquals("line 060", row(show(doc, keys(goTo("30"), goTo("60"))), 1));
        assertEquals("line 010", row(show(doc, keys(goTo("60"), goTo("10"))), 1));
        assertEquals("line 003", row(show(doc, keys(goTo("90"), goTo("3"))), 1));
        assertEquals("line 059", row(show(doc, keys(goTo("60"), goTo("59"))), 1));
        assertEquals("line 061", row(show(doc, keys(goTo("60"), goTo("61"))), 1));
        assertEquals("line 060", row(show(doc, keys(goTo("60"), goTo("60"))), 1));
    }

    @Test
    void crlfLinesAndABomCountTheSame() throws Exception {
        assertEquals("line 003", row(show(document("\r\n"), goTo("3")), 1));
        assertEquals("line 003", row(show("\uFEFF" + document("\r\n"), goTo("3")), 1));
        assertEquals("line 001", row(show("\uFEFF" + document("\n"), goTo("1")), 1));
    }

    @Test
    void aLinePastTheEndShowsTheLastLinesAndSaysSo() throws Exception {
        TerminalScreen screen = show(document("\n"), goTo("999"));
        assertEquals("line 100", row(screen, ROWS - 3), "the last line is on the last body row");
        assertTrue(row(screen, ROWS - 2).contains("past the end"), row(screen, ROWS - 2));
        // The line just after the last one (the file ends with a line feed) is past the end as well.
        assertTrue(row(show(document("\n"), goTo("101")), ROWS - 2).contains("past the end"));
        assertTrue(!row(show(document("\n"), goTo("100")), ROWS - 2).contains("past the end"));
    }

    @Test
    void badInputIsReportedAndNothingMoves() throws Exception {
        assertTrue(row(show(document("\n"), goTo("0")), ROWS - 2).contains("Lines start at 1"));
        assertTrue(row(show(document("\n"), goTo("abc")), ROWS - 2).contains("Not a number"));
        assertTrue(row(show(document("\n"), goTo(":abc")), ROWS - 2).contains("Not a number"));
        assertTrue(row(show(document("\n"), goTo("line")), ROWS - 2).contains("Not a line number"));
        TerminalScreen screen = show(document("\n"), goTo("99999999999999999999"));
        assertTrue(row(screen, ROWS - 2).contains("Not a line number"), row(screen, ROWS - 2));
        assertEquals("line 001", row(screen, 1));
    }

    @Test
    void inTheHexViewAPlainNumberIsAnOffsetAndALetterFormIsRefused() throws Exception {
        List<KeyStroke> hex = List.of(new KeyStroke(KeyType.Tab));
        TerminalScreen offset = show(document("\n"), keys(hex, goTo("32")));
        assertTrue(row(offset, 1).startsWith("00000020"), row(offset, 1));
        TerminalScreen refused = show(document("\n"), keys(hex, goTo("l5")));
        assertTrue(row(refused, ROWS - 2).contains("apply to the text view"), row(refused, ROWS - 2));
    }
}
