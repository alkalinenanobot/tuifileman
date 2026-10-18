package org.ds.tuifileman.app;

import org.ds.tuifileman.compare.DirCompare.Action;
import org.ds.tuifileman.compare.DirCompare.Item;
import org.ds.tuifileman.fs.Parallel;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes the actions chosen in the directory sync view: folders first (parents before children), then files
 * through {@link CopyScheduler} (small ones in parallel, big ones alongside), deletions last (deepest level
 * first, each level in parallel). Files are written to a temporary name and renamed, and keep the source's
 * modified time so they compare equal afterwards.
 */
public final class SyncJob implements Job {
    private final Path leftRoot;
    private final Path rightRoot;
    private final List<Item> folders = new ArrayList<>();
    private final List<Item> files = new ArrayList<>();
    private final List<Item> deletes = new ArrayList<>();
    private final AtomicLong bytesTotal = new AtomicLong();
    private final AtomicLong bytesDone = new AtomicLong();
    private final AtomicInteger filesDone = new AtomicInteger();
    private final AtomicInteger copied = new AtomicInteger();
    private final AtomicInteger deleted = new AtomicInteger();
    private final List<Problem> errors = Collections.synchronizedList(new ArrayList<>());
    private volatile String phase = "Preparing...";
    private volatile String current = "";
    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile long elapsedNanos;

    public SyncJob(Path leftRoot, Path rightRoot, List<Item> items) {
        this.leftRoot = leftRoot;
        this.rightRoot = rightRoot;
        for (Item item : items) {
            switch (item.action()) {
                case TO_LEFT, TO_RIGHT -> {
                    if (item.directory()) {
                        folders.add(item);
                    } else {
                        files.add(item);
                        bytesTotal.addAndGet(size(item));
                    }
                }
                case DELETE_LEFT, DELETE_RIGHT -> deletes.add(item);
                default -> { }
            }
        }
        // Stable sorts by depth, as CopyScheduler.byLevel needs them: parents created first, children deleted first.
        folders.sort(Comparator.comparingInt(this::depth));
        deletes.sort(Comparator.comparingInt(this::depth).reversed());
    }

    private static long size(Item item) {
        return item.action() == Action.TO_RIGHT ? item.left().size() : item.right().size();
    }

    private int depth(Item item) {
        return leftRoot.resolve(item.relative()).getNameCount();
    }

    @Override
    public String title() {
        return "Synchronizing";
    }

    /** Files copied so far (folders created are not counted). */
    public int copied() {
        return copied.get();
    }

    public int deleted() {
        return deleted.get();
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
        return true;
    }

    @Override
    public long bytesTotal() {
        return bytesTotal.get();
    }

    @Override
    public long bytesDone() {
        return bytesDone.get();
    }

    @Override
    public int filesTotal() {
        return folders.size() + files.size() + deletes.size();
    }

    @Override
    public int filesDone() {
        return filesDone.get();
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
    public String summary() {
        StringBuilder text = new StringBuilder(cancelled ? "Cancelled. " : "Synchronized: ");
        text.append(copied.get()).append(" copied, ").append(deleted.get()).append(" deleted")
                .append(String.format(" in %.1f s", Math.max(0.001, elapsedNanos / 1e9)));
        if (!errors.isEmpty()) {
            text.append(", ").append(errors.size()).append(errors.size() == 1 ? " error" : " errors");
        }
        return text.toString();
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        try {
            phase = "Copying";
            int threads = Parallel.threads(leftRoot, rightRoot);
            if (!CopyScheduler.byLevel(folders, this::depth, this::copyItem, threads, () -> cancelled)
                    || !CopyScheduler.copyFiles(files, SyncJob::size, this::copyItem, threads, () -> cancelled)) {
                cancelled = true;
            }
            phase = "Deleting";
            if (!deletes.isEmpty() && !cancelled
                    && !CopyScheduler.byLevel(deletes, this::depth, this::deleteItem, deleteThreads(), () -> cancelled)) {
                cancelled = true;
            }
        } finally {
            elapsedNanos = System.nanoTime() - start;
            finished = true;
        }
    }

    /** Deletes touch only their own side: a slower other side must not hold them back. */
    private int deleteThreads() {
        boolean onLeft = false;
        boolean onRight = false;
        for (Item item : deletes) {
            if (item.action() == Action.DELETE_LEFT) {
                onLeft = true;
            } else {
                onRight = true;
            }
        }
        return onLeft && onRight ? Parallel.threads(leftRoot, rightRoot) : Parallel.threads(onLeft ? leftRoot : rightRoot);
    }

    /** Called by several threads at once; folders before any file, so no file creates a folder planned as new. */
    private void copyItem(Item item) {
        if (cancelled) {
            return;
        }
        boolean toRight = item.action() == Action.TO_RIGHT;
        Path source = (toRight ? leftRoot : rightRoot).resolve(item.relative());
        Path target = (toRight ? rightRoot : leftRoot).resolve(item.relative());
        // Absent when scanned: whatever is there now (another name differing only in letter case on a
        // case-insensitive side, or a file created meanwhile) is not ours to replace.
        boolean replace = (toRight ? item.right() : item.left()) != null;
        current = item.relative();
        try {
            if (!item.directory()) {
                if (copyFile(source, target, replace)) {
                    copied.incrementAndGet();
                }
            } else if (replace) {
                Files.createDirectories(target);
            } else {
                Files.createDirectory(target);
            }
        } catch (FileAlreadyExistsException e) {
            errors.add(new Problem(target.toString(), CopyJob.APPEARED));
        } catch (IOException e) {
            // Cancelling interrupts the pool's workers, which NIO reports as an IOException: not a failure of the file.
            if (!cancelled && !(e instanceof ClosedByInterruptException) && !(e instanceof InterruptedIOException)) {
                errors.add(new Problem(target.toString(), DeleteJob.reason(e)));
            }
        } finally {
            filesDone.incrementAndGet();
        }
    }

    /** Returns false when cancelled half way (the partial temp file is removed). */
    private boolean copyFile(Path source, Path target, boolean replace) throws IOException {
        Files.createDirectories(target.getParent());
        // A random name, created only if it does not exist: never deletes a user's file of that name, and two
        // syncs into one folder cannot take each other's temp file.
        Path temp = target.resolveSibling(target.getFileName() + ".tfm-"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt()) + ".tmp");
        try {
            ChannelCopy.copy(source, temp, false, bytesDone::addAndGet, () -> cancelled);
        } catch (NativeCopy.Cancelled e) {
            return false;
        }
        try {
            if (!replace) {
                Files.move(temp, target);
            } else {
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (AccessDeniedException e) {
                    clearReadOnly(target);
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return true;
    }

    /** Called by several threads at once; everything below a folder was deleted (or failed) on an earlier level. */
    private void deleteItem(Item item) {
        if (cancelled) {
            return;
        }
        boolean left = item.action() == Action.DELETE_LEFT;
        Path target = (left ? leftRoot : rightRoot).resolve(item.relative());
        current = item.relative();
        try {
            try {
                Files.delete(target);
            } catch (AccessDeniedException e) {
                clearReadOnly(target);
                Files.delete(target);
            }
            deleted.incrementAndGet();
        } catch (IOException e) {
            errors.add(new Problem(target.toString(), DeleteJob.reason(e)));
        } finally {
            filesDone.incrementAndGet();
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            Files.setAttribute(path, "dos:readonly", false);
        } catch (IOException | UnsupportedOperationException ignored) { }
    }
}
