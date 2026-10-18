package org.ds.tuifileman.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SystemClipboardTest {
    @Test
    void wslWithoutLinuxToolsUsesWindowsTools() {
        var tool = SystemClipboard.choose(false, Set.of("clip.exe", "powershell.exe")::contains, "wayland-0", ":0");
        assertEquals(List.of("clip.exe"), tool.copy());
        assertTrue(tool.windows());
    }

    @Test
    void waylandThenXclipThenXsel() {
        assertEquals("wl-copy", SystemClipboard.choose(false, t -> true, "wayland-0", ":0").copy().get(0));
        assertEquals("xclip", SystemClipboard.choose(false, t -> true, null, ":0").copy().get(0));
        assertEquals("xsel", SystemClipboard.choose(false, Set.of("xsel")::contains, null, ":0").copy().get(0));
        assertFalse(SystemClipboard.choose(false, t -> true, null, ":0").windows());
    }

    @Test
    void displayToolsNeedADisplay() {
        assertNull(SystemClipboard.choose(false, Set.of("xclip", "wl-copy", "wl-paste")::contains, null, null));
    }

    @Test
    void nothingAvailable() {
        assertNull(SystemClipboard.choose(false, t -> false, "wayland-0", ":0"));
    }
}
