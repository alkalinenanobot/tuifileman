package org.ds.tuifileman.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Zip-like archives as read-only folders. An opened archive is a {@link FileSystem} of the JDK zip provider,
 * so its entries are ordinary {@link Path}s of another file system ({@link #isVirtual}) and listing, viewing and
 * copying out reuse the NIO code. Opens are counted: both panels may show the same archive.
 */
public final class Archives {
    private static final class Open {
        final FileSystem system;
        final Path origin;
        final Path file;
        int references = 1;

        Open(FileSystem system, Path origin, Path file) {
            this.system = system;
            this.origin = origin;
            this.file = file;
        }
    }

    private static final Map<Path, Open> BY_FILE = new HashMap<>();
    private static final Map<FileSystem, Open> BY_SYSTEM = new HashMap<>();
    private static Path tempRoot;

    private Archives() { }

    /** True for paths inside an opened archive (not the default file system). */
    public static boolean isVirtual(Path path) {
        return path.getFileSystem() != FileSystems.getDefault();
    }

    /**
     * Opens an archive file (or takes another reference to it) and returns the root of its entries.
     * An archive inside another archive is extracted to a temporary file first.
     */
    public static synchronized Path open(Path archive) throws IOException {
        Path file = isVirtual(archive) ? extractKept(archive) : archive.toRealPath();
        Open open = BY_FILE.get(file);
        if (open == null) {
            open = new Open(newSystem(file), archive, file);
            BY_FILE.put(file, open);
            BY_SYSTEM.put(open.system, open);
        } else {
            open.references++;
        }
        return open.system.getRootDirectories().iterator().next();
    }

    private static FileSystem newSystem(Path file) throws IOException {
        // Unix modes of the entries when the archive has them; names that are not valid UTF-8 fall back to Latin-1.
        Map<String, Object> env = new HashMap<>();
        env.put("enablePosixFileAttributes", "true");
        try {
            return FileSystems.newFileSystem(file, env);
        } catch (IOException | RuntimeException first) {
            env.put("encoding", StandardCharsets.ISO_8859_1.name());
            try {
                return FileSystems.newFileSystem(file, env);
            } catch (IOException | RuntimeException second) {
                throw new IOException("Not a readable zip archive", first);
            }
        }
    }

    /** Gives up one reference to the archive that this virtual path belongs to; the last one closes it. */
    public static synchronized void release(Path virtual) {
        Open open = BY_SYSTEM.get(virtual.getFileSystem());
        if (open == null || --open.references > 0) {
            return;
        }
        BY_SYSTEM.remove(open.system);
        BY_FILE.remove(open.file);
        try {
            open.system.close();
        } catch (IOException ignored) {
            // The archive is read-only: nothing is lost when closing fails.
        }
    }

    public static synchronized void closeAll() {
        for (Open open : BY_SYSTEM.values().toArray(new Open[0])) {
            try {
                open.system.close();
            } catch (IOException ignored) { }
        }
        BY_SYSTEM.clear();
        BY_FILE.clear();
    }

    /** The archive file as the user saw it (a real file, or an entry of an outer archive). */
    public static synchronized Path origin(Path virtual) {
        Open open = BY_SYSTEM.get(virtual.getFileSystem());
        return open == null ? null : open.origin;
    }

    /** The nearest real directory: the path itself, or the folder that holds the (outermost) archive file. */
    public static Path realDirectory(Path path) {
        Path current = path;
        while (isVirtual(current)) {
            Path origin = origin(current);
            if (origin == null || origin.getParent() == null) {
                return Path.of("").toAbsolutePath();
            }
            current = origin.getParent();
        }
        return current;
    }

    /** "C:\data\a.zip!/dir" for a path inside an archive, the plain path otherwise. */
    public static String display(Path path) {
        if (!isVirtual(path)) {
            return path.toString();
        }
        Path origin = origin(path);
        return (origin == null ? "archive" : display(origin)) + "!" + path;
    }

    /** A file of the real file system with the content of {@code path}, deleted again by {@link Extracted#close}. */
    public static Extracted extract(Path path) throws IOException {
        if (!isVirtual(path)) {
            return new Extracted(path, null);
        }
        Path dir = Files.createTempDirectory(tempRoot(), "x");
        Path target = dir.resolve(path.getFileName().toString());
        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
        return new Extracted(target, dir);
    }

    /** Like {@link #extract} but the file stays (an external program may still use it) until the program exits. */
    public static Path extractKept(Path path) throws IOException {
        if (!isVirtual(path)) {
            return path;
        }
        Extracted extracted = extract(path);
        return extracted.path();
    }

    public record Extracted(Path path, Path directory) implements AutoCloseable {
        @Override
        public void close() {
            if (directory != null) {
                deleteTree(directory);
            }
        }
    }

    private static synchronized Path tempRoot() throws IOException {
        if (tempRoot == null) {
            tempRoot = Files.createTempDirectory("tuifileman-archive-");
            Path root = tempRoot;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(root)));
        }
        return tempRoot;
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
