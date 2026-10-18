package org.ds.tuifileman.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
     */
    static <T> boolean copyFiles(List<T> tasks, ToLongFunction<T> size, Consumer<T> copy, int threads,
                                 BooleanSupplier cancelled) {
        List<T> small = new ArrayList<>();
        List<T> big = new ArrayList<>();
        for (T task : tasks) {
            (size.applyAsLong(task) >= BIG_FILE ? big : small).add(task);
        }
        // Big files stream at disk bandwidth, small ones are latency bound: running both at once
        // keeps the disk busier than one kind after the other.
        Thread bigWorker = new Thread(() -> {
            for (T task : big) {
                copy.accept(task);
            }
        }, "copy-big");
        bigWorker.setDaemon(true);
        bigWorker.start();
        ExecutorService pool = newPool(threads);
        try {
            for (T task : small) {
                pool.execute(() -> copy.accept(task));
            }
            pool.shutdown();
            while (!pool.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    pool.shutdownNow();
                }
            }
            bigWorker.join();
            return true;
        } catch (InterruptedException e) {
            pool.shutdownNow();
            return false;
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
