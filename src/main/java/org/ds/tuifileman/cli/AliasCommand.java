package org.ds.tuifileman.cli;

import org.ds.tuifileman.cli.Console.Style;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code install-aliases}: makes {@code tfm-diff.exe}, {@code tfm-copy.exe} and so on next to the program,
 * so that each command can be started by its own name. They are hard links (no extra disk space); a copy is
 * made with {@code --copy} where the file system has no hard links. A link points at the file it was made
 * from, so run this again with {@code --force} after the program was replaced by a new build.
 */
final class AliasCommand implements Cli.Command {
    static final String COMMAND = "install-aliases";
    static final List<String> NAMES = List.of("diff", "copy", "move", "delete", "sync", "rename", "find", "cat");

    private enum Format { TEXT, JSON, NONE }

    /** {@code state}: created, replaced, exists, same, removed, kept, missing, failed. */
    private record Result(Path path, String state, String reason) { }

    @Override
    public int run(List<String> arguments) {
        Args args = new Args()
                .option("dir", 'd', true)
                .option("exe", (char) 0, true)
                .option("prefix", 'p', true)
                .option("force", (char) 0, false)
                .option("copy", (char) 0, false)
                .option("remove", (char) 0, false)
                .option("dry-run", 'n', false)
                .option("format", 'f', true)
                .option("quiet", 'q', false)
                .option("color", (char) 0, true)
                .option("theme", (char) 0, true)
                .option("help", 'h', false);
        Format format;
        Console.ColorMode color;
        Path exe;
        Path dir;
        String prefix;
        try {
            args.parse(arguments);
            if (args.has("help")) {
                return help(args.get("theme", null));
            }
            if (!args.positional().isEmpty()) {
                throw new Args.UsageException("unexpected argument: " + args.positional().get(0));
            }
            format = switch (args.get("format", "text").toLowerCase(Locale.ROOT)) {
                case "text" -> Format.TEXT;
                case "json" -> Format.JSON;
                case "none" -> Format.NONE;
                default -> throw new Args.UsageException("--format must be text, json or none");
            };
            color = Cli.colorMode(args.get("color", "auto"));
            prefix = args.get("prefix", "tfm-");
            if (prefix.isEmpty() || prefix.contains("\\") || prefix.contains("/") || prefix.contains(":")) {
                throw new Args.UsageException("--prefix must be a plain file name start, e.g. tfm-");
            }
            if (args.has("copy") && args.has("remove")) {
                throw new Args.UsageException("--copy and --remove cannot be combined");
            }
            exe = executable(args);
            dir = args.has("dir") ? Path.of(args.get("dir", "")).toAbsolutePath().normalize() : exe.getParent();
            if (!Files.isDirectory(dir)) {
                throw new Args.UsageException("not a folder: " + dir);
            }
        } catch (Args.UsageException | java.nio.file.InvalidPathException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }

        Console console = Console.open(color, Cli.theme(args.get("theme", null)));
        try {
            return args.has("remove") ? remove(args, console, format, exe, dir, prefix)
                    : install(args, console, format, exe, dir, prefix);
        } finally {
            console.close();
        }
    }

