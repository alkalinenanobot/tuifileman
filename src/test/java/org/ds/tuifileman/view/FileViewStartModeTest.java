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
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F3 starts in the text view for text files and in the hex view for binary data. */
class FileViewStartModeTest {
    @TempDir
    Path dir;

    private String firstBodyRow(byte[] content) throws Exception {
        Path file = Files.write(dir.resolve("f.txt"), content);
        TerminalScreen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(60, 8)));
        screen.startScreen();
        ((DefaultVirtualTerminal) screen.getTerminal()).addInput(new KeyStroke(KeyType.Escape));
        FileView.show(screen, Theme.mc(), file, Config.parse("viewer.line_numbers = false"));
        StringBuilder row = new StringBuilder();
        for (int x = 0; x < 60; x++) {
            row.append(screen.getFrontCharacter(new TerminalPosition(x, 1)).getCharacter());
        }
        return row.toString().stripTrailing();
    }

    @Test
    void textMostlyOutsideAsciiOpensAsText() throws Exception {
        assertEquals("árvíztűrő tükörfúrógép", firstBodyRow("árvíztűrő tükörfúrógép\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Привет, мир", firstBodyRow("Привет, мир\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void controlBytesStillOpenAsHex() throws Exception {
        assertTrue(firstBodyRow(new byte[] {'a', 0, 'b', 1, 2, 3}).startsWith("00000000"));
        assertTrue(firstBodyRow(new byte[] {1, 2, 3, 4, 5, 6, 7, 'a'}).startsWith("00000000"));
    }
}
