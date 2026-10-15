package io.github.ghosthack.harnessmanager.tmux;

import io.github.ghosthack.harnessmanager.harness.HarnessPlugin;
import io.github.ghosthack.harnessmanager.host.SessionHostException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH plumbing shared by {@link RealTmux}'s remote {@link RealTmux.Transport} and the
 * one-off commands that aren't tmux calls at all (resolving the remote {@code tmux}
 * path, unlocking a macOS login keychain). Every invocation uses the same standard
 * options: {@code BatchMode=yes} (this app's whole premise is passwordless SSH — never
 * prompt), {@code StrictHostKeyChecking=accept-new} against a dedicated
 * {@code known_hosts} file (auto-trust an unseen host without disabling verification
 * outright, and without touching the user's real {@code ~/.ssh/known_hosts}), and
 * connection multiplexing ({@code ControlMaster}/{@code ControlPath}/
 * {@code ControlPersist}) so the frequent control calls {@link RealTmux} issues (status
 * polling, graceful-close waits) reuse one already-authenticated connection per machine
 * instead of paying a fresh SSH handshake every time.
 *
 * <p>An instance, constructed with the app's state directory (where {@code known_hosts}
 * and the control-socket directory live) and built once in {@code HarnessManagerApp},
 * so this class never has to reach into the registry package for a path.
 */
public final class SshExec {

    /** Ceiling for the one-off (non-tmux) commands below; {@link RealTmux}'s remote
     * transport carries its own, longer ceiling for tmux calls themselves. */
    private static final long EXEC_TIMEOUT_SECONDS = 20;
    private static final String CONNECT_TIMEOUT_SECONDS = "8";
    private static final String CONTROL_PERSIST = "10m";

    /** ssh's own exit status for a failure of ssh itself — could not connect, timed out,
     * authentication refused — as opposed to the remote command's status, which it
     * passes through otherwise. The one way to tell "unreachable" from "reached it and
     * the command failed" without parsing ssh's text; {@link RealTmux#attach} uses it too. */
    static final int SSH_FAILURE_EXIT = 255;

    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final Path stateDir;

    /** @param stateDir the directory holding {@code known_hosts} and the {@code ssh/}
     *                  control-socket directory — the registry file's own directory in
     *                  production, a temp dir in tests */
    public SshExec(Path stateDir) {
        this.stateDir = stateDir;
    }

    /**
     * Quotes one argument for the *one* shell layer sitting between a local process and
     * a remote command: first {@link RealTmux#escapeTrailingSemicolon} (tmux's own argv
     * parser still gets the final say once the remote shell hands it argv), then always
     * single-quotes the result — {@code ssh} re-joins its trailing command arguments
     * with a single space before the remote shell parses them, so each argument must
     * already carry its own quoting; local {@code ProcessBuilder} argv boundaries don't
     * protect anything the way they do for a purely local exec.
     *
     * <p>Deliberately does <em>not</em> reuse {@link HarnessPlugin#shellArgument}'s
     * allow-list-then-passthrough shortcut, even though both end up single-quoting the
     * same way when they do quote: that allow-list treats a leading {@code =} as safe,
     * which is correct for its own use (typing a resume id into a pane's own
     * interactive shell as literal keystrokes) but not here — verified live, an
     * unquoted tmux {@code =name:} target crashes under a remote zsh login shell
     * ({@code "name: not found"}), because zsh treats a bareword starting with
     * {@code =} as its own path-expansion operator, not just an ordinary character.
     * Always quoting sidesteps needing an allow-list that's correct for every shell a
     * registered machine might default to.
     */
    static String quoteForRemoteShell(String value) {
        String escaped = RealTmux.escapeTrailingSemicolon(value);
        return "'" + escaped.replace("'", "'\"'\"'") + "'";
    }

