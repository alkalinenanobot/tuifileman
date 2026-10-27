package org.ds.tuifileman.cli;

/** Process exit codes of the command-line mode; scripts rely on them, so they never change meaning. */
public final class Exit {
    /** Success; for {@code diff} also "the trees are identical", for {@code find} "something matched". */
    public static final int OK = 0;
    /** {@code diff} found differences. */
    public static final int DIFFERENT = 1;
    /** {@code find} matched nothing (like grep). */
    public static final int NO_MATCH = 1;
    /** Bad command line. */
    public static final int USAGE = 2;
    /** The operation ran to the end but some files failed. */
    public static final int PARTIAL = 3;
    /** Fatal I/O error, for example an unreadable root. */
    public static final int FATAL = 4;
    /** Cancelled with Ctrl+C. */
    public static final int CANCELLED = 130;

    private Exit() { }
}
