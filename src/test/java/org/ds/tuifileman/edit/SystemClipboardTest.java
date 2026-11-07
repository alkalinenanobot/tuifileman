package org.ds.tuifileman.edit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SystemClipboardTest {
    @Test
    void onWindowsTheToolsAreStartedByAbsolutePath() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win")
                && System.getenv("SystemRoot") != null);
        SystemClipboard.Tool tool = SystemClipboard.choose(true, name -> false, null, null);
        assertTrue(Path.of(tool.copy().get(0)).isAbsolute(), "clip: " + tool.copy());
        assertTrue(Path.of(tool.paste().get(0)).isAbsolute(), "powershell: " + tool.paste());
    }
}
