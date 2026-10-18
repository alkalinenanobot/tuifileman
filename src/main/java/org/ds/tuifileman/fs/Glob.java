package org.ds.tuifileman.fs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * File name wildcards: {@code *} is any text, {@code ?} any one character. Only those two count, because they
 * cannot occur in a Windows file name whereas {@code [} and {@code ]} can, so {@code file[1].txt} stays a plain
 * name. Shared by the command line expansion and the file search.
 */
public final class Glob {
    private Glob() { }

    public static boolean has(String text) {
        return text.indexOf('*') >= 0 || text.indexOf('?') >= 0;
    }

    /** A regular expression that matches a whole file name. */
    public static Pattern compile(String glob, boolean ignoreCase) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        // DOTALL: a Linux file name may contain a line break, and * and ? match any character.
        return Pattern.compile(regex.toString(),
                Pattern.DOTALL | (ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0));
    }

    /** The patterns of a list such as {@code *.txt, *.ini; readme}: separated by a comma or semicolon, blanks trimmed. */
    public static List<String> split(String list) {
        List<String> parts = new ArrayList<>();
        for (String part : list.split("[,;]")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }
}
