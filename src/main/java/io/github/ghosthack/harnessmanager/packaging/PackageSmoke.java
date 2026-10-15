package io.github.ghosthack.harnessmanager.packaging;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.sun.net.httpserver.HttpServer;
import io.github.ghosthack.jmux.client.SessionServerClient;
import io.github.ghosthack.jmux.session.CaptureFormat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exercises the packaged runtime before any UI, settings or registry is initialized. */
public final class PackageSmoke {
    private static final String MARKER = "HARNESS_MANAGER_PACKAGE_OK";

    private PackageSmoke() { }

    public static void run() throws Exception {
        System.setProperty("java.awt.headless", "true");
        for (String module : List.of("java.desktop", "java.net.http", "java.logging",
                "java.management", "java.sql", "jdk.httpserver", "jdk.unsupported", "jdk.charsets")) {
            if (ModuleLayer.boot().findModule(module).isEmpty()) {
                throw new IllegalStateException("Missing packaged runtime module: " + module);
            }
        }
        Class.forName("com.formdev.flatlaf.FlatLaf");
        Class.forName("com.jediterm.terminal.ui.JediTermWidget");
        Class.forName("io.github.ghosthack.harnessstimulator.Main");
        TerminalTextBuffer screen = new TerminalTextBuffer(80, 24, new StyleState());
        if (screen.getWidth() != 80 || screen.getHeight() != 24) {
            throw new IllegalStateException("JediTerm model did not initialize");
        }
        try (HttpClient ignored = HttpClient.newHttpClient()) {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.stop(0);
        }

        Path root = Files.createTempDirectory("hm-");
        String originalHome = System.getProperty("user.home");
        SessionServerClient client = new SessionServerClient(root.resolve("jmux"), true);
        ProcessHandle daemon = null;
        try {
            // A scratch home and explicit non-login shell prevent user startup files,
            // existing sessions and real coding agents from participating in this check.
            System.setProperty("user.home", root.toString());
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            List<String> shell = windows
                    ? List.of(System.getenv().getOrDefault("ComSpec", "C:\\Windows\\System32\\cmd.exe"), "/d", "/q")
                    : List.of("/bin/sh");
            var session = client.newSession("package-smoke", root, 80, 24, shell,
                    null, Map.of("HOME", root.toString()));
            daemon = ProcessHandle.of(client.ping().orElseThrow().pid()).orElseThrow();
            String expectedBackend = windows ? "conpty" : "unix";
            if (!session.backend().equals(expectedBackend)) {
                throw new IllegalStateException("Expected " + expectedBackend + ", got " + session.backend());
            }
            client.sendKeys("package-smoke", "echo " + MARKER, true);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            boolean captured = false;
            while (System.nanoTime() < deadline) {
                String text = client.capture("package-smoke", CaptureFormat.TEXT).orElseThrow(() -> new IllegalStateException("Smoke session vanished")).text();
                if (text.lines().anyMatch(line -> line.strip().equals(MARKER))) {
                    captured = true;
                    break;
                }
                Thread.sleep(100);
            }
            if (!captured) {
                throw new IllegalStateException("Packaged PTY input/output capture failed");
            }
            System.out.println("Harness Manager package smoke: runtime, UI classes, HTTP and "
                    + expectedBackend + " session input/capture verified");
        } finally {
            try {
                if (daemon == null) {
                    daemon = client.ping().flatMap(info -> ProcessHandle.of(info.pid())).orElse(null);
                }
                client.shutdown();
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (client.isServerRunning() && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                if (client.isServerRunning()) {
                    throw new IllegalStateException("Package smoke daemon did not shut down");
                }
                if (daemon != null && daemon.isAlive()) {
                    daemon.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            } finally {
                System.setProperty("user.home", originalHome);
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }
}
