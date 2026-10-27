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
    void longOptionsWithSeparateAndInlineValues() throws Exception {
        assertEquals("json", parse("--format", "json").get("format", "text"));
        assertEquals("json", parse("--format=json").get("format", "text"));
        assertEquals("a=b", parse("--format=a=b").get("format", "text"), "only the first = splits");
        assertEquals("", parse("--format=").get("format", "text"));
    }

    @Test
    void shortOptionsAndClusters() throws Exception {
        Args cluster = parse("-vq");
        assertTrue(cluster.has("verbose") && cluster.has("quiet"));
        Args clusterWithValue = parse("-vf", "json");
        assertTrue(clusterWithValue.has("verbose"));
        assertEquals("json", clusterWithValue.get("format", ""));
        assertEquals("json", parse("-f", "json").get("format", ""));
    }

    @Test
    void positionalArgumentsKeepTheirOrder() throws Exception {
        Args parsed = parse("one", "-v", "two", "-f", "text", "three");
        assertEquals(List.of("one", "two", "three"), parsed.positional());
    }

    @Test
    void aSingleDashIsPositional() throws Exception {
        assertEquals(List.of("-"), parse("-").positional());
    }

    @Test
    void doubleDashEndsOptionParsing() throws Exception {
        Args parsed = parse("-v", "--", "-q", "--format", "x");
        assertTrue(parsed.has("verbose"));
        assertFalse(parsed.has("quiet"));
        assertEquals(List.of("-q", "--format", "x"), parsed.positional());
    }

    @Test
    void repeatedOptionsAreCollectedAndTheLastWins() throws Exception {
        Args parsed = parse("-x", "a", "--exclude", "b", "--exclude=c", "-f", "text", "-f", "json");
        assertEquals(List.of("a", "b", "c"), parsed.all("exclude"));
        assertEquals("json", parsed.get("format", ""));
        assertEquals(List.of(), parsed.all("missing"));
        assertEquals("fallback", parsed.get("missing", "fallback"));
    }

    @Test
    void unknownOptionsAreRejected() {
        assertEquals("unknown option --nope", assertThrows(Args.UsageException.class, () -> parse("--nope")).getMessage());
        assertEquals("unknown option -z", assertThrows(Args.UsageException.class, () -> parse("-z")).getMessage());
        assertEquals("unknown option -z", assertThrows(Args.UsageException.class, () -> parse("-vz")).getMessage());
    }

    @Test
    void missingValuesAreRejected() {
        assertEquals("option --format needs a value",
                assertThrows(Args.UsageException.class, () -> parse("--format")).getMessage());
        assertEquals("option -f needs a value", assertThrows(Args.UsageException.class, () -> parse("-f")).getMessage());
    }

    @Test
    void aFlagWithAValueAndAGluedShortValueAreRejected() {
        assertEquals("option --verbose does not take a value",
                assertThrows(Args.UsageException.class, () -> parse("--verbose=1")).getMessage());
        assertTrue(assertThrows(Args.UsageException.class, () -> parse("-fjson")).getMessage().contains("-f VALUE"));
    }

    @Test
    void optionsWithoutAShortNameWork() throws Exception {
        assertTrue(parse("--long-only").has("long-only"));
    }
}
