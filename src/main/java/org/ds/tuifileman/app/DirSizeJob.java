package org.ds.tuifileman.app;

import org.ds.tuifileman.fs.Entries;
import org.ds.tuifileman.fs.Parallel;
import org.ds.tuifileman.model.FileEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Measures the total size of folders: every file below them added up (the length of the files, not the space they
 * take on disk), with one task per directory on a shared work-stealing pool like
 * {@link org.ds.tuifileman.search.FileSearch}, so top-level folders and their subdirectories are scanned in parallel.
 * Links and junctions inside a folder are counted as the small items they are, never followed, so a loop cannot make
 * the walk endless. Only folders that were measured completely are reported; a cancelled run reports none of the ones
 * it was in the middle of, and a folder whose walk failed is reported as a problem instead of a size.
 */
public final class DirSizeJob implements Job {
    /** One folder's totals; {@code unreadable} counts the folders and files whose size could not be read. */
    public record Result(Path folder, long bytes, long files, long folders, long unreadable) { }

    private final List<Path> targets;
    private final AtomicReferenceArray<Result> results;
    private final AtomicReferenceArray<Problem> problems;
    private final AtomicLong bytesSeen = new AtomicLong();
    private final AtomicLong itemsSeen = new AtomicLong();
    private volatile String current = "";
    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile Problem internal;

    public DirSizeJob(List<FileEntry> folders) {
        this.targets = folders.stream().map(FileEntry::path).distinct().toList();
        this.results = new AtomicReferenceArray<>(this.targets.size());
        this.problems = new AtomicReferenceArray<>(this.targets.size());
    }

    /** The folders measured completely, in the order they were given. */
    public List<Result> results() {
        List<Result> done = new ArrayList<>(results.length());
        for (int i = 0; i < results.length(); i++) {
            Result result = results.get(i);
            if (result != null) {
                done.add(result);
            }
        }
        return done;
    }

    /** The measured totals by folder path, for {@link PanelState#showSizes}. */
    public Map<Path, Long> sizes() {
        Map<Path, Long> sizes = new LinkedHashMap<>();
        for (Result result : results()) {
            sizes.put(result.folder(), result.bytes());
        }
        return sizes;
    }

    @Override
    public String title() {
        return "Folder size";
    }

    @Override
    public String phase() {
        return "Measuring";
    }

    @Override
    public String current() {
        return current;
    }

    @Override
    public boolean tracksBytes() {
        return true;
    }

    /** Unknown until the walk is over. */
    @Override
    public long bytesTotal() {
        return 0;
    }

    @Override
    public long bytesDone() {
        return bytesSeen.get();
    }

    /** Unknown until the walk is over. */
    @Override
    public int filesTotal() {
        return 0;
    }

    @Override
    public int filesDone() {
        return (int) Math.min(Integer.MAX_VALUE, itemsSeen.get());
    }

