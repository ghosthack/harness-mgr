package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.harnessmanager.harness.ClaudeProjects;
import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.status.CodexSessionNameResolver;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The transcript locations harness-manager knows: Codex's open rollout file (found from
 * the pane's process tree, see {@link CodexSessionNameResolver#transcript}) and Claude
 * Code's per-project JSONL directory ({@link ClaudeProjects}): the session's own
 * {@code <id>.jsonl} when the id is known (nothing until Claude has written it), else the
 * project's newest file — which the caller must not present as this session's when
 * other Claude sessions share the project. Other harnesses have none (yet).
 */
public final class HarnessTranscripts implements TranscriptLocator {

    private final Path claudeProjects;
    private final CodexSessionNameResolver codex;

    public HarnessTranscripts(Path claudeProjects, CodexSessionNameResolver codex) {
        this.claudeProjects = claudeProjects;
        this.codex = codex;
    }

    /** {@code ~/.claude/projects} (or {@code $CLAUDE_CONFIG_DIR/projects}) and the default
     * Codex home. */
    public static HarnessTranscripts standard() {
        return new HarnessTranscripts(ClaudeProjects.root(), new CodexSessionNameResolver());
    }

    @Override
    public Optional<Path> transcript(HarnessType harness, Path project, Optional<String> harnessSessionId,
                                     long paneProcessId) {
        try {
            return switch (harness) {
                case CODEX -> codex.transcript(paneProcessId);
                case CLAUDE_CODE -> claude(project, harnessSessionId);
                default -> Optional.empty();
            };
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> claude(Path project, Optional<String> harnessSessionId) {
        if (harnessSessionId.isPresent()) {
            // never another session's file: a known id whose conversation has no message
            // yet has no transcript
            return ClaudeProjects.transcript(claudeProjects, project, harnessSessionId.get());
        }
        return ClaudeProjects.newest(claudeProjects, project);
    }
}
