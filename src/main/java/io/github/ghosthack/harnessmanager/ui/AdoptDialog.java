package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Adopt an unmanaged {@code hm-}-prefixed tmux session into the registry. */
final class AdoptDialog {

    record Result(String projectPath, HarnessType harnessType, Optional<String> harnessSessionId) {
    }

    static Optional<Result> show(Window parent, String tmuxSession, String guessedProjectPath) {
        JDialog dialog = DialogChrome.newDialog(parent, "Adopt " + tmuxSession);
        DialogChrome.Form form = DialogChrome.form();

        JTextField pathField = new JTextField(guessedProjectPath, 30);
        JComboBox<HarnessType> harnessCombo = new JComboBox<>(HarnessType.values());
        JTextField sessionIdField = new JTextField(20);

        form.addRow("tmux session:", new JLabel(tmuxSession));
        form.addRow("Project path:", pathField);
        form.addRow("Harness:", harnessCombo);
        form.addRow("Harness session id (optional):", sessionIdField);

        AtomicReference<Optional<Result>> resultHolder = new AtomicReference<>(Optional.empty());
        JButton ok = new JButton("Adopt");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            String text = pathField.getText().strip();
            if (text.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Project path is required.",
                        "Missing path", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Path path = UserPath.expand(text);
            if (!Files.isDirectory(path)) {
                JOptionPane.showMessageDialog(dialog, "'" + path + "' is not a directory.",
                        "Invalid path", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String id = sessionIdField.getText().strip();
            resultHolder.set(Optional.of(new Result(path.toString(),
                    (HarnessType) harnessCombo.getSelectedItem(), id.isEmpty() ? Optional.empty() : Optional.of(id))));
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());

        DialogChrome.layout(dialog, form.panel, DialogChrome.buttons(ok, cancel));
        DialogChrome.show(dialog, parent, ok, cancel);
        return resultHolder.get();
    }

    private AdoptDialog() {
    }
}
