package org.ds.tuifileman.compare;

/**
 * Myers O(ND) diff in linear space (middle snake). Works on interned line ids and reports which
 * lines of each side are not part of the common subsequence. Past {@link #COST_LIMIT} edits in one
 * sub-problem it settles for a good-enough split instead of the minimal diff, so wildly different
 * files stay fast.
 */
public final class LineDiff {
    static final int COST_LIMIT = 3000;

    private final int[] a;
    private final int[] b;
    private final boolean[] changedA;
    private final boolean[] changedB;

    private LineDiff(int[] a, int[] b) {
        this.a = a;
        this.b = b;
        this.changedA = new boolean[a.length];
        this.changedB = new boolean[b.length];
    }

    /** Returns {changedA, changedB}; true means the line has no counterpart on the other side. */
    public static boolean[][] diff(int[] a, int[] b) {
        LineDiff d = new LineDiff(a, b);
        d.compare(0, a.length, 0, b.length);
        return new boolean[][] {d.changedA, d.changedB};
    }

    private void compare(int aLo, int aHi, int bLo, int bHi) {
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
            aLo++;
            bLo++;
        }
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
            aHi--;
            bHi--;
        }
        if (aLo == aHi) {
            for (int j = bLo; j < bHi; j++) {
                changedB[j] = true;
            }
            return;
        }
        if (bLo == bHi) {
            for (int i = aLo; i < aHi; i++) {
                changedA[i] = true;
            }
            return;
        }
        int n = aHi - aLo;
        int m = bHi - bLo;
        int[] split = split(aLo, aHi, bLo, bHi);
        // {leftAEnd, leftBEnd, rightAStart, rightBStart}, relative to aLo / bLo
        boolean usable = split != null
                && split[0] + split[1] < n + m
                && (n - split[2]) + (m - split[3]) < n + m;
        if (!usable) {
            for (int i = aLo; i < aHi; i++) {
                changedA[i] = true;
            }
            for (int j = bLo; j < bHi; j++) {
                changedB[j] = true;
            }
            return;
        }
        compare(aLo, aLo + split[0], bLo, bLo + split[1]);
        compare(aLo + split[2], aHi, bLo + split[3], bHi);
    }

    /** Finds a middle snake; null when no usable split exists. */
    private int[] split(int aLo, int aHi, int bLo, int bHi) {
        int n = aHi - aLo;
        int m = bHi - bLo;
        int delta = n - m;
        boolean odd = (delta & 1) != 0;
        int max = Math.min((n + m + 1) / 2, COST_LIMIT);
        int off = max + 2;
        int[] vf = new int[2 * max + 5];
        int[] vb = new int[2 * max + 5];
        int bestK = 0;
        int bestSum = -1;
        for (int d = 0; d <= max; d++) {
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && vf[off + k - 1] < vf[off + k + 1])
                        ? vf[off + k + 1] : vf[off + k - 1] + 1;
                int y = x - k;
                int sx = x;
                int sy = y;
                while (x < n && y < m && a[aLo + x] == b[bLo + y]) {
                    x++;
                    y++;
                }
                vf[off + k] = x;
                if (x <= n && y <= m && x + y > bestSum) {
                    bestSum = x + y;
                    bestK = k;
                }
                if (odd && k >= delta - (d - 1) && k <= delta + (d - 1)
                        && x + vb[off + delta - k] >= n) {
                    return new int[] {sx, sy, x, y};
                }
            }
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && vb[off + k - 1] < vb[off + k + 1])
                        ? vb[off + k + 1] : vb[off + k - 1] + 1;
                int y = x - k;
                int sx = x;
                int sy = y;
                while (x < n && y < m && a[aHi - 1 - x] == b[bHi - 1 - y]) {
                    x++;
                    y++;
                }
                vb[off + k] = x;
                if (!odd && k >= delta - d && k <= delta + d
                        && x + vf[off + delta - k] >= n) {
                    return new int[] {n - x, m - y, n - sx, m - sy};
                }
            }
        }
        // Cost limit reached: split at the furthest forward point (any split is a valid diff).
        int x = vf[off + bestK];
        int y = x - bestK;
        if (x > n || y > m || y < 0) {
            return null;
        }
        return new int[] {x, y, x, y};
    }
}
