package io.github.ghosthack.harnessmanager.status;

import io.github.ghosthack.harnessmanager.harness.HarnessPlugin;
import io.github.ghosthack.harnessmanager.harness.StateDetector;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;

import java.time.Duration;
import java.util.Optional;

/**
 * Cmd/Ctrl+W's "close tab, but politely": ends a session by asking the processes inside
 * to quit themselves — the harness via its own quit command, then the shell underneath
 * via {@code exit} — instead of {@code kill-session}'s SIGHUP. Refuses outright when
 * anything is still in progress: an agent mid-turn or waiting on an approval, or a
 * foreground program the user started (caffeinate, vim, a build) — closing would kill
 * work in flight, which is exactly what "graceful" must never do.
 *
 * <p>Blocking (captures, quit-then-wait polling loops with real sleeps) — call from the
 * background executor, never the EDT. One-shot and stateless across calls, so a single
 * instance serves every session.
 *
 * <p>The busy checks are two layers deep because each sees what the other can't:
 * the poller's last {@link SessionState} knows a quiescent-looking harness is actually
 * mid-turn (its patterns read "esc to interrupt"), while the fresh foreground-process
 * check knows about programs the poller files under generic idle (caffeinate produces
 * no output and polls as IDLE forever). The poll state is up to one poll interval
 * stale — accepted: the window between an agent starting a turn and Cmd/Ctrl+W landing
 * inside that same interval is small, and the cost of the miss is a {@code /exit} typed
 * into a running turn's composer, not data loss.
 */
public final class GracefulCloser {

    public enum Outcome {
        /** The session ended; safe to drop its runtime UI artifacts. */
        CLOSED,
        /** The pane/session was already gone before anything was sent — same cleanup. */
        ALREADY_GONE,
        /** Refused: the harness is working or waiting on the human, or its status has
         * never been polled (an unknown harness state must not have quit text typed
         * into it blind). */
        BUSY_STATE,
        /** Refused: a foreground program that is neither the harness nor a shell is
         * still running; detail carries its process name. */
        BUSY_FOREGROUND,
        /** Refused: this harness has no verified quit syntax and one must not be
         * guessed — the caller should point at Kill session instead. */
        NO_QUIT_SYNTAX,
        /** The quit command was sent but the harness outlived the timeout. Nothing was
         * cleaned up — the session is intact, just still running. */
        QUIT_TIMEOUT,
        /** {@code exit} was sent to the shell but the tmux session outlived the
         * timeout (a stray background child keeping the shell alive, say). */
        EXIT_TIMEOUT
    }

    /** {@code detail} is the human-facing specifics: the busy foreground's name, the
     * quit command that went unanswered — whatever the outcome needs to explain itself. */
    public record Result(Outcome outcome, String detail, String harnessSessionId) {
        public Result(Outcome outcome, String detail) {
            this(outcome, detail, null);
        }

        public Optional<String> harnessSessionIdOpt() {
            return harnessSessionId == null || harnessSessionId.isBlank()
                    ? Optional.empty() : Optional.of(harnessSessionId);
        }
    }

    private record Inspection(Result terminalResult, Screen screen) {
    }

    /** A successful harness-exit wait. {@code screen} is the returned shell screen,
     * or null when exiting the harness ended the entire tmux session. */
    private record HarnessExit(Screen screen) {
    }

    private final SessionHost host;
    private final Duration pollInterval;
    private final Duration quitTimeout;
    private final Duration exitTimeout;

    public GracefulCloser(SessionHost host, Duration pollInterval, Duration quitTimeout, Duration exitTimeout) {
        this.host = host;
        this.pollInterval = pollInterval;
        this.quitTimeout = quitTimeout;
        this.exitTimeout = exitTimeout;
    }

    /**
     * Performs the read-only half of {@link #close}: returns the result that would stop
     * the close before any input is sent, or empty when it is safe to offer the user a
     * confirmation. The actual close repeats the inspection after confirmation because
     * pane state can change while the dialog is open.
     */
    public Optional<Result> preflight(String session, String targetPane,
                                      HarnessPlugin plugin, SessionState lastPolled)
            throws SessionHostException {
        return Optional.ofNullable(inspect(session, targetPane, plugin, lastPolled).terminalResult());
    }

