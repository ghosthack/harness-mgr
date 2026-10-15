package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.host.AttachOptions;
import io.github.ghosthack.harnessmanager.host.FakeSessionHost;
import io.github.ghosthack.harnessmanager.host.HostFeature;
import io.github.ghosthack.harnessmanager.host.RoutingSessionHost;
import io.github.ghosthack.harnessmanager.host.SessionConnection;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.machine.MachineRegistry;
import io.github.ghosthack.harnessmanager.registry.RegistryPaths;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.stimulator.StimulatorClient;
import io.github.ghosthack.harnessmanager.stimulator.StimulatorClients;
import io.github.ghosthack.harnessmanager.tmux.SshExec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Drives a real {@link MainWindow} (never shown) over a routed fake host whose attach
 * for one session blocks until the test releases it, and a stimulator client reading
 * locks from a temp dir. Pins the landing rule from SESSION-HOST.md: when a connection
 * lands in the wrong mode because the stimulator's ownership changed in flight, the
 * re-dispatch shows the "Attaching to X…" placeholder only if X is still the selected
 * row — it must never cover the terminal of whichever other session the user has
 * selected meanwhile. Needs a display: {@code MainWindow} is a {@code JFrame}.
 */
final class MainWindowAttachLandingTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String A = "hm-a";
    private static final String B = "hm-b";

    @TempDir
    Path dir;

    private String previousRegistryOverride;
    private MainWindow window;

    @BeforeEach
    void pointSettingsAtTempDir() {
        // AppSettings resolves settings.properties beside the registry file
        previousRegistryOverride = System.getProperty(RegistryPaths.OVERRIDE_PROPERTY);
        System.setProperty(RegistryPaths.OVERRIDE_PROPERTY, dir.resolve("sessions.json").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (window != null) {
            SwingUtilities.invokeAndWait(window::shutdown);
        }
        if (previousRegistryOverride == null) {
            System.clearProperty(RegistryPaths.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(RegistryPaths.OVERRIDE_PROPERTY, previousRegistryOverride);
        }
    }

    @Test
    void modeRecheckRedispatchNeverHidesAnotherSelectedSessionsTerminal() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "MainWindow is a JFrame");

        SessionRegistry registry = SessionRegistry.load(dir.resolve("sessions.json"));
        for (String name : List.of(A, B)) {
            registry.put(SessionEntry.newlyCreated(name, Files.createDirectories(dir.resolve(name)).toString(),
                    HarnessType.SHELL, null, "0.0", null, SessionEntry.TMUX_HOST));
        }
        MachineRegistry machines = MachineRegistry.load(dir.resolve("machines.json"));
        FakeSessionHost tmux = new FakeSessionHost(SessionEntry.TMUX_HOST, EnumSet.of(HostFeature.STIMULATION));
        tmux.seedLiveSession(A);
        tmux.seedLiveSession(B);
        BlockingAttachHost host = new BlockingAttachHost(tmux, A);
        RoutingSessionHost router = new RoutingSessionHost(Map.of(SessionEntry.TMUX_HOST, host), machines,
                registry, machine -> fail("no remote backend expected"));
        Path locks = Files.createDirectories(dir.resolve("stimulator"));
        // the peek stand-in exits at once: what matters is that a PEEK attach is dispatched
        StimulatorClient stimulator = StimulatorClients.withCommand(List.of("/bin/sh", "-c", "exit 0"),
                locks, dir.resolve("logs"));

        window = onEdt(() -> new MainWindow(registry, router, machines, new SshExec(dir), Optional.of(stimulator)));

        // B selected and attached: its terminal is the visible card
        awaitOnEdt("the sidebar to list B", () -> window.selectSidebarRowForTesting(B));
        awaitOnEdt("B's attach to land", () -> window.terminalCardForTesting(B) != null);
        Component terminalB = onEdt(() -> window.terminalCardForTesting(B));
        assertSame(terminalB, onEdt(window::visibleCardForTesting));

        // A selected: its HOST attach is dispatched and blocks; the user goes back to B
        assertTrue(onEdt(() -> window.selectSidebarRowForTesting(A)));
        host.awaitAttachStarted(A);
        assertEquals("HOST", onEdt(() -> window.attachmentModeForTesting(A)));
        assertTrue(onEdt(() -> window.selectSidebarRowForTesting(B)));
        assertSame(terminalB, onEdt(window::visibleCardForTesting));

        // a stimulator takes A over while its attach is in flight, then the attach lands
        Files.writeString(locks.resolve(A + ".lock"), Long.toString(ProcessHandle.current().pid()));
        host.releaseAttach(A);
        awaitOnEdt("A's landing to re-dispatch in PEEK mode",
                () -> "PEEK".equals(window.attachmentModeForTesting(A)));

        // the re-dispatch happened under B's selection: B's terminal is still showing
        assertSame(terminalB, onEdt(window::visibleCardForTesting),
                "the 'Attaching to A' placeholder must not replace the selected session's terminal");
        assertNotNull(onEdt(() -> window.terminalCardForTesting(B)), "B's terminal was never evicted");
    }

    // ---- helpers

    private static <T> T onEdt(Callable<T> call) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(call.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /** Polls {@code condition} on the EDT until it holds; every poll also flushes
     * whatever the background executor has posted there in the meantime. */
    private static void awaitOnEdt(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!onEdt(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what + " after " + WAIT);
            }
            Thread.sleep(25);
        }
    }

    /**
     * A {@link FakeSessionHost} whose {@link #attach} for {@code blockedSession} parks
     * until {@link #releaseAttach} — the fake's analogue of a slow SSH or daemon
     * connect — and whose connections block on read until closed, so a landed
     * terminal stays alive (the fake's own connections end at once, which would make
     * the cached widget look dead on the next selection).
     */
    private static final class BlockingAttachHost implements SessionHost {
        private final FakeSessionHost delegate;
        private final String blockedSession;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingAttachHost(FakeSessionHost delegate, String blockedSession) {
            this.delegate = delegate;
            this.blockedSession = blockedSession;
        }

        void awaitAttachStarted(String session) throws InterruptedException {
            assertEquals(blockedSession, session);
            assertTrue(started.await(WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                    "the attach for " + session + " was never dispatched");
        }

        void releaseAttach(String session) {
            assertEquals(blockedSession, session);
            release.countDown();
        }

        @Override
        public SessionConnection attach(String session, int columns, int rows, AttachOptions options) {
            delegate.attach(session, columns, rows, options); // records the call, checks liveness
            if (session.equals(blockedSession)) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new HeldConnection(session);
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public boolean hasSession(String session) {
            return delegate.hasSession(session);
        }

        @Override
        public List<String> listSessions() {
            return delegate.listSessions();
        }

        @Override
        public void newSession(String session, Path workdir, int columns, int rows) {
            delegate.newSession(session, workdir, columns, rows);
        }

        @Override
        public void sendKeys(String session, String command) {
            delegate.sendKeys(session, command);
        }

        @Override
        public void killSession(String session) {
            delegate.killSession(session);
        }

        @Override
        public Path currentPath(String session) {
            return delegate.currentPath(session);
        }

        @Override
        public String currentPaneTarget(String session) {
            return delegate.currentPaneTarget(session);
        }

        @Override
        public Screen capture(String session, String targetPane) {
            return delegate.capture(session, targetPane);
        }

        @Override
        public EnumSet<HostFeature> features(String session) {
            return delegate.features(session);
        }

        @Override
        public void setMouseMode(String session, boolean on) {
            delegate.setMouseMode(session, on);
        }

        @Override
        public void setStatusBar(String session, boolean visible) {
            delegate.setStatusBar(session, visible);
        }

        @Override
        public void scrollPage(String session, boolean up) {
            delegate.scrollPage(session, up);
        }

        @Override
        public void jumpHistory(String session, boolean top) {
            delegate.jumpHistory(session, top);
        }
    }

    /** Alive, producing nothing, until closed; then reads return end-of-stream. */
    private static final class HeldConnection implements SessionConnection {
        private final String name;
        private final CountDownLatch closed = new CountDownLatch(1);

        HeldConnection(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public boolean isAlive() {
            return closed.getCount() > 0;
        }

        @Override
        public int waitFor() throws InterruptedException {
            closed.await();
            return 0;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
