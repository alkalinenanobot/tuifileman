package org.ds.tuifileman.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the program as a child JVM, the way a script would: exit code, standard output and standard error are
 * kept apart. The CLI writes to the real file descriptors, so it cannot be captured inside the test JVM.
 */
final class Cmd {
    /** What a run produced. */
    record Result(int exit, String out, String err) {
        List<String> outLines() {
            return out.isEmpty() ? List.of() : List.of(out.split("\\R"));
        }

        /** All of stdout parsed as one JSON object (format json). */
        Map<?, ?> json() {
            return (Map<?, ?>) JsonReader.parse(out);
        }

        /** The last line of stdout parsed as a JSON object (format jsonl). */
        Map<?, ?> lastJson() {
            List<String> lines = outLines();
            return (Map<?, ?>) JsonReader.parse(lines.get(lines.size() - 1));
        }
    }

    private Cmd() { }

    private static final Path APPDATA = isolatedAppData();

    private static Path isolatedAppData() {
        try {
            return Files.createTempDirectory("tfm-appdata");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The child process gets an empty settings folder, so the tests never depend on the developer's own INI file. */
    static Result run(String... arguments) throws Exception {
        return run(APPDATA, arguments);
    }

    /** As {@link #run}, with the given text as the settings file ({@code config.ini}). */
    static Result runWithIni(Path scratch, String ini, String... arguments) throws Exception {
        Path appData = Files.createDirectories(scratch.resolve("appdata"));
        Files.createDirectories(appData.resolve("tuifileman"));
        Files.writeString(appData.resolve("tuifileman").resolve("config.ini"), ini);
        return run(appData, arguments);
    }

    private static Result run(Path appData, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.ds.tuifileman.app.App");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("NO_COLOR", "1");
        builder.environment().put("APPDATA", appData.toString());
        builder.environment().remove("TFM_TRACE");
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<String> out = read(process.getInputStream());
        CompletableFuture<String> err = read(process.getErrorStream());
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out: " + command);
        }
        return new Result(process.exitValue(), out.get(), err.get());
    }

    private static CompletableFuture<String> read(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    static Path file(Path dir, String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }
}
