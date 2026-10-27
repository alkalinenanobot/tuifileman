package org.ds.tuifileman.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.Fixtures;
import org.ds.tuifileman.compare.DirCompare.Item;
import org.ds.tuifileman.compare.DirCompare.Mode;
import org.ds.tuifileman.ignore.IgnoreMatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirCompareIgnoreTest {
    @TempDir
    Path temp;
    Path left;
    Path right;
    final AtomicInteger ignored = new AtomicInteger();

    @BeforeEach
    void folders() throws IOException {
        left = Fixtures.dir(temp, "left");
        right = Fixtures.dir(temp, "right");
    }

    private List<Item> scan(IgnoreMatcher matcher) throws IOException {
        return DirCompare.scan(left, right, true, false, new AtomicBoolean(), new AtomicInteger(), matcher, ignored);
    }

    private static boolean has(List<Item> items, String relative) {
        return items.stream().anyMatch(item -> item.relative().equals(relative));
    }

    private static Item item(List<Item> items, String relative) {
        return items.stream().filter(item -> item.relative().equals(relative)).findFirst().orElseThrow();
    }

    @Test
    void anIgnoredFolderIsOneItemAndIsNotEntered() throws IOException {
        Fixtures.file(left, ".gitignore", "node_modules/\n*.log\n");
        Fixtures.file(left, "node_modules/pkg/index.js", "js");
        Fixtures.file(left, "node_modules/pkg/deep/more.js", "js");
        Fixtures.file(left, "app.log", "log");
        Fixtures.file(left, "src/main.c", "c");
        List<Item> items = scan(IgnoreMatcher.git(List.of(), true, left, right));
        assertEquals(DirCompare.Status.ONLY_LEFT, item(items, "src/main.c").status());
        assertEquals(DirCompare.Status.IGNORED, item(items, "node_modules").status());
        assertTrue(item(items, "node_modules").directory());
        assertFalse(has(items, "node_modules/pkg"), "an ignored folder is not entered");
        assertFalse(has(items, "node_modules/pkg/index.js"));
        assertEquals(DirCompare.Status.IGNORED, item(items, "app.log").status());
        assertEquals(2, ignored.get(), "the folder counts once, not once per file inside");
    }

    @Test
    void anEntryIgnoredOnOneSideIsIgnoredOnBothAndSaysWhichSide() throws IOException {
        Fixtures.file(right, ".gitignore", "*.bak\n");
        Fixtures.file(left, "a.bak", "x");
        Fixtures.file(right, "a.bak", "y");
        Fixtures.file(left, "only-left.bak", "z");
        List<Item> items = scan(IgnoreMatcher.git(List.of(), true, left, right));
        Item both = item(items, "a.bak");
        assertEquals(DirCompare.Status.IGNORED, both.status());
        assertEquals(IgnoreMatcher.BY_RIGHT, both.ignoredBy());
        assertTrue(both.left() != null && both.right() != null, "both sides are shown");
        Item one = item(items, "only-left.bak");
        assertEquals(DirCompare.Status.IGNORED, one.status());
        assertEquals(IgnoreMatcher.BY_RIGHT, one.ignoredBy(), "the right rules ignore a file only the left has");
        assertEquals(2, ignored.get());
    }

    @Test
    void explicitRulesCountForBothSides() throws IOException {
        Path rules = Files.writeString(temp.resolve("rules"), "*.tmp\n");
        Fixtures.file(left, "a.tmp", "x");
        List<Item> items = scan(IgnoreMatcher.git(List.of(rules), false, left, right));
        assertEquals(IgnoreMatcher.BY_LEFT | IgnoreMatcher.BY_RIGHT, item(items, "a.tmp").ignoredBy());
    }

    @Test
    void anIgnoredItemAllowsNoActionAndNoModeProposesOne() throws IOException {
        Path rules = Files.writeString(temp.resolve("rules"), "*.tmp\n");
        Fixtures.file(left, "a.tmp", "x");
        Fixtures.file(right, "b.tmp", "y");
        List<Item> items = scan(IgnoreMatcher.git(List.of(rules), false, left, right));
        for (Mode mode : Mode.values()) {
            DirCompare.apply(items, mode);
            for (Item item : items) {
                assertEquals(DirCompare.Action.NONE, item.action(), mode + " " + item.relative());
            }
        }
        for (DirCompare.Action action : DirCompare.Action.values()) {
            assertEquals(action == DirCompare.Action.NONE, item(items, "a.tmp").allows(action), action.toString());
        }
    }

    @Test
    void mirroringNeverDeletesAnIgnoredTargetEntry() throws IOException {
        Path rules = Files.writeString(temp.resolve("rules"), "cache/\n*.tmp\n");
        Fixtures.file(left, "keep.txt", "k");
        Fixtures.file(right, "cache/data.bin", "d");
        Fixtures.file(right, "x.tmp", "t");
        Fixtures.file(right, "extra.txt", "e");
        List<Item> items = scan(IgnoreMatcher.git(List.of(rules), false, left, right));
        DirCompare.apply(items, Mode.MIRROR_TO_RIGHT);
        assertEquals(DirCompare.Action.DELETE_RIGHT, item(items, "extra.txt").action());
        assertEquals(DirCompare.Action.NONE, item(items, "cache").action());
        assertEquals(DirCompare.Action.NONE, item(items, "x.tmp").action());
        assertFalse(has(items, "cache/data.bin"));
    }

    @Test
    void aShallowScanListsIgnoredFilesButNotIgnoredFolders() throws IOException {
        Path rules = Files.writeString(temp.resolve("rules"), "*.tmp\nbuild/\n");
        Fixtures.file(left, "a.tmp", "x");
        Fixtures.file(left, "build/o.obj", "o");
        List<Item> items = DirCompare.scan(left, right, false, false, new AtomicBoolean(), new AtomicInteger(),
                IgnoreMatcher.git(List.of(rules), false, left, right), ignored);
        assertTrue(has(items, "a.tmp"));
        assertFalse(has(items, "build"));
        assertEquals(2, ignored.get(), "still counted");
    }

    @Test
    void withoutAMatcherNothingChanges() throws IOException {
        Fixtures.file(left, ".gitignore", "*.log\n");
        Fixtures.file(left, "a.log", "x");
        List<Item> items = DirCompare.scan(left, right, true, false, new AtomicBoolean(), new AtomicInteger());
        assertEquals(DirCompare.Status.ONLY_LEFT, item(items, "a.log").status());
    }

    @Test
    void anExcludedFolderIsNeitherListedNorEnteredNorCountedAsIgnored() throws IOException {
        Fixtures.file(left, ".gitignore", "*.log\n");
        Fixtures.file(left, "node_modules/pkg/index.js", "js");
        Fixtures.file(right, "node_modules/pkg/index.js", "other");
        Fixtures.file(left, "skip.log", "x");
        Fixtures.file(left, "src/main.c", "c");
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Item> items = DirCompare.scan(left, right, true, false, new AtomicBoolean(), new AtomicInteger(),
                IgnoreMatcher.git(List.of(), true, left, right), ignored, relative -> {
                    asked.add(relative);
                    return relative.startsWith("node_modules") || relative.equals("skip.log");
                });
        assertFalse(has(items, "node_modules"));
        assertFalse(has(items, "skip.log"), "excluding wins over ignoring: no IGNORED row");
        assertEquals(0, ignored.get());
        assertTrue(has(items, "src/main.c"));
        assertFalse(asked.contains("node_modules/pkg"), "an excluded folder is not listed");
    }
}
