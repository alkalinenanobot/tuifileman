package org.ds.tuifileman.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class WrapTest {
    @Test
    void breaksAfterTheLastSpaceThatFits() {
        assertArrayEquals(new int[] {0, 6, 12}, Wrap.rows("hello world foo", 8));
        assertArrayEquals(new int[] {0}, Wrap.rows("short", 8));
        assertArrayEquals(new int[] {0}, Wrap.rows("", 8));
    }

    @Test
    void breaksHardWhenThereIsNoSpaceOrOnlyAnEarlyOne() {
        assertArrayEquals(new int[] {0, 4, 8}, Wrap.rows("abcdefghij", 4));
        // The only space would leave a row of 3 of 6 columns: not at least half full, so the break stays hard.
        assertArrayEquals(new int[] {0, 6, 12}, Wrap.rows("ab cdefghijklmn", 6));
    }

    @Test
    void everyRowFitsAndNothingIsLost() {
        Random random = new Random(7);
        for (int round = 0; round < 2000; round++) {
            int width = 1 + random.nextInt(30);
            StringBuilder text = new StringBuilder();
            for (int i = random.nextInt(120); i > 0; i--) {
                text.append(random.nextInt(4) == 0 ? ' ' : (char) ('a' + random.nextInt(26)));
            }
            String s = text.toString();
            int[] starts = Wrap.rows(s, width);
            assertEquals(0, starts[0]);
            assertEquals(starts.length, Wrap.count(s, width));
            for (int row = 0; row < starts.length; row++) {
                int end = Wrap.end(starts, row, s.length());
                assertTrue(end - starts[row] <= width, "row " + row + " of '" + s + "' at " + width);
                assertTrue(row == 0 || starts[row] > starts[row - 1], "rows advance");
                if (row + 1 < starts.length) {
                    int length = end - starts[row];
                    assertTrue(length == width || s.charAt(end - 1) == ' ' && length > width / 2,
                            "a short row ends in a space: '" + s + "' at " + width + " row " + row);
                }
            }
            assertEquals(s.length(), Wrap.end(starts, starts.length - 1, s.length()));
            for (int index = 0; index < s.length(); index++) {
                int row = Wrap.rowOf(starts, index);
                assertTrue(starts[row] <= index && index < Wrap.end(starts, row, s.length()));
            }
        }
    }
}
