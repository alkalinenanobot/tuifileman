package org.ds.tuifileman.cli;

import org.ds.tuifileman.fs.ArchivePaths;
import org.ds.tuifileman.fs.Archives;
import org.ds.tuifileman.fs.Glob;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code *} and {@code ?} in the last path segment, expanded by the tool itself because neither cmd nor
 * PowerShell does it for a native program. Only those two characters count as wildcards: they cannot occur
 * in a Windows file name, whereas {@code [} and {@code ]} can, so {@code file[1].txt} stays a plain name.
 */
final class Wildcards {
    private Wildcards() { }

    static boolean has(String argument) {
        return argument.indexOf('*') >= 0 || argument.indexOf('?') >= 0;
    }

    /**
     * The existing paths matching the pattern, sorted by name.
     *
     * @throws Args.UsageException a wildcard anywhere but the last segment, or an invalid path
     */
    static List<Path> expand(String argument) throws Args.UsageException {
        try {
            int cut = Math.max(argument.lastIndexOf('\\'), argument.lastIndexOf('/'));
            String parentText = cut < 0 ? "." : argument.substring(0, cut + 1);
            String name = argument.substring(cut + 1);
            if (!has(name) || has(parentText)) {
                throw new Args.UsageException("wildcards are allowed in the last path segment only: " + argument);
            }
            Path parent;
            try {
                parent = ArchivePaths.resolve(parentText).toAbsolutePath().normalize();
            } catch (IOException e) {
                throw new Args.UsageException(e.getMessage());
            }
            Pattern regex = compile(name);
            List<Path> found = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
                for (Path path : stream) {
                    if (regex.matcher(path.getFileName().toString()).matches()) {
                        found.add(path);
                    }
                }
            } catch (IOException e) {
                throw new Args.UsageException("cannot read " + Archives.display(parent) + ": " + e.getMessage());
            }
            // Case-insensitive, and a fixed order for names that differ only in case (possible on Linux), so the
            // same command always sees the same order (rename numbers by it) whatever order the directory lists.
            found.sort(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(p -> p.getFileName().toString()));
            return found;
        } catch (InvalidPathException e) {
            throw new Args.UsageException("invalid path: " + argument);
        }
    }

    private static Pattern compile(String glob) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Glob.compile(glob, windows);
    }
}
