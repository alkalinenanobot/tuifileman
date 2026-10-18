package org.ds.tuifileman.app;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import org.ds.tuifileman.ui.Theme;
import java.util.List;

/**
 * Pull-down menu bar drawn on the top row of the main view. It is driven from the main loop (not GUI2):
 * while a menu is open it swallows every key and mouse event; a chosen item is handed back through
 * {@link #takeChosen()} after the menu has closed, so the action may open dialogs.
 */
final class MenuBar {

    record Item(String label, String shortcut, Command action) {
        static Item separator() {
            return new Item(null, "", null);
        }

        boolean isSeparator() {
            return label == null;
        }
    }

    record Menu(String title, List<Item> items) {
        char hotkey() {
            return Character.toLowerCase(title.charAt(0));
        }
    }

    private final List<Menu> menus;
    private int open = -1;
    private int selected;
    private Command chosen;

    MenuBar(List<Menu> menus) {
        this.menus = menus;
    }

    boolean isOpen() {
        return open >= 0;
    }

    List<Menu> menus() {
        return menus;
    }

    void open(int index) {
        open = Math.floorMod(index, menus.size());
        selected = step(-1, 1);
    }

    void close() {
        open = -1;
    }

    /** The action picked since the last call, or null. */
    Command takeChosen() {
        Command result = chosen;
        chosen = null;
        return result;
    }

    /** Opens the menu with this Alt+letter shortcut (closes it when it is already open); false if none matches. */
    boolean toggleByHotkey(char c) {
        for (int i = 0; i < menus.size(); i++) {
            if (menus.get(i).hotkey() == Character.toLowerCase(c)) {
                if (open == i) {
                    close();
                } else {
                    open(i);
                }
                return true;
            }
        }
        return false;
    }

    void toggle() {
        if (isOpen()) {
            close();
        } else {
            open(0);
        }
    }

    /** Returns true when the key was consumed (always, while a menu is open). */
    boolean handleKey(KeyStroke key) {
        if (!isOpen()) {
            return false;
        }
        switch (key.getKeyType()) {
            case Escape, F9 -> close();
            case ArrowLeft -> open(open - 1);
            case ArrowRight -> open(open + 1);
            case ArrowDown -> selected = step(selected, 1);
            case ArrowUp -> selected = step(selected, -1);
            case Home -> selected = step(-1, 1);
            case End -> selected = step(items().size(), -1);
            case Enter -> choose();
            case Character -> {
                if (key.isAltDown() && key.getCharacter() != null) {
                    toggleByHotkey(key.getCharacter());
                }
            }
            default -> { }
        }
        return true;
    }

    /** Returns true when the event was consumed. */
    boolean handleMouse(MouseAction mouse, int screenWidth) {
        int column = mouse.getPosition().getColumn();
        int row = mouse.getPosition().getRow();
        boolean click = mouse.getActionType() == MouseActionType.CLICK_DOWN;
        if (!isOpen() && row != 0) {
            return false;
        }
        if (!click) {
            return isOpen() || row == 0;
        }
        if (row == 0) {
            int index = titleAt(column);
            if (index < 0 || index == open) {
                close();
            } else {
                open(index);
            }
            return true;
        }
        if (isOpen()) {
            int x = dropdownX(screenWidth);
            int index = row - 2;
            if (column > x && column < x + dropdownWidth() - 1 && index >= 0 && index < items().size()
                    && !items().get(index).isSeparator()) {
                selected = index;
                choose();
            } else if (column < x || column >= x + dropdownWidth() || row > items().size() + 2) {
                close();
            }
        }
        return true;
    }

