package org.ds.tuifileman.cli;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * What the standard streams are connected to. {@code System.console()} cannot answer this: it needs
 * both stdin and stdout to be a terminal (JDK 17) or is never null (JDK 22+), while the CLI has to decide
 * per stream, because {@code diff a b | less} still has a terminal on stderr and wants its progress bar.
 * Windows is asked through kernel32, Linux through /proc/self/fd and stty; elsewhere the answer is a conservative "no".
 */
final class Tty {
    private static final int STDIN = -10;
    private static final int STDOUT = -11;
    private static final int STDERR = -12;
    private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;
    private static final int UTF8 = 65001;

    interface Api extends StdCallLibrary {
        Pointer GetStdHandle(int which);

        boolean GetConsoleMode(Pointer handle, IntByReference mode);

        boolean SetConsoleMode(Pointer handle, int mode);

        int GetConsoleOutputCP();

        boolean SetConsoleOutputCP(int codePage);

        boolean GetConsoleScreenBufferInfo(Pointer handle, byte[] info);
    }

    private static final Api API = load();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static int cachedColumns;
    private static long cachedAt;
    private static int savedOutputCodePage;
    private static final int[] savedModes = {-1, -1};

    private Tty() { }

    private static Api load() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        try {
            Trace.at("jna: initialising (unpacks jnidispatch.dll)");
            Class.forName("com.sun.jna.Native");
            Trace.at("jna: native dispatch loaded");
            Api api = Native.load("kernel32", Api.class, W32APIOptions.UNICODE_OPTIONS);
            Trace.at("jna: kernel32 bound");
            return api;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean stdoutIsTerminal() {
        return isTerminal(STDOUT);
    }

    static boolean stdinIsTerminal() {
        return isTerminal(STDIN);
    }

    /** Questions can be asked only when someone can see them and answer them. */
    static boolean interactive() {
        return stdinIsTerminal() && stderrIsTerminal();
    }

    static boolean stderrIsTerminal() {
        return isTerminal(STDERR);
    }

    private static boolean isTerminal(int which) {
        if (API == null) {
            return !WINDOWS && linuxIsTerminal(fd(which));
        }
        Pointer handle = API.GetStdHandle(which);
        return handle != null && API.GetConsoleMode(handle, new IntByReference());
    }

    private static int fd(int which) {
        return which == STDIN ? 0 : which == STDOUT ? 1 : 2;
    }

    /** A stream is a terminal when its descriptor points at a tty or pty device (no libc call, so no JNA). */
    private static boolean linuxIsTerminal(int fd) {
        try {
            String target = Files.readSymbolicLink(Path.of("/proc/self/fd/" + fd)).toString();
            return target.startsWith("/dev/pts/") || target.startsWith("/dev/tty") || target.equals("/dev/console");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Columns of the console behind this stream; 80 when unknown. */
    static int width(boolean stderr) {
        if (API == null) {
            return WINDOWS ? 80 : linuxWidth(stderr ? 2 : 1);
        }
        Pointer handle = API.GetStdHandle(stderr ? STDERR : STDOUT);
        byte[] info = new byte[22];
        if (handle != null && API.GetConsoleScreenBufferInfo(handle, info)) {
            int left = (info[10] & 0xFF) | (info[11] << 8);
            int right = (info[14] & 0xFF) | (info[15] << 8);
            int columns = right - left + 1;
            if (columns > 0) {
                return columns;
            }
        }
        return 80;
    }

    /** Asks {@code stty} about the terminal on that descriptor; remembered for a moment because progress asks per frame. */
    private static synchronized int linuxWidth(int fd) {
        long now = System.nanoTime();
        if (cachedColumns > 0 && now - cachedAt < 500_000_000L) {
            return cachedColumns;
        }
        int columns = 0;
        try {
            Process process = new ProcessBuilder("stty", "size")
                    .redirectInput(new java.io.File("/proc/" + ProcessHandle.current().pid() + "/fd/" + fd))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String[] parts = new String(process.getInputStream().readAllBytes()).trim().split("\\s+");
            process.waitFor(2, TimeUnit.SECONDS);
            if (parts.length == 2) {
                columns = Integer.parseInt(parts[1]);
            }
        } catch (IOException | NumberFormatException e) {
            // fall through to COLUMNS
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (columns <= 0) {
            try {
                columns = Integer.parseInt(System.getenv().getOrDefault("COLUMNS", "0").trim());
            } catch (NumberFormatException e) {
                columns = 0;
            }
        }
        cachedColumns = columns > 0 ? columns : 80;
        cachedAt = now;
        return cachedColumns;
    }

    /**
     * Turns on ANSI escape handling and UTF-8 output for this terminal stream, remembering the old settings
     * for {@link #restore()}. Returns false when escapes cannot be used (very old consoles).
     */
    static boolean enableVirtualTerminal(boolean stderr) {
        if (API == null) {
            return !WINDOWS;
        }
        Pointer handle = API.GetStdHandle(stderr ? STDERR : STDOUT);
        IntByReference mode = new IntByReference();
        if (handle == null || !API.GetConsoleMode(handle, mode)) {
            return false;
        }
        int index = stderr ? 1 : 0;
        if (savedModes[index] < 0) {
            savedModes[index] = mode.getValue();
        }
        if (savedOutputCodePage == 0) {
            savedOutputCodePage = API.GetConsoleOutputCP();
            API.SetConsoleOutputCP(UTF8);
        }
        return API.SetConsoleMode(handle, mode.getValue() | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
    }

    /** Puts the console modes and code page back as they were. */
    static void restore() {
        if (API == null) {
            return;
        }
        for (int index = 0; index < savedModes.length; index++) {
            if (savedModes[index] >= 0) {
                Pointer handle = API.GetStdHandle(index == 0 ? STDOUT : STDERR);
                if (handle != null) {
                    API.SetConsoleMode(handle, savedModes[index]);
                }
                savedModes[index] = -1;
            }
        }
        if (savedOutputCodePage != 0) {
            API.SetConsoleOutputCP(savedOutputCodePage);
            savedOutputCodePage = 0;
        }
    }
}
