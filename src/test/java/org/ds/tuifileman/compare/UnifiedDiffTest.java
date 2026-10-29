package org.ds.tuifileman.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnifiedDiffTest {
    @TempDir
    Path dir;

    private List<String> diff(String left, String right, int context) throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), left);
        Path b = Files.writeString(dir.resolve("b.txt"), right);
        TextCompare compare = TextCompare.load(a, b);
        compare.compute(false, false);
        return UnifiedDiff.render(compare, context);
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void equalTextGivesNoHunk() throws Exception {
        assertTrue(diff(lines("a", "b"), lines("a", "b"), 3).isEmpty());
    }

    @Test
    void aChangeShowsRemovedBeforeAddedWithContext() throws Exception {
        List<String> out = diff(lines("1", "2", "3", "4", "5"), lines("1", "2", "X", "4", "5"), 1);
        assertEquals(List.of("@@ -2,3 +2,3 @@", " 2", "-3", "+X", " 4"), out);
    }

    @Test
    void aBlockOfSeveralLinesIsOneRemovedRunThenOneAddedRun() throws Exception {
        List<String> out = diff(lines("a", "b", "c", "d"), lines("a", "B", "C", "d"), 0);
        assertEquals(List.of("@@ -2,2 +2,2 @@", "-b", "-c", "+B", "+C"), out);
    }

    @Test
    void closeChangesShareAHunkAndFarOnesDoNot() throws Exception {
        String left = lines("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        String right = lines("1", "X", "3", "4", "5", "6", "7", "8", "Y", "10");
        List<String> separate = diff(left, right, 1);
        assertEquals(List.of("@@ -1,3 +1,3 @@", " 1", "-2", "+X", " 3", "@@ -8,3 +8,3 @@", " 8", "-9", "+Y", " 10"),
                separate);
        List<String> merged = diff(left, right, 3);
        assertEquals(1, merged.stream().filter(line -> line.startsWith("@@")).count(), merged.toString());
        assertEquals("@@ -1,10 +1,10 @@", merged.get(0));
    }

    @Test
    void insertionAndDeletionCountsFollowTheGnuConvention() throws Exception {
        assertEquals(List.of("@@ -2,0 +3 @@", "+new"), diff(lines("a", "b"), lines("a", "b", "new"), 0));
        assertEquals(List.of("@@ -1 +0,0 @@", "-a"), diff(lines("a", "b"), lines("b"), 0));
    }

    @Test
    void lineNumbersAfterAnEarlierHunkStayRight() throws Exception {
        List<String> out = diff(lines("a", "b", "c", "d", "e", "f", "g"), lines("A", "b", "c", "d", "e", "f", "G", "h"), 0);
        assertEquals(List.of("@@ -1 +1 @@", "-a", "+A", "@@ -7 +7,2 @@", "-g", "+G", "+h"), out);
    }
}
