package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.harnessmanager.harness.HarnessType;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates and kills sessions on behalf of agents, the way the window does — registered,
 * labelled, MCP-injected and announced to the sidebar. The production implementation is
 * {@link LauncherSpawner}; tests use a fake. Calls block and may throw.
 */
public interface SessionSpawner {

    /** Starts {@code request} on the local jmux host and returns the new session's name. */
    String spawn(SpawnRequest request);

    /** Ends the session (no-op when already gone) and drops it from the sidebar. */
    void kill(String session);

    /**
     * @param project     an existing directory
     * @param displayName the sidebar name, when the agent gave one
     * @param labels      extra jmux labels
     */
    record SpawnRequest(HarnessType harness, Path project, Optional<String> displayName,
                        Map<String, String> labels) {

        public SpawnRequest {
            Objects.requireNonNull(harness, "harness");
            Objects.requireNonNull(project, "project");
            displayName = displayName == null ? Optional.empty() : displayName;
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
    }
}
