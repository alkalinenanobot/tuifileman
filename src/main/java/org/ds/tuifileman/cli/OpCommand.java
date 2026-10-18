package org.ds.tuifileman.cli;

import org.ds.tuifileman.app.CopyJob;
import org.ds.tuifileman.app.CopyJob.Conflict;
import org.ds.tuifileman.app.DeleteJob;
import org.ds.tuifileman.app.Job;
import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.compare.TimeTolerance;
import org.ds.tuifileman.model.FileEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code copy}, {@code move} and {@code delete}: the same engines the file manager uses (parallel small
 * files, native large-file copy, same-volume rename, parallel delete), driven from the command line.
 * Wildcards are expanded here, conflicts follow {@code --on-conflict}, and {@code --dry-run} plans without
 * touching anything. A delete asks first on a terminal and needs {@code --yes} everywhere else.
 */
final class OpCommand implements Cli.Command {
    enum Kind {
        COPY("copy", "Copying"), MOVE("move", "Moving"), DELETE("delete", "Deleting");

        final String command;
        final String verb;

        Kind(String command, String verb) {
            this.command = command;
            this.verb = verb;
        }
    }

    private enum Format { TEXT, JSON, JSONL, CSV, NONE }

    private enum Policy { ASK, OVERWRITE, SKIP, KEEP_BOTH, NEWER, LARGER, SMALLER, DIFFERENT }

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Kind kind;

    OpCommand(Kind kind) {
        this.kind = kind;
    }

