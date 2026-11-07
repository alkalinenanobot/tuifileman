package org.ds.tuifileman.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlobTest {
    private static boolean matches(String glob, String name) {
        return Glob.compile(glob, false).matches(name);
    }

    @Test
    void theWholeNameMustMatch() {
        assertTrue(matches("a.txt", "a.txt"));
        assertFalse(matches("a.txt", "a.txt.bak"));
        assertFalse(matches("a.txt", "xa.txt"));
        assertFalse(matches("a.txt", "A.txt"));
        assertTrue(matches("", ""));
        assertFalse(matches("", "a"));
        assertFalse(matches("a", ""));
    }

    @Test
    void starIsAnyRunIncludingNothing() {
        assertTrue(matches("*", ""));
        assertTrue(matches("*", "anything"));
        assertTrue(matches("*.txt", ".txt"));
        assertTrue(matches("*.txt", "a.b.txt"));
        assertFalse(matches("*.txt", "a.txt.bak"));
        assertTrue(matches("a*b*c", "abc"));
        assertTrue(matches("a*b*c", "axxbyyc"));
        assertFalse(matches("a*b*c", "axxbyy"));
        assertTrue(matches("**a**", "bab"));
        assertTrue(matches("*ab", "aab"), "the star must give a character back");
        assertTrue(matches("*aab", "aaab"));
    }

    @Test
    void questionMarkIsExactlyOneCharacter() {
        assertTrue(matches("a?c", "abc"));
        assertFalse(matches("a?c", "ac"));
        assertFalse(matches("a?c", "abbc"));
        assertTrue(matches("???", "xyz"));
        assertTrue(matches("?*", "x"));
        assertFalse(matches("?*", ""));
    }

    @Test
    void otherCharactersAreLiteral() {
        assertTrue(matches("a+b.txt", "a+b.txt"));
        assertFalse(matches("a+b.txt", "aab.txt"));
        assertTrue(matches("f(1)[x]*", "f(1)[x]-copy"));
        assertTrue(matches("a\\b", "a\\b"));
    }

    @Test
    void starAndQuestionMarkMatchALineBreak() {
        assertTrue(matches("two?lines.txt", "two\nlines.txt"));
        assertTrue(matches("*.txt", "two\nlines.txt"));
        assertTrue(matches("*", "\n"));
    }

    @Test
    void caseFoldingFollowsTheUnicodeRulesOfRegularExpressions() {
        assertTrue(Glob.compile("README.*", true).matches("readme.TXT"));
        assertTrue(Glob.compile("\u00e9*", true).matches("\u00c9T\u00c9"));
        assertTrue(Glob.compile("\u03c3", true).matches("\u03a3"));
        assertFalse(Glob.compile("README.*", false).matches("readme.TXT"));
        assertFalse(Glob.compile("a", true).matches("b"));
    }

    @Test
    void charactersOutsideTheBasicPlaneCountAsOne() {
        String smile = new String(Character.toChars(0x1F600));
        assertTrue(matches("a?b", "a" + smile + "b"));
        assertFalse(matches("a??b", "a" + smile + "b"));
        assertTrue(matches("a*b", "a" + smile + "b"));
        assertTrue(matches("a" + smile + "*", "a" + smile + smile));
        assertTrue(matches("?", smile));
        String lower = new String(Character.toChars(0x10428));
        String upper = new String(Character.toChars(0x10400));
        assertTrue(Glob.compile(upper + "?", true).matches(lower + smile));
        assertFalse(Glob.compile(upper, false).matches(lower));
    }

    @Test
    void manyStarsOnANameThatDoesNotMatchTakeLinearTimeNotExponential() {
        String glob = "*a*a*a*a*a*a*a*a*b";
        String name = "a".repeat(200);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(Glob.compile(glob, false).matches(name)));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(Glob.compile(glob, true).matches(name)));
        assertTrue(Glob.compile(glob, false).matches(name + "b"));
    }

    @Test
    void hasAndSplit() {
        assertTrue(Glob.has("a*"));
        assertTrue(Glob.has("a?"));
        assertFalse(Glob.has("a[1]"));
        assertEquals(List.of("*.txt", "*.ini", "readme"), Glob.split(" *.txt, *.ini; readme ,,"));
    }
}
