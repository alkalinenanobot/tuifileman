package org.ds.tuifileman.fs;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Absolute paths of the programs Windows ships. A bare name given to CreateProcess is looked up in the working
 * folder first, so a file manager sitting in a folder with a {@code cmd.exe} or {@code clip.exe} of its own would
 * run that one instead of the real tool.
 */
public final class SystemTools {
    private SystemTools() { }

    /** {@code %SystemRoot%\System32\relative}; the bare name when {@code SystemRoot} is not set. */
    public static String system32(String relative) {
        return system32(relative, System.getenv("SystemRoot"));
    }

    static String system32(String relative, String systemRoot) {
        if (systemRoot == null || systemRoot.isBlank()) {
            return relative;
        }
        try {
            return Path.of(systemRoot.strip(), "System32", relative).toString();
        } catch (InvalidPathException e) {
            return relative;
        }
    }

    /** The command interpreter: {@code %ComSpec%} when it is an absolute path, else System32's {@code cmd.exe}. */
    public static String cmd() {
        return cmd(System.getenv("ComSpec"), System.getenv("SystemRoot"));
    }

    static String cmd(String comSpec, String systemRoot) {
        if (comSpec != null && !comSpec.isBlank()) {
            try {
                if (Path.of(comSpec.strip()).isAbsolute()) {
                    return comSpec.strip();
                }
            } catch (InvalidPathException ignored) {
                // an unusable ComSpec is replaced by the real cmd.exe
            }
        }
        return system32("cmd.exe", systemRoot);
    }

    /** Windows PowerShell 5, the one every Windows has. */
    public static String powershell() {
        return system32("WindowsPowerShell\\v1.0\\powershell.exe");
    }
}
