package org.ds.tuifileman.cli;

/** CSV fields for the CLI's own output (RFC 4180). */
final class Csv {
    private Csv() { }

    /**
     * The field as is, or quoted (inner quotes doubled) when it holds a comma, a quote or a line break: a Linux
     * file name may contain CR or LF, and unquoted it would split one record into several.
     */
    static String field(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                return "\"" + text.replace("\"", "\"\"") + "\"";
            }
        }
        return text;
    }
}
