package org.ds.tuifileman.app;

import org.ds.tuifileman.compare.TimeTolerance;
import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.fs.Entries;
import org.ds.tuifileman.fs.Parallel;
import org.ds.tuifileman.model.FileEntry;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Copies or moves files in the background. Files are copied by {@link CopyScheduler} (small ones in
 * parallel, big ones alongside with native progress reporting). Same-volume moves are plain renames.
 * All state is read by the UI thread.
 */
public final class CopyJob implements Job {
    /**
     * The user's answer for an existing target. The plain ones apply to this file only; the others
     * stay in force for the rest of the job so the question is not asked again.
     */
    public enum Conflict {
        OVERWRITE, SKIP, KEEP_BOTH,
        OVERWRITE_ALL, SKIP_ALL, KEEP_BOTH_ALL, OVERWRITE_OLDER, OVERWRITE_SIZE_DIFFERS,
        OVERWRITE_SMALLER, OVERWRITE_LARGER,
        CANCEL
    }

    /** Called from the worker thread; blocks until the user decides. */
    public interface ConflictResolver {
        Conflict resolve(Path source, Path existing);
    }

    /**
     * A target that did not exist when the job (or sync) was planned exists when it is written: typically a
     * second name that differs only in letter case, copied to a case-insensitive file system. Never overwritten.
     */
    static final String APPEARED =
            "Target already exists (another name differing only in letter case, or created meanwhile)";

    /** The newer-than check of "overwrite older" ignores differences up to this many milliseconds. */
    private long timeToleranceMillis = TimeTolerance.DEFAULT_MILLIS;

    /** {@code replace}: the user chose to overwrite an existing target; any other target must not exist yet. */
    private record FileTask(Path source, Path target, long size, boolean link, int top, boolean replace) { }

    /** {@code exists}: the folder was already there when planned, so the copy merges into it. */
    private record DirTask(Path source, Path target, int top, boolean exists) { }

    private final List<Path> sources;
    private final Path destination;
    private final boolean move;
    private final ConflictResolver resolver;

    private final AtomicLong bytesTotal = new AtomicLong();
    private final AtomicLong bytesDone = new AtomicLong();
    private final AtomicInteger filesTotal = new AtomicInteger();
    private final AtomicInteger filesDone = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final List<Problem> errors = Collections.synchronizedList(new ArrayList<>());
    private volatile String phase = "Scanning...";
    private volatile String current = "";
    private volatile boolean cancelled;
    private volatile boolean done;
    private volatile long elapsedNanos;
    private Conflict policy;
    private final Set<Path> reserved = new HashSet<>();
    private boolean[] incomplete;
    private int copyThreads;

    public CopyJob(List<FileEntry> entries, Path destination, boolean move, ConflictResolver resolver) {
        this.sources = entries.stream().map(FileEntry::path).toList();
        this.destination = destination;
        this.move = move;
        this.resolver = resolver;
    }

    /** What a dry run decided for one source: copy, move (rename), overwrite, keep-both or skip. */
    public record Planned(String action, Path source, Path target, long size) { }

    private final List<Planned> planned = Collections.synchronizedList(new ArrayList<>());
    private boolean dryRun;
    private String targetName;

    /**
     * For a single source: the name it gets inside the destination folder (copy or move under a new name).
     * Ignored when there are several sources.
     */
    public void setTargetName(String name) {
        this.targetName = name;
    }

    /** Plans (scan, conflict questions) and stops: nothing is created, copied or deleted. */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /** The tolerance of "overwrite older", the same one directory sync uses (default 2 s). */
    public void setTimeTolerance(long millis) {
        this.timeToleranceMillis = millis;
    }

    public List<Planned> planned() {
        synchronized (planned) {
            return new ArrayList<>(planned);
        }
    }

    public int skipped() {
        return skipped.get();
    }

    @Override
    public String title() {
        return move ? "Moving" : "Copying";
    }

    @Override
    public boolean tracksBytes() {
        return true;
    }

    boolean isMove() {
        return move;
    }

    @Override
    public String phase() {
        return phase;
    }

    @Override
    public String current() {
        return current;
    }

    @Override
    public long bytesTotal() {
        return bytesTotal.get();
    }

    @Override
    public long bytesDone() {
        return Math.max(0, bytesDone.get());
    }

    @Override
    public int filesTotal() {
        return filesTotal.get();
    }

