package org.ds.tuifileman.fs;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes a file so that a full disk, an I/O error or a kill in the middle leaves the old content intact:
 * the bytes go to a temporary file in the same folder, which then replaces the target in one move.
 * A symbolic link is followed (the file it points to is replaced, the link stays).
 *
 * <p>An existing file keeps what belongs to it rather than to its content. On Windows ReplaceFile carries
 * over the ACL, attributes, alternate streams and creation time. Elsewhere the temporary file gets the
 * target's permissions before any byte is written (a private file is never readable in between), its owner,
 * group and extended attributes; on Linux that is every attribute through libc ({@link LinuxXattrs}), so a
 * POSIX ACL, an SELinux label and file capabilities stay too. Where that cannot be done (the owner of a file
 * of someone else, a label the user may not set, a file with several hard links) the file is rewritten in
 * place, as {@code Files.write} does, giving up atomicity rather than the file's identity.
 */
public final class AtomicFile {
    private AtomicFile() { }

    @FunctionalInterface
    private interface Body {
        void write(OutputStream out) throws IOException;
    }

    public static void write(Path file, byte[] bytes) throws IOException {
        write(file, out -> out.write(bytes));
    }

    /** Same bytes as {@code Files.write(file, lines, cs)}: every line followed by the platform line separator. */
    public static void writeLines(Path file, List<String> lines, Charset cs) throws IOException {
        write(file, out -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, cs.newEncoder()));
            for (String line : lines) {
                writer.append(line);
                writer.newLine();
            }
            writer.flush();
        });
    }

    private static void write(Path file, Body body) throws IOException {
        Path target = Files.isSymbolicLink(file) ? file.toRealPath() : file.toAbsolutePath();
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributes posix = exists ? posixAttributes(target) : null;
        if (posix != null && hardLinks(target) > 1) {
            // a replacement would leave the other names with the old content
            writeInPlace(target, body);
            return;
        }
        // a short fixed-length name: the target's own name can already be at the file system's length limit
        Path temp = target.resolveSibling(".tfm-" + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");
        FileAttribute<?>[] initial = posix == null ? new FileAttribute<?>[0]
                : new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(posix.permissions())};
        try {
            boolean inPlace;
            try (FileChannel channel = FileChannel.open(temp, Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE), initial)) {
                inPlace = posix != null && !takeOver(target, posix, temp);
                if (!inPlace) {
                    OutputStream out = Channels.newOutputStream(channel);
                    body.write(out);
                    out.flush();
                    channel.force(true);
                }
            }
            if (inPlace) {
                Files.delete(temp);
                writeInPlace(target, body);
            } else if (exists && WindowsFiles.available()) {
                WindowsFiles.replace(target, temp);
            } else {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static PosixFileAttributes posixAttributes(Path target) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        return view == null ? null : view.readAttributes();
    }

    private static int hardLinks(Path target) {
        try {
            return target.getFileSystem().supportedFileAttributeViews().contains("unix")
                    ? (Integer) Files.getAttribute(target, "unix:nlink") : 1;
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    /**
     * Gives the still empty temporary file the target's permissions (the creation mask may have dropped some),
     * owner, group and extended attributes. False when one of them cannot be set. The attributes come last:
     * a change of owner drops file capabilities.
     */
    private static boolean takeOver(Path target, PosixFileAttributes posix, Path temp) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
        view.setPermissions(posix.permissions());
        PosixFileAttributes now = view.readAttributes();
        try {
            if (!now.owner().equals(posix.owner())) {
                view.setOwner(posix.owner());
            }
            if (!now.group().equals(posix.group())) {
                view.setGroup(posix.group());
            }
            if (LinuxXattrs.LINUX) {
                // without the native calls an ACL could not even be seen: never risk dropping one
                return LinuxXattrs.copy(target, temp);
            }
            copyUserAttributes(target, temp);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
        return true;
    }

    private static void copyUserAttributes(Path target, Path temp) throws IOException {
        UserDefinedFileAttributeView from = Files.getFileAttributeView(target, UserDefinedFileAttributeView.class);
        if (from == null || !Files.getFileStore(target).supportsFileAttributeView(UserDefinedFileAttributeView.class)) {
            return;
        }
        UserDefinedFileAttributeView to = Files.getFileAttributeView(temp, UserDefinedFileAttributeView.class);
        for (String name : from.list()) {
            ByteBuffer value = ByteBuffer.allocate(from.size(name));
            from.read(name, value);
            value.flip();
            to.write(name, value);
        }
    }

    /** The content is produced first, so a failure there leaves the file untouched; a failed write does not. */
    private static void writeInPlace(Path target, Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        body.write(bytes);
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }
}
