package io.github.ghosthack.harnessmanager.session;

import io.github.ghosthack.harnessmanager.harness.ClaudeProjects;
import io.github.ghosthack.harnessmanager.harness.HarnessPlugin;
import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.host.FakeSessionHost;
import io.github.ghosthack.harnessmanager.host.RoutingSessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.machine.MachineRegistry;
import io.github.ghosthack.harnessmanager.mcp.McpService;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch sequence against fake tmux and jmux hosts behind a real router: naming,
 * routing, the typed command (with and without MCP injection), labels and the registry
 * row — for both a fresh create and a resume.
 */
final class SessionLauncherTest {

    private static final String URL = "http://127.0.0.1:7391/mcp/";
    private static final String TOKEN = "secret-token";

    @TempDir
    Path dir;

    private FakeSessionHost tmux;
    private FakeSessionHost jmux;
    private SessionRegistry registry;
    private RoutingSessionHost router;
    private Path project;
    private String mcpUrl;
    private Path mcpDir;
    private Path claudeProjects;
    private final List<String> labelCalls = new ArrayList<>();
    /** Every env-carrying jmux start, as {@code session env}. */
    private final List<String> envStarts = new ArrayList<>();
    /** What the fake daemon acknowledges: false plays one that predates {@code env}. */
    private boolean envAcknowledged = true;

    @BeforeEach
    void setUp() throws Exception {
        tmux = new FakeSessionHost(SessionEntry.TMUX_HOST);
        jmux = new FakeSessionHost("jmux");
        Map<String, SessionHost> hosts = new LinkedHashMap<>();
        hosts.put(tmux.id(), tmux);
        hosts.put(jmux.id(), jmux);
        registry = SessionRegistry.load(dir.resolve("sessions.json"));
        router = new RoutingSessionHost(hosts, MachineRegistry.load(dir.resolve("machines.json")), registry,
                machine -> {
                    throw new AssertionError("no remotes");
                });
        project = Files.createDirectories(dir.resolve("proj"));
        mcpUrl = URL;
        mcpDir = dir.resolve("mcp");
        claudeProjects = dir.resolve("claude-projects");
        System.setProperty(ClaudeProjects.OVERRIDE_PROPERTY, claudeProjects.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ClaudeProjects.OVERRIDE_PROPERTY);
    }

    private SessionLauncher launcher() {
        return new SessionLauncher(registry, router,
                session -> mcpUrl == null ? null : new McpService.McpLink(mcpUrl + session, TOKEN), mcpDir,
                (session, labels) -> labelCalls.add(session + " " + labels),
                (session, workdir, columns, rows, env) -> {
                    envStarts.add(session + " " + env);
                    jmux.newSession(session, workdir, columns, rows);
                    return envAcknowledged;
                });
    }