    void render(TextGraphics g, Theme theme, int width) {
        g.setBackgroundColor(theme.panelBackground());
        g.setForegroundColor(theme.text());
        g.putString(0, 0, " ".repeat(width));
        for (int i = 0; i < menus.size(); i++) {
            Menu menu = menus.get(i);
            int x = titleX(i);
            boolean isOpen = i == open;
            g.setBackgroundColor(isOpen ? theme.headerBackground() : theme.panelBackground());
            g.setForegroundColor(isOpen ? theme.headerText() : theme.accent());
            g.putString(x + 1, 0, menu.title().substring(0, 1));
            g.setForegroundColor(isOpen ? theme.headerText() : theme.text());
            g.putString(x, 0, " ");
            g.putString(x + 2, 0, menu.title().substring(1) + " ");
        }
        String hint = "F9 menu ";
        g.setBackgroundColor(theme.panelBackground());
        g.setForegroundColor(theme.muted());
        g.putString(Math.max(0, width - hint.length()), 0, hint);
        if (isOpen()) {
            renderDropdown(g, theme, width);
        }
    }

    private void renderDropdown(TextGraphics g, Theme theme, int width) {
        int x = dropdownX(width);
        int w = dropdownWidth();
        int inner = w - 2;
        int shortcutWidth = items().stream().mapToInt(i -> i.shortcut().length()).max().orElse(0);
        g.setBackgroundColor(theme.panelBackground());
        g.setForegroundColor(theme.accent());
        g.putString(x, 1, "\u250c" + "\u2500".repeat(inner) + "\u2510");
        for (int i = 0; i < items().size(); i++) {
            Item item = items().get(i);
            int y = 2 + i;
            g.setBackgroundColor(theme.panelBackground());
            g.setForegroundColor(theme.accent());
            if (item.isSeparator()) {
                g.putString(x, y, "\u251c" + "\u2500".repeat(inner) + "\u2524");
                continue;
            }
            g.putString(x, y, "\u2502");
            g.putString(x + w - 1, y, "\u2502");
            var rowBackground = i == selected ? theme.selection() : theme.panelBackground();
            g.setBackgroundColor(rowBackground);
            g.setForegroundColor(theme.text());
            int labelWidth = inner - shortcutWidth - 3;
            g.putString(x + 1, y, " " + pad(item.label(), labelWidth));
            g.setForegroundColor(theme.muted());
            g.putString(x + 2 + labelWidth, y, " " + padLeft(item.shortcut(), shortcutWidth) + " ");
        }
        g.setBackgroundColor(theme.panelBackground());
        g.setForegroundColor(theme.accent());
        g.putString(new TerminalPosition(x, 2 + items().size()),
                "\u2514" + "\u2500".repeat(inner) + "\u2518");
    }

    private void choose() {
        chosen = items().get(selected).action();
        close();
    }

    private List<Item> items() {
        return menus.get(open).items();
    }

    /** Next selectable item after {@code from} in direction {@code dir}, wrapping around. */
    private int step(int from, int dir) {
        List<Item> list = items();
        int index = from;
        for (int n = 0; n < list.size(); n++) {
            index = Math.floorMod(index + dir, list.size());
            if (!list.get(index).isSeparator()) {
                return index;
            }
        }
        return 0;
    }

    private int titleX(int index) {
        int x = 1;
        for (int i = 0; i < index; i++) {
            x += menus.get(i).title().length() + 2;
        }
        return x;
    }

    private int titleAt(int column) {
        for (int i = 0; i < menus.size(); i++) {
            int x = titleX(i);
            if (column >= x && column < x + menus.get(i).title().length() + 2) {
                return i;
            }
        }
        return -1;
    }

    private int dropdownWidth() {
        int label = 0;
        int shortcut = 0;
        for (Item item : items()) {
            label = Math.max(label, item.isSeparator() ? 0 : item.label().length());
            shortcut = Math.max(shortcut, item.shortcut().length());
        }
        return 2 + 1 + label + 3 + shortcut + 1;
    }

    private int dropdownX(int screenWidth) {
        return Math.max(0, Math.min(titleX(open), screenWidth - dropdownWidth()));
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    private static String padLeft(String value, int width) {
        return value.length() >= width ? value : " ".repeat(width - value.length()) + value;
    }
}
