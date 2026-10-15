package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.state.SessionState;
import io.github.ghosthack.harnessmanager.stimulator.StimulatorClient;

import java.nio.file.Path;

/**
 * One row in the sidebar. Wraps either a registered {@link SessionEntry} (live or dead)
 * or a bare tmux session name for an unmanaged, adoptable session.
 */
final class SidebarEntry {

    enum Status { LIVE, DEAD, UNMANAGED }

    private final Status status;
    private final SessionEntry sessionEntry; // null for UNMANAGED
    private final String tmuxSession;
    private boolean attached;
    private boolean stimulated;

    /** Live stimuli-used/budget snapshot while {@link #stimulated}; null otherwise, or
     * whenever the attached stimulator hasn't written a status file yet. */
    private StimulatorClient.StimuliStatus stimuliStatus;

    /** Latest status-poll result. Null until the first poll tick lands (or always, for
     * DEAD/UNMANAGED entries — those are never polled). */
    private SessionState state;
    private String stateReason;

    /** Latest pane title the harness set via OSC (e.g. Claude Code's /rename); empty
     * until a poll reports one. Kept even when a later poll reports none — a harness
     * mid-redraw must not make the name flicker off. */
    private String paneTitle = "";

    private SidebarEntry(Status status, SessionEntry sessionEntry, String tmuxSession) {
        this.status = status;
        this.sessionEntry = sessionEntry;
        this.tmuxSession = tmuxSession;
    }

    static SidebarEntry live(SessionEntry entry) {
        return new SidebarEntry(Status.LIVE, entry, entry.tmuxSession());
    }

    static SidebarEntry dead(SessionEntry entry) {
        return new SidebarEntry(Status.DEAD, entry, entry.tmuxSession());
    }

    static SidebarEntry unmanaged(String tmuxSession) {
        return new SidebarEntry(Status.UNMANAGED, null, tmuxSession);
    }

    Status status() {
        return status;
    }

    String tmuxSession() {
        return tmuxSession;
    }

    /** Null only for {@link Status#UNMANAGED}. */
    SessionEntry sessionEntry() {
        return sessionEntry;
    }

    /** The row's harness, or null for {@link Status#UNMANAGED} — nothing knows what an
     * un-adopted tmux session is running. */
    HarnessType harnessType() {
        return sessionEntry == null ? null : sessionEntry.harnessType();
    }

    boolean isAttached() {
        return attached;
    }

    void setAttached(boolean attached) {
        this.attached = attached;
    }

    boolean isStimulated() {
        return stimulated;
    }

    void setStimulated(boolean stimulated) {
        this.stimulated = stimulated;
    }

    /** Null unless {@link #isStimulated()} and the attached stimulator has written a
     * status file. */
    StimulatorClient.StimuliStatus stimuliStatus() {
        return stimuliStatus;
    }

    void setStimuliStatus(StimulatorClient.StimuliStatus stimuliStatus) {
        this.stimuliStatus = stimuliStatus;
    }

    /** Null until the first status-poll result lands for this (live) session. */
    SessionState state() {
        return state;
    }

    /** Reason paired with {@link #state()}; null exactly when the state is. */
    String stateReason() {
        return stateReason;
    }

    void setState(SessionState state, String reason) {
        this.state = state;
        this.stateReason = reason;
    }

    String paneTitle() {
        return paneTitle;
    }

    void setPaneTitle(String paneTitle) {
        if (paneTitle != null && !paneTitle.isBlank()) {
            this.paneTitle = paneTitle;
        }
    }

    /** The session's name as shown to the user: an explicit persisted rename, then the
     * harness-set OSC title (e.g. Claude Code's /rename), then the tmux session name. */
    String displayName() {
        if (sessionEntry != null && sessionEntry.displayName() != null
                && !sessionEntry.displayName().isBlank()) {
            return sessionEntry.displayName();
        }
        return detectedName();
    }

    /** Best name supplied by the harness/provider, before any persisted sidebar override. */
    String detectedName() {
        return paneTitle.isEmpty() ? tmuxSession : paneTitle;
    }

    /** Project path with the home directory abbreviated to {@code ~}; empty for
     * {@link Status#UNMANAGED} rows, which have no registered path. */
    String abbreviatedPath() {
        if (sessionEntry == null) {
            return "";
        }
        String path = sessionEntry.projectPath();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    /**
     * The row's top line: the session's name (a persisted rename, then the harness-set
     * OSC title, then the tmux session name). The harness isn't named
     * here — the renderer badges the row with {@link #harnessType()}'s glyph instead.
     */
    String nameText() {
        return switch (status) {
            case LIVE, DEAD -> displayName();
            case UNMANAGED -> tmuxSession;
        };
    }

    /** The row's muted second line: the project dir. The renderer clips this from the
     * left ("…tail"), so the path's most telling segment — the project folder at its
     * end — survives a narrow sidebar. No state is spelled out in text: the
     * {@code Reticule} screen door says not-attached, the skull icon says dead. */
    String detailText() {
        return switch (status) {
            case LIVE, DEAD -> abbreviatedPath();
            case UNMANAGED -> "unmanaged — right-click to adopt";
        };
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** For the sidebar's per-row tooltip; combines the session name (labeled with its
     * backing host — tmux or jmux) with the harness the row's glyph stands for and the
     * latest status reason, if any. */
    String tooltipText() {
        String fullDisplayName = displayName();
        StringBuilder html = new StringBuilder("<html><b>")
                .append(escapeHtml(fullDisplayName)).append("</b>");
        String hostLabel = sessionEntry != null ? sessionEntry.hostId() : SessionEntry.TMUX_HOST;
        html.append("<br>").append(hostLabel);
        if (!fullDisplayName.equals(tmuxSession)) {
            html.append(": ").append(escapeHtml(tmuxSession));
        }
        if (sessionEntry != null) {
            html.append("<br>").append(sessionEntry.harnessType().id());
        }
        if (stimulated) {
            html.append("<br>harness-stimulator attached — read-only peek");
        }
        if (status == Status.LIVE && state != null) {
            html.append("<br>").append(state).append(" — ").append(stateReason);
        } else if (status == Status.LIVE) {
            html.append("<br>status pending first poll…");
        } else if (status == Status.DEAD) {
            // the skull's fine print: dead isn't gone — Resume… brings it back
            html.append("<br>dead — right-click to resume");
        }
        html.append("</html>");
        return html.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SidebarEntry other && tmuxSession.equals(other.tmuxSession);
    }

    @Override
    public int hashCode() {
        return tmuxSession.hashCode();
    }
}
