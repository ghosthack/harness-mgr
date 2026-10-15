package io.github.ghosthack.harnessmanager.status;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.host.AttachOptions;
import io.github.ghosthack.harnessmanager.host.FakeSessionHost;
import io.github.ghosthack.harnessmanager.host.HostFeature;
import io.github.ghosthack.harnessmanager.host.SessionConnection;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Confidence;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;
import io.github.ghosthack.harnessmanager.testutil.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the poll -> publish wiring against {@link FakeSessionHost}, with both the
 * "background" and "result" executors running same-thread so results are visible
 * immediately (no EDT involved — that part is production-only wiring, not tested here).
 */
final class SessionStatusPollerTest {

    private static final Duration SHORT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(20);

    private static SessionEntry entry(String tmuxSession, HarnessType type) {
        return new SessionEntry(tmuxSession, "/Users/x/Projects/foo", type, null, Instant.EPOCH, "0.0");
    }

    @Test
    void firstSnapshotsRemainPendingInsteadOfPresentingUnknownAsWorking() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.seedLiveSession("hm-b");
        tmux.screen("hm-a", "working output");
        tmux.screen("hm-b", "more output");

        SessionStatusPoller poller = new SessionStatusPoller(tmux, Runnable::run, Runnable::run,
                new MutableClock(), SHORT, LONG);
        Map<String, SessionStatusPoller.PollResult> results = new HashMap<>();
        poller.pollAll(List.of(entry("hm-a", HarnessType.CLAUDE_CODE), entry("hm-b", HarnessType.CURSOR_AGENT)),
                results::put);

