package org.ds.tuifileman.ignore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Decides which entries a folder scan leaves out. A matcher belongs to one folder; {@link #enter} gives
 * the matcher of a subfolder, which is where a nested {@code .gitignore} takes effect. Implementations
 * are immutable and thread safe, because the scan walks folders in parallel.
 */
public interface IgnoreMatcher {
    /** Bit of {@link #ignoredBy}: the rules of the left tree ignore the entry. */
    int BY_LEFT = 1;
    /** Bit of {@link #ignoredBy}: the rules of the right tree ignore the entry. */
    int BY_RIGHT = 2;

    /** Ignores nothing. */
    IgnoreMatcher NONE = new IgnoreMatcher() {
        @Override
        public int ignoredBy(String name, boolean directory) {
            return 0;
        }

        @Override
        public IgnoreMatcher enter(String name, Path left, Path right) {
            return this;
        }
    };

    /**
     * Which trees' rules ignore the entry {@code name} of this folder: {@link #BY_LEFT}, {@link #BY_RIGHT}
     * or both, 0 when it is not ignored. Rules given explicitly (not found in a tree) count for both.
     */
    int ignoredBy(String name, boolean directory);

    /** Whether the entry {@code name} of this folder is ignored; an ignored folder is not entered at all. */
    default boolean ignored(String name, boolean directory) {
        return ignoredBy(name, directory) != 0;
    }

    /** The matcher for the subfolder {@code name}; {@code left} and {@code right} are null where it is missing. */
    IgnoreMatcher enter(String name, Path left, Path right);

    /**
     * The git rules of this project: the given ignore files (patterns relative to the compared folders,
     * the first one has the lowest priority) and, with {@code nested}, the {@code .gitignore} of every
     * folder in both trees. An entry is ignored when the rules of either tree ignore it.
     */
    static IgnoreMatcher git(List<Path> ignoreFiles, boolean nested, Path left, Path right) throws IOException {
        return GitIgnoreMatcher.create(ignoreFiles, nested, left, right, GitIgnoreMatcher.PLATFORM_IGNORES_CASE);
    }
}
