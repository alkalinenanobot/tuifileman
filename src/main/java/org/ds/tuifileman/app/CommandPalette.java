package org.ds.tuifileman.app;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import org.ds.tuifileman.ui.Theme;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ctrl+P command palette: a type-to-filter list of every menu action. Like {@link MenuBar} it is drawn and
 * driven from the main loop and hands the chosen action back after it has closed.
 */
final class CommandPalette {

    record Entry(String title, String shortcut, Command action) { }

    private static final int MAX_ROWS = 10;
    private static final int MAX_WIDTH = 64;

    private final List<Entry> all = new ArrayList<>();
    private final StringBuilder query = new StringBuilder();
    private List<Entry> shown = List.of();
    private boolean open;
    private int selected;
    private int scroll;
    private Command chosen;

    CommandPalette(List<MenuBar.Menu> menus) {
        for (MenuBar.Menu menu : menus) {
            for (MenuBar.Item item : menu.items()) {
                if (!item.isSeparator()) {
                    String label = item.label().endsWith("...")
                            ? item.label().substring(0, item.label().length() - 3) : item.label();
                    all.add(new Entry(menu.title() + ": " + label, item.shortcut(), item.action()));
                }
            }
        }
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        open = true;
        query.setLength(0);
        refilter();
    }

    void close() {
        open = false;
    }

    Command takeChosen() {
        Command result = chosen;
        chosen = null;
        return result;
    }

    boolean handleKey(KeyStroke key) {
        if (!open) {
            return false;
        }
        switch (key.getKeyType()) {
            case Escape -> close();
            case Enter -> choose();
            case Backspace -> {
                if (query.length() > 0) {
                    query.setLength(query.length() - 1);
                    refilter();
                }
            }
            case ArrowUp -> select(selected - 1);
            case ArrowDown -> select(selected + 1);
            case PageUp -> select(selected - MAX_ROWS);
            case PageDown -> select(selected + MAX_ROWS);
            case Home -> select(0);
            case End -> select(shown.size() - 1);
            case Character -> {
                Character c = key.getCharacter();
                if (key.isCtrlDown() && c != null && Character.toLowerCase(c) == 'p') {
                    close();
                } else if (!key.isCtrlDown() && !key.isAltDown() && c != null && !Character.isISOControl(c)) {
                    query.append(c);
                    refilter();
                }
            }
            default -> { }
        }
        return true;
    }

    boolean handleMouse(MouseAction mouse, int screenWidth) {
        if (!open) {
            return false;
        }
        int column = mouse.getPosition().getColumn();
        int row = mouse.getPosition().getRow();
        int x = boxX(screenWidth);
        boolean inside = column >= x && column < x + boxWidth(screenWidth)
                && row >= TOP && row <= TOP + 3 + rows();
        switch (mouse.getActionType()) {
            case CLICK_DOWN -> {
                int index = scroll + row - (TOP + 3);
                if (!inside) {
                    close();
                } else if (row >= TOP + 3 && row < TOP + 3 + rows() && index < shown.size()) {
                    selected = index;
                    choose();
                }
            }
            case SCROLL_UP -> select(selected - 1);
            case SCROLL_DOWN -> select(selected + 1);
            default -> { }
        }
        return true;
    }

    private static final int TOP = 2;

