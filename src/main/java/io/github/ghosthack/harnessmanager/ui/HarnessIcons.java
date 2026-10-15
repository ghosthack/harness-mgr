package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;

import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import java.awt.Component;

/** Shared 16px harness identities for sidebar rows and harness-selection controls. */
final class HarnessIcons {

    private static final int SIZE = 16;
    private static final int TEXT_GAP = 8;

    private static final Icon CLAUDE = ProviderIcon.anthropic(SIZE);
    private static final Icon CODEX = ProviderIcon.openAi(SIZE);
    private static final Icon CURSOR = ProviderIcon.cursor(SIZE);
    private static final Icon GROK = ProviderIcon.grok(SIZE);
    private static final Icon OPENCODE = ProviderIcon.opencode(SIZE);
    private static final Icon PI = ProviderIcon.pi(SIZE);
    private static final Icon MUSE = ProviderIcon.muse(SIZE);
    private static final Icon SHELL = LucidIcon.shell(SIZE);

    static Icon forType(HarnessType type) {
        return switch (type) {
            case CLAUDE_CODE -> CLAUDE;
            case CODEX -> CODEX;
            case CURSOR_AGENT -> CURSOR;
            case GROK -> GROK;
            case OPENCODE -> OPENCODE;
            case PI -> PI;
            case MUSE -> MUSE;
            case SHELL -> SHELL;
        };
    }

    /** A renderer for both the combo's closed selection and its expanded list. */
    static ListCellRenderer<? super HarnessType> comboRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof HarnessType type) {
                    setText(type.id());
                    setIcon(forType(type));
                    setIconTextGap(TEXT_GAP);
                } else {
                    setIcon(null);
                }
                return this;
            }
        };
    }

    private HarnessIcons() {
    }
}
