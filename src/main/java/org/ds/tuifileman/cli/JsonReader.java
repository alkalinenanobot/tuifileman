package org.ds.tuifileman.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON parser for the plans the CLI reads. Objects become {@code Map}, arrays {@code List}, numbers
 * {@code Double}; no reflection, so it works in the native image. Bad input throws
 * {@link IllegalArgumentException} with the column.
 */
final class JsonReader {
    private static final int MAX_DEPTH = 64;

    private final String text;
    private int pos;

    private JsonReader(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        reader.skipSpace();
        Object value = reader.value(0);
        reader.skipSpace();
        if (reader.pos != text.length()) {
            throw reader.error("unexpected text after the value");
        }
        return value;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " (column " + (pos + 1) + ")");
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected character");
        }
        pos += word.length();
        return value;
    }

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.valueOf(text.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("unexpected character");
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (pos < text.length() && text.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw error("expected a quoted name");
            }
            String name = string();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(name, value(depth + 1));
            skipSpace();
            if (pos < text.length() && text.charAt(pos) == ',') {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (pos < text.length() && text.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value(depth + 1));
            skipSpace();
            if (pos < text.length() && text.charAt(pos) == ',') {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private void expect(char c) {
        if (pos >= text.length() || text.charAt(pos) != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char escape = text.charAt(pos++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("bad \\u escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error("bad escape");
            }
        }
        throw error("unterminated string");
    }
}
