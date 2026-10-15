package io.github.ghosthack.harnessmanager.stimulator;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.host.PtySessionConnection;
import io.github.ghosthack.harnessmanager.host.SessionConnection;
import io.github.ghosthack.jmux.client.ServerLauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Process boundary between the Swing manager and the separately launched harness-stimulator.
 * The worker owns stimulation and deliberately outlives this app; the manager only
 * starts it, peeks through its read-only command, and requests an acknowledged detach
 * before taking tmux back.
 */
public final class StimulatorClient {

    public static final String LAUNCHER_PROPERTY = "harnessmanager.stimulatorLauncher";
    public static final String LAUNCHER_ENV = "HARNESS_STIMULATOR_LAUNCHER";
    public static final String JAR_PROPERTY = "harnessmanager.stimulatorJar";
    public static final String JAR_ENV = "HARNESS_STIMULATOR_JAR";
    private static final String MAIN_CLASS = "io.github.ghosthack.harnessstimulator.Main";
    private static final String NATIVE_LAUNCHER = "harness-stimulator";
    private static final String JAR_NAME = "harness-stimulator-0.1.0-SNAPSHOT.jar";

    public record StartRequest(String session, Path workdir, HarnessType harness,
                               Optional<String> model, String prompt, int maxStimuli) {
    }

    private final List<String> commandPrefix;
    private final Optional<Path> standaloneJar;
    private final Path stateDir;
    private final Path logDir;

    public StimulatorClient(Path jar) {
        this(jar,
                Path.of(System.getProperty("user.home"), ".harness-stimulator"),
                Path.of(System.getProperty("user.home"), ".harness-manager", "stimulator"));
    }

    StimulatorClient(Path jar, Path stateDir, Path logDir) {
        Path normalized = jar.toAbsolutePath().normalize();
        this.commandPrefix = List.of(javaBinary().toString(), "-jar", normalized.toString());
        this.standaloneJar = Optional.of(normalized);
        this.stateDir = stateDir;
        this.logDir = logDir;
    }

    StimulatorClient(List<String> commandPrefix, Path stateDir, Path logDir) {
        if (commandPrefix.isEmpty()) {
            throw new IllegalArgumentException("stimulator command prefix is required");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
        this.standaloneJar = Optional.empty();
        this.stateDir = stateDir;
        this.logDir = logDir;
    }

    /**
     * Explicit overrides first, then a sibling jpackage launcher, then the
     * stimulator main class carried in this app's runtime classpath.
     */
    public static Optional<StimulatorClient> discover() {
        Optional<Path> launcher = configuredPath(LAUNCHER_PROPERTY, LAUNCHER_ENV);
        if (launcher.isPresent()) {
            return launcher.filter(Files::isRegularFile)
                    .map(path -> fromCommand(List.of(path.toString())));
        }

        Optional<Path> jar = configuredPath(JAR_PROPERTY, JAR_ENV);
        if (jar.isPresent()) {
            return jar.filter(Files::isRegularFile).map(StimulatorClient::new);
        }

        Optional<Path> packagedLauncher = packagedLauncher();
        if (packagedLauncher.isPresent()) {
            return packagedLauncher.map(path -> fromCommand(List.of(path.toString())));
        }

        String classpath = System.getProperty("java.class.path", "");
        Path java = javaBinary();
        if (!classpath.isBlank() && Files.isRegularFile(java) && stimulatorMainIsPresent()) {
            // Peek/run/detach subprocesses are deliberately spawned from a fixed
            // directory (not this JVM's cwd), so a relative classpath entry — as
            // seen when this app itself was launched with a relative -jar path —
            // would resolve against the wrong directory and fail to find the class.
            return Optional.of(fromCommand(List.of(java.toString(), "-cp", absolutize(classpath), MAIN_CLASS)));
        }

        // Compatibility for an older development setup without the Maven dependency.
        List<Path> candidates = new ArrayList<>();
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        candidates.add(cwd.resolveSibling("harness-stimulator").resolve("target").resolve(JAR_NAME));
        try {
            Path code = Path.of(StimulatorClient.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path project = code.getParent() != null ? code.getParent().getParent() : null;
            if (project != null) {
                candidates.add(project.resolveSibling("harness-stimulator").resolve("target").resolve(JAR_NAME));
            }
        } catch (Exception ignored) {
            // Explicit overrides remain available when the code source is opaque.
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().map(StimulatorClient::new);
    }

    /** Retained for callers using the legacy explicit standalone-jar route. */
    public Path jar() {
        return standaloneJar.orElseThrow(() ->
                new IllegalStateException("stimulator is launched from the application classpath"));
    }

    List<String> commandPrefix() {
        return commandPrefix;
    }

    public Process start(StartRequest request) throws IOException {
        Files.createDirectories(logDir);
        return new ProcessBuilder(runCommand(request))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath(request.session()).toFile()))
                .start();
    }

    public List<String> runCommand(StartRequest request) {
        if (request.harness() == HarnessType.SHELL) {
            throw new IllegalArgumentException("a shell session has no harness to stimulate");
        }
        if (request.prompt().isBlank()) {
            throw new IllegalArgumentException("stimulation prompt is required");
        }
        if (request.maxStimuli() < 0) {
            throw new IllegalArgumentException("max stimuli must be zero or greater");
        }
        List<String> command = baseCommand();
        command.addAll(List.of("run", request.session(), "--dir", request.workdir().toString(),
                "--harness", request.harness().id(),
                "--prompt", request.prompt(),
                // This dialog's one instruction is intentionally the repeated stimulus.
                "--idle-message", request.prompt(),
                "--max-stimuli", Integer.toString(request.maxStimuli())));
        request.model().filter(s -> !s.isBlank()).ifPresent(model -> {
            command.add("--model");
            command.add(model);
        });
        return List.copyOf(command);
    }

    public List<String> peekCommand(String session) {
        List<String> command = baseCommand();
        command.addAll(List.of("peek", session, "--embedded"));
        return List.copyOf(command);
    }

    /**
     * Opens harness-stimulator's capture-pane echo as a read-only connection, so the
     * manager can show a stimulated session in the same terminal widget without
     * creating any tmux client of its own. Input is dropped at the connection, making
     * the card genuinely read-only even when it has keyboard focus. Spawns a process —
     * call off the EDT.
     */
    public SessionConnection peekConnection(String session, int columns, int rows) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.putIfAbsent("LANG", "en_US.UTF-8");
        return PtySessionConnection.spawn("peek", peekCommand(session), env, columns, rows, false);
    }

