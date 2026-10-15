package io.github.ghosthack.harnessmanager.jmux;

import io.github.ghosthack.harnessmanager.host.AttachOptions;
import io.github.ghosthack.harnessmanager.host.HostFeature;
import io.github.ghosthack.harnessmanager.host.SessionConnection;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.jmux.client.SessionServerClient;
import io.github.ghosthack.jmux.client.SessionServerException;
import io.github.ghosthack.jmux.protocol.Frame;
import io.github.ghosthack.jmux.protocol.FrameCodec;
import io.github.ghosthack.jmux.protocol.FrameType;
import io.github.ghosthack.jmux.protocol.Json;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.server.ServeOptions;
import io.github.ghosthack.jmux.server.ServerHome;
import io.github.ghosthack.jmux.server.SessionManagerFactory;
import io.github.ghosthack.jmux.server.SessionServer;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End to end through the seam: a real {@link SessionServer} bound in-process on a
 * temporary home — with the real PTY-backed manager from {@link SessionManagerFactory},
 * so a genuine PTY-backed shell runs — driven through {@link SessionServerHost} over a
 * {@link SessionServerClient} with spawning disabled, so no test can ever fork a daemon
 * or touch the user's {@code ~/.jmux}. The shared server is
 * kept alive by option so the daemon's 2 s idle timer cannot race a test; the one test
 * that checks the idle exit starts its own server without it.
 *
 * <p>The sessions run a plain, non-login {@code /bin/sh} (see {@link #start}) rather
 * than the daemon's default {@code $SHELL -l}: {@link SessionServerHost#newSession}
 * has no command parameter, so without that substitution every session would source
 * the developer's login files, and a {@code .zprofile} that {@code cd}s somewhere, or
 * a {@code .zlogin} that {@code exec}s another shell, would fail the cwd and
 * foreground-command assertions below with no product bug. The daemon's own live
 * tests pin {@code /bin/sh} for the same reason. The reproduction, and the check that
 * this class now passes under it, is to run it with {@code ZDOTDIR} pointing at a
 * directory whose {@code .zprofile} contains {@code cd /} (surefire cannot vary the
 * environment per test, so that stays a manual check).
 *
 * <p>Echo commands are typed as {@code echo hello-''e2e}: the shell's echo of the
 * keystrokes then never contains the word an assertion looks for, only the command's
 * output does.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class SessionServerHostTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    /** What the foreground probe reports for the idle {@code /bin/sh}: its basename. */
    private static final String SHELL = "sh";

    @TempDir
    Path root;

    private Path home;
    private Path deadHome;
    private Path workdir;
    private SessionServer server;
    private SessionServerClient client;
    private SessionServerHost host;
    private final List<SessionServer> servers = new ArrayList<>();
    private final List<SessionConnection> connections = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        // One @TempDir with subdirectories by hand: the homes must never coincide, and
        // Unix socket paths have a ~104-byte ceiling on macOS, so the tree stays flat.
        home = Files.createDirectories(root.resolve("home"));
        deadHome = Files.createDirectories(root.resolve("dead"));
        workdir = Files.createDirectories(root.resolve("work"));
        server = start(ServeOptions.defaults(home).withKeepAlive(true));
        client = new SessionServerClient(home, false);
        host = new SessionServerHost(client);
    }

    @AfterEach
    void stopEverything() {
        for (SessionConnection connection : connections) {
            connection.close();
        }
        for (SessionServer started : servers) {
            started.close(); // kills every session, removes the socket, releases the lock
        }
    }

    // ---- "no server running": tmux parity, and never a spawn

    @Test
    void deadHomeAnswersLikeNoTmuxServerAndNeverSpawns() {
        SessionServerHost dead = new SessionServerHost(new SessionServerClient(deadHome, false));
        assertEquals(List.of(), dead.listSessions());
        assertFalse(dead.hasSession("hm-ghost"));
        Screen screen = dead.capture("hm-ghost", "0.0");
        assertTrue(screen.paneDead());
        assertEquals("", screen.text());
        assertDoesNotThrow(() -> dead.killSession("hm-ghost"));
        assertThrows(SessionHostException.class, () -> dead.sendKeys("hm-ghost", "echo x"));
        assertThrows(SessionHostException.class, () -> dead.currentPath("hm-ghost"));
        assertThrows(SessionHostException.class, () -> dead.attach("hm-ghost", 80, 24, new AttachOptions(true)));
        // With spawning disabled even newSession only reports the missing server...
        SessionHostException refused = assertThrows(SessionHostException.class,
                () -> dead.newSession("hm-ghost", workdir, 80, 24));
        assertTrue(refused.getMessage().contains("does not spawn"), refused.getMessage());
        // ...and nothing was forked: a spawn creates server.log before the daemon even binds.
        assertFalse(Files.exists(ServerHome.socket(deadHome)));
        assertFalse(Files.exists(ServerHome.log(deadHome)), "a spawn would have created server.log");
        assertFalse(Files.exists(ServerHome.lock(deadHome)));
        assertTrue(server.isRunning(), "the live server on the other home was never involved");
    }

    @Test
    void noServerExceptionsKeepTheDaemonsMessageAndCause() {
        SessionServerHost dead = new SessionServerHost(new SessionServerClient(deadHome, false));
        SessionHostException e = assertThrows(SessionHostException.class,
                () -> dead.attach("hm-named", 80, 24, new AttachOptions(false)));
        assertTrue(e.getMessage().contains("hm-named"), e.getMessage());
        SessionServerException cause = assertInstanceOf(SessionServerException.class, e.getCause());
        assertTrue(cause.isNoServer());
        assertEquals(cause.getMessage(), e.getMessage());
    }

    @Test
    void protocolMismatchSurfacesTheDaemonsMessageVerbatimAndNeverSpawns() throws Exception {
        try (MismatchListener mismatch = MismatchListener.start(ServerHome.socket(deadHome), 7, "9.9.9", 777)) {
            // Spawning enabled on purpose: a mismatch is a connected outcome, so even
            // newSession must report it rather than fork a competing daemon.
            SessionServerHost stale = new SessionServerHost(new SessionServerClient(deadHome, true));
            String expected = "jmux 9.9.9 (pid 777) speaks protocol 7; this app needs "
                    + Protocol.VERSION + ". Finish its sessions or run `jmux shutdown`, then retry";
            assertEquals(expected, assertThrows(SessionHostException.class, stale::listSessions).getMessage());
            assertEquals(expected, assertThrows(SessionHostException.class,
                    () -> stale.newSession("hm-x", workdir, 80, 24)).getMessage());
            assertFalse(Files.exists(ServerHome.log(deadHome)), "no spawn happened");
            assertTrue(mismatch.isOpen(), "the mismatched server was neither killed nor replaced");
        }
    }

    // ---- live server

    @Test
    void featureGatedCallsAreUnsupportedAndFeaturesAreEmpty() {
        assertEquals(SessionServerHost.ID, host.id());
        assertEquals(EnumSet.noneOf(HostFeature.class), host.features("hm-any"));
        assertEquals("0.0", host.currentPaneTarget("hm-any"));
        for (Executable call : List.<Executable>of(
                () -> host.setMouseMode("hm-any", true),
                () -> host.setStatusBar("hm-any", false),
                () -> host.scrollPage("hm-any", true),
                () -> host.jumpHistory("hm-any", false))) {
            assertEquals("unsupported", assertThrows(SessionHostException.class, call).getMessage());
        }
    }

    @Test
    void captureMapsTheDaemonReplyOntoScreen() {
        assertTrue(host.capture("hm-missing", "0.0").paneDead(), "missing on a live server is a dead pane too");

        host.newSession("hm-cap", workdir, 80, 24);
        awaitUntil("the shell to be idle at its prompt",
                () -> SHELL.equals(host.capture("hm-cap", "0.0").foregroundCommand()),
                () -> host.capture("hm-cap", "0.0").toString());
        Instant before = Instant.now();
        Screen screen = host.capture("hm-cap", "7.3"); // the pane target is ignored: there is one pane
        assertFalse(screen.paneDead());
        assertTrue(screen.text().endsWith("\n"), "visible screen, newline-joined, ending in a newline");
        assertTrue(screen.paneProcessId() > 0, "the shell's pid");
        assertEquals(SHELL, screen.foregroundCommand());
        assertFalse(screen.capturedAt().isBefore(before));
        assertFalse(screen.capturedAt().isAfter(Instant.now()));

        host.killSession("hm-cap");
        awaitUntil("the killed session to capture as a dead pane", () -> host.capture("hm-cap", "0.0").paneDead());
    }

    @Test
    void newSessionWithAnEnvironmentReportsThatTheDaemonAppliedIt() {
        assertTrue(host.newSession("hm-env", workdir, 80, 24, java.util.Map.of("HM_TEST_VAR", "env-ok")),
                "this daemon acknowledges env");
        assertTrue(host.newSession("hm-noenv", workdir, 80, 24, java.util.Map.of()), "nothing asked, nothing lost");
        host.sendKeys("hm-env", "echo \"[$HM_TEST_VAR]\"");
        awaitUntil("the variable to reach the shell", () -> host.capture("hm-env", "0.0").text().contains("[env-ok]"),
                () -> host.capture("hm-env", "0.0").toString());
    }

    @Test
    void newSendKeysCaptureAttachInputOutputDetachKill() throws Exception {
        String name = "hm-e2e";
        host.newSession(name, workdir, 100, 30);
        assertTrue(host.hasSession(name));
        assertEquals(List.of(name), host.listSessions());
        assertEquals("0.0", host.currentPaneTarget(name));
        SessionHostException duplicate = assertThrows(SessionHostException.class,
                () -> host.newSession(name, workdir, 100, 30));
        assertTrue(duplicate.getMessage().contains("already exists"), duplicate.getMessage());
        assertInstanceOf(SessionServerException.class, duplicate.getCause());

        // send-keys → capture: the command's output (not its keystroke echo) is on the
        // screen and the shell is back at its prompt as the foreground process.
        host.sendKeys(name, "echo hello-''e2e");
        awaitUntil("capture to show the echo with the shell idle", () -> {
            Screen s = host.capture(name, "0.0");
            return s.text().contains("hello-e2e") && SHELL.equals(s.foregroundCommand());
        }, () -> host.capture(name, "0.0").toString());
        Screen screen = host.capture(name, "0.0");
        assertFalse(screen.paneDead());
        assertTrue(screen.paneProcessId() > 0, "the shell's pid");
        // macOS: the temp dir is under /var, a symlink to /private/var; the probe reports the real path
        assertEquals(workdir.toRealPath(), host.currentPath(name).toRealPath());

        // attach: the redraw carries the earlier output, typed input reaches the shell,
        // and its output comes back on the same stream
        SessionConnection connection = attach(name, 120, 32, new AttachOptions(true));
        assertEquals(name, connection.name());
        assertTrue(connection.isAlive());
        StreamReader reader = StreamReader.start(connection);
        awaitUntil("the redraw to contain the earlier output", () -> reader.text().contains("hello-e2e"), reader::text);
        int mark = reader.length();
        connection.write("echo bye-''e2e\r".getBytes(StandardCharsets.UTF_8));
        awaitUntil("live output after typed input", () -> reader.textAfter(mark).contains("bye-e2e"), reader::text);
        assertDoesNotThrow(() -> connection.resize(100, 30));
        assertTrue(connection.isAlive());

        // detach: the connection ends, the session does not
        connection.close();
        assertFalse(connection.isAlive());
        assertEquals(0, connection.waitFor(), "a detach has no exit status");
        reader.join();
        assertTrue(host.hasSession(name), "detach never kills the session");
        awaitUntil("the daemon to drop the detached client", () -> client.list().get(0).clients() == 0);

        // kill: gone from has/list, a dead pane on capture, and killing again is not an error
        host.killSession(name);
        awaitUntil("the session to be gone", () -> !host.hasSession(name));
        assertEquals(List.of(), host.listSessions());
        assertTrue(host.capture(name, "0.0").paneDead());
        assertDoesNotThrow(() -> host.killSession(name));
    }

    /** Hermeticity pin: the session's cwd is the requested workdir and its foreground
     * process is {@code sh}, whatever {@code $SHELL} and its login files say — so no
     * developer dotfile can move the cwd or replace the foreground process under the
     * assertions above. */
    @Test
    void sessionsIgnoreTheDevelopersLoginFiles() throws Exception {
        host.newSession("hm-sh", workdir, 80, 24);
        awaitUntil("the plain shell to be idle at its prompt",
                () -> SHELL.equals(host.capture("hm-sh", "0.0").foregroundCommand()),
                () -> host.capture("hm-sh", "0.0").toString());
        assertEquals(SHELL, host.capture("hm-sh", "0.0").foregroundCommand());
        // macOS: the temp dir is under /var, a symlink to /private/var; the probe reports the real path
        assertEquals(workdir.toRealPath(), host.currentPath("hm-sh").toRealPath());
        host.killSession("hm-sh");
    }

    /** The unwrapped manager — the daemon's real {@code $SHELL -l} — checked only for
     * properties no dotfile can change: the session exists, is listed, captures live
     * with a pid, and dies on kill. */
    @Test
    void genuineLoginShellSmoke() throws Exception {
        Path loginHome = Files.createDirectories(root.resolve("login"));
        ServeOptions options = ServeOptions.defaults(loginHome).withKeepAlive(true);
        SessionServer login = new SessionServer(options, SessionManagerFactory.createSessionManager(options));
        servers.add(login);
        assertTrue(login.start(), "bound on " + loginHome);
        SessionServerHost loginHost = new SessionServerHost(new SessionServerClient(loginHome, false));

        loginHost.newSession("hm-login", workdir, 80, 24);
        assertTrue(loginHost.hasSession("hm-login"));
        assertEquals(List.of("hm-login"), loginHost.listSessions());
        awaitUntil("the login shell to capture with a pid", () -> {
            Screen s = loginHost.capture("hm-login", "0.0");
            return !s.paneDead() && s.paneProcessId() > 0;
        }, () -> loginHost.capture("hm-login", "0.0").toString());

        loginHost.killSession("hm-login");
        awaitUntil("the login session to be gone", () -> !loginHost.hasSession("hm-login"));
    }

    @Test
    void serverExitsAfterTheLastKillWhenNotKeptAlive() throws Exception {
        Path exitingHome = Files.createDirectories(root.resolve("exit"));
        SessionServer exiting = start(ServeOptions.defaults(exitingHome)); // no keep-alive: the daemon's normal mode
        SessionServerHost exitingHost = new SessionServerHost(new SessionServerClient(exitingHome, false));
        exitingHost.newSession("hm-last", workdir, 80, 24); // cancels the boot idle timer
        assertTrue(exitingHost.hasSession("hm-last"));

        exitingHost.killSession("hm-last");
        // The idle timer re-arms when the count drops to zero and fires 2 s later.
        assertTrue(exiting.awaitExitWithin(WAIT), "the server exits after its last session ends");
        assertFalse(exiting.isRunning());
        assertFalse(Files.exists(ServerHome.socket(exitingHome)), "the socket file goes with it");
        // A host over the now-dead home is back to the no-server semantics.
        assertEquals(List.of(), exitingHost.listSessions());
        assertFalse(exitingHost.hasSession("hm-last"));
        assertTrue(exitingHost.capture("hm-last", "0.0").paneDead());
    }

    // ---- helpers

    /** The real PTY-backed manager, with an empty command (what {@link SessionServerHost}
     * always sends) turned into a plain {@code /bin/sh} — see the class doc. */
    private SessionServer start(ServeOptions options) throws IOException {
        SessionManager real = SessionManagerFactory.createSessionManager(options);
        SessionManager hermetic = new SessionManager() {
            @Override
            public Session create(String name, Path workdir, int columns, int rows, List<String> command) {
                return real.create(name, workdir, columns, rows,
                        command == null || command.isEmpty() ? List.of("/bin/sh") : command);
            }

            @Override
            public Session create(String name, Path workdir, int columns, int rows, List<String> command,
                                  java.util.Map<String, String> labels, java.util.Map<String, String> environment) {
                return real.create(name, workdir, columns, rows,
                        command == null || command.isEmpty() ? List.of("/bin/sh") : command, labels, environment);
            }

            @Override
            public Optional<Session> find(String name) {
                return real.find(name);
            }

            @Override
            public List<Session> sessions() {
                return real.sessions();
            }

            @Override
            public void kill(String name) {
                real.kill(name);
            }

            @Override
            public void killAll() {
                real.killAll();
            }

            @Override
            public void setListener(Listener listener) {
                real.setListener(listener);
            }

            @Override
            public void addCreatedListener(Consumer<Session> listener) {
                real.addCreatedListener(listener);
            }

            @Override
            public void removeCreatedListener(Consumer<Session> listener) {
                real.removeCreatedListener(listener);
            }
        };
        SessionServer started = new SessionServer(options, hermetic);
        servers.add(started);
        assertTrue(started.start(), "bound on " + options.home());
        return started;
    }

    private SessionConnection attach(String name, int columns, int rows, AttachOptions options) {
        SessionConnection connection = host.attach(name, columns, rows, options);
        connections.add(connection);
        return connection;
    }

    private static void awaitUntil(String what, BooleanSupplier condition) {
        awaitUntil(what, condition, () -> "");
    }

    private static void awaitUntil(String what, BooleanSupplier condition, Supplier<String> detail) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what + " after " + WAIT + ": " + detail.get());
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
    }

    /** Drains a connection on its own thread so assertions can poll what has arrived so
     * far; bytes are kept as ISO-8859-1 so a read boundary inside a UTF-8 sequence
     * cannot corrupt the ASCII the assertions look for. */
    private static final class StreamReader {
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final Thread thread;

        private StreamReader(SessionConnection connection) {
            thread = Thread.ofVirtual().name("test-attach-reader").start(() -> {
                byte[] buf = new byte[65536];
                try {
                    while (true) {
                        int n = connection.read(buf, 0, buf.length);
                        if (n < 0) {
                            return;
                        }
                        synchronized (this) {
                            received.write(buf, 0, n);
                        }
                    }
                } catch (IOException e) {
                    // the stream ended with an error; the assertions report what arrived
                }
            });
        }

        static StreamReader start(SessionConnection connection) {
            return new StreamReader(connection);
        }

        synchronized String text() {
            return received.toString(StandardCharsets.ISO_8859_1);
        }

        synchronized int length() {
            return received.size();
        }

        synchronized String textAfter(int offset) {
            byte[] all = received.toByteArray();
            return offset >= all.length ? "" : new String(all, offset, all.length - offset, StandardCharsets.ISO_8859_1);
        }

        void join() throws InterruptedException {
            thread.join(WAIT.toMillis());
            assertFalse(thread.isAlive(), "the reader never saw end-of-stream after the detach");
        }
    }

    /** A listener that answers every request with a protocol mismatch, standing in for
     * a daemon build this app cannot talk to. */
    private static final class MismatchListener implements AutoCloseable {
        private final ServerSocketChannel listener;

        private MismatchListener(ServerSocketChannel listener) {
            this.listener = listener;
        }

        static MismatchListener start(Path socket, int protocol, String version, long pid) throws IOException {
            ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(socket));
            String reply = Json.error(Protocol.ERROR_PROTOCOL_MISMATCH)
                    .put(Protocol.F_PROTOCOL, protocol).put(Protocol.F_VERSION, version).put(Protocol.F_PID, pid)
                    .toString();
            Thread.ofVirtual().start(() -> {
                while (true) {
                    try (SocketChannel accepted = listener.accept()) {
                        FrameCodec codec = new FrameCodec(Channels.newInputStream(accepted),
                                Channels.newOutputStream(accepted));
                        codec.read();
                        codec.write(Frame.json(FrameType.RESPONSE, reply));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            return new MismatchListener(listener);
        }

        boolean isOpen() {
            return listener.isOpen();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
