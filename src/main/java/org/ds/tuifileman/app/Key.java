package org.ds.tuifileman.app;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/** Normalized keyboard input used as a command-registry lookup key. */
public record Key(KeyType type, Character character, boolean ctrl, boolean alt, boolean shift) {
    public Key(KeyType type, Character character, boolean ctrl, boolean alt) {
        this(type, character, ctrl, alt, false);
    }

    public static Key from(KeyStroke stroke) {
        // Lanterna enforces a character on some special keys (Tab='\t', Enter='\n',
        // Backspace='\b'); only Character-typed keys carry meaningful characters.
        Character character = stroke.getKeyType() == KeyType.Character
                ? stroke.getCharacter()
                : null;
        // Shift only distinguishes function keys and Enter; for other keys it is already part of the character.
        KeyType type = stroke.getKeyType();
        boolean functionKey = type.ordinal() >= KeyType.F1.ordinal() && type.ordinal() <= KeyType.F19.ordinal();
        return new Key(type, character, stroke.isCtrlDown(), stroke.isAltDown(),
                (functionKey || type == KeyType.Enter) && stroke.isShiftDown());
    }

    public static Key of(KeyType type) {
        return new Key(type, null, false, false);
    }

    public static Key ctrl(char c) {
        return new Key(KeyType.Character, Character.toLowerCase(c), true, false);
    }

    public static Key ch(char c) {
        return new Key(KeyType.Character, c, false, false);
    }
}
