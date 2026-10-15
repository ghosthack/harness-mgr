package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * New-or-convert dialog for handing a tmux session to harness-stimulator. Create-new
 * is tmux-only — the stimulator creates the session with its own tmux client — so the
 * "New session" target is offered only while a local tmux host is configured
 * ({@code canCreateNew}); a selected session is convertible only when its host reports
 * {@code HostFeature.STIMULATION}, which the caller has already checked.
 */
final class StimulateDialog {

    private static final String INHERIT_MODEL = "Inherit";

    record Result(boolean selectedSession, Path projectPath, HarnessType harness,
                  Optional<String> model, String prompt, int maxStimuli) {
    }

    static Optional<Result> show(Window parent, SessionEntry selected, boolean canCreateNew) {
        boolean canConvert = selected != null && selected.harnessType() != HarnessType.SHELL;
        JDialog dialog = DialogChrome.newDialog(parent, "Stimulate session");
        DialogChrome.Form form = DialogChrome.form();

        List<String> targets = new ArrayList<>();
        if (canConvert) {
            targets.add("Selected session — " + selected.tmuxSession());
        }
        if (canCreateNew) {
            targets.add("New session");
        }
        JComboBox<String> target = new JComboBox<>(targets.toArray(String[]::new));

        Path defaultPath = canConvert ? Path.of(selected.projectPath())
                : Path.of(System.getProperty("user.home"));
        JTextField path = new JTextField(defaultPath.toString(), 32);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            Path seed = UserPath.expand(path.getText());
            JFileChooser chooser = new JFileChooser(Files.isDirectory(seed) ? seed.toFile() : defaultPath.toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                path.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        List<String> harnesses = new ArrayList<>();
        if (canConvert) {
            harnesses.add("Inherit — " + selected.harnessType().id());
        }
        harnesses.add(HarnessType.CLAUDE_CODE.id());
        harnesses.add(HarnessType.CODEX.id());
        harnesses.add(HarnessType.CURSOR_AGENT.id());
        // Not HarnessType.GROK, HarnessType.OPENCODE, or HarnessType.PI: harness-stimulator's
        // --harness flag only recognizes cursor-agent | claude-code | codex (see its
        // Main.java usage text), so offering "grok", "opencode", or "pi" here would just
        // hand it a value it rejects.
        JComboBox<String> harness = new JComboBox<>(harnesses.toArray(String[]::new));
        if (!canConvert) {
            harness.setSelectedItem(HarnessType.CODEX.id());
        }

        JComboBox<String> model = new JComboBox<>(new String[]{INHERIT_MODEL});
        model.setEditable(true);
        model.setToolTipText("Choose Inherit or type a model name understood by the selected harness");

        JTextArea prompt = new JTextArea(5, 32);
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        JSpinner max = new JSpinner(new SpinnerNumberModel(25, 0, Integer.MAX_VALUE, 1));

        form.addRow("Target:", target);
        form.addRow("Project path:", path, browse);
        form.addRow("Harness:", harness);
        form.addRow("Model:", model);
        form.addRow("Prompt:", new JScrollPane(prompt));
        form.addRow("Max stimuli:", max);
        form.constraints.gridx = 1;
        form.constraints.gridy = form.reserveRow();
        form.constraints.gridwidth = 2;
        form.panel.add(new JLabel("0 means unlimited; the prompt is sent initially and on each idle."),
                form.constraints);

        Runnable updateTarget = () -> {
            boolean converting = canConvert && target.getSelectedIndex() == 0;
            path.setEnabled(!converting);
            browse.setEnabled(!converting);
            if (converting) {
                path.setText(selected.projectPath());
            }
        };
        target.addActionListener(e -> updateTarget.run());
        updateTarget.run();

        AtomicReference<Optional<Result>> result = new AtomicReference<>(Optional.empty());
        JButton start = new JButton("Stimulate");
        start.setIcon(LucidIcon.heartbeat(16));
        JButton cancel = new JButton("Cancel");
        start.addActionListener(e -> {
            boolean converting = canConvert && target.getSelectedIndex() == 0;
            Path project = UserPath.expand(path.getText().strip());
            if (!Files.isDirectory(project)) {
                JOptionPane.showMessageDialog(dialog, "'" + project + "' is not a directory.",
                        "Invalid path", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String instruction = prompt.getText().strip();
            if (instruction.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Prompt is required.",
                        "Missing prompt", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String harnessChoice = (String) harness.getSelectedItem();
            HarnessType harnessType = harnessChoice.startsWith("Inherit")
                    ? selected.harnessType() : HarnessType.fromId(harnessChoice);
            String modelChoice = String.valueOf(model.getEditor().getItem()).strip();
            Optional<String> selectedModel = modelChoice.isEmpty() || INHERIT_MODEL.equals(modelChoice)
                    ? Optional.empty() : Optional.of(modelChoice);
            if (selectedModel.isPresent() && harnessType == HarnessType.CURSOR_AGENT) {
                JOptionPane.showMessageDialog(dialog,
                        "cursor-agent has no verified model-selection flag; choose Inherit.",
                        "Unsupported model selection", JOptionPane.WARNING_MESSAGE);
                return;
            }
            result.set(Optional.of(new Result(converting, project, harnessType,
                    selectedModel, instruction, (Integer) max.getValue())));
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());

        DialogChrome.layout(dialog, form.panel, DialogChrome.buttons(start, cancel));
        DialogChrome.show(dialog, parent, start, cancel);
        return result.get();
    }

    private StimulateDialog() {
    }
}
