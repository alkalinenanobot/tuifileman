package org.ds.tuifileman.syntax;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.Random;
import org.ds.tuifileman.edit.TextBuffer;
import org.ds.tuifileman.edit.TextBuffer.Pos;
import org.ds.tuifileman.ui.TextStyle;
import org.junit.jupiter.api.Test;

class DocumentStylesTest {
    private static final Language JAVA = Language.forFile(Path.of("A.java"));
    private static final String[] PIECES = {"/*", "*/", "\"", "\"\"\"", "//", "'", "\n", "\n", "x ", "int ", "\\", " ",
            "1", "/**", "@A "};

    private static DocumentStyles.Lines linesOf(TextBuffer buffer) {
        return new DocumentStyles.Lines() {
            @Override
            public int count() {
                return buffer.lineCount();
            }

            @Override
            public String line(int index) {
                return buffer.line(index);
            }
        };
    }

    private static DocumentStyles track(TextBuffer buffer, int eager) {
        DocumentStyles styles = new DocumentStyles(JAVA, linesOf(buffer), eager);
        buffer.setListener(styles::edited);
        return styles;
    }

    private static void assertSameAsAFreshScan(TextBuffer buffer, DocumentStyles styles, String context) {
        DocumentStyles fresh = new DocumentStyles(JAVA, linesOf(buffer));
        for (int line = 0; line < buffer.lineCount(); line++) {
            assertArrayEquals(fresh.styles(line), styles.styles(line), context + ", line " + line + ": " + buffer.line(line));
        }
    }

    private static String randomText(Random random) {
        StringBuilder sb = new StringBuilder();
        for (int n = random.nextInt(4); n > 0; n--) {
            sb.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return sb.toString();
    }

    private static Pos randomPos(Random random, TextBuffer buffer, int minLine) {
        int line = Math.min(buffer.lineCount() - 1, minLine + random.nextInt(4));
        return new Pos(line, random.nextInt(buffer.line(line).length() + 1));
    }

    /** Random edits, undo and redo: the incrementally kept colours must always equal a scan from the first line. */
    @Test
    void randomEditsGiveTheColoursOfAFreshScan() {
        for (int eager : new int[] {1, 2, 5, DocumentStyles.DEFAULT_EAGER_LINES}) {
            for (long seed = 1; seed <= 12; seed++) {
                Random random = new Random(seed);
                StringBuilder initial = new StringBuilder();
                for (int i = 0; i < 40; i++) {
                    initial.append(randomText(random)).append('\n');
                }
                TextBuffer buffer = TextBuffer.of(initial.toString());
                DocumentStyles styles = track(buffer, eager);
                for (int step = 0; step < 150; step++) {
                    String context = "eager " + eager + ", seed " + seed + ", step " + step;
                    int kind = random.nextInt(10);
                    if (kind == 0) {
                        buffer.undo();
                    } else if (kind == 1) {
                        buffer.redo();
                    } else {
                        Pos a = randomPos(random, buffer, random.nextInt(buffer.lineCount()));
                        Pos b = kind < 5 ? a : randomPos(random, buffer, a.line());
                        buffer.replace(a, b.compareTo(a) < 0 ? a : b, randomText(random));
                    }
                    // Look at some lines (as a screen does) between edits, then at all of them now and then.
                    styles.styles(random.nextInt(buffer.lineCount()));
                    if (step % 5 == 0) {
                        assertSameAsAFreshScan(buffer, styles, context);
                    }
                }
                assertSameAsAFreshScan(buffer, styles, "eager " + eager + ", seed " + seed + ", end");
            }
        }
    }

    @Test
    void openingAndClosingABlockCommentRecolourTheLinesBetween() {
        TextBuffer buffer = TextBuffer.of("int a;\nint b;\nint c;\n");
        DocumentStyles styles = track(buffer, DocumentStyles.DEFAULT_EAGER_LINES);
        assertEquals(TextStyle.TYPE, styles.styles(2)[0] & TextStyle.ROLE_MASK);
        buffer.replace(new Pos(0, 0), new Pos(0, 0), "/* ");
        for (int line = 0; line < 3; line++) {
            assertEquals(TextStyle.COMMENT, styles.styles(line)[0] & TextStyle.ROLE_MASK, "line " + line);
        }
        buffer.replace(new Pos(1, 6), new Pos(1, 6), " */");
        assertEquals(TextStyle.COMMENT, styles.styles(1)[0] & TextStyle.ROLE_MASK);
        assertEquals(TextStyle.TYPE, styles.styles(2)[0] & TextStyle.ROLE_MASK, "after the comment is closed");
        buffer.undo();
        assertEquals(TextStyle.COMMENT, styles.styles(2)[0] & TextStyle.ROLE_MASK, "undo reopens it");
    }

    @Test
    void anEditFarFromTheScreenIsNotScannedUntilItIsDrawn() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("int v").append(i).append(";\n");
        }
        TextBuffer buffer = TextBuffer.of(sb.toString());
        DocumentStyles styles = track(buffer, 100);
        styles.styles(19_999);
        // Opening a comment on line 0 changes every line below; only 100 are followed at once, the rest on demand.
        buffer.replace(new Pos(0, 0), new Pos(0, 0), "/* ");
        assertNotEquals(TextStyle.TYPE, styles.styles(19_999)[0] & TextStyle.ROLE_MASK);
        assertEquals(TextStyle.COMMENT, styles.styles(19_999)[0] & TextStyle.ROLE_MASK);
        buffer.undo();
        assertEquals(TextStyle.TYPE, styles.styles(19_999)[0] & TextStyle.ROLE_MASK);
        assertSameAsAFreshScan(buffer, styles, "after undo");
    }

    @Test
    void ordinaryTypingFollowsOneLineOnly() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            sb.append("int v").append(i).append(";\n");
        }
        TextBuffer buffer = TextBuffer.of(sb.toString());
        int[] scanned = {0};
        DocumentStyles.Lines counting = new DocumentStyles.Lines() {
            @Override
            public int count() {
                return buffer.lineCount();
            }

            @Override
            public String line(int index) {
                scanned[0]++;
                return buffer.line(index);
            }
        };
        DocumentStyles styles = new DocumentStyles(JAVA, counting);
        buffer.setListener(styles::edited);
        styles.styles(4_999);
        scanned[0] = 0;
        buffer.replace(new Pos(10, 3), new Pos(10, 3), "x");
        assertEquals(1, scanned[0], "only the edited line is scanned: the end state is the same as before");
    }

    @Test
    void anEditBelowTheKnownPartCostsNothing() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            sb.append("int v").append(i).append(";\n");
        }
        TextBuffer buffer = TextBuffer.of(sb.toString());
        int[] scanned = {0};
        DocumentStyles styles = new DocumentStyles(JAVA, new DocumentStyles.Lines() {
            @Override
            public int count() {
                return buffer.lineCount();
            }

            @Override
            public String line(int index) {
                scanned[0]++;
                return buffer.line(index);
            }
        });
        buffer.setListener(styles::edited);
        styles.styles(5);
        scanned[0] = 0;
        buffer.replace(new Pos(900, 0), new Pos(900, 0), "/* ");
        assertEquals(0, scanned[0]);
        assertEquals(TextStyle.COMMENT, styles.styles(999)[0] & TextStyle.ROLE_MASK);
    }
}
