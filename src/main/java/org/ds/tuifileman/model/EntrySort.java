package org.ds.tuifileman.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The order of a panel listing: ".." first, then folders, then files, each group by the sort key. Extension and
 * size say nothing about a folder, so under them the folders keep name order (ascending). Ties are broken by
 * name, so equal sizes or extensions still list predictably.
 */
public record EntrySort(Key key, boolean descending) {
    public enum Key {
        NAME, EXTENSION, SIZE, MODIFIED, ATTRIBUTES;

        /** A first click shows what is usually wanted: A to Z, but the biggest and the newest first. */
        boolean startsDescending() {
            return this == SIZE || this == MODIFIED;
        }
    }

    public static final EntrySort DEFAULT = new EntrySort(Key.NAME, false);

    private static final Comparator<FileEntry> BY_NAME = Comparator
            .comparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(FileEntry::name);

    /** The same key again reverses the order; another key starts in that key's usual direction. */
    public EntrySort toggle(Key clicked) {
        return clicked == key ? new EntrySort(key, !descending) : new EntrySort(clicked, clicked.startsDescending());
    }

    /** The extension a sort by extension uses: after the last dot, none for ".gitignore" or "README". */
    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    public List<FileEntry> apply(List<FileEntry> entries) {
        List<FileEntry> parent = new ArrayList<>(1);
        List<FileEntry> folders = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
        for (FileEntry entry : entries) {
            (entry.name().equals("..") ? parent : entry.directory() ? folders : files).add(entry);
        }
        boolean foldersByKey = key == Key.NAME || key == Key.MODIFIED || key == Key.ATTRIBUTES;
        folders.sort(foldersByKey ? comparator() : BY_NAME);
        files.sort(comparator());
        parent.addAll(folders);
        parent.addAll(files);
        return parent;
    }

    private Comparator<FileEntry> comparator() {
        Comparator<FileEntry> primary = switch (key) {
            case NAME -> BY_NAME;
            case EXTENSION -> Comparator.comparing((FileEntry e) -> extension(e.name()).toLowerCase(Locale.ROOT));
            case SIZE -> Comparator.comparingLong(FileEntry::size);
            case MODIFIED -> Comparator.comparing(FileEntry::modified, Comparator.nullsFirst(Comparator.naturalOrder()));
            case ATTRIBUTES -> Comparator.comparing(FileEntry::attributes);
        };
        if (descending) {
            primary = primary.reversed();
        }
        return key == Key.NAME ? primary : primary.thenComparing(BY_NAME);
    }
}
