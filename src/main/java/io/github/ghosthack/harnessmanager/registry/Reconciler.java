package io.github.ghosthack.harnessmanager.registry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup (and on-demand refresh) reconciliation: the session host's live list filtered
 * by the {@code hm-} prefix, diffed against the registry. A pure function of its inputs
 * — the caller reads the registry and the host — so it needs no live substrate to test
 * and this package depends on neither {@code host} nor {@code tmux}.
 */
public final class Reconciler {

    /** Every session this app owns is named with this prefix. */
    public static final String SESSION_PREFIX = "hm-";

    public static ReconciliationResult reconcile(List<SessionEntry> registered, List<String> allTmuxSessions) {
        Set<String> live = new LinkedHashSet<>();
        for (String name : allTmuxSessions) {
            if (name.startsWith(SESSION_PREFIX)) {
                live.add(name);
            }
        }
        List<SessionEntry> liveManaged = new ArrayList<>();
        List<SessionEntry> deadRegistered = new ArrayList<>();
        Set<String> registeredNames = new LinkedHashSet<>();
        for (SessionEntry entry : registered) {
            registeredNames.add(entry.tmuxSession());
            if (live.contains(entry.tmuxSession())) {
                liveManaged.add(entry);
            } else {
                deadRegistered.add(entry);
            }
        }
        List<String> unmanagedLive = new ArrayList<>();
        for (String name : live) {
            if (!registeredNames.contains(name)) {
                unmanagedLive.add(name);
            }
        }
        return new ReconciliationResult(liveManaged, deadRegistered, unmanagedLive);
    }

    private Reconciler() {
    }
}
