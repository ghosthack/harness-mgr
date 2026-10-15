package io.github.ghosthack.harnessmanager.tmux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the escape-then-quote pipeline {@link SshExec#quoteForRemoteShell} runs for
 * every remote tmux argument: {@link RealTmux#escapeTrailingSemicolon} first, then
 * {@link io.github.ghosthack.harnessmanager.harness.HarnessPlugin#shellArgument} — in
 * that order, since quoting first would hide a trailing {@code ;} behind a closing
 * {@code '} and make the semicolon check a no-op. Live SSH itself is out of scope here
 * (see IMPLEMENTATION.md for the manual smoke check); this is the pure string pipeline.
 */
final class SshExecTest {

    @Test
    void tmuxTargetSyntaxIsAlwaysQuotedEvenThoughItLooksShellSafe() {
        // verified live: an unquoted "=name:" crashes under a remote zsh login shell
        // ("name: not found") -- zsh treats a leading '=' as its own path-expansion
        // operator, unlike HarnessPlugin.shellArgument's allow-list, which (correctly,
        // for its own different use) treats '=' as an ordinary safe character
        assertEquals("'=hm-foo:'", SshExec.quoteForRemoteShell("=hm-foo:"));
    }

    @Test
    void plainWordIsStillQuoted() {
        assertEquals("'hm-foo'", SshExec.quoteForRemoteShell("hm-foo"));
    }

    @Test
    void trailingSemicolonIsEscapedBeforeShellQuoting() {
        assertEquals("'echo hi\\;'", SshExec.quoteForRemoteShell("echo hi;"));
    }

    @Test
    void midArgumentSemicolonIsNotEscaped() {
        assertEquals("'echo a;b'", SshExec.quoteForRemoteShell("echo a;b"));
    }

    @Test
    void valueWithSpaceIsSingleQuoted() {
        assertEquals("'/Users/x/my project'", SshExec.quoteForRemoteShell("/Users/x/my project"));
    }

    @Test
    void embeddedSingleQuoteIsEscaped() {
        // the harness-launch command string already carries its own shellArgument
        // quoting (e.g. claude --resume 'abc-123') before it ever reaches here — this
        // pins that the SSH remote-shell layer re-quotes it correctly on top
        assertEquals("'claude --resume '\"'\"'abc-123'\"'\"''",
                SshExec.quoteForRemoteShell("claude --resume 'abc-123'"));
    }
}
