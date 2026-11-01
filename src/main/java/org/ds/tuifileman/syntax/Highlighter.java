package org.ds.tuifileman.syntax;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.swing.text.Segment;
import org.ds.tuifileman.ui.TextStyle;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Colours source text line by line. The scanner keeps nothing between lines except one integer, the state at the
 * end of a line (inside a block comment, inside a text block ...); the next line is scanned with it. The same engine
 * serves a whole-file pass (the viewer) and incremental rescans after an edit (the editor).
 */
public final class Highlighter {
    /** Longer lines are left uncoloured: a minified file would be slow to scan and unreadable anyway. */
    static final int MAX_LINE = 10_000;
    /** The state at the start of a document. */
    public static final int START = TokenTypes.NULL;

    private final TokenMaker maker;

    public Highlighter(Language language) {
        this.maker = language.newTokenMaker();
    }

    /**
     * Styles one line (no line terminator): fills {@code styles[0 .. text.length())} with {@link TextStyle} bytes
     * and returns the state at the end of the line, to be passed as {@code startState} of the next one.
     */
    public int line(String text, int startState, byte[] styles) {
        int length = text.length();
        Arrays.fill(styles, 0, length, (byte) 0);
        if (length > MAX_LINE) {
            return startState;
        }
        try {
            Segment segment = new Segment(text.toCharArray(), 0, length);
            int state = startState;
            for (Token token = maker.getTokenList(segment, startState, 0); token != null; token = token.getNextToken()) {
                // The last token, painted or not, carries the state the next line starts in.
                state = token.getType();
                if (token.isPaintable() && token.length() > 0) {
                    int from = Math.max(0, token.getOffset());
                    int to = Math.min(length, from + token.length());
                    Arrays.fill(styles, from, to, (byte) roleOf(token.getType()));
                }
            }
            return state;
        } catch (RuntimeException e) {
            // A scanner that chokes on odd input must not take the viewer down: this line stays plain.
            Arrays.fill(styles, 0, length, (byte) 0);
            return startState;
        }
    }

    /**
     * Styles a whole document: one {@link TextStyle} byte per byte of {@code data}, which is text in {@code charset}
     * starting at byte {@code start} (after a BOM). Returns null when the bytes cannot be mapped to characters one
     * to one: a multi-byte charset other than UTF-8, or bytes that are not valid in the charset.
     */
    public byte[] document(byte[] data, int start, Charset charset) {
        boolean utf8 = charset.equals(StandardCharsets.UTF_8);
        if (!utf8 && charset.newEncoder().maxBytesPerChar() != 1.0f) {
            return null;
        }
        String all = new String(data, start, data.length - start, charset);
        long expected = data.length - start;
        long actual = utf8 ? all.getBytes(StandardCharsets.UTF_8).length : all.length();
        if (actual != expected) {
            return null;
        }
        byte[] out = new byte[data.length];
        byte[] lineStyles = new byte[256];
        int state = START;
        int bytePos = start;
        int pos = 0;
        while (pos < all.length()) {
            int newline = all.indexOf('\n', pos);
            int end = newline < 0 ? all.length() : newline;
            int textEnd = end > pos && all.charAt(end - 1) == '\r' ? end - 1 : end;
            String line = all.substring(pos, textEnd);
            if (lineStyles.length < line.length()) {
                lineStyles = new byte[Math.max(line.length(), lineStyles.length * 2)];
            }
            state = line(line, state, lineStyles);
            for (int i = 0; i < line.length(); i++) {
                int n = utf8 ? TextStyle.utf8Length(line, i) : 1;
                if (lineStyles[i] != 0) {
                    Arrays.fill(out, bytePos, bytePos + n, lineStyles[i]);
                }
                bytePos += n;
            }
            // The \r and \n are not coloured: one byte each.
            bytePos += end - textEnd + (newline < 0 ? 0 : 1);
            pos = newline < 0 ? all.length() : newline + 1;
        }
        return out;
    }

    /** The colour role of a RSyntaxTextArea token type. */
    static int roleOf(int type) {
        return switch (type) {
            case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION,
                    TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT -> TextStyle.COMMENT;
            case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2 -> TextStyle.KEYWORD;
            case TokenTypes.DATA_TYPE, TokenTypes.FUNCTION, TokenTypes.MARKUP_TAG_ATTRIBUTE -> TextStyle.TYPE;
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE,
                    TokenTypes.REGEX, TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE, TokenTypes.MARKUP_CDATA -> TextStyle.STRING;
            case TokenTypes.LITERAL_BOOLEAN, TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
                    TokenTypes.LITERAL_NUMBER_HEXADECIMAL -> TextStyle.NUMBER;
            case TokenTypes.ANNOTATION, TokenTypes.PREPROCESSOR, TokenTypes.VARIABLE, TokenTypes.MARKUP_TAG_NAME,
                    TokenTypes.MARKUP_DTD, TokenTypes.MARKUP_PROCESSING_INSTRUCTION,
                    TokenTypes.MARKUP_ENTITY_REFERENCE -> TextStyle.META;
            default -> TextStyle.TEXT;
        };
    }
}
