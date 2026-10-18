package org.ds.tuifileman.model;

import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.fs.Entries;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class DirectoryListing {
    /** Unix: the attribute column shows the mode (rwxr-xr-x); Windows: the DOS flags (rhsa). */
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private DirectoryListing() {}

    /** Lists a directory: ".." first (unless root), then directories, then files, by name ignoring case. */
    public static List<FileEntry> list(Path directory) throws IOException {
        return list(directory, EntrySort.DEFAULT);
    }

    /** As above, in the given order. */
    public static List<FileEntry> list(Path directory, EntrySort sort) throws IOException {
        List<FileEntry> dirs = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
        Path normalized = directory.toAbsolutePath().normalize();
        boolean virtual = Archives.isVirtual(normalized);
        Path parent = normalized.getParent();
        if (parent == null && virtual) {
            // ".." of an archive's root leaves the archive: back to the folder that holds the archive file.
            Path origin = Archives.origin(normalized);
            parent = origin == null ? null : origin.getParent();
        }
        if (parent != null) {
            dirs.add(new FileEntry("..", parent, true, 0, null));
        }
        if (virtual || POSIX || !listFromEnumeration(normalized, dirs, files)) {
            try (Stream<Path> stream = Files.list(normalized)) {
                stream.forEach(path -> {
                    BasicFileAttributes own = null;
                    String attributes = "";
                    try {
                        if (virtual) {
                            own = archiveAttributes(path);
                            if (own instanceof PosixFileAttributes posix) {
                                attributes = PosixFilePermissions.toString(posix.permissions());
                            }
                        } else if (POSIX) {
                            PosixFileAttributes posix = Files.readAttributes(path, PosixFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                            attributes = PosixFilePermissions.toString(posix.permissions());
                            own = posix;
                        } else {
                            DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                            attributes = dosFlags(dos);
                            own = dos;
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // Entry stays listed with unknown attributes.
                    }
                    add(path, own, attributes, dirs, files);
                });
            }
        }
        dirs.addAll(files);
        return sort.apply(dirs);
    }

    /**
     * Windows: a depth-1 walk hands over the attributes the directory enumeration already returned
     * ({@code WindowsFileAttributes}, which are {@link DosFileAttributes}), so there is no file system call per
     * entry. Not used on Unix: there the walk yields basic attributes only, so a permission read would come on
     * top of the walk's own stat. Returns false when the walk did not open the directory: a symbolic-link
     * root is visited as a single file.
     */
    private static boolean listFromEnumeration(Path directory, List<FileEntry> dirs, List<FileEntry> files)
            throws IOException {
        boolean[] opened = {true};
        IOException[] failure = {null};
        Entries.list(directory, new Entries.Handler() {
            @Override
            public void entry(Path path, BasicFileAttributes attrs) {
                if (path.equals(directory)) {
                    opened[0] = false;
                    return;
                }
                String attributes = "";
                try {
                    DosFileAttributes dos = attrs instanceof DosFileAttributes d ? d
                            : Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    attributes = dosFlags(dos);
                } catch (IOException | RuntimeException ignored) {
                    // Type, size and date are still known; only the flag column stays empty.
                }
                add(path, attrs, attributes, dirs, files);
            }

            @Override
            public void failed(Path path, IOException error) {
                if (path.equals(directory)) {
                    failure[0] = error;
                } else {
                    add(path, null, "", dirs, files);
                }
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return opened[0];
    }

    /** Adds a row from the entry's own (link not followed) attributes; null when they are unknown. */
    private static void add(Path path, BasicFileAttributes own, String attributes,
            List<FileEntry> dirs, List<FileEntry> files) {
        boolean isDirectory = false;
        boolean link = false;
        long size = 0;
        FileTime modified = null;
        if (own != null) {
            // Symlinks and junctions: show the target's type, size and date.
            link = own.isSymbolicLink() || own.isOther();
            BasicFileAttributes shown = own;
            if (link) {
                try {
                    shown = Files.readAttributes(path, BasicFileAttributes.class);
                } catch (IOException | RuntimeException broken) {
                    // Dangling or denied target: keep the link's own values.
                }
            }
            isDirectory = shown.isDirectory();
            modified = shown.lastModifiedTime();
            size = isDirectory ? 0 : shown.size();
        }
        FileEntry entry = new FileEntry(path.getFileName().toString(), path,
                isDirectory, size, modified, link, attributes);
        (isDirectory ? dirs : files).add(entry);
    }

    private static String dosFlags(DosFileAttributes dos) {
        return FileEntry.attributeFlags(dos.isReadOnly(), dos.isHidden(), dos.isSystem(), dos.isArchive());
    }

    /** Archive entries carry Unix modes only when the archive has them. */
    private static BasicFileAttributes archiveAttributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, PosixFileAttributes.class);
        } catch (UnsupportedOperationException noModes) {
            return Files.readAttributes(path, BasicFileAttributes.class);
        }
    }
}
