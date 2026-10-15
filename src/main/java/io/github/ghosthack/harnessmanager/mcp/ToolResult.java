package io.github.ghosthack.harnessmanager.mcp;

import java.util.Objects;

/**
 * The outcome of one tool call: a single text content block, flagged as a tool-level
 * error ({@code isError}) when the call failed. Tool errors are results, not JSON-RPC
 * errors, so the agent sees the message.
 */
public record ToolResult(String text, boolean isError) {

    public ToolResult {
        Objects.requireNonNull(text, "text");
    }

    public static ToolResult ok(String text) {
        return new ToolResult(text, false);
    }

    public static ToolResult error(String text) {
        return new ToolResult(text, true);
    }
}
