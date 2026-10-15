package io.github.ghosthack.harnessmanager.status;

import io.github.ghosthack.harnessmanager.harness.ClaudeCodePlugin;
import io.github.ghosthack.harnessmanager.harness.CodexPlugin;
import io.github.ghosthack.harnessmanager.harness.CursorAgentPlugin;
import io.github.ghosthack.harnessmanager.harness.HarnessPlugin;
import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.harness.ShellPlugin;
import io.github.ghosthack.harnessmanager.state.SessionState;
import io.github.ghosthack.harnessmanager.host.FakeSessionHost;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graceful-close decision tree, driven through {@link FakeSessionHost} with scripted pane
 * reactions ({@code onSendKeys}). Timeouts are tiny real durations — the timeout
 * fixtures wait them out for real, which at 50ms is cheaper than injecting a clock into
 * what is otherwise a sleep-and-poll loop.
 */
final class GracefulCloserTest {

    private static final String SESSION = "hm-proj";
    private static final String PANE = "0.0";

    private final FakeSessionHost tmux = new FakeSessionHost("tmux");
    private final GracefulCloser closer = new GracefulCloser(tmux,
            Duration.ofMillis(1), Duration.ofMillis(50), Duration.ofMillis(50));

    private GracefulCloser.Result close(HarnessPlugin plugin, SessionState lastPolled) {
        return closer.close(SESSION, PANE, plugin, lastPolled);
    }

