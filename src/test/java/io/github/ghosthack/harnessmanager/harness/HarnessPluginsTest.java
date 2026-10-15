package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Screen;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the wiring a new harness has to get right. The {@code switch} in
 * {@link HarnessPlugins} is exhaustive so the compiler catches a missing branch, but
 * nothing stops a branch from returning the wrong plugin, or a plugin from reporting a
 * type that disagrees with the one it was looked up by.
 */
final class HarnessPluginsTest {

    @Test
    void everyHarnessTypeResolvesToItsOwnPlugin() {
        for (HarnessType type : HarnessType.values()) {
            HarnessPlugin plugin = HarnessPlugins.of(type);
            assertNotNull(plugin, "no plugin for " + type);
            assertEquals(type, plugin.type(), "plugin registered under the wrong type");
            if (type == HarnessType.SHELL) {
                // the documented exception: blank means "the pane's shell is the
                // harness", and MainWindow skips send-keys entirely for it
                assertTrue(plugin.launchCommand().isBlank(), "shell must launch nothing");
            } else {
                assertFalse(plugin.launchCommand().isBlank(), type + " has no launch command");
            }
            assertFalse(plugin.processName().isBlank(), type + " has no process name");
        }
    }

    /** The shell harness claims every shell {@link StateDetector} knows, and only those:
     * a foreground program the user launched must fall through to the behavioral layer,
     * and a conversation id must never resolve to a guessed resume command. */
    @Test
    void shellOwnsShellsAndNothingElse() {
        HarnessPlugin shell = HarnessPlugins.of(HarnessType.SHELL);
        assertTrue(shell.ownsProcess("zsh"));
        assertTrue(shell.ownsProcess("bash"));
        assertFalse(shell.ownsProcess("vim"));
        assertFalse(shell.ownsProcess("claude"));
        assertEquals(Optional.empty(), shell.resumeCommand("abc-123"));
    }

    /** npm shims on Windows run {@code node.exe} as the shell's child, so the foreground
     * probe reports "node" for claude and codex there — and only there: on macOS/Linux
     * a bare node process in the pane is the user's own program. */
    @Test
    void claudeOwnsNodeOnlyOnWindows() {
        assertTrue(new ClaudeCodePlugin(true).ownsProcess("node"));
        assertTrue(new ClaudeCodePlugin(true).ownsProcess("claude"));
        assertTrue(new ClaudeCodePlugin(true).ownsProcess("2.1.235"));
        assertFalse(new ClaudeCodePlugin(false).ownsProcess("node"));
        assertTrue(new ClaudeCodePlugin(false).ownsProcess("claude"));
        assertTrue(new ClaudeCodePlugin(false).ownsProcess("2.1.235"));
    }

    @Test
    void codexOwnsNodeOnlyOnWindows() {
        assertTrue(new CodexPlugin(true).ownsProcess("node"));
        assertTrue(new CodexPlugin(true).ownsProcess("codex"));
        assertFalse(new CodexPlugin(false).ownsProcess("node"));
        assertTrue(new CodexPlugin(false).ownsProcess("codex"));
        assertFalse(new CodexPlugin(false).ownsProcess("vim"));
    }

    @Test
    void platformCheckIsAPrefixMatchOnTheOsName() {
        assertTrue(Platform.isWindows("Windows 11"));
        assertTrue(Platform.isWindows("windows server 2022"));
        assertFalse(Platform.isWindows("Mac OS X"));
        assertFalse(Platform.isWindows("Linux"));
    }

