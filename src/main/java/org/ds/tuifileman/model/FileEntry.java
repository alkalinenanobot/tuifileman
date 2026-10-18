package org.ds.tuifileman.model;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One directory entry. {@code link} marks symlinks and junctions (type/size/date are the target's);
 * {@code attributes} is the four-character flag string from {@link #attributeFlags}.
 */
public record FileEntry(String name, Path path, boolean directory, long size, FileTime modified,
        boolean link, String attributes) {
    private static final DateTimeFormatter MODIFIED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public FileEntry(String name, Path path, boolean directory, long size, FileTime modified) {
        this(name, path, directory, size, modified, false, "");
    }

    /** Read-only, hidden, system, archive as "rhsa" with '-' for unset flags. */
    public static String attributeFlags(boolean readOnly, boolean hidden, boolean system, boolean archive) {
        return (readOnly ? "r" : "-") + (hidden ? "h" : "-") + (system ? "s" : "-") + (archive ? "a" : "-");
    }

    public String displaySize() {
        return link ? "<LNK>" : directory ? "<DIR>" : humanSize(size);
    }

    public String displayModified() {
        return modified == null ? "" : MODIFIED_FORMAT.format(modified.toInstant());
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }
}
