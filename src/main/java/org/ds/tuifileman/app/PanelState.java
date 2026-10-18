package org.ds.tuifileman.app;

import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.model.DirectoryListing;
import org.ds.tuifileman.model.EntrySort;
import org.ds.tuifileman.model.FileEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Browsable state of one file panel: current directory, entries, cursor, scroll offset. */
public final class PanelState {
    private Path directory;
    private List<FileEntry> entries = List.of();
    private final Set<Path> marked = new LinkedHashSet<>();
    private int cursor;
    private int first;
    private int lastVisibleRows;
    private long freeSpace = -1;
    private String volumeLabel = "";
    private String listError;
    private String searchText = "";
    private boolean searchMatched = true;
    private EntrySort sort = EntrySort.DEFAULT;

    public PanelState(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    public List<FileEntry> entries() {
        return entries;
    }

    public int cursor() {
        return cursor;
    }

    public int first() {
        return first;
    }

    public long freeSpace() {
        return freeSpace;
    }

    /** Why the directory could not be listed, or null. */
    public String listError() {
        return listError;
    }

    /** Label of the volume the directory is on (empty when unknown or unlabeled). */
    public String volumeLabel() {
        return volumeLabel;
    }

    /** The order of the listing; it stays when the panel moves to another directory. */
    public EntrySort sort() {
        return sort;
    }

    /**
     * Sorts by {@code key}, or reverses the order when it already is the key. The cursor stays on its entry and
     * marks are kept.
     */
    public void sortBy(EntrySort.Key key, int visibleRows) {
        FileEntry current = current();
        sort = sort.toggle(key);
        entries = sort.apply(entries);
        if (current != null) {
            cursorTo(entries.indexOf(current), visibleRows);
        }
    }

    /** Switches to another directory (e.g. a drive root). Returns an error or null. */
    public String goTo(Path target) {
        Path previous = directory;
        directory = target.toAbsolutePath().normalize();
        cursor = 0;
        first = 0;
        String error = refresh();
        if (error != null) {
            directory = previous;
            refresh();
            return "Cannot open " + target + ": " + error;
        }
        leave(previous);
        return null;
    }

    /** True while the panel shows the inside of an archive (read-only). */
    public boolean isVirtual() {
        return Archives.isVirtual(directory);
    }

    /** The directory as shown in the panel title: "C:\data\a.zip!/dir" inside an archive. */
    public String displayPath() {
        return Archives.display(directory);
    }

    /** Opens an archive file as a folder of this panel. Returns an error or null. */
    public String openArchive(Path file) {
        String name = String.valueOf(file.getFileName());
        Path root;
        try {
            root = Archives.open(file);
        } catch (IOException | RuntimeException e) {
            return "Cannot open " + name + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        Path previous = directory;
        directory = root;
        cursor = 0;
        first = 0;
        String error = refresh();
        if (error != null) {
            directory = previous;
            refresh();
            Archives.release(root);
            return "Cannot open " + name + ": " + error;
        }
        return null;
    }

    /** Drops the archive reference when the panel has moved out of an archive (or to another one). */
    private void leave(Path previous) {
        if (Archives.isVirtual(previous) && previous.getFileSystem() != directory.getFileSystem()) {
            Archives.release(previous);
        }
    }

    /** Where the panel goes when the current directory is an archive's root, or null. */
    private Path leaveArchive() {
        Path origin = Archives.origin(directory);
        return origin == null ? null : origin.getParent();
    }

    public FileEntry current() {
        return cursor >= 0 && cursor < entries.size() ? entries.get(cursor) : null;
    }

    public boolean isMarked(FileEntry entry) {
        return marked.contains(entry.path());
    }

    public int markedCount() {
        return marked.size();
    }

    public void clearMarks() {
        marked.clear();
    }

    public long markedSize() {
        long total = 0;
        for (FileEntry entry : entries) {
            if (!entry.directory() && marked.contains(entry.path())) {
                total += entry.size();
            }
        }
        return total;
    }

    /** Toggles the mark on the current entry (not ".."), optionally moving the cursor down. */
    public void toggleMark(int visibleRows, boolean advance) {
        FileEntry entry = current();
        if (entry == null || "..".equals(entry.name())) {
            return;
        }
        if (!marked.remove(entry.path())) {
            marked.add(entry.path());
        }
        if (advance) {
            moveCursor(1, visibleRows);
        }
    }

    /** Marked entries, or the entry under the cursor when nothing is marked. */
    public List<FileEntry> targets() {
        List<FileEntry> result = new ArrayList<>();
        for (FileEntry entry : entries) {
            if (marked.contains(entry.path())) {
                result.add(entry);
            }
        }
        FileEntry entry = current();
        if (result.isEmpty() && entry != null && !"..".equals(entry.name())) {
            result.add(entry);
        }
        return result;
    }

    /** Marked entries, or every entry of the directory (without "..") when nothing is marked. */
    public List<FileEntry> markedOrAll() {
        List<FileEntry> result = new ArrayList<>();
        for (FileEntry entry : entries) {
            if (!"..".equals(entry.name()) && (marked.isEmpty() || marked.contains(entry.path()))) {
                result.add(entry);
            }
        }
        return result;
    }

    /** Reloads the listing. Returns an error message, or null on success. */
    public String refresh() {
        listError = null;
        try {
            entries = DirectoryListing.list(directory, sort);
        } catch (java.nio.file.AccessDeniedException e) {
            // A junction/link with a "deny list" ACL (e.g. "Documents and Settings"): follow it to
            // its real target like Total Commander does.
            Path real = realPathOrNull(directory);
            if (real != null && !real.equals(directory)) {
                directory = real;
                return refresh();
            }
            Path parent = directory.getParent();
            entries = parent == null ? List.of() : List.of(new FileEntry("..", parent, true, 0, null));
            listError = "Access denied - this directory cannot be listed";
        } catch (IOException e) {
            entries = List.of();
            marked.clear();
            cursor = 0;
            first = 0;
            freeSpace = -1;
            if (e instanceof java.nio.file.NoSuchFileException) {
                return "Directory no longer exists";
            }
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        Set<Path> present = new LinkedHashSet<>();
        for (FileEntry entry : entries) {
            present.add(entry.path());
        }
        marked.retainAll(present);
        cursor = Math.min(cursor, Math.max(0, entries.size() - 1));
        if (isVirtual()) {
            freeSpace = -1;
            volumeLabel = "";
            return null;
        }
        try {
            java.nio.file.FileStore store = Files.getFileStore(directory);
            freeSpace = store.getUsableSpace();
            volumeLabel = store.name() == null ? "" : store.name();
        } catch (IOException ignored) {
            freeSpace = -1;
            volumeLabel = "";
        }
        return null;
    }

    private static Path realPathOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    public void moveCursor(int delta, int visibleRows) {
        cursorTo(cursor + delta, visibleRows);
    }

    /** Quick search text typed so far (empty when not searching). */
    public String searchText() {
        return searchText;
    }

    public boolean searching() {
        return !searchText.isEmpty();
    }

    /** True when the current search text matches at least one entry. */
    public boolean searchMatched() {
        return searchMatched;
    }

    public void searchClear() {
        searchText = "";
        searchMatched = true;
    }

    public void searchAppend(char c, int visibleRows) {
        searchText += c;
        searchFrom(0, visibleRows);
    }

    public void searchBackspace(int visibleRows) {
        searchText = searchText.substring(0, searchText.length() - 1);
        if (searchText.isEmpty()) {
            searchMatched = true;
        } else {
            searchFrom(0, visibleRows);
        }
    }

    /** Jumps to the next (+1) or previous (-1) entry matching the search text. */
    public void searchStep(int direction, int visibleRows) {
        FileEntry current = current();
        boolean prefixOnly = current != null && searchMatches(current, true);
        for (int i = cursor + direction; i >= 0 && i < entries.size(); i += direction) {
            if (searchMatches(entries.get(i), prefixOnly)) {
                cursorTo(i, visibleRows);
                return;
            }
        }
    }

    /** Names starting with the text win; otherwise any name containing it. */
    private void searchFrom(int start, int visibleRows) {
        for (boolean prefixOnly : new boolean[] {true, false}) {
            for (int i = start; i < entries.size(); i++) {
                if (searchMatches(entries.get(i), prefixOnly)) {
                    searchMatched = true;
                    cursorTo(i, visibleRows);
                    return;
                }
            }
        }
        searchMatched = false;
    }

    private boolean searchMatches(FileEntry entry, boolean prefixOnly) {
        if ("..".equals(entry.name())) {
            return false;
        }
        String name = entry.name().toLowerCase(java.util.Locale.ROOT);
        String text = searchText.toLowerCase(java.util.Locale.ROOT);
        return prefixOnly ? name.startsWith(text) : name.contains(text);
    }

    public void cursorTo(int index, int visibleRows) {
        cursor = Math.max(0, Math.min(entries.size() - 1, index));
        ensureVisible(visibleRows);
    }

    public void ensureVisible(int visibleRows) {
        if (visibleRows < 1) {
            return;
        }
        lastVisibleRows = visibleRows;
        if (cursor < first) {
            first = cursor;
        } else if (cursor >= first + visibleRows) {
            first = cursor - visibleRows + 1;
        }
    }

    /** Enters the selected directory (".." goes to parent). Returns an error or null. */
    public String enter() {
        FileEntry entry = current();
        if (entry == null) {
            return "Empty directory";
        }
        if (!entry.directory()) {
            return entry.name() + " is not a directory";
        }
        Path previous = directory;
        directory = entry.path().toAbsolutePath().normalize();
        cursor = 0;
        first = 0;
        String error = refresh();
        if (error != null) {
            directory = previous;
            refresh();
            return "Cannot open " + entry.name() + ": " + error;
        }
        if ("..".equals(entry.name())) {
            Path origin = Archives.isVirtual(previous) && previous.getParent() == null
                    ? Archives.origin(previous) : null;
            leave(previous);
            selectPath(origin != null ? origin : previous);
        } else {
            leave(previous);
        }
        return null;
    }

    /** Moves to the parent directory, keeping the just-left directory selected. */
    public String goUp() {
        Path parent = directory.getParent();
        Path origin = null;
        if (parent == null && isVirtual()) {
            origin = Archives.origin(directory);
            parent = leaveArchive();
        }
        if (parent == null) {
            return null;
        }
        Path previous = directory;
        directory = parent;
        cursor = 0;
        first = 0;
        String error = refresh();
        if (error != null) {
            directory = previous;
            refresh();
            return "Cannot open parent: " + error;
        }
        leave(previous);
        selectPath(origin != null ? origin : previous);
        return null;
    }

    /** Puts the cursor on the entry with the given name, if present. */
    public void selectName(String name, int visibleRows) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equals(name)) {
                cursorTo(i, visibleRows);
                return;
            }
        }
    }

    /**
     * Shows a search hit: the folder it is in (inside the archive, for an archive hit) with the cursor on it.
     * Returns an error or null.
     *
     * @param archive the archive file, or null for a file of the real file system
     */
    public String reveal(String archive, String folder, String name, int visibleRows) {
        String error;
        if (archive == null) {
            error = goTo(Path.of(folder));
        } else {
            Path file = Path.of(archive);
            error = goTo(file.getParent());
            if (error == null) {
                error = openArchive(file);
            }
            if (error == null && !"/".equals(folder)) {
                error = goTo(directory.getFileSystem().getPath(folder));
            }
        }
        if (error == null) {
            selectName(name, visibleRows);
        }
        return error;
    }

    /** Puts the cursor on a path (after going up) and scrolls so that it is in view, roughly in the middle. */
    private void selectPath(Path path) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).path().equals(path)) {
                cursor = i;
                if (lastVisibleRows > 0 && cursor >= lastVisibleRows) {
                    first = Math.max(0, Math.min(cursor - lastVisibleRows / 2, entries.size() - lastVisibleRows));
                }
                return;
            }
        }
    }
}
