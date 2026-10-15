package io.github.ghosthack.harnessmanager.state;

/**
 * The three sidebar-badge buckets the manager cares about. Trimmed from
 * ../harness-stimulator's six-value {@code HarnessState} (which also has
 * {@code WAITING_FOR_APPROVAL}/{@code WAITING_FOR_QUESTION} split out, and a
 * {@code BROKEN} state) — this app doesn't nudge or babysit, it just shows a
 * badge, so approval and question collapse into one "needs you" bucket, and a
 * harness that crashed but left tmux alive folds into IDLE (still gray; the
 * tooltip reason says why).
 */
public enum SessionState {
    WORKING,
    WAITING_FOR_INPUT,
    IDLE
}