    /** The standard {@code -o key=value} pairs every SSH invocation this app makes uses. */
    List<String> standardOptions(String destination) {
        List<String> options = new ArrayList<>();
        options.add("-o");
        options.add("BatchMode=yes");
        // Every control exec merges stderr into stdout (RealTmux's remote Transport —
        // see its class doc) so tmux's own error text, e.g. "not in a mode", stays
        // visible the same way it is locally. Without QUIET, ssh's own connection-level
        // notices land in that same stream — most disruptively, "Warning: Permanently
        // added ... to the list of known hosts" on a machine's first-ever connection,
        // which corrupted a resolved tmux path badly enough to misdirect later argv
        // (verified live). QUIET suppresses ssh's own informational messages while
        // still surfacing fatal connection errors (auth failure, unreachable host).
        options.add("-o");
        options.add("LogLevel=QUIET");
        options.add("-o");
        options.add("StrictHostKeyChecking=accept-new");
        options.add("-o");
        options.add("UserKnownHostsFile=" + stateDir.resolve("known_hosts"));
        options.add("-o");
        options.add("ConnectTimeout=" + CONNECT_TIMEOUT_SECONDS);
        options.add("-o");
        options.add("ControlMaster=auto");
        options.add("-o");
        options.add("ControlPath=" + controlSocketPath(destination));
        options.add("-o");
        options.add("ControlPersist=" + CONTROL_PERSIST);
        return options;
    }

    /** A short, stable, per-destination socket path — unix socket paths have a small
     * length ceiling, so this is a hash, not the destination string itself. */
    private String controlSocketPath(String destination) {
        Path dir = stateDir.resolve("ssh");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // ssh will fail with a clear "can't create socket" error if this didn't work
        }
        return dir.resolve(shortHash(destination) + ".sock").toString();
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Resolves {@code tmux}'s absolute remote path via the user's login shell, since a
     * plain non-interactive {@code ssh dest 'command -v tmux'} does not see the PATH a
     * real login would (Homebrew/MacPorts/etc. are typically added by profile files a
     * non-login shell never sources — verified live). Throws with a message
     * distinguishing "couldn't reach the machine" from "reached it, but tmux isn't on
     * the login PATH". Blocks on a live SSH round trip — never call it on the EDT.
     */
    public String resolveTmuxPath(String destination) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.addAll(standardOptions(destination));
        cmd.add(destination);
        cmd.add("--");
        cmd.add("$SHELL");
        cmd.add("-lc");
        cmd.add(quoteForRemoteShell("command -v tmux"));
        Result result = run(cmd);
        if (result.exitCode() == SSH_FAILURE_EXIT) {
            throw new SessionHostException("could not reach '" + destination + "' over SSH: " + result.output());
        }
        // Defensive, on top of LogLevel=QUIET above: `command -v` prints exactly one
        // line on success, so the last non-blank line is the path even if something
        // upstream (a login-shell startup message, e.g.) put other text before it.
        String path = result.output().lines()
                .map(String::strip).filter(line -> !line.isEmpty())
                .reduce((first, last) -> last).orElse("");
        if (path.isEmpty() || path.indexOf('/') != 0) {
            throw new SessionHostException("tmux was not found on '" + destination
                    + "' (checked the login shell's PATH) — install it there and try again"
                    + (path.isEmpty() ? "" : " (got: '" + path + "')"));
        }
        return path;
    }

    /**
     * Unlocks the macOS login keychain on {@code destination} — a one-off exec, never
     * routed through a tmux pane (so it never lands in scrollback or a future
     * {@code capture-pane} read by the status poller/graceful closer). No explicit
     * keychain path is passed; {@code security unlock-keychain -p <password>} with no
     * keychain name unlocks the invoking user's default keychain.
     */
    public Result unlockKeychain(String destination, char[] password) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.addAll(standardOptions(destination));
        cmd.add(destination);
        cmd.add("--");
        cmd.add("/usr/bin/security");
        cmd.add("unlock-keychain");
        cmd.add("-p");
        cmd.add(quoteForRemoteShell(new String(password)));
        return run(cmd);
    }

    /** The ssh argv prefix (options + destination + {@code --}) for an *interactive*
     * attach — adds {@code -t} to force a remote pty, unlike every control exec above.
     * The caller appends the resolved-absolute-path tmux attach argv after this, each
     * token quoted via {@link #quoteAttachArg}. */
    List<String> attachArgvPrefix(String destination) {
        List<String> argv = new ArrayList<>();
        argv.add("ssh");
        argv.add("-t");
        argv.addAll(standardOptions(destination));
        argv.add(destination);
        argv.add("--");
        return argv;
    }

    /** Quotes one tmux attach argv token the same way every other remote tmux argument
     * is quoted — for building the tail {@link #attachArgvPrefix} is meant to precede. */
    static String quoteAttachArg(String value) {
        return quoteForRemoteShell(value);
    }

    private static Result run(List<String> cmd) {
        ProcessRunner.Result r = ProcessRunner.run(cmd, EXEC_TIMEOUT_SECONDS, "ssh");
        return new Result(r.exitCode(), r.output());
    }
}