    @Test
    void idleHarnessQuitsThenShellExitsThenSessionIsClosed() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "❯ ", "claude");
        tmux.onSendKeys = command -> {
            if (command.equals(SESSION + ": /exit")) {
                tmux.screen(SESSION, """
                        Resume this session with:
                        claude --resume fd626959-efe0-414b-a3f5-9261602748ed
                        tester@host proj %
                        """, "zsh"); // harness hands back the shell
            } else if (command.equals(SESSION + ": exit")) {
                tmux.endSession(SESSION);
            }
        };
        GracefulCloser.Result result = close(new ClaudeCodePlugin(), SessionState.IDLE);
        assertEquals(GracefulCloser.Outcome.CLOSED, result.outcome());
        assertEquals("fd626959-efe0-414b-a3f5-9261602748ed", result.harnessSessionId());
        assertEquals(List.of(SESSION + ": /exit", SESSION + ": exit"), tmux.sentCommands);
    }

    @Test
    void quitAndExitGoToTheInspectedPaneNotTheActiveOne() {
        // The harness runs in window 0; the user has since split/focused another pane.
        // Keys typed into the active pane would land in whatever runs there (vim, …).
        String harnessPane = "0.1";
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "❯ ", "claude");
        tmux.onSendKeys = command -> {
            if (command.equals(SESSION + ": /exit")) {
                tmux.screen(SESSION, "tester@host proj %", "zsh");
            } else if (command.equals(SESSION + ": exit")) {
                tmux.endSession(SESSION);
            }
        };
        GracefulCloser.Result result = closer.close(SESSION, harnessPane,
                new ClaudeCodePlugin(), SessionState.IDLE);
        assertEquals(GracefulCloser.Outcome.CLOSED, result.outcome());
        assertEquals(List.of(SESSION + ":" + harnessPane, SESSION + ":" + harnessPane),
                tmux.sentPanes);
    }

    @Test
    void codexExitReceiptSurvivesAShellExitTimeout() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "› Ask Codex to do anything", "codex");
        tmux.onSendKeys = command -> {
            if (command.equals(SESSION + ": /quit")) {
                tmux.screen(SESSION, """
                        To continue this session, run codex resume 0199f0a1-8b2c-7d3e-9f40-5a6b7c8d9e0f
                        tester@host proj %
                        """, "zsh");
            }
            // Deliberately ignore shell exit: metadata must not be tied to full closure.
        };

        GracefulCloser.Result result = close(new CodexPlugin(), SessionState.IDLE);

        assertEquals(GracefulCloser.Outcome.EXIT_TIMEOUT, result.outcome());
        assertEquals("0199f0a1-8b2c-7d3e-9f40-5a6b7c8d9e0f", result.harnessSessionId());
    }

    @Test
    void cursorAgentQuitsAndReturnsItsResumeBreadcrumb() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "> ", "cursor-agent");
        tmux.onSendKeys = command -> {
            if (command.equals(SESSION + ": /quit")) {
                tmux.screen(SESSION, """
                        To resume this session: agent --resume=bc-abc123
                        tester@host proj %
                        """, "zsh");
            } else if (command.equals(SESSION + ": exit")) {
                tmux.endSession(SESSION);
            }
        };

        GracefulCloser.Result result = close(new CursorAgentPlugin(), SessionState.IDLE);

        assertEquals(GracefulCloser.Outcome.CLOSED, result.outcome());
        assertEquals("bc-abc123", result.harnessSessionId());
        assertEquals(List.of(SESSION + ": /quit", SESSION + ": exit"), tmux.sentCommands);
    }

    /** The shell harness (and a harness that already died back to its shell) needs no
     * quit stage — a bare prompt goes straight to {@code exit}. */
    @Test
    void shellAtPromptSkipsTheQuitStage() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "tester@host proj %", "zsh");
        tmux.onSendKeys = command -> tmux.endSession(SESSION);
        GracefulCloser.Result result = close(new ShellPlugin(), null); // never-polled is fine at a prompt
        assertEquals(GracefulCloser.Outcome.CLOSED, result.outcome());
        assertEquals(List.of(SESSION + ": exit"), tmux.sentCommands);
    }

    @Test
    void workingSessionIsRefusedUntouched() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "esc to interrupt", "claude");
        GracefulCloser.Result result = close(new ClaudeCodePlugin(), SessionState.WORKING);
        assertEquals(GracefulCloser.Outcome.BUSY_STATE, result.outcome());
        assertTrue(tmux.sentCommands.isEmpty(), "a refused close must send nothing");
    }

    @Test
    void waitingForInputIsRefusedUntouched() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "Do you want to make this edit?", "claude");
        assertEquals(GracefulCloser.Outcome.BUSY_STATE,
                close(new ClaudeCodePlugin(), SessionState.WAITING_FOR_INPUT).outcome());
        assertTrue(tmux.sentCommands.isEmpty());
    }

    @Test
    void preflightReturnsKnownBusyStateWithoutSendingInput() {
        tmux.seedLiveSession(SESSION);

        GracefulCloser.Result blocker = closer.preflight(
                SESSION, PANE, new ClaudeCodePlugin(), SessionState.WORKING).orElseThrow();

        assertEquals(GracefulCloser.Outcome.BUSY_STATE, blocker.outcome());
        assertTrue(tmux.sentCommands.isEmpty());
    }

    @Test
    void preflightAllowsIdleHarnessWithoutSendingItsQuitCommand() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "❯ ", "claude");

        assertFalse(closer.preflight(
                SESSION, PANE, new ClaudeCodePlugin(), SessionState.IDLE).isPresent());
        assertTrue(tmux.sentCommands.isEmpty());
    }

    /** The caffeinate case: quiet enough to poll as IDLE forever, but killing it would
     * kill work in flight — only the fresh foreground check can catch it. */
    @Test
    void foregroundProgramIsRefusedByName() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "", "caffeinate");
        GracefulCloser.Result result = close(new ShellPlugin(), SessionState.IDLE);
        assertEquals(GracefulCloser.Outcome.BUSY_FOREGROUND, result.outcome());
        assertEquals("caffeinate", result.detail());
        assertTrue(tmux.sentCommands.isEmpty());
    }

    @Test
    void preflightReturnsForegroundBlockerWithoutSendingInput() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "", "caffeinate");

        GracefulCloser.Result blocker = closer.preflight(
                SESSION, PANE, new ShellPlugin(), SessionState.IDLE).orElseThrow();

        assertEquals(GracefulCloser.Outcome.BUSY_FOREGROUND, blocker.outcome());
        assertEquals("caffeinate", blocker.detail());
        assertTrue(tmux.sentCommands.isEmpty());
    }

    /** Never type quit text into a harness whose status has never been polled — the
     * whole point of the busy checks is not to submit into a possibly-running turn. */
    @Test
    void unpolledHarnessIsRefused() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "❯ ", "claude");
        assertEquals(GracefulCloser.Outcome.BUSY_STATE,
                close(new ClaudeCodePlugin(), null).outcome());
        assertTrue(tmux.sentCommands.isEmpty());
    }

    /** A future plugin without grounded quit syntax must still be refused rather than
     * having guessed text typed into its conversation. */
    @Test
    void harnessWithoutQuitSyntaxIsRefused() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "❯ ", "mystery-harness");
        HarnessPlugin unknown = new HarnessPlugin() {
            @Override
            public HarnessType type() {
                return HarnessType.CURSOR_AGENT;
            }

            @Override
            public String launchCommand() {
                return "mystery-harness";
            }

            @Override
            public String processName() {
                return "mystery-harness";
            }
        };
        GracefulCloser.Result result = close(unknown, SessionState.IDLE);
        assertEquals(GracefulCloser.Outcome.NO_QUIT_SYNTAX, result.outcome());
        assertTrue(tmux.sentCommands.isEmpty());
    }

    @Test
    void harnessThatIgnoresItsQuitCommandTimesOutWithoutCleanup() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "❯ ", "claude"); // never reacts to /exit
        GracefulCloser.Result result = close(new ClaudeCodePlugin(), SessionState.IDLE);
        assertEquals(GracefulCloser.Outcome.QUIT_TIMEOUT, result.outcome());
        assertEquals(List.of(SESSION + ": /exit"), tmux.sentCommands);
        assertTrue(tmux.hasSession(SESSION), "a timed-out close must leave the session alone");
    }

    @Test
    void shellThatIgnoresExitTimesOut() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "tester@host proj %", "zsh"); // never reacts to exit
        assertEquals(GracefulCloser.Outcome.EXIT_TIMEOUT,
                close(new ShellPlugin(), SessionState.IDLE).outcome());
        assertTrue(tmux.hasSession(SESSION));
    }

    @Test
    void alreadyGoneSessionJustReportsGone() {
        // never seeded: capture reports a dead pane
        assertEquals(GracefulCloser.Outcome.ALREADY_GONE,
                close(new ClaudeCodePlugin(), SessionState.IDLE).outcome());
        assertTrue(tmux.sentCommands.isEmpty());
    }

    /** A harness running as the pane's root process takes the whole session with it on
     * quit — the exit stage must notice the session is gone instead of sending
     * {@code exit} into the void (FakeSessionHost would throw; real tmux would error). */
    @Test
    void sessionCollapsingWithTheHarnessSkipsTheExitStage() {
        tmux.seedLiveSession(SESSION);
        tmux.screen(SESSION, "❯ ", "claude");
        tmux.onSendKeys = command -> tmux.endSession(SESSION); // /exit ends everything
        GracefulCloser.Result result = close(new ClaudeCodePlugin(), SessionState.IDLE);
        assertEquals(GracefulCloser.Outcome.CLOSED, result.outcome());
        assertEquals(List.of(SESSION + ": /exit"), tmux.sentCommands);
    }
}
