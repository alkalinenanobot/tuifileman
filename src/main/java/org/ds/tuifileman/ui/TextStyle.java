package org.ds.tuifileman.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.EnumSet;

/**
 * Style of one character of a rendered or highlighted text, packed in a byte: the low four bits are a colour role
 * (resolved with the {@link Theme}), the high four bits are bold, italic, underline and strike-through.
 */
public final class TextStyle {
    public static final int TEXT = 0;
    public static final int HEADING = 1;
    public static final int LINK = 2;
    public static final int CODE = 3;
    public static final int MUTED = 4;
    public static final int SUCCESS = 5;
    public static final int KEYWORD = 6;
    public static final int TYPE = 7;
    public static final int STRING = 8;
    public static final int NUMBER = 9;
    public static final int COMMENT = 10;
    public static final int META = 11;

    public static final int ROLE_MASK = 0x0F;
    public static final int BOLD = 0x10;
    public static final int ITALIC = 0x20;
    public static final int UNDERLINE = 0x40;
    public static final int STRIKE = 0x80;

    private static final EnumSet<SGR>[] MODIFIERS = modifierTable();

    private TextStyle() { }

    /** The style a nested element gets: {@code add} replaces the colour role when it has one and adds its flags. */
    public static byte combine(byte outer, int add) {
        int role = (add & ROLE_MASK) != 0 ? add & ROLE_MASK : outer & ROLE_MASK;
        return (byte) (role | ((outer | add) & 0xF0));
    }

    public static TextColor color(Theme theme, byte style) {
        return switch (style & ROLE_MASK) {
            case HEADING -> theme.heading();
            case LINK -> theme.link();
            case CODE -> theme.code();
            case MUTED -> theme.muted();
            case SUCCESS -> theme.success();
            case KEYWORD -> theme.keyword();
            case TYPE -> theme.type();
            case STRING -> theme.string();
            case NUMBER -> theme.number();
            case COMMENT -> theme.comment();
            case META -> theme.meta();
            default -> theme.text();
        };
    }

    /** The terminal attributes of the style; the sets are shared, do not modify them. */
    public static EnumSet<SGR> modifiers(byte style) {
        return MODIFIERS[(style & 0xF0) >> 4];
    }

    /**
     * UTF-8 length of the character at {@code i}: a surrogate pair counts 4 at the high surrogate and 0 at the low
     * one, so the sum over a string is the length of its UTF-8 encoding.
     */
    public static int utf8Length(CharSequence text, int i) {
        char c = text.charAt(i);
        if (c < 0x80) {
            return 1;
        }
        if (c < 0x800) {
            return 2;
        }
        if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
            return 4;
        }
        return Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(text.charAt(i - 1)) ? 0 : 3;
    }

    @SuppressWarnings("unchecked")
    private static EnumSet<SGR>[] modifierTable() {
        EnumSet<SGR>[] table = new EnumSet[16];
        for (int i = 0; i < 16; i++) {
            EnumSet<SGR> set = EnumSet.noneOf(SGR.class);
            if ((i & 1) != 0) {
                set.add(SGR.BOLD);
            }
            if ((i & 2) != 0) {
                set.add(SGR.ITALIC);
            }
            if ((i & 4) != 0) {
                set.add(SGR.UNDERLINE);
            }
            if ((i & 8) != 0) {
                set.add(SGR.CROSSED_OUT);
            }
            table[i] = set;
        }
        return table;
    }
}
