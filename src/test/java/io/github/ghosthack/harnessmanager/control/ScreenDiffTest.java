package io.github.ghosthack.harnessmanager.control;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The read tool's text helpers. */
final class ScreenDiffTest {

    private static List<String> rows(String... rows) {
        return List.of(rows);
    }

    @Test
    void plainDropsMarkersAndTheCursorPadding() {
        assertEquals("  1. Yes\n  2. No\n> hi\n",
                ScreenDiff.plain("▶   1. Yes\n  2. No\n> hi   ▮\n"));
    }

    @Test
    void bodyLinesTrimsOnlyTrailingBlankRows() {
        assertEquals(rows("", "a", "", "b"), ScreenDiff.bodyLines("\na\n\nb\n\n  \n▮\n"));
    }

    @Test
    void scrolledOutputYieldsTheLinesBelowTheOverlapIncludingAChangedFooter() {
        List<String> before = rows("a", "b", "c", "d", "e", "f", "g", "h", "› ", "ctx 50% left");
        List<String> after = rows("c", "d", "e", "f", "g", "h", "i", "j", "› ▮", "ctx 48% left");
        ScreenDiff.Delta delta = ScreenDiff.newLines(before, after).orElseThrow();
        assertEquals(6, delta.unchangedAbove());
        assertEquals(rows("i", "j", "› ▮", "ctx 48% left"), delta.lines());
    }

    @Test
    void aChangeBelowTheOverlapIsKept() {
        List<String> before = rows("a", "b", "c", "d", "e");
        List<String> after = rows("a", "b", "c", "X", "e");
        assertEquals(Optional.of(new ScreenDiff.Delta(3, rows("X", "e"))), ScreenDiff.newLines(before, after));
    }

    @Test
    void wholeScreenWhenTheOverlapIsTooShortOrTheChangeTooBig() {
        // a changed first line: no leading overlap at all
        assertTrue(ScreenDiff.newLines(rows("t1", "a", "b", "c"), rows("t2", "a", "b", "c")).isEmpty());
        // two lines of overlap are not enough
        assertTrue(ScreenDiff.newLines(rows("a", "b", "c"), rows("b", "c", "d", "e")).isEmpty());
        // blank rows do not count toward the overlap
        assertTrue(ScreenDiff.newLines(rows("", "", "", "a", "b"), rows("", "", "", "a", "X")).isEmpty());
        // more than 60% new
        List<String> before = rows("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        List<String> after = rows("1", "2", "3", "x", "x", "x", "x", "x", "x", "x");
        assertTrue(ScreenDiff.newLines(before, after).isEmpty());
        // nothing after the overlap: lines vanished, show it all
        assertTrue(ScreenDiff.newLines(rows("a", "b", "c", "d"), rows("a", "b", "c")).isEmpty());
    }

    @Test
    void capKeepsTheTailAndCountsWhatWasDropped() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines.add("row " + i + " " + "-".repeat(90));
        }
        String capped = ScreenDiff.cap(lines, 1000);
        assertTrue(capped.length() <= 1000, "" + capped.length());
        int kept = (int) capped.lines().count() - 1;
        assertEquals("… (" + (100 - kept) + " earlier lines omitted)", capped.lines().findFirst().orElseThrow());
        assertTrue(capped.endsWith(lines.getLast()));
        assertEquals("short", ScreenDiff.cap(List.of("short"), 1000));
        String oneHuge = ScreenDiff.cap(List.of("y".repeat(5000)), 1000);
        assertTrue(oneHuge.length() <= 1000);
    }
}