    /**
     * @param lastPolled the poller's latest state for this session, or null if none has
     *                   landed yet — null refuses unless the pane is at a bare shell
     *                   prompt, where the foreground check alone is definitive
     */
    public Result close(String session, String targetPane, HarnessPlugin plugin, SessionState lastPolled)
            throws SessionHostException {
        Inspection inspection = inspect(session, targetPane, plugin, lastPolled);
        if (inspection.terminalResult() != null) {
            return inspection.terminalResult();
        }
        String foreground = inspection.screen().foregroundCommand();
        String harnessSessionId = StateDetector.isShell(foreground)
                ? plugin.harnessSessionIdFromExit(inspection.screen()).orElse(null) : null;
        if (!StateDetector.isShell(foreground)) {
            // inspect() proved that a verified quit command exists on this path.
            String quit = plugin.quitCommand().orElseThrow();
            host.sendKeys(session, targetPane, quit);
            HarnessExit harnessExit = awaitHarnessGone(session, targetPane);
            if (harnessExit == null) {
                return new Result(Outcome.QUIT_TIMEOUT, quit);
            }
            if (harnessExit.screen() != null) {
                harnessSessionId = plugin.harnessSessionIdFromExit(harnessExit.screen())
                        .orElse(harnessSessionId);
            }
        }
        // The pane is (now) a bare shell — or the whole session already collapsed with
        // the harness, when the harness was the pane's root process rather than a
        // shell's child (tmux new-session <cmd>-style panes).
        if (host.hasSession(session)) {
            host.sendKeys(session, targetPane, "exit");
            if (!await(() -> !host.hasSession(session), exitTimeout)) {
                return new Result(Outcome.EXIT_TIMEOUT, session, harnessSessionId);
            }
        }
        return new Result(Outcome.CLOSED, session, harnessSessionId);
    }

    /** One fresh, non-mutating safety inspection shared by pre-flight and execution. */
    private Inspection inspect(String session, String targetPane,
                               HarnessPlugin plugin, SessionState lastPolled) {
        if (lastPolled == SessionState.WORKING || lastPolled == SessionState.WAITING_FOR_INPUT) {
            return new Inspection(new Result(Outcome.BUSY_STATE,
                    "session is " + lastPolled), null);
        }
        Screen screen = host.capture(session, targetPane);
        if (screen.paneDead()) {
            return new Inspection(new Result(Outcome.ALREADY_GONE, "pane already dead"), screen);
        }
        String foreground = screen.foregroundCommand();
        if (!foreground.isEmpty() && !plugin.ownsProcess(foreground) && !StateDetector.isShell(foreground)) {
            return new Inspection(new Result(Outcome.BUSY_FOREGROUND, foreground), screen);
        }
        if (!StateDetector.isShell(foreground)) {
            // The harness itself is in the foreground (or tmux couldn't say — treated
            // the same, since typing quit text blind is the risk being managed here).
            if (lastPolled == null) {
                return new Inspection(new Result(Outcome.BUSY_STATE,
                        "status not yet polled — try again in a moment"), screen);
            }
            Optional<String> quit = plugin.quitCommand();
            if (quit.isEmpty()) {
                return new Inspection(new Result(Outcome.NO_QUIT_SYNTAX,
                        plugin.type().id()), screen);
            }
        }
        return new Inspection(null, screen);
    }

    /**
     * Waits until the harness hands the pane back to its shell, retaining that exact
     * screen so provider-specific exit metadata can be recovered before shell exit
     * destroys the tmux pane and its scrollback. Null means the timeout elapsed.
     */
    private HarnessExit awaitHarnessGone(String session, String targetPane) {
        long deadline = System.nanoTime() + quitTimeout.toNanos();
        while (true) {
            if (!host.hasSession(session)) {
                return new HarnessExit(null);
            }
            Screen screen = host.capture(session, targetPane);
            if (screen.paneDead()) {
                return new HarnessExit(null);
            }
            if (StateDetector.isShell(screen.foregroundCommand())) {
                return new HarnessExit(screen);
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            if (!pause()) {
                return null;
            }
        }
    }

    private interface Check {
        boolean ok() throws SessionHostException;
    }

    private boolean await(Check check, Duration timeout) throws SessionHostException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (check.ok()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            if (!pause()) {
                return false; // treated as a timeout: nothing is cleaned up on this path
            }
        }
    }

    private boolean pause() {
        try {
            Thread.sleep(pollInterval.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
