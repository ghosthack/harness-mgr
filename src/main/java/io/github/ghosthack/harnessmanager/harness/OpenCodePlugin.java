package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Confidence;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bootstrap + state-detection patterns for opencode's {@code opencode} CLI, collapsed onto
 * this app's 3-state {@link SessionState} the same way {@link ClaudeCodePlugin},
 * {@link CodexPlugin}, and {@link GrokPlugin} are. There is no {@code ../harness-stimulator}
 * precedent to adapt this from either (that sibling project's {@code --harness} flag only
 * recognizes {@code cursor-agent}, {@code claude-code}, and {@code codex}, which is also why
 * {@code StimulateDialog} does not offer it), so everything here was grounded directly
 * against a live {@code opencode 1.18.21} pane (macOS, tmux 3.6a, 120x40): a fresh launch, a
 * shell-command turn and a file-edit turn each driven through both an "Allow once" approval
 * and a "Reject", a trailing-question turn, a crash, and two real {@code /exit}s.
 *
 * <p>Two things make opencode differ from the other three plugins:
 *
 * <ul>
 *   <li>Its composer is a persistent bordered box drawn at the bottom of the screen in
 *       every state — working, idle, and mid-approval all show it — so, unlike
 *       {@link CodexPlugin}'s configurable footer, the box itself is never the signal; only
 *       the line(s) below its bottom border ({@code ╹▀▀▀…}) change between states, and that
 *       border is what {@link #contentLines} cuts on rather than matching the footer text.
 *   <li>The permission dialog replaces the composer with its own bordered box rather than
 *       drawing over it, headed "Permission required" with a "# Shell command" or "→ Edit
 *       &lt;file&gt;" subheading depending on the tool, and three buttons — "Allow once",
 *       "Allow always", "Reject" — all verified live.
 * </ul>
 */
public final class OpenCodePlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-23.1";

    // "Session   New session - …" / "Continue  opencode -s ses_…" — printed to the pane's
    // own shell right after /exit, verified live across two separate real exits.
    private static final Pattern EXIT_RESUME = Pattern.compile(
            "(?is)Continue\\s+opencode\\s+-s\\s+([A-Za-z0-9_@%+=:,./-]+)");

    /** The composer's running-turn footer reads e.g. "⬝⬝⬝⬝⬝⬝■■  esc interrupt" — no "to",
     * unlike the other three harnesses' "esc to interrupt" — verified live. Since that
     * progress animation repaints every poll, {@link StateDetector}'s behavioral layer
     * already reads WORKING without consulting this plugin; this is the same safety net
     * {@link CodexPlugin}'s WORKING_INDICATOR is, for a turn whose render freezes. */
    private static final Pattern WORKING_INDICATOR = Pattern.compile("(?i)esc interrupt");

    /** "△ Permission required" plus its three-button row — verified live for both a shell
     * command ("# Shell command" subheading) and a file edit ("→ Edit &lt;file&gt;"
     * subheading). Matching the heading and button row rather than either subheading covers
     * both without guessing a third alternative for tool kinds (webfetch, etc.) that were
     * not exercised live. */
    private static final Pattern APPROVAL = Pattern.compile(
            "(?i)(permission required|allow once\\s+allow always\\s+reject)");

    /** Both shell spellings — bash says "opencode: command not found", zsh says "command
     * not found: opencode" (verified live) — plus generic crash signatures, the same narrow
     * BROKEN shape {@link CodexPlugin} and {@link GrokPlugin} use, so a command a running
     * opencode happens to run that itself isn't found doesn't get folded in. */
    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(opencode: command not found|command not found: opencode(?![\\w-])"
                    + "|panic:|segmentation fault|fatal error)");

    /** The composer's bottom border, verified live as the stable divider between the
     * composer/transcript above and the cwd-plus-hints footer below. The footer's left half
     * is the working directory, which wraps across two rows when long (as it did throughout
     * grounding, from this app's own deeply-nested scratch path) — so unlike {@link #CHROME}
     * this is matched positionally, everything from this border onward is footer rather than
     * transcript, the same reasoning {@link CodexPlugin#footerBelowComposer} uses for its
     * configurable status line. */
    private static final Pattern COMPOSER_BORDER = Pattern.compile("^\\s*╹");

    /** The footer's right half, present whenever the composer is idle-visible — either the
     * pre-conversation "tab agents  ctrl+p commands" hint or, once a turn has run, the
     * token-usage "8.3K (4%)  ctrl+p commands" readout. Checked only after
     * {@link #WORKING_INDICATOR} has already failed to match, so (unlike the working
     * footer, which also carries "ctrl+p" alongside "esc interrupt") its presence here is
     * unambiguous — the same ordering {@link GrokPlugin}'s IDLE_HINT relies on. */
    private static final Pattern IDLE_HINT = Pattern.compile("(?i)ctrl\\+p");

    /** UI furniture stripped before the trailing-question check: the composer's left rail,
     * the per-turn "▣  &lt;mode&gt; · &lt;model&gt;[ · Ns]" marker, "Thought: …" asides, and
     * tool-activity preview rows (a shell echo, an edit/write summary, a "~ Preparing…"
     * status) — all verified live. The footer below {@link #COMPOSER_BORDER} is dropped by
     * position instead, per that field's comment. */
    private static final Pattern CHROME = Pattern.compile(
            "(?i)(^\\s*┃"
                    + "|^\\s*▣\\s"
                    + "|^\\s*thought:\\s"
                    + "|^\\s*[$~]\\s"
                    + "|^\\s*[←→]\\s)");

    @Override
    public HarnessType type() {
        return HarnessType.OPENCODE;
    }

    // processName() is left at its default of type().id() — "opencode". The installed
    // binary at ~/.opencode/bin/opencode is a self-contained Mach-O executable, not a
    // launcher that execs a versioned copy the way ClaudeCodePlugin's and GrokPlugin's
    // binaries do, so tmux reports pane_current_command as "opencode" verbatim — verified
    // live at 1.18.21. No VERSIONED_BINARY-style override is needed.

    @Override
    public String launchCommand() {
        return "opencode";
    }

    /**
     * {@code opencode --session &lt;id&gt;} — the {@code -s, --session} flag documented by
     * {@code opencode --help} and confirmed live: resuming with it replayed the full prior
     * transcript rather than starting fresh.
     */
    @Override
    public Optional<String> resumeCommand(String harnessSessionId) {
        return Optional.of("opencode --session " + HarnessPlugin.shellArgument(harnessSessionId));
    }

    @Override
    public Optional<String> harnessSessionIdFromExit(Screen exitScreen) {
        var matcher = EXIT_RESUME.matcher(exitScreen.text());
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return Optional.ofNullable(last);
    }

    /** {@code /exit} — verified live via the composer's own slash-command autocomplete
     * ("/exit  Exit the app"), and it's the exact word the exit receipt's own wording names. */
    @Override
    public Optional<String> quitCommand() {
        return Optional.of("/exit");
    }

    /** Drops everything from the composer's bottom border down (the positional footer,
     * see {@link #COMPOSER_BORDER}), then every {@link #CHROME} line above it. */
    @Override
    public String maskChrome(String screenText) {
        List<String> lines = ScreenMask.lines(stripVolatile(screenText));
        int borderAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (COMPOSER_BORDER.matcher(ScreenMask.plain(lines.get(i))).find()) {
                borderAt = i;
            }
        }
        List<String> above = borderAt >= 0 ? lines.subList(0, borderAt) : lines;
        return ScreenMask.join(above.stream()
                .filter(line -> !CHROME.matcher(ScreenMask.plain(line)).find()).toList());
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(20);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return classified(SessionState.IDLE, "error signature on screen — opencode likely crashed");
        }
        if (WORKING_INDICATOR.matcher(tailText).find()) {
            return classified(SessionState.WORKING,
                    "esc-interrupt visible: turn still running despite quiescent screen");
        }
        if (APPROVAL.matcher(tailText).find()) {
            return classified(SessionState.WAITING_FOR_INPUT, "permission dialog shape");
        }
        List<String> content = contentLines(tail);
        if (!content.isEmpty() && content.getLast().stripTrailing().endsWith("?")) {
            return classified(SessionState.WAITING_FOR_INPUT,
                    "quiescent with trailing question above UI chrome");
        }
        if (IDLE_HINT.matcher(tailText).find()) {
            return classified(SessionState.IDLE, "composer footer, no running-turn indicator");
        }
        return Optional.empty();
    }

    /** The transcript above the composer's footer (see {@link #COMPOSER_BORDER}), with the
     * remaining UI chrome filtered out, for the trailing-question check. */
    private static List<String> contentLines(List<String> tail) {
        int borderAt = -1;
        for (int i = 0; i < tail.size(); i++) {
            if (COMPOSER_BORDER.matcher(tail.get(i)).find()) {
                borderAt = i;
            }
        }
        List<String> above = borderAt >= 0 ? tail.subList(0, borderAt) : tail;
        return above.stream().filter(l -> !CHROME.matcher(l).find()).toList();
    }

    private static Optional<Classification> classified(SessionState state, String reason) {
        return Optional.of(new Classification(state, Confidence.HIGH,
                reason + " (patterns " + PATTERNS_VERSION + ")", Classification.Origin.PLUGIN));
    }
}
