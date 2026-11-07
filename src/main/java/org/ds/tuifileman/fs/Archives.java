package org.ds.tuifileman.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Zip-like archives as read-only folders. An opened archive is a {@link FileSystem} of the JDK zip provider,
 * so its entries are ordinary {@link Path}s of another file system ({@link #isVirtual}) and listing, viewing and
 * copying out reuse the NIO code. Opens are counted: both panels may show the same archive.
 */
public final class Archives {
    /**
     * The real archive file plus the chain of entry paths that leads from it to an inner archive (empty for an
     * archive that is a real file), whose temporary copy is thereby found again. A list, not a joined string: an
     * entry name may contain any separator character.
     */
    private record Key(Path file, List<String> chain) { }

    private static final class Open {
        final FileSystem system;
        final Path origin;
        final Key key;
        /** The folder holding the temporary copy of an inner archive; null for an archive that is a real file. */
        final Path tempDirectory;
        int references = 1;

        Open(FileSystem system, Path origin, Key key, Path tempDirectory) {
            this.system = system;
            this.origin = origin;
            this.key = key;
            this.tempDirectory = tempDirectory;
        }
    }

    private static final Map<Key, Open> BY_FILE = new HashMap<>();
    private static final Map<FileSystem, Open> BY_SYSTEM = new HashMap<>();
    private static Path tempRoot;

    private Archives() { }

    /** True for paths inside an opened archive (not the default file system). */
    public static boolean isVirtual(Path path) {
        return path.getFileSystem() != FileSystems.getDefault();
    }

    /**
     * Opens an archive file (or takes another reference to it) and returns the root of its entries.
     * An archive inside another archive is extracted to a temporary file once; opening it again reuses that
     * copy, and the last {@link #release} deletes it.
     */
    public static synchronized Path open(Path archive) throws IOException {
        Key key;
        if (isVirtual(archive)) {
            Open outer = BY_SYSTEM.get(archive.getFileSystem());
            if (outer == null) {
                throw new IOException("the archive that holds " + archive + " is closed");
            }
            List<String> chain = new ArrayList<>(outer.key.chain());
            chain.add(archive.toString());
            key = new Key(outer.key.file(), chain);
        } else {
            key = new Key(archive.toRealPath(), List.of());
        }
        Open open = BY_FILE.get(key);
        if (open == null) {
            open = create(archive, key);
            BY_FILE.put(key, open);
            BY_SYSTEM.put(open.system, open);
        } else {
            open.references++;
        }
        return open.system.getRootDirectories().iterator().next();
    }

    private static Open create(Path archive, Key key) throws IOException {
        if (!isVirtual(archive)) {
            return new Open(newSystem(key.file()), archive, key, null);
        }
        Extracted copy = extract(archive);
        try {
            return new Open(newSystem(copy.path()), archive, key, copy.directory());
        } catch (IOException | RuntimeException e) {
            copy.close();
            throw e;
        }
    }

    private static FileSystem newSystem(Path realFile) throws IOException {
        // Names with backslash separators (some Windows tools) are corrected on the fly, see PatchedZip.
        ZipNames.Fix fix = ZipNames.find(realFile);
        Path file = fix == null ? realFile : PatchedZip.wrap(realFile, fix);
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
        BY_FILE.remove(open.key);
        discard(open);
    }

    public static synchronized void closeAll() {
        for (Open open : BY_SYSTEM.values().toArray(new Open[0])) {
            discard(open);
        }
        BY_SYSTEM.clear();
        BY_FILE.clear();
    }

    /** Closes the archive and deletes the temporary copy of an archive that was inside another one. */
    private static void discard(Open open) {
        try {
            open.system.close();
        } catch (IOException ignored) {
            // The archive is read-only: nothing is lost when closing fails.
        }
        if (open.tempDirectory != null) {
            deleteTree(open.tempDirectory);
        }
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

    /**
     * A name that is safe to use as the file name of a copy made from an archive entry: zip entry names are
     * arbitrary text, and the provider hands out names such as {@code ..\..\x} or {@code C:\x} as a "file name"
     * that Windows would resolve outside the target folder.
     *
     * @throws IOException the name is empty, {@code .} or {@code ..}, or contains a separator, a colon or a control character
     */
    public static String safeEntryName(String name) throws IOException {
        boolean unsafe = name.isEmpty() || name.equals(".") || name.equals("..");
        for (int i = 0; i < name.length() && !unsafe; i++) {
            char c = name.charAt(i);
            unsafe = c == '\\' || c == '/' || c == ':' || Character.isISOControl(c);
        }
        if (unsafe) {
            throw new IOException("unsafe archive entry name: " + name.replaceAll("\\p{Cntrl}", "?"));
        }
        return name;
    }

    /** A file of the real file system with the content of {@code path}, deleted again by {@link Extracted#close}. */
    public static Extracted extract(Path path) throws IOException {
        if (!isVirtual(path)) {
            return new Extracted(path, null);
        }
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IOException("not a file inside an archive: " + path);
        }
        String name = safeEntryName(fileName.toString());
        Path root = tempRoot();
        if (Files.size(path) > Files.getFileStore(root).getUsableSpace()) {
            throw new IOException("not enough free space in the temp folder");
        }
        Path dir = Files.createTempDirectory(root, "x");
        try {
            Path target = dir.resolve(name);
            if (!target.normalize().startsWith(dir.normalize())) {
                throw new IOException("unsafe archive entry name: " + name);
            }
            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            return new Extracted(target, dir);
        } catch (IOException | RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
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
