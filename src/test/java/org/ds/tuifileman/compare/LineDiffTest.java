package org.ds.tuifileman.compare;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LineDiffTest {
    /** The lines that were not marked as changed, in order. */
    private static int[] kept(int[] lines, boolean[] changed) {
        return java.util.stream.IntStream.range(0, lines.length).filter(i -> !changed[i]).map(i -> lines[i]).toArray();
    }

    private static int lcs(int[] a, int[] b) {
        int[][] table = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                table[i][j] = a[i] == b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        return table[0][0];
    }

    @Test
    void identicalInputsHaveNoChanges() {
        boolean[][] result = LineDiff.diff(new int[] {1, 2, 3}, new int[] {1, 2, 3});
        assertArrayEquals(new boolean[3], result[0]);
        assertArrayEquals(new boolean[3], result[1]);
    }

    @Test
    void emptyInputs() {
        boolean[][] both = LineDiff.diff(new int[0], new int[0]);
        assertEquals(0, both[0].length);
        boolean[][] added = LineDiff.diff(new int[0], new int[] {1, 2});
        assertArrayEquals(new boolean[] {true, true}, added[1]);
        boolean[][] removed = LineDiff.diff(new int[] {1, 2}, new int[0]);
        assertArrayEquals(new boolean[] {true, true}, removed[0]);
    }

    @Test
    void insertionMarksOnlyTheNewLine() {
        boolean[][] result = LineDiff.diff(new int[] {1, 2, 3}, new int[] {1, 2, 9, 3});
        assertArrayEquals(new boolean[3], result[0]);
        assertArrayEquals(new boolean[] {false, false, true, false}, result[1]);
    }

    @Test
    void deletionMarksOnlyTheRemovedLine() {
        boolean[][] result = LineDiff.diff(new int[] {1, 2, 3, 4}, new int[] {1, 3, 4});
        assertArrayEquals(new boolean[] {false, true, false, false}, result[0]);
        assertArrayEquals(new boolean[3], result[1]);
    }

    @Test
    void replacementMarksBothSides() {
        boolean[][] result = LineDiff.diff(new int[] {1, 2, 3}, new int[] {1, 9, 3});
        assertArrayEquals(new boolean[] {false, true, false}, result[0]);
        assertArrayEquals(new boolean[] {false, true, false}, result[1]);
    }

    @Test
    void completelyDifferentInputsAreAllChanged() {
        boolean[][] result = LineDiff.diff(new int[] {1, 2}, new int[] {3, 4, 5});
        assertArrayEquals(new boolean[] {true, true}, result[0]);
        assertArrayEquals(new boolean[] {true, true, true}, result[1]);
    }

    @Test
    void unchangedLinesAreAMinimalCommonSubsequenceOnRandomInput() {
        Random random = new Random(42);
        for (int round = 0; round < 300; round++) {
            int[] a = random.ints(random.nextInt(40), 0, 5).toArray();
            int[] b = random.ints(random.nextInt(40), 0, 5).toArray();
            boolean[][] result = LineDiff.diff(a, b);
            int[] keptA = kept(a, result[0]);
            int[] keptB = kept(b, result[1]);
            assertArrayEquals(keptA, keptB, "both sides keep the same lines");
            assertEquals(lcs(a, b), keptA.length, "the diff is minimal for small inputs (round " + round + ")");
        }
    }

    @Test
    void hugeDifferentInputsFinishAndStayConsistent() {
        int[] a = new int[20_000];
        int[] b = new int[20_000];
        for (int i = 0; i < a.length; i++) {
            a[i] = i;
            b[i] = i + 1_000_000;
        }
        boolean[][] result = LineDiff.diff(a, b);
        assertEquals(0, kept(a, result[0]).length);
        assertEquals(0, kept(b, result[1]).length);
    }

    @Test
    void aFewEditsInALongFileStayCheap() {
        int[] a = new int[50_000];
        for (int i = 0; i < a.length; i++) {
            a[i] = i;
        }
        int[] b = a.clone();
        b[10] = -1;
        b[25_000] = -2;
        b[49_999] = -3;
        boolean[][] result = LineDiff.diff(a, b);
        assertTrue(result[0][10] && result[0][25_000] && result[0][49_999]);
        assertEquals(a.length - 3, kept(a, result[0]).length);
        assertArrayEquals(kept(a, result[0]), kept(b, result[1]));
    }
}
