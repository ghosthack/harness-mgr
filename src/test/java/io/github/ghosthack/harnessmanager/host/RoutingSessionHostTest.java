package io.github.ghosthack.harnessmanager.host;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.machine.Machine;
import io.github.ghosthack.harnessmanager.machine.MachineRegistry;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionNaming;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dispatch tests against {@link FakeSessionHost} instances — no live SSH or tmux
 * involved: remote backends come from an injected factory returning a fake. */
final class RoutingSessionHostTest {

    private static final String TMUX = SessionEntry.TMUX_HOST;
    private static final String SERVER = "jmux";

    private static MachineRegistry machines(Path dir, Machine... seed) {
        MachineRegistry registry = MachineRegistry.load(dir.resolve("machines.json"));
        for (Machine machine : seed) {
            registry.put(machine);
        }
        return registry;
    }

    private static RoutingSessionHost.RemoteHostFactory noRemotes() {
        return machine -> {
            throw new AssertionError("no remote backend expected for " + machine.label());
        };
    }

    private static Map<String, SessionHost> hosts(SessionHost... hosts) {
        Map<String, SessionHost> byId = new LinkedHashMap<>();
        for (SessionHost host : hosts) {
            byId.put(host.id(), host);
        }
        return byId;
    }

    @Test
    void localSessionRoutesToTmuxByDefault(@TempDir Path dir) {
        FakeSessionHost tmux = new FakeSessionHost(TMUX);
        FakeSessionHost server = new FakeSessionHost(SERVER);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(tmux, server), machines(dir), sessions, noRemotes());

        router.newSession("hm-local", Path.of("/a"), 120, 32);

