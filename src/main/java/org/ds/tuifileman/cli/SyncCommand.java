package org.ds.tuifileman.cli;

import org.ds.tuifileman.app.Job;
import org.ds.tuifileman.app.SyncJob;
import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.compare.DirCompare;
import org.ds.tuifileman.compare.DirCompare.Action;
import org.ds.tuifileman.compare.DirCompare.Item;
import org.ds.tuifileman.compare.DirCompare.Mode;
import org.ds.tuifileman.compare.DirCompare.Side;
import org.ds.tuifileman.compare.DirCompare.Status;
import org.ds.tuifileman.ignore.IgnoreMatcher;
import org.ds.tuifileman.model.FileEntry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code sync LEFT RIGHT}: the directory synchronisation of the file manager (Ctrl+F9), for scripts.
 * The actions come from a mode ({@code merge}, {@code mirror-right}, {@code mirror-left}) or from a plan
 * ({@code --plan}, the JSON lines this command or {@code diff} wrote), so a plan can be filtered by a tool
 * before it is applied. Every real run asks first on a terminal and needs {@code --yes} everywhere else.
 */
final class SyncCommand implements Cli.Command {
    private static final String COMMAND = "sync";

    private enum Format { TEXT, JSON, JSONL, CSV, NONE }

    /** One planned step; {@code reason} explains a skipped item ("conflict", "type-mismatch"). */
    private record Line(Item item, Action action, String reason) { }

    private record Report(String mode, boolean dryRun, int copied, int deleted, long bytes, int skipped, int ignored,
                          boolean showIgnored, long elapsedMs, List<Line> lines, List<Job.Problem> errors,
                          String summary) { }

    @Override
    public int run(List<String> arguments) {
        Args args = new Args()
                .option("mode", 'm', true)
                .option("content", 'c', false)
                .option("shallow", 's', false)
                .option("exclude", 'x', true)
                .option("ignore-file", (char) 0, true)
                .option("gitignore", 'g', false)
                .option("show-ignored", (char) 0, false)
                .option("time-tolerance", (char) 0, true)
                .option("plan", 'p', true)
                .option("dry-run", 'n', false)
                .option("yes", 'y', false)
                .option("format", 'f', true)
                .option("threads", 't', true)
                .option("stats", (char) 0, false)
                .option("quiet", 'q', false)
                .option("color", (char) 0, true)
                .option("theme", (char) 0, true)
                .option("progress", (char) 0, true)
                .option("help", 'h', false);
        Format format;
        Mode mode;
        Console.ColorMode color;
        Progress.Mode progressMode;
        List<PathMatcher> excludes;
        long tolerance;
        Path left;
        Path right;
        try {
            args.parse(arguments);
            if (args.has("help")) {
                return help(args.get("theme", null));
            }
            if (args.positional().size() != 2) {
                throw new Args.UsageException("expected two folders, got " + args.positional().size());
            }
            format = format(args.get("format", "text"));
            mode = mode(args.get("mode", "merge"));
            color = Cli.colorMode(args.get("color", "auto"));
            progressMode = Cli.progressMode(args.get("progress", "auto"));
            excludes = DiffCommand.excludes(args.all("exclude"));
            tolerance = DiffCommand.tolerance(args);
            if (args.has("plan") && (args.has("gitignore") || args.has("ignore-file"))) {
                throw new Args.UsageException("--gitignore and --ignore-file apply when scanning; a --plan is already decided");
            }
            if (args.has("threads")) {
                System.setProperty("tfm.threads", Integer.toString(DiffCommand.number(args.get("threads", ""), "--threads")));
            }
            left = root(args.positional().get(0));
            right = root(args.positional().get(1));
            if (left.equals(right) || left.startsWith(right) || right.startsWith(left)) {
                throw new Args.UsageException("the folders must be different and neither inside the other");
            }
        } catch (Args.UsageException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }

        Console console = Console.open(color, Cli.theme(args.get("theme", null)));
        try {
            return sync(args, console, format, mode, progressMode, excludes, tolerance, left, right);
        } finally {
            console.close();
        }
    }

