package org.ds.tuifileman.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The promises scripts rely on: exit codes, which stream carries what, the JSON fields and that nothing is
 * changed without being asked. Each test is a real program run.
 */
class CliContractTest {
    @TempDir
    Path dir;

    private Path left() throws Exception {
        Cmd.file(dir, "a/same.txt", "same");
        Cmd.file(dir, "a/only-left.txt", "left");
        Cmd.file(dir, "a/sub/deep.txt", "deep");
        Cmd.file(dir, "b/same.txt", "same");
        Files.setLastModifiedTime(dir.resolve("b/same.txt"), Files.getLastModifiedTime(dir.resolve("a/same.txt")));
        return dir.resolve("a");
    }

    private Path right() {
        return dir.resolve("b");
    }

    // ---- general ----------------------------------------------------------------------------------------------

    @Test
    void versionIsPrintedAndMatchesThePom() throws Exception {
        Cmd.Result result = Cmd.run("--version");
        assertEquals(0, result.exit());
        assertEquals("tuifileman " + Cli.VERSION, result.out().strip());
        String pom = Files.readString(Path.of("pom.xml"));
        String declared = pom.replaceFirst("(?s).*?</artifactId>\\s*<version>([^<]+)</version>.*", "$1");
        assertEquals(declared.replace("-SNAPSHOT", ""), Cli.VERSION, "Cli.VERSION and the pom version drifted apart");
    }

    @Test
    void helpListsEveryCommand() throws Exception {
        Cmd.Result result = Cmd.run("help");
        assertEquals(0, result.exit());
        for (String command : List.of("diff", "copy", "move", "delete", "sync", "rename", "find", "install-aliases")) {
            assertTrue(result.out().contains(command), command);
        }
    }

    @Test
    void unknownOptionIsAUsageError() throws Exception {
        Cmd.Result result = Cmd.run("diff", "--bogus", "x", "y");
        assertEquals(2, result.exit());
        assertEquals("", result.out(), "errors go to stderr only");
        assertTrue(result.err().contains("error:"));
    }

    @Test
    void missingArgumentsAreAUsageError() throws Exception {
        assertEquals(2, Cmd.run("diff").exit());
        assertEquals(2, Cmd.run("copy").exit());
        assertEquals(2, Cmd.run("rename").exit());
        assertEquals(2, Cmd.run("find").exit(), "find needs the folder to search");
        assertEquals(2, Cmd.run("find", dir.toString(), dir.toString()).exit());
    }

    @Test
    void aMistypedCommandIsRefusedInsteadOfOpeningAFolder() throws Exception {
        Cmd.Result typo = Cmd.run("install-aliasses");
        assertEquals(2, typo.exit());
        assertEquals("", typo.out());
        assertTrue(typo.err().contains("did you mean 'install-aliases'?"), typo.err());
        Cmd.Result missing = Cmd.run(dir.resolve("no-such-folder").toString());
        assertEquals(2, missing.exit());
        assertTrue(missing.err().contains("not a command or an existing folder"), missing.err());
        assertEquals(2, Cmd.run("--bogus").exit());
        assertEquals(2, Cmd.run("a", "b", "c").exit());
    }

    // ---- diff -------------------------------------------------------------------------------------------------

    @Test
    void diffOfEqualFoldersExitsZero() throws Exception {
        Cmd.file(dir, "a/x.txt", "1");
        Cmd.file(dir, "b/x.txt", "1");
        Files.setLastModifiedTime(dir.resolve("b/x.txt"), Files.getLastModifiedTime(dir.resolve("a/x.txt")));
        assertEquals(0, Cmd.run("diff", "-q", dir.resolve("a").toString(), dir.resolve("b").toString()).exit());
    }

    @Test
    void diffWithDifferencesExitsOneAndKeepsStreamsApart() throws Exception {
        Cmd.Result result = Cmd.run("diff", left().toString(), right().toString());
        assertEquals(1, result.exit());
        assertTrue(result.out().contains("only-left.txt"));
        assertFalse(result.out().contains("same.txt"), "identical entries are hidden by default");
        assertFalse(result.err().contains("only-left.txt"), "the listing is data: stdout only");
        assertEquals("", result.err(), "no terminal: no summary line");
    }

    @Test
    void diffQuietPrintsNothingOnStderr() throws Exception {
        Cmd.Result result = Cmd.run("diff", "-q", left().toString(), right().toString());
        assertEquals(1, result.exit());
        assertEquals("", result.err());
    }

    @Test
    void diffExitCodeIsAboutTheTreesNotTheShownRows() throws Exception {
        Cmd.Result result = Cmd.run("diff", "-q", "-o", "same", left().toString(), right().toString());
        assertTrue(result.out().contains("same.txt") && !result.out().contains("only-left.txt"), result.out());
        assertEquals(1, result.exit(), "--only filters the listing; the trees still differ");
    }