    @Test
    void everyHarnessTypeIdRoundTrips() {
        for (HarnessType type : HarnessType.values()) {
            assertEquals(type, HarnessType.fromId(type.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> HarnessType.fromId("no-such-harness"));
    }

    /**
     * Verified against the codex-cli 0.149.0 binary: {@code codex resume --help} documents
     * the positional as "Session id (UUID) or session name", and tmux reports
     * {@code pane_current_command} as "codex" for a pane running it — so the default
     * {@code processName()} of {@link HarnessType#id()} is correct and needs no override.
     */
    @Test
    void codexBootstrapMatchesTheRealBinary() {
        HarnessPlugin codex = HarnessPlugins.of(HarnessType.CODEX);
        assertEquals("codex", codex.launchCommand());
        assertEquals("codex", codex.processName());
        assertEquals(Optional.of("codex resume 0199f0a1-8b2c-7d3e-9f40-5a6b7c8d9e0f"),
                codex.resumeCommand("0199f0a1-8b2c-7d3e-9f40-5a6b7c8d9e0f"));
    }

    /**
     * Verified against a live grok 1.0.5 (5115b46bc909) [stable] pane: {@code grok --help}
     * documents {@code -r, --resume [<SESSION_ID_OR_TITLE>]}, tmux reports
     * {@code pane_current_command} as "grok" when the binary is invoked directly by that
     * name, and a real {@code /quit} printed back exactly this resume line.
     */
    @Test
    void grokBootstrapMatchesTheRealBinary() {
        HarnessPlugin grok = HarnessPlugins.of(HarnessType.GROK);
        assertEquals("grok", grok.launchCommand());
        assertEquals("grok", grok.processName());
        assertEquals(Optional.of("grok --resume 01a03151-c3f2-7e91-b819-be449127e073"),
                grok.resumeCommand("01a03151-c3f2-7e91-b819-be449127e073"));
        assertTrue(grok.ownsProcess("grok"), "exact binary name");
        // tmux truncates pane_current_command to 15 bytes; the launcher execs the
        // downloaded versioned binary — verified live.
        assertTrue(grok.ownsProcess("grok-1.0.5-maco"), "tmux-truncated versioned binary name");
        assertFalse(grok.ownsProcess("grokking-tool"), "unrelated binary sharing the prefix");
    }

    /**
     * Verified against a live opencode 1.18.21 pane: {@code opencode --help} documents
     * {@code -s, --session <id>} to continue a specific session, tmux reports
     * {@code pane_current_command} as "opencode" for a pane running it (the installed
     * binary is a self-contained executable, not a launcher that execs a versioned copy),
     * and resuming with the flag live replayed the full prior transcript.
     */
    @Test
    void openCodeBootstrapMatchesTheRealBinary() {
        HarnessPlugin openCode = HarnessPlugins.of(HarnessType.OPENCODE);
        assertEquals("opencode", openCode.launchCommand());
        assertEquals("opencode", openCode.processName());
        assertEquals(Optional.of("opencode --session ses_fce87a83fffelfbjovglvpMeuS"),
                openCode.resumeCommand("ses_fce87a83fffelfbjovglvpMeuS"));
        assertTrue(openCode.ownsProcess("opencode"), "exact binary name");
    }

    /**
     * Verified against a live pi v0.84.2 pane: {@code pi --help} documents
     * {@code --session <path|id>} to use a specific session file or partial UUID, tmux
     * reports {@code pane_current_command} as "node" for a pane running it (the
     * installed binary is a JS file run via a {@code #!/usr/bin/env node} shebang, so
     * node execs itself in place of the script), and resuming with the flag live
     * replayed the full prior transcript.
     */
    @Test
    void piBootstrapMatchesTheRealBinary() {
        HarnessPlugin pi = HarnessPlugins.of(HarnessType.PI);
        assertEquals("pi", pi.launchCommand());
        assertEquals("node", pi.processName());
        assertEquals(Optional.of("pi --session 01a03190-5463-719c-94f2-c8a627bc2e33"),
                pi.resumeCommand("01a03190-5463-719c-94f2-c8a627bc2e33"));
        assertTrue(pi.ownsProcess("node"), "node is the exec'd process image, verified live");
        assertFalse(pi.ownsProcess("pi"), "tmux never reports the shebang script's own name");
    }

    @Test
    void exitReceiptsRecoverProviderConversationSelectors() {
        Screen codexExit = new Screen("""
                Token usage: total=12,345
                To continue this session,
                run codex resume 0199f0a1-8b2c-7d3e-9f40-5a6b7c8d9e0f
                tester@host project %
                """, false, Instant.EPOCH, "zsh");
        assertEquals(Optional.of("0199f0a1-8b2c-7d3e-9f40-5a6b7c8d9e0f"),
                HarnessPlugins.of(HarnessType.CODEX).harnessSessionIdFromExit(codexExit));

        Screen claudeExit = new Screen("""
                Resume this session with:
                claude --resume "my analysis session"
                tester@host project %
                """, false, Instant.EPOCH, "zsh");
        HarnessPlugin claude = HarnessPlugins.of(HarnessType.CLAUDE_CODE);
        assertEquals(Optional.of("my analysis session"),
                claude.harnessSessionIdFromExit(claudeExit));
        assertEquals(Optional.of("claude --resume 'my analysis session'"),
                claude.resumeCommand("my analysis session"));

        Screen cursorExit = new Screen("""
                To resume this session: cursor-agent --resume=bc-abc123
                tester@host project %
                """, false, Instant.EPOCH, "zsh");
        HarnessPlugin cursor = HarnessPlugins.of(HarnessType.CURSOR_AGENT);
        assertEquals(Optional.of("bc-abc123"), cursor.harnessSessionIdFromExit(cursorExit));
        assertEquals(Optional.of("cursor-agent --resume=bc-abc123"),
                cursor.resumeCommand("bc-abc123"));

        // Verbatim capture-pane output from a real /quit against grok 1.0.5.
        Screen grokExit = new Screen("""
                Hi. What would you like to work on?

                Resume this session with:
                  grok --resume 01a03151-c3f2-7e91-b819-be449127e073
                tester@host project %
                """, false, Instant.EPOCH, "zsh");
        HarnessPlugin grok = HarnessPlugins.of(HarnessType.GROK);
        assertEquals(Optional.of("01a03151-c3f2-7e91-b819-be449127e073"),
                grok.harnessSessionIdFromExit(grokExit));
        assertEquals(Optional.of("grok --resume 01a03151-c3f2-7e91-b819-be449127e073"),
                grok.resumeCommand("01a03151-c3f2-7e91-b819-be449127e073"));

        // Verbatim capture-pane output from a real /exit against opencode 1.18.21.
        Screen openCodeExit = new Screen("""
                Session   New session - 2026-08-24T01:52:45.508Z
                Continue  opencode -s ses_fce87a83fffelfbjovglvpMeuS

                tester@host project %
                """, false, Instant.EPOCH, "zsh");
        HarnessPlugin openCode = HarnessPlugins.of(HarnessType.OPENCODE);
        assertEquals(Optional.of("ses_fce87a83fffelfbjovglvpMeuS"),
                openCode.harnessSessionIdFromExit(openCodeExit));
        assertEquals(Optional.of("opencode --session ses_fce87a83fffelfbjovglvpMeuS"),
                openCode.resumeCommand("ses_fce87a83fffelfbjovglvpMeuS"));

        // Verbatim capture-pane output from a real /quit against pi v0.84.2.
        Screen piExit = new Screen("""

                To resume this session: pi --session 01a03190-5463-719c-94f2-c8a627bc2e33
                tester@mac pi-grounding %
                """, false, Instant.EPOCH, "zsh");
        HarnessPlugin pi = HarnessPlugins.of(HarnessType.PI);
        assertEquals(Optional.of("01a03190-5463-719c-94f2-c8a627bc2e33"),
                pi.harnessSessionIdFromExit(piExit));
        assertEquals(Optional.of("pi --session 01a03190-5463-719c-94f2-c8a627bc2e33"),
                pi.resumeCommand("01a03190-5463-719c-94f2-c8a627bc2e33"));
    }

    @Test
    void unrelatedResumeTextIsNotTreatedAsAnExitReceipt() {
        Screen conversation = new Screen(
                "Try codex resume 0199f0a1-8b2c-7d3e-9f40-5a6b7c8d9e0f later",
                false, Instant.EPOCH, "zsh");
        assertEquals(Optional.empty(),
                HarnessPlugins.of(HarnessType.CODEX).harnessSessionIdFromExit(conversation));
        assertEquals(Optional.empty(),
                HarnessPlugins.of(HarnessType.CLAUDE_CODE).harnessSessionIdFromExit(conversation));
        assertEquals(Optional.empty(),
                HarnessPlugins.of(HarnessType.CURSOR_AGENT).harnessSessionIdFromExit(conversation));
        assertEquals(Optional.empty(),
                HarnessPlugins.of(HarnessType.GROK).harnessSessionIdFromExit(conversation));
        assertEquals(Optional.empty(),
                HarnessPlugins.of(HarnessType.OPENCODE).harnessSessionIdFromExit(conversation));
        assertEquals(Optional.empty(),
                HarnessPlugins.of(HarnessType.PI).harnessSessionIdFromExit(conversation));
    }

    /**
     * Graceful close (Cmd/Ctrl+W) types these into the harness's own prompt, so a wrong
     * value lands in a conversation as a prompt. These are grounded against their
     * corresponding CLIs rather than inferred from a shared convention.
     */
    @Test
    void quitSyntaxIsVerifiedOrAbsent() {
        assertEquals(Optional.of("/exit"), HarnessPlugins.of(HarnessType.CLAUDE_CODE).quitCommand());
        assertEquals(Optional.of("/quit"), HarnessPlugins.of(HarnessType.CODEX).quitCommand());
        assertEquals(Optional.of("/quit"), HarnessPlugins.of(HarnessType.CURSOR_AGENT).quitCommand());
        assertEquals(Optional.of("/quit"), HarnessPlugins.of(HarnessType.GROK).quitCommand());
        assertEquals(Optional.of("/exit"), HarnessPlugins.of(HarnessType.OPENCODE).quitCommand());
        assertEquals(Optional.of("/quit"), HarnessPlugins.of(HarnessType.PI).quitCommand());
        assertEquals(Optional.of("exit"), HarnessPlugins.of(HarnessType.SHELL).quitCommand());
    }
}
