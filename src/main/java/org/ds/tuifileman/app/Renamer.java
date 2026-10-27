package org.ds.tuifileman.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies a batch of renames. Names that are free only after another rename of the same batch
 * (swaps, shifts, case-only changes) go through a temporary name first, so the order inside the
 * batch does not matter.
 */
public final class Renamer {

    public record Move(Path from, String toName) { }

    /** A rename that happened: {@code original} was renamed to {@code now}. */
    public record Applied(Path original, Path now) { }

    public record Result(List<Applied> applied, List<Job.Problem> problems) { }

    private Renamer() { }

    public static Result apply(List<Move> moves) {
        List<Applied> applied = new ArrayList<>();
        List<Job.Problem> problems = new ArrayList<>();
        Set<String> vacated = new HashSet<>();
        Set<String> wanted = new HashSet<>();
        for (Move move : moves) {
            vacated.add(key(move.from()));
            wanted.add(key(move.from().resolveSibling(move.toName())));
        }
        Path[] current = new Path[moves.size()];
        boolean[] live = new boolean[moves.size()];
        for (int i = 0; i < moves.size(); i++) {
            Move move = moves.get(i);
            current[i] = move.from();
            live[i] = true;
            // a name that another move wants, or that is wanted from a name another move frees, goes through a temp name
            if (vacated.contains(key(move.from().resolveSibling(move.toName()))) || wanted.contains(key(move.from()))) {
                Path temp = move.from().resolveSibling(".tfm-rename-" + System.nanoTime() + "-" + i + ".tmp");
                try {
                    Files.move(move.from(), temp);
                    current[i] = temp;
                } catch (IOException e) {
                    problems.add(new Job.Problem(move.from().toString(), DeleteJob.reason(e)));
                    live[i] = false;
                }
            }
        }
        for (int i = 0; i < moves.size(); i++) {
            if (!live[i]) {
                continue;
            }
            Move move = moves.get(i);
            Path target = move.from().resolveSibling(move.toName());
            try {
                Files.move(current[i], target);
                applied.add(new Applied(move.from(), target));
            } catch (IOException e) {
                problems.add(new Job.Problem(move.from() + "  ->  " + move.toName(), DeleteJob.reason(e)));
                if (!current[i].equals(move.from())) {
                    try {
                        Files.move(current[i], move.from());
                    } catch (IOException restore) {
                        problems.add(new Job.Problem(current[i].toString(),
                                "Could not restore the original name: " + DeleteJob.reason(restore)));
                    }
                }
            }
        }
        return new Result(applied, problems);
    }

    /** The moves that revert a previous result. */
    public static List<Move> undoMoves(List<Applied> applied) {
        List<Move> moves = new ArrayList<>();
        for (int i = applied.size() - 1; i >= 0; i--) {
            Applied a = applied.get(i);
            moves.add(new Move(a.now(), a.original().getFileName().toString()));
        }
        return moves;
    }

    private static String key(Path path) {
        return path.toString().toLowerCase(Locale.ROOT);
    }
}
