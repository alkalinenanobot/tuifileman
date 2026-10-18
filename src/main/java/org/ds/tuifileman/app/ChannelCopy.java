package org.ds.tuifileman.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Portable file copy on FileChannel.transferTo (copy_file_range/sendfile on Linux): chunked, so it reports
 * progress and can be cancelled inside a big file, and it keeps the modified time and, on POSIX file
 * systems, the mode, the owner (when permitted) and the user xattrs, like {@code cp -a} does for a file.
 */
final class ChannelCopy {
    private static final int CHUNK = 8 << 20;
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("unix");
    private static final String ATTRIBUTES = "unix:mode,uid,gid,lastModifiedTime,lastAccessTime";

    private ChannelCopy() { }

    /**
     * Copies the content of a regular file. A cancelled or failed copy leaves no partial target behind and takes
     * back what it reported as transferred.
     *
     * @param replace  replace an existing target; otherwise an existing one fails the copy with
     *                 {@link java.nio.file.FileAlreadyExistsException} and is left alone
     * @param progress receives the number of newly transferred bytes (may be null)
     * @param cancel   polled between chunks (may be null)
     * @throws NativeCopy.Cancelled when cancelled
     */
    static void copy(Path source, Path target, boolean replace, LongConsumer progress, BooleanSupplier cancel)
            throws IOException {
        if (source.getFileSystem() != FileSystems.getDefault() || target.getFileSystem() != FileSystems.getDefault()) {
            streamCopy(source, target, replace, progress, cancel);
            return;
        }
        Map<String, Object> attributes = POSIX ? Files.readAttributes(source, ATTRIBUTES) : null;
        if (replace) {
            Files.deleteIfExists(target);
        }
        long[] done = {0};
        boolean created = false;
        boolean complete = false;
        try {
            try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
                 FileChannel out = open(target, attributes)) {
                created = true;
                long size = in.size();
                long position = 0;
                ByteBuffer fallback = null;
                while (position < size) {
                    if (cancel != null && cancel.getAsBoolean()) {
                        throw new NativeCopy.Cancelled();
                    }
                    long count = Math.min(CHUNK, size - position);
                    long n = in.transferTo(position, count, out);
                    if (n <= 0) {
                        // transferTo stops short at the end of the file, but also where copy_file_range gives
                        // up early (some Linux file systems); a plain read tells the two apart.
                        if (fallback == null) {
                            fallback = ByteBuffer.allocate(1 << 20);
                        }
                        fallback.clear().limit((int) Math.min(fallback.capacity(), count));
                        n = in.read(fallback, position);
                        if (n < 0) {
                            throw new IOException(source + " got shorter during the copy (" + position
                                    + " of " + size + " bytes)");
                        }
                        fallback.flip();
                        while (fallback.hasRemaining()) {
                            out.write(fallback);
                        }
                    }
                    position += n;
                    done[0] += n;
                    if (progress != null) {
                        progress.accept(n);
                    }
                }
            }
            if (attributes != null) {
                // Xattrs first: a non-root user cannot write them once the file is read-only.
                copyUserAttributes(source, target);
                applyPosix(target, attributes);
            } else {
                Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
            }
            complete = true;
        } finally {
            if (!complete && created) {
                if (progress != null && done[0] > 0) {
                    progress.accept(-done[0]);
                }
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Copies from or to another file system (an archive): plain streams, as such paths have no FileChannel.
     * The modified time and, on POSIX targets, the mode of the source entry are kept when it has them.
     */
    private static void streamCopy(Path source, Path target, boolean replace, LongConsumer progress,
            BooleanSupplier cancel) throws IOException {
        if (replace) {
            Files.deleteIfExists(target);
        }
        long[] done = {0};
        boolean created = false;
        boolean complete = false;
        try {
            try (InputStream in = Files.newInputStream(source);
                 OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                byte[] buffer = new byte[256 << 10];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (cancel != null && cancel.getAsBoolean()) {
                        throw new NativeCopy.Cancelled();
                    }
                    out.write(buffer, 0, n);
                    done[0] += n;
                    if (progress != null) {
                        progress.accept(n);
                    }
                }
            }
            try {
                Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
                if (POSIX && target.getFileSystem() == FileSystems.getDefault()) {
                    Files.setPosixFilePermissions(target,
                            Files.readAttributes(source, java.nio.file.attribute.PosixFileAttributes.class).permissions());
                }
            } catch (IOException | RuntimeException ignored) {
                // Missing times or modes in the entry never fail the copy.
            }
            complete = true;
        } finally {
            if (!complete && created) {
                if (progress != null && done[0] > 0) {
                    progress.accept(-done[0]);
                }
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Created with the source mode (the umask can only remove bits), so a private file is never briefly readable.
     * The owner can always write meanwhile, which xattrs need; the exact mode is set at the end.
     */
    private static FileChannel open(Path target, Map<String, Object> attributes) throws IOException {
        Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (attributes == null) {
            return FileChannel.open(target, options);
        }
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(modeText((Integer) attributes.get("mode") | 0200));
        FileAttribute<Set<PosixFilePermission>> mode = PosixFilePermissions.asFileAttribute(permissions);
        return FileChannel.open(target, options, mode);
    }

    private static void applyPosix(Path target, Map<String, Object> attributes) throws IOException {
        int mode = (Integer) attributes.get("mode");
        // The owner goes first: changing it clears setuid/setgid. Usually only root may; others keep their own.
        for (String id : new String[] {"uid", "gid"}) {
            try {
                Files.setAttribute(target, "unix:" + id, attributes.get(id), LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | UnsupportedOperationException ignored) { }
        }
        Files.setAttribute(target, "unix:mode", mode & 07777, LinkOption.NOFOLLOW_LINKS);
        Files.getFileAttributeView(target, java.nio.file.attribute.BasicFileAttributeView.class)
                .setTimes((FileTime) attributes.get("lastModifiedTime"), (FileTime) attributes.get("lastAccessTime"), null);
    }

    private static void copyUserAttributes(Path source, Path target) {
        try {
            UserDefinedFileAttributeView from = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
            if (from == null) {
                return;
            }
            UserDefinedFileAttributeView to = null;
            for (String name : from.list()) {
                ByteBuffer value = ByteBuffer.allocate(from.size(name));
                from.read(name, value);
                value.flip();
                if (to == null) {
                    to = Files.getFileAttributeView(target, UserDefinedFileAttributeView.class);
                }
                to.write(name, value);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Not every file system has xattrs; they are an extra, never a reason to fail the copy.
        }
    }

    private static String modeText(int mode) {
        char[] text = "---------".toCharArray();
        String letters = "rwxrwxrwx";
        for (int i = 0; i < 9; i++) {
            if ((mode & (1 << (8 - i))) != 0) {
                text[i] = letters.charAt(i);
            }
        }
        return new String(text);
    }
}
