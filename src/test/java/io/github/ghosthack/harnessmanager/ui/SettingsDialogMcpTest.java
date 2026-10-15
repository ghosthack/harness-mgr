package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When Save re-applies the MCP server: only for an MCP change, never for unrelated ones. */
final class SettingsDialogMcpTest {

    @Test
    void unrelatedSavesLeaveTheServerAlone() {
        assertFalse(SettingsDialog.mcpChanged(true, 7391, true, 7391, false));
        assertFalse(SettingsDialog.mcpChanged(false, 7391, false, 7391, false));
        assertFalse(SettingsDialog.mcpChanged(false, 7391, false, 9000, false), "a port while off just saves");
    }

    @Test
    void anyStaleSaveRetriesAServerThatShouldRunButDoesNot() {
        assertTrue(SettingsDialog.mcpNeedsApply(false, true, false), "a failed bind is retried without a toggle");
        assertFalse(SettingsDialog.mcpNeedsApply(false, true, true), "a running server is left alone");
        assertFalse(SettingsDialog.mcpNeedsApply(false, false, false));
        assertTrue(SettingsDialog.mcpNeedsApply(true, false, true));
        assertTrue(SettingsDialog.mcpNeedsApply(true, true, true));
    }

    @Test
    void switchingAPortChangeWhileOnOrANewTokenReapplies() {
        assertTrue(SettingsDialog.mcpChanged(false, 7391, true, 7391, false));
        assertTrue(SettingsDialog.mcpChanged(true, 7391, false, 7391, false));
        assertTrue(SettingsDialog.mcpChanged(true, 7391, true, 9000, false));
        assertTrue(SettingsDialog.mcpChanged(true, 7391, true, 7391, true));
        assertTrue(SettingsDialog.mcpChanged(false, 7391, false, 7391, true));
    }
}
