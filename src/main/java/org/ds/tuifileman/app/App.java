package org.ds.tuifileman.app;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.ProgressBar;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.dialogs.ActionListDialog;
import com.googlecode.lanterna.gui2.dialogs.ActionListDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import org.ds.tuifileman.compare.TimeTolerance;
import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.model.EntrySort;
import org.ds.tuifileman.model.FileEntry;
import org.ds.tuifileman.search.FileSearch;
import org.ds.tuifileman.ui.DialogTheme;
import org.ds.tuifileman.ui.Panel;
import org.ds.tuifileman.ui.Theme;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class App {
    private static final int MIN_WIDTH = 72;
    private static final int MIN_HEIGHT = 18;
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;

    private int lastClickPanel = -1;
    private final Map<Path, Path> lastDirectory = new HashMap<>();
    private final Bookmarks bookmarks = new Bookmarks();
    private final RunHistory runHistory = new RunHistory();
    private int lastClickIndex = -1;
    private long lastClickTime;

    private Theme theme = initialTheme();
    private final PanelState[] panels;
    private final MenuBar menuBar = createMenuBar();
    private final CommandPalette palette = new CommandPalette(menuBar.menus());
    private final Map<Key, Command> commands = new HashMap<>();
    private int active;
    private String status = "Ready";
    private boolean running = true;
    private Screen screen;
    private UnixTty unixTty;
    private MultiWindowTextGUI gui;
    private FileSearch.Query lastFind;

    /** The progress window redraws about 20 times a second; without the pause the UI thread spun at 200+ redraws/s. */
    private static final int PROGRESS_INTERVAL_MS = 50;

    /** Optional start folders for the left and right panel; the current folder otherwise. */
    private App(String[] args) {
        Path home = Path.of(System.getProperty("user.dir"));
        panels = new PanelState[] {
            new PanelState(args.length > 0 ? Path.of(args[0]) : home),
            new PanelState(args.length > 1 ? Path.of(args[1]) : args.length > 0 ? Path.of(args[0]) : home)
        };
    }

    public static void main(String[] args) {
        org.ds.tuifileman.fs.JnaBoot.prepare();
        int exitCode = org.ds.tuifileman.cli.Cli.dispatch(args);
        if (exitCode >= 0) {
            System.exit(exitCode);
        }
        try {
            new App(args).run();
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                java.nio.file.Files.writeString(Path.of("target", "app-error.log"),
                        t + System.lineSeparator()
                                + java.util.Arrays.toString(t.getStackTrace()));
            } catch (IOException ignored) { }
            // A wrapper or agent that started the program must see the failure, not a clean exit.
            System.exit(org.ds.tuifileman.cli.Exit.FATAL);
        }
    }

    /**
     * The terminal is created directly, not through Lanterna's {@code DefaultTerminalFactory}: the factory
     * ignores the Ctrl+C setting for the Windows console (it exits the JVM; with this, Ctrl+C is a normal key
     * and the editor copies with it) and drags in the Swing/AWT terminal classes, which a native image
     * would have to carry along with their DLLs.
     */
    private static Terminal createTerminal() throws IOException {
        Terminal terminal = openTerminal();
        if (terminal instanceof com.googlecode.lanterna.terminal.ansi.StreamBasedTerminal stream) {
            stream.getInputDecoder().addProfile(enterKeys(terminal instanceof UnixTty));
        }
        return terminal;
    }

    /**
     * Lanterna reports Enter without modifiers. Terminals that can say more do it with xterm's modifyOtherKeys
     * (ESC [ 27 ; mod ; 13 ~) or the kitty protocol (ESC [ 13 ; mod u); and a plain line feed is Ctrl+Enter in the
     * Windows console and (with the tty's CR-to-LF translation switched off) in most terminals on Unix.
     */
    private static com.googlecode.lanterna.input.KeyDecodingProfile enterKeys(boolean unix) {
        var shift = new KeyStroke(KeyType.Enter, false, false, true);
        var ctrl = new KeyStroke(KeyType.Enter, true, false, false);
        List<com.googlecode.lanterna.input.CharacterPattern> patterns = new ArrayList<>(List.of(
                new com.googlecode.lanterna.input.BasicCharacterPattern(shift, '\u001b', '[', '2', '7', ';', '2', ';', '1', '3', '~'),
                new com.googlecode.lanterna.input.BasicCharacterPattern(ctrl, '\u001b', '[', '2', '7', ';', '5', ';', '1', '3', '~'),
                new com.googlecode.lanterna.input.BasicCharacterPattern(shift, '\u001b', '[', '1', '3', ';', '2', 'u'),
                new com.googlecode.lanterna.input.BasicCharacterPattern(ctrl, '\u001b', '[', '1', '3', ';', '5', 'u'),
                new com.googlecode.lanterna.input.BasicCharacterPattern(ctrl, '\n')));
        if (unix) {
            patterns.add(new com.googlecode.lanterna.input.BasicCharacterPattern(new KeyStroke(KeyType.Enter), '\r'));
        }
        return () -> patterns;
    }

    private static Terminal openTerminal() throws IOException {
        var utf8 = java.nio.charset.StandardCharsets.UTF_8;
        var ctrlC = UnixLikeTerminal.CtrlCBehaviour.TRAP;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return new com.googlecode.lanterna.terminal.win32.WindowsTerminal(System.in, System.out, utf8, ctrlC);
        }
        // System.out flushes after every write; Lanterna writes each escape sequence separately, so a full repaint
        // became thousands of tiny writes (very slow through the pty relay of WSL). Buffer it: Lanterna flushes
        // once per refresh, the direct writers (sixel, media) flush explicitly, and a few queries (terminal size)
        // are written without a flush and then wait for the reply, so any read of the input flushes first.
        PrintStream buffered = new PrintStream(new java.io.BufferedOutputStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), 1 << 16), false, utf8);
        System.setOut(buffered);
        Runtime.getRuntime().addShutdownHook(new Thread(buffered::flush));
        var input = new java.io.FilterInputStream(System.in) {
            @Override
            public int available() throws IOException {
                buffered.flush();
                return super.available();
            }

            @Override
            public int read() throws IOException {
                buffered.flush();
                return super.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                buffered.flush();
                return super.read(b, off, len);
            }
        };
        return new UnixTty(input, buffered, utf8, ctrlC);
    }

    /** Lanterna keeps the tty raw after stopScreen; a command that runs in this terminal needs echo and line editing. */
    private static final class UnixTty extends com.googlecode.lanterna.terminal.ansi.UnixTerminal {
        UnixTty(java.io.InputStream in, java.io.OutputStream out, java.nio.charset.Charset charset,
                UnixLikeTerminal.CtrlCBehaviour ctrlC) throws IOException {
            super(in, out, charset, ctrlC);
        }

        void cooked() throws IOException {
            restoreTerminalSettings();
        }

        void raw() throws IOException {
            keyEchoEnabled(false);
            canonicalMode(false);
            keyStrokeSignalsEnabled(false);
            freeFlowControlKeys();
        }

        /** Ctrl+S/Ctrl+Q are the editor's save and quit and Ctrl+Z is undo: the tty must not swallow or act on them.
         * Enter must arrive as a carriage return so that Ctrl+Enter (a line feed) is a different key. */
        void freeFlowControlKeys() throws IOException {
            runSTTYCommand("-ixon", "-isig", "-icrnl");
        }
    }

    private void run() throws IOException, InterruptedException {
        Terminal terminal = createTerminal();
        unixTty = terminal instanceof UnixTty tty ? tty : null;
        if (unixTty != null) {
            unixTty.freeFlowControlKeys();
        }
        if (terminal instanceof UnixLikeTerminal unixLike) {
            unixLike.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG);
        }
        registerCommands();
        try (Screen s = new TerminalScreen(terminal)) {
            screen = s;
            screen.startScreen();
            screen.setCursorPosition(null);
            for (PanelState panel : panels) {
                String error = panel.refresh();
                if (error != null) {
                    status = error;
                }
            }
            var configWarnings = org.ds.tuifileman.config.Config.get().warnings();
            if (!configWarnings.isEmpty()) {
                status = "Config " + org.ds.tuifileman.config.Config.defaultFile() + ": " + configWarnings.get(0);
            }
            String themeName = org.ds.tuifileman.config.Config.get().text("theme.name", "dark");
            if (Theme.find(themeName) == null) {
                status = "Config: unknown theme '" + themeName + "' (dark, light, contrast, mc); using dark";
            }
            render();
            while (running) {
                var newSize = screen.doResizeIfNecessary();
                KeyStroke key = screen.pollInput();
                if (key == null) {
                    if (newSize != null) {
                        render();
                    } else {
                        Thread.sleep(25);
                    }
                    continue;
                }
                dispatch(key);
                render();
            }
        } finally {
            Archives.closeAll();
        }
    }

    private void registerCommands() {
        commands.put(Key.of(KeyType.Tab), app -> app.active = 1 - app.active);
        commands.put(Key.of(KeyType.ArrowLeft), app -> app.active = 0);
        commands.put(Key.of(KeyType.ArrowRight), app -> app.active = 1);
        commands.put(Key.of(KeyType.ArrowUp), app -> app.panel().moveCursor(-1, app.visibleRows()));
        commands.put(Key.of(KeyType.ArrowDown), app -> app.panel().moveCursor(1, app.visibleRows()));
        commands.put(Key.of(KeyType.Home), app -> app.panel().cursorTo(0, app.visibleRows()));
        commands.put(Key.of(KeyType.End),
                app -> app.panel().cursorTo(app.panel().entries().size() - 1, app.visibleRows()));
        commands.put(Key.of(KeyType.PageUp),
                app -> app.panel().moveCursor(-app.visibleRows(), app.visibleRows()));
        commands.put(Key.of(KeyType.PageDown),
                app -> app.panel().moveCursor(app.visibleRows(), app.visibleRows()));
        commands.put(Key.of(KeyType.Enter), app -> app.openCurrent(app.panel()));
        commands.put(new Key(KeyType.Enter, null, true, false), App::openAsArchive);
        commands.put(new Key(KeyType.Enter, null, false, false, true), App::openAsArchive);
        commands.put(new Key(KeyType.PageDown, null, true, false), App::openAsArchive);
        commands.put(Key.of(KeyType.Backspace), app -> app.setStatus(app.panel().goUp()));
        commands.put(Key.ctrl('r'), app -> {
            app.setStatus(app.panels[0].refresh());
            String error = app.panels[1].refresh();
            if (error != null) {
                app.setStatus(error);
            }
        });
        commands.put(Key.of(KeyType.F2), App::multiRename);
        commands.put(Key.ctrl('z'), App::undoRename);
        commands.put(Key.of(KeyType.F3), App::viewFile);
        commands.put(Key.of(KeyType.F4), App::editFile);
        commands.put(new Key(KeyType.F4, null, false, false, true), App::newFile);
        commands.put(new Key(KeyType.F3, null, true, false), App::compareFiles);
        commands.put(Key.of(KeyType.F5), app -> app.copyOrMove(false));
        commands.put(Key.of(KeyType.F6), app -> app.copyOrMove(true));
        commands.put(Key.of(KeyType.F7), App::makeDirectory);
        commands.put(Key.of(KeyType.F8), App::deleteSelected);
        commands.put(Key.ch(' '), app -> app.panel().toggleMark(app.visibleRows(), true));
        commands.put(Key.of(KeyType.Insert), app -> app.panel().toggleMark(app.visibleRows(), false));
        commands.put(Key.ch('*'), app -> app.panel().invertMarks(true));
        commands.put(new Key(KeyType.Character, '*', true, false), app -> app.panel().invertMarks(false));
        commands.put(Key.of(KeyType.F9), app -> app.menuBar.toggle());
        commands.put(Key.ctrl('p'), app -> app.palette.open());
        commands.put(new Key(KeyType.F9, null, true, false), App::directorySync);
        commands.put(new Key(KeyType.F7, null, false, true), App::findFiles);
        for (MenuBar.Menu menu : menuBar.menus()) {
            char hotkey = menu.hotkey();
            commands.put(new Key(KeyType.Character, hotkey, false, true), app -> app.menuBar.toggleByHotkey(hotkey));
        }
        commands.put(Key.of(KeyType.F10), app -> app.running = false);
        commands.put(Key.of(KeyType.F12), App::showDemoProblems);
        commands.put(Key.ctrl('l'), App::goToPath);
        commands.put(Key.ctrl('d'), App::showBookmarks);
        commands.put(Key.ctrl('o'), App::runCommand);
        commands.put(Key.ctrl('c'), app -> app.copyToClipboard(false));
        commands.put(Key.ctrl('k'), app -> app.copyToClipboard(true));
        commands.put(new Key(KeyType.F1, null, false, true), app -> app.changeDrive(0));
        commands.put(new Key(KeyType.F2, null, false, true), app -> app.changeDrive(1));
    }

    private static MenuBar createMenuBar() {
        var sep = MenuBar.Item.separator();
        return new MenuBar(List.of(
                new MenuBar.Menu("Files", List.of(
                        new MenuBar.Item("Open", "Enter", app -> app.openCurrent(app.panel())),
                        new MenuBar.Item("Open as archive", "Ctrl+Enter", App::openAsArchive),
                        new MenuBar.Item("View", "F3", App::viewFile),
                        new MenuBar.Item("Edit", "F4", App::editFile),
                        new MenuBar.Item("New file...", "Shift+F4", App::newFile),
                        sep,
                        new MenuBar.Item("Copy", "F5", app -> app.copyOrMove(false)),
                        new MenuBar.Item("Move", "F6", app -> app.copyOrMove(true)),
                        new MenuBar.Item("Make directory", "F7", App::makeDirectory),
                        new MenuBar.Item("Delete", "F8", App::deleteSelected),
                        new MenuBar.Item("Multi-rename", "F2", App::multiRename),
                        sep,
                        new MenuBar.Item("Quit", "F10", app -> app.running = false))),
                new MenuBar.Menu("Commands", List.of(
                        new MenuBar.Item("Compare files", "Ctrl+F3", App::compareFiles),
                        new MenuBar.Item("Synchronize directories", "Ctrl+F9", App::directorySync),
                        new MenuBar.Item("Find files...", "Alt+F7", App::findFiles),
                        new MenuBar.Item("Copy name(s) to clipboard", "Ctrl+C", app -> app.copyToClipboard(false)),
                        new MenuBar.Item("Copy full path(s) to clipboard", "Ctrl+K", app -> app.copyToClipboard(true)),
                        new MenuBar.Item("Undo last rename", "Ctrl+Z", App::undoRename),
                        sep,
                        new MenuBar.Item("Bookmarks", "Ctrl+D", App::showBookmarks),
                        new MenuBar.Item("Go to path...", "Ctrl+L", App::goToPath),
                        new MenuBar.Item("Run command...", "Ctrl+O", App::runCommand),
                        new MenuBar.Item("Refresh", "Ctrl+R", App::refreshAll))),
                new MenuBar.Menu("Left", panelMenu(0, "Alt+F1")),
                new MenuBar.Menu("Right", panelMenu(1, "Alt+F2")),
                new MenuBar.Menu("Options", Theme.all().stream()
                        .map(t -> new MenuBar.Item("Theme: " + t.title(), "", app -> app.applyTheme(t)))
                        .toList()),
                new MenuBar.Menu("Help", List.of(
                        new MenuBar.Item("Command palette", "Ctrl+P", app -> app.palette.open()),
                        new MenuBar.Item("Keyboard shortcuts", "", App::showKeys)))));
    }

    /** The Left / Right menu. The sort items do what a click on the column header does (the same again reverses). */
    private static List<MenuBar.Item> panelMenu(int panel, String driveKey) {
        return List.of(
                new MenuBar.Item("Change drive...", driveKey, app -> app.changeDrive(panel)),
                new MenuBar.Item("Go to path...", "", app -> {
                    app.active = panel;
                    app.goToPath();
                }),
                MenuBar.Item.separator(),
                new MenuBar.Item("Sort by name", "", app -> app.sortPanel(panel, EntrySort.Key.NAME)),
                new MenuBar.Item("Sort by extension", "", app -> app.sortPanel(panel, EntrySort.Key.EXTENSION)),
                new MenuBar.Item("Sort by size", "", app -> app.sortPanel(panel, EntrySort.Key.SIZE)),
                new MenuBar.Item("Sort by modified time", "", app -> app.sortPanel(panel, EntrySort.Key.MODIFIED)),
                new MenuBar.Item("Sort by attributes", "", app -> app.sortPanel(panel, EntrySort.Key.ATTRIBUTES)));
    }

    private void sortPanel(int panel, EntrySort.Key key) {
        active = panel;
        panels[panel].sortBy(key, visibleRows());
        EntrySort sort = panels[panel].sort();
        status = (panel == 0 ? "Left" : "Right") + ": sorted by " + key.name().toLowerCase(Locale.ROOT)
                + (sort.descending() ? ", descending" : ", ascending");
    }

    private void showKeys() {
        MessageDialog dialog = new MessageDialogBuilder()
                .setTitle("Keyboard shortcuts")
                .setText("""
                        F9 / Alt+letter  menu            Tab       switch panel
                        Ctrl+P           palette         Backspace parent directory
                        Enter            open / run      Shift+F4  new file
                        Ctrl+Enter       as archive      Ctrl+L    go to path
                        F3 / F4          view / edit     F7 / F8   mkdir / delete
                        F5 / F6          copy / move     Ctrl+Z    undo rename
                        F2               multi-rename    Ctrl+F9   sync directories
                        Ctrl+F3          compare files   Ctrl+R    refresh
                        Ctrl+D           bookmarks       F12       problem list
                        Alt+F1 / Alt+F2  change drive    F10       quit
                        Space / Insert   mark            Ctrl+O    run command
                        *                invert marks    Ctrl+PgDn  open as archive
                        Ctrl+*           invert (files)  Ctrl+C    copy name(s)
                        type letters     quick search    Ctrl+K    copy path(s)
                        Alt+F7           find files
                        click a column header: sort by it, click again: reverse (.EXT = extension)""")
                .addButton(MessageDialogButton.Close)
                .build();
        dialog.setCloseWindowWithEscape(true);
        pad(dialog, 1);
        dialog.showDialog(gui());
    }

    private static Theme initialTheme() {
        Theme found = Theme.find(org.ds.tuifileman.config.Config.get().text("theme.name", "dark"));
        return found != null ? found : Theme.dark();
    }

    /** Switches the colours at once (dialogs included) and remembers the choice in the INI file. */
    private void applyTheme(Theme next) {
        theme = next;
        if (gui != null) {
            gui.setTheme(DialogTheme.create(next));
        }
        String error = org.ds.tuifileman.config.Config.get().save("theme.name", next.name());
        status = error == null ? "Theme: " + next.title() : "Theme: " + next.title() + " (not saved: " + error + ")";
    }

    /** Win+R style dialog: pick a shell, type a command (Up/Down recall history); a new window on Windows, this terminal on Linux. */
    private void runCommand() {
        PanelState target = panel();
        Path directory = Archives.realDirectory(target.directory());
        org.ds.tuifileman.config.Config config = org.ds.tuifileman.config.Config.get();
        List<RunCommand.Shell> shells = RunCommand.shells();
        com.googlecode.lanterna.gui2.ComboBox<String> shellBox = new com.googlecode.lanterna.gui2.ComboBox<>(
                shells.stream().map(RunCommand.Shell::name).toList());
        int configured = shells.stream().map(RunCommand.Shell::name).toList()
                .indexOf(config.text("run.shell", RunCommand.defaultShell()));
        shellBox.setSelectedIndex(Math.max(0, configured));
        int width = Math.max(30, Math.min(70, screen.getTerminalSize().getColumns() - 16));
        List<FileEntry> picked = target.targets();
        String prefill = picked.size() == 1 ? RunCommand.quoteName(picked.get(0).name()) : "";
        TextBox command = new TextBox(new TerminalSize(width, 1), prefill);
        command.setCaretPosition(prefill.length());
        boolean[] armed = {!prefill.isEmpty()};
        com.googlecode.lanterna.gui2.CheckBox keepOpen = new com.googlecode.lanterna.gui2.CheckBox(
                RunCommand.WINDOWS ? "Keep the window open after the command" : "Wait for Enter after the command");
        keepOpen.setChecked(config.flag("run.keep_open", true));
        List<String> history = runHistory.list();
        int[] position = {-1};
        String[] draft = {""};
        boolean[] go = {false};
        BasicWindow window = new BasicWindow("Run command") {
            @Override
            public boolean handleInput(KeyStroke key) {
                KeyType type = key.getKeyType();
                if (key.isAltDown() && (type == KeyType.ArrowUp || type == KeyType.ArrowDown)) {
                    int count = shells.size();
                    shellBox.setSelectedIndex((shellBox.getSelectedIndex() + (type == KeyType.ArrowDown ? 1 : count - 1)) % count);
                    return true;
                }
                if (armed[0] && getFocusedInteractable() == command) {
                    // the pre-filled name counts as selected: a printable first key replaces it, any other key keeps it
                    armed[0] = false;
                    if (replacesSelection(key, false)) {
                        command.setText("");
                    }
                }
                if (getFocusedInteractable() == command) {
                    if (type == KeyType.Enter) {
                        go[0] = true;
                        close();
                        return true;
                    }
                    if ((type == KeyType.ArrowUp || type == KeyType.ArrowDown) && !history.isEmpty()) {
                        if (position[0] < 0) {
                            draft[0] = command.getText();
                        }
                        position[0] = Math.max(-1, Math.min(history.size() - 1,
                                position[0] + (type == KeyType.ArrowUp ? 1 : -1)));
                        String text = position[0] < 0 ? draft[0] : history.get(position[0]);
                        command.setText(text);
                        command.setCaretPosition(text.length());
                        return true;
                    }
                }
                return super.handleInput(key);
            }
        };
        window.setCloseWindowWithEscape(true);
        Button ok = new Button("Run", () -> {
            go[0] = true;
            window.close();
        });
        Button cancel = new Button("Cancel", window::close);

        com.googlecode.lanterna.gui2.Panel form = new com.googlecode.lanterna.gui2.Panel(new GridLayout(2));
        form.addComponent(new Label("Shell"));
        form.addComponent(shellBox);
        form.addComponent(new Label("Command"));
        form.addComponent(command);
        com.googlecode.lanterna.gui2.Panel buttons = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.HORIZONTAL).setSpacing(2));
        buttons.addComponent(ok);
        buttons.addComponent(cancel);
        com.googlecode.lanterna.gui2.Panel content = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.VERTICAL).setSpacing(1));
        content.addComponent(new Label("In " + fit(directory.toString(), width + 8)));
        content.addComponent(form);
        content.addComponent(keepOpen);
        content.addComponent(new Label("Enter runs, Up/Down recall earlier commands, Alt+Up/Down change the shell.\nAn empty command just opens the shell here."));
        content.addComponent(buttons);
        window.setComponent(content);
        window.setHints(List.of(Window.Hint.CENTERED));
        pad(window, 2);
        window.setFocusedInteractable(command);
        gui().addWindowAndWait(window);
        if (!go[0]) {
            status = "Run cancelled";
            return;
        }

        RunCommand.Shell shell = shells.get(Math.max(0, shellBox.getSelectedIndex()));
        String text = command.getText();
        config.save("run.shell", shell.name());
        config.save("run.keep_open", String.valueOf(keepOpen.isChecked()));
        String historyError = runHistory.add(text);
        String result = RunCommand.WINDOWS ? RunCommand.run(shell, text, keepOpen.isChecked(), directory)
                : runInThisTerminal(shell, text, keepOpen.isChecked(), directory);
        status = historyError == null ? result : result + " (" + historyError + ")";
    }

    /** Linux: hands the terminal to the shell (like mc), waits, takes it back and repaints. */
    private String runInThisTerminal(RunCommand.Shell shell, String text, boolean pause, Path directory) {
        String windowsDirectory = shell.kind() == RunCommand.Kind.CMD ? RunCommand.windowsPath(directory) : null;
        RunCommand.Launch launch = RunCommand.launch(shell, text, windowsDirectory);
        String result;
        try {
            screen.stopScreen();
            if (unixTty != null) {
                unixTty.cooked();
            }
        } catch (IOException ignored) { }
        try {
            ProcessBuilder builder = new ProcessBuilder(launch.command()).directory(directory.toFile()).inheritIO();
            if (windowsDirectory != null && windowsDirectory.startsWith("\\\\") && Files.isDirectory(Path.of("/mnt/c"))) {
                // cmd cannot start in a \\wsl.localhost folder (it complains); its line does a pushd there anyway
                builder.directory(new java.io.File("/mnt/c"));
            }
            builder.environment().putAll(launch.environment());
            System.out.flush();
            int exit = builder.start().waitFor();
            result = text.isBlank() ? "Back from " + shell.name()
                    : (exit == 0 ? "Ran in " : "Exit code " + exit + " in ") + shell.name() + ": " + text.strip();
            if (pause && !text.isBlank()) {
                waitForEnter("[" + shell.name() + " exit " + exit + "] Press Enter to return");
            }
        } catch (IOException e) {
            result = "Cannot start " + shell.name() + ": " + e.getMessage();
            waitForEnter(result + " - press Enter to return");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "Run interrupted";
        }
        try {
            if (unixTty != null) {
                unixTty.raw();
            }
            screen.startScreen();
            screen.refresh(Screen.RefreshType.COMPLETE);
        } catch (IOException e) {
            result = "Cannot restore the screen: " + e.getMessage();
        }
        return result;
    }

    private static void waitForEnter(String prompt) {
        System.out.print(System.lineSeparator() + prompt);
        System.out.flush();
        try {
            int c;
            while ((c = System.in.read()) != -1 && c != '\n') {
                // the terminal is in its normal line mode here: wait for the line
            }
        } catch (IOException ignored) { }
    }

    /**
     * Ctrl+C / Ctrl+K: the names or full paths of the marked entries (the one under the cursor when none is
     * marked), one per line, on the system clipboard. Shift+Enter would be the Total Commander way, but Windows
     * Terminal does not report Shift with Enter.
     */
    private void copyToClipboard(boolean fullPath) {
        PanelState source = panel();
        List<FileEntry> items = source.targets();
        if (items.isEmpty()) {
            status = "Nothing to copy";
            return;
        }
        if (fullPath && source.isVirtual()) {
            status = "Inside an archive there is no real path to copy (names work)";
            return;
        }
        String text = items.stream().map(item -> fullPath ? item.path().toString() : item.name())
                .collect(java.util.stream.Collectors.joining("\n"));
        String what = fullPath ? "path" : "name";
        if (!org.ds.tuifileman.edit.SystemClipboard.set(text)) {
            status = RunCommand.WINDOWS ? "The system clipboard is not available"
                    : "The system clipboard is not available (install wl-clipboard, xclip or xsel)";
            return;
        }
        status = items.size() == 1 ? "Copied " + what + ": " + text : "Copied " + items.size() + " " + what + "s";
    }

    /** Go to a typed path - also the way into directories that can be entered but not listed. */
    private void goToPath() {
        PanelState target = panel();
        Path base = Archives.realDirectory(target.directory());
        TextInputDialog input = new TextInputDialogBuilder()
                .setTitle("Go to path")
                .setDescription("Directory:")
                .setInitialContent(base.toString())
                .setTextBoxSize(new TerminalSize(56, 1))
                .setValidator(text -> {
                    try {
                        if (text.isBlank()) {
                            return "Path cannot be empty";
                        }
                        base.resolve(text.strip());
                        return null;
                    } catch (InvalidPathException e) {
                        return "Invalid path";
                    }
                })
                .build();
        input.setCloseWindowWithEscape(true);
        confirmOnEnter(input);
        replaceOnFirstKey(input);
        pad(input, 1);
        String text = input.showDialog(gui());
        if (text != null) {
            setStatus(target.goTo(base.resolve(text.strip())));
        }
    }

    private void makeDirectory() {
        if (refuseInArchive("create a directory", panel())) {
            return;
        }
        PanelState target = panel();
        Path parent = target.directory();
        TextInputDialog input = new TextInputDialogBuilder()
                .setTitle("Create directory")
                .setDescription("Name of the new directory:")
                .setTextBoxSize(new TerminalSize(40, 1))
                .setValidator(name -> {
                    String error = FileOps.validateName(name);
                    if (error == null && Files.exists(parent.resolve(name.strip()))) {
                        error = "\"" + name.strip() + "\" already exists";
                    }
                    return error;
                })
                .build();
        input.setCloseWindowWithEscape(true);
        confirmOnEnter(input);
        pad(input, 1);
        String name = input.showDialog(gui());
        if (name == null) {
            status = "Cancelled";
            return;
        }
        name = name.strip();
        String error = FileOps.createDirectory(parent, name);
        refreshAll();
        if (error != null) {
            status = "Cannot create directory: " + error;
            return;
        }
        target.selectName(name, visibleRows());
        status = "Created directory " + name;
    }

    /** Dialogs only ever run modally, so a window is always active while GUI2 reads input. */
    private MultiWindowTextGUI gui() {
        if (gui == null) {
            gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new Backdrop());
            gui.setTheme(DialogTheme.create(theme));
        }
        return gui;
    }

    private record ConflictRequest(Path source, Path existing, CompletableFuture<CopyJob.Conflict> answer) { }

    private volatile ConflictRequest pendingConflict;

    private static boolean endsWithSeparator(String text) {
        String s = text.strip();
        return s.endsWith("\\") || s.endsWith("/");
    }

    private void copyOrMove(boolean move) {
        PanelState source = panel();
        if (move && refuseInArchive("move", source)) {
            return;
        }
        if (panels[1 - active].isVirtual()) {
            setStatus("The other panel shows an archive (read-only): cannot copy into it");
            return;
        }
        List<FileEntry> items = source.targets();
        if (items.isEmpty()) {
            status = "Nothing to " + (move ? "move" : "copy");
            return;
        }
        String verb = move ? "Move" : "Copy";
        Path base = Archives.realDirectory(source.directory());
        boolean single = items.size() == 1;
        String what = single
                ? "\"" + items.get(0).name() + "\""
                : items.size() + " items";
        TextInputDialog input = new TextInputDialogBuilder()
                .setTitle(verb)
                .setDescription(verb + " " + what + " to" + (single ? " (a folder, or a new name):" : ":"))
                .setInitialContent(panels[1 - active].directory().toString())
                .setTextBoxSize(new TerminalSize(56, 1))
                .setValidator(text -> {
                    if (text.isBlank()) {
                        return "Destination cannot be empty";
                    }
                    try {
                        Path path = base.resolve(text.strip());
                        // one item to an existing file is a copy/move under that name (the conflict dialog follows)
                        return Files.exists(path) && !Files.isDirectory(path) && !(single && !endsWithSeparator(text))
                                ? "Destination is a file" : null;
                    } catch (InvalidPathException e) {
                        return "Invalid path";
                    }
                })
                .build();
        input.setCloseWindowWithEscape(true);
        confirmOnEnter(input);
        replaceOnFirstKey(input);
        pad(input, 1);
        String text = input.showDialog(gui());
        if (text == null) {
            status = verb + " cancelled";
            return;
        }
        Path destination = base.resolve(text.strip()).normalize();
        String newName = null;
        // One item and no trailing separator: an existing folder receives it, anything else is the new name
        // (a trailing \ or / forces "a folder with this name").
        if (single && !endsWithSeparator(text) && !Files.isDirectory(destination) && destination.getParent() != null
                && destination.getFileName() != null) {
            newName = destination.getFileName().toString();
            destination = destination.getParent();
        }
        CopyJob job = new CopyJob(items, destination, move, this::askConflict);
        job.setTargetName(newName);
        job.setTimeTolerance(TimeTolerance.configured());
        runJob(job);
        if (!job.isCancelled()) {
            source.clearMarks();
        }
        refreshAll();
        if (newName != null && source.directory().equals(destination)) {
            source.selectName(newName, visibleRows());
        }
        status = job.summary();
        List<Job.Problem> errors = job.errors();
        if (!errors.isEmpty()) {
            showProblems(verb + " finished with " + errors.size()
                    + (errors.size() == 1 ? " problem" : " problems"), errors);
        }
    }

    /** Bookmark list (Ctrl+D): Enter jumps in the active panel, Insert/Ctrl+D adds the current directory, Delete removes. */
    private void showBookmarks() {
        PanelState target = panel();
        int selected = 0;
        String note = "";
        while (true) {
            List<Path> items = bookmarks.list();
            int width = Math.max(40, Math.min(80, screen.getTerminalSize().getColumns() - 12));
            int rows = Math.max(3, Math.min(12, items.size()));
            Path[] chosen = {null};
            int[] action = {0}; // 1 = add current directory, 2 = remove selected
            ActionListBox list = new ActionListBox(new TerminalSize(width, rows));
            BasicWindow window = new BasicWindow("Bookmarks") {
                @Override
                public boolean handleInput(KeyStroke key) {
                    boolean add = key.getKeyType() == KeyType.Insert
                            || (key.getKeyType() == KeyType.Character && key.isCtrlDown()
                                    && key.getCharacter() != null && Character.toLowerCase(key.getCharacter()) == 'd');
                    if (add || key.getKeyType() == KeyType.Delete) {
                        action[0] = add ? 1 : 2;
                        close();
                        return true;
                    }
                    if (key.getKeyType() == KeyType.Escape) {
                        close();
                        return true;
                    }
                    return super.handleInput(key);
                }
            };
            for (Path path : items) {
                list.addItem(fit(path.toString(), width - 2), () -> {
                    chosen[0] = path;
                    window.close();
                });
            }
            if (!items.isEmpty()) {
                list.setSelectedIndex(Math.min(selected, items.size() - 1));
            }
            window.setHints(List.of(Window.Hint.CENTERED));
            com.googlecode.lanterna.gui2.Panel content = new com.googlecode.lanterna.gui2.Panel(
                    new LinearLayout(Direction.VERTICAL).setSpacing(1));
            content.addComponent(new Label(fit(items.isEmpty()
                    ? "No bookmarks yet - press Insert to add the current directory"
                    : (target == panels[0] ? "Left" : "Right") + " panel: " + target.displayPath(), width)));
            content.addComponent(list);
            content.addComponent(new Label(note.isEmpty()
                    ? "Enter go   Insert add current dir   Delete remove   Esc close" : note));
            window.setComponent(content);
            pad(window, 1);
            gui().addWindowAndWait(window);

            if (chosen[0] != null) {
                setStatus(target.goTo(chosen[0]));
                return;
            }
            int index = list.getSelectedIndex();
            if (action[0] == 1) {
                Path here = Archives.realDirectory(target.directory());
                String error = bookmarks.add(here);
                note = error != null ? error : "Added " + fit(here.toString(), width - 6)
                        + (target.isVirtual() ? " (the folder of the archive)" : "");
                selected = bookmarks.list().indexOf(here.normalize());
            } else if (action[0] == 2 && !items.isEmpty()) {
                String error = bookmarks.remove(index);
                note = error != null ? error : "Removed";
                selected = index;
            } else {
                return;
            }
        }
    }

    /** Drive picker for one side (Alt+F1 / Alt+F2, or a click on the panel title). */
    private void changeDrive(int side) {
        PanelState target = panels[side];
        active = side;
        ActionListDialogBuilder builder = new ActionListDialogBuilder()
                .setTitle("Change drive")
                .setDescription((side == 0 ? "Left" : "Right") + " panel - current: "
                        + (target.isVirtual() ? "inside an archive" : target.directory().getRoot()));
        Path[] chosen = {null};
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            String label = "";
            String free = "not ready";
            try {
                FileStore store = Files.getFileStore(root);
                label = store.name() == null ? "" : store.name();
                free = FileEntry.humanSize(store.getUsableSpace()) + " free of "
                        + FileEntry.humanSize(store.getTotalSpace());
            } catch (IOException ignored) {
                // Empty card readers and the like are still listed.
            }
            String drive = root.toString().replaceAll("[\\\\/]+$", "");
            builder.addAction(String.format("%-4s %-18s %s", drive, fit(label, 18), free),
                    () -> chosen[0] = root);
        }
        ActionListDialog dialog = builder.build();
        dialog.setCloseWindowWithEscape(true);
        pad(dialog, 1);
        dialog.showDialog(gui());
        if (chosen[0] == null) {
            return;
        }
        Path current = target.directory();
        if (current.getRoot() != null && !target.isVirtual()) {
            lastDirectory.put(current.getRoot(), current);
        }
        Path destination = lastDirectory.getOrDefault(chosen[0], chosen[0]);
        String error = Files.isDirectory(destination) ? target.goTo(destination) : "gone";
        if (error != null) {
            error = target.goTo(chosen[0]);
        }
        setStatus(error);
    }

    /** Debug aid (F12): previews the problem list window with synthetic entries. */
    private void showDemoProblems() {
        String[] reasons = {"Path not found (Windows error 3)", "Access denied",
            "File is in use by another program (Windows error 32)", "Path too long (Windows error 206)"};
        List<Job.Problem> demo = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            demo.add(new Job.Problem("D:\\demo\\" + "folder".repeat(1 + i % 12) + "\\sub" + i + "\\file" + i + ".txt",
                    reasons[i % reasons.length]));
        }
        showProblems("Demo: " + demo.size() + " problems", demo);
    }

    /** Scrollable list of everything that failed; the complete list is also saved to a log file. */
    private void showProblems(String title, List<Job.Problem> problems) {
        TerminalSize screenSize = screen.getTerminalSize();
        int width = Math.max(40, Math.min(110, screenSize.getColumns() - 10));
        int height = Math.max(6, Math.min(18, screenSize.getRows() - 12));
        StringBuilder text = new StringBuilder();
        StringBuilder log = new StringBuilder();
        int number = 1;
        for (Job.Problem problem : problems) {
            text.append(number).append(". ").append(problem.reason()).append('\n');
            String path = problem.path();
            for (int i = 0; i < path.length(); i += width - 6) {
                text.append("     ").append(path, i, Math.min(path.length(), i + width - 6)).append('\n');
            }
            text.append('\n');
            log.append(problem.reason()).append('\t').append(problem.path()).append(System.lineSeparator());
            number++;
        }
        String logNote;
        try {
            Path file = Path.of(System.getProperty("java.io.tmpdir"), "tuifileman-errors.log");
            Files.writeString(file, log.toString());
            logNote = "Full list saved to " + file;
        } catch (IOException e) {
            logNote = "Could not save the log: " + e.getMessage();
        }

        BasicWindow window = new BasicWindow(title);
        window.setHints(List.of(Window.Hint.CENTERED));
        window.setCloseWindowWithEscape(true);
        TextBox list = new TextBox(new TerminalSize(width, height), text.toString().stripTrailing(),
                TextBox.Style.MULTI_LINE);
        list.setReadOnly(true);
        window.addWindowListener(new com.googlecode.lanterna.gui2.WindowListenerAdapter() {
            @Override
            public void onInput(Window w, KeyStroke key, java.util.concurrent.atomic.AtomicBoolean deliver) {
                if (key instanceof MouseAction mouse) {
                    KeyType arrow = switch (mouse.getActionType()) {
                        case SCROLL_UP -> KeyType.ArrowUp;
                        case SCROLL_DOWN -> KeyType.ArrowDown;
                        default -> null;
                    };
                    if (arrow != null) {
                        deliver.set(false);
                        for (int i = 0; i < 3; i++) {
                            list.handleInput(new KeyStroke(arrow));
                        }
                    }
                }
            }
        });
        Button ok = new Button("OK", window::close);
        ok.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End));
        com.googlecode.lanterna.gui2.Panel content = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.VERTICAL).setSpacing(1));
        content.addComponent(list);
        content.addComponent(new Label(fit(logNote, width)));
        content.addComponent(new Label("Up/Down/PgUp/PgDn/wheel scroll   Tab switch to OK   Esc close"));
        content.addComponent(ok);
        window.setComponent(content);
        pad(window, 2);
        gui().addWindowAndWait(window);
    }

    /**
     * Treats a pre-filled text box as if all of it were selected: a printable first key replaces the text, while a
     * cursor or edit key (or a path separator, meaning "append to this path") keeps it for editing.
     */
    private static void replaceOnFirstKey(TextInputDialog dialog) {
        boolean[] armed = {true};
        dialog.addWindowListener(new com.googlecode.lanterna.gui2.WindowListenerAdapter() {
            @Override
            public void onInput(Window window, KeyStroke key, java.util.concurrent.atomic.AtomicBoolean deliver) {
                if (!armed[0] || !(window.getFocusedInteractable() instanceof TextBox box)) {
                    return;
                }
                armed[0] = false;
                if (replacesSelection(key, true)) {
                    box.setText("");
                }
            }
        });
    }

    /** True for a printable key that replaces a pre-filled text; a path separator (when asked) means "append". */
    private static boolean replacesSelection(KeyStroke key, boolean separatorAppends) {
        if (key.getKeyType() != KeyType.Character || key.isCtrlDown() || key.isAltDown()) {
            return false;
        }
        return !(separatorAppends && (key.getCharacter() == '\\' || key.getCharacter() == '/'));
    }

    /** Enter in the text box confirms the dialog (Lanterna would only move focus to the OK button). */
    private static void confirmOnEnter(TextInputDialog dialog) {
        dialog.addWindowListener(new com.googlecode.lanterna.gui2.WindowListenerAdapter() {
            @Override
            public void onInput(Window window, KeyStroke key, java.util.concurrent.atomic.AtomicBoolean deliver) {
                if (key.getKeyType() != KeyType.Enter
                        || !(window.getFocusedInteractable() instanceof TextBox)) {
                    return;
                }
                Button ok = findOkButton(window.getComponent());
                if (ok != null) {
                    deliver.set(false);
                    window.setFocusedInteractable(ok);
                    window.handleInput(key);
                }
            }
        });
    }

    private static Button findOkButton(com.googlecode.lanterna.gui2.Component component) {
        if (component instanceof Button button) {
            return button.getLabel().equals(MessageDialogButton.OK.toString()) ? button : null;
        }
        if (component instanceof com.googlecode.lanterna.gui2.Container container) {
            for (com.googlecode.lanterna.gui2.Component child : container.getChildrenList()) {
                Button found = findOkButton(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Wraps the window content in an empty margin (horizontal cells are about half as tall as wide). */
    private static void pad(Window window, int horizontal) {
        com.googlecode.lanterna.gui2.Component inner = window.getComponent();
        window.setComponent(null);
        GridLayout layout = new GridLayout(1);
        layout.setLeftMarginSize(horizontal);
        layout.setRightMarginSize(horizontal);
        layout.setTopMarginSize(1);
        layout.setBottomMarginSize(1);
        com.googlecode.lanterna.gui2.Panel wrapper = new com.googlecode.lanterna.gui2.Panel(layout);
        inner.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, true));
        wrapper.addComponent(inner);
        window.setComponent(wrapper);
    }

    /** Called on the worker thread; the UI thread picks the request up in {@link #runJob}. */
    private CopyJob.Conflict askConflict(Path source, Path existing) {
        ConflictRequest request = new ConflictRequest(source, existing, new CompletableFuture<>());
        pendingConflict = request;
        try {
            return request.answer().get();
        } catch (InterruptedException | ExecutionException e) {
            return CopyJob.Conflict.CANCEL;
        }
    }

    private static String conflictLine(String label, Path file) {
        try {
            return label + String.format("%10s  ", FileEntry.humanSize(Files.size(file)))
                    + CONFLICT_DATE.format(Files.getLastModifiedTime(file).toInstant());
        } catch (IOException e) {
            return label + "unknown";
        }
    }

    private static final java.time.format.DateTimeFormatter CONFLICT_DATE =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());

    private void showConflictDialog(ConflictRequest request) {
        pendingConflict = null;
        String note = "";
        try {
            long delta = Files.getLastModifiedTime(request.source()).toMillis()
                    - Files.getLastModifiedTime(request.existing()).toMillis();
            note = Math.abs(delta) <= 2000 ? "  (same time)" : delta > 0 ? "  (newer)" : "  (older)";
        } catch (IOException ignored) {
            // The sizes and dates are only information.
        }
        ActionListDialog dialog = new ActionListDialogBuilder()
                .setTitle("Already exists")
                .setDescription("\"" + request.existing().getFileName() + "\" already exists in the destination.\n\n"
                        + conflictLine("New:      ", request.source()) + note + "\n"
                        + conflictLine("Existing: ", request.existing()))
                .addAction("Overwrite", () -> request.answer().complete(CopyJob.Conflict.OVERWRITE))
                .addAction("Skip", () -> request.answer().complete(CopyJob.Conflict.SKIP))
                .addAction("Keep both (rename the new one)", () -> request.answer().complete(CopyJob.Conflict.KEEP_BOTH))
                .addAction("Overwrite all", () -> request.answer().complete(CopyJob.Conflict.OVERWRITE_ALL))
                .addAction("Skip all", () -> request.answer().complete(CopyJob.Conflict.SKIP_ALL))
                .addAction("Keep both for all", () -> request.answer().complete(CopyJob.Conflict.KEEP_BOTH_ALL))
                .addAction("Overwrite all older (new one is newer)",
                        () -> request.answer().complete(CopyJob.Conflict.OVERWRITE_OLDER))
                .addAction("Overwrite all with different size",
                        () -> request.answer().complete(CopyJob.Conflict.OVERWRITE_SIZE_DIFFERS))
                .addAction("Overwrite all smaller (new one is larger)",
                        () -> request.answer().complete(CopyJob.Conflict.OVERWRITE_SMALLER))
                .addAction("Overwrite all larger (new one is smaller)",
                        () -> request.answer().complete(CopyJob.Conflict.OVERWRITE_LARGER))
                .addAction("Cancel", () -> request.answer().complete(CopyJob.Conflict.CANCEL))
                .build();
        dialog.setCloseWindowWithEscape(true);
        pad(dialog, 1);
        dialog.showDialog(gui());
        request.answer().complete(CopyJob.Conflict.CANCEL);
    }

    /** Runs the job on a worker thread while this thread drives a progress window. */
    private void runJob(Job job) {
        Label what = new Label("");
        Label stats = new Label("");
        ProgressBar bar = new ProgressBar(0, 1000, 56);
        BasicWindow window = new BasicWindow(job.title()) {
            @Override
            public boolean handleInput(KeyStroke key) {
                if (key.getKeyType() == KeyType.Escape) {
                    job.cancel();
                    return true;
                }
                return super.handleInput(key);
            }
        };
        window.setHints(List.of(Window.Hint.CENTERED));
        Button cancel = new Button("Cancel", job::cancel);
        cancel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End));
        com.googlecode.lanterna.gui2.Panel content = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.VERTICAL).setSpacing(1));
        content.addComponent(what);
        content.addComponent(bar);
        content.addComponent(stats);
        content.addComponent(cancel);
        window.setComponent(content);
        pad(window, 2);

        MultiWindowTextGUI g = gui();
        g.addWindow(window);
        Thread worker = new Thread(job::run, "copy-job");
        worker.setDaemon(true);
        worker.start();
        long start = System.nanoTime();
        try {
            while (!job.isDone()) {
                String name = job.current();
                what.setText(job.isCancelled() ? "Cancelling..."
                        : fit(job.phase() + "  " + name, 56));
                long total = job.bytesTotal();
                bar.setValue(total > 0 ? (int) Math.min(1000, job.bytesDone() * 1000 / total)
                        : job.filesTotal() > 0 ? job.filesDone() * 1000 / job.filesTotal() : 0);
                double seconds = Math.max(0.001, (System.nanoTime() - start) / 1e9);
                stats.setText(job.tracksBytes()
                        ? job.filesDone() + " / " + job.filesTotal() + " items   "
                                + FileEntry.humanSize(job.bytesDone()) + " / " + FileEntry.humanSize(total)
                                + "   " + FileEntry.humanSize((long) (job.bytesDone() / seconds)) + "/s"
                        : job.filesDone() + " / " + job.filesTotal() + " items   "
                                + String.format("%.0f", job.filesDone() / seconds) + " items/s");
                ConflictRequest request = pendingConflict;
                if (request != null) {
                    showConflictDialog(request);
                }
                g.getGUIThread().processEventsAndUpdate();
                Thread.sleep(PROGRESS_INTERVAL_MS);
            }
        } catch (IOException | InterruptedException e) {
            job.cancel();
            status = "Progress display failed: " + e;
        } finally {
            g.removeWindow(window);
        }
    }

    private List<Renamer.Applied> lastRename = List.of();

    /** Multi-rename (F2): marked entries, or everything in the directory when nothing is marked. */
    private void multiRename() {
        if (refuseInArchive("rename", panel())) {
            return;
        }
        PanelState target = panel();
        List<FileEntry> batch = target.markedOrAll();
        if (batch.isEmpty()) {
            status = "Nothing to rename";
            return;
        }
        Set<String> directoryNames = new java.util.HashSet<>();
        for (FileEntry entry : target.entries()) {
            directoryNames.add(entry.name());
        }
        TerminalSize screenSize = screen.getTerminalSize();
        int width = Math.max(60, Math.min(100, screenSize.getColumns() - 12));
        int previewRows = Math.max(5, Math.min(18, screenSize.getRows() - 25));

        TextBox nameMask = new TextBox(new TerminalSize(22, 1), "[N]");
        TextBox extensionMask = new TextBox(new TerminalSize(22, 1), "[E]");
        TextBox search = new TextBox(new TerminalSize(22, 1), "");
        TextBox replace = new TextBox(new TerminalSize(22, 1), "");
        com.googlecode.lanterna.gui2.CheckBox matchCase = new com.googlecode.lanterna.gui2.CheckBox("Match case");
        TextBox start = new TextBox(new TerminalSize(8, 1), "1");
        TextBox step = new TextBox(new TerminalSize(8, 1), "1");
        TextBox digits = new TextBox(new TerminalSize(8, 1), "1");
        com.googlecode.lanterna.gui2.ComboBox<String> caseMode = new com.googlecode.lanterna.gui2.ComboBox<>(
                "unchanged", "lower case", "UPPER CASE", "Capitalize Words", "First letter upper");
        com.googlecode.lanterna.gui2.ComboBox<String> caseScope = new com.googlecode.lanterna.gui2.ComboBox<>(
                "name + extension", "name only", "extension only");
        TextBox preview = new TextBox(new TerminalSize(width, previewRows), TextBox.Style.MULTI_LINE);
        preview.setReadOnly(true);
        Label summary = new Label("");
        RenamePlan[] plan = {null};

        String[] lastInput = {null};
        Runnable update = () -> {
            String input = String.join("\u0000", nameMask.getText(), extensionMask.getText(),
                    search.getText(), replace.getText(), String.valueOf(matchCase.isChecked()),
                    start.getText(), step.getText(), digits.getText(),
                    String.valueOf(caseMode.getSelectedIndex()), String.valueOf(caseScope.getSelectedIndex()));
            if (input.equals(lastInput[0])) {
                return;
            }
            lastInput[0] = input;
            RenamePlan.Settings settings;
            try {
                settings = new RenamePlan.Settings(nameMask.getText(), extensionMask.getText(),
                        Long.parseLong(start.getText().strip()), Long.parseLong(step.getText().strip()),
                        Math.max(1, Math.min(255, Integer.parseInt(digits.getText().strip()))),
                        search.getText(), replace.getText(), matchCase.isChecked(),
                        RenamePlan.CaseMode.values()[Math.max(0, caseMode.getSelectedIndex())],
                        RenamePlan.CaseScope.values()[Math.max(0, caseScope.getSelectedIndex())]);
            } catch (NumberFormatException e) {
                plan[0] = null;
                summary.setText("Counter start, step and digits must be whole numbers");
                preview.setText("");
                return;
            }
            plan[0] = RenamePlan.create(batch, directoryNames, settings);
            int column = Math.max(12, (width - 8) / 2);
            StringBuilder text = new StringBuilder();
            for (RenamePlan.Item item : plan[0].items()) {
                String line = (item.error() != null ? "! " : item.changed() ? "  " : "= ")
                        + String.format("%-" + column + "s", fit(item.entry().name(), column))
                        + " \u2192 " + (item.newName().isBlank() ? "<" + (item.newName().isEmpty() ? "empty" : "spaces") + ">" : item.newName())
                        + (item.error() != null ? "   [" + item.error() + "]" : "");
                text.append(fit(line, width - 1)).append('\n');
            }
            preview.setText(text.toString());
            summary.setText(batch.size() + " items, " + plan[0].changedCount() + " will change"
                    + (plan[0].errorCount() > 0 ? ", " + plan[0].errorCount() + " with problems (!)" : ""));
        };
        boolean[] confirmed = {false};
        Button[] ok = {null};
        BasicWindow window = new BasicWindow("Multi-rename  -  "
                + (target.markedCount() > 0 ? "marked items" : "all items in directory")) {
            @Override
            public boolean handleInput(KeyStroke key) {
                if (key.getKeyType() == KeyType.Enter && getFocusedInteractable() instanceof TextBox box
                        && box != preview) {
                    setFocusedInteractable(ok[0]);
                }
                boolean handled = super.handleInput(key);
                update.run();
                return handled;
            }
        };
        ok[0] = new Button("Rename", () -> {
            if (plan[0] == null || plan[0].errorCount() > 0) {
                summary.setText("Cannot rename yet - fix the lines marked with !");
            } else if (plan[0].changedCount() == 0) {
                summary.setText("Nothing would change");
            } else {
                confirmed[0] = true;
                window.close();
            }
        });
        Button cancel = new Button("Cancel", window::close);
        window.setCloseWindowWithEscape(true);

        com.googlecode.lanterna.gui2.Panel masks = new com.googlecode.lanterna.gui2.Panel(new GridLayout(2));
        masks.addComponent(new Label("Name mask"));
        masks.addComponent(nameMask);
        masks.addComponent(new Label("Extension mask"));
        masks.addComponent(extensionMask);
        masks.addComponent(new Label("Search"));
        masks.addComponent(search);
        masks.addComponent(new Label("Replace with"));
        masks.addComponent(replace);
        masks.addComponent(new com.googlecode.lanterna.gui2.EmptySpace());
        masks.addComponent(matchCase);
        com.googlecode.lanterna.gui2.Panel options = new com.googlecode.lanterna.gui2.Panel(new GridLayout(2));
        options.addComponent(new Label("Counter start"));
        options.addComponent(start);
        options.addComponent(new Label("Counter step"));
        options.addComponent(step);
        options.addComponent(new Label("Counter digits"));
        options.addComponent(digits);
        options.addComponent(new Label("Change case"));
        options.addComponent(caseMode);
        options.addComponent(new Label("Case applies to"));
        options.addComponent(caseScope);
        com.googlecode.lanterna.gui2.Panel fields = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.HORIZONTAL).setSpacing(4));
        fields.addComponent(masks);
        fields.addComponent(options);

        com.googlecode.lanterna.gui2.Panel buttons = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.HORIZONTAL).setSpacing(2));
        buttons.addComponent(ok[0]);
        buttons.addComponent(cancel);

        com.googlecode.lanterna.gui2.Panel content = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.VERTICAL).setSpacing(1));
        content.addComponent(fields);
        content.addComponent(new Label("[N] name  [E] extension  [N2-5] [N3-] character range  [C] counter\n"
                + "[P] parent folder  [Y] [M] [D] [h] [m] [s] modified time  [YMD] [hms]\n"
                + "Search: * any text, ? any one character; every match in the new name is replaced"));
        content.addComponent(preview);
        content.addComponent(summary);
        content.addComponent(buttons);
        window.setComponent(content);
        window.setHints(List.of(Window.Hint.CENTERED));
        pad(window, 2);
        update.run();
        gui().addWindowAndWait(window);
        if (!confirmed[0]) {
            status = "Multi-rename cancelled";
            return;
        }

        List<Renamer.Move> moves = new java.util.ArrayList<>();
        for (RenamePlan.Item item : plan[0].items()) {
            if (item.changed()) {
                moves.add(new Renamer.Move(item.entry().path(), item.newName()));
            }
        }
        Renamer.Result result = Renamer.apply(moves);
        lastRename = result.applied();
        refreshAll();
        if (!result.applied().isEmpty()) {
            target.selectName(result.applied().get(0).now().getFileName().toString(), visibleRows());
        }
        status = "Renamed " + result.applied().size() + " of " + moves.size() + " items (Ctrl+Z undoes)";
        if (!result.problems().isEmpty()) {
            showProblems(result.problems().size() + " problems while renaming", result.problems());
        }
    }

    /** F3: opens the file under the cursor in the viewer. */
    private void viewFile() {
        FileEntry entry = panel().current();
        if (entry == null || "..".equals(entry.name())) {
            setStatus("View: put the cursor on a file");
        } else if (entry.directory()) {
            setStatus("View: " + entry.name() + " is a directory");
        } else {
            Path path = entry.path();
            try (Archives.Extracted file = Archives.extract(path)) {
                setStatus(org.ds.tuifileman.view.FileView.show(screen, theme, file.path()));
            } catch (IOException e) {
                setStatus("View: cannot read " + entry.name() + ": " + e.getMessage());
            }
        }
    }

    /** Enter / double click: enters a directory, opens a file (an archive too) with its associated program. */
    private void openCurrent(PanelState target) {
        FileEntry entry = target.current();
        if (entry != null && !entry.directory() && !"..".equals(entry.name())) {
            launch(entry);
        } else {
            setStatus(target.enter());
        }
    }

    /** Ctrl+Enter / Ctrl+PageDown / Files menu: browses any file as a read-only zip archive. */
    private void openAsArchive() {
        FileEntry entry = panel().current();
        if (entry == null || entry.directory() || "..".equals(entry.name())) {
            setStatus("Open as archive: put the cursor on a file");
        } else {
            setStatus(panel().openArchive(entry.path()));
        }
    }

    private void launch(FileEntry entry) {
        try {
            // A file inside an archive is extracted first; the copy stays until the program exits.
            setStatus(Launcher.open(Archives.extractKept(entry.path())));
        } catch (IOException e) {
            setStatus("Open: cannot extract " + entry.name() + ": " + e.getMessage());
        }
    }

    /** Archives are browsed, viewed and copied out of, never changed: tells the user and returns true. */
    private boolean refuseInArchive(String action, PanelState... states) {
        for (PanelState state : states) {
            if (state.isVirtual()) {
                setStatus("An archive is read-only: cannot " + action + (action.startsWith("copy") ? "" : " (F5 copies out of it)"));
                return true;
            }
        }
        return false;
    }

    /** F4: edits the file under the cursor. */
    private void editFile() {
        if (refuseInArchive("edit here", panel())) {
            return;
        }
        FileEntry entry = panel().current();
        if (entry == null || "..".equals(entry.name())) {
            setStatus("Edit: put the cursor on a file");
        } else if (entry.directory()) {
            setStatus("Edit: " + entry.name() + " is a directory");
        } else {
            setStatus(org.ds.tuifileman.edit.EditorView.edit(screen, theme, entry.path(), false));
            refreshAll();
        }
    }

    /** Shift+F4: asks for a name and edits that (new) file in the active panel's directory. */
    private void newFile() {
        if (refuseInArchive("create a file", panel())) {
            return;
        }
        PanelState target = panel();
        Path parent = target.directory();
        TextInputDialog input = new TextInputDialogBuilder()
                .setTitle("Edit new file")
                .setDescription("Name of the file to edit (created when saved):")
                .setTextBoxSize(new TerminalSize(40, 1))
                .setValidator(FileOps::validateName)
                .build();
        input.setCloseWindowWithEscape(true);
        confirmOnEnter(input);
        pad(input, 1);
        String name = input.showDialog(gui());
        if (name == null) {
            status = "Cancelled";
            return;
        }
        name = name.strip();
        setStatus(org.ds.tuifileman.edit.EditorView.edit(screen, theme, parent.resolve(name), true));
        refreshAll();
        target.selectName(name, visibleRows());
    }

    /** Ctrl+F3: compares the two marked files of the active panel, or the cursor files of both panels. */
    private void compareFiles() {
        FileEntry left;
        FileEntry right;
        if (panel().markedCount() == 2) {
            List<FileEntry> pair = panel().targets();
            left = pair.get(0);
            right = pair.get(1);
        } else {
            left = panels[0].current();
            right = panels[1].current();
        }
        if (left == null || right == null || "..".equals(left.name()) || "..".equals(right.name())) {
            setStatus("Compare: put the cursor on a file in both panels, or mark two files");
        } else if (left.directory() && right.directory()) {
            setStatus("Compare: these are directories - directory sync (Ctrl+F9) to compare directories");
        } else if (left.directory() || right.directory()) {
            setStatus("Compare: cannot compare a file with a directory");
        } else if (left.path().equals(right.path())) {
            setStatus("Compare: both panels point at the same file");
        } else {
            try (Archives.Extracted a = Archives.extract(left.path());
                 Archives.Extracted b = Archives.extract(right.path())) {
                setStatus(org.ds.tuifileman.compare.CompareView.show(screen, theme, a.path(), b.path()));
            } catch (IOException e) {
                setStatus("Compare: cannot read the file: " + e.getMessage());
            }
        }
    }

    /** Ctrl+F9: compares the two panel directories and lets the user review and run the proposed copies. */
    private void directorySync() {
        if (refuseInArchive("synchronize with it", panels[0], panels[1])) {
            return;
        }
        Path left = panels[0].directory();
        Path right = panels[1].directory();
        try {
            if (Files.isSameFile(left, right)) {
                setStatus("Sync: both panels show the same directory");
                return;
            }
        } catch (IOException ignored) { }
        SyncView view = new SyncView(screen, theme, left, right);
        while (view.run() == SyncView.Result.EXECUTE) {
            MessageDialog dialog = new MessageDialogBuilder()
                    .setTitle("Synchronize")
                    .setText(view.describe() + "\n\nProceed?")
                    .addButton(MessageDialogButton.Yes)
                    .addButton(MessageDialogButton.No)
                    .build();
            dialog.setCloseWindowWithEscape(true);
            pad(dialog, 1);
            if (dialog.showDialog(gui()) != MessageDialogButton.Yes) {
                continue;
            }
            SyncJob job = new SyncJob(left, right, view.items());
            runJob(job);
            refreshAll();
            status = job.summary();
            List<Job.Problem> errors = job.errors();
            if (!errors.isEmpty()) {
                showProblems("Sync finished with " + errors.size()
                        + (errors.size() == 1 ? " problem" : " problems"), errors);
            }
            return;
        }
        refreshAll();
        status = "Sync closed";
    }

    /** Alt+F7: asks what to look for, then lists the matches while the search runs on several threads. */
    private void findFiles() {
        FileSearch.Query query = askFind(lastFind, Archives.realDirectory(panel().directory()));
        while (query != null) {
            lastFind = query;
            FindView view = new FindView(screen, theme, query);
            FindView.Result result;
            while ((result = view.run()) == FindView.Result.VIEW) {
                viewHit(view.selected());
            }
            if (result == FindView.Result.GO) {
                FileSearch.Hit hit = view.selected();
                setStatus(panel().reveal(hit.archive(), hit.folder(), hit.name(), visibleRows()));
                return;
            }
            if (result == FindView.Result.CLOSE) {
                status = "Find closed";
                return;
            }
            query = askFind(query, query.root());
        }
        status = "Find cancelled";
    }

    /** The find dialog; null when cancelled. {@code previous} (may be null) supplies the other fields. */
    private FileSearch.Query askFind(FileSearch.Query previous, Path root) {
        int width = Math.max(30, Math.min(60, screen.getTerminalSize().getColumns() - 30));
        TextBox where = new TextBox(new TerminalSize(width, 1), root.toString());
        TextBox names = new TextBox(new TerminalSize(width, 1), previous == null ? "*" : previous.names());
        TextBox text = new TextBox(new TerminalSize(width, 1), previous == null ? "" : previous.text());
        TextBox depth = new TextBox(new TerminalSize(8, 1), previous == null
                || previous.maxDepth() == FileSearch.UNLIMITED ? "" : String.valueOf(previous.maxDepth()));
        com.googlecode.lanterna.gui2.CheckBox archives = new com.googlecode.lanterna.gui2.CheckBox("Search in archives");
        archives.setChecked(previous != null && previous.inArchives());
        com.googlecode.lanterna.gui2.CheckBox matchCase = new com.googlecode.lanterna.gui2.CheckBox("Match case (text)");
        matchCase.setChecked(previous != null && previous.matchCase());
        Label note = new Label("");
        FileSearch.Query[] result = {null};
        Runnable[] submit = {null};
        BasicWindow window = new BasicWindow("Find files") {
            @Override
            public boolean handleInput(KeyStroke key) {
                if (key.getKeyType() == KeyType.Enter && getFocusedInteractable() instanceof TextBox) {
                    submit[0].run();
                    return true;
                }
                return super.handleInput(key);
            }
        };
        submit[0] = () -> {
            Path folder;
            try {
                String typed = where.getText().strip();
                folder = root.resolve(typed.isEmpty() ? "." : typed).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                note.setText("Search in: not a valid path");
                return;
            }
            if (!Files.isDirectory(folder)) {
                note.setText("Search in: not a folder");
                return;
            }
            int levels = FileSearch.UNLIMITED;
            String limit = depth.getText().strip();
            if (!limit.isEmpty()) {
                try {
                    levels = Integer.parseInt(limit);
                } catch (NumberFormatException e) {
                    levels = 0;
                }
                if (levels < 1) {
                    note.setText("Depth: a whole number from 1 (this folder only), or empty for no limit");
                    return;
                }
            }
            result[0] = new FileSearch.Query(folder, names.getText(), archives.isChecked(), text.getText(),
                    matchCase.isChecked(), levels);
            window.close();
        };
        Button search = new Button("Search", () -> submit[0].run());
        Button cancel = new Button("Cancel", window::close);
        window.setCloseWindowWithEscape(true);

        com.googlecode.lanterna.gui2.Panel fields = new com.googlecode.lanterna.gui2.Panel(new GridLayout(2));
        fields.addComponent(new Label("Search in"));
        fields.addComponent(where);
        fields.addComponent(new Label("File names"));
        fields.addComponent(names);
        fields.addComponent(new Label("Containing text"));
        fields.addComponent(text);
        fields.addComponent(new Label("Max depth"));
        fields.addComponent(depth);
        fields.addComponent(new com.googlecode.lanterna.gui2.EmptySpace());
        fields.addComponent(archives);
        fields.addComponent(new com.googlecode.lanterna.gui2.EmptySpace());
        fields.addComponent(matchCase);
        com.googlecode.lanterna.gui2.Panel buttons = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.HORIZONTAL).setSpacing(2));
        buttons.addComponent(search);
        buttons.addComponent(cancel);
        com.googlecode.lanterna.gui2.Panel content = new com.googlecode.lanterna.gui2.Panel(
                new LinearLayout(Direction.VERTICAL).setSpacing(1));
        content.addComponent(fields);
        content.addComponent(new Label("Names: * any text, ? any one character; several patterns separated by , or ;\n"
                + "a pattern without * or ? matches names that contain it; empty finds everything\n"
                + "Depth: empty = all levels, 1 = only the folder itself   Archives: zip, jar, war, apk ..."));
        content.addComponent(note);
        content.addComponent(buttons);
        window.setComponent(content);
        window.setHints(List.of(Window.Hint.CENTERED));
        pad(window, 2);
        gui().addWindowAndWait(window);
        return result[0];
    }

    /** F3 on a hit of the find list: views the file, also when it is inside an archive; the find prompt holds the searched text. */
    private void viewHit(FileSearch.Hit hit) {
        String text = hit.line() > 0 && lastFind != null ? lastFind.text() : "";
        try {
            if (hit.archive() == null) {
                setStatus(org.ds.tuifileman.view.FileView.show(screen, theme, Path.of(hit.folder(), hit.name()), text));
                return;
            }
            Path root = Archives.open(Path.of(hit.archive()));
            try (Archives.Extracted file = Archives.extract(root.getFileSystem().getPath(hit.folder(), hit.name()))) {
                setStatus(org.ds.tuifileman.view.FileView.show(screen, theme, file.path(), text));
            } finally {
                Archives.release(root);
            }
        } catch (IOException | RuntimeException e) {
            setStatus("View: cannot read " + hit.name() + ": " + e.getMessage());
        }
    }

    /** Ctrl+Z: reverts the last multi-rename of this session. */
    private void undoRename() {
        if (lastRename.isEmpty()) {
            status = "Nothing to undo";
            return;
        }
        Renamer.Result result = Renamer.apply(Renamer.undoMoves(lastRename));
        int total = lastRename.size();
        lastRename = List.of();
        refreshAll();
        status = "Undid " + result.applied().size() + " of " + total + " renames";
        if (!result.problems().isEmpty()) {
            showProblems(result.problems().size() + " problems while undoing", result.problems());
        }
    }

    private static String fit(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "…";
    }

    private void deleteSelected() {
        if (refuseInArchive("delete", panel())) {
            return;
        }
        PanelState target = panel();
        List<FileEntry> victims = target.targets();
        if (victims.isEmpty()) {
            status = "Nothing to delete";
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(victims.size() == 1
                ? "Delete \"" + victims.get(0).name() + "\""
                        + (victims.get(0).directory() ? " and everything in it?" : "?")
                : "Delete " + victims.size() + " items?");
        if (victims.size() > 1) {
            for (int i = 0; i < Math.min(5, victims.size()); i++) {
                text.append("\n  ").append(victims.get(i).name());
            }
            if (victims.size() > 5) {
                text.append("\n  ... and ").append(victims.size() - 5).append(" more");
            }
        }
        MessageDialog confirm = new MessageDialogBuilder()
                .setTitle("Delete")
                .setText(text.toString())
                .addButton(MessageDialogButton.No)
                .addButton(MessageDialogButton.Yes)
                .build();
        confirm.setCloseWindowWithEscape(true);
        pad(confirm, 1);
        if (confirm.showDialog(gui()) != MessageDialogButton.Yes) {
            status = "Delete cancelled";
            return;
        }
        DeleteJob job = new DeleteJob(victims);
        runJob(job);
        int cursorBefore = target.cursor();
        if (!job.isCancelled()) {
            target.clearMarks();
        }
        refreshAll();
        target.cursorTo(cursorBefore, visibleRows());
        status = job.summary();
        List<Job.Problem> errors = job.errors();
        if (!errors.isEmpty()) {
            showProblems("Delete finished with " + errors.size()
                    + (errors.size() == 1 ? " problem" : " problems"), errors);
        }
    }

    private void refreshAll() {
        for (PanelState p : panels) {
            String error = p.refresh();
            if (error != null) {
                status = error;
            }
        }
    }

    private void dispatch(KeyStroke key) {
        if (key.getKeyType() == KeyType.EOF) {
            running = false;
            return;
        }
        if (key.getKeyType() == KeyType.MouseEvent) {
            panel().searchClear();
            int columns = screen.getTerminalSize().getColumns();
            if (palette.handleMouse((MouseAction) key, columns) || menuBar.handleMouse((MouseAction) key, columns)) {
                runChosenMenuItem();
                return;
            }
            handleMouse((MouseAction) key);
            return;
        }
        if (palette.handleKey(key) || menuBar.handleKey(key)) {
            runChosenMenuItem();
            return;
        }
        if (handleSearchKey(key)) {
            return;
        }
        
        Command command = commands.get(Key.from(key));
        if (command != null) {
            command.execute(this);
        }
    }

    /** Runs the item picked in the menu or palette; both are already closed so the action may open dialogs. */
    private void runChosenMenuItem() {
        Command chosen = palette.takeChosen();
        if (chosen == null) {
            chosen = menuBar.takeChosen();
        }
        if (chosen != null) {
            chosen.execute(this);
        }
    }

    /**
     * Quick search: plain characters narrow the cursor to matching names. Space only counts once a
     * search has started (otherwise it marks). Any non-search key ends the search and then acts normally.
     */
    private boolean handleSearchKey(KeyStroke key) {
        PanelState target = panel();
        boolean plain = key.getKeyType() == KeyType.Character && !key.isCtrlDown() && !key.isAltDown()
                && key.getCharacter() != null && !Character.isISOControl(key.getCharacter());
        if (plain && (target.searching() || (key.getCharacter() != ' ' && key.getCharacter() != '*'))) {
            target.searchAppend(key.getCharacter(), visibleRows());
            return true;
        }
        if (!target.searching()) {
            return false;
        }
        switch (key.getKeyType()) {
            case Backspace -> {
                target.searchBackspace(visibleRows());
                return true;
            }
            case Escape -> {
                target.searchClear();
                return true;
            }
            case ArrowUp -> {
                target.searchStep(-1, visibleRows());
                return true;
            }
            case ArrowDown -> {
                target.searchStep(1, visibleRows());
                return true;
            }
            default -> {
                target.searchClear();
                return false;
            }
        }
    }

    private void handleMouse(MouseAction mouse) {
        int width = screen.getTerminalSize().getColumns();
        int height = screen.getTerminalSize().getRows();
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return;
        }
        if (mouse.getActionType() == com.googlecode.lanterna.input.MouseActionType.CLICK_DOWN) {
            int row = mouse.getPosition().getRow();
            if (row == height - 4 || row == height - 3) {
                Hint hint = hintAt(row == height - 4 ? HINTS_PRIMARY : HINTS_SECONDARY,
                        mouse.getPosition().getColumn(), width - 2);
                if (hint != null) {
                    hint.action().execute(this);
                }
                return;
            }
        }
        Panel.Rect left = leftRect(width, height);
        Panel.Rect right = rightRect(width, height);
        int panel = mouse.getPosition().getColumn() < right.x() ? 0 : 1;
        Panel.Rect rect = panel == 0 ? left : right;
        switch (mouse.getActionType()) {
            case CLICK_DOWN -> {
                if (mouse.getPosition().getRow() == rect.y()) {
                    changeDrive(panel);
                    return;
                }
                EntrySort.Key sortKey = Panel.headerAt(panels[panel], rect, mouse.getPosition().getColumn(),
                        mouse.getPosition().getRow());
                if (sortKey != null) {
                    sortPanel(panel, sortKey);
                    return;
                }
                active = panel;
                int index = panels[panel].first()
                        + mouse.getPosition().getRow() - rect.firstEntryRow();
                if (mouse.getPosition().getRow() >= rect.firstEntryRow()
                        && mouse.getPosition().getRow() < rect.firstEntryRow() + rect.visibleRows()
                        && index >= 0 && index < panels[panel].entries().size()) {
                    panels[panel].cursorTo(index, rect.visibleRows());
                    long now = System.nanoTime();
                    if (panel == lastClickPanel && index == lastClickIndex
                            && now - lastClickTime < DOUBLE_CLICK_NANOS) {
                        openCurrent(panels[panel]);
                        lastClickIndex = -1;
                    } else {
                        lastClickPanel = panel;
                        lastClickIndex = index;
                        lastClickTime = now;
                    }
                }
            }
            case SCROLL_UP -> panels[panel].moveCursor(-3, rect.visibleRows());
            case SCROLL_DOWN -> panels[panel].moveCursor(3, rect.visibleRows());
            default -> { }
        }
    }

    private PanelState panel() {
        return panels[active];
    }

    private void setStatus(String message) {
        if (message == null) {
            return;
        }
        status = message;
        if (message.startsWith("Cannot open")) {
            MessageDialog dialog = new MessageDialogBuilder()
                    .setTitle("Cannot open")
                    .setText(message.substring("Cannot open".length()).stripLeading())
                    .addButton(MessageDialogButton.OK)
                    .build();
            dialog.setCloseWindowWithEscape(true);
            pad(dialog, 1);
            dialog.showDialog(gui());
        }
    }

    private int visibleRows() {
        return Math.max(1, screen.getTerminalSize().getRows() - 15);
    }

    private static Panel.Rect leftRect(int width, int height) {
        return new Panel.Rect(1, 1, (width - 5) / 2, height - 6);
    }

    private static Panel.Rect rightRect(int width, int height) {
        int x = (width - 5) / 2 + 3;
        return new Panel.Rect(x, 1, width - x - 1, height - 6);
    }

    private void render() throws IOException {
        drawMainView(screen.newTextGraphics());
        screen.setCursorPosition(null);
        screen.refresh();
    }

    /** Draws the whole main view; also used as the backdrop behind GUI2 dialogs. */
    private void drawMainView(TextGraphics g) {
        int width = g.getSize().getColumns();
        int height = g.getSize().getRows();
        g.setBackgroundColor(theme.appBackground());
        g.setForegroundColor(theme.text());
        g.fill(' ');
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            menuBar.close();
            palette.close();
            put(g, 1, 1, width - 2, "Resize to at least " + MIN_WIDTH + " x " + MIN_HEIGHT
                    + ". F10 quits.", theme.accent(), theme.appBackground());
            return;
        }
        Panel.render(g, theme, panels[0], leftRect(width, height), active == 0);
        Panel.render(g, theme, panels[1], rightRect(width, height), active == 1);
        menuBar.render(g, theme, width);
        palette.render(g, theme, width);

        drawHints(g, height - 4, HINTS_PRIMARY, theme.text(), width - 2);
        drawHints(g, height - 3, HINTS_SECONDARY, theme.muted(), width - 2);
        put(g, 2, height - 2, width - 4, status, theme.muted(), theme.appBackground());
    }

    /** A clickable entry of the two key-hint rows above the status line. */
    private record Hint(String key, String label, Command action) {
        int width() {
            return key.length() + 1 + label.length();
        }
    }

    private static final int HINT_GAP = 3;

    private static final List<Hint> HINTS_PRIMARY = List.of(
            new Hint("F2", "rename", App::multiRename),
            new Hint("F3", "view", App::viewFile),
            new Hint("F4", "edit", App::editFile),
            new Hint("F5", "copy", app -> app.copyOrMove(false)),
            new Hint("F6", "move", app -> app.copyOrMove(true)),
            new Hint("F7", "mkdir", App::makeDirectory),
            new Hint("F8", "del", App::deleteSelected),
            new Hint("F9", "menu", app -> app.menuBar.toggle()),
            new Hint("F10", "quit", app -> app.running = false));

    private static final List<Hint> HINTS_SECONDARY = List.of(
            new Hint("Ctrl+F3", "compare", App::compareFiles),
            new Hint("Ctrl+F9", "sync", App::directorySync),
            new Hint("Shift+F4", "new file", App::newFile),
            new Hint("Alt+F7", "find", App::findFiles),
            new Hint("Ctrl+D", "bookmarks", App::showBookmarks),
            new Hint("Ctrl+L", "go to", App::goToPath),
            new Hint("Ctrl+O", "run", App::runCommand),
            new Hint("Ctrl+P", "palette", app -> app.palette.open()),
            new Hint("Alt+F1/F2", "drive", app -> app.changeDrive(app.active)));

    private void drawHints(TextGraphics g, int y, List<Hint> hints, com.googlecode.lanterna.TextColor labelColor,
                           int rightEdge) {
        int x = 2;
        for (Hint hint : hints) {
            if (x >= rightEdge) {
                break;
            }
            put(g, x, y, rightEdge - x, hint.key(), theme.accent(), theme.appBackground());
            put(g, x + hint.key().length(), y, rightEdge - x - hint.key().length(), " " + hint.label(),
                    labelColor, theme.appBackground());
            x += hint.width() + HINT_GAP;
        }
    }

    /** The hint under the column, or null. */
    private static Hint hintAt(List<Hint> hints, int column, int rightEdge) {
        int x = 2;
        for (Hint hint : hints) {
            if (column >= x && column < Math.min(x + hint.width(), rightEdge)) {
                return hint;
            }
            x += hint.width() + HINT_GAP;
        }
        return null;
    }

    /** Non-interactive GUI2 component that paints the main view behind dialogs. */
    private final class Backdrop extends AbstractComponent<Backdrop> {
        @Override
        protected ComponentRenderer<Backdrop> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(Backdrop component) {
                    return new TerminalSize(MIN_WIDTH, MIN_HEIGHT);
                }

                @Override
                public void drawComponent(TextGUIGraphics graphics, Backdrop component) {
                    drawMainView(graphics);
                }
            };
        }
    }

    private void put(TextGraphics g, int x, int y, int width,
                     String value, com.googlecode.lanterna.TextColor fg,
                     com.googlecode.lanterna.TextColor bg) {
        if (width <= 0) {
            return;
        }
        String clipped = value.length() <= width ? value : value.substring(0, width);
        g.setForegroundColor(fg);
        g.setBackgroundColor(bg);
        g.putString(new TerminalPosition(x, y), clipped);
    }
}
