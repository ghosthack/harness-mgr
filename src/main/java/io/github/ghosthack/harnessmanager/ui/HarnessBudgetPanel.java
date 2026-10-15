package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.usage.UsageSnapshot;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Harness-level provider quota summary shown below the live session list. */
final class HarnessBudgetPanel extends JPanel {

    private static final Dimension BAR_SIZE = new Dimension(82, 8);
    private static final Dimension VALUE_SIZE = new Dimension(38, 16);
    private static final Dimension METRIC_SIZE = new Dimension(52, 16);
    private static final long WEEK_SECONDS = Duration.ofDays(7).toSeconds();
    private static final long SESSION_SECONDS = Duration.ofHours(5).toSeconds();

    record Budget(int availablePercent, String description, Optional<Instant> resetsAt) {
    }

    record Summary(Optional<Budget> session, Optional<Budget> week, Optional<Budget> fable,
                   Optional<Budget> credits) {
    }

    record WeeklyReset(int daysRemaining, int remainingPercent, String tooltip) {
    }

    record SessionReset(String remaining, int remainingPercent, String tooltip) {
    }

    record MonthlyReset(int daysRemaining, int remainingPercent, String tooltip) {
    }

    private final JPanel content = new JPanel();
    private final JButton refresh = new JButton();
    private final Clock clock;
    private final EnumSet<HarnessType> activeHarnesses = EnumSet.noneOf(HarnessType.class);
    private final Map<HarnessType, UsageSnapshot> snapshots = new EnumMap<>(HarnessType.class);
    private final Map<HarnessType, String> errors = new EnumMap<>(HarnessType.class);
    private final EnumSet<HarnessType> loading = EnumSet.noneOf(HarnessType.class);

    HarnessBudgetPanel() {
        this(Clock.systemUTC());
    }

