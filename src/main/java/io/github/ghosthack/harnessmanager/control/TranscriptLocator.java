package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.harnessmanager.harness.HarnessType;

import java.nio.file.Path;
import java.util.Optional;

/** Finds a session's conversation transcript on disk, for the {@code list} tool. May
 * block for a moment (Codex is found through {@code lsof}); never throws. */
@FunctionalInterface
public interface TranscriptLocator {

    TranscriptLocator NONE = (harness, project, harnessSessionId, paneProcessId) -> Optional.empty();

    /**
     * @param project          the session's project directory
     * @param harnessSessionId the harness's own conversation id when known
     * @param paneProcessId    the PID of the session's shell, or -1
     */
    Optional<Path> transcript(HarnessType harness, Path project, Optional<String> harnessSessionId,
                              long paneProcessId);
}
