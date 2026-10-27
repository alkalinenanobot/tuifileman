package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.Fixtures;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WildcardsTest {
    @TempDir
    Path folder;

    private String pattern(String name) {
        return folder + File.separator + name;
    }

    private List<String> expand(String name) throws Exception {
        return Wildcards.expand(pattern(name)).stream().map(p -> p.getFileName().toString()).toList();
    }

    @Test
    void detectsWildcardCharactersOnly() {
        assertTrue(Wildcards.has("*.txt"));
        assertTrue(Wildcards.has("a?c"));
        assertFalse(Wildcards.has("file[1].txt"));
        assertFalse(Wildcards.has("plain"));
    }

    @Test
    void starAndQuestionMark() throws Exception {
        for (String name : List.of("a.txt", "b.txt", "ab.txt", "c.log", "a1.txt")) {
            Fixtures.file(folder, name, "x");
        }
        assertEquals(List.of("a.txt", "a1.txt", "ab.txt", "b.txt"), expand("*.txt"));
        assertEquals(List.of("a1.txt", "ab.txt"), expand("a?.txt"));
        assertEquals(List.of("a.txt", "a1.txt", "ab.txt"), expand("a*"));
        assertEquals(List.of(), expand("*.zip"));
    }

    @Test
    void matchesFoldersToo() throws Exception {
        Fixtures.dir(folder, "docs");
        Fixtures.file(folder, "d.txt", "x");
        assertEquals(List.of("d.txt", "docs"), expand("d*"));
    }

    @Test
    void theResultIsSortedIgnoringCase() throws Exception {
        for (String name : List.of("b.txt", "A.txt", "c.txt", "B2.txt")) {
            Fixtures.file(folder, name, "x");
        }
        assertEquals(List.of("A.txt", "b.txt", "B2.txt", "c.txt"), expand("*.txt"));
    }

    @Test
    void namesDifferingOnlyInCaseHaveAFixedOrder() throws Exception {
        Fixtures.file(folder, "probe", "x");
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.exists(folder.resolve("PROBE")),
                "needs a case-sensitive file system");
        // Created in both orders: the result must not follow the directory's own order.
        for (List<String> names : List.of(List.of("foo.txt", "Foo.txt", "FOO.txt"), List.of("FOO.txt", "Foo.txt", "foo.txt"))) {
            for (String name : names) {
                Fixtures.file(folder, name, "x");
            }
            assertEquals(List.of("FOO.txt", "Foo.txt", "foo.txt"), expand("*.txt"));
            for (String name : names) {
                java.nio.file.Files.delete(folder.resolve(name));
            }
        }
    }

    @Test
    void starAndQuestionMarkMatchALineBreakInAName() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(File.separatorChar == '/', "Windows names cannot hold one");
        Fixtures.file(folder, "two\nlines.txt", "x");
        assertEquals(List.of("two\nlines.txt"), expand("*.txt"));
        assertEquals(List.of("two\nlines.txt"), expand("two?lines.txt"));
    }

    @Test
    void matchingIgnoresCaseOnWindows() throws Exception {
        Fixtures.file(folder, "README.TXT", "x");
        assertEquals(System.getProperty("os.name").toLowerCase().contains("win") ? List.of("README.TXT") : List.of(),
                expand("*.txt"));
    }

    @Test
    void regexCharactersAreLiteral() throws Exception {
        Fixtures.file(folder, "a+b.txt", "x");
        Fixtures.file(folder, "aab.txt", "x");
        Fixtures.file(folder, "f(1).txt", "x");
        assertEquals(List.of("a+b.txt"), expand("a+b.*"));
        assertEquals(List.of("f(1).txt"), expand("f(1)*"));
    }

    @Test
    void aWildcardInAParentSegmentIsAUsageError() {
        assertThrows(Args.UsageException.class, () -> Wildcards.expand(folder + File.separator + "*" + File.separator + "x.txt"));
        assertThrows(Args.UsageException.class, () -> Wildcards.expand("plain.txt"), "no wildcard at all is not expandable");
    }

    @Test
    void aMissingParentIsAUsageError() {
        assertThrows(Args.UsageException.class, () -> Wildcards.expand(pattern("missing") + File.separator + "*.txt"));
    }
}
