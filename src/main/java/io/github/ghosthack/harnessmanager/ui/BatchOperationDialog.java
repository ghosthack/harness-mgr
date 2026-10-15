package io.github.ghosthack.harnessmanager.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared shell for the toolbar's "pick some sessions, then act on them" dialogs (Batch
 * close, Remove dead sessions): a {@link SessionChecklist} to choose the targets, then a
 * confirm button that swaps the dialog to a progress bar advancing one candidate at a
 * time, ending on a summary of how many succeeded and — if any didn't — why. The
 * per-candidate work itself ({@link Operation}) is the only thing that differs between
 * callers; everything about presenting and sequencing it lives here once.
 */
final class BatchOperationDialog {

    private static final String CARD_SELECT = "select";
    private static final String CARD_PROGRESS = "progress";

    /** Runs on the background executor for one candidate. Empty means it succeeded;
     * otherwise the human-readable "name — reason" line to show in the summary. */
    @FunctionalInterface
    interface Operation {
        Optional<String> attempt(SidebarEntry entry);
    }

    /** The verb forms the progress/summary text is built from, e.g. ("Closing", "Closed",
     * "close") or ("Removing", "Removed", "remove"). */
    record ActionWords(String gerund, String pastTenseCapitalized, String infinitive) {
    }

    static void show(Window parent, String title, String introHtml, String confirmLabel,
                     List<SidebarEntry> candidates, Predicate<SidebarEntry> defaultChecked,
                     Function<SidebarEntry, String> rowLabel, ActionWords words,
                     ExecutorService backgroundExecutor, Operation operation,
                     Consumer<SidebarEntry> onItemSucceeded, Runnable onFinished) {
        JDialog dialog = DialogChrome.newDialog(parent, title);

        SessionChecklist checklist = SessionChecklist.build(candidates, defaultChecked, rowLabel);

        JButton confirm = new JButton(confirmLabel);
        confirm.setEnabled(!checklist.selected().isEmpty());
        checklist.addSelectionListener(() -> confirm.setEnabled(!checklist.selected().isEmpty()));

        JLabel intro = new JLabel(introHtml);
        JPanel selectPanel = new JPanel(new BorderLayout(0, 8));
        selectPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        selectPanel.add(intro, BorderLayout.NORTH);
        selectPanel.add(checklist.component(), BorderLayout.CENTER);

        JProgressBar progressBar = new JProgressBar(0, Math.max(1, candidates.size()));
        progressBar.setStringPainted(true);
        JLabel progressLabel = new JLabel(" ");
        JTextArea summary = new JTextArea();
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setOpaque(false);
        summary.setFont(UIManager.getFont("Label.font"));
        JScrollPane summaryScroll = new JScrollPane(summary,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        summaryScroll.setPreferredSize(new Dimension(480, 240));
        summaryScroll.setBorder(BorderFactory.createEmptyBorder());
        summaryScroll.setVisible(false);

        JPanel progressPanel = new JPanel(new BorderLayout(0, 8));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        JPanel progressTop = new JPanel(new BorderLayout(0, 6));
        progressTop.add(progressLabel, BorderLayout.NORTH);
        progressTop.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(progressTop, BorderLayout.NORTH);
        progressPanel.add(summaryScroll, BorderLayout.CENTER);

        CardLayout cardLayout = new CardLayout();
        JPanel cards = new JPanel(cardLayout);
        cards.add(selectPanel, CARD_SELECT);
        cards.add(progressPanel, CARD_PROGRESS);

        JButton cancel = new JButton("Cancel");
        JButton done = new JButton("Done");
        done.setVisible(false);
        cancel.addActionListener(e -> dialog.dispose());
        done.addActionListener(e -> dialog.dispose());

        confirm.addActionListener(e -> {
            List<SidebarEntry> selected = checklist.selected();
            if (selected.isEmpty()) {
                return;
            }
            confirm.setVisible(false);
            cancel.setEnabled(false);
            progressBar.setMaximum(selected.size());
            progressBar.setValue(0);
            progressLabel.setText(words.gerund() + " " + selected.size() + " session"
                    + (selected.size() == 1 ? "" : "s") + "…");
            cardLayout.show(cards, CARD_PROGRESS);
            dialog.pack();
            run(dialog, selected, words, backgroundExecutor, operation, onItemSucceeded, onFinished,
                    progressBar, progressLabel, summary, summaryScroll, cancel, done);
        });

        JPanel buttons = DialogChrome.buttons(confirm, cancel, done);
        dialog.add(cards, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        DialogChrome.show(dialog, parent, confirm, cancel);
    }

    /** Sequential background pass over the chosen sessions — never parallel, since two
     * mutations racing on the same tmux server or the same registry is unnecessary risk
     * for an operation this destructive. Each iteration reports back to the EDT
     * immediately so the progress bar and count advance live rather than only at the end. */
    private static void run(JDialog dialog, List<SidebarEntry> selected, ActionWords words,
                            ExecutorService backgroundExecutor, Operation operation,
                            Consumer<SidebarEntry> onItemSucceeded, Runnable onFinished,
                            JProgressBar progressBar, JLabel progressLabel, JTextArea summary,
                            JScrollPane summaryScroll, JButton cancel, JButton done) {
        backgroundExecutor.execute(() -> {
            int total = selected.size();
            List<String> succeeded = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            int index = 0;
            for (SidebarEntry entry : selected) {
                index++;
                int current = index;
                String name = entry.tmuxSession();
                SwingUtilities.invokeLater(() -> progressLabel.setText(
                        words.gerund() + " '" + name + "'… (" + current + " of " + total + ")"));
                Optional<String> failure = operation.attempt(entry);
                SwingUtilities.invokeLater(() -> {
                    if (failure.isEmpty()) {
                        succeeded.add(name);
                        onItemSucceeded.accept(entry);
                    } else {
                        failed.add(failure.get());
                    }
                    progressBar.setValue(current);
                });
            }
            SwingUtilities.invokeLater(() -> {
                StringBuilder text = new StringBuilder();
                text.append(words.pastTenseCapitalized()).append(' ').append(succeeded.size())
                        .append(" of ").append(total).append(" session")
                        .append(total == 1 ? "" : "s").append(".\n");
                if (!failed.isEmpty()) {
                    text.append("\nCould not ").append(words.infinitive()).append(' ')
                            .append(failed.size()).append(":\n");
                    for (String failureText : failed) {
                        text.append("• ").append(failureText).append('\n');
                    }
                }
                summary.setText(text.toString());
                summary.setCaretPosition(0);
                summaryScroll.setVisible(true);
                progressLabel.setText("Done.");
                cancel.setVisible(false);
                done.setVisible(true);
                DialogKeys.bindEscapeTo(dialog.getRootPane(), done);
                dialog.getRootPane().setDefaultButton(done);
                dialog.pack();
                onFinished.run();
            });
        });
    }

    private BatchOperationDialog() {
    }
}
