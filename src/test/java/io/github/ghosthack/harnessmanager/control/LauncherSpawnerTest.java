package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.host.FakeSessionHost;
import io.github.ghosthack.harnessmanager.host.RoutingSessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.machine.MachineRegistry;
import io.github.ghosthack.harnessmanager.mcp.McpService;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import io.github.ghosthack.harnessmanager.session.SessionEvents;
import io.github.ghosthack.harnessmanager.session.SessionLabeller;
import io.github.ghosthack.harnessmanager.session.SessionLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production spawner over a real launcher and router with fake hosts. */
final class LauncherSpawnerTest {

    @TempDir
    Path dir;

    @Test
    void spawnsOnJmuxRegistersAndAnnouncesThenKillsAndAnnounces() throws Exception {
        FakeSessionHost tmux = new FakeSessionHost(SessionEntry.TMUX_HOST);
        FakeSessionHost jmux = new FakeSessionHost("jmux");
        Map<String, SessionHost> hosts = new LinkedHashMap<>();
        hosts.put(tmux.id(), tmux);
        hosts.put(jmux.id(), jmux);
        SessionRegistry registry = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(hosts, MachineRegistry.load(dir.resolve("machines.json")),
                registry, machine -> {
                    throw new AssertionError("no remotes");
                });
        SessionLauncher launcher = SessionLauncher.withoutMcp(registry, router, SessionLabeller.NONE);
        SessionEvents events = new SessionEvents();
        List<String> changed = new ArrayList<>();
        List<String> killed = new ArrayList<>();
        events.addSessionsChangedListener(changed::add);
        events.addSessionKilledListener(killed::add);
        Path project = Files.createDirectories(dir.resolve("api"));

        LauncherSpawner spawner = new LauncherSpawner(launcher, jmux, events);
        String name = spawner.spawn(new SessionSpawner.SpawnRequest(HarnessType.CODEX, project,
                Optional.of("API"), Map.of("spawned-by", "hm-boss")));

        assertEquals("hm-api", name);
        assertEquals(List.of("hm-api " + project + " 120x32"), jmux.newSessionCalls);
        assertTrue(tmux.newSessionCalls.isEmpty());
        SessionEntry entry = registry.find("hm-api").orElseThrow();
        assertEquals("jmux", entry.hostId());
        assertEquals("API", entry.displayName());
        assertEquals(List.of("hm-api"), changed);

        spawner.kill("hm-api");
        assertEquals(List.of("hm-api"), jmux.killedSessions());
        assertEquals(List.of("hm-api"), killed);
        assertEquals(2, changed.size());
    }

    @Test
    void killDeletesTheSessionsMcpConfigFile() throws Exception {
        FakeSessionHost jmux = new FakeSessionHost("jmux");
        SessionRegistry registry = SessionRegistry.load(dir.resolve("sessions.json"));
        RoutingSessionHost router = new RoutingSessionHost(Map.of(jmux.id(), jmux),
                MachineRegistry.load(dir.resolve("machines.json")), registry, machine -> {
                    throw new AssertionError("no remotes");
                });
        Path mcpDir = dir.resolve("mcp");
        SessionLauncher launcher = new SessionLauncher(registry, router,
                session -> new McpService.McpLink("http://127.0.0.1:1/mcp/" + session, "tok"), mcpDir,
                SessionLabeller.NONE, (session, workdir, columns, rows, env) -> {
                    jmux.newSession(session, workdir, columns, rows);
                    return true;
                });
        LauncherSpawner spawner = new LauncherSpawner(launcher, jmux, new SessionEvents());
        String name = spawner.spawn(new SessionSpawner.SpawnRequest(HarnessType.CLAUDE_CODE,
                Files.createDirectories(dir.resolve("api")), Optional.empty(), Map.of()));
        assertTrue(Files.exists(mcpDir.resolve(name + ".json")));

        spawner.kill(name);
        assertFalse(Files.exists(mcpDir.resolve(name + ".json")));
    }
}
