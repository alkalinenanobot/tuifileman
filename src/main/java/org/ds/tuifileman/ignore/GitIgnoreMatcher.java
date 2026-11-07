package org.ds.tuifileman.ignore;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.ds.tuifileman.fs.Entries;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.ignore.IgnoreNode;

/**
 * {@code .gitignore} semantics on top of JGit's rule matcher (BSD-3, see THIRD-PARTY-NOTICES.md). Each
 * folder with a {@code .gitignore} adds a scope; the deepest scope that has an opinion about an entry
 * wins, a later rule beats an earlier one, and because ignored folders are never entered, a file inside
 * one cannot be re-included, exactly as in git. Both compared trees can have their own file in a folder;
 * when they disagree at the same level the entry counts as ignored, the safe side for a sync.
 */
final class GitIgnoreMatcher implements IgnoreMatcher {
    /** git's {@code core.ignorecase} default: on for Windows. */
    static final boolean PLATFORM_IGNORES_CASE = File.separatorChar == '\\';

    static {
        // JGit logs through slf4j; with no binding slf4j would print a "no providers" notice to stderr.
        if (System.getProperty("slf4j.internal.verbosity") == null) {
            System.setProperty("slf4j.internal.verbosity", "ERROR");
        }
    }

    /** The rules of one folder level; {@code base} is the folder below the compared root ("" for the root). */
    private record Scope(Scope parent, String base, IgnoreNode left, IgnoreNode right) { }

    private final Scope scope;
    private final String dir;
    private final boolean nested;
    private final boolean ignoreCase;

    private GitIgnoreMatcher(Scope scope, String dir, boolean nested, boolean ignoreCase) {
        this.scope = scope;
        this.dir = dir;
        this.nested = nested;
        this.ignoreCase = ignoreCase;
    }

    static IgnoreMatcher create(List<Path> ignoreFiles, boolean nested, Path leftRoot, Path rightRoot,
                                boolean ignoreCase) throws IOException {
        if (ignoreFiles.isEmpty() && !nested) {
            return NONE;
        }
        List<String> lines = new ArrayList<>();
        for (Path file : ignoreFiles) {
            lines.addAll(lines(file));
        }
        IgnoreNode global = node(lines, ignoreCase);
        Scope scope = global == null ? null : new Scope(null, "", global, global);
        if (nested) {
            scope = push(scope, "", load(leftRoot, ignoreCase), load(rightRoot, ignoreCase));
        }
        return new GitIgnoreMatcher(scope, "", nested, ignoreCase);
    }

    @Override
    public int ignoredBy(String name, boolean directory) {
        if (scope == null) {
            return 0;
        }
        String path = dir.isEmpty() ? name : dir + "/" + name;
        for (Scope s = scope; s != null; s = s.parent()) {
            String sub = s.base().isEmpty() ? path : path.substring(s.base().length() + 1);
            if (ignoreCase) {
                sub = sub.toLowerCase(Locale.ROOT);
            }
            Boolean a = s.left() == null ? null : s.left().checkIgnored(sub, directory);
            Boolean b = s.right() == null || s.right() == s.left() ? null : s.right().checkIgnored(sub, directory);
            if (a != null || b != null) {
                if (s.right() == s.left()) {
                    return Boolean.TRUE.equals(a) ? BY_LEFT | BY_RIGHT : 0;
                }
                return (Boolean.TRUE.equals(a) ? BY_LEFT : 0) | (Boolean.TRUE.equals(b) ? BY_RIGHT : 0);
            }
        }
        return 0;
    }

    @Override
    public IgnoreMatcher enter(String name, Path leftDir, Path rightDir) {
        String childDir = dir.isEmpty() ? name : dir + "/" + name;
        Scope child = scope;
        if (nested) {
            try {
                child = push(scope, childDir, leftDir == null ? null : load(leftDir, ignoreCase),
                        rightDir == null ? null : load(rightDir, ignoreCase));
            } catch (IOException e) {
                throw new UncheckedIOException(e.getMessage(), e);
            }
        }
        return child == null && !nested ? NONE : new GitIgnoreMatcher(child, childDir, nested, ignoreCase);
    }

    private static Scope push(Scope parent, String base, IgnoreNode left, IgnoreNode right) {
        return left == null && right == null ? parent : new Scope(parent, base, left, right);
    }

    /** The rules of the {@code .gitignore} in {@code folder}, or null when it has none. */
    private static IgnoreNode load(Path folder, boolean ignoreCase) throws IOException {
        Path file = folder.resolve(".gitignore");
        try {
            // not Files.isRegularFile: it answers false for a file whose attributes cannot be read
            if (!Files.readAttributes(file, BasicFileAttributes.class).isRegularFile()) {
                return null;
            }
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new IOException("cannot read ignore file " + file + ": " + (e instanceof AccessDeniedException
                    ? "access denied" : Entries.message(e)), e);
        }
        return node(lines(file), ignoreCase);
    }

    /** The lines of an ignore file; a file that cannot be read stops the run, naming the file and the reason. */
    private static List<String> lines(Path file) throws IOException {
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            String reason = e instanceof AccessDeniedException ? "access denied"
                    : e instanceof FileSystemException f && f.getReason() != null ? f.getReason()
                    : Entries.message(e);
            throw new IOException("cannot read ignore file " + file + ": " + reason, e);
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return List.of(text.split("\r?\n", -1));
    }

    /** The rules of the lines, or null when there are none. */
    private static IgnoreNode node(List<String> lines, boolean ignoreCase) {
        List<FastIgnoreRule> rules = new ArrayList<>();
        for (String line : lines) {
            try {
                FastIgnoreRule rule = new FastIgnoreRule(ignoreCase ? line.toLowerCase(Locale.ROOT) : line);
                if (!rule.isEmpty()) {
                    rules.add(rule);
                }
            } catch (RuntimeException | Error e) {
                if (e instanceof VirtualMachineError) {
                    throw e;
                }
                // A malformed pattern is skipped like git does. JGit builds its error message from a resource
                // bundle that a native image does not have, which surfaces as an Error here.
            }
        }
        return rules.isEmpty() ? null : new IgnoreNode(rules);
    }
}
