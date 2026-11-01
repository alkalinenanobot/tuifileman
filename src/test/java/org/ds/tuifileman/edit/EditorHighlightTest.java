package org.ds.tuifileman.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Colours in the F4 editor on a virtual terminal: by language, while typing, with a selection and with tabs. */
class EditorHighlightTest {
    private static final int COLUMNS = 40;
    private static final int ROWS = 12;
    private static final Theme THEME = Theme.mc();
    private static final String CONFIG = "editor.line_numbers = false\neditor.wrap = true\n";

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

    private TerminalScreen run(String name, String text, String config, KeyStroke... keys) throws Exception {
        Path file = Files.writeString(dir.resolve(name), text);
        Scripted screen = new Scripted(new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS)));
        screen.startScreen();
        for (KeyStroke key : keys) {
            screen.keys.add(key);
        }
        screen.keys.add(new KeyStroke(KeyType.Escape));
        EditorView.edit(screen, THEME, file, false, Config.parse(CONFIG + config));
        return screen;
    }

    private static TextCharacter at(TerminalScreen screen, int x, int y) {
        return screen.getFrontCharacter(new TerminalPosition(x, y));
    }

    private static String row(TerminalScreen screen, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < COLUMNS; x++) {
            sb.append(at(screen, x, y).getCharacter());
        }
        return sb.toString().stripTrailing();
    }

    private static KeyStroke chr(char c) {
        return new KeyStroke(c, false, false);
    }

    @Test
    void javaIsColouredAndTheHeaderNamesTheLanguage() throws Exception {
        TerminalScreen screen = run("A.java", "int x = 1; // note\nString s = \"q\";\n", "");
        assertTrue(row(screen, 0).contains("Java"), row(screen, 0));
        assertEquals(THEME.type(), at(screen, 0, 1).getForegroundColor(), "int");
        assertEquals(THEME.number(), at(screen, 8, 1).getForegroundColor(), "1");
        assertEquals(THEME.comment(), at(screen, 11, 1).getForegroundColor(), "comment");
        assertEquals(THEME.string(), at(screen, 11, 2).getForegroundColor(), "string");
    }

    @Test
    void unknownExtensionsAndTheSettingKeepTheTextPlain() throws Exception {
        TerminalScreen txt = run("a.txt", "int x = 1;\n", "");
        assertEquals(THEME.text(), at(txt, 0, 1).getForegroundColor());
        assertTrue(!row(txt, 0).contains("Java"));
        TerminalScreen off = run("A.java", "int x = 1;\n", "editor.highlight = false");
        assertEquals(THEME.text(), at(off, 0, 1).getForegroundColor());
        assertTrue(!row(off, 0).contains("Java"), row(off, 0));
    }

    @Test
    void typingACommentOpenerRecoloursTheLinesBelowAndUndoRestoresThem() throws Exception {
        String doc = "int a;\nint b;\nint c;\n";
        TerminalScreen typed = run("A.java", doc, "", chr('/'), chr('*'));
        assertEquals("/*int a;", row(typed, 1));
        for (int y = 1; y <= 3; y++) {
            assertEquals(THEME.comment(), at(typed, 0, y).getForegroundColor(), "row " + y + " is inside the comment");
        }
        TerminalScreen undone = run("A.java", doc, "", chr('/'), chr('*'),
                new KeyStroke('z', true, false), new KeyStroke('z', true, false));
        assertEquals("int a;", row(undone, 1));
        for (int y = 1; y <= 3; y++) {
            assertEquals(THEME.type(), at(undone, 0, y).getForegroundColor(), "row " + y + " after undo");
        }
    }

    @Test
    void closingTheCommentEndsItAtOnce() throws Exception {
        // "/*" at the start, then End and "*/": the lines below the closing are code again.
        TerminalScreen screen = run("A.java", "int a;\nint b;\n", "", chr('/'), chr('*'), new KeyStroke(KeyType.End),
                chr('*'), chr('/'));
        assertEquals("/*int a;*/", row(screen, 1));
        assertEquals(THEME.comment(), at(screen, 9, 1).getForegroundColor(), "the closing slash");
        assertEquals(THEME.type(), at(screen, 0, 2).getForegroundColor(), "next line is code again");
    }

    @Test
    void aSelectionKeepsTheSyntaxColoursOnTheSelectionBackground() throws Exception {
        TerminalScreen screen = run("A.java", "int x = 1;\n", "", new KeyStroke(KeyType.End, false, false, true));
        TextCharacter keyword = at(screen, 0, 1);
        assertEquals(THEME.type(), keyword.getForegroundColor());
        assertEquals(THEME.selection(), keyword.getBackgroundColor());
        assertEquals(THEME.number(), at(screen, 8, 1).getForegroundColor());
        assertEquals(THEME.selection(), at(screen, 8, 1).getBackgroundColor());
    }

    @Test
    void aTabIsDrawnAsSpacesAndTheColoursStayAligned() throws Exception {
        TerminalScreen screen = run("A.java", "\tint x;\n", "");
        assertEquals("    int x;", row(screen, 1));
        assertEquals(THEME.text(), at(screen, 0, 1).getForegroundColor());
        assertEquals(THEME.type(), at(screen, 4, 1).getForegroundColor());
    }

    @Test
    void theSyntaxColoursSurviveWrapping() throws Exception {
        String line = "int aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = 1; // c";
        TerminalScreen screen = run("A.java", line + "\n", "");
        // The line takes two rows of 39 columns; the comment starts on the second one.
        assertEquals(THEME.type(), at(screen, 0, 1).getForegroundColor());
        String second = row(screen, 2);
        int comment = second.indexOf("//");
        assertTrue(comment >= 0, second);
        assertEquals(THEME.comment(), at(screen, comment, 2).getForegroundColor());
    }
}
