package org.ds.tuifileman.cli;

import org.ds.tuifileman.app.Job;
import org.ds.tuifileman.app.RenamePlan;
import org.ds.tuifileman.app.Renamer;
import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.model.FileEntry;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code rename FILE...}: the multi-rename of the file manager (F2), for scripts. Same masks, counter,
 * search/replace and case options. All items must be in one folder; the counter runs in the order given
 * (a wildcard expands sorted by name). A plan with an invalid or clashing name changes nothing. Every real
 * run asks on a terminal and needs {@code --yes} elsewhere; {@code --undo-log} records what was renamed and
 * {@code rename --undo FILE} reverts it.
 */
final class RenameCommand implements Cli.Command {
    private static final String COMMAND = "rename";

    private enum Format { TEXT, JSON, JSONL, CSV, NONE }

    /** One planned or finished rename; {@code error} is set for a name that cannot be used. */
    private record Line(Path from, String to, boolean directory, String error) { }

    private record Report(boolean dryRun, boolean undo, int renamed, int unchanged, long elapsedMs, List<Line> lines,
                          List<Job.Problem> errors) { }

    @Override
    public int run(List<String> arguments) {
        Args args = new Args()
                .option("name", 'N', true)
                .option("ext", 'e', true)
                .option("start", (char) 0, true)
                .option("step", (char) 0, true)
                .option("digits", (char) 0, true)
                .option("search", 's', true)
                .option("replace", 'r', true)
                .option("match-case", (char) 0, false)
                .option("case", (char) 0, true)
                .option("case-scope", (char) 0, true)
                .option("dry-run", 'n', false)
                .option("yes", 'y', false)
                .option("undo-log", 'u', true)
                .option("undo", (char) 0, true)
                .option("format", 'f', true)
                .option("quiet", 'q', false)
                .option("color", (char) 0, true)
                .option("theme", (char) 0, true)
                .option("help", 'h', false);
        Format format;
        Console.ColorMode color;
        RenamePlan.Settings settings = null;
        List<Path> sources = new ArrayList<>();
        try {
            args.parse(arguments);
            if (args.has("help")) {
                return help(args.get("theme", null));
            }
            format = format(args.get("format", "text"));
            color = Cli.colorMode(args.get("color", "auto"));
            if (args.has("undo")) {
                for (String option : List.of("name", "ext", "start", "step", "digits", "search", "replace", "case", "undo-log")) {
                    if (args.has(option)) {
                        throw new Args.UsageException("--" + option + " cannot be combined with --undo");
                    }
                }
                if (!args.positional().isEmpty()) {
                    throw new Args.UsageException("--undo takes the log file only, not files to rename");
                }
            } else {
                if (args.positional().isEmpty()) {
                    throw new Args.UsageException("nothing to rename: give files or wildcards (a folder's content: DIR\\*)");
                }
                settings = settings(args);
                OpCommand.collect(args.positional(), sources, false);
                checkSources(sources);
                if (args.has("undo-log") && Files.exists(logPath(args.get("undo-log", "")))) {
                    throw new Args.UsageException("the undo log exists already: " + args.get("undo-log", "")
                            + " (delete it or choose another file)");
                }
            }
        } catch (Args.UsageException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }

        Console console = Console.open(color, Cli.theme(args.get("theme", null)));
        try {
            return args.has("undo") ? undo(args, console, format) : rename(args, console, format, settings, sources);
        } finally {
            console.close();
        }
    }

    // ---- rename -----------------------------------------------------------------------------------------------

