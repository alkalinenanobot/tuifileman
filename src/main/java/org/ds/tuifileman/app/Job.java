package org.ds.tuifileman.app;

import java.util.List;

/** A long running file operation: the worker thread runs it, the UI thread polls the progress. */
public interface Job {
    /** One failed item: the full path plus a short human readable reason. */
    record Problem(String path, String reason) { }

    /** Window title, e.g. "Copying". */
    String title();

    /** Short description of the current stage, e.g. "Deleting". */
    String phase();

    String current();

    /** False for operations that only count items (delete). */
    boolean tracksBytes();

    long bytesTotal();

    long bytesDone();

    int filesTotal();

    int filesDone();

    boolean isDone();

    boolean isCancelled();

    void cancel();

    void run();

    String summary();

    List<Problem> errors();
}
