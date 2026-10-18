package org.ds.tuifileman.compare;

import org.ds.tuifileman.fs.Entries;
import org.ds.tuifileman.fs.Parallel;
import org.ds.tuifileman.ignore.IgnoreMatcher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Compares two directory trees item by item. Items are listed in tree order (a directory before its
 * content); directories present on both sides are only walked, not listed. Symbolic links and
 * junctions are skipped so a sync can never walk out of the trees.
 */
public final class DirCompare {
    /** File times closer than this count as equal (FAT/exFAT store 2 s resolution). */
    public static final long TIME_TOLERANCE_MILLIS = TimeTolerance.DEFAULT_MILLIS;

    /**
     * {@code IGNORED} items are entries the ignore rules leave out: they are listed (a folder as one item, its
     * content is not scanned) but never compared, and no action is possible on them.
     */
    public enum Status { ONLY_LEFT, ONLY_RIGHT, LEFT_NEWER, RIGHT_NEWER, DIFFERENT, SAME, TYPE_MISMATCH, IGNORED }

    public enum Action { NONE, TO_RIGHT, TO_LEFT, DELETE_LEFT, DELETE_RIGHT }

    public enum Mode { MERGE, MIRROR_TO_RIGHT, MIRROR_TO_LEFT, CLEAR }

    /** One side of an item; {@code modified} is in epoch milliseconds. */
    public record Side(boolean directory, long size, long modified) { }

    public static final class Item {
        private final String relative;
        private final Side left;
        private final Side right;
        private final Status status;
        private final int ignoredBy;
        private Action action = Action.NONE;

        Item(String relative, Side left, Side right, Status status) {
            this(relative, left, right, status, 0);
        }

        Item(String relative, Side left, Side right, Status status, int ignoredBy) {
            this.relative = relative;
            this.left = left;
            this.right = right;
            this.status = status;
            this.ignoredBy = ignoredBy;
        }

        /** Path below the compared roots, with backslashes. */
        public String relative() {
            return relative;
        }

        public Side left() {
            return left;
        }

        public Side right() {
            return right;
        }

        public Status status() {
            return status;
        }

        /** For {@code IGNORED} items: {@link IgnoreMatcher#BY_LEFT} and/or {@link IgnoreMatcher#BY_RIGHT}; else 0. */
        public int ignoredBy() {
            return ignoredBy;
        }

        public Action action() {
            return action;
        }

        public void setAction(Action action) {
            this.action = action;
        }

        public boolean directory() {
            return (left != null && left.directory()) || (right != null && right.directory());
        }

        /** Whether the action is possible at all: sources must exist, delete targets must exist. */
        public boolean allows(Action candidate) {
            if (status == Status.IGNORED) {
                return candidate == Action.NONE;
            }
            return switch (candidate) {
                case NONE -> true;
                case TO_RIGHT -> left != null && status != Status.TYPE_MISMATCH;
                case TO_LEFT -> right != null && status != Status.TYPE_MISMATCH;
                case DELETE_LEFT -> left != null;
                case DELETE_RIGHT -> right != null;
            };
        }
    }

    private DirCompare() { }
    private static final int COMPARE_BUFFER = 512 * 1024;

    /**
     * Scans both trees. {@code contents} compares the bytes of same-size files; {@code cancel} stops
     * the scan (the partial list is returned); {@code progress} counts examined entries. Directories
     * are scanned in parallel; the result is still in tree order.
     */
    public static List<Item> scan(Path left, Path right, boolean recursive, boolean contents,
                                  AtomicBoolean cancel, AtomicInteger progress) throws IOException {
        return scan(left, right, recursive, contents, cancel, progress, IgnoreMatcher.NONE, new AtomicInteger());
    }

