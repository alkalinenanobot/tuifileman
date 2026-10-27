package org.ds.tuifileman.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Favourite directories, kept in a plain text file (one path per line) so it can be edited by hand. */
final class Bookmarks {

    private final Path file;
    private final List<Path> paths = new ArrayList<>();

    Bookmarks() {
        this(defaultFile());
    }

    Bookmarks(Path file) {
        this.file = file;
        load();
    }

    private static Path defaultFile() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Path.of(appData, "tuifileman")
                : Path.of(System.getProperty("user.home"), ".tuifileman");
        return base.resolve("bookmarks.txt");
    }

    List<Path> list() {
        return List.copyOf(paths);
    }

    boolean contains(Path path) {
        return paths.contains(path.normalize());
    }

    /** Returns an error message, or null on success. */
    String add(Path path) {
        Path normalized = path.normalize();
        if (paths.contains(normalized)) {
            return "Already bookmarked";
        }
        paths.add(normalized);
        return save();
    }

    String remove(int index) {
        if (index < 0 || index >= paths.size()) {
            return null;
        }
        paths.remove(index);
        return save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.strip();
                if (!text.isEmpty()) {
                    try {
                        paths.add(Path.of(text).normalize());
                    } catch (InvalidPathException ignored) {
                        // A hand-edited bad line is skipped.
                    }
                }
            }
        } catch (IOException ignored) {
            // Unreadable file: start with an empty list.
        }
    }

    private String save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            for (Path path : paths) {
                lines.add(path.toString());
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            return "Cannot save bookmarks: " + e.getMessage();
        }
    }
}
