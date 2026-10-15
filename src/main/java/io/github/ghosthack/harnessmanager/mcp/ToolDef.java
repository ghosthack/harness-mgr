package io.github.ghosthack.harnessmanager.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * One tool as advertised by {@code tools/list}.
 *
 * @param name        the tool name agents call
 * @param description what the tool does — the documentation agents actually read
 * @param inputSchema JSON Schema of the {@code arguments} object
 */
public record ToolDef(String name, String description, ObjectNode inputSchema) {

    public ToolDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
