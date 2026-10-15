package io.github.ghosthack.harnessmanager.session;

import io.github.ghosthack.harnessmanager.harness.HarnessPlugin;
import io.github.ghosthack.harnessmanager.harness.HarnessPlugins;
import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.harness.McpEndpoint;
import io.github.ghosthack.harnessmanager.host.RoutingSessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.jmux.SessionServerHost;
import io.github.ghosthack.harnessmanager.mcp.McpService.McpLink;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionNaming;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import io.github.ghosthack.harnessmanager.terminal.SessionTerminal;
import io.github.ghosthack.jmux.client.SessionServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Creates and resumes registered sessions: the blocking body behind the window's
 * Open…/Duplicate/Shell-here/Resume actions, and behind the MCP {@code spawn} tool. Free
 * of Swing and safe from any non-EDT thread — every call blocks on host round trips (and
 * {@code sendKeys}' deliberate 400 ms pause), so never call it on the EDT.
 *
 * <p>A launch is: a unique {@code hm-} name, a pending route so the router knows which
 * host owns the name before the registry does, the host session at the standard
 * terminal size, the harness launch command typed into its shell, and the registry row.
 * When the MCP service is running and the session lives on the local jmux host, the
 * launch command also registers the app's MCP server for that session (see
 * {@link HarnessPlugin#withMcpServer}); a jmux session additionally gets the
 * {@code harness}/{@code project}/{@code display} labels, best effort.
 *
 * <p>The MCP token is never typed: a harness that reads it from the environment
 * ({@link HarnessPlugin#usesMcpTokenEnv}) gets a session started with it in its
 * {@value HarnessPlugin#MCP_TOKEN_ENV} environment variable — no other session carries
 * it — and a harness that reads its MCP config from a file gets an owner-only
 * {@code <mcpConfigDir>/<session>.json} (rewritten on resume; {@link #discardMcpConfig}
 * deletes it once the session is gone). A jmux daemon that predates per-session
 * environments starts the session without the variable; such a session is launched
 * without MCP (logged), since its harness could not authenticate.
 */
public final class SessionLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(SessionLauncher.class);

    /** Label keys every launched jmux session carries. */
    public static final String LABEL_HARNESS = "harness";
    public static final String LABEL_PROJECT = "project";
    public static final String LABEL_DISPLAY = "display";

    private final SessionRegistry registry;
    private final RoutingSessionHost router;
    private final Function<String, McpLink> mcpLinkFor;
    private final Path mcpConfigDir;
    private final SessionLabeller labeller;
    private final JmuxSessionStarter jmuxStarter;

    /** How many times {@link #reserveName} re-derives before giving up. */
    static final int MAX_NAME_ATTEMPTS = 32;

    /** Names derived but not yet visible to the host or registry; guarded by itself. */
    private final Set<String> reserved = new HashSet<>();

    /**
     * @param mcpLinkFor   the MCP URL and token for a session name, or null while the
     *                     MCP service is not running (then nothing is injected)
     * @param mcpConfigDir where per-session MCP config files go (created owner-only)
     * @param labeller     sets jmux labels; {@link SessionLabeller#NONE} for none
     * @param jmuxStarter  starts a local jmux session with the MCP token in its
     *                     environment (used only while the MCP service runs, and only
     *                     for a harness that {@linkplain HarnessPlugin#usesMcpTokenEnv
     *                     reads the token from there})
     */
    public SessionLauncher(SessionRegistry registry, RoutingSessionHost router,
                           Function<String, McpLink> mcpLinkFor, Path mcpConfigDir,
                           SessionLabeller labeller, JmuxSessionStarter jmuxStarter) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.router = Objects.requireNonNull(router, "router");
        this.mcpLinkFor = Objects.requireNonNull(mcpLinkFor, "mcpLinkFor");
        this.mcpConfigDir = Objects.requireNonNull(mcpConfigDir, "mcpConfigDir");
        this.labeller = Objects.requireNonNull(labeller, "labeller");
        this.jmuxStarter = Objects.requireNonNull(jmuxStarter, "jmuxStarter");
    }

    /** A launcher that never injects MCP (so never writes a config file or passes an
     * environment): plain host sessions plus {@code labeller}'s jmux labels. */
    public static SessionLauncher withoutMcp(SessionRegistry registry, RoutingSessionHost router,
                                             SessionLabeller labeller) {
        return new SessionLauncher(registry, router, session -> null,
                Path.of(System.getProperty("java.io.tmpdir"), "harness-manager-unused-mcp"), labeller,
                (session, workdir, columns, rows, env) -> {
                    throw new IllegalStateException("no MCP, no environment to pass");
                });
    }

    /**
     * Creates and registers a new session; see {@link #create(Path, HarnessType,
     * Optional, String, String, Optional, Map, Runnable)}. A resume id the harness has no
     * syntax for silently falls back to a fresh launch (logged).
     */
    public String create(Path projectPath, HarnessType harness, Optional<String> harnessSessionId,
                         String machineId, String hostId, Optional<String> displayName,
                         Map<String, String> labels) {
        return create(projectPath, harness, harnessSessionId, machineId, hostId, displayName, labels,
                () -> LOG.warn("'{}' has no known resume syntax — starting a fresh session instead",
                        harness.id()));
    }

    /**
     * Creates and registers a new session running {@code harness} in {@code projectPath}
     * and returns its host session name. A null {@code machineId} means local, on the
     * local host {@code hostId} names; otherwise the session goes on that registered
     * machine's tmux and {@code hostId} is ignored. The caller validates the directory
     * and the machine beforehand. {@code labels} are extra jmux labels (ignored on other
     * hosts); {@code displayName}, when present and non-blank, becomes the row's
     * display-name override.
     *
     * @param onNoResumeSyntax runs (on this thread) when a resume id was given but the
     *                         harness has no resume syntax, just before the fresh launch
     * @throws RuntimeException whatever the host threw; a half-created session may be
     *                          left behind, exactly as the window's create always did
     */
    public String create(Path projectPath, HarnessType harness, Optional<String> harnessSessionId,
                         String machineId, String hostId, Optional<String> displayName,
                         Map<String, String> labels, Runnable onNoResumeSyntax) {
        String effectiveHostId = machineId != null ? null : hostId; // a remote row never names a host
        String name = reserveName(projectPath, machineId);
        try {
            router.registerPending(name, machineId, effectiveHostId);
            boolean jmux = isLocalJmux(machineId, effectiveHostId);
            HarnessPlugin plugin = HarnessPlugins.of(harness);
            McpLink mcp = newSession(name, projectPath, plugin, jmux ? mcpLinkFor.apply(name) : null);
            Optional<String> display = displayName.filter(d -> !d.isBlank());
            if (jmux) {
                Map<String, String> all = new LinkedHashMap<>(labels);
                all.put(LABEL_HARNESS, harness.id());
                all.put(LABEL_PROJECT, projectPath.toString());
                display.ifPresent(d -> all.put(LABEL_DISPLAY, d));
                label(name, all);
            }
            Optional<String> id = harnessSessionId;
            String command;
            String freshId = UUID.randomUUID().toString();
            Optional<String> withId = id.isEmpty() && machineId == null
                    ? plugin.launchCommandWithId(freshId) : Optional.empty();
            if (withId.isPresent()) {
                // a local fresh launch names its own conversation, so the row knows the id
                // (transcript, resume) from the start; remote rows keep learning it at exit
                command = withId.get();
                id = Optional.of(freshId);
            } else {
                command = launchCommand(plugin, id, onNoResumeSyntax);
            }
            sendLaunch(name, plugin, command, jmux, mcp);
            SessionEntry entry = SessionEntry.newlyCreated(name, projectPath.toString(), harness,
                    id.orElse(null), discoverPaneTarget(name), machineId, effectiveHostId);
            registry.put(display.map(entry::withDisplayName).orElse(entry));
            return name;
        } finally {
            router.clearPending(name);
            synchronized (reserved) {
                reserved.remove(name);
            }
        }
    }

    /**
     * Starts a fresh host session under a dead registered row's name and relaunches its
     * harness — resuming its conversation when the row has an id — then records the new
     * pane target. No pending route is needed: the row already names the host.
     *
     * @param onNoResumeSyntax as for {@link #create}
     */
    public void resume(SessionEntry entry, Runnable onNoResumeSyntax) {
        String name = entry.tmuxSession();
        boolean jmux = isLocalJmux(entry.machineId(), entry.hostId());
        HarnessPlugin plugin = HarnessPlugins.of(entry.harnessType());
        McpLink mcp = newSession(name, Path.of(entry.projectPath()), plugin, jmux ? mcpLinkFor.apply(name) : null);
        if (jmux) {
            Map<String, String> all = new LinkedHashMap<>();
            all.put(LABEL_HARNESS, entry.harnessType().id());
            all.put(LABEL_PROJECT, entry.projectPath());
            if (entry.displayName() != null && !entry.displayName().isBlank()) {
                all.put(LABEL_DISPLAY, entry.displayName());
            }
            label(name, all);
        }
        Optional<String> id = entry.harnessSessionIdOpt();
        Optional<String> fresh = id.isPresent() && entry.machineId() == null
                && !plugin.canResume(id.get(), Path.of(entry.projectPath()))
                ? Optional.of(plugin.launchCommandWithId(id.get()).orElse(plugin.launchCommand()))
                : Optional.empty();
        // an id given at launch whose conversation never got a message has nothing to
        // resume: start it fresh, under the same id when the harness allows
        sendLaunch(name, plugin, fresh.orElseGet(() -> launchCommand(plugin, id, onNoResumeSyntax)), jmux, mcp);
        // the recreated session's pane target replaces the dead lifecycle's;
        // everything else (the host included) rides along untouched
        registry.put(entry.withTargetPane(discoverPaneTarget(name)));
    }

    /**
     * The command a launch types: the resume command when an id is given and the
     * harness has resume syntax, otherwise the fresh launch command
     * ({@code onNoResumeSyntax} runs first when an id had to be dropped).
     */
    public static String launchCommand(HarnessPlugin plugin, Optional<String> harnessSessionId,
                                       Runnable onNoResumeSyntax) {
        if (harnessSessionId.isEmpty()) {
            return plugin.launchCommand();
        }
        Optional<String> resumeCommand = plugin.resumeCommand(harnessSessionId.get());
        if (resumeCommand.isPresent()) {
            return resumeCommand.get();
        }
        onNoResumeSyntax.run();
        return plugin.launchCommand();
    }

    /**
     * Starts the host session at the standard size and returns the MCP link the launch
     * may inject ({@code mcp}, or null for none). Through the router — except while the
     * MCP service runs for a local jmux session whose harness reads the token from the
     * environment: then straight on jmux with the token in its environment. When the
     * daemon predates per-session environments and starts the session without it, the
     * harness could not authenticate, so the session is launched without MCP (null).
     */
    private McpLink newSession(String name, Path workdir, HarnessPlugin plugin, McpLink mcp) {
        if (mcp == null || !plugin.usesMcpTokenEnv()) {
            router.newSession(name, workdir, SessionTerminal.COLUMNS, SessionTerminal.ROWS);
            return mcp;
        }
        boolean envApplied = jmuxStarter.newSession(name, workdir, SessionTerminal.COLUMNS, SessionTerminal.ROWS,
                Map.of(HarnessPlugin.MCP_TOKEN_ENV, mcp.token()));
        if (envApplied) {
            return mcp;
        }
        LOG.warn("the running jmux daemon predates per-session environment variables, so {} could not be given "
                + "its MCP token: launching its {} without the harness-manager MCP server. MCP for such sessions "
                + "needs the jmux daemon to be restarted (it is replaced once every jmux session has ended)",
                name, plugin.type().id());
        return null;
    }

    /** Types {@code command} unless blank (the pane's shell is the harness), with the MCP
     * server injected for a local jmux session while the service runs. */
    private void sendLaunch(String name, HarnessPlugin plugin, String command, boolean jmux, McpLink mcp) {
        if (jmux && mcp == null) {
            discardMcpConfig(name); // a stale token's file from an earlier lifecycle
        }
        if (command.isBlank()) {
            return;
        }
        router.sendKeys(name, mcp != null ? withMcpServer(name, plugin, command, mcp) : command);
    }

    /** {@code command} with the MCP server injected, after writing the harness's config
     * file if it reads one; a file that cannot be written means launching without MCP. */
    private String withMcpServer(String name, HarnessPlugin plugin, String command, McpLink mcp) {
        McpEndpoint endpoint = new McpEndpoint(mcp.url(), mcp.token(), mcpConfigFile(name));
        Optional<String> config = plugin.mcpConfigFile(endpoint);
        if (config.isPresent()) {
            try {
                writeOwnerOnly(endpoint.configFile(), config.get());
            } catch (IOException | UncheckedIOException e) {
                LOG.warn("could not write the MCP config for {} — launching it without MCP: {}", name, e.toString());
                return command;
            }
        }
        return plugin.withMcpServer(command, endpoint);
    }

    /** The per-session MCP config file; the name is reduced to file-safe characters. */
    public Path mcpConfigFile(String session) {
        return mcpConfigDir.resolve(session.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    /** Deletes {@code session}'s MCP config file (and the token in it), best effort —
     * for when the session is killed or forgotten. Safe from any thread. */
    public void discardMcpConfig(String session) {
        try {
            Files.deleteIfExists(mcpConfigFile(session));
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not delete the MCP config for {}: {}", session, e.toString());
        }
    }

    /** Writes {@code content} to {@code file} so it is never readable by anyone but the
     * owner, not even mid-write: a 0600 temp file in a 0700 directory, then an atomic
     * replace. Where POSIX permissions don't exist (Windows) the defaults apply. */
    private static void writeOwnerOnly(Path file, String content) throws IOException {
        Path dir = file.getParent();
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } else {
            Files.createDirectories(dir);
        }
        FileAttribute<?>[] attrs = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        Path temp = Files.createTempFile(dir, ".mcp-", ".tmp", attrs);
        try {
            Files.writeString(temp, content);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean isLocalJmux(String machineId, String hostId) {
        return (machineId == null || machineId.isBlank()) && SessionServerHost.ID.equals(hostId);
    }

    /** A name free in the registry, on the target host(s), and among launches still in
     * flight here — two concurrent launches in the same folder (the window and an agent)
     * must not both derive {@code hm-x-2}. The host probes (an SSH round trip each for a
     * remote machine) run outside the lock, so one slow host never stalls another
     * launch; the lock only covers re-checking the one chosen candidate — still not
     * reserved and still not in the registry (a concurrent launch may have registered
     * and released it meanwhile; a launch registers before it releases) — and adding it.
     * A candidate that fails the re-check is re-derived, at most
     * {@value #MAX_NAME_ATTEMPTS} times. */
    private String reserveName(Path projectPath, String machineId) {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            Set<String> inFlight;
            synchronized (reserved) {
                inFlight = Set.copyOf(reserved);
            }
            String name = SessionNaming.deriveUnique(projectPath,
                    candidate -> inFlight.contains(candidate)
                            || registry.find(candidate).isPresent()
                            || router.hasSessionOn(machineId, candidate));
            synchronized (reserved) {
                if (!reserved.contains(name) && registry.find(name).isEmpty()) {
                    reserved.add(name);
                    return name;
                }
            }
        }
        throw new IllegalStateException("could not reserve a session name for " + projectPath + " after "
                + MAX_NAME_ATTEMPTS + " attempts");
    }

    private void label(String name, Map<String, String> labels) {
        try {
            labeller.label(name, labels);
        } catch (RuntimeException e) {
            if (predatesLabels(e)) {
                // a jmux daemon older than labels: the registry entry still names the session
                LOG.debug("not labelling session {}: {}", name, e.getMessage());
            } else {
                LOG.warn("could not label session {}: {}", name, e.toString());
            }
        }
    }

    /** Whether {@code e} (or what it wraps) is a jmux daemon refusing an op it predates. */
    private static boolean predatesLabels(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SessionServerException s && s.isUnsupported()) {
                return true;
            }
        }
        return false;
    }

    /** The just-created session's actual {@code window.pane} — "0.0" is only the
     * fallback, since a non-zero {@code base-index} makes it never resolve. */
    private String discoverPaneTarget(String name) {
        try {
            return router.currentPaneTarget(name);
        } catch (SessionHostException e) {
            return "0.0";
        }
    }
}
