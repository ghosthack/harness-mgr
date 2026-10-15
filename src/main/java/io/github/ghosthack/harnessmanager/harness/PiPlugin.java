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
 * Bootstrap + state-detection patterns for earendil-works' {@code pi} CLI
 * ({@code @earendil-works/pi-coding-agent}), collapsed onto this app's 3-state
 * {@link SessionState} the same way {@link GrokPlugin} and {@link OpenCodePlugin} are.
 * There is no {@code ../harness-stimulator} precedent to adapt this from either (that
 * sibling project's {@code --harness} flag only recognizes {@code cursor-agent},
 * {@code claude-code}, and {@code codex}, which is also why {@code StimulateDialog} does
 * not offer it), so everything here was grounded directly against a live {@code pi
 * v0.84.2} pane (macOS, tmux 3.6a, 120x40): a fresh launch, a bash-tool turn, a project-
 * trust prompt (triggered via a scratch {@code .pi/settings.json}), and a real
 * {@code /quit}. Pi's own bundled docs confirm there is no approval dialog to ground for
 * an individual tool call — {@code docs/usage.md}'s Design Principles section states
 * pi "intentionally does not include ... permission popups", and {@code docs/security.md}
 * adds that built-in tools run bash/edit/write "with the permissions of the pi process"
 * with "no built-in sandbox" — so unlike the other four plugins, {@link #classifyQuiescent}
 * has no per-tool-call APPROVAL pattern; the one interactive prompt it recognizes is the
 * one-time project-trust dialog.
 *
 * <p>Two things make pi differ from the other four plugins:
 *
 * <ul>
 *   <li>The installed {@code pi} binary is a JS file run via a {@code #!/usr/bin/env
 *       node} shebang; Node execs itself as the process image, so tmux reports the
 *       pane's {@code pane_current_command} as "node", not "pi" — verified live. See
 *       {@link #ownsProcess}.
 *   <li>Its composer is a plain two-line frame — a bare box-drawing rule above the
 *       (possibly empty) input text and another below it, with no side rails and no
 *       prompt caret — so {@link #contentLines} splits transcript from footer on the
 *       rule immediately above that frame rather than on any glyph within it.
 * </ul>
 */
public final class PiPlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-23.1";

    // "To resume this session: pi --session 01a03190-5463-719c-94f2-c8a627bc2e33" —
    // printed to the pane's own shell right after /quit, verified live.
    private static final Pattern EXIT_RESUME = Pattern.compile(
            "(?is)To resume this session:\\s*pi\\s+--session\\s+([A-Za-z0-9_@%+=:,./-]+)");

    /** Node execs itself in place of the shebang script, so a live pane reports
     * "node" rather than "pi" for {@code pane_current_command} — verified live at
     * 0.84.2. This is necessarily broader than the other plugins' process checks (any
     * node process in the pane reads as "owned"), since tmux's report carries no
     * argv to narrow it further; see the class comment. */
    private static final String NODE_PROCESS = "node";

    /** "⠹ Working..." (a braille spinner glyph plus this literal text) — verified live.
     * Since the spinner repaints every poll, {@link StateDetector}'s behavioral layer
     * already reads WORKING without consulting this plugin; this is the same safety net
     * {@link CodexPlugin}'s WORKING_INDICATOR is, for a turn whose render freezes. */
    private static final Pattern WORKING_INDICATOR = Pattern.compile("(?i)working\\.\\.\\.");

    /** The one interactive prompt pi has: a one-time "Trust project folder?" dialog
     * shown at session start for a project carrying {@code .pi} resources with no saved
     * trust decision (see {@code docs/security.md}'s Project Trust section) — verified
     * live by launching in a scratch directory seeded with a bare {@code .pi/settings.json}.
     * The same shape {@link CodexPlugin}'s directory-trust prompt is for codex. */
    private static final Pattern TRUST_PROMPT = Pattern.compile("(?i)trust project folder\\?");

    /** Both shell spellings for a missing binary, plus generic crash signatures — the
     * same narrow BROKEN shape {@link GrokPlugin} and {@link OpenCodePlugin} use, so a
     * command a running pi happens to run that itself isn't found doesn't get folded in. */
    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(pi: command not found|command not found: pi(?![\\w-])"
                    + "|panic:|segmentation fault|fatal error)");

    /** The composer's plain box-drawing rule, top and bottom, verified live as the
     * stable divider between the transcript above and the (possibly wrapped) cwd-plus-
     * usage footer below — present in every state, working or idle, the same way
     * {@link OpenCodePlugin}'s composer border is. */
    private static final Pattern COMPOSER_BORDER = Pattern.compile("^─+$");

    @Override
    public HarnessType type() {
        return HarnessType.PI;
    }

    @Override
    public String launchCommand() {
        return "pi";
    }

    // processName() is overridden to "node" below; the default ownsProcess() (equality
    // against processName()) is exactly right here, so it is left unoverridden — unlike
    // GrokPlugin, there is no second, versioned-binary spelling to also match.
    @Override
    public String processName() {
        return NODE_PROCESS;
    }

    /**
     * {@code pi --session <id>} — the {@code --session <path|id>} flag documented by
     * {@code pi --help} and confirmed live: resuming with it replayed the full prior
     * transcript rather than starting fresh, and it's the exact syntax a real
     * {@code /quit} printed back (see {@link #harnessSessionIdFromExit}).
     */
    @Override
    public Optional<String> resumeCommand(String harnessSessionId) {
        return Optional.of("pi --session " + HarnessPlugin.shellArgument(harnessSessionId));
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

    /** {@code /quit} — verified live via the composer's own slash-command autocomplete
     * ("/quit  Quit pi"); {@code /exit} is not a recognized command (typing it leaves
     * the composer showing plain, unmatched text rather than an autocomplete entry). */
    @Override
    public Optional<String> quitCommand() {
        return Optional.of("/quit");
    }

    /** The transcript above the composer frame's top rule, the same split
     * {@link #contentLines} makes: the frame and the cwd-plus-usage footer below it are
     * all chrome. */
    @Override
    public String maskChrome(String screenText) {
        List<String> lines = ScreenMask.lines(stripVolatile(screenText));
        int borderAt = -1;
        int seen = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (COMPOSER_BORDER.matcher(ScreenMask.plain(lines.get(i)).strip()).find()) {
                seen++;
                borderAt = i;
                if (seen == 2) {
                    break;
                }
            }
        }
        return ScreenMask.join(borderAt >= 0 ? lines.subList(0, borderAt) : lines);
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(20);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return classified(SessionState.IDLE, "error signature on screen — pi likely crashed");
        }
        if (WORKING_INDICATOR.matcher(tailText).find()) {
            return classified(SessionState.WORKING,
                    "working indicator visible: turn still running despite quiescent screen");
        }
        if (TRUST_PROMPT.matcher(tailText).find()) {
            return classified(SessionState.WAITING_FOR_INPUT, "project-trust prompt shape");
        }
        List<String> content = contentLines(tail);
        if (!content.isEmpty() && content.getLast().stripTrailing().endsWith("?")) {
            return classified(SessionState.WAITING_FOR_INPUT,
                    "quiescent with trailing question above UI chrome");
        }
        if (tail.stream().anyMatch(line -> COMPOSER_BORDER.matcher(line.strip()).find())) {
            return classified(SessionState.IDLE, "composer frame, no running-turn indicator");
        }
        return Optional.empty();
    }

    /** The transcript above the composer's top rule (see {@link #COMPOSER_BORDER}): the
     * frame's own rule lines are the only chrome pi draws outside the footer, so unlike
     * {@link OpenCodePlugin#contentLines} no separate per-line chrome pattern is needed
     * once the split point is found. */
    private static List<String> contentLines(List<String> tail) {
        int borderAt = -1;
        int seen = 0;
        for (int i = tail.size() - 1; i >= 0; i--) {
            if (COMPOSER_BORDER.matcher(tail.get(i).strip()).find()) {
                seen++;
                borderAt = i;
                if (seen == 2) {
                    break;
                }
            }
        }
        return borderAt >= 0 ? tail.subList(0, borderAt) : tail;
    }

    private static Optional<Classification> classified(SessionState state, String reason) {
        return Optional.of(new Classification(state, Confidence.HIGH,
                reason + " (patterns " + PATTERNS_VERSION + ")", Classification.Origin.PLUGIN));
    }
}
