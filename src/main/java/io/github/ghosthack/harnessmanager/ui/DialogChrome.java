package io.github.ghosthack.harnessmanager.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;

/**
 * Shared chrome for the application's hand-built modal dialogs: a padded GridBagLayout
 * form, a padded button row, Escape bound to Cancel, and the common pack/center/show
 * sequence. Each dialog still creates its own fields and wires its own button listeners
 * (they need the {@link JDialog} instance to validate input and dispose it), so this only
 * removes the boilerplate that used to be copy-pasted around that.
 */
final class DialogChrome {

    /** A GridBagLayout form panel with standard outer padding, per-cell spacing, and a
     * row cursor so callers just keep calling {@code addRow(...)}. */
    static final class Form {
        final JPanel panel = new JPanel(new GridBagLayout());
        final GridBagConstraints constraints = new GridBagConstraints();
        private int row = 0;

        private Form() {
            panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
            constraints.insets = new Insets(4, 4, 4, 4);
            constraints.fill = GridBagConstraints.HORIZONTAL;
        }

        void addRow(String label, JComponent field) {
            addRow(label, field, null);
        }

        void addRow(String label, JComponent field, JComponent trailing) {
            constraints.gridy = row++;
            constraints.gridx = 0;
            constraints.gridwidth = 1;
            constraints.weightx = 0;
            panel.add(new JLabel(label), constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            panel.add(field, constraints);
            if (trailing != null) {
                constraints.gridx = 2;
                constraints.weightx = 0;
                panel.add(trailing, constraints);
            }
        }

        /** Reserves the next row index for a one-off placement (e.g. a full-width hint
         * label) that needs a custom column span instead of the label/field shape. */
        int reserveRow() {
            return row++;
        }
    }

    static Form form() {
        return new Form();
    }

    /** The standard button row: default FlowLayout, padded to match the form. */
    static JPanel buttons(AbstractButton... buttons) {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        for (AbstractButton button : buttons) {
            panel.add(button);
        }
        return panel;
    }

    /** A modal JDialog shell: BorderLayout, dispose-on-close, no content yet. Callers
     * build their form/buttons against this instance (button listeners typically capture
     * it to validate and {@code dispose()}), then finish with {@link #layout} and
     * {@link #show}. */
    static JDialog newDialog(Window parent, String title) {
        JDialog dialog = new JDialog(parent, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());
        return dialog;
    }

    /** Places the form at CENTER and the button row at SOUTH. */
    static void layout(JDialog dialog, JPanel form, JPanel buttons) {
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
    }

    /** Binds Escape to {@code cancelButton}, makes {@code defaultButton} the Enter
     * target, packs, centers on {@code parent}, and shows — blocking until the modal
     * dialog is disposed. */
    static void show(JDialog dialog, Window parent, JButton defaultButton, AbstractButton cancelButton) {
        DialogKeys.bindEscapeTo(dialog.getRootPane(), cancelButton);
        dialog.getRootPane().setDefaultButton(defaultButton);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private DialogChrome() {
    }
}
