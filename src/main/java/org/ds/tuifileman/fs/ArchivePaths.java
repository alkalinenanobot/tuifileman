package org.ds.tuifileman.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Command-line spelling of a file or folder inside an archive: {@code C:\data\a.zip!/dir/file.txt}, the same form
 * {@code find} prints for a hit. {@code !} counts as the separator only when it is followed by a slash, a backslash
 * or the end of the text and the part before it is an existing file; every other path is a plain path. An archive
 * inside an archive works too ({@code a.zip!/b.zip!/c.txt}; the inner one is extracted to a temporary file).
 *
 * <p>An opened archive stays open until {@link Archives#release} or {@link Archives#closeAll}: a command that
 * resolves paths here should end with {@code Archives.closeAll()}.
 */
public final class ArchivePaths {
    private ArchivePaths() { }

    /**
     * The path the text names. For an entry of an archive this is a path of the archive's file system, which may
     * not exist (the caller reports that like any missing file).
     *
     * @throws IOException the file before the {@code !} is not a readable archive
     * @throws InvalidPathException the text is not a valid path
     */
    public static Path resolve(String text) throws IOException {
        for (int bang = marker(text, 0); bang >= 0; bang = marker(text, bang + 1)) {
            Path file = Path.of(text.substring(0, bang)).toAbsolutePath().normalize();
            if (Files.isRegularFile(file)) {
                return enter(file, text.substring(bang + 1));
            }
        }
        return Path.of(text);
    }

    private static Path enter(Path archive, String rest) throws IOException {
        Path root;
        try {
            root = Archives.open(archive);
        } catch (IOException | RuntimeException e) {
            throw new IOException(archive + " is not a readable archive", e);
        }
        String inside = rest.replace('\\', '/');
        for (int bang = marker(inside, 0); bang >= 0; bang = marker(inside, bang + 1)) {
            Path inner = root.resolve(inside.substring(0, bang));
            if (Files.isRegularFile(inner)) {
                return enter(inner, inside.substring(bang + 1));
            }
        }
        return inside.isEmpty() ? root : root.resolve(inside);
    }

    /** Index of the next {@code !} at or after {@code from} that is followed by a separator or ends the text; -1 if none. */
    private static int marker(String text, int from) {
        for (int i = text.indexOf('!', from); i >= 0; i = text.indexOf('!', i + 1)) {
            if (i > 0 && (i + 1 == text.length() || text.charAt(i + 1) == '/' || text.charAt(i + 1) == '\\')) {
                return i;
            }
        }
        return -1;
    }
}
