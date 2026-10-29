package org.ds.tuifileman.cli;

import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.fs.ArchivePaths;
import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.fs.Glob;
import org.ds.tuifileman.ignore.IgnoreMatcher;
import org.ds.tuifileman.search.FileSearch;
import org.ds.tuifileman.search.FileSearch.Hit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code tuifileman find FOLDER}: the file search of the file manager (Alt+F7 there), for scripts and terminals.
 * Read-only. stdout carries the matches in the chosen format (absolute paths) as they are found, stderr the
 * progress and the human summary; the exit code says whether anything matched.
 */
final class FindCommand implements Cli.Command {
    private static final String COMMAND = "find";

    private enum Format { TEXT, JSON, JSONL, CSV, NONE }

    /** What a finished search found; {@code matches} is also right when the hits were streamed and not kept. */
    private record Result(List<Hit> hits, int matches, long examined, int unreadable, boolean limited) { }

    @Override
    public int run(List<String> arguments) {
        Args args = new Args()
                .option("name", 'N', true)
                .option("text", 'T', true)
                .option("archives", 'A', false)
                .option("exclude", 'x', true)
                .option("ignore-file", (char) 0, true)
                .option("gitignore", 'g', false)
                .option("depth", 'd', true)
                .option("match-case", (char) 0, false)
                .option("limit", (char) 0, true)
                .option("sort", (char) 0, false)
                .option("format", 'f', true)
                .option("summary", 'S', false)
                .option("threads", 't', true)
                .option("stats", (char) 0, false)
                .option("quiet", 'q', false)
                .option("color", (char) 0, true)
                .option("theme", (char) 0, true)
                .option("progress", (char) 0, true)
                .option("help", 'h', false);
        Format format;
        Console.ColorMode color;
        Progress.Mode progressMode;
        int depth = FileSearch.UNLIMITED;
        int limit = FileSearch.UNLIMITED;
        Path root;
        List<PathMatcher> excludes;
        IgnoreMatcher ignore;
        try {
            args.parse(arguments);
            if (args.has("help")) {
                return help(args.get("theme", null));
            }
            if (args.positional().size() != 1) {
                throw new Args.UsageException(args.positional().isEmpty() ? "expected the folder to search"
                        : "expected one folder, got " + args.positional().size());
            }
            format = format(args.get("format", "text"));
            color = Cli.colorMode(args.get("color", "auto"));
            progressMode = Cli.progressMode(args.get("progress", "auto"));
            if (args.has("depth")) {
                depth = DiffCommand.number(args.get("depth", ""), "--depth");
            }
            if (args.has("limit")) {
                limit = DiffCommand.number(args.get("limit", ""), "--limit");
            }
            if (args.has("threads")) {
                int threads = DiffCommand.number(args.get("threads", ""), "--threads");
                System.setProperty("tfm.threads", Integer.toString(threads));
            }
            root = root(args.positional().get(0));
            excludes = DiffCommand.excludes(args.all("exclude"));
            ignore = DiffCommand.ignores(args, root, root);
        } catch (Args.UsageException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }

        Console console = Console.open(color, Cli.theme(args.get("theme", null)));
        try {
            Console.Out err = console.err();
            if (!Files.exists(root)) {
                return fatal(err, "no such folder: " + Archives.display(root));
            }
            if (!Files.isDirectory(root) && !Files.isRegularFile(root)) {
                err.println(err.style(Style.ERROR, "error:") + " find searches a folder or an archive; "
                        + Archives.display(root) + " is neither");
                return Exit.USAGE;
            }
            String text = args.get("text", "");
            FileSearch.Query query = new FileSearch.Query(root, String.join(";", args.all("name")), args.has("archives"),
                    text, args.has("match-case"), depth, limit, relative -> DiffCommand.excluded(relative, excludes),
                    ignore);
            return search(args, console, format, progressMode, query);
        } finally {
            Archives.closeAll();
            console.close();
        }
    }

