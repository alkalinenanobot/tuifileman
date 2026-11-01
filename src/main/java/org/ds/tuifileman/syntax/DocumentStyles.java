package org.ds.tuifileman.syntax;

import java.util.Arrays;

/**
 * The colours of an edited document, kept up to date cheaply. Only one integer per line is stored: the scanner state
 * at the start of the line (inside a block comment, a text block ...). The styles of a line are computed from that
 * state when the line is drawn, so a document that is only displayed costs one scan of the lines before the screen.
 *
 * <p>After an edit the states of the edited lines are recomputed at once and carried forward until a line ends in
 * the state it ended in before the edit: everything below is then still right (typing in an ordinary line stops
 * after one line, opening a comment changes the lines until it is closed). A change that reaches further than
 * {@code eagerLines} lines is not chased: the lines beyond are marked unknown and computed when they are drawn.
 */
public final class DocumentStyles {
    /** The lines of the document, as the editor holds them. */
    public interface Lines {
        int count();

        String line(int index);
    }

    private static final int UNKNOWN = Integer.MIN_VALUE;
    /** How far an edit is followed at once. */
    public static final int DEFAULT_EAGER_LINES = 5000;

    private final Highlighter highlighter;
    private final Lines lines;
    private final int eagerLines;
    /** Scanner state at the start of each line; every entry after {@link #validUpTo} is {@link #UNKNOWN}. */
    private int[] state;
    /** Highest line whose start state is known and right; all lines up to it are. */
    private int validUpTo;
    private byte[] scratch = new byte[256];

    public DocumentStyles(Language language, Lines lines) {
        this(language, lines, DEFAULT_EAGER_LINES);
    }

    DocumentStyles(Language language, Lines lines, int eagerLines) {
        this.highlighter = new Highlighter(language);
        this.lines = lines;
        this.eagerLines = Math.max(1, eagerLines);
        reset();
    }

    private void reset() {
        state = new int[Math.max(1, lines.count())];
        Arrays.fill(state, UNKNOWN);
        state[0] = Highlighter.START;
        validUpTo = 0;
    }

    /**
     * Tells that the {@code removed} lines from index {@code from} were replaced by {@code inserted} lines (both at
     * least 1); call it after the document changed.
     */
    public void edited(int from, int removed, int inserted) {
        int newCount = lines.count();
        int oldCount = newCount - inserted + removed;
        if (state.length != oldCount || from < 0 || from >= oldCount || from + removed > oldCount) {
            reset();
            return;
        }
        int oldValid = validUpTo;
        int tailStart = from + inserted;
        int oldTailStart = from + removed;
        int[] next = new int[newCount];
        System.arraycopy(state, 0, next, 0, from + 1);
        Arrays.fill(next, from + 1, tailStart, UNKNOWN);
        System.arraycopy(state, oldTailStart, next, tailStart, oldCount - oldTailStart);
        state = next;
        if (from > oldValid) {
            // Nothing below the valid part was known, and still is not.
            return;
        }
        // The old states after the edited lines are right for the old text: they are checked against the new end state.
        int trustedEnd = oldValid >= oldTailStart ? tailStart + (oldValid - oldTailStart) : tailStart - 1;
        validUpTo = from;
        int budget = eagerLines;
        int line = from;
        while (true) {
            int end = scanEnd(line);
            int following = line + 1;
            if (following >= newCount) {
                return;
            }
            boolean inTail = following >= tailStart;
            boolean known = inTail && state[following] != UNKNOWN;
            if (known && state[following] == end) {
                // The state below is what it was: the rest of the old chain is still right.
                validUpTo = trustedEnd;
                return;
            }
            state[following] = end;
            validUpTo = following;
            if (inTail && !known) {
                return;
            }
            if (--budget <= 0) {
                Arrays.fill(state, following + 1, newCount, UNKNOWN);
                return;
            }
            line = following;
        }
    }

    /** The style of every character of line {@code index} (see {@link org.ds.tuifileman.ui.TextStyle}). */
    public byte[] styles(int index) {
        if (state.length != lines.count()) {
            reset();
        }
        if (index < 0 || index >= state.length) {
            return new byte[0];
        }
        while (validUpTo < index) {
            int end = scanEnd(validUpTo);
            state[++validUpTo] = end;
        }
        String text = lines.line(index);
        byte[] out = new byte[text.length()];
        highlighter.line(text, state[index], out);
        return out;
    }

    private int scanEnd(int line) {
        String text = lines.line(line);
        if (scratch.length < text.length()) {
            scratch = new byte[Math.max(text.length(), scratch.length * 2)];
        }
        return highlighter.line(text, state[line], scratch);
    }
}
