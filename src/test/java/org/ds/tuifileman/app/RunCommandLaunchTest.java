package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunCommandLaunchTest {
    private static final RunCommand.Shell BASH = new RunCommand.Shell("bash", RunCommand.Kind.UNIX, "/usr/bin/bash");
    private static final RunCommand.Shell FISH = new RunCommand.Shell("fish", RunCommand.Kind.UNIX, "/usr/bin/fish");
    private static final RunCommand.Shell CMD = new RunCommand.Shell("cmd", RunCommand.Kind.CMD, null);
    private static final RunCommand.Shell PS = new RunCommand.Shell("powershell", RunCommand.Kind.POWERSHELL, null);

    @Test
    void bashRunsTheCommandAsOneArgumentInALoginShell() {
        var launch = RunCommand.launch(BASH, "  echo \"a b\" && ls | wc -l ", null);
        assertEquals(List.of("/usr/bin/bash", "-lc", "echo \"a b\" && ls | wc -l"), launch.command());
        assertTrue(launch.environment().isEmpty());
    }

    @Test
    void anEmptyCommandJustStartsTheShell() {
        assertEquals(List.of("/usr/bin/bash"), RunCommand.launch(BASH, "  ", null).command());
        assertEquals(List.of("/usr/bin/fish", "-c", "ls"), RunCommand.launch(FISH, "ls", null).command());
    }

    @Test
    void cmdGetsItsLineThroughTheEnvironmentSoTheInteropLayerCannotRequoteIt() {
        var launch = RunCommand.launch(CMD, "echo \"a b\" & dir", "\\\\wsl.localhost\\Fedora\\home\\me");
        assertEquals(List.of("cmd.exe", "/d", "/c", "%TFM_LINE%"), launch.command());
        assertEquals("pushd \"\\\\wsl.localhost\\Fedora\\home\\me\" && echo \"a b\" & dir", launch.environment().get("TFM_LINE"));
        assertTrue(launch.environment().get("WSLENV").endsWith("TFM_LINE"));
    }

    @Test
    void cmdWithoutACommandStaysOpenInTheFolder() {
        var launch = RunCommand.launch(CMD, "", "C:\\work");
        assertEquals(List.of("cmd.exe", "/d", "/k", "%TFM_LINE%"), launch.command());
        assertEquals("pushd \"C:\\work\"", launch.environment().get("TFM_LINE"));
    }

    @Test
    void powershellReceivesTheCommandBase64EncodedAsUtf16() {
        var launch = RunCommand.launch(PS, "Get-ChildItem 'a b'; \"x\"", null);
        assertEquals("powershell.exe", launch.command().get(0));
        assertEquals("-EncodedCommand", launch.command().get(1));
        assertEquals("Get-ChildItem 'a b'; \"x\"",
                new String(Base64.getDecoder().decode(launch.command().get(2)), StandardCharsets.UTF_16LE));
    }

    @Test
    void customShellsGoThroughSh() {
        var custom = new RunCommand.Shell("x", RunCommand.Kind.CUSTOM, "xterm -e bash -c \"{cmd}\"");
        assertEquals(List.of("sh", "-c", "xterm -e bash -c \"say \\\"hi\\\"\""),
                RunCommand.launch(custom, "say \"hi\"", null).command());
    }
}
