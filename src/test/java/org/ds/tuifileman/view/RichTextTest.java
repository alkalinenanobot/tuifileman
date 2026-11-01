package org.ds.tuifileman.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.ds.tuifileman.ui.TextStyle;
import org.junit.jupiter.api.Test;

class RichTextTest {

    private static TextOut.Result html(String html) throws IOException {
        return RichText.html(html.getBytes(StandardCharsets.UTF_8));
    }

    /** The style byte of the first character of the whole word {@code word} (or of the literal text when it has no letters). */
    private static byte style(TextOut.Result result, String word) {
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])").matcher(result.text());
        assertTrue(m.find(), "'" + word + "' not in " + result.text());
        return result.styles()[m.start()];
    }

    private static boolean has(byte style, int flag) {
        return (style & flag) != 0;
    }

    private static int role(byte style) {
        return style & TextStyle.ROLE_MASK;
    }

    @Test
    void htmlBlocksListsCodeAndLinks() throws IOException {
        String html = "<html><head><title>Hello</title><style>p{color:red}</style><script>alert(1)</script></head>"
                + "<body><h1>Hello</h1><p>One <b>two</b>   three<br>four</p>"
                + "<ul><li>a<li>b<ul><li>c</ul></ul><pre>x\n  y</pre>"
                + "<p>See <a href=\"http://x.y/\">here</a> and <a href=\"http://z\">http://z</a> &amp; <a href=\"#top\">top</a></p>"
                + "</body></html>";
        assertEquals("""
                Hello
                =====

                One two three
                four

                \u2022 a
                \u2022 b
                  \u2022 c

                    x
                      y

                See here (http://x.y/) and http://z & top
                """, html(html).text());
    }

    @Test
    void htmlTitleIsShownWhenNoHeadingRepeatsIt() throws IOException {
        assertEquals("Page\n====\n\nbody text\n", html("<title>Page</title><div>body   text</div>").text());
    }

    @Test
    void htmlOrderedListsContinueFromTheStartAttribute() throws IOException {
        assertEquals("3. x\n4. y\n", html("<ol start=\"3\"><li>x</li><li>y</li></ol>").text());
    }

    @Test
    void htmlWithoutTextIsEmpty() throws IOException {
        assertTrue(html("<html><head><script>x()</script></head><body> </body></html>").isEmpty());
    }

    @Test
    void htmlTableIsAlignedWithABoldHeaderAndARule() throws IOException {
        String table = "<table><thead><tr><th>Name</th><th align=\"right\">Qty</th></tr></thead>"
                + "<tbody><tr><td>apple</td><td align=\"right\">3</td></tr>"
                + "<tr><td><s>kiwi</s> fruit</td><td>12</td></tr></tbody></table><p>after</p>";
        TextOut.Result r = html(table);
        assertEquals("""
                Name       \u2502 Qty
                \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u253C\u2500\u2500\u2500\u2500
                apple      \u2502   3
                kiwi fruit \u2502  12

                after
                """, r.text());
        assertTrue(has(style(r, "Name"), TextStyle.BOLD));
        assertTrue(has(style(r, "kiwi"), TextStyle.STRIKE));
        assertTrue(!has(style(r, "fruit"), TextStyle.STRIKE));
        assertEquals(TextStyle.MUTED, role(r.styles()[r.text().indexOf('\u2502')]));
    }

    @Test
    void htmlTableWithoutHeaderCellsHasNoRuleAndColspanPads() throws IOException {
        assertEquals("a    \u2502 b \u2502 c\nlong\n",
                html("<table><tr><td>a</td><td>b</td><td>c</td></tr><tr><td colspan=\"3\">long</td></tr></table>")
                        .text());
    }

    @Test
    void htmlInlineStyles() throws IOException {
        TextOut.Result r = html("<p><b>bold</b> <i>it</i> <u>und</u> <del>gone</del> <code>mono</code> "
                + "<a href=\"http://x\">link</a> <h3>sub</h3>");
        assertTrue(has(style(r, "bold"), TextStyle.BOLD));
        assertTrue(has(style(r, "it"), TextStyle.ITALIC));
        assertTrue(has(style(r, "und"), TextStyle.UNDERLINE));
        assertTrue(has(style(r, "gone"), TextStyle.STRIKE));
        assertEquals(TextStyle.CODE, role(style(r, "mono")));
        byte link = style(r, "link");
        assertEquals(TextStyle.LINK, role(link));
        assertTrue(has(link, TextStyle.UNDERLINE));
        byte url = r.styles()[r.text().indexOf("(http")];
        assertEquals(TextStyle.MUTED, role(url));
        assertTrue(!has(url, TextStyle.UNDERLINE));
        assertEquals(TextStyle.HEADING, role(r.styles()[r.text().indexOf("###")]));
    }

    @Test
    void markdownStructure() {
        String md = "# Title\n\nSome *text* with `code` and [link](http://a.b).\n\n- one\n- two\n  1. x\n  2. y\n\n"
                + "> quote\n\n```\ncode line\n```\n";
        assertEquals("""
                Title
                =====

                Some text with code and link (http://a.b).

                \u2022 one
                \u2022 two
                  1. x
                  2. y

                > quote

                    code line
                """, RichText.markdown(md).text());
    }

    @Test
    void markdownHeadingLevelsAndEntities() {
        assertEquals("Sub\n---\n\n### Third\n\nA & B \u00e9\n",
                RichText.markdown("## Sub\n\n### Third\n\nA &amp; B &eacute;\n").text());
    }

    @Test
    void markdownTableHonoursAlignmentAndStylesTheCells() {
        String md = "| Name | Qty | Note |\n|:-----|----:|:----:|\n| apple | 3 | ok |\n| kiwi | 12 | ~~old~~ |\n\nafter\n";
        TextOut.Result r = RichText.markdown(md);
        assertEquals("""
                Name  \u2502 Qty \u2502 Note
                \u2500\u2500\u2500\u2500\u2500\u2500\u253C\u2500\u2500\u2500\u2500\u2500\u253C\u2500\u2500\u2500\u2500\u2500
                apple \u2502   3 \u2502  ok
                kiwi  \u2502  12 \u2502 old

                after
                """, r.text());
        assertTrue(has(style(r, "Name"), TextStyle.BOLD));
        assertTrue(has(style(r, "old"), TextStyle.STRIKE));
        assertTrue(!has(style(r, "apple"), TextStyle.BOLD));
    }

    @Test
    void markdownInlineStyles() {
        TextOut.Result r = RichText.markdown("~~gone~~ **bold** *it* `c` [l](http://x) ![pic](a.png)\n\n- [x] done\n- [ ] todo\n");
        assertEquals("gone bold it c l (http://x) [image: pic]\n\n\u2022 [x] done\n\u2022 [ ] todo\n", r.text());
        assertTrue(has(style(r, "gone"), TextStyle.STRIKE));
        assertTrue(has(style(r, "bold"), TextStyle.BOLD));
        assertTrue(has(style(r, "it"), TextStyle.ITALIC));
        assertEquals(TextStyle.CODE, role(style(r, "c")));
        byte link = style(r, "l");
        assertEquals(TextStyle.LINK, role(link));
        assertTrue(has(link, TextStyle.UNDERLINE));
        assertEquals(TextStyle.SUCCESS, role(r.styles()[r.text().indexOf("[x]")]));
        assertEquals(TextStyle.MUTED, role(r.styles()[r.text().indexOf("[ ]")]));
    }

    @Test
    void aSpaceInsideAStruckPhraseIsStruckButNotTheOnesAround() {
        TextOut.Result r = RichText.markdown("a ~~b c~~ d\n");
        assertEquals("a b c d\n", r.text());
        assertEquals(0, r.styles()[1]);
        assertTrue(has(r.styles()[2], TextStyle.STRIKE));
        assertTrue(has(r.styles()[3], TextStyle.STRIKE), "the space between b and c");
        assertTrue(has(r.styles()[4], TextStyle.STRIKE));
        assertEquals(0, r.styles()[5]);
    }

    @Test
    void markdownFrontMatterIsShownMuted() {
        TextOut.Result r = RichText.markdown("---\ntitle: Doc\n---\n\nBody\n");
        assertEquals("title: Doc\n\nBody\n", r.text());
        assertEquals(TextStyle.MUTED, role(r.styles()[0]));
        assertEquals(0, role(style(r, "Body")));
    }

    @Test
    void byteStylesFollowTheUtf8LengthOfEachCharacter() {
        TextOut.Result r = RichText.markdown("**\u00e9\u20ac** x\n");
        byte[] bytes = r.byteStyles();
        assertEquals("\u00e9\u20ac x\n".getBytes(StandardCharsets.UTF_8).length, bytes.length);
        for (int i = 0; i < 5; i++) {
            assertTrue(has(bytes[i], TextStyle.BOLD), "byte " + i);
        }
        assertEquals(0, bytes[5]);
    }

    @Test
    void kindFollowsTheExtension() {
        assertEquals(RichText.Kind.HTML, RichText.kindOf(Path.of("a/page.HTM")));
        assertEquals(RichText.Kind.MARKDOWN, RichText.kindOf(Path.of("README.md")));
        assertNull(RichText.kindOf(Path.of("notes.txt")));
        assertNull(RichText.kindOf(Path.of("Makefile")));
    }
}
