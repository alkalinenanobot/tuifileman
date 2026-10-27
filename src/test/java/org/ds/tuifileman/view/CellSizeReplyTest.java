package org.ds.tuifileman.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.googlecode.lanterna.input.CharacterPattern.Matching;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CellSizeReplyTest {
    private static List<Character> chars(String text) {
        List<Character> list = new ArrayList<>();
        for (char c : text.toCharArray()) {
            list.add(c);
        }
        return list;
    }

    @Test
    void theAnswerIsHeightThenWidth() {
        Matching matching = new CellSize.Reply().match(chars("\u001b[6;17;7t"));
        CellSize.Report report = (CellSize.Report) matching.fullMatch;
        assertEquals(7, report.width);
        assertEquals(17, report.height);
    }

    @Test
    void aPartialAnswerWaitsForTheRest() {
        assertSame(Matching.NOT_YET, new CellSize.Reply().match(chars("\u001b[6;17;")));
    }

    @Test
    void ctrlPageDownIsNotAnAnswer() {
        assertNull(new CellSize.Reply().match(chars("\u001b[6;5~")));
    }

    @Test
    void zeroOrAbsurdSizesAreNotAnswers() {
        assertNull(new CellSize.Reply().match(chars("\u001b[6;0;0t")));
        assertNull(new CellSize.Reply().match(chars("\u001b[6;99999;7t")));
    }
}
