package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Confidence;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bootstrap + state-detection patterns for the OpenAI Codex CLI. Adapted from
 * ../harness-stimulator's {@code CodexPlugin} (captured against codex-cli 0.147.0) and
 * collapsed onto this app's 3-state {@link SessionState} the same way
 * {@link ClaudeCodePlugin} and {@link CursorAgentPlugin} are: approval, question widget
 * and stalled goal loop all read WAITING_FOR_INPUT, and a crashed codex folds into IDLE.
 *
 * <p>Re-grounded against <b>codex-cli 0.149.0</b> (macOS, tmux 3.7b, pane 120x40, zsh);
 * what was re-verified live at that version is called out per pattern below. Everything
 * else is inherited from the 0.147.0 capture session and rots the same way the other
 * plugins' patterns do — empty means "fall back to the behavioral heuristics".
 *
 * <p>Two things still make codex differ from the other two plugins:
 *
 * <ul>
 *   <li>Its running-turn line carries a live elapsed-time counter
 *       ("• Working (5s • esc to interrupt)"), so a running turn never goes quiescent and
 *       {@link StateDetector}'s behavioral layer already reads WORKING without consulting
 *       this plugin. The esc-to-interrupt check below is the safety net for a turn whose
 *       render freezes.
 *   <li>Codex puts its own modals — startup directory-trust, the rate-limit
 *       "switch model?" nudge — in the same numbered-selector widget it uses for
 *       permission prompts. All of them need a human, so an unrecognized numbered
 *       selector reads WAITING_FOR_INPUT rather than being left to fall through to idle.
 * </ul>
 */
