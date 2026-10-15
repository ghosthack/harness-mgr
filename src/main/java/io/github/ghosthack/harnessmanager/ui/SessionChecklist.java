package io.github.ghosthack.harnessmanager.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared grouped-by-folder checkbox list for the toolbar's batch dialogs (Batch close,
 * Remove dead sessions): a "Select all" master checkbox, one bold header checkbox per
 * folder that toggles every session inside it, and one checkbox per session.
 *
 * <p>Every level is kept in sync via plain {@code ActionListener}s rather than
 * {@code ItemListener}s: unlike item events, an action event never fires for a
 * programmatic {@code setSelected(...)}, so a child updating its header (or a header
 * cascading to its children, or "Select all" cascading to everything) can never recurse
 * back into itself.
 */
final class SessionChecklist {

    private JComponent component;
    private final Map<SidebarEntry, JCheckBox> checkboxes = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private SessionChecklist() {
    }

    /**
     * @param defaultChecked which candidates start pre-checked
     * @param rowLabel       the text after each session's name, e.g. its status or harness
     */
    static SessionChecklist build(List<SidebarEntry> candidates, Predicate<SidebarEntry> defaultChecked,
                                  Function<SidebarEntry, String> rowLabel) {
        SessionChecklist checklist = new SessionChecklist();
        checklist.init(candidates, defaultChecked, rowLabel);
        return checklist;
    }

    private void init(List<SidebarEntry> candidates, Predicate<SidebarEntry> defaultChecked,
                      Function<SidebarEntry, String> rowLabel) {
        Map<String, List<SidebarEntry>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SidebarEntry entry : candidates) {
            String key = entry.abbreviatedPath().isBlank() ? entry.tmuxSession() : entry.abbreviatedPath();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }
        for (List<SidebarEntry> members : groups.values()) {
            members.sort(Comparator.comparing(SidebarEntry::displayName, String.CASE_INSENSITIVE_ORDER));
        }

        List<JCheckBox> headers = new ArrayList<>();
        JCheckBox selectAll = new JCheckBox("Select all");
        selectAll.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        Runnable refreshSelectAllState = () -> selectAll.setSelected(
                !checkboxes.isEmpty() && checkboxes.values().stream().allMatch(JCheckBox::isSelected));

        for (Map.Entry<String, List<SidebarEntry>> group : groups.entrySet()) {
            List<SidebarEntry> members = group.getValue();

            JCheckBox header = new JCheckBox(group.getKey() + " (" + members.size() + ")");
            header.setFont(header.getFont().deriveFont(Font.BOLD));
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            headers.add(header);
            rows.add(header);

            for (SidebarEntry entry : members) {
                JCheckBox box = new JCheckBox(entry.displayName() + "  —  " + rowLabel.apply(entry));
                box.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 0));
                box.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.setSelected(defaultChecked.test(entry));
                checkboxes.put(entry, box);
                rows.add(box);

                box.addActionListener(e -> {
                    header.setSelected(members.stream().allMatch(m -> checkboxes.get(m).isSelected()));
                    refreshSelectAllState.run();
                    fireChanged();
                });
            }

            header.setSelected(members.stream().allMatch(m -> checkboxes.get(m).isSelected()));
            header.addActionListener(e -> {
                boolean selected = header.isSelected();
                for (SidebarEntry entry : members) {
                    checkboxes.get(entry).setSelected(selected);
                }
                refreshSelectAllState.run();
                fireChanged();
            });
        }
        refreshSelectAllState.run();

        selectAll.addActionListener(e -> {
            boolean selected = selectAll.isSelected();
            checkboxes.values().forEach(box -> box.setSelected(selected));
            headers.forEach(box -> box.setSelected(selected));
            fireChanged();
        });

        int rowsHeight = rows.getPreferredSize().height;
        JScrollPane scroll = new JScrollPane(rows,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(480, Math.max(120, Math.min(360, rowsHeight + 8))));

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(selectAll, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        this.component = panel;
    }

    JComponent component() {
        return component;
    }

    List<SidebarEntry> selected() {
        List<SidebarEntry> result = new ArrayList<>();
        for (Map.Entry<SidebarEntry, JCheckBox> entry : checkboxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** Called whenever any checkbox at any level changes, directly or by cascade. */
    void addSelectionListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fireChanged() {
        listeners.forEach(Runnable::run);
    }
}
