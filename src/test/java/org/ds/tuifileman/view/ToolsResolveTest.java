package org.ds.tuifileman.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolsResolveTest {
    @TempDir
    Path dir;

    @Test
    void aToolFoundOnPathIsStartedByItsFullPath() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path tool = Files.createFile(dir.resolve("fakeviewer.exe"));
        // An extensionless sibling is a script for another shell and must not win over the .exe.
        Files.createFile(dir.resolve("fakeviewer"));
        assertEquals(tool.toString(), Tools.resolve("fakeviewer", dir.toString() + ";" + dir.resolve("none")));
        assertTrue(Path.of(Tools.resolve("fakeviewer.exe", dir.toString())).isAbsolute());
    }

    @Test
    void aConfiguredPathStaysAsItIs() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        assertEquals("C:\\tools\\ffmpeg.exe", Tools.resolve("C:\\tools\\ffmpeg.exe", ""));
        assertEquals("", Tools.resolve("", "C:\\tools"));
    }
}
