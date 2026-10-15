package io.github.ghosthack.harnessmanager;

import io.github.ghosthack.harnessmanager.control.ClientJmuxControl;
import io.github.ghosthack.harnessmanager.control.LauncherSpawner;
import io.github.ghosthack.harnessmanager.control.SessionController;
import io.github.ghosthack.harnessmanager.host.RoutingSessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.machine.MachineRegistry;
import io.github.ghosthack.harnessmanager.mcp.McpService;
import io.github.ghosthack.harnessmanager.session.SessionEvents;
import io.github.ghosthack.harnessmanager.session.SessionLauncher;
import io.github.ghosthack.harnessmanager.registry.RegistryPaths;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import io.github.ghosthack.harnessmanager.jmux.SessionServerHost;
import io.github.ghosthack.harnessmanager.tmux.RealTmux;
import io.github.ghosthack.harnessmanager.tmux.SshExec;
import io.github.ghosthack.harnessmanager.ui.MacWindowAppearance;
import io.github.ghosthack.harnessmanager.ui.MainWindow;
import io.github.ghosthack.harnessmanager.settings.AppSettings;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point: multi-session Swing app over session-hosted harnesses. Loads the session
 * registry, reconciles it against the live hosts, and shows the main window. The only
 * place concrete host classes are wired together.
 *
 * <p>Registry path defaults to {@code ~/.harness-manager/sessions.json}; override with
 * {@code -Dharnessmanager.registry=/path/to/sessions.json} (used by smoke tests).
 */
public final class HarnessManagerApp {

    /**
     * Exclusive lock held for the process lifetime — saves are full-snapshot rewrites,
     * so a second instance on the same registry would silently clobber this one's
     * entries. Never closed deliberately; the OS releases it on exit.
     */
    @SuppressWarnings("unused")
    private static FileChannel instanceLock;

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--package-smoke")) {
            try {
                io.github.ghosthack.harnessmanager.packaging.PackageSmoke.run();
                System.exit(0);
            } catch (Exception failure) {
                failure.printStackTrace();
                System.exit(1);
            }
            return;
        }
        // Same pair the MainWindow toolbar toggle switches between at runtime.
        AppSettings.UiTheme theme = AppSettings.uiTheme();
        // Before any AWT/Swing call: the JDK reads the appearance property once, while
        // starting AppKit. Setting it later is silently ignored (the toolbar toggle uses
        // MacWindowAppearance.apply instead). Same timing constraint for the menu-bar/
        // Dock name, which otherwise shows the main class name.
        System.setProperty("apple.awt.application.name", MainWindow.APP_NAME);
        MacWindowAppearance.applyBeforeAwt(theme);
        if (theme == AppSettings.UiTheme.LIGHT) {
            FlatMacLightLaf.setup();
        } else {
            FlatMacDarkLaf.setup();
        }
        Path registryFile = RegistryPaths.resolve();
        if (!lockInstance(registryFile)) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null,
                        "harness-manager is already running for this registry ("
                                + registryFile + ").",
                        "Already running", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            });
            return;
        }
        SessionRegistry registry = SessionRegistry.load(registryFile);
        MachineRegistry machineRegistry = MachineRegistry.load(RegistryPaths.resolveMachines());
        // known_hosts and the ssh control sockets live beside the registry file, so a
        // scratch registry gets isolated SSH state too
        SshExec ssh = new SshExec(registryFile.toAbsolutePath().getParent());
        // Both local substrates, keyed by host id. Insertion order matters: when two local
        // hosts list the same session name the router keeps the first (map order is the
        // unmanaged-table tie-break), so tmux stays ahead of the daemon. Registering the
        // daemon costs nothing where it is unused — only newSession ever spawns it, and
        // every other call on a dead daemon home answers "no server" the tmux way. tmux
        // itself is skipped on Windows, where the binary never exists: registering it
        // there would just fail every call instead of costing nothing.
        Map<String, SessionHost> localHosts = new LinkedHashMap<>();
        if (!isWindows()) {
            localHosts.put(RealTmux.ID, new RealTmux());
        }
        SessionServerHost jmuxHost = SessionServerHost.forDefaultHome();
        localHosts.put(SessionServerHost.ID, jmuxHost);
        RoutingSessionHost host = new RoutingSessionHost(localHosts, machineRegistry, registry,
                machine -> RealTmux.remote(ssh, machine.destination(), ssh.resolveTmuxPath(machine.destination())));
        // The MCP server: off unless mcp.server=on. Sessions launched on jmux while it
        // runs get its URL in their launch command (the token rides in their environment
        // or an owner-only config file, never on the command line); its tools are the
        // session controller over the jmux daemon, which spawns through the window's own
        // launcher, and SessionEvents carries what agents create or kill into the
        // window's sidebar. The launcher asks the service for URLs and tokens and the
        // service hosts the controller
        // that uses the launcher, hence the late-bound reference.
        AtomicReference<McpService> mcpRef = new AtomicReference<>();
        SessionEvents sessionEvents = new SessionEvents();
        SessionLauncher launcher = new SessionLauncher(registry, host, session -> {
            McpService service = mcpRef.get();
            return service != null ? service.linkFor(session) : null;
        }, RegistryPaths.resolveMcpConfigDir(), jmuxHost::label, jmuxHost::newSession);
        SessionController controller = new SessionController(new ClientJmuxControl(jmuxHost.client()),
                registry, new LauncherSpawner(launcher, jmuxHost, sessionEvents), Clock.systemUTC());
        McpService mcpService = new McpService(controller);
        mcpRef.set(mcpService);
        startMcpServer(mcpService);
        SwingUtilities.invokeLater(() -> new MainWindow(registry, host, machineRegistry, ssh,
                launcher, mcpService, sessionEvents).setVisible(true));
    }

    /** Starts the MCP server when the setting is on. A taken port is logged by the
     * service and leaves the app running without it — Settings can pick another. */
    private static void startMcpServer(McpService mcpService) {
        if (!AppSettings.mcpServerEnabled()) {
            return;
        }
        try {
            mcpService.start(AppSettings.mcpPort(), AppSettings.mcpToken());
        } catch (IOException | RuntimeException e) {
            // logged by the service; tell the user too, once the window is up
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "The MCP server could not start on port " + AppSettings.mcpPort() + ": " + e.getMessage()
                            + "\nharness-manager runs without it; pick another port in Settings.",
                    "MCP server", JOptionPane.WARNING_MESSAGE));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Takes the single-instance lock: an {@code instance.lock} file beside the resolved
     * registry file, so scratch registries don't contend with the real one. Failure to
     * even attempt the lock (unwritable directory, exotic filesystem) runs unguarded —
     * a working session manager beats refusing to start.
     */
    private static boolean lockInstance(Path registryFile) {
        Path lockFile = registryFile.toAbsolutePath().resolveSibling("instance.lock");
        try {
            Path parent = lockFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                if (channel.tryLock() == null) {
                    channel.close();
                    return false;
                }
            } catch (OverlappingFileLockException e) {
                channel.close();
                return false;
            }
            instanceLock = channel;
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    private HarnessManagerApp() {
    }
}
