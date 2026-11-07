package org.ds.tuifileman.ignore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.ds.tuifileman.Fixtures;
import org.ds.tuifileman.UnreadableFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitIgnoreMatcherTest {
    @TempDir
    Path temp;
    Path left;
    Path right;

    @BeforeEach
    void folders() throws IOException {
        left = Fixtures.dir(temp, "left");
        right = Fixtures.dir(temp, "right");
    }

    private IgnoreMatcher withRules(String rules) throws IOException {
        Path file = temp.resolve("rules.ignore");
        Files.writeString(file, rules);
        return GitIgnoreMatcher.create(List.of(file), false, left, right, false);
    }

    private IgnoreMatcher nested(boolean ignoreCase) throws IOException {
        return GitIgnoreMatcher.create(List.of(), true, left, right, ignoreCase);
    }

    @Test
    void wildcardsFolderOnlyAnchoredAndDoubleStarPatternsAndLaterRulesWin() throws IOException {
        IgnoreMatcher matcher = withRules("*.log\nbuild/\n");
        assertTrue(matcher.ignored("a.log", false));
        assertTrue(matcher.ignored("build", true));
        assertFalse(matcher.ignored("build", false), "a trailing slash only matches folders");
        assertFalse(matcher.ignored("a.txt", false));
        assertFalse(withRules("*.log\n!keep.log\n").ignored("keep.log", false), "a later negation re-includes");
        assertTrue(withRules("*.log\n!keep.log\n").ignored("a.log", false));

        IgnoreMatcher anchored = withRules("/*.txt\n");
        assertTrue(anchored.ignored("a.txt", false));
        assertFalse(anchored.enter("foo", null, null).ignored("bar.txt", false),
                "an anchored pattern must not match in a subfolder");

        IgnoreMatcher doubleStar = withRules("build/**\n");
        assertFalse(doubleStar.ignored("build.txt", false), "a trailing /** keeps names that only start the same");
        assertTrue(doubleStar.enter("build", left, right).ignored("out.o", false));
    }

    @Test
    void ruleFilesAreReadLeniently() throws IOException {
        IgnoreMatcher[] holder = new IgnoreMatcher[1];
        assertDoesNotThrow(() -> holder[0] = withRules("# comment\n\n[unclosed\n*.tmp\n"));
        assertTrue(holder[0].ignored("x.tmp", false), "a bad line must not stop the good ones");
        assertFalse(holder[0].ignored("# comment", false));

        Path file = temp.resolve("crlf.ignore");
        Files.write(file, "\uFEFF*.log\r\nbuild/\r\n".getBytes(StandardCharsets.UTF_8));
        IgnoreMatcher matcher = GitIgnoreMatcher.create(List.of(file), false, left, right, false);
        assertTrue(matcher.ignored("a.log", false), "a byte order mark and CRLF line ends are skipped");
        assertTrue(matcher.ignored("build", true));
    }

    @Test
    void caseIsIgnoredOnlyWhenAskedFor() throws IOException {
        Fixtures.file(left, ".gitignore", "*.LOG\nBuild/\n");
        assertFalse(nested(false).ignored("a.log", false));
        assertTrue(nested(true).ignored("a.log", false));
        assertTrue(nested(true).ignored("build", true));
        assertFalse(nested(false).ignored("build", true));
    }

    @Test
    void aNestedGitignoreAppliesBelowItsFolderOnly() throws IOException {
        Fixtures.file(left, ".gitignore", "*.log\n");
        Fixtures.file(left, "src/.gitignore", "!a.log\n*.c\n");
        IgnoreMatcher root = nested(false);
        IgnoreMatcher src = root.enter("src", left.resolve("src"), null);
        assertTrue(root.ignored("a.log", false));
        assertFalse(src.ignored("a.log", false), "the deeper file re-includes");
        assertTrue(src.ignored("b.log", false), "the root rule still applies below");
        assertTrue(src.ignored("main.c", false));
        assertFalse(root.ignored("main.c", false), "the nested rule does not leak upwards");
    }

    @Test
    void theRulesOfEitherTreeApply() throws IOException {
        Fixtures.file(right, ".gitignore", "*.bak\n");
        IgnoreMatcher matcher = nested(false);
        assertTrue(matcher.ignored("x.bak", false));
        assertFalse(matcher.ignored("x.txt", false));
    }

    @Test
    void anIgnoreFileIsOverriddenByAGitignoreBelowIt() throws IOException {
        Path file = temp.resolve("rules.ignore");
        Files.writeString(file, "*.log\n");
        Fixtures.file(left, ".gitignore", "!keep.log\n");
        IgnoreMatcher matcher = GitIgnoreMatcher.create(List.of(file), true, left, right, false);
        assertTrue(matcher.ignored("a.log", false));
        assertFalse(matcher.ignored("keep.log", false));
    }

    @Test
    void whenTheTreesDisagreeAtOneLevelTheEntryCountsAsIgnored() throws IOException {
        Fixtures.file(left, ".gitignore", "*.log\n!x.log\n");
        Fixtures.file(right, ".gitignore", "*.log\n");
        IgnoreMatcher matcher = nested(false);
        assertTrue(matcher.ignored("x.log", false), "left re-includes, right ignores: the safe side wins");
        assertTrue(matcher.ignored("y.log", false));
    }

    @Test
    void ignoreFilesFormOneListInOrderAndNoneMeansNothingIsIgnored() throws IOException {
        assertTrue(GitIgnoreMatcher.create(List.of(), false, left, right, false) == IgnoreMatcher.NONE);
        assertFalse(IgnoreMatcher.NONE.ignored("anything", false));
        Path first = temp.resolve("first.ignore");
        Path second = temp.resolve("second.ignore");
        Files.writeString(first, "*.log\n");
        Files.writeString(second, "!keep.log\n");
        IgnoreMatcher matcher = GitIgnoreMatcher.create(List.of(first, second), false, left, right, false);
        assertTrue(matcher.ignored("a.log", false));
        assertFalse(matcher.ignored("keep.log", false));
    }

    @Test
    void anUnreadableGitignoreStopsTheRunInsteadOfDroppingItsRules() throws Exception {
        Fixtures.file(left, ".gitignore", "*.log\n");
        Path nestedRules = Fixtures.file(left, "src/.gitignore", "*.tmp\n");
        Fixtures.dir(right, "src");
        try (AutoCloseable locked = UnreadableFile.lock(nestedRules)) {
            assumeTrue(locked != null, "the user can read any file (root)");
            IgnoreMatcher root = nested(false);
            UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                    () -> root.enter("src", left.resolve("src"), right.resolve("src")));
            assertTrue(failure.getMessage().contains(nestedRules.toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains("cannot read"), failure.getMessage());
        }
        Path rootRules = left.resolve(".gitignore");
        try (AutoCloseable locked = UnreadableFile.lock(rootRules)) {
            assumeTrue(locked != null, "the user can read any file (root)");
            IOException failure = assertThrows(IOException.class, () -> nested(false));
            assertTrue(failure.getMessage().contains(rootRules.toString()), failure.getMessage());
        }
    }
}
