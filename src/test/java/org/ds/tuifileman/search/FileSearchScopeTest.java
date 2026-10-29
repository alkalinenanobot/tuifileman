package org.ds.tuifileman.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ds.tuifileman.Fixtures;
import org.ds.tuifileman.fs.ArchivePaths;
import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.ignore.IgnoreMatcher;
import org.ds.tuifileman.search.FileSearch.Hit;
import org.ds.tuifileman.search.FileSearch.Query;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a search leaves out (exclude patterns, ignore rules) and where it may start (an archive). */
class FileSearchScopeTest {
    @TempDir
    Path root;

    @AfterEach
    void close() {
        Archives.closeAll();
    }

    private FileSearch run(Query query) {
        FileSearch search = new FileSearch(query);
        search.run();
        return search;
    }

    private List<String> paths(FileSearch search) {
        List<String> paths = new ArrayList<>();
        for (Hit hit; (hit = search.poll()) != null; ) {
            paths.add(hit.path().substring(root.toString().length() + 1).replace('\\', '/'));
        }
        paths.sort(String::compareTo);
        return paths;
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

    private Query query(Path start, String names, boolean archives, String text, Predicate<String> excluded,
                        IgnoreMatcher ignore) {
        return new Query(start, names, archives, text, false, FileSearch.UNLIMITED, FileSearch.UNLIMITED, excluded, ignore);
    }

    @Test
    void anExcludedFolderIsNotEnteredAndAnExcludedFileIsNotListed() throws Exception {
        Fixtures.file(root, "keep/bom.ini", "x");
        Fixtures.file(root, ".git/bom.ini", "x");
        Fixtures.file(root, "keep/.git/bom.ini", "x");
        Fixtures.file(root, "keep/skip.tmp", "x");
        Predicate<String> excluded = relative -> relative.equals(".git") || relative.endsWith("/.git")
                || relative.endsWith(".tmp");
        assertEquals(List.of("keep/bom.ini"), paths(run(query(root, "*.ini", false, "", excluded, IgnoreMatcher.NONE))));
        assertEquals(List.of("keep", "keep/bom.ini"),
                paths(run(query(root, "keep;bom", false, "", excluded, IgnoreMatcher.NONE))),
                "names that contain a pattern: the folder and the file below it");
    }

    @Test
    void theExcludeTestSeesThePathBelowTheRootWithSlashes() throws Exception {
        Fixtures.file(root, "a/b/c.txt", "x");
        List<String> asked = new ArrayList<>();
        run(query(root, "*", false, "", relative -> {
            asked.add(relative);
            return false;
        }, IgnoreMatcher.NONE));
        asked.sort(String::compareTo);
        assertEquals(List.of("a", "a/b", "a/b/c.txt"), asked);
    }

    @Test
    void gitignoreRulesLeaveOutFilesAndWholeFolders() throws Exception {
        Fixtures.file(root, ".gitignore", "build/\n*.log\n");
        Fixtures.file(root, "src/a.txt", "x");
        Fixtures.file(root, "src/trace.log", "x");
        Fixtures.file(root, "build/b.txt", "x");
        Fixtures.file(root, "src/.gitignore", "a.txt\n");
        IgnoreMatcher ignore = IgnoreMatcher.git(List.of(), true, root, root);
        assertEquals(List.of(), paths(run(query(root, "*.txt", false, "", p -> false, ignore))),
                "build/ is ignored as a folder, src/a.txt by the nested file");
        assertEquals(List.of(), paths(run(query(root, "*.log", false, "", p -> false, ignore))), "the root rule *.log");
        assertEquals(List.of("build/b.txt", "src/a.txt"), paths(run(query(root, "*.txt", false, "", p -> false,
                IgnoreMatcher.NONE))));
    }

    @Test
    void anArchiveFileCanBeTheRoot() throws Exception {
        Path archive = zip("pack.zip", "BuildID.txt", "id 42", "dir/other.txt", "needle");
        FileSearch search = run(query(archive, "*.txt", false, "", p -> false, IgnoreMatcher.NONE));
        assertNull(search.error());
        Hit first = search.poll();
        assertNotNull(first);
        assertEquals(archive.toString(), first.archive());
        List<String> found = new ArrayList<>();
        found.add(first.path());
        for (Hit hit; (hit = search.poll()) != null; ) {
            found.add(hit.path());
        }
        found.sort(String::compareTo);
        assertEquals(List.of(archive + "!/BuildID.txt", archive + "!/dir/other.txt"), found);

        FileSearch text = run(query(archive, "*", false, "NEEDLE", p -> false, IgnoreMatcher.NONE));
        Hit hit = text.poll();
        assertNotNull(hit);
        assertEquals(archive + "!/dir/other.txt", hit.path());
        assertEquals(1, hit.line());
    }

    @Test
    void aFolderInsideAnArchiveCanBeTheRootAndTheExcludeStillApplies() throws Exception {
        Path archive = zip("pack.zip", "top.txt", "x", "dir/a.txt", "x", "dir/skip/b.txt", "x");
        Path inside = ArchivePaths.resolve(archive + "!/dir");
        List<String> found = new ArrayList<>();
        FileSearch search = run(query(inside, "*.txt", false, "", relative -> relative.equals("skip"), IgnoreMatcher.NONE));
        for (Hit hit; (hit = search.poll()) != null; ) {
            found.add(hit.path());
        }
        assertEquals(List.of(archive + "!/dir/a.txt"), found);
    }

    @Test
    void aFileThatIsNoArchiveAsRootIsAnError() throws Exception {
        Path text = Files.writeString(root.resolve("t.txt"), "plain");
        assertNotNull(run(query(text, "*", false, "", p -> false, IgnoreMatcher.NONE)).error());
    }
}
