package io.github.ghosthack.harnessmanager.harness;

/** Looks up the plugin for a {@link HarnessType}. */
public final class HarnessPlugins {

    private static final ClaudeCodePlugin CLAUDE_CODE = new ClaudeCodePlugin();
    private static final CodexPlugin CODEX = new CodexPlugin();
    private static final CursorAgentPlugin CURSOR_AGENT = new CursorAgentPlugin();
    private static final GrokPlugin GROK = new GrokPlugin();
    private static final OpenCodePlugin OPENCODE = new OpenCodePlugin();
    private static final PiPlugin PI = new PiPlugin();
    private static final MusePlugin MUSE = new MusePlugin();
    private static final ShellPlugin SHELL = new ShellPlugin();

    public static HarnessPlugin of(HarnessType type) {
        return switch (type) {
            case CLAUDE_CODE -> CLAUDE_CODE;
            case CODEX -> CODEX;
            case CURSOR_AGENT -> CURSOR_AGENT;
            case GROK -> GROK;
            case OPENCODE -> OPENCODE;
            case PI -> PI;
            case MUSE -> MUSE;
            case SHELL -> SHELL;
        };
    }

    private HarnessPlugins() {
    }
}
