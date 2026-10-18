package org.ds.tuifileman.view;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds optional external tools. A program started from an already running Windows Terminal keeps the
 * {@code PATH} it had at that time, so a tool installed later is invisible although a fresh shell finds it.
 * When a command is not on the current {@code PATH}, the machine and user {@code PATH} from the registry
 * are searched as well.
 */
final class Tools {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String[] EXTENSIONS = {"", ".exe", ".cmd", ".bat", ".com"};
    private static List<String> registryDirs;

    private Tools() { }

    /** Output of a finished tool: stdout (cut at the limit) and whether more was dropped. */
    record Output(byte[] data, boolean truncated) { }

    /** Runs a tool; a missing tool or a failure becomes a readable exception. Stdin is closed, stderr is only used for the message. */
    static Output run(List<String> command, int timeoutSeconds, long maxBytes, String tool, String installHint) {
        if (command.get(0).isEmpty()) {
            throw new PreviewSource.ToolMissing(tool + " is switched off in the configuration");
        }
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new PreviewSource.ToolMissing("cannot start '" + command.get(0) + "' (" + installHint + ")");
        }
        try {
            process.getOutputStream().close();
            CompletableFuture<Output> out = CompletableFuture.supplyAsync(
                    () -> readLimited(process.getInputStream(), maxBytes));
            CompletableFuture<Output> err = CompletableFuture.supplyAsync(
                    () -> readLimited(process.getErrorStream(), 1 << 16));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(tool + " did not finish in " + timeoutSeconds + " s");
            }
            Output result = out.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                String message = new String(err.get(5, TimeUnit.SECONDS).data(), StandardCharsets.UTF_8).strip();
                throw new IllegalStateException(tool + " failed: " + (message.isEmpty() ? "exit " + process.exitValue()
                        : message.lines().reduce((first, second) -> second).orElse(message)));
            }
            return result;
        } catch (IOException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException(tool + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } finally {
            process.destroyForcibly();
        }
    }

    private static Output readLimited(InputStream in, long max) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean truncated = false;
        byte[] buffer = new byte[1 << 16];
        try (in) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                long room = max - bytes.size();
                if (room > 0) {
                    bytes.write(buffer, 0, (int) Math.min(n, room));
                }
                truncated |= n > room;
            }
        } catch (IOException ignored) { }
        return new Output(bytes.toByteArray(), truncated);
    }

    /** The command to start: unchanged when it is a path or found on the current PATH, else the full path found. */
    static String resolve(String command) {
        if (!WINDOWS || command == null || command.isBlank() || command.contains("\\") || command.contains("/")
                || command.contains(":")) {
            return command;
        }
        if (find(command, splitPath(System.getenv("PATH"))) != null) {
            return command;
        }
        String found = find(command, registryDirs());
        if (found == null) {
            found = find(command, knownDirs());
        }
        return found != null ? found : command;
    }

    /**
     * Like {@link #resolve}, but prefers the console build when a tool ships both: mpv.exe is a GUI program
     * without terminal output, mpv.com next to it is the one that can draw into the terminal.
     */
    static String resolveConsole(String command) {
        String resolved = resolve(command);
        if (!WINDOWS || resolved == null || resolved.isBlank()) {
            return resolved;
        }
        String full = resolved.contains("\\") || resolved.contains("/") ? resolved
                : find(resolved, splitPath(System.getenv("PATH")));
        if (full != null && full.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            String console = full.substring(0, full.length() - 4) + ".com";
            if (Files.isRegularFile(Path.of(console))) {
                return console;
            }
        }
        return resolved;
    }

    /** Install folders of tools whose installers do not add themselves to PATH (mpv). */
    private static List<String> knownDirs() {
        List<String> dirs = new ArrayList<>();
        for (String variable : new String[] {"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
            String base = System.getenv(variable);
            if (base != null) {
                String prefix = variable.equals("LOCALAPPDATA") ? base + "\\Programs" : base;
                dirs.add(prefix + "\\MPV Player");
                dirs.add(prefix + "\\mpv");
            }
        }
        return dirs;
    }

    private static String find(String command, List<String> dirs) {
        for (String dir : dirs) {
            for (String extension : EXTENSIONS) {
                try {
                    Path candidate = Path.of(dir, command + extension);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toString();
                    }
                } catch (RuntimeException ignored) { }
            }
        }
        return null;
    }

    private static List<String> splitPath(String path) {
        List<String> dirs = new ArrayList<>();
        if (path != null) {
            for (String part : path.split(";")) {
                if (!part.isBlank()) {
                    dirs.add(part.strip());
                }
            }
        }
        return dirs;
    }

    private static synchronized List<String> registryDirs() {
        if (registryDirs == null) {
            List<String> dirs = new ArrayList<>();
            dirs.addAll(splitPath(expand(readPath("HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"))));
            dirs.addAll(splitPath(expand(readPath("HKCU\\Environment"))));
            registryDirs = dirs;
        }
        return registryDirs;
    }

    private static String readPath(String key) {
        try {
            Process process = new ProcessBuilder("reg", "query", key, "/v", "Path").redirectErrorStream(true).start();
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            process.waitFor(5, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("(?m)^\\s*Path\\s+REG_(?:EXPAND_)?SZ\\s+(.*)$").matcher(out);
            return m.find() ? m.group(1).strip() : "";
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Replaces %NAME% with the environment value. */
    private static String expand(String text) {
        Matcher m = Pattern.compile("%([^%]+)%").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = System.getenv(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
