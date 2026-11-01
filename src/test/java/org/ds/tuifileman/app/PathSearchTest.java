package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSearchTest {

    private final List<Path> items = List.of(
            Path.of("/work/alpha"), Path.of("/data/beta-alpha"), Path.of("/x/alpine"), Path.of("/y/gamma"));

    @Test
    void namePrefixBeatsEarlierSubstringMatch() {
        List<Path> list = List.of(Path.of("/alpha/zeta"), Path.of("/other/alpine"));
        assertEquals(1, new PathSearch().append('a', list), "name prefix wins over an earlier path containing 'a'");
    }

    @Test
    void fallsBackToContainsAndReportsNoMatch() {
        PathSearch search = new PathSearch();
        assertEquals(1, search.append('b', items));
        assertEquals(-1, search.append('z', items));
        assertFalse(search.matched());
        assertEquals(1, search.backspace(items));
        assertTrue(search.matched());
    }

    @Test
    void stepWalksMatchesAndStopsAtEnds() {
        PathSearch search = new PathSearch();
        search.append('a', items);
        search.append('l', items);
        assertEquals(2, search.step(1, 0, items));
        assertEquals(-1, search.step(1, 2, items));
        assertEquals(0, search.step(-1, 2, items));
    }
}
