package io.github.ghosthack.harnessmanager.host;

import io.github.ghosthack.harnessmanager.state.Screen;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

/**
 * A substrate that hosts named sessions: tmux (local, or over SSH) or the
 * jmux daemon. Identified by a stable {@link #id()} that the session
 * registry persists per row. This is the seam the rest of the app talks through —
 * status polling, graceful close, reconciliation and the terminal widget never learn
 * which substrate is underneath. Adapted from the former {@code Tmux} interface.
 *
 * <p>"No server running" semantics, identical on every host: {@link #listSessions} is
 * empty, {@link #hasSession} is false, {@link #capture} reports a dead pane,
 * {@link #killSession} returns normally (already gone); {@link #currentPath},
 * {@link #sendKeys}, {@link #attach} and the feature-gated calls throw
 * {@link SessionHostException}. Only {@link #newSession} may start a server.
 */
public interface SessionHost {

    /** {@code "tmux"} or {@code "jmux"} — persisted in the registry. */
    String id();

    boolean hasSession(String session);

    /** Names of all live sessions (unfiltered — callers apply the {@code hm-} prefix filter). */
    List<String> listSessions();

    /** Creates a detached session running the default shell in {@code workdir}. */
    void newSession(String session, Path workdir, int columns, int rows);

    /** Sends {@code command} literally to the session, pauses 400ms, then Enter — TUIs
     * with paste detection treat a CR arriving within milliseconds of the text as a
     * literal newline, not a submit. */
    void sendKeys(String session, String command);

    /**
     * {@link #sendKeys(String, String)} into the registry's {@code window.pane} target —
     * the same pane {@link #capture} reads — falling back to the active pane exactly
     * when {@link #capture} would, so what is typed always lands in the pane that was
     * inspected. Hosts without panes (the default) have only one place to send to.
     */
    default void sendKeys(String session, String targetPane, String command) {
        sendKeys(session, command);
    }

    /** Kills the session outright (and whatever it's running). No-op if already gone. */
    void killSession(String session);

    /** Best-effort discovery of a live session's working directory, for adopting unmanaged sessions. */
    Path currentPath(String session);

    /**
     * The {@code window.pane} target of the session's active pane right now — recorded
     * at create/adopt time as the registry's {@code targetPane}, so status polling reads
     * the harness pane even under a non-zero tmux {@code base-index}. {@code "0.0"} on
     * hosts without panes.
     */
    String currentPaneTarget(String session);

    /**
     * Captures the rendered screen for status polling. {@code targetPane} is the
     * registry's {@code window.pane} target — the harness pane, not whatever pane
     * happens to be active if the user opened extra windows. A missing session or dead
     * pane yields {@code paneDead=true}. Read-only — never attaches a client. The
     * {@link Screen} contract is the same on every host: visible text trimmed per line
     * and {@code \n}-joined, the foreground process's basename, the shell PID, and the
     * last OSC title (empty when never set).
     */
    Screen capture(String session, String targetPane);

    /** What this host can do for {@code session} beyond the core contract. */
    EnumSet<HostFeature> features(String session);

    /**
     * Opens a live connection at the given size. May block (SSH round trip, daemon
     * connect) and is therefore never called on the EDT.
     */
    SessionConnection attach(String session, int columns, int rows, AttachOptions options);

    /** {@link HostFeature#MOUSE_OPTION}: sets the session's mouse option live. */
    void setMouseMode(String session, boolean on);

    /** {@link HostFeature#STATUS_BAR_OPTION}: shows or hides the host's own status chrome live. */
    void setStatusBar(String session, boolean visible);

    /** {@link HostFeature#HISTORY_PAGING}: scrolls the host's own history one viewport. */
    void scrollPage(String session, boolean up);

    /** {@link HostFeature#HISTORY_PAGING}: jumps to the top of history, or back to the live bottom. */
    void jumpHistory(String session, boolean top);
}
