package org.ds.tuifileman.edit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The system clipboard through external tools; every failure just means "not available" and the editor keeps its
 * own copy. Windows: {@code clip.exe} and PowerShell. Linux: wl-copy/wl-paste (Wayland), xclip or xsel (X11), and in
 * WSL the Windows tools through interop.
 */
final class SystemClipboard {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final List<String> POWERSHELL_PASTE = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);[Console]::Out.Write((Get-Clipboard -Raw))");

    /** {@code windows}: clip.exe wants UTF-16 with CRLF and PowerShell answers with CRLF. */
    record Tool(List<String> copy, List<String> paste, boolean windows) { }

    private static Tool tool;
    private static boolean toolChosen;

    private SystemClipboard() { }

    static Tool choose(boolean windows, Predicate<String> onPath, String wayland, String display) {
        if (windows) {
            return new Tool(List.of("clip.exe"), POWERSHELL_PASTE, true);
        }
        if (wayland != null && !wayland.isEmpty() && onPath.test("wl-copy") && onPath.test("wl-paste")) {
            return new Tool(List.of("wl-copy"), List.of("wl-paste", "--no-newline"), false);
        }
        if (display != null && !display.isEmpty()) {
            if (onPath.test("xclip")) {
                return new Tool(List.of("xclip", "-selection", "clipboard", "-in"),
                        List.of("xclip", "-selection", "clipboard", "-out"), false);
            }
            if (onPath.test("xsel")) {
                return new Tool(List.of("xsel", "--clipboard", "--input"), List.of("xsel", "--clipboard", "--output"), false);
            }
        }
        if (onPath.test("clip.exe") && onPath.test("powershell.exe")) {
            return new Tool(List.of("clip.exe"), POWERSHELL_PASTE, true);
        }
        return null;
    }

    private static synchronized Tool tool() {
        if (!toolChosen) {
            tool = choose(WINDOWS, SystemClipboard::onPath, System.getenv("WAYLAND_DISPLAY"), System.getenv("DISPLAY"));
            toolChosen = true;
        }
        return tool;
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank() && Files.isRegularFile(Path.of(dir.strip()).resolve(exe))) {
                    return true;
                }
            }
        }
        return false;
    }

    static void set(String text) {
        Tool tool = tool();
        if (tool == null) {
            return;
        }
        try {
            Process process = new ProcessBuilder(tool.copy())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream out = process.getOutputStream()) {
                out.write(tool.windows()
                        ? text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_16LE)
                        : text.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            // no clipboard tool: the editor keeps its own copy
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The clipboard text, or null when it cannot be read or holds no text. */
    static String get() {
        Tool tool = tool();
        if (tool == null) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(tool.paste())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            byte[] data;
            try (InputStream in = process.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                in.transferTo(buffer);
                data = buffer.toByteArray();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String text = new String(data, StandardCharsets.UTF_8);
            return text.isEmpty() ? null : text.replace("\r\n", "\n");
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