    void render(TextGraphics g, Theme theme, int width) {
        if (!open) {
            return;
        }
        int x = boxX(width);
        int w = boxWidth(width);
        int inner = w - 2;
        int rows = rows();
        g.setBackgroundColor(theme.panelBackground());
        g.setForegroundColor(theme.accent());
        String title = " Command palette ";
        g.putString(x, TOP, "\u250c\u2500" + title + "\u2500".repeat(Math.max(0, inner - 1 - title.length())) + "\u2510");
        g.putString(x, TOP + 1, "\u2502");
        g.putString(x + w - 1, TOP + 1, "\u2502");
        g.setForegroundColor(theme.text());
        String typed = "> " + query;
        String shownQuery = typed.length() > inner - 3 ? typed.substring(typed.length() - (inner - 3)) : typed;
        g.putString(x + 1, TOP + 1, " " + pad(shownQuery + "_", inner - 1));
        g.setForegroundColor(theme.accent());
        g.putString(x, TOP + 2, "\u251c" + "\u2500".repeat(inner) + "\u2524");
        for (int i = 0; i < rows; i++) {
            int y = TOP + 3 + i;
            int index = scroll + i;
            g.setBackgroundColor(theme.panelBackground());
            g.setForegroundColor(theme.accent());
            g.putString(x, y, "\u2502");
            g.putString(x + w - 1, y, "\u2502");
            g.setBackgroundColor(index == selected && !shown.isEmpty() ? theme.selection() : theme.panelBackground());
            if (shown.isEmpty()) {
                g.setForegroundColor(theme.muted());
                g.putString(x + 1, y, pad(" No matching command", inner));
                continue;
            }
            Entry entry = shown.get(index);
            String shortcut = entry.shortcut();
            int titleWidth = inner - shortcut.length() - 2;
            g.setForegroundColor(theme.text());
            String label = entry.title().length() > titleWidth
                    ? entry.title().substring(0, Math.max(0, titleWidth)) : entry.title();
            g.putString(x + 1, y, " " + pad(label, inner - 1));
            g.setForegroundColor(theme.muted());
            g.putString(x + 1 + inner - shortcut.length() - 1, y, shortcut + " ");
        }
        g.setBackgroundColor(theme.panelBackground());
        g.setForegroundColor(theme.accent());
        g.putString(x, TOP + 3 + rows, "\u2514" + "\u2500".repeat(inner) + "\u2518");
    }

    private int rows() {
        return Math.max(1, Math.min(MAX_ROWS, shown.size()));
    }

    private int boxWidth(int screenWidth) {
        return Math.min(MAX_WIDTH, screenWidth - 4);
    }

    private int boxX(int screenWidth) {
        return (screenWidth - boxWidth(screenWidth)) / 2;
    }

    private void choose() {
        if (!shown.isEmpty()) {
            chosen = shown.get(selected).action();
            close();
        }
    }

    private void select(int index) {
        if (shown.isEmpty()) {
            return;
        }
        selected = Math.max(0, Math.min(shown.size() - 1, index));
        if (selected < scroll) {
            scroll = selected;
        } else if (selected >= scroll + MAX_ROWS) {
            scroll = selected - MAX_ROWS + 1;
        }
    }

    private void refilter() {
        String[] tokens = query.toString().toLowerCase().trim().split("\\s+");
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            String title = all.get(i).title().toLowerCase();
            int total = 0;
            for (String token : tokens) {
                int score = token.isEmpty() ? 0 : score(token, title);
                if (score == Integer.MAX_VALUE) {
                    total = Integer.MAX_VALUE;
                    break;
                }
                total += score;
            }
            if (total != Integer.MAX_VALUE) {
                scored.add(new int[] {i, total});
            }
        }
        scored.sort(Comparator.comparingInt((int[] s) -> s[1]));
        shown = scored.stream().map(s -> all.get(s[0])).toList();
        selected = 0;
        scroll = 0;
    }

    /** Lower is better; MAX_VALUE when the token is not even a subsequence of the title. */
    private static int score(String token, String title) {
        int at = title.indexOf(token);
        if (at >= 0) {
            boolean wordStart = at == 0 || !Character.isLetterOrDigit(title.charAt(at - 1));
            return -1000 + at + (wordStart ? 0 : 50);
        }
        int first = -1;
        int pos = 0;
        for (char c : token.toCharArray()) {
            pos = title.indexOf(c, pos);
            if (pos < 0) {
                return Integer.MAX_VALUE;
            }
            if (first < 0) {
                first = pos;
            }
            pos++;
        }
        return pos - first;
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
