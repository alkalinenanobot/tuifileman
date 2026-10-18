package org.ds.tuifileman.app;

import org.ds.tuifileman.config.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Starts a typed command in a shell, like Win+R with a choice of shell.
 *
 * <p>On Windows the shell gets a separate terminal window: a new console started through
 * {@code cmd /c start}, so it outlives this program, and the shell is told to stay open ({@code /k},
 * {@code -NoExit}, {@code exec bash}) so the output can be read. The command text goes through two
 * command-line parsers (ours, then {@code start}), which is why PowerShell and WSL receive it Base64-encoded
 * and every cmd special character of the final line is escaped with a caret: whatever the user types arrives
 * in the shell unchanged.
 *
 * <p>On Linux (also WSL) the shell runs in the terminal of the program: the caller hands the terminal over
 * with {@link #launch}, like mc does. {@code bash} (and the login shell) are native; under WSL interop
 * {@code cmd.exe}, {@code powershell.exe} and {@code pwsh.exe} work too. The cmd line travels in an
 * environment variable (listed in {@code WSLENV}), because the interop layer re-quotes arguments with the
 * rules of CommandLineToArgvW, which cmd does not share.
 */
final class RunCommand {
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    enum Kind { CMD, POWERSHELL, PWSH, WSL, UNIX, CUSTOM }

    /** {@code template} is the executable of a UNIX shell and the command template of a custom one. */
    record Shell(String name, Kind kind, String template) { }

    /** What to start in the terminal of this program (Linux): argument vector and extra environment. */
    record Launch(List<String> command, Map<String, String> environment) { }

    private RunCommand() { }

    /** The shell chosen when none was saved. */
    static String defaultShell() {
        return WINDOWS ? "cmd" : "bash";
    }

    /** Built-in shells that exist on this machine, then the ones from {@code [run] shell.NAME} in the INI. */
    static List<Shell> shells() {
        List<Shell> shells = new ArrayList<>();
        if (WINDOWS) {
            shells.add(new Shell("cmd", Kind.CMD, null));
        } else {
            addUnixShell(shells, "bash");
            String login = System.getenv("SHELL");
            if (login != null && !login.isBlank()) {
                addUnixShell(shells, Path.of(login.strip()).getFileName().toString());
            }
            addUnixShell(shells, "sh");
            if (onPath("cmd.exe")) {
                shells.add(new Shell("cmd", Kind.CMD, null));
            }
        }
        if (onPath("powershell.exe")) {
            shells.add(new Shell("powershell", Kind.POWERSHELL, null));
        }
        if (onPath("pwsh.exe")) {
            shells.add(new Shell("pwsh", Kind.PWSH, null));
        }
        if (WINDOWS && onPath("wsl.exe")) {
            shells.add(new Shell("wsl", Kind.WSL, null));
        }
        for (Map.Entry<String, String> extra : Config.get().entries("run.shell.").entrySet()) {
            if (!extra.getValue().isBlank()) {
                shells.removeIf(shell -> shell.name().equals(extra.getKey()));
                shells.add(new Shell(extra.getKey(), Kind.CUSTOM, extra.getValue()));
            }
        }
        return shells;
    }

    private static void addUnixShell(List<Shell> shells, String name) {
        Path exe = find(name);
        if (exe != null && shells.stream().noneMatch(shell -> shell.name().equals(name))) {
            shells.add(new Shell(name, Kind.UNIX, exe.toString()));
        }
    }

    /**
     * Linux: the process that runs {@code command} in {@code shell} in the current terminal. {@code windowsDirectory}
     * is the working folder as a Windows path (see {@link #windowsPath}) or null; cmd needs it because it cannot
     * start in a {@code \\wsl.localhost} folder.
     */
    static Launch launch(Shell shell, String command, String windowsDirectory) {
        String text = command.strip().replace('\r', ' ').replace('\n', ' ');
        boolean empty = text.isEmpty();
        return switch (shell.kind()) {
            case UNIX -> {
                String name = Path.of(shell.template()).getFileName().toString();
                yield new Launch(empty ? List.of(shell.template())
                        : List.of(shell.template(), name.equals("bash") || name.equals("zsh") ? "-lc" : "-c", text), Map.of());
            }
            case CMD -> {
                String line = windowsDirectory == null ? text
                        : "pushd \"" + windowsDirectory + "\"" + (empty ? "" : " && " + text);
                yield line.isEmpty() ? new Launch(List.of("cmd.exe"), Map.of())
                        : new Launch(List.of("cmd.exe", "/d", empty ? "/k" : "/c", "%TFM_LINE%"), withWslEnv("TFM_LINE", line));
            }
            case POWERSHELL, PWSH -> {
                String exe = shell.kind() == Kind.PWSH ? "pwsh.exe" : "powershell.exe";
                yield new Launch(empty ? List.of(exe) : List.of(exe, "-EncodedCommand",
                        Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_16LE))), Map.of());
            }
            case CUSTOM -> {
                String template = shell.template();
                yield new Launch(List.of("sh", "-c", template.contains("{cmd}")
                        ? template.replace("{cmd}", text.replace("\"", "\\\""))
                        : empty ? template : template + " " + text), Map.of());
            }
            case WSL -> throw new IllegalArgumentException("the wsl shell is a Windows entry");
        };
    }

    private static Map<String, String> withWslEnv(String variable, String value) {
        String wslenv = System.getenv("WSLENV");
        return Map.of(variable, value, "WSLENV", wslenv == null || wslenv.isBlank() ? variable : wslenv + ":" + variable);
    }

    /** The Windows form of a Linux path ({@code wslpath -w}), or null outside WSL. */
    static String windowsPath(Path directory) {
        try {
            Process process = new ProcessBuilder("wslpath", "-w", directory.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.waitFor() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The command line that runs {@code command} in {@code shell} (what {@code start} is given). */
    static String commandLine(Shell shell, String command, boolean keepOpen) {
        String text = command.strip().replace('\r', ' ').replace('\n', ' ');
        boolean empty = text.isEmpty();
        return switch (shell.kind()) {
            case CMD -> empty ? "cmd.exe /k" : "cmd.exe /s " + (keepOpen ? "/k" : "/c") + " \"" + text + "\"";
            case UNIX -> throw new IllegalArgumentException("a Unix shell is started with launch()");
            case POWERSHELL -> powershell("powershell.exe", text, keepOpen);
            case PWSH -> powershell("pwsh.exe", text, keepOpen);
            case WSL -> empty ? "wsl.exe" : "wsl.exe -e bash -lc \"eval \\\"$(echo "
                    + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
                    + " | base64 -d)\\\"" + (keepOpen ? "; exec bash -l" : "") + "\"";
            case CUSTOM -> {
                String template = shell.template();
                yield template.contains("{cmd}")
                        ? template.replace("{cmd}", text.replace("\"", "\\\""))
                        : empty ? template : template + " " + text;
            }
        };
    }

    private static String powershell(String exe, String text, boolean keepOpen) {
        if (text.isEmpty()) {
            return exe + " -NoExit";
        }
        String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_16LE));
        return exe + (keepOpen ? " -NoExit" : "") + " -EncodedCommand " + encoded;
    }

    /** The argument for {@code cmd /s /c} that opens {@code commandLine} in a new window. */
    static String startLine(String commandLine) {
        StringBuilder escaped = new StringBuilder("start ");
        for (char c : ("\"\" " + commandLine).toCharArray()) {
            if ("^&|<>()\"".indexOf(c) >= 0) {
                escaped.append('^');
            }
            escaped.append(c);
        }
        return "\"" + escaped + "\"";
    }

    /** Starts the window and returns at once; the result is a status message. */
    static String run(Shell shell, String command, boolean keepOpen, Path directory) {
        if (!WINDOWS) {
            return "Run command is only available on Windows";
        }
        try {
            new ProcessBuilder("cmd.exe", "/d", "/s", "/c", startLine(commandLine(shell, command, keepOpen)))
                    .directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return command.isBlank() ? "Opened " + shell.name() + " in " + directory
                    : "Started in " + shell.name() + ": " + command.strip();
        } catch (IOException | RuntimeException e) {
            return "Cannot start " + shell.name() + ": " + e.getMessage();
        }
    }

    static boolean onPath(String exe) {
        return find(exe) != null;
    }

    private static Path find(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank()) {
                Path candidate = Path.of(dir.strip()).resolve(exe);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
