package io.github.ghosthack.harnessmanager.state;

import java.time.Instant;
import java.util.List;

/**
 * One capture of a tmux pane's rendered text, plus the pane's foreground process name
 * ({@code pane_current_command}) when tmux could report it. Adapted (in shape) from
 * ../harness-stimulator's {@code Screen}; adds {@code paneTitle} — the title the program
 * in the pane set via OSC (e.g. Claude Code's /rename), empty when the pane still has
 * tmux's hostname default or tmux couldn't report it. {@code paneProcessId} is tmux's
 * pane-shell PID, used to discover provider metadata without changing pane contents.
 */
public record Screen(String text, boolean paneDead, Instant capturedAt, String foregroundCommand,
                     long paneProcessId, String paneTitle) {

    public Screen(String text, boolean paneDead, Instant capturedAt) {
        this(text, paneDead, capturedAt, "", -1, "");
    }

    public Screen(String text, boolean paneDead, Instant capturedAt, String foregroundCommand) {
        this(text, paneDead, capturedAt, foregroundCommand, -1, "");
    }

    public Screen(String text, boolean paneDead, Instant capturedAt, String foregroundCommand,
                  String paneTitle) {
        this(text, paneDead, capturedAt, foregroundCommand, -1, paneTitle);
    }

    /** Last {@code n} non-blank lines, oldest first — the region prompts live in. */
    public List<String> tailLines(int n) {
        List<String> nonBlank = text.lines().filter(l -> !l.isBlank()).toList();
        return nonBlank.subList(Math.max(0, nonBlank.size() - n), nonBlank.size());
    }
}
