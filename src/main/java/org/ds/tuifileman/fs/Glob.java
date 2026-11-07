package org.ds.tuifileman.fs;

import java.util.ArrayList;
import java.util.List;

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

    /** A matcher for a whole file name. */
    public static Compiled compile(String glob, boolean ignoreCase) {
        return new Compiled(glob.codePoints().toArray(), ignoreCase);
    }

    /**
     * A compiled wildcard. Matching walks the name once and remembers only the last {@code *}, so the time is
     * at most name length times pattern length however many stars there are (a regular expression with many
     * {@code .*} takes exponential time on a name that does not match). Characters are code points, and case is
     * folded like {@code Pattern.CASE_INSENSITIVE | UNICODE_CASE}.
     */
    public static final class Compiled {
        private final int[] pattern;
        private final boolean ignoreCase;

        private Compiled(int[] pattern, boolean ignoreCase) {
            this.pattern = pattern;
            this.ignoreCase = ignoreCase;
        }

        /** Whether the whole name matches; {@code *} and {@code ?} also match a line break. */
        public boolean matches(CharSequence name) {
            int length = name.length();
            int p = 0;
            int i = 0;
            int star = -1;
            int mark = 0;
            while (i < length) {
                int c = Character.codePointAt(name, i);
                if (p < pattern.length && pattern[p] == '*') {
                    star = p++;
                    mark = i;
                } else if (p < pattern.length && (pattern[p] == '?' || same(pattern[p], c))) {
                    p++;
                    i += Character.charCount(c);
                } else if (star >= 0) {
                    // let the last * swallow one more character and retry after it
                    p = star + 1;
                    mark += Character.charCount(Character.codePointAt(name, mark));
                    i = mark;
                } else {
                    return false;
                }
            }
            while (p < pattern.length && pattern[p] == '*') {
                p++;
            }
            return p == pattern.length;
        }

        private boolean same(int a, int b) {
            return a == b || ignoreCase
                    && Character.toLowerCase(Character.toUpperCase(a)) == Character.toLowerCase(Character.toUpperCase(b));
        }
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
