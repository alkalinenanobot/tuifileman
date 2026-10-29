package org.ds.tuifileman.compare;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a {@link TextCompare} as a unified diff (what {@code diff -u} prints): hunks of changed lines with
 * some unchanged lines around them. A block of changed lines lists the removed lines first, then the added ones.
 * Counts of one are written without the {@code ,1}, like GNU diff does.
 */
public final class UnifiedDiff {
    private UnifiedDiff() { }

    /** One line of the diff: {@code kind} is ' ' (in both), '-' (only in the left file) or '+' (only in the right). */
    private record Op(char kind, String text) { }

    /**
     * The hunks of the comparison, each as its {@code @@ -a,b +c,d @@} line followed by its lines (every one
     * starts with ' ', '-' or '+'). Empty when the two sides have no difference.
     *
     * @param context unchanged lines to show around each change; changes closer than twice that share a hunk
     */
    public static List<String> render(TextCompare compare, int context) {
        List<Op> ops = ops(compare);
        List<String> out = new ArrayList<>();
        int oldBefore = 0; // left lines before the current position
        int newBefore = 0;
        int at = 0;
        int count = ops.size();
        while (true) {
            int first = at;
            while (first < count && ops.get(first).kind() == ' ') {
                first++;
            }
            if (first == count) {
                break;
            }
            int last = first; // last change of this hunk
            int scan = first;
            while (scan < count) {
                if (ops.get(scan).kind() != ' ') {
                    last = scan;
                } else if (scan - last > 2 * context) {
                    break;
                }
                scan++;
            }
            int start = Math.max(at, first - context);
            int end = Math.min(count, last + 1 + context);
            // lines skipped between the previous hunk (or the top) and this one
            for (int i = at; i < start; i++) {
                oldBefore += ops.get(i).kind() == '+' ? 0 : 1;
                newBefore += ops.get(i).kind() == '-' ? 0 : 1;
            }
            int oldCount = 0;
            int newCount = 0;
            List<String> lines = new ArrayList<>();
            for (int i = start; i < end; i++) {
                Op op = ops.get(i);
                oldCount += op.kind() == '+' ? 0 : 1;
                newCount += op.kind() == '-' ? 0 : 1;
                lines.add(op.kind() + op.text());
            }
            out.add("@@ -" + range(oldBefore, oldCount) + " +" + range(newBefore, newCount) + " @@");
            out.addAll(lines);
            oldBefore += oldCount;
            newBefore += newCount;
            at = end;
        }
        return out;
    }

    /** {@code start,count} with the first line number 1; an empty range sits after the line before it. */
    private static String range(int before, int count) {
        int start = count == 0 ? before : before + 1;
        return count == 1 ? Integer.toString(start) : start + "," + count;
    }

    private static List<Op> ops(TextCompare compare) {
        List<Op> ops = new ArrayList<>();
        List<Op> removed = new ArrayList<>();
        List<Op> added = new ArrayList<>();
        for (TextCompare.Row row : compare.rows()) {
            if (row.type() == TextCompare.RowType.SAME) {
                flush(ops, removed, added);
                ops.add(new Op(' ', compare.leftLine(row.left())));
                continue;
            }
            if (row.left() >= 0) {
                removed.add(new Op('-', compare.leftLine(row.left())));
            }
            if (row.right() >= 0) {
                added.add(new Op('+', compare.rightLine(row.right())));
            }
        }
        flush(ops, removed, added);
        return ops;
    }

    private static void flush(List<Op> ops, List<Op> removed, List<Op> added) {
        ops.addAll(removed);
        ops.addAll(added);
        removed.clear();
        added.clear();
    }
}
