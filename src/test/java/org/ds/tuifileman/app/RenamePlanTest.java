package org.ds.tuifileman.app;

import static org.ds.tuifileman.Fixtures.virtual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ds.tuifileman.app.RenamePlan.CaseMode;
import org.ds.tuifileman.app.RenamePlan.CaseScope;
import org.ds.tuifileman.app.RenamePlan.Item;
import org.ds.tuifileman.app.RenamePlan.Settings;
import org.ds.tuifileman.model.FileEntry;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenamePlanTest {
    @TempDir
    Path folder;

    private FileEntry file(String name) {
        return virtual(folder, name, false);
    }

    private List<String> names(List<FileEntry> batch, Settings settings) {
        return RenamePlan.create(batch, Set.of(), settings).items().stream().map(Item::newName).toList();
    }

    private List<String> names(Settings settings, String... files) {
        return names(java.util.Arrays.stream(files).map(this::file).toList(), settings);
    }

    private static Settings mask(String name, String extension) {
        return new Settings(name, extension, 1, 1, 1);
    }

    @Test
    void identityMaskChangesNothing() {
        RenamePlan plan = RenamePlan.create(List.of(file("a.txt"), file("b")), Set.of("a.txt", "b"), mask("[N]", "[E]"));
        assertEquals(0, plan.changedCount());
        assertEquals(0, plan.errorCount());
    }

    @Test
    void nameAndExtensionSplitAtTheLastDot() {
        assertEquals(List.of("a.tar-gz.bak"), names(mask("[N]-[E]", "bak"), "a.tar.gz"));
    }

    @Test
    void dotFilesAndFoldersHaveNoExtension() {
        assertEquals(List.of(".gitignore.x"), names(mask("[N]", "x"), ".gitignore"));
        RenamePlan plan = RenamePlan.create(List.of(virtual(folder, "v1.2", true)), Set.of(), mask("[N]-[E]", ""));
        assertEquals("v1.2-", plan.items().get(0).newName(), "a folder name is never split");
    }

    @Test
    void characterRangesAreOneBased() {
        assertEquals(List.of("bc"), names(mask("[N2-3]", ""), "abcdef"));
        assertEquals(List.of("cdef"), names(mask("[N3-]", ""), "abcdef"));
        assertEquals(List.of("b"), names(mask("[N2]", ""), "abcdef"));
        assertEquals(List.of("abcdef"), names(mask("[N1-99]", ""), "abcdef"));
        assertEquals(List.of(""), names(mask("[N9-]", ""), "abc"));
        assertEquals(List.of("tx"), names(mask("[E1-2]", ""), "a.txt"));
    }

    @Test
    void counterUsesStartStepAndDigits() {
        Settings settings = new Settings("img[C]", "[E]", 8, 2, 3);
        assertEquals(List.of("img008.jpg", "img010.jpg", "img012.jpg"), names(settings, "a.jpg", "b.jpg", "c.jpg"));
    }

    @Test
    void negativeCounterKeepsTheSignInFront() {
        Settings settings = new Settings("n[C]", "[E]", -1, 1, 2);
        assertEquals(List.of("n-01.t", "n00.t", "n01.t"), names(settings, "a.t", "b.t", "c.t"));
    }

    @Test
    void parentFolderToken() {
        assertEquals(List.of(folder.getFileName() + "_a.txt"), names(mask("[P]_[N]", "[E]"), "a.txt"));
    }

    @Test
    void dateTokensUseTheModifiedTime() {
        LocalDateTime time = LocalDateTime.of(2023, 4, 5, 6, 7, 8);
        FileTime modified = FileTime.from(time.atZone(ZoneId.systemDefault()).toInstant());
        FileEntry entry = new FileEntry("x.txt", folder.resolve("x.txt"), false, 0, modified);
        assertEquals(List.of("2023-04-05 06.07.08.txt"),
                names(List.of(entry), mask("[Y]-[M]-[D] [h].[m].[s]", "[E]")));
        assertEquals(List.of("20230405_060708.txt"), names(List.of(entry), mask("[YMD]_[hms]", "[E]")));
    }

    @Test
    void searchAndReplaceWithWildcards() {
        assertEquals("a-b.txt", RenamePlan.replaceWildcards("a b.txt", " ", "-", false));
        assertEquals("X", RenamePlan.replaceWildcards("IMG_001.txt", "IMG_*", "X", false), "a trailing * runs to the end");
        assertEquals("a-X.txt", RenamePlan.replaceWildcards("a-IMG_1.txt", "IMG_?", "X", false));
        assertEquals("Xc", RenamePlan.replaceWildcards("abc", "?b", "X", false));
        assertEquals("ABC", RenamePlan.replaceWildcards("ABC", "b", "x", true), "match case");
        assertEquals("AxC", RenamePlan.replaceWildcards("ABC", "b", "x", false));
        assertEquals("abc", RenamePlan.replaceWildcards("abc", "", "x", false), "no search, no change");
    }

    @Test
    void searchAndReplaceRunsInTheSettings() {
        Settings settings = new Settings("[N]", "[E]", 1, 1, 1, "_", " ", false, CaseMode.UNCHANGED, CaseScope.BOTH);
        assertEquals(List.of("my file.txt"), names(settings, "my_file.txt"));
    }

    @Test
    void caseModesAndScopes() {
        assertEquals("my file.txt", RenamePlan.changeCase("My File.TXT", false, CaseMode.LOWER, CaseScope.BOTH));
        assertEquals("MY FILE.TXT", RenamePlan.changeCase("My File.txt", false, CaseMode.UPPER, CaseScope.BOTH));
        assertEquals("MY FILE.txt", RenamePlan.changeCase("My File.txt", false, CaseMode.UPPER, CaseScope.NAME));
        assertEquals("My File.TXT", RenamePlan.changeCase("My File.txt", false, CaseMode.UPPER, CaseScope.EXTENSION));
        assertEquals("My file.txt", RenamePlan.changeCase("MY FILE.txt", false, CaseMode.FIRST, CaseScope.NAME));
        assertEquals("My_Big-File (Old).txt",
                RenamePlan.changeCase("my_big-file (old).txt", false, CaseMode.WORDS, CaseScope.NAME));
        assertEquals("abc.d", RenamePlan.changeCase("abc.d", false, CaseMode.UNCHANGED, CaseScope.BOTH));
    }

    @Test
    void caseOfAFolderTouchesTheWholeName() {
        assertEquals("V1.2", RenamePlan.changeCase("v1.2", true, CaseMode.UPPER, CaseScope.NAME));
        assertEquals("v1.2", RenamePlan.changeCase("v1.2", true, CaseMode.UPPER, CaseScope.EXTENSION));
    }

    @Test
    void invalidNamesAreReported() {
        assertNull(RenamePlan.validate("fine.txt"));
        assertEquals("Name cannot be empty", RenamePlan.validate(" "));
        assertEquals("Name cannot contain ?", RenamePlan.validate("a?b"));
        assertEquals("Name cannot end with a space or dot", RenamePlan.validate("a."));
        assertEquals("Reserved Windows name", RenamePlan.validate("con"));
        assertEquals("Reserved Windows name", RenamePlan.validate("NUL.txt"));
        assertEquals("Name too long", RenamePlan.validate("a".repeat(256)));
        assertNull(RenamePlan.validate("a".repeat(255)));
    }

    @Test
    void twoItemsWithTheSameNewNameAreBothInError() {
        RenamePlan plan = RenamePlan.create(List.of(file("a.txt"), file("b.txt")), Set.of("a.txt", "b.txt"),
                mask("same", "[E]"));
        assertEquals(2, plan.errorCount());
        assertEquals("Same new name as another item", plan.items().get(0).error());
    }

    @Test
    void clashesAreCaseInsensitive() {
        RenamePlan plan = RenamePlan.create(List.of(file("a.txt"), file("b.txt")), Set.of("a.txt", "b.txt"),
                new Settings("[N]", "[E]", 1, 1, 1, "", "", false, CaseMode.UNCHANGED, CaseScope.BOTH));
        assertEquals(0, plan.errorCount());
        RenamePlan clash = RenamePlan.create(List.of(file("A.txt"), file("b.txt")), Set.of("A.txt", "b.txt"),
                new Settings("x", "txt", 1, 1, 1));
        assertEquals(2, clash.errorCount());
        RenamePlan mixed = RenamePlan.create(List.of(file("a.txt"), file("B.txt")), Set.of("a.txt", "B.txt"),
                new Settings("[N]", "[E]", 1, 1, 1, "", "", false, CaseMode.UPPER, CaseScope.NAME));
        assertEquals(0, mixed.errorCount(), "A.txt and B.txt are different");
    }

    @Test
    void aNameTakenByAFileOutsideTheBatchIsAnError() {
        RenamePlan plan = RenamePlan.create(List.of(file("a.txt")), Set.of("a.txt", "Other.TXT"), mask("other", "txt"));
        assertEquals("A file with this name already exists", plan.items().get(0).error());
    }

    @Test
    void renamingIntoAnotherBatchMembersOldNameIsFine() {
        // a shifts to b's old name while b moves on: the Renamer sorts out the order
        RenamePlan plan = RenamePlan.create(List.of(file("1.txt"), file("2.txt")), Set.of("1.txt", "2.txt"),
                new Settings("[C]", "[E]", 2, 1, 1));
        assertEquals(0, plan.errorCount());
        assertEquals(List.of("2.txt", "3.txt"), plan.items().stream().map(Item::newName).toList());
        assertTrue(plan.items().stream().allMatch(Item::changed));
    }
}
