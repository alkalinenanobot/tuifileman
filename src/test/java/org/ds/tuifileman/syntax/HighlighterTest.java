package org.ds.tuifileman.syntax;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.ds.tuifileman.ui.TextStyle;
import org.junit.jupiter.api.Test;

class HighlighterTest {

    private static Highlighter java() {
        return new Highlighter(Language.forFile(Path.of("A.java")));
    }

    /** The colour role of the first character of {@code needle} in {@code line}. */
    private static int roleAt(String line, byte[] styles, String needle) {
        int at = line.indexOf(needle);
        assertNotEquals(-1, at, needle + " not in " + line);
        return styles[at] & TextStyle.ROLE_MASK;
    }

    /** Scans the lines in order, carrying the state; returns the styles of every line. */
    private static byte[][] scan(Highlighter h, String... lines) {
        byte[][] out = new byte[lines.length][];
        int state = Highlighter.START;
        for (int i = 0; i < lines.length; i++) {
            out[i] = new byte[lines[i].length()];
            state = h.line(lines[i], state, out[i]);
        }
        return out;
    }

    @Test
    void anEscapedQuoteAndCommentMarkersInsideAStringStayInTheString() {
        String line = "String s = \"a \\\" b // not a comment\"; // real comment";
        byte[] styles = scan(java(), line)[0];
        assertEquals(TextStyle.STRING, roleAt(line, styles, "\"a"));
        assertEquals(TextStyle.STRING, roleAt(line, styles, "not a comment"));
        assertEquals(TextStyle.STRING, roleAt(line, styles, "comment\";"));
        assertEquals(TextStyle.COMMENT, roleAt(line, styles, "// real"));
        assertEquals(TextStyle.COMMENT, roleAt(line, styles, "real comment"));
    }

    @Test
    void aQuoteInACharLiteralDoesNotOpenAString() {
        String line = "char c = '\"'; int x = 1; // done";
        byte[] styles = scan(java(), line)[0];
        assertEquals(TextStyle.STRING, roleAt(line, styles, "'\"'"));
        assertEquals(TextStyle.NUMBER, roleAt(line, styles, "1;"));
        assertEquals(TextStyle.COMMENT, roleAt(line, styles, "// done"));
    }

    @Test
    void aTextBlockAndABlockCommentCarryTheirStateAcrossLines() {
        String[] lines = {
                "String t = \"\"\"",
                "  text \" block // not a comment",
                "  \"\"\"; /* multi",
                "line */ int x = 1;",
                "int y;"};
        byte[][] s = scan(java(), lines);
        assertEquals(TextStyle.STRING, roleAt(lines[1], s[1], "text"));
        assertEquals(TextStyle.STRING, roleAt(lines[1], s[1], "// not"));
        assertEquals(TextStyle.STRING, roleAt(lines[2], s[2], "\"\"\""));
        assertEquals(TextStyle.COMMENT, roleAt(lines[2], s[2], "/* multi"));
        assertEquals(TextStyle.COMMENT, roleAt(lines[3], s[3], "line"));
        assertEquals(TextStyle.TYPE, roleAt(lines[3], s[3], "int"));
        assertEquals(TextStyle.TYPE, roleAt(lines[4], s[4], "int"), "colouring is back to normal after the comment");
    }

    @Test
    void anEmptyLineInsideABlockKeepsTheState() {
        String[] lines = {"/* open", "", "still comment */ int z;"};
        byte[][] s = scan(java(), lines);
        assertEquals(TextStyle.COMMENT, roleAt(lines[2], s[2], "still"));
        assertEquals(TextStyle.TYPE, roleAt(lines[2], s[2], "int"));
    }

    @Test
    void theEndStateIsStartForAPlainLineAndDiffersInsideABlock() {
        Highlighter h = java();
        byte[] styles = new byte[40];
        assertEquals(Highlighter.START, h.line("int a = 1;", Highlighter.START, styles));
        int inComment = h.line("/* open", Highlighter.START, styles);
        assertNotEquals(Highlighter.START, inComment);
        assertEquals(Highlighter.START, h.line("close */", inComment, styles));
    }

    @Test
    void pythonTripleQuotesSpanLinesAndKeepEscapedQuotes() {
        Highlighter h = new Highlighter(Language.forFile(Path.of("a.py")));
        String[] lines = {"x = '''doc", "string ''' + \"q\\\"uote\"  # comment", "y = 2"};
        byte[][] s = scan(h, lines);
        assertEquals(TextStyle.STRING, roleAt(lines[1], s[1], "string"));
        assertEquals(TextStyle.STRING, roleAt(lines[1], s[1], "q\\\"uote"));
        assertEquals(TextStyle.COMMENT, roleAt(lines[1], s[1], "# comment"));
        assertEquals(TextStyle.NUMBER, roleAt(lines[2], s[2], "2"));
    }

