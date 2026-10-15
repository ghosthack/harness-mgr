package io.github.ghosthack.harnessmanager.harness;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where an agent launched in one session reaches the app's MCP server, and how it
 * authenticates — everything {@link HarnessPlugin#withMcpServer} may need. The secret
 * never goes into the typed command line itself: a harness either reads it from
 * {@link #configFile} (written by the launcher from {@link HarnessPlugin#mcpConfigFile},
 * owner-only) or from the {@value HarnessPlugin#MCP_TOKEN_ENV} environment variable the
 * launcher puts into the session's environment.
 *
 * @param url        the session's MCP URL, carrying no token
 *                   ({@code http://127.0.0.1:<port>/mcp/<session>})
 * @param token      the bearer token the server expects
 * @param configFile the per-session file a harness with a file-based MCP config uses
 */
public record McpEndpoint(String url, String token, Path configFile) {

    public McpEndpoint {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(configFile, "configFile");
    }

    /** Hides the token, which must never reach a log line. */
    @Override
    public String toString() {
        return "McpEndpoint[url=" + url + ", configFile=" + configFile + "]";
    }
}
