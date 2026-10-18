package org.ds.tuifileman.app;

import org.ds.tuifileman.fs.Entries;
import org.ds.tuifileman.fs.Parallel;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Deletes and counts directory trees with one task per directory. Links and junctions are removed
 * themselves, never entered. Cold-cache delete of 211k files: 65 s sequential, 13 s with 16+ threads.
 */
final class TreeDeleter {
    interface Listener {
        void deleting(Path path);

        void deleted();

        void failed(Path path, IOException error);
    }

    private final int threads;
    private final BooleanSupplier cancelled;
    private final Listener listener;
    private final AtomicInteger failures = new AtomicInteger();

    TreeDeleter(int threads, BooleanSupplier cancelled, Listener listener) {
        this.threads = threads;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    /** Counts the items below (and including) root; the delete phase uses it for the progress total. */
    static int count(Path root, int threads, BooleanSupplier cancelled) {
        AtomicInteger total = new AtomicInteger();
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                return 1;
            }
            total.incrementAndGet();
            if (Entries.isPlainDirectory(attrs)) {
                pool.invoke(new Count(root, total, cancelled));
            }
        } finally {
            pool.shutdown();
        }
        return total.get();
    }

    private static final class Count extends RecursiveAction {
        private final Path dir;
        private final AtomicInteger total;
        private final BooleanSupplier cancelled;

        Count(Path dir, AtomicInteger total, BooleanSupplier cancelled) {
            this.dir = dir;
            this.total = total;
            this.cancelled = cancelled;
        }

        @Override
        protected void compute() {
            if (cancelled.getAsBoolean()) {
                return;
            }
            List<Count> subs = new ArrayList<>();
            Entries.list(dir, new Entries.Handler() {
                @Override
                public void entry(Path path, BasicFileAttributes attrs) {
                    total.incrementAndGet();
                    if (Entries.isPlainDirectory(attrs)) {
                        subs.add(new Count(path, total, cancelled));
                    }
                }

                @Override
                public void failed(Path path, IOException error) {
                    // The delete phase reports the real problem.
                }
            });
            invokeAll(subs);
        }
    }

    void delete(Path root) {
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                listener.failed(root, e);
                return;
            }
            if (Entries.isPlainDirectory(attrs)) {
                pool.invoke(new Delete(root));
            } else {
                deleteOne(root);
            }
        } finally {
            pool.shutdown();
        }
    }

    private final class Delete extends RecursiveAction {
        private final Path dir;

        Delete(Path dir) {
            this.dir = dir;
        }

        @Override
        protected void compute() {
            if (cancelled.getAsBoolean()) {
                return;
            }
            List<Delete> subs = new ArrayList<>();
            Entries.list(dir, new Entries.Handler() {
                @Override
                public void entry(Path path, BasicFileAttributes attrs) {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    if (Entries.isPlainDirectory(attrs)) {
                        subs.add(new Delete(path));
                    } else {
                        deleteOne(path);
                    }
                }

                @Override
                public void failed(Path path, IOException error) {
                    failures.incrementAndGet();
                    listener.failed(path, error);
                }
            });
            invokeAll(subs);
            if (!cancelled.getAsBoolean()) {
                deleteOne(dir);
            }
        }
    }

    private void deleteOne(Path path) {
        listener.deleting(path);
        try {
            try {
                Files.delete(path);
            } catch (AccessDeniedException e) {
                // Read-only files cannot be deleted on Windows until the flag is cleared.
                try {
                    Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                } catch (UnsupportedOperationException | IOException ignored) {
                    throw e;
                }
                Files.delete(path);
            }
            listener.deleted();
        } catch (DirectoryNotEmptyException e) {
            // Normally the result of a failed child, which has been reported already.
            if (failures.get() == 0) {
                failures.incrementAndGet();
                listener.failed(path, e);
            }
        } catch (IOException e) {
            failures.incrementAndGet();
            listener.failed(path, e);
        }
    }
}
