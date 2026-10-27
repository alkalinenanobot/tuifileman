package org.ds.tuifileman.ignore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.Fixtures;
import java.io.IOException;
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
    void noRulesIgnoreNothing() throws IOException {
        assertTrue(GitIgnoreMatcher.create(List.of(), false, left, right, false) == IgnoreMatcher.NONE);
        assertFalse(IgnoreMatcher.NONE.ignored("anything", false));
    }

    @Test
    void wildcardsAndFolderOnlyPatterns() throws IOException {
        IgnoreMatcher matcher = withRules("*.log\nbuild/\n");
        assertTrue(matcher.ignored("a.log", false));
        assertTrue(matcher.ignored("build", true));
        assertFalse(matcher.ignored("build", false), "a trailing slash only matches folders");
        assertFalse(matcher.ignored("a.txt", false));
    }

    @Test
    void aLeadingSlashAnchorsToTheRoot() throws IOException {
        IgnoreMatcher matcher = withRules("/*.txt\n");
        assertTrue(matcher.ignored("a.txt", false));
        assertFalse(matcher.enter("foo", null, null).ignored("bar.txt", false),
                "an anchored pattern must not match in a subfolder");
    }

    @Test
    void aTrailingDoubleStarKeepsNamesThatOnlyStartTheSame() throws IOException {
        IgnoreMatcher matcher = withRules("build/**\n");
        assertFalse(matcher.ignored("build.txt", false));
        assertTrue(matcher.enter("build", left, right).ignored("out.o", false));
    }

    @Test
    void laterRulesWinAndNegationReincludes() throws IOException {
        IgnoreMatcher matcher = withRules("*.log\n!keep.log\n");
        assertTrue(matcher.ignored("a.log", false));
        assertFalse(matcher.ignored("keep.log", false));
    }

    @Test
    void commentsBlankLinesAndMalformedPatternsAreHarmless() throws IOException {
        IgnoreMatcher[] holder = new IgnoreMatcher[1];
        assertDoesNotThrow(() -> holder[0] = withRules("# comment\n\n[unclosed\n*.tmp\n"));
        assertTrue(holder[0].ignored("x.tmp", false), "a bad line must not stop the good ones");
        assertFalse(holder[0].ignored("# comment", false));
    }

    @Test
    void windowsLineEndingsAndAByteOrderMarkAreHandled() throws IOException {
        Path file = temp.resolve("crlf.ignore");
        Files.write(file, "\uFEFF*.log\r\nbuild/\r\n".getBytes(StandardCharsets.UTF_8));
        IgnoreMatcher matcher = GitIgnoreMatcher.create(List.of(file), false, left, right, false);
        assertTrue(matcher.ignored("a.log", false));
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
    void severalIgnoreFilesAreOneListInOrder() throws IOException {
        Path first = temp.resolve("first.ignore");
        Path second = temp.resolve("second.ignore");
        Files.writeString(first, "*.log\n");
        Files.writeString(second, "!keep.log\n");
        IgnoreMatcher matcher = GitIgnoreMatcher.create(List.of(first, second), false, left, right, false);
        assertTrue(matcher.ignored("a.log", false));
        assertFalse(matcher.ignored("keep.log", false));
    }
}
