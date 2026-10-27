package org.ds.tuifileman.app;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.KeyDecodingProfile;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.util.List;

/**
 * Mouse reports in xterm's SGR encoding, {@code ESC [ < button ; column ; row M} (press, motion, wheel) or
 * {@code ... m} (release). Lanterna decodes only the old X10 form, which writes each coordinate as one character
 * (column + 33): it cannot say more than 223 columns, and in the UTF-8 variant Lanterna asks for, a Windows Terminal
 * window wider than 94 columns splits a multi-byte coordinate in the pipe to the console now and then; the rest of
 * the report turns up much later, and the first part has already been typed into quick search as "[M#".
 * SGR is plain ASCII with decimal numbers.
 */
final class SgrMousePattern implements CharacterPattern {
    /** Asks the terminal for SGR reports; it takes precedence over the encoding Lanterna asks for. */
    static final String ENABLE = "\u001b[?1006h";
    static final String DISABLE = "\u001b[?1006l";

    private static final int MAX_LENGTH = 32;
    private static final String PREFIX = "\u001b[<";

    static final KeyDecodingProfile PROFILE = () -> List.of(new SgrMousePattern());

    private boolean buttonDown;

    @Override
    public Matching match(List<Character> sequence) {
        int size = sequence.size();
        if (size > MAX_LENGTH) {
            return null;
        }
        int[] numbers = new int[3];
        int field = 0;
        boolean digits = false;
        for (int i = 0; i < size; i++) {
            char c = sequence.get(i);
            if (i < PREFIX.length()) {
                if (c != PREFIX.charAt(i)) {
                    return null;
                }
            } else if (c >= '0' && c <= '9') {
                if (field > 2) {
                    return null;
                }
                numbers[field] = numbers[field] * 10 + (c - '0');
                if (numbers[field] > 100_000) {
                    return null;
                }
                digits = true;
            } else if (c == ';' && digits && field < 2) {
                field++;
                digits = false;
            } else if ((c == 'M' || c == 'm') && digits && field == 2 && i == size - 1) {
                return new Matching(action(numbers[0], numbers[1] - 1, numbers[2] - 1, c == 'm'));
            } else {
                return null;
            }
        }
        return Matching.NOT_YET;
    }

    private MouseAction action(int code, int column, int row, boolean release) {
        TerminalPosition position = new TerminalPosition(Math.max(0, column), Math.max(0, row));
        int button = (code & 3) + 1;
        if ((code & 64) != 0) {
            return new MouseAction(button == 1 ? MouseActionType.SCROLL_UP : MouseActionType.SCROLL_DOWN,
                    button == 1 ? 4 : 5, position);
        }
        if ((code & 32) != 0) {
            boolean noButton = button == 4;
            return noButton && !buttonDown
                    ? new MouseAction(MouseActionType.MOVE, 0, position)
                    : new MouseAction(MouseActionType.DRAG, noButton ? 1 : button, position);
        }
        buttonDown = !release;
        return release
                ? new MouseAction(MouseActionType.CLICK_RELEASE, 0, position)
                : new MouseAction(MouseActionType.CLICK_DOWN, button, position);
    }
}