        assertEquals(2, results.size());
        assertTrue(results.containsKey("hm-a"));
        assertTrue(results.containsKey("hm-b"));
        assertEquals(SessionState.WORKING, results.get("hm-a").classification().state());
        assertFalse(results.get("hm-a").stateConfirmed());
        assertFalse(results.get("hm-b").stateConfirmed());
    }

    @Test
    void quiescenceAccumulatesAcrossPolls() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "checked off item 3\n❯ ");
        MutableClock clock = new MutableClock();

        SessionStatusPoller poller = new SessionStatusPoller(tmux, Runnable::run, Runnable::run, clock, SHORT, LONG);
        Map<String, SessionStatusPoller.PollResult> results = new HashMap<>();
        List<SessionEntry> sessions = List.of(entry("hm-a", HarnessType.CURSOR_AGENT));

        poller.pollAll(sessions, results::put);
        assertFalse(results.get("hm-a").stateConfirmed());

        clock.advance(Duration.ofSeconds(3));
        poller.pollAll(sessions, results::put);
        assertFalse(results.get("hm-a").stateConfirmed());
        assertEquals(Confidence.MEDIUM, results.get("hm-a").classification().confidence());

        clock.advance(Duration.ofSeconds(3));
        poller.pollAll(sessions, results::put);
        assertTrue(results.get("hm-a").stateConfirmed());
        assertEquals(SessionState.IDLE, results.get("hm-a").classification().state());
    }

    @Test
    void detectorStateIsIndependentPerSession() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.seedLiveSession("hm-b");
        tmux.screen("hm-a", "same text");
        tmux.screen("hm-b", "same text");
        MutableClock clock = new MutableClock();

        SessionStatusPoller poller = new SessionStatusPoller(tmux, Runnable::run, Runnable::run, clock, SHORT, LONG);
        Map<String, Classification> results = new HashMap<>();
        List<SessionEntry> sessions = List.of(
                entry("hm-a", HarnessType.CLAUDE_CODE), entry("hm-b", HarnessType.CLAUDE_CODE));

        poller.pollAll(sessions, (n, r) -> results.put(n, r.classification()));
        clock.advance(Duration.ofSeconds(6));
        // hm-b's text changes, hm-a's doesn't — they must classify independently
        tmux.screen("hm-b", "different text now");
        poller.pollAll(sessions, (n, r) -> results.put(n, r.classification()));

        assertEquals(SessionState.IDLE, results.get("hm-a").state());
        assertEquals(SessionState.WORKING, results.get("hm-b").state());
    }

    @Test
    void aFailingSessionDoesNotBlockOthersOrPublishAResultForItself() {
        SessionHost flaky = new SessionHost() {
            @Override
            public String id() {
                return "tmux";
            }

            @Override
            public boolean hasSession(String session) {
                return true;
            }

            @Override
            public List<String> listSessions() {
                return List.of("hm-good", "hm-bad");
            }

            @Override
            public void newSession(String session, Path workdir, int columns, int rows) {
            }

            @Override
            public void sendKeys(String session, String command) {
            }

            @Override
            public void killSession(String session) {
            }

            @Override
            public void setMouseMode(String session, boolean on) {
            }

            @Override
            public void setStatusBar(String session, boolean visible) {
            }

            @Override
            public void scrollPage(String session, boolean up) {
            }

            @Override
            public void jumpHistory(String session, boolean top) {
            }

            @Override
            public Path currentPath(String session) {
                return Path.of(".");
            }

            @Override
            public String currentPaneTarget(String session) {
                return "0.0";
            }

            @Override
            public Screen capture(String session, String targetPane) {
                if (session.equals("hm-bad")) {
                    throw new SessionHostException("simulated tmux failure");
                }
                return new Screen("output", false, Instant.EPOCH, "");
            }

            @Override
            public EnumSet<HostFeature> features(String session) {
                return EnumSet.noneOf(HostFeature.class);
            }

            @Override
            public SessionConnection attach(String session, int columns, int rows, AttachOptions options) {
                throw new SessionHostException("not attachable in this test");
            }
        };

        SessionStatusPoller poller = new SessionStatusPoller(flaky, Runnable::run, Runnable::run,
                new MutableClock(), SHORT, LONG);
        Map<String, Classification> results = new HashMap<>();
        poller.pollAll(List.of(entry("hm-good", HarnessType.CLAUDE_CODE), entry("hm-bad", HarnessType.CLAUDE_CODE)),
                (n, r) -> results.put(n, r.classification()));

        assertEquals(1, results.size());
        assertTrue(results.containsKey("hm-good"));
    }

    @Test
    void forgetDropsDetectorSoANewLifecycleStartsFresh() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "same text");
        MutableClock clock = new MutableClock();

        SessionStatusPoller poller = new SessionStatusPoller(tmux, Runnable::run, Runnable::run, clock, SHORT, LONG);
        Map<String, Classification> results = new HashMap<>();
        List<SessionEntry> sessions = List.of(entry("hm-a", HarnessType.CLAUDE_CODE));

        poller.pollAll(sessions, (n, r) -> results.put(n, r.classification()));
        clock.advance(Duration.ofSeconds(6));
        poller.pollAll(sessions, (n, r) -> results.put(n, r.classification()));
        assertEquals(SessionState.IDLE, results.get("hm-a").state());

        poller.forget("hm-a");
        Map<String, SessionStatusPoller.PollResult> freshResults = new HashMap<>();
        poller.pollAll(sessions, freshResults::put);
        // A fresh detector has no comparison baseline, so its provisional WORKING
        // classification must not be presented as a confirmed green state.
        assertFalse(freshResults.get("hm-a").stateConfirmed());
    }

    /** Background "executor" that queues tasks for the test to run by hand, so overlap
     * and forget/poll interleavings can be exercised deterministically. */
    private static final class ManualExecutor implements java.util.concurrent.Executor {
        final java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queued.add(task);
        }

        void runAll() {
            while (!queued.isEmpty()) {
                queued.poll().run();
            }
        }
    }

    @Test
    void pollsSerializePerSessionInsteadOfStacking() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "output");
        ManualExecutor background = new ManualExecutor();

        SessionStatusPoller poller = new SessionStatusPoller(tmux, background, Runnable::run,
                new MutableClock(), SHORT, LONG);
        Map<String, Classification> results = new HashMap<>();
        List<SessionEntry> sessions = List.of(entry("hm-a", HarnessType.CLAUDE_CODE));

        // three ticks land while the first capture is still "in flight" (unrun)
        poller.pollAll(sessions, (n, r) -> results.put(n, r.classification()));
        poller.pollAll(sessions, (n, r) -> results.put(n, r.classification()));
        poller.pollAll(sessions, (n, r) -> results.put(n, r.classification()));
        assertEquals(1, background.queued.size()); // ticks 2 and 3 skipped, not stacked

        background.runAll();
        assertEquals(1, results.size());

        // with the first task done, the next tick polls again
        poller.pollAll(sessions, (n, r) -> results.put(n, r.classification()));
        assertEquals(1, background.queued.size());
    }

    @Test
    void forgetDuringAnInFlightPollDropsTheResultAndTheStaleDetector() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "old lifecycle output");
        ManualExecutor background = new ManualExecutor();

        SessionStatusPoller poller = new SessionStatusPoller(tmux, background, Runnable::run,
                new MutableClock(), SHORT, LONG);
        Map<String, Classification> results = new HashMap<>();

        poller.pollAll(List.of(entry("hm-a", HarnessType.CLAUDE_CODE)), (n, r) -> results.put(n, r.classification()));
        poller.forget("hm-a"); // lifecycle ends while the capture task is still queued
        background.runAll();

        assertTrue(results.isEmpty(), "a poll of the ended lifecycle must not publish");
        assertTrue(!poller.hasDetector("hm-a"),
                "the ended lifecycle's detector must not be resurrected");
    }

    @Test
    void forgetWhileTheResultSitsInTheDispatchQueueDropsIt() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "old lifecycle output");
        ManualExecutor background = new ManualExecutor();
        ManualExecutor dispatcher = new ManualExecutor();

        SessionStatusPoller poller = new SessionStatusPoller(tmux, background, dispatcher,
                new MutableClock(), SHORT, LONG);
        Map<String, Classification> results = new HashMap<>();

        poller.pollAll(List.of(entry("hm-a", HarnessType.CLAUDE_CODE)), (n, r) -> results.put(n, r.classification()));
        background.runAll(); // capture+classify done; the result now sits in the dispatch queue
        poller.forget("hm-a"); // production equivalent: forget() on the EDT before the result task runs
        dispatcher.runAll();

        assertTrue(results.isEmpty(), "a result dispatched after forget() must not be published");
    }

    @Test
    void forgetBetweenCaptureAndPublishEvictsTheResurrectedDetector() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "old lifecycle output");

        // Fires forget() from inside StateDetector.classify (which reads the injected
        // clock) — i.e. after the capture-time generation check passed and after
        // computeIfAbsent re-inserted a detector, the exact window the conditional
        // self-eviction exists for.
        SessionStatusPoller[] pollerRef = new SessionStatusPoller[1];
        boolean[] fired = new boolean[1];
        Clock hookClock = new Clock() {
            private final Instant now = Instant.parse("2026-08-17T00:00:00Z");

            @Override
            public Instant instant() {
                if (!fired[0] && pollerRef[0] != null) {
                    fired[0] = true;
                    pollerRef[0].forget("hm-a");
                }
                return now;
            }

            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };

        SessionStatusPoller poller = new SessionStatusPoller(tmux, Runnable::run, Runnable::run,
                hookClock, SHORT, LONG);
        pollerRef[0] = poller;
        Map<String, Classification> results = new HashMap<>();
        poller.pollAll(List.of(entry("hm-a", HarnessType.CLAUDE_CODE)), (n, r) -> results.put(n, r.classification()));

        assertTrue(fired[0], "the hook must actually have run inside classify");
        assertTrue(results.isEmpty(), "a classification of the ended lifecycle must not be published");
        assertTrue(!poller.hasDetector("hm-a"),
                "the detector re-inserted mid-poll must be evicted, not resurrected");
    }

    @Test
    void publishesThePaneTitleAlongsideTheClassification() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "output");
        tmux.paneTitle("hm-a", "✳ refactor the poller");

        SessionStatusPoller poller = new SessionStatusPoller(tmux, Runnable::run, Runnable::run,
                new MutableClock(), SHORT, LONG);
        Map<String, SessionStatusPoller.PollResult> results = new HashMap<>();
        poller.pollAll(List.of(entry("hm-a", HarnessType.CLAUDE_CODE)), results::put);

        assertEquals("✳ refactor the poller", results.get("hm-a").paneTitle());
        assertEquals(SessionState.WORKING, results.get("hm-a").classification().state());
        assertFalse(results.get("hm-a").stateConfirmed());
    }

    @Test
    void codexSavedRenameOverridesItsGenericOscTitle(@TempDir Path codexHome) throws Exception {
        String id = "01a0299e-d978-7651-871e-e746fedd1186";
        Path rollout = codexHome.resolve("rollout-" + id + ".jsonl");
        Files.writeString(rollout, "");
        Files.writeString(codexHome.resolve("session_index.jsonl"),
                "{\"id\":\"" + id + "\",\"thread_name\":\"saved Codex name\"}\n");

        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "output");
        tmux.paneProcessId("hm-a", 42);
        tmux.paneTitle("hm-a", "⠼ project-directory");
        CodexSessionNameResolver resolver = new CodexSessionNameResolver(
                codexHome, ignored -> Optional.of(rollout));
        SessionStatusPoller poller = new SessionStatusPoller(tmux, Runnable::run, Runnable::run,
                new MutableClock(), SHORT, LONG, resolver);
        Map<String, SessionStatusPoller.PollResult> results = new HashMap<>();

        poller.pollAll(List.of(entry("hm-a", HarnessType.CODEX)), results::put);

        assertEquals("saved Codex name", results.get("hm-a").paneTitle());
    }

    @Test
    void capturesTargetTheRegisteredHarnessPaneNotTheActiveOne() {
        FakeSessionHost tmux = new FakeSessionHost("tmux");
        tmux.seedLiveSession("hm-a");
        tmux.screen("hm-a", "output");

        SessionStatusPoller poller = new SessionStatusPoller(tmux, Runnable::run, Runnable::run,
                new MutableClock(), SHORT, LONG);
        poller.pollAll(List.of(new SessionEntry("hm-a", "/Users/x/Projects/foo",
                HarnessType.CLAUDE_CODE, null, Instant.EPOCH, "0.1")), (name, c) -> {
        });

        assertEquals(List.of("hm-a:0.1"), tmux.captureCalls);
    }
}
