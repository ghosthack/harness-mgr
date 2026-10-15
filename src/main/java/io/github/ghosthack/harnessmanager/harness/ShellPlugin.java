package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Confidence;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;

import java.time.Duration;
import java.util.Optional;

/**
 * The "no harness" harness: the pane runs the user's default shell and nothing else.
 * tmux's {@code new-session} already starts that shell in the pane, so there is no
 * bootstrap command to send — {@link #launchCommand()} is blank, which callers treat as
 * "skip send-keys" rather than a command line.
 *
 * <p>State detection inverts the other plugins' liveness rule: for them a shell in the
 * foreground means the harness died, but here the shell <em>is</em> the harness, so
 * {@link #ownsProcess} claims every shell {@link StateDetector} knows about. A foreground
 * program the user launched (vim, top, a build) is deliberately not claimed — it falls
 * through to the behavioral layer, which reads changing output as WORKING and long
 * quiescence as IDLE, which is as much as anyone can say about an arbitrary program.
 */
public final class ShellPlugin implements HarnessPlugin {

    @Override
    public HarnessType type() {
        return HarnessType.SHELL;
    }

    /** Blank on purpose — see the class comment. The one launch command that is not a
     * command: the pane's own shell is already running when this is consulted. */
    @Override
    public String launchCommand() {
        return "";
    }

    // resumeCommand() stays at its empty default: shells have no conversation ids. The
    // "Open…" dialog warns and falls back to a fresh (blank) launch if one is supplied.

    /** A shell's own graceful quit. In practice graceful close never consults this — a
     * shell foreground goes straight to the closer's exit stage — but it keeps the
     * contract honest for a caller that asks the plugin directly. */
    @Override
    public Optional<String> quitCommand() {
        return Optional.of("exit");
    }

    @Override
    public boolean ownsProcess(String foregroundCommand) {
        return StateDetector.isShell(foregroundCommand);
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        if (StateDetector.isShell(screen.foregroundCommand())) {
            return Optional.of(new Classification(SessionState.IDLE, Confidence.HIGH,
                    "shell sitting at its prompt", Classification.Origin.PLUGIN));
        }
        return Optional.empty();
    }
}
