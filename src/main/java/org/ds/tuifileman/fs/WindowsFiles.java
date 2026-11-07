package org.ds.tuifileman.fs;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Windows file operations Java has no API for, through JNA: junctions (reparse points) and ReplaceFile, the
 * call that swaps in a new version of a file while keeping its ACL, attributes, streams and creation time.
 */
public final class WindowsFiles {
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_READ_ATTRIBUTES = 0x80;
    private static final int SHARE_ALL = 0x7;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FSCTL_GET_REPARSE_POINT = 0x000900A8;
    private static final int FSCTL_SET_REPARSE_POINT = 0x000900A4;
    private static final int MAXIMUM_REPARSE_DATA_BUFFER_SIZE = 16 * 1024;
    private static final int IO_REPARSE_TAG_MOUNT_POINT = 0xA0000003;
    /** Set in the tag of every reparse point that names another file (junction, symbolic link). */
    private static final int NAME_SURROGATE = 0x20000000;
    private static final int ERROR_UNABLE_TO_MOVE_REPLACEMENT_2 = 1177;

    interface Api extends StdCallLibrary {
        Pointer CreateFileW(WString name, int access, int share, Pointer security, int disposition,
                            int flags, Pointer template);

        boolean DeviceIoControl(Pointer handle, int code, byte[] in, int inSize, byte[] out, int outSize,
                                IntByReference returned, Pointer overlapped);

        boolean CloseHandle(Pointer handle);

        boolean ReplaceFileW(WString replaced, WString replacement, WString backup, int flags,
                             Pointer exclude, Pointer reserved);
    }

    private static final Api API = load();

    private WindowsFiles() { }

    private static Api load() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            return Native.load("kernel32", Api.class, W32APIOptions.UNICODE_OPTIONS);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether this is Windows and the native calls are usable. */
    public static boolean available() {
        return API != null;
    }

    /**
     * Whether {@code folder}, a reparse point, holds content of its own (a cloud placeholder folder, for
     * example) rather than naming another folder as a junction does. False when its tag cannot be read.
     */
    public static boolean holdsContent(Path folder) {
        if (API == null) {
            return false;
        }
        try {
            return (tag(reparseData(folder)) & NAME_SURROGATE) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Creates {@code target} as a junction to the same folder {@code source} points to. The target must not
     * exist unless {@code replace}, and then it must be a file or a link, never a folder with content.
     *
     * @throws IOException {@code source} is not a junction, or the junction cannot be created
     */
    public static void copyJunction(Path source, Path target, boolean replace) throws IOException {
        if (API == null) {
            throw new IOException("Cannot copy a junction here");
        }
        byte[] data = reparseData(source);
        int tag = tag(data);
        if (tag != IO_REPARSE_TAG_MOUNT_POINT) {
            throw new IOException(String.format("Cannot copy this kind of link (reparse tag 0x%08X)", tag));
        }
        if (replace) {
            Files.deleteIfExists(target);
        }
        Files.createDirectory(target);
        try {
            Pointer handle = open(target, GENERIC_WRITE);
            try {
                IntByReference returned = new IntByReference();
                if (!API.DeviceIoControl(handle, FSCTL_SET_REPARSE_POINT, data, data.length, null, 0, returned, null)) {
                    throw failure("Cannot create junction", Native.getLastError());
                }
            } finally {
                API.CloseHandle(handle);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Puts {@code replacement} in the place of the existing {@code target} (same folder) with ReplaceFile:
     * the target's ACL, attributes, alternate streams and creation time carry over to the new content.
     * On failure the target is unchanged and {@code replacement} still exists.
     */
    public static void replace(Path target, Path replacement) throws IOException {
        if (API == null) {
            throw new IOException("ReplaceFile is not available");
        }
        if (API.ReplaceFileW(new WString(longPath(target)), new WString(longPath(replacement)), null, 0, null, null)) {
            return;
        }
        int error = Native.getLastError();
        if (error == ERROR_UNABLE_TO_MOVE_REPLACEMENT_2) {
            // The old file was moved aside but the new one did not take its name: give it the name now.
            Files.move(replacement, target);
            return;
        }
        throw failure("Cannot replace " + target, error);
    }

    /** Win32 only accepts paths over MAX_PATH (260) when they carry the \\?\ prefix. */
    public static String longPath(Path path) {
        String text = path.toAbsolutePath().toString();
        if (text.length() < 240 || text.startsWith("\\\\?\\")) {
            return text;
        }
        return text.startsWith("\\\\") ? "\\\\?\\UNC\\" + text.substring(2) : "\\\\?\\" + text;
    }

    private static byte[] reparseData(Path path) throws IOException {
        Pointer handle = open(path, FILE_READ_ATTRIBUTES);
        try {
            byte[] buffer = new byte[MAXIMUM_REPARSE_DATA_BUFFER_SIZE];
            IntByReference returned = new IntByReference();
            if (!API.DeviceIoControl(handle, FSCTL_GET_REPARSE_POINT, null, 0, buffer, buffer.length, returned, null)
                    || returned.getValue() < 8) {
                throw failure("Cannot read link " + path, Native.getLastError());
            }
            return Arrays.copyOf(buffer, returned.getValue());
        } finally {
            API.CloseHandle(handle);
        }
    }

    private static Pointer open(Path path, int access) throws IOException {
        Pointer handle = API.CreateFileW(new WString(longPath(path)), access, SHARE_ALL, null, OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || Pointer.nativeValue(handle) == -1L) {
            throw failure("Cannot open " + path, Native.getLastError());
        }
        return handle;
    }

    /** The reparse tag: the first four bytes of the reparse data, little-endian. */
    private static int tag(byte[] data) {
        return (data[0] & 0xFF) | (data[1] & 0xFF) << 8 | (data[2] & 0xFF) << 16 | (data[3] & 0xFF) << 24;
    }

    private static IOException failure(String what, int error) {
        String reason = switch (error) {
            case 2, 3 -> "not found";
            case 5 -> "access denied";
            case 32 -> "in use by another program";
            case 4390 -> "not a link";
            default -> "Windows error " + error;
        };
        return new IOException(what + ": " + reason);
    }
}
