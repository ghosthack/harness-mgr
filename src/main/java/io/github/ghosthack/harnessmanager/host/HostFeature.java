package io.github.ghosthack.harnessmanager.host;

/**
 * What a {@link SessionHost} can do beyond the core contract. The UI gates on these
 * (never on {@code instanceof} or the host id): tmux has session options and a
 * copy-mode history the daemon does not, and harness-stimulator can only take over a
 * local tmux session.
 */
public enum HostFeature {
    /** {@link SessionHost#setMouseMode} is honoured. */
    MOUSE_OPTION,
    /** {@link SessionHost#setStatusBar} is honoured. */
    STATUS_BAR_OPTION,
    /** {@link SessionHost#scrollPage} / {@link SessionHost#jumpHistory} move the host's
     * own history; without it the terminal widget pages its local scrollback instead. */
    HISTORY_PAGING,
    /** harness-stimulator can attach its own client to the session (local tmux only). */
    STIMULATION
}
