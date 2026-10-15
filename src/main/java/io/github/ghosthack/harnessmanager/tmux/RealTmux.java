package io.github.ghosthack.harnessmanager.tmux;

import io.github.ghosthack.harnessmanager.host.AttachOptions;
import io.github.ghosthack.harnessmanager.host.HostFeature;
import io.github.ghosthack.harnessmanager.host.PtySessionConnection;
import io.github.ghosthack.harnessmanager.host.SessionConnection;
import io.github.ghosthack.harnessmanager.host.SessionHost;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.state.Screen;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tmux {@link SessionHost}: shells out to the tmux binary. Adapted from
 * ../harness-stimulator's {@code RealTmux}.
 *
 * <p>Every tmux-domain method below ({@code hasSession}, {@code capture}, ...) is shared
 * between a local instance and a remote (SSH-backed) one — only the low-level
 * {@link #exec} step differs, via the injected {@link Transport}. This means the
 * nontrivial parsing {@code capture} does (two tmux calls combined into one
 * {@code Screen}) is written and tested exactly once for both cases. {@link #attach}
 * likewise differs only in the argv it hands the PTY: the local {@code tmux} binary, or
 * {@code ssh -t ... -- <tmuxPath> ...}.
 */
public final class RealTmux implements SessionHost {

    /** The id every tmux-hosted registry row carries; the same on a remote machine. */
    public static final String ID = "tmux";

    /**
     * Ceiling on any single *local* tmux invocation. tmux commands complete in
     * milliseconds; a wait this long means the server is wedged, and an unbounded wait
     * would wedge every caller with it (the poller's virtual threads pile up, UI actions
     * never complete). {@link #remote} uses a longer ceiling — see {@link Transport}.
     */
    private static final long EXEC_TIMEOUT_SECONDS = 15;

    /** A remote call pays real network latency on top of tmux's own near-instant work,
     * so it gets headroom the local ceiling doesn't need. */
    private static final long REMOTE_EXEC_TIMEOUT_SECONDS = 20;

    /**
     * How {@link #exec} turns a tmux-domain argv (already past this class's own
     * higher-level methods) into a runnable local process. {@code processPrefix} is
     * prepended verbatim ({@code ["tmux"]}, or {@code ["ssh", ...options, destination,
     * "--", resolvedTmuxPath]}); {@code quoteForRemoteShell} additionally shell-quotes
     * each argument for the remote login shell that sits between this process and the
     * remote tmux — see {@link SshExec#quoteForRemoteShell}. {@code ssh},
     * {@code destination} and {@code tmuxPath} are null for the local transport and
     * carried for the remote one so {@link #attach} can build its interactive
     * {@code ssh -t} argv from the same resolved path.
     */
    private record Transport(List<String> processPrefix, boolean quoteForRemoteShell,
                             long execTimeoutSeconds, SshExec ssh, String destination, String tmuxPath) {
        static final Transport LOCAL = new Transport(List.of("tmux"), false, EXEC_TIMEOUT_SECONDS,
                null, null, null);

        static Transport remote(SshExec ssh, String destination, String tmuxPath) {
            List<String> prefix = new ArrayList<>();
            prefix.add("ssh");
            prefix.addAll(ssh.standardOptions(destination));
            prefix.add(destination);
            prefix.add("--");
            // Quoted like every other remote argument (SshExec.quoteForRemoteShell),
            // not appended raw: processPrefix elements bypass exec()'s own per-argument
            // quoting loop, and a resolved tmux path containing a space (an uncommon
            // but real possibility — a macOS home directory named after the account's
            // full name, say) would otherwise split into two words once ssh rejoins
            // this prefix with the rest of the argv.
            prefix.add(SshExec.quoteForRemoteShell(tmuxPath));
            return new Transport(List.copyOf(prefix), true, REMOTE_EXEC_TIMEOUT_SECONDS,
                    ssh, destination, tmuxPath);
        }

        boolean isRemote() {
            return ssh != null;
        }
    }

    /**
     * How {@link #exec} runs one local process to completion — {@link ProcessRunner#run}
     * in production. A seam only: it lets a unit test script the exit code and output
     * of a probe (an SSH failure, a tmux "can't find session") without spawning
     * anything.
     */
    @FunctionalInterface
    interface Runner {
        ProcessRunner.Result run(List<String> cmd, long timeoutSeconds, String label);
    }

    private final Transport transport;
    private final Runner runner;

    public RealTmux() {
        this(Transport.LOCAL, ProcessRunner::run);
    }

    private RealTmux(Transport transport, Runner runner) {
        this.transport = transport;
        this.runner = runner;
    }

    /**
     * A tmux host that runs every command against {@code destination} over SSH
     * instead of the local {@code tmux} binary. {@code tmuxPath} must already be the
     * remote absolute path (see {@link SshExec#resolveTmuxPath}) — a non-interactive SSH
     * command does not see the PATH a real login would.
     */
    public static RealTmux remote(SshExec ssh, String destination, String tmuxPath) {
        return new RealTmux(Transport.remote(ssh, destination, tmuxPath), ProcessRunner::run);
    }

    /** Test-only: a remote transport whose process runs are scripted by {@code runner}. */
    static RealTmux remoteForTesting(SshExec ssh, String destination, String tmuxPath, Runner runner) {
        return new RealTmux(Transport.remote(ssh, destination, tmuxPath), runner);
    }

    /** Test-only: the local transport with its process runs scripted by {@code runner}. */
    static RealTmux localForTesting(Runner runner) {
        return new RealTmux(Transport.LOCAL, runner);
    }

    /** Test-only: the exact local argv prefix a remote transport for
     * {@code destination}/{@code tmuxPath} would run, without spawning anything —
     * lets a unit test pin that {@code tmuxPath} is quoted the same way every other
     * remote argument is. */
    static List<String> remotePrefixForTesting(SshExec ssh, String destination, String tmuxPath) {
        return Transport.remote(ssh, destination, tmuxPath).processPrefix();
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * tmux has session options and a copy-mode history everywhere; harness-stimulator,
     * a separate local-only worker that attaches its own tmux client, can only take
     * over a session on the local server.
     */
    @Override
    public EnumSet<HostFeature> features(String session) {
        EnumSet<HostFeature> features = EnumSet.of(HostFeature.MOUSE_OPTION,
                HostFeature.STATUS_BAR_OPTION, HostFeature.HISTORY_PAGING);
        if (!transport.isRemote()) {
            features.add(HostFeature.STIMULATION);
        }
        return features;
    }

    /**
     * A pty4j process running {@code tmux -u [-T RGB] attach-session -t =name:} — the
     * verified spike path — or, for a remote transport, the same argv behind
     * {@code ssh -t}: the local pty's {@code TIOCSWINSZ} becomes {@code ssh -t}'s own
     * pty, and SSH's standard window-change forwarding resizes the remote tmux pane in
     * turn, so resize works unmodified. Checks the session exists first so a missing
     * one is a clean {@link SessionHostException} rather than a client that prints
     * "no sessions" and exits. Over SSH that probe also fails when ssh itself cannot
     * connect (host unreachable, VPN down, key rejected), which must not be reported as
     * a missing session — the session is untouched on the machine, and "no such
     * session" invites the user to Resume it; ssh's own exit code tells the two apart.
     */
    @Override
    public SessionConnection attach(String session, int columns, int rows, AttachOptions options) {
        Result probe = exec("has-session", "-t", sessionTarget(session));
        if (probe.exitCode() != 0) {
            if (transport.isRemote() && probe.exitCode() == SshExec.SSH_FAILURE_EXIT) {
                throw new SessionHostException("could not reach '" + transport.destination()
                        + "' over SSH: " + probe.output().strip());
            }
            throw new SessionHostException("no such session: " + session);
        }
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.putIfAbsent("LANG", "en_US.UTF-8");
        // tmux forwards 24-bit SGR only to clients whose terminal advertises RGB;
        // otherwise it quantizes to the 256 palette. xterm-256color doesn't advertise
        // it, so declare it both ways tmux accepts — the client feature flag and
        // COLORTERM (honored since 3.2; we require 3.7 anyway). JediTerm parses 38;2
        // natively. With truecolor off, COLORTERM must also be scrubbed: launched
        // from a terminal, the app may have inherited one.
        if (options.trueColor()) {
            env.put("COLORTERM", "truecolor");
        } else {
            env.remove("COLORTERM");
        }
        List<String> tmuxArgv = new ArrayList<>();
        tmuxArgv.add("-u");
        if (options.trueColor()) {
            tmuxArgv.add("-T");
            tmuxArgv.add("RGB");
        }
        tmuxArgv.add("attach-session");
        tmuxArgv.add("-t");
        tmuxArgv.add(sessionTarget(session));
        List<String> command = new ArrayList<>();
        if (transport.isRemote()) {
            command.addAll(transport.ssh().attachArgvPrefix(transport.destination()));
            command.add(SshExec.quoteAttachArg(transport.tmuxPath()));
            for (String arg : tmuxArgv) {
                command.add(SshExec.quoteAttachArg(arg));
            }
        } else {
            command.add("tmux");
            command.addAll(tmuxArgv);
        }
        try {
            return PtySessionConnection.spawn(ID, command, env, columns, rows, true);
        } catch (IOException e) {
            throw new SessionHostException("could not start a tmux client for '" + session + "': "
                    + e.getMessage(), e);
        }
    }

    @Override
    public boolean hasSession(String session) {
        return exec("has-session", "-t", sessionTarget(session)).exitCode() == 0;
    }

    @Override
    public List<String> listSessions() {
        Result r = exec("list-sessions", "-F", "#{session_name}");
        if (r.exitCode() != 0) {
            // tmux exits non-zero (with "no server running") when there is no server at
            // all yet — that is "no sessions", not a failure worth surfacing.
            return List.of();
        }
        // Names are kept verbatim: tmux allows leading/trailing whitespace in session
        // names, and a trimmed name no longer matches any -t target.
        return r.output().lines().filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void newSession(String session, Path workdir, int columns, int rows) {
        Result r = exec("new-session", "-d", "-s", session, "-c", workdir.toString(),
                "-x", String.valueOf(columns), "-y", String.valueOf(rows));
        if (r.exitCode() != 0) {
            throw new SessionHostException("tmux new-session failed: " + r.output());
        }
    }

    @Override
    public void sendKeys(String session, String command) {
        sendKeysTo(paneTarget(session), command);
    }

    @Override
    public void sendKeys(String session, String targetPane, String command) {
        // Same resolution as capture(), with the same capture-pane probe (send-keys may
        // be as lenient about a bogus window.pane suffix as display-message): the stored
        // window.pane first, the active pane only when that target no longer resolves —
        // so the keys reach the pane GracefulCloser inspected, not whichever pane the
        // user last focused.
        String target = paneTarget(session, targetPane);
        if (exec("capture-pane", "-p", "-t", target).exitCode() != 0) {
            target = paneTarget(session);
        }
        sendKeysTo(target, command);
    }

    private void sendKeysTo(String target, String command) {
        Result r = exec("send-keys", "-t", target, "-l", command);
        if (r.exitCode() != 0) {
            throw new SessionHostException("tmux send-keys failed: " + r.output());
        }
        // Mirrors ../harness-stimulator: TUIs with paste detection treat a CR arriving
        // within milliseconds of the text as a literal newline, not a submit.
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionHostException("interrupted between text and Enter", e);
        }
        Result enter = exec("send-keys", "-t", target, "Enter");
        if (enter.exitCode() != 0) {
            throw new SessionHostException("tmux send-keys Enter failed: " + enter.output());
        }
    }

    @Override
    public void killSession(String session) {
        Result r = exec("kill-session", "-t", sessionTarget(session));
        if (r.exitCode() != 0 && !isAlreadyGone(r.output())) {
            throw new SessionHostException("tmux kill-session failed: " + r.output());
        }
    }

    @Override
    public void setMouseMode(String session, boolean on) {
        Result r = exec("set-option", "-t", sessionTarget(session), "mouse", on ? "on" : "off");
        if (r.exitCode() != 0) {
            throw new SessionHostException("tmux set-option mouse failed: " + r.output());
        }
    }

    @Override
    public void setStatusBar(String session, boolean visible) {
        Result r = exec("set-option", "-t", sessionTarget(session), "status", visible ? "on" : "off");
        if (r.exitCode() != 0) {
            throw new SessionHostException("tmux set-option status failed: " + r.output());
        }
    }

    @Override
    public void scrollPage(String session, boolean up) {
        Result r = up
                ? exec("copy-mode", "-u", "-t", paneTarget(session))
                : exec("send-keys", "-X", "-t", paneTarget(session), "page-down-and-cancel");
        // Page Down at the live bottom means there is no copy mode to move. Treat that
        // as the natural boundary rather than surfacing an error for a harmless click.
        if (r.exitCode() != 0 && (up || !r.output().contains("not in a mode"))) {
            throw new SessionHostException("tmux page " + (up ? "up" : "down")
                    + " failed: " + r.output());
        }
    }

    @Override
    public void jumpHistory(String session, boolean top) {
        String target = paneTarget(session);
        if (top) {
            Result enter = exec("copy-mode", "-t", target);
            if (enter.exitCode() != 0) {
                throw new SessionHostException("tmux history top failed entering copy mode: "
                        + enter.output());
            }
            Result jump = exec("send-keys", "-X", "-t", target, "history-top");
            if (jump.exitCode() != 0) {
                throw new SessionHostException("tmux history top failed: " + jump.output());
            }
            return;
        }
        // End means the live bottom, not merely a cursor at the bottom of a frozen
        // copy-mode view. cancel exits copy mode in one step; already live is a no-op.
        Result cancel = exec("send-keys", "-X", "-t", target, "cancel");
        if (cancel.exitCode() != 0 && !cancel.output().contains("not in a mode")) {
            throw new SessionHostException("tmux history end failed: " + cancel.output());
        }
    }

    /** "Already gone" per the interface contract covers the session being missing AND the
     * whole server being down (killing the last session takes the server with it). */
    private static boolean isAlreadyGone(String killOutput) {
        String lower = killOutput.toLowerCase();
        return lower.contains("can't find session")
                || lower.contains("error connecting")
                || lower.contains("no server running");
    }

    @Override
    public Path currentPath(String session) {
        Result r = exec("display-message", "-p", "-t", paneTarget(session), "#{pane_current_path}");
        if (r.exitCode() != 0) {
            throw new SessionHostException("tmux display-message failed: " + r.output());
        }
        return Path.of(r.output().strip());
    }

    @Override
    public Screen capture(String session, String targetPane) {
        if (!hasSession(session)) {
            return new Screen("", true, Instant.now());
        }
        // If the stored window.pane target no longer resolves (window renumbered/closed,
        // or a non-zero base-index), fall back to the session's active pane rather than
        // reporting a live session dead. The probe has to be capture-pane, not
        // display-message: display-message silently tolerates a bogus window.pane
        // suffix, capture-pane rejects it — verified against tmux 3.7b.
        Screen screen = captureTarget(paneTarget(session, targetPane));
        if (screen == null) {
            screen = captureTarget(paneTarget(session));
        }
        return screen != null ? screen : new Screen("", true, Instant.now());
    }

    /** One full pane read for {@code target}; null when the target doesn't resolve. */
    private Screen captureTarget(String target) {
        Result r = exec("capture-pane", "-p", "-t", target);
        if (r.exitCode() != 0) {
            return null;
        }
        // All pane facts in one round trip, mirroring ../harness-stimulator's RealTmux.
        // pane_title carries the OSC title the program set (e.g. Claude Code's /rename);
        // its untouched default is the hostname, which tmux itself blanks out here so
        // callers never mistake it for a harness-chosen name.
        Result info = exec("display-message", "-p", "-t", target,
                "#{pane_dead}\t#{pane_current_command}\t#{pane_pid}\t"
                        + "#{?#{||:#{==:#{pane_title},#{host}},#{==:#{pane_title},#{host_short}}},,#{pane_title}}");
        String foreground = "";
        String title = "";
        long paneProcessId = -1;
        if (info.exitCode() == 0) {
            // limit 4: the first three fields never contain tabs, a title might
            String[] fields = info.output().strip().split("\t", 4);
            if (fields.length > 0 && fields[0].equals("1")) {
                return new Screen("", true, Instant.now());
            }
            if (fields.length > 1) {
                foreground = fields[1].strip();
            }
            if (fields.length > 2) {
                try {
                    paneProcessId = Long.parseLong(fields[2].strip());
                } catch (NumberFormatException ignored) {
                    // Metadata enrichment can fall back to the OSC title.
                }
            }
            if (fields.length > 3) {
                title = fields[3].strip();
            }
        }
        return new Screen(r.output(), false, Instant.now(), foreground, paneProcessId, title);
    }

    @Override
    public String currentPaneTarget(String session) {
        Result r = exec("display-message", "-p", "-t", paneTarget(session),
                "#{window_index}.#{pane_index}");
        if (r.exitCode() != 0) {
            throw new SessionHostException("tmux display-message failed: " + r.output());
        }
        return r.output().strip();
    }

    /**
     * Exact-match session target; pane-addressed commands resolve it to the current
     * window's active pane. The trailing colon is load-bearing: a bare {@code =name} is
     * split on the last {@code .} / {@code :} as a window.pane suffix, so live sessions
     * named e.g. {@code hm-app.web} fail to resolve — verified against tmux 3.7b.
     * {@code =name:} pins the whole string as the session name.
     * Package-visible so the unit suite pins the colon — the live smoke is the only other guard.
     */
    static String sessionTarget(String session) {
        return "=" + session + ":";
    }

    /** The session's current window's active pane. */
    private static String paneTarget(String session) {
        return sessionTarget(session);
    }

    /** A specific {@code window.pane} in the session, e.g. {@code =name:0.0}. */
    private static String paneTarget(String session, String targetPane) {
        return "=" + session + ":" + targetPane;
    }

    private record Result(int exitCode, String output) {
    }

    private Result exec(String... args) {
        List<String> cmd = new ArrayList<>(transport.processPrefix());
        for (String arg : args) {
            cmd.add(transport.quoteForRemoteShell()
                    ? SshExec.quoteForRemoteShell(arg) : escapeTrailingSemicolon(arg));
        }
        ProcessRunner.Result r = runner.run(cmd, transport.execTimeoutSeconds(),
                "tmux " + String.join(" ", args));
        return new Result(r.exitCode(), r.output());
    }

    /**
     * tmux's own argv parser treats an argument ending in an unescaped {@code ;} as a
     * command separator — silently truncating {@code send-keys -l "…;"} and breaking
     * {@code new-session -c "dir;"} outright. {@code \;} passes the literal through
     * (verified against tmux 3.7b). Only a trailing semicolon is special.
     * Package-visible so the unit suite pins it — the live smoke is the only other guard.
     */
    static String escapeTrailingSemicolon(String arg) {
        if (arg.endsWith(";")) {
            return arg.substring(0, arg.length() - 1) + "\\;";
        }
        return arg;
    }
}