    @Override
    public int run(List<String> arguments) {
        boolean delete = kind == Kind.DELETE;
        Args args = new Args()
                .option("dry-run", 'n', false)
                .option("format", 'f', true)
                .option("threads", 't', true)
                .option("stats", (char) 0, false)
                .option("quiet", 'q', false)
                .option("color", (char) 0, true)
                .option("theme", (char) 0, true)
                .option("progress", (char) 0, true)
                .option("help", 'h', false);
        if (delete) {
            args.option("yes", 'y', false);
        } else {
            args.option("on-conflict", 'c', true);
            args.option("time-tolerance", (char) 0, true);
        }
        Format format;
        Policy policy = Policy.ASK;
        long tolerance = TimeTolerance.DEFAULT_MILLIS;
        Console.ColorMode color;
        Progress.Mode progressMode;
        List<Path> sources = new ArrayList<>();
        Path destination = null;
        try {
            args.parse(arguments);
            if (args.has("help")) {
                return help(args.get("theme", null));
            }
            format = format(args.get("format", "text"));
            color = Cli.colorMode(args.get("color", "auto"));
            progressMode = Cli.progressMode(args.get("progress", "auto"));
            if (!delete) {
                policy = policy(args.get("on-conflict", "ask"));
                tolerance = DiffCommand.tolerance(args);
            }
            if (args.has("threads")) {
                System.setProperty("tfm.threads", Integer.toString(number(args.get("threads", ""))));
            }
            List<String> given = new ArrayList<>(args.positional());
            if (!delete) {
                if (given.size() < 2) {
                    throw new Args.UsageException("expected SOURCE... DESTINATION");
                }
                destination = path(given.remove(given.size() - 1)).toAbsolutePath().normalize();
                if (Files.exists(destination) && !Files.isDirectory(destination)) {
                    throw new Args.UsageException("the destination must be a folder: " + destination);
                }
            } else if (given.isEmpty()) {
                throw new Args.UsageException("expected at least one file or folder to delete");
            }
            collect(given, sources);
        } catch (Args.UsageException e) {
            return Cli.usageError(kind.command, e.getMessage());
        }

        Console console = Console.open(color, Cli.theme(args.get("theme", null)));
        try {
            Console.Out err = console.err();
            for (Path source : sources) {
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    return fail(err, Exit.FATAL, "no such file or folder: " + source);
                }
                if (delete && source.getParent() == null) {
                    return fail(err, Exit.USAGE, "refusing to delete a drive root: " + source);
                }
            }
            if (delete && !args.has("yes") && !args.has("dry-run")) {
                int declined = confirmDelete(err, sources);
                if (declined >= 0) {
                    return declined;
                }
            }
            return execute(console, args, format, policy, tolerance, progressMode, sources, destination);
        } finally {
            console.close();
        }
    }

    // ---- running ----------------------------------------------------------------------------------------------

    private int execute(Console console, Args args, Format format, Policy policy, long tolerance,
                        Progress.Mode progressMode, List<Path> sources, Path destination) {
        Console.Out err = console.err();
        boolean dryRun = args.has("dry-run");
        boolean quiet = args.has("quiet");
        List<FileEntry> entries = new ArrayList<>();
        for (Path source : sources) {
            boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
            entries.add(new FileEntry(source.getFileName() == null ? source.toString() : source.getFileName().toString(),
                    source, directory, 0, null));
        }

        AtomicReference<Job> current = new AtomicReference<>();
        Set<Path> unresolved = new HashSet<>();
        String[] abort = new String[1];
        long started = System.nanoTime();
        Job job;
        Stats.begin(args.has("stats"), "scan");
        Stats.roots(sources.get(0), destination);
        try (Progress progress = Progress.start(progressMode, quiet, console, "Scanning", () -> snapshot(current.get()))) {
            if (kind == Kind.DELETE) {
                DeleteJob deleteJob = new DeleteJob(entries);
                deleteJob.setDryRun(dryRun);
                job = deleteJob;
            } else {
                CopyJob copyJob = new CopyJob(entries, destination, kind == Kind.MOVE,
                        (source, existing) -> resolve(err, progress, policy, dryRun, source, existing, unresolved, abort));
                copyJob.setDryRun(dryRun);
                copyJob.setTimeTolerance(tolerance);
                job = copyJob;
            }
            current.set(job);
            Thread watcher = new Thread(() -> watch(current.get()), "cancel-watch");
            watcher.setDaemon(true);
            watcher.start();
            job.run();
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        if (abort[0] != null) {
            err.println(err.style(Style.ERROR, "error:") + " " + abort[0]);
            return Exit.USAGE;
        }
        if (job.isCancelled()) {
            err.println(err.style(Style.WARN, "cancelled") + err.style(Style.MUTED, " - " + job.summary()));
            return Exit.CANCELLED;
        }

        Report report = report(job, dryRun, unresolved, elapsedMs);
        Stats.totals(report.items(), report.bytes());
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
        return report.errors().isEmpty() ? Exit.OK : Exit.PARTIAL;
    }

    static void watch(Job job) {
        try {
            while (!job.isDone()) {
                if (Cli.CANCEL.get()) {
                    job.cancel();
                }
                String phase = job.phase();
                if (!phase.startsWith("Scanning") && !phase.startsWith("Preparing")) {
                    Stats.phase("work");
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException ignored) {
            // the job is over anyway
        }
    }

    private static Progress.Snapshot snapshot(Job job) {
        if (job == null) {
            return Progress.Snapshot.counting(0, "items");
        }
        String phase = job.phase();
        if (phase.startsWith("Scanning")) {
            return job.tracksBytes()
                    ? new Progress.Snapshot(job.bytesTotal(), -1, "bytes", true, "Scanning")
                    : new Progress.Snapshot(job.filesTotal(), -1, "items", false, "Scanning");
        }
        if (job.tracksBytes() && job.bytesTotal() > 0) {
            return new Progress.Snapshot(job.bytesDone(), job.bytesTotal(), "bytes", true, phase);
        }
        return new Progress.Snapshot(job.filesDone(), job.filesTotal(), "items", false, phase);
    }

    // ---- conflicts and confirmation --------------------------------------------------------------------------

    /** Called by the copy job from its own thread for each existing target until a policy sticks. */
    private static Conflict resolve(Console.Out err, Progress progress, Policy policy, boolean dryRun,
                                    Path source, Path existing, Set<Path> unresolved, String[] abort) {
        switch (policy) {
            case OVERWRITE:
                return Conflict.OVERWRITE_ALL;
            case SKIP:
                return Conflict.SKIP_ALL;
            case KEEP_BOTH:
                return Conflict.KEEP_BOTH_ALL;
            case NEWER:
                return Conflict.OVERWRITE_OLDER;
            case LARGER:
                return Conflict.OVERWRITE_SMALLER;
            case SMALLER:
                return Conflict.OVERWRITE_LARGER;
            case DIFFERENT:
                return Conflict.OVERWRITE_SIZE_DIFFERS;
            default:
                break;
        }
        if (!Tty.interactive()) {
            if (dryRun) {
                unresolved.add(existing);
                return Conflict.SKIP;
            }
            abort[0] = "already exists: " + existing + " (nothing was copied). "
                    + "Choose a policy with --on-conflict overwrite|skip|keep-both|newer|larger|smaller|different";
            return Conflict.CANCEL;
        }
        progress.suspend();
        try {
            err.println(err.style(Style.WARN, "exists") + "  " + err.style(Style.BOLD, existing.toString()));
            err.println("  " + err.style(Style.MUTED, "new     ") + describe(source));
            err.println("  " + err.style(Style.MUTED, "present ") + describe(existing));
            while (true) {
                String answer = Prompt.ask(err, " ",
                        "[o]verwrite [s]kip [k]eep both | all: [a]overwrite [n]skip [b]keep both [u]newer | [c]ancel >");
                if (answer == null) {
                    return Conflict.CANCEL;
                }
                switch (answer.toLowerCase(Locale.ROOT)) {
                    case "o": return Conflict.OVERWRITE;
                    case "s": return Conflict.SKIP;
                    case "k": return Conflict.KEEP_BOTH;
                    case "a": return Conflict.OVERWRITE_ALL;
                    case "n": return Conflict.SKIP_ALL;
                    case "b": return Conflict.KEEP_BOTH_ALL;
                    case "u": return Conflict.OVERWRITE_OLDER;
                    case "c": return Conflict.CANCEL;
                    default: err.println(err.style(Style.MUTED, "  one letter from the list, please"));
                }
            }
        } finally {
            progress.resume();
        }
    }

    private static String describe(Path path) {
        try {
            return String.format(Locale.ROOT, "%10s  %s", FileEntry.humanSize(Files.size(path)),
                    WHEN.format(Files.getLastModifiedTime(path).toInstant()));
        } catch (IOException e) {
            return "(unreadable)";
        }
    }

    /** Returns -1 to go ahead, or the exit code to leave with. */
    private int confirmDelete(Console.Out err, List<Path> targets) {
        if (!Tty.interactive()) {
            return fail(err, Exit.USAGE, "refusing to delete without --yes: there is no terminal to ask on");
        }
        err.println(err.style(Style.WARN, "About to delete " + targets.size() + (targets.size() == 1 ? " item:" : " items:")));
        for (int i = 0; i < Math.min(targets.size(), 8); i++) {
            boolean folder = Files.isDirectory(targets.get(i), LinkOption.NOFOLLOW_LINKS);
            err.println("  " + targets.get(i) + (folder ? err.style(Style.MUTED, "  (folder, with everything in it)") : ""));
        }
        if (targets.size() > 8) {
            err.println(err.style(Style.MUTED, "  ... and " + (targets.size() - 8) + " more"));
        }
        String answer = Prompt.ask(err, "Delete permanently?", "[y/N]");
        if (answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"))) {
            return -1;
        }
        err.println(err.style(Style.MUTED, "aborted, nothing deleted"));
        return Exit.CANCELLED;
    }

    private static int fail(Console.Out err, int code, String message) {
        err.println(err.style(Style.ERROR, "error:") + " " + message);
        return code;
    }

    // ---- results ----------------------------------------------------------------------------------------------

    private record Line(String action, String source, String target, long size, int items) { }

    private record Report(Kind kind, boolean dryRun, boolean cancelled, int items, long bytes, int skipped,
                          long elapsedMs, List<Line> lines, List<Job.Problem> errors, String summary) { }

    private Report report(Job job, boolean dryRun, Set<Path> unresolved, long elapsedMs) {
        List<Line> lines = new ArrayList<>();
        int items = job.filesDone();
        long bytes = job.bytesDone();
        int skipped = 0;
        if (job instanceof CopyJob copy) {
            skipped = copy.skipped();
            if (dryRun) {
                items = 0;
                bytes = 0;
                for (CopyJob.Planned planned : copy.planned()) {
                    boolean conflict = planned.action().equals("skip") && unresolved.contains(planned.target());
                    lines.add(new Line(conflict ? "conflict" : planned.action(), planned.source().toString(),
                            planned.target().toString(), planned.size(), 1));
                    if (!planned.action().equals("skip")) {
                        items++;
                        bytes += planned.size();
                    }
                }
            }
        } else if (job instanceof DeleteJob del && dryRun) {
            items = 0;
            for (DeleteJob.Planned planned : del.planned()) {
                lines.add(new Line("delete", planned.target().toString(), "", 0, planned.items()));
                items += planned.items();
            }
        }
        return new Report(kind, dryRun, job.isCancelled(), items, bytes, skipped, elapsedMs, lines, job.errors(),
                job.summary());
    }

    private String summaryLine(Console.Out err, Report report) {
        if (!report.dryRun()) {
            Style style = report.errors().isEmpty() ? Style.OK : Style.WARN;
            return err.style(style, report.errors().isEmpty() ? "done" : "finished with errors") + err.style(Style.MUTED, " - ")
                    + report.summary();
        }
        String what = switch (kind) {
            case COPY -> "copy";
            case MOVE -> "move";
            case DELETE -> "delete";
        };
        String size = kind == Kind.DELETE ? "" : " (" + FileEntry.humanSize(report.bytes()) + ")";
        int conflicts = 0;
        for (Line line : report.lines()) {
            conflicts += line.action().equals("conflict") ? 1 : 0;
        }
        return err.style(Style.ACCENT, "dry run") + err.style(Style.MUTED, " - ") + "would " + what + " "
                + String.format(Locale.ROOT, "%,d", report.items()) + (report.items() == 1 ? " item" : " items") + size
                + (report.skipped() - conflicts > 0 ? ", " + (report.skipped() - conflicts) + " skipped" : "")
                + (conflicts > 0 ? err.style(Style.ERROR, ", " + conflicts + " conflicts (use --on-conflict)") : "");
    }

    private static Style style(String action) {
        return switch (action) {
            case "copy", "move" -> Style.OK;
            case "overwrite" -> Style.WARN;
            case "keep-both" -> Style.ACCENT;
            case "delete", "conflict" -> Style.ERROR;
            default -> Style.MUTED;
        };
    }

    private void print(Console.Out out, Format format, Report report) {
        switch (format) {
            case TEXT -> {
                for (Line line : report.lines()) {
                    String target = line.target().isEmpty() ? "" : " -> " + line.target();
                    String note = kind == Kind.DELETE ? "  (" + line.items() + (line.items() == 1 ? " item)" : " items)")
                            : line.size() > 0 ? "  (" + FileEntry.humanSize(line.size()) + ")" : "";
                    out.println(out.style(style(line.action()), String.format("%-10s", line.action()))
                            + line.source() + target + out.style(Style.MUTED, note));
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
                for (Line line : report.lines()) {
                    out.println("{\"schema\":1,\"kind\":\"item\"," + lineJson(line).substring(1));
                }
                out.println("{\"schema\":1,\"kind\":\"result\"," + head(report) + "}");
            }
            case CSV -> {
                if (report.dryRun()) {
                    out.println("action,source,target,size,items");
                    for (Line line : report.lines()) {
                        out.println(line.action() + "," + Csv.field(line.source()) + "," + Csv.field(line.target()) + ","
                                + line.size() + "," + line.items());
                    }
                } else {
                    out.println("status,items,bytes,skipped,errors,elapsed_ms");
                    out.println(status(report) + "," + report.items() + "," + report.bytes() + "," + report.skipped()
                            + "," + report.errors().size() + "," + report.elapsedMs());
                }
            }
            case NONE -> { }
        }
    }

    private static String status(Report report) {
        return report.errors().isEmpty() ? "ok" : "errors";
    }

    private static String head(Report report) {
        StringBuilder text = new StringBuilder("\"command\":\"").append(report.kind().command)
                .append("\",\"dry_run\":").append(report.dryRun())
                .append(",\"status\":\"").append(status(report))
                .append("\",\"items\":").append(report.items())
                .append(",\"bytes\":").append(report.bytes())
                .append(",\"skipped\":").append(report.skipped())
                .append(",\"elapsed_ms\":").append(report.elapsedMs())
                .append(",\"errors\":[");
        for (int i = 0; i < report.errors().size(); i++) {
            Job.Problem problem = report.errors().get(i);
            text.append(i == 0 ? "" : ",").append("{\"path\":").append(Json.quote(problem.path()))
                    .append(",\"reason\":").append(Json.quote(problem.reason())).append('}');
        }
        return text.append(']').append(Stats.json()).toString();
    }

    private String lineJson(Line line) {
        return "{\"action\":\"" + line.action() + "\",\"source\":" + Json.quote(line.source())
                + (line.target().isEmpty() ? "" : ",\"target\":" + Json.quote(line.target()))
                + (kind == Kind.DELETE ? ",\"items\":" + line.items() : ",\"size\":" + line.size()) + "}";
    }

    // ---- arguments --------------------------------------------------------------------------------------------

    private static Path path(String text) throws Args.UsageException {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new Args.UsageException("invalid path: " + text);
        }
    }

    /** Literal paths stay as they are (an existing file with a "*" is impossible on Windows); wildcards expand. */
    static void collect(List<String> given, List<Path> sources) throws Args.UsageException {
        Set<Path> unique = new LinkedHashSet<>();
        for (String argument : given) {
            if (Wildcards.has(argument)) {
                List<Path> matches = Wildcards.expand(argument);
                if (matches.isEmpty()) {
                    throw new Args.UsageException("nothing matches " + argument);
                }
                unique.addAll(matches);
            } else {
                unique.add(path(argument).toAbsolutePath().normalize());
            }
        }
        sources.addAll(unique);
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

    private static Policy policy(String text) throws Args.UsageException {
        return switch (text.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "ask" -> Policy.ASK;
            case "overwrite" -> Policy.OVERWRITE;
            case "skip" -> Policy.SKIP;
            case "keep-both" -> Policy.KEEP_BOTH;
            case "newer" -> Policy.NEWER;
            case "larger" -> Policy.LARGER;
            case "smaller" -> Policy.SMALLER;
            case "different" -> Policy.DIFFERENT;
            default -> throw new Args.UsageException(
                    "--on-conflict must be ask, overwrite, skip, keep-both, newer, larger, smaller or different");
        };
    }

    private static int number(String text) throws Args.UsageException {
        try {
            int value = Integer.parseInt(text.strip());
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new Args.UsageException("--threads needs a positive number");
        }
    }

    // ---- help -------------------------------------------------------------------------------------------------

    private int help(String themeName) {
        Console console = Console.open(Console.ColorMode.AUTO, Cli.theme(themeName));
        Console.Out out = console.out();
        boolean delete = kind == Kind.DELETE;
        List<String[]> options = new ArrayList<>();
        if (!delete) {
            options.add(new String[] {"-c, --on-conflict POLICY", "when a target exists: ask (default on a terminal), overwrite, skip, "
                    + "keep-both, newer, larger, smaller, different"});
            options.add(new String[] {"    --time-tolerance T", "--on-conflict newer ignores time differences up to T: 2s, 90s, 5m, 1h, 0 = exact (default: INI [sync] time_tolerance, else 2s)"});
        } else {
            options.add(new String[] {"-y, --yes", "do not ask; required when there is no terminal"});
        }
        options.add(new String[] {"-n, --dry-run", "plan and list what would happen, change nothing"});
        options.add(new String[] {"-f, --format FORMAT", "text (default), json, jsonl, csv or none; written to stdout"});
        options.add(new String[] {"-t, --threads N", "worker threads (default: chosen by disk type)"});
        options.add(new String[] {"    --stats", "timing, throughput, threads and disk type (stderr, and a \"stats\" object in json/jsonl)"});
        options.add(new String[] {"-q, --quiet", "no progress and no summary on stderr"});
        options.add(new String[] {"    --progress MODE", "auto (bar on a terminal, silent otherwise), bar, lines, json, none"});
        options.add(new String[] {"    --color WHEN", "auto, always or never (NO_COLOR is honoured)"});
        options.add(new String[] {"    --theme NAME", "dark, light, contrast or mc (default: from the INI file)"});
        options.add(new String[] {"-h, --help", "this help"});
        String usage = delete ? "PATH..." : "SOURCE... DESTINATION";
        String about = switch (kind) {
            case COPY -> "copy files and folders into DESTINATION (a folder, created if missing)";
            case MOVE -> "move files and folders into DESTINATION (a rename on the same drive)";
            case DELETE -> "delete files and folders permanently";
        };
        out.println(out.style(Style.TITLE, Cli.NAME + " " + kind.command) + " " + usage + "  " + out.style(Style.MUTED, about));
        out.println();
        out.println(out.style(Style.BOLD, "Options"));
        for (String[] option : options) {
            out.println("  " + out.style(Style.ACCENT, String.format("%-26s", option[0])) + " " + option[1]);
        }
        out.println();
        out.println(out.style(Style.BOLD, "Wildcards") + "  * and ? in the last path segment are expanded by the tool");
        if (!delete) {
            out.println(out.style(Style.BOLD, "Conflicts") + "  without a terminal and without --on-conflict the run stops before "
                    + "copying anything (exit 2)");
        }
        out.println(out.style(Style.BOLD, "Streams  ") + "  dry-run listing / result on stdout; progress, summary and errors on stderr");
        out.println(out.style(Style.BOLD, "Exit     ") + "  0 ok, 2 usage, 3 finished with file errors, 4 error, 130 cancelled");
        console.close();
        return Exit.OK;
    }
}
