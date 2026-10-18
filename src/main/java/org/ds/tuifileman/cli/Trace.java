package org.ds.tuifileman.cli;

import java.time.Duration;
import java.time.Instant;

/** Opt-in startup timeline ({@code TFM_TRACE=1}) written to stderr; used to find where a slow start goes. */
final class Trace {
    static final boolean ON = System.getenv("TFM_TRACE") != null;

    private Trace() { }

    /** Milliseconds since the process was created; 0 when the start time is unknown. */
    static long sinceStartMs() {
        try {
            Instant start = ProcessHandle.current().info().startInstant().orElse(null);
            if (start != null) {
                return Math.max(0, Duration.between(start, Instant.now()).toMillis());
            }
        } catch (RuntimeException ignored) {
            // unknown
        }
        return 0;
    }

    static void at(String what) {
        if (ON) {
            System.err.println("[trace +" + sinceStartMs() + " ms] " + what);
        }
    }
}
