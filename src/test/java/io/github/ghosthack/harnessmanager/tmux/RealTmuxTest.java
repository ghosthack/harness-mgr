package io.github.ghosthack.harnessmanager.tmux;

import io.github.ghosthack.harnessmanager.host.AttachOptions;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link RealTmux}'s pure argument-building helpers, and {@link RealTmux#attach}'s
 * pre-check through the scripted {@link RealTmux.Runner} seam. The shell-out behavior
 * itself is verified by live smoke against a real tmux (see IMPLEMENTATION.md) — but
 * these details are load-bearing and invisible to the FakeSessionHost-based suite, so a
 * unit pin keeps them from being silently reverted. None of the attach tests reach the
 * PTY spawn: the probe fails first, so nothing is ever forked.
 */
final class RealTmuxTest {

    private static final AttachOptions PLAIN = new AttachOptions(false);

    /** A runner answering every process run with the same scripted result. */
    private static RealTmux.Runner always(int exitCode, String output) {
        return (cmd, timeoutSeconds, label) -> new ProcessRunner.Result(exitCode, output);
    }

    @Test
    void sessionTargetKeepsTheTrailingColon() {
        // bare "=name" is split on the last '.'/':' as a window.pane suffix by tmux;
        // the trailing colon pins the whole string as the session name
        assertEquals("=hm-foo:", RealTmux.sessionTarget("hm-foo"));
        assertEquals("=hm-app.web:", RealTmux.sessionTarget("hm-app.web"));
    }

    @Test
    void trailingSemicolonIsEscapedAndNothingElseIsTouched() {
        // an argument ending in an unescaped ';' terminates the tmux command line
        assertEquals("echo hi\\;", RealTmux.escapeTrailingSemicolon("echo hi;"));
        assertEquals("/Users/x/demo\\;", RealTmux.escapeTrailingSemicolon("/Users/x/demo;"));
        assertEquals("echo a;b", RealTmux.escapeTrailingSemicolon("echo a;b")); // mid-arg ';' is not special
        assertEquals("plain", RealTmux.escapeTrailingSemicolon("plain"));
    }

    @Test
    void remoteTransportQuotesTheResolvedTmuxPath(@TempDir Path stateDir) {
        // processPrefix elements bypass exec()'s per-argument quoting loop, so the
        // resolved tmux path has to be quoted where the prefix is built -- an
        // unquoted path containing a space would otherwise split into two words once
        // ssh rejoins this prefix with the rest of the argv
        List<String> prefix = RealTmux.remotePrefixForTesting(new SshExec(stateDir), "lan23",
                "/Users/my user/bin/tmux");
        assertEquals("'/Users/my user/bin/tmux'", prefix.get(prefix.size() - 1));
    }

    @Test
    void remoteAttachReportsSshFailureNotMissingSession(@TempDir Path stateDir) {
        // the has-session probe runs under ssh, whose own exit code is 255 when it
        // could not connect at all -- the session is untouched on the machine, so
        // calling it missing would send the user to Resume a live session
        RealTmux remote = RealTmux.remoteForTesting(new SshExec(stateDir), "lan23", "/opt/homebrew/bin/tmux",
                always(SshExec.SSH_FAILURE_EXIT, "ssh: connect to host lan23 port 22: Operation timed out\n"));
        SessionHostException e = assertThrows(SessionHostException.class,
                () -> remote.attach("hm-api", 80, 24, PLAIN));
        assertTrue(e.getMessage().contains("could not reach 'lan23' over SSH"), e.getMessage());
        assertTrue(e.getMessage().contains("Operation timed out"), e.getMessage());
        assertFalse(e.getMessage().contains("no such session"), e.getMessage());
    }

    @Test
    void remoteAttachReportsMissingSessionOnTmuxExit1(@TempDir Path stateDir) {
        // reached the machine; the remote tmux itself said no
        RealTmux remote = RealTmux.remoteForTesting(new SshExec(stateDir), "lan23", "/opt/homebrew/bin/tmux",
                always(1, "can't find session: hm-api"));
        SessionHostException e = assertThrows(SessionHostException.class,
                () -> remote.attach("hm-api", 80, 24, PLAIN));
        assertEquals("no such session: hm-api", e.getMessage());
    }

    @Test
    void localAttachReportsMissingSessionOnAnyNonZeroExit() {
        // 255 only means "ssh failed" behind the remote transport; a local tmux probe
        // has no ssh in front of it, so every non-zero exit is a missing session
        RealTmux local = RealTmux.localForTesting(always(SshExec.SSH_FAILURE_EXIT, ""));
        SessionHostException e = assertThrows(SessionHostException.class,
                () -> local.attach("x", 80, 24, PLAIN));
        assertEquals("no such session: x", e.getMessage());
    }
}