    /** The program to link: {@code --exe}, else the running native program (a JVM run has none). */
    private static Path executable(Args args) throws Args.UsageException {
        if (args.has("exe")) {
            Path path = Path.of(args.get("exe", "")).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new Args.UsageException("--exe is not a file: " + path);
            }
            return path;
        }
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null) {
            Path path = Path.of(command).toAbsolutePath().normalize();
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.startsWith("java")) {
                return path;
            }
        }
        throw new Args.UsageException("this is not the native program (java is running); "
                + "run it as tuifileman.exe, or name the program with --exe");
    }

    private int install(Args args, Console console, Format format, Path exe, Path dir, String prefix) {
        boolean dryRun = args.has("dry-run");
        boolean force = args.has("force");
        List<Result> results = new ArrayList<>();
        String suffix = exe.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe") ? ".exe" : "";
        for (String name : NAMES) {
            Path target = dir.resolve(prefix + name + suffix);
            if (target.equals(exe)) {
                results.add(new Result(target, "failed", "that is the program itself"));
                continue;
            }
            results.add(create(exe, target, force, args.has("copy"), dryRun));
        }
        return finish(console, format, "install-aliases", dryRun, results, args.has("quiet"));
    }

    private static Result create(Path exe, Path target, boolean force, boolean copy, boolean dryRun) {
        boolean present = Files.exists(target);
        try {
            if (present && sameContent(exe, target)) {
                return new Result(target, "same", "");
            }
            if (present && !force) {
                return new Result(target, "exists", "a different file is there; --force replaces it");
            }
            if (dryRun) {
                return new Result(target, present ? "replaced" : "created", "");
            }
            if (present) {
                Files.delete(target);
            }
            if (copy) {
                Files.copy(exe, target);
            } else {
                Files.createLink(target, exe);
            }
            return new Result(target, present ? "replaced" : "created", "");
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            return new Result(target, "failed", copy ? reason : reason + " (try --copy)");
        }
    }

    private int remove(Args args, Console console, Format format, Path exe, Path dir, String prefix) {
        boolean dryRun = args.has("dry-run");
        List<Result> results = new ArrayList<>();
        String suffix = exe.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe") ? ".exe" : "";
        for (String name : NAMES) {
            Path target = dir.resolve(prefix + name + suffix);
            if (!Files.exists(target)) {
                results.add(new Result(target, "missing", ""));
                continue;
            }
            try {
                // only what this command could have made: a link to, or a copy of, the program
                if (target.equals(exe) || !sameContent(exe, target)) {
                    results.add(new Result(target, "kept", "not a link or copy of the program"));
                    continue;
                }
                if (!dryRun) {
                    Files.delete(target);
                }
                results.add(new Result(target, "removed", ""));
            } catch (IOException e) {
                results.add(new Result(target, "failed", e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }
        return finish(console, format, "remove-aliases", dryRun, results, args.has("quiet"));
    }

    private static boolean sameContent(Path a, Path b) throws IOException {
        return Files.isSameFile(a, b) || Files.size(a) == Files.size(b) && Files.mismatch(a, b) < 0;
    }

    private int finish(Console console, Format format, String command, boolean dryRun, List<Result> results,
                       boolean quiet) {
        Console.Out out = console.out();
        Console.Out err = console.err();
        int problems = 0;
        for (Result result : results) {
            if (result.state().equals("failed") || result.state().equals("exists") || result.state().equals("kept")) {
                problems++;
            }
        }
        switch (format) {
            case TEXT -> {
                for (Result result : results) {
                    out.println(String.format("%-9s", result.state()) + " " + result.path());
                }
            }
            case JSON -> {
                StringBuilder text = new StringBuilder("{\"schema\":1,\"command\":\"").append(command)
                        .append("\",\"dry_run\":").append(dryRun).append(",\"status\":\"")
                        .append(problems == 0 ? "ok" : "errors").append("\",\"aliases\":[");
                for (int i = 0; i < results.size(); i++) {
                    Result result = results.get(i);
                    text.append(i == 0 ? "" : ",").append("{\"path\":").append(Json.quote(result.path().toString()))
                            .append(",\"state\":\"").append(result.state()).append('"');
                    if (!result.reason().isEmpty()) {
                        text.append(",\"reason\":").append(Json.quote(result.reason()));
                    }
                    text.append('}');
                }
                out.println(text.append("]}").toString());
            }
            default -> { }
        }
        out.flush();
        for (Result result : results) {
            if (!result.reason().isEmpty()) {
                Style style = result.state().equals("failed") ? Style.ERROR : Style.WARN;
                err.println(err.style(style, result.state() + ":") + " " + result.path()
                        + err.style(Style.MUTED, " - " + result.reason()));
            }
        }
        if (!quiet) {
            long done = results.stream().filter(r -> r.state().equals("created") || r.state().equals("replaced")
                    || r.state().equals("removed")).count();
            err.println((dryRun ? "dry run - would change " : "changed ") + done + " of " + results.size() + " aliases");
        }
        return problems == 0 ? Exit.OK : Exit.PARTIAL;
    }

    private int help(String themeName) {
        Console console = Console.open(Console.ColorMode.AUTO, Cli.theme(themeName));
        Console.Out out = console.out();
        String[][] options = {
            {"-d, --dir DIR", "where to make the aliases (default: the folder of the program)"},
            {"    --exe FILE", "the program to link (default: the running tuifileman.exe)"},
            {"-p, --prefix TEXT", "start of the alias names (default tfm-)"},
            {"    --force", "replace a different file of the same name"},
            {"    --copy", "copy the program instead of a hard link"},
            {"    --remove", "delete the aliases (only links to, or copies of, the program)"},
            {"-n, --dry-run", "list what would change"},
            {"-f, --format FORMAT", "text (default), json or none; written to stdout"},
            {"-q, --quiet", "no summary on stderr"},
            {"    --color WHEN", "auto, always or never (NO_COLOR is honoured)"},
            {"    --theme NAME", "dark, light, contrast or mc (default: from the INI file)"},
            {"-h, --help", "this help"},
        };
        out.println(out.style(Style.TITLE, Cli.NAME + " " + COMMAND) + "  "
                + out.style(Style.MUTED, "make tfm-diff.exe, tfm-copy.exe ... next to the program"));
        out.println();
        out.println(out.style(Style.BOLD, "Options"));
        for (String[] option : options) {
            out.println("  " + out.style(Style.ACCENT, String.format("%-22s", option[0])) + " " + option[1]);
        }
        out.println();
        out.println(out.style(Style.BOLD, "Aliases  ") + "  " + String.join(", ", NAMES.stream().map(n -> "tfm-" + n).toList()));
        out.println(out.style(Style.BOLD, "Note     ") + "  a hard link keeps the program it was made from: after a new build run this again with --force");
        out.println(out.style(Style.BOLD, "Exit     ") + "  0 ok, 2 usage, 3 something was not done (exists, failed)");
        console.close();
        return Exit.OK;
    }
}
