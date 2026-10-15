package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.machine.Machine;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import java.awt.Window;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Add/edit form for one registered machine: a label, an SSH destination (whatever
 * you'd type after {@code ssh }), and whether its macOS login keychain needs unlocking
 * before a harness launches there. */
final class MachineEditDialog {

    static Optional<Machine> show(Window parent) {
        return show(parent, null);
    }

    /** {@code existing} non-null edits it in place (same id, so sessions already
     * created against it stay associated); null adds a brand new machine. */
    static Optional<Machine> show(Window parent, Machine existing) {
        JDialog dialog = DialogChrome.newDialog(parent, existing == null ? "Add machine" : "Edit machine");
        DialogChrome.Form form = DialogChrome.form();

        JTextField labelField = new JTextField(existing != null ? existing.label() : "", 24);
        JTextField destinationField = new JTextField(existing != null ? existing.destination() : "", 24);
        JCheckBox keychainBox = new JCheckBox(
                "Unlock this machine's login keychain before launching a harness",
                existing != null && existing.needsKeychainUnlock());

        form.addRow("Label:", labelField);
        form.addRow("SSH destination:", destinationField);
        form.addRow("Keychain:", keychainBox);

        JLabel hint = new JLabel("<html><body width='340'>The destination is whatever you'd "
                + "type after 'ssh ' — a ~/.ssh/config Host alias or user@host. The "
                + "keychain option is macOS-only; leave it off for Linux machines.</body></html>");
        form.constraints.gridx = 0;
        form.constraints.gridy = form.reserveRow();
        form.constraints.gridwidth = 3;
        form.panel.add(hint, form.constraints);

        AtomicReference<Optional<Machine>> resultHolder = new AtomicReference<>(Optional.empty());
        JButton ok = new JButton(existing == null ? "Add" : "Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            String label = labelField.getText().strip();
            String destination = destinationField.getText().strip();
            if (label.isEmpty() || destination.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Label and SSH destination are both required.",
                        "Missing field", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Machine machine = existing == null
                    ? Machine.create(label, destination, keychainBox.isSelected())
                    : existing.withDetails(label, destination, keychainBox.isSelected());
            resultHolder.set(Optional.of(machine));
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());

        DialogChrome.layout(dialog, form.panel, DialogChrome.buttons(ok, cancel));
        DialogChrome.show(dialog, parent, ok, cancel);
        return resultHolder.get();
    }

    private MachineEditDialog() {
    }
}
