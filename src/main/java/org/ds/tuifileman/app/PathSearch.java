package org.ds.tuifileman.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Quick search over a list of paths, mirroring the main panel: typed characters jump to the first path whose
 * last name starts with the text, otherwise to the first path containing it; up/down step through the matches.
 * Methods return the new cursor index, or -1 when the cursor must stay where it is.
 */
final class PathSearch {

    private final StringBuilder text = new StringBuilder();
    private boolean matched = true;

    boolean active() {
        return text.length() > 0;
    }

    String text() {
        return text.toString();
    }

    /** True when the current text matches at least one path. */
    boolean matched() {
        return matched;
    }

    void clear() {
        text.setLength(0);
        matched = true;
    }

    int append(char c, List<Path> items) {
        text.append(c);
        return restart(items);
    }

    int backspace(List<Path> items) {
        if (text.length() == 0) {
            return -1;
        }
        text.setLength(text.length() - 1);
        return restart(items);
    }

    /** Next (+1) or previous (-1) match after {@code current}; prefix matches only if the current one is a prefix match. */
    int step(int direction, int current, List<Path> items) {
        boolean prefixOnly = current >= 0 && current < items.size() && matches(items.get(current), true);
        for (int i = current + direction; i >= 0 && i < items.size(); i += direction) {
            if (matches(items.get(i), prefixOnly)) {
                return i;
            }
        }
        return -1;
    }

    private int restart(List<Path> items) {
        if (text.length() == 0) {
            matched = true;
            return -1;
        }
        for (boolean prefixOnly : new boolean[] {true, false}) {
            for (int i = 0; i < items.size(); i++) {
                if (matches(items.get(i), prefixOnly)) {
                    matched = true;
                    return i;
                }
            }
        }
        matched = false;
        return -1;
    }

    private boolean matches(Path path, boolean prefixOnly) {
        String needle = text.toString().toLowerCase(Locale.ROOT);
        if (prefixOnly) {
            return name(path).toLowerCase(Locale.ROOT).startsWith(needle);
        }
        return path.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String name(Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }
}
