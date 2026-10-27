package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RunCommandQuoteTest {
    @Test
    void plainNamesStayAsTheyAre() {
        assertEquals("report-2024_v1.2.txt", RunCommand.quoteName("report-2024_v1.2.txt"));
        assertEquals("árvíztűrő.txt", RunCommand.quoteName("árvíztűrő.txt"));
    }

    @Test
    void spacesAndShellCharactersGetDoubleQuotes() {
        assertEquals("\"my file.txt\"", RunCommand.quoteName("my file.txt"));
        assertEquals("\"a&b.bat\"", RunCommand.quoteName("a&b.bat"));
        assertEquals("\"x(1).exe\"", RunCommand.quoteName("x(1).exe"));
    }

    @Test
    void aNameWithADoubleQuoteIsLeftAlone() {
        assertEquals("we\"ird name", RunCommand.quoteName("we\"ird name"));
    }
}