    @Test
    void anInvalidDiffPathIsAUsageError() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File.separatorChar == '\\',
                "only NUL is invalid in a POSIX path, and no argument can hold one");
        Cmd.Result result = Cmd.run("diff", "-q", "a<b", dir.toString());
        assertEquals(2, result.exit(), result.err());
        assertTrue(result.err().contains("invalid path"), result.err());
    }

    @Test
    void diffJsonHasTheDocumentedFields() throws Exception {
        Cmd.Result result = Cmd.run("diff", "-q", "-f", "json", left().toString(), right().toString());
        Map<?, ?> json = result.json();
        assertEquals(1, ((Number) json.get("schema")).intValue());
        assertEquals("diff", json.get("command"));
        Map<?, ?> summary = (Map<?, ?>) json.get("summary");
        assertEquals(3, ((Number) summary.get("only-left")).intValue(), "a file, a folder and the file in it");
        assertTrue(((List<?>) json.get("items")).size() >= 2);
        assertFalse(json.containsKey("stats"), "stats only on request");
    }

    @Test
    void diffJsonLinesEndWithASummary() throws Exception {
        Cmd.Result result = Cmd.run("diff", "-q", "-f", "jsonl", left().toString(), right().toString());
        List<String> lines = result.outLines();
        assertTrue(lines.size() >= 2);
        for (String line : lines) {
            assertTrue(JsonReader.parse(line) instanceof Map, "every line is one JSON object");
        }
        assertEquals("summary", result.lastJson().get("kind"));
        assertEquals("item", ((Map<?, ?>) JsonReader.parse(lines.get(0))).get("kind"));
    }

    @Test
    void diffCsvStartsWithAHeader() throws Exception {
        Cmd.Result result = Cmd.run("diff", "-q", "-f", "csv", left().toString(), right().toString());
        assertEquals("status,entry,path,left_size,left_modified,right_size,right_modified", result.outLines().get(0));
    }

    @Test
    void diffStatsAddsAnObjectAndABlockOnStderr() throws Exception {
        Cmd.Result result = Cmd.run("diff", "-q", "--stats", "-f", "json", left().toString(), right().toString());
        Map<?, ?> stats = (Map<?, ?>) result.json().get("stats");
        for (String key : List.of("startup_ms", "wall_ms", "phases", "items", "bytes", "items_per_s", "bytes_per_s",
                "threads", "storage")) {
            assertTrue(stats.containsKey(key), key);
        }
        assertTrue(result.err().startsWith("stats"), "the block is printed even with --quiet");
    }

    @Test
    void diffOfAMissingFolderFails() throws Exception {
        Cmd.Result result = Cmd.run("diff", "-q", dir.resolve("nope").toString(), dir.toString());
        assertTrue(result.exit() == 2 || result.exit() == 4, "exit " + result.exit());
        assertEquals("", result.out());
    }

    @Test
    void anUnreadableFolderMakesDiffAndSyncReportFileErrors() throws Exception {
        Cmd.file(dir, "a/x.txt", "1");
        Cmd.file(dir, "b/x.txt", "1");
        Files.setLastModifiedTime(dir.resolve("b/x.txt"), Files.getLastModifiedTime(dir.resolve("a/x.txt")));
        Cmd.file(dir, "a/locked/secret.txt", "only here");
        Files.createDirectories(dir.resolve("b/locked"));
        try (AutoCloseable locked = org.ds.tuifileman.Fixtures.unreadable(dir.resolve("a/locked"))) {
            org.junit.jupiter.api.Assumptions.assumeTrue(locked != null, "the user can read any folder (root)");
            String a = dir.resolve("a").toString();
            String b = dir.resolve("b").toString();
            Cmd.Result diff = Cmd.run("diff", "-q", "-f", "json", a, b);
            assertEquals(3, diff.exit(), "an incomplete compare is not \"identical\": " + diff.err());
            assertTrue(diff.err().contains("locked"), diff.err());
            assertEquals(1, ((Number) ((Map<?, ?>) diff.json().get("summary")).get("unreadable")).intValue());
            Cmd.Result plan = Cmd.run("sync", "-q", "-n", a, b);
            assertEquals(3, plan.exit(), plan.err());
            assertTrue(plan.err().contains("locked"), plan.err());
        }
    }

    // ---- copy, move, delete -----------------------------------------------------------------------------------

    @Test
    void copyDryRunChangesNothing() throws Exception {
        Path source = Cmd.file(dir, "src/f.txt", "data");
        Cmd.Result result = Cmd.run("copy", "-n", "-q", "-f", "json", source.toString(), dir.resolve("dst").toString());
        assertEquals(0, result.exit());
        assertEquals(true, result.json().get("dry_run"));
        assertFalse(Files.exists(dir.resolve("dst/f.txt")));
    }

    @Test
    void copyCopiesAndReportsTheCounts() throws Exception {
        Path source = Cmd.file(dir, "src/f.txt", "data");
        Cmd.Result result = Cmd.run("copy", "-q", "-f", "json", source.toString(), dir.resolve("dst").toString());
        assertEquals(0, result.exit());
        assertEquals("ok", result.json().get("status"));
        assertEquals(1, ((Number) result.json().get("items")).intValue());
        assertEquals(4, ((Number) result.json().get("bytes")).intValue());
        assertEquals("data", Files.readString(dir.resolve("dst/f.txt")));
        assertTrue(Files.exists(source), "copy keeps the source");
    }

    @Test
    void copyWithoutAPolicyStopsOnAConflictWhenThereIsNoTerminal() throws Exception {
        Path source = Cmd.file(dir, "src/f.txt", "new");
        Cmd.file(dir, "dst/f.txt", "old");
        Cmd.Result result = Cmd.run("copy", "-q", source.toString(), dir.resolve("dst").toString());
        assertEquals(2, result.exit());
        assertEquals("old", Files.readString(dir.resolve("dst/f.txt")), "nothing was touched");
        assertEquals("new", Files.readString(source));
    }

    @Test
    void copyConflictPolicyOverwriteReplaces() throws Exception {
        Path source = Cmd.file(dir, "src/f.txt", "new");
        Cmd.file(dir, "dst/f.txt", "old");
        Cmd.Result result = Cmd.run("copy", "-q", "-c", "overwrite", source.toString(), dir.resolve("dst").toString());
        assertEquals(0, result.exit());
        assertEquals("new", Files.readString(dir.resolve("dst/f.txt")));
    }

    @Test
    void moveRemovesTheSource() throws Exception {
        Path source = Cmd.file(dir, "src/f.txt", "data");
        Cmd.Result result = Cmd.run("move", "-q", source.toString(), dir.resolve("dst").toString());
        assertEquals(0, result.exit());
        assertFalse(Files.exists(source));
        assertEquals("data", Files.readString(dir.resolve("dst/f.txt")));
    }

    @Test
    void deleteRefusesWithoutYesWhenThereIsNoTerminal() throws Exception {
        Path victim = Cmd.file(dir, "victim/f.txt", "data");
        Cmd.Result result = Cmd.run("delete", "-q", dir.resolve("victim").toString());
        assertEquals(2, result.exit());
        assertTrue(Files.exists(victim));
    }

    @Test
    void deleteWithYesDeletes() throws Exception {
        Cmd.file(dir, "victim/f.txt", "data");
        Cmd.Result result = Cmd.run("delete", "-q", "-y", dir.resolve("victim").toString());
        assertEquals(0, result.exit());
        assertFalse(Files.exists(dir.resolve("victim")));
    }

    @Test
    void deleteDryRunDeletesNothing() throws Exception {
        Path victim = Cmd.file(dir, "victim/f.txt", "data");
        assertEquals(0, Cmd.run("delete", "-q", "-n", dir.resolve("victim").toString()).exit());
        assertTrue(Files.exists(victim));
    }

    // ---- sync -------------------------------------------------------------------------------------------------

    @Test
    void syncRefusesWithoutYesWhenThereIsNoTerminal() throws Exception {
        Cmd.Result result = Cmd.run("sync", "-q", left().toString(), right().toString());
        assertEquals(2, result.exit());
        assertFalse(Files.exists(right().resolve("only-left.txt")));
    }

    @Test
    void syncMergeCopiesMissingFiles() throws Exception {
        Cmd.Result result = Cmd.run("sync", "-q", "-y", left().toString(), right().toString());
        assertEquals(0, result.exit());
        assertEquals("left", Files.readString(right().resolve("only-left.txt")));
        assertEquals("deep", Files.readString(right().resolve("sub/deep.txt")));
    }

    @Test
    void syncPlanRoundTrip() throws Exception {
        Cmd.Result plan = Cmd.run("sync", "-n", "-q", "-f", "jsonl", left().toString(), right().toString());
        assertEquals(0, plan.exit());
        assertFalse(Files.exists(right().resolve("only-left.txt")), "a plan changes nothing");
        Path file = dir.resolve("plan.jsonl");
        Files.writeString(file, plan.out());
        Cmd.Result applied = Cmd.run("sync", "-q", "-y", "-p", file.toString(), left().toString(), right().toString());
        assertEquals(0, applied.exit(), applied.err());
        assertEquals("left", Files.readString(right().resolve("only-left.txt")));
    }

    @Test
    void syncMirrorDeletesWhatTheSourceLacks() throws Exception {
        Cmd.file(dir, "a/keep.txt", "k");
        Cmd.file(dir, "b/keep.txt", "k");
        Cmd.file(dir, "b/extra.txt", "x");
        Files.setLastModifiedTime(dir.resolve("b/keep.txt"), Files.getLastModifiedTime(dir.resolve("a/keep.txt")));
        Cmd.Result result = Cmd.run("sync", "-q", "-y", "-m", "mirror-right", dir.resolve("a").toString(), right().toString());
        assertEquals(0, result.exit(), result.err());
        assertFalse(Files.exists(dir.resolve("b/extra.txt")));
    }

    // ---- ignore rules ------------------------------------------------------------------------------------------

    @Test
    void diffGitignoreHidesIgnoredEntriesInTextAndCountsThem() throws Exception {
        Cmd.file(dir, "a/.gitignore", "*.log\nbuild/\n");
        Cmd.file(dir, "a/x.log", "log");
        Cmd.file(dir, "a/build/out.o", "o");
        Cmd.file(dir, "a/keep.txt", "k");
        Files.createDirectories(right());
        Cmd.Result result = Cmd.run("diff", "-q", "-g", "--stats", "-f", "text", dir.resolve("a").toString(), right().toString());
        assertEquals(1, result.exit(), result.err());
        assertTrue(result.out().contains("keep.txt"), result.out());
        assertFalse(result.out().contains("x.log"), result.out());
        assertFalse(result.out().contains("build"), result.out());
        Cmd.Result shown = Cmd.run("diff", "-q", "-g", "--show-ignored", dir.resolve("a").toString(), right().toString());
        assertTrue(shown.out().contains("ignored") && shown.out().contains("x.log") && shown.out().contains("build/"), shown.out());
        assertFalse(shown.out().contains("out.o"), "an ignored folder is one item: " + shown.out());
        Cmd.Result json = Cmd.run("diff", "-q", "-g", "--stats", "-f", "json", dir.resolve("a").toString(), right().toString());
        assertEquals(2, ((Number) ((Map<?, ?>) json.json().get("stats")).get("ignored")).intValue());
        Cmd.Result without = Cmd.run("diff", "-q", "-f", "csv", dir.resolve("a").toString(), right().toString());
        assertTrue(without.out().contains("x.log") && !without.out().contains(",ignored"), "without --gitignore nothing is ignored");
    }

    @Test
    void diffListsIgnoredEntriesInDataFormatsAndNamesTheSide() throws Exception {
        Cmd.file(dir, "a/x.log", "log");
        Cmd.file(dir, "b/.gitignore", "*.log\n");
        Cmd.file(dir, "b/y.log", "log");
        Cmd.Result result = Cmd.run("diff", "-q", "-g", "-f", "jsonl", dir.resolve("a").toString(), right().toString());
        long ignored = result.outLines().stream().filter(l -> l.contains("\"status\":\"ignored\"")).count();
        assertEquals(2, ignored, result.out());
        assertTrue(result.out().contains("\"ignoredBy\":\"right\""), result.out());
        assertTrue(result.out().contains("\"ignored\":2"), "the summary counts them: " + result.out());
        Cmd.Result csv = Cmd.run("diff", "-q", "-g", "-f", "csv", dir.resolve("a").toString(), right().toString());
        assertTrue(csv.out().contains("ignored,file,x.log"), csv.out());
        Cmd.Result only = Cmd.run("diff", "-q", "-g", "-o", "ignored", dir.resolve("a").toString(), right().toString());
        assertTrue(only.out().contains("x.log") && only.out().contains("(by right rules)"), only.out());
    }

    @Test
    void ignoredEntriesAreNotDifferences() throws Exception {
        Cmd.file(dir, "a/.gitignore", "*.log\n");
        Cmd.file(dir, "b/.gitignore", "*.log\n");
        Cmd.file(dir, "a/x.log", "log");
        Files.setLastModifiedTime(dir.resolve("b/.gitignore"), Files.getLastModifiedTime(dir.resolve("a/.gitignore")));
        Cmd.Result result = Cmd.run("diff", "-q", "-g", "-f", "jsonl", dir.resolve("a").toString(), right().toString());
        assertEquals(0, result.exit(), result.out());
        assertTrue(result.out().contains("\"status\":\"ignored\""), result.out());
    }

    @Test
    void diffGitignoreDoesNotSkipTheGitFolderButExcludeCan() throws Exception {
        Cmd.file(dir, "a/.git/HEAD", "ref");
        Cmd.file(dir, "a/keep.txt", "k");
        Files.createDirectories(right());
        Cmd.Result plain = Cmd.run("diff", "-q", "-g", "-f", "text", dir.resolve("a").toString(), right().toString());
        assertTrue(plain.out().contains(".git/HEAD"), plain.out());
        Cmd.Result excluded = Cmd.run("diff", "-q", "-g", "-x", ".git", "-f", "text", dir.resolve("a").toString(), right().toString());
        assertFalse(excluded.out().contains(".git"), excluded.out());
        assertTrue(excluded.out().contains("keep.txt"), excluded.out());
    }

    @Test
    void diffIgnoreFileTakesPatternsFromAnyFile() throws Exception {
        Cmd.file(dir, "a/x.tmp", "t");
        Cmd.file(dir, "a/keep.txt", "k");
        Files.createDirectories(right());
        Path rules = Cmd.file(dir, "my.rules", "*.tmp\n");
        Cmd.Result result = Cmd.run("diff", "-q", "--ignore-file", rules.toString(), "-f", "text",
                dir.resolve("a").toString(), right().toString());
        assertTrue(result.out().contains("keep.txt") && !result.out().contains("x.tmp"), result.out());
        Cmd.Result missing = Cmd.run("diff", "-q", "--ignore-file", dir.resolve("nope").toString(),
                dir.resolve("a").toString(), right().toString());
        assertEquals(2, missing.exit());
        assertEquals("", missing.out());
    }

    @Test
    void syncMirrorLeavesIgnoredFilesOnTheTargetAlone() throws Exception {
        Cmd.file(dir, "a/keep.txt", "k");
        Cmd.file(dir, "b/keep.txt", "k");
        Cmd.file(dir, "b/cache.tmp", "c");
        Cmd.file(dir, "b/extra.txt", "x");
        Files.setLastModifiedTime(dir.resolve("b/keep.txt"), Files.getLastModifiedTime(dir.resolve("a/keep.txt")));
        Path rules = Cmd.file(dir, "my.rules", "*.tmp\n");
        Cmd.Result result = Cmd.run("sync", "-q", "-y", "-m", "mirror-right", "--ignore-file", rules.toString(),
                dir.resolve("a").toString(), right().toString());
        assertEquals(0, result.exit(), result.err());
        assertFalse(Files.exists(dir.resolve("b/extra.txt")), "mirror still deletes what is not ignored");
        assertTrue(Files.exists(dir.resolve("b/cache.tmp")), "an ignored file is never deleted");
    }

    @Test
    void syncPlanListsIgnoredEntriesAsSkipsAndAPlanNeverActsOnThem() throws Exception {
        Cmd.file(dir, "a/keep.txt", "k");
        Cmd.file(dir, "b/cache.tmp", "c");
        Cmd.file(dir, "b/extra.txt", "x");
        Path rules = Cmd.file(dir, "my.rules", "*.tmp\n");
        Cmd.Result plan = Cmd.run("sync", "-q", "-n", "-m", "mirror-right", "--ignore-file", rules.toString(), "-f", "jsonl",
                dir.resolve("a").toString(), right().toString());
        assertEquals(0, plan.exit(), plan.err());
        assertTrue(plan.out().contains("\"action\":\"skip\",\"entry\":\"file\",\"path\":\"cache.tmp\",\"status\":\"ignored\""), plan.out());
        assertTrue(plan.out().contains("\"reason\":\"ignored\""), plan.out());
        assertTrue(plan.out().contains("\"ignored\":1"), plan.out());
        Cmd.Result text = Cmd.run("sync", "-q", "-n", "-m", "mirror-right", "--ignore-file", rules.toString(),
                dir.resolve("a").toString(), right().toString());
        assertFalse(text.out().contains("cache.tmp"), "text lists ignored entries on request only: " + text.out());
        assertTrue(Cmd.run("sync", "-q", "-n", "--show-ignored", "--ignore-file", rules.toString(),
                dir.resolve("a").toString(), right().toString()).out().contains("cache.tmp"));

        Cmd.Result diff = Cmd.run("diff", "-q", "--ignore-file", rules.toString(), "-f", "jsonl",
                dir.resolve("a").toString(), right().toString());
        Path file = dir.resolve("diff.jsonl");
        Files.writeString(file, diff.out());
        Cmd.Result applied = Cmd.run("sync", "-q", "-y", "-m", "mirror-right", "-p", file.toString(),
                dir.resolve("a").toString(), right().toString());
        assertEquals(0, applied.exit(), applied.err());
        assertTrue(Files.exists(dir.resolve("b/cache.tmp")), "an ignored line of a diff is never mirrored away");
        assertFalse(Files.exists(dir.resolve("b/extra.txt")));
        assertEquals("k", Files.readString(dir.resolve("b/keep.txt")));
    }

    @Test
    void aPlanLineCannotForceAnIgnoredEntry() throws Exception {
        Cmd.file(dir, "a/x.tmp", "t");
        Files.createDirectories(right());
        Path file = Cmd.file(dir, "plan.jsonl",
                "{\"action\":\"to-right\",\"path\":\"x.tmp\",\"status\":\"ignored\"}\n");
        Cmd.Result result = Cmd.run("sync", "-q", "-y", "-p", file.toString(), dir.resolve("a").toString(), right().toString());
        assertEquals(2, result.exit(), result.err());
        assertFalse(Files.exists(dir.resolve("b/x.tmp")));
    }

    @Test
    void syncRefusesIgnoreOptionsTogetherWithAPlan() throws Exception {
        Cmd.Result result = Cmd.run("sync", "-q", "-g", "-p", dir.resolve("plan.jsonl").toString(),
                dir.resolve("a").toString(), right().toString());
        assertEquals(2, result.exit());
        assertEquals("", result.out());
    }

    // ---- find -------------------------------------------------------------------------------------------------

    private Path zip(String name, String entry, String content) throws Exception {
        Path file = dir.resolve(name);
        try (java.io.OutputStream out = Files.newOutputStream(file);
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entry));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void findStreamsAbsolutePathsForSeveralPatternsAndSortOrdersThem() throws Exception {
        Cmd.file(dir, "b.ini", "x");
        Cmd.file(dir, "a.txt", "x");
        Cmd.file(dir, "sub/c.TXT", "x");
        Cmd.file(dir, "sub/d.log", "x");
        List<String> expected = List.of(dir.resolve("a.txt").toString(), dir.resolve("b.ini").toString(),
                dir.resolve("sub/c.TXT").toString());
        Cmd.Result result = Cmd.run("find", "-N", "*.txt,*.ini", dir.toString());
        assertEquals(0, result.exit(), result.err());
        assertEquals(expected, result.outLines().stream().sorted().toList(), "streamed: the order is not promised");
        assertEquals("", result.err(), "no terminal: no summary line");
        Cmd.Result sorted = Cmd.run("find", "-q", "--sort", "-N", "*.txt,*.ini", dir.toString());
        assertEquals(expected, sorted.outLines());
        Cmd.Result repeated = Cmd.run("find", "-q", "--sort", "-N", "*.txt", "-N", "*.ini", dir.toString());
        assertEquals(expected, repeated.outLines(), "the option repeats like the comma list");
    }

    @Test
    void findWithoutMatchesExitsOneAndTheFolderMustExist() throws Exception {
        Cmd.file(dir, "a.txt", "x");
        Cmd.Result none = Cmd.run("find", "-q", "-N", "*.zip", dir.toString());
        assertEquals(1, none.exit());
        assertEquals("", none.out());
        assertEquals(4, Cmd.run("find", "-q", dir.resolve("no-such-folder").toString()).exit());
        assertEquals(2, Cmd.run("find", "-q", dir.resolve("a.txt").toString()).exit(), "a file is not a folder");
        assertEquals(2, Cmd.run("find", "-q", "--depth", "0", dir.toString()).exit());
        assertEquals(2, Cmd.run("find", "-q", "-f", "xml", dir.toString()).exit());
    }

    @Test
    void findDepthAndLimit() throws Exception {
        Cmd.file(dir, "top.txt", "x");
        Cmd.file(dir, "one/a.txt", "x");
        Cmd.file(dir, "one/two/b.txt", "x");
        assertEquals(List.of(dir.resolve("top.txt").toString()),
                Cmd.run("find", "-q", "-N", "*.txt", "-d", "1", dir.toString()).outLines());
        assertEquals(2, Cmd.run("find", "-q", "-N", "*.txt", "-d", "2", dir.toString()).outLines().size());
        Cmd.Result limited = Cmd.run("find", "-q", "-N", "*.txt", "--limit", "1", "-f", "json", dir.toString());
        Map<?, ?> summary = (Map<?, ?>) limited.json().get("summary");
        assertEquals(1, ((Number) summary.get("matches")).intValue());
        assertEquals(Boolean.TRUE, summary.get("limited"));
        assertEquals(0, limited.exit());
    }

    @Test
    void findTextAndArchivesWithTheDocumentedJsonFields() throws Exception {
        Cmd.file(dir, "a.txt", "first\nthe Needle is here\n");
        Path archive = zip("pack.zip", "dir/in.txt", "needle too");
        Cmd.Result plain = Cmd.run("find", "-q", "-T", "needle", "-f", "json", dir.toString());
        Map<?, ?> json = plain.json();
        assertEquals(1, ((Number) json.get("schema")).intValue());
        assertEquals("find", json.get("command"));
        assertEquals("needle", json.get("text"));
        List<?> items = (List<?>) json.get("items");
        assertEquals(1, items.size(), "archives are only searched on request");
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("file", item.get("entry"));
        assertEquals(dir.resolve("a.txt").toString(), item.get("path"));
        assertEquals(null, item.get("archive"));
        assertEquals(2, ((Number) item.get("line")).intValue());
        assertEquals("the Needle is here", item.get("snippet"));
        assertTrue(item.containsKey("size") && item.containsKey("modified"));

        Cmd.Result both = Cmd.run("find", "-q", "-A", "-T", "needle", "-f", "jsonl", dir.toString());
        List<String> lines = both.outLines();
        assertEquals(3, lines.size(), "two items and the summary");
        assertEquals("summary", both.lastJson().get("kind"));
        Map<?, ?> inside = (Map<?, ?>) JsonReader.parse(lines.stream()
                .filter(line -> line.contains("pack.zip")).findFirst().orElseThrow());
        assertEquals(archive.toString(), inside.get("archive"));
        assertEquals(archive + "!/dir/in.txt", inside.get("path"));
    }

    @Test
    void findCsvAndTextFormatsMirrorTheJsonFields() throws Exception {
        Cmd.file(dir, "a.txt", "x, \"y\" needle\n");
        Cmd.Result nameOnly = Cmd.run("find", "-q", "-f", "csv", dir.toString());
        assertEquals("entry,path,archive,size,modified", nameOnly.outLines().get(0));
        Cmd.Result content = Cmd.run("find", "-q", "-f", "csv", "-T", "needle", dir.toString());
        assertEquals("entry,path,archive,size,modified,line,snippet", content.outLines().get(0));
        assertTrue(content.outLines().get(1).endsWith(",1,\"x, \"\"y\"\" needle\""), content.out());
        Cmd.Result text = Cmd.run("find", "-q", "-T", "needle", dir.toString());
        assertEquals(dir.resolve("a.txt") + ":1: x, \"y\" needle", text.outLines().get(0));
        Cmd.Result summary = Cmd.run("find", "-q", "-S", dir.toString());
        assertTrue(summary.out().startsWith("matches"), summary.out());
    }

    // ---- install-aliases --------------------------------------------------------------------------------------

    @Test
    void aliasesAreLinkedAndRemoved() throws Exception {
        Path exe = Cmd.file(dir, "bin/tuifileman.exe", "program");
        Cmd.Result made = Cmd.run("install-aliases", "-q", "--exe", exe.toString());
        assertEquals(0, made.exit(), made.err());
        for (String name : List.of("diff", "copy", "move", "delete", "sync", "rename")) {
            assertEquals("program", Files.readString(dir.resolve("bin/tfm-" + name + ".exe")));
        }
        assertEquals(0, Cmd.run("install-aliases", "-q", "--exe", exe.toString()).exit(), "a second run changes nothing");
        assertEquals(0, Cmd.run("install-aliases", "-q", "--remove", "--exe", exe.toString()).exit());
        assertFalse(Files.exists(dir.resolve("bin/tfm-diff.exe")));
        assertTrue(Files.exists(exe));
    }

    @Test
    void aFileOfAnotherKindIsNeitherReplacedNorRemoved() throws Exception {
        Path exe = Cmd.file(dir, "bin/tuifileman.exe", "program");
        Path other = Cmd.file(dir, "bin/tfm-diff.exe", "mine");
        assertEquals(3, Cmd.run("install-aliases", "-q", "--exe", exe.toString()).exit());
        assertEquals("mine", Files.readString(other));
        assertEquals(3, Cmd.run("install-aliases", "-q", "--remove", "--exe", exe.toString()).exit());
        assertEquals("mine", Files.readString(other));
        assertEquals(0, Cmd.run("install-aliases", "-q", "--force", "--exe", exe.toString()).exit());
        assertEquals("program", Files.readString(other));
    }

    // ---- time tolerance ---------------------------------------------------------------------------------------

    private void skewedPair() throws Exception {
        Path a = Cmd.file(dir, "a/t.txt", "same");
        Path b = Cmd.file(dir, "b/t.txt", "same");
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));
        Files.setLastModifiedTime(b, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L - 90_000));
    }

    @Test
    void diffTimeToleranceDecidesWhenTwoTimesCountAsEqual() throws Exception {
        skewedPair();
        String a = dir.resolve("a").toString();
        String b = dir.resolve("b").toString();
        assertEquals(1, Cmd.run("diff", "-q", a, b).exit(), "90 s apart is a difference by default (2 s)");
        assertEquals(1, Cmd.run("diff", "-q", "--time-tolerance", "1m", a, b).exit());
        assertEquals(1, Cmd.run("diff", "-q", "--time-tolerance", "2", a, b).exit(), "a bare number is seconds");
        assertEquals(0, Cmd.run("diff", "-q", "--time-tolerance", "2m", a, b).exit());
        assertEquals(0, Cmd.run("diff", "-q", "--time-tolerance=1h", a, b).exit());
    }

    @Test
    void zeroToleranceNeedsExactlyEqualTimes() throws Exception {
        skewedPair();
        long base = 1_700_000_000_000L;
        Files.setLastModifiedTime(dir.resolve("a/t.txt"), java.nio.file.attribute.FileTime.fromMillis(base + 1_000));
        Files.setLastModifiedTime(dir.resolve("b/t.txt"), java.nio.file.attribute.FileTime.fromMillis(base));
        assertEquals(0, Cmd.run("diff", "-q", dir.resolve("a").toString(), dir.resolve("b").toString()).exit());
        assertEquals(1, Cmd.run("diff", "-q", "--time-tolerance", "0", dir.resolve("a").toString(), dir.resolve("b").toString()).exit());
    }

    @Test
    void syncHonoursTheTimeTolerance() throws Exception {
        skewedPair();
        Cmd.Result strict = Cmd.run("sync", "-q", "-n", "-f", "jsonl", dir.resolve("a").toString(), dir.resolve("b").toString());
        assertTrue(strict.out().contains("\"action\":\"to-right\""), strict.out());
        Cmd.Result loose = Cmd.run("sync", "-q", "-n", "-f", "jsonl", "--time-tolerance", "2m", dir.resolve("a").toString(),
                dir.resolve("b").toString());
        assertFalse(loose.out().contains("\"action\":\"to-"), loose.out());
    }

    @Test
    void aBadToleranceIsAUsageError() throws Exception {
        skewedPair();
        for (String command : List.of("diff", "sync")) {
            Cmd.Result result = Cmd.run(command, "-q", "--time-tolerance", "5ms", dir.resolve("a").toString(), dir.resolve("b").toString());
            assertEquals(2, result.exit(), command);
            assertEquals("", result.out());
            assertTrue(result.err().contains("--time-tolerance needs a duration"), result.err());
        }
    }

    @Test
    void theIniFileSetsTheDefaultToleranceAndTheOptionWins() throws Exception {
        skewedPair();
        String a = dir.resolve("a").toString();
        String b = dir.resolve("b").toString();
        String ini = "[sync]\ntime_tolerance = 2m\n";
        assertEquals(0, Cmd.runWithIni(dir, ini, "diff", "-q", a, b).exit());
        assertEquals(1, Cmd.runWithIni(dir, ini, "diff", "-q", "--time-tolerance", "1m", a, b).exit());
        assertEquals(0, Cmd.runWithIni(dir, ini, "sync", "-q", "-n", a, b).exit());
    }

    @Test
    void anInvalidIniToleranceFallsBackToTwoSeconds() throws Exception {
        skewedPair();
        Cmd.Result result = Cmd.runWithIni(dir, "[sync]\ntime_tolerance = soon\n", "diff", "-q",
                dir.resolve("a").toString(), dir.resolve("b").toString());
        assertEquals(1, result.exit());
    }

    @Test
    void copyOnConflictNewerHonoursTheTolerance() throws Exception {
        Path source = Cmd.file(dir, "src/t.txt", "new");
        Path target = Cmd.file(dir, "dst/t.txt", "old");
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(1_700_000_030_000L));
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));
        String dst = dir.resolve("dst").toString();
        assertEquals(0, Cmd.run("copy", "-q", "-c", "newer", "--time-tolerance", "1m", source.toString(), dst).exit());
        assertEquals("old", Files.readString(target), "30 s newer is within 1m");
        assertEquals(0, Cmd.run("copy", "-q", "-c", "newer", source.toString(), dst).exit());
        assertEquals("new", Files.readString(target), "30 s newer is beyond the 2 s default");
    }

    @Test
    void copyAndMoveRejectABadTolerance() throws Exception {
        Path source = Cmd.file(dir, "src/t.txt", "x");
        for (String command : List.of("copy", "move")) {
            Cmd.Result result = Cmd.run(command, "-q", "--time-tolerance", "5ms", source.toString(), dir.resolve("dst").toString());
            assertEquals(2, result.exit(), command);
            assertTrue(result.err().contains("--time-tolerance needs a duration"), result.err());
        }
    }
}
