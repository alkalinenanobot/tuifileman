package org.ds.tuifileman.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Lists one directory. Going through {@code walkFileTree} with depth 1 hands over the attributes the
 * directory enumeration already returned, so there is no extra file system call per entry (a stat per
 * entry made a cold scan several times slower).
 */
public final class Entries {
    public interface Handler {
        /** A file, link or directory below the listed directory; links are not followed. */
        void entry(Path path, BasicFileAttributes attrs);

        void failed(Path path, IOException error);
    }

    private Entries() { }

    private static final boolean WINDOWS = File.separatorChar == '\\';
    private static final boolean PLATFORM_IGNORES_CASE = WINDOWS
            || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    public static void list(Path dir, Handler handler) {
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    handler.entry(path, attrs);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException error) {
                    handler.failed(path, error);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException error) {
                    if (error != null) {
                        handler.failed(path, error);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            handler.failed(dir, e);
        }
    }

    /** A real directory that can be descended into: not a symbolic link and not a junction or mount point. */
    public static boolean isPlainDirectory(BasicFileAttributes attrs) {
        return attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther();
    }

    /** The text shown for a failure: its message, or the exception's simple class name when it has none. */
    public static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Whether names in {@code dir} that differ only in letter case are one file. Case sensitivity belongs to the
     * file system (and on Windows to each folder), not to the operating system. Looked up with an entry of the
     * folder (its nearest existing parent while it does not exist yet) spelled in the other case; a folder
     * without such an entry gets a short-lived probe file. When nothing tells, the default of Windows and macOS.
     */
    public static boolean ignoresCase(Path dir) {
        Path existing = dir.toAbsolutePath();
        while (existing != null && !Files.isDirectory(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return PLATFORM_IGNORES_CASE;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(existing)) {
            for (Path child : stream) {
                Boolean answer = oneFileInBothCases(child);
                if (answer != null) {
                    return answer;
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            // no answer from the entries: try the probe
        }
        Path probe = null;
        try {
            probe = Files.createTempFile(existing, ".tfm-case-", ".tmp");
            Boolean answer = oneFileInBothCases(probe);
            if (answer != null) {
                return answer;
            }
        } catch (IOException e) {
            // a read-only folder: the platform default
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // a temp file left behind is harmless
                }
            }
        }
        return PLATFORM_IGNORES_CASE;
    }

    /** Whether {@code path} is also found under its name in the other letter case; null when nothing tells. */
    private static Boolean oneFileInBothCases(Path path) {
        String name = path.getFileName().toString();
        String other = name.toUpperCase(Locale.ROOT);
        if (other.equals(name)) {
            other = name.toLowerCase(Locale.ROOT);
        }
        if (other.equals(name)) {
            return null;
        }
        Path alias = path.resolveSibling(other);
        try {
            if (!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            // The real path carries the stored spelling. A Windows path compares equal in any case, so there
            // only the spelling tells; elsewhere the two names may still be one file on a file system that does
            // not report the stored case.
            if (alias.toRealPath(LinkOption.NOFOLLOW_LINKS).getFileName().toString().equals(name)) {
                return true;
            }
            return !WINDOWS && Files.isSameFile(path, alias);
        } catch (IOException e) {
            return null;
        }
    }
}
