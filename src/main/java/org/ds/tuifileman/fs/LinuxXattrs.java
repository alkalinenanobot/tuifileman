package org.ds.tuifileman.fs;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Every extended attribute of a Linux file, through libc ({@code listxattr}, {@code getxattr}, {@code setxattr},
 * {@code removexattr}) with the Foreign Function API. Java itself only reaches the {@code user.} namespace, but a
 * POSIX ACL lives in {@code system.posix_acl_access}, an SELinux label in {@code security.selinux} and file
 * capabilities in {@code security.capability}. Names and values are handled as raw bytes.
 */
public final class LinuxXattrs {
    /** Whether this is Linux, where the attributes have to be copied with these calls. */
    public static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    private static final int ERANGE = 34;
    private static final int ENODATA = 61;
    private static final int ENOTSUP = 95;

    private record Calls(MethodHandle list, MethodHandle get, MethodHandle set, MethodHandle remove,
                         VarHandle errno, long stateSize) { }

    private static final Calls CALLS = load();

    private LinuxXattrs() { }

    private static Calls load() {
        if (!LINUX) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            MemoryLayout ssize = linker.canonicalLayouts().get("long");
            MemoryLayout size = linker.canonicalLayouts().get("size_t");
            MemoryLayout cint = linker.canonicalLayouts().get("int");
            MemoryLayout pointer = linker.canonicalLayouts().get("void*");
            Linker.Option errno = Linker.Option.captureCallState("errno");
            MethodHandle list = linker.downcallHandle(libc.find("listxattr").orElseThrow(),
                    FunctionDescriptor.of(ssize, pointer, pointer, size), errno);
            MethodHandle get = linker.downcallHandle(libc.find("getxattr").orElseThrow(),
                    FunctionDescriptor.of(ssize, pointer, pointer, pointer, size), errno);
            MethodHandle set = linker.downcallHandle(libc.find("setxattr").orElseThrow(),
                    FunctionDescriptor.of(cint, pointer, pointer, pointer, size, cint), errno);
            MethodHandle remove = linker.downcallHandle(libc.find("removexattr").orElseThrow(),
                    FunctionDescriptor.of(cint, pointer, pointer), errno);
            var state = Linker.Option.captureStateLayout();
            return new Calls(list, get, set, remove,
                    state.varHandle(MemoryLayout.PathElement.groupElement("errno")), state.byteSize());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the calls are usable (Linux with the Foreign Function API available). */
    public static boolean available() {
        return CALLS != null;
    }

    /**
     * Makes the extended attributes of {@code to} equal to those of {@code from}: missing ones are set, changed
     * ones overwritten, extra ones removed. False when one of them cannot be read, set or removed (an SELinux
     * label or file capabilities need privileges a user may lack), so the caller can avoid losing it.
     */
    public static boolean copy(Path from, Path to) {
        if (CALLS == null) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fromPath = path(arena, from);
            MemorySegment toPath = path(arena, to);
            MemorySegment state = arena.allocate(CALLS.stateSize());
            List<byte[]> wanted = names(arena, state, fromPath);
            List<byte[]> present = names(arena, state, toPath);
            if (wanted == null || present == null) {
                return false;
            }
            for (byte[] name : wanted) {
                MemorySegment cName = cString(arena, name);
                byte[] value = value(arena, state, fromPath, cName);
                if (value == null) {
                    return false;
                }
                if (Arrays.equals(value, value(arena, state, toPath, cName))) {
                    continue;
                }
                MemorySegment buffer = arena.allocate(Math.max(1, value.length));
                MemorySegment.copy(value, 0, buffer, ValueLayout.JAVA_BYTE, 0, value.length);
                if ((int) CALLS.set().invokeExact(state, toPath, cName, buffer, (long) value.length, 0) != 0) {
                    return false;
                }
            }
            for (byte[] name : present) {
                if (wanted.stream().noneMatch(w -> Arrays.equals(w, name))
                        && (int) CALLS.remove().invokeExact(state, toPath, cString(arena, name)) != 0
                        && errno(state) != ENODATA) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The attribute names of a file; empty where the file system has none; null on failure. */
    private static List<byte[]> names(Arena arena, MemorySegment state, MemorySegment path) throws Throwable {
        while (true) {
            long size = (long) CALLS.list().invokeExact(state, path, MemorySegment.NULL, 0L);
            if (size < 0) {
                return errno(state) == ENOTSUP ? List.of() : null;
            }
            MemorySegment buffer = arena.allocate(Math.max(1, size));
            long got = (long) CALLS.list().invokeExact(state, path, buffer, size);
            if (got < 0) {
                if (errno(state) == ERANGE) {
                    continue;   // an attribute was added in between
                }
                return null;
            }
            List<byte[]> names = new ArrayList<>();
            byte[] all = buffer.asSlice(0, got).toArray(ValueLayout.JAVA_BYTE);
            int start = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] == 0) {
                    if (i > start) {
                        names.add(Arrays.copyOfRange(all, start, i));
                    }
                    start = i + 1;
                }
            }
            return names;
        }
    }

    /** The value of one attribute; null when it is not there or cannot be read. */
    private static byte[] value(Arena arena, MemorySegment state, MemorySegment path, MemorySegment name)
            throws Throwable {
        while (true) {
            long size = (long) CALLS.get().invokeExact(state, path, name, MemorySegment.NULL, 0L);
            if (size < 0) {
                return null;
            }
            MemorySegment buffer = arena.allocate(Math.max(1, size));
            long got = (long) CALLS.get().invokeExact(state, path, name, buffer, size);
            if (got >= 0) {
                return buffer.asSlice(0, got).toArray(ValueLayout.JAVA_BYTE);
            }
            if (errno(state) != ERANGE) {
                return null;
            }
        }
    }

    private static int errno(MemorySegment state) {
        return (int) CALLS.errno().get(state, 0L);
    }

    /** The path in the encoding the JDK uses for file names, NUL-terminated. */
    private static MemorySegment path(Arena arena, Path path) {
        String encoding = System.getProperty("sun.jnu.encoding");
        Charset charset = encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
        return arena.allocateFrom(path.toAbsolutePath().toString(), charset);
    }

    private static MemorySegment cString(Arena arena, byte[] bytes) {
        MemorySegment segment = arena.allocate(bytes.length + 1L);
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return segment;
    }
}
