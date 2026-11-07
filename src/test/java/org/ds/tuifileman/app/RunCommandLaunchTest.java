package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunCommandLaunchTest {
    @Test
    void eachShellGetsTheCommandInTheFormItCannotMangle() {
        var bash = new RunCommand.Shell("bash", RunCommand.Kind.UNIX, "/usr/bin/bash");
        var launch = RunCommand.launch(bash, "  echo \"a b\" && ls | wc -l ", null);
        assertEquals(List.of("/usr/bin/bash", "-lc", "echo \"a b\" && ls | wc -l"), launch.command(), "bash: one argument, login shell");
        assertTrue(launch.environment().isEmpty(), "bash: no environment");

        var cmd = new RunCommand.Shell("cmd", RunCommand.Kind.CMD, null);
        launch = RunCommand.launch(cmd, "echo \"a b\" & dir", "\\\\wsl.localhost\\Fedora\\home\\me");
        assertEquals(List.of("cmd.exe", "/d", "/c", "%TFM_LINE%"), launch.command(), "cmd: line comes from the environment");
        assertEquals("pushd \"\\\\wsl.localhost\\Fedora\\home\\me\" && echo \"a b\" & dir", launch.environment().get("TFM_LINE"), "cmd: line");
        assertTrue(launch.environment().get("WSLENV").endsWith("TFM_LINE"), "cmd: variable crosses the interop layer");

        var powershell = new RunCommand.Shell("powershell", RunCommand.Kind.POWERSHELL, null);
        launch = RunCommand.launch(powershell, "Get-ChildItem 'a b'; \"x\"", null);
        assertEquals("-EncodedCommand", launch.command().get(1), "powershell: encoded command");
        assertEquals("Get-ChildItem 'a b'; \"x\"",
                new String(Base64.getDecoder().decode(launch.command().get(2)), StandardCharsets.UTF_16LE), "powershell: UTF-16 base64");
    }
}
