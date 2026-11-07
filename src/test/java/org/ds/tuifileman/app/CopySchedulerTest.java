package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class CopySchedulerTest {
    private static final long BIG = 40L << 20;

    /** The task is its own size: small ones go to the pool, {@link #BIG} ones to the big-file thread. */
    private static final List<Long> TASKS = List.of(1L, 2L, 3L, BIG, BIG + 1, BIG + 2);

    private static void failOn(Set<Long> done, long task) {
        if (task == 2L || task == BIG) {
            throw new IllegalStateException("task " + task);
        }
        done.add(task);
    }

    @Test
    void aFailingTaskDoesNotStopTheOthersAndIsThrownAfterwards() {
        Set<Long> done = ConcurrentHashMap.newKeySet();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CopyScheduler.copyFiles(TASKS, size -> size, task -> failOn(done, task), 2, () -> false));
        assertEquals(Set.of(1L, 3L, BIG + 1, BIG + 2), done, "the files behind a failing one are still attempted");
        assertEquals(1, failure.getSuppressed().length, "the second failure is kept too");
    }

    @Test
    void aFeedKeepsRunningAfterAFailingTask() {
        Set<Long> done = ConcurrentHashMap.newKeySet();
        CopyScheduler.Feed<Long> feed = new CopyScheduler.Feed<>(size -> size, task -> failOn(done, task), 2, () -> false);
        TASKS.forEach(feed::submit);
        assertThrows(IllegalStateException.class, feed::finish);
        assertEquals(Set.of(1L, 3L, BIG + 1, BIG + 2), done);
    }

    @Test
    void withoutFailuresEverythingRunsAndNothingIsThrown() {
        Set<Long> done = ConcurrentHashMap.newKeySet();
        assertTrue(CopyScheduler.copyFiles(TASKS, size -> size, done::add, 2, () -> false));
        assertEquals(Set.copyOf(TASKS), done);
    }
}
