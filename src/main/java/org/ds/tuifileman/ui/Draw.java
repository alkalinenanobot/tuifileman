package org.ds.tuifileman.ui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

/** Small drawing helpers shared by the full-screen views. */
public final class Draw {
    private Draw() { }

    /** Result of {@link #expand(String, int, int)}: the display text and the mapped character range. */
    public record Expanded(String text, int from, int to) { }

    /** Draws text clipped and padded to exactly {@code width} columns. */
    public static void cell(TextGraphics g, int x, int y, int width, String value, TextColor fg, TextColor bg) {
        if (width <= 0) {
            return;
        }
        String clipped = value.length() > width ? value.substring(0, width) : value;
        StringBuilder padded = new StringBuilder(width).append(clipped);
        while (padded.length() < width) {
            padded.append(' ');
        }
        g.setForegroundColor(fg);
        g.setBackgroundColor(bg);
        g.putString(new TerminalPosition(x, y), padded.toString());
    }

    public static String shorten(String path, int width) {
        return path.length() <= width ? path : "…" + path.substring(path.length() - width + 1);
    }

    /** Tabs become spaces (4-column stops) and control characters a middle dot. */
    public static String expand(String s) {
        return expand(s, 0, 0).text();
    }

    /** Like {@link #expand(String)}; also maps the character range [from, to) of {@code s} into the result. */
    public static Expanded expand(String s, int from, int to) {
        boolean plain = true;
        for (int i = 0; i < s.length() && plain; i++) {
            char c = s.charAt(i);
            plain = c != '\t' && !Character.isISOControl(c);
        }
        if (plain) {
            return new Expanded(s, from, to);
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        int mappedFrom = from;
        int mappedTo = to;
        for (int i = 0; i <= s.length(); i++) {
            if (i == from) {
                mappedFrom = out.length();
            }
            if (i == to) {
                mappedTo = out.length();
            }
            if (i == s.length()) {
                break;
            }
            char c = s.charAt(i);
            if (c == '\t') {
                out.append(" ".repeat(4 - (out.length() % 4)));
            } else {
                out.append(Character.isISOControl(c) ? '·' : c);
            }
        }
        return new Expanded(out.toString(), mappedFrom, mappedTo);
    }
}
