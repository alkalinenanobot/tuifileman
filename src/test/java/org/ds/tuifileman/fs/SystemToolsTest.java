package org.ds.tuifileman.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SystemToolsTest {
    private static final String ROOT = Path.of("system", "root").toString();

    @Test
    void builtInsLiveUnderSystem32OfTheSystemRoot() {
        assertEquals(Path.of(ROOT, "System32", "clip.exe").toString(), SystemTools.system32("clip.exe", ROOT));
        assertEquals("clip.exe", SystemTools.system32("clip.exe", null), "no SystemRoot: the bare name");
        assertEquals("clip.exe", SystemTools.system32("clip.exe", " "));
    }

    @Test
    void cmdIsTheAbsoluteComSpecElseSystem32() {
        Path comSpec = Path.of("elsewhere", "cmd.exe").toAbsolutePath();
        assertEquals(comSpec.toString(), SystemTools.cmd(comSpec.toString(), ROOT));
        String system = Path.of(ROOT, "System32", "cmd.exe").toString();
        assertEquals(system, SystemTools.cmd("cmd.exe", ROOT), "a relative ComSpec would search the working folder");
        assertEquals(system, SystemTools.cmd(null, ROOT));
    }
}
