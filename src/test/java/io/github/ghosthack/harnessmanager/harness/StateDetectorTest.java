package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Confidence;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;
import io.github.ghosthack.harnessmanager.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classification fixtures adapted from ../harness-stimulator's {@code StateDetectorTest},
 * collapsed onto this app's 3-state {@link SessionState} (approval/question fixtures now
 * assert {@code WAITING_FOR_INPUT} instead of the split
 * {@code WAITING_FOR_APPROVAL}/{@code WAITING_FOR_QUESTION}; the broken-pane fixtures
 * assert {@code IDLE} instead of {@code BROKEN}).
 */
final class StateDetectorTest {

    private static final Duration SHORT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(30);

    private static final HarnessPlugin NO_PATTERNS = new HarnessPlugin() {
        @Override
        public HarnessType type() {
            return HarnessType.CLAUDE_CODE;
        }

        @Override
        public String launchCommand() {
            return "null-harness";
        }

        @Override
        public String processName() {
            return "null-harness";
        }

        @Override
        public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
            return Optional.empty();
        }
    };

    private final MutableClock clock = new MutableClock();

    private Screen screen(String text) {
        return new Screen(text, false, Instant.EPOCH);
    }

    /** The daemon spawns pwsh/powershell/cmd on Windows and reports them lower-cased
     * without {@code .exe}; they must read as "at a prompt" exactly like zsh does. */
    @Test
    void windowsShellsCountAsShells() {
        for (String shell : new String[]{"pwsh", "powershell", "cmd", "zsh", "bash"}) {
            assertTrue(StateDetector.isShell(shell), shell);
        }
        assertFalse(StateDetector.isShell("node"));
        assertFalse(StateDetector.isShell("claude"));
        assertFalse(StateDetector.isShell("cmd.exe"), "the probe strips .exe before reporting");
    }

    @Test
    void changingScreenIsWorking() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        assertEquals(SessionState.WORKING, d.classify(screen("a")).state());
        clock.advance(Duration.ofSeconds(2));
        Classification c = d.classify(screen("ab"));
        assertEquals(SessionState.WORKING, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void brieflyQuiescentIsStillWorkingMediumConfidence() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        d.classify(screen("a"));
        clock.advance(Duration.ofSeconds(3));
        Classification c = d.classify(screen("a"));
        assertEquals(SessionState.WORKING, c.state());
        assertEquals(Confidence.MEDIUM, c.confidence());
    }

    @Test
    void quiescentUnmatchedIsIdleLowConfidence() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        d.classify(screen("a"));
        clock.advance(Duration.ofSeconds(10));
        Classification c = d.classify(screen("a"));
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.LOW, c.confidence());
    }

    @Test
    void longQuiescenceFallsBackToIdleHighConfidence() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        d.classify(screen("a"));
        clock.advance(Duration.ofSeconds(31));
        Classification c = d.classify(screen("a"));
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void deadPaneIsIdle() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        Classification c = d.classify(new Screen("", true, Instant.EPOCH));
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void cursorAgentApprovalPromptIsWaitingForInput() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("doing item 8\nRun this command? [y/n]");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    @Test
    void approvalWinsOverQuestionWhenPromptEndsWithQuestionMark() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("Do you want to run date > notes/item_08.txt?");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    @Test
    void cursorAgentTrailingQuestionIsWaitingForInput() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("Item 14: should notes/item_14.txt contain option A or option B?");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    @Test
    void questionDetectedAboveInputBoxAndFooterChrome() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Should notes/item_14.txt contain "option A" or "option B"?
                  → Add a follow-up                              ctrl+c to stop
                  Auto Balance · 7.2% · 1 file edited
                  /private/tmp/babysit-scratch · main""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void questionSelectorWidgetDetected() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                 │ 1. Should test.txt contain option A or option B?      │
                 │   › [ ] Option A                                       │
                 │     [ ] Option B                                       │
                 │     [ ] Other: (type to answer)                        │
                 │ ↑/↓ option · Space select · Enter next/submit · Esc to skip │
                 └────────────────────────────────────────────────────────┘""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void idleWithChromeStaysIdleWhenLastContentLineIsNotAQuestion() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Item 12 is done: created notes/item_12.txt and checked it off.
                  Waiting for further instructions.
                  → Add a follow-up
                  Auto Balance · 7.3% · 1 file edited
                  /private/tmp/babysit-scratch · main""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.IDLE, d.classify(s).state());
    }

    @Test
    void cursorAgentEmptyInputBoxIsIdleBeforeLongFallback() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("checked off item 3\n❯ ");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void cursorAgentBackgroundTasksBelowInputIsWorking() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Background subagent launched for sleep 60. Ready for further instructions.
                  → Add a follow-up
                  2 tasks
                  Auto Balance · 6.3%
                  /private/tmp/babysit-ca-bg""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(45));
        Classification c = d.classify(s);
        assertEquals(SessionState.WORKING, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void cursorAgentStaleTasksMentionAboveInputIsIdle() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  I finished 2 tasks
                  → Add a follow-up
                  Auto Balance · 6.5%
                  /private/tmp/babysit-ca-bg""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.IDLE, d.classify(s).state());
    }

    @Test
    void cursorAgentErrorSignatureFoldsIntoIdle() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("zsh: cursor-agent: command not found");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    // ------------------------------------------------------------------
    // codex. The first two fixtures are verbatim `capture-pane -p` output from
    // codex-cli 0.149.0 (macOS, tmux 3.7b, 120x40); the rest are adapted from
    // ../harness-stimulator's CodexPluginTest. See codex-grounding.md.
    // ------------------------------------------------------------------

    @Test
    void codexDirectoryTrustPromptIsWaitingForInput() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                > You are in /private/tmp/codex-scratch
                  Do you trust the contents of this directory? Working with untrusted contents comes with higher risk of prompt
                  injection. Trusting the directory allows project-local config, hooks, and exec policies to load.
                › 1. Yes, continue
                  2. No, quit
                  Press enter to continue""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    /**
     * The 0.149.0 default footer is "&lt;model&gt; &lt;effort&gt; · &lt;cwd&gt;", which
     * matches none of the plugin's named footer components — idle here rests entirely on
     * the structural "composer line above an indented footer" branch. That is the branch
     * most likely to be broken by a careless edit, so this fixture stays verbatim.
     */
    @Test
    void codexIdleComposerIsIdleEvenWhenTheFooterNamesNoKnownComponent() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                ╭────────────────────────────────────────────────────────╮
                │ >_ OpenAI Codex (v0.149.0)                             │
                │ model:       gpt-5.6-sol high   /model to change       │
                │ directory:   /private/tmp/…/codex-scratch              │
                ╰────────────────────────────────────────────────────────╯
                  Tip: NEW: Prevent sleep while running is now available in /experimental.
                • You have 1 usage limit reset available. Run /usage to use one.
                › Ask Codex to do anything
                  gpt-5.6-sol high · /private/tmp/codex-scratch""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    @Test
    void codexWorkingIndicatorIsWorkingDespiteQuiescentScreen() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("• Working (5s • esc to interrupt)");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WORKING, d.classify(s).state());
    }

    /**
     * The question widget's hint bar ends in "esc to interrupt" — the same string the
     * running-turn line carries. A bare match on it reads a pending question as a running
     * turn, and the sidebar never goes amber for a session that is waiting on the human.
     */
    @Test
    void codexQuestionWidgetIsNotMistakenForARunningTurn() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Question 1/1 (1 unanswered)
                  Which text should notes/item_14.txt contain?
                › 1. option A (Recommended)  Write the text "option A" to the file.
                  2. option B                Write the text "option B" to the file.
                  tab to add notes | enter to submit answer | esc to interrupt""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    /**
     * The auto-resolve countdown repaints every second. Without
     * {@code CodexPlugin.stripVolatile} normalizing it away the screen never reaches
     * quiescence, the plugin is never consulted, and the whole wait reads as WORKING.
     */
    @Test
    void codexAutoResolveCountdownStillReachesQuiescence() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        String frame = "  Question 1/1 (1 unanswered) · auto-resolves in %ds\n"
                + "  Which text should notes/item_14.txt contain?";
        d.classify(screen(frame.formatted(52)));
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(screen(frame.formatted(46)));
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    /**
     * Codex renders its own modals — here the rate-limit model-switch nudge — in the same
     * numbered selector it uses for permission prompts. They are consent decisions that
     * stall the session until a human clears them, so an unrecognized selector must reach
     * the "needs you" badge rather than falling through to idle.
     */
    @Test
    void codexUnrecognizedNumberedSelectorIsWaitingForInput() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Approaching rate limits — Switch to gpt-5.6-luna for lower credit usage?
                › 1. Yes, switch
                  2. Not now
                  3. Keep current model (never show again)""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertTrue(c.reason().contains("numbered selector"),
                "expected the selector branch to classify this, got: " + c.reason());
    }

    /** A stalled /goal loop looks idle but only `/goal resume` from a human restarts it. */
    @Test
    void codexStalledGoalLoopIsWaitingForInput() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Wrote notes/item_09.txt and checked it off.
                  Goal paused (4m 12s)""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    @Test
    void codexErrorSignatureFoldsIntoIdle() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("zsh: command not found: codex");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    /**
     * A coding agent's transcript is full of failures from commands it ran. Only codex's
     * own death is an error signature; "command not found" for anything else is content.
     */
    @Test
    void codexBrokenPatternIgnoresCommandFailuresInTheTranscript() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  $ codex-helper --check
                  zsh: command not found: codex-helper
                › Ask Codex to do anything
                  gpt-5.6-sol high · /private/tmp/codex-scratch""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        // The state alone cannot fail this test — a session codex crashed out of is IDLE
        // too. The reason is the only thing that distinguishes "idle at the composer"
        // from "we think the process died", so assert on it or the regression walks past.
        assertTrue(c.reason().contains("composer"),
                "expected the idle-composer branch, not the crash branch: " + c.reason());
    }

    /**
     * A turn that ends by asking the human a plain prose question — no widget, no
     * selector, just text above the composer. This is the ordinary way codex asks
     * something, since its request-user-input widget is behind a feature flag that is off
     * by default, so it is the branch that matters most.
     *
     * <p>It is also the branch that quietly dies when codex changes its status footer: if
     * the footer is not recognized as chrome it becomes the last content line, and a
     * footer never ends in "?". Hence the 0.149.0 default footer here, which matches none
     * of the plugin's footer patterns — the footer must be dropped by position for this
     * to pass.
     */
    @Test
    void codexTrailingQuestionAboveTheComposerIsWaitingForInput() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                • I can either rewrite the parser or patch it in place. Which would you prefer?
                › Ask Codex to do anything
                  gpt-5.6-sol high · /private/tmp/codex-scratch""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertTrue(c.reason().contains("trailing question"),
                "expected the trailing-question branch: " + c.reason());
    }

    /** Same question, an operator who configured `status_line = ["current-dir"]`. The
     * footer's contents are configuration, so they must not decide the answer. */
    @Test
    void codexTrailingQuestionSurvivesACustomStatusFooter() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                • Should notes/item_14.txt contain "option A" or "option B"?
                › Ask Codex to do anything
                  /private/tmp/codex-scratch""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    /**
     * Codex echoes a submitted prompt with the same "›" caret it draws selector rows
     * with, so a perfectly ordinary numbered instruction looks exactly like option 1 of a
     * consent dialog. What tells them apart is the composer: codex's modals replace that
     * region rather than drawing above it, so a visible composer means no modal — and
     * without that guard this session would sit on the "needs you" badge forever.
     */
    @Test
    void codexEchoedNumberedPromptIsNotMistakenForASelectorWidget() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                › 1. run the unit tests, 2. fix whatever fails
                • Ran 74 tests, all green. Nothing to fix.
                › Ask Codex to do anything
                  gpt-5.6-sol high · /private/tmp/codex-scratch""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.IDLE, d.classify(s).state());
    }

    // ------------------------------------------------------------------
    // shell. The liveness rule inverts for this plugin: a shell foreground is the
    // harness running, not the harness dead.
    // ------------------------------------------------------------------

    @Test
    void shellAtItsPromptIsIdleViaThePlugin() {
        StateDetector d = new StateDetector(new ShellPlugin(), clock, SHORT, LONG);
        Screen s = new Screen("tester@host project %", false, Instant.EPOCH, "zsh");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    /** For every other plugin a shell foreground short-circuits to "process died" IDLE
     * even while output is changing. The shell harness must not inherit that: a scrolling
     * command run from its prompt is WORKING. */
    @Test
    void shellWithChangingOutputIsWorkingNotDead() {
        StateDetector d = new StateDetector(new ShellPlugin(), clock, SHORT, LONG);
        d.classify(new Screen("$ make\ncc -c a.c", false, Instant.EPOCH, "zsh"));
        clock.advance(Duration.ofSeconds(2));
        Classification c = d.classify(new Screen("$ make\ncc -c a.c\ncc -c b.c", false, Instant.EPOCH, "zsh"));
        assertEquals(SessionState.WORKING, c.state());
        assertEquals(Classification.Origin.BEHAVIORAL, c.origin());
    }

    /** A quiescent foreground program (vim, a REPL) is nobody's known pattern — the
     * plugin must stay silent and let the behavioral fallback answer. */
    @Test
    void shellForegroundProgramFallsThroughToBehavioralFallback() {
        StateDetector d = new StateDetector(new ShellPlugin(), clock, SHORT, LONG);
        Screen s = new Screen("~ some file open in vim ~", false, Instant.EPOCH, "vim");
        d.classify(s);
        clock.advance(Duration.ofSeconds(45));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Classification.Origin.BEHAVIORAL, c.origin());
    }

    @Test
    void paneBackAtAShellPromptFoldsIntoIdleNotWorking() {
        // the harness exited and left a bare shell prompt: quiescent, no output changing —
        // without the liveness check this would eventually read as a generic IDLE anyway,
        // but the reason text should say the process died, not "no pattern matched"
        StateDetector d = new StateDetector(new ClaudeCodePlugin(), clock, SHORT, LONG);
        Screen s = new Screen("tester@host project %", false, Instant.EPOCH, "zsh");
        d.classify(s);
        clock.advance(Duration.ofSeconds(45));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
        assertEquals(Classification.Origin.BEHAVIORAL, c.origin());
    }

    @Test
    void livenessCheckUsesTheBinaryNameNotTheProfileName() {
        // profile "claude-code" runs a process called "claude"
        StateDetector d = new StateDetector(new ClaudeCodePlugin(), clock, SHORT, LONG);
        Screen running = new Screen("""
                ● Done. Waiting for further instructions.
                ❯
                  ⏵⏵ auto mode on (shift+tab to cycle)""", false, Instant.EPOCH, "claude");
        d.classify(running);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.IDLE, d.classify(running).state());
    }

    @Test
    void deadPaneStillWinsOverEverything() {
        StateDetector d = new StateDetector(new ClaudeCodePlugin(), clock, SHORT, LONG);
        assertEquals(SessionState.IDLE,
                d.classify(new Screen("", true, Instant.EPOCH, "zsh")).state());
    }

    @Test
    void claudeCodeEscToInterruptIsWorkingDespiteQuiescentScreen() {
        StateDetector d = new StateDetector(new ClaudeCodePlugin(), clock, SHORT, LONG);
        Screen s = screen("Running a long tool call...\n(esc to interrupt)");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WORKING, d.classify(s).state());
    }

    @Test
    void claudeCodeApprovalDialogIsWaitingForInput() {
        StateDetector d = new StateDetector(new ClaudeCodePlugin(), clock, SHORT, LONG);
        Screen s = screen("Do you want to make this edit?\n❯ 1. Yes");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    // ------------------------------------------------------------------
    // grok. Grounded directly against a live grok 1.0.5 (5115b46bc909) [stable] pane
    // (macOS, tmux 3.6a, 120x40) via tmux capture-pane, except the approval wording, which
    // is derived from the installed binary's own string table — this install's settings
    // auto-approve every tool call, so the live dialog never painted. See GrokPlugin's
    // class comment.
    // ------------------------------------------------------------------

    @Test
    void grokWorkingIndicatorIsWorkingDespiteQuiescentScreen() {
        StateDetector d = new StateDetector(new GrokPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                ⠦ Waiting for response… 1.0s
                Shift+Tab:mode  │  Esc:cancel  │  Ctrl+x:shortcuts""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WORKING, d.classify(s).state());
    }

    @Test
    void grokAllowPromptIsWaitingForInput() {
        StateDetector d = new StateDetector(new GrokPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                I'll run rm -rf tmp/scratch now.
                Allow Execute?
                ❯""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    /**
     * Grok right-pads a clock timestamp onto the same line as each chat bubble, so a naive
     * trailing-content check would see "...option B?                    6:05 PM" and miss
     * the question. {@link GrokPlugin} strips it before checking.
     */
    @Test
    void grokTrailingQuestionSurvivesTheRightAlignedTimestamp() {
        StateDetector d = new StateDetector(new GrokPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                Should notes/item_14.txt contain option A or option B?                    6:05 PM

                ╭──────────────────────────────────────────────╮
                │ ❯                                              │
                ╰──────────────────────────────────────── Grok 4.6 (high) ─╯
                Shift+Tab:mode  │  Ctrl+x:shortcuts""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertTrue(c.reason().contains("trailing question"),
                "expected the trailing-question branch: " + c.reason());
    }

    @Test
    void grokIdleComposerIsIdleBeforeLongFallback() {
        StateDetector d = new StateDetector(new GrokPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                Created probe.txt in the current directory with the text test.          6:05 PM

                Worked for 6.4s

                ╭──────────────────────────────────────────────╮
                │ ❯                                              │
                ╰──────────────────────────────────────── Grok 4.6 (high) ─╯
                Shift+Tab:mode  │  Ctrl+x:shortcuts""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    @Test
    void grokErrorSignatureFoldsIntoIdle() {
        StateDetector d = new StateDetector(new GrokPlugin(), clock, SHORT, LONG);
        Screen s = screen("zsh: command not found: grok");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    // ------------------------------------------------------------------
    // opencode. Grounded directly against a live opencode 1.18.21 pane (macOS, tmux 3.6a,
    // 120x40) via tmux capture-pane, including a real "Allow once / Allow always / Reject"
    // permission dialog (driven by a temporary project-local opencode.json setting
    // permission.bash/edit to "ask" — the default install auto-approved both). See
    // OpenCodePlugin's class comment.
    // ------------------------------------------------------------------

    @Test
    void openCodeWorkingIndicatorIsWorkingDespiteQuiescentScreen() {
        StateDetector d = new StateDetector(new OpenCodePlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                ┃
                ┃  ⠋ echo test > probe.txt
                ┃

                ▣  Build · Big Pickle

                ┃
                ┃
                ┃
                ┃  Build · Big Pickle OpenCode Zen
                ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
                 ⬝⬝⬝⬝⬝⬝■■  esc interrupt                                        8.1K (4%)  ctrl+p commands""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WORKING, d.classify(s).state());
    }

    @Test
    void openCodePermissionDialogIsWaitingForInput() {
        StateDetector d = new StateDetector(new OpenCodePlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                ┃  △ Permission required
                ┃    # Shell command
                ┃
                ┃  $ ls -la
                ┃
                ┃
                ┃   Allow once   Allow always   Reject         ctrl+f fullscreen  ⇆ select  enter confirm
                ┃""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WAITING_FOR_INPUT, d.classify(s).state());
    }

    @Test
    void openCodeTrailingQuestionIsWaitingForInput() {
        StateDetector d = new StateDetector(new OpenCodePlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                Which file would you like me to rename, and what should its new name be?

                ▣  Build · Big Pickle · 13.9s

                ┃
                ┃
                ┃
                ┃  Build · Big Pickle OpenCode Zen
                ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
                 /Users/sample/Projects/sample/harness-manager                        8.3K (4%)ctrl+p
                                                                                                 commands""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertTrue(c.reason().contains("trailing question"),
                "expected the trailing-question branch: " + c.reason());
    }

    @Test
    void openCodeIdleComposerIsIdleBeforeLongFallback() {
        StateDetector d = new StateDetector(new OpenCodePlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                The directory is empty — just . and .., no files or subdirectories.

                ▣  Build · Big Pickle · 5.4s

                ┃
                ┃
                ┃
                ┃  Build · Big Pickle OpenCode Zen
                ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
                 /Users/sample/Projects/sample/harness-manager                        8.3K (4%)ctrl+p
                                                                                                 commands""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    @Test
    void openCodeErrorSignatureFoldsIntoIdle() {
        StateDetector d = new StateDetector(new OpenCodePlugin(), clock, SHORT, LONG);
        Screen s = screen("zsh: command not found: opencode");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    // ------------------------------------------------------------------
    // pi. Grounded directly against a live pi v0.84.2 pane (macOS, tmux 3.6a, 120x40) via
    // tmux capture-pane, including a real one-time "Trust project folder?" prompt
    // (triggered by a scratch .pi/settings.json). Pi's own docs/security.md, shipped
    // alongside the installed binary, confirms there is no per-tool-call approval
    // dialog to ground. See PiPlugin's class comment.
    // ------------------------------------------------------------------

    @Test
    void piWorkingIndicatorIsWorkingDespiteQuiescentScreen() {
        StateDetector d = new StateDetector(new PiPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                ⠹ Working...

                ────────────────────────────────────────────────────────────────

                ────────────────────────────────────────────────────────────────
                /Users/sample/Projects/sample/harness-manager
                $0.005 (sub) 0.4%/500k (auto)                    (xai) grok-4.6 • medium""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(SessionState.WORKING, d.classify(s).state());
    }

    @Test
    void piTrustPromptIsWaitingForInput() {
        StateDetector d = new StateDetector(new PiPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                ────────────────────────────────────────────────────────────────

                 Trust project folder?
                 /Users/sample/Projects/sample/harness-manager

                 This allows pi to load .pi settings and resources, install missing project packages, and execute project extensions.

                 → Trust
                   Trust parent folder
                   Trust (this session only)
                   Do not trust
                   Do not trust (this session only)

                 ↑↓ navigate  enter select  escape/ctrl+c cancel""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    @Test
    void piTrailingQuestionIsWaitingForInput() {
        StateDetector d = new StateDetector(new PiPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                I can either rewrite the parser or patch it in place. Which would you prefer?

                ────────────────────────────────────────────────────────────────

                ────────────────────────────────────────────────────────────────
                /Users/sample/Projects/sample/harness-manager
                $0.018 (sub) 0.7%/500k (auto)                    (xai) grok-4.6 • medium""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.WAITING_FOR_INPUT, c.state());
        assertTrue(c.reason().contains("trailing question"),
                "expected the trailing-question branch: " + c.reason());
    }

    /** Verbatim (trimmed to a portable path) capture-pane output from a real pi bash-tool
     * turn against the live 0.84.2 pane. */
    @Test
    void piIdleComposerIsIdleBeforeLongFallback() {
        StateDetector d = new StateDetector(new PiPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                The directory is empty. I should list the files and say hi.

                Hi.

                This directory is empty — only . and .. are present.

                ────────────────────────────────────────────────────────────────

                ────────────────────────────────────────────────────────────────
                /Users/sample/Projects/sample/harness-manager
                $0.005 (sub) 0.4%/500k (auto)                    (xai) grok-4.6 • medium""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    @Test
    void piErrorSignatureFoldsIntoIdle() {
        StateDetector d = new StateDetector(new PiPlugin(), clock, SHORT, LONG);
        Screen s = screen("zsh: command not found: pi");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(SessionState.IDLE, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }
}
