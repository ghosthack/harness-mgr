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
 * Bootstrap + state-detection patterns for xAI's Grok CLI ({@code grok}), collapsed onto
 * this app's 3-state {@link SessionState} the same way {@link ClaudeCodePlugin} and
 * {@link CodexPlugin} are. There is no {@code ../harness-stimulator} precedent to adapt
 * this from (that sibling project's {@code --harness} flag does not recognize Grok, which
 * is also why {@code StimulateDialog} does not offer it), so everything here was grounded
 * directly against a live {@code grok 1.0.5 (5115b46bc909) [stable]} pane (macOS, tmux
 * 3.6a, 120x40) or, where noted, the installed binary's own string table when a live
 * render could not be produced.
 *
 * <p>Grok prints a clock timestamp flush-right on the same line as each chat bubble
 * (e.g. {@code "Hi. What would you like to work on?                    6:04 PM"}), which
 * none of the other three harnesses do — {@link #stripTrailingClock} removes it before the
 * trailing-question check, or every quiescent turn ending in "?" would instead end in a
 * time and never match.
 */
public final class GrokPlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-23.1";

    // "grok 1.0.5 (5115b46bc909) [stable]": Resume this session with:
    //   grok --resume <session-id>
    // Captured verbatim from a real /quit — see harnessSessionIdFromExit below.
    private static final Pattern EXIT_RESUME = Pattern.compile(
            "(?is)Resume this session with:\\s*grok\\s+--resume\\s+"
                    + "(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9_@%+=:,./-]+))");

    /** tmux truncates {@code pane_current_command} to 15 bytes; the launcher execs the
     * downloaded versioned binary, so a live pane reports "grok-1.0.5-maco" rather than
     * "grok" — verified live at 1.0.5. */
    private static final Pattern VERSIONED_BINARY = Pattern.compile("grok-\\d.*");

    /** The status line while a turn is in flight reads e.g. "Waiting for response… 1.0s",
     * "Responding… 0.0s", or "Thinking… 0.2s", and the composer's hint bar swaps in
     * "Esc:cancel" for the duration — all verified live. Since the elapsed-time counter on
     * that status line ticks every poll, {@link StateDetector}'s behavioral layer already
     * reads WORKING without consulting this plugin; this is the same safety net
     * {@link CodexPlugin}'s WORKING_INDICATOR is, for a turn whose render freezes. */
    private static final Pattern WORKING_INDICATOR = Pattern.compile(
            "(?i)(esc:cancel|(waiting for response|responding|thinking)…)");

    /** Derived from the installed 1.0.5 binary's own string table
     * ({@code xai_grok_pager::app::acp_handler::permissions}), which lists the approval
     * headings verbatim: "Allow Edit?", "Allow Delete?", "Allow Execute?", "Allow?", and
     * "Allow `<command>`?" for a shell command. Not confirmed against a live render — this
     * install's global settings (~/.claude/settings.json, {@code defaultMode: "auto"})
     * auto-approve every tool call, so the dialog never painted during grounding. An
     * over-match here only adds WAITING_FOR_INPUT, the "needs you" badge, so the safe
     * direction is the same one {@link CodexPlugin}'s string-table-derived patterns take. */
    private static final Pattern APPROVAL = Pattern.compile(
            "(?i)(allow (edit|delete|execute)\\?|allow edit to\\b|allow `[^`]*`\\?|allow\\?)");

    /** Grok's own death, both shell phrasings, plus generic crash signatures — the same
     * narrow BROKEN shape {@link CodexPlugin} and {@link CursorAgentPlugin} use, so a
     * command a running Grok happens to run that itself isn't found doesn't get folded in. */
    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(grok: command not found|command not found: grok(?![\\w-])"
                    + "|panic:|segmentation fault|fatal error)");

    /** UI furniture stripped before the trailing-question check: the composer's rounded
     * border and side rails, the echoed input caret, transient "◆ Thought for …s" /
     * "◆ Creating …" tool-activity rows, the per-turn "Worked for …s" footer, and the
     * hint-bar legends — all verified live. */
    private static final Pattern CHROME = Pattern.compile(
            "(?i)(^\\s*[╭╮╰╯│]"
                    + "|^\\s*❯"
                    + "|^\\s*◆"
                    + "|shift\\+tab:mode"
                    + "|ctrl\\+x:shortcuts"
                    + "|esc:cancel"
                    + "|enter:send"
                    + "|^\\s*worked for [0-9.]+s\\s*$)");

    /** The idle hint bar carries both legends with no "Esc:cancel" between them — verified
     * live as "Shift+Tab:mode  │  Ctrl+x:shortcuts". */
    private static final Pattern IDLE_HINT = Pattern.compile(
            "(?i)shift\\+tab:mode.*ctrl\\+x:shortcuts");

    /** A clock timestamp Grok right-pads onto the same line as each chat bubble, e.g.
     * "...work on?                    6:04 PM" — verified live. Stripped before the
     * trailing-question check; see the class comment. */
    private static final Pattern TRAILING_CLOCK = Pattern.compile(
            "\\s{2,}\\d{1,2}:\\d{2}\\s?[AaPp][Mm]\\s*$");

    @Override
    public HarnessType type() {
        return HarnessType.GROK;
    }

    @Override
    public String launchCommand() {
        return "grok";
    }

    @Override
    public boolean ownsProcess(String foregroundCommand) {
        return "grok".equals(foregroundCommand) || VERSIONED_BINARY.matcher(foregroundCommand).matches();
    }

    /** {@code grok --resume <id>} — the {@code -r, --resume} flag documented by
     * {@code grok --help} and, independently, the exact syntax a real {@code /quit}
     * printed back (see {@link #harnessSessionIdFromExit}). */
    @Override
    public Optional<String> resumeCommand(String harnessSessionId) {
        return Optional.of("grok --resume " + HarnessPlugin.shellArgument(harnessSessionId));
    }

    @Override
    public Optional<String> harnessSessionIdFromExit(Screen exitScreen) {
        var matcher = EXIT_RESUME.matcher(exitScreen.text());
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        }
        return Optional.ofNullable(last);
    }

    /** {@code /quit} — verified live via the composer's own slash-command autocomplete
     * ("/quit  Quit the application"), and the bundled docs list {@code /exit} as an
     * alias. {@code /quit} is used here for the same unambiguous-over-alias reasoning
     * {@link CodexPlugin} gives for preferring it over {@code /exit}. */
    @Override
    public Optional<String> quitCommand() {
        return Optional.of("/quit");
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(20);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return classified(SessionState.IDLE, "error signature on screen — grok likely crashed");
        }
        if (WORKING_INDICATOR.matcher(tailText).find()) {
            return classified(SessionState.WORKING,
                    "esc-to-cancel visible: turn still running despite quiescent screen");
        }
        if (APPROVAL.matcher(tailText).find()) {
            return classified(SessionState.WAITING_FOR_INPUT, "allow/deny approval prompt shape");
        }
        List<String> content = tail.stream()
                .map(GrokPlugin::stripTrailingClock)
                .filter(line -> !CHROME.matcher(line).find())
                .toList();
        if (!content.isEmpty() && content.getLast().stripTrailing().endsWith("?")) {
            return classified(SessionState.WAITING_FOR_INPUT,
                    "quiescent with trailing question above UI chrome");
        }
        if (tail.stream().anyMatch(line -> IDLE_HINT.matcher(line).find())) {
            return classified(SessionState.IDLE, "idle hint bar, no running-turn indicator");
        }
        return Optional.empty();
    }

    /** Each bubble's flush-right clock stripped (see {@link #TRAILING_CLOCK}), then every
     * {@link #CHROME} line dropped — the composer box, hint bars, activity rows and the
     * "Worked for Ns" footer. */
    @Override
    public String maskChrome(String screenText) {
        return ScreenMask.join(ScreenMask.lines(stripVolatile(screenText)).stream()
                .map(GrokPlugin::stripTrailingClock)
                .filter(line -> !CHROME.matcher(ScreenMask.plain(line)).find())
                .toList());
    }

    private static String stripTrailingClock(String line) {
        return TRAILING_CLOCK.matcher(line).replaceAll("");
    }

    private static Optional<Classification> classified(SessionState state, String reason) {
        return Optional.of(new Classification(state, Confidence.HIGH,
                reason + " (patterns " + PATTERNS_VERSION + ")", Classification.Origin.PLUGIN));
    }
}