    private int search(Args args, Console console, Format format, Progress.Mode progressMode, FileSearch.Query query) {
        Console.Out err = console.err();
        Console.Out out = console.out();
        FileSearch search = new FileSearch(query);
        boolean content = !query.text().isEmpty();
        boolean summaryOnly = args.has("summary");
        // Matches are printed as they are found, so a long search shows results at once and a caller that stops
        // reading (or is killed) keeps what it got. json is one document and --sort asks for path order: both
        // collect the matches first.
        boolean collect = !summaryOnly && format != Format.NONE && (format == Format.JSON || args.has("sort"));
        boolean stream = !summaryOnly && !collect && format != Format.NONE;
        // Matches are printed on the same terminal as the bar: it steps aside while a batch is written
        boolean barBesideMatches = stream && out.terminal()
                && Progress.resolve(progressMode, args.has("quiet"), err) == Progress.Mode.BAR;
        Stats.begin(args.has("stats"), "scan");
        Stats.roots(query.root());
        if (stream) {
            header(out, format, content);
        }
        List<Hit> hits = new ArrayList<>();
        Thread worker = new Thread(search::run, "find");
        worker.setDaemon(true);
        try (Progress progress = Progress.start(progressMode, args.has("quiet"), console, "Searching",
                () -> Progress.Snapshot.counting(search.examined(), "entries"))) {
            worker.start();
            while (!search.isDone()) {
                if (Cli.CANCEL.get()) {
                    search.cancel();
                }
                try {
                    worker.join(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    search.cancel();
                    break;
                }
                take(search, hits, out, format, content, stream, collect, barBesideMatches ? progress : null);
            }
        }
        take(search, hits, out, format, content, stream, collect, null);
        out.flush();
        if (Cli.CANCEL.get()) {
            return Exit.CANCELLED;
        }
        if (search.error() != null) {
            if (Files.isRegularFile(query.root())) {
                err.println(err.style(Style.ERROR, "error:") + " find searches a folder or an archive; "
                        + Archives.display(query.root()) + " is neither");
                return Exit.USAGE;
            }
            return fatal(err, search.error());
        }
        Stats.phase("report");
        Stats.totals(search.examined(), 0);

        Result result = new Result(hits, search.hitCount(), search.examined(), search.unreadable(), search.truncated());
        if (collect) {
            hits.sort(FileSearch.BY_PATH);
        }
        if (format == Format.JSON) {
            json(out, query, result, content, summaryOnly);
        } else {
            if (collect) {
                header(out, format, content);
                for (Hit hit : hits) {
                    line(out, format, hit, content);
                }
            }
            footer(out, format, result, summaryOnly);
        }
        if (format == Format.TEXT && !summaryOnly && !args.has("quiet") && err.terminal()) {
            err.println(summaryLine(err, result));
        }
        out.flush();
        printUnreadable(err, search);
        Stats.print(err);

        if (result.unreadable() > 0) {
            // What could not be read was not searched: neither "nothing found" nor a complete list.
            return Exit.PARTIAL;
        }
        return result.matches() == 0 ? Exit.NO_MATCH : Exit.OK;
    }

    /**
     * Moves the hits found so far to the output (streaming) or to the list (sorting, json). A {@code bar} is
     * hidden from the first hit on until they are written.
     */
    private static void take(FileSearch search, List<Hit> hits, Console.Out out, Format format, boolean content,
                             boolean stream, boolean collect, Progress bar) {
        Hit first = search.poll();
        boolean hold = bar != null && first != null;
        if (hold) {
            bar.suspend();
        }
        try {
            takeFrom(first, search, hits, out, format, content, stream, collect);
        } finally {
            if (hold) {
                bar.resume();
            }
        }
    }

    private static void takeFrom(Hit first, FileSearch search, List<Hit> hits, Console.Out out, Format format,
                                 boolean content, boolean stream, boolean collect) {
        int printed = 0;
        for (Hit hit = first; hit != null; hit = search.poll()) {
            if (stream) {
                line(out, format, hit, content);
                // Looking at the stream flushes it, so only now and then; the reader of a pipe may be gone
                // (find ... | head) and every further line would only be thrown away.
                if (++printed % 256 == 0 && out.failed()) {
                    search.cancel();
                    return;
                }
            } else if (collect) {
                hits.add(hit);
            }
        }
        if (stream) {
            out.flush();
            if (out.failed()) {
                search.cancel(); // nobody reads the matches any more: stop searching
            }
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

    private static int fatal(Console.Out err, String message) {
        err.println(err.style(Style.ERROR, "error:") + " " + message);
        return Exit.FATAL;
    }

    private static void printUnreadable(Console.Out err, FileSearch search) {
        for (String[] entry : search.unreadableSamples()) {
            err.println(err.style(Style.ERROR, "error:") + " " + entry[0]
                    + err.style(Style.MUTED, " - cannot read, not searched: " + entry[1]));
        }
        int more = search.unreadable() - search.unreadableSamples().size();
        if (more > 0) {
            err.println(err.style(Style.MUTED, "... and " + more + " more unreadable"));
        }
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

    // ---- formats ----------------------------------------------------------------------------------------------

    private static String entry(Hit hit) {
        return hit.directory() ? "dir" : "file";
    }

    /** What comes before the first match: the column names of csv. */
    private static void header(Console.Out out, Format format, boolean content) {
        if (format == Format.CSV) {
            out.println("entry,path,archive,size,modified" + (content ? ",line,snippet" : ""));
        }
    }

    /** One match, in a format that has one line per match. */
    private static void line(Console.Out out, Format format, Hit hit, boolean content) {
        switch (format) {
            case TEXT -> out.println(hit.path() + (hit.directory() ? "/" : "")
                    + (content ? out.style(Style.MUTED, ":" + hit.line() + ": " + hit.snippet()) : ""));
            case JSONL -> out.println("{\"schema\":1,\"kind\":\"item\"," + jsonItem(hit, content).substring(1));
            case CSV -> out.println(entry(hit) + "," + Csv.field(hit.path()) + ","
                    + (hit.archive() == null ? "" : Csv.field(hit.archive())) + "," + hit.size() + ","
                    + Instant.ofEpochMilli(hit.modified())
                    + (content ? "," + hit.line() + "," + Csv.field(hit.snippet()) : ""));
            default -> { }
        }
    }

    /** What comes after the last match: the counts (always for jsonl, with {@code -S} for text and csv). */
    private static void footer(Console.Out out, Format format, Result result, boolean summaryOnly) {
        switch (format) {
            case TEXT -> {
                if (summaryOnly) {
                    out.println(String.format("%-14s%d", "matches", result.matches()));
                    out.println(String.format("%-14s%d", "examined", result.examined()));
                    out.println(String.format("%-14s%d", "unreadable", result.unreadable()));
                }
            }
            case JSONL -> out.println("{\"schema\":1,\"kind\":\"summary\",\"summary\":" + jsonSummary(result)
                    + Stats.json() + "}");
            case CSV -> {
                if (summaryOnly) {
                    out.println("what,count");
                    out.println("matches," + result.matches());
                    out.println("examined," + result.examined());
                    out.println("unreadable," + result.unreadable());
                }
            }
            default -> { }
        }
    }

    private static String summaryLine(Console.Out err, Result result) {
        int count = result.matches();
        String found = count == 0 ? err.style(Style.WARN, "no matches")
                : err.style(Style.OK, count + (count == 1 ? " match" : " matches"));
        String limit = result.limited() ? err.style(Style.WARN, "  ·  stopped at the --limit") : "";
        String unreadable = result.unreadable() > 0
                ? err.style(Style.MUTED, "  ·  ") + err.style(Style.ERROR, result.unreadable() + " unreadable") : "";
        return found + err.style(Style.MUTED, "  ·  " + result.examined() + " entries examined") + limit + unreadable;
    }

    private static String jsonItem(Hit hit, boolean content) {
        StringBuilder text = new StringBuilder("{\"entry\":\"").append(entry(hit)).append("\",\"path\":")
                .append(Json.quote(hit.path())).append(",\"archive\":")
                .append(hit.archive() == null ? "null" : Json.quote(hit.archive()))
                .append(",\"size\":").append(hit.size()).append(",\"modified\":\"")
                .append(Instant.ofEpochMilli(hit.modified())).append('"');
        if (content) {
            text.append(",\"line\":").append(hit.line()).append(",\"snippet\":").append(Json.quote(hit.snippet()));
        }
        return text.append('}').toString();
    }

    private static String jsonSummary(Result result) {
        return "{\"matches\":" + result.matches() + ",\"examined\":" + result.examined()
                + ",\"unreadable\":" + result.unreadable() + ",\"limited\":" + result.limited() + "}";
    }

    private static void json(Console.Out out, FileSearch.Query query, Result result, boolean content,
                             boolean summaryOnly) {
        StringBuilder text = new StringBuilder();
        text.append("{\"schema\":1,\"command\":\"find\",\"root\":").append(Json.quote(query.root().toString()))
                .append(",\"names\":[");
        List<String> names = Glob.split(query.names());
        for (int i = 0; i < names.size(); i++) {
            text.append(i == 0 ? "" : ",").append(Json.quote(names.get(i)));
        }
        text.append("],\"text\":").append(content ? Json.quote(query.text()) : "null")
                .append(",\"summary\":").append(jsonSummary(result));
        if (!summaryOnly) {
            text.append(",\"items\":[");
            for (int i = 0; i < result.hits().size(); i++) {
                text.append(i == 0 ? "\n" : ",\n").append(jsonItem(result.hits().get(i), content));
            }
            text.append(result.hits().isEmpty() ? "]" : "\n]");
        }
        out.println(text.append(Stats.json()).append('}').toString());
    }

    // ---- help -------------------------------------------------------------------------------------------------

    private int help(String themeName) {
        Console console = Console.open(Console.ColorMode.AUTO, Cli.theme(themeName));
        Console.Out out = console.out();
        String[][] options = {
            {"-N, --name PATTERN", "file name wildcards * and ?; several patterns separated by , or ; and the option may repeat"
                + " (a pattern without a wildcard matches names that contain it; default: every name; never case sensitive)"},
            {"-T, --text TEXT", "only files that contain this text (binary files are skipped; case-insensitive unless --match-case)"},
            {"    --match-case", "--text is case sensitive"},
            {"-A, --archives", "also search inside zip, jar, war, ear, apk, aar, whl, nupkg and vsix files (an archive counts as a folder)"},
            {"-x, --exclude GLOB", "leave out matching names or paths below FOLDER (repeatable); a matching folder is not entered, e.g. -x .git -x node_modules"},
            {"-g, --gitignore", "also leave out what the .gitignore files in FOLDER and below ignore (.git itself is not skipped: add -x .git)"},
            {"    --ignore-file FILE", "extra ignore rules in .gitignore syntax (repeatable)"},
            {"-d, --depth N", "folder levels to search: 1 = only FOLDER itself (default: all)"},
            {"    --limit N", "stop after N matches (which ones is undefined: folders are read in parallel)"},
            {"    --sort", "list the matches in path order (waits for the end of the search; json is always in path order)"},
            {"-f, --format FORMAT", "text (default), json, jsonl, csv or none; written to stdout"},
            {"-S, --summary", "only the counts"},
            {"-t, --threads N", "search threads (default: chosen by disk type)"},
            {"    --stats", "timing, throughput, threads and disk type (stderr, and a \"stats\" object in json/jsonl)"},
            {"-q, --quiet", "no progress and no summary on stderr"},
            {"    --progress MODE", "auto (bar on a terminal, silent otherwise), bar, lines, json, none"},
            {"    --color WHEN", "auto, always or never (NO_COLOR is honoured)"},
            {"    --theme NAME", "dark, light, contrast or mc (default: from the INI file)"},
            {"-h, --help", "this help"},
        };
        out.println(out.style(Style.TITLE, Cli.NAME + " find") + " FOLDER|ARCHIVE  "
                + out.style(Style.MUTED, "find files by name and content (read-only)"));
        out.println();
        out.println(out.style(Style.BOLD, "Options"));
        for (String[] option : options) {
            out.println("  " + out.style(Style.ACCENT, String.format("%-22s", option[0])) + " " + option[1]);
        }
        out.println();
        out.println(out.style(Style.BOLD, "Output  ") + "  absolute paths (inside an archive: C:\\a.zip!/dir/file), printed as found, "
                + "in no fixed order unless --sort; with --text each line is path:line: text of the first match");
        out.println(out.style(Style.BOLD, "Quoting ") + "  quote patterns (-N '*.txt') so the shell does not expand them; "
                + "PowerShell splits an unquoted a,b into two arguments - repeat -N or quote it");
        out.println(out.style(Style.BOLD, "Streams ") + "  result on stdout; progress, summary and errors on stderr");
        out.println(out.style(Style.BOLD, "Exit    ") + "  0 matches, 1 no matches, 2 usage, 3 incomplete (unreadable folders),"
                + " 4 error, 130 cancelled");
        console.close();
        return Exit.OK;
    }
}
