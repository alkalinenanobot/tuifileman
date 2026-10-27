package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsArgsTest {
    @Test
    void splitsLikeCommandLineToArgv() {
        assertEquals(List.of("C:\\tools\\tuifileman.exe", "find", "-T", "a b", "C:\\my dir\\", "end"),
                WindowsArgs.split("C:\\tools\\tuifileman.exe  find -T \"a b\" \"C:\\my dir\\\\\" end"));
        assertEquals(List.of("say \"hi\"", "x\\y", ""), WindowsArgs.split("\"say \\\"hi\\\"\" x\\y \"\""));
    }

    @Test
    void aLostCharacterComesBackFromTheRealCommandLine() {
        String[] lost = {"find", "-T", "c?ur", "D:\\data"};
        String line = "\"C:\\Program Files\\Java\\bin\\java.exe\" -jar t.jar find -T c\u0153ur D:\\data";

        assertArrayEquals(new String[] {"find", "-T", "c\u0153ur", "D:\\data"}, WindowsArgs.restore(lost, line));
    }

    @Test
    void aRealQuestionMarkWildcardStaysWhileAnotherArgumentIsRestored() {
        String[] lost = {"find", "-N", "a?.txt", "-T", "??????"};
        String line = "tuifileman find -N a?.txt -T \u043f\u0440\u0438\u0432\u0435\u0442";

        assertArrayEquals(new String[] {"find", "-N", "a?.txt", "-T", "\u043f\u0440\u0438\u0432\u0435\u0442"},
                WindowsArgs.restore(lost, line));
    }

    @Test
    void anArgumentTheRuntimeExpandedAsAWildcardIsRestoredToo() {
        // six question marks became every six letter name of the current folder
        String[] expanded = {"find", "-T", ".cache", "native", "target", "D:\\data"};
        String line = "tuifileman.exe find -T \u043f\u0440\u0438\u0432\u0435\u0442 D:\\data";

        assertArrayEquals(new String[] {"find", "-T", "\u043f\u0440\u0438\u0432\u0435\u0442", "D:\\data"},
                WindowsArgs.restore(expanded, line));
    }

    @Test
    void aCommandLineWithoutAnyNonAsciiCharacterLeavesTheArgumentsAlone() {
        String[] args = {"find", "-N", "a.txt", "b.txt"}; // what the runtime made of "*.txt"

        assertArrayEquals(args, WindowsArgs.restore(args, "tuifileman find -N *.txt"));
    }

    @Test
    void aCommandLineThatDoesNotFitTheArgumentsIsNotTrusted() {
        String[] args = {"find", "-T", "c?ur"};

        assertArrayEquals(args, WindowsArgs.restore(args, "tuifileman copy a b"));
        assertArrayEquals(args, WindowsArgs.restore(args, "tuifileman -T c?ur"));
    }
}
