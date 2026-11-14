package org.ds.tuifileman.app;

import org.ds.tuifileman.fs.Entries;
import org.ds.tuifileman.fs.Parallel;
import org.ds.tuifileman.model.FileEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Measures the total size of folders: every file below them added up (the length of the files, not the space they
 * take on disk), one task per directory like {@link TreeDeleter}. Links and junctions inside a folder are counted
 * as the small items they are, never followed, so a loop cannot make the walk endless. Only folders that were
 * measured completely are reported; a cancelled run reports none of the one it was in the middle of.
 */
public final class DirSizeJob implements Job {
    /** One folder's totals; {@code unreadable} counts the folders and files whose size could not be read. */
    public record Result(Path folder, long bytes, long files, long folders, long unreadable) { }

    private final List<Path> targets;
    private final Map<Path, Result> results = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicLong bytesSeen = new AtomicLong();
    private final AtomicLong itemsSeen = new AtomicLong();
    private volatile String current = "";
    private volatile boolean cancelled;
    private volatile boolean finished;

    public DirSizeJob(List<FileEntry> folders) {
        this.targets = folders.stream().map(FileEntry::path).toList();
    }

    /** The folders measured completely, in the order they were given. */
    public List<Result> results() {
        synchronized (results) {
            return new ArrayList<>(results.values());
        }
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

    @Override
    public List<Problem> errors() {
        return List.of();
    }

    @Override
    public void run() {
        try {
            for (Path target : targets) {
                if (cancelled) {
                    break;
                }
                current = name(target);
                Walk root = new Walk(target, () -> cancelled);
                ForkJoinPool pool = new ForkJoinPool(Parallel.threads(target));
                try {
                    pool.invoke(root);
                } finally {
                    pool.shutdown();
                }
                if (!cancelled) {
                    results.put(target, new Result(target, root.bytes.get(), root.files.get(),
                            root.folders.get(), root.unreadable.get()));
                }
            }
        } finally {
            finished = true;
        }
    }

    private static String name(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
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
            return text.append("No folder measured").toString();
        }
        text.append(what).append(FileEntry.humanSize(bytes)).append(" (")
                .append(String.format("%,d", bytes)).append(" bytes), ")
                .append(String.format("%,d", files)).append(files == 1 ? " file, " : " files, ")
                .append(String.format("%,d", folders)).append(folders == 1 ? " folder" : " folders");
        if (unreadable > 0) {
            text.append(", ").append(unreadable).append(" could not be read");
        }
        return text.toString();
    }

    /** Adds up one folder's tree; each task adds its own directory's files once, so the counters are touched rarely. */
    private final class Walk extends RecursiveAction {
        private final Path dir;
        private final BooleanSupplier stop;
        private final AtomicLong bytes;
        private final AtomicLong files;
        private final AtomicLong folders;
        private final AtomicLong unreadable;

        Walk(Path dir, BooleanSupplier stop) {
            this(dir, stop, new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong());
        }

        private Walk(Path dir, BooleanSupplier stop, AtomicLong bytes, AtomicLong files, AtomicLong folders,
                AtomicLong unreadable) {
            this.dir = dir;
            this.stop = stop;
            this.bytes = bytes;
            this.files = files;
            this.folders = folders;
            this.unreadable = unreadable;
        }

        @Override
        protected void compute() {
            if (stop.getAsBoolean()) {
                return;
            }
            List<Walk> subs = new ArrayList<>();
            long[] here = new long[3]; // bytes, files, unreadable
            Entries.list(dir, new Entries.Handler() {
                @Override
                public void entry(Path path, BasicFileAttributes attrs) {
                    if (Entries.isPlainDirectory(attrs)) {
                        subs.add(new Walk(path, stop, bytes, files, folders, unreadable));
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
            bytes.addAndGet(here[0]);
            files.addAndGet(here[1]);
            folders.addAndGet(subs.size());
            unreadable.addAndGet(here[2]);
            bytesSeen.addAndGet(here[0]);
            itemsSeen.addAndGet(here[1] + subs.size());
            invokeAll(subs);
        }
    }
}