    /**
     * As above, but entries {@code ignore} rejects become {@code IGNORED} items on both sides (a folder is one
     * item and is not entered, in a shallow scan folders are not listed); {@code ignored} counts them (once per
     * name, however many sides have it).
     */
    public static List<Item> scan(Path left, Path right, boolean recursive, boolean contents,
                                  AtomicBoolean cancel, AtomicInteger progress, IgnoreMatcher ignore,
                                  AtomicInteger ignored) throws IOException {
        return scan(left, right, recursive, contents, cancel, progress, ignore, ignored, relative -> false);
    }

    /**
     * As above, and {@code exclude} (given the relative path) drops entries altogether: no item, not counted
     * as ignored, and an excluded folder is not even listed, so its content costs nothing.
     */
    public static List<Item> scan(Path left, Path right, boolean recursive, boolean contents,
                                  AtomicBoolean cancel, AtomicInteger progress, IgnoreMatcher ignore,
                                  AtomicInteger ignored, Predicate<String> exclude) throws IOException {
        return scan(left, right, recursive, contents, cancel, progress, ignore, ignored, exclude,
                TIME_TOLERANCE_MILLIS, new ConcurrentLinkedQueue<>());
    }

    /**
     * As above, and two files whose times differ by at most {@code toleranceMillis} count as having the same
     * time (the default is {@link #TIME_TOLERANCE_MILLIS}; 0 means exactly equal). Folders below the roots that
     * cannot be listed, and entries whose attributes cannot be read, are left out on both sides and added to
     * {@code unreadable}: the result is then incomplete. An unreadable root throws.
     */
    public static List<Item> scan(Path left, Path right, boolean recursive, boolean contents,
                                  AtomicBoolean cancel, AtomicInteger progress, IgnoreMatcher ignore,
                                  AtomicInteger ignored, Predicate<String> exclude, long toleranceMillis,
                                  Queue<Unreadable> unreadable) throws IOException {
        Context context = new Context(recursive, contents, cancel, progress, ignored, exclude, toleranceMillis,
                unreadable);
        ForkJoinPool pool = new ForkJoinPool(Parallel.threads(left, right));
        try {
            DirTask root = new DirTask(context, left, right, "", ignore);
            List<Item> items = pool.invoke(root);
            if (root.error != null) {
                throw root.error;
            }
            return items;
        } finally {
            pool.shutdownNow();
        }
    }

    /** An entry the scan could not read; what is below it is missing on both sides of the result. */
    public record Unreadable(Path path, String reason) { }

    private record Context(boolean recursive, boolean contents, AtomicBoolean cancel, AtomicInteger progress,
                           AtomicInteger ignored, Predicate<String> exclude, long toleranceMillis,
                           Queue<Unreadable> unreadable) { }

    /** Both sides of one name; {@code skip} marks links and unreadable entries (skipped on both sides). */
    private static final class Pair {
        final Path[] path = new Path[2];
        final BasicFileAttributes[] attrs = new BasicFileAttributes[2];
        boolean skip;
    }

    /** An item whose status is still being worked out by a content comparison. */
    private record Pending(String relative, Side left, Side right, ContentTask task) { }

    private static final class DirTask extends RecursiveTask<List<Item>> {
        private final Context context;
        private final Path left;
        private final Path right;
        private final String prefix;
        private final IgnoreMatcher ignore;
        IOException error;

        DirTask(Context context, Path left, Path right, String prefix, IgnoreMatcher ignore) {
            this.context = context;
            this.left = left;
            this.right = right;
            this.prefix = prefix;
            this.ignore = ignore;
        }

