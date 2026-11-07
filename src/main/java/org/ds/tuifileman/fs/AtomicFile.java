package org.ds.tuifileman.fs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes a file so that a full disk, an I/O error or a kill in the middle leaves the old content intact:
 * the bytes go to a temporary file in the same folder, which then replaces the target in one move.
 * A symbolic link is followed (the file it points to is replaced, the link stays).
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
        // a short fixed-length name: the target's own name can already be at the file system's length limit
        Path temp = target.resolveSibling(".tfm-" + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                OutputStream out = Channels.newOutputStream(channel);
                body.write(out);
                out.flush();
                channel.force(true);
            }
            keepPermissions(target, temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
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

    /** The temporary file is created with the default permissions; an existing target keeps its own. */
    private static void keepPermissions(Path target, Path temp) {
        PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        try {
            Files.setPosixFilePermissions(temp, view.readAttributes().permissions());
        } catch (IOException | UnsupportedOperationException ignored) {
            // The target is new or the file system has no permissions: the default ones stay.
        }
    }
}
