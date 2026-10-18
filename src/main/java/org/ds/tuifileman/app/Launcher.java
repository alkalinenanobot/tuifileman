package org.ds.tuifileman.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Opens a file with the program Windows associates with its type, like a double click in Explorer. It goes
 * through {@code rundll32 url.dll,FileProtocolHandler} so that no AWT/Desktop classes are needed; programs
 * run in their own window and are not waited for.
 */
final class Launcher {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private Launcher() { }

    /** Returns a status message. */
    static String open(Path file) {
        Path absolute = file.toAbsolutePath();
        List<String> command = WINDOWS
                ? List.of("rundll32.exe", "url.dll,FileProtocolHandler", absolute.toString())
                : unixCommand(absolute, Files.isExecutable(absolute), RunCommand::onPath, RunCommand::windowsPath);
        if (command == null) {
            return "Cannot open " + file.getFileName() + ": no opener found (install xdg-utils; in WSL wslu or explorer.exe also work)";
        }
        try {
            new ProcessBuilder(command)
                    .directory(absolute.getParent() == null ? null : absolute.getParent().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return "Opened " + file.getFileName();
        } catch (IOException e) {
            return "Cannot open " + file.getFileName() + ": " + e.getMessage();
        }
    }

    /**
     * Linux/WSL: a Windows program ({@code .exe}, through WSL interop) runs itself; other files go to xdg-open,
     * else to wslview (wslu) or Explorer in WSL. Null when nothing can open it.
     */
    static List<String> unixCommand(Path file, boolean executable, Predicate<String> onPath,
            Function<Path, String> windowsPath) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe") && executable) {
            return List.of(file.toString());
        }
        if (onPath.test("xdg-open")) {
            return List.of("xdg-open", file.toString());
        }
        if (onPath.test("wslview")) {
            return List.of("wslview", file.toString());
        }
        if (onPath.test("explorer.exe")) {
            String windows = windowsPath.apply(file);
            if (windows != null) {
                return List.of("explorer.exe", windows);
            }
        }
        return null;
    }
}
