package org.ds.tuifileman.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Human-editable settings in INI style: {@code [section]} headers, {@code key = value} lines and
 * {@code #} or {@code ;} comment lines. Unknown keys are ignored and bad lines only produce warnings,
 * so a typo never prevents the program from starting.
 */
public final class Config {
    private static final String DEFAULT_TEXT = """
            # tuifileman settings. Lines starting with # or ; are comments.
            # Delete a line to get its default. Changes apply the next time the program starts.

            [theme]
            # dark, light, contrast (high contrast) or mc (Midnight Commander look).
            # Also switchable in the Options menu, which saves the choice here.
            name = dark

            [run]
            # Run command (Ctrl+O) runs the command in the active folder: in a new terminal window on Windows,
            # in this terminal on Linux (the program waits, then repaints).
            # Built-in shells: cmd, powershell, pwsh and wsl (the last three when found on PATH) on Windows;
            # bash, your login shell and sh on Linux, plus cmd, powershell and pwsh through WSL interop.
            # shell: the shell chosen last in the dialog (saved here by the program); default cmd, or bash on Linux.
            # shell = bash
            # keep_open: true = the window stays open after the command (cmd /k, -NoExit, exec bash); on Linux the
            # program waits for Enter after the command. Also remembered from the dialog.
            keep_open = true
            # Extra shells, shown in the dialog: shell.NAME = command line to start.
            # {cmd} is replaced by the typed command (quotes in it become \\"); without {cmd} the
            # command is appended. Example:
            # shell.gitbash = "C:\\Program Files\\Git\\bin\\bash.exe" -c "{cmd}; exec bash"

            [viewer]
            # Line numbers in the F3 text viewer (L toggles them while viewing): true or false.
            line_numbers = true
            # Wrap long lines in the F3 text viewer instead of panning sideways (W toggles it while viewing).
            wrap = true

            [image]
            # External renderer for image preview in F3 (png, jpg, gif, webp, svg, tiff, qoi).
            # A name on PATH or a full path; leave empty to disable image preview.
            chafa = chafa
            # Character cells used for the picture: any chafa --symbols value, for example
            # half+quad+space (default), block, half, ascii.
            symbols = half+quad+space
            # symbols = coloured block characters, works in every terminal.
            # sixel   = real pixels, for sixel terminals (Windows Terminal 1.22+). S toggles it while viewing.
            format = sixel
            # Terminal cell size in pixels for sixel pictures: auto asks the terminal (falls back to 10x20),
            # or a fixed WIDTHxHEIGHT such as 7x17.
            cell_size = auto

            [pdf]
            # Poppler tools used by F3 on .pdf files: pdfinfo (details), pdftoppm (page picture,
            # shown through the image settings above) and pdftotext (text view, Tab).
            # A name on PATH or a full path.
            pdfinfo = pdfinfo
            pdftoppm = pdftoppm
            pdftotext = pdftotext

            [video]
            # F3 on video/audio files: ffprobe (details), ffmpeg (preview frame, Space/PgUp for
            # other frames) and mpv (P plays it in the terminal).
            ffprobe = ffprobe
            ffmpeg = ffmpeg
            mpv = mpv
            # mpv video output: sixel = pixels inside the terminal (needs an mpv build with sixel,
            # otherwise tct is used); tct = coloured half blocks, works with any build;
            # gpu = a separate window with full quality.
            vo = sixel
            # Terminal cell size in pixels (width x height), used to size the sixel picture.
            cell_size = 10x20
            # Extra mpv arguments, space separated, for example --vo-sixel-dither=none.
            args =

            [editor]
            # F4 editor (Shift+F4 creates a new file).
            tab_size = 4
            # true = Tab inserts a tab character, false = spaces.
            use_tabs = false
            # Enter keeps the indentation of the current line.
            auto_indent = true
            # Line number gutter (Ctrl+L toggles it while editing).
            line_numbers = true
            # Wrap long lines instead of panning sideways (Alt+W toggles it while editing); Home/End and
            # Up/Down then work on screen rows.
            wrap = true

            [sync]
            # Two files whose modified times differ by at most this count as having the same time:
            # directory sync (Ctrl+F9), copy/move "overwrite older" and the diff/sync/copy/move commands
            # (--time-tolerance overrides it there). A whole number with a unit s, m or h, for example
            # 2s (default), 90s, 5m, 1h; 0 = the times must be exactly equal.
            time_tolerance = 2s

            # Not settings of this file: command-line options (--theme, --time-tolerance, --threads, ...), the
            # environment (NO_COLOR, TFM_TRACE) and -Dtfm.threads=N. The full reference is CONFIG.md in the repository.
            """;

    private static Config current;

    private final Map<String, String> values = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final Path file;

    private Config(Path file) {
        this.file = file;
    }

    /** The settings of this run, loaded on first use from {@link #defaultFile()}. */
    public static synchronized Config get() {
        if (current == null) {
            current = load(defaultFile(), true);
        }
        return current;
    }

    public static Path defaultFile() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Path.of(appData, "tuifileman")
                : Path.of(System.getProperty("user.home"), ".tuifileman");
        return base.resolve("config.ini");
    }

    /** Reads {@code file}; when it does not exist and {@code create} is set, writes the commented defaults. */
    public static Config load(Path file, boolean create) {
        Config config = new Config(file);
        if (!Files.exists(file)) {
            if (create) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, DEFAULT_TEXT.replace("\n", System.lineSeparator()));
                } catch (IOException e) {
                    config.warnings.add("cannot create " + file + ": " + e.getMessage());
                }
            }
            return config;
        }
        try {
            config.parse(Files.readAllLines(file));
        } catch (IOException | RuntimeException e) {
            config.warnings.add("cannot read " + file + ": " + e.getMessage());
        }
        return config;
    }

    /** Parses settings from text, for tests. */
    public static Config parse(String text) {
        Config config = new Config(null);
        config.parse(text.lines().toList());
        return config;
    }

    private void parse(List<String> lines) {
        String section = "";
        int number = 0;
        for (String raw : lines) {
            number++;
            String line = raw.strip();
            if (number == 1 && line.startsWith("\uFEFF")) {
                line = line.substring(1).strip();
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    warnings.add("line " + number + ": missing ]");
                    continue;
                }
                section = line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                warnings.add("line " + number + ": expected key = value");
                continue;
            }
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).strip();
            // Quotes around the whole value are dropped, but not when they belong to a command line.
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                    && value.indexOf('"', 1) == value.length() - 1) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(section.isEmpty() ? key : section + "." + key, value);
        }
    }

    /** The value of {@code section.key}, or {@code fallback} when it is not set. */
    public String text(String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    /** All settings whose key starts with {@code prefix} (for example {@code run.shell.}), by the rest of the key. */
    public Map<String, String> entries(String prefix) {
        Map<String, String> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getKey().length() > prefix.length()) {
                found.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return found;
    }

    public boolean flag(String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> {
                return true;
            }
            case "false", "no", "off", "0" -> {
                return false;
            }
            default -> {
                warn(key + ": '" + value + "' is not true/false");
                return fallback;
            }
        }
    }

    public int number(String key, int fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            warn(key + ": '" + value + "' is not a number");
            return fallback;
        }
    }

    public void warn(String message) {
        if (!warnings.contains(message)) {
            warnings.add(message);
        }
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * Stores {@code section.key} in memory and in the file, editing the existing line (or adding the key,
     * or the whole section) so that comments and the rest of the file stay as they are.
     *
     * @return an error message, or null on success
     */
    public synchronized String save(String key, String value) {
        values.put(key, value);
        if (file == null) {
            return null;
        }
        int dot = key.indexOf('.');
        String section = key.substring(0, dot);
        String name = key.substring(dot + 1);
        try {
            List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
            String current = "";
            int sectionStart = -1;
            int lastInSection = -1;
            int keyLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT);
                    if (current.equals(section) && sectionStart < 0) {
                        sectionStart = i;
                        lastInSection = i;
                    }
                    continue;
                }
                if (sectionStart >= 0 && current.equals(section) && !line.isEmpty()) {
                    lastInSection = i;
                }
                int eq = line.indexOf('=');
                if (current.equals(section) && keyLine < 0 && eq > 0 && !line.startsWith("#") && !line.startsWith(";")
                        && line.substring(0, eq).strip().equalsIgnoreCase(name)) {
                    keyLine = i;
                }
            }
            String entry = name + " = " + value;
            if (keyLine >= 0) {
                lines.set(keyLine, entry);
            } else if (sectionStart >= 0) {
                lines.add(lastInSection + 1, entry);
            } else {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                    lines.add("");
                }
                lines.add("[" + section + "]");
                lines.add(entry);
            }
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
            return null;
        } catch (IOException | RuntimeException e) {
            return "cannot save " + file + ": " + e.getMessage();
        }
    }

    public Path file() {
        return file;
    }
}