        @Override
        protected List<Item> compute() {
            Map<String, Pair> leftNames;
            Map<String, Pair> rightNames;
            try {
                leftNames = list(left, 0, context.unreadable());
            } catch (IOException e) {
                return unreadable(left, e);
            }
            try {
                rightNames = list(right, 1, context.unreadable());
            } catch (IOException e) {
                return unreadable(right, e);
            }
            TreeMap<String, Pair> names = merge(leftNames, rightNames);
            List<Object> slots = new ArrayList<>();
            for (var entry : names.entrySet()) {
                if (context.cancel().get()) {
                    break;
                }
                context.progress().incrementAndGet();
                Pair pair = entry.getValue();
                if (pair.skip) {
                    continue;
                }
                String relative = prefix + entry.getKey();
                if (context.exclude().test(relative)) {
                    continue;
                }
                Side ls = pair.attrs[0] == null ? null : side(pair.attrs[0]);
                Side rs = pair.attrs[1] == null ? null : side(pair.attrs[1]);
                int by = ignoredBy(entry.getKey(), pair);
                if (by != 0) {
                    context.ignored().incrementAndGet();
                    if (context.recursive() || !(ls != null ? ls : rs).directory()) {
                        slots.add(new Item(relative, ls, rs, Status.IGNORED, by));
                    }
                    continue;
                }
                if (ls != null && rs != null) {
                    both(slots, relative, pair, ls, rs);
                    continue;
                }
                boolean directory = (ls != null ? ls : rs).directory();
                if (directory && !context.recursive()) {
                    continue;
                }
                slots.add(new Item(relative, ls, rs, ls != null ? Status.ONLY_LEFT : Status.ONLY_RIGHT));
                if (directory) {
                    slots.add(child(relative, pair));
                }
            }
            List<Item> out = new ArrayList<>();
            for (Object slot : slots) {
                if (slot instanceof Item item) {
                    out.add(item);
                } else if (slot instanceof Pending pending) {
                    out.add(new Item(pending.relative(), pending.left(), pending.right(), pending.task().join()));
                } else {
                    out.addAll(((DirTask) slot).join());
                }
            }
            return out;
        }

        private void both(List<Object> slots, String relative, Pair pair, Side ls, Side rs) {
            if (ls.directory() && rs.directory()) {
                if (context.recursive()) {
                    slots.add(child(relative, pair));
                }
            } else if (ls.directory() != rs.directory()) {
                slots.add(new Item(relative, ls, rs, Status.TYPE_MISMATCH));
            } else if (context.contents() && ls.size() == rs.size()) {
                ContentTask task = new ContentTask(context.cancel(), pair.path[0], pair.path[1], ls, rs,
                        context.toleranceMillis());
                task.fork();
                slots.add(new Pending(relative, ls, rs, task));
            } else {
                slots.add(new Item(relative, ls, rs, byTime(ls, rs, false, context.toleranceMillis())));
            }
        }

        private int ignoredBy(String name, Pair pair) {
            int by = 0;
            for (BasicFileAttributes attrs : pair.attrs) {
                if (attrs != null) {
                    by |= ignore.ignoredBy(name, attrs.isDirectory());
                }
            }
            return by;
        }

        private DirTask child(String relative, Pair pair) {
            String name = relative.substring(prefix.length());
            DirTask task = new DirTask(context, pair.path[0], pair.path[1], relative + "/",
                    ignore.enter(name, pair.path[0], pair.path[1]));
            task.fork();
            return task;
        }

        /**
         * Only the roots must be readable. Below them the pair is left out on both sides, not only on the
         * unreadable one: listing the other side alone would make its entries look one-sided, and a mirror
         * would delete them.
         */
        private List<Item> unreadable(Path dir, IOException e) {
            if (prefix.isEmpty()) {
                error = e;
            } else {
                context.unreadable().add(new Unreadable(dir, reason(e)));
            }
            return List.of();
        }
    }

    /** Compares the bytes of two same-size files. */
    private static final class ContentTask extends RecursiveTask<Status> {
        private final AtomicBoolean cancel;
        private final Path left;
        private final Path right;
        private final Side ls;
        private final Side rs;
        private final long toleranceMillis;

        ContentTask(AtomicBoolean cancel, Path left, Path right, Side ls, Side rs, long toleranceMillis) {
            this.cancel = cancel;
            this.left = left;
            this.right = right;
            this.ls = ls;
            this.rs = rs;
            this.toleranceMillis = toleranceMillis;
        }

