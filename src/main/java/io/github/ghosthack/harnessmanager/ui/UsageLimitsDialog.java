package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.usage.HarnessUsageService;
import io.github.ghosthack.harnessmanager.usage.UsageSnapshot;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Selects a harness and displays its current provider-account usage and limits. */
final class UsageLimitsDialog {

    private static final DateTimeFormatter RESET_FORMAT = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    static void show(Window parent, Executor executor, HarnessType initialHarness) {
        JDialog dialog = DialogChrome.newDialog(parent, "Usage & limits");

        JComboBox<HarnessType> harness = new JComboBox<>(HarnessType.values());
        harness.setRenderer(HarnessIcons.comboRenderer());
        harness.setSelectedItem(initialHarness == null ? HarnessType.CLAUDE_CODE : initialHarness);
        JButton refresh = new JButton("Refresh");
        refresh.setIcon(LucidIcon.refresh(16));

        JPanel selector = new JPanel(new GridBagLayout());
        selector.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 8);
        c.gridx = 0;
        selector.add(new JLabel("Harness:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        selector.add(harness, c);
        c.gridx = 2;
        c.weightx = 0;
        c.insets = new Insets(0, 0, 0, 0);
        selector.add(refresh, c);
        dialog.add(selector, BorderLayout.NORTH);

        JPanel resultHost = new JPanel(new BorderLayout());
        resultHost.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
        dialog.add(resultHost, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        DialogKeys.bindEscapeTo(dialog.getRootPane(), close);
        dialog.add(DialogChrome.buttons(close), BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(refresh);

        HarnessUsageService service = new HarnessUsageService();
        AtomicInteger requestSequence = new AtomicInteger();
        Runnable load = () -> {
            HarnessType selected = (HarnessType) harness.getSelectedItem();
            int request = requestSequence.incrementAndGet();
            harness.setEnabled(false);
            refresh.setEnabled(false);
            replace(resultHost, loadingPanel(selected));
            executor.execute(() -> {
                try {
                    UsageSnapshot snapshot = service.load(selected);
                    SwingUtilities.invokeLater(() -> {
                        if (!dialog.isDisplayable() || request != requestSequence.get()) return;
                        replace(resultHost, snapshotPanel(snapshot));
                        harness.setEnabled(true);
                        refresh.setEnabled(true);
                    });
                } catch (Exception failure) {
                    SwingUtilities.invokeLater(() -> {
                        if (!dialog.isDisplayable() || request != requestSequence.get()) return;
                        replace(resultHost, errorPanel(selected, failure));
                        harness.setEnabled(true);
                        refresh.setEnabled(true);
                    });
                }
            });
        };
        harness.addActionListener(e -> load.run());
        refresh.addActionListener(e -> load.run());

        dialog.setMinimumSize(new Dimension(540, 380));
        dialog.setSize(580, 470);
        dialog.setLocationRelativeTo(parent);
        SwingUtilities.invokeLater(load);
        dialog.setVisible(true);
    }

    static JPanel snapshotPanel(UsageSnapshot snapshot) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel heading = new JLabel(snapshot.heading());
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 2));
        heading.setAlignmentX(0f);
        content.add(heading);
        JLabel source = muted("Source: " + snapshot.source());
        source.setAlignmentX(0f);
        content.add(source);
        content.add(Box.createVerticalStrut(14));

        if (snapshot.limits().isEmpty()) {
            JLabel empty = new JLabel("No live limit windows available.");
            empty.setAlignmentX(0f);
            content.add(empty);
        } else {
            for (UsageSnapshot.Limit limit : snapshot.limits()) {
                content.add(limitPanel(limit));
                content.add(Box.createVerticalStrut(10));
            }
        }

        if (!snapshot.statistics().isEmpty()) {
            JLabel statsHeading = new JLabel("Usage details");
            statsHeading.setFont(statsHeading.getFont().deriveFont(Font.BOLD));
            statsHeading.setAlignmentX(0f);
            content.add(Box.createVerticalStrut(4));
            content.add(statsHeading);
            content.add(Box.createVerticalStrut(5));
            content.add(statisticsPanel(snapshot));
        }

        snapshot.note().ifPresent(note -> {
            content.add(Box.createVerticalStrut(14));
            JLabel label = muted(note);
            label.setAlignmentX(0f);
            content.add(label);
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(content, BorderLayout.NORTH);
        return wrapper;
    }

    private static JPanel limitPanel(UsageSnapshot.Limit limit) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(0f);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));

        JLabel label = new JLabel(limit.label());
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setAlignmentX(0f);
        panel.add(label);
        panel.add(Box.createVerticalStrut(5));

        JProgressBar progress = new JProgressBar(0, 100);
        progress.setValue(limit.usedPercent());
        progress.setStringPainted(true);
        progress.setString(limit.usedPercent() + "% used");
        progress.setAlignmentX(0f);
        panel.add(progress);

        StringBuilder detail = new StringBuilder();
        limit.detail().ifPresent(detail::append);
        limit.resetsAt().ifPresent(reset -> {
            if (!detail.isEmpty()) detail.append(" · ");
            detail.append("Resets ").append(formatReset(reset));
        });
        if (!detail.isEmpty()) {
            JLabel detailLabel = muted(detail.toString());
            detailLabel.setAlignmentX(0f);
            panel.add(Box.createVerticalStrut(3));
            panel.add(detailLabel);
        }
        return panel;
    }

    private static JPanel statisticsPanel(UsageSnapshot snapshot) {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setAlignmentX(0f);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 16);
        for (int row = 0; row < snapshot.statistics().size(); row++) {
            UsageSnapshot.Statistic statistic = snapshot.statistics().get(row);
            c.gridy = row;
            c.gridx = 0;
            c.weightx = 0;
            grid.add(muted(statistic.label() + ":"), c);
            c.gridx = 1;
            c.weightx = 1;
            grid.add(new JLabel(statistic.value()), c);
        }
        return grid;
    }

    private static JPanel loadingPanel(HarnessType harness) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel("Loading " + harness.id() + " usage…", SwingConstants.CENTER);
        label.setIcon(HarnessIcons.forType(harness));
        label.setIconTextGap(8);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel errorPanel(HarnessType harness, Exception failure) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel("Couldn’t load " + harness.id() + " usage");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setAlignmentX(0f);
        panel.add(heading);
        panel.add(Box.createVerticalStrut(8));
        String message = failure.getMessage();
        JLabel detail = new JLabel(message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message);
        detail.setAlignmentX(0f);
        panel.add(detail);
        return panel;
    }

    private static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setEnabled(false);
        return label;
    }

    private static String formatReset(Instant instant) {
        return RESET_FORMAT.format(instant.atZone(ZoneId.systemDefault()));
    }

    private static void replace(JPanel host, JPanel content) {
        host.removeAll();
        host.add(new JScrollPane(content), BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    private UsageLimitsDialog() {
    }
}
