package org.ds.tuifileman.compare;

import static org.ds.tuifileman.Fixtures.dir;
import static org.ds.tuifileman.Fixtures.file;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ds.tuifileman.compare.DirCompare.Item;
import org.ds.tuifileman.compare.DirCompare.Status;
import org.ds.tuifileman.ignore.IgnoreMatcher;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** With a content verdict the bytes decide, not the times; without it the direction (newer) is kept for a merge. */
class DirCompareContentVerdictTest {
    @TempDir
    Path root;

    private Map<String, Status> scan(boolean verdict) throws Exception {
        List<Item> items = DirCompare.scan(dir(root, "left"), dir(root, "right"), true, true, verdict,
                new AtomicBoolean(), new AtomicInteger(), IgnoreMatcher.NONE, new AtomicInteger(), p -> false,
                DirCompare.TIME_TOLERANCE_MILLIS, new ConcurrentLinkedQueue<>());
        Map<String, Status> result = new TreeMap<>();
        for (Item item : items) {
            result.put(item.relative(), item.status());
        }
        return result;
    }

    private void fixture() throws Exception {
        file(root.resolve("left"), "size.txt", "abc", 60_000);
        file(root.resolve("right"), "size.txt", "abcdef", 0);
        file(root.resolve("left"), "bytes.txt", "abc", 0);
        file(root.resolve("right"), "bytes.txt", "abd", 60_000);
        file(root.resolve("left"), "same.txt", "abc", 60_000);
        file(root.resolve("right"), "same.txt", "abc", 0);
    }

    @Test
    void theVerdictFollowsTheBytesWhateverTheTimes() throws Exception {
        fixture();
        Map<String, Status> status = scan(true);
        assertEquals(Status.DIFFERENT, status.get("size.txt"), "other size, left is newer");
        assertEquals(Status.DIFFERENT, status.get("bytes.txt"), "other bytes, right is newer");
        assertEquals(Status.SAME, status.get("same.txt"), "equal bytes are the same whatever the times");
    }

    @Test
    void withoutTheVerdictTheNewerSideIsNamed() throws Exception {
        fixture();
        Map<String, Status> status = scan(false);
        assertEquals(Status.LEFT_NEWER, status.get("size.txt"));
        assertEquals(Status.RIGHT_NEWER, status.get("bytes.txt"));
        assertEquals(Status.SAME, status.get("same.txt"));
    }
}