    @Override
    public int filesDone() {
        return filesDone.get();
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public List<Problem> errors() {
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }

    /** Runs the whole job; intended to be the body of a worker thread. */
    @Override
    public void run() {
        long start = System.nanoTime();
        try {
            execute();
        } catch (Throwable t) {
            errors.add(new Problem("(internal error)", String.valueOf(t)));
        } finally {
            elapsedNanos = System.nanoTime() - start;
            done = true;
        }
    }

    @Override
    public String summary() {
        double seconds = Math.max(0.001, elapsedNanos / 1e9);
        String verb = move ? "Moved" : "Copied";
        StringBuilder text = new StringBuilder();
        text.append(cancelled ? "Cancelled. " : "")
                .append(verb).append(' ').append(filesDone.get())
                .append(filesDone.get() == 1 ? " item" : " items");
        long bytes = bytesDone();
        if (bytes > 0) {
            text.append(" (").append(FileEntry.humanSize(bytes)).append(") in ")
                    .append(String.format("%.1f s", seconds)).append(", ")
                    .append(FileEntry.humanSize((long) (bytes / seconds))).append("/s");
        }
        if (skipped.get() > 0) {
            text.append(", ").append(skipped.get()).append(" skipped");
        }
        if (!errors.isEmpty()) {
            text.append(", ").append(errors.size()).append(" errors");
        }
        return text.toString();
    }

    private void execute() throws IOException {
        if (!dryRun) {
            Files.createDirectories(destination);
        }
        incomplete = new boolean[sources.size()];

        List<DirTask> dirs = new ArrayList<>();
        List<FileTask> files = new ArrayList<>();
        List<Path[]> renames = new ArrayList<>();
        plan(dirs, files, renames);
        if (cancelled || dryRun) {
            return;
        }

        phase = move ? "Moving" : "Copying";
        for (Path[] rename : renames) {
            if (cancelled) {
                return;
            }
            current = rename[0].getFileName().toString();
            try {
                try {
                    Files.move(rename[0], rename[1], StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(rename[0], rename[1]);
                }
                filesDone.incrementAndGet();
            } catch (IOException e) {
                fail(rename[0], e);
            }
        }
        createDirectories(dirs);
        copyAll(files);
        for (int i = dirs.size() - 1; i >= 0; i--) {
            try {
                Files.setLastModifiedTime(dirs.get(i).target(),
                        Files.getLastModifiedTime(dirs.get(i).source()));
            } catch (IOException | RuntimeException ignored) { }
        }
        if (move) {
            deleteMovedSources(renames);
        }
    }

    private void plan(List<DirTask> dirs, List<FileTask> files, List<Path[]> renames) {
        Queue<DirTask> foundDirs = new ConcurrentLinkedQueue<>();
        Queue<FileTask> foundFiles = new ConcurrentLinkedQueue<>();
        Queue<FileTask> conflicts = new ConcurrentLinkedQueue<>();
        for (int top = 0; top < sources.size() && !cancelled; top++) {
            Path source = sources.get(top);
            String name = targetName != null && sources.size() == 1 ? targetName : source.getFileName().toString();
            Path target = destination.resolve(name);
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                errors.add(new Problem(source.toString(), message(e)));
                incomplete[top] = true;
                continue;
            }
            boolean directory = attrs.isDirectory();
            if (move && target.equals(source) && !source.getFileName().toString().equals(name)) {
                // only the letter case changes (a.txt -> A.txt on a case-insensitive file system)
                renames.add(new Path[] {source, target});
                filesTotal.incrementAndGet();
                if (dryRun) {
                    planned.add(new Planned("move", source, target, 0));
                }
                continue;
            }
            if (target.equals(source)) {
                errors.add(new Problem(source.toString(), "Source and destination are the same"));
                incomplete[top] = true;
                continue;
            }
            if (directory && inside(target, source)) {
                errors.add(new Problem(source.toString(), "Cannot copy a folder into itself"));
                incomplete[top] = true;
                continue;
            }
            boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (move && !targetExists && sameVolume(source)) {
                renames.add(new Path[] {source, target});
                filesTotal.incrementAndGet();
                if (dryRun) {
                    planned.add(new Planned("move", source, target, 0));
                }
                continue;
            }
            if (directory) {
                if (targetExists && !Files.isDirectory(target)) {
                    errors.add(new Problem(target.toString(), "A file with this name already exists"));
                    incomplete[top] = true;
                    continue;
                }
                foundDirs.add(new DirTask(source, target, top, targetExists));
                ForkJoinPool pool = new ForkJoinPool(Parallel.threads(source));
                try {
                    pool.invoke(new Collector(top, source, target, targetExists, foundDirs, foundFiles, conflicts));
                } finally {
                    pool.shutdown();
                }
            } else if (targetExists && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                errors.add(new Problem(target.toString(), "A folder with this name already exists"));
                incomplete[top] = true;
            } else {
                addFile(new FileTask(source, target, attrs.isRegularFile() ? attrs.size() : 0,
                        !attrs.isRegularFile(), top, false), targetExists, foundFiles, conflicts);
            }
        }
        dirs.addAll(foundDirs);
        // Parents first: directories are created and finally get their times set in this order.
        dirs.sort(Comparator.comparingInt((DirTask d) -> d.target().getNameCount()));
        files.addAll(foundFiles);
        if (dryRun) {
            for (FileTask task : foundFiles) {
                planned.add(new Planned(move ? "move" : "copy", task.source(), task.target(), task.size()));
            }
        }
        resolveConflicts(conflicts, files);
        filesTotal.addAndGet(files.size());
    }

    private void addFile(FileTask task, boolean exists, Queue<FileTask> files, Queue<FileTask> conflicts) {
        if (exists && !task.link() && sameFile(task.source(), task.target())) {
            // Reached through a link or a hard link: replacing the "target" would delete the source.
            errors.add(new Problem(task.source().toString(), "Source and destination are the same"));
            incomplete[task.top()] = true;
        } else if (exists) {
            conflicts.add(task);
        } else {
            files.add(task);
            bytesTotal.addAndGet(task.size());
        }
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isLink(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isSymbolicLink() || attrs.isOther();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether {@code target} lies inside {@code source} once links are resolved: a destination folder (or an
     * existing target folder) that is a link or junction into the source would make the copy write into, and a
     * move then delete, its own source.
     */
    private static boolean inside(Path target, Path source) {
        if (target.startsWith(source)) {
            return true;
        }
        try {
            Path existing = target;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing != null
                    && existing.toRealPath().resolve(existing.relativize(target)).startsWith(source.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** The user is asked one file at a time, in path order, after the (parallel) scan is complete. */
    private void resolveConflicts(Queue<FileTask> conflicts, List<FileTask> files) {
        List<FileTask> ordered = new ArrayList<>(conflicts);
        ordered.sort(Comparator.comparing(task -> task.source().toString()));
        for (FileTask task : ordered) {
            if (cancelled) {
                return;
            }
            Path to = resolveConflict(task.source(), task.target());
            if (to == null) {
                skipped.incrementAndGet();
                incomplete[task.top()] = true;
                if (dryRun && !cancelled) {
                    planned.add(new Planned("skip", task.source(), task.target(), task.size()));
                }
                continue;
            }
            if (dryRun) {
                planned.add(new Planned(to.equals(task.target()) ? "overwrite" : "keep-both",
                        task.source(), to, task.size()));
            }
            files.add(new FileTask(task.source(), to, task.size(), task.link(), task.top(), to.equals(task.target())));
            bytesTotal.addAndGet(task.size());
        }
    }

    /**
     * Scans one source directory per task. Whether names already exist in the target is learned from a
     * single listing of the target directory, and not at all when the target directory is new.
     */
    private final class Collector extends RecursiveAction {
        private final int top;
        private final Path source;
        private final Path target;
        private final boolean targetExists;
        private final Queue<DirTask> dirs;
        private final Queue<FileTask> files;
        private final Queue<FileTask> conflicts;

        Collector(int top, Path source, Path target, boolean targetExists, Queue<DirTask> dirs,
                  Queue<FileTask> files, Queue<FileTask> conflicts) {
            this.top = top;
            this.source = source;
            this.target = target;
            this.targetExists = targetExists;
            this.dirs = dirs;
            this.files = files;
            this.conflicts = conflicts;
        }

        @Override
        protected void compute() {
            if (cancelled) {
                return;
            }
            Set<String> existing = targetExists ? namesIn(target) : Set.of();
            List<Collector> subs = new ArrayList<>();
            Entries.list(source, new Entries.Handler() {
                @Override
                public void entry(Path path, BasicFileAttributes attrs) {
                    Path to = target.resolve(path.getFileName().toString());
                    // existing == null: the target could not be listed, so every name is checked.
                    boolean exists = targetExists && (existing == null
                            || existing.contains(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                            && Files.exists(to, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isDirectory()) {
                        if (exists && !Files.isDirectory(to)) {
                            errors.add(new Problem(to.toString(), "A file with this name already exists"));
                            incomplete[top] = true;
                            return;
                        }
                        if (exists && isLink(to) && inside(to, sources.get(top))) {
                            errors.add(new Problem(path.toString(), "Cannot copy a folder into itself"));
                            incomplete[top] = true;
                            return;
                        }
                        dirs.add(new DirTask(path, to, top, exists));
                        subs.add(new Collector(top, path, to, exists, dirs, files, conflicts));
                    } else if (exists && Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
                        errors.add(new Problem(to.toString(), "A folder with this name already exists"));
                        incomplete[top] = true;
                    } else {
                        addFile(new FileTask(path, to, attrs.isRegularFile() ? attrs.size() : 0,
                                !attrs.isRegularFile(), top, false), exists, files, conflicts);
                    }
                }

                @Override
                public void failed(Path path, IOException error) {
                    errors.add(new Problem(path.toString(), message(error)));
                    incomplete[top] = true;
                }
            });
            invokeAll(subs);
        }
    }

    /** Lower case names in a directory, or null when it cannot be listed. */
    private static Set<String> namesIn(Path dir) {
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                names.add(path.getFileName().toString().toLowerCase(Locale.ROOT));
            }
            return names;
        } catch (IOException e) {
            return null;
        }
    }
    /** Returns the path to write to, or null when this file is to be skipped. */
    private Path resolveConflict(Path source, Path existing) {
        Conflict answer = policy;
        if (answer == null) {
            answer = resolver.resolve(source, existing);
            switch (answer) {
                case OVERWRITE_ALL, SKIP_ALL, KEEP_BOTH_ALL, OVERWRITE_OLDER, OVERWRITE_SIZE_DIFFERS,
                        OVERWRITE_SMALLER, OVERWRITE_LARGER -> policy = answer;
                case CANCEL -> cancelled = true;
                default -> { }
            }
        }
        return switch (answer) {
            case OVERWRITE, OVERWRITE_ALL -> existing;
            case KEEP_BOTH, KEEP_BOTH_ALL -> freeName(existing);
            case OVERWRITE_OLDER -> isNewer(source, existing) ? existing : null;
            case OVERWRITE_SIZE_DIFFERS, OVERWRITE_SMALLER, OVERWRITE_LARGER ->
                    sizeAllows(answer, source, existing) ? existing : null;
            case SKIP, SKIP_ALL, CANCEL -> null;
        };
    }

    // Unreadable attributes count as "different": the copy itself will then report the real problem.
    private boolean isNewer(Path source, Path existing) {
        try {
            return Files.getLastModifiedTime(source).toMillis()
                    - Files.getLastModifiedTime(existing).toMillis() > timeToleranceMillis;
        } catch (IOException e) {
            return true;
        }
    }

    /** "Smaller" and "larger" describe the existing file that would be replaced. */
    private static boolean sizeAllows(Conflict policy, Path source, Path existing) {
        try {
            int order = Long.compare(Files.size(source), Files.size(existing));
            return switch (policy) {
                case OVERWRITE_SMALLER -> order > 0;
                case OVERWRITE_LARGER -> order < 0;
                default -> order != 0;
            };
        } catch (IOException e) {
            return true;
        }
    }

    /** "name.ext" becomes "name (1).ext", "name (2).ext", ... */
    private Path freeName(Path existing) {
        String name = existing.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; ; n++) {
            Path candidate = existing.resolveSibling(stem + " (" + n + ")" + extension);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && reserved.add(candidate)) {
                return candidate;
            }
        }
    }

    private boolean sameVolume(Path source) {
        try {
            // a dry run has not created the destination yet: its nearest existing parent is on the same volume
            Path existing = destination;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing != null && Files.getFileStore(source).equals(Files.getFileStore(existing));
        } catch (IOException e) {
            return false;
        }
    }

    private void copyAll(List<FileTask> files) {
        if (!CopyScheduler.copyFiles(files, FileTask::size, this::copyOne, copyThreads(), () -> cancelled)) {
            cancelled = true;
        }
    }

    /**
     * One pool serves every source, so it is sized for the slowest end of any of them (a network share among
     * SSD sources gets its few threads). Judged once per parent folder: storage detection is not free.
     */
    private int copyThreads() {
        if (copyThreads == 0) {
            int threads = Parallel.threads(destination);
            Set<Path> folders = new HashSet<>();
            for (Path source : sources) {
                Path folder = source.getParent() == null ? source : source.getParent();
                if (folders.add(folder)) {
                    threads = Math.min(threads, Parallel.threads(source));
                }
            }
            copyThreads = threads;
        }
        return copyThreads;
    }

    /** Folders are created one depth level at a time (parents first), each level in parallel. */
    private void createDirectories(List<DirTask> dirs) {
        if (!CopyScheduler.byLevel(dirs, dir -> dir.target().getNameCount(), this::createDirectory,
                copyThreads(), () -> cancelled)) {
            cancelled = true;
        }
    }

    private void createDirectory(DirTask dir) {
        try {
            if (dir.exists()) {
                Files.createDirectories(dir.target());
            } else {
                Files.createDirectory(dir.target());
            }
        } catch (IOException e) {
            // A move must not delete a source whose folder never arrived (an empty one has no file to fail).
            incomplete[dir.top()] = true;
            errors.add(new Problem(dir.target().toString(), e instanceof FileAlreadyExistsException
                    ? APPEARED : "Cannot create folder: " + message(e)));
        }
    }

    private void copyOne(FileTask task) {
        if (cancelled) {
            return;
        }
        current = task.source().getFileName().toString();
        try {
            transfer(task);
            filesDone.incrementAndGet();
        } catch (NativeCopy.Cancelled e) {
            incomplete[task.top()] = true;
        } catch (IOException e) {
            incomplete[task.top()] = true;
            if (e instanceof FileAlreadyExistsException) {
                errors.add(new Problem(task.source().toString(), APPEARED + ": " + task.target()));
            } else if (!cancelled && !(e instanceof java.nio.channels.ClosedByInterruptException)
                    && !(e instanceof java.io.InterruptedIOException)) {
                // Cancelling interrupts the workers, which NIO reports as an IOException: not a failure of the file.
                fail(task.source(), e);
            }
        }
    }

    private void transfer(FileTask task) throws IOException {
        if (task.link()) {
            if (task.replace()) {
                Files.copy(task.source(), task.target(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            } else {
                Files.copy(task.source(), task.target(), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            }
            bytesDone.addAndGet(task.size());
            return;
        }
        if (!NativeCopy.available() || Archives.isVirtual(task.source()) || Archives.isVirtual(task.target())) {
            ChannelCopy.copy(task.source(), task.target(), task.replace(), bytesDone::addAndGet, () -> cancelled);
            return;
        }
        try {
            NativeCopy.copy(task.source(), task.target(), task.replace(), bytesDone::addAndGet, () -> cancelled);
        } catch (NativeCopy.Denied e) {
            if (!task.replace()) {
                throw e;
            }
            // Overwriting a read-only file needs the flag cleared first.
            try {
                Files.setAttribute(task.target(), "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | UnsupportedOperationException ignored) {
                throw e;
            }
            NativeCopy.copy(task.source(), task.target(), true, bytesDone::addAndGet, () -> cancelled);
        }
    }

    private void deleteMovedSources(List<Path[]> renames) {
        for (int top = 0; top < sources.size() && !cancelled; top++) {
            Path source = sources.get(top);
            if (incomplete[top] || renamed(renames, source)) {
                continue;
            }
            current = source.getFileName().toString();
            int before = errors.size();
            new TreeDeleter(Parallel.threads(source), () -> cancelled, new TreeDeleter.Listener() {
                @Override
                public void deleting(Path path) {
                    current = path.getFileName() == null ? path.toString() : path.getFileName().toString();
                }

                @Override
                public void deleted() { }

                @Override
                public void failed(Path path, IOException error) {
                    errors.add(new Problem(path.toString(), DeleteJob.reason(error)));
                }
            }).delete(source);
            if (errors.size() == before) {
                filesDone.incrementAndGet();
            }
        }
    }

    private static boolean renamed(List<Path[]> renames, Path source) {
        for (Path[] rename : renames) {
            if (rename[0].equals(source)) {
                return true;
            }
        }
        return false;
    }

    private void fail(Path source, IOException e) {
        errors.add(new Problem(source.toString(), message(e)));
    }

    private static String message(IOException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
