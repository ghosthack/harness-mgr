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
 * Bootstrap + state-detection patterns for the cursor-agent CLI. Adapted from
 * ../harness-stimulator's {@code CursorAgentPlugin}: same regexes, collapsed onto the
 * manager's 3-state {@link SessionState} the same way {@link ClaudeCodePlugin} is.
 * Resume, quit, and exit-receipt syntax are grounded against Cursor Agent
 * 2026.08.11-e8db854: {@code --resume [chatId]}, {@code /quit}, and the emitted
 * {@code To resume this session: cursor-agent --resume=<chatId>} breadcrumb.
 */
public final class CursorAgentPlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-17.5";

    private static final Pattern APPROVAL = Pattern.compile(
            "(?i)(\\[y/n]|\\(y/n\\)|\\by/N\\b|\\byes/no\\b|allow this|allow\\?"
                    + "|run this command|run command\\?|press enter to (confirm|continue|approve)"
                    + "|do you want to (run|proceed|continue|allow)"
                    + "|[▸❯>]\\s*(yes|allow|approve))");

    private static final Pattern IDLE_INPUT_BOX = Pattern.compile(
            "(?i)(^\\s*[❯>]\\s*$|type a message|add a follow-up|send a message)");

    private static final Pattern BACKGROUND_TASKS = Pattern.compile(
            "(?i)^\\s*\\d+\\s+tasks?\\s*$");

    private static final Pattern INPUT_LINE = Pattern.compile(
            "(?i)(add a follow-up|type a message|send a message|^\\s*[❯>]\\s*$)");

    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(cursor-agent: command not found|panic:|traceback \\(most recent call last\\)"
                    + "|segmentation fault|fatal error)");

    private static final Pattern QUESTION_WIDGET = Pattern.compile(
            "(?i)(enter next/submit|space select|\\(type to answer\\)|[›▸]\\s*\\[[ x]\\])");

    private static final Pattern CHROME = Pattern.compile(
            "(?i)(^\\s*[→▸❯>]?\\s*(add a follow-up|type a message|send a message)"
                    + "|\\s·\\s"
                    + "|ctrl\\+\\w"
                    + "|^\\s*tip:"
                    + "|^\\s*[/~]\\S*\\s*$"
                    + "|^\\s*[⠀-⣿]+\\s*working)");

    /** The command name reflects how the binary was invoked; accept both official
     * entry points while still requiring Cursor's complete exit-receipt prefix. */
    private static final Pattern EXIT_RESUME = Pattern.compile(
            "(?is)To resume this session:\\s*(?:cursor-agent|agent)\\s+--resume(?:=|\\s+)"
                    + "(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9_@%+:,./-]+))");

    @Override
    public HarnessType type() {
        return HarnessType.CURSOR_AGENT;
    }

    @Override
    public String launchCommand() {
        return "cursor-agent";
    }

    @Override
    public Optional<String> resumeCommand(String harnessSessionId) {
        return Optional.of("cursor-agent --resume=" + HarnessPlugin.shellArgument(harnessSessionId));
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

    @Override
    public Optional<String> quitCommand() {
        return Optional.of("/quit");
    }

    /** Drops {@link #CHROME} lines: the follow-up input box, hint and tip lines, the
     * bare cwd line and the spinner's "working" row. */
    @Override
    public String maskChrome(String screenText) {
        return ScreenMask.mask(stripVolatile(screenText), line -> CHROME.matcher(line).find());
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(15);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return Optional.of(new Classification(SessionState.IDLE, Confidence.HIGH,
                    "error signature on screen — cursor-agent likely crashed (patterns "
                            + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (APPROVAL.matcher(tailText).find()) {
            return Optional.of(new Classification(SessionState.WAITING_FOR_INPUT, Confidence.HIGH,
                    "approval prompt shape (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (QUESTION_WIDGET.matcher(tailText).find()) {
            return Optional.of(new Classification(SessionState.WAITING_FOR_INPUT, Confidence.HIGH,
                    "question selector widget (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        List<String> content = tail.stream().filter(l -> !CHROME.matcher(l).find()).toList();
        if (!content.isEmpty() && content.getLast().stripTrailing().endsWith("?")) {
            return Optional.of(new Classification(SessionState.WAITING_FOR_INPUT, Confidence.HIGH,
                    "quiescent with trailing question above UI chrome (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        int lastInput = -1;
        for (int i = 0; i < tail.size(); i++) {
            if (INPUT_LINE.matcher(tail.get(i)).find()) {
                lastInput = i;
            }
        }
        if (lastInput >= 0 && tail.subList(lastInput + 1, tail.size()).stream()
                .anyMatch(l -> BACKGROUND_TASKS.matcher(l).find())) {
            return Optional.of(new Classification(SessionState.WORKING, Confidence.HIGH,
                    "main idle but background tasks running (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (tail.stream().anyMatch(l -> IDLE_INPUT_BOX.matcher(l).find())) {
            return Optional.of(new Classification(SessionState.IDLE, Confidence.HIGH,
                    "empty input box (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        return Optional.empty();
    }
}
