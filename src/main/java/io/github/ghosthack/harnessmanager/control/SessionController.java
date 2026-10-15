package io.github.ghosthack.harnessmanager.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.jmux.SessionServerHost;
import io.github.ghosthack.harnessmanager.mcp.McpToolset;
import io.github.ghosthack.harnessmanager.mcp.ToolDef;
import io.github.ghosthack.harnessmanager.mcp.ToolResult;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import io.github.ghosthack.harnessmanager.session.SessionLauncher;
import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.SessionState;
import io.github.ghosthack.jmux.client.SessionServerException;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The MCP session controller: the tools agents running in harness-manager's jmux sessions
 * use to list, read, drive, watch, start and end each other. Only jmux-hosted sessions
 * are controllable; everything harness-specific (state detection, chrome masking,
 * transcripts) lives here, on top of jmux's generic input/capture/wait/watch.
 *
 * <p>Per session the controller keeps its own {@link SessionTracker} (state detection fed
 * by every capture it takes); per (caller, session) what that caller last read, so reads
 * are incremental; and the pending one-shot notifications and spawn prompts, which a
 * background monitor re-evaluates — once a second while anything is pending, and at once
 * when jmux's event stream reports activity in a session involved. The monitor runs while
 * the MCP server does ({@link #serverStarted}/{@link #serverStopped}); it reconnects the
 * event stream with backoff when the daemon restarts or is not running yet.
 *
 * <p>Thread-safe: tool calls arrive on concurrent request threads.
 */
public final class SessionController implements McpToolset {

    private static final Logger LOG = LoggerFactory.getLogger(SessionController.class);

    static final String TOOL_LIST = "list";
    static final String TOOL_READ = "read";
    static final String TOOL_SEND = "send";
    static final String TOOL_KEYS = "keys";
    static final String TOOL_NOTIFY = "notify";
    static final String TOOL_SPAWN = "spawn";
    static final String TOOL_KILL = "kill";

    /** Label a spawned session carries naming the session whose agent spawned it. */
    static final String LABEL_SPAWNED_BY = "spawned-by";

    static final int MAX_BODY_CHARS = 8000;
    /** Cap on the changed lines a read shows when only status lines changed. */
    static final int MAX_STATUS_CHARS = 2000;
    static final String NOTICE_PREFIX = "[harness-manager] ";
    static final String NOT_JMUX = "not a jmux session (only jmux sessions can be controlled)";
    /** Appended to a read's header when the daemon only gave plain text. */
    static final String OLD_DAEMON_NOTE = "(old jmux daemon: no selection/cursor markers)";
    static final String OLD_DAEMON_REPLACED = "It is replaced automatically once every jmux session has ended "
            + "and a new one starts.";
    /** A multi-line paste the session's program would receive as typed lines. */
    static final String MULTI_LINE_REFUSED = "this session doesn't accept multi-line pastes; send one line at a time"
            + " (line breaks and control characters can only be pasted where the program enables bracketed paste)";
    static final String DAEMON_UNREACHABLE = "the jmux daemon is not answering; try again shortly";
    /** Whether {@code ch}, written raw (no bracketed paste), would act as a key rather than
     *  as text: a line break or any other C0 control except tab, DEL, or a C1 control. */
    static boolean actsAsKeyOutsidePaste(int ch) {
        return (ch < 0x20 && ch != '\t') || (ch >= 0x7f && ch <= 0x9f);
    }

    /** How many times an owed Enter (after its text was pasted) is tried before giving up. */
    static final int MAX_ENTER_ATTEMPTS = 5;

    /** Every duration the controller waits on, injectable so tests run instantly. */
    record Timings(Duration tick, Duration enterDelay, Duration shortQuiescence, Duration longQuiescence,
                   Duration notifyTtl, Duration promptTimeout, long keysQuietMs, long keysTimeoutMs,
                   long watchQuietMs, Duration watchBackoffMin, Duration watchBackoffMax) {

        static final Timings DEFAULT = new Timings(Duration.ofSeconds(1), Duration.ofMillis(400),
                Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofMinutes(30), Duration.ofSeconds(120),
                300, 3000, 500, Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /** {@link Thread#sleep}, replaceable in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** What a notify waits for. */
    enum Trigger {
        FINISHED("finished", "finished"),
        NEEDS_INPUT("needs-input", "needs input"),
        EXITED("exited", "exited"),
        ANY_CHANGE("any-change", "changed");

        final String id;
        final String description;

        Trigger(String id, String description) {
            this.id = id;
            this.description = description;
        }

        static Optional<Trigger> fromId(String id) {
            return Arrays.stream(values()).filter(t -> t.id.equals(id)).findFirst();
        }
    }

    private final JmuxControl jmux;
    private final SessionRegistry registry;
    private final SessionSpawner spawner;
    private final Clock clock;
    private final TranscriptLocator transcripts;
    private final Sleeper sleeper;
    private final Executor deliveries;
    private final Timings timings;
    private final List<ToolDef> tools;
    private final DaemonCompat compat;

    private final ConcurrentHashMap<String, SessionTracker> trackers = new ConcurrentHashMap<>();
    /** Keyed by {@link #seenKey}. */
    private final ConcurrentHashMap<String, Seen> seen = new ConcurrentHashMap<>();
    /** Guarded by itself, as is {@link #prompts}. */
    private final List<Notification> notifications = new ArrayList<>();
    private final List<PromptDelivery> prompts = new ArrayList<>();
    /** Display names (lower case) of spawns in flight, between their uniqueness check and
     * their creation returning; guarded by itself. */
    private final Set<String> spawningDisplays = new HashSet<>();

    private final Semaphore wake = new Semaphore(0);
    /** Guarded by {@code this}. */
    private Thread monitor;

    /** The production controller: standard transcript locations, real sleeps, deliveries
     * on their own virtual threads. */
    public SessionController(JmuxControl jmux, SessionRegistry registry, SessionSpawner spawner, Clock clock) {
        this(jmux, registry, spawner, clock, HarnessTranscripts.standard(), Thread::sleep,
                task -> Thread.ofVirtual().name("hm-control-deliver").start(task), Timings.DEFAULT);
    }

    SessionController(JmuxControl jmux, SessionRegistry registry, SessionSpawner spawner, Clock clock,
                      TranscriptLocator transcripts, Sleeper sleeper, Executor deliveries, Timings timings) {
        this.jmux = Objects.requireNonNull(jmux, "jmux");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.timings = Objects.requireNonNull(timings, "timings");
        this.tools = List.copyOf(toolDefs());
        this.compat = new DaemonCompat(jmux::daemonPid);
    }

    // ------------------------------------------------------------------ McpToolset

    @Override
    public List<ToolDef> tools() {
        return tools;
    }

    @Override
    public ToolResult call(String caller, String tool, JsonNode arguments) {
        JsonNode args = arguments == null ? JsonNodeFactory.instance.objectNode() : arguments;
        try {
            compat.revalidate();
            return ToolResult.ok(switch (tool) {
                case TOOL_LIST -> list(caller);
                case TOOL_READ -> read(caller, args);
                case TOOL_SEND -> send(caller, args);
                case TOOL_KEYS -> keys(caller, args);
                case TOOL_NOTIFY -> notify(caller, args);
                case TOOL_SPAWN -> spawn(caller, args);
                case TOOL_KILL -> kill(caller, args);
                default -> throw new ToolError("unknown tool: " + tool);
            });
        } catch (ToolError e) {
            return ToolResult.error(e.getMessage());
        } catch (RuntimeException e) {
            LOG.debug("tool {} failed", tool, e);
            return ToolResult.error(tool + " failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** Starts the background monitor (idempotent). */
    @Override
    public synchronized void serverStarted() {
        if (monitor != null) {
            return;
        }
        monitor = Thread.ofVirtual().name("hm-control-monitor").unstarted(this::monitorLoop);
        monitor.start();
    }

    /** Stops the background monitor, waiting briefly for it to close its event stream.
     * Pending notifications and prompts are kept for the next start. */
    @Override
    public void serverStopped() {
        Thread stopping;
        synchronized (this) {
            stopping = monitor;
            monitor = null;
        }
        if (stopping != null) {
            stopping.interrupt();
            try {
                stopping.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Whether the calling thread is the current monitor — a monitor replaced by a quick
     * stop/start that outlived its join notices here and ends. */
    private synchronized boolean isCurrentMonitor() {
        return monitor == Thread.currentThread();
    }

    // ------------------------------------------------------------------ tools

    private String list(String caller) {
        List<Session.Info> infos = jmux.list();
        if (infos.isEmpty() && !reachable()) {
            return "no jmux sessions are running (" + DAEMON_UNREACHABLE + ")";
        }
        forgetAllBut(infos.stream().map(Session.Info::name).collect(Collectors.toSet()));
        if (infos.isEmpty()) {
            return "no jmux sessions are running (sessions on tmux or other machines cannot be controlled)";
        }
        List<Target> targets = infos.stream().map(this::target).toList();
        Map<String, Long> sameHarnessAndProject = targets.stream()
                .collect(Collectors.groupingBy(t -> t.harness().id() + "\0" + t.project(), Collectors.counting()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < infos.size(); i++) {
            Session.Info info = infos.get(i);
            Target target = targets.get(i);
            Optional<Observation> obs = observe(target);
            String state = obs.map(o -> o.classification().state().name()).orElse("EXITED");
            long pid = obs.map(o -> o.capture().processId()).orElse(info.pid());
            Optional<String> id = target.entry().flatMap(SessionEntry::harnessSessionIdOpt);
            String transcript = transcripts.transcript(target.harness(), Path.of(target.project()), id, pid)
                    .map(found -> id.isEmpty() && target.harness() == HarnessType.CLAUDE_CODE
                            && sameHarnessAndProject.get(target.harness().id() + "\0" + target.project()) > 1
                            // the newest file there may well be a sibling's: point at the folder
                            ? found.getParent() + "/ (id unknown and other claude-code sessions share this"
                                    + " project: one of these files, not necessarily the newest)"
                            : found.toString())
                    .orElse("-");
            sb.append(target.name()).append(" · ").append(target.display() != null ? target.display() : "-")
                    .append(" · ").append(target.harness().id())
                    .append(" · ").append(state)
                    .append(" · ").append(target.project())
                    .append(" · transcript: ").append(transcript);
            if (target.name().equals(caller)) {
                sb.append(" (you)");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String read(String caller, JsonNode args) {
        Target target = resolve(requiredText(args, "session"));
        boolean full = args.path("full").asBoolean(false);
        Optional<Observation> obs = observe(target);
        if (obs.isEmpty()) {
            return target.name() + " · exited";
        }
        return render(caller, target, obs.get(), full);
    }

    private String send(String caller, JsonNode args) {
        Target target = resolve(requiredText(args, "session"));
        JsonNode textNode = args.get("text");
        if (textNode == null || !textNode.isTextual()) {
            throw new ToolError("text is required (a string)");
        }
        boolean enter = args.path("enter").asBoolean(true);
        refuseSelf(caller, target, "send to");
        String text = textNode.asText();
        if (text.isEmpty() && !enter) {
            throw new ToolError("nothing to send: text is empty and enter is false");
        }
        type(target.name(), text, enter, false, true, () -> { });
        return "sent to " + target.name();
    }

    private String keys(String caller, JsonNode args) {
        Target target = resolve(requiredText(args, "session"));
        JsonNode keysNode = args.get("keys");
        if (keysNode == null || !keysNode.isArray() || keysNode.isEmpty()) {
            throw new ToolError("keys is required: a non-empty array of key names, e.g. [\"Down\",\"Enter\"]");
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode k : keysNode) {
            if (!k.isTextual() || k.asText().isEmpty()) {
                throw new ToolError("every key must be a non-empty string, e.g. \"Enter\", \"y\", \"C-c\"");
            }
            keys.add(k.asText());
        }
        if (!compat.has(DaemonCompat.Feature.INPUT)) {
            throw new ToolError(keysNeedNewerDaemon());
        }
        Captured before = capture(target.name())
                .orElseThrow(() -> new ToolError(target.name() + " has exited"));
        try {
            jmux.input(target.name(), null, false, keys);
        } catch (RuntimeException e) {
            if (!DaemonCompat.isUnsupported(e)) {
                throw e;
            }
            compat.markMissing(DaemonCompat.Feature.INPUT);
            throw new ToolError(keysNeedNewerDaemon());
        }
        if (compat.has(DaemonCompat.Feature.WAIT)) {
            try {
                jmux.awaitQuiet(target.name(), before.capture().outputBytes(), timings.keysQuietMs(),
                        timings.keysTimeoutMs());
            } catch (RuntimeException e) {
                if (!DaemonCompat.isUnsupported(e)) {
                    throw e;
                }
                compat.markMissing(DaemonCompat.Feature.WAIT);
            }
        }
        Optional<Observation> obs = observe(target);
        if (obs.isEmpty()) {
            return target.name() + " · exited";
        }
        return render(caller, target, obs.get(), true);
    }

    private String notify(String caller, JsonNode args) {
        if (caller == null || caller.isBlank()) {
            throw new ToolError("notify only works from a session harness-manager launched in jmux: the "
                    + "notification is typed into your session, and this connection does not say which "
                    + "session that is");
        }
        Trigger on = Trigger.fromId(args.path("on").asText(""))
                .orElseThrow(() -> new ToolError("on must be one of: finished, needs-input, exited, any-change"));
        List<String> requested = sessionsArgument(args);
        List<Session.Info> infos = liveSessions();
        Session.Info callerInfo = infos.stream().filter(i -> i.name().equals(caller)).findFirst()
                .orElseThrow(() -> new ToolError("your session " + caller + " is not a running jmux session, so a "
                        + "notification could not be delivered to it"));
        if (target(callerInfo).harness() == HarnessType.SHELL) {
            throw new ToolError("notify cannot deliver to " + caller + ": it is a plain shell session, where the "
                    + "notification line would be typed at the shell prompt and run as a command. Notifications "
                    + "only go to agent sessions (claude-code, codex, …); poll with read instead");
        }
        // resolve and validate every session before registering any, so a failure registers nothing
        Map<String, Target> targets = new LinkedHashMap<>();
        for (String argument : requested) {
            Target target = resolve(argument, infos);
            if (target.name().equals(caller)) {
                throw new ToolError("notify watches other sessions, not your own (" + caller + ")");
            }
            targets.putIfAbsent(target.name(), target);
        }
        Map<String, Optional<Observation>> observed = new LinkedHashMap<>();
        for (Target target : targets.values()) {
            observed.put(target.name(), observe(target));
        }
        List<String> replies = new ArrayList<>();
        for (Target target : targets.values()) {
            Optional<Observation> obs = observed.get(target.name());
            if (obs.isEmpty() || !obs.get().capture().alive()) {
                replies.add(target.name() + " has already exited; nothing registered");
                continue;
            }
            Observation o = obs.get();
            // a session whose spawn prompt is not typed in yet is about to work on it
            boolean awaitPrompt = (on == Trigger.FINISHED || on == Trigger.NEEDS_INPUT)
                    && promptPending(target.name());
            SessionState state = awaitPrompt ? SessionState.WORKING : o.classification().state();
            if (on == Trigger.FINISHED && state != SessionState.WORKING) {
                replies.add(target.name() + " is already " + state + " (not working); nothing registered");
                continue;
            }
            if (on == Trigger.NEEDS_INPUT && state == SessionState.WAITING_FOR_INPUT) {
                replies.add(target.name() + " is already " + state + "; nothing registered");
                continue;
            }
            String reply = "will notify " + caller + " when " + target.name() + " " + on.id;
            synchronized (notifications) {
                boolean duplicate = notifications.stream().anyMatch(n -> n.message == null
                        && n.caller.equals(caller) && n.target.equals(target.name()) && n.trigger == on);
                if (duplicate) {
                    reply = "already registered: " + reply;
                } else {
                    Notification n = new Notification(caller, target.name(), on, o.masked(), clock.instant());
                    n.awaitPrompt = awaitPrompt;
                    notifications.add(n);
                }
            }
            replies.add(reply);
        }
        wake();
        return String.join("\n", replies);
    }

    /** Whether a spawn prompt for {@code session} is still waiting or being typed in. */
    private boolean promptPending(String session) {
        synchronized (prompts) {
            return prompts.stream().anyMatch(p -> p.session.equals(session));
        }
    }

    private String spawn(String caller, JsonNode args) {
        String harnessId = requiredText(args, "harness");
        HarnessType harness;
        try {
            harness = HarnessType.fromId(harnessId);
        } catch (IllegalArgumentException e) {
            throw new ToolError("unknown harness " + harnessId + "; one of: " + harnessIds());
        }
        Path project = expandProject(requiredText(args, "project"));
        Optional<String> display = optionalText(args, "name").map(String::strip);
        Optional<String> prompt = optionalText(args, "prompt");
        Map<String, String> labels = caller != null && !caller.isBlank()
                ? Map.of(LABEL_SPAWNED_BY, caller) : Map.of();
        // the check and the creation are one step: a reservation keeps a concurrent spawn
        // from taking the same name before this one's label and registry row exist
        String reservation = display.map(d -> d.toLowerCase(Locale.ROOT)).orElse(null);
        if (reservation != null) {
            synchronized (spawningDisplays) {
                if (!spawningDisplays.add(reservation)) {
                    throw new ToolError("name already in use by a session being spawned; choose another");
                }
            }
        }
        String name;
        try {
            if (display.isPresent()) {
                String wanted = display.get();
                for (Session.Info info : liveSessions()) {
                    Target live = target(info);
                    if (wanted.equalsIgnoreCase(live.display()) || wanted.equals(live.name())) {
                        throw new ToolError("name already in use by " + live.name() + "; choose another");
                    }
                }
            }
            name = spawner.spawn(new SessionSpawner.SpawnRequest(harness, project, display, labels));
        } finally {
            if (reservation != null) {
                synchronized (spawningDisplays) {
                    spawningDisplays.remove(reservation);
                }
            }
        }
        String reply = name + " started (" + harness.id() + " in " + project + ")";
        if (prompt.isPresent()) {
            synchronized (prompts) {
                prompts.add(new PromptDelivery(name, prompt.get(), caller,
                        clock.instant().plus(timings.promptTimeout())));
            }
            wake();
            reply += "; the prompt will be typed in once it is ready at its prompt";
        }
        return reply;
    }

    private String kill(String caller, JsonNode args) {
        Target target = resolve(requiredText(args, "session"));
        refuseSelf(caller, target, "kill");
        spawner.kill(target.name());
        forget(target.name());
        return "killed " + target.name();
    }

    // ------------------------------------------------------------------ reading

    /** The read-style answer for {@code obs}, recording what {@code caller} has now seen. */
    private String render(String caller, Target target, Observation obs, boolean full) {
        String header = header(target, obs);
        List<String> lines = obs.body();
        long bytes = obs.capture().outputBytes();
        Seen previous = seen.put(seenKey(caller, target.name()), new Seen(bytes, obs.masked(), lines, obs.plain()));
        if (!full && previous != null) {
            // outputBytes is -1 (unknown) from a daemon that predates the counter
            boolean sameOutput = previous.outputBytes() >= 0 && bytes >= 0 && previous.outputBytes() == bytes;
            if (sameOutput || previous.plain().equals(obs.plain())) {
                return header + " · unchanged";
            }
            if (previous.masked().equals(obs.masked())) {
                List<String> changed = changedLines(previous.lines(), lines);
                return header + " · only status lines changed"
                        + (changed.isEmpty() ? "" : "\n" + ScreenDiff.cap(changed, MAX_STATUS_CHARS));
            }
            Optional<ScreenDiff.Delta> delta = ScreenDiff.newLines(previous.lines(), lines);
            if (delta.isPresent()) {
                return header + "\n… (" + delta.get().unchangedAbove()
                        + " lines above already seen; full=true shows the whole screen)\n"
                        + ScreenDiff.cap(delta.get().lines(), MAX_BODY_CHARS);
            }
        }
        return header + "\n" + ScreenDiff.cap(lines, MAX_BODY_CHARS);
    }

    private static String header(Target target, Observation obs) {
        Classification c = obs.classification();
        String title = obs.capture().title();
        return target.name() + " · " + target.harness().id() + " · " + c.state() + " (" + c.reason() + ")"
                + (title != null && !title.isBlank() ? " · " + title.strip() : "")
                + (obs.annotated() ? "" : " " + OLD_DAEMON_NOTE);
    }

    /** The lines of {@code current} that {@code previous} does not have (as many times):
     * what changed when only masked lines did. */
    private static List<String> changedLines(List<String> previous, List<String> current) {
        Map<String, Integer> before = new HashMap<>();
        previous.forEach(l -> before.merge(l, 1, Integer::sum));
        List<String> changed = new ArrayList<>();
        for (String line : current) {
            if (before.merge(line, -1, Integer::sum) < 0) {
                changed.add(line);
            }
        }
        return changed;
    }

    /** A capture and whether it is annotated (an old daemon only gives plain text). */
    private record Captured(Session.Capture capture, boolean annotated) {
    }

    /** The annotated screen, or plain text from a daemon that predates capture formats. */
    private Optional<Captured> capture(String name) {
        if (compat.has(DaemonCompat.Feature.ANNOTATED_CAPTURE)) {
            try {
                return jmux.capture(name, CaptureFormat.ANNOTATED).map(c -> new Captured(c, true));
            } catch (RuntimeException e) {
                if (!DaemonCompat.isUnsupported(e)) {
                    throw e;
                }
                compat.markMissing(DaemonCompat.Feature.ANNOTATED_CAPTURE);
            }
        }
        return jmux.capture(name, CaptureFormat.TEXT).map(c -> new Captured(c, false));
    }

    /** Captures the screen and classifies it; empty when the session is gone. */
    private Optional<Observation> observe(Target target) {
        Optional<Captured> captured = capture(target.name());
        if (captured.isEmpty()) {
            if (!reachable()) {
                throw new Unreachable(); // not gone: the daemon may be restarting
            }
            forget(target.name());
            return Optional.empty();
        }
        Session.Capture c = captured.get().capture();
        boolean annotated = captured.get().annotated();
        SessionTracker tracker = trackers.compute(target.name(), (name, existing) ->
                existing != null && existing.harness == target.harness() ? existing
                        : new SessionTracker(target.harness(), clock, timings.shortQuiescence(),
                        timings.longQuiescence()));
        String text = c.text() == null ? "" : c.text();
        String plain = annotated ? ScreenDiff.plain(text) : ScreenDiff.trimmed(text);
        Classification classification = tracker.classify(
                SessionTracker.screen(plain, c.alive(), clock.instant(), c.foregroundCommand(),
                        c.processId(), c.title()),
                quiet -> probeQuiet(target.name(), quiet));
        return Optional.of(new Observation(c, annotated, classification, tracker.plugin.maskChrome(text),
                ScreenDiff.bodyLines(text), plain, tracker));
    }

    /** Whether the session's output has been quiet this long; false (no backdating) when
     * the daemon predates wait. */
    private boolean probeQuiet(String name, Duration quiet) {
        if (!compat.has(DaemonCompat.Feature.WAIT)) {
            return false;
        }
        try {
            Session.WaitResult result = jmux.awaitQuiet(name, -1, quiet.toMillis(), 0);
            return result.quiet() && result.alive();
        } catch (RuntimeException e) {
            if (DaemonCompat.isUnsupported(e)) {
                compat.markMissing(DaemonCompat.Feature.WAIT);
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ resolution

    private Target resolve(String argument) {
        return resolve(argument, liveSessions());
    }

    /** {@link JmuxControl#list}, but an empty answer from a daemon that does not answer
     * is {@link Unreachable}, never "no sessions". */
    private List<Session.Info> liveSessions() {
        List<Session.Info> infos = jmux.list();
        if (infos.isEmpty() && !reachable()) {
            throw new Unreachable();
        }
        return infos;
    }

    /** Whether the daemon answers a ping; a failing ping counts as no. */
    private boolean reachable() {
        try {
            return jmux.ping();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The jmux session {@code argument} names: a session name, else a registry display
     * name (case-insensitive), else a {@code display} label. */
    private Target resolve(String argument, List<Session.Info> infos) {
        for (Session.Info info : infos) {
            if (info.name().equals(argument)) {
                return target(info);
            }
        }
        List<Target> targets = infos.stream().map(this::target).toList();
        for (boolean registryName : new boolean[]{true, false}) {
            List<Target> matches = targets.stream().filter(t -> {
                String candidate = registryName
                        ? t.entry().map(SessionEntry::displayName).orElse(null)
                        : t.labels().get(SessionLauncher.LABEL_DISPLAY);
                return candidate != null && candidate.equalsIgnoreCase(argument);
            }).toList();
            if (matches.size() == 1) {
                return matches.getFirst();
            }
            if (matches.size() > 1) {
                throw new ToolError("\"" + argument + "\" is ambiguous: "
                        + matches.stream().map(Target::name).collect(Collectors.joining(", "))
                        + " — use the session name");
            }
        }
        Optional<SessionEntry> registered = registry.find(argument).or(() -> registry.sessions().stream()
                .filter(e -> e.displayName() != null && e.displayName().equalsIgnoreCase(argument))
                .findFirst());
        if (registered.isPresent()) {
            if (!SessionServerHost.ID.equals(registered.get().hostId()) || registered.get().isRemote()) {
                throw new ToolError(registered.get().tmuxSession() + " is " + NOT_JMUX);
            }
            throw new ToolError(registered.get().tmuxSession() + " is not running");
        }
        throw new ToolError("no such session: " + argument + " (list shows the sessions)");
    }

    private Target target(Session.Info info) {
        Map<String, String> labels = info.labels() == null ? Map.of() : info.labels();
        Optional<SessionEntry> entry = registry.find(info.name())
                .filter(e -> SessionServerHost.ID.equals(e.hostId()) && !e.isRemote());
        HarnessType harness = entry.map(SessionEntry::harnessType)
                .orElseGet(() -> harnessFromLabel(labels.get(SessionLauncher.LABEL_HARNESS)));
        String display = entry.map(SessionEntry::displayName).filter(d -> !d.isBlank())
                .orElseGet(() -> blankToNull(labels.get(SessionLauncher.LABEL_DISPLAY)));
        String project = entry.map(SessionEntry::projectPath)
                .or(() -> Optional.ofNullable(blankToNull(labels.get(SessionLauncher.LABEL_PROJECT))))
                .orElseGet(() -> info.workdir() != null ? info.workdir().toString() : "-");
        return new Target(info.name(), entry, labels, harness, display, project);
    }

    private static HarnessType harnessFromLabel(String id) {
        if (id == null) {
            return HarnessType.SHELL;
        }
        try {
            return HarnessType.fromId(id);
        } catch (IllegalArgumentException e) {
            return HarnessType.SHELL;
        }
    }

    // ------------------------------------------------------------------ typing

    /**
     * Types in two phases: pastes {@code text} (unless empty or {@code pasted} — a retry
     * after the paste went in but the Enter did not), runs {@code onPasted}, then after the
     * enter delay presses Enter. Against a daemon that predates input it falls back to the
     * old send-keys op, which types the text as is and presses Enter after its own pause.
     *
     * @param multiLineNeedsBracketedPaste when {@code text} has a line break or another
     *        control character, refuse the paste (nothing written, {@link PasteRefusal})
     *        unless the session's program enabled bracketed paste — otherwise each line
     *        would arrive as typed, the first newline would submit early, and ESC or ^C
     *        would act as keys
     */
    private void type(String session, String text, boolean enter, boolean pasted,
                      boolean multiLineNeedsBracketedPaste, Runnable onPasted) {
        if (!compat.has(DaemonCompat.Feature.INPUT)) {
            typeLegacy(session, pasted ? "" : text, enter);
            onPasted.run();
            return;
        }
        if (!pasted && !text.isEmpty()) {
            boolean requireBracketed = multiLineNeedsBracketedPaste
                    && text.chars().anyMatch(SessionController::actsAsKeyOutsidePaste);
            try {
                jmux.input(session, text, true, requireBracketed, null);
            } catch (RuntimeException e) {
                if (requireBracketed && isBracketedPasteRefusal(e)) {
                    throw new PasteRefusal(MULTI_LINE_REFUSED);
                }
                if (!DaemonCompat.isUnsupported(e)) {
                    throw e;
                }
                compat.markMissing(DaemonCompat.Feature.INPUT);
                typeLegacy(session, text, enter);
                onPasted.run();
                return;
            }
            onPasted.run();
            if (enter) {
                try {
                    sleeper.sleep(timings.enterDelay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ToolError("interrupted before pressing Enter in " + session);
                }
            }
        }
        if (enter) {
            try {
                jmux.input(session, null, false, List.of("Enter"));
            } catch (RuntimeException e) {
                if (!DaemonCompat.isUnsupported(e)) {
                    throw e;
                }
                compat.markMissing(DaemonCompat.Feature.INPUT);
                typeLegacy(session, "", true);
            }
        }
    }

    /** {@code text} through the old send-keys op: typed as is, so a newline would submit a
     * line and a control character would act as a key — such text is refused. */
    private void typeLegacy(String session, String text, boolean enter) {
        if (text.chars().anyMatch(ch -> ch == '\n' || ch == '\r')) {
            throw new LegacyRefusal("the running jmux daemon (pid " + compat.pid() + ") predates pasting, so text "
                    + "is typed as is and every newline would submit a line: send one line at a time. "
                    + OLD_DAEMON_REPLACED);
        }
        if (text.chars().anyMatch(ch -> (ch < 0x20 && ch != '\t') || (ch >= 0x7f && ch < 0xa0))) {
            throw new LegacyRefusal("the running jmux daemon (pid " + compat.pid() + ") predates pasting, so text "
                    + "is typed as is and control characters in it would act as keys: remove them. "
                    + OLD_DAEMON_REPLACED);
        }
        if (text.isEmpty() && !enter) {
            return;
        }
        jmux.sendKeys(session, text, enter);
    }

    /** Whether {@code e} is the daemon refusing a paste the program did not enable
     * bracketed paste for. */
    private static boolean isBracketedPasteRefusal(Throwable e) {
        return e instanceof SessionServerException && Protocol.ERROR_BRACKETED_PASTE_DISABLED.equals(e.getMessage());
    }

    private String keysNeedNewerDaemon() {
        return "keys needs a newer jmux daemon; the running one (pid " + compat.pid() + ") predates it. "
                + OLD_DAEMON_REPLACED;
    }

    // ------------------------------------------------------------------ monitor

    /** Whether the monitor has anything to evaluate. */
    boolean hasPending() {
        synchronized (notifications) {
            if (!notifications.isEmpty()) {
                return true;
            }
        }
        synchronized (prompts) {
            return !prompts.isEmpty();
        }
    }

    /**
     * One monitor pass: fires the registrations whose condition holds, delivers spawn
     * prompts to sessions now ready (and gives up on ones that never got there), and
     * types fired notifications into callers idle at their harness's prompt. Package-private
     * so tests drive it with a mutable clock instead of the background thread.
     */
    void tick() {
        if (!hasPending()) {
            return;
        }
        List<Session.Info> infos = jmux.list();
        if (infos.isEmpty() && !reachable()) {
            // not "every session is gone": the daemon does not answer — judge nothing now
            LOG.debug("control monitor: the jmux daemon is not answering; skipping this pass");
            return;
        }
        Map<String, Target> live = new HashMap<>();
        for (Session.Info info : infos) {
            live.put(info.name(), target(info));
        }
        Map<String, Look> observed = new HashMap<>();
        Instant now = clock.instant();
        evaluateRegistrations(live, observed, now);
        evaluatePrompts(live, observed, now);
        deliverNotifications(live, observed, now);
    }

    /** One monitor look at a session: seen, gone, or unknown (the capture failed for some
     * other reason, or the daemon stopped answering — try again next tick rather than take
     * it for gone). */
    private record Look(Observation obs, boolean unknown) {
        static final Look GONE = new Look(null, false);
        static final Look UNKNOWN = new Look(null, true);

        boolean gone() {
            return !unknown && (obs == null || !obs.capture().alive());
        }
    }

    private Look observeOnce(String name, Map<String, Target> live, Map<String, Look> observed) {
        return observed.computeIfAbsent(name, n -> {
            Target target = live.get(n);
            if (target == null) {
                forget(n);
                return Look.GONE;
            }
            try {
                return observe(target).map(o -> new Look(o, false)).orElse(Look.GONE);
            } catch (RuntimeException e) {
                LOG.debug("monitor could not capture {}: {}", n, e.toString());
                return Look.UNKNOWN;
            }
        });
    }

    private void evaluateRegistrations(Map<String, Target> live, Map<String, Look> observed, Instant now) {
        List<Notification> armed;
        synchronized (notifications) {
            armed = notifications.stream().filter(n -> n.message == null).toList();
        }
        for (Notification n : armed) {
            if (!live.containsKey(n.caller)) {
                drop(n, "its caller " + n.caller + " is gone");
                continue;
            }
            Look look = observeOnce(n.target, live, observed);
            if (look.unknown()) {
                continue;
            }
            if (look.gone()) {
                fire(n, "exited", "EXITED", now);
                continue;
            }
            Observation o = look.obs();
            SessionState state = o.classification().state();
            if (n.trigger == Trigger.FINISHED || n.trigger == Trigger.NEEDS_INPUT) {
                boolean waitingForPrompt;
                synchronized (notifications) {
                    waitingForPrompt = n.awaitPrompt;
                }
                if (waitingForPrompt) {
                    if (Duration.between(n.registered, now).compareTo(timings.notifyTtl()) > 0) {
                        drop(n, n.target + " did not take up its spawn prompt within "
                                + timings.notifyTtl().toMinutes() + " min");
                        continue;
                    }
                    // only once the spawn prompt is in and the session has reacted to it:
                    // working on it, asking something, or its harness gone back to a shell
                    boolean reacted = state == SessionState.WORKING || state == SessionState.WAITING_FOR_INPUT
                            || o.tracker().atShell(o.capture().foregroundCommand());
                    if (promptPending(n.target) || !reacted) {
                        continue;
                    }
                    synchronized (notifications) {
                        n.awaitPrompt = false;
                    }
                }
            }
            boolean fired = switch (n.trigger) {
                case FINISHED -> state != SessionState.WORKING;
                case NEEDS_INPUT -> state == SessionState.WAITING_FOR_INPUT;
                case EXITED -> false;
                case ANY_CHANGE -> !o.masked().equals(n.baseline);
            };
            if (fired) {
                fire(n, n.trigger.description, state.name(), now);
            }
        }
    }

    private void evaluatePrompts(Map<String, Target> live, Map<String, Look> observed, Instant now) {
        List<PromptDelivery> waiting;
        synchronized (prompts) {
            waiting = prompts.stream().filter(p -> !p.delivering).toList();
        }
        for (PromptDelivery p : waiting) {
            boolean enterOwed;
            synchronized (prompts) {
                enterOwed = p.pasted;
            }
            Look look = live.containsKey(p.session) ? observeOnce(p.session, live, observed) : Look.GONE;
            if (enterOwed) {
                // the prompt is in its composer: only Enter is owed, whatever the session
                // looks like now and however late it is
                if (look.gone()) {
                    abandon(p, "exited after its prompt was pasted, before it was submitted", live, now);
                    continue;
                }
            } else {
                boolean late = now.isAfter(p.deadline);
                if (look.unknown() && !late) {
                    continue;
                }
                if (look.gone() || late) {
                    abandon(p, look.gone() ? "exited before its prompt could be sent"
                            : "did not reach its prompt within " + timings.promptTimeout().toSeconds()
                            + " s, so the prompt was not sent", live, now);
                    continue;
                }
                Observation o = look.obs();
                boolean ready = o.classification().state() == SessionState.IDLE
                        && o.tracker().harnessRunning(o.capture().foregroundCommand());
                if (!ready) {
                    continue;
                }
            }
            boolean pasted;
            synchronized (prompts) {
                if (p.delivering || !prompts.contains(p)) {
                    continue; // another monitor pass took it meanwhile
                }
                p.delivering = true;
                pasted = p.pasted;
            }
            deliveries.execute(() -> {
                try {
                    type(p.session, p.prompt, true, pasted, true, () -> {
                        synchronized (prompts) {
                            p.pasted = true;
                        }
                    });
                    synchronized (prompts) {
                        prompts.remove(p);
                    }
                } catch (LegacyRefusal | PasteRefusal e) {
                    abandon(p, "could not be given its prompt: " + e.getMessage(), live, clock.instant());
                } catch (RuntimeException e) {
                    LOG.warn("could not type the spawn prompt into {}: {}", p.session, e.toString());
                    boolean giveUp;
                    synchronized (prompts) {
                        p.delivering = false;
                        giveUp = p.pasted && ++p.enterAttempts >= MAX_ENTER_ATTEMPTS;
                    }
                    if (giveUp) {
                        abandon(p, "had its prompt pasted but not submitted: pressing Enter failed ("
                                + e.getMessage() + "); press Enter in it with the keys tool", live, clock.instant());
                    }
                }
            });
        }
    }

    /** Gives up on {@code p}: tells its requester why, and lets notifications that were
     * waiting for the prompt judge the session as it is. */
    private void abandon(PromptDelivery p, String why, Map<String, Target> live, Instant now) {
        synchronized (prompts) {
            if (!prompts.remove(p)) {
                return;
            }
        }
        LOG.info("spawned session {} {}", p.session, why);
        synchronized (notifications) {
            notifications.stream().filter(n -> n.target.equals(p.session)).forEach(n -> n.awaitPrompt = false);
        }
        Target requester = p.requester == null ? null : live.get(p.requester);
        if (requester != null && requester.harness() != HarnessType.SHELL) {
            Notification notice = new Notification(p.requester, p.session, null, null, now);
            notice.message = NOTICE_PREFIX + p.session + " " + why + ". Use the read tool to see it.";
            notice.firedAt = now;
            synchronized (notifications) {
                notifications.add(notice);
            }
        }
    }

    private void deliverNotifications(Map<String, Target> live, Map<String, Look> observed, Instant now) {
        Map<String, List<Notification>> byCaller = new LinkedHashMap<>();
        synchronized (notifications) {
            for (Notification n : notifications) {
                if (n.message != null && !n.delivering) {
                    byCaller.computeIfAbsent(n.caller, c -> new ArrayList<>()).add(n);
                }
            }
        }
        for (Map.Entry<String, List<Notification>> e : byCaller.entrySet()) {
            String caller = e.getKey();
            List<Notification> ready = new ArrayList<>();
            for (Notification n : e.getValue()) {
                boolean enterOwed;
                synchronized (notifications) {
                    enterOwed = n.pasted;
                }
                // a pasted notice only owes its Enter: never dropped as undelivered
                if (!enterOwed && Duration.between(n.firedAt, now).compareTo(timings.notifyTtl()) > 0) {
                    drop(n, "undelivered for " + timings.notifyTtl().toMinutes() + " min while " + caller
                            + " was busy");
                } else {
                    ready.add(n);
                }
            }
            if (ready.isEmpty()) {
                continue;
            }
            Look look = live.containsKey(caller) ? observeOnce(caller, live, observed) : Look.GONE;
            if (look.gone()) {
                ready.forEach(n -> drop(n, "its caller " + caller + " is gone"));
                continue;
            }
            // only into the caller's own harness, idle at its prompt: never while it waits
            // for an answer (Enter would pick an option) and never into a bare shell — except
            // the Enter owed for a notice already pasted, which goes in regardless
            boolean atItsPrompt = !look.unknown()
                    && look.obs().classification().state() == SessionState.IDLE
                    && live.get(caller).harness() != HarnessType.SHELL
                    && look.obs().tracker().harnessRunning(look.obs().capture().foregroundCommand());
            List<Notification> batch;
            boolean pasted;
            synchronized (notifications) {
                List<Notification> free = ready.stream()
                        .filter(n -> !n.delivering && notifications.contains(n)).toList();
                List<Notification> enterOnly = free.stream().filter(n -> n.pasted).toList();
                pasted = !enterOnly.isEmpty();
                batch = pasted ? enterOnly : atItsPrompt ? free : List.of();
                batch.forEach(n -> n.delivering = true);
            }
            if (batch.isEmpty()) {
                continue; // held until the caller is ready for input
            }
            // the old send-keys op types as is, where a newline would submit
            String text = pasted ? "" : batch.stream().map(n -> n.message)
                    .collect(Collectors.joining(compat.has(DaemonCompat.Feature.INPUT) ? "\n" : " "));
            deliveries.execute(() -> {
                try {
                    type(caller, text, true, pasted, false, () -> {
                        synchronized (notifications) {
                            batch.forEach(n -> n.pasted = true);
                        }
                    });
                    synchronized (notifications) {
                        notifications.removeAll(batch);
                    }
                } catch (RuntimeException ex) {
                    LOG.warn("could not deliver a notification to {}: {}", caller, ex.toString());
                    List<Notification> givenUp = new ArrayList<>();
                    synchronized (notifications) {
                        for (Notification n : batch) {
                            n.delivering = false;
                            if (n.pasted && ++n.enterAttempts >= MAX_ENTER_ATTEMPTS) {
                                givenUp.add(n);
                            }
                        }
                    }
                    givenUp.forEach(n -> drop(n, "it was pasted into " + caller + " but not submitted: pressing "
                            + "Enter failed (" + ex.getMessage() + ")"));
                }
            });
        }
    }

    private void fire(Notification n, String description, String state, Instant now) {
        synchronized (notifications) {
            n.message = NOTICE_PREFIX + n.target + " " + description + " (" + state + "). Use the read tool to see it.";
            n.firedAt = now;
        }
    }

    private void drop(Notification n, String why) {
        LOG.info("dropping notification for {} about {}: {}", n.caller, n.target, why);
        synchronized (notifications) {
            notifications.remove(n);
        }
    }

    private void monitorLoop() {
        JmuxControl.Watch watch = null;
        Duration backoff = timings.watchBackoffMin();
        long nextAttempt = System.nanoTime();
        try {
            while (isCurrentMonitor()) {
                compat.revalidate();
                if (compat.has(DaemonCompat.Feature.WATCH) && (watch == null || !watch.isOpen())
                        && System.nanoTime() - nextAttempt >= 0) {
                    closeQuietly(watch);
                    try {
                        watch = jmux.watch(timings.watchQuietMs(), this::onEvent);
                        backoff = timings.watchBackoffMin();
                    } catch (RuntimeException e) {
                        watch = null;
                        if (DaemonCompat.isUnsupported(e)) {
                            // logged once per daemon; the monitor polls on its tick instead
                            compat.markMissing(DaemonCompat.Feature.WATCH);
                        } else {
                            LOG.debug("jmux watch unavailable, retrying in {}: {}", backoff, e.toString());
                            nextAttempt = System.nanoTime() + backoff.toNanos();
                            Duration doubled = backoff.multipliedBy(2);
                            backoff = doubled.compareTo(timings.watchBackoffMax()) > 0
                                    ? timings.watchBackoffMax() : doubled;
                        }
                    }
                }
                try {
                    tick();
                } catch (RuntimeException e) {
                    LOG.debug("control monitor tick failed: {}", e.toString());
                }
                try {
                    if (wake.tryAcquire(timings.tick().toMillis(), TimeUnit.MILLISECONDS)) {
                        wake.drainPermits();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            closeQuietly(watch);
        }
    }

    private void onEvent(SessionEvent event) {
        if (Protocol.EVENT_EXIT.equals(event.type())) {
            forget(event.name());
        }
        if (hasPending()) {
            wake();
        }
    }

    private void wake() {
        if (wake.availablePermits() == 0) {
            wake.release();
        }
    }

    private static void closeQuietly(JmuxControl.Watch watch) {
        if (watch != null) {
            try {
                watch.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    /** Whether the background monitor is running (tests). */
    synchronized boolean monitorRunning() {
        return monitor != null && monitor.isAlive();
    }

    // ------------------------------------------------------------------ bookkeeping

    private void forget(String session) {
        trackers.remove(session);
        String suffix = "\u0000" + session;
        seen.keySet().removeIf(k -> k.endsWith(suffix));
    }

    private void forgetAllBut(Set<String> live) {
        trackers.keySet().retainAll(live);
        seen.keySet().removeIf(k -> !live.contains(k.substring(k.indexOf('\u0000') + 1)));
    }

    private static String seenKey(String caller, String session) {
        return (caller == null ? "" : caller) + "\u0000" + session;
    }

    private static void refuseSelf(String caller, Target target, String verb) {
        if (target.name().equals(caller)) {
            throw new ToolError("refusing to " + verb + " your own session (" + caller + ")");
        }
    }

    private static List<String> sessionsArgument(JsonNode args) {
        Set<String> names = new LinkedHashSet<>();
        for (String field : List.of("session", "sessions")) {
            JsonNode node = args.get(field);
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isTextual() && !node.asText().isBlank()) {
                names.add(node.asText().strip());
            } else if (node.isArray()) {
                for (JsonNode n : node) {
                    if (!n.isTextual() || n.asText().isBlank()) {
                        throw new ToolError(field + " must hold session names (strings)");
                    }
                    names.add(n.asText().strip());
                }
            } else {
                throw new ToolError(field + " must be a session name or an array of them");
            }
        }
        if (names.isEmpty()) {
            throw new ToolError("session is required (a session name, or sessions: an array of them)");
        }
        return List.copyOf(names);
    }

    private static String requiredText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ToolError(field + " is required (a string)");
        }
        return node.asText().strip();
    }

    private static Optional<String> optionalText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isTextual()) {
            throw new ToolError(field + " must be a string");
        }
        return Optional.of(node.asText()).filter(s -> !s.isBlank());
    }

    /** {@code ~} and {@code ~/…} expanded; must name an existing directory by absolute path. */
    static Path expandProject(String text) {
        String home = System.getProperty("user.home");
        String expanded = text.equals("~") ? home
                : text.startsWith("~/") ? home + text.substring(1) : text;
        Path path;
        try {
            path = Path.of(expanded);
        } catch (InvalidPathException e) {
            throw new ToolError("not a valid path: " + text);
        }
        if (!path.isAbsolute()) {
            throw new ToolError("project must be an absolute path (or start with ~): " + text);
        }
        path = path.normalize();
        if (!Files.isDirectory(path)) {
            throw new ToolError("project is not an existing directory: " + path);
        }
        return path;
    }

    private static String harnessIds() {
        return Arrays.stream(HarnessType.values()).map(HarnessType::id).collect(Collectors.joining(", "));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ------------------------------------------------------------------ types

    /** A resolved jmux session and what the registry and its labels say about it. */
    private record Target(String name, Optional<SessionEntry> entry, Map<String, String> labels,
                          HarnessType harness, String display, String project) {
    }

    /** One capture of a session, classified. {@code body} is the annotated screen's
     * lines; {@code masked} its chrome-free text for change comparison. */
    private record Observation(Session.Capture capture, boolean annotated, Classification classification,
                               String masked, List<String> body, String plain, SessionTracker tracker) {
    }

    /** What one caller last read of one session; {@code plain} is the unmasked screen
     * without markers. */
    private record Seen(long outputBytes, String masked, List<String> lines, String plain) {
    }

    /** A notify registration; {@code message} is set once it fired. Mutable fields are
     * guarded by the {@code notifications} list. A null trigger is an internal notice. */
    private static final class Notification {
        final String caller;
        final String target;
        final Trigger trigger;
        final String baseline;
        final Instant registered;
        String message;
        Instant firedAt;
        boolean delivering;
        /** The message is in the caller's composer; only its Enter is still owed. */
        boolean pasted;
        /** Failed tries of the owed Enter. */
        int enterAttempts;
        /** Registered while the target's spawn prompt was pending: FINISHED and NEEDS_INPUT
         * wait until the prompt is in and the target has reacted (seen WORKING or
         * WAITING_FOR_INPUT, or back at a shell); dropped when that takes longer than the
         * notify TTL. */
        boolean awaitPrompt;

        Notification(String caller, String target, Trigger trigger, String baseline, Instant registered) {
            this.caller = caller;
            this.target = target;
            this.trigger = trigger;
            this.baseline = baseline;
            this.registered = registered;
        }
    }

    /** A spawn prompt waiting for its session to be ready. Mutable fields are guarded by
     * the {@code prompts} list. */
    private static final class PromptDelivery {
        final String session;
        final String prompt;
        final String requester;
        final Instant deadline;
        boolean delivering;
        /** The prompt is in the composer; only its Enter is still owed. */
        boolean pasted;
        /** Failed tries of the owed Enter. */
        int enterAttempts;

        PromptDelivery(String session, String prompt, String requester, Instant deadline) {
            this.session = session;
            this.prompt = prompt;
            this.requester = requester;
            this.deadline = deadline;
        }
    }

    /** A caller mistake, reported verbatim as the tool's error text. */
    static class ToolError extends RuntimeException {
        ToolError(String message) {
            super(message);
        }
    }

    /** Text the old send-keys fallback cannot type safely; retrying cannot help. */
    static final class LegacyRefusal extends ToolError {
        LegacyRefusal(String message) {
            super(message);
        }
    }

    /** A multi-line paste the session's program has not enabled bracketed paste for;
     * nothing was written, and retrying cannot help. */
    static final class PasteRefusal extends ToolError {
        PasteRefusal(String message) {
            super(message);
        }
    }

    /** The daemon did not answer: a session that could not be listed or captured is not
     * known to be gone. */
    static final class Unreachable extends ToolError {
        Unreachable() {
            super(DAEMON_UNREACHABLE);
        }
    }

    // ------------------------------------------------------------------ tool definitions

    private static List<ToolDef> toolDefs() {
        return List.of(
                new ToolDef(TOOL_LIST, LIST_DESCRIPTION, schema()),
                new ToolDef(TOOL_READ, READ_DESCRIPTION, required(schema(
                        prop("session", "string", SESSION_ARG),
                        prop("full", "boolean", "true: return the whole screen even if you have seen it. "
                                + "Default false: only what changed since your last read.")),
                        "session")),
                new ToolDef(TOOL_SEND, SEND_DESCRIPTION, required(schema(
                        prop("session", "string", SESSION_ARG),
                        prop("text", "string", "The message to type, as one block (newlines do not submit; "
                                + "multi-line text needs a session that accepts pastes)."),
                        prop("enter", "boolean", "Press Enter after the text to submit it. Default true.")),
                        "session", "text")),
                new ToolDef(TOOL_KEYS, KEYS_DESCRIPTION, required(schema(
                        prop("session", "string", SESSION_ARG),
                        stringArray("keys", "Key names pressed in order, e.g. [\"Down\",\"Enter\"], [\"y\"], "
                                + "[\"Esc\"], [\"C-c\"]. See the tool description for every name.")),
                        "session", "keys")),
                new ToolDef(TOOL_NOTIFY, NOTIFY_DESCRIPTION, required(schema(
                        prop("session", "string", "The session to watch (name or display name)."),
                        stringArray("sessions", "Several sessions to watch instead; each notifies separately."),
                        enumProp("on", "What to be told about.", "finished", "needs-input", "exited", "any-change")),
                        "on")),
                new ToolDef(TOOL_SPAWN, SPAWN_DESCRIPTION, required(schema(
                        enumProp("harness", "Which agent (or plain shell) to run.",
                                Arrays.stream(HarnessType.values()).map(HarnessType::id).toArray(String[]::new)),
                        prop("project", "string", "The directory to run it in: an existing absolute path, ~ allowed."),
                        prop("name", "string", "Optional display name for the sidebar and for addressing it."),
                        prop("prompt", "string", "Optional first message, typed in once the harness is ready "
                                + "(multi-line only where the harness accepts pastes).")),
                        "harness", "project")),
                new ToolDef(TOOL_KILL, KILL_DESCRIPTION, required(schema(
                        prop("session", "string", SESSION_ARG)), "session")));
    }

    private static final String SESSION_ARG = "The session: its name (hm-…) or display name, as shown by list.";

    static final String LIST_DESCRIPTION = """
            List the sessions you can see and drive: every terminal session harness-manager hosts \
            in jmux (coding agents and shells), one per line:
            `name · display name · harness · STATE · project · transcript: path`
            Your own session is marked (you). STATE is WORKING (busy, output changing), \
            WAITING_FOR_INPUT (a question, approval or menu waits for an answer) or IDLE (at its \
            prompt, or its harness is not running). Address a session by its name or display name.
            transcript is the harness's own conversation log (JSONL) when harness-manager can find \
            it (claude-code, codex), else "-" — or, when it can't tell which file is a session's, \
            the folder with a note saying so. History lives there, not on screen: search it with \
            your file tools (grep, tail, read a line range) — never load a whole transcript, they \
            get large.""";

    static final String READ_DESCRIPTION = """
            Show a session's terminal screen. The first line is \
            `name · harness · STATE (reason) · window title`; the screen follows.
            Reads are incremental and cheap, per session and per reader: the first read returns \
            the whole visible screen. A later read returns:
            - `… · unchanged` when the session printed nothing since your previous read, or its \
            screen text is identical (only the cursor or highlight moved);
            - `… · only status lines changed` followed by just the lines that differ, when every \
            change is in lines the harness's chrome filter drops: its input box/composer (for \
            opencode and pi everything from it down), hint, tip and footer bars, rules, box-drawn \
            lines, spinner and timer lines, and for codex also numbered option rows and `$ ` \
            command previews;
            - otherwise only the lines that are new since your previous read (a `… (N lines \
            above already seen)` line leads them), or the whole screen when it was redrawn.
            full=true always returns the whole screen.
            Markers: a line starting with `▶ ` is highlighted — usually the selected option of a \
            menu; `▮` is where the cursor is. If the header ends with `(old jmux daemon: no \
            selection/cursor markers)` there are none. Output is capped at 8000 characters (oldest lines \
            dropped). Only the visible screen is shown; for earlier history read the transcript \
            (see list). Don't call read in a loop to wait for a session: use notify.""";

    static final String SEND_DESCRIPTION = """
            Type a message into another session, as a user would at its prompt, and press Enter \
            to submit it (enter=false only types it). The text is pasted as one block, so \
            newlines in it do not submit early. Multi-line text is only sent where the session's \
            program accepts pastes (coding agents do; a bare shell or a plain program may not): \
            otherwise nothing is typed and you get an error — send one line at a time then. Use \
            it for prompts, follow-ups and slash commands. \
            Returns once typed — it does not wait for an answer: use notify with on=finished to \
            learn when the session is done, then read it. To answer a menu, an approval or a y/n \
            question use keys instead. You cannot send to your own session.""";

    static final String KEYS_DESCRIPTION = """
            Press keys in a session and get back its screen once the output settles (up to 3 s) — \
            for menus, approvals, y/n questions, pickers and interrupting. Work one step at a \
            time: read the screen, press the key(s) for one choice, check the returned screen, \
            repeat. A `▶ ` line is the highlighted option, `▮` the cursor.
            Key names (case-insensitive):
            - a single character: `y`, `n`, `1`, `/`, `?` (typed as is)
            - Enter, Tab, BTab (shift-tab), Esc, Space, Backspace, Delete, Insert
            - Up, Down, Left, Right, Home, End, PageUp, PageDown, F1 … F12
            - C-x: Ctrl with a letter or one of @[\\]^_? — C-c interrupts, C-d is end of input
            - M-x: Alt/Meta, i.e. Esc then the key (M-b, M-Enter)
            Examples: ["Down","Down","Enter"] picks the third option of a menu; ["2"] picks \
            option 2 where digits select; ["Esc"] dismisses. An unknown name fails before \
            anything is pressed. For free text use send.""";

    static final String NOTIFY_DESCRIPTION = """
            Be told when another session reaches a state, instead of polling it with read. \
            One-shot: it fires once, then is gone. on:
            - finished: the session is WORKING now and stops working. If it is not working, \
            you are told its current state and nothing is registered.
            - needs-input: it waits for an answer (question, approval, menu).
            - exited: the session ends.
            - any-change: its content changes. Changes confined to the harness's chrome do not \
            count — its input box/composer, hint and footer bars, rules, spinner and timer lines \
            — so typing into its prompt does not fire it.
            A session that exits always notifies, whatever on says. A session just spawned with \
            a prompt counts as working on it: finished and needs-input fire only after the \
            prompt was typed in and the session took it up.
            The notification arrives as a line typed into your own session, and only while your \
            harness is idle at its prompt: it is held while you are working and while you wait \
            for an answer (a question, approval or menu — so it can never answer one), e.g.
            `[harness-manager] hm-api finished (IDLE). Use the read tool to see it.`
            So: register, end your turn, and continue when that line arrives. It never contains \
            the session's output — read it. Notifications still undelivered after 30 minutes \
            are dropped. Registering the same session and on again changes nothing. Only works \
            from an agent session harness-manager launched (it must know where to deliver; a plain \
            shell session cannot receive notifications); you cannot watch your own session.""";

    static final String SPAWN_DESCRIPTION = """
            Start a new session in harness-manager: the chosen harness runs in project, the \
            session appears in harness-manager's sidebar (labelled as spawned by you), and \
            claude-code and codex get this same MCP server. name sets its display name (it must \
            not already be used by a running session). prompt, \
            if given, is typed in as its first message once the harness is idle at its prompt \
            (up to 120 s; if it never gets there you are notified and nothing is typed — read \
            the session to see why, e.g. a trust question to answer with keys). A multi-line \
            prompt goes in as one paste; if the harness does not accept pastes you are notified \
            and nothing is typed. Returns the new \
            session's name; use notify with on=finished to learn when it is done.""";

    static final String KILL_DESCRIPTION = """
            End a session: its whole process tree is terminated and it leaves harness-manager's \
            sidebar. Work in progress that the harness has not saved is lost — to stop an agent \
            cleanly, send it its own quit command (/exit, /quit) instead. You cannot kill your \
            own session.""";

    // ------------------------------------------------------------------ schema helpers

    private record Prop(String name, ObjectNode schema) {
    }

    private static ObjectNode schema(Prop... props) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (Prop p : props) {
            properties.set(p.name(), p.schema());
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode required(ObjectNode schema, String... names) {
        ArrayNode required = schema.putArray("required");
        for (String n : names) {
            required.add(n);
        }
        return schema;
    }

    private static Prop prop(String name, String type, String description) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("type", type);
        p.put("description", description);
        return new Prop(name, p);
    }

    private static Prop stringArray(String name, String description) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("type", "array");
        p.putObject("items").put("type", "string");
        p.put("description", description);
        return new Prop(name, p);
    }

    private static Prop enumProp(String name, String description, String... values) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("type", "string");
        ArrayNode e = p.putArray("enum");
        for (String v : values) {
            e.add(v);
        }
        p.put("description", description);
        return new Prop(name, p);
    }
}
