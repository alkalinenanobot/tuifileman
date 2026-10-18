package org.ds.tuifileman.fs;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

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
}
