package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelCopyTest {
    @TempDir
    Path root;

    private static byte[] bytes(int size) {
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);
        return data;
    }

    @Test
    void copiesContentAndModifiedTimeAndCountsBytes() throws Exception {
        Path source = root.resolve("src.bin");
        byte[] data = bytes(20 << 20);
        Files.write(source, data);
        Files.setLastModifiedTime(source, FileTime.fromMillis(1_700_000_000_000L));
        AtomicLong progress = new AtomicLong();
        Path target = root.resolve("dst.bin");
        ChannelCopy.copy(source, target, false, progress::addAndGet, null);
        assertArrayEquals(data, Files.readAllBytes(target));
        assertEquals(1_700_000_000_000L, Files.getLastModifiedTime(target).toMillis());
        assertEquals(data.length, progress.get());
    }

    @Test
    void emptyFileIsCopied() throws Exception {
        Path source = Files.write(root.resolve("empty"), new byte[0]);
        Path target = root.resolve("empty2");
        ChannelCopy.copy(source, target, false, null, null);
        assertEquals(0, Files.size(target));
    }

    @Test
    void replacesAnExistingTargetEvenWhenReadOnly() throws Exception {
        Assumptions.assumeTrue(posix());
        Path source = Files.writeString(root.resolve("src.txt"), "new");
        Path target = Files.writeString(root.resolve("dst.txt"), "old content that is longer");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"));
        ChannelCopy.copy(source, target, true, null, null);
        assertEquals("new", Files.readString(target));
    }

    @Test
    void withoutReplaceAnExistingTargetFailsTheCopyAndIsKept() throws Exception {
        Path source = Files.writeString(root.resolve("src.txt"), "new");
        Path target = Files.writeString(root.resolve("dst.txt"), "written by another source");
        AtomicLong progress = new AtomicLong();
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> ChannelCopy.copy(source, target, false, progress::addAndGet, null));
        assertEquals("written by another source", Files.readString(target));
        assertEquals(0, progress.get());
    }

    @Test
    void cancelLeavesNoPartialFileAndTakesBackProgress() throws Exception {
        Path source = Files.write(root.resolve("big.bin"), bytes(40 << 20));
        Path target = root.resolve("big2.bin");
        AtomicLong progress = new AtomicLong();
        int[] polls = {0};
        assertThrows(NativeCopy.Cancelled.class,
                () -> ChannelCopy.copy(source, target, false, progress::addAndGet, () -> ++polls[0] > 2));
        assertFalse(Files.exists(target));
        assertEquals(0, progress.get());
    }

    @Test
    void sourceShrinkingDuringTheCopyFailsInsteadOfLeavingATruncatedTarget() throws Exception {
        Path source = Files.write(root.resolve("log.bin"), bytes(20 << 20));
        Path target = root.resolve("log2.bin");
        AtomicLong progress = new AtomicLong();
        int[] polls = {0};
        assertThrows(IOException.class, () -> ChannelCopy.copy(source, target, false, progress::addAndGet, () -> {
            if (++polls[0] == 2) {
                // After the first 8 MiB chunk another writer cuts the file to 10 MiB.
                try (FileChannel shrink = FileChannel.open(source, StandardOpenOption.WRITE)) {
                    shrink.truncate(10 << 20);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return false;
        }));
        assertFalse(Files.exists(target));
        assertEquals(0, progress.get());
    }

    @Test
    void keepsTheModeOnPosixFileSystems() throws Exception {
        Assumptions.assumeTrue(posix());
        Path script = Files.writeString(root.resolve("run.sh"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));
        Path secret = Files.writeString(root.resolve("secret"), "x");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        ChannelCopy.copy(script, root.resolve("run2.sh"), false, null, null);
        ChannelCopy.copy(secret, root.resolve("secret2"), false, null, null);
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("run2.sh"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("secret2"))));
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
