package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class DialogKeysTest {

    @Test
    void escapeInvokesTheSameButtonAsCancel() {
        JRootPane rootPane = new JRootPane();
        JButton cancel = new JButton("Cancel");
        AtomicInteger cancellations = new AtomicInteger();
        cancel.addActionListener(event -> cancellations.incrementAndGet());

        DialogKeys.bindEscapeTo(rootPane, cancel);

        Object actionKey = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        Action escape = rootPane.getActionMap().get(actionKey);
        assertNotNull(escape);
        escape.actionPerformed(new ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "escape"));

        assertEquals(1, cancellations.get());
    }
}
