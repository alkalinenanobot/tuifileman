package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LauncherUnixTest {
    private static final Path MPV = Path.of("/mnt/c/Users/x/mpv/mpv.exe");

    @Test
    void exeRunsItselfThroughInterop() {
        assertEquals(List.of(MPV.toString()), Launcher.unixCommand(MPV, true, tools -> true, p -> "C:\\x"));
    }

    @Test
    void nonExecutableExeAndOtherFilesGoToXdgOpen() {
        Path pdf = Path.of("/home/u/a.pdf");
        assertEquals(List.of("xdg-open", pdf.toString()), Launcher.unixCommand(pdf, false, Set.of("xdg-open")::contains, p -> null));
        assertEquals(List.of("xdg-open", MPV.toString()), Launcher.unixCommand(MPV, false, Set.of("xdg-open")::contains, p -> null));
    }

    @Test
    void wslFallbacksWithoutXdgOpen() {
        Path pdf = Path.of("/home/u/a.pdf");
        assertEquals(List.of("wslview", pdf.toString()),
                Launcher.unixCommand(pdf, false, Set.of("wslview", "explorer.exe")::contains, p -> "\\\\wsl\\a.pdf"));
        assertEquals(List.of("explorer.exe", "\\\\wsl\\a.pdf"),
                Launcher.unixCommand(pdf, false, Set.of("explorer.exe")::contains, p -> "\\\\wsl\\a.pdf"));
    }

    @Test
    void nothingAvailable() {
        assertNull(Launcher.unixCommand(Path.of("/home/u/a.pdf"), false, tool -> false, p -> null));
    }
}
