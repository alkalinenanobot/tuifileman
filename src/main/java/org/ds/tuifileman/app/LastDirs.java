package org.ds.tuifileman.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.ds.tuifileman.config.Config;

/**
 * The folders of the left and right panel, kept in a two-line text file next to the config so the next start opens
 * where this one ended. Remembering is a convenience: a file that cannot be read or written is ignored.
 */
final class LastDirs {
    private final Path file;
    private List<String> written = List.of();

    LastDirs() {
        this(Config.defaultFile().resolveSibling("last-dirs.txt"));
    }

    LastDirs(Path file) {
        this.file = file;
    }

    /** The remembered left and right folder; an entry is null when it is missing or the folder no longer exists. */
    Path[] load() {
        Path[] dirs = new Path[2];
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            written = List.copyOf(lines);
            for (int i = 0; i < dirs.length && i < lines.size(); i++) {
                String text = lines.get(i).strip();
                if (!text.isEmpty()) {
                    Path path = Path.of(text);
                    dirs[i] = Files.isDirectory(path) ? path : null;
                }
            }
        } catch (IOException | InvalidPathException e) {
            // No file yet, or an unreadable or hand-damaged one: start in the current folder.
        }
        return dirs;
    }

    /** Remembers the folders ({@code null} = none); writes only when they differ from what is already on disk. */
    void save(Path left, Path right) {
        List<String> lines = List.of(left == null ? "" : left.toString(), right == null ? "" : right.toString());
        if (lines.equals(written)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
            written = lines;
        } catch (IOException e) {
            // Not remembered this time; the next change tries again.
        }
    }
}
