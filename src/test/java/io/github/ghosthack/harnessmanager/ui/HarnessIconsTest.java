package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class HarnessIconsTest {

    @Test
    void comboRendererShowsTheSharedIconAndStableHarnessId() {
        ListCellRenderer<? super HarnessType> renderer = HarnessIcons.comboRenderer();
        JList<HarnessType> list = new JList<>(HarnessType.values());

        for (int index = 0; index < HarnessType.values().length; index++) {
            HarnessType type = HarnessType.values()[index];
            Component rendered = renderer.getListCellRendererComponent(
                    list, type, index, index == 1, false);
            JLabel label = (JLabel) rendered;
            Icon icon = HarnessIcons.forType(type);

            assertEquals(type.id(), label.getText());
            assertSame(icon, label.getIcon());
            assertEquals(16, icon.getIconWidth());
            assertEquals(16, icon.getIconHeight());
            assertEquals(8, label.getIconTextGap());
        }
    }
}
