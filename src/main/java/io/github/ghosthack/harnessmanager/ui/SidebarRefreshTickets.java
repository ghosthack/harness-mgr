package io.github.ghosthack.harnessmanager.ui;

/**
 * The ordering rules behind {@link MainWindow}'s off-EDT sidebar refreshes. Every
 * refresh takes a ticket and only the newest one's result applies, so a slow older
 * reconciliation never overwrites a newer one. A refresh asked to reselect a session
 * (the user just created or resumed it) leaves that target pending until some refresh
 * applies, so a later reselect-nothing refresh — an agent's MCP spawn or kill landing
 * mid-flight — supersedes its result but not its selection. A newer reselect replaces
 * an older one. A refresh that fails drops the target it (or an older refresh) left
 * pending, so a later unrelated refresh never jumps the selection to it. Thread-safe.
 */
final class SidebarRefreshTickets {

    private long latest;
    private String pendingReselect;
    /** The ticket that set {@link #pendingReselect}. */
    private long pendingTicket;

    /** Takes the next ticket; a non-null {@code reselect} becomes the pending target. */
    synchronized long issue(String reselect) {
        ++latest;
        if (reselect != null) {
            pendingReselect = reselect;
            pendingTicket = latest;
        }
        return latest;
    }

    /** {@code ticket}'s refresh failed: the pending target is dropped unless a newer
     * refresh set it. */
    synchronized void failed(long ticket) {
        if (pendingReselect != null && pendingTicket <= ticket) {
            pendingReselect = null;
        }
    }

    /** Whether {@code ticket}'s result is still the newest. */
    synchronized boolean isCurrent(long ticket) {
        return ticket == latest;
    }

    /** Claims the pending reselect target for the current ticket's result (null for
     * none, i.e. keep the selection) and clears it — it applies exactly once. */
    synchronized String takeReselect() {
        String target = pendingReselect;
        pendingReselect = null;
        return target;
    }
}
