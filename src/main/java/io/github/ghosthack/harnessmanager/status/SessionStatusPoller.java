package io.github.ghosthack.harnessmanager.status;

import io.github.ghosthack.harnessmanager.harness.HarnessPlugins;
import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.harness.StateDetector;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Confidence;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * Drives {@link StateDetector} classification for the sidebar's status badges. Each live
 * session gets its own {@code StateDetector} instance, kept across polls (in
 * {@link #detectors}) so quiescence timing accumulates correctly instead of resetting
 * every tick.
 *
 * <p>{@code backgroundExecutor} is where the blocking {@code tmux capture-pane} call
 * runs — never the EDT. {@code resultDispatcher} is where {@code onResult} is invoked;
 * production wires this to {@code SwingUtilities::invokeLater}, tests can pass a
 * same-thread executor for determinism.
 *
 * <p>Polls are serialized per session: a tick whose capture for a session is still in
 * flight skips that session instead of stacking a second task on it. This is what makes
 * the per-session {@code StateDetector} safe (exactly one task touches it at a time),
 * keeps captures classifying in the order they were taken, and bounds the pileup from a
 * slow or wedged tmux server to one task per session no matter how many ticks elapse.
 */
public final class SessionStatusPoller {

    private static final Duration DEFAULT_SHORT_QUIESCENCE = Duration.ofSeconds(5);
    private static final Duration DEFAULT_LONG_QUIESCENCE = Duration.ofSeconds(20);

    private final SessionHost host;
    private final Executor backgroundExecutor;
    private final Executor resultDispatcher;
    private final Clock clock;
    private final Duration shortQuiescence;
    private final Duration longQuiescence;
    private final CodexSessionNameResolver codexSessionNameResolver;
    private final Map<String, StateDetector> detectors = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Bumped by {@link #forget} so a poll task launched before the forget can tell its
     * session's lifecycle ended while it was capturing: such a task discards its result
     * and does not re-insert a detector seeded with the dead lifecycle's screen.
     */
    private final Map<String, Integer> generations = new ConcurrentHashMap<>();

    public SessionStatusPoller(SessionHost host, Executor backgroundExecutor, Executor resultDispatcher, Clock clock) {
        this(host, backgroundExecutor, resultDispatcher, clock, DEFAULT_SHORT_QUIESCENCE, DEFAULT_LONG_QUIESCENCE);
    }

    public SessionStatusPoller(SessionHost host, Executor backgroundExecutor, Executor resultDispatcher, Clock clock,
                                Duration shortQuiescence, Duration longQuiescence) {
        this(host, backgroundExecutor, resultDispatcher, clock, shortQuiescence, longQuiescence,
                new CodexSessionNameResolver());
    }

    SessionStatusPoller(SessionHost host, Executor backgroundExecutor, Executor resultDispatcher, Clock clock,
                        Duration shortQuiescence, Duration longQuiescence,
                        CodexSessionNameResolver codexSessionNameResolver) {
        this.host = host;
        this.backgroundExecutor = backgroundExecutor;
        this.resultDispatcher = resultDispatcher;
        this.clock = clock;
        this.shortQuiescence = shortQuiescence;
        this.longQuiescence = longQuiescence;
        this.codexSessionNameResolver = codexSessionNameResolver;
    }

    /** One poll's published result: the provisional detector classification, whether it
     * is ready to present as a status, and the best provider session title (Codex's
     * saved chat name, otherwise the pane's OSC title). The first ordinary screen is
     * not confirmed: without a previous capture, "different from nothing" is not proof
     * that a harness is working. */
    public record PollResult(Classification classification, boolean stateConfirmed, String paneTitle) {
    }

    /**
     * Kicks off one background capture+classify per session, each independently
     * dispatching its result as it completes — a slow/hung tmux call for one session
     * must not delay the others' badges. Sessions with a capture still in flight from an
     * earlier tick are skipped this round (see the class comment).
     */
    public void pollAll(List<SessionEntry> liveSessions, BiConsumer<String, PollResult> onResult) {
        for (SessionEntry entry : liveSessions) {
            String name = entry.tmuxSession();
            if (!inFlight.add(name)) {
                continue;
            }
            int generation = generationOf(name);
            Runnable task = () -> {
                try {
                    Classification classification;
                    boolean stateConfirmed;
                    Screen screen;
                    String sessionTitle;
                    try {
                        screen = host.capture(name, entry.targetPane());
                        if (generationOf(name) != generation) {
                            return; // forgotten mid-capture — stale lifecycle, drop it
                        }
                        StateDetector detector = detectors.computeIfAbsent(name, k -> new StateDetector(
                                HarnessPlugins.of(entry.harnessType()), clock, shortQuiescence, longQuiescence));
                        boolean hadBaseline = detector.hasScreenBaseline();
                        classification = detector.classify(screen);
                        stateConfirmed = classification.confidence() != Confidence.MEDIUM
                                && (hadBaseline || classification.state() != SessionState.WORKING);
                        sessionTitle = entry.harnessType() == HarnessType.CODEX
                                ? codexSessionNameResolver.resolve(screen.paneProcessId())
                                        .orElse(screen.paneTitle())
                                : screen.paneTitle();
                        if (generationOf(name) != generation) {
                            // forget() ran between the check above and here; its
                            // detectors.remove may have lost the race with our insert,
                            // so evict our own (conditionally — a successor lifecycle's
                            // fresh detector must survive) and drop the stale result.
                            detectors.remove(name, detector);
                            return;
                        }
                    } catch (SessionHostException e) {
                        // transient — the next tick retries; nothing to publish this round
                        return;
                    }
                    // Re-checked inside the dispatched task as well: every forget() call
                    // site runs on the EDT (= the production resultDispatcher), so a
                    // forget landing while this result sits in the dispatch queue is
                    // observed here — the dead lifecycle's badge is never published.
                    resultDispatcher.execute(() -> {
                        if (generationOf(name) == generation) {
                            onResult.accept(name, new PollResult(
                                    classification, stateConfirmed, sessionTitle));
                        }
                    });
                } finally {
                    inFlight.remove(name);
                }
            };
            try {
                backgroundExecutor.execute(task);
            } catch (RuntimeException rejected) {
                // e.g. RejectedExecutionException after shutdown — a stranded inFlight
                // entry would otherwise silence this session's polling forever
                inFlight.remove(name);
                throw rejected;
            }
        }
    }

    private int generationOf(String tmuxSession) {
        return generations.getOrDefault(tmuxSession, 0);
    }

    /**
     * Drops the detector for a session so its quiescence timing doesn't leak into a
     * future, unrelated tmux session of the same name (kill, remove-from-registry,
     * resume — anywhere the underlying tmux session's lifecycle actually ends). The
     * generation bump comes first so an in-flight poll task observes it before it could
     * re-insert the dead lifecycle's detector.
     */
    public void forget(String tmuxSession) {
        generations.merge(tmuxSession, 1, Integer::sum);
        detectors.remove(tmuxSession);
    }

    /** Test hook: whether a detector currently exists for {@code tmuxSession}. */
    boolean hasDetector(String tmuxSession) {
        return detectors.containsKey(tmuxSession);
    }
}
