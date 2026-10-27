package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.CharacterPattern.Matching;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SgrMousePatternTest {
    private static List<Character> chars(String text) {
        List<Character> list = new ArrayList<>();
        for (char c : text.toCharArray()) {
            list.add(c);
        }
        return list;
    }

    private static MouseAction decode(SgrMousePattern pattern, String report) {
        Matching matching = pattern.match(chars(report));
        return (MouseAction) matching.fullMatch;
    }

    @Test
    void pressAndReleaseCarryTheButtonAndAOneBasedPositionBecomesZeroBased() {
        SgrMousePattern pattern = new SgrMousePattern();
        MouseAction press = decode(pattern, "\u001b[<0;123;7M");
        assertEquals(MouseActionType.CLICK_DOWN, press.getActionType());
        assertEquals(1, press.getButton());
        assertEquals(new TerminalPosition(122, 6), press.getPosition());
        MouseAction release = decode(pattern, "\u001b[<0;123;7m");
        assertEquals(MouseActionType.CLICK_RELEASE, release.getActionType());
        assertEquals(new TerminalPosition(122, 6), release.getPosition());
    }

    @Test
    void columnsBeyondTheOldEncodingLimitAreExact() {
        MouseAction press = decode(new SgrMousePattern(), "\u001b[<2;300;40M");
        assertEquals(3, press.getButton());
        assertEquals(new TerminalPosition(299, 39), press.getPosition());
    }

    @Test
    void wheelAndMotionAreTold() {
        SgrMousePattern pattern = new SgrMousePattern();
        assertEquals(MouseActionType.SCROLL_UP, decode(pattern, "\u001b[<64;5;5M").getActionType());
        assertEquals(MouseActionType.SCROLL_DOWN, decode(pattern, "\u001b[<65;5;5M").getActionType());
        assertEquals(MouseActionType.MOVE, decode(pattern, "\u001b[<35;5;5M").getActionType());
        decode(pattern, "\u001b[<0;5;5M");
        MouseAction drag = decode(pattern, "\u001b[<32;6;5M");
        assertEquals(MouseActionType.DRAG, drag.getActionType());
        assertEquals(1, drag.getButton());
    }

    @Test
    void anIncompleteReportWaitsForTheRest() {
        SgrMousePattern pattern = new SgrMousePattern();
        for (String part : List.of("\u001b", "\u001b[", "\u001b[<", "\u001b[<0", "\u001b[<0;12", "\u001b[<0;12;")) {
            assertSame(Matching.NOT_YET, pattern.match(chars(part)), part);
        }
    }

    @Test
    void otherSequencesAreNotMouseReports() {
        SgrMousePattern pattern = new SgrMousePattern();
        assertNull(pattern.match(chars("\u001b[A")));
        assertNull(pattern.match(chars("\u001b[<x")));
        assertNull(pattern.match(chars("\u001b[<0;1;2;3M")));
        assertNull(pattern.match(chars("\u001b[<;1;2M")));
    }
}
