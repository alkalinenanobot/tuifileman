package org.ds.tuifileman.cli;

import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.fs.Parallel;
import org.ds.tuifileman.model.FileEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code --stats}: where the time went and how fast it was (wall time, phases, items and bytes per second,
 * threads, storage type). One command runs per process, so the state is static; {@link #begin} resets it.
 * Everything is a no-op unless enabled, so the commands call it unconditionally.
 */
final class Stats {
    private static boolean enabled;
    private static long startupMs;
    private static long begin;
    private static String phase;
    private static long phaseBegin;
    private static final Map<String, Long> PHASES = new LinkedHashMap<>();
    private static final List<Path> ROOTS = new ArrayList<>();
    private static long items;
    private static long bytes;
    private static long ignored;
    private static long wallMs = -1;

    private Stats() { }

    /** Starts the clock; {@code first} names the phase that is running at once. */
    static void begin(boolean on, String first) {
        enabled = on;
        PHASES.clear();
        ROOTS.clear();
        items = 0;
        bytes = 0;
        ignored = 0;
        wallMs = -1;
        if (!on) {
            return;
        }
        startupMs = Trace.sinceStartMs();
        begin = System.nanoTime();
        phase = first;
        phaseBegin = begin;
    }

    /** Ends the running phase and starts {@code name}. */
    static void phase(String name) {
        if (!enabled || wallMs >= 0 || name.equals(phase)) {
            return;
        }
        closePhase(System.nanoTime());
        phase = name;
        phaseBegin = System.nanoTime();
    }

    /** The folders the work reads or writes: their drives decide the thread count and the storage line. */
    static void roots(Path... paths) {
        if (enabled) {
            for (Path path : paths) {
                if (path != null) {
                    ROOTS.add(path.toAbsolutePath());
                }
            }
        }
    }

    static void totals(long itemCount, long byteCount) {
        items = itemCount;
        bytes = byteCount;
    }

    /** Entries the ignore rules left out of the scan. */
    static void ignored(long count) {
        ignored = count;
    }

    private static void closePhase(long now) {
        if (phase != null) {
            PHASES.merge(phase, (now - phaseBegin) / 1_000_000, Long::sum);
            phase = null;
        }
    }

    /** Stops the clock (once); the numbers no longer change after this. */
    private static void finish() {
        if (enabled && wallMs < 0) {
            long now = System.nanoTime();
            closePhase(now);
            wallMs = (now - begin) / 1_000_000;
        }
    }

    private static int threads() {
        if (ROOTS.isEmpty()) {
            return 0;
        }
        return ROOTS.size() == 1 ? Parallel.threads(ROOTS.get(0)) : Parallel.threads(ROOTS.get(0), ROOTS.get(1));
    }

    private static List<String> storage() {
        List<String> result = new ArrayList<>();
        for (Path root : ROOTS) {
            Path drive = root.getRoot() != null ? root.getRoot() : root;
            String text = drive + " " + Parallel.storage(root).name();
            if (!result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private static double perSecond(long amount) {
        return wallMs <= 0 ? 0 : amount * 1000.0 / wallMs;
    }

    /** {@code ,"stats":{...}} to append inside a result object, or an empty string when disabled. */
    static String json() {
        if (!enabled) {
            return "";
        }
        finish();
        StringBuilder text = new StringBuilder(",\"stats\":{\"startup_ms\":").append(startupMs)
                .append(",\"wall_ms\":").append(wallMs).append(",\"phases\":{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : PHASES.entrySet()) {
            text.append(first ? "" : ",").append('"').append(entry.getKey()).append("_ms\":").append(entry.getValue());
            first = false;
        }
        text.append("},\"items\":").append(items).append(",\"bytes\":").append(bytes).append(",\"ignored\":").append(ignored)
                .append(String.format(Locale.ROOT, ",\"items_per_s\":%.1f,\"bytes_per_s\":%.0f", perSecond(items), perSecond(bytes)))
                .append(",\"threads\":").append(threads()).append(",\"storage\":[");
        List<String> storage = storage();
        for (int i = 0; i < storage.size(); i++) {
            String[] parts = storage.get(i).split(" ");
            text.append(i == 0 ? "" : ",").append("{\"root\":").append(Json.quote(parts[0]))
                    .append(",\"type\":\"").append(parts[1]).append("\"}");
        }
        return text.append("]}").toString();
    }

    /** The human block, on stderr (also with {@code --quiet}: asking for stats is explicit). */
    static void print(Console.Out err) {
        if (!enabled) {
            return;
        }
        finish();
        err.println(err.style(Style.BOLD, "stats"));
        StringBuilder phases = new StringBuilder();
        for (Map.Entry<String, Long> entry : PHASES.entrySet()) {
            phases.append(", ").append(entry.getKey()).append(' ').append(seconds(entry.getValue()));
        }
        err.println("  " + err.style(Style.MUTED, String.format("%-9s", "time")) + seconds(wallMs)
                + err.style(Style.MUTED, " (startup " + seconds(startupMs) + phases + ")"));
        String work = String.format(Locale.ROOT, "%,d items  %,.0f items/s", items, perSecond(items));
        if (bytes > 0) {
            work += "  " + FileEntry.humanSize(bytes) + "  " + FileEntry.humanSize((long) perSecond(bytes)) + "/s";
        }
        err.println("  " + err.style(Style.MUTED, String.format("%-9s", "work")) + work);
        if (ignored > 0) {
            err.println("  " + err.style(Style.MUTED, String.format("%-9s", "ignored")) + String.format(Locale.ROOT, "%,d entries", ignored)
                    + err.style(Style.MUTED, " (not scanned or compared)"));
        }
        if (!ROOTS.isEmpty()) {
            err.println("  " + err.style(Style.MUTED, String.format("%-9s", "threads")) + threads()
                    + err.style(Style.MUTED, " (" + String.join(", ", storage()) + ")"));
        }
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.2f s", ms / 1000.0);
    }
}
