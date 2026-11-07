package org.ds.tuifileman.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rendered HTML / Markdown view of the F3 viewer on a virtual terminal: colours, attributes and the R toggle. */
class FileViewRichTest {
    private static final int COLUMNS = 50;
    private static final int ROWS = 14;
    private static final Theme THEME = Theme.mc();
    private static final Config CONFIG = Config.parse("viewer.line_numbers = false\nviewer.wrap = true");

    @TempDir
    Path dir;

    private TerminalScreen show(String name, String content, KeyStroke... keys) throws Exception {
        Path file = Files.writeString(dir.resolve(name), content);
        TerminalScreen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS)));
        screen.startScreen();
        var terminal = (DefaultVirtualTerminal) screen.getTerminal();
        for (KeyStroke key : keys) {
            terminal.addInput(key);
        }
        terminal.addInput(new KeyStroke(KeyType.Escape));
        FileView.show(screen, THEME, file, CONFIG);
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

    @Test
    void markdownIsDrawnWithColoursAndTerminalAttributes() throws Exception {
        TerminalScreen screen = show("a.md", "# Head\n\n~~gone~~ **bold** [l](http://x) plain\n");
        assertEquals("Head", row(screen, 1));
        assertEquals("====", row(screen, 2));
        assertEquals("gone bold l (http://x) plain", row(screen, 4));

        TextCharacter heading = at(screen, 0, 1);
        assertEquals(THEME.heading(), heading.getForegroundColor());
        assertTrue(heading.getModifiers().contains(SGR.BOLD));

        TextCharacter struck = at(screen, 0, 4);
        assertTrue(struck.getModifiers().contains(SGR.CROSSED_OUT));
        assertEquals(THEME.text(), struck.getForegroundColor());

        assertTrue(at(screen, 5, 4).getModifiers().contains(SGR.BOLD));
        assertFalse(at(screen, 5, 4).getModifiers().contains(SGR.CROSSED_OUT));

        TextCharacter link = at(screen, 10, 4);
        assertEquals('l', link.getCharacter());
        assertEquals(THEME.link(), link.getForegroundColor());
        assertTrue(link.getModifiers().contains(SGR.UNDERLINE));

        TextCharacter url = at(screen, 12, 4);
        assertEquals('(', url.getCharacter());
        assertEquals(THEME.muted(), url.getForegroundColor());
        assertFalse(url.getModifiers().contains(SGR.UNDERLINE));

        TextCharacter plain = at(screen, 23, 4);
        assertEquals('p', plain.getCharacter());
        assertEquals(THEME.text(), plain.getForegroundColor());
        assertTrue(plain.getModifiers().isEmpty());
    }

    @Test
    void rShowsTheRawSourceAndShowsTheRenderingAgain() throws Exception {
        String md = "# Head\n\ntext\n";
        assertEquals("# Head", row(show("a.md", md, new KeyStroke('r', false, false)), 1));
        assertEquals("Head", row(show("a.md", md, new KeyStroke('r', false, false), new KeyStroke('r', false, false)), 1));
    }

    @Test
    void textLinesOfAPlainFileKeepTheNormalColourAndNoAttributes() throws Exception {
        TerminalScreen screen = show("a.txt", "# not markdown\n");
        TextCharacter c = at(screen, 0, 1);
        assertEquals('#', c.getCharacter());
        assertEquals(THEME.text(), c.getForegroundColor());
        assertTrue(c.getModifiers().isEmpty());
    }
}
