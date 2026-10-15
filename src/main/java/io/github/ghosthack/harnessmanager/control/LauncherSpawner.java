package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.jmux.SessionServerHost;
import io.github.ghosthack.harnessmanager.session.SessionEvents;
import io.github.ghosthack.harnessmanager.session.SessionLauncher;

import java.util.Objects;
import java.util.Optional;

/**
 * The production {@link SessionSpawner}: launches through the window's own
 * {@link SessionLauncher} on the local jmux host (so the session is named, registered,
 * labelled and MCP-injected exactly like one opened from the toolbar), then tells the
 * window through {@link SessionEvents} so the sidebar picks it up without stealing focus.
 */
public final class LauncherSpawner implements SessionSpawner {

    private final SessionLauncher launcher;
    private final SessionHost jmuxHost;
    private final SessionEvents events;

    public LauncherSpawner(SessionLauncher launcher, SessionHost jmuxHost, SessionEvents events) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.jmuxHost = Objects.requireNonNull(jmuxHost, "jmuxHost");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public String spawn(SpawnRequest request) {
        String name = launcher.create(request.project(), request.harness(), Optional.empty(), null,
                SessionServerHost.ID, request.displayName(), request.labels());
        events.fireSessionsChanged(name);
        return name;
    }

    @Override
    public void kill(String session) {
        jmuxHost.killSession(session);
        launcher.discardMcpConfig(session);
        events.fireSessionKilled(session);
    }
}
