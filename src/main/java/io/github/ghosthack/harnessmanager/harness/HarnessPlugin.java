package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Screen;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Per-harness bootstrap commands, plus (as of step 4) the state-detection surface
 * {@link StateDetector} consults. Adapted from ../harness-stimulator's
 * {@code HarnessPlugin} — this app still doesn't babysit, so there's no stimulus/nudge
 * logic anywhere near this interface, but it does need to tell a working session from
 * an idle one for the sidebar badge.
 */
public interface HarnessPlugin {

    HarnessType type();

    /**
     * Command that starts the harness fresh, with no prior conversation. Blank means
     * there is nothing to launch — the shell tmux started in the pane is already the
     * harness ({@link ShellPlugin}) — and callers must skip send-keys rather than send
     * an empty line.
     */
    String launchCommand();

    /**
     * Command that resumes an existing harness conversation by id. Empty means this
     * harness has no verified resume syntax — callers must not guess one; ../harness-
     * stimulator leaves this unimplemented for cursor-agent for the same reason
     * (never checked against a real binary), and this app follows that precedent.
     */
    default Optional<String> resumeCommand(String harnessSessionId) {
        return Optional.empty();
    }

    /**
     * Command that starts a fresh conversation under an id the caller chose, so the id
     * (and with it the transcript and a later resume) is known from the start rather than
     * only once the harness exits. Empty means the harness can't be told its id.
     */
    default Optional<String> launchCommandWithId(String harnessSessionId) {
        return Optional.empty();
    }

    /**
     * Whether a conversation with this id exists to resume in {@code project}, for a
     * harness that can tell from local files. A conversation that was given its id at
     * launch but never got a message has nothing to resume, and resuming it fails. The
     * default, for harnesses that can't tell, is true.
     */
    default boolean canResume(String harnessSessionId, Path project) {
        return true;
    }

    /**
     * Extracts the stable conversation selector printed by the harness while it exits.
     * The graceful closer calls this on the shell screen returned by the native quit
     * command, before it sends {@code exit} to that shell and destroys the tmux pane.
     * Empty means this harness has no grounded exit-receipt format.
     */
    default Optional<String> harnessSessionIdFromExit(Screen exitScreen) {
        return Optional.empty();
    }

    /** Quotes a provider session id/name as one POSIX-shell argument. UUID-like ids stay
     * pleasantly readable; names containing spaces or punctuation are single-quoted. */
    static String shellArgument(String value) {
        if (value.matches("[A-Za-z0-9_@%+=:,./-]+")) {
            return value;
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * Text that, typed into the harness's own idle prompt and submitted, makes it exit
     * gracefully — its own quit command, not a signal. Empty means no verified quit
     * syntax: callers must refuse to close rather than guess, the same rule as
     * {@link #resumeCommand} (a guessed slash command lands in the conversation as a
     * prompt).
     */
    default Optional<String> quitCommand() {
        return Optional.empty();
    }

    /**
     * The pane's foreground process name while this harness runs, as tmux reports it in
     * {@code pane_current_command}. Defaults to {@link HarnessType#id()}; override when
     * the profile name and the binary name differ.
     */
    default String processName() {
        return type().id();
    }

    /**
     * Whether a pane whose foreground is {@code foregroundCommand} is running this
     * harness. Equality with {@link #processName()} by default; override when the
     * harness execs into something else.
     */
    default boolean ownsProcess(String foregroundCommand) {
        return processName().equals(foregroundCommand);
    }

    /**
     * Blanks regions that repaint on a timer while the harness is waiting, so that
     * screen quiescence still fires and the plugin still gets consulted. See
     * ../harness-stimulator's {@code HarnessPlugin#stripVolatile} for the motivating
     * case (a countdown that never stops changing).
     */
    default String stripVolatile(String screenText) {
        return screenText;
    }

    /**
     * {@code command} — a shell line from {@link #launchCommand} or {@link #resumeCommand}
     * — with the flags that register an HTTP MCP server named {@value #MCP_SERVER_NAME}
     * at {@code endpoint} for this one run, so an agent launched in a session can reach
     * the app's session controller. The token never appears in the returned line (it
     * would land in shell history, process argv and the screen): the flags point at
     * {@link McpEndpoint#configFile} (see {@link #mcpConfigFile}) or name the
     * {@value #MCP_TOKEN_ENV} environment variable instead. The default returns
     * {@code command} unchanged: a harness without verified per-run MCP syntax simply
     * does not get the server, the same don't-guess rule as {@link #resumeCommand}. A
     * blank command (the shell harness) is always returned as is.
     */
    default String withMcpServer(String command, McpEndpoint endpoint) {
        return command;
    }

    /**
     * The contents of {@link McpEndpoint#configFile} for a harness whose
     * {@link #withMcpServer} flags read the server (token included) from that file, or
     * empty when the harness needs no file. The launcher writes it owner-only before
     * typing the command, and launches without MCP when it cannot.
     */
    default Optional<String> mcpConfigFile(McpEndpoint endpoint) {
        return Optional.empty();
    }

    /**
     * The screen text reduced to what an agent would call "the content", for deciding
     * whether a session's screen changed between two reads and for feeding change
     * comparison: {@link #stripVolatile}, then the harness's own UI chrome (composer,
     * hint bars, spinners, positional footers) dropped line by line, then runs of blank
     * lines collapsed to one and leading/trailing blank lines removed. Accepts both plain
     * and annotated captures: a line's {@code "▶ "} selection prefix is ignored when
     * deciding whether it is chrome, and the {@code "▮"} cursor marker is removed, so a
     * moving cursor alone never reads as a change. The default drops no lines.
     */
    default String maskChrome(String screenText) {
        return ScreenMask.mask(stripVolatile(screenText), line -> false);
    }

    /**
     * Whether {@link #withMcpServer} has the harness read its bearer token from the
     * {@value #MCP_TOKEN_ENV} environment variable — only then does the launcher put the
     * token into the session's environment (and only then does a daemon that cannot set
     * an environment rule out MCP for the session). The default is false.
     */
    default boolean usesMcpTokenEnv() {
        return false;
    }

    /** The name every injected MCP server is registered under in a harness's config. */
    String MCP_SERVER_NAME = "harness-manager";

    /** The environment variable a launched local jmux session carries the MCP token in,
     * for harnesses that read a bearer token from the environment. */
    String MCP_TOKEN_ENV = "HM_MCP_TOKEN";

    /**
     * Per-harness textual pattern layer, consulted once the screen is behaviorally
     * quiescent. Empty falls back to the generic behavioral heuristics — expected
     * whenever a harness UI update outruns these patterns.
     */
    default Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        return Optional.empty();
    }
}
