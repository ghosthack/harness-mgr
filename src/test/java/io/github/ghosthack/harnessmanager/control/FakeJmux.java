package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.jmux.client.SessionServerException;
import io.github.ghosthack.jmux.protocol.Protocol;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** An in-memory jmux for controller tests: sessions with a scripted screen. With
 * {@link #old} set it plays a daemon that predates input, wait, watch, labels, capture
 * formats and the output counter, as the old jmux does. */
final class FakeJmux implements JmuxControl {

    static final class FakeSession {
        final String name;
        final Map<String, String> labels = new LinkedHashMap<>();
        Path workdir = Path.of("/work");
        String text = "";
        String foreground = "zsh";
        String title = "";
        long outputBytes;
        boolean alive = true;
        /** Whether its program enabled bracketed paste (DEC 2004), as agents do. */
        boolean bracketedPaste = true;

        FakeSession(String name) {
            this.name = name;
        }

        /** Replaces the screen, counting the new text as output. */
        FakeSession show(String screen) {
            text = screen;
            outputBytes += screen.length() + 1;
            return this;
        }
    }

    /** One {@link #input} call. */
    record Input(String session, String text, boolean paste, List<String> keys) {
    }

    /** One {@link #sendKeys} call (the old daemon's only way to type). */
    record SendKeys(String session, String text, boolean enter) {
    }

    /** One {@link #awaitQuiet} call that was not a non-blocking probe. */
    record Wait(String session, long sinceBytes, long quietMs, long timeoutMs) {
    }

    final Map<String, FakeSession> sessions = new LinkedHashMap<>();
    final List<Input> inputs = new CopyOnWriteArrayList<>();
    final List<Wait> waits = new CopyOnWriteArrayList<>();
    final List<String> labelled = new ArrayList<>();
    final List<SendKeys> sentKeys = new CopyOnWriteArrayList<>();
    /** The inputs (written or refused) that asked for requireBracketedPaste. */
    final List<Input> bracketedRequired = new CopyOnWriteArrayList<>();
    /** Plays an unreachable daemon: list and capture come back empty, ping fails, every
     * other call reports no server — as the client does when no daemon answers. */
    volatile boolean down;
    /** Runs after every successful {@link #list} — e.g. to take the daemon down between a
     * list and the captures that follow it. */
    volatile Runnable afterList = () -> { };
    /** Plays a daemon that predates everything but list/capture-text/send-keys/kill. */
    volatile boolean old;
    /** What ping reports; -1 = no daemon answers. */
    volatile long pid = 100;
    final AtomicInteger pings = new AtomicInteger();
    final AtomicInteger unsupportedCalls = new AtomicInteger();
    /** Per session: how many captures still fail with a transient error. */
    final Map<String, Integer> captureFailures = new ConcurrentHashMap<>();
    /** Input calls matching this fail with a transient error (before anything is written). */
    volatile Predicate<Input> failInput = i -> false;
    /** Answer to the controller's "quiet for this long already?" probes. */
    volatile boolean quietProbe;
    /** How many watch() calls fail before one succeeds; -1 = always fail. */
    volatile int watchFailures;
    final AtomicInteger watchAttempts = new AtomicInteger();
    final List<FakeWatch> watches = new CopyOnWriteArrayList<>();

    synchronized FakeSession add(String name) {
        FakeSession s = new FakeSession(name);
        sessions.put(name, s);
        return s;
    }

    synchronized FakeSession session(String name) {
        return sessions.get(name);
    }

    @Override
    public synchronized List<Session.Info> list() {
        if (down) {
            return List.of();
        }
        List<Session.Info> infos = new ArrayList<>();
        for (FakeSession s : sessions.values()) {
            infos.add(new Session.Info(s.name, s.workdir, 120, 32, 100, Instant.EPOCH, 0, s.alive, "pty",
                    old ? Map.of() : s.labels, old ? -1 : s.outputBytes));
        }
        afterList.run();
        return infos;
    }

    @Override
    public synchronized Optional<Session.Capture> capture(String name, CaptureFormat format) {
        FakeSession s = sessions.get(name);
        if (s == null || down) {
            return Optional.empty();
        }
        if (old && format != CaptureFormat.TEXT) {
            throw unsupported("capture format " + format.wireName());
        }
        Integer failures = captureFailures.get(name);
        if (failures != null && failures > 0) {
            captureFailures.put(name, failures - 1);
            throw new SessionServerException("capture failed: java.io.IOException: Broken pipe");
        }
        String text = format == CaptureFormat.TEXT ? ScreenDiff.plain(s.text) : s.text;
        return Optional.of(new Session.Capture(text, s.alive, s.foreground, 4242, s.title, 120, 32,
                old ? -1 : s.outputBytes, true));
    }

    @Override
    public synchronized void input(String name, String text, boolean paste, boolean requireBracketedPaste,
                                   List<String> keys) {
        if (old) {
            throw unsupported("input");
        }
        if (down) {
            throw SessionServerException.noServer(name);
        }
        Input input = new Input(name, text, paste, keys == null ? null : List.copyOf(keys));
        if (failInput.test(input)) {
            throw new SessionServerException("input failed: java.io.IOException: Broken pipe");
        }
        if (!sessions.containsKey(name)) {
            throw new IllegalStateException("no such session: " + name);
        }
        if (keys != null && keys.contains("Bogus")) {
            throw new IllegalArgumentException("unknown key: Bogus");
        }
        if (requireBracketedPaste) {
            bracketedRequired.add(input);
            if (paste && text != null && !text.isEmpty() && !sessions.get(name).bracketedPaste) {
                throw new SessionServerException(Protocol.ERROR_BRACKETED_PASTE_DISABLED);
            }
        }
        inputs.add(input);
        sessions.get(name).outputBytes += 1;
    }

    @Override
    public synchronized Session.WaitResult awaitQuiet(String name, long sinceBytes, long quietMs, long timeoutMs) {
        if (old) {
            throw unsupported("wait");
        }
        FakeSession s = sessions.get(name);
        if (s == null) {
            throw new IllegalStateException("no such session: " + name);
        }
        if (sinceBytes < 0 && timeoutMs == 0) {
            return new Session.WaitResult(quietProbe, s.outputBytes, s.alive);
        }
        waits.add(new Wait(name, sinceBytes, quietMs, timeoutMs));
        return new Session.WaitResult(true, s.outputBytes, s.alive);
    }

    @Override
    public Watch watch(long quietMs, Consumer<SessionEvent> onEvent) {
        if (old) {
            watchAttempts.incrementAndGet();
            throw unsupported("watch");
        }
        int attempt = watchAttempts.incrementAndGet();
        if (watchFailures < 0 || attempt <= watchFailures) {
            throw new IllegalStateException("no session server is running");
        }
        FakeWatch watch = new FakeWatch(onEvent);
        watches.add(watch);
        return watch;
    }

    @Override
    public synchronized void sendKeys(String name, String text, boolean enter) {
        if (!sessions.containsKey(name)) {
            throw new IllegalStateException("no such session: " + name);
        }
        sentKeys.add(new SendKeys(name, text, enter));
        sessions.get(name).outputBytes += 1;
    }

    @Override
    public OptionalLong daemonPid() {
        pings.incrementAndGet();
        return pid < 0 || down ? OptionalLong.empty() : OptionalLong.of(pid);
    }

    @Override
    public boolean ping() {
        return pid >= 0 && !down;
    }

    private SessionServerException unsupported(String what) {
        unsupportedCalls.incrementAndGet();
        return SessionServerException.unsupported(what);
    }

    @Override
    public synchronized void kill(String name) {
        sessions.remove(name);
    }

    @Override
    public synchronized void label(String name, Map<String, String> changes) {
        if (old) {
            throw unsupported("label");
        }
        labelled.add(name);
        FakeSession s = sessions.get(name);
        if (s != null) {
            s.labels.putAll(changes);
        }
    }

    static final class FakeWatch implements Watch {
        final Consumer<SessionEvent> onEvent;
        volatile boolean open = true;

        FakeWatch(Consumer<SessionEvent> onEvent) {
            this.onEvent = onEvent;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