    private int rename(Args args, Console console, Format format, RenamePlan.Settings settings, List<Path> sources) {
        Console.Out err = console.err();
        boolean quiet = args.has("quiet");
        Path folder = sources.get(0).getParent();
        List<FileEntry> batch = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try {
            for (Path source : sources) {
                batch.add(entry(source));
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                for (Path path : stream) {
                    names.add(path.getFileName().toString());
                }
            }
        } catch (IOException e) {
            return fail(err, Exit.FATAL, "cannot read " + e.getMessage());
        }

        RenamePlan plan = RenamePlan.create(batch, names, settings);
        List<Line> lines = new ArrayList<>();
        int unchanged = 0;
        int invalid = 0;
        for (RenamePlan.Item item : plan.items()) {
            if (item.error() != null) {
                invalid++;
            } else if (!item.changed()) {
                unchanged++;
                continue;
            }
            lines.add(new Line(item.entry().path(), item.newName(), item.entry().directory(), item.error()));
        }
        boolean dryRun = args.has("dry-run");
        if (invalid > 0 || dryRun || lines.isEmpty()) {
            Report report = new Report(dryRun, false, 0, unchanged, 0, lines, List.of());
            int code = invalid > 0 ? Exit.USAGE : Exit.OK;
            code = finish(console, format, report, code, quiet);
            if (invalid > 0) {
                err.println(err.style(Style.ERROR, "error:") + " " + invalid + (invalid == 1 ? " name is" : " names are")
                        + " not usable; nothing was renamed");
            }
            return code;
        }

        if (!args.has("yes")) {
            int declined = confirm(err, folder, lines);
            if (declined >= 0) {
                return declined;
            }
        }
        BufferedWriter log = null;
        if (args.has("undo-log")) {
            try {
                log = Files.newBufferedWriter(Path.of(args.get("undo-log", "")).toAbsolutePath().normalize(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException e) {
                return fail(err, Exit.FATAL, "cannot create the undo log, nothing was renamed: " + e.getMessage());
            }
        }

        long started = System.nanoTime();
        List<Renamer.Move> moves = new ArrayList<>();
        for (Line line : lines) {
            moves.add(new Renamer.Move(line.from(), line.to()));
        }
        Renamer.Result result = Renamer.apply(moves);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        List<Job.Problem> problems = new ArrayList<>(result.problems());
        if (log != null) {
            try (BufferedWriter out = log) {
                for (Renamer.Applied applied : result.applied()) {
                    out.write("{\"schema\":1,\"kind\":\"rename\",\"from\":" + Json.quote(applied.original().toString())
                            + ",\"to\":" + Json.quote(applied.now().toString()) + "}\n");
                }
            } catch (IOException e) {
                problems.add(new Job.Problem(args.get("undo-log", ""), "could not write the undo log: " + e.getMessage()));
            }
        }
        List<Line> done = new ArrayList<>();
        for (Renamer.Applied applied : result.applied()) {
            done.add(new Line(applied.original(), applied.now().getFileName().toString(), directoryOf(lines, applied), null));
        }
        Report report = new Report(false, false, result.applied().size(), unchanged, elapsedMs, done, problems);
        return finish(console, format, report, problems.isEmpty() ? Exit.OK : Exit.PARTIAL, quiet);
    }

    private static boolean directoryOf(List<Line> lines, Renamer.Applied applied) {
        for (Line line : lines) {
            if (line.from().equals(applied.original())) {
                return line.directory();
            }
        }
        return false;
    }

    private static FileEntry entry(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IOException(path + ": " + e.getMessage(), e);
        }
        return new FileEntry(path.getFileName().toString(), path, attributes.isDirectory(), attributes.size(),
                attributes.lastModifiedTime(), attributes.isSymbolicLink() || attributes.isOther(), "");
    }

    private static void checkSources(List<Path> sources) throws Args.UsageException {
        Path parent = null;
        for (Path source : sources) {
            if (source.getParent() == null || source.getFileName() == null) {
                throw new Args.UsageException("cannot rename a drive root: " + source);
            }
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new Args.UsageException("no such file or folder: " + source);
            }
            if (parent == null) {
                parent = source.getParent();
            } else if (!parent.equals(source.getParent())) {
                throw new Args.UsageException("all items must be in the same folder (" + parent + " and "
                        + source.getParent() + "); run one rename per folder");
            }
        }
    }

