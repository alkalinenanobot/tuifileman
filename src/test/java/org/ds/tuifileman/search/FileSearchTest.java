package org.ds.tuifileman.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.Fixtures;
import org.ds.tuifileman.search.FileSearch.Hit;
import org.ds.tuifileman.search.FileSearch.Query;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSearchTest {
    @TempDir
    Path root;

    private FileSearch last;

    private List<String> find(String names, boolean archives, String text, boolean matchCase, int depth) {
        last = new FileSearch(new Query(root, names, archives, text, matchCase, depth));
        last.run();
        List<String> paths = new ArrayList<>();
        for (Hit hit; (hit = last.poll()) != null; ) {
            paths.add(root.relativize(Path.of(hit.archive() == null ? hit.path() : hit.path().replace('!', java.io.File.separatorChar)))
                    .toString().replace('\\', '/'));
        }
        paths.sort(String::compareTo);
        return paths;
    }

    private List<String> find(String names) {
        return find(names, false, "", false, FileSearch.UNLIMITED);
    }

    private Path zip(String name, String... pairs) throws IOException {
        Path file = root.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < pairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pairs[i]));
                zip.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void severalPatternsInOneRoundAndWildcards() throws Exception {
        Fixtures.file(root, "a.txt", "x");
        Fixtures.file(root, "sub/b.ini", "x");
        Fixtures.file(root, "sub/deep/c.TXT", "x");
        Fixtures.file(root, "sub/d.log", "x");
        Fixtures.file(root, "file1.dat", "x");
        Fixtures.file(root, "file22.dat", "x");

        assertEquals(List.of("a.txt", "sub/b.ini", "sub/deep/c.TXT"), find("*.txt,*.ini"));
        assertEquals(List.of("a.txt", "sub/b.ini", "sub/deep/c.TXT"), find(" *.txt ; *.ini "));
        assertEquals(List.of("file1.dat"), find("file?.dat"));
    }

    @Test
    void aPatternWithoutWildcardsMatchesPartOfTheNameAndEmptyMatchesAll() throws Exception {
        Fixtures.file(root, "ReadMe.md", "x");
        Fixtures.file(root, "sub/readme.txt", "x");
        Fixtures.file(root, "other.txt", "x");

        assertEquals(List.of("ReadMe.md", "sub/readme.txt"), find("readme"));
        assertEquals(List.of("ReadMe.md", "other.txt", "sub", "sub/readme.txt"), find(""));
    }

    @Test
    void foldersMatchByNameButNotWhenSearchingText() throws Exception {
        Fixtures.file(root, "logs/a.txt", "needle");

        assertEquals(List.of("logs"), find("log*"));
        assertEquals(List.of(), find("log*", false, "needle", false, FileSearch.UNLIMITED));
    }

    @Test
    void depthLimitCountsFolderLevels() throws Exception {
        Fixtures.file(root, "top.txt", "x");
        Fixtures.file(root, "one/a.txt", "x");
        Fixtures.file(root, "one/two/b.txt", "x");

        assertEquals(List.of("top.txt"), find("*.txt", false, "", false, 1));
        assertEquals(List.of("one/a.txt", "top.txt"), find("*.txt", false, "", false, 2));
        assertEquals(List.of("one/a.txt", "one/two/b.txt", "top.txt"), find("*.txt", false, "", false, 3));
    }

    @Test
    void textSearchIsCaseInsensitiveUnlessAskedAndReportsTheFirstLine() throws Exception {
        Fixtures.file(root, "a.txt", "first\nsecond Needle here\nthird needle\n");
        Fixtures.file(root, "b.txt", "nothing\n");

        assertEquals(List.of("a.txt"), find("*", false, "NEEDLE", false, FileSearch.UNLIMITED));
        Hit hit = null;
        last = new FileSearch(new Query(root, "*", false, "needle", false, FileSearch.UNLIMITED));
        last.run();
        hit = last.poll();
        assertNotNull(hit);
        assertEquals(2, hit.line());
        assertEquals("second Needle here", hit.snippet());
        assertEquals(List.of("a.txt"), find("*", false, "Needle", true, FileSearch.UNLIMITED));
        assertEquals(List.of(), find("*", false, "NEEDLE", true, FileSearch.UNLIMITED));
    }

    @Test
    void textSearchCombinesWithTheNameFilterAndSkipsBinaryFiles() throws Exception {
        Fixtures.file(root, "a.txt", "needle");
        Fixtures.file(root, "a.log", "needle");
        Files.write(root.resolve("bin.dat"), new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, 1, 2});

        assertEquals(List.of("a.txt"), find("*.txt", false, "needle", false, FileSearch.UNLIMITED));
        assertEquals(List.of("a.log", "a.txt"), find("*", false, "needle", false, FileSearch.UNLIMITED));
    }

    @Test
    void textInOtherEncodingsIsFound() throws Exception {
        Files.write(root.resolve("utf8.txt"), "grüße\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("latin.txt"), "grüße\n".getBytes(java.nio.charset.Charset.forName("windows-1252")));
        Files.write(root.resolve("utf16.txt"), "grüße\n".getBytes(StandardCharsets.UTF_16));
        Files.write(root.resolve("utf16le.txt"), "\uFEFFgrüße\n".getBytes(StandardCharsets.UTF_16LE));

        assertEquals(List.of("latin.txt", "utf16.txt", "utf16le.txt", "utf8.txt"),
                find("*", false, "grüße", false, FileSearch.UNLIMITED));
    }

    @Test
    void aMatchAcrossThePiecesOfAVeryLongLineIsFound() throws Exception {
        String line = "x".repeat(64 * 1024 - 3) + "needle" + "y".repeat(100);
        Fixtures.file(root, "long.txt", line);

        assertEquals(List.of("long.txt"), find("*", false, "needle", true, FileSearch.UNLIMITED));
    }

    @Test
    void archivesAreSearchedOnlyWhenAsked() throws Exception {
        zip("pack.zip", "readme.txt", "hello", "dir/notes.txt", "needle inside", "dir/skip.bin", "x");
        Fixtures.file(root, "plain.txt", "x");

        assertEquals(List.of("plain.txt"), find("*.txt"));
        List<String> inside = find("*.txt", true, "", false, FileSearch.UNLIMITED);
        assertEquals(3, inside.size(), inside.toString());
        assertTrue(inside.contains("plain.txt"));
        assertTrue(inside.contains("pack.zip/readme.txt"), inside.toString());
        assertTrue(inside.contains("pack.zip/dir/notes.txt"), inside.toString());

        assertEquals(List.of("pack.zip/dir/notes.txt"), find("*", true, "needle", false, FileSearch.UNLIMITED));
        assertEquals(List.of(), find("*", false, "needle", false, FileSearch.UNLIMITED));
    }

    @Test
    void anArchiveHitKnowsItsArchiveAndFolder() throws Exception {
        Path archive = zip("pack.zip", "dir/notes.txt", "hello");

        last = new FileSearch(new Query(root, "notes.txt", true, "", false, FileSearch.UNLIMITED));
        last.run();
        Hit hit = last.poll();
        assertNotNull(hit);
        assertEquals(archive.toString(), hit.archive());
        assertEquals("/dir", hit.folder());
        assertEquals("notes.txt", hit.name());
        assertEquals(5, hit.size());
        assertEquals(archive + "!/dir/notes.txt", hit.path());
    }

    @Test
    void anArchiveCountsAsAFolderForTheDepthLimit() throws Exception {
        zip("pack.zip", "top.txt", "x", "dir/deep.txt", "x");

        assertEquals(List.of(), find("*.txt", true, "", false, 1));
        assertEquals(List.of("pack.zip/top.txt"), find("*.txt", true, "", false, 2));
        assertEquals(2, find("*.txt", true, "", false, 3).size());
    }

    @Test
    void aBrokenArchiveIsCountedNotFatal() throws Exception {
        Files.writeString(root.resolve("fake.zip"), "this is not a zip");
        Fixtures.file(root, "a.txt", "x");

        assertEquals(List.of("a.txt"), find("*.txt", true, "", false, FileSearch.UNLIMITED));
        assertEquals(1, last.unreadable());
    }

    @Test
    void cancellingStopsTheSearch() throws Exception {
        for (int i = 0; i < 50; i++) {
            Fixtures.file(root, "d" + i + "/f.txt", "x");
        }
        FileSearch search = new FileSearch(new Query(root, "*.txt", false, "", false, FileSearch.UNLIMITED));
        search.cancel();
        search.run();
        assertTrue(search.isDone());
        assertEquals(0, search.hitCount());
    }

    @Test
    void aMissingRootIsAnError() {
        FileSearch search = new FileSearch(new Query(root.resolve("nope"), "*", false, "", false, FileSearch.UNLIMITED));
        search.run();
        assertTrue(search.isDone());
        assertNotNull(search.error());
    }
}
