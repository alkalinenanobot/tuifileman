package org.ds.tuifileman.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.DefaultWindowDecorationRenderer;

/** Maps our semantic {@link Theme} onto a Lanterna GUI2 theme used by dialogs. */
public final class DialogTheme {
    private DialogTheme() { }

    public static SimpleTheme create(Theme t) {
        SimpleTheme theme = SimpleTheme.makeTheme(false, t.text(), t.panelBackground(),
                t.text(), t.selection(), t.appBackground(), t.accent(), t.appBackground());

        // Same rounded frame as the file panels; both bevel edges use one colour.
        SimpleTheme.Definition window = theme.addOverride(
                DefaultWindowDecorationRenderer.class, t.accent(), t.appBackground());
        window.setPreLight(t.accent(), t.appBackground());
        window.setActive(t.accent(), t.appBackground());
        window.setSelected(t.accent(), t.appBackground());
        window.setCharacter("HORIZONTAL_LINE", '\u2500');
        window.setCharacter("VERTICAL_LINE", '\u2502');
        window.setCharacter("TOP_LEFT_CORNER", '\u256d');
        window.setCharacter("TOP_RIGHT_CORNER", '\u256e');
        window.setCharacter("BOTTOM_LEFT_CORNER", '\u2570');
        window.setCharacter("BOTTOM_RIGHT_CORNER", '\u256f');
        window.setBooleanProperty("TITLE_PADDING", true);
        window.setCharacter("TITLE_SEPARATOR_LEFT", '\u2500');
        window.setCharacter("TITLE_SEPARATOR_RIGHT", '\u2500');

        // Buttons: raised look when idle, accent fill when focused, [ ] instead of < >.
        SimpleTheme.Definition button = theme.addOverride(Button.class, t.text(), t.buttonBackground());
        button.setPreLight(t.text(), t.buttonBackground());
        button.setSelected(t.appBackground(), t.accent(), SGR.BOLD);
        button.setActive(t.appBackground(), t.accent(), SGR.BOLD);
        button.setInsensitive(t.muted(), t.panelBackground());
        button.setCharacter("LEFT_BORDER", '[');
        button.setCharacter("RIGHT_BORDER", ']');
        return theme;
    }
}
