package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CsvTest {
    @Test
    void quotesOnlyWhatWouldBreakTheRecord() {
        assertEquals("plain name.txt", Csv.field("plain name.txt"));
        assertEquals("\"a,b\"", Csv.field("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""));
        assertEquals("\"line\nbreak\"", Csv.field("line\nbreak"), "a Linux file name may hold LF");
        assertEquals("\"carriage\rreturn\"", Csv.field("carriage\rreturn"), "and CR");
    }
}