public final class CodexPlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-21.2";

    private static final Pattern AUTO_RESOLVE_COUNTDOWN = Pattern.compile(
            "(?i)auto-resolves in \\d+s");

    /** Printed after /quit, immediately before control returns to the pane shell. */
    private static final Pattern EXIT_RESUME = Pattern.compile(
            "(?is)To continue this session,\\s+run\\s+codex\\s+resume\\s+"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    // Must match the whole "Working (… esc to interrupt)" shape, not the bare hint: the
    // request_user_input widget's hint bar ends with "tab to add notes | enter to submit
    // answer | esc to interrupt", so a bare match classifies a pending question as a
    // running turn and the sidebar never goes amber for it.
    private static final Pattern WORKING_INDICATOR = Pattern.compile(
            "(?i)Working\\s*\\([^)]*esc to interrupt");

    // The directory-trust wording and "Press enter to continue" were re-verified live at
    // 0.149.0 (see codex-grounding.md); the exec/patch approval phrasings are inherited
    // from the 0.147.0 captures. The remaining alternatives come from the binary's string
    // table and have never been confirmed against a live render — they can only add
    // WAITING_FOR_INPUT, which is the "needs you" badge, so an over-match is the safe
    // direction here.
    private static final Pattern APPROVAL_EXPLICIT = Pattern.compile(
            "(?i)(would you like to (run the following command|make the following edits)"
                    + "|do you trust the contents of this directory"
                    + "|press enter to (confirm|continue)"
                    + "|needs your approval"
                    + "|do you want to approve network access to"
                    + "|codex wants to edit)");

    // request_user_input widget, gated behind codex's default_mode_request_user_input
    // feature flag. Derived from the binary's string table, not confirmed against a live
    // render. Kept separate from APPROVAL_EXPLICIT even though both now yield the same
    // state, because the reason string lands in the sidebar tooltip and "consent dialog"
    // and "question" are different things to be told.
    private static final Pattern QUESTION_WIDGET = Pattern.compile(
            "(?i)(enter to submit answer|\\d+ unanswered|auto-resolves in|^\\s*Question \\d+/\\d+\\b)",
            Pattern.MULTILINE);

    // "› 1. Yes, continue" — the caret is U+203A. Re-verified live at 0.149.0 against the
    // startup directory-trust prompt. Note this caret is not exclusive to selectors:
    // codex draws the composer and echoes submitted prompts with it too, so the branch
    // that uses this is guarded on the composer being absent.
    private static final Pattern OPTION_SELECTOR = Pattern.compile(
            "^\\s*›\\s*\\d+\\.\\s");

    // Narrow on purpose: a coding agent's transcript is full of "command not found" and
    // "fatal error" from commands it ran, so only codex's own death counts. Both shell
    // spellings — bash says "codex: command not found", zsh says "command not found: codex".
    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(codex: command not found|command not found: codex(?![\\w-])"
                    + "|killed\\s+codex\\b"
                    + "|agent errored:"
                    + "|failed to start codex session:)");

    // Idle is read off the composer+footer layout at the bottom of the screen, never off
    // the caret being empty: codex renders a rotating dim placeholder inside the caret
    // ("› Ask Codex to do anything" at 0.149.0) and capture-pane cannot tell ghost text
    // from a real draft.
    private static final Pattern COMPOSER_LINE = Pattern.compile("^\\s*›");

    // /goal is codex's own self-loop. "Pursuing goal (7s)" rides alongside the normal
    // running-turn line so it needs no pattern, and "Goal achieved (11s)" freezes at its
    // final duration and reads as ordinary idle. These variants are the ones that look
    // idle but are not: the loop has stopped and only a human typing `/goal resume` will
    // restart it, which is a "needs you" badge, not a gray one. Wording is verbatim from
    // the 0.147.0 string table and unconfirmed; a miss falls through to idle.
    private static final Pattern GOAL_NEEDS_HUMAN = Pattern.compile(
            "(?i)Goal (paused|stalled|hit usage limits|abandoned)");

    // Fast path for the footers that name a recognizable component, so idle still
    // resolves if the composer line itself is scrolled or styled unexpectedly. Note this
    // is only ever a fast path: the 0.149.0 default footer is "<model> <effort> · <cwd>"
    // ("gpt-5.6-sol high · /private/tmp/…"), which matches none of these alternatives and
    // is caught by the structural branch of composerVisible instead — which is exactly
    // why that branch exists. The footer's contents are operator configuration ([tui]
    // status_line picks from ~20 components), so its text can never be the primary signal.
    private static final Pattern FOOTER_COMPONENT = Pattern.compile(
            "(?i)(Context\\s+\\d+%\\s+left|\\d+%\\s+context|\\d+K\\s+window"
                    + "|Fast\\s+(on|off)|\\bv?\\d+\\.\\d+\\.\\d+\\b)");

    // Everything that is UI furniture rather than agent speech, stripped before the
    // trailing-question-mark check: composer/echoed prompts/selector carets (all "›"),
    // horizontal rules, the welcome box, tool-output tree rows, option rows, the command
    // preview, the approval receipt, and the tip banners. The status footer is NOT in
    // here — it is dropped positionally by contentLines(), because its text is operator
    // configuration. The "Context N% left" alternative below only survives as a fast path
    // for the operators who do configure that component.
    private static final Pattern CHROME = Pattern.compile(
            "(?i)(^\\s*›"
                    + "|^\\s*─+\\s*$"
                    + "|^\\s*[╭╮╰╯│└]"
                    + "|^\\s*\\d+\\.\\s"
                    + "|^\\s*\\$\\s"
                    + "|^\\s*✔"
                    + "|^\\s*⚠"
                    + "|^\\s*Tip:"
                    + "|^\\s*Environment:\\s"
                    + "|press enter to "
                    + "|esc to interrupt"
                    + "|Context\\s+\\d+%\\s+left)");

    /** Whether {@code node} counts as this harness (see {@link #ownsProcess}). */
    private final boolean windows;

    public CodexPlugin() {
        this(Platform.isWindows());
    }

    /** {@code windows} is injectable so both branches of {@link #ownsProcess} have a
     * unit test regardless of the platform the suite runs on. */
    CodexPlugin(boolean windows) {
        this.windows = windows;
    }

    @Override
    public HarnessType type() {
        return HarnessType.CODEX;
    }

    // processName() is left at its default of type().id() — "codex". The launcher at
    // ~/.local/bin/codex is a real Mach-O binary (a symlink into the versioned standalone
    // release), not a shim that execs something else, and tmux reports pane_current_command
    // as "codex": verified at 0.149.0. On Windows the npm shim runs node.exe as the shell's
    // child instead, so the foreground is "node" there — hence the override below.
    @Override
    public boolean ownsProcess(String foregroundCommand) {
        return processName().equals(foregroundCommand)
                || windows && "node".equals(foregroundCommand);
    }

    @Override
    public String launchCommand() {
        return "codex";
    }

    /**
     * {@code codex resume <SESSION_ID>} — verified against the 0.149.0 binary's own
     * {@code codex resume --help}, which documents the positional as "Session id (UUID)
     * or session name". Unlike {@link CursorAgentPlugin} this is a real, checked syntax
     * rather than a guess, so the "Open…" dialog can resume codex conversations by id
     * without falling back to a fresh launch.
     */
    @Override
    public Optional<String> resumeCommand(String harnessSessionId) {
        return Optional.of("codex resume " + HarnessPlugin.shellArgument(harnessSessionId));
    }

    /**
     * {@code codex -c 'mcp_servers.harness-manager.url="<url>"'
     * -c 'mcp_servers.harness-manager.bearer_token_env_var="HM_MCP_TOKEN"'} — per-run
     * config overrides that add a streamable-HTTP server next to the user's configured
     * ones, authenticating with the token the launcher put into the session's
     * {@value HarnessPlugin#MCP_TOKEN_ENV} environment variable (so the token itself is
     * never typed). {@code -c} is a global option, so both go right after the leading
     * {@code codex}, ahead of the {@code resume} subcommand.
     */
    @Override
    public String withMcpServer(String command, McpEndpoint endpoint) {
        String prefix = "mcp_servers." + MCP_SERVER_NAME + ".";
        return ScreenMask.insertAfterProgram(command, "codex",
                "-c " + HarnessPlugin.shellArgument(prefix + "url=" + ScreenMask.quoted(endpoint.url()))
                        + " -c " + HarnessPlugin.shellArgument(
                                prefix + "bearer_token_env_var=" + ScreenMask.quoted(MCP_TOKEN_ENV)));
    }

    /** The bearer token comes from {@value HarnessPlugin#MCP_TOKEN_ENV}; see {@link #withMcpServer}. */
    @Override
    public boolean usesMcpTokenEnv() {
        return true;
    }

    /**
     * Drops the status footer by position (the indented line directly below the
     * composer — see {@link #contentLines} for why never by text), then every
     * {@link #CHROME} line, which takes the composer, echoed prompts and the running-turn
     * line with its elapsed counter.
     */
    @Override
    public String maskChrome(String screenText) {
        List<String> lines = new ArrayList<>(ScreenMask.lines(stripVolatile(screenText)));
        int last = ScreenMask.lastNonBlank(lines, lines.size() - 1);
        int above = ScreenMask.lastNonBlank(lines, last - 1);
        if (above >= 0) {
            String footer = ScreenMask.plain(lines.get(last));
            if (COMPOSER_LINE.matcher(ScreenMask.plain(lines.get(above))).find()
                    && Character.isWhitespace(footer.charAt(0))) {
                lines.remove(last);
            }
        }
        return ScreenMask.join(lines.stream()
                .filter(line -> !CHROME.matcher(ScreenMask.plain(line)).find()).toList());
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

    /**
     * {@code /quit} — "exit Codex". Grounded in the 0.149.0 binary's string table, which
     * lists both {@code quit} and {@code exit} in its slash-command registry with that
     * description; not confirmed against a live render, same caveat as the string-table
     * patterns above. {@code /quit} over {@code /exit} because the table also carries
     * "archive this session and exit" siblings near {@code exit} — quit is unambiguous.
     */
    @Override
    public Optional<String> quitCommand() {
        return Optional.of("/quit");
    }

    /**
     * "Question 1/1 (1 unanswered) · auto-resolves in 52s" repaints every second while
     * codex waits for an answer. Without normalizing the counter away the screen never
     * goes quiescent, {@link StateDetector} never consults this plugin, and the whole
     * wait reads as a running turn. Deliberately not applied to the running-turn
     * counter: that churn is a true liveness signal.
     */
    @Override
    public String stripVolatile(String screenText) {
        return AUTO_RESOLVE_COUNTDOWN.matcher(screenText).replaceAll("auto-resolves in <n>s");
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(20);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return Optional.of(classified(SessionState.IDLE,
                    "error signature on screen — codex likely crashed"));
        }
        if (WORKING_INDICATOR.matcher(tailText).find()) {
            return Optional.of(classified(SessionState.WORKING,
                    "esc-to-interrupt visible: turn still running despite quiescent screen"));
        }
        if (APPROVAL_EXPLICIT.matcher(tailText).find()) {
            return Optional.of(classified(SessionState.WAITING_FOR_INPUT, "consent dialog shape"));
        }
        if (QUESTION_WIDGET.matcher(tailText).find()) {
            return Optional.of(classified(SessionState.WAITING_FOR_INPUT,
                    "request-user-input selector widget"));
        }
        // Guarded by "no composer": codex's modals replace the composer region rather
        // than drawing above it, so a visible composer means this caret is not a modal's.
        // Without the guard, an echoed prompt that happens to start with "1. " — codex
        // echoes submitted prompts with the same "›" caret it uses for selector rows —
        // pins an idle session to the "needs you" badge for the rest of its life.
        if (!composerVisible(tail) && tail.stream().anyMatch(l -> OPTION_SELECTOR.matcher(l).find())) {
            return Optional.of(classified(SessionState.WAITING_FOR_INPUT,
                    "numbered selector widget — a consent or modal decision only a human can make"));
        }
        List<String> content = contentLines(tail);
        if (!content.isEmpty() && content.getLast().stripTrailing().endsWith("?")) {
            return Optional.of(classified(SessionState.WAITING_FOR_INPUT,
                    "quiescent with trailing question above UI chrome"));
        }
        if (GOAL_NEEDS_HUMAN.matcher(tailText).find()) {
            return Optional.of(classified(SessionState.WAITING_FOR_INPUT,
                    "goal loop is paused/stalled — needs `/goal resume` from a human"));
        }
        if (composerVisible(tail)) {
            return Optional.of(classified(SessionState.IDLE,
                    "composer and status footer present, no running-turn indicator"));
        }
        return Optional.empty();
    }

    /**
     * The tail with UI furniture removed, for the trailing-question check.
     *
     * <p>The status footer is dropped by <em>position</em>, not by matching its text.
     * Its contents are operator configuration — {@code [tui] status_line} picks from ~20
     * components — so no pattern can be relied on to recognize it, and in fact none of
     * {@link #CHROME}'s alternatives match the 0.149.0 default
     * ("gpt-5.6-sol high · /Users/x/Projects/foo"). Matching by text is how this check
     * silently died between codex versions upstream: with the footer left in, it is
     * always the last content line, it never ends in "?", and a turn that ends by asking
     * the human a plain prose question badges gray instead of amber. What holds across
     * status_line configurations is the footer's position — the line directly below the
     * composer.
     */
    private static List<String> contentLines(List<String> tail) {
        List<String> lines = footerBelowComposer(tail) ? tail.subList(0, tail.size() - 1) : tail;
        return lines.stream().filter(l -> !CHROME.matcher(l).find()).toList();
    }

    /**
     * True when the bottom of the screen is codex's composer region rather than a modal.
     * Accepts the three shapes the configurable status line can produce: a recognizable
     * footer component on the last line, a composer line directly above an indented
     * footer (the 0.149.0 default — verified live), or a composer line as the last line
     * (status_line configured empty).
     */
    private static boolean composerVisible(List<String> tail) {
        if (tail.isEmpty()) {
            return false;
        }
        String last = tail.getLast();
        return COMPOSER_LINE.matcher(last).find()
                || FOOTER_COMPONENT.matcher(last).find()
                || footerBelowComposer(tail);
    }

    /** The composer+footer shape: a "›" composer line directly above an indented footer. */
    private static boolean footerBelowComposer(List<String> tail) {
        if (tail.size() < 2) {
            return false;
        }
        String last = tail.getLast();
        return COMPOSER_LINE.matcher(tail.get(tail.size() - 2)).find()
                && !last.isBlank()
                && Character.isWhitespace(last.charAt(0));
    }

    private static Classification classified(SessionState state, String reason) {
        return new Classification(state, Confidence.HIGH,
                reason + " (patterns " + PATTERNS_VERSION + ")", Classification.Origin.PLUGIN);
    }
}