        assertTrue(tmux.hasSession("hm-local"));
        assertTrue(server.newSessionCalls.isEmpty());
    }

    @Test
    void registeredRemoteSessionRoutesToItsMachinesBackendNotLocal(@TempDir Path dir) {
        FakeSessionHost local = new FakeSessionHost(TMUX);
        FakeSessionHost remote = new FakeSessionHost(TMUX);
        Machine machine = Machine.create("lan23", "lan23", false);
        MachineRegistry machineRegistry = machines(dir, machine);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        sessions.put(SessionEntry.newlyCreated("hm-remote", "/remote/project",
                HarnessType.OPENCODE, null, "0.0", machine.id()));

        RoutingSessionHost router = new RoutingSessionHost(hosts(local), machineRegistry, sessions, m -> remote);

        remote.seedLiveSession("hm-remote");

        assertTrue(router.hasSession("hm-remote"));
        assertFalse(local.hasSession("hm-remote"));

        router.sendKeys("hm-remote", "opencode");
        assertEquals(List.of("hm-remote: opencode"), remote.sentCommands);
        assertTrue(local.sentCommands.isEmpty());
    }

    @Test
    void hasSessionOnProbesTheSpecificMachineNotJustAnyRemote(@TempDir Path dir) {
        FakeSessionHost local = new FakeSessionHost(TMUX);
        FakeSessionHost remoteA = new FakeSessionHost(TMUX);
        FakeSessionHost remoteB = new FakeSessionHost(TMUX);
        Machine machineA = Machine.create("a", "a", false);
        Machine machineB = Machine.create("b", "b", false);
        MachineRegistry machineRegistry = machines(dir, machineA, machineB);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(local), machineRegistry, sessions,
                machine -> machine.id().equals(machineA.id()) ? remoteA : remoteB);

        remoteA.seedLiveSession("hm-foo");

        assertTrue(router.hasSessionOn(machineA.id(), "hm-foo"));
        assertFalse(router.hasSessionOn(machineB.id(), "hm-foo"));
        assertFalse(router.hasSessionOn(null, "hm-foo"));

        local.seedLiveSession("hm-foo");
        assertTrue(router.hasSessionOn(null, "hm-foo"));
    }

    @Test
    void deletedMachineIsTreatedAsNotLiveNeverThrown(@TempDir Path dir) {
        FakeSessionHost local = new FakeSessionHost(TMUX);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        // registers a session against a machine id that was never (or no longer) added
        sessions.put(SessionEntry.newlyCreated("hm-orphan", "/a", HarnessType.CLAUDE_CODE,
                null, "0.0", "deleted-machine-id"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(local), machines(dir), sessions, noRemotes());

        assertFalse(router.hasSession("hm-orphan"));
        assertTrue(router.listSessions().isEmpty());
        assertTrue(router.features("hm-orphan").isEmpty());
        assertThrows(SessionHostException.class, () -> router.newSession("hm-orphan", Path.of("/a"), 120, 32));
    }

    @Test
    void listSessionsUnionsLocalHostsWithReachableRegisteredRemotes(@TempDir Path dir) {
        FakeSessionHost local = new FakeSessionHost(TMUX);
        FakeSessionHost server = new FakeSessionHost(SERVER);
        FakeSessionHost remote = new FakeSessionHost(TMUX);
        local.seedLiveSession("hm-local");
        server.seedLiveSession("hm-daemon");
        Machine machine = Machine.create("lan23", "lan23", false);
        MachineRegistry machineRegistry = machines(dir, machine);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        sessions.put(SessionEntry.newlyCreated("hm-remote-live", "/a", HarnessType.SHELL,
                null, "0.0", machine.id()));
        sessions.put(SessionEntry.newlyCreated("hm-remote-dead", "/a", HarnessType.SHELL,
                null, "0.0", machine.id()));
        RoutingSessionHost router = new RoutingSessionHost(hosts(local, server), machineRegistry, sessions,
                m -> remote);
        remote.seedLiveSession("hm-remote-live"); // "hm-remote-dead" is registered but not live

        List<String> live = router.listSessions();
        assertTrue(live.contains("hm-local"));
        assertTrue(live.contains("hm-daemon"));
        assertTrue(live.contains("hm-remote-live"));
        assertFalse(live.contains("hm-remote-dead"));
    }

    /** Required on Windows, where a tmux host is still constructed but the binary is
     * absent — and symmetrically on macOS while the daemon isn't running. */
    @Test
    void aLocalHostThatCannotListContributesNothingAndBlocksNothing(@TempDir Path dir) {
        FakeSessionHost tmux = new FakeSessionHost(TMUX);
        FakeSessionHost server = new FakeSessionHost(SERVER);
        tmux.seedLiveSession("hm-local");
        server.failListing(new SessionHostException("connect refused"));
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(tmux, server), machines(dir), sessions, noRemotes());

        assertEquals(List.of("hm-local"), router.listSessions());
        assertTrue(router.hasSessionOn(null, "hm-local"), "the working host still answers");
        assertFalse(router.hasSessionOn(null, "hm-other"), "the failing host is treated as empty");
    }

    /** The registry key is host-agnostic, so a name live on tmux is taken for a
     * jmux create too — and the pending registration then routes the create. */
    @Test
    void unmanagedNameOnTmuxMakesASessionServerCreateDeriveTheNextSuffix(@TempDir Path dir) {
        FakeSessionHost tmux = new FakeSessionHost(TMUX);
        FakeSessionHost server = new FakeSessionHost(SERVER);
        tmux.seedLiveSession("hm-foo"); // live on tmux, never registered
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(tmux, server), machines(dir), sessions, noRemotes());

        String name = SessionNaming.deriveUnique(Path.of("/Users/x/Projects/foo"),
                candidate -> sessions.find(candidate).isPresent() || router.hasSessionOn(null, candidate));
        assertEquals("hm-foo-2", name);

        router.registerPending(name, null, SERVER);
        router.newSession(name, Path.of("/Users/x/Projects/foo"), 120, 32);
        assertTrue(server.hasSession("hm-foo-2"));
        assertFalse(tmux.hasSession("hm-foo-2"));
        assertEquals("0.0", router.currentPaneTarget(name), "still routed while pending");
        router.clearPending(name);
    }

    /** Every rebuild of a jmux row goes through a with-er or newlyCreated,
     * so the host survives resume (withTargetPane), duplicate and adopt (newlyCreated). */
    @Test
    void sessionServerRowsKeepTheirHostThroughEveryRowRebuild() {
        SessionEntry created = SessionEntry.newlyCreated("hm-foo", "/a", HarnessType.CLAUDE_CODE,
                null, "0.0", null, SERVER);
        assertEquals(SERVER, created.hostId());
        assertEquals(SERVER, created.withTargetPane("0.1").hostId(), "resume");
        assertEquals("0.1", created.withTargetPane("0.1").targetPane());
        assertEquals(SERVER, created.withDisplayName("renamed").hostId());
        assertEquals(SERVER, created.withHarnessSessionId("abc").hostId());
        assertEquals(SERVER, SessionEntry.newlyCreated("hm-foo-2", "/a", HarnessType.CLAUDE_CODE,
                null, "0.0", null, created.hostId()).hostId(), "duplicate");
        assertEquals(TMUX, SessionEntry.newlyCreated("hm-bar", "/a", HarnessType.SHELL,
                null, "0.0", null, null).hostId(), "a null host is tmux");
    }

    @Test
    void sessionServerRowRoutesEveryCallToTheSessionServerHost(@TempDir Path dir) {
        FakeSessionHost tmux = new FakeSessionHost(TMUX);
        FakeSessionHost server = new FakeSessionHost(SERVER);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(tmux, server), machines(dir), sessions, noRemotes());

        // adopt: the unmanaged table names the host that listed the session
        server.seedLiveSession("hm-adopted");
        router.listSessions();
        assertEquals(Optional.of(SERVER), router.hostIdOf("hm-adopted"));
        assertEquals(Optional.empty(), router.hostIdOf("hm-unknown"));
        sessions.put(SessionEntry.newlyCreated("hm-adopted", "/a", HarnessType.CODEX, null, "0.0",
                null, router.hostIdOf("hm-adopted").orElseThrow()));

        // resume: same name, same host, new pane target
        SessionEntry resumed = sessions.find("hm-adopted").orElseThrow().withTargetPane("0.0");
        sessions.put(resumed);
        assertEquals(SERVER, sessions.find("hm-adopted").orElseThrow().hostId());

        server.screen("hm-adopted", "daemon output");
        assertEquals("daemon output", router.capture("hm-adopted", "0.0").text());
        assertEquals(List.of("hm-adopted:0.0"), server.captureCalls);
        assertTrue(tmux.captureCalls.isEmpty());

        router.attach("hm-adopted", 120, 32, new AttachOptions(true)).close();
        assertEquals(List.of("hm-adopted 120x32"), server.attachCalls);
        assertTrue(router.features("hm-adopted").isEmpty(), "the daemon fake reports no features");
    }

    /** "Move to …" on a dead row rewrites only the host, so the next Resume lands there. */
    @Test
    void movedDeadRowResumesOnItsNewHost(@TempDir Path dir) {
        FakeSessionHost tmux = new FakeSessionHost(TMUX);
        FakeSessionHost server = new FakeSessionHost(SERVER);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(tmux, server), machines(dir), sessions, noRemotes());
        SessionEntry row = SessionEntry.newlyCreated("hm-moved", "/a", HarnessType.CLAUDE_CODE, "conv",
                "0.0", null, TMUX).withDisplayName("named");

        sessions.put(row.withHost(SERVER));
        SessionEntry moved = sessions.find("hm-moved").orElseThrow();
        assertEquals(SERVER, moved.hostId());
        assertEquals("conv", moved.harnessSessionId(), "the resume id rides along");
        assertEquals("named", moved.displayName());

        router.newSession("hm-moved", Path.of("/a"), 120, 32);
        assertTrue(server.hasSession("hm-moved"));
        assertFalse(tmux.hasSession("hm-moved"));

        sessions.put(moved.withHost(TMUX));
        router.newSession("hm-moved", Path.of("/a"), 120, 32);
        assertTrue(tmux.hasSession("hm-moved"), "and back");
    }

    @Test
    void featuresAndOptionCallsFollowTheRow(@TempDir Path dir) {
        FakeSessionHost tmux = new FakeSessionHost(TMUX, java.util.EnumSet.allOf(HostFeature.class));
        FakeSessionHost server = new FakeSessionHost(SERVER);
        tmux.seedLiveSession("hm-t");
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        sessions.put(SessionEntry.newlyCreated("hm-t", "/a", HarnessType.SHELL, null, "0.0", null, TMUX));
        RoutingSessionHost router = new RoutingSessionHost(hosts(tmux, server), machines(dir), sessions, noRemotes());

        assertTrue(router.features("hm-t").contains(HostFeature.STIMULATION));
        router.setMouseMode("hm-t", true);
        router.scrollPage("hm-t", true);
        assertEquals(List.of("hm-t: on"), tmux.mouseModeCalls);
        assertEquals(List.of("hm-t: up"), tmux.pageCalls);
    }

    @Test
    void unmanagedTableKeepsTheFirstHostInMapOrderWhenBothListAName(@TempDir Path dir) {
        FakeSessionHost tmux = new FakeSessionHost(TMUX);
        FakeSessionHost server = new FakeSessionHost(SERVER);
        tmux.seedLiveSession("hm-both");
        server.seedLiveSession("hm-both");
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(tmux, server), machines(dir), sessions, noRemotes());

        assertEquals(List.of("hm-both"), router.listSessions(), "listed once");
        assertEquals(Optional.of(TMUX), router.hostIdOf("hm-both"));
        router.killSession("hm-both");
        assertEquals(List.of("hm-both"), tmux.killedSessions());
        assertTrue(server.killedSessions().isEmpty());
    }

    @Test
    void pendingRegistrationRoutesABrandNewNameBeforeItIsInTheRegistry(@TempDir Path dir) {
        FakeSessionHost local = new FakeSessionHost(TMUX);
        FakeSessionHost remote = new FakeSessionHost(TMUX);
        Machine machine = Machine.create("lan23", "lan23", false);
        MachineRegistry machineRegistry = machines(dir, machine);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts(local), machineRegistry, sessions, m -> remote);

        router.registerPending("hm-brand-new", machine.id(), null);
        router.newSession("hm-brand-new", Path.of("/remote/proj"), 120, 32);

        assertTrue(remote.hasSession("hm-brand-new"));
        assertFalse(local.hasSession("hm-brand-new"));

        router.clearPending("hm-brand-new");
        // once cleared and still absent from the registry, it falls back to local —
        // matching MainWindow.createSession's finally-block cleanup
        assertFalse(router.hasSessionOn(null, "hm-brand-new")); // the local fake never saw it
    }

    @Test
    void refreshMachineRebuildsTheRemoteBackend(@TempDir Path dir) {
        FakeSessionHost first = new FakeSessionHost(TMUX);
        FakeSessionHost second = new FakeSessionHost(TMUX);
        Machine machine = Machine.create("lan23", "lan23", false);
        MachineRegistry machineRegistry = machines(dir, machine);
        SessionRegistry sessions = SessionRegistry.load(dir.resolve("sessions.json"));
        sessions.put(SessionEntry.newlyCreated("hm-remote", "/a", HarnessType.SHELL, null, "0.0", machine.id()));
        int[] builds = {0};
        RoutingSessionHost router = new RoutingSessionHost(hosts(new FakeSessionHost(TMUX)), machineRegistry,
                sessions, m -> builds[0]++ == 0 ? first : second);

        first.seedLiveSession("hm-remote");
        assertTrue(router.hasSession("hm-remote"));
        router.refreshMachine(machine);
        assertFalse(router.hasSession("hm-remote"), "the rebuilt backend is a fresh instance");
        assertEquals(2, builds[0]);
    }
}
