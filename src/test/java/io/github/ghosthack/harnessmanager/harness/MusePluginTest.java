package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MusePluginTest {
    private final MusePlugin plugin = new MusePlugin();
    private static final String ID = "01a138c8-10c5-7d81-93a7-f964e2854b9e";

    private Screen screen(String text) {
        return new Screen(text, false, Instant.EPOCH, "muse-bin-1.4.7-");
    }

    @Test
    void bootstrapKeepsMuseDefaultsAndRecognizesTheNativeLauncher() {
        assertEquals("muse", plugin.launchCommand());
        assertEquals(Optional.of("muse resume " + ID), plugin.resumeCommand(ID));
        assertEquals(Optional.of("muse resume 'a session name'"), plugin.resumeCommand("a session name"));
        assertEquals(Optional.of("/quit"), plugin.quitCommand());
        assertTrue(plugin.ownsProcess("muse-bin-1.4.7-"));
        assertTrue(plugin.ownsProcess("muse-bin-1.4.7-R6101.1"));
        assertTrue(plugin.ownsProcess("muse"));
        assertFalse(plugin.ownsProcess("zsh"));
        assertFalse(plugin.ownsProcess("node"));
    }

    @Test
    void extractsOnlyTheGroundedExitReceipt() {
        assertEquals(Optional.of(ID), plugin.harnessSessionIdFromExit(screen(
                "◆ echo: Harness Manager echo test\n\nTo continue this session, run muse resume " + ID + "\ntester@macbookpro empty %")));
        assertEquals(Optional.empty(), plugin.harnessSessionIdFromExit(screen(
                "◆ To continue this session, run muse resume " + ID)));
        assertEquals(Optional.empty(), plugin.harnessSessionIdFromExit(screen("Muse Code 1.4.7")));
    }

    @Test
    void trustAndLoginWaitForTheUser() {
        assertEquals(SessionState.WAITING_FOR_INPUT, plugin.classifyQuiescent(screen("""
                Do you trust this workspace?
                Workspace: /Users/sample/Projects/muse-untrusted.demo01
                ⟩ 1  Trust and continue
                  2  Quit
                Use Up/Down or 1/2, then Enter. Esc quits.
                """), Duration.ofSeconds(5)).orElseThrow().state());
        assertEquals(SessionState.WAITING_FOR_INPUT, plugin.classifyQuiescent(screen("""
                Muse Code 1.4.7
                Log in with browser · Enter to choose
                Set an API key
                ↓↑ to select · Esc to quit
                """), Duration.ofSeconds(5)).orElseThrow().state());
    }

    @Test
    void recognizesRunningAndCompletedEchoTurns() {
        String chrome = "\n── Voice input (alt(option)+v to start) ─────────\n❯\n────────────────\n  echo · ~/Projects/empty · Auto-review\n";
        assertEquals(SessionState.WORKING, plugin.classifyQuiescent(screen(
                "❯ Harness Manager echo test\n◇ Thinking\n" + chrome), Duration.ofSeconds(5)).orElseThrow().state());
        assertEquals(SessionState.IDLE, plugin.classifyQuiescent(screen(
                "❯ Harness Manager echo test\n◆ echo: Harness Manager echo test\n" + chrome), Duration.ofSeconds(5)).orElseThrow().state());
        assertEquals(SessionState.WAITING_FOR_INPUT, plugin.classifyQuiescent(screen(
                "◆ Which folder should I use?\n" + chrome), Duration.ofSeconds(5)).orElseThrow().state());
    }

    @Test
    void masksComposerChromeWithoutDroppingTheConversation() {
        assertEquals("❯ test\n◆ echo: test", plugin.maskChrome("""
                ❯ test
                ◆ echo: test

                ── Voice input (alt(option)+v to start) ─────────
                ❯ ▮
                ────────────────
                  echo · ~/Projects/empty · Auto-review
                """));
    }
}
