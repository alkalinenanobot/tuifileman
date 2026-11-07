package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToleranceTest {
    private static long millis(String text) throws Exception {
        return DiffCommand.tolerance(text, "--time-tolerance");
    }

    @Test
    void aBareNumberIsSecondsAndUnitsAreSecondsMinutesAndHours() throws Exception {
        assertEquals(2_000, millis("2"));
        assertEquals(0, millis("0"));
        assertEquals(2_000, millis("2s"));
        assertEquals(90_000, millis("90 sec"));
        assertEquals(300_000, millis("5m"));
        assertEquals(120_000, millis("2 min"));
        assertEquals(3_600_000, millis("1h"));
        assertEquals(7_200_000, millis("2 Hours"));
    }

    @Test
    void anythingElseIsAUsageErrorNamingTheOption() {
        for (String bad : new String[] {"", "s", "-1", "1.5s", "5ms", "2d", "abc", "99999999999999999999",
                "9223372036854775807h"}) {
            Args.UsageException error = assertThrows(Args.UsageException.class, () -> millis(bad), bad);
            assertTrue(error.getMessage().contains("--time-tolerance"), bad + " -> " + error.getMessage());
        }
    }
}
