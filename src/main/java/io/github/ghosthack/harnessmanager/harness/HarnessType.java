package io.github.ghosthack.harnessmanager.harness;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The harness CLIs the manager knows how to bootstrap. */
public enum HarnessType {

    CLAUDE_CODE("claude-code"),
    CODEX("codex"),
    CURSOR_AGENT("cursor-agent"),
    GROK("grok"),
    OPENCODE("opencode"),
    PI("pi"),
    MUSE("muse"),
    /** No harness CLI at all — the pane just runs the user's default shell. */
    SHELL("shell");

    private final String id;

    HarnessType(String id) {
        this.id = id;
    }

    /** Stable id — this is what's persisted in the registry JSON, not the enum name. */
    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static HarnessType fromId(String id) {
        for (HarnessType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown harness type: " + id);
    }

    @Override
    public String toString() {
        return id;
    }
}
