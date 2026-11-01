package org.ds.tuifileman.view;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.EnumSet;
import org.ds.tuifileman.ui.Theme;

/**
 * Style of one character of a rendered document, packed in a byte: the low four bits are a colour role (resolved
 * with the {@link Theme}), the high four bits are bold, italic, underline and strike-through.
 */
final class TextStyle {
    static final int TEXT = 0;
    static final int HEADING = 1;
    static final int LINK = 2;
    static final int CODE = 3;
    static final int MUTED = 4;
    static final int SUCCESS = 5;

    static final int ROLE_MASK = 0x0F;
    static final int BOLD = 0x10;
    static final int ITALIC = 0x20;
    static final int UNDERLINE = 0x40;
    static final int STRIKE = 0x80;

    private static final EnumSet<SGR>[] MODIFIERS = modifierTable();

    private TextStyle() { }

    /** The style a nested element gets: {@code add} replaces the colour role when it has one and adds its flags. */
    static byte combine(byte outer, int add) {
        int role = (add & ROLE_MASK) != 0 ? add & ROLE_MASK : outer & ROLE_MASK;
        return (byte) (role | ((outer | add) & 0xF0));
    }

    static TextColor color(Theme theme, byte style) {
        return switch (style & ROLE_MASK) {
            case HEADING -> theme.heading();
            case LINK -> theme.link();
            case CODE -> theme.code();
            case MUTED -> theme.muted();
            case SUCCESS -> theme.success();
            default -> theme.text();
        };
    }

    /** The terminal attributes of the style; the sets are shared, do not modify them. */
    static EnumSet<SGR> modifiers(byte style) {
        return MODIFIERS[(style & 0xF0) >> 4];
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
