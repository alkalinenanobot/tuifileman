package org.ds.tuifileman.app;

import org.ds.tuifileman.fs.Parallel;
import org.ds.tuifileman.model.FileEntry;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Recursive delete with progress and cancellation; symlinks are removed, never followed. */
public final class DeleteJob implements Job {
    private final List<Path> targets;
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final List<Problem> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<Planned> planned = Collections.synchronizedList(new ArrayList<>());
    private boolean dryRun;

    /** What a dry run found under one target: the number of items (files and folders) it would remove. */
    public record Planned(Path target, boolean directory, int items) { }

    /** Counts what would be deleted and stops. */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public List<Planned> planned() {
        synchronized (planned) {
            return new ArrayList<>(planned);
        }
    }
    private volatile String phase = "Scanning...";
    private volatile String current = "";
    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile long elapsedNanos;

    public DeleteJob(List<FileEntry> entries) {
        this.targets = entries.stream().map(FileEntry::path).toList();
    }

    @Override
    public String title() {
        return "Deleting";
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
    public boolean tracksBytes() {
        return false;
    }

    @Override
    public long bytesTotal() {
        return 0;
    }

    @Override
    public long bytesDone() {
        return 0;
    }

    @Override
    public int filesTotal() {
        return total.get();
    }

    @Override
    public int filesDone() {
        return done.get();
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
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        try {
            for (Path target : targets) {
                int items = TreeDeleter.count(target, Parallel.threads(target), () -> cancelled);
                total.addAndGet(items);
                planned.add(new Planned(target, java.nio.file.Files.isDirectory(target,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS), items));
            }
            if (dryRun) {
                return;
            }
            phase = "Deleting";
            for (Path target : targets) {
                if (cancelled) {
                    break;
                }
                new TreeDeleter(Parallel.threads(target), () -> cancelled, listener).delete(target);
            }
        } catch (Throwable t) {
            errors.add(new Problem("(internal error)", String.valueOf(t)));
        } finally {
            elapsedNanos = System.nanoTime() - start;
            finished = true;
        }
    }

    private final TreeDeleter.Listener listener = new TreeDeleter.Listener() {
        @Override
        public void deleting(Path path) {
            current = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        }

        @Override
        public void deleted() {
            done.incrementAndGet();
        }

        @Override
        public void failed(Path path, IOException error) {
            errors.add(new Problem(path.toString(), reason(error)));
        }
    };
    @Override
    public String summary() {
        StringBuilder text = new StringBuilder(cancelled ? "Cancelled. " : "");
        text.append("Deleted ").append(done.get()).append(done.get() == 1 ? " item" : " items")
                .append(String.format(" in %.1f s", Math.max(0.001, elapsedNanos / 1e9)));
        if (!errors.isEmpty()) {
            text.append(", ").append(errors.size()).append(errors.size() == 1 ? " error" : " errors");
        }
        return text.toString();
    }

    static String reason(IOException e) {
        if (e instanceof AccessDeniedException) {
            return "Access denied";
        }
        if (e instanceof DirectoryNotEmptyException) {
            return "Folder is not empty";
        }
        if (e instanceof java.nio.file.FileAlreadyExistsException) {
            return "A file with this name already exists";
        }
        if (e instanceof java.nio.file.FileSystemException fse && fse.getReason() != null) {
            String reason = fse.getReason().strip();
            return reason.startsWith("The process cannot access the file")
                    ? "File is in use by another program" : reason;
        }
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
