package org.ds.tuifileman.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * The parallel part of copy and directory sync. Small files go to a thread pool (per-file overhead dominates,
 * so parallelism wins); big files are copied one at a time on their own thread alongside it, as they stream
 * at disk bandwidth. Work with an order between parents and children (creating folders, deleting a sync's
 * planned items) runs one depth level at a time, each level in parallel.
 */
final class CopyScheduler {
    private static final long BIG_FILE = 32L << 20;

    /** Below this many items a pool costs more than it saves. */
    private static final int PARALLEL_ITEMS = 64;

    private CopyScheduler() { }

    /**
     * Runs {@code copy} for every task and returns when all have finished. Once {@code cancelled} turns true the
     * pool's workers are interrupted and queued tasks are dropped; {@code copy} itself has to notice the flag on
     * the big-file thread (and should check it first, for tasks that were already taken).
     *
     * @return false when the calling thread was interrupted: the caller is then to treat the job as cancelled
     * @throws RuntimeException the first one {@code copy} threw, once every other task has been attempted
     */
    static <T> boolean copyFiles(List<T> tasks, ToLongFunction<T> size, Consumer<T> copy, int threads,
                                 BooleanSupplier cancelled) {
        Failures failures = new Failures();
        Consumer<T> safeCopy = failures.guard(copy);
        List<T> small = new ArrayList<>();
        List<T> big = new ArrayList<>();
        for (T task : tasks) {
            (size.applyAsLong(task) >= BIG_FILE ? big : small).add(task);
        }
        // Big files stream at disk bandwidth, small ones are latency bound: running both at once
        // keeps the disk busier than one kind after the other.
        Thread bigWorker = new Thread(() -> {
            for (T task : big) {
                safeCopy.accept(task);
            }
        }, "copy-big");
        bigWorker.setDaemon(true);
        bigWorker.start();
        ExecutorService pool = newPool(threads);
        try {
            for (T task : small) {
                pool.execute(() -> safeCopy.accept(task));
            }
            pool.shutdown();
            while (!pool.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    pool.shutdownNow();
                }
            }
            bigWorker.join();
            failures.rethrow();
            return true;
        } catch (InterruptedException e) {
            pool.shutdownNow();
            return false;
        }
    }

    /**
     * Keeps the first exception of a task, so that one failing file neither ends a worker thread (the files behind it
     * would silently never be copied) nor goes unreported: it is thrown once every lane is done.
     */
    private static final class Failures {
        private final AtomicReference<RuntimeException> first = new AtomicReference<>();

        <T> Consumer<T> guard(Consumer<T> copy) {
            return task -> {
                try {
                    copy.accept(task);
                } catch (RuntimeException e) {
                    if (!first.compareAndSet(null, e)) {
                        first.get().addSuppressed(e);
                    }
                }
            };
        }

        void rethrow() {
            RuntimeException failure = first.get();
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * The lanes of {@link #copyFiles} for a list that is still being made: tasks are {@link #submit submitted} one at a
     * time (from any thread) and start at once, so copying overlaps with whatever produces the tasks.
     */
    static final class Feed<T> {
        private static final Object END = new Object();

        private final ToLongFunction<T> size;
        private final Consumer<T> copy;
        private final BooleanSupplier cancelled;
        private final ExecutorService pool;
        private final BlockingQueue<Object> big = new LinkedBlockingQueue<>();
        private final Failures failures = new Failures();
        private final Thread bigWorker;

        Feed(ToLongFunction<T> size, Consumer<T> copy, int threads, BooleanSupplier cancelled) {
            this.size = size;
            this.copy = failures.guard(copy);
            this.cancelled = cancelled;
            this.pool = newPool(threads);
            this.bigWorker = new Thread(this::takeBig, "copy-big");
            bigWorker.setDaemon(true);
            bigWorker.start();
        }

        @SuppressWarnings("unchecked")
        private void takeBig() {
            try {
                for (Object task = big.take(); task != END; task = big.take()) {
                    copy.accept((T) task);
                }
            } catch (InterruptedException ignored) {
                // the job is over
            }
        }

        void submit(T task) {
            if (size.applyAsLong(task) >= BIG_FILE) {
                big.add(task);
            } else {
                pool.execute(() -> copy.accept(task));
            }
        }

        /**
         * No more tasks: waits until every submitted one has finished.
         *
         * @return false when the calling thread was interrupted: the caller is then to treat the job as cancelled
         * @throws RuntimeException the first one a task threw, once all the others have run
         */
        boolean finish() {
            big.add(END);
            pool.shutdown();
            try {
                while (!pool.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        pool.shutdownNow();
                    }
                }
                bigWorker.join();
                failures.rethrow();
                return true;
            } catch (InterruptedException e) {
                pool.shutdownNow();
                bigWorker.interrupt();
                return false;
            }
        }
    }

    /**
     * Runs {@code action} for every item, one depth level at a time: the items of one depth run in parallel, the
     * next depth only starts once they are done. {@code items} must be grouped by {@code depth} in the order the
     * levels are to run (ascending to create parents first, descending to delete children first).
     *
     * @return false when interrupted or {@code action} threw: the caller is then to treat the job as cancelled
     */
    static <T> boolean byLevel(List<T> items, ToIntFunction<T> depth, Consumer<T> action, int threads,
                               BooleanSupplier cancelled) {
        if (items.size() < PARALLEL_ITEMS) {
            for (T item : items) {
                action.accept(item);
            }
            return true;
        }
        ExecutorService pool = newPool(threads);
        try {
            int from = 0;
            while (from < items.size() && !cancelled.getAsBoolean()) {
                int level = depth.applyAsInt(items.get(from));
                int to = from;
                List<Future<?>> futures = new ArrayList<>();
                while (to < items.size() && depth.applyAsInt(items.get(to)) == level) {
                    T item = items.get(to++);
                    futures.add(pool.submit(() -> action.accept(item)));
                }
                for (Future<?> future : futures) {
                    future.get();
                }
                from = to;
            }
            return true;
        } catch (InterruptedException | ExecutionException e) {
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ExecutorService newPool(int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "copy-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
