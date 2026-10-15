package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.host.RoutingSessionHost;
import io.github.ghosthack.harnessmanager.machine.Machine;
import io.github.ghosthack.harnessmanager.machine.MachineRegistry;
import io.github.ghosthack.harnessmanager.tmux.SshExec;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.util.function.Supplier;

/**
 * Manages registered SSH machines: add/edit/remove, "Test connection" (also the first
 * real exercise — and pinning, under {@code StrictHostKeyChecking=accept-new} — of a
 * new machine's host key), and a manual "Unlock now" for a keychain-flagged machine.
 * New UI territory for this app — every other dialog is a flat single-entry form; this
 * one owns a persisted list, so it isn't built on {@link DialogChrome#form()}.
 */
final class MachinesDialog {

    static void show(Window parent, MachineRegistry registry, RoutingSessionHost router, SshExec ssh) {
        JDialog dialog = DialogChrome.newDialog(parent, "Machines");

        DefaultListModel<Machine> listModel = new DefaultListModel<>();
        registry.machines().forEach(listModel::addElement);
        JList<Machine> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                if (value instanceof Machine machine) {
                    setText(machine.label() + "  (" + machine.destination() + ")");
                    setIcon(LucidIcon.wifi(16));
                    setIconTextGap(8);
                }
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(420, 220));

        JButton add = new JButton("Add…");
        JButton edit = new JButton("Edit…");
        JButton remove = new JButton("Remove");
        JButton test = new JButton("Test connection");
        JButton unlock = new JButton("Unlock now");
        JButton[] actionButtons = {add, edit, remove, test, unlock};

        JLabel status = new JLabel(" ");
        status.setForeground(UIManager.getColor("Label.disabledForeground"));

        Runnable refreshButtons = () -> {
            Machine selected = list.getSelectedValue();
            edit.setEnabled(selected != null);
            remove.setEnabled(selected != null);
            test.setEnabled(selected != null);
            unlock.setEnabled(selected != null && selected.needsKeychainUnlock());
        };
        list.addListSelectionListener(e -> refreshButtons.run());
        refreshButtons.run();

        add.addActionListener(e -> MachineEditDialog.show(dialog).ifPresent(machine -> {
            registry.put(machine);
            listModel.addElement(machine);
            list.setSelectedValue(machine, true);
        }));
        edit.addActionListener(e -> {
            Machine selected = list.getSelectedValue();
            if (selected == null) {
                return;
            }
            MachineEditDialog.show(dialog, selected).ifPresent(edited -> {
                registry.put(edited);
                listModel.set(listModel.indexOf(selected), edited);
                list.setSelectedValue(edited, true);
            });
        });
        remove.addActionListener(e -> {
            Machine selected = list.getSelectedValue();
            if (selected == null) {
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(dialog,
                    "Remove '" + selected.label() + "'? Sessions already created on it stay in "
                            + "the sidebar as dead entries.",
                    "Remove machine", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.OK_OPTION) {
                registry.remove(selected.id());
                listModel.removeElement(selected);
            }
        });
        test.addActionListener(e -> {
            Machine selected = list.getSelectedValue();
            if (selected == null) {
                return;
            }
            runAsync(actionButtons, status, refreshButtons, "Testing '" + selected.label() + "'…", () -> {
                router.refreshMachine(selected);
                return "'" + selected.label() + "' is reachable and tmux resolves there.";
            });
        });
        unlock.addActionListener(e -> {
            Machine selected = list.getSelectedValue();
            if (selected == null) {
                return;
            }
            CredentialsDialog.show(dialog, selected.label()).ifPresent(password ->
                    runAsync(actionButtons, status, refreshButtons,
                            "Unlocking '" + selected.label() + "'…", () -> {
                                SshExec.Result r = ssh.unlockKeychain(selected.destination(), password);
                                if (!r.ok()) {
                                    throw new RuntimeException(
                                            "wrong password, or an SSH error: " + r.output().strip());
                                }
                                return "Unlocked the keychain on '" + selected.label() + "'.";
                            }));
        });

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.add(add);
        buttons.add(edit);
        buttons.add(remove);
        buttons.add(Box.createVerticalStrut(12));
        buttons.add(test);
        buttons.add(unlock);

        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        body.add(scroll, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.EAST);
        body.add(status, BorderLayout.SOUTH);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());

        dialog.add(body, BorderLayout.CENTER);
        dialog.add(DialogChrome.buttons(close), BorderLayout.SOUTH);
        DialogChrome.show(dialog, parent, close, close);
    }

    /** Disables every action button, runs {@code work} on a virtual thread (SSH calls
     * block), then restores button state and shows the outcome — success message or,
     * on any thrown exception, its message prefixed as a failure. Never runs on the EDT. */
    private static void runAsync(JButton[] toDisable, JLabel status, Runnable onSettle,
                                 String startMessage, Supplier<String> work) {
        for (JButton b : toDisable) {
            b.setEnabled(false);
        }
        status.setText(startMessage);
        Thread.ofVirtual().start(() -> {
            String outcome;
            try {
                outcome = work.get();
            } catch (RuntimeException e) {
                outcome = "Failed: " + e.getMessage();
            }
            String finalOutcome = outcome;
            SwingUtilities.invokeLater(() -> {
                status.setText(finalOutcome);
                onSettle.run();
            });
        });
    }

    private MachinesDialog() {
    }
}
