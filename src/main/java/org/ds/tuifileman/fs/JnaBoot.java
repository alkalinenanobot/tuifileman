package org.ds.tuifileman.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Gives JNA a stable copy of {@code jnidispatch.dll}. By default JNA unpacks the DLL to a new temp file on
 * every start, and a virus scanner that has not seen that file yet can hold the first load for seconds
 * (8 s was measured). A copy that stays in one place is scanned once. Must run before the first JNA class
 * is used; every failure is ignored, JNA then falls back to its own temp unpacking.
 */
public final class JnaBoot {
    private static final String RESOURCE = "/com/sun/jna/win32-x86-64/jnidispatch.dll";

    private JnaBoot() { }

    public static void prepare() {
        try {
            if (System.getProperty("jna.boot.library.path") != null
                    || !System.getProperty("os.name", "").startsWith("Windows")
                    || !System.getProperty("os.arch", "").equals("amd64")) {
                return;
            }
            String base = System.getenv("LOCALAPPDATA");
            Path dir = (base != null ? Path.of(base, "tuifileman") : Path.of(System.getProperty("user.home"), ".tuifileman"))
                    .resolve("native");
            Path target = dir.resolve("jnidispatch.dll");
            try (InputStream in = JnaBoot.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    return;
                }
                byte[] bytes = in.readAllBytes();
                if (!Files.isRegularFile(target) || Files.size(target) != bytes.length
                        || !java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
                    Files.createDirectories(dir);
                    Path temp = Files.createTempFile(dir, "jnidispatch", ".part");
                    Files.write(temp, bytes);
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.setProperty("jna.boot.library.path", dir.toString());
        } catch (IOException | RuntimeException e) {
            // JNA unpacks its own copy
        }
    }
}
