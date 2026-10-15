package io.github.ghosthack.harnessmanager.registry;

import java.nio.file.Path;

/** Where the session registry file lives. */
public final class RegistryPaths {

    /** Overridable for tests/smoke-checks: {@code -Dharnessmanager.registry=/path/to/sessions.json}. */
    public static final String OVERRIDE_PROPERTY = "harnessmanager.registry";

    /** Overridable for tests/smoke-checks: {@code -Dharnessmanager.machines=/path/to/machines.json}. */
    public static final String MACHINES_OVERRIDE_PROPERTY = "harnessmanager.machines";

    public static Path resolve() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".harness-manager", "sessions.json");
    }

    /** Where the registered-machines file lives — a sibling of {@link #resolve()} by
     * default, so a scratch registry gets an isolated machines file too. */
    public static Path resolveMachines() {
        String override = System.getProperty(MACHINES_OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return resolve().resolveSibling("machines.json");
    }

    /** Where per-session MCP config files live (owner-only): an {@code mcp} directory
     * beside {@link #resolve()}, so a scratch registry gets an isolated one too. */
    public static Path resolveMcpConfigDir() {
        return resolve().toAbsolutePath().resolveSibling("mcp");
    }

    private RegistryPaths() {
    }
}
