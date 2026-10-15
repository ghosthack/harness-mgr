package io.github.ghosthack.harnessmanager.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The shared mechanics behind {@link HarnessPlugin#maskChrome}: line splitting, the
 * annotated-capture markers, and blank-run collapsing, so each plugin only states which
 * lines are its chrome.
 *
 * <p>Annotated captures (jmux's {@code capture --format annotated}) prefix a
 * highlighted row with {@code "▶ "} and insert {@code "▮"} at the cursor. Chrome tests
 * see each line without either, because the plugins' patterns are anchored at the
 * line start and a cursor parked in the composer must not hide it; the output keeps the
 * selection prefix (a moved selection is a real change) and drops the cursor marker.
 */
final class ScreenMask {

    /** jmux's selection-row prefix ({@code HeadlessTerminal.SELECTION_MARKER}). */
    static final String SELECTION_MARKER = "▶ ";

    /** jmux's cursor marker ({@code HeadlessTerminal.CURSOR_MARKER}). */
    static final String CURSOR_MARKER = "▮";

    /** The screen's lines, cursor marker removed, with no trailing empty element. */
    static List<String> lines(String screenText) {
        String text = screenText.replace(CURSOR_MARKER, "");
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    /** {@code line} as the plugins' chrome patterns expect it: no selection prefix. */
    static String plain(String line) {
        return line.startsWith(SELECTION_MARKER) ? line.substring(SELECTION_MARKER.length()) : line;
    }

    /** Drops every line whose {@linkplain #plain plain} form {@code chrome} accepts, then
     * {@linkplain #join joins} the rest. */
    static String mask(String screenText, Predicate<String> chrome) {
        return join(lines(screenText).stream().filter(line -> !chrome.test(plain(line))).toList());
    }

    /** Joins with {@code "\n"}, trailing whitespace stripped, runs of blank lines
     * collapsed to one, and no blank line at either end. */
    static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        boolean pendingBlank = false;
        for (String line : lines) {
            String trimmed = line.stripTrailing();
            if (trimmed.isBlank()) {
                pendingBlank = !out.isEmpty();
                continue;
            }
            if (!out.isEmpty()) {
                out.append(pendingBlank ? "\n\n" : "\n");
            }
            out.append(trimmed);
            pendingBlank = false;
        }
        return out.toString();
    }

    /** Index of the last non-blank line at or before {@code from}, or -1. */
    static int lastNonBlank(List<String> lines, int from) {
        for (int i = Math.min(from, lines.size() - 1); i >= 0; i--) {
            if (!plain(lines.get(i)).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code command} with {@code flags} inserted right after its leading
     * {@code program} token, or unchanged when the command does not start with that
     * token — the shape every per-run MCP injection takes, since the flags are global
     * options that must precede any subcommand or resume selector.
     */
    static String insertAfterProgram(String command, String program, String flags) {
        if (command.equals(program)) {
            return program + " " + flags;
        }
        if (command.startsWith(program + " ")) {
            return program + " " + flags + command.substring(program.length());
        }
        return command;
    }

    /** {@code value} as a JSON (and TOML basic) string literal, quotes included. */
    static String quoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private ScreenMask() {
    }
}
