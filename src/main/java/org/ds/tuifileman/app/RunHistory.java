package org.ds.tuifileman.app;

import org.ds.tuifileman.config.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Commands typed in the run dialog, newest first, one per line in {@code run-history.txt} next to the INI file. */
final class RunHistory {
    private static final int LIMIT = 100;

    private final Path file;
    private final List<String> commands = new ArrayList<>();

    RunHistory() {
        this(Config.defaultFile().resolveSibling("run-history.txt"));
    }

    RunHistory(Path file) {
        this.file = file;
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank() && commands.size() < LIMIT) {
                        commands.add(line.strip());
                    }
                }
            }
        } catch (IOException ignored) {
            // Unreadable file: start with an empty history.
        }
    }

    List<String> list() {
        return List.copyOf(commands);
    }

    /** Moves {@code command} to the front (no duplicates). Returns an error message, or null. */
    String add(String command) {
        String text = command.strip();
        if (text.isEmpty()) {
            return null;
        }
        commands.remove(text);
        commands.add(0, text);
        while (commands.size() > LIMIT) {
            commands.remove(commands.size() - 1);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, commands, StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            return "Cannot save history: " + e.getMessage();
        }
    }
}
