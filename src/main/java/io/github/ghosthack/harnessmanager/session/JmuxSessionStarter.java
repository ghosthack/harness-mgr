package io.github.ghosthack.harnessmanager.session;

import java.nio.file.Path;
import java.util.Map;

/**
 * Starts a session on the local jmux host with extra environment variables — the one
 * thing {@link io.github.ghosthack.harnessmanager.host.SessionHost#newSession} cannot
 * express, kept to this seam rather than widened onto every host. Production is
 * {@code SessionServerHost::newSession}.
 */
@FunctionalInterface
public interface JmuxSessionStarter {

    /** Starts the session and returns whether {@code env} was applied — false when the
     * running daemon predates {@code env} and started the session without it. */
    boolean newSession(String session, Path workdir, int columns, int rows, Map<String, String> env);
}
