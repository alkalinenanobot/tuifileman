package org.ds.tuifileman.ui;

import com.googlecode.lanterna.TextColor;
import java.util.List;
import java.util.Locale;

/**
 * Semantic colour roles; every screen draws with these only, so a new palette is just a new factory method.
 * {@code match} is the search hit in the viewer and the marked range in the compare hex view;
 * {@code filler} is the empty side of a one-sided difference; {@code cursor} and {@code cursorText} are the cursor
 * row of the active panel, {@code directory} the folder names; {@code heading}, {@code link} and {@code code}
 * colour the rendered HTML and Markdown in the viewer; {@code keyword} to {@code meta} (annotations,
 * preprocessor lines, markup tags) colour source code in the viewer.
 */
public record Theme(
        String name,
        String title,
        TextColor appBackground,
        TextColor panelBackground,
        TextColor text,
        TextColor muted,
        TextColor accent,
        TextColor border,
        TextColor selection,
        TextColor cursor,
        TextColor cursorText,
        TextColor directory,
        TextColor headerBackground,
        TextColor headerText,
        TextColor success,
        TextColor warning,
        TextColor error,
        TextColor buttonBackground,
        TextColor match,
        TextColor removed,
        TextColor removedStrong,
        TextColor added,
        TextColor addedStrong,
        TextColor changed,
        TextColor changedStrong,
        TextColor filler,
        TextColor heading,
        TextColor link,
        TextColor code,
        TextColor keyword,
        TextColor type,
        TextColor string,
        TextColor number,
        TextColor comment,
        TextColor meta) {

    private static final List<Theme> ALL = List.of(dark(), light(), contrast(), mc(), oneDark());

    /** All built-in themes, in menu order. */
    public static List<Theme> all() {
        return ALL;
    }

    /** The theme with this name (case-insensitive), or null. */
    public static Theme find(String name) {
        if (name != null) {
            String wanted = name.strip().toLowerCase(Locale.ROOT);
            for (Theme theme : ALL) {
                if (theme.name().equals(wanted)) {
                    return theme;
                }
            }
        }
        return null;
    }

    public static Theme dark() {
        return new Theme("dark", "Dark",
                rgb(17, 23, 34), rgb(23, 31, 44), rgb(218, 225, 236), rgb(137, 153, 174),
                rgb(93, 213, 195), rgb(58, 74, 95), rgb(40, 74, 96), rgb(40, 74, 96), rgb(218, 225, 236), rgb(93, 213, 195),
                rgb(22, 98, 116), rgb(255, 255, 255),
                rgb(132, 204, 148), rgb(229, 192, 123), rgb(255, 0, 0),
                rgb(58, 74, 95), rgb(40, 110, 130),
                rgb(62, 28, 34), rgb(128, 42, 52), rgb(24, 58, 40), rgb(36, 118, 66),
                rgb(62, 54, 24), rgb(132, 104, 30), rgb(27, 33, 45),
                rgb(240, 170, 110), rgb(110, 170, 255), rgb(190, 160, 235),
                rgb(198, 140, 232), rgb(86, 190, 210), rgb(152, 195, 121), rgb(209, 154, 102), rgb(110, 125, 145),
                rgb(224, 108, 117));
    }

    public static Theme light() {
        return new Theme("light", "Light",
                rgb(238, 241, 245), rgb(255, 255, 255), rgb(28, 34, 44), rgb(102, 112, 126),
                rgb(0, 100, 160), rgb(176, 184, 196), rgb(204, 226, 246), rgb(204, 226, 246), rgb(28, 34, 44), rgb(0, 100, 160),
                rgb(0, 100, 160), rgb(255, 255, 255),
                rgb(22, 128, 58), rgb(176, 106, 0), rgb(200, 32, 32),
                rgb(214, 220, 229), rgb(255, 226, 120),
                rgb(255, 224, 228), rgb(250, 170, 180), rgb(214, 244, 224), rgb(150, 220, 175),
                rgb(255, 243, 205), rgb(245, 214, 120), rgb(232, 235, 240),
                rgb(176, 80, 0), rgb(0, 90, 200), rgb(120, 60, 170),
                rgb(120, 50, 190), rgb(0, 110, 140), rgb(30, 120, 50), rgb(170, 90, 0), rgb(120, 130, 140),
                rgb(190, 50, 70));
    }

    /** Black and white with yellow accents and a saturated selection: for bright rooms and weak eyes. */
    public static Theme contrast() {
        return new Theme("contrast", "High contrast",
                rgb(0, 0, 0), rgb(0, 0, 0), rgb(255, 255, 255), rgb(200, 200, 200),
                rgb(255, 235, 0), rgb(150, 150, 150), rgb(0, 0, 200), rgb(0, 0, 200), rgb(255, 255, 255), rgb(255, 235, 0),
                rgb(255, 235, 0), rgb(0, 0, 0),
                rgb(0, 255, 0), rgb(255, 160, 0), rgb(255, 80, 80),
                rgb(80, 80, 80), rgb(150, 0, 150),
                rgb(110, 0, 0), rgb(200, 0, 0), rgb(0, 90, 0), rgb(0, 130, 0),
                rgb(110, 90, 0), rgb(150, 110, 0), rgb(40, 40, 40),
                rgb(255, 235, 0), rgb(80, 200, 255), rgb(255, 150, 255),
                rgb(255, 150, 255), rgb(80, 220, 255), rgb(120, 255, 120), rgb(255, 170, 60), rgb(170, 170, 170),
                rgb(255, 110, 110));
    }

    /** Black panels, white frames and a blue bar, like a Midnight Commander skin. */
    public static Theme mc() {
        return new Theme("mc", "Midnight Commander",
                rgb(0, 0, 0), rgb(0, 0, 0), rgb(235, 235, 235), rgb(150, 150, 150),
                rgb(60, 150, 255), rgb(210, 210, 210), rgb(0, 115, 225), rgb(0, 115, 225), rgb(235, 235, 235), rgb(60, 150, 255),
                rgb(0, 115, 225), rgb(255, 255, 255),
                rgb(80, 255, 80), rgb(255, 255, 80), rgb(255, 80, 80),
                rgb(70, 70, 70), rgb(0, 90, 160),
                rgb(80, 0, 0), rgb(170, 0, 0), rgb(0, 70, 0), rgb(0, 140, 0),
                rgb(90, 80, 0), rgb(150, 130, 0), rgb(30, 30, 30),
                rgb(255, 255, 80), rgb(80, 255, 255), rgb(200, 160, 255),
                rgb(255, 120, 255), rgb(80, 255, 255), rgb(80, 255, 80), rgb(255, 170, 60), rgb(130, 130, 130),
                rgb(255, 100, 100));
    }

    /**
     * Atom One Dark: slate panels on a lighter frame, blue accent, yellow folders. The cursor of the active panel is
     * a solid accent bar with dark text; the inactive panel keeps the quiet {@code selection} grey.
     */
    public static Theme oneDark() {
        return new Theme("onedark", "One Dark",
                rgb(44, 49, 58), rgb(33, 37, 43), rgb(200, 204, 212), rgb(127, 132, 142),
                rgb(97, 175, 239), rgb(75, 82, 99), rgb(62, 68, 81), rgb(97, 175, 239), rgb(27, 29, 35), rgb(229, 192, 123),
                rgb(97, 175, 239), rgb(27, 29, 35),
                rgb(152, 195, 121), rgb(209, 154, 102), rgb(224, 108, 117),
                rgb(62, 68, 81), rgb(55, 85, 128),
                rgb(64, 38, 44), rgb(122, 50, 60), rgb(35, 58, 42), rgb(50, 102, 64),
                rgb(62, 54, 36), rgb(122, 100, 46), rgb(40, 44, 52),
                rgb(224, 108, 117), rgb(97, 175, 239), rgb(152, 195, 121),
                rgb(198, 120, 221), rgb(229, 192, 123), rgb(152, 195, 121), rgb(209, 154, 102), rgb(92, 99, 112),
                rgb(224, 108, 117));
    }

    private static TextColor rgb(int r, int g, int b) {
        return new TextColor.RGB(r, g, b);
    }
}
