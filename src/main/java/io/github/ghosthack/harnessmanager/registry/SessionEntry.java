package io.github.ghosthack.harnessmanager.registry;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Optional;

/**
 * One managed session: the session-host session that hosts it (a tmux session, or one
 * on the jmux daemon), the harness running inside it, and enough to
 * resume that harness conversation later. Per IMPLEMENTATION.md the conceptual mapping
 * is harness-session -> (host session, target pane); in practice they are 1-1, so
 * {@link #tmuxSession()} (always {@code hm-}-prefixed; the name predates the second
 * substrate) is the registry's key.
 *
 * <p>{@code host} is the owning substrate's id, persisted as {@code "host"}. Null or
 * blank means {@value #TMUX_HOST}, so every {@code sessions.json} row written before the
 * seam existed keeps working with no migration. Remote (SSH) sessions are always tmux;
 * the router ignores {@code host} when {@code machineId} is set. Rows are only ever
 * rebuilt through the with-ers or {@link #newlyCreated}, so no flow can silently drop
 * the host on the way through.
 */
public record SessionEntry(
        String tmuxSession,
        String projectPath,
        HarnessType harnessType,
        String harnessSessionId,
        Instant createdAt,
        String targetPane,
        String displayName,
        String machineId,
        String host) {

    /** The host id a row with no {@code host} means — the substrate that existed before
     * there was a choice. Mirrored by {@code RealTmux.ID}; kept here so the registry
     * package needs nothing from {@code tmux} or {@code host}. */
    public static final String TMUX_HOST = "tmux";

    @JsonCreator
    public SessionEntry(
            @JsonProperty("tmuxSession") String tmuxSession,
            @JsonProperty("projectPath") String projectPath,
            @JsonProperty("harnessType") HarnessType harnessType,
            @JsonProperty("harnessSessionId") String harnessSessionId,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("targetPane") String targetPane,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("machineId") String machineId,
            @JsonProperty("host") String host) {
        this.tmuxSession = tmuxSession;
        this.projectPath = projectPath;
        this.harnessType = harnessType;
        this.harnessSessionId = harnessSessionId;
        this.createdAt = createdAt;
        this.targetPane = targetPane;
        this.displayName = displayName;
        this.machineId = machineId;
        this.host = host;
    }

    /** Source-compatible constructor for entries on the default (tmux) host. Old
     * {@code sessions.json} rows with no {@code host} field bind through the canonical
     * constructor with null — Jackson passes null for a missing property. */
    public SessionEntry(String tmuxSession, String projectPath, HarnessType harnessType,
                        String harnessSessionId, Instant createdAt, String targetPane,
                        String displayName, String machineId) {
        this(tmuxSession, projectPath, harnessType, harnessSessionId, createdAt, targetPane,
                displayName, machineId, null);
    }

    /** Source-compatible constructor for entries with no machine (local). */
    public SessionEntry(String tmuxSession, String projectPath, HarnessType harnessType,
                        String harnessSessionId, Instant createdAt, String targetPane,
                        String displayName) {
        this(tmuxSession, projectPath, harnessType, harnessSessionId, createdAt, targetPane,
                displayName, null, null);
    }

    /** Source-compatible constructor for entries without a user-assigned sidebar name. */
    public SessionEntry(String tmuxSession, String projectPath, HarnessType harnessType,
                        String harnessSessionId, Instant createdAt, String targetPane) {
        this(tmuxSession, projectPath, harnessType, harnessSessionId, createdAt, targetPane,
                null, null, null);
    }

    /** Returns a copy with a persisted sidebar name. Blank means use the live pane title
     * (when present), then fall back to the session name. */
    public SessionEntry withDisplayName(String displayName) {
        String normalized = displayName == null || displayName.isBlank() ? null : displayName.strip();
        return new SessionEntry(tmuxSession, projectPath, harnessType, harnessSessionId,
                createdAt, targetPane, normalized, machineId, host);
    }

    /** Returns a copy associated with the provider conversation recovered at exit. */
    public SessionEntry withHarnessSessionId(String harnessSessionId) {
        String normalized = harnessSessionId == null || harnessSessionId.isBlank()
                ? null : harnessSessionId.strip();
        return new SessionEntry(tmuxSession, projectPath, harnessType, normalized,
                createdAt, targetPane, displayName, machineId, host);
    }

    /** Returns a copy pointing status polling at a new {@code window.pane} — what Resume
     * writes once it has recreated the session under the same name. Everything else,
     * the host included, is carried over untouched. */
    public SessionEntry withTargetPane(String targetPane) {
        return new SessionEntry(tmuxSession, projectPath, harnessType, harnessSessionId,
                createdAt, targetPane, displayName, machineId, host);
    }

    /** Returns a copy on another local host — what "Move to …" on a dead row writes, so
     * the next Resume recreates the session there. Only meaningful for local rows: the
     * router ignores {@code host} on remote ones. */
    public SessionEntry withHost(String host) {
        return new SessionEntry(tmuxSession, projectPath, harnessType, harnessSessionId,
                createdAt, targetPane, displayName, machineId, host);
    }

    /** The effective host id: {@code host}, or {@value #TMUX_HOST} when null/blank. */
    public String hostId() {
        return host == null || host.isBlank() ? TMUX_HOST : host.strip();
    }

    /** {@code harnessSessionId} is optional (blank/null means "no resume id recorded yet"). */
    public Optional<String> harnessSessionIdOpt() {
        return harnessSessionId == null || harnessSessionId.isBlank()
                ? Optional.empty() : Optional.of(harnessSessionId);
    }

    /** {@code machineId} is optional — null/blank means this session runs locally. */
    public Optional<String> machineIdOpt() {
        return machineId == null || machineId.isBlank() ? Optional.empty() : Optional.of(machineId);
    }

    /** Whether this session runs on a registered machine rather than locally — the one
     * named check every remote-vs-local gate in the UI should use instead of each
     * re-deriving its own {@code machineId() != null}. */
    public boolean isRemote() {
        return machineId != null && !machineId.isBlank();
    }

    /** New entry for a local tmux session created just now. {@code targetPane} is the
     * harness pane's {@code window.pane} target, read from the host at creation time —
     * hardcoding {@code "0.0"} would miss users with a non-zero {@code base-index}. */
    public static SessionEntry newlyCreated(String tmuxSession, String projectPath,
                                              HarnessType harnessType, String harnessSessionId,
                                              String targetPane) {
        return newlyCreated(tmuxSession, projectPath, harnessType, harnessSessionId, targetPane, null);
    }

    /** As above, for a tmux session created on a registered machine ({@code machineId}
     * non-null) or locally ({@code machineId} null). */
    public static SessionEntry newlyCreated(String tmuxSession, String projectPath,
                                              HarnessType harnessType, String harnessSessionId,
                                              String targetPane, String machineId) {
        return newlyCreated(tmuxSession, projectPath, harnessType, harnessSessionId, targetPane,
                machineId, null);
    }

    /** The one producer that names a host: a session created on the local host
     * {@code hostId} ({@code machineId} null), or on a registered machine (always tmux —
     * {@code hostId} is ignored and should be null). */
    public static SessionEntry newlyCreated(String tmuxSession, String projectPath,
                                              HarnessType harnessType, String harnessSessionId,
                                              String targetPane, String machineId, String hostId) {
        return new SessionEntry(tmuxSession, projectPath, harnessType, harnessSessionId,
                Instant.now(), targetPane, null, machineId, hostId);
    }
}
