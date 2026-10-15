package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Confidence;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Behavioral-first state detection, adapted from ../harness-stimulator's
 * {@code StateDetector}. The core signal is screen quiescence, which survives harness
 * UI updates; the per-harness plugin's textual patterns only refine a quiescent screen.
 * Stateful: tracks the last screen across calls, so one instance belongs to exactly one
 * tmux session — {@code io.github.ghosthack.harnessmanager.status.SessionStatusPoller} keeps a
 * map of these, one per polled session, so quiescence timing survives across polls.
 */
public final class StateDetector {

    /**
     * If the pane's foreground process is one of these while the harness is supposed to
     * be running, the harness has exited and left a shell prompt behind — quiescent, but
     * not the same "idle" as a harness sitting at its own prompt. Folded into IDLE (this
     * app doesn't have a fourth badge color), with a reason that says so. The last three
     * are the Windows shells the jmux daemon spawns (its foreground
     * probe reports names lower-cased with {@code .exe} stripped).
     */
    private static final Set<String> SHELLS =
            Set.of("zsh", "bash", "sh", "fish", "dash", "ksh", "tcsh", "csh",
                    "pwsh", "powershell", "cmd");

    /** Public because it answers "is this pane at a bare prompt?" for more than state
     * detection: {@link ShellPlugin} claims shells as its own process, and graceful
     * close uses it to decide between quitting a harness and exiting a shell. */
    public static boolean isShell(String foregroundCommand) {
        return SHELLS.contains(foregroundCommand);
    }

    private final HarnessPlugin plugin;
    private final Clock clock;
    private final Duration shortQuiescence;
    private final Duration longQuiescence;

    private String lastText;
    private Instant lastChange;

    public StateDetector(HarnessPlugin plugin, Clock clock, Duration shortQuiescence, Duration longQuiescence) {
        this.plugin = plugin;
        this.clock = clock;
        this.shortQuiescence = shortQuiescence;
        this.longQuiescence = longQuiescence;
    }

    /** Whether an ordinary screen has been captured to compare the next one against. */
    public synchronized boolean hasScreenBaseline() {
        return lastText != null;
    }

    /** Synchronized: callers serialize per session (SessionStatusPoller's in-flight
     * guard), but consecutive polls run on different virtual threads — this puts the
     * lastText/lastChange handoff between them beyond doubt. Uncontended in practice. */
    public synchronized Classification classify(Screen screen) {
        if (screen.paneDead()) {
            return new Classification(SessionState.IDLE, Confidence.HIGH, "pane dead or session gone",
                    Classification.Origin.BEHAVIORAL);
        }
        String foreground = screen.foregroundCommand();
        if (!foreground.isEmpty() && !plugin.ownsProcess(foreground) && isShell(foreground)) {
            return new Classification(SessionState.IDLE, Confidence.HIGH,
                    "pane is at a " + foreground + " prompt: the " + plugin.processName()
                            + " process is not running (never started, exited, or crashed)",
                    Classification.Origin.BEHAVIORAL);
        }
        Instant now = clock.instant();
        String comparable = plugin.stripVolatile(screen.text());
        if (!comparable.equals(lastText)) {
            lastText = comparable;
            lastChange = now;
            return new Classification(SessionState.WORKING, Confidence.HIGH, "screen output changing",
                    Classification.Origin.BEHAVIORAL);
        }
        Duration quiet = Duration.between(lastChange, now);
        if (quiet.compareTo(shortQuiescence) < 0) {
            return new Classification(SessionState.WORKING, Confidence.MEDIUM,
                    "quiescent only " + quiet.toSeconds() + "s, may still be working",
                    Classification.Origin.BEHAVIORAL);
        }
        Optional<Classification> refined = plugin.classifyQuiescent(screen, quiet);
        if (refined.isPresent()) {
            return refined.get();
        }
        if (quiet.compareTo(longQuiescence) >= 0) {
            return new Classification(SessionState.IDLE, Confidence.HIGH,
                    "quiescent " + quiet.toSeconds() + "s, generic behavioral fallback",
                    Classification.Origin.BEHAVIORAL);
        }
        return new Classification(SessionState.IDLE, Confidence.LOW,
                "quiescent " + quiet.toSeconds() + "s but no pattern matched",
                Classification.Origin.BEHAVIORAL);
    }
}
