package io.github.ghosthack.harnessmanager.host;

import io.github.ghosthack.harnessmanager.machine.Machine;
import io.github.ghosthack.harnessmanager.machine.MachineRegistry;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import io.github.ghosthack.harnessmanager.state.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes every {@link SessionHost} call by session name to the host that owns it: a
 * registered {@link Machine} gets a per-machine remote tmux backend (built lazily on
 * first use and kept for the app's run); a local session goes to the local host whose
 * {@link SessionHost#id()} the registry row names. {@code Reconciler},
 * {@code SessionStatusPoller} and {@code GracefulCloser} never need to know this class
 * exists — they only ever call {@code SessionHost.<method>(sessionName, ...)}.
 * Replaces the tmux-only {@code MultiMachineTmux}.
 *
 * <p>Resolution order for a name: the pending map (a creation in flight) → the
 * registry row ({@code machineId} → that machine's remote backend, else
 * {@link SessionEntry#hostId()}) → the "unmanaged" table (which local host listed the
 * name in the last {@link #listSessions()}) → {@code "tmux"}, the same rule
 * {@link SessionEntry} applies to a null host. The router has no configurable default:
 * the app chooses a host only when it creates a session.
 *
 * <p>The only host class that reads the registry; remote backends come from an
 * injected factory so this package never depends on {@code tmux}.
 */
public final class RoutingSessionHost implements SessionHost {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingSessionHost.class);

    /** Builds the remote tmux backend for a registered machine — resolving the remote
     * {@code tmux} path over SSH, so it blocks and is only ever called off the EDT.
     * Injected (rather than calling {@code RealTmux.remote} here) so {@code host} does
     * not depend on {@code tmux}, which depends on {@code host}. */
    @FunctionalInterface
    public interface RemoteHostFactory {
        SessionHost create(Machine machine);
    }

    /** Where a session that is not yet in the registry is being created. */
    private record PendingTarget(String machineId, String hostId) {
    }

    private final Map<String, SessionHost> localHosts;
    private final MachineRegistry machines;
    private final SessionRegistry sessions;
    private final RemoteHostFactory remoteHostFactory;

    /** A session name not yet in {@code sessions} (mid-creation), mapped to its intended
     * target — cleared once the caller's {@code registry.put(...)} lands or the creation
     * attempt fails. See {@code MainWindow.createSession}. */
    private final Map<String, PendingTarget> pending = new ConcurrentHashMap<>();

    /** One remote backend per machine id, built lazily and kept for the app's run. */
    private final Map<String, SessionHost> remoteBackends = new ConcurrentHashMap<>();

    /** Which local host listed each name in the last {@link #listSessions()} — the only
     * way to route a live session that has no registry row (an unmanaged one, or one
     * whose creation the app didn't see). Replaced wholesale per refresh, never mutated,
     * so readers on other threads see one consistent snapshot. */
    private volatile Map<String, String> unmanaged = Map.of();

    /** Keys already WARNed once via {@link #warnOnce}. A host whose binary is simply
     * absent for this platform (tmux on Windows) fails the exact same way on every
     * refresh for the whole run — worth one WARN so it's visible, not one per refresh
     * drowning out whatever comes after it. */
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    private void warnOnce(String key, String message) {
        if (warnedOnce.add(key)) {
            LOG.warn(message);
        } else if (LOG.isDebugEnabled()) {
            LOG.debug(message);
        }
    }

    /**
     * @param localHosts the local substrates keyed by {@link SessionHost#id()}; map
     *                   order breaks ties when two of them list the same session name
     */
    public RoutingSessionHost(Map<String, SessionHost> localHosts, MachineRegistry machines,
                              SessionRegistry sessions, RemoteHostFactory remoteHostFactory) {
        this.localHosts = new LinkedHashMap<>(localHosts);
        this.machines = machines;
        this.sessions = sessions;
        this.remoteHostFactory = remoteHostFactory;
    }

    /** The ids of the configured local hosts, in routing order. */
    public Set<String> localHostIds() {
        return localHosts.keySet();
    }

    /**
     * Registers {@code sessionName} against its target before it exists in the session
     * registry. Any flow that creates a session outside {@link #newSession} (today:
     * harness-stimulator {@code run}) must register the host that will own it before
     * spawning the creator. A null {@code machineId} means local; a null {@code hostId}
     * means the {@code "tmux"} default.
     */
    public void registerPending(String sessionName, String machineId, String hostId) {
        pending.put(sessionName, new PendingTarget(machineId, hostId));
    }

    public void clearPending(String sessionName) {
        pending.remove(sessionName);
    }

    /** Rebuilds {@code machine}'s remote backend — used by "Test connection" so a stale
     * tmux path from an earlier, since-changed run doesn't linger. Blocks on SSH. */
    public void refreshMachine(Machine machine) {
        remoteBackends.remove(machine.id());
        backendFor(machine);
    }

    /** Which local host listed {@code session} in the last {@link #listSessions()} — how
     * Adopt learns the host of a session that has no registry row yet. */
    public Optional<String> hostIdOf(String session) {
        return Optional.ofNullable(unmanaged.get(session));
    }

    private SessionHost backendFor(Machine machine) {
        return remoteBackends.computeIfAbsent(machine.id(), id -> remoteHostFactory.create(machine));
    }

    /** The backend for a session name per the resolution order in the class comment, or
     * empty when its machine or host id doesn't resolve to anything configured — a
     * since-deleted machine, treated as "nothing to route to", never thrown. */
    private Optional<SessionHost> backendForSession(String sessionName) {
        PendingTarget target = pending.get(sessionName);
        if (target == null) {
            Optional<SessionEntry> row = sessions.find(sessionName);
            if (row.isPresent()) {
                target = new PendingTarget(row.get().machineIdOpt().orElse(null), row.get().hostId());
            }
        }
        if (target == null) {
            target = new PendingTarget(null, unmanaged.getOrDefault(sessionName, SessionEntry.TMUX_HOST));
        }
        if (target.machineId() != null) {
            // remote sessions are always tmux; the host id is ignored for them
            return machines.find(target.machineId()).map(this::backendFor);
        }
        String hostId = target.hostId() == null ? SessionEntry.TMUX_HOST : target.hostId();
        return Optional.ofNullable(localHosts.get(hostId));
    }

    /**
     * Whether {@code candidateName} is already live where a new session named that is
     * about to be created — used only by {@code SessionNaming}'s uniqueness check. For
     * a null {@code machineId} that means <em>any</em> local host: the registry key is
     * host-agnostic, so a name must be unique across all local hosts, not just the one
     * that will own it. A registered machine probes that machine's tmux specifically;
     * the flat registry has no per-machine namespace, so two machines' same-named
     * sessions would otherwise have one silently overwrite the other's row — and its
     * unrecoverable {@code harnessSessionId} — on the next {@code registry.put}.
     */
    public boolean hasSessionOn(String machineId, String candidateName) {
        if (machineId == null) {
            for (SessionHost host : localHosts.values()) {
                if (hasSessionQuietly(host, candidateName)) {
                    return true;
                }
            }
            return false;
        }
        return machines.find(machineId).map(m -> backendFor(m).hasSession(candidateName)).orElse(false);
    }

    /** {@code hasSession} with the same isolation {@link #listSessions()} gives a local
     * host: one substrate whose binary is absent (tmux on Windows) must not veto a name
     * on another. */
    private boolean hasSessionQuietly(SessionHost host, String candidateName) {
        try {
            return host.hasSession(candidateName);
        } catch (RuntimeException e) {
            warnOnce("hasSession:" + host.id(), "local host '" + host.id() + "' could not be asked about '"
                    + candidateName + "' — treating it as not live: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String id() {
        // never persisted: a registry row always names the concrete host it routes to
        return "routing";
    }

    @Override
    public boolean hasSession(String session) {
        return backendForSession(session).map(t -> t.hasSession(session)).orElse(false);
    }

    /**
     * Every local host's list, unioned with — for every registry entry whose machine
     * still resolves — that session's name iff a <em>targeted</em> probe against its
     * specific machine confirms it is live. Never a remote scrape: scraping would
     * resurface the remote-adoption scope this app defers (any stranger tmux session on
     * a newly-registered machine would come back looking "unmanaged"), and would
     * reopen the same-name-different-machine collision noted on {@link #hasSessionOn}.
     * Each local host and each machine is isolated the same way: one that can't be
     * reached (tmux absent on Windows, an SSH hop down) degrades to "none of its
     * sessions are live" rather than failing the whole reconciliation —
     * {@code Reconciler.reconcile} has no per-entry isolation the way
     * {@code SessionStatusPoller} does. Also rebuilds the unmanaged table.
     */
    @Override
    public List<String> listSessions() {
        List<String> result = new ArrayList<>();
        Map<String, String> listedBy = new LinkedHashMap<>();
        for (SessionHost host : localHosts.values()) {
            List<String> names;
            try {
                names = host.listSessions();
            } catch (RuntimeException e) {
                warnOnce("listSessions:" + host.id(), "local host '" + host.id()
                        + "' could not list its sessions — treating it as having none: " + e.getMessage());
                continue;
            }
            for (String name : names) {
                String earlier = listedBy.putIfAbsent(name, host.id());
                if (earlier == null) {
                    result.add(name);
                } else {
                    LOG.warn("session '{}' is listed by both '{}' and '{}' — routing it to '{}'",
                            name, earlier, host.id(), earlier);
                }
            }
        }
        unmanaged = Map.copyOf(listedBy);
        for (SessionEntry entry : sessions.sessions()) {
            String machineId = entry.machineId();
            if (machineId == null) {
                continue;
            }
            Optional<Machine> machine = machines.find(machineId);
            if (machine.isEmpty()) {
                continue;
            }
            try {
                if (backendFor(machine.get()).hasSession(entry.tmuxSession())) {
                    result.add(entry.tmuxSession());
                }
            } catch (RuntimeException e) {
                warnOnce("machine:" + machineId, "could not reach machine '" + machine.get().label()
                        + "' while reconciling '" + entry.tmuxSession() + "' — treating it as not live: "
                        + e.getMessage());
            }
        }
        return result;
    }

    @Override
    public void newSession(String session, Path workdir, int columns, int rows) {
        backendForSession(session).orElseThrow(() -> unroutable(session))
                .newSession(session, workdir, columns, rows);
    }

    @Override
    public void sendKeys(String session, String command) {
        backendForSession(session).orElseThrow(() -> unroutable(session)).sendKeys(session, command);
    }

    @Override
    public void sendKeys(String session, String targetPane, String command) {
        backendForSession(session).orElseThrow(() -> unroutable(session))
                .sendKeys(session, targetPane, command);
    }

    @Override
    public void killSession(String session) {
        backendForSession(session).ifPresent(t -> t.killSession(session));
    }

    @Override
    public Path currentPath(String session) {
        return backendForSession(session).orElseThrow(() -> unroutable(session)).currentPath(session);
    }

    @Override
    public String currentPaneTarget(String session) {
        return backendForSession(session).orElseThrow(() -> unroutable(session)).currentPaneTarget(session);
    }

    /** {@code SessionStatusPoller} already isolates a {@link SessionHostException} per
     * session (see its {@code pollAll}), so a machine that's unreachable this tick
     * simply produces no result for this session rather than needing special handling
     * here — matching how a wedged local tmux server already behaves. */
    @Override
    public Screen capture(String session, String targetPane) {
        return backendForSession(session)
                .map(t -> t.capture(session, targetPane))
                .orElseGet(() -> new Screen("", true, Instant.now()));
    }

    /** Empty for an unroutable session: nothing to gate on, so the UI offers nothing. */
    @Override
    public EnumSet<HostFeature> features(String session) {
        return backendForSession(session)
                .map(t -> t.features(session))
                .orElseGet(() -> EnumSet.noneOf(HostFeature.class));
    }

    @Override
    public SessionConnection attach(String session, int columns, int rows, AttachOptions options) {
        return backendForSession(session).orElseThrow(() -> unroutable(session))
                .attach(session, columns, rows, options);
    }

    @Override
    public void setMouseMode(String session, boolean on) {
        backendForSession(session).ifPresent(t -> t.setMouseMode(session, on));
    }

    @Override
    public void setStatusBar(String session, boolean visible) {
        backendForSession(session).ifPresent(t -> t.setStatusBar(session, visible));
    }

    @Override
    public void scrollPage(String session, boolean up) {
        backendForSession(session).ifPresent(t -> t.scrollPage(session, up));
    }

    @Override
    public void jumpHistory(String session, boolean top) {
        backendForSession(session).ifPresent(t -> t.jumpHistory(session, top));
    }

    private static SessionHostException unroutable(String session) {
        return new SessionHostException("'" + session
                + "' is registered to a machine or session host that is no longer configured");
    }
}
