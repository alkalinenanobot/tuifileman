package org.ds.tuifileman.fs;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * A read-only view of one real file whose central directory reads as {@link ZipNames.Fix#directory}. The JDK zip
 * provider takes any {@link Path} and reads the archive through {@code Files.newByteChannel} of that path's own
 * provider, so a path of this file system is the hook: every read that touches the central directory is answered
 * from the corrected bytes, all other reads (entry data) go straight to the file. Nothing is copied or written.
 */
final class PatchedZip {
    private static final Provider PROVIDER = new Provider();
    private static final FileSystem SYSTEM = new View();

    private PatchedZip() { }

    /** {@code file} as a path whose archive reads see the corrected names. */
    static Path wrap(Path file, ZipNames.Fix fix) {
        return new PatchedPath(file, fix);
    }

    private static Path unwrap(Path path) {
        if (path instanceof PatchedPath patched) {
            return patched.real;
        }
        return path;
    }

    private static final class PatchedPath implements Path {
        final Path real;
        final ZipNames.Fix fix;

        PatchedPath(Path real, ZipNames.Fix fix) {
            this.real = real;
            this.fix = fix;
        }

        private Path wrapped(Path other) {
            return other == null ? null : new PatchedPath(other, fix);
        }

        @Override public FileSystem getFileSystem() { return SYSTEM; }
        @Override public boolean isAbsolute() { return real.isAbsolute(); }
        @Override public Path getRoot() { return wrapped(real.getRoot()); }
        @Override public Path getFileName() { return wrapped(real.getFileName()); }
        @Override public Path getParent() { return wrapped(real.getParent()); }
        @Override public int getNameCount() { return real.getNameCount(); }
        @Override public Path getName(int index) { return wrapped(real.getName(index)); }
        @Override public Path subpath(int begin, int end) { return wrapped(real.subpath(begin, end)); }
        @Override public boolean startsWith(Path other) { return real.startsWith(unwrap(other)); }
        @Override public boolean endsWith(Path other) { return real.endsWith(unwrap(other)); }
        @Override public Path normalize() { return wrapped(real.normalize()); }
        @Override public Path resolve(Path other) { return wrapped(real.resolve(unwrap(other))); }
        @Override public Path relativize(Path other) { return wrapped(real.relativize(unwrap(other))); }
        @Override public URI toUri() { return real.toUri(); }
        @Override public Path toAbsolutePath() { return wrapped(real.toAbsolutePath()); }
        @Override public Path toRealPath(LinkOption... options) throws IOException {
            return wrapped(real.toRealPath(options));
        }
        @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                WatchEvent.Modifier... modifiers) throws IOException {
            return real.register(watcher, events, modifiers);
        }
        @Override public int compareTo(Path other) { return real.compareTo(unwrap(other)); }
        @Override public boolean equals(Object other) {
            return other instanceof PatchedPath patched && real.equals(patched.real);
        }
        @Override public int hashCode() { return real.hashCode(); }
        @Override public String toString() { return real.toString(); }
    }

    private static final class View extends FileSystem {
        private final FileSystem real = java.nio.file.FileSystems.getDefault();

        @Override public FileSystemProvider provider() { return PROVIDER; }
        @Override public void close() { throw new UnsupportedOperationException(); }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isReadOnly() { return true; }
        @Override public String getSeparator() { return real.getSeparator(); }
        @Override public Iterable<Path> getRootDirectories() { return real.getRootDirectories(); }
        @Override public Iterable<FileStore> getFileStores() { return real.getFileStores(); }
        @Override public Set<String> supportedFileAttributeViews() { return real.supportedFileAttributeViews(); }
        @Override public Path getPath(String first, String... more) { return real.getPath(first, more); }
        @Override public PathMatcher getPathMatcher(String syntaxAndPattern) {
            return real.getPathMatcher(syntaxAndPattern);
        }
        @Override public UserPrincipalLookupService getUserPrincipalLookupService() {
            return real.getUserPrincipalLookupService();
        }
        @Override public WatchService newWatchService() throws IOException { return real.newWatchService(); }
    }

    private static final class Provider extends FileSystemProvider {
        private final FileSystemProvider real = java.nio.file.FileSystems.getDefault().provider();

        @Override public String getScheme() { return real.getScheme(); }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }
        @Override public FileSystem getFileSystem(URI uri) { throw new UnsupportedOperationException(); }
        @Override public Path getPath(URI uri) { throw new UnsupportedOperationException(); }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                FileAttribute<?>... attrs) throws IOException {
            for (OpenOption option : options) {
                if (option != StandardOpenOption.READ && option != StandardOpenOption.SPARSE) {
                    throw new ReadOnlyFileSystemException();
                }
            }
            SeekableByteChannel channel = real.newByteChannel(unwrap(path), options, attrs);
            return path instanceof PatchedPath patched ? new PatchedChannel(channel, patched.fix) : channel;
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) {
            throw new UnsupportedOperationException();
        }
        @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) {
            throw new ReadOnlyFileSystemException();
        }
        @Override public void delete(Path path) { throw new ReadOnlyFileSystemException(); }
        @Override public void copy(Path source, Path target, CopyOption... options) {
            throw new ReadOnlyFileSystemException();
        }
        @Override public void move(Path source, Path target, CopyOption... options) {
            throw new ReadOnlyFileSystemException();
        }
        @Override public boolean isSameFile(Path a, Path b) throws IOException {
            return real.isSameFile(unwrap(a), unwrap(b));
        }
        @Override public boolean isHidden(Path path) throws IOException { return real.isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException {
            return real.getFileStore(unwrap(path));
        }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException {
            for (AccessMode mode : modes) {
                if (mode == AccessMode.WRITE) {
                    throw new AccessDeniedException(path.toString(), null, "read-only archive view");
                }
            }
            real.checkAccess(unwrap(path), modes);
        }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                LinkOption... options) {
            return real.getFileAttributeView(unwrap(path), type, options);
        }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                LinkOption... options) throws IOException {
            return real.readAttributes(unwrap(path), type, options);
        }
        @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
                throws IOException {
            return real.readAttributes(unwrap(path), attributes, options);
        }
        @Override public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
            throw new ReadOnlyFileSystemException();
        }
    }

    /** Answers reads of the central directory from the corrected bytes. */
    private static final class PatchedChannel implements SeekableByteChannel {
        private final SeekableByteChannel real;
        private final long from;
        private final long to;
        private final byte[] directory;

        PatchedChannel(SeekableByteChannel real, ZipNames.Fix fix) {
            this.real = real;
            this.from = fix.offset();
            this.to = fix.offset() + fix.directory().length;
            this.directory = fix.directory();
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            long start = real.position();
            int base = dst.position();
            int count = real.read(dst);
            if (count > 0) {
                long overlapFrom = Math.max(start, from);
                long overlapTo = Math.min(start + count, to);
                if (overlapFrom < overlapTo) {
                    dst.put(base + (int) (overlapFrom - start), directory, (int) (overlapFrom - from),
                            (int) (overlapTo - overlapFrom));
                }
            }
            return count;
        }

        @Override public int write(ByteBuffer src) { throw new ReadOnlyFileSystemException(); }
        @Override public long position() throws IOException { return real.position(); }
        @Override public SeekableByteChannel position(long newPosition) throws IOException {
            real.position(newPosition);
            return this;
        }
        @Override public long size() throws IOException { return real.size(); }
        @Override public SeekableByteChannel truncate(long size) { throw new ReadOnlyFileSystemException(); }
        @Override public boolean isOpen() { return real.isOpen(); }
        @Override public void close() throws IOException { real.close(); }
    }
}
