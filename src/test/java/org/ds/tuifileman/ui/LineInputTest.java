package org.ds.tuifileman.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

class LineInputTest {
    private static KeyStroke key(KeyType type) {
        return new KeyStroke(type, false, false, false);
    }

    private static KeyStroke ctrl(KeyType type) {
        return new KeyStroke(type, true, false, false);
    }

    private static void type(LineInput input, String text) {
        for (char c : text.toCharArray()) {
            assertTrue(input.handle(new KeyStroke(c, false, false, false)));
        }
    }

    @Test
    void cursorStartsAtTheEndOfTheInitialText() {
        LineInput input = new LineInput("needle");
        assertEquals(6, input.cursor());
        type(input, "s");
        assertEquals("needles", input.text());
    }

    @Test
    void charactersAreInsertedAndDeletedAtTheCursor() {
        LineInput input = new LineInput("helo");
        input.handle(key(KeyType.ArrowLeft));
        input.handle(key(KeyType.ArrowLeft));
        type(input, "l");
        assertEquals("hello", input.text());
        assertEquals(3, input.cursor());

        input.handle(key(KeyType.Backspace));
        assertEquals("helo", input.text());
        input.handle(key(KeyType.Delete));
        assertEquals("heo", input.text());
        assertEquals(2, input.cursor());
    }

    @Test
    void homeEndAndTheEdgesAreClamped() {
        LineInput input = new LineInput("ab");
        input.handle(key(KeyType.Home));
        input.handle(key(KeyType.ArrowLeft));
        input.handle(key(KeyType.Backspace));
        assertEquals("ab", input.text());
        assertEquals(0, input.cursor());
        input.handle(key(KeyType.Delete));
        assertEquals("b", input.text());
        input.handle(key(KeyType.End));
        input.handle(key(KeyType.ArrowRight));
        input.handle(key(KeyType.Delete));
        assertEquals("b", input.text());
        assertEquals(1, input.cursor());
    }

    @Test
    void ctrlArrowsJumpByWord() {
        LineInput input = new LineInput("one two  three");
        input.handle(ctrl(KeyType.ArrowLeft));
        assertEquals(9, input.cursor());
        input.handle(ctrl(KeyType.ArrowLeft));
        assertEquals(4, input.cursor());
        input.handle(ctrl(KeyType.ArrowRight));
        assertEquals(7, input.cursor());
        input.handle(ctrl(KeyType.ArrowRight));
        input.handle(ctrl(KeyType.ArrowRight));
        assertEquals(14, input.cursor());
    }

    @Test
    void controlCharactersAreNotTypedButAltGrCharactersAre() {
        LineInput input = new LineInput("");
        assertTrue(input.handle(new KeyStroke('a', true, false, false)));
        assertEquals("", input.text());
        assertTrue(input.handle(new KeyStroke('@', true, true, false)));
        assertEquals("@", input.text());
    }

    @Test
    void enterAndEscapeAreLeftToTheCaller() {
        LineInput input = new LineInput("x");
        assertFalse(input.handle(key(KeyType.Enter)));
        assertFalse(input.handle(key(KeyType.Escape)));
        assertEquals("x", input.text());
    }
}
