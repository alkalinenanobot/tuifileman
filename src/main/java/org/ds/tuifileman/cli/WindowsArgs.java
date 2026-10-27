package org.ds.tuifileman.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows passes a program its command line in UTF-16, but the JVM (and a native image) converts the arguments to the
 * ANSI code page before {@code main} runs. A character outside that code page (with Windows-1250: the French oe
 * ligature, Cyrillic, Greek) arrives as a question mark, in a search text as well as in a file name, and the C
 * runtime then takes the question marks for a wildcard and replaces the argument by the names in the current folder
 * that fit it (six question marks: every six letter name). On Linux the bytes of the command line are decoded with
 * the UTF-8 locale and nothing is lost. The real line is asked for again and, when it holds such characters,
 * replaces the arguments.
 */
public final class WindowsArgs {
    private WindowsArgs() { }

    /** The arguments as the user typed them; {@code args} itself when nothing was lost or the line is not available. */
    public static String[] restore(String[] args) {
        if (args.length == 0) {
            return args;
        }
        String line = Tty.commandLine();
        return line == null ? args : restore(args, line);
    }

    /**
     * The words of {@code line} that follow the program (for {@code java -jar}, its own options as well), found by
     * the leading arguments that no conversion can have touched. Used only when one of them has a character beyond
     * ASCII, so that a command line without any behaves as before, and only when the words that cannot have been
     * touched are all still there, in order: a wrapper that started the program differently cannot make them wrong.
     */
    static String[] restore(String[] args, String line) {
        List<String> words = split(line);
        int from = start(words, args);
        if (from < 0) {
            return args;
        }
        List<String> real = words.subList(from, words.size());
        if (real.stream().noneMatch(word -> word.chars().anyMatch(c -> c >= 128))) {
            return args;
        }
        int at = 0;
        for (String word : real) {
            if (intact(word)) {
                while (at < args.length && !args[at].equals(word)) {
                    at++;
                }
                if (at == args.length) {
                    return args;
                }
                at++;
            }
        }
        return real.toArray(new String[0]);
    }

    /**
     * Index in {@code words} of the first argument, or -1. The leading arguments without a damaged character are
     * the same in the real line; how many of them there are is not known when a damaged one was expanded into plain
     * names, so the longest run that is found in the line wins.
     */
    private static int start(List<String> words, String[] args) {
        int safe = 0;
        while (safe < args.length && intact(args[safe])) {
            safe++;
        }
        if (safe == 0) {
            // the first argument is already damaged: no way to find it, so count from the end (nothing expanded then)
            return words.size() >= args.length ? words.size() - args.length : -1;
        }
        for (int run = safe; run >= 1; run--) {
            for (int from = 1; from + run <= words.size(); from++) {
                int i = 0;
                while (i < run && words.get(from + i).equals(args[i])) {
                    i++;
                }
                if (i == run) {
                    return from;
                }
            }
        }
        return -1;
    }

    /** A word that the conversion leaves as it is: ASCII, and without the characters that stand for a wildcard. */
    private static boolean intact(String word) {
        return word.chars().allMatch(c -> c < 128 && c != '?' && c != '*');
    }

    /** Splits a command line like {@code CommandLineToArgvW}: quotes group, backslashes only count before a quote. */
    static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\') {
                int end = i;
                while (end < n && line.charAt(end) == '\\') {
                    end++;
                }
                int slashes = end - i;
                if (end < n && line.charAt(end) == '"') {
                    word.append("\\".repeat(slashes / 2));
                    if (slashes % 2 == 1) {
                        word.append('"');
                        end++;
                    }
                } else {
                    word.append("\\".repeat(slashes));
                }
                started = true;
                i = end;
            } else if (c == '"') {
                if (quoted && i + 1 < n && line.charAt(i + 1) == '"') {
                    word.append('"');
                    i += 2;
                } else {
                    quoted = !quoted;
                    i++;
                }
                started = true;
            } else if ((c == ' ' || c == '\t') && !quoted) {
                if (started) {
                    words.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
                i++;
            } else {
                word.append(c);
                started = true;
                i++;
            }
        }
        if (started) {
            words.add(word.toString());
        }
        return words;
    }
}
