package io.github.ghosthack.harnessmanager.registry;

import java.util.List;

/**
 * Result of diffing the registry against what tmux actually has running.
 *
 * @param liveManaged     registry entries whose tmux session is alive right now
 * @param deadRegistered  registry entries whose tmux session is gone — shown dead/resumable
 * @param unmanagedLive   live {@code hm-}-prefixed tmux sessions with no registry entry —
 *                        shown "unmanaged", with an adopt action
 */
public record ReconciliationResult(
        List<SessionEntry> liveManaged,
        List<SessionEntry> deadRegistered,
        List<String> unmanagedLive) {
}
