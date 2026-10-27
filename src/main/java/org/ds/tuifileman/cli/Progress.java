package org.ds.tuifileman.cli;

import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.model.FileEntry;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Progress on stderr, in the form the reader needs: a live coloured bar for a person, a status line every
 * few seconds for a log, newline-delimited JSON for a wrapping tool, or nothing. stdout is never touched,
 * so {@code tool diff a b > result.json} still gives a clean file while the terminal shows the bar.
 */
final class Progress implements AutoCloseable {
    enum Mode { AUTO, BAR, LINES, JSON, NONE }

    /**
     * One reading; {@code total < 0} means the amount of work is not known yet (a spinner, not a bar).
     * {@code phase} overrides the label the reporter was started with (a copy goes from "Scanning" to "Copying").
     */
    record Snapshot(long done, long total, String unit, boolean bytes, String phase) {
        static Snapshot counting(long done, String unit) {
            return new Snapshot(done, -1, unit, false, null);
        }
    }

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final Console.Out err;
    private final Mode mode;
    private final String phase;
    private final Supplier<Snapshot> source;
    private final long started = System.nanoTime();
    private final Thread thread;
    private volatile boolean stopped;
    private volatile boolean paused;
    private boolean barShown;
    private long firstProgressNanos;

    private Progress(Console.Out err, Mode mode, String phase, Supplier<Snapshot> source) {
        this.err = err;
        this.mode = mode;
        this.phase = phase;
        this.source = source;
        this.thread = new Thread(this::loop, "progress");
        this.thread.setDaemon(true);
    }

    /** The mode that applies: {@code auto} is a bar on a terminal and silence otherwise; {@code -q} is silence. */
    static Mode resolve(Mode requested, boolean quiet, Console.Out err) {
        if (quiet) {
            return Mode.NONE;
        }
        if (requested == Mode.AUTO) {
            return err.terminal() ? Mode.BAR : Mode.NONE;
        }
        return requested;
    }

    static Progress start(Mode requested, boolean quiet, Console console, String phase, Supplier<Snapshot> source) {
        Progress progress = new Progress(console.err(), resolve(requested, quiet, console.err()), phase, source);
        if (progress.mode != Mode.NONE) {
            progress.thread.start();
        }
        return progress;
    }

    private void loop() {
        long interval = switch (mode) {
            case BAR -> 100;
            case JSON -> 500;
            default -> 2000;
        };
        int frame = 0;
        try {
            // a scan that finishes at once should not flash a bar
            Trace.at("progress thread running");
            Thread.sleep(mode == Mode.JSON ? 0 : 250);
            while (!stopped) {
                long t0 = System.nanoTime();
                render(frame);
                if (Trace.ON && frame < 3) {
                    Trace.at("frame " + frame + " rendered in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                }
                frame++;
                Thread.sleep(interval);
            }
        } catch (InterruptedException ignored) {
            // closing
        }
    }

    /** Hides the bar (and stops redrawing it) so a question can be asked; pair with {@link #resume()}. */
    synchronized void suspend() {
        paused = true;
        if (mode == Mode.BAR && barShown) {
            err.write("\r\u001b[K");
            barShown = false;
        }
    }

    void resume() {
        paused = false;
    }

    private synchronized void render(int frame) {
        if (paused) {
            return;
        }
        Snapshot snapshot = source.get();
        long now = System.nanoTime();
        if (firstProgressNanos == 0 && snapshot.done() > 0 && snapshot.total() >= 0) {
            firstProgressNanos = now;
        }
        double seconds = (now - started) / 1e9;
        String label = snapshot.phase() != null ? snapshot.phase() : phase;
        switch (mode) {
            case BAR -> {
                err.write("\r" + bar(snapshot, label, seconds, frame) + "\u001b[K");
                barShown = true;
            }
            case LINES -> err.write(label + ": " + amount(snapshot) + ", " + elapsed(seconds) + "\n");
            case JSON -> err.write("{\"event\":\"progress\",\"phase\":" + Json.quote(label)
                    + ",\"done\":" + snapshot.done()
                    + (snapshot.total() >= 0 ? ",\"total\":" + snapshot.total() : "")
                    + ",\"unit\":\"" + (snapshot.bytes() ? "bytes" : snapshot.unit())
                    + "\",\"elapsed_ms\":" + Math.round(seconds * 1000) + "}\n");
            default -> { }
        }
    }

    /** Bytes per second since the first byte moved; empty until there is something to measure. */
    private String speed(Snapshot snapshot, long now) {
        double window = (now - firstProgressNanos) / 1e9;
        if (!snapshot.bytes() || snapshot.total() < 0 || firstProgressNanos == 0 || window < 1) {
            return "";
        }
        return FileEntry.humanSize((long) (snapshot.done() / window)) + "/s";
    }

    private String bar(Snapshot snapshot, String label, double seconds, int frame) {
        int width = err.width();
        String time = elapsed(seconds);
        if (snapshot.total() < 0) {
            return err.style(Style.ACCENT, SPINNER[frame % SPINNER.length]) + " "
                    + err.style(Style.BOLD, label) + "  " + amount(snapshot) + "  " + err.style(Style.MUTED, time);
        }
        double fraction = snapshot.total() == 0 ? 1 : Math.min(1.0, (double) snapshot.done() / snapshot.total());
        String percent = String.format(Locale.ROOT, "%3d%%", Math.round(fraction * 100));
        String amount = amount(snapshot);
        String rate = speed(snapshot, System.nanoTime());
        String tail = rate.isEmpty() ? "" : "  " + err.style(Style.MUTED, rate);
        int fixed = label.length() + amount.length() + time.length() + percent.length() + rate.length() + 12;
        int cells = Math.max(10, Math.min(40, width - fixed));
        int full = (int) Math.round(cells * fraction);
        return err.style(Style.BOLD, label) + "  "
                + err.style(Style.ACCENT, "█".repeat(full)) + err.style(Style.MUTED, "░".repeat(cells - full))
                + "  " + err.style(Style.BOLD, percent) + "  " + amount + tail + "  " + err.style(Style.MUTED, time);
    }

    private static String amount(Snapshot snapshot) {
        if (snapshot.bytes()) {
            String done = FileEntry.humanSize(snapshot.done());
            return snapshot.total() < 0 ? done : done + " / " + FileEntry.humanSize(snapshot.total());
        }
        String done = String.format(Locale.ROOT, "%,d", snapshot.done());
        return snapshot.total() < 0
                ? done + " " + snapshot.unit()
                : done + " / " + String.format(Locale.ROOT, "%,d", snapshot.total()) + " " + snapshot.unit();
    }

    private static String elapsed(double seconds) {
        long whole = (long) seconds;
        return whole >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", whole / 3600, whole / 60 % 60, whole % 60)
                : String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
    }

    /** Stops the reporter and tidies the terminal: the bar line is erased and the cursor shown again. */
    @Override
    public void close() {
        stopped = true;
        if (mode == Mode.NONE) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            switch (mode) {
                case BAR -> err.write(barShown ? "\r\u001b[K" : "");
                case JSON -> err.write("{\"event\":\"done\",\"phase\":\"" + phase + "\",\"elapsed_ms\":"
                        + Math.round((System.nanoTime() - started) / 1e6) + "}\n");
                default -> { }
            }
        }
    }
}
