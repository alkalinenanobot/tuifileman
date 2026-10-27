package org.ds.tuifileman.compare;

import org.ds.tuifileman.config.Config;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How far apart two modified times may be and still count as the same time. One setting for diff, sync,
 * copy and move, in the file manager and on the command line: {@code --time-tolerance T} wins over
 * {@code [sync] time_tolerance} in the INI file, which wins over {@link #DEFAULT_MILLIS}.
 */
public final class TimeTolerance {
    /** FAT stores times with a resolution of 2 seconds. */
    public static final long DEFAULT_MILLIS = 2000;

    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)\\s*(|s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours)",
            Pattern.CASE_INSENSITIVE);

    private TimeTolerance() { }

    /**
     * Parses a whole number with an optional unit (s/sec/second(s), m/min/minute(s), h/hr/hour(s); no unit means
     * seconds) into milliseconds. 0 means exactly equal times.
     *
     * @throws IllegalArgumentException when the text is anything else
     */
    public static long parse(String text) {
        Matcher match = DURATION.matcher(text.strip());
        if (match.matches()) {
            try {
                String unit = match.group(2).toLowerCase(Locale.ROOT);
                long seconds = unit.isEmpty() || unit.startsWith("s") ? 1 : unit.startsWith("m") ? 60 : 3600;
                return Math.multiplyExact(Long.parseLong(match.group(1)), seconds * 1000);
            } catch (NumberFormatException | ArithmeticException e) {
                // falls through
            }
        }
        throw new IllegalArgumentException("'" + text + "' is not a duration like 2s, 90s, 5m or 1h");
    }

    /** The tolerance of the INI file, or the default when it is not set or not valid (a warning is recorded). */
    public static long configured() {
        Config config = Config.get();
        String text = config.text("sync.time_tolerance", "");
        if (text.isBlank()) {
            return DEFAULT_MILLIS;
        }
        try {
            return parse(text);
        } catch (IllegalArgumentException e) {
            config.warn("sync.time_tolerance: " + e.getMessage());
            return DEFAULT_MILLIS;
        }
    }
}