    private static Path logPath(String text) throws Args.UsageException {
        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new Args.UsageException("invalid path: " + text);
        }
    }

    // ---- undo -------------------------------------------------------------------------------------------------

    private int undo(Args args, Console console, Format format) {
        Console.Out err = console.err();
        boolean quiet = args.has("quiet");
        List<Renamer.Applied> applied = new ArrayList<>();
        try {
            readLog(args.get("undo", ""), applied);
        } catch (Args.UsageException e) {
            return fail(err, Exit.USAGE, e.getMessage());
        } catch (IOException e) {
            return fail(err, Exit.FATAL, "cannot read the undo log: " + e.getMessage());
        }
        List<Renamer.Move> moves = Renamer.undoMoves(applied);
        List<Line> lines = new ArrayList<>();
        for (Renamer.Move move : moves) {
            lines.add(new Line(move.from(), move.toName(), Files.isDirectory(move.from(), LinkOption.NOFOLLOW_LINKS), null));
        }
        boolean dryRun = args.has("dry-run");
        if (dryRun || lines.isEmpty()) {
            return finish(console, format, new Report(dryRun, true, 0, 0, 0, lines, List.of()), Exit.OK, quiet);
        }
        if (!args.has("yes")) {
            int declined = confirm(err, lines.get(0).from().getParent(), lines);
            if (declined >= 0) {
                return declined;
            }
        }
        long started = System.nanoTime();
        Renamer.Result result = Renamer.apply(moves);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        List<Line> done = new ArrayList<>();
        for (Renamer.Applied item : result.applied()) {
            done.add(new Line(item.original(), item.now().getFileName().toString(),
                    Files.isDirectory(item.now(), LinkOption.NOFOLLOW_LINKS), null));
        }
        Report report = new Report(false, true, result.applied().size(), 0, elapsedMs, done, result.problems());
        return finish(console, format, report, result.problems().isEmpty() ? Exit.OK : Exit.PARTIAL, quiet);
    }

    /** Reads the log written by {@code --undo-log}; only absolute paths in one folder, with a plain new name, are accepted. */
    private static void readLog(String file, List<Renamer.Applied> applied) throws Args.UsageException, IOException {
        try (BufferedReader reader = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
            String text;
            int number = 0;
            while ((text = reader.readLine()) != null) {
                number++;
                if (text.isBlank()) {
                    continue;
                }
                String where = "undo log line " + number + ": ";
                Object parsed;
                try {
                    parsed = JsonReader.parse(text);
                } catch (IllegalArgumentException e) {
                    throw new Args.UsageException(where + e.getMessage());
                }
                if (!(parsed instanceof Map<?, ?> map) || !"rename".equals(map.get("kind"))) {
                    continue;
                }
                if (!(map.get("from") instanceof String from) || !(map.get("to") instanceof String to)) {
                    throw new Args.UsageException(where + "missing \"from\" or \"to\"");
                }
                Path original;
                Path now;
                try {
                    original = Path.of(from);
                    now = Path.of(to);
                } catch (InvalidPathException e) {
                    throw new Args.UsageException(where + "invalid path");
                }
                if (!original.isAbsolute() || !now.isAbsolute() || original.getParent() == null
                        || !original.getParent().equals(now.getParent())) {
                    throw new Args.UsageException(where + "expected two absolute paths in the same folder");
                }
                if (!Files.exists(now, LinkOption.NOFOLLOW_LINKS)) {
                    throw new Args.UsageException(where + now + " no longer exists; nothing was changed");
                }
                applied.add(new Renamer.Applied(original, now));
            }
        } catch (InvalidPathException e) {
            throw new Args.UsageException("invalid path: " + file);
        }
    }

    // ---- confirmation and results -----------------------------------------------------------------------------

    /** Returns -1 to go ahead, or the exit code to leave with. */
    private int confirm(Console.Out err, Path folder, List<Line> lines) {
        if (!Tty.interactive()) {
            return fail(err, Exit.USAGE, "refusing to rename anything without --yes: there is no terminal to ask on");
        }
        err.println(err.style(Style.WARN, "About to rename " + lines.size() + (lines.size() == 1 ? " item" : " items"))
                + " in " + folder);
        for (int i = 0; i < Math.min(lines.size(), 8); i++) {
            Line line = lines.get(i);
            err.println("  " + line.from().getFileName() + err.style(Style.ACCENT, "  ->  ") + line.to());
        }
        if (lines.size() > 8) {
            err.println(err.style(Style.MUTED, "  ... and " + (lines.size() - 8) + " more (use -n to see all)"));
        }
        String answer = Prompt.ask(err, "Rename these items?", "[y/N]");
        if (answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"))) {
            return -1;
        }
        err.println(err.style(Style.MUTED, "aborted, nothing changed"));
        return Exit.CANCELLED;
    }

    private static int fail(Console.Out err, int code, String message) {
        err.println(err.style(Style.ERROR, "error:") + " " + message);
        return code;
    }

    private int finish(Console console, Format format, Report report, int code, boolean quiet) {
        Console.Out err = console.err();
        print(console.out(), format, report);
        console.out().flush();
        if (!quiet) {
            String summary = summaryLine(err, report);
            if (!summary.isEmpty()) {
                err.println(summary);
            }
        }
        for (int i = 0; i < Math.min(report.errors().size(), 50); i++) {
            Job.Problem problem = report.errors().get(i);
            err.println(err.style(Style.ERROR, "error:") + " " + problem.path() + err.style(Style.MUTED, " - " + problem.reason()));
        }
        if (report.errors().size() > 50) {
            err.println(err.style(Style.MUTED, "... and " + (report.errors().size() - 50) + " more errors"));
        }
        return code;
    }

    private static String summaryLine(Console.Out err, Report report) {
        String verb = report.undo() ? "undo" : "rename";
        if (!noInvalid(report)) {
            return "";
        }
        String same = report.unchanged() > 0 ? err.style(Style.MUTED, ", " + report.unchanged() + " unchanged") : "";
        if (report.dryRun()) {
            return err.style(Style.ACCENT, "dry run") + err.style(Style.MUTED, " - " + verb + ": ") + "would rename "
                    + report.lines().size() + (report.lines().size() == 1 ? " item" : " items") + same;
        }
        if (report.lines().isEmpty() && report.renamed() == 0 && report.errors().isEmpty()) {
            return err.style(Style.OK, "nothing to do") + err.style(Style.MUTED, " - every name is already as wanted") + same;
        }
        Style style = report.errors().isEmpty() ? Style.OK : Style.WARN;
        return err.style(style, report.errors().isEmpty() ? "done" : "finished with errors")
                + err.style(Style.MUTED, " - " + verb + ": ") + "renamed " + report.renamed()
                + (report.renamed() == 1 ? " item" : " items") + same;
    }

    private static String lineJson(Line line) {
        return "{\"entry\":\"" + (line.directory() ? "dir" : "file") + "\",\"dir\":" + Json.quote(line.from().getParent().toString())
                + ",\"from\":" + Json.quote(line.from().getFileName().toString()) + ",\"to\":" + Json.quote(line.to())
                + (line.error() == null ? "" : ",\"error\":" + Json.quote(line.error())) + "}";
    }

    private static String head(Report report) {
        StringBuilder text = new StringBuilder("\"command\":\"").append(report.undo() ? "rename-undo" : COMMAND)
                .append("\",\"dry_run\":").append(report.dryRun())
                .append(",\"status\":\"").append(report.errors().isEmpty() && noInvalid(report) ? "ok" : "errors")
                .append("\",\"renamed\":").append(report.renamed())
                .append(",\"unchanged\":").append(report.unchanged())
                .append(",\"elapsed_ms\":").append(report.elapsedMs())
                .append(",\"errors\":[");
        for (int i = 0; i < report.errors().size(); i++) {
            Job.Problem problem = report.errors().get(i);
            text.append(i == 0 ? "" : ",").append("{\"path\":").append(Json.quote(problem.path()))
                    .append(",\"reason\":").append(Json.quote(problem.reason())).append('}');
        }
        return text.append(']').toString();
    }

    private static boolean noInvalid(Report report) {
        for (Line line : report.lines()) {
            if (line.error() != null) {
                return false;
            }
        }
        return true;
    }

    private void print(Console.Out out, Format format, Report report) {
        switch (format) {
            case TEXT -> {
                for (Line line : report.lines()) {
                    String from = line.from().getFileName().toString();
                    if (line.error() != null) {
                        out.println(out.style(Style.ERROR, "error   ") + from + out.style(Style.MUTED, "  ->  " + line.to()
                                + "  (" + line.error() + ")"));
                    } else {
                        out.println(from + out.style(Style.ACCENT, "  ->  ") + out.style(Style.OK, line.to()));
                    }
                }
            }
            case JSON -> {
                StringBuilder text = new StringBuilder("{\"schema\":1,").append(head(report)).append(",\"items\":[");
                for (int i = 0; i < report.lines().size(); i++) {
                    text.append(i == 0 ? "\n" : ",\n").append(lineJson(report.lines().get(i)));
                }
                out.println(text.append(report.lines().isEmpty() ? "]" : "\n]").append('}').toString());
            }
            case JSONL -> {
                for (Line line : report.lines()) {
                    out.println("{\"schema\":1,\"kind\":\"item\"," + lineJson(line).substring(1));
                }
                out.println("{\"schema\":1,\"kind\":\"result\"," + head(report) + "}");
            }
            case CSV -> {
                out.println("entry,dir,from,to,error");
                for (Line line : report.lines()) {
                    out.println((line.directory() ? "dir" : "file") + "," + Csv.field(line.from().getParent().toString()) + ","
                            + Csv.field(line.from().getFileName().toString()) + "," + Csv.field(line.to()) + ","
                            + Csv.field(line.error() == null ? "" : line.error()));
                }
            }
            case NONE -> { }
        }
    }

    // ---- arguments --------------------------------------------------------------------------------------------

    private static RenamePlan.Settings settings(Args args) throws Args.UsageException {
        long start = 1;
        long step = 1;
        int digits = 1;
        try {
            start = Long.parseLong(args.get("start", "1").strip());
            step = Long.parseLong(args.get("step", "1").strip());
            digits = Integer.parseInt(args.get("digits", "1").strip());
        } catch (NumberFormatException e) {
            throw new Args.UsageException("--start, --step and --digits must be whole numbers");
        }
        if (digits < 1 || digits > 255) {
            throw new Args.UsageException("--digits must be 1 to 255");
        }
        return new RenamePlan.Settings(args.get("name", "[N]"), args.get("ext", "[E]"), start, step, digits,
                args.get("search", ""), args.get("replace", ""), args.has("match-case"),
                caseMode(args.get("case", "unchanged")), caseScope(args.get("case-scope", "both")));
    }

    private static RenamePlan.CaseMode caseMode(String text) throws Args.UsageException {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "unchanged" -> RenamePlan.CaseMode.UNCHANGED;
            case "lower" -> RenamePlan.CaseMode.LOWER;
            case "upper" -> RenamePlan.CaseMode.UPPER;
            case "words" -> RenamePlan.CaseMode.WORDS;
            case "first" -> RenamePlan.CaseMode.FIRST;
            default -> throw new Args.UsageException("--case must be unchanged, lower, upper, words or first");
        };
    }

    private static RenamePlan.CaseScope caseScope(String text) throws Args.UsageException {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "both" -> RenamePlan.CaseScope.BOTH;
            case "name" -> RenamePlan.CaseScope.NAME;
            case "ext", "extension" -> RenamePlan.CaseScope.EXTENSION;
            default -> throw new Args.UsageException("--case-scope must be both, name or ext");
        };
    }

    private static Format format(String text) throws Args.UsageException {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "text" -> Format.TEXT;
            case "json" -> Format.JSON;
            case "jsonl" -> Format.JSONL;
            case "csv" -> Format.CSV;
            case "none" -> Format.NONE;
            default -> throw new Args.UsageException("--format must be text, json, jsonl, csv or none");
        };
    }

    // ---- help -------------------------------------------------------------------------------------------------

    private int help(String themeName) {
        Console console = Console.open(Console.ColorMode.AUTO, Cli.theme(themeName));
        Console.Out out = console.out();
        String[][] options = {
            {"-N, --name MASK", "new name without extension (default [N])"},
            {"-e, --ext MASK", "new extension (default [E])"},
            {"    --start N", "first value of [C] (default 1)"},
            {"    --step N", "increase of [C] per item (default 1)"},
            {"    --digits N", "zero-pad [C] to N digits (default 1)"},
            {"-s, --search PATTERN", "replace this in the new name: * any text, ? any one character"},
            {"-r, --replace TEXT", "the replacement for --search (default: nothing, i.e. remove it)"},
            {"    --match-case", "--search is case sensitive (default: not)"},
            {"    --case MODE", "unchanged (default), lower, upper, words (Capitalize Words), first"},
            {"    --case-scope WHAT", "both (default), name or ext"},
            {"-n, --dry-run", "list the renames and change nothing"},
            {"-y, --yes", "do not ask; required when there is no terminal"},
            {"-u, --undo-log FILE", "write what was renamed (JSON lines); refuses an existing file"},
            {"    --undo FILE", "revert the renames recorded in FILE (no other arguments)"},
            {"-f, --format FORMAT", "text (default), json, jsonl, csv or none; written to stdout"},
            {"-q, --quiet", "no summary on stderr"},
            {"    --color WHEN", "auto, always or never (NO_COLOR is honoured)"},
            {"    --theme NAME", "dark, light, contrast or mc (default: from the INI file)"},
            {"-h, --help", "this help"},
        };
        out.println(out.style(Style.TITLE, Cli.NAME + " rename") + " FILE...  "
                + out.style(Style.MUTED, "multi-rename files and folders"));
        out.println();
        out.println(out.style(Style.BOLD, "Options"));
        for (String[] option : options) {
            out.println("  " + out.style(Style.ACCENT, String.format("%-22s", option[0])) + " " + option[1]);
        }
        out.println();
        out.println(out.style(Style.BOLD, "Masks    ") + "  [N] name  [E] extension  [N2-5] [N3-] characters  [C] counter  [P] parent folder");
        out.println(out.style(Style.BOLD, "         ") + "  [Y] [M] [D] [h] [m] [s] [YMD] [hms] modified time");
        out.println(out.style(Style.BOLD, "Items    ") + "  files, folders and wildcards in the last segment (DIR\\*.jpg); all in ONE folder;");
        out.println(out.style(Style.BOLD, "         ") + "  the counter follows the order given (a wildcard: sorted by name)");
        out.println(out.style(Style.BOLD, "Example  ") + "  rename -N \"holiday_[C]\" --digits 3 --case lower 'D:\\pics\\*.JPG' --undo-log undo.jsonl");
        out.println(out.style(Style.BOLD, "Safety   ") + "  an invalid or clashing name changes nothing (exit 2); every real run asks on a");
        out.println(out.style(Style.BOLD, "         ") + "  terminal, otherwise it needs --yes (exit 2)");
        out.println(out.style(Style.BOLD, "Streams  ") + "  renames on stdout; summary and errors on stderr");
        out.println(out.style(Style.BOLD, "Exit     ") + "  0 ok, 2 usage or invalid names, 3 finished with errors, 4 error, 130 cancelled");
        console.close();
        return Exit.OK;
    }
}
