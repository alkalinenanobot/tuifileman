package org.ds.tuifileman;

import org.ds.tuifileman.model.FileEntry;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Small helpers to build folder trees for the engine tests and to compare them afterwards. */
public final class Fixtures {
    /** A fixed point in time, so that "newer" and "older" are explicit in the tests. */
    public static final long BASE = 1_700_000_000_000L;

    private Fixtures() { }

    /** Writes a file (creating folders) and sets its modified time to {@code BASE + offsetMillis}. */
    public static Path file(Path root, String relative, String content, long offsetMillis) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        Files.setLastModifiedTime(path, FileTime.fromMillis(BASE + offsetMillis));
        return path;
    }

    public static Path file(Path root, String relative, String content) throws IOException {
        return file(root, relative, content, 0);
    }

    public static Path dir(Path root, String relative) throws IOException {
        return Files.createDirectories(root.resolve(relative));
    }

    public static long modified(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis();
    }

    /** A link to a folder that needs no privileges: a junction on Windows, a symbolic link elsewhere. */
    public static Path directoryLink(Path link, Path target) throws IOException, InterruptedException {
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            return Files.createSymbolicLink(link, target);
        }
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(mklink.getInputStream().readAllBytes());
        if (mklink.waitFor() != 0) {
            throw new IOException("mklink /J failed: " + output);
        }
        return link;
    }

    /**
     * Makes a folder unlistable for the current user until the returned handle is closed: mode 000 on POSIX, a
     * deny-list ACL entry on Windows. Returns null when the user can read it anyway (root).
     */
    public static AutoCloseable unreadable(Path dir) throws IOException {
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            var before = Files.getPosixFilePermissions(dir);
            Files.setPosixFilePermissions(dir, java.util.Set.of());
            AutoCloseable restore = () -> Files.setPosixFilePermissions(dir, before);
            if (Files.isReadable(dir)) {
                try {
                    restore.close();
                } catch (Exception e) {
                    throw new IOException(e);
                }
                return null;
            }
            return restore;
        }
        var view = Files.getFileAttributeView(dir, java.nio.file.attribute.AclFileAttributeView.class);
        var before = view.getAcl();
        var deny = java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.DENY)
                .setPrincipal(dir.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name")))
                .setPermissions(java.nio.file.attribute.AclEntryPermission.LIST_DIRECTORY)
                .build();
        java.util.List<java.nio.file.attribute.AclEntry> acl = new java.util.ArrayList<>(before);
        acl.add(0, deny);
        view.setAcl(acl);
        return () -> view.setAcl(before);
    }

    /**
     * Every file and folder below {@code root}: relative path (forward slashes) to content, "<dir>" for folders,
     * "<link>" for a symbolic link or junction, which is not followed.
     */
    public static Map<String, String> tree(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                boolean link = attrs.isOther();
                result.put(name(dir), link ? "<link>" : "<dir>");
                return link ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                result.put(name(file), attrs.isSymbolicLink() || attrs.isOther() ? "<link>" : Files.readString(file));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                // a junction whose folder is gone cannot be opened (the walker opens folders before asking)
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isOther()) {
                    throw error;
                }
                result.put(name(file), "<link>");
                return FileVisitResult.CONTINUE;
            }

            private String name(Path path) {
                return root.relativize(path).toString().replace('\\', '/');
            }
        });
        return result;
    }

    public static FileEntry entry(Path path) throws IOException {
        boolean directory = Files.isDirectory(path);
        return new FileEntry(path.getFileName().toString(), path, directory, directory ? 0 : Files.size(path),
                Files.getLastModifiedTime(path));
    }

    public static List<FileEntry> entries(Path... paths) throws IOException {
        java.util.ArrayList<FileEntry> list = new java.util.ArrayList<>();
        for (Path path : paths) {
            list.add(entry(path));
        }
        return list;
    }

    /** A virtual entry (no file behind it) for the pure name logic. */
    public static FileEntry virtual(Path folder, String name, boolean directory) {
        return new FileEntry(name, folder.resolve(name), directory, 0, FileTime.fromMillis(BASE));
    }
}
