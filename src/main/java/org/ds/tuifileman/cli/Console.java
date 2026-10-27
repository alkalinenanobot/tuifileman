package org.ds.tuifileman.cli;

import com.googlecode.lanterna.TextColor;
import org.ds.tuifileman.ui.Theme;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Every line the CLI prints goes through here, so styling is decided in one place: semantic roles taken
 * from the same {@link Theme} the TUI uses, escape codes only on a real terminal (and never when
 * {@code NO_COLOR} is set or {@code --color never} is given), and always UTF-8 bytes. Machine-readable
 * output (json, csv) never passes through a style.
 */
final class Console {
    enum Style { PLAIN, BOLD, MUTED, ACCENT, OK, WARN, ERROR, ADDED, REMOVED, CHANGED, TITLE }

    enum ColorMode { AUTO, ALWAYS, NEVER }

    /** One output stream with its own colour decision. */
    static final class Out {
        private final PrintStream stream;
        private final boolean terminal;
        private final boolean color;
        private final boolean stderr;
        private final Theme theme;

        private Out(boolean stderr, boolean terminal, boolean color, Theme theme) {
            this.stderr = stderr;
            this.stream = new PrintStream(new BufferedOutputStream(
                    new FileOutputStream(stderr ? FileDescriptor.err : FileDescriptor.out), 1 << 16),
                    false, StandardCharsets.UTF_8);
            this.terminal = terminal;
            this.color = color;
            this.theme = theme;
        }

        boolean terminal() {
            return terminal;
        }

        boolean color() {
            return color;
        }

        int width() {
            return terminal ? Tty.width(stderr) : 80;
        }

        /** The text wrapped in the style's escape codes, or unchanged when this stream has no colour. */
        String style(Style style, String text) {
            if (!color || style == Style.PLAIN) {
                return text;
            }
            return switch (style) {
                case BOLD -> "\u001b[1m" + text + "\u001b[22m";
                case MUTED -> fg(theme.muted(), text);
                case ACCENT -> fg(theme.accent(), text);
                case OK, ADDED -> fg(theme.success(), text);
                case WARN, CHANGED -> fg(theme.warning(), text);
                case ERROR, REMOVED -> fg(theme.error(), text);
                case TITLE -> "\u001b[1m" + fg(theme.accent(), text) + "\u001b[22m";
                case PLAIN -> text;
            };
        }

        private static String fg(TextColor color, String text) {
            if (!(color instanceof TextColor.RGB rgb)) {
                return text;
            }
            return "\u001b[38;2;" + rgb.getRed() + ";" + rgb.getGreen() + ";" + rgb.getBlue() + "m" + text + "\u001b[39m";
        }

        void print(String text) {
            stream.print(text);
        }

        void println(String text) {
            stream.print(text);
            stream.print('\n');
        }

        void println() {
            stream.print('\n');
        }

        /** Writes and flushes at once; for progress frames and prompts. */
        void write(String text) {
            stream.print(text);
            stream.flush();
        }

        void flush() {
            stream.flush();
        }

        /** True once writing failed, e.g. the reader of a pipe went away ({@code find ... | head}). */
        boolean failed() {
            return stream.checkError();
        }
    }

    private final Out out;
    private final Out err;

    private Console(Out out, Out err) {
        this.out = out;
        this.err = err;
    }

    /**
     * @param mode   the {@code --color} choice
     * @param theme  the palette for the roles
     */
    static Console open(ColorMode mode, Theme theme) {
        Trace.at("detecting terminals");
        boolean outTerminal = Tty.stdoutIsTerminal();
        boolean errTerminal = Tty.stderrIsTerminal();
        Trace.at("terminals detected");
        boolean noColor = System.getenv("NO_COLOR") != null && !System.getenv("NO_COLOR").isEmpty();
        boolean dumb = "dumb".equals(System.getenv("TERM"));
        // on a terminal the escape handling and UTF-8 code page must be switched on, whatever the colour choice
        boolean outVt = !outTerminal || Tty.enableVirtualTerminal(false);
        boolean errVt = !errTerminal || Tty.enableVirtualTerminal(true);
        boolean outColor = wantsColor(mode, outTerminal, noColor, dumb) && (outVt || mode == ColorMode.ALWAYS);
        boolean errColor = wantsColor(mode, errTerminal, noColor, dumb) && (errVt || mode == ColorMode.ALWAYS);
        return new Console(new Out(false, outTerminal, outColor, theme), new Out(true, errTerminal, errColor, theme));
    }

    private static boolean wantsColor(ColorMode mode, boolean terminal, boolean noColor, boolean dumb) {
        return switch (mode) {
            case NEVER -> false;
            case ALWAYS -> true;
            case AUTO -> terminal && !noColor && !dumb;
        };
    }

    Out out() {
        return out;
    }

    Out err() {
        return err;
    }

    void close() {
        out.flush();
        err.flush();
        Tty.restore();
    }
}
