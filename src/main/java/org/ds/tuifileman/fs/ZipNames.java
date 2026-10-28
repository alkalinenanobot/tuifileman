package org.ds.tuifileman.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Finds zip files whose entry names use backslashes as path separators. Some Windows tools (the .NET Framework
 * zip classes behind PowerShell 5.1 {@code Compress-Archive}) write {@code dir\file.txt}, which the specification
 * forbids; 7-Zip and Explorer accept it, the JDK zip provider does not and shows one flat folder of names with
 * backslashes in them. The provider only reads names from the central directory and a backslash is as long as a
 * slash, so {@link PatchedZip} shows it a central directory with the names corrected; nothing is copied or written.
 */
final class ZipNames {
    /** The corrected central directory and the offset in the file where it starts. */
    record Fix(long offset, byte[] directory) { }

    private static final int EOCD = 0x06054b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;
    private static final int ZIP64_EOCD = 0x06064b50;
    private static final int CENTRAL_HEADER = 0x02014b50;
    private static final int CENTRAL_FIXED = 46;

    private ZipNames() { }

    /**
     * Returns the corrected central directory of {@code file}, or null when its entry names are fine (or it is no
     * zip file). Only archives with backslashes and no slash at all are repaired, so a name that legitimately
     * contains a backslash is left alone.
     */
    static Fix find(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            int tail = (int) Math.min(size, 22 + 65535);
            if (tail < 22) {
                return null;
            }
            ByteBuffer end = read(channel, size - tail, tail);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (end.getInt(i) == EOCD) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                return null;
            }
            long directorySize = end.getInt(eocd + 12) & 0xFFFFFFFFL;
            long directoryOffset = end.getInt(eocd + 16) & 0xFFFFFFFFL;
            if ((directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL)
                    && eocd >= 20 && end.getInt(eocd - 20) == ZIP64_LOCATOR) {
                long record = end.getLong(eocd - 20 + 8);
                if (record < 0 || record + 56 > size) {
                    return null;
                }
                ByteBuffer zip64 = read(channel, record, 56);
                if (zip64.getInt(0) != ZIP64_EOCD) {
                    return null;
                }
                directorySize = zip64.getLong(40);
                directoryOffset = zip64.getLong(48);
            }
            if (directorySize <= 0 || directorySize > Integer.MAX_VALUE - 8 || directoryOffset < 0
                    || directoryOffset + directorySize > size) {
                return null;
            }
            byte[] directory = read(channel, directoryOffset, (int) directorySize).array();
            return patchNames(directory) ? new Fix(directoryOffset, directory) : null;
        }
    }

    /** Replaces backslashes in the names of a central directory in place; false when nothing needs repair. */
    private static boolean patchNames(byte[] directory) {
        ByteBuffer buffer = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN);
        boolean backslash = false;
        for (int pos = 0; pos + CENTRAL_FIXED <= directory.length && buffer.getInt(pos) == CENTRAL_HEADER; ) {
            int nameLength = buffer.getShort(pos + 28) & 0xFFFF;
            int next = pos + CENTRAL_FIXED + nameLength + (buffer.getShort(pos + 30) & 0xFFFF)
                    + (buffer.getShort(pos + 32) & 0xFFFF);
            if (next > directory.length) {
                return false;
            }
            for (int i = pos + CENTRAL_FIXED; i < pos + CENTRAL_FIXED + nameLength; i++) {
                if (directory[i] == '/') {
                    return false;
                }
                backslash |= directory[i] == '\\';
            }
            pos = next;
        }
        if (!backslash) {
            return false;
        }
        for (int pos = 0; pos + CENTRAL_FIXED <= directory.length && buffer.getInt(pos) == CENTRAL_HEADER; ) {
            int nameLength = buffer.getShort(pos + 28) & 0xFFFF;
            for (int i = pos + CENTRAL_FIXED; i < pos + CENTRAL_FIXED + nameLength; i++) {
                if (directory[i] == '\\') {
                    directory[i] = '/';
                }
            }
            pos += CENTRAL_FIXED + nameLength + (buffer.getShort(pos + 30) & 0xFFFF)
                    + (buffer.getShort(pos + 32) & 0xFFFF);
        }
        return true;
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new IOException("Truncated zip file");
            }
        }
        return buffer;
    }
}