    @Test
    void jmuxCreateInjectsTheMcpServerLabelsAndRegistersTheDisplayName() throws Exception {
        String name = launcher().create(project, HarnessType.CLAUDE_CODE, Optional.empty(), null, "jmux",
                Optional.of("reviewer"), Map.of("spawnedBy", "hm-boss"));

        assertEquals("hm-proj", name);
        assertEquals(List.of("hm-proj " + project + " 120x32"), jmux.newSessionCalls);
        assertTrue(tmux.newSessionCalls.isEmpty());
        assertTrue(envStarts.isEmpty(), "claude reads its token from the config file, not the environment");
        Path config = mcpDir.resolve("hm-proj.json");
        String id = registry.find("hm-proj").orElseThrow().harnessSessionId();
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), id);
        assertEquals(List.of("hm-proj: claude --mcp-config " + HarnessPlugin.shellArgument(config.toString())
                        + " --session-id " + id),
                jmux.sentCommands);
        assertEquals("{\"mcpServers\":{\"harness-manager\":{\"type\":\"http\",\"url\":\"" + URL + "hm-proj\","
                + "\"headers\":{\"Authorization\":\"Bearer " + TOKEN + "\"}}}}\n", Files.readString(config));
        assertEquals(List.of("hm-proj {spawnedBy=hm-boss, harness=claude-code, project=" + project
                + ", display=reviewer}"), labelCalls);
        SessionEntry entry = registry.find("hm-proj").orElseThrow();
        assertEquals("jmux", entry.hostId());
        assertEquals("reviewer", entry.displayName());
        assertEquals(HarnessType.CLAUDE_CODE, entry.harnessType());
        assertEquals("0.0", entry.targetPane());
        assertNull(entry.machineId());
    }

    @Test
    void onlyAHarnessThatReadsTheTokenFromTheEnvironmentGetsIt() {
        SessionLauncher launcher = launcher();
        launcher.create(project, HarnessType.CODEX, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        launcher.create(project, HarnessType.SHELL, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        launcher.create(project, HarnessType.OPENCODE, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        assertEquals(List.of("hm-proj {HM_MCP_TOKEN=" + TOKEN + "}"), envStarts, "codex only");
        assertEquals(3, jmux.newSessionCalls.size());
    }

    @Test
    void aDaemonThatDropsTheEnvironmentGetsCodexLaunchedWithoutMcp() {
        envAcknowledged = false;
        SessionLauncher launcher = launcher();
        launcher.create(project, HarnessType.CODEX, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        assertEquals(List.of("hm-proj {HM_MCP_TOKEN=" + TOKEN + "}"), envStarts);
        assertEquals(List.of("hm-proj: codex"), jmux.sentCommands, "no MCP flags its harness could not use");
        assertTrue(registry.find("hm-proj").isPresent());

        // a harness that reads a config file is unaffected
        launcher.create(project, HarnessType.CLAUDE_CODE, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        assertTrue(jmux.sentCommands.getLast().startsWith("hm-proj-2: claude --mcp-config "),
                jmux.sentCommands.getLast());

        // and a resume on the same old daemon likewise drops MCP
        SessionEntry dead = registry.find("hm-proj").orElseThrow();
        jmux.killSession("hm-proj");
        launcher.resume(dead, () -> {
        });
        assertEquals("hm-proj: codex", jmux.sentCommands.getLast());
    }

    @Test
    void jmuxCreateWithoutARunningMcpServiceTypesThePlainCommand() {
        mcpUrl = null;
        launcher().create(project, HarnessType.CODEX, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        assertEquals(List.of("hm-proj: codex"), jmux.sentCommands);
        assertTrue(envStarts.isEmpty(), "no MCP, no token in the environment");
        assertEquals(List.of("hm-proj " + project + " 120x32"), jmux.newSessionCalls);
        assertNull(registry.find("hm-proj").orElseThrow().displayName());
    }

    @Test
    void tmuxCreateIsNeverInjectedNorLabelled() {
        launcher().create(project, HarnessType.CLAUDE_CODE, Optional.of("abc-1"), null, SessionEntry.TMUX_HOST,
                Optional.of("  "), Map.of());
        assertEquals(List.of("hm-proj: claude --resume abc-1"), tmux.sentCommands);
        assertTrue(jmux.newSessionCalls.isEmpty());
        assertTrue(envStarts.isEmpty());
        assertFalse(Files.exists(mcpDir), "no config file for a tmux session");
        assertTrue(labelCalls.isEmpty());
        SessionEntry entry = registry.find("hm-proj").orElseThrow();
        assertEquals("abc-1", entry.harnessSessionId());
        assertNull(entry.displayName(), "a blank display name is no override");
    }

    @Test
    void shellHarnessTypesNothing() {
        launcher().create(project, HarnessType.SHELL, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        assertTrue(jmux.sentCommands.isEmpty());
        assertTrue(registry.find("hm-proj").isPresent());
    }

    @Test
    void namesStayUniqueAcrossHostsAndTheRegistry() {
        tmux.seedLiveSession("hm-proj");
        SessionLauncher launcher = launcher();
        assertEquals("hm-proj-2", launcher.create(project, HarnessType.SHELL, Optional.empty(), null, "jmux",
                Optional.empty(), Map.of()));
        assertEquals("hm-proj-3", launcher.create(project, HarnessType.SHELL, Optional.empty(), null, "jmux",
                Optional.empty(), Map.of()));
    }

    @Test
    void aResumeIdWithoutSyntaxWarnsThenLaunchesFresh() {
        AtomicInteger warned = new AtomicInteger();
        // the shell harness is the one without resume syntax; its fresh launch is "nothing"
        launcher().create(project, HarnessType.SHELL, Optional.of("x"), null, SessionEntry.TMUX_HOST,
                Optional.empty(), Map.of(), warned::incrementAndGet);
        assertEquals(1, warned.get());
        assertTrue(tmux.sentCommands.isEmpty());
        assertEquals(List.of("hm-proj " + project + " 120x32"), tmux.newSessionCalls);
    }

    @Test
    void aFailingLabellerNeverFailsTheLaunch() {
        SessionLauncher launcher = SessionLauncher.withoutMcp(registry, router, (session, labels) -> {
            throw new SessionHostException("daemon too old");
        });
        assertEquals("hm-proj", launcher.create(project, HarnessType.CODEX, Optional.empty(), null, "jmux",
                Optional.empty(), Map.of()));
        assertEquals(List.of("hm-proj: codex"), jmux.sentCommands);
    }

    @Test
    void aFailedCreatePropagatesAndReleasesThePendingRoute() {
        jmux.onSendKeys = command -> {
            throw new SessionHostException("boom");
        };
        assertThrows(SessionHostException.class, () -> launcher().create(project, HarnessType.CODEX,
                Optional.empty(), null, "jmux", Optional.empty(), Map.of()));
        assertTrue(registry.find("hm-proj").isEmpty());
        // without its pending route (and with no registry row) the name falls back to tmux
        router.newSession("hm-proj", project, 80, 24);
        assertEquals(List.of("hm-proj " + project + " 80x24"), tmux.newSessionCalls);
    }

    @Test
    void jmuxResumeInjectsAheadOfTheResumeSubcommandAndRefreshesThePaneTarget() {
        SessionEntry dead = new SessionEntry("hm-proj", project.toString(), HarnessType.CODEX,
                "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b", java.time.Instant.EPOCH, "9.9", "named", null, "jmux");
        registry.put(dead);
        launcher().resume(dead, () -> {
            throw new AssertionError("codex has resume syntax");
        });
        assertEquals(List.of("hm-proj: codex -c 'mcp_servers.harness-manager.url=\"" + URL
                + "hm-proj\"' -c 'mcp_servers.harness-manager.bearer_token_env_var=\"HM_MCP_TOKEN\"'"
                + " resume 0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"), jmux.sentCommands);
        assertEquals(List.of("hm-proj {HM_MCP_TOKEN=" + TOKEN + "}"), envStarts);
        assertFalse(Files.exists(mcpDir), "codex needs no config file");
        assertEquals(List.of("hm-proj {harness=codex, project=" + project + ", display=named}"), labelCalls);
        SessionEntry resumed = registry.find("hm-proj").orElseThrow();
        assertEquals("0.0", resumed.targetPane());
        assertEquals("named", resumed.displayName());
    }

    @Test
    void tmuxResumeIsUntouched() throws Exception {
        Path claudeDir = Files.createDirectories(claudeProjects.resolve(
                project.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-")));
        Files.writeString(claudeDir.resolve("id-7.jsonl"), "{}\n");
        SessionEntry dead = SessionEntry.newlyCreated("hm-proj", project.toString(), HarnessType.CLAUDE_CODE,
                "id-7", "0.0", null, SessionEntry.TMUX_HOST);
        registry.put(dead);
        launcher().resume(dead, () -> {
        });
        assertEquals(List.of("hm-proj: claude --resume id-7"), tmux.sentCommands);
        assertTrue(labelCalls.isEmpty());
    }

    @Test
    void theConfigFileIsOwnerOnlyRewrittenOnResumeAndDiscardedOnRequest() throws Exception {
        SessionLauncher launcher = launcher();
        launcher.create(project, HarnessType.CLAUDE_CODE, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        Path config = mcpDir.resolve("hm-proj.json");
        assertTrue(Files.exists(config));
        if (mcpDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(mcpDir)));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)));
        }
        try (var leftovers = Files.list(mcpDir)) {
            assertEquals(List.of(config), leftovers.toList(), "no temp file left behind");
        }

        // a resume after a token change overwrites the file with the current token
        mcpUrl = "http://127.0.0.1:9/mcp/";
        jmux.killSession("hm-proj");
        launcher.resume(registry.find("hm-proj").orElseThrow(), () -> {
        });
        assertTrue(Files.readString(config).contains("http://127.0.0.1:9/mcp/hm-proj"), Files.readString(config));

        // resumed while the service is off: the stale token's file goes
        mcpUrl = null;
        jmux.killSession("hm-proj");
        launcher.resume(registry.find("hm-proj").orElseThrow(), () -> {
        });
        assertFalse(Files.exists(config));
        assertEquals("hm-proj: claude --session-id " + registry.find("hm-proj").orElseThrow().harnessSessionId(),
                jmux.sentCommands.getLast(), "no MCP flag; still no message, so a fresh start under its id");

        Files.writeString(config, "x");
        launcher.discardMcpConfig("hm-proj");
        assertFalse(Files.exists(config));
        launcher.discardMcpConfig("hm-proj"); // already gone: no complaint
    }

    @Test
    void anUnwritableConfigDirLaunchesWithoutMcp() throws Exception {
        Files.writeString(dir.resolve("blocker"), "not a directory");
        mcpDir = dir.resolve("blocker").resolve("mcp");
        launcher().create(project, HarnessType.CLAUDE_CODE, Optional.empty(), null, "jmux", Optional.empty(), Map.of());
        assertEquals(List.of("hm-proj: claude --session-id " + registry.find("hm-proj").orElseThrow().harnessSessionId()),
                jmux.sentCommands);
    }

    @Test
    void sessionNamesBecomeFileSafeConfigNames() {
        assertEquals(mcpDir.resolve("a_b_.._c.json"), launcher().mcpConfigFile("a/b/../c"));
    }

    @Test
    void aNameThatKeepsGettingTakenGivesUpAfterBoundedRetries() {
        AtomicInteger probes = new AtomicInteger();
        // every candidate is registered by "someone else" right after it probes free
        jmux.onHasSession = session -> {
            probes.incrementAndGet();
            registry.put(SessionEntry.newlyCreated(session, project.toString(), HarnessType.SHELL, null, "0.0",
                    null, "jmux"));
        };
        assertThrows(IllegalStateException.class, () -> launcher().create(project, HarnessType.SHELL,
                Optional.empty(), null, "jmux", Optional.empty(), Map.of()));
        assertTrue(probes.get() <= 2 * SessionLauncher.MAX_NAME_ATTEMPTS, "bounded: " + probes.get());
        assertTrue(jmux.newSessionCalls.isEmpty());
    }

    @Test
    void aSlowHostProbeNeitherBlocksOtherLaunchesNorLetsThemShareAName() throws Exception {
        CountDownLatch probing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        // the first probe of hm-proj answers "free" and then stalls, like an SSH round trip
        jmux.onHasSession = session -> {
            if (session.equals("hm-proj") && probes.getAndIncrement() == 0) {
                probing.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        SessionLauncher launcher = launcher();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> slow = pool.submit(() -> launcher.create(project, HarnessType.SHELL, Optional.empty(),
                    null, "jmux", Optional.empty(), Map.of()));
            assertTrue(probing.await(10, TimeUnit.SECONDS));
            // the stalled probe holds no lock: a second launch in the same folder completes
            Future<String> quick = pool.submit(() -> launcher.create(project, HarnessType.SHELL, Optional.empty(),
                    null, "jmux", Optional.empty(), Map.of()));
            assertEquals("hm-proj", quick.get(10, TimeUnit.SECONDS));
            release.countDown();
            // the slow launch's "free" answer went stale meanwhile; it re-derives
            assertEquals("hm-proj-2", slow.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
    @Test
    void aFreshLocalClaudeIsGivenItsSessionIdSoTheRowKnowsItFromTheStart() {
        mcpUrl = null;
        launcher().create(project, HarnessType.CLAUDE_CODE, Optional.empty(), null, SessionEntry.TMUX_HOST,
                Optional.empty(), Map.of());
        String id = registry.find("hm-proj").orElseThrow().harnessSessionId();
        assertEquals(List.of("hm-proj: claude --session-id " + id), tmux.sentCommands);

        // a harness that can't be told its id launches as before, with no id on the row
        launcher().create(project, HarnessType.CODEX, Optional.empty(), null, SessionEntry.TMUX_HOST,
                Optional.empty(), Map.of());
        assertEquals("hm-proj-2: codex", tmux.sentCommands.getLast());
        assertNull(registry.find("hm-proj-2").orElseThrow().harnessSessionId());
    }

    @Test
    void aResumeWhoseConversationNeverGotAMessageStartsFreshUnderTheSameId() throws Exception {
        mcpUrl = null;
        SessionEntry dead = SessionEntry.newlyCreated("hm-proj", project.toString(), HarnessType.CLAUDE_CODE,
                "1111-2222", "0.0", null, SessionEntry.TMUX_HOST);
        registry.put(dead);
        launcher().resume(dead, () -> {
        });
        assertEquals("hm-proj: claude --session-id 1111-2222", tmux.sentCommands.getLast(),
                "no transcript yet: --resume would fail with \"No conversation found\"");

        Path claudeDir = Files.createDirectories(claudeProjects.resolve(
                project.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-")));
        Files.writeString(claudeDir.resolve("1111-2222.jsonl"), "{}\n");
        tmux.killSession("hm-proj");
        launcher().resume(dead, () -> {
        });
        assertEquals("hm-proj: claude --resume 1111-2222", tmux.sentCommands.getLast());
    }
}
