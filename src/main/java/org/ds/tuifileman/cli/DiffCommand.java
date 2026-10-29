package org.ds.tuifileman.cli;

import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.compare.BinaryCompare;
import org.ds.tuifileman.compare.DirCompare;
import org.ds.tuifileman.compare.DirCompare.Item;
import org.ds.tuifileman.compare.DirCompare.Side;
import org.ds.tuifileman.compare.DirCompare.Status;
import org.ds.tuifileman.compare.DirCompare.Unreadable;
import org.ds.tuifileman.compare.TextCompare;
import org.ds.tuifileman.compare.TimeTolerance;
import org.ds.tuifileman.compare.UnifiedDiff;
import org.ds.tuifileman.fs.ArchivePaths;
import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.ignore.IgnoreMatcher;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code tuifileman diff LEFT RIGHT}: the folder comparison of the file manager (Ctrl+F9 there), for
 * scripts and terminals. Read-only. stdout carries the result in the chosen format, stderr the progress
 * and the human summary; the exit code says whether the trees differ.
 */
final class DiffCommand implements Cli.Command {
    private static final String COMMAND = "diff";

    private enum Format { TEXT, JSON, JSONL, CSV, NONE }

    @Override
    public int run(List<String> arguments) {
        Args args = new Args()
                .option("content", 'c', false)
                .option("shallow", 's', false)
                .option("only", 'o', true)
                .option("all", 'a', false)
                .option("format", 'f', true)
                .option("summary", 'S', false)
                .option("exclude", 'x', true)
                .option("ignore-file", (char) 0, true)
                .option("gitignore", 'g', false)
                .option("unified", 'U', true)
                .option("ignore-space", 'w', false)
                .option("ignore-case", 'i', false)
                .option("show-ignored", (char) 0, false)
                .option("time-tolerance", (char) 0, true)
                .option("threads", 't', true)
                .option("stats", (char) 0, false)
                .option("quiet", 'q', false)
                .option("color", (char) 0, true)
                .option("theme", (char) 0, true)
                .option("progress", (char) 0, true)
                .option("help", 'h', false);
        Format format;
        Set<Status> only;
        Console.ColorMode color;
        Progress.Mode progressMode;
        List<PathMatcher> excludes;
        long tolerance;
        try {
            args.parse(arguments);
            if (args.has("help")) {
                return help(args.get("theme", null));
            }
            if (args.positional().size() != 2) {
                throw new Args.UsageException("expected two folders or two files, got " + args.positional().size());
            }
            format = format(args.get("format", "text"));
            only = statuses(args.all("only"));
            color = Cli.colorMode(args.get("color", "auto"));
            progressMode = Cli.progressMode(args.get("progress", "auto"));
            excludes = excludes(args.all("exclude"));
            tolerance = tolerance(args);
            if (args.has("threads")) {
                int threads = number(args.get("threads", ""), "--threads");
                System.setProperty("tfm.threads", Integer.toString(threads));
            }
        } catch (Args.UsageException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }

        Trace.at("arguments parsed");
        Trace.at("loading theme");
        var theme = Cli.theme(args.get("theme", null));
        Trace.at("theme loaded");
        Console console = Console.open(color, theme);
        Trace.at("console open");
        try {
            return compare(args, console, format, only, progressMode, excludes, tolerance);
        } finally {
            Archives.closeAll();
            console.close();
        }
    }

