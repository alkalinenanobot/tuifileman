package org.ds.tuifileman.view;

import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.KeyDecodingProfile;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import java.io.IOException;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pixel size of one terminal cell. A sixel picture is measured in pixels, the screen in cells, so the picture
 * only lines up when the app knows how many pixels a cell has: Windows Terminal draws 10x20, but Konsole on a
 * scaled display (7x17 logical pixels at 125 %) does not. xterm's window operation 16 asks for it
 * ({@code ESC [ 16 t}, the answer is {@code ESC [ 6 ; height ; width t}); a terminal that does not answer
 * gets the 10x20 grid.
 */
public final class CellSize {
    public static final int DEFAULT_WIDTH = 10;
    public static final int DEFAULT_HEIGHT = 20;

    /** Turns the terminal's answer into a {@link Report} key; register it on the terminal's input decoder. */
    public static final KeyDecodingProfile PROFILE = () -> List.of(new Reply());

    private static final byte[] QUERY = "\u001b[16t".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int ANSWER_MILLIS = 300;
    private static final int MAX_PIXELS = 500;
    private static final Pattern FIXED = Pattern.compile("(\\d{1,3})\\s*x\\s*(\\d{1,3})");

    /** Set once a query went unanswered, so that a terminal without the operation is not asked again. */
    private static boolean unanswered;

    private CellSize() { }

    /** The answer to the query; {@link Screen#pollInput()} returns it like a key. */
    static final class Report extends KeyStroke {
        final int width;
        final int height;

        Report(int width, int height) {
            super(KeyType.Unknown, false, false, false);
            this.width = width;
            this.height = height;
        }
    }

    /**
     * The cell size in pixels, {width, height}: the configured {@code WIDTHxHEIGHT}, else what the terminal
     * reports, else 10x20. Keys typed while waiting for the answer are appended to {@code pending}.
     */
    static int[] detect(Screen screen, String configured, Deque<KeyStroke> pending) throws IOException {
        Matcher fixed = FIXED.matcher(configured.strip());
        if (fixed.matches()) {
            return new int[] {Integer.parseInt(fixed.group(1)), Integer.parseInt(fixed.group(2))};
        }
        if (unanswered || !(screen instanceof TerminalScreen terminalScreen)) {
            return new int[] {DEFAULT_WIDTH, DEFAULT_HEIGHT};
        }
        terminalScreen.getTerminal().flush();
        System.out.write(QUERY);
        System.out.flush();
        long deadline = System.nanoTime() + ANSWER_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            KeyStroke key = screen.pollInput();
            if (key == null) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else if (key instanceof Report report) {
                return new int[] {report.width, report.height};
            } else {
                pending.add(key);
            }
        }
        unanswered = true;
        return new int[] {DEFAULT_WIDTH, DEFAULT_HEIGHT};
    }

    /** {@code ESC [ 6 ; height ; width t}. Values the terminal cannot mean (zero, huge) are not an answer. */
    static final class Reply implements CharacterPattern {
        private static final String PREFIX = "\u001b[6;";
        private static final int MAX_LENGTH = 16;

        @Override
        public Matching match(List<Character> sequence) {
            int size = sequence.size();
            if (size > MAX_LENGTH) {
                return null;
            }
            int[] numbers = new int[2];
            int field = 0;
            boolean digits = false;
            for (int i = 0; i < size; i++) {
                char c = sequence.get(i);
                if (i < PREFIX.length()) {
                    if (c != PREFIX.charAt(i)) {
                        return null;
                    }
                } else if (c >= '0' && c <= '9') {
                    numbers[field] = numbers[field] * 10 + (c - '0');
                    if (numbers[field] > MAX_PIXELS) {
                        return null;
                    }
                    digits = true;
                } else if (c == ';' && digits && field == 0) {
                    field = 1;
                    digits = false;
                } else if (c == 't' && digits && field == 1 && i == size - 1) {
                    return numbers[0] > 0 && numbers[1] > 0 ? new Matching(new Report(numbers[1], numbers[0])) : null;
                } else {
                    return null;
                }
            }
            return Matching.NOT_YET;
        }
    }
}
