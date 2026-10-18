package org.ds.tuifileman.view;

import com.googlecode.lanterna.screen.Screen;
import java.nio.file.Path;
import java.util.List;

/**
 * Something the F3 viewer can show through the image path although it is not an image itself: a page of a
 * PDF, a frame of a video. The source renders one picture to a PNG; everything else here only describes it.
 */
interface PreviewSource {
    /** Tag in the header and status line, such as "PDF" or "Video". */
    String kind();

    /** Current position (1-based page or frame). */
    int position();

    void setPosition(int position);

    /** Number of positions, 0 when unknown. */
    int count();

    /** Position text for the status line, such as "page 3/19"; may be empty. */
    String positionLabel();

    /** Further facts for the status line, such as "595x842 pts"; may be empty. */
    String describe();

    /** Footer text about moving between positions; may be empty. */
    String navigationHint();

    /** The footer text for the Tab key. */
    String tabHint();

    /** Renders the current position to a PNG of the given height in pixels. */
    Path render(int pixelHeight);

    /** Details (label, value) for the info page. */
    List<String[]> infoRows();

    /** Why the details are missing, or null. */
    String infoError();

    /** Text shown under "No preview" when a tool is missing: where to get it and which setting names it. */
    String installHint();

    /** True when P hands the terminal to an external player. */
    default boolean canPlay() {
        return false;
    }

    /** Plays the file, giving the terminal to the player; returns a message or null. */
    default String play(Screen screen) {
        return null;
    }

    /** Releases temporary files. */
    void close();

    /** A tool is not installed or not configured: the viewer then points at the setup. */
    class ToolMissing extends IllegalStateException {
        ToolMissing(String message) {
            super(message);
        }
    }

    /** There is nothing to draw (an audio file without cover art); the info page is the whole view. */
    class NoPicture extends IllegalStateException {
        NoPicture(String message) {
            super(message);
        }
    }
}
