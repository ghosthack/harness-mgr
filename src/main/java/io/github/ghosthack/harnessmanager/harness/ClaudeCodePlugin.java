package io.github.ghosthack.harnessmanager.harness;

import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Confidence;
import io.github.ghosthack.harnessmanager.state.Screen;
import io.github.ghosthack.harnessmanager.state.SessionState;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bootstrap + state-detection patterns for the Claude Code CLI. Adapted from
 * ../harness-stimulator's {@code ClaudeCodePlugin}: same regexes, collapsed onto the
 * manager's 3-state {@link SessionState} (approval and question both read
 * WAITING_FOR_INPUT; the error-signature check folds into IDLE since there's no
 * separate "broken" badge here). These patterns rot with harness UI updates the same
 * way they do upstream — empty falls back to the generic behavioral heuristics.
 */
public final class ClaudeCodePlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-17.6";

    private static final Pattern WORKING_INDICATOR = Pattern.compile(
            "(?i)esc to interrupt");

    private static final Pattern APPROVAL = Pattern.compile(
            "(?i)(do you want to|don't ask again|[❯›]?\\s*1\\.\\s*yes\\b"
                    + "|allow this|\\[y/n]|\\(y/n\\))");

    private static final Pattern OPTION_SELECTOR = Pattern.compile(
            "[❯›]\\s*\\d+\\.\\s");

    private static final Pattern BACKGROUND_WORK = Pattern.compile(
            "(?im)(·\\s*\\d+\\s+shells?\\s*·|^\\s*◯\\s+\\S+)");

    private static final Pattern INPUT_LINE = Pattern.compile(
            "(^[\\s\\u00A0]*❯|^[\\s\\u00A0]*│[\\s\\u00A0]*>)");

    private static final Pattern IDLE_STATUS = Pattern.compile(
            "(shift\\+tab to cycle|\\? for shortcuts|auto mode on|accept edits on"
                    + "|^[\\s\\u00A0]*│?[\\s\\u00A0]*>[\\s\\u00A0]*│?[\\s\\u00A0]*$"
                    + "|^[\\s\\u00A0]*❯[\\s\\u00A0]*$)");

    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(claude: command not found|panic:|traceback \\(most recent call last\\)"
                    + "|segmentation fault|fatal error)");

    private static final Pattern VERSIONED_BINARY = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /** Whether {@code node} counts as this harness (see {@link #ownsProcess}). */
    private final boolean windows;

    public ClaudeCodePlugin() {
        this(Platform.isWindows());
    }

    /** {@code windows} is injectable so both branches of {@link #ownsProcess} have a
     * unit test regardless of the platform the suite runs on. */
    ClaudeCodePlugin(boolean windows) {
        this.windows = windows;
    }

    /** Claude Code prints this receipt after /exit. The selector is normally a UUID,
     * but a renamed session is printed as a quoted name and is equally resumable. */
    private static final Pattern EXIT_RESUME = Pattern.compile(
            "(?is)Resume this session with:\\s*claude\\s+--resume\\s+"
                    + "(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9_@%+=:,./-]+))");

    @Override
    public HarnessType type() {
        return HarnessType.CLAUDE_CODE;
    }

    @Override
    public String processName() {
        return "claude";
    }

    // the launcher execs the versioned binary, so tmux reports e.g. "2.1.235"; on
    // Windows the npm shim runs node.exe as the shell's child instead, so the
    // foreground is "node" there — as broad as PiPlugin's check, for the same reason
    @Override
    public boolean ownsProcess(String foregroundCommand) {
        return "claude".equals(foregroundCommand)
                || VERSIONED_BINARY.matcher(foregroundCommand).matches()
                || windows && "node".equals(foregroundCommand);
    }

    @Override
    public String launchCommand() {
        return "claude";
    }

    @Override
    public Optional<String> resumeCommand(String harnessSessionId) {
        return Optional.of("claude --resume " + HarnessPlugin.shellArgument(harnessSessionId));
    }

    /** {@code claude --session-id <uuid>}: Claude Code names the new conversation (and its
     * transcript, {@code <id>.jsonl}) after the id it is given. */
    @Override
    public Optional<String> launchCommandWithId(String harnessSessionId) {
        return Optional.of("claude --session-id " + HarnessPlugin.shellArgument(harnessSessionId));
    }

    /** Claude Code writes {@code <id>.jsonl} with the conversation's first message; before
     * that, {@code --resume <id>} fails with "No conversation found". */
    @Override
    public boolean canResume(String harnessSessionId, Path project) {
        return ClaudeProjects.transcript(ClaudeProjects.root(), project, harnessSessionId).isPresent();
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

    /**
     * {@code claude --mcp-config <file>} — the file form of {@code --mcp-config}, which
     * adds to (never replaces) the user's own servers for this run. The file
     * ({@link #mcpConfigFile}) carries the URL and the bearer token as an
     * {@code Authorization} header, so the token stays out of the typed line. Inserted
     * right after the leading {@code claude} so a resume's {@code --resume <id>} still
     * follows it; {@code --mcp-config} takes a list of values, and the next {@code --}
     * option is what ends that list.
     */
    @Override
    public String withMcpServer(String command, McpEndpoint endpoint) {
        return ScreenMask.insertAfterProgram(command, "claude",
                "--mcp-config " + HarnessPlugin.shellArgument(endpoint.configFile().toString()));
    }

    /** {@code {"mcpServers":{"harness-manager":{"type":"http","url":…,"headers":{"Authorization":"Bearer …"}}}}}. */
    @Override
    public Optional<String> mcpConfigFile(McpEndpoint endpoint) {
        return Optional.of("{\"mcpServers\":{" + ScreenMask.quoted(MCP_SERVER_NAME)
                + ":{\"type\":\"http\",\"url\":" + ScreenMask.quoted(endpoint.url())
                + ",\"headers\":{\"Authorization\":" + ScreenMask.quoted("Bearer " + endpoint.token())
                + "}}}}\n");
    }

    /** Drops {@link #CHROME} lines: the input box, rules, hint bars and the spinner line
     * (its "esc to interrupt" hint), so a ticking turn timer is not a content change. */
    @Override
    public String maskChrome(String screenText) {
        return ScreenMask.mask(stripVolatile(screenText), ClaudeCodePlugin::isChrome);
    }

    /** {@code /exit} ends the Claude Code session from its idle prompt. */
    @Override
    public Optional<String> quitCommand() {
        return Optional.of("/exit");
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(20);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return Optional.of(new Classification(SessionState.IDLE, Confidence.HIGH,
                    "error signature on screen — claude likely crashed (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (WORKING_INDICATOR.matcher(tailText).find()) {
            return Optional.of(new Classification(SessionState.WORKING, Confidence.HIGH,
                    "esc-to-interrupt visible: turn still running despite quiescent screen (patterns "
                            + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (APPROVAL.matcher(tailText).find()) {
            return Optional.of(new Classification(SessionState.WAITING_FOR_INPUT, Confidence.HIGH,
                    "permission dialog shape (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (OPTION_SELECTOR.matcher(tailText).find()) {
            return Optional.of(new Classification(SessionState.WAITING_FOR_INPUT, Confidence.HIGH,
                    "option selector widget (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        List<String> content = tail.stream().filter(l -> !isChrome(l)).toList();
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
        if (lastInput >= 0) {
            String belowInput = String.join("\n", tail.subList(lastInput + 1, tail.size()));
            if (BACKGROUND_WORK.matcher(belowInput).find()) {
                return Optional.of(new Classification(SessionState.WORKING, Confidence.HIGH,
                        "main idle but subagents/background work running (patterns " + PATTERNS_VERSION + ")",
                        Classification.Origin.PLUGIN));
            }
        }
        if (tail.stream().anyMatch(l -> IDLE_STATUS.matcher(l).find())) {
            return Optional.of(new Classification(SessionState.IDLE, Confidence.HIGH,
                    "idle status bar, no running-turn indicator (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        return Optional.empty();
    }

    // box-drawing borders (input box and dialogs), horizontal rules, hint bars,
    // the bare input caret, and the emoji status line
    private static final Pattern CHROME = Pattern.compile(
            "(?i)(^\\s*[╭╰│⏵❯]|^\\s*─+\\s*$|\\? for shortcuts|esc to |shift\\+tab"
                    + "|auto-accept|plan mode|bypass permissions|tokens?\\s*$|📁|🧠)");

    private static boolean isChrome(String line) {
        return CHROME.matcher(line).find();
    }
}
