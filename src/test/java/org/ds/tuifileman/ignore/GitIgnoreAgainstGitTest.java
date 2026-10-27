package org.ds.tuifileman.ignore;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.ds.tuifileman.compare.DirCompare;
import org.ds.tuifileman.compare.DirCompare.Item;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The reference test: builds a real folder tree, lets {@code git check-ignore} judge every entry for many
 * {@code .gitignore} contents and compares that with what a pruning {@link DirCompare} scan keeps. It is
 * skipped when git is not installed.
 */
class GitIgnoreAgainstGitTest {
    private static final String[] FILES = {
        "a.txt", "ab.txt", "b.log", "a.log", "Thumbs.db", "sub/Thumbs.db", ".hidden", "a b.txt", "[x].txt",
        "#file.txt", "build.txt", "fooo", "foo/bar.txt", "build/out.o", "build/keep.txt", "build/sub/deep.o",
        "abuild/x.txt", "src/main.c", "src/gen/x.c", "src/gen/keep.c", "src/sub/a.log", "src/sub/b.c",
        "doc/readme.md", "doc/a.txt", "doc/sub/c.md", "node_modules/pkg/index.js", "deep/er/est/f.tmp",
        "deep/f.tmp", "x/y/z/w.txt", "UP.LOG", "empty/.keepdir"
    };
    // no "Build" next to "build": they would be one folder on a case-insensitive file system
    private static final String[] DIRS = {"foo", "build", "build/sub", "abuild", "src", "src/gen", "src/sub", "doc",
        "doc/sub", "node_modules", "node_modules/pkg", "deep", "deep/er", "deep/er/est", "x", "x/y", "x/y/z",
        "empty", "sub"};

    /** Root .gitignore content first, then pairs of (nested file, content); "\n" stands for a line break. */
    private static final String[][] CASES = {
        {"*.log"}, {"build/"}, {"/build"}, {"build"}, {"*.txt"}, {"*.txt\n!a.txt"}, {"doc/*"}, {"doc/**"},
        {"**/gen"}, {"**/gen/*.c"}, {"src/**/*.c"}, {"build/\n!build/keep.txt"}, {"build/*\n!build/keep.txt"},
        {"Thumbs.db"}, {"/Thumbs.db"}, {"*.o"}, {"a?.txt"}, {"[ab].log"}, {"\\#file*"}, {"a b.txt"}, {"a\\ b.txt"},
        {"foo/"}, {"*\n!*/\n!*.c"}, {"deep/**/f.tmp"}, {"*.tmp"}, {"/src/*.c"}, {"src/*.c"}, {"!a.txt"},
        {"**/build"}, {"build/**"}, {"doc/"}, {".*"}, {"/*.txt"}, {"abuild"}, {"src/gen"}, {"**"}, {"*.log   "},
        {"#*.log"}, {"/doc/*.md"}, {"doc/*.md"}, {"**/doc/**/*.md"}, {"x/**/w.txt"}, {"x/*/z"}, {"x/*/*/w.txt"},
        {"*.[oc]"}, {"[!a]*.txt"}, {"sub/"}, {"/sub"}, {"empty/"}, {"node_modules"}, {"*.txt\n!doc/\n"},
        {"doc/*\n!doc/sub/\n"}, {"*.log\n!src/**/a.log"}, {"src/gen/\n!src/gen/keep.c"}, {"*.c\n!keep.c"},
        {"*.LOG"}, {"Build/"}, {"build.TXT"}, {"UP.log"},
        {"*.log", "src/.gitignore", "!a.log\n*.c\n!main.c"},
        {"", "src/.gitignore", "gen/\n*.log"},
        {"*.txt", "doc/.gitignore", "!a.txt"},
        {"/src", "src/.gitignore", "!main.c"},
        {"", "x/.gitignore", "/y\nw.txt"},
    };

    @TempDir
    Path temp;

    @ParameterizedTest(name = "core.ignorecase={0}")
    @ValueSource(booleans = {false, true})
    void theScanKeepsExactlyWhatGitKeeps(boolean ignoreCase) throws Exception {
        assumeTrue(run(temp, "git", "--version") == 0, "git is not installed");
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Path other = Files.createDirectories(temp.resolve("other"));
        assumeTrue(run(repo, "git", "init", "-q") == 0, "git init failed");
        for (String file : FILES) {
            Path path = repo.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "x");
        }
        List<String> all = new ArrayList<>(List.of(DIRS));
        all.addAll(List.of(FILES));

        List<String> mismatches = new ArrayList<>();
        for (String[] testCase : CASES) {
            try (var walk = Files.walk(repo)) {
                for (Path path : (Iterable<Path>) walk.filter(p -> p.getFileName().toString().equals(".gitignore"))::iterator) {
                    Files.delete(path);
                }
            }
            Files.writeString(repo.resolve(".gitignore"), testCase[0]);
            for (int i = 1; i < testCase.length; i += 2) {
                Files.writeString(repo.resolve(testCase[i]), testCase[i + 1]);
            }
            Map<String, Boolean> git = gitVerdicts(repo, all, ignoreCase);

            IgnoreMatcher matcher = GitIgnoreMatcher.create(List.of(), true, repo, other, ignoreCase);
            Set<String> kept = new HashSet<>();
            for (Item item : DirCompare.scan(repo, other, true, false, new AtomicBoolean(), new AtomicInteger(),
                    matcher, new AtomicInteger())) {
                if (item.status() != DirCompare.Status.IGNORED) {
                    kept.add(item.relative().replace('\\', '/'));
                }
            }
            for (String path : all) {
                boolean expected = git.get(path);
                boolean actual = !kept.contains(path);
                if (expected != actual) {
                    mismatches.add("[" + String.join(" | ", testCase).replace("\n", "\\n") + "] " + path
                            + ": git " + (expected ? "ignores" : "keeps") + ", we " + (actual ? "ignore" : "keep"));
                }
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.size() + " differences from git:\n" + String.join("\n", mismatches));
    }

    private static Map<String, Boolean> gitVerdicts(Path repo, List<String> paths, boolean ignoreCase) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("git", "-c", "core.ignorecase=" + ignoreCase, "-c",
                "core.quotepath=false", "check-ignore", "--no-index", "-v", "-n", "--stdin", "-z");
        builder.directory(repo.toFile());
        Process process = builder.start();
        try (var out = process.getOutputStream()) {
            for (String path : paths) {
                out.write(path.getBytes(StandardCharsets.UTF_8));
                out.write(0);
            }
        }
        byte[] bytes = process.getInputStream().readAllBytes();
        process.waitFor();
        // four NUL-separated fields per path: source, line number, pattern, path (the source is empty for "kept")
        String[] fields = new String(bytes, StandardCharsets.UTF_8).split("\0", -1);
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (int i = 0; i + 3 < fields.length; i += 4) {
            result.put(fields[i + 3], !fields[i].isEmpty() && !fields[i + 2].startsWith("!"));
        }
        return result;
    }

    private static int run(Path dir, String... command) {
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }
}
