package org.ds.tuifileman.compare;

import static org.ds.tuifileman.Fixtures.dir;
import static org.ds.tuifileman.Fixtures.file;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.compare.DirCompare.Action;
import org.ds.tuifileman.compare.DirCompare.Item;
import org.ds.tuifileman.compare.DirCompare.Mode;
import org.ds.tuifileman.compare.DirCompare.Status;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirCompareTest {
    @TempDir
    Path root;

    private Path left() throws Exception {
        return dir(root, "left");
    }

    private Path right() throws Exception {
        return dir(root, "right");
    }

    private Map<String, Item> scan(boolean recursive, boolean contents) throws Exception {
        List<Item> items = DirCompare.scan(left(), right(), recursive, contents, new AtomicBoolean(), new AtomicInteger());
        Map<String, Item> byPath = new TreeMap<>();
        for (Item item : items) {
            byPath.put(item.relative().replace('\\', '/'), item);
        }
        return byPath;
    }

    private Map<String, Item> scan() throws Exception {
        return scan(true, false);
    }

    @Test
    void classifiesEveryStatus() throws Exception {
        file(left(), "only-left.txt", "l");
        file(right(), "only-right.txt", "r");
        file(left(), "same.txt", "abc", 0);
        file(right(), "same.txt", "abc", 0);
        file(left(), "left-newer.txt", "abc", 60_000);
        file(right(), "left-newer.txt", "abc", 0);
        file(left(), "right-newer.txt", "abc", 0);
        file(right(), "right-newer.txt", "abc", 60_000);
        file(left(), "different.txt", "abc", 0);
        file(right(), "different.txt", "abcdef", 0);
        file(left(), "mismatch", "a file");
        dir(right(), "mismatch");

        Map<String, Item> items = scan();
        assertEquals(Status.ONLY_LEFT, items.get("only-left.txt").status());
        assertEquals(Status.ONLY_RIGHT, items.get("only-right.txt").status());
        assertEquals(Status.SAME, items.get("same.txt").status());
        assertEquals(Status.LEFT_NEWER, items.get("left-newer.txt").status());
        assertEquals(Status.RIGHT_NEWER, items.get("right-newer.txt").status());
        assertEquals(Status.DIFFERENT, items.get("different.txt").status());
        assertEquals(Status.TYPE_MISMATCH, items.get("mismatch").status());
    }

    @Test
    void timesWithinTheToleranceAreEqual() throws Exception {
        file(left(), "a.txt", "abc", 1_900);
        file(right(), "a.txt", "abc", 0);
        file(left(), "b.txt", "abc", 2_100);
        file(right(), "b.txt", "abc", 0);
        Map<String, Item> items = scan();
        assertEquals(Status.SAME, items.get("a.txt").status());
        assertEquals(Status.LEFT_NEWER, items.get("b.txt").status());
    }

    @Test
    void aCustomToleranceMovesTheLineBetweenSameAndNewer() throws Exception {
        file(left(), "a.txt", "abc", 30_000);
        file(right(), "a.txt", "abc", 0);
        file(left(), "c.txt", "abc", 30_000);
        file(right(), "c.txt", "abd", 0);
        for (boolean contents : new boolean[] {false, true}) {
            Map<String, Item> strict = byPath(DirCompare.scan(left(), right(), true, contents, new AtomicBoolean(),
                    new AtomicInteger(), org.ds.tuifileman.ignore.IgnoreMatcher.NONE, new AtomicInteger(), p -> false, 0,
                    new java.util.concurrent.ConcurrentLinkedQueue<>()));
            assertEquals(contents ? Status.SAME : Status.LEFT_NEWER, strict.get("a.txt").status(),
                    "with --content equal bytes are the same whatever the times");
            Map<String, Item> loose = byPath(DirCompare.scan(left(), right(), true, contents, new AtomicBoolean(),
                    new AtomicInteger(), org.ds.tuifileman.ignore.IgnoreMatcher.NONE, new AtomicInteger(), p -> false, 60_000,
                    new java.util.concurrent.ConcurrentLinkedQueue<>()));
            assertEquals(Status.SAME, loose.get("a.txt").status());
            if (contents) {
                assertEquals(Status.DIFFERENT, loose.get("c.txt").status(),
                        "equal times but different bytes stay different");
                assertEquals(Status.LEFT_NEWER, strict.get("c.txt").status());
            }
        }
    }

    private static Map<String, Item> byPath(List<Item> items) {
        Map<String, Item> byPath = new TreeMap<>();
        for (Item item : items) {
            byPath.put(item.relative().replace('\\', '/'), item);
        }
        return byPath;
    }

    @Test
    void foldersOnBothSidesAreWalkedButNotListed() throws Exception {
        file(left(), "sub/deep/x.txt", "x");
        dir(right(), "sub");
        Map<String, Item> items = scan();
        assertFalse(items.containsKey("sub"));
        assertTrue(items.get("sub/deep").directory());
        assertEquals(Status.ONLY_LEFT, items.get("sub/deep").status());
        assertEquals(Status.ONLY_LEFT, items.get("sub/deep/x.txt").status());
    }

    @Test
    void shallowScanStaysOnTheTopLevel() throws Exception {
        file(left(), "top.txt", "x");
        file(left(), "sub/deep.txt", "x");
        dir(right(), "sub");
        Map<String, Item> items = scan(false, false);
        assertTrue(items.containsKey("top.txt"));
        assertEquals(1, items.size(), "folders are not entered and not listed: " + items.keySet());
    }

    @Test
    void contentCompareFindsSameSizeSameTimeDifferences() throws Exception {
        file(left(), "a.txt", "aaaa", 0);
        file(right(), "a.txt", "aaab", 0);
        file(left(), "b.txt", "same", 0);
        file(right(), "b.txt", "same", 0);
        assertEquals(Status.SAME, scan(true, false).get("a.txt").status(), "size and time cannot tell");
        Map<String, Item> items = scan(true, true);
        assertEquals(Status.DIFFERENT, items.get("a.txt").status());
        assertEquals(Status.SAME, items.get("b.txt").status());
    }

    @Test
    void cancelReturnsWhatWasFoundSoFarWithoutFailing() throws Exception {
        for (int i = 0; i < 50; i++) {
            file(left(), "f" + i + ".txt", "x");
        }
        AtomicBoolean cancel = new AtomicBoolean(true);
        List<Item> items = DirCompare.scan(left(), right(), true, false, cancel, new AtomicInteger());
        assertTrue(items.size() <= 50);
    }

    @Test
    void progressCountsExaminedEntries() throws Exception {
        file(left(), "a.txt", "x");
        file(left(), "sub/b.txt", "x");
        AtomicInteger progress = new AtomicInteger();
        DirCompare.scan(left(), right(), true, false, new AtomicBoolean(), progress);
        assertTrue(progress.get() >= 3, "a.txt, sub and b.txt: " + progress.get());
    }

    @Test
    void theResultIsInTreeOrder() throws Exception {
        file(left(), "b.txt", "x");
        file(left(), "a/z.txt", "x");
        file(right(), "a/y.txt", "x");
        file(left(), "c/inner.txt", "x");
        List<String> order = DirCompare.scan(left(), right(), true, false, new AtomicBoolean(), new AtomicInteger())
                .stream().map(Item::relative).toList();
        assertEquals(List.of("a/y.txt", "a/z.txt", "b.txt", "c", "c/inner.txt"), order);
    }

    // ---- actions ----------------------------------------------------------------------------------------------

    private Map<String, Item> mixed() throws Exception {
        file(left(), "only-left.txt", "l");
        file(right(), "only-right.txt", "r");
        file(left(), "left-newer.txt", "abc", 60_000);
        file(right(), "left-newer.txt", "abc", 0);
        file(left(), "right-newer.txt", "abc", 0);
        file(right(), "right-newer.txt", "abc", 60_000);
        file(left(), "different.txt", "abc", 0);
        file(right(), "different.txt", "abcdef", 0);
        file(left(), "same.txt", "abc", 0);
        file(right(), "same.txt", "abc", 0);
        return scan();
    }

    @Test
    void mergeCopiesTheNewerOrMissingSideAndNeverDeletes() throws Exception {
        Map<String, Item> items = mixed();
        DirCompare.apply(List.copyOf(items.values()), Mode.MERGE);
        assertEquals(Action.TO_RIGHT, items.get("only-left.txt").action());
        assertEquals(Action.TO_LEFT, items.get("only-right.txt").action());
        assertEquals(Action.TO_RIGHT, items.get("left-newer.txt").action());
        assertEquals(Action.TO_LEFT, items.get("right-newer.txt").action());
        assertEquals(Action.NONE, items.get("different.txt").action(), "a conflict is left alone");
        assertEquals(Action.NONE, items.get("same.txt").action());
    }

    @Test
    void mirrorToRightMakesTheRightSideLikeTheLeft() throws Exception {
        Map<String, Item> items = mixed();
        DirCompare.apply(List.copyOf(items.values()), Mode.MIRROR_TO_RIGHT);
        assertEquals(Action.TO_RIGHT, items.get("only-left.txt").action());
        assertEquals(Action.DELETE_RIGHT, items.get("only-right.txt").action());
        assertEquals(Action.TO_RIGHT, items.get("right-newer.txt").action(), "mirror overwrites even a newer target");
        assertEquals(Action.TO_RIGHT, items.get("different.txt").action());
        assertEquals(Action.NONE, items.get("same.txt").action());
    }

    @Test
    void mirrorToLeftMakesTheLeftSideLikeTheRight() throws Exception {
        Map<String, Item> items = mixed();
        DirCompare.apply(List.copyOf(items.values()), Mode.MIRROR_TO_LEFT);
        assertEquals(Action.DELETE_LEFT, items.get("only-left.txt").action());
        assertEquals(Action.TO_LEFT, items.get("only-right.txt").action());
        assertEquals(Action.TO_LEFT, items.get("left-newer.txt").action());
        assertEquals(Action.TO_LEFT, items.get("different.txt").action());
    }

    @Test
    void clearRemovesAllActions() throws Exception {
        Map<String, Item> items = mixed();
        DirCompare.apply(List.copyOf(items.values()), Mode.MERGE);
        DirCompare.apply(List.copyOf(items.values()), Mode.CLEAR);
        assertTrue(items.values().stream().allMatch(item -> item.action() == Action.NONE));
    }

    @Test
    void actionsThatCannotHappenAreNotAllowed() throws Exception {
        file(left(), "only-left.txt", "l");
        file(left(), "mismatch", "x");
        dir(right(), "mismatch");
        Map<String, Item> items = scan();
        Item onlyLeft = items.get("only-left.txt");
        assertTrue(onlyLeft.allows(Action.TO_RIGHT));
        assertTrue(onlyLeft.allows(Action.DELETE_LEFT));
        assertFalse(onlyLeft.allows(Action.TO_LEFT), "there is nothing on the right to copy");
        assertFalse(onlyLeft.allows(Action.DELETE_RIGHT));
        Item mismatch = items.get("mismatch");
        assertFalse(mismatch.allows(Action.TO_RIGHT), "a file cannot replace a folder");
        assertEquals(Action.NONE, DirCompare.proposed(mismatch, Mode.MIRROR_TO_RIGHT));
    }

    // ---- itemFor ----------------------------------------------------------------------------------------------

    @Test
    void itemForBuildsAnItemFromTheCurrentState() throws Exception {
        file(left(), "a.txt", "abc", 60_000);
        file(right(), "a.txt", "abc", 0);
        file(left(), "b.txt", "x");
        Item both = DirCompare.itemFor(left(), right(), "a.txt", null);
        assertEquals(Status.LEFT_NEWER, both.status());
        assertEquals(Status.ONLY_LEFT, DirCompare.itemFor(left(), right(), "b.txt", null).status());
        assertNull(DirCompare.itemFor(left(), right(), "nope.txt", null));
        assertEquals(Status.DIFFERENT, DirCompare.itemFor(left(), right(), "a.txt", Status.DIFFERENT).status(),
                "a stored status stands in for the size/time comparison");
        assertNotNull(both.left());
    }

    @Test
    void caseDistinctNamesOfOneSideStaySeparate() throws Exception {
        file(root, "probe", "x");
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.exists(root.resolve("PROBE")),
                "needs a case-sensitive file system");
        file(left(), "Foo.txt", "upper", 0);
        file(left(), "foo.txt", "lower", 0);
        file(right(), "foo.txt", "lower", 0);
        file(left(), "Docs/a.txt", "a");
        file(left(), "docs/b.txt", "b");
        Map<String, Item> items = scan();
        assertEquals(List.of("Docs", "Docs/a.txt", "docs", "docs/b.txt", "Foo.txt", "foo.txt"), List.copyOf(
                DirCompare.scan(left(), right(), true, false, new AtomicBoolean(), new AtomicInteger()).stream()
                        .map(item -> item.relative().replace('\\', '/')).toList()));
        assertEquals(Status.ONLY_LEFT, items.get("Foo.txt").status());
        assertEquals(Status.SAME, items.get("foo.txt").status());
        assertEquals(Status.ONLY_LEFT, items.get("docs/b.txt").status());
    }

    @Test
    void namesDifferingOnlyInCaseArePairedAcrossTheSides() throws Exception {
        file(left(), "Readme.txt", "abc", 0);
        file(right(), "README.TXT", "abc", 0);
        Map<String, Item> items = scan();
        assertEquals(1, items.size(), items.keySet().toString());
        assertEquals(Status.SAME, items.get("Readme.txt").status());
    }

    @Test
    void anUnreadableFolderIsReportedAndLeftOutOnBothSides() throws Exception {
        file(left(), "locked/secret.txt", "s");
        file(right(), "locked/secret.txt", "s");
        file(right(), "locked/extra.txt", "only right");
        file(left(), "ok.txt", "x", 0);
        file(right(), "ok.txt", "x", 0);
        try (AutoCloseable locked = org.ds.tuifileman.Fixtures.unreadable(left().resolve("locked"))) {
            org.junit.jupiter.api.Assumptions.assumeTrue(locked != null, "the user can read any folder (root)");
            java.util.Queue<DirCompare.Unreadable> unreadable = new java.util.concurrent.ConcurrentLinkedQueue<>();
            List<Item> items = DirCompare.scan(left(), right(), true, false, new AtomicBoolean(), new AtomicInteger(),
                    org.ds.tuifileman.ignore.IgnoreMatcher.NONE, new AtomicInteger(), relative -> false,
                    DirCompare.TIME_TOLERANCE_MILLIS, unreadable);
            assertEquals(List.of(left().resolve("locked")), unreadable.stream().map(DirCompare.Unreadable::path).toList());
            // Listing the readable side alone would make "extra.txt" one-sided, and a mirror would delete it.
            assertEquals(List.of("ok.txt"), items.stream().map(Item::relative).toList());
        }
    }
}
