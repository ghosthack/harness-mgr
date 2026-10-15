package io.github.ghosthack.harnessmanager.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The sessions-changed hub between code that creates or kills sessions off the UI (the
 * MCP session controller) and the main window, which otherwise only reconciles its
 * sidebar after its own actions. Thread-safe: fire from any thread; listeners run on the
 * firing thread and must hand UI work to the EDT themselves. A throwing listener is
 * logged and does not stop the others.
 */
public final class SessionEvents {

    private static final Logger LOG = LoggerFactory.getLogger(SessionEvents.class);

    /** Something outside the window created, renamed or ended a session. */
    @FunctionalInterface
    public interface SessionsChangedListener {
        /** @param createdOrNull the session just created, or null for any other change */
        void sessionsChanged(String createdOrNull);
    }

    /** A session was killed by something other than the window's own Kill action. */
    @FunctionalInterface
    public interface SessionKilledListener {
        void sessionKilled(String session);
    }

    private final CopyOnWriteArrayList<SessionsChangedListener> changed = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SessionKilledListener> killed = new CopyOnWriteArrayList<>();

    public void addSessionsChangedListener(SessionsChangedListener listener) {
        changed.addIfAbsent(listener);
    }

    public void removeSessionsChangedListener(SessionsChangedListener listener) {
        changed.remove(listener);
    }

    public void addSessionKilledListener(SessionKilledListener listener) {
        killed.addIfAbsent(listener);
    }

    public void removeSessionKilledListener(SessionKilledListener listener) {
        killed.remove(listener);
    }

    public void fireSessionsChanged(String createdOrNull) {
        for (SessionsChangedListener listener : changed) {
            try {
                listener.sessionsChanged(createdOrNull);
            } catch (RuntimeException e) {
                LOG.warn("sessions-changed listener failed", e);
            }
        }
    }

    /** Announces an external kill; killed-listeners run first, then a sessions-changed. */
    public void fireSessionKilled(String session) {
        for (SessionKilledListener listener : killed) {
            try {
                listener.sessionKilled(session);
            } catch (RuntimeException e) {
                LOG.warn("session-killed listener failed for {}", session, e);
            }
        }
        fireSessionsChanged(null);
    }
}
