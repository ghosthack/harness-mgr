package io.github.ghosthack.harnessmanager.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure text helpers behind the {@code read} tool: turning an annotated capture into the
 * lines an agent sees and the plain text state detection compares, working out which
 * lines are new since the caller's previous read, and capping the output.
 */
final class ScreenDiff {

    /** jmux's annotated-capture markers (see {@code HeadlessTerminal#annotatedText}). */
    static final String SELECTION_MARKER = "▶ ";
    static final String CURSOR_MARKER = "▮";

    /** An incremental read needs at least this many non-blank lines of overlap. */
    static final int MIN_OVERLAP = 3;
    /** More new lines than this share of the screen and the whole screen is returned. */
    static final double MAX_NEW_SHARE = 0.6;

    /** The new lines of a read and how many unchanged lines precede them on screen. */
    record Delta(int unchangedAbove, List<String> lines) {
    }

    private ScreenDiff() {
    }

    /** The screen's lines with trailing blank lines dropped (the empty rows below the
     * content), markers kept: what a read shows. */
    static List<String> bodyLines(String annotated) {
        List<String> lines = new ArrayList<>(annotated.lines().toList());
        while (!lines.isEmpty() && isBlank(lines.getLast())) {
            lines.removeLast();
        }
        return lines;
    }

    /** The annotated capture back as jmux's plain {@code text} capture: no selection
     * prefix, no cursor marker, no trailing whitespace — what the harness plugins'
     * patterns and the state detector were written against. */
    static String plain(String annotated) {
        StringBuilder sb = new StringBuilder(annotated.length());
        annotated.lines().forEach(line -> {
            String l = line.startsWith(SELECTION_MARKER) ? line.substring(SELECTION_MARKER.length()) : line;
            sb.append(l.replace(CURSOR_MARKER, "").stripTrailing()).append('\n');
        });
        return sb.toString();
    }

    /** A plain-text capture with trailing whitespace dropped from every line, as
     * {@link #plain} leaves it — for a daemon that only gives plain text, whose real
     * {@code ▶ }/{@code ▮} characters must not be taken for markers. */
    static String trimmed(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.lines().forEach(line -> sb.append(line.stripTrailing()).append('\n'));
        return sb.toString();
    }

    /**
     * The lines of {@code current} that are new since {@code previous}, or empty when the
     * whole screen should be shown. The overlap is the longest leading block of
     * {@code current} (at least {@value #MIN_OVERLAP} non-blank lines) that occurs as a
     * contiguous block in {@code previous}: output scrolled up by the new lines below it.
     * Everything after that block is returned — new output, and the prompt or footer
     * area, changed or not. No such overlap, nothing after it, or more than
     * {@value #MAX_NEW_SHARE} of the screen new: empty.
     */
    static Optional<Delta> newLines(List<String> previous, List<String> current) {
        int size = current.size();
        int maxNew = (int) Math.floor(size * MAX_NEW_SHARE);
        for (int k = size - 1; k >= 1 && size - k <= maxNew; k--) {
            List<String> prefix = current.subList(0, k);
            if (nonBlank(prefix) < MIN_OVERLAP) {
                break; // shorter prefixes only have fewer
            }
            if (occursIn(previous, prefix)) {
                return Optional.of(new Delta(k, List.copyOf(current.subList(k, size))));
            }
        }
        return Optional.empty();
    }

    /**
     * {@code lines} joined, keeping the tail within {@code maxChars}: when lines must go,
     * the oldest are dropped and a {@code "… (N earlier lines omitted)"} line leads.
     */
    static String cap(List<String> lines, int maxChars) {
        String joined = String.join("\n", lines);
        if (joined.length() <= maxChars) {
            return joined;
        }
        int budget = maxChars - 40; // room for the omission line
        int used = 0;
        int first = lines.size();
        while (first > 0 && used + lines.get(first - 1).length() + 1 <= budget) {
            first--;
            used += lines.get(first).length() + 1;
        }
        List<String> kept = new ArrayList<>(lines.subList(first, lines.size()));
        if (kept.isEmpty()) {
            String last = lines.getLast();
            kept.add(last.substring(Math.max(0, last.length() - budget)));
            first = lines.size() - 1;
        }
        return "… (" + first + " earlier lines omitted)\n" + String.join("\n", kept);
    }

    private static boolean occursIn(List<String> haystack, List<String> block) {
        outer:
        for (int start = 0; start + block.size() <= haystack.size(); start++) {
            for (int i = 0; i < block.size(); i++) {
                if (!haystack.get(start + i).equals(block.get(i))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int nonBlank(List<String> lines) {
        int n = 0;
        for (String line : lines) {
            if (!isBlank(line)) {
                n++;
            }
        }
        return n;
    }

    private static boolean isBlank(String line) {
        return line.replace(CURSOR_MARKER, "").isBlank();
    }
}
