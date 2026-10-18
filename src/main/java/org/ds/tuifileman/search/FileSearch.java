package org.ds.tuifileman.search;

import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.fs.Entries;
import org.ds.tuifileman.fs.Glob;
import org.ds.tuifileman.fs.Parallel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds files by name (several wildcard patterns at once), optionally by the text they contain, optionally
 * inside zip-like archives and with a limit on the folder depth. One task per folder runs on a work stealing
 * pool, like the delete and compare scans, so a cold SSD tree is read with many threads at once.
 *
 * <p>{@link #run} blocks until the search is over; the UI thread polls {@link #poll}, the counters and
 * {@link #isDone} meanwhile and stops it with {@link #cancel}. Links and junctions are never entered. Hits
 * arrive in no particular order.
 */
public final class FileSearch {
    /** {@link Query#maxDepth} for "no limit". */
    public static final int UNLIMITED = Integer.MAX_VALUE;
    /** The file manager stops here: a list of millions of hits is of no use and would exhaust the memory. */
    public static final int MAX_HITS = 100_000;
    /** Hits in path order (ignoring case), the order both the file manager and the command line list them in. */
    public static final java.util.Comparator<Hit> BY_PATH =
            java.util.Comparator.comparing(Hit::path, String.CASE_INSENSITIVE_ORDER).thenComparing(Hit::path);

    private static final int UNREADABLE_SAMPLES = 50;
    private static final Set<String> ARCHIVE_EXTENSIONS =
            Set.of("zip", "jar", "war", "ear", "apk", "aar", "whl", "nupkg", "vsix");
    private static final int SNIFF = 8192;
    private static final int CHUNK = 16 * 1024;
    /** A longer line is searched in pieces (overlapping by the length of the text, so no match is lost). */
    private static final int LINE_CAP = 64 * 1024;
    private static final int SNIPPET_BEFORE = 40;
    private static final int SNIPPET_AFTER = 160;

    /**
     * What to look for.
     *
     * @param root folder to search (a real folder; the search never starts inside an archive)
     * @param names wildcard patterns separated by a comma or semicolon; a pattern without {@code *} or
     *     {@code ?} matches names that contain it; empty matches every name
     * @param inArchives also look into zip-like archives (an archive counts as a folder)
     * @param text only files that contain this text (empty: names only); a text search skips folders
     * @param matchCase whether {@code text} is case sensitive (names never are)
     * @param maxDepth folder levels to look at: 1 is {@code root} only, {@link #UNLIMITED} everything below
     * @param maxHits the search stops after this many hits (which ones is undefined: the folders are read in
     *     parallel); {@link #UNLIMITED} for none
     */
    public record Query(Path root, String names, boolean inArchives, String text, boolean matchCase, int maxDepth,
                        int maxHits) {
        /** A query with the {@link #MAX_HITS} limit of the file manager. */
        public Query(Path root, String names, boolean inArchives, String text, boolean matchCase, int maxDepth) {
            this(root, names, inArchives, text, matchCase, maxDepth, MAX_HITS);
        }
    }

    /**
     * One match. For a plain file {@code archive} is null and {@code folder} the real folder; inside an archive
     * {@code archive} is the archive file and {@code folder} the folder in it.
     *
     * @param line line number of the first match of the text, 0 when only the name was searched
     * @param snippet that line around the match, empty when only the name was searched
     */
    public record Hit(String archive, String folder, String name, boolean directory, long size, long modified,
                      int line, String snippet) {
        /** "C:\data\a.txt", or "C:\data\a.zip!/dir/b.txt" inside an archive. */
        public String path() {
            if (archive == null) {
                return folder.endsWith(java.io.File.separator) ? folder + name : folder + java.io.File.separator + name;
            }
            return archive + "!" + (folder.endsWith("/") ? folder : folder + "/") + name;
        }
    }

    private final Query query;
    private final Predicate<String> nameFilter;
    private final Pattern textPattern;
    private final Queue<Hit> hits = new ConcurrentLinkedQueue<>();
    private final AtomicLong examined = new AtomicLong();
    private final AtomicInteger found = new AtomicInteger();
    private final AtomicInteger unreadable = new AtomicInteger();
    private final Queue<String[]> unreadableSamples = new ConcurrentLinkedQueue<>();
    private volatile boolean cancelled;
    private volatile boolean truncated;
    private volatile boolean done;
    private volatile String current = "";
    private volatile String error;

    public FileSearch(Query query) {
        this.query = query;
        this.nameFilter = nameFilter(query.names());
        this.textPattern = query.text().isEmpty() ? null : Pattern.compile(query.text(),
                Pattern.LITERAL | (query.matchCase() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    /** Whether a file name matches any of the patterns of the list; see {@link Query#names}. */
    public static Predicate<String> nameFilter(String names) {
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : Glob.split(names)) {
            patterns.add(Glob.compile(Glob.has(glob) ? glob : "*" + glob + "*", true));
        }
        if (patterns.isEmpty()) {
            return name -> true;
        }
        return name -> {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(name).matches()) {
                    return true;
                }
            }
            return false;
        };
    }

    // ---- control and progress (any thread) ----

    public void cancel() {
        cancelled = true;
    }

    public boolean isDone() {
        return done;
    }

    /** The next hit not handed out yet, or null. */
    public Hit poll() {
        return hits.poll();
    }

    /** Files, folders and archive entries looked at so far. */
    public long examined() {
        return examined.get();
    }

    public int hitCount() {
        return found.get();
    }

    /** Folders and archives that could not be read. */
    public int unreadable() {
        return unreadable.get();
    }

    /** The first {@value #UNREADABLE_SAMPLES} of them as {path, reason}. */
    public List<String[]> unreadableSamples() {
        return List.copyOf(unreadableSamples);
    }

    private void unreadable(Path path, Exception failure) {
        if (unreadable.incrementAndGet() <= UNREADABLE_SAMPLES) {
            String reason = failure instanceof java.nio.file.AccessDeniedException ? "access denied"
                    : failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            unreadableSamples.add(new String[] {path.toString(), reason});
        }
    }

    /** True when the search stopped at its {@link Query#maxHits} hits. */
    public boolean truncated() {
        return truncated;
    }

    /** The folder being read (best effort). */
    public String current() {
        return current;
    }

    /** Why the search could not start, or null. */
    public String error() {
        return error;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    // ---- the search ----

    /** Runs the whole search on the calling thread plus a pool of workers; returns when it is over. */
    public void run() {
        ForkJoinPool pool = null;
        try {
            if (!Files.isDirectory(query.root())) {
                error = "Not a folder: " + query.root();
                return;
            }
            pool = new ForkJoinPool(Parallel.threads(query.root()));
            pool.invoke(new FolderTask(query.root(), null, 1));
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
            done = true;
        }
    }

    private final class FolderTask extends RecursiveAction {
        private final Path folder;
        private final String archive;
        private final int depth;

        FolderTask(Path folder, String archive, int depth) {
            this.folder = folder;
            this.archive = archive;
            this.depth = depth;
        }

        @Override
        protected void compute() {
            if (cancelled) {
                return;
            }
            current = archive == null ? folder.toString() : archive + "!" + folder;
            String folderText = folder.toString();
            List<ForkJoinTask<Void>> subs = new ArrayList<>();
            Entries.list(folder, new Entries.Handler() {
                @Override
                public void entry(Path path, BasicFileAttributes attrs) {
                    if (cancelled) {
                        return;
                    }
                    examined.incrementAndGet();
                    String name = String.valueOf(path.getFileName());
                    boolean nameMatches = nameFilter.test(name);
                    if (Entries.isPlainDirectory(attrs)) {
                        if (nameMatches && textPattern == null) {
                            report(new Hit(archive, folderText, name, true, 0, attrs.lastModifiedTime().toMillis(), 0, ""));
                        }
                        if (depth < query.maxDepth()) {
                            subs.add(new FolderTask(path, archive, depth + 1).fork());
                        }
                        return;
                    }
                    if (nameMatches) {
                        fileMatched(path, attrs, folderText, name);
                    }
                    if (query.inArchives() && archive == null && attrs.isRegularFile() && depth < query.maxDepth()
                            && isArchiveName(name)) {
                        subs.add(new ArchiveTask(path, depth + 1).fork());
                    }
                }

                @Override
                public void failed(Path path, java.io.IOException failure) {
                    unreadable(path, failure);
                }
            });
            for (ForkJoinTask<Void> sub : subs) {
                sub.join();
            }
        }

        private void fileMatched(Path path, BasicFileAttributes attrs, String folderText, String name) {
            long modified = attrs.lastModifiedTime().toMillis();
            if (textPattern == null) {
                report(new Hit(archive, folderText, name, false, attrs.size(), modified, 0, ""));
            } else if (attrs.isRegularFile() && attrs.size() > 0) {
                String[] match = findText(path);
                if (match != null) {
                    report(new Hit(archive, folderText, name, false, attrs.size(), modified,
                            Integer.parseInt(match[0]), match[1]));
                }
            }
        }
    }

    /** An archive file is searched like a folder whose entries are one level deeper. */
    private final class ArchiveTask extends RecursiveAction {
        private final Path file;
        private final int depth;

        ArchiveTask(Path file, int depth) {
            this.file = file;
            this.depth = depth;
        }

        @Override
        protected void compute() {
            if (cancelled) {
                return;
            }
            Path root;
            try {
                root = Archives.open(file);
            } catch (IOException | RuntimeException e) {
                unreadable(file, e);
                return;
            }
            try {
                new FolderTask(root, file.toString(), depth).invoke();
            } finally {
                Archives.release(root);
            }
        }
    }

    private void report(Hit hit) {
        if (found.incrementAndGet() > query.maxHits()) {
            found.decrementAndGet();
            truncated = true;
            cancelled = true;
            return;
        }
        hits.add(hit);
    }

    private static boolean isArchiveName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && ARCHIVE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    // ---- text search ----

    /** {line number, snippet} of the first line that contains the text, or null (also for binary or unreadable files). */
    private String[] findText(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(SNIFF);
            Charset charset = textCharset(head);
            if (charset == null) {
                return null;
            }
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (Reader reader = new InputStreamReader(
                    new SequenceInputStream(new ByteArrayInputStream(head), in), decoder)) {
                return firstMatch(reader);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private String[] firstMatch(Reader reader) throws IOException {
        Matcher matcher = textPattern.matcher("");
        int carry = query.text().length() - 1;
        StringBuilder line = new StringBuilder();
        char[] chunk = new char[CHUNK];
        int number = 1;
        int read;
        while ((read = reader.read(chunk)) > 0) {
            if (cancelled) {
                return null;
            }
            for (int i = 0; i < read; i++) {
                char c = chunk[i];
                if (c == '\n') {
                    String[] found = matchLine(matcher, line, number);
                    if (found != null) {
                        return found;
                    }
                    line.setLength(0);
                    number++;
                } else {
                    line.append(c);
                    if (line.length() >= LINE_CAP) {
                        String[] found = matchLine(matcher, line, number);
                        if (found != null) {
                            return found;
                        }
                        line.delete(0, line.length() - carry);
                    }
                }
            }
        }
        return matchLine(matcher, line, number);
    }

    private static String[] matchLine(Matcher matcher, StringBuilder line, int number) {
        matcher.reset(line);
        if (!matcher.find()) {
            return null;
        }
        int from = Math.max(0, matcher.start() - SNIPPET_BEFORE);
        int to = Math.min(line.length(), matcher.end() + SNIPPET_AFTER);
        StringBuilder snippet = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            char c = line.charAt(i);
            snippet.append(Character.isISOControl(c) ? ' ' : c);
        }
        return new String[] {Integer.toString(number), snippet.toString().strip()};
    }

    /**
     * The charset to read a file as text, or null for binary data. A NUL byte means binary unless the file starts
     * with a UTF-16 byte order mark; bytes that are not valid UTF-8 are read as Windows-1252.
     */
    static Charset textCharset(byte[] head) {
        if (head.length >= 2 && (head[0] == (byte) 0xFE && head[1] == (byte) 0xFF
                || head[0] == (byte) 0xFF && head[1] == (byte) 0xFE)) {
            return StandardCharsets.UTF_16;
        }
        for (byte b : head) {
            if (b == 0) {
                return null;
            }
        }
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        // endOfInput is false: a character cut off by the end of the sample is not an error.
        boolean utf8 = !strict.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.length + 1), false).isError();
        if (utf8) {
            return StandardCharsets.UTF_8;
        }
        return Charset.isSupported("windows-1252") ? Charset.forName("windows-1252") : StandardCharsets.ISO_8859_1;
    }
}