    private int compare(Args args, Console console, Format format, Set<Status> only, Progress.Mode progressMode,
                        List<PathMatcher> excludes, long tolerance) {
        Console.Out err = console.err();
        Path left;
        Path right;
        try {
            left = root(args.positional().get(0));
            right = root(args.positional().get(1));
        } catch (Args.UsageException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }
        for (Path root : List.of(left, right)) {
            if (!Files.exists(root)) {
                return fatal(err, "no such file or folder: " + Archives.display(root));
            }
        }
        if (Files.isRegularFile(left) && Files.isRegularFile(right)) {
            return files(args, console, format, left, right);
        }
        if (!Files.isDirectory(left) || !Files.isDirectory(right)) {
            err.println(err.style(Style.ERROR, "error:") + " diff compares two folders or two files, "
                    + "not a folder and a file");
            return Exit.USAGE;
        }
        for (String option : List.of("unified", "ignore-space", "ignore-case")) {
            if (args.has(option)) {
                return Cli.usageError(COMMAND, "--" + option + " applies to the comparison of two files");
            }
        }

        AtomicInteger examined = new AtomicInteger();
        AtomicInteger ignored = new AtomicInteger();
        IgnoreMatcher ignore;
        try {
            ignore = ignores(args, left, right);
        } catch (Args.UsageException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }
        List<Item> scanned;
        Queue<Unreadable> unreadable = new ConcurrentLinkedQueue<>();
        Stats.begin(args.has("stats"), "scan");
        Stats.roots(left, right);
        try (Progress progress = Progress.start(progressMode, args.has("quiet"), console, "Comparing",
                () -> Progress.Snapshot.counting(examined.get(), "entries"))) {
            Trace.at("scan starts");
            scanned = DirCompare.scan(left, right, !args.has("shallow"), args.has("content"), args.has("content"),
                    Cli.CANCEL, examined, ignore, ignored, relative -> excluded(relative, excludes), tolerance,
                    unreadable);
            Trace.at("scan done, " + examined.get() + " entries");
            Stats.phase("report");
            Stats.totals(examined.get(), 0);
            Stats.ignored(ignored.get());
        } catch (IOException e) {
            return fatal(err, "cannot read folder: " + e.getMessage());
        }
        if (Cli.CANCEL.get()) {
            return Exit.CANCELLED;
        }

        List<Item> kept = scanned;
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Status status : Status.values()) {
            counts.put(status, 0);
        }
        for (Item item : kept) {
            counts.merge(item.status(), 1, Integer::sum);
        }
        boolean showSame = args.has("all") || only.contains(Status.SAME);
        // Ignored items are noise for a person but part of the data: text lists them on request only.
        boolean showIgnored = args.has("show-ignored") || only.contains(Status.IGNORED)
                || format == Format.JSON || format == Format.JSONL || format == Format.CSV;
        List<Item> shown = new ArrayList<>();
        for (Item item : kept) {
            boolean visible = only.isEmpty()
                    ? (showSame || item.status() != Status.SAME) && (showIgnored || item.status() != Status.IGNORED)
                    : only.contains(item.status());
            if (visible) {
                shown.add(item);
            }
        }

        boolean summaryOnly = args.has("summary");
        Console.Out out = console.out();
        int missing = unreadable.size();
        switch (format) {
            case TEXT -> text(out, shown, counts, missing, summaryOnly);
            case JSON -> json(out, left, right, shown, counts, missing, summaryOnly);
            case JSONL -> jsonl(out, shown, counts, missing, summaryOnly);
            case CSV -> csv(out, shown, counts, missing, summaryOnly);
            case NONE -> { }
        }
        if (format == Format.TEXT && !summaryOnly && !args.has("quiet") && err.terminal()) {
            err.println(summaryLine(err, counts, missing));
        }
        out.flush();
        printUnreadable(err, unreadable);
        Stats.print(err);