    HarnessBudgetPanel(Clock clock) {
        super(new BorderLayout());
        this.clock = clock;
        setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
        add(new JSeparator(), BorderLayout.NORTH);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(5, 12, 3, 5));
        JLabel heading = new JLabel("Budgets");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        // A fixed minimum here would floor the sidebar's width the same way the metric
        // row's did (see below) — BoxLayout/BorderLayout propagate it up to the JSplitPane.
        heading.setMinimumSize(new Dimension(0, heading.getPreferredSize().height));
        header.add(heading);
        header.add(Box.createHorizontalGlue());
        MainWindow.configureSidebarControlButton(refresh);
        refresh.setIcon(LucidIcon.refresh(13));
        refresh.setToolTipText("Update budgets");
        refresh.setMinimumSize(new Dimension(0, refresh.getPreferredSize().height));
        header.add(refresh);
        add(header, BorderLayout.CENTER);

        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(1, 12, 7, 12));
        add(content, BorderLayout.SOUTH);
        render();
    }

    void setRefreshAction(Runnable action) {
        refresh.addActionListener(e -> action.run());
    }

    void showLoading(Set<HarnessType> harnesses) {
        activeHarnesses.clear();
        activeHarnesses.addAll(budgetedHarnesses(harnesses));
        snapshots.keySet().retainAll(activeHarnesses);
        errors.keySet().retainAll(activeHarnesses);
        loading.clear();
        loading.addAll(activeHarnesses);
        refresh.setEnabled(activeHarnesses.isEmpty());
        render();
    }

    void showSnapshot(HarnessType harness, UsageSnapshot snapshot) {
        if (!activeHarnesses.contains(harness)) return;
        snapshots.put(harness, snapshot);
        errors.remove(harness);
        loading.remove(harness);
        finishOne();
    }

    void showError(HarnessType harness, Exception failure) {
        if (!activeHarnesses.contains(harness)) return;
        String message = failure.getMessage();
        errors.put(harness, message == null || message.isBlank()
                ? "Usage unavailable" : message);
        snapshots.remove(harness);
        loading.remove(harness);
        finishOne();
    }

    private void finishOne() {
        refresh.setEnabled(loading.isEmpty());
        render();
    }

    private void render() {
        content.removeAll();
        if (activeHarnesses.isEmpty()) {
            content.add(muted("No budgets available."));
        } else {
            List<HarnessType> rows = List.copyOf(activeHarnesses);
            for (int index = 0; index < rows.size(); index++) {
                HarnessType harness = rows.get(index);
                JPanel row = harnessRow(harness);
                row.setAlignmentX(0f);
                content.add(row);
                if (index < rows.size() - 1) {
                    content.add(Box.createVerticalStrut(8));
                }
            }
        }
        revalidate();
        repaint();
    }

    private JPanel harnessRow(HarnessType harness) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));

        JLabel name = new JLabel(label(harness), HarnessIcons.forType(harness), JLabel.LEADING);
        name.setIconTextGap(6);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        name.setAlignmentX(0f);
        row.add(name);

        if (loading.contains(harness)) {
            JLabel state = muted("Loading usage…");
            state.setAlignmentX(0f);
            row.add(state);
            return row;
        }
        if (errors.containsKey(harness)) {
            JLabel state = muted("Usage unavailable");
            state.setToolTipText(errors.get(harness));
            state.setAlignmentX(0f);
            row.add(state);
            return row;
        }

        UsageSnapshot snapshot = snapshots.get(harness);
        if (snapshot == null || snapshot.limits().isEmpty()) {
            JLabel state = muted("Provider limits unavailable");
            snapshotNote(snapshot).ifPresent(state::setToolTipText);
            state.setAlignmentX(0f);
            row.add(state);
            return row;
        }

        Summary summary = summarize(snapshot);
        summary.session().ifPresent(session -> {
            row.add(budgetRow(session, "Session"));
            sessionReset(session.resetsAt(), clock).ifPresent(reset ->
                    row.add(metricRow(reset.remainingPercent(), reset.remainingPercent() + "%",
                            reset.remaining(), reset.tooltip())));
        });
        summary.week().ifPresent(week -> row.add(budgetRow(week, "Week")));
        summary.fable().ifPresent(fable -> row.add(budgetRow(fable, "Fable")));
        summary.week().ifPresent(week -> weeklyReset(week.resetsAt(), clock).ifPresent(reset ->
                row.add(metricRow(reset.remainingPercent(),
                        Integer.toString(reset.daysRemaining()), "Days", reset.tooltip()))));
        summary.credits().ifPresent(credits -> {
            row.add(budgetRow(credits, "Credits"));
            MonthlyReset reset = monthlyReset(clock);
            row.add(metricRow(reset.remainingPercent(), Integer.toString(reset.daysRemaining()),
                    "Days", reset.tooltip()));
        });
        return row;
    }

    private static JPanel budgetRow(Budget budget, String metric) {
        return metricRow(budget.availablePercent(), budget.availablePercent() + "%", metric,
                budget.description() + " · " + budget.availablePercent() + "% available");
    }

    private static JPanel metricRow(int progress, String value, String metric, String tooltip) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(0f);
        row.setToolTipText(tooltip);

        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(progress);
        bar.setBorderPainted(false);
        bar.setStringPainted(false);
        // No setMinimumSize: a fixed minimum here would floor the whole sidebar's
        // width, since BoxLayout/BorderLayout propagate it up and JSplitPane clamps
        // divider drags to the left component's computed minimum size.
        bar.setMinimumSize(new Dimension(0, BAR_SIZE.height));
        bar.setPreferredSize(BAR_SIZE);
        bar.setMaximumSize(BAR_SIZE);
        bar.setToolTipText(tooltip);
        bar.getAccessibleContext().setAccessibleName(metric);
        bar.getAccessibleContext().setAccessibleDescription(tooltip);
        row.add(bar);
        row.add(Box.createHorizontalStrut(7));

        JLabel valueLabel = fixedLabel(value, VALUE_SIZE);
        valueLabel.setHorizontalAlignment(JLabel.TRAILING);
        valueLabel.setToolTipText(tooltip);
        row.add(valueLabel);
        row.add(Box.createHorizontalStrut(7));

        JLabel metricLabel = fixedLabel(metric, METRIC_SIZE);
        metricLabel.setToolTipText(tooltip);
        row.add(metricLabel);
        return row;
    }

    private static JLabel fixedLabel(String text, Dimension size) {
        JLabel label = new JLabel(text);
        // No setMinimumSize here either, for the same reason as the progress bar above.
        label.setMinimumSize(new Dimension(0, size.height));
        label.setPreferredSize(size);
        label.setMaximumSize(size);
        return label;
    }

    static Summary summarize(UsageSnapshot snapshot) {
        List<UsageSnapshot.Limit> limits = snapshot.limits();
        List<UsageSnapshot.Limit> rateLimits = limits.stream()
                .filter(limit -> !isFable(limit.label()) && !isCredits(limit.label()))
                .toList();

        Optional<Budget> session = rateLimits.isEmpty() ? Optional.empty() : Optional.of(availableBudget(
                rateLimits.stream()
                        .filter(limit -> isShortWindow(limit.label()))
                        .findFirst()
                        .orElseGet(() -> rateLimits.stream()
                                .min(Comparator.comparing(limit -> limit.resetsAt().orElse(Instant.MAX)))
                                .orElseThrow())));
        Optional<Budget> week = rateLimits.isEmpty() ? Optional.empty() : Optional.of(availableBudget(
                rateLimits.stream()
                        .filter(limit -> isAllModelsWeek(limit.label()))
                        .findFirst()
                        .orElseGet(() -> rateLimits.stream()
                                .filter(limit -> isLongWindow(limit.label()))
                                .findFirst()
                                .orElseGet(() -> rateLimits.stream()
                                        .max(Comparator.comparing(
                                                limit -> limit.resetsAt().orElse(Instant.MIN)))
                                        .orElseThrow()))));
        Optional<Budget> fable = limits.stream()
                .filter(limit -> isFable(limit.label()))
                .findFirst()
                .map(HarnessBudgetPanel::availableBudget);
        Optional<Budget> credits = limits.stream()
                .filter(limit -> isCredits(limit.label()))
                .findFirst()
                .map(HarnessBudgetPanel::availableBudget);
        return new Summary(session, week, fable, credits);
    }

    private static Budget availableBudget(UsageSnapshot.Limit limit) {
        return new Budget(100 - limit.usedPercent(), limit.label(), limit.resetsAt());
    }

    static Optional<WeeklyReset> weeklyReset(Optional<Instant> reset, Clock clock) {
        if (reset.isEmpty()) return Optional.empty();
        long seconds = Duration.between(clock.instant(), reset.get()).getSeconds();
        if (seconds <= 0) {
            return Optional.of(new WeeklyReset(0, 0, "Weekly reset due"));
        }
        int days = (int) Math.max(1, (seconds + 86_399) / 86_400);
        int percent = Math.clamp((int) Math.round(seconds * 100.0 / WEEK_SECONDS), 0, 100);
        return Optional.of(new WeeklyReset(days, percent,
                days + (days == 1 ? " day" : " days") + " until weekly reset"));
    }

    /** Anthropic's extra_usage credit window carries no reset timestamp, so this assumes
     * a calendar-month cycle (observed empirically: the account resets on the 1st). */
    static MonthlyReset monthlyReset(Clock clock) {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), clock.getZone());
        ZonedDateTime nextMonth = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(clock.getZone());
        long seconds = Duration.between(now, nextMonth).getSeconds();
        int days = (int) Math.max(1, (seconds + 86_399) / 86_400);
        long monthSeconds = now.toLocalDate().lengthOfMonth() * 86_400L;
        int percent = Math.clamp((int) Math.round(seconds * 100.0 / monthSeconds), 0, 100);
        return new MonthlyReset(days, percent,
                days + (days == 1 ? " day" : " days") + " until monthly reset");
    }

    static Optional<SessionReset> sessionReset(Optional<Instant> reset, Clock clock) {
        if (reset.isEmpty()) return Optional.empty();
        long seconds = Math.max(0, Duration.between(clock.instant(), reset.get()).getSeconds());
        int percent = Math.clamp((int) Math.round(seconds * 100.0 / SESSION_SECONDS), 0, 100);
        String remaining = formatHoursMinutes(seconds);
        String tooltip = seconds == 0 ? "Session reset due" : remaining + " left in session";
        return Optional.of(new SessionReset(remaining, percent, tooltip));
    }

    private static String formatHoursMinutes(long seconds) {
        long totalMinutes = seconds / 60;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + "h" + String.format(Locale.ROOT, "%02d", minutes) + "m";
    }

    private static boolean isShortWindow(String label) {
        String normalized = label.toLowerCase(Locale.ROOT);
        return normalized.contains("session") || normalized.contains("hour")
                || normalized.contains("minute");
    }

    private static boolean isLongWindow(String label) {
        String normalized = label.toLowerCase(Locale.ROOT);
        return normalized.contains("week") || normalized.contains("month")
                || normalized.contains("day");
    }

    private static boolean isAllModelsWeek(String label) {
        String normalized = label.toLowerCase(Locale.ROOT);
        return normalized.contains("all models")
                || (isLongWindow(label) && !isFable(label)
                        && !normalized.contains("opus") && !normalized.contains("sonnet"));
    }

    private static boolean isFable(String label) {
        return label.toLowerCase(Locale.ROOT).contains("fable");
    }

    private static boolean isCredits(String label) {
        return label.toLowerCase(Locale.ROOT).contains("credit");
    }

    private static Optional<String> snapshotNote(UsageSnapshot snapshot) {
        return snapshot == null ? Optional.empty() : snapshot.note();
    }

    static EnumSet<HarnessType> budgetedHarnesses(Set<HarnessType> harnesses) {
        EnumSet<HarnessType> result = EnumSet.noneOf(HarnessType.class);
        result.addAll(harnesses);
        result.remove(HarnessType.SHELL);
        return result;
    }

    private static String label(HarnessType harness) {
        return switch (harness) {
            case CLAUDE_CODE -> "Claude";
            case CODEX -> "Codex";
            case CURSOR_AGENT -> "Cursor";
            case GROK -> "Grok";
            case OPENCODE -> "OpenCode";
            case PI -> "Pi";
            case MUSE -> "Muse";
            case SHELL -> throw new IllegalArgumentException("Shell has no provider budget");
        };
    }

    private static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setEnabled(false);
        label.setAlignmentX(0f);
        return label;
    }
}
