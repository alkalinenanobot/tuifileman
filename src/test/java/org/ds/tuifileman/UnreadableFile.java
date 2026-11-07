package org.ds.tuifileman;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Makes one file unreadable for every reader, in this process and in a child one: mode 000 on POSIX, a mandatory
 * lock over the whole content on Windows (reading raises "another process has locked a portion of the file").
 */
public final class UnreadableFile {
    private UnreadableFile() { }

    /**
     * Locks {@code file} (it must have content) until the returned handle is closed. Returns null when it stays
     * readable anyway (root on POSIX).
     */
    public static AutoCloseable lock(Path file) throws IOException {
        AutoCloseable release;
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            var before = Files.getPosixFilePermissions(file);
            Files.setPosixFilePermissions(file, Set.of());
            release = () -> Files.setPosixFilePermissions(file, before);
        } else {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock = channel.lock(0, Long.MAX_VALUE, false);
            release = () -> {
                lock.release();
                channel.close();
            };
        }
        try {
            Files.readAllBytes(file);
        } catch (IOException e) {
            return release;
        }
        try {
            release.close();
        } catch (Exception e) {
            throw new IOException(e);
        }
        return null;
    }
}
