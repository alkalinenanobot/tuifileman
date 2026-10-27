package org.ds.tuifileman.fs;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * How many threads a file operation should use. Cold-cache measurements on an NVMe SSD showed that
 * small-file work is latency bound and scales to 16-32 threads (copy 345 s with 1 thread, 58 s with 32;
 * delete 65 s sequential, 13 s with 16-32). Spinning disks and network shares want far fewer.
 * Override with {@code -Dtfm.threads=N}.
 */
public final class Parallel {
    public enum Storage { SSD, HDD, REMOVABLE, NETWORK, RAM, UNKNOWN }

    private static final int DRIVE_REMOVABLE = 2;
    private static final int DRIVE_FIXED = 3;
    private static final int DRIVE_REMOTE = 4;
    private static final int DRIVE_RAMDISK = 6;
    private static final int IOCTL_STORAGE_QUERY_PROPERTY = 0x2D1400;
    private static final int STORAGE_DEVICE_SEEK_PENALTY_PROPERTY = 7;
    private static final int SHARE_READ_WRITE = 3;
    private static final int OPEN_EXISTING = 3;

    interface Api extends StdCallLibrary {
        int GetDriveTypeW(WString root);

        Pointer CreateFileW(WString name, int access, int share, Pointer security, int disposition,
                            int flags, Pointer template);

        boolean DeviceIoControl(Pointer handle, int code, byte[] in, int inSize, byte[] out, int outSize,
                                IntByReference returned, Pointer overlapped);

        boolean CloseHandle(Pointer handle);
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final Api API = load();
    private static final ConcurrentMap<String, Storage> CACHE = new ConcurrentHashMap<>();

    private Parallel() { }

    private static Api load() {
        if (!WINDOWS) {
            return null;
        }
        try {
            return Native.load("kernel32", Api.class, W32APIOptions.UNICODE_OPTIONS);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Threads for work that touches this path (scanning, deleting, copying from it or to it). */
    public static int threads(Path path) {
        Integer forced = Integer.getInteger("tfm.threads");
        if (forced != null) {
            return Math.max(1, forced);
        }
        if (Archives.isVirtual(path)) {
            return 4; // the zip provider reads through one shared channel; more threads only wait for each other
        }
        return switch (storage(path)) {
            case SSD, RAM -> 32;
            case HDD, REMOVABLE -> 4;
            case NETWORK -> 6;
            case UNKNOWN -> 16;
        };
    }

    /** A copy is limited by the slower of its two ends. */
    public static int threads(Path source, Path destination) {
        return Math.min(threads(source), threads(destination));
    }

    public static Storage storage(Path path) {
        if (!WINDOWS) {
            return linuxStorage(path.toAbsolutePath());
        }
        Path root = path.toAbsolutePath().getRoot();
        if (root == null) {
            return Storage.UNKNOWN;
        }
        return CACHE.computeIfAbsent(root.toString(), key -> detect(root));
    }

    /** Per mount (the root is always "/" here); a path that does not exist yet is judged by its nearest parent. */
    private static Storage linuxStorage(Path path) {
        Path probe = path;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return Storage.UNKNOWN;
        }
        try {
            FileStore store = Files.getFileStore(probe);
            return CACHE.computeIfAbsent(store.toString(), key -> detectLinux(store));
        } catch (IOException | RuntimeException e) {
            return Storage.UNKNOWN;
        }
    }

    private static Storage detectLinux(FileStore store) {
        String type = store.type();
        if (type.equals("tmpfs") || type.equals("ramfs")) {
            return Storage.RAM;
        }
        if (type.startsWith("nfs") || type.equals("cifs") || type.startsWith("smb") || type.equals("fuse.sshfs")
                || type.equals("ceph") || type.equals("glusterfs") || type.equals("lustre")) {
            return Storage.NETWORK;
        }
        String name = store.name();
        if (!name.startsWith("/dev/")) {
            return Storage.UNKNOWN;
        }
        try {
            // /dev/mapper/x and similar are links to /dev/dm-N
            Path device = Path.of(name).toRealPath();
            Path sys = Path.of("/sys/class/block", device.getFileName().toString()).toRealPath();
            if (Files.exists(sys.resolve("partition"))) {
                sys = sys.getParent();
            }
            // A virtual disk (WSL, Hyper-V, VMware, QEMU) reports rotational=1 whatever the host has underneath.
            String vendor = read(sys.resolve("device/vendor")) + " " + read(sys.resolve("device/model"));
            if (vendor.matches("(?is).*(msft|virtual|vmware|qemu|vbox).*")) {
                return Storage.UNKNOWN;
            }
            return switch (read(sys.resolve("queue/rotational"))) {
                case "1" -> Storage.HDD;
                case "0" -> Storage.SSD;
                default -> Storage.UNKNOWN;
            };
        } catch (IOException | RuntimeException e) {
            return Storage.UNKNOWN;
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static Storage detect(Path root) {
        String text = root.toString();
        if (text.startsWith("\\\\")) {
            return Storage.NETWORK;
        }
        if (API == null || text.length() < 2 || text.charAt(1) != ':') {
            return Storage.UNKNOWN;
        }
        try {
            int type = API.GetDriveTypeW(new WString(text));
            switch (type) {
                case DRIVE_REMOTE:
                    return Storage.NETWORK;
                case DRIVE_REMOVABLE:
                    return Storage.REMOVABLE;
                case DRIVE_RAMDISK:
                    return Storage.RAM;
                case DRIVE_FIXED:
                    return seekPenalty(text.substring(0, 2));
                default:
                    return Storage.UNKNOWN;
            }
        } catch (Throwable t) {
            return Storage.UNKNOWN;
        }
    }

    /** Asks the volume whether its device has a seek penalty (a spinning disk). Needs no admin rights. */
    private static Storage seekPenalty(String drive) {
        Pointer handle = API.CreateFileW(new WString("\\\\.\\" + drive), 0, SHARE_READ_WRITE, null,
                OPEN_EXISTING, 0, null);
        if (handle == null || Pointer.nativeValue(handle) == -1L) {
            return Storage.UNKNOWN;
        }
        try {
            // STORAGE_PROPERTY_QUERY: PropertyId, QueryType (standard = 0), one spare byte
            byte[] query = new byte[12];
            query[0] = STORAGE_DEVICE_SEEK_PENALTY_PROPERTY;
            byte[] answer = new byte[16];
            IntByReference returned = new IntByReference();
            if (!API.DeviceIoControl(handle, IOCTL_STORAGE_QUERY_PROPERTY, query, query.length,
                    answer, answer.length, returned, null) || returned.getValue() < 9) {
                return Storage.UNKNOWN;
            }
            // DEVICE_SEEK_PENALTY_DESCRIPTOR: Version, Size, IncursSeekPenalty (BOOLEAN)
            return answer[8] != 0 ? Storage.HDD : Storage.SSD;
        } finally {
            API.CloseHandle(handle);
        }
    }
}
