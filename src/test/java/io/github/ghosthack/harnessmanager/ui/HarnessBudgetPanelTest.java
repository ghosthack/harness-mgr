package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.usage.UsageSnapshot;
import org.junit.jupiter.api.Test;

import javax.swing.JProgressBar;
import java.awt.Component;
import java.awt.Container;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HarnessBudgetPanelTest {

    @Test
    void excludesShellBecauseItHasNoProviderBudget() {
        assertEquals(Set.of(HarnessType.CLAUDE_CODE, HarnessType.CODEX),
                HarnessBudgetPanel.budgetedHarnesses(Set.of(
                        HarnessType.CLAUDE_CODE, HarnessType.CODEX, HarnessType.SHELL)));
    }

    @Test
    void selectsShortSessionAndLongTotalWindows() {
        UsageSnapshot snapshot = new UsageSnapshot("Codex", "test", List.of(
                limit("Codex · 1 week", 41, "2026-08-29T00:00:00Z"),
                limit("Codex · 5 hours", 12, "2026-08-22T05:00:00Z")),
                List.of(), Optional.empty());

        HarnessBudgetPanel.Summary summary = HarnessBudgetPanel.summarize(snapshot);

        assertEquals(88, summary.session().orElseThrow().availablePercent());
        assertEquals("Codex · 5 hours", summary.session().orElseThrow().description());
        assertEquals(59, summary.week().orElseThrow().availablePercent());
        assertEquals("Codex · 1 week", summary.week().orElseThrow().description());
        assertEquals(Instant.parse("2026-08-29T00:00:00Z"),
                summary.week().orElseThrow().resetsAt().orElseThrow());
        assertEquals(Optional.empty(), summary.fable());
        assertEquals(Optional.empty(), summary.credits());
    }

    @Test
    void turnsTimeUntilWeeklyResetIntoDaysAndBarProgress() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);

        assertEquals(new HarnessBudgetPanel.WeeklyReset(
                        3, 29, "3 days until weekly reset"),
                HarnessBudgetPanel.weeklyReset(
                        Optional.of(Instant.parse("2026-08-24T00:00:01Z")), clock).orElseThrow());
        assertEquals(new HarnessBudgetPanel.WeeklyReset(
                        1, 2, "1 day until weekly reset"),
                HarnessBudgetPanel.weeklyReset(
                        Optional.of(Instant.parse("2026-08-22T04:00:00Z")), clock).orElseThrow());
    }

    @Test
    void weeklyResetBarIsFullAtSevenDaysAndEmptyWhenDue() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);

        assertEquals(100, HarnessBudgetPanel.weeklyReset(
                Optional.of(Instant.parse("2026-08-29T00:00:00Z")), clock)
                .orElseThrow().remainingPercent());
        assertEquals(new HarnessBudgetPanel.WeeklyReset(0, 0, "Weekly reset due"),
                HarnessBudgetPanel.weeklyReset(
                        Optional.of(Instant.parse("2026-08-21T00:00:00Z")), clock).orElseThrow());
    }

    @Test
    void oneProviderWindowCanSupplyBothAvailableNumbers() {
        UsageSnapshot snapshot = new UsageSnapshot("Codex", "test", List.of(
                limit("Codex", 25, "2026-08-23T00:00:00Z")), List.of(), Optional.empty());

        HarnessBudgetPanel.Summary summary = HarnessBudgetPanel.summarize(snapshot);

        assertEquals(75, summary.session().orElseThrow().availablePercent());
        assertEquals(75, summary.week().orElseThrow().availablePercent());
    }

    @Test
    void preservesClaudeFableAsItsOwnBudget() {
        UsageSnapshot snapshot = new UsageSnapshot("Claude", "test", List.of(
                limit("Current session (5 hours)", 12, "2026-08-22T05:00:00Z"),
                limit("Current week (all models)", 41, "2026-08-29T00:00:00Z"),
                limit("Current week (Fable)", 63, "2026-08-29T00:00:00Z")),
                List.of(), Optional.empty());

        HarnessBudgetPanel.Summary summary = HarnessBudgetPanel.summarize(snapshot);

        assertEquals(59, summary.week().orElseThrow().availablePercent());
        assertEquals(37, summary.fable().orElseThrow().availablePercent());
        assertEquals("Current week (Fable)", summary.fable().orElseThrow().description());
    }

    @Test
    void creditMeteredAccountsWithNoRateLimitWindowsGetTheirOwnBudget() {
        UsageSnapshot snapshot = new UsageSnapshot("Claude", "test", List.of(
                limit("Monthly credits", 50)), List.of(), Optional.empty());

        HarnessBudgetPanel.Summary summary = HarnessBudgetPanel.summarize(snapshot);

        assertEquals(Optional.empty(), summary.session());
        assertEquals(Optional.empty(), summary.week());
        assertEquals(Optional.empty(), summary.fable());
        assertEquals(50, summary.credits().orElseThrow().availablePercent());
        assertEquals("Monthly credits", summary.credits().orElseThrow().description());
    }

    @Test
    void rendersACreditsBarWithADaysUntilMonthlyResetBar() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);
        HarnessBudgetPanel panel = new HarnessBudgetPanel(clock);
        UsageSnapshot snapshot = new UsageSnapshot("Claude", "test", List.of(
                limit("Monthly credits", 50)), List.of(), Optional.empty());

        panel.showLoading(Set.of(HarnessType.CLAUDE_CODE));
        panel.showSnapshot(HarnessType.CLAUDE_CODE, snapshot);

        assertEquals(List.of(50, 32), progressValues(panel));
    }

    @Test
    void turnsTimeUntilMonthlyResetIntoDaysAndBarProgress() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);

        assertEquals(new HarnessBudgetPanel.MonthlyReset(10, 32, "10 days until monthly reset"),
                HarnessBudgetPanel.monthlyReset(clock));
    }

    @Test
    void rendersNativeBarsForSessionWeekFableAndDays() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);
        HarnessBudgetPanel panel = new HarnessBudgetPanel(clock);
        UsageSnapshot snapshot = new UsageSnapshot("Claude", "test", List.of(
                limit("Current session (5 hours)", 12, "2026-08-22T05:00:00Z"),
                limit("Current week (all models)", 41, "2026-08-29T00:00:00Z"),
                limit("Current week (Fable)", 63, "2026-08-29T00:00:00Z")),
                List.of(), Optional.empty());

        panel.showLoading(Set.of(HarnessType.CLAUDE_CODE));
        panel.showSnapshot(HarnessType.CLAUDE_CODE, snapshot);

        assertEquals(List.of(88, 100, 59, 37, 100), progressValues(panel));
    }

    @Test
    void turnsTimeUntilSessionResetIntoHoursMinutesAndBarProgress() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);

        assertEquals(new HarnessBudgetPanel.SessionReset("4h35m", 92, "4h35m left in session"),
                HarnessBudgetPanel.sessionReset(
                        Optional.of(Instant.parse("2026-08-22T04:35:00Z")), clock).orElseThrow());
    }

    @Test
    void sessionResetBarIsFullAtFiveHoursAndEmptyWhenDue() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);

        assertEquals(100, HarnessBudgetPanel.sessionReset(
                Optional.of(Instant.parse("2026-08-22T05:00:00Z")), clock)
                .orElseThrow().remainingPercent());
        assertEquals(new HarnessBudgetPanel.SessionReset("0h00m", 0, "Session reset due"),
                HarnessBudgetPanel.sessionReset(
                        Optional.of(Instant.parse("2026-08-21T00:00:00Z")), clock).orElseThrow());
    }

    private static List<Integer> progressValues(Container root) {
        List<Integer> values = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (component instanceof JProgressBar bar) {
                values.add(bar.getValue());
            }
            if (component instanceof Container container) {
                values.addAll(progressValues(container));
            }
        }
        return values;
    }

    private static UsageSnapshot.Limit limit(String label, int used, String reset) {
        return new UsageSnapshot.Limit(label, used, Optional.of(Instant.parse(reset)), Optional.empty());
    }

    private static UsageSnapshot.Limit limit(String label, int used) {
        return new UsageSnapshot.Limit(label, used, Optional.empty(), Optional.empty());
    }
}