    @Override
    public boolean isDone() {
        return finished;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    /** The folders that could not be measured, in the order they were given, then an internal failure if any. */
    @Override
    public List<Problem> errors() {
        List<Problem> found = new ArrayList<>();
        for (int i = 0; i < problems.length(); i++) {
            Problem problem = problems.get(i);
            if (problem != null) {
                found.add(problem);
            }
        }
        if (internal != null) {
            found.add(internal);
        }
        return found;
    }

    @Override
    public void run() {
        try {
            if (targets.isEmpty() || cancelled) {
                return;
            }
            int threads = targets.stream().mapToInt(Parallel::threads).min().orElse(1);
            ForkJoinPool pool = new ForkJoinPool(threads);
            try {
                pool.invoke(new RecursiveAction() {
                    @Override
                    protected void compute() {
                        List<ForkJoinTask<Void>> roots = new ArrayList<>(targets.size());
                        Throwable failure = null;
                        try {
                            for (int i = 0; i < targets.size() && !cancelled; i++) {
                                roots.add(new Walk(i, targets.get(i)).fork());
                            }
                        } catch (RuntimeException | Error e) {
                            failure = e;
                        }
                        joinAll(roots, failure);
                    }
                });
            } finally {
                pool.shutdown();
            }
        } catch (Throwable t) {
            internal = new Problem("(internal error)", String.valueOf(t));
        } finally {
            finished = true;
        }
    }

    private static String name(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    /**
     * Joins every task, also after one of them failed, so none is still running when the caller goes on (a job is
     * done only when no walk can change its results any more); then rethrows the first failure, if any.
     */
    private static void joinAll(List<ForkJoinTask<Void>> tasks, Throwable failure) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            try {
                tasks.get(i).join();
            } catch (RuntimeException | Error e) {
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
    }

    @Override
    public String summary() {
        List<Result> done = results();
        long bytes = 0;
        long files = 0;
        long folders = 0;
        long unreadable = 0;
        for (Result result : done) {
            bytes += result.bytes();
            files += result.files();
            folders += result.folders();
            unreadable += result.unreadable();
        }
        String what = done.size() == 1 ? name(done.get(0).folder()) + ": "
                : done.size() + " folders: ";
        StringBuilder text = new StringBuilder(cancelled ? "Cancelled. " : "");
        if (done.isEmpty()) {
            text.append("No folder measured");
        } else {
            text.append(what).append(FileEntry.humanSize(bytes)).append(" (")
                    .append(String.format("%,d", bytes)).append(" bytes), ")
                    .append(String.format("%,d", files)).append(files == 1 ? " file, " : " files, ")
                    .append(String.format("%,d", folders)).append(folders == 1 ? " folder" : " folders");
            if (unreadable > 0) {
                text.append(", ").append(unreadable).append(" could not be read");
            }
        }
        int failed = errors().size();
        if (failed > 0) {
            text.append(", ").append(failed).append(failed == 1 ? " problem" : " problems");
        }
        return text.toString();
    }

    /** Adds up one folder's tree; each task adds its own directory's files once, so the counters are touched rarely. */
    private final class Walk extends RecursiveAction {
        private final int slot;
        private final Path dir;
        private final AtomicLong bytes;
        private final AtomicLong files;
        private final AtomicLong folders;
        private final AtomicLong unreadable;

        Walk(int slot, Path dir) {
            this(slot, dir, new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong());
        }

        private Walk(int slot, Path dir, AtomicLong bytes, AtomicLong files, AtomicLong folders,
                AtomicLong unreadable) {
            this.slot = slot;
            this.dir = dir;
            this.bytes = bytes;
            this.files = files;
            this.folders = folders;
            this.unreadable = unreadable;
        }

        /** A top-level folder also records its result, or the failure that stopped its walk as its problem. */
        @Override
        protected void compute() {
            if (slot < 0) {
                walk();
                return;
            }
            try {
                walk();
                if (!cancelled) {
                    results.set(slot, new Result(dir, bytes.get(), files.get(), folders.get(), unreadable.get()));
                }
            } catch (RuntimeException | Error e) {
                problems.set(slot, new Problem(dir.toString(), Entries.message(e)));
            }
        }

        private void walk() {
            if (cancelled) {
                return;
            }
            current = name(dir);
            List<ForkJoinTask<Void>> subs = new ArrayList<>();
            long[] here = new long[3]; // bytes, files, unreadable
            Throwable failure = null;
            try {
                Entries.list(dir, new Entries.Handler() {
                    @Override
                    public void entry(Path path, BasicFileAttributes attrs) {
                        if (cancelled) {
                            return;
                        }
                        if (Entries.isPlainDirectory(attrs)) {
                            subs.add(new Walk(-1, path, bytes, files, folders, unreadable).fork());
                        } else {
                            here[0] += attrs.size();
                            here[1]++;
                        }
                    }

                    @Override
                    public void failed(Path path, IOException error) {
                        here[2]++;
                    }
                });
            } catch (RuntimeException | Error e) {
                failure = e; // the walks already forked still run: join them before rethrowing
            }
            bytes.addAndGet(here[0]);
            files.addAndGet(here[1]);
            folders.addAndGet(subs.size());
            unreadable.addAndGet(here[2]);
            bytesSeen.addAndGet(here[0]);
            itemsSeen.addAndGet(here[1] + subs.size());
            joinAll(subs, failure);
        }
    }
}
