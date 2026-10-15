package io.github.ghosthack.harnessmanager.jmux;

import io.github.ghosthack.harnessmanager.host.AttachOptions;
import io.github.ghosthack.harnessmanager.host.HostFeature;
import io.github.ghosthack.harnessmanager.host.SessionConnection;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.jmux.client.AttachedSession;
import io.github.ghosthack.jmux.client.SessionServerClient;
import io.github.ghosthack.jmux.client.SessionServerException;
import io.github.ghosthack.jmux.session.Session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The jmux {@link SessionHost}: the daemon that hosts sessions where
 * tmux does not run (Windows), spoken to in-process through its client library. One
 * instance per server home; the default home is {@code ~/.jmux}
 * ({@code server.sock}, {@code server.lock}, {@code server.log}; override with
 * {@code -Djmux.home} or {@code JMUX_HOME}).
 *
 * <p>tmux parity is the client's contract, not something added here: only
 * {@link #newSession} ever spawns the daemon, and on a dead home {@link #listSessions}
 * is empty, {@link #hasSession} false, {@link #capture} a dead pane and
 * {@link #killSession} a no-op, while {@link #sendKeys}, {@link #currentPath} and
 * {@link #attach} throw. The daemon has neither panes nor a server-side copy mode nor
 * session options, so {@link #currentPaneTarget} is always {@code "0.0"},
 * {@link #features} is empty (the terminal widget pages its own scrollback) and the
 * feature-gated calls throw {@code "unsupported"}. The 400 ms pause between typed text
 * and Enter lives inside the daemon's {@code send-keys}, so {@link #sendKeys} is one
 * round trip.
 *
 * <p>Every {@link SessionServerException} surfaces as a {@link SessionHostException}
 * with the same message and the original as its cause — including the daemon's
 * protocol-mismatch message verbatim, which names the running server's version and pid
 * and tells the user how to retire it (the client never spawns, kills or replaces a
 * mismatched server: it still hosts live sessions).
 */
public final class SessionServerHost implements SessionHost {

    /** The id every daemon-hosted registry row carries; what {@code session.host} selects. */
    public static final String ID = "jmux";

    /** The daemon has no windows or panes: every session is its one {@code window.pane}. */
    private static final String PANE_TARGET = "0.0";

    private final SessionServerClient client;

    /** Over an explicit client — tests pass one bound to an in-process server on a
     * temporary home with spawning disabled, so no test can ever fork a real daemon. */
    public SessionServerHost(SessionServerClient client) {
        this.client = client;
    }

    /** The host the app wires: the default home, auto-spawning on {@link #newSession}. */
    public static SessionServerHost forDefaultHome() {
        return new SessionServerHost(SessionServerClient.forDefaultHome());
    }

    /**
     * The underlying daemon client, for what the {@link SessionHost} seam does not carry:
     * labels, named-key input, annotated captures, quiet waits and event watches (the
     * MCP session controller). Its calls throw {@link SessionServerException}, not
     * {@link SessionHostException}.
     */
    public SessionServerClient client() {
        return client;
    }

    /** Merges {@code labels} into the session's daemon-side labels (a null value removes
     * a key); a {@link io.github.ghosthack.harnessmanager.session.SessionLabeller}. */
    public void label(String session, java.util.Map<String, String> labels) {
        run(() -> client.label(session, labels));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean hasSession(String session) {
        return call(() -> client.has(session));
    }

    @Override
    public List<String> listSessions() {
        return call(() -> client.list().stream().map(Session.Info::name).toList());
    }

    /** The only call that may start the daemon: the client spawns it and poll-connects
     * when no server answers. A null command is the daemon's platform login shell. */
    @Override
    public void newSession(String session, Path workdir, int columns, int rows) {
        run(() -> client.newSession(session, workdir, columns, rows, null));
    }

    /** {@link #newSession(String, Path, int, int)} with extra environment variables for
     * the session's shell (null/empty = none) — jmux-only, hence not on {@link SessionHost};
     * a {@link io.github.ghosthack.harnessmanager.session.JmuxSessionStarter}. A daemon
     * that predates {@code env} ignores it and still starts the session.
     *
     * @return whether {@code env} was applied: false only when a non-empty {@code env}
     *         went to a daemon that did not acknowledge it */
    public boolean newSession(String session, Path workdir, int columns, int rows, java.util.Map<String, String> env) {
        return call(() -> client.newSession(session, workdir, columns, rows, null, null, env)).envApplied();
    }

    @Override
    public void sendKeys(String session, String command) {
        run(() -> client.sendKeys(session, command, true));
    }

    /** Absent — or the whole daemon gone — already counts as killed. */
    @Override
    public void killSession(String session) {
        run(() -> client.kill(session));
    }

    @Override
    public Path currentPath(String session) {
        return call(() -> client.currentPath(session));
    }

    @Override
    public String currentPaneTarget(String session) {
        return PANE_TARGET;
    }

    /**
     * {@code targetPane} is ignored: there is exactly one pane. A missing session (or no
     * server) is a dead pane; a present one maps field for field — the daemon's
     * {@code alive} is the inverse of {@code paneDead}, {@code processId} is the shell's
     * PID and {@code title} the last OSC 0/2 title, empty when never set — exactly the
     * {@link Screen} contract the tmux host honours.
     */
    @Override
    public Screen capture(String session, String targetPane) {
        Optional<Session.Capture> captured = call(() -> client.capture(session));
        if (captured.isEmpty()) {
            return new Screen("", true, Instant.now());
        }
        Session.Capture c = captured.get();
        return new Screen(c.text(), !c.alive(), Instant.now(), c.foregroundCommand(), c.processId(), c.title());
    }

    @Override
    public EnumSet<HostFeature> features(String session) {
        return EnumSet.noneOf(HostFeature.class);
    }

    /**
     * A dedicated attach-mode connection; the first bytes readable are the daemon's
     * redraw of the session at this size. {@code options.trueColor()} has no effect
     * here: the daemon runs every session with {@code COLORTERM=truecolor}, forwards
     * 24-bit SGR untouched, and JediTerm renders it natively.
     */
    @Override
    public SessionConnection attach(String session, int columns, int rows, AttachOptions options) {
        AttachedSession attached = call(() -> client.attach(session, columns, rows));
        return new SessionServerConnection(attached);
    }

    @Override
    public void setMouseMode(String session, boolean on) {
        throw unsupported();
    }

    @Override
    public void setStatusBar(String session, boolean visible) {
        throw unsupported();
    }

    @Override
    public void scrollPage(String session, boolean up) {
        throw unsupported();
    }

    @Override
    public void jumpHistory(String session, boolean top) {
        throw unsupported();
    }

    /** The UI gates on {@link #features}, so reaching one of these is a programming
     * error, reported the way the contract prescribes rather than silently ignored. */
    private static SessionHostException unsupported() {
        return new SessionHostException("unsupported");
    }

    /** One daemon request, with its failure translated for the seam: same message, the
     * daemon's exception kept as the cause so a log still shows which side failed. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (SessionServerException e) {
            throw new SessionHostException(e.getMessage(), e);
        }
    }

    private static void run(Runnable request) {
        call(() -> {
            request.run();
            return null;
        });
    }
}