    private static Path root(String text) throws Args.UsageException {
        try {
            Path path = Path.of(text).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new Args.UsageException("not an existing folder: " + path);
            }
            return path.toRealPath();
        } catch (InvalidPathException e) {
            throw new Args.UsageException("invalid path: " + text);
        } catch (IOException e) {
            throw new Args.UsageException("cannot read folder " + text + ": " + e.getMessage());
        }
    }

    private int sync(Args args, Console console, Format format, Mode mode, Progress.Mode progressMode,
                     List<PathMatcher> excludes, long tolerance, Path left, Path right) {
        Console.Out err = console.err();
        boolean quiet = args.has("quiet");
        boolean dryRun = args.has("dry-run");
        boolean fromPlan = args.has("plan");
        String modeName = fromPlan ? "plan" : modeName(mode);

        List<Item> items;
        // Entries the scan could not read: nothing below them is copied or deleted, so the run is incomplete.
        List<Job.Problem> unreadable = new ArrayList<>();
        Stats.begin(args.has("stats"), "scan");
        Stats.roots(left, right);
        if (fromPlan) {
            try {
                items = readPlan(args.get("plan", ""), left, right, mode, tolerance);
            } catch (Args.UsageException e) {
                return fail(err, Exit.USAGE, e.getMessage());
            } catch (IOException e) {
                return fail(err, Exit.FATAL, "cannot use the plan: " + e.getMessage());
            }
        } else {
            AtomicInteger examined = new AtomicInteger();
            AtomicInteger ignored = new AtomicInteger();
            IgnoreMatcher ignore;
            try {
                ignore = DiffCommand.ignores(args, left, right);
            } catch (Args.UsageException e) {
                return fail(err, Exit.USAGE, e.getMessage());
            }
            List<Item> scanned;
            Queue<DirCompare.Unreadable> missed = new ConcurrentLinkedQueue<>();
            try (Progress progress = Progress.start(progressMode, quiet, console, "Comparing",
                    () -> Progress.Snapshot.counting(examined.get(), "entries"))) {
                scanned = DirCompare.scan(left, right, !args.has("shallow"), args.has("content"), Cli.CANCEL, examined,
                        ignore, ignored, relative -> DiffCommand.excluded(relative, excludes), tolerance, missed);
                Stats.ignored(ignored.get());
            } catch (IOException e) {
                return fail(err, Exit.FATAL, "cannot read folder: " + e.getMessage());
            }
            for (DirCompare.Unreadable entry : missed) {
                unreadable.add(new Job.Problem(entry.path().toString(), "cannot read, not compared: " + entry.reason()));
            }
            if (Cli.CANCEL.get()) {
                return Exit.CANCELLED;
            }
            items = new ArrayList<>(scanned);
            DirCompare.apply(items, mode);
        }

        List<Line> lines = new ArrayList<>();
        int copies = 0;
        int deletes = 0;
        int skipped = 0;
        int ignoredItems = 0;
        long bytes = 0;
        for (Item item : items) {
            if (item.status() == Status.IGNORED) {
                ignoredItems++;
                lines.add(new Line(item, Action.NONE, "ignored"));
                continue;
            }
            Action action = item.action();
            String reason = action == Action.NONE ? reason(item) : "";
            if (action == Action.NONE && reason.isEmpty()) {
                continue;
            }
            lines.add(new Line(item, action, reason));
            switch (action) {
                case TO_LEFT, TO_RIGHT -> {
                    if (!item.directory()) {
                        copies++;
                        bytes += size(item, action);
                    }
                }
                case DELETE_LEFT, DELETE_RIGHT -> deletes++;
                default -> skipped++;
            }
        }
        boolean showIgnored = args.has("show-ignored");

        if (dryRun || copies + deletes == 0 && !hasFolderWork(lines)) {
            Report report = new Report(modeName, dryRun, copies, deletes, bytes, skipped, ignoredItems, showIgnored, 0,
                    lines, unreadable, "");
            return finish(console, format, report, unreadable.isEmpty() ? Exit.OK : Exit.PARTIAL, quiet);
        }

        if (!args.has("yes")) {
            Stats.phase("confirm");
            int declined = confirm(err, left, right, mode, fromPlan, copies, bytes, lines);
            if (declined >= 0) {
                return declined;
            }
        }
        Stats.phase("prepare");
        return apply(console, format, progressMode, quiet, left, right, items, modeName, lines, skipped, ignoredItems,
                showIgnored, unreadable);
    }

    private static boolean hasFolderWork(List<Line> lines) {
        for (Line line : lines) {
            if (line.action() != Action.NONE) {
                return true;
            }
        }
        return false;
    }

    private int apply(Console console, Format format, Progress.Mode progressMode, boolean quiet, Path left, Path right,
                      List<Item> items, String modeName, List<Line> lines, int skipped, int ignored,
                      boolean showIgnored, List<Job.Problem> unreadable) {
        Console.Out err = console.err();
        AtomicReference<SyncJob> current = new AtomicReference<>();
        long started = System.nanoTime();
        SyncJob job;
        try (Progress progress = Progress.start(progressMode, quiet, console, "Synchronizing", () -> snapshot(current.get()))) {
            job = new SyncJob(left, right, items);
            current.set(job);
            Thread watcher = new Thread(() -> OpCommand.watch(job), "cancel-watch");
            watcher.setDaemon(true);
            watcher.start();
            job.run();
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (job.isCancelled()) {
            err.println(err.style(Style.WARN, "cancelled") + err.style(Style.MUTED, " - " + job.summary()));
            return Exit.CANCELLED;
        }
        List<Job.Problem> errors = new ArrayList<>(unreadable);
        errors.addAll(job.errors());
        Report report = new Report(modeName, false, job.copied(), job.deleted(), job.bytesDone(), skipped, ignored,
                showIgnored, elapsedMs, lines, errors, job.summary());
        return finish(console, format, report, errors.isEmpty() ? Exit.OK : Exit.PARTIAL, quiet);
    }

    private static Progress.Snapshot snapshot(SyncJob job) {
        if (job == null) {
            return Progress.Snapshot.counting(0, "items");
        }
        String phase = job.phase();
        if (phase.equals("Copying") && job.bytesTotal() > 0) {
            return new Progress.Snapshot(job.bytesDone(), job.bytesTotal(), "bytes", true, phase);
        }
        return new Progress.Snapshot(job.filesDone(), job.filesTotal(), "items", false, phase);
    }

    private int finish(Console console, Format format, Report report, int code, boolean quiet) {
        Console.Out err = console.err();
        Stats.totals(report.copied() + report.deleted(), report.bytes());
        print(console.out(), format, report);
        console.out().flush();
        if (!quiet) {
            err.println(summaryLine(err, report));
        }
        for (int i = 0; i < Math.min(report.errors().size(), 50); i++) {
            Job.Problem problem = report.errors().get(i);
            err.println(err.style(Style.ERROR, "error:") + " " + problem.path() + err.style(Style.MUTED, " - " + problem.reason()));
        }
        if (report.errors().size() > 50) {
            err.println(err.style(Style.MUTED, "... and " + (report.errors().size() - 50) + " more errors"));
        }
        Stats.print(err);
        return code;
    }

    // ---- confirmation -----------------------------------------------------------------------------------------

    /** Returns -1 to go ahead, or the exit code to leave with. */
    private int confirm(Console.Out err, Path left, Path right, Mode mode, boolean fromPlan, int copies, long bytes,
                        List<Line> lines) {
        if (!Tty.interactive()) {
            return fail(err, Exit.USAGE, "refusing to change anything without --yes: there is no terminal to ask on"
                    + (fromPlan ? " (a plan read from standard input leaves none)" : ""));
        }
        String arrow = fromPlan ? "plan" : switch (mode) {
            case MIRROR_TO_RIGHT -> "->";
            case MIRROR_TO_LEFT -> "<-";
            default -> "<->";
        };
        err.println(err.style(Style.WARN, "About to synchronize") + " " + left + " " + err.style(Style.ACCENT, arrow) + " " + right);
        List<Line> removals = new ArrayList<>();
        for (Line line : lines) {
            if (line.action() == Action.DELETE_LEFT || line.action() == Action.DELETE_RIGHT) {
                removals.add(line);
            }
        }
        err.println("  copy " + copies + (copies == 1 ? " file" : " files") + " (" + FileEntry.humanSize(bytes) + ")");
        if (!removals.isEmpty()) {
            err.println(err.style(Style.ERROR, "  delete " + removals.size() + (removals.size() == 1 ? " item:" : " items:")));
            for (int i = 0; i < Math.min(removals.size(), 8); i++) {
                Line line = removals.get(i);
                String side = line.action() == Action.DELETE_LEFT ? left.toString() : right.toString();
                err.println("    " + side + java.io.File.separator
                        + line.item().relative().replace('/', java.io.File.separatorChar));
            }
            if (removals.size() > 8) {
                err.println(err.style(Style.MUTED, "    ... and " + (removals.size() - 8) + " more"));
            }
        }
        String answer = Prompt.ask(err, "Apply these changes?", "[y/N]");
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

    // ---- plans ------------------------------------------------------------------------------------------------

    /**
     * Reads a plan: one JSON object per line with at least {@code path}. An {@code action} is applied as it
     * stands; without one the {@code mode} decides from the {@code status} (as written by {@code diff}). Lines
     * of another {@code kind} (summary, result) are ignored. The current state of both trees is checked, and
     * a path that is unsafe or no longer exists stops the run before anything is touched.
     */
    private static List<Item> readPlan(String source, Path left, Path right, Mode mode, long tolerance)
            throws Args.UsageException, IOException {
        Map<String, Item> items = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (BufferedReader reader = source.equals("-")
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : Files.newBufferedReader(Path.of(source), StandardCharsets.UTF_8)) {
            String text;
            int number = 0;
            while ((text = reader.readLine()) != null) {
                number++;
                if (text.isBlank()) {
                    continue;
                }
                Object parsed;
                try {
                    parsed = JsonReader.parse(text);
                } catch (IllegalArgumentException e) {
                    throw new Args.UsageException("plan line " + number + ": " + e.getMessage());
                }
                if (!(parsed instanceof Map<?, ?> map)) {
                    throw new Args.UsageException("plan line " + number + ": expected a JSON object");
                }
                Object kind = map.get("kind");
                if (kind != null && !"item".equals(kind)) {
                    continue;
                }
                Item item = planItem(map, number, left, right, mode, tolerance);
                if (item != null) {
                    items.put(item.relative(), item);
                }
            }
        } catch (InvalidPathException e) {
            throw new Args.UsageException("invalid plan path: " + source);
        }
        return new ArrayList<>(items.values());
    }

    private static Item planItem(Map<?, ?> map, int number, Path left, Path right, Mode mode, long tolerance)
            throws Args.UsageException, IOException {
        String where = "plan line " + number + ": ";
        if (!(map.get("path") instanceof String path)) {
            throw new Args.UsageException(where + "missing \"path\"");
        }
        String relative = safeRelative(path, where);
        Status status = null;
        if (map.get("status") instanceof String name) {
            status = status(name, where);
        }
        Action action = null;
        if (map.get("action") instanceof String name) {
            action = action(name, where);
        }
        if (status == Status.IGNORED) {
            // An ignored item (as written by diff or a dry run) is left out; the mode must never act on it.
            if (action != null && action != Action.NONE) {
                throw new Args.UsageException(where + "'" + path + "' is ignored by the ignore rules and cannot be "
                        + actionName(action) + "; drop the line or the rule");
            }
            return null;
        }
        Item item;
        try {
            item = DirCompare.itemFor(left, right, relative, status, tolerance);
        } catch (InvalidPathException e) {
            throw new Args.UsageException(where + "invalid path '" + path + "'");
        }
        if (item == null) {
            throw new Args.UsageException(where + "'" + path + "' exists on neither side; the plan is out of date");
        }
        Action chosen = action != null ? action : DirCompare.proposed(item, mode);
        if (!item.allows(chosen)) {
            throw new Args.UsageException(where + "cannot apply " + actionName(chosen) + " to '" + path
                    + "' (missing on that side or a file/folder mismatch); the plan is out of date");
        }
        item.setAction(chosen);
        return item;
    }

    /** A relative path below the roots: no drive, no leading separator, no "." or ".." segments. */
    private static String safeRelative(String path, String where) throws Args.UsageException {
        boolean windows = java.io.File.separatorChar == '\\';
        String relative = windows ? path.replace('\\', '/') : path;
        if (relative.isEmpty() || (windows && relative.indexOf(':') >= 0) || relative.startsWith("/")) {
            throw new Args.UsageException(where + "unsafe path '" + path + "'");
        }
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new Args.UsageException(where + "unsafe path '" + path + "'");
            }
        }
        return relative;
    }

    private static Status status(String text, String where) throws Args.UsageException {
        String wanted = text.toLowerCase(Locale.ROOT).replace('_', '-');
        for (Status status : Status.values()) {
            if (status.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(wanted)) {
                return status;
            }
        }
        throw new Args.UsageException(where + "unknown status '" + text + "'");
    }

    private static Action action(String text, String where) throws Args.UsageException {
        return switch (text.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "to-right" -> Action.TO_RIGHT;
            case "to-left" -> Action.TO_LEFT;
            case "delete-left" -> Action.DELETE_LEFT;
            case "delete-right" -> Action.DELETE_RIGHT;
            case "skip", "none" -> Action.NONE;
            default -> throw new Args.UsageException(where + "unknown action '" + text
                    + "' (to-right, to-left, delete-left, delete-right or skip)");
        };
    }

    // ---- results ----------------------------------------------------------------------------------------------

    private static String actionName(Action action) {
        return switch (action) {
            case TO_RIGHT -> "to-right";
            case TO_LEFT -> "to-left";
            case DELETE_LEFT -> "delete-left";
            case DELETE_RIGHT -> "delete-right";
            case NONE -> "skip";
        };
    }

    private static String statusName(Status status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String reason(Item item) {
        return switch (item.status()) {
            case DIFFERENT -> "conflict";
            case TYPE_MISMATCH -> "type-mismatch";
            case IGNORED -> "ignored";
            default -> "";
        };
    }

    private static long size(Item item, Action action) {
        if (item.directory()) {
            return 0;
        }
        Side side = switch (action) {
            case TO_RIGHT, DELETE_LEFT -> item.left();
            case TO_LEFT, DELETE_RIGHT -> item.right();
            default -> item.left() != null ? item.left() : item.right();
        };
        return side == null ? 0 : side.size();
    }

    private static String path(Item item) {
        return item.relative();
    }

    private static String modeName(Mode mode) {
        return switch (mode) {
            case MERGE -> "merge";
            case MIRROR_TO_RIGHT -> "mirror-right";
            case MIRROR_TO_LEFT -> "mirror-left";
            case CLEAR -> "clear";
        };
    }

    private static Style style(Line line) {
        if (!line.reason().isEmpty()) {
            return Style.WARN;
        }
        return switch (line.action()) {
            case TO_RIGHT, TO_LEFT -> Style.OK;
            case DELETE_LEFT, DELETE_RIGHT -> Style.ERROR;
            default -> Style.MUTED;
        };
    }

    private String summaryLine(Console.Out err, Report report) {
        String size = " (" + FileEntry.humanSize(report.bytes()) + ")";
        String skipped = (report.skipped() > 0
                ? err.style(Style.MUTED, ", " + report.skipped() + " skipped" + conflictNote(report)) : "")
                + (report.ignored() > 0 ? err.style(Style.MUTED, ", " + report.ignored() + " ignored") : "");
        if (report.dryRun()) {
            return err.style(Style.ACCENT, "dry run") + err.style(Style.MUTED, " - " + report.mode() + ": ") + "would copy "
                    + String.format(Locale.ROOT, "%,d", report.copied()) + (report.copied() == 1 ? " file" : " files") + size
                    + ", delete " + String.format(Locale.ROOT, "%,d", report.deleted())
                    + (report.deleted() == 1 ? " item" : " items") + skipped;
        }
        if (report.summary().isEmpty()) {
            return err.style(Style.OK, "in sync") + err.style(Style.MUTED, " - nothing to do") + skipped;
        }
        Style style = report.errors().isEmpty() ? Style.OK : Style.WARN;
        return err.style(style, report.errors().isEmpty() ? "done" : "finished with errors")
                + err.style(Style.MUTED, " - " + report.mode() + ": ") + report.summary() + skipped;
    }

    private static String conflictNote(Report report) {
        int conflicts = 0;
        for (Line line : report.lines()) {
            conflicts += line.reason().equals("conflict") ? 1 : 0;
        }
        return conflicts > 0 ? " (" + conflicts + (conflicts == 1 ? " conflict" : " conflicts") + ": same time, different content)" : "";
    }

    private static String lineJson(Line line) {
        Item item = line.item();
        String by = switch (item.ignoredBy()) {
            case IgnoreMatcher.BY_LEFT -> ",\"ignoredBy\":\"left\"";
            case IgnoreMatcher.BY_RIGHT -> ",\"ignoredBy\":\"right\"";
            case IgnoreMatcher.BY_LEFT | IgnoreMatcher.BY_RIGHT -> ",\"ignoredBy\":\"both\"";
            default -> "";
        };
        return "{\"action\":\"" + actionName(line.action()) + "\",\"entry\":\"" + (item.directory() ? "dir" : "file")
                + "\",\"path\":" + Json.quote(path(item)) + ",\"status\":\"" + statusName(item.status())
                + "\",\"size\":" + size(item, line.action())
                + (line.reason().isEmpty() ? "" : ",\"reason\":\"" + line.reason() + "\"") + by + "}";
    }

    private static String status(Report report) {
        return report.errors().isEmpty() ? "ok" : "errors";
    }

    private static String head(Report report) {
        StringBuilder text = new StringBuilder("\"command\":\"sync\",\"mode\":\"").append(report.mode())
                .append("\",\"dry_run\":").append(report.dryRun())
                .append(",\"status\":\"").append(status(report))
                .append("\",\"copied\":").append(report.copied())
                .append(",\"deleted\":").append(report.deleted())
                .append(",\"bytes\":").append(report.bytes())
                .append(",\"skipped\":").append(report.skipped())
                .append(",\"ignored\":").append(report.ignored())
                .append(",\"elapsed_ms\":").append(report.elapsedMs())
                .append(",\"errors\":[");
        for (int i = 0; i < report.errors().size(); i++) {
            Job.Problem problem = report.errors().get(i);
            text.append(i == 0 ? "" : ",").append("{\"path\":").append(Json.quote(problem.path()))
                    .append(",\"reason\":").append(Json.quote(problem.reason())).append('}');
        }
        return text.append(']').append(Stats.json()).toString();
    }

    private void print(Console.Out out, Format format, Report report) {
        switch (format) {
            case TEXT -> {
                if (report.dryRun()) {
                    for (Line line : report.lines()) {
                        if (line.item().status() == Status.IGNORED && !report.showIgnored()) {
                            continue;
                        }
                        long size = size(line.item(), line.action());
                        String note = (size > 0 ? "  (" + FileEntry.humanSize(size) + ")" : "")
                                + (line.reason().isEmpty() ? "" : "  " + line.reason());
                        out.println(out.style(style(line), String.format("%-14s", actionName(line.action())))
                                + path(line.item()) + (line.item().directory() ? "/" : "") + out.style(Style.MUTED, note));
                    }
                }
            }
            case JSON -> {
                StringBuilder text = new StringBuilder("{\"schema\":1,").append(head(report));
                if (report.dryRun()) {
                    text.append(",\"plan\":[");
                    for (int i = 0; i < report.lines().size(); i++) {
                        text.append(i == 0 ? "\n" : ",\n").append(lineJson(report.lines().get(i)));
                    }
                    text.append(report.lines().isEmpty() ? "]" : "\n]");
                }
                out.println(text.append('}').toString());
            }
            case JSONL -> {
                if (report.dryRun()) {
                    for (Line line : report.lines()) {
                        out.println("{\"schema\":1,\"kind\":\"item\"," + lineJson(line).substring(1));
                    }
                }
                out.println("{\"schema\":1,\"kind\":\"result\"," + head(report) + "}");
            }
            case CSV -> {
                if (report.dryRun()) {
                    out.println("action,entry,path,status,size,reason");
                    for (Line line : report.lines()) {
                        out.println(actionName(line.action()) + "," + (line.item().directory() ? "dir" : "file") + ","
                                + Csv.field(path(line.item())) + "," + statusName(line.item().status()) + ","
                                + size(line.item(), line.action()) + "," + line.reason());
                    }
                } else {
                    out.println("status,copied,deleted,bytes,skipped,errors,elapsed_ms");
                    out.println(status(report) + "," + report.copied() + "," + report.deleted() + "," + report.bytes()
                            + "," + report.skipped() + "," + report.errors().size() + "," + report.elapsedMs());
                }
            }
            case NONE -> { }
        }
    }

    // ---- arguments --------------------------------------------------------------------------------------------

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

    private static Mode mode(String text) throws Args.UsageException {
        return switch (text.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "merge" -> Mode.MERGE;
            case "mirror-right", "mirror-to-right" -> Mode.MIRROR_TO_RIGHT;
            case "mirror-left", "mirror-to-left" -> Mode.MIRROR_TO_LEFT;
            default -> throw new Args.UsageException("--mode must be merge, mirror-right or mirror-left");
        };
    }

    // ---- help -------------------------------------------------------------------------------------------------

    private int help(String themeName) {
        Console console = Console.open(Console.ColorMode.AUTO, Cli.theme(themeName));
        Console.Out out = console.out();
        String[][] options = {
            {"-m, --mode MODE", "merge (default): newer and missing files go to the other side, nothing is deleted;"},
            {"", "mirror-right: make RIGHT identical to LEFT (deletes extra items on RIGHT);"},
            {"", "mirror-left: make LEFT identical to RIGHT (deletes extra items on LEFT)"},
            {"-n, --dry-run", "list the plan and change nothing"},
            {"-y, --yes", "do not ask; required when there is no terminal"},
            {"-p, --plan FILE", "apply a plan (JSON lines, '-' = standard input) instead of comparing; the"},
            {"", "action of each line wins, a line without one follows --mode and its status"},
            {"-c, --content", "compare the bytes of files of equal size (default: size and time)"},
            {"-s, --shallow", "only the top level, no subfolders"},
            {"    --time-tolerance T", "times differing by at most T are the same time: 2s, 90s, 5m, 1h, 0 = exact (default: INI [sync] time_tolerance, else 2s)"},
            {"-x, --exclude GLOB", "leave out matching names or paths (repeatable); never copied or deleted"},
            {"-g, --gitignore", "also obey the .gitignore files in both folders; ignored entries are never copied or deleted"},
            {"    --ignore-file FILE", "obey the patterns of this ignore file, relative to the folders (repeatable)"},
            {"    --show-ignored", "list ignored entries in the text plan (json, jsonl and csv always have them)"},
            {"-f, --format FORMAT", "text (default), json, jsonl, csv or none; written to stdout"},
            {"-t, --threads N", "scan and copy threads (default: chosen by disk type)"},
            {"    --stats", "timing, throughput, threads and disk type (stderr, and a \"stats\" object in json/jsonl)"},
            {"-q, --quiet", "no progress and no summary on stderr"},
            {"    --progress MODE", "auto (bar on a terminal, silent otherwise), bar, lines, json, none"},
            {"    --color WHEN", "auto, always or never (NO_COLOR is honoured)"},
            {"    --theme NAME", "dark, light, contrast or mc (default: from the INI file)"},
            {"-h, --help", "this help"},
        };
        out.println(out.style(Style.TITLE, Cli.NAME + " sync") + " LEFT RIGHT  "
                + out.style(Style.MUTED, "synchronize two folders"));
        out.println();
        out.println(out.style(Style.BOLD, "Options"));
        for (String[] option : options) {
            out.println("  " + out.style(Style.ACCENT, String.format("%-22s", option[0])) + " " + option[1]);
        }
        out.println();
        out.println(out.style(Style.BOLD, "Plans    ") + "  sync -n -f jsonl A B > plan.jsonl, edit or filter it, then sync -p plan.jsonl -y A B");
        out.println(out.style(Style.BOLD, "         ") + "  diff -f jsonl A B | sync -p - -m mirror-right -y A B");
        out.println(out.style(Style.BOLD, "Actions  ") + "  to-right, to-left, delete-left, delete-right, skip (a conflict is listed as a skip)");
        out.println(out.style(Style.BOLD, "Conflicts") + "  same time but different size or content: never touched by merge; set an action in a plan");
        out.println(out.style(Style.BOLD, "Safety   ") + "  every real run asks on a terminal, otherwise it needs --yes (exit 2)");
        out.println(out.style(Style.BOLD, "Ignored  ") + "  an ignored entry is a skip (reason ignored) and is never copied or deleted, even by a mirror");
        out.println(out.style(Style.BOLD, "Streams  ") + "  plan / result on stdout; progress, summary and errors on stderr");
        out.println(out.style(Style.BOLD, "Exit     ") + "  0 ok, 2 usage, 3 finished with file errors, 4 error, 130 cancelled");
        console.close();
        return Exit.OK;
    }
}
