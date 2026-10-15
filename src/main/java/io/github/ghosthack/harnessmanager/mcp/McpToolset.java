package io.github.ghosthack.harnessmanager.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The tools an {@link McpServer} exposes. The server owns the transport (HTTP, JSON-RPC,
 * auth); a toolset only describes its tools and answers calls.
 */
public interface McpToolset {

    /** The tools offered by {@code tools/list}, in display order. */
    List<ToolDef> tools();

    /**
     * Runs one {@code tools/call}. Exceptions thrown here become an {@code isError}
     * result carrying the message; they never fail the HTTP request.
     *
     * @param caller    the calling session's name from the URL path, or {@code null} for
     *                  an anonymous caller ({@code /mcp} without a path segment)
     * @param tool      the tool name; may be one not listed by {@link #tools()}
     * @param arguments the call's {@code arguments} object (an empty object when absent)
     */
    ToolResult call(String caller, String tool, JsonNode arguments);

    /** The hosting {@link McpService} started listening: start any background work the
     * tools need (called after a successful start, also after each restart). Default: none. */
    default void serverStarted() {
    }

    /** The hosting {@link McpService} stopped: stop that background work. Default: none. */
    default void serverStopped() {
    }
}
