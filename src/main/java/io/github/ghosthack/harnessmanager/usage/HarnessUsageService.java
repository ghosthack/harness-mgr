package io.github.ghosthack.harnessmanager.usage;

import io.github.ghosthack.harnessmanager.harness.HarnessType;

import java.util.List;
import java.util.Optional;

/** Selects the live usage source for a harness family. */
public final class HarnessUsageService {

    private final ClaudeUsageClient claude = new ClaudeUsageClient();
    private final CodexUsageClient codex = new CodexUsageClient();

    public UsageSnapshot load(HarnessType harness) throws Exception {
        return switch (harness) {
            case CLAUDE_CODE -> claude.load();
            case CODEX -> codex.load();
            case CURSOR_AGENT -> unavailable("Cursor Agent", "Cursor Settings → Usage",
                    "Cursor does not publish individual-plan usage through its CLI or a public API.");
            case GROK -> unavailable("Grok", "xAI account settings",
                    "Grok's CLI exposes context-window usage via /status but no session or "
                            + "weekly provider budget, and there is no public usage API to poll instead.");
            case OPENCODE -> unavailable("OpenCode", "opencode stats",
                    "opencode is provider-agnostic — its own `stats` command reports token/cost "
                            + "totals it tallied locally across whichever providers are configured, "
                            + "not a session or weekly budget from a single provider account, so "
                            + "there is no one budget to poll.");
            case PI -> unavailable("Pi", "/session",
                    "pi is provider-agnostic — --provider/--model select whichever of google, "
                            + "openai, anthropic, xai, or another configured provider a session uses, "
                            + "so there is no single provider account to poll for a session or weekly "
                            + "budget; its own /session command and footer report local cost/token "
                            + "totals instead.");
            case MUSE -> unavailable("Muse Code", "Meta AI account usage",
                    "Muse's live provider budget is not integrated. Check your Meta AI account for usage.");
            case SHELL -> unavailable("Shell", "Local shell",
                    "A shell has no model usage or provider limits.");
        };
    }

    private static UsageSnapshot unavailable(String heading, String source, String note) {
        return new UsageSnapshot(heading, source, List.of(), List.of(), Optional.of(note));
    }
}
