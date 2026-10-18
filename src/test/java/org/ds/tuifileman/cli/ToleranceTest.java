package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ToleranceTest {
    private static long millis(String text) throws Exception {
        return DiffCommand.tolerance(text, "--time-tolerance");
    }

    @Test
    void aBareNumberIsSeconds() throws Exception {
        assertEquals(2_000, millis("2"));
        assertEquals(0, millis("0"));
    }

    @Test
    void unitsAreSecondsMinutesAndHours() throws Exception {
        assertEquals(2_000, millis("2s"));
        assertEquals(90_000, millis("90 sec"));
        assertEquals(300_000, millis("5m"));
        assertEquals(120_000, millis("2 min"));
        assertEquals(3_600_000, millis("1h"));
        assertEquals(7_200_000, millis("2 Hours"));
    }

    @Test
    void anythingElseIsAUsageErrorNamingTheOption() {
        for (String bad : new String[] {"", "s", "-1", "1.5s", "5ms", "2d", "abc", "99999999999999999999"}) {
            Args.UsageException error = assertThrows(Args.UsageException.class, () -> millis(bad), bad);
            assertEquals("--time-tolerance needs a duration like 2s, 90s, 5m or 1h (0 = exact times)",
                    error.getMessage());
        }
    }

    @Test
    void anOverflowingValueIsRejected() {
        assertThrows(Args.UsageException.class, () -> millis("9223372036854775807h"));
    }
}
