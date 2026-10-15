package io.github.ghosthack.harnessmanager.settings;

import io.github.ghosthack.harnessmanager.registry.RegistryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the parsing of the terminal/tmux settings keys. Settings live beside the resolved
 * registry file, so pointing the registry override at a temp dir isolates each test from
 * the developer's real {@code ~/.harness-manager/settings.properties}.
 */
final class AppSettingsTest {

    @TempDir
    Path dir;

    private String previousOverride;

    @BeforeEach
    void pointRegistryAtTempDir() {
        previousOverride = System.getProperty(RegistryPaths.OVERRIDE_PROPERTY);
        System.setProperty(RegistryPaths.OVERRIDE_PROPERTY,
                dir.resolve("sessions.json").toString());
    }

    @AfterEach
    void restoreOverride() {
        if (previousOverride == null) {
            System.clearProperty(RegistryPaths.OVERRIDE_PROPERTY);
        } else {
            System.setProperty(RegistryPaths.OVERRIDE_PROPERTY, previousOverride);
        }
    }

    private void settings(String content) throws IOException {
        Files.writeString(dir.resolve("settings.properties"), content);
    }

    @Test
    void concurrentSavesOfDifferentKeysNeitherLoseAChangeNorLeaveTempFiles() throws Exception {
        for (int round = 0; round < 50; round++) {
            settings("");
            boolean on = round % 2 == 0;
            Thread labels = new Thread(() -> AppSettings.saveToolbarLabels(on));
            Thread pages = new Thread(() -> AppSettings.saveToolbarPageButtons(on));
            labels.start();
            pages.start();
            labels.join();
            pages.join();
            assertEquals(on, AppSettings.toolbarLabels(), "round " + round);
            assertEquals(on, AppSettings.toolbarPageButtons(), "round " + round);
        }
        try (var siblings = Files.list(dir)) {
            assertTrue(siblings.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void scrollbarDefaultsToVisible() throws IOException {
        assertFalse(AppSettings.terminalScrollbarHidden()); // no file at all
        settings("");
        assertFalse(AppSettings.terminalScrollbarHidden());
        settings("terminal.scrollbar=visible\n");
        assertFalse(AppSettings.terminalScrollbarHidden());
        settings("terminal.scrollbar=nonsense\n");
        assertFalse(AppSettings.terminalScrollbarHidden());
    }

    @Test
    void scrollbarHiddenIsRecognizedCaseInsensitively() throws IOException {
        settings("terminal.scrollbar=hidden\n");
        assertTrue(AppSettings.terminalScrollbarHidden());
        settings("terminal.scrollbar= HIDDEN \n");
        assertTrue(AppSettings.terminalScrollbarHidden());
    }

    @Test
    void tmuxMouseDefaultsToLeavingTmuxAlone() throws IOException {
        assertEquals(Optional.empty(), AppSettings.tmuxMouse()); // no file at all
        settings("");
        assertEquals(Optional.empty(), AppSettings.tmuxMouse());
        settings("tmux.mouse=nonsense\n");
        assertEquals(Optional.empty(), AppSettings.tmuxMouse());
    }

    @Test
    void tmuxMousePinParsesOnAndOff() throws IOException {
        settings("tmux.mouse=on\n");
        assertEquals(Optional.of(true), AppSettings.tmuxMouse());
        settings("tmux.mouse= OFF \n");
        assertEquals(Optional.of(false), AppSettings.tmuxMouse());
    }

    @Test
    void codexMouseWheelDefaultsToDisabledAndOnlyOnEnables() throws IOException {
        assertTrue(AppSettings.codexMouseWheelDisabled()); // no file at all
        settings("");
        assertTrue(AppSettings.codexMouseWheelDisabled());
        settings("codex.mouse-wheel=nonsense\n");
        assertTrue(AppSettings.codexMouseWheelDisabled());
        settings("codex.mouse-wheel= ON \n");
        assertFalse(AppSettings.codexMouseWheelDisabled());
        settings("codex.mouse-wheel=off\n");
        assertTrue(AppSettings.codexMouseWheelDisabled());
    }

    @Test
    void paletteDefaultsToMacosTerminal() throws IOException {
        assertEquals(AppSettings.TerminalPalette.MACOS_TERMINAL, AppSettings.terminalPalette()); // no file
        settings("");
        assertEquals(AppSettings.TerminalPalette.MACOS_TERMINAL, AppSettings.terminalPalette());
        settings("terminal.palette=nonsense\n");
        assertEquals(AppSettings.TerminalPalette.MACOS_TERMINAL, AppSettings.terminalPalette());
    }

    @Test
    void paletteParsesEachChoiceCaseInsensitively() throws IOException {
        settings("terminal.palette=macos\n");
        assertEquals(AppSettings.TerminalPalette.MACOS_TERMINAL, AppSettings.terminalPalette());
        settings("terminal.palette= ITERM2 \n");
        assertEquals(AppSettings.TerminalPalette.ITERM2, AppSettings.terminalPalette());
        settings("terminal.palette=jediterm\n");
        assertEquals(AppSettings.TerminalPalette.JEDITERM, AppSettings.terminalPalette());
    }

    @Test
    void trueColorDefaultsToOnAndOnlyOffDisables() throws IOException {
        assertTrue(AppSettings.terminalTrueColor()); // no file at all
        settings("");
        assertTrue(AppSettings.terminalTrueColor());
        settings("terminal.truecolor=nonsense\n");
        assertTrue(AppSettings.terminalTrueColor());
        settings("terminal.truecolor= OFF \n");
        assertFalse(AppSettings.terminalTrueColor());
        settings("terminal.truecolor=on\n");
        assertTrue(AppSettings.terminalTrueColor());
    }

    @Test
    void terminalFontDefaultsAndParsingAreBounded() throws IOException {
        assertEquals(14, AppSettings.terminalFontSize());
        settings("terminal.font-family= JetBrains Mono \nterminal.font-size=20\n");
        assertEquals("JetBrains Mono", AppSettings.terminalFontFamily());
        assertEquals(20, AppSettings.terminalFontSize());

        settings("terminal.font-family=  \nterminal.font-size=999\n");
        assertFalse(AppSettings.terminalFontFamily().isBlank());
        assertEquals(AppSettings.MAX_TERMINAL_FONT_SIZE, AppSettings.terminalFontSize());

        settings("terminal.font-size=not-a-number\n");
        assertEquals(AppSettings.DEFAULT_TERMINAL_FONT_SIZE, AppSettings.terminalFontSize());
    }

    @Test
    void tmuxPageKeysDefaultToOnAndOnlyOffDisables() throws IOException {
        assertTrue(AppSettings.tmuxPageKeys()); // no file at all
        settings("");
        assertTrue(AppSettings.tmuxPageKeys());
        settings("tmux.page-keys=nonsense\n");
        assertTrue(AppSettings.tmuxPageKeys());
        settings("tmux.page-keys= OFF \n");
        assertFalse(AppSettings.tmuxPageKeys());
        settings("tmux.page-keys=on\n");
        assertTrue(AppSettings.tmuxPageKeys());
    }

    @Test
    void toolbarPageButtonsDefaultToOffAndOnlyOnEnables() throws IOException {
        assertFalse(AppSettings.toolbarPageButtons()); // no file at all
        settings("");
        assertFalse(AppSettings.toolbarPageButtons());
        settings("toolbar.page-buttons=nonsense\n");
        assertFalse(AppSettings.toolbarPageButtons());
        settings("toolbar.page-buttons= ON \n");
        assertTrue(AppSettings.toolbarPageButtons());
        settings("toolbar.page-buttons=off\n");
        assertFalse(AppSettings.toolbarPageButtons());
    }

    @Test
    void toolbarStopLadderDefaultsToOffAndOnlyOnEnablesIt() throws IOException {
        assertFalse(AppSettings.toolbarStopLadder()); // no file at all
        settings("");
        assertFalse(AppSettings.toolbarStopLadder());
        settings("toolbar.stop-ladder=nonsense\n");
        assertFalse(AppSettings.toolbarStopLadder());
        settings("toolbar.stop-ladder= ON \n");
        assertTrue(AppSettings.toolbarStopLadder());
        settings("toolbar.stop-ladder=off\n");
        assertFalse(AppSettings.toolbarStopLadder());
    }

    @Test
    void sidebarFocusBorderDefaultsToHiddenAndOnlyVisibleEnablesIt() throws IOException {
        assertTrue(AppSettings.sidebarFocusBorderHidden()); // no file at all
        settings("");
        assertTrue(AppSettings.sidebarFocusBorderHidden());
        settings("sidebar.focus-border=nonsense\n");
        assertTrue(AppSettings.sidebarFocusBorderHidden());
        settings("sidebar.focus-border= VISIBLE \n");
        assertFalse(AppSettings.sidebarFocusBorderHidden());
        settings("sidebar.focus-border=hidden\n");
        assertTrue(AppSettings.sidebarFocusBorderHidden());
    }

    @Test
    void sidebarReticuleDefaultsToOffAndOnlyOnEnablesIt() throws IOException {
        assertFalse(AppSettings.sidebarReticule()); // no file at all
        settings("");
        assertFalse(AppSettings.sidebarReticule());
        settings("sidebar.reticule=nonsense\n");
        assertFalse(AppSettings.sidebarReticule());
        settings("sidebar.reticule= ON \n");
        assertTrue(AppSettings.sidebarReticule());
        settings("sidebar.reticule=off\n");
        assertFalse(AppSettings.sidebarReticule());
    }

    @Test
    void sidebarFolderGroupsDefaultToOnAndOnlyOffDisablesThem() throws IOException {
        assertTrue(AppSettings.sidebarFolderGroups()); // no file at all
        settings("");
        assertTrue(AppSettings.sidebarFolderGroups());
        settings("sidebar.folder-groups=nonsense\n");
        assertTrue(AppSettings.sidebarFolderGroups());
        settings("sidebar.folder-groups= OFF \n");
        assertFalse(AppSettings.sidebarFolderGroups());
        settings("sidebar.folder-groups=on\n");
        assertTrue(AppSettings.sidebarFolderGroups());
    }

    @Test
    void sidebarViewDefaultsToSessionSortWithNothingHidden() throws IOException {
        AppSettings.SidebarViewSettings defaults = AppSettings.sidebarViewSettings();
        assertEquals("session", defaults.sort());
        assertFalse(defaults.descending());
        assertEquals(Set.of(), defaults.hiddenStatuses());
        assertEquals(Set.of(), defaults.hiddenHarnessFamilies());

        settings("sidebar.sort= folder \n"
                + "sidebar.sort-direction= descending \n"
                + "sidebar.hidden-statuses= working, dead,working \n"
                + "sidebar.hidden-harnesses= CODEX, shell \n");
        AppSettings.SidebarViewSettings configured = AppSettings.sidebarViewSettings();
        assertEquals("folder", configured.sort());
        assertTrue(configured.descending());
        assertEquals(Set.of("working", "dead"), configured.hiddenStatuses());
        assertEquals(Set.of("codex", "shell"), configured.hiddenHarnessFamilies());
    }

    @Test
    void savesRoundTripAndEmptyPinsClearTheirKeys() {
        AppSettings.saveTerminalScrollbarHidden(true);
        AppSettings.saveTmuxMouse(Optional.of(true));
        AppSettings.saveCodexMouseWheelDisabled(false);
        AppSettings.saveTerminalBackgroundOverride(Optional.of(AppSettings.TerminalBackground.WHITE));
        AppSettings.saveTerminalPalette(AppSettings.TerminalPalette.ITERM2);
        AppSettings.saveTerminalTrueColor(false);
        AppSettings.saveTmuxPageKeys(false);
        AppSettings.saveToolbarPageButtons(true);
        AppSettings.saveToolbarStopLadder(true);
        AppSettings.saveSidebarFocusBorderHidden(false);
        AppSettings.saveSidebarReticule(true);
        AppSettings.saveSidebarFolderGroups(false);
        AppSettings.saveTerminalFont("JetBrains Mono", 18);
        AppSettings.saveSidebarViewSettings("status", true, Set.of("idle", "working"),
                Set.of("claude", "unmanaged"));
        assertEquals(AppSettings.TerminalPalette.ITERM2, AppSettings.terminalPalette());
        assertFalse(AppSettings.terminalTrueColor());
        assertFalse(AppSettings.tmuxPageKeys());
        assertTrue(AppSettings.toolbarPageButtons());
        assertTrue(AppSettings.toolbarStopLadder());
        assertFalse(AppSettings.sidebarFocusBorderHidden());
        assertTrue(AppSettings.sidebarReticule());
        assertFalse(AppSettings.sidebarFolderGroups());
        assertEquals("JetBrains Mono", AppSettings.terminalFontFamily());
        assertEquals(18, AppSettings.terminalFontSize());
        assertEquals(new AppSettings.SidebarViewSettings(
                        "status", true, Set.of("idle", "working"), Set.of("claude", "unmanaged")),
                AppSettings.sidebarViewSettings());
        assertTrue(AppSettings.terminalScrollbarHidden());
        assertEquals(Optional.of(true), AppSettings.tmuxMouse());
        assertFalse(AppSettings.codexMouseWheelDisabled());
        assertEquals(Optional.of(AppSettings.TerminalBackground.WHITE),
                AppSettings.terminalBackgroundOverride());

        AppSettings.saveTmuxMouse(Optional.empty());
        AppSettings.saveCodexMouseWheelDisabled(true);
        AppSettings.saveTerminalBackgroundOverride(Optional.empty());
        AppSettings.saveTmuxPageKeys(true);
        AppSettings.saveToolbarPageButtons(false);
        AppSettings.saveToolbarStopLadder(false);
        AppSettings.saveSidebarFocusBorderHidden(true);
        AppSettings.saveSidebarReticule(false);
        AppSettings.saveSidebarFolderGroups(true);
        assertEquals(Optional.empty(), AppSettings.tmuxMouse());
        assertTrue(AppSettings.codexMouseWheelDisabled());
        assertEquals(Optional.empty(), AppSettings.terminalBackgroundOverride());
        assertTrue(AppSettings.tmuxPageKeys());
        assertFalse(AppSettings.toolbarPageButtons());
        assertFalse(AppSettings.toolbarStopLadder());
        assertTrue(AppSettings.sidebarFocusBorderHidden());
        assertFalse(AppSettings.sidebarReticule());
        assertTrue(AppSettings.sidebarFolderGroups());
        // load-mutate-store: clearing the pins must not have dropped the scrollbar choice
        assertTrue(AppSettings.terminalScrollbarHidden());
    }

    @Test
    void localHostDefaultsToJmuxAndParsesBothIds() throws IOException {
        assertEquals("jmux", AppSettings.parseLocalHostId(null));
        assertEquals("jmux", AppSettings.parseLocalHostId("nonsense"));
        assertEquals("tmux", AppSettings.parseLocalHostId(" TMUX "));
        assertEquals("jmux", AppSettings.parseLocalHostId(" JmUx "));

        assertEquals("jmux", AppSettings.localHostId()); // no file at all
        settings("session.host=nonsense\n");
        assertEquals("jmux", AppSettings.localHostId());
        settings("session.host=jmux\n");
        assertEquals("jmux", AppSettings.localHostId());
        AppSettings.saveLocalHostId("tmux");
        assertEquals("tmux", AppSettings.localHostId());
    }

    @Test
    void localHostSystemPropertyOverridesTheFile() throws IOException {
        settings("session.host=tmux\n");
        String previous = System.getProperty(AppSettings.LOCAL_HOST_OVERRIDE_PROPERTY);
        try {
            System.setProperty(AppSettings.LOCAL_HOST_OVERRIDE_PROPERTY, "jmux");
            assertEquals("jmux", AppSettings.localHostId());
            System.setProperty(AppSettings.LOCAL_HOST_OVERRIDE_PROPERTY, "  ");
            assertEquals("tmux", AppSettings.localHostId(), "a blank override is no override");
        } finally {
            if (previous == null) {
                System.clearProperty(AppSettings.LOCAL_HOST_OVERRIDE_PROPERTY);
            } else {
                System.setProperty(AppSettings.LOCAL_HOST_OVERRIDE_PROPERTY, previous);
            }
        }
    }

    @Test
    void uiThemeParsesExplicitChoicesAndLeavesTheRestToTheSystem() throws IOException {
        assertEquals(Optional.of(AppSettings.UiTheme.LIGHT), AppSettings.UiTheme.parse(" Light "));
        assertEquals(Optional.of(AppSettings.UiTheme.DARK), AppSettings.UiTheme.parse("dark"));
        assertEquals(Optional.empty(), AppSettings.UiTheme.parse(null));
        assertEquals(Optional.empty(), AppSettings.UiTheme.parse("system"));
        settings("ui.theme=dark\n");
        assertEquals(AppSettings.UiTheme.DARK, AppSettings.uiTheme());
    }

    @Test
    void sidebarBackgroundDefaultsToThemeBlackWhite() throws IOException {
        assertEquals(AppSettings.SidebarBackground.TERMINAL, AppSettings.sidebarBackground());
        settings("sidebar.background=nonsense\n");
        assertEquals(AppSettings.SidebarBackground.TERMINAL, AppSettings.sidebarBackground());
        settings("sidebar.background=default\n");
        assertEquals(AppSettings.SidebarBackground.DEFAULT, AppSettings.sidebarBackground());
        settings("sidebar.background=ui-gray\n");
        assertEquals(AppSettings.SidebarBackground.UI_GRAY, AppSettings.sidebarBackground());
    }

    @Test
    void sidebarMatchesDefaultOnAndOnlyOffUnlinks() throws IOException {
        assertTrue(AppSettings.rightPaneMatchesSidebar());
        assertTrue(AppSettings.toolbarMatchesSidebar());
        assertTrue(AppSettings.sidebarSectionsMatchBackground());
        settings("right-pane.match-sidebar=off\ntoolbar.match-sidebar=off\n");
        assertFalse(AppSettings.rightPaneMatchesSidebar());
        assertFalse(AppSettings.toolbarMatchesSidebar());
        AppSettings.saveRightPaneMatchesSidebar(true);
        assertTrue(AppSettings.rightPaneMatchesSidebar());
    }

    @Test
    void savingOneKeyPreservesHandWrittenOthers() throws IOException {
        settings("ui.theme=light\ntmux.mouse=on\n");
        AppSettings.saveTerminalScrollbarHidden(true);
        assertEquals(AppSettings.UiTheme.LIGHT, AppSettings.uiTheme());
        assertEquals(Optional.of(true), AppSettings.tmuxMouse());
        assertTrue(AppSettings.terminalScrollbarHidden());
    }

    @Test
    void mcpServerDefaultsToOffAndOnlyOnEnablesIt() throws IOException {
        assertFalse(AppSettings.mcpServerEnabled()); // no file at all
        settings("");
        assertFalse(AppSettings.mcpServerEnabled());
        settings("mcp.server=nonsense\n");
        assertFalse(AppSettings.mcpServerEnabled());
        settings("mcp.server= ON \n");
        assertTrue(AppSettings.mcpServerEnabled());
        settings("mcp.server=off\n");
        assertFalse(AppSettings.mcpServerEnabled());
        AppSettings.saveMcpServerEnabled(true);
        assertTrue(AppSettings.mcpServerEnabled());
        AppSettings.saveMcpServerEnabled(false);
        assertFalse(AppSettings.mcpServerEnabled());
    }

    @Test
    void mcpPortDefaultsAndIsClampedToTheUnprivilegedRange() throws IOException {
        assertEquals(AppSettings.DEFAULT_MCP_PORT, AppSettings.mcpPort()); // no file at all
        assertEquals(7391, AppSettings.DEFAULT_MCP_PORT);
        settings("mcp.port= 8123 \n");
        assertEquals(8123, AppSettings.mcpPort());
        settings("mcp.port=80\n");
        assertEquals(AppSettings.MIN_MCP_PORT, AppSettings.mcpPort());
        settings("mcp.port=700000\n");
        assertEquals(AppSettings.MAX_MCP_PORT, AppSettings.mcpPort());
        settings("mcp.port=not-a-port\n");
        assertEquals(AppSettings.DEFAULT_MCP_PORT, AppSettings.mcpPort());
        AppSettings.saveMcpPort(1);
        assertEquals(AppSettings.MIN_MCP_PORT, AppSettings.mcpPort());
        AppSettings.saveMcpPort(9000);
        assertEquals(9000, AppSettings.mcpPort());
    }

    @Test
    void mcpTokenIsGeneratedOnceThenStable() throws IOException {
        String first = AppSettings.mcpToken(); // no file at all: generated and saved
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"), first);
        assertEquals(first, AppSettings.mcpToken());
        assertTrue(Files.readString(dir.resolve("settings.properties")).contains(first));

        settings("mcp.token= hand-written \n");
        assertEquals("hand-written", AppSettings.mcpToken());

        settings("mcp.token=  \n");
        String regenerated = AppSettings.mcpToken();
        assertTrue(regenerated.matches("[A-Za-z0-9_-]{43}"), regenerated);
        assertFalse(regenerated.equals(first), "a fresh random token");
    }

    @Test
    void regeneratingTheMcpTokenReplacesAndPersistsIt() {
        String first = AppSettings.mcpToken();
        AppSettings.saveMcpPort(9000);
        String second = AppSettings.regenerateMcpToken();
        assertTrue(second.matches("[A-Za-z0-9_-]{43}"), second);
        assertNotEquals(first, second);
        assertEquals(second, AppSettings.mcpToken());
        assertEquals(9000, AppSettings.mcpPort(), "other settings survive");
    }

    @Test
    void racingFirstMcpTokenCallsAgreeOnOneToken() throws Exception {
        for (int round = 0; round < 20; round++) {
            settings("");
            String[] seen = new String[2];
            Thread a = new Thread(() -> seen[0] = AppSettings.mcpToken());
            Thread b = new Thread(() -> seen[1] = AppSettings.mcpToken());
            a.start();
            b.start();
            a.join();
            b.join();
            assertEquals(seen[0], seen[1], "round " + round);
            assertEquals(seen[0], AppSettings.mcpToken(), "round " + round);
        }
    }
}
