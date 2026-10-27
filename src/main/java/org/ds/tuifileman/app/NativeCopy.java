package org.ds.tuifileman.app;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Windows CopyFileEx via JNA: native copy (offload/clone aware) with progress and cancellation. */
final class NativeCopy {
    private static final int COPY_FILE_FAIL_IF_EXISTS = 0x1;
    private static final int COPY_FILE_ALLOW_DECRYPTED_DESTINATION = 0x8;
    private static final int PROGRESS_CONTINUE = 0;
    private static final int PROGRESS_CANCEL = 1;
    private static final int ERROR_REQUEST_ABORTED = 1235;
    private static final int ERROR_ACCESS_DENIED = 5;
    private static final int ERROR_FILE_EXISTS = 80;
    private static final int ERROR_ALREADY_EXISTS = 183;

    interface ProgressRoutine extends Callback {
        int callback(long total, long transferred, long streamSize, long streamTransferred,
                     int streamNumber, int reason, Pointer src, Pointer dst, Pointer data);
    }

    interface Kernel32 extends StdCallLibrary {
        boolean CopyFileExW(WString source, WString target, ProgressRoutine progress,
                            Pointer data, IntByReference cancel, int flags);
    }

    /** Thrown when the user cancelled while a file was being copied. */
    static final class Cancelled extends IOException {
        Cancelled() {
            super("Cancelled");
        }
    }

    /** Thrown when overwriting failed because the target is read-only or otherwise denied. */
    static final class Denied extends IOException {
        Denied(String message) {
            super(message);
        }
    }

    private static final Kernel32 API = load();

    private NativeCopy() { }

    private static Kernel32 load() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        try {
            return Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean available() {
        return API != null;
    }

    /**
     * @param progress receives the number of newly transferred bytes (may be null)
     * @param cancel   polled by the native copy; may be null
     */
    static void copy(Path source, Path target, boolean overwrite,
                     LongConsumer progress, BooleanSupplier cancel) throws IOException {
        long[] last = {0};
        ProgressRoutine routine = (total, transferred, streamSize, streamTransferred,
                                   streamNumber, reason, src, dst, data) -> {
            if (progress != null && transferred > last[0]) {
                progress.accept(transferred - last[0]);
                last[0] = transferred;
            }
            return cancel != null && cancel.getAsBoolean() ? PROGRESS_CANCEL : PROGRESS_CONTINUE;
        };
        int flags = COPY_FILE_ALLOW_DECRYPTED_DESTINATION | (overwrite ? 0 : COPY_FILE_FAIL_IF_EXISTS);
        boolean ok = API.CopyFileExW(new WString(extended(source)), new WString(extended(target)),
                routine, null, null, flags);
        if (!ok) {
            int error = Native.getLastError();
            if (progress != null && last[0] > 0) {
                progress.accept(-last[0]);
            }
            if (error == ERROR_REQUEST_ABORTED) {
                throw new Cancelled();
            }
            if (error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS) {
                throw new FileAlreadyExistsException(target.toString());
            }
            if (error == ERROR_ACCESS_DENIED) {
                throw new Denied("Access denied: " + target);
            }
            throw new IOException(describe(error));
        }
    }

    /** Win32 only accepts paths over MAX_PATH (260) when they carry the \\?\ prefix. */
    private static String extended(Path path) {
        String text = path.toAbsolutePath().toString();
        if (text.length() < 240 || text.startsWith("\\\\?\\")) {
            return text;
        }
        return text.startsWith("\\\\") ? "\\\\?\\UNC\\" + text.substring(2) : "\\\\?\\" + text;
    }

    private static String describe(int error) {
        String reason = switch (error) {
            case 2 -> "File not found";
            case 3 -> "Path not found";
            case 5 -> "Access denied";
            case 32 -> "File is in use by another program";
            case 39, 112 -> "Not enough disk space";
            case 123 -> "Invalid file name";
            case 206 -> "Path too long";
            case 225 -> "Blocked by antivirus";
            default -> "Copy failed";
        };
        return reason + " (Windows error " + error + ")";
    }
}