        @Override
        protected Status compute() {
            try {
                return sameBytes(left, right, cancel) ? Status.SAME : byTime(ls, rs, true, toleranceMillis);
            } catch (IOException e) {
                return Status.DIFFERENT;
            }
        }
    }

    /** One pair of compare buffers per worker thread: allocating 1 MB for every file dominated small-file compares. */
    private static final ThreadLocal<byte[][]> BUFFERS =
            ThreadLocal.withInitial(() -> new byte[][] {new byte[COMPARE_BUFFER], new byte[COMPARE_BUFFER]});

    private static boolean sameBytes(Path a, Path b, AtomicBoolean cancel) throws IOException {
        try (InputStream in1 = Files.newInputStream(a); InputStream in2 = Files.newInputStream(b)) {
            byte[][] buffers = BUFFERS.get();
            byte[] x = buffers[0];
            byte[] y = buffers[1];
            while (!cancel.get()) {
                int n = in1.readNBytes(x, 0, x.length);
                int m = in2.readNBytes(y, 0, y.length);
                if (n != m || !Arrays.equals(x, 0, n, y, 0, m)) {
                    return false;
                }
                if (n < x.length) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Tree order: case-insensitive, and names that differ only in letter case (possible on Linux) next to each
     * other in a fixed order.
     */
    private static final Comparator<String> ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    /** Reads the entries of one directory (exact names) with the attributes the enumeration returned. */
    private static Map<String, Pair> list(Path dir, int slot, Queue<Unreadable> unreadable) throws IOException {
        Map<String, Pair> names = new HashMap<>();
        if (dir == null) {
            return names;
        }
        IOException[] failure = {null};
        Entries.list(dir, new Entries.Handler() {
            @Override
            public void entry(Path path, BasicFileAttributes attrs) {
                Pair pair = names.computeIfAbsent(path.getFileName().toString(), k -> new Pair());
                pair.path[slot] = path;
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    pair.skip = true;
                } else {
                    pair.attrs[slot] = attrs;
                }
            }

            @Override
            public void failed(Path path, IOException error) {
                if (path.equals(dir)) {
                    failure[0] = error;
                } else if (path.getFileName() != null) {
                    names.computeIfAbsent(path.getFileName().toString(), k -> new Pair()).skip = true;
                    unreadable.add(new Unreadable(path, reason(error)));
                }
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return names;
    }

    private static String reason(IOException e) {
        if (e instanceof AccessDeniedException) {
            return "access denied";
        }
        if (e instanceof NoSuchFileException) {
            return "no longer exists";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Pairs the two sides by exact name. A name that differs only in letter case is still paired when it is
     * unambiguous, one spelling per side ({@code Readme.txt} and {@code README.TXT} are one file on Windows);
     * two spellings on one side ({@code Foo} and {@code foo} on Linux) are never merged.
     */
    private static TreeMap<String, Pair> merge(Map<String, Pair> left, Map<String, Pair> right) {
        TreeMap<String, Pair> names = new TreeMap<>(ORDER);
        names.putAll(left);
        if (right.isEmpty()) {
            return names;
        }
        Map<String, String> leftSpelling = spellings(left);
        Map<String, String> rightSpelling = spellings(right);
        for (var entry : right.entrySet()) {
            String name = entry.getKey();
            Pair pair = names.get(name);
            if (pair == null) {
                // No exact match: pair a different spelling only when each side has just that one.
                String folded = name.toLowerCase(Locale.ROOT);
                String other = leftSpelling.get(folded);
                if (other != null && name.equals(rightSpelling.get(folded))) {
                    pair = names.get(other);
                }
            }
            if (pair == null) {
                names.put(name, entry.getValue());
            } else {
                Pair from = entry.getValue();
                pair.path[1] = from.path[1];
                pair.attrs[1] = from.attrs[1];
                pair.skip |= from.skip;
            }
        }
        return names;
    }

    /** Folded name to its one spelling; null value when one side has several spellings of it. */
    private static Map<String, String> spellings(Map<String, Pair> side) {
        Map<String, String> spelling = new HashMap<>();
        for (String name : side.keySet()) {
            spelling.merge(name.toLowerCase(Locale.ROOT), name, (a, b) -> AMBIGUOUS);
        }
        return spelling;
    }

    /** Marks a folded name with several spellings on one side; never equal to a real spelling. */
    private static final String AMBIGUOUS = "/";

    private static Side side(BasicFileAttributes attrs) {
        return new Side(attrs.isDirectory(), attrs.isDirectory() ? 0 : attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    /** Status of two files from size and time alone; {@code sameSize} files are SAME when the times match. */
    private static Status byTime(Side ls, Side rs, boolean contentsDiffer, long toleranceMillis) {
        long delta = ls.modified() - rs.modified();
        boolean sameTime = Math.abs(delta) <= toleranceMillis;
        if (sameTime) {
            return ls.size() == rs.size() && !contentsDiffer ? Status.SAME : Status.DIFFERENT;
        }
        return delta > 0 ? Status.LEFT_NEWER : Status.RIGHT_NEWER;
    }

    /**
     * Builds the item of one relative path from the current state of both trees, to apply a stored plan.
     * {@code status} (nullable) stands in for the size/time comparison of two files, e.g. when the plan came
     * from a content compare. Returns null when the path exists on neither side.
     */
    public static Item itemFor(Path leftRoot, Path rightRoot, String relative, Status status) throws IOException {
        return itemFor(leftRoot, rightRoot, relative, status, TIME_TOLERANCE_MILLIS);
    }

    /** As above with an explicit time tolerance in milliseconds. */
    public static Item itemFor(Path leftRoot, Path rightRoot, String relative, Status status, long toleranceMillis)
            throws IOException {
        Side ls = stat(leftRoot.resolve(relative));
        Side rs = stat(rightRoot.resolve(relative));
        if (ls == null && rs == null) {
            return null;
        }
        Status computed;
        if (rs == null) {
            computed = Status.ONLY_LEFT;
        } else if (ls == null) {
            computed = Status.ONLY_RIGHT;
        } else if (ls.directory() != rs.directory()) {
            computed = Status.TYPE_MISMATCH;
        } else if (ls.directory()) {
            computed = Status.SAME;
        } else {
            computed = status != null ? status : byTime(ls, rs, false, toleranceMillis);
        }
        return new Item(relative, ls, rs, computed);
    }

    private static Side stat(Path path) throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IOException("links and junctions are not synchronized: " + path);
            }
            return side(attrs);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** Action a mode proposes for one item. */
    public static Action proposed(Item item, Mode mode) {
        Action action = switch (mode) {
            case CLEAR -> Action.NONE;
            case MERGE -> switch (item.status()) {
                case ONLY_LEFT, LEFT_NEWER -> Action.TO_RIGHT;
                case ONLY_RIGHT, RIGHT_NEWER -> Action.TO_LEFT;
                default -> Action.NONE;
            };
            case MIRROR_TO_RIGHT -> switch (item.status()) {
                case ONLY_LEFT, LEFT_NEWER, RIGHT_NEWER, DIFFERENT -> Action.TO_RIGHT;
                case ONLY_RIGHT -> Action.DELETE_RIGHT;
                default -> Action.NONE;
            };
            case MIRROR_TO_LEFT -> switch (item.status()) {
                case ONLY_RIGHT, LEFT_NEWER, RIGHT_NEWER, DIFFERENT -> Action.TO_LEFT;
                case ONLY_LEFT -> Action.DELETE_LEFT;
                default -> Action.NONE;
            };
        };
        return item.allows(action) ? action : Action.NONE;
    }

    public static void apply(List<Item> items, Mode mode) {
        for (Item item : items) {
            item.setAction(proposed(item, mode));
        }
    }
}
