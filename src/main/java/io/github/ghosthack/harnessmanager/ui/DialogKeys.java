package io.github.ghosthack.harnessmanager.ui;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/** Shared keyboard behavior for the application's hand-built dialogs. */
final class DialogKeys {

    private static final String CANCEL_ACTION = DialogKeys.class.getName() + ".cancel";

    /** Makes Escape take the dialog's real Cancel/Close path. */
    static void bindEscapeTo(JRootPane rootPane, AbstractButton cancelButton) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CANCEL_ACTION);
        rootPane.getActionMap().put(CANCEL_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                cancelButton.doClick();
            }
        });
    }

    private DialogKeys() {
    }
}
