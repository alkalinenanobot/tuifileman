package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgsTest {
    private static Args args() {
        return new Args().option("verbose", 'v', false).option("quiet", 'q', false)
                .option("format", 'f', true).option("exclude", 'x', true).option("long-only", (char) 0, false);
    }

    private static Args parse(String... arguments) throws Exception {
        return args().parse(List.of(arguments));
    }

    @Test
    void longOptionsTakeSeparateOrInlineValuesAndRepeat() throws Exception {
        assertEquals("json", parse("--format", "json").get("format", "text"));
        assertEquals("json", parse("--format=json").get("format", "text"));
        assertEquals("a=b", parse("--format=a=b").get("format", "text"), "only the first = splits");
        assertEquals("", parse("--format=").get("format", "text"));
        assertTrue(parse("--long-only").has("long-only"));

        Args repeated = parse("-x", "a", "--exclude", "b", "--exclude=c", "-f", "text", "-f", "json");
        assertEquals(List.of("a", "b", "c"), repeated.all("exclude"));
        assertEquals("json", repeated.get("format", ""), "the last value wins");
        assertEquals(List.of(), repeated.all("missing"));
        assertEquals("fallback", repeated.get("missing", "fallback"));
    }

    @Test
    void shortOptionsCanBeClustered() throws Exception {
        Args cluster = parse("-vq");
        assertTrue(cluster.has("verbose") && cluster.has("quiet"));
        Args clusterWithValue = parse("-vf", "json");
        assertTrue(clusterWithValue.has("verbose"));
        assertEquals("json", clusterWithValue.get("format", ""));
        assertEquals("json", parse("-f", "json").get("format", ""));
    }

    @Test
    void positionalArgumentsKeepTheirOrderAndDoubleDashEndsOptions() throws Exception {
        assertEquals(List.of("one", "two", "three"), parse("one", "-v", "two", "-f", "text", "three").positional());
        assertEquals(List.of("-"), parse("-").positional(), "a single dash is positional");
        Args parsed = parse("-v", "--", "-q", "--format", "x");
        assertTrue(parsed.has("verbose"));
        assertFalse(parsed.has("quiet"));
        assertEquals(List.of("-q", "--format", "x"), parsed.positional());
    }

    @Test
    void malformedOptionsAreUsageErrorsNamingTheOption() {
        String[][] rows = {
            {"--nope", "--nope"}, {"-z", "-z"}, {"-vz", "-z"},
            {"--format", "--format"}, {"-f", "-f"},
            {"--verbose=1", "--verbose"}, {"-fjson", "-f"},
        };
        for (String[] row : rows) {
            Args.UsageException error = assertThrows(Args.UsageException.class, () -> parse(row[0]), row[0]);
            assertTrue(error.getMessage().contains(row[1]), row[0] + " -> " + error.getMessage());
        }
    }
}