    @Test
    void javaScriptTemplateLiteralsAndRegexLiterals() {
        Highlighter h = new Highlighter(Language.forFile(Path.of("a.js")));
        String line = "var r = /ab+\\/c/gi; var s = `tpl ${a + `in${b}`} end`; // c";
        byte[] styles = scan(h, line)[0];
        assertEquals(TextStyle.STRING, roleAt(line, styles, "/ab+"));
        assertEquals(TextStyle.STRING, roleAt(line, styles, "`tpl"));
        assertEquals(TextStyle.STRING, roleAt(line, styles, " end`"));
        assertEquals(TextStyle.COMMENT, roleAt(line, styles, "// c"));
    }

    @Test
    void aLongLineIsLeftPlainAndKeepsTheState() {
        Highlighter h = java();
        String line = "int a; ".repeat(Highlighter.MAX_LINE / 5);
        byte[] styles = new byte[line.length()];
        styles[0] = 99;
        assertEquals(Highlighter.START, h.line(line, Highlighter.START, styles));
        assertEquals(0, styles[0]);
    }

    @Test
    void documentStylesFollowTheBytesOfMultiByteCharacters() {
        String text = "// \u00e9\u20ac\r\nint x;\n";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        byte[] styles = java().document(data, 0, StandardCharsets.UTF_8);
        assertNotNull(styles);
        assertEquals(data.length, styles.length);
        int eol = new String(data, StandardCharsets.UTF_8).indexOf('\r');
        int bytesBeforeEol = text.substring(0, eol).getBytes(StandardCharsets.UTF_8).length;
        for (int i = 0; i < bytesBeforeEol; i++) {
            assertEquals(TextStyle.COMMENT, styles[i] & TextStyle.ROLE_MASK, "byte " + i);
        }
        assertEquals(0, styles[bytesBeforeEol], "\\r");
        assertEquals(0, styles[bytesBeforeEol + 1], "\\n");
        assertEquals(TextStyle.TYPE, styles[bytesBeforeEol + 2] & TextStyle.ROLE_MASK, "int");
    }

    @Test
    void documentSkipsABomAndWorksInSingleByteCharsets() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '/', '/', ' ', 'x'};
        byte[] styles = java().document(bom, 3, StandardCharsets.UTF_8);
        assertEquals(0, styles[0]);
        assertEquals(TextStyle.COMMENT, styles[3] & TextStyle.ROLE_MASK);
        assertEquals(TextStyle.COMMENT, styles[6] & TextStyle.ROLE_MASK);

        Charset latin = Charset.forName("windows-1252");
        byte[] data = "int a; // caf\u00e9\n".getBytes(latin);
        byte[] colours = java().document(data, 0, latin);
        assertNotNull(colours);
        assertEquals(TextStyle.COMMENT, colours[data.length - 2] & TextStyle.ROLE_MASK, "the accented letter is one byte");
    }

    @Test
    void documentRefusesBytesThatAreNotValidInTheCharset() {
        byte[] bad = {'/', '/', ' ', (byte) 0xE9, '\n'};
        assertNull(java().document(bad, 0, StandardCharsets.UTF_8));
        assertNull(java().document("// x".getBytes(StandardCharsets.UTF_16LE), 0, StandardCharsets.UTF_16LE));
    }

    @Test
    void documentWithoutALastNewlineAndBlankLines() {
        byte[] data = "\n\nint a".getBytes(StandardCharsets.UTF_8);
        byte[] styles = java().document(data, 0, StandardCharsets.UTF_8);
        assertEquals(TextStyle.TYPE, styles[2] & TextStyle.ROLE_MASK);
        assertArrayEquals(new byte[] {0, 0}, new byte[] {styles[0], styles[1]});
    }

    @Test
    void languagesComeFromTheFileName() {
        assertEquals("Java", Language.forFile(Path.of("src/Main.JAVA")).name());
        assertEquals("C++", Language.forFile(Path.of("a.hpp")).name());
        assertEquals("Makefile", Language.forFile(Path.of("GNUmakefile")).name());
        assertEquals("Dockerfile", Language.forFile(Path.of("Dockerfile")).name());
        assertNull(Language.forFile(Path.of("notes.txt")));
        assertNull(Language.forFile(Path.of("README")));
    }
}