    /** True only for a lock owned by a process that is still alive. */
    public boolean isAttached(String session) {
        return liveOwner(lockPath(session)).isPresent();
    }

    /** Stimuli used against the run's budget, as of the attached stimulator's last send.
     * {@code maxStimuli} 0 means unlimited, mirroring {@code --max-stimuli 0}. */
    public record StimuliStatus(int stimuliSent, int maxStimuli) {
        public boolean unlimited() {
            return maxStimuli == 0;
        }

        public int remaining() {
            return Math.max(0, maxStimuli - stimuliSent);
        }

        /** What a counter badge should show: stimuli left to spend, or — when the
         * budget is unlimited and there is nothing to count down from — stimuli spent
         * so far. */
        public int displayCount() {
            return unlimited() ? stimuliSent : remaining();
        }
    }

    /**
     * Reads the attached stimulator's live status file directly — plain file I/O, no
     * subprocess — the same way {@link #isAttached} reads the lock file, so a poll
     * across every stimulated sidebar row stays cheap. Empty when nothing has attached
     * yet, or the file is mid-write or otherwise unparseable.
     */
    public Optional<StimuliStatus> stimuliStatus(String session) {
        Path path = statusPath(session);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (BufferedReader r = Files.newBufferedReader(path)) {
            p.load(r);
            return Optional.of(new StimuliStatus(
                    Integer.parseInt(p.getProperty("stimuliSent")),
                    Integer.parseInt(p.getProperty("maxStimuli"))));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    public String detachAndWait(String session, Duration timeout) throws IOException, InterruptedException {
        List<String> command = baseCommand();
        command.addAll(List.of("detach", session, "--wait"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out waiting for harness-stimulator to release " + session);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            throw new IOException(output.isEmpty() ? "harness-stimulator detach failed" : output);
        }
        return output;
    }

    public Path logPath(String session) {
        String safe = session.replaceAll("[^A-Za-z0-9._-]", "_");
        return logDir.resolve(safe + ".log");
    }

    public String logTail(String session, int maxLines) {
        Path log = logPath(session);
        if (!Files.exists(log)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - maxLines), lines.size()));
        } catch (IOException e) {
            return "";
        }
    }

    private List<String> baseCommand() {
        return new ArrayList<>(commandPrefix);
    }

    private Path lockPath(String session) {
        return stateDir.resolve(session + ".lock");
    }

    private Path statusPath(String session) {
        return stateDir.resolve(session + ".status");
    }

    private static String absolutize(String classpath) {
        return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static Path javaBinary() {
        return ServerLauncher.javaBinary();
    }

    /** The current JVM's launcher — {@code java.exe} on Windows, where a bare
     * {@code java} would never resolve and the classpath route above could never be
     * taken. One lookup shared with the jmux daemon's launcher
     * ({@link ServerLauncher#javaBinary(String, String)}), so both process boundaries
     * find the JVM the same way; this package-private delegate stays so the unit test
     * pins the behaviour this class relies on. */
    static Path javaBinary(String javaHome, String osName) {
        return ServerLauncher.javaBinary(javaHome, osName);
    }

    private static StimulatorClient fromCommand(List<String> commandPrefix) {
        Path home = Path.of(System.getProperty("user.home"));
        return new StimulatorClient(commandPrefix,
                home.resolve(".harness-stimulator"),
                home.resolve(".harness-manager").resolve("stimulator"));
    }

    private static Optional<Path> configuredPath(String property, String environment) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environment);
        }
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(configured).toAbsolutePath().normalize());
    }

    private static Optional<Path> packagedLauncher() {
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .map(Path::getParent)
                .flatMap(directory -> {
                    Path launcher = directory.resolve(NATIVE_LAUNCHER);
                    if (Files.isRegularFile(launcher)) {
                        return Optional.of(launcher);
                    }
                    Path windowsLauncher = directory.resolve(NATIVE_LAUNCHER + ".exe");
                    return Files.isRegularFile(windowsLauncher)
                            ? Optional.of(windowsLauncher) : Optional.empty();
                });
    }

    private static boolean stimulatorMainIsPresent() {
        try {
            Class.forName(MAIN_CLASS, false, StimulatorClient.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static OptionalLong liveOwner(Path lock) {
        try {
            long pid = Long.parseLong(Files.readString(lock).strip());
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                    ? OptionalLong.of(pid) : OptionalLong.empty();
        } catch (IOException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
