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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Source files in the F3 viewer on a virtual terminal: colours by language, the limits and the raw view of HTML. */
class FileViewHighlightTest {
    private static final int COLUMNS = 60;
    private static final int ROWS = 14;
    private static final Theme THEME = Theme.mc();
    private static final String BASE = "viewer.line_numbers = false\nviewer.wrap = true\n";

    @TempDir
    Path dir;

    private TerminalScreen show(String name, byte[] content, String config, KeyStroke... keys) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, content);
        TerminalScreen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS)));
        screen.startScreen();
        var terminal = (DefaultVirtualTerminal) screen.getTerminal();
        for (KeyStroke key : keys) {
            terminal.addInput(key);
        }
        terminal.addInput(new KeyStroke(KeyType.Escape));
        FileView.show(screen, THEME, file, Config.parse(BASE + config));
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

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void javaIsColouredAndTheHeaderNamesTheLanguage() throws Exception {
        TerminalScreen screen = show("A.java", utf8("int x = 1; // note\n\t\"s\"\n"), "");
        assertTrue(row(screen, 0).contains("Java"), row(screen, 0));
        assertEquals(THEME.type(), at(screen, 0, 1).getForegroundColor(), "int");
        assertEquals(THEME.text(), at(screen, 4, 1).getForegroundColor(), "x");
        assertEquals(THEME.number(), at(screen, 8, 1).getForegroundColor(), "1");
        assertEquals(THEME.comment(), at(screen, 11, 1).getForegroundColor(), "// note");
        assertEquals(THEME.comment(), at(screen, 17, 1).getForegroundColor(), "last comment letter");
        // The tab is drawn as spaces up to the next stop of 4 columns; the string starts after it.
        assertEquals("    \"s\"", row(screen, 2));
        assertEquals(THEME.string(), at(screen, 4, 2).getForegroundColor(), "string after a tab");
    }

    @Test
    void multiByteTextBeforeAColouredTokenKeepsTheColoursAligned() throws Exception {
        TerminalScreen screen = show("A.java", utf8("// \u00e9\u20ac\u00fc\nint x;\n"), "");
        assertEquals(THEME.comment(), at(screen, 3, 1).getForegroundColor());
        assertEquals(THEME.type(), at(screen, 0, 2).getForegroundColor(), "int on the next line");
    }

    @Test
    void highlightOffAFileOverTheLimitUnknownExtensionsAndBinaryBytesStayPlain() throws Exception {
        assertEquals(THEME.text(), at(show("A.java", utf8("int x;\n"), "viewer.highlight = false"), 0, 1)
                .getForegroundColor());
        assertEquals(THEME.text(), at(show("A.java", utf8("int x;\n"), "viewer.highlight_max_kb = 0"), 0, 1)
                .getForegroundColor());
        assertTrue(!row(show("A.java", utf8("int x;\n"), "viewer.highlight = false"), 0).contains("Java"));
        assertEquals(THEME.text(), at(show("notes.txt", utf8("int x;\n"), ""), 0, 1).getForegroundColor());
        // Bytes that are not UTF-8 and not decodable one to one are refused by the engine; the text still shows.
        TerminalScreen screen = show("B.java", new byte[] {'i', 'n', 't', ' ', (byte) 0xE9, ';', '\n'}, "");
        assertTrue(row(screen, 1).startsWith("int "), row(screen, 1));
    }

    @Test
    void theRawViewOfHtmlIsColouredAndTheRenderedViewKeepsItsOwnColours() throws Exception {
        byte[] html = utf8("<html><body><h1 class=\"a\">Hi</h1></body></html>\n");
        TerminalScreen raw = show("p.html", html, "", new KeyStroke('r', false, false));
        assertTrue(row(raw, 1).startsWith("<html>"), row(raw, 1));
        assertEquals(THEME.meta(), at(raw, 1, 1).getForegroundColor(), "tag name in the source");
        TerminalScreen rendered = show("p.html", html, "");
        assertEquals("Hi", row(rendered, 1));
        assertEquals(THEME.heading(), at(rendered, 0, 1).getForegroundColor());
    }
}