        if (missing > 0) {
            // What could not be read was not compared: neither "identical" nor a complete list of differences.
            return Exit.PARTIAL;
        }
        // Whether the trees differ, whatever --only, --all or --show-ignored left in the listing.
        boolean different = false;
        for (Item item : kept) {
            different |= item.status() != Status.SAME && item.status() != Status.IGNORED;
        }
        return different ? Exit.DIFFERENT : Exit.OK;
    }

    /**
     * The comparison of two files as a unified diff on stdout (like {@code diff -u}). Text files are compared line
     * by line; anything else only tells whether the bytes differ. Exit 0 identical, 1 different.
     */
    private int files(Args args, Console console, Format format, Path left, Path right) {
        Console.Out out = console.out();
        Console.Out err = console.err();
        if (format == Format.JSON || format == Format.JSONL || format == Format.CSV) {
            return Cli.usageError(COMMAND, "--format json, jsonl and csv describe folders; two files give a unified "
                    + "diff (text) or only the exit code (none)");
        }
        int context = 3;
        try {
            if (args.has("unified")) {
                try {
                    context = Integer.parseInt(args.get("unified", "").strip());
                    if (context < 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw new Args.UsageException("--unified needs the number of context lines (0 or more)");
                }
            }
        } catch (Args.UsageException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }
        boolean ignoreSpace = args.has("ignore-space");
        boolean ignoreCase = args.has("ignore-case");
        boolean print = format == Format.TEXT;
        String leftName = Archives.display(left);
        String rightName = Archives.display(right);
        try {
            if (TextCompare.bothText(left, right)) {
                TextCompare compare = TextCompare.load(left, right);
                compare.compute(ignoreSpace, ignoreCase);
                List<String> lines = UnifiedDiff.render(compare, context);
                if (!lines.isEmpty()) {
                    if (print) {
                        out.println(out.style(Style.BOLD, "--- " + leftName));
                        out.println(out.style(Style.BOLD, "+++ " + rightName));
                        for (String line : lines) {
                            out.println(out.style(switch (line.charAt(0)) {
                                case '@' -> Style.ACCENT;
                                case '+' -> Style.ADDED;
                                case '-' -> Style.REMOVED;
                                default -> Style.PLAIN;
                            }, line));
                        }
                    }
                    return finishFiles(out, Exit.DIFFERENT);
                }
                if (!ignoreSpace && !ignoreCase && bytesDiffer(left, right)) {
                    if (print) {
                        out.println("Files " + leftName + " and " + rightName
                                + " differ only in line endings, a final newline or the encoding");
                    }
                    return finishFiles(out, Exit.DIFFERENT);
                }
                return Exit.OK;
            }
            if (bytesDiffer(left, right)) {
                if (print) {
                    out.println("Files " + leftName + " and " + rightName
                            + " differ (binary, or too large for a line diff)");
                }
                return finishFiles(out, Exit.DIFFERENT);
            }
            return Exit.OK;
        } catch (IOException e) {
            return fatal(err, "cannot read: " + e.getMessage());
        }
    }

    private static int finishFiles(Console.Out out, int exit) {
        out.flush();
        return exit;
    }

    private static boolean bytesDiffer(Path left, Path right) throws IOException {
        try (BinaryCompare bytes = new BinaryCompare(left, right)) {
            return bytes.find(0, true) >= 0;
        }
    }

    private static Path root(String text) throws Args.UsageException {
        try {
            return ArchivePaths.resolve(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new Args.UsageException("invalid path: " + text);
        } catch (IOException e) {
            throw new Args.UsageException(e.getMessage());
        }
    }

    /** One error line per unreadable entry on stderr (the first 50); shared with {@code sync}. */
    static void printUnreadable(Console.Out err, Collection<Unreadable> unreadable) {
        int shown = 0;
        for (Unreadable entry : unreadable) {
            if (shown++ == 50) {
                err.println(err.style(Style.MUTED, "... and " + (unreadable.size() - 50) + " more unreadable"));
                return;
            }
            err.println(err.style(Style.ERROR, "error:") + " " + entry.path()
                    + err.style(Style.MUTED, " - cannot read, not compared: " + entry.reason()));
        }
    }

    private static int fatal(Console.Out err, String message) {
        err.println(err.style(Style.ERROR, "error:") + " " + message);
        return Exit.FATAL;
    }

    // ---- filters ----------------------------------------------------------------------------------------------

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

    private static String name(Status status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Set<Status> statuses(List<String> values) throws Args.UsageException {
        Set<Status> result = EnumSet.noneOf(Status.class);
        for (String value : values) {
            for (String part : value.split(",")) {
                String wanted = part.strip().toLowerCase(Locale.ROOT).replace('_', '-');
                if (wanted.isEmpty()) {
                    continue;
                }
                boolean found = false;
                for (Status status : Status.values()) {
                    if (name(status).equals(wanted)) {
                        result.add(status);
                        found = true;
                    }
                }
                if (!found) {
                    StringBuilder known = new StringBuilder();
                    for (Status status : Status.values()) {
                        known.append(known.length() == 0 ? "" : ", ").append(name(status));
                    }
                    throw new Args.UsageException("unknown status '" + part.strip() + "' for --only; one of: " + known);
                }
            }
        }
        return result;
    }

    /** The value of {@code --time-tolerance} in milliseconds (see {@link TimeTolerance#parse}). */
    static long tolerance(String text, String option) throws Args.UsageException {
        try {
            return TimeTolerance.parse(text);
        } catch (IllegalArgumentException e) {
            throw new Args.UsageException(option + " needs a duration like 2s, 90s, 5m or 1h (0 = exact times)");
        }
    }

    /** {@code --time-tolerance}, else {@code [sync] time_tolerance} of the INI file, else 2 s. */
    static long tolerance(Args args) throws Args.UsageException {
        return args.has("time-tolerance")
                ? tolerance(args.get("time-tolerance", ""), "--time-tolerance") : TimeTolerance.configured();
    }

    static int number(String text, String option) throws Args.UsageException {
        try {
            int value = Integer.parseInt(text.strip());
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new Args.UsageException(option + " needs a positive number");
        }
    }

    /** The matcher for {@code --ignore-file} and {@code --gitignore}; {@link IgnoreMatcher#NONE} when neither is given. */
    static IgnoreMatcher ignores(Args args, Path left, Path right) throws Args.UsageException {
        List<Path> files = new ArrayList<>();
        for (String text : args.all("ignore-file")) {
            try {
                Path file = Path.of(text).toAbsolutePath().normalize();
                if (!Files.isRegularFile(file)) {
                    throw new Args.UsageException("--ignore-file: no such file: " + file);
                }
                files.add(file);
            } catch (InvalidPathException e) {
                throw new Args.UsageException("--ignore-file: invalid path: " + text);
            }
        }
        try {
            return IgnoreMatcher.git(files, args.has("gitignore"), left, right);
        } catch (IOException e) {
            throw new Args.UsageException("--ignore-file: cannot read: " + e.getMessage());
        }
    }

    static List<PathMatcher> excludes(List<String> globs) throws Args.UsageException {
        List<PathMatcher> result = new ArrayList<>();
        for (String glob : globs) {
            try {
                result.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                throw new Args.UsageException("bad --exclude pattern '" + glob + "'");
            }
        }
        return result;
    }

    /** Matches the whole relative path or any single name in it, so {@code node_modules} drops the folder and its content. */
    static boolean excluded(String relative, List<PathMatcher> excludes) {
        if (excludes.isEmpty()) {
            return false;
        }
        try {
            Path path = Path.of(relative);
            for (PathMatcher matcher : excludes) {
                if (matcher.matches(path)) {
                    return true;
                }
                for (Path part : path) {
                    if (matcher.matches(part)) {
                        return true;
                    }
                }
            }
        } catch (InvalidPathException e) {
            return false;
        }
        return false;
    }

    // ---- formats ----------------------------------------------------------------------------------------------

    private static String path(Item item) {
        return item.relative();
    }

    private static Style style(Status status) {
        return switch (status) {
            case ONLY_LEFT, TYPE_MISMATCH -> Style.REMOVED;
            case ONLY_RIGHT -> Style.ADDED;
            case LEFT_NEWER, RIGHT_NEWER, DIFFERENT -> Style.CHANGED;
            case SAME, IGNORED -> Style.MUTED;
        };
    }

    /** The side whose rules ignore an item, when it is only one of them; an item both sides ignore needs no note. */
    private static String ignoredNote(Item item) {
        return switch (item.ignoredBy()) {
            case IgnoreMatcher.BY_LEFT -> "  (by left rules)";
            case IgnoreMatcher.BY_RIGHT -> "  (by right rules)";
            default -> "";
        };
    }

    /** Entries that were actually compared; ignored ones are counted apart. */
    private static int compared(Map<Status, Integer> counts) {
        int total = 0;
        for (Status status : Status.values()) {
            total += status == Status.IGNORED ? 0 : counts.get(status);
        }
        return total;
    }

    private static void text(Console.Out out, List<Item> shown, Map<Status, Integer> counts, int unreadable,
                             boolean summaryOnly) {
        if (summaryOnly) {
            for (Status status : Status.values()) {
                out.println(String.format("%-14s%d", name(status), counts.get(status)));
            }
            out.println(String.format("%-14s%d", "unreadable", unreadable));
            out.println(String.format("%-14s%d", "total", compared(counts)));
            return;
        }
        for (Item item : shown) {
            String label = String.format("%-14s", name(item.status()));
            out.println(out.style(style(item.status()), label) + path(item) + (item.directory() ? "/" : "")
                    + out.style(Style.MUTED, ignoredNote(item)));
        }
    }

    private static String summaryLine(Console.Out err, Map<Status, Integer> counts, int unreadable) {
        List<String> parts = new ArrayList<>();
        int total = compared(counts);
        for (Status status : Status.values()) {
            int count = counts.get(status);
            if (count > 0 && status != Status.SAME && status != Status.IGNORED) {
                parts.add(err.style(style(status), count + " " + name(status).replace('-', ' ')));
            }
        }
        if (unreadable > 0) {
            parts.add(err.style(Style.ERROR, unreadable + " unreadable"));
        }
        String ignored = counts.get(Status.IGNORED) > 0
                ? err.style(Style.MUTED, "  ·  " + counts.get(Status.IGNORED) + " ignored") : "";
        if (parts.isEmpty()) {
            return err.style(Style.OK, "identical") + err.style(Style.MUTED, " (" + total + " entries)") + ignored;
        }
        return String.join(err.style(Style.MUTED, "  ·  "), parts)
                + err.style(Style.MUTED, "  ·  " + counts.get(Status.SAME) + " same") + ignored;
    }

    private static String side(Side side) {
        return side == null ? "null"
                : "{\"entry\":\"" + (side.directory() ? "dir" : "file") + "\",\"size\":" + side.size()
                + ",\"modified\":\"" + Instant.ofEpochMilli(side.modified()) + "\"}";
    }

    private static String jsonItem(Item item) {
        String by = switch (item.ignoredBy()) {
            case IgnoreMatcher.BY_LEFT -> ",\"ignoredBy\":\"left\"";
            case IgnoreMatcher.BY_RIGHT -> ",\"ignoredBy\":\"right\"";
            case IgnoreMatcher.BY_LEFT | IgnoreMatcher.BY_RIGHT -> ",\"ignoredBy\":\"both\"";
            default -> "";
        };
        return "{\"status\":\"" + name(item.status()) + "\",\"entry\":\"" + (item.directory() ? "dir" : "file")
                + "\",\"path\":" + Json.quote(path(item)) + ",\"left\":" + side(item.left())
                + ",\"right\":" + side(item.right()) + by + "}";
    }

    private static String jsonSummary(Map<Status, Integer> counts, int unreadable) {
        StringBuilder text = new StringBuilder("{");
        for (Status status : Status.values()) {
            text.append('"').append(name(status)).append("\":").append(counts.get(status)).append(',');
        }
        return text.append("\"unreadable\":").append(unreadable)
                .append(",\"total\":").append(compared(counts)).append('}').toString();
    }

    private static void json(Console.Out out, Path left, Path right, List<Item> shown, Map<Status, Integer> counts,
                             int unreadable, boolean summaryOnly) {
        StringBuilder text = new StringBuilder();
        text.append("{\"schema\":1,\"command\":\"diff\",\"left\":").append(Json.quote(left.toString()))
                .append(",\"right\":").append(Json.quote(right.toString()))
                .append(",\"summary\":").append(jsonSummary(counts, unreadable));
        if (!summaryOnly) {
            text.append(",\"items\":[");
            for (int i = 0; i < shown.size(); i++) {
                text.append(i == 0 ? "\n" : ",\n").append(jsonItem(shown.get(i)));
            }
            text.append(shown.isEmpty() ? "]" : "\n]");
        }
        out.println(text.append(Stats.json()).append('}').toString());
    }

    private static void jsonl(Console.Out out, List<Item> shown, Map<Status, Integer> counts, int unreadable,
                              boolean summaryOnly) {
        if (!summaryOnly) {
            for (Item item : shown) {
                out.println("{\"schema\":1,\"kind\":\"item\"," + jsonItem(item).substring(1));
            }
        }
        out.println("{\"schema\":1,\"kind\":\"summary\",\"summary\":" + jsonSummary(counts, unreadable)
                + Stats.json() + "}");
    }

    private static void csv(Console.Out out, List<Item> shown, Map<Status, Integer> counts, int unreadable,
                            boolean summaryOnly) {
        if (summaryOnly) {
            out.println("status,count");
            for (Status status : Status.values()) {
                out.println(name(status) + "," + counts.get(status));
            }
            out.println("unreadable," + unreadable);
            return;
        }
        out.println("status,entry,path,left_size,left_modified,right_size,right_modified");
        for (Item item : shown) {
            Side l = item.left();
            Side r = item.right();
            out.println(name(item.status()) + "," + (item.directory() ? "dir" : "file") + ","
                    + Csv.field(path(item)) + ","
                    + (l == null ? "," : l.size() + "," + Instant.ofEpochMilli(l.modified())) + ","
                    + (r == null ? "," : r.size() + "," + Instant.ofEpochMilli(r.modified())));
        }
    }

    // ---- help -------------------------------------------------------------------------------------------------

    private int help(String themeName) {
        Console console = Console.open(Console.ColorMode.AUTO, Cli.theme(themeName));
        Console.Out out = console.out();
        String[][] options = {
            {"-c, --content", "compare the bytes of files (default: size and time); a file whose content differs is 'different' whatever its time"},
            {"-s, --shallow", "only the top level, no subfolders"},
            {"    --time-tolerance T", "times differing by at most T are the same time: 2s, 90s, 5m, 1h, 0 = exact (default: INI [sync] time_tolerance, else 2s)"},
            {"-o, --only STATUS,...", "show only these statuses (repeatable; the exit code still covers all)"},
            {"-a, --all", "also list identical entries"},
            {"-x, --exclude GLOB", "leave out matching names or paths, e.g. node_modules or *.tmp (repeatable)"},
            {"-g, --gitignore", "also obey the .gitignore files found in both folders (ignored folders are not scanned)"},
            {"    --ignore-file FILE", "obey the patterns of this ignore file, relative to the folders (repeatable)"},
            {"    --show-ignored", "list the ignored entries too (always listed in json, jsonl and csv)"},
            {"-U, --unified N", "two files: context lines around each change (default 3)"},
            {"-w, --ignore-space", "two files: ignore differences in white space"},
            {"-i, --ignore-case", "two files: ignore differences in letter case"},
            {"-f, --format FORMAT", "text (default), json, jsonl, csv or none; written to stdout (two files: text or none)"},
            {"-S, --summary", "only the counts"},
            {"-t, --threads N", "scan threads (default: chosen by disk type)"},
            {"    --stats", "timing, throughput, threads and disk type (stderr, and a \"stats\" object in json/jsonl)"},
            {"-q, --quiet", "no progress and no summary on stderr"},
            {"    --progress MODE", "auto (bar on a terminal, silent otherwise), bar, lines, json, none"},
            {"    --color WHEN", "auto, always or never (NO_COLOR is honoured)"},
            {"    --theme NAME", "dark, light, contrast or mc (default: from the INI file)"},
            {"-h, --help", "this help"},
        };
        out.println(out.style(Style.TITLE, Cli.NAME + " diff") + " LEFT RIGHT  "
                + out.style(Style.MUTED, "compare two folders, or two files as a unified diff (read-only)"));
        out.println();
        out.println(out.style(Style.BOLD, "Options"));
        for (String[] option : options) {
            out.println("  " + out.style(Style.ACCENT, String.format("%-22s", option[0])) + " " + option[1]);
        }
        out.println();
        out.println(out.style(Style.BOLD, "Statuses") + "  only-left, only-right, left-newer, right-newer, "
                + "different, same, type-mismatch, ignored");
        out.println(out.style(Style.BOLD, "Ignore  ") + "  -g does not skip .git (compared like any folder); use -x .git for that");
        out.println(out.style(Style.BOLD, "Files   ") + "  two files give a unified diff (like diff -u); text only, binary files just say whether they differ");
        out.println(out.style(Style.BOLD, "Archives") + "  either side may be inside a zip, jar, docx ...: C:\\a.zip!/dir/file");
        out.println(out.style(Style.BOLD, "Streams ") + "  result on stdout; progress, summary and errors on stderr");
        out.println(out.style(Style.BOLD, "Exit    ") + "  0 identical, 1 differences, 2 usage, 3 incomplete (unreadable folders),"
                + " 4 error, 130 cancelled");
        console.close();
        return Exit.OK;
    }
}
