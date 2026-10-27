package org.ds.tuifileman.ui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/**
 * The one-line prompt of the full-screen views (find, go to ...): text with an editable cursor. Left/Right,
 * Home/End, Delete and Backspace work at the cursor, Ctrl+Left/Right jump by word, characters are inserted
 * at the cursor.
 */
public final class LineInput {
    private final StringBuilder text;
    private int cursor;
    private int first;

    /** The cursor starts at the end of {@code initial}. */
    public LineInput(String initial) {
        this.text = new StringBuilder(initial);
        this.cursor = text.length();
    }

    public String text() {
        return text.toString();
    }

    public int cursor() {
        return cursor;
    }

    /** Applies an editing or cursor key; false when the key is none of those (Enter, Esc and the like). */
    public boolean handle(KeyStroke key) {
        switch (key.getKeyType()) {
            case ArrowLeft -> cursor = key.isCtrlDown() ? wordStart() : Math.max(0, cursor - 1);
            case ArrowRight -> cursor = key.isCtrlDown() ? wordEnd() : Math.min(text.length(), cursor + 1);
            case Home -> cursor = 0;
            case End -> cursor = text.length();
            case Backspace -> {
                if (cursor > 0) {
                    text.deleteCharAt(--cursor);
                }
            }
            case Delete -> {
                if (cursor < text.length()) {
                    text.deleteCharAt(cursor);
                }
            }
            case Character -> {
                // Ctrl and Alt together is AltGr on Windows keyboards: it types characters such as @ and \.
                boolean typed = key.isCtrlDown() == key.isAltDown() && key.getCharacter() != null
                        && !Character.isISOControl(key.getCharacter());
                if (typed) {
                    text.insert(cursor++, key.getCharacter());
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private int wordStart() {
        int i = cursor;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private int wordEnd() {
        int i = cursor;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Draws {@code " " + label + text} over one row of {@code width} columns; long text scrolls so that the cursor
     * stays visible. The cursor is the character under it (or a blank at the end) in reverse colours.
     */
    public void draw(TextGraphics g, int y, int width, String label, TextColor fg, TextColor bg) {
        int room = Math.max(1, width - 1 - label.length() - 1);
        if (cursor < first) {
            first = cursor;
        } else if (cursor > first + room - 1) {
            first = cursor - room + 1;
        }
        first = Math.max(0, Math.min(first, Math.max(0, text.length() - room + 1)));
        int last = Math.min(text.length(), first + room);
        Draw.cell(g, 0, y, width, " " + label + text.substring(first, last), fg, bg);
        int x = 1 + label.length() + cursor - first;
        if (x < width) {
            g.setForegroundColor(bg);
            g.setBackgroundColor(fg);
            g.putString(new TerminalPosition(x, y), cursor < text.length() ? String.valueOf(text.charAt(cursor)) : " ");
        }
    }
}
