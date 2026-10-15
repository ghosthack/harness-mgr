package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.state.SessionState;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarViewTest {

    @Test
    void persistedSortKeysRoundTripAndUnknownKeysFallBackToSession() {
        for (SidebarView.SortMode mode : SidebarView.SortMode.values()) {
            assertEquals(mode, SidebarView.SortMode.fromKey(mode.key()));
        }
        assertEquals(SidebarView.SortMode.SESSION_NAME,
                SidebarView.SortMode.fromKey("from-a-future-version"));
    }

    @Test
    void classifiesEveryVisibleStatusIncludingPendingAndLifecycleRows() {
        SidebarEntry working = live("hm-working", "/work/working");
        working.setState(SessionState.WORKING, "test");
        SidebarEntry waiting = live("hm-waiting", "/work/waiting");
        waiting.setState(SessionState.WAITING_FOR_INPUT, "test");
        SidebarEntry idle = live("hm-idle", "/work/idle");
        idle.setState(SessionState.IDLE, "test");

        assertEquals(SidebarView.FilterStatus.WORKING, SidebarView.filterStatus(working));
        assertEquals(SidebarView.FilterStatus.WAITING_FOR_INPUT, SidebarView.filterStatus(waiting));
        assertEquals(SidebarView.FilterStatus.IDLE, SidebarView.filterStatus(idle));
        assertEquals(SidebarView.FilterStatus.STATUS_PENDING,
                SidebarView.filterStatus(live("hm-pending", "/work/pending")));
        assertEquals(SidebarView.FilterStatus.DEAD,
                SidebarView.filterStatus(SidebarEntry.dead(session("hm-dead", "/work/dead"))));
        assertEquals(SidebarView.FilterStatus.UNMANAGED,
                SidebarView.filterStatus(SidebarEntry.unmanaged("hm-unmanaged")));
    }

    @Test
    void pendingLiveRowsShowAnHourglassWhileUnmanagedRowsStayUnbadged() {
        SidebarEntry pending = live("hm-pending", "/work/pending");
        var pendingIcon = MainWindow.SidebarCellRenderer.statusIcon(pending);
        assertNotNull(pendingIcon);
        pending.setState(SessionState.WORKING, "confirmed");
        var workingIcon = MainWindow.SidebarCellRenderer.statusIcon(pending);
        assertNotSame(pendingIcon, workingIcon);
        assertEquals(pendingIcon.getIconWidth(), workingIcon.getIconWidth());
        assertNull(MainWindow.SidebarCellRenderer.statusIcon(
                SidebarEntry.unmanaged("hm-unmanaged")));
    }

    @Test
    void deadSessionIconIsOnlyReticulatedWhenTheSettingIsEnabled() {
        SidebarEntry dead = SidebarEntry.dead(session("hm-dead", "/work/dead"));

        assertNotSame(MainWindow.SidebarCellRenderer.statusIcon(dead, false),
                MainWindow.SidebarCellRenderer.statusIcon(dead, true));
    }

    @Test
    void classifiesEveryHarnessFamilyAndIntersectsItWithStatusFiltering() {
        for (HarnessType type : HarnessType.values()) {
            SidebarEntry entry = SidebarEntry.live(session("hm-" + type.id(), "/work/" + type.id(), type));
            SidebarView.HarnessFamily expected = switch (type) {
                case CLAUDE_CODE -> SidebarView.HarnessFamily.CLAUDE_CODE;
                case CODEX -> SidebarView.HarnessFamily.CODEX;
                case CURSOR_AGENT -> SidebarView.HarnessFamily.CURSOR_AGENT;
                case GROK -> SidebarView.HarnessFamily.GROK;
                case OPENCODE -> SidebarView.HarnessFamily.OPENCODE;
                case PI -> SidebarView.HarnessFamily.PI;
                case MUSE -> SidebarView.HarnessFamily.MUSE;
                case SHELL -> SidebarView.HarnessFamily.SHELL;
            };
            assertEquals(expected, SidebarView.harnessFamily(entry));
        }

        SidebarEntry unmanaged = SidebarEntry.unmanaged("hm-unmanaged");
        assertEquals(SidebarView.HarnessFamily.UNMANAGED, SidebarView.harnessFamily(unmanaged));
        assertFalse(SidebarView.isVisible(unmanaged,
                EnumSet.allOf(SidebarView.FilterStatus.class),
                EnumSet.of(SidebarView.HarnessFamily.CODEX)));
        assertTrue(SidebarView.isVisible(unmanaged,
                EnumSet.of(SidebarView.FilterStatus.UNMANAGED),
                EnumSet.of(SidebarView.HarnessFamily.UNMANAGED)));
    }

    @Test
    void sortsByDisplayNameDirectoryBasenameAndStatus() {
        SidebarEntry alpha = live("hm-z", "/work/alpha");
        alpha.setPaneTitle("Alpha title");
        alpha.setState(SessionState.IDLE, "test");
        SidebarEntry beta = live("hm-a", "/work/beta");
        beta.setState(SessionState.WAITING_FOR_INPUT, "test");
        SidebarEntry dead = SidebarEntry.dead(session("hm-dead", "/work/charlie"));
        SidebarEntry unmanaged = SidebarEntry.unmanaged("hm-unmanaged");
        List<SidebarEntry> entries = List.of(unmanaged, dead, beta, alpha);

        assertOrder(entries, SidebarView.SortMode.SESSION_NAME,
                "hm-z", "hm-a", "hm-dead", "hm-unmanaged");
        assertOrder(entries, SidebarView.SortMode.DIRECTORY_NAME,
                "hm-z", "hm-a", "hm-dead", "hm-unmanaged");
        assertOrder(entries, SidebarView.SortMode.STATUS,
                "hm-a", "hm-z", "hm-dead", "hm-unmanaged");

        List<SidebarEntry> descending = new ArrayList<>(entries);
        descending.sort(SidebarView.comparator(SidebarView.SortMode.STATUS, true));
        assertEquals(List.of("hm-unmanaged", "hm-dead", "hm-z", "hm-a"),
                descending.stream().map(SidebarEntry::tmuxSession).toList());
    }

    @Test
    void folderSortKeepsEachFolderContiguousEvenWhenBasenamesCollide() {
        List<SidebarEntry> entries = new ArrayList<>(List.of(
                live("hm-a", "/work/one/project"),
                live("hm-b", "/work/two/project"),
                live("hm-c", "/work/one/project")));

        entries.sort(SidebarView.comparator(SidebarView.SortMode.DIRECTORY_NAME));

        assertEquals(List.of("hm-a", "hm-c", "hm-b"),
                entries.stream().map(SidebarEntry::tmuxSession).toList());
        assertEquals("/work/one/project", SidebarView.folderGroupKey(entries.get(0)));
        assertEquals("", SidebarView.folderGroupKey(SidebarEntry.unmanaged("hm-unmanaged")));
    }

    @Test
    void folderGroupingHeadsEachRunOnceAndDropsTheRepeatedPathBelow() {
        DefaultListModel<SidebarEntry> model = new DefaultListModel<>();
        model.addElement(live("hm-a", "/work/project"));
        model.addElement(live("hm-b", "/work/project"));
        model.addElement(SidebarEntry.unmanaged("hm-unmanaged"));
        JList<SidebarEntry> list = new JList<>(model);
        MainWindow.SidebarCellRenderer renderer = new MainWindow.SidebarCellRenderer();
        renderer.setFolderGrouping(true);

        JLabel header = groupHeader(render(renderer, list, 0));
        assertTrue(header.isVisible());
        assertEquals("/work/project", header.getText());
        assertFalse(detail(render(renderer, list, 0)).isVisible());

        assertFalse(groupHeader(render(renderer, list, 1)).isVisible());
        assertFalse(detail(render(renderer, list, 1)).isVisible());

        // an unmanaged row has no folder to belong to: it keeps its own detail line
        assertFalse(groupHeader(render(renderer, list, 2)).isVisible());
        assertTrue(detail(render(renderer, list, 2)).isVisible());

        renderer.setFolderGrouping(false);
        assertFalse(groupHeader(render(renderer, list, 0)).isVisible());
        assertTrue(detail(render(renderer, list, 0)).isVisible());
    }

    @Test
    void persistedRenameOverridesPaneTitleAndAppliesToDeadRows() {
        SessionEntry renamed = session("hm-original", "/work/project").withDisplayName("My session");
        SidebarEntry live = SidebarEntry.live(renamed);
        live.setPaneTitle("Harness title");

        assertEquals("My session", live.displayName());
        assertEquals("Harness title", live.detectedName());
        assertEquals("My session", live.nameText());
        assertEquals("My session", SidebarEntry.dead(renamed).nameText());
    }

    @Test
    void activeHarnessesAreDeduplicatedAndIgnoreDeadOrUnmanagedSessions() {
        SidebarEntry codexOne = SidebarEntry.live(session("hm-codex-1", "/work/a", HarnessType.CODEX));
        SidebarEntry codexTwo = SidebarEntry.live(session("hm-codex-2", "/work/b", HarnessType.CODEX));
        SidebarEntry claude = SidebarEntry.live(session("hm-claude", "/work/c", HarnessType.CLAUDE_CODE));
        SidebarEntry deadShell = SidebarEntry.dead(session("hm-shell", "/work/d", HarnessType.SHELL));

        assertEquals(EnumSet.of(HarnessType.CODEX, HarnessType.CLAUDE_CODE),
                SidebarView.activeHarnesses(List.of(
                        codexOne, codexTwo, claude, deadShell, SidebarEntry.unmanaged("hm-other"))));
    }

    @Test
    void tooltipKeepsFullDisplayNameWhileWindowTitleEllipsisIsUnicodeSafe() {
        SidebarEntry entry = live("hm-original", "/work/project");
        entry.setPaneTitle("A very long & detailed <Codex> session name");

        assertTrue(entry.tooltipText().contains(
                "A very long &amp; detailed &lt;Codex&gt; session name"));
        assertTrue(entry.tooltipText().contains("tmux: hm-original"));
        assertEquals("A very lo…", MainWindow.ellipsizeRight(entry.displayName(), 10));
        assertEquals("😀a…", MainWindow.ellipsizeRight("😀abc", 3));
    }

    @Test
    void tooltipLabelsTheSessionWithItsActualBackingHost() {
        SessionEntry jmuxSession = new SessionEntry("hm-jmux", "/work/project",
                HarnessType.SHELL, null, Instant.EPOCH, "0.0", null, null, "jmux");
        SidebarEntry entry = SidebarEntry.live(jmuxSession);
        entry.setPaneTitle("Renamed by harness");

        assertTrue(entry.tooltipText().contains("jmux: hm-jmux"));
        assertFalse(entry.tooltipText().contains("tmux:"));
    }

    @Test
    void tooltipShowsTheHostEvenWhenTheSessionWasNeverRenamed() {
        SessionEntry jmuxSession = new SessionEntry("hm-jmux-plain", "/work/project",
                HarnessType.SHELL, null, Instant.EPOCH, "0.0", null, null, "jmux");
        SidebarEntry entry = SidebarEntry.live(jmuxSession);

        assertTrue(entry.tooltipText().contains("<br>jmux<br>"));
    }

    private static JPanel render(MainWindow.SidebarCellRenderer renderer,
                                 JList<SidebarEntry> list, int index) {
        return (JPanel) renderer.getListCellRendererComponent(
                list, list.getModel().getElementAt(index), index, false, false);
    }

    private static JLabel groupHeader(JPanel cell) {
        return (JLabel) ((BorderLayout) cell.getLayout()).getLayoutComponent(BorderLayout.NORTH);
    }

    /** The row's muted path line: the second of the two stacked labels in the center. */
    private static JLabel detail(JPanel cell) {
        Container lines = (Container) ((BorderLayout) cell.getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
        return (JLabel) lines.getComponent(1);
    }

    private static void assertOrder(List<SidebarEntry> source, SidebarView.SortMode mode,
                                    String... expectedNames) {
        List<SidebarEntry> sorted = new ArrayList<>(source);
        sorted.sort(SidebarView.comparator(mode));
        assertEquals(List.of(expectedNames), sorted.stream().map(SidebarEntry::tmuxSession).toList());
    }

    private static SidebarEntry live(String name, String projectPath) {
        return SidebarEntry.live(session(name, projectPath));
    }

    private static SessionEntry session(String name, String projectPath) {
        return session(name, projectPath, HarnessType.SHELL);
    }

    private static SessionEntry session(String name, String projectPath, HarnessType harnessType) {
        return new SessionEntry(name, projectPath, harnessType, null, Instant.EPOCH, "0.0");
    }
}
