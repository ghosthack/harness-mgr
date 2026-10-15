package io.github.ghosthack.harnessmanager.ui;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import java.awt.Window;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prompts for the password to unlock a registered machine's macOS login keychain
 * before a harness launches or resumes there. Never persisted — the caller caches the
 * unlock outcome in memory for the app's run (see {@code MainWindow}), and this dialog
 * itself keeps no state past returning.
 */
final class CredentialsDialog {

    static Optional<char[]> show(Window parent, String machineLabel) {
        JDialog dialog = DialogChrome.newDialog(parent, "Unlock keychain");
        DialogChrome.Form form = DialogChrome.form();

        JLabel hint = new JLabel("<html><body width='320'>'" + escapeHtml(machineLabel)
                + "' needs its login keychain unlocked before a harness there can read its "
                + "stored credentials.</body></html>");
        form.constraints.gridx = 0;
        form.constraints.gridy = form.reserveRow();
        form.constraints.gridwidth = 2;
        form.panel.add(hint, form.constraints);

        JPasswordField passwordField = new JPasswordField(20);
        form.addRow("Password:", passwordField);

        AtomicReference<Optional<char[]>> resultHolder = new AtomicReference<>(Optional.empty());
        JButton ok = new JButton("Unlock");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            char[] password = passwordField.getPassword();
            if (password.length == 0) {
                JOptionPane.showMessageDialog(dialog, "Password is required.",
                        "Missing password", JOptionPane.WARNING_MESSAGE);
                return;
            }
            resultHolder.set(Optional.of(password));
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());

        DialogChrome.layout(dialog, form.panel, DialogChrome.buttons(ok, cancel));
        DialogChrome.show(dialog, parent, ok, cancel);
        return resultHolder.get();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private CredentialsDialog() {
    }
}
