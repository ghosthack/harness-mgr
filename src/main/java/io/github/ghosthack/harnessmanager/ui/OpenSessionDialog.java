package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.machine.Machine;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Open…" dialog: project path (with Browse…), harness dropdown, machine dropdown
 * (Local, or a registered machine — see {@code MachinesDialog}), optional harness
 * session id (blank = start fresh).
 */
final class OpenSessionDialog {

    /** {@code machine} empty means local. A remote path is not locally checkable (and
     * must be absolute — no {@code ~} expansion happens server-side), so callers must
     * skip {@link Files#isDirectory} for it and let tmux's own {@code -c} failure
     * surface instead. */
    record Result(Path projectPath, HarnessType harnessType, Optional<String> harnessSessionId,
                  Optional<Machine> machine) {
    }

    static Optional<Result> show(Window parent, List<Machine> machines) {
        return show(parent, null, null, machines);
    }

    /**
     * As {@link #show(Window, List)}, but seeded: a non-null {@code prefillPath}
     * replaces the home-directory default, and a non-null {@code prefillHarness}
     * preselects the dropdown — the toolbar's open-here button hands over the selected
     * session's pair.
     */
    static Optional<Result> show(Window parent, String prefillPath, HarnessType prefillHarness,
                                  List<Machine> machines) {
        JDialog dialog = DialogChrome.newDialog(parent, "New session");
        DialogChrome.Form form = DialogChrome.form();

        Path defaultFolder = Path.of(System.getProperty("user.home"));
        JTextField pathField = new JTextField(
                prefillPath != null ? prefillPath : defaultFolder.toString(), 30);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(
                    pathField.getText().isBlank() ? defaultFolder.toFile()
                            : UserPath.expand(pathField.getText()).toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                pathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JComboBox<HarnessType> harnessCombo = new JComboBox<>(HarnessType.values());
        harnessCombo.setRenderer(HarnessIcons.comboRenderer());
        if (prefillHarness != null) {
            harnessCombo.setSelectedItem(prefillHarness);
        }

        // null stands for "Local" — every entry after it is a registered machine.
        JComboBox<Machine> machineCombo = new JComboBox<>();
        machineCombo.addItem(null);
        for (Machine machine : machines) {
            machineCombo.addItem(machine);
        }
        machineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Machine machine) {
                    setText(machine.label());
                    setIcon(LucidIcon.wifi(16));
                    setIconTextGap(8);
                } else {
                    setText("Local");
                    setIcon(null);
                }
                return this;
            }
        });

        JTextField sessionIdField = new JTextField(20);

        form.addRow("Project path:", pathField, browse);
        form.addRow("Machine:", machineCombo);
        form.addRow("Harness:", harnessCombo);
        form.addRow("Resume session id (optional):", sessionIdField);

        JLabel hint = new JLabel(
                "Blank session id starts a fresh harness conversation; 'shell' is just your default shell.");
        hint.setHorizontalAlignment(SwingConstants.LEFT);
        form.constraints.gridx = 0;
        form.constraints.gridy = form.reserveRow();
        form.constraints.gridwidth = 3;
        form.panel.add(hint, form.constraints);

        // A remote path can't be checked from here, and must stay absolute — no local
        // Browse… and no "must be a directory" validation for it.
        machineCombo.addItemListener(e -> {
            boolean remote = machineCombo.getSelectedItem() != null;
            browse.setEnabled(!remote);
            pathField.setToolTipText(remote
                    ? "Must be an absolute path on the remote machine — no '~' expansion." : null);
        });

        AtomicReference<Optional<Result>> resultHolder = new AtomicReference<>(Optional.empty());
        JButton ok = new JButton("New");
        ok.setIcon(LucidIcon.folderPlus(16));
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            String text = pathField.getText().strip();
            if (text.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Project path is required.",
                        "Missing path", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Machine machine = (Machine) machineCombo.getSelectedItem();
            Path path;
            if (machine == null) {
                path = UserPath.expand(text);
                if (!Files.isDirectory(path)) {
                    JOptionPane.showMessageDialog(dialog, "'" + path + "' is not a directory.",
                            "Invalid path", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            } else {
                if (!text.startsWith("/")) {
                    JOptionPane.showMessageDialog(dialog,
                            "A remote project path must be absolute (start with '/').",
                            "Invalid path", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                path = Path.of(text);
            }
            HarnessType type = (HarnessType) harnessCombo.getSelectedItem();
            String id = sessionIdField.getText().strip();
            resultHolder.set(Optional.of(new Result(path, type,
                    id.isEmpty() ? Optional.empty() : Optional.of(id), Optional.ofNullable(machine))));
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());

        DialogChrome.layout(dialog, form.panel, DialogChrome.buttons(ok, cancel));
        DialogChrome.show(dialog, parent, ok, cancel);
        return resultHolder.get();
    }

    private OpenSessionDialog() {
    }
}
