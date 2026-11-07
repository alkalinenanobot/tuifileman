package org.ds.tuifileman.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigSaveTest {
    @TempDir
    Path dir;

    private Path write(String... lines) throws Exception {
        Path file = dir.resolve("config.ini");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void changesTheKeyInPlaceAndAddsNewOnesToTheirSectionKeepingComments() throws Exception {
        Path file = write("# top comment", "[view]", "wrap = true", "", "[run]", "; shell note", "shell = cmd", "", "[tail]", "x = 1");
        Config config = Config.load(file, false);
        assertNull(config.save("run.shell", "bash"));
        assertNull(config.save("run.keep_open", "true"));
        assertNull(config.save("new.key", "v"));
        assertEquals(List.of("# top comment", "[view]", "wrap = true", "", "[run]", "; shell note", "shell = bash",
                "keep_open = true", "", "[tail]", "x = 1", "", "[new]", "key = v"), Files.readAllLines(file));
        assertEquals("bash", Config.load(file, false).text("run.shell", ""));
    }

    @Test
    void aByteOrderMarkInFrontOfTheFirstSectionDoesNotDuplicateIt() throws Exception {
        Path file = write("\uFEFF[run]", "shell = cmd");
        Config config = Config.load(file, false);
        assertEquals("cmd", config.text("run.shell", ""));
        assertNull(config.save("run.shell", "bash"));
        assertNull(config.save("run.keep_open", "true"));
        assertEquals(List.of("[run]", "shell = bash", "keep_open = true"), Files.readAllLines(file));
    }
}
