package io.github.ghosthack.harnessmanager.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.Objects;

/**
 * Shared confirmation step for actions that end a session or transfer ownership away
 * from harness-stimulator. Keeping the copy and layout here makes the blast radius
 * visible before every action without letting toolbar, context-menu, and keyboard
 * entry points drift into different confirmations.
 */
final class PreflightDialog {

    /** One affected session as it should appear in the review list. */
    record Session(String displayName, String tmuxSession, String status) {
        Session {
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(tmuxSession, "tmuxSession");
            Objects.requireNonNull(status, "status");
        }

        String label() {
            String name = displayName.equals(tmuxSession)
                    ? displayName : displayName + " (tmux: " + tmuxSession + ")";
            return name + " — " + status;
        }
    }

    /** Testable, presentation-independent description of one pre-flight. */
    record Request(String title, String prompt, List<Session> sessions, String consequence,
                   String confirmLabel, int messageType) {
        Request {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(prompt, "prompt");
            sessions = List.copyOf(sessions);
            if (sessions.isEmpty()) {
                throw new IllegalArgumentException("a pre-flight needs at least one session");
            }
            Objects.requireNonNull(consequence, "consequence");
            Objects.requireNonNull(confirmLabel, "confirmLabel");
        }
    }

    static Request kill(Session session) {
        return new Request(
                "Kill session",
                "Review the session before killing it.",
                List.of(session),
                "This kills the tmux session and ends the harness process inside it. "
                        + "Any unsaved terminal work is lost, and this cannot be undone.",
                "Kill session",
                JOptionPane.WARNING_MESSAGE);
    }

    static Request close(Session session) {
        return new Request(
                "Close session",
                "Review the session before closing it gracefully.",
                List.of(session),
                "Harness Manager will ask the harness and its shell to exit. If the "
                        + "session closes, its tmux terminal and scrollback cannot be restored, "
                        + "but its saved entry remains available to Resume. If it is busy or "
                        + "does not exit in time, it is left running.",
                "Close session",
                JOptionPane.QUESTION_MESSAGE);
    }

    static Request reload(Session session) {
        return new Request(
                "Reload session",
                "Review the session before reloading it.",
                List.of(session),
                "Harness Manager will ask the harness and its shell to exit, then "
                        + "immediately relaunch the harness in a fresh tmux session, resuming "
                        + "its conversation where it left off. Useful after the harness "
                        + "auto-updates and needs a restart to pick up the new version. If it "
                        + "is busy or does not exit in time, it is left running.",
                "Reload session",
                JOptionPane.QUESTION_MESSAGE);
    }

    static Request closeAll(List<Session> sessions) {
        return new Request(
                "Close all sessions",
                "Review the " + sessions.size() + " live session"
                        + (sessions.size() == 1 ? "" : "s") + " before closing them.",
                sessions,
                "Each harness is asked to exit gracefully. A closed tmux terminal and its "
                        + "scrollback cannot be restored, but every saved entry remains available "
                        + "to Resume. Busy sessions and sessions that do not exit in time are left "
                        + "running and reported afterward.",
                "Close all",
                JOptionPane.QUESTION_MESSAGE);
    }

    static Request stopStimAndReattach(Session session) {
        return new Request(
                "Stop stimulation and reattach",
                "Review the session before taking back manual control.",
                List.of(session),
                "Harness Manager will stop harness-stimulator gracefully, keep the tmux "
                        + "session running, and reattach it for interactive use. The current "
                        + "stimulation run cannot be resumed after it stops.",
                "Stop and reattach",
                JOptionPane.QUESTION_MESSAGE);
    }

    static boolean show(Component parent, Request request) {
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel prompt = new JLabel(request.prompt());
        prompt.setFont(prompt.getFont().deriveFont(Font.BOLD));
        content.add(prompt, BorderLayout.NORTH);

        content.add(sessionReview(request.sessions()), BorderLayout.CENTER);

        // HTML gives Swing a width before pack(), so the warning computes its full
        // wrapped height instead of being packed as one line and then clipped.
        JLabel consequence = wrappingLabel(request.consequence());
        content.add(consequence, BorderLayout.SOUTH);

        JButton confirm = new JButton(request.confirmLabel());
        JButton cancel = new JButton("Cancel");

        // Real JButtons (rather than showOptionDialog's String options) so keyboard
        // navigation works as expected: a JRootPane invokes only its one "default"
        // button on Enter, no matter which button currently has focus, so leaving
        // Cancel wired as that fixed default meant tabbing to the confirm button and
        // pressing Enter silently cancelled instead. Moving the default button along
        // with focus keeps Cancel safe on open while letting Tab, then Space/Enter,
        // activate whichever button was actually navigated to.
        JOptionPane pane = new JOptionPane(
                content,
                request.messageType(), JOptionPane.DEFAULT_OPTION, null,
                new Object[]{confirm, cancel}, cancel);
        JDialog dialog = pane.createDialog(parent, request.title());

        boolean[] confirmed = {false};
        confirm.addActionListener(e -> {
            confirmed[0] = true;
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        DialogKeys.bindEscapeTo(dialog.getRootPane(), cancel);

        FocusAdapter followFocus = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                dialog.getRootPane().setDefaultButton((JButton) e.getSource());
            }
        };
        confirm.addFocusListener(followFocus);
        cancel.addFocusListener(followFocus);
        dialog.getRootPane().setDefaultButton(cancel);

        dialog.setVisible(true);
        return confirmed[0];
    }

    /**
     * One/few sessions do not need a scroll pane at all. For a large close-all blast
     * radius, cap the height and add only a vertical scrollbar; a horizontal scrollbar
     * used to consume half of the one-row viewport and visibly clip its label.
     */
    static JComponent sessionReview(List<Session> sessions) {
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        for (Session session : sessions) {
            JLabel row = wrappingLabel(session.label());
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            rows.add(row);
        }

        if (sessions.size() <= 7) {
            rows.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEtchedBorder(), rows.getBorder()));
            return rows;
        }

        JScrollPane scroll = new JScrollPane(rows,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(520,
                Math.min(190, rows.getPreferredSize().height + 4)));
        return scroll;
    }

    private static JLabel wrappingLabel(String text) {
        return new JLabel("<html><body width='500'>" + escapeHtml(text) + "</body></html>");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private PreflightDialog() {
    }
}
