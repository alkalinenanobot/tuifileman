package org.ds.tuifileman.cli;

import org.ds.tuifileman.cli.Console.ColorMode;
import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.config.Config;
import org.ds.tuifileman.ui.Theme;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The headless entry point. {@link #dispatch} is called before any terminal UI is created and answers
 * "-1" when this is not a command-line invocation (no arguments, or folders to open), so the TUI starts
 * exactly as before. A command is selected by the first argument, or by the name of the executable:
 * a copy named {@code tfm-diff.exe} (or just {@code diff.exe}) runs {@code diff}.
 */
public final class Cli {
    interface Command {
        int run(List<String> args);
    }

    static final String NAME = "tuifileman";
    static final String VERSION = "0.3.0";
    private static final Set<String> IMPLEMENTED = Set.of("diff", "compare", "copy", "move", "delete", "sync", "rename", "find",
            AliasCommand.COMMAND);
    /** Planned commands: recognised so that a typo or an early try gets a clear message, not a folder error. */
    private static final Set<String> PLANNED = Set.of();

    static final AtomicBoolean CANCEL = new AtomicBoolean();
    private static final CountDownLatch FINISHED = new CountDownLatch(1);

    private Cli() { }

    /** Runs the command-line mode if the arguments ask for it; returns its exit code, or -1 for "start the TUI". */
    public static int dispatch(String[] args) {
        Trace.at("main entered");
        List<String> list = List.of(args);
        String alias = commandFromExecutable();
        if (alias != null) {
            return execute(alias, list);
        }
        if (list.isEmpty()) {
            return -1;
        }
        String first = list.get(0);
        switch (first) {
            case "--help", "-h", "/?" -> {
                return execute("help", List.of());
            }
            case "--version", "-V" -> {
                return execute("version", List.of());
            }
            default -> { }
        }
        if (isCommandName(first)) {
            return execute(first.toLowerCase(Locale.ROOT), list.subList(1, list.size()));
        }
        return checkStartFolders(list);
    }

    /** The TUI takes up to two start folders; anything else (a mistyped command, a typo'd path) is an error. */
    private static int checkStartFolders(List<String> list) {
        if (list.size() > 2) {
            System.err.println(NAME + ": too many arguments; the file manager takes at most two start folders");
            return Exit.USAGE;
        }
        for (String argument : list) {
            if (argument.startsWith("-") && argument.length() > 1) {
                System.err.println(NAME + ": unknown option " + argument + " (try '" + NAME + " --help')");
                return Exit.USAGE;
            }
            boolean folder;
            try {
                folder = Files.isDirectory(Path.of(argument));
            } catch (InvalidPathException e) {
                folder = false;
            }
            if (!folder) {
                String guess = closestCommand(argument);
                System.err.println(NAME + ": '" + argument + "' is not a command or an existing folder"
                        + (guess == null ? "" : "; did you mean '" + guess + "'?")
                        + " (try '" + NAME + " --help')");
                return Exit.USAGE;
            }
        }
        return -1;
    }

    /** A command name within two edits of the argument, for "did you mean". */
    private static String closestCommand(String argument) {
        String word = argument.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = 3;
        for (String name : new java.util.TreeSet<>(IMPLEMENTED)) {
            int distance = editDistance(word, name);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = name;
            }
        }
        return best;
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1], previous[j]) + 1, previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    /** {@code copy} is a command, {@code .\copy} and {@code D:\copy} are folders. */
    private static boolean isCommandName(String argument) {
        if (argument.startsWith(".") || argument.contains("\\") || argument.contains("/") || argument.contains(":")) {
            return false;
        }
        String name = argument.toLowerCase(Locale.ROOT);
        return IMPLEMENTED.contains(name) || PLANNED.contains(name) || name.equals("help") || name.equals("version");
    }

    /** The command a renamed executable stands for: {@code tfm-diff.exe} and {@code diff.exe} give "diff". */
    private static String commandFromExecutable() {
        try {
            String command = ProcessHandle.current().info().command().orElse(null);
            if (command == null) {
                return null;
            }
            String stem = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
            int dot = stem.lastIndexOf('.');
            if (dot > 0) {
                stem = stem.substring(0, dot);
            }
            if (isCommandName(stem)) {
                return stem;
            }
            int cut = Math.max(stem.lastIndexOf('-'), stem.lastIndexOf('_'));
            String last = cut >= 0 ? stem.substring(cut + 1) : stem;
            return isCommandName(last) && !last.equals("help") && !last.equals("version") ? last : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int execute(String name, List<String> args) {
        installCancelHook();
        int code;
        try {
            code = switch (name) {
                case "diff", "compare" -> new DiffCommand().run(args);
                case "copy" -> new OpCommand(OpCommand.Kind.COPY).run(args);
                case "move" -> new OpCommand(OpCommand.Kind.MOVE).run(args);
                case "delete" -> new OpCommand(OpCommand.Kind.DELETE).run(args);
                case "sync" -> new SyncCommand().run(args);
                case "rename" -> new RenameCommand().run(args);
                case "find" -> new FindCommand().run(args);
                case AliasCommand.COMMAND -> new AliasCommand().run(args);
                case "help" -> {
                    help(args);
                    yield Exit.OK;
                }
                case "version" -> {
                    System.out.println(NAME + " " + version());
                    yield Exit.OK;
                }
                default -> {
                    System.err.println(NAME + ": the '" + name + "' command is not available yet; "
                            + "use the file manager (run " + NAME + " without a command)");
                    yield Exit.USAGE;
                }
            };
        } catch (RuntimeException | Error e) {
            System.err.println(NAME + ": internal error: " + e);
            code = Exit.FATAL;
        } finally {
            FINISHED.countDown();
        }
        return CANCEL.get() ? Exit.CANCELLED : code;
    }

    /**
     * Ctrl+C: ask the running command to stop, give it a few seconds to tidy up (erase the progress bar,
     * restore the console), then leave with 130. {@code halt} because the normal exit path would wait for
     * this very hook.
     */
    private static void installCancelHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (FINISHED.getCount() == 0) {
                return;
            }
            CANCEL.set(true);
            try {
                FINISHED.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // leaving anyway
            }
            Runtime.getRuntime().halt(Exit.CANCELLED);
        }, "cancel"));
    }

    static String version() {
        return VERSION;
    }

    /** The colour palette for terminal output: {@code --theme}, else the one chosen in the INI file. */
    static Theme theme(String requested) {
        Theme found = Theme.find(requested != null ? requested : Config.get().text("theme.name", "dark"));
        return found != null ? found : Theme.dark();
    }

    static ColorMode colorMode(String text) throws Args.UsageException {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "auto" -> ColorMode.AUTO;
            case "always" -> ColorMode.ALWAYS;
            case "never" -> ColorMode.NEVER;
            default -> throw new Args.UsageException("--color must be auto, always or never");
        };
    }

    static Progress.Mode progressMode(String text) throws Args.UsageException {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "auto" -> Progress.Mode.AUTO;
            case "bar" -> Progress.Mode.BAR;
            case "lines" -> Progress.Mode.LINES;
            case "json" -> Progress.Mode.JSON;
            case "none" -> Progress.Mode.NONE;
            default -> throw new Args.UsageException("--progress must be auto, bar, lines, json or none");
        };
    }

    /** A usage error: message and a pointer to the help, on stderr, exit code 2. */
    static int usageError(String command, String message) {
        Console console = Console.open(ColorMode.AUTO, theme(null));
        console.err().println(console.err().style(Style.ERROR, "error:") + " " + message);
        console.err().println(console.err().style(Style.MUTED, "try '" + NAME + " " + command + " --help'"));
        console.close();
        return Exit.USAGE;
    }

    private static void help(List<String> args) {
        if (!args.isEmpty()) {
            String topic = args.get(0).toLowerCase(Locale.ROOT);
            if (IMPLEMENTED.contains(topic)) {
                execute(topic, List.of("--help"));
                return;
            }
        }
        Console console = Console.open(ColorMode.AUTO, theme(null));
        Console.Out out = console.out();
        out.println(out.style(Style.TITLE, NAME) + " " + out.style(Style.MUTED, version())
                + "  two-panel file manager and file tools");
        out.println();
        out.println(out.style(Style.BOLD, "Usage"));
        out.println("  " + NAME + " [LEFT [RIGHT]]       open the file manager");
        out.println("  " + NAME + " COMMAND [OPTIONS]   run a command without the interface");
        out.println();
        out.println(out.style(Style.BOLD, "Commands"));
        out.println("  " + out.style(Style.ACCENT, "diff") + "      compare two folders (alias: compare)");
        out.println("  " + out.style(Style.ACCENT, "copy") + "      copy files and folders into a folder");
        out.println("  " + out.style(Style.ACCENT, "move") + "      move files and folders into a folder");
        out.println("  " + out.style(Style.ACCENT, "delete") + "    delete files and folders");
        out.println("  " + out.style(Style.ACCENT, "sync") + "      synchronize two folders (merge or mirror, with plans)");
        out.println("  " + out.style(Style.ACCENT, "rename") + "    multi-rename with masks, counter, search/replace and case (with undo)");
        out.println("  " + out.style(Style.ACCENT, "find") + "      find files by name patterns and content, optionally inside archives");
        out.println("  " + out.style(Style.ACCENT, "install-aliases") + "  make tfm-diff.exe, tfm-copy.exe ... next to the program");
        out.println();
        out.println("Run '" + NAME + " COMMAND --help' for the options of a command. "
                + "A copy of the program named tfm-COMMAND.exe runs that command directly.");
        console.close();
    }
}
