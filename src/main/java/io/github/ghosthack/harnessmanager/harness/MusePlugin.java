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
 * Muse Code 1.4.7: launch/resume help and live trusted/untrusted workspace captures
 * ground its bootstrap, trust/login prompts, echo-provider working/idle UI and /quit
 * receipt. The launcher execs a versioned native binary; tmux truncates its process
 * name to "muse-bin-1.4.7-" on macOS. No safety or trust flags are added at launch.
 */
public final class MusePlugin implements HarnessPlugin {

    private static final Pattern EXIT_RESUME = Pattern.compile(
            "(?m)^\\s*To continue this session, run muse resume "
                    + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\b");
    private static final Pattern WORKING = Pattern.compile("(?m)^\\s*◇\\s+Thinking\\b");
    private static final Pattern BORDER = Pattern.compile("^(?:── Voice input .*─+|─{3,})$");

    @Override
    public HarnessType type() {
        return HarnessType.MUSE;
    }

    @Override
    public String launchCommand() {
        return "muse";
    }

    @Override
    public boolean ownsProcess(String command) {
        return "muse".equals(command) || command.startsWith("muse-bin-");
    }

    @Override
    public Optional<String> resumeCommand(String sessionId) {
        return Optional.of("muse resume " + HarnessPlugin.shellArgument(sessionId));
    }

    @Override
    public Optional<String> quitCommand() {
        return Optional.of("/quit");
    }

    @Override
    public Optional<String> harnessSessionIdFromExit(Screen screen) {
        var matcher = EXIT_RESUME.matcher(screen.text());
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return Optional.ofNullable(last);
    }

    @Override
    public String maskChrome(String text) {
        List<String> lines = ScreenMask.lines(text);
        int borderAt = -1;
        int seen = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (BORDER.matcher(ScreenMask.plain(lines.get(i)).strip()).matches()) {
                borderAt = i;
                if (++seen == 2) {
                    break;
                }
            }
        }
        return ScreenMask.join(borderAt >= 0 ? lines.subList(0, borderAt) : lines);
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        String tail = String.join("\n", screen.tailLines(24));
        if (tail.contains("Do you trust this workspace?")) {
            return classified(SessionState.WAITING_FOR_INPUT, "workspace trust decision");
        }
        if (tail.contains("Log in with browser") && tail.contains("Set an API key")) {
            return classified(SessionState.WAITING_FOR_INPUT, "Muse login choice");
        }
        // Muse's documented approval choices; require the full pair, not quoted prose.
        if (tail.contains("Allow once") && tail.contains("Always allow in this workspace")
                && tail.contains("Reject")) {
            return classified(SessionState.WAITING_FOR_INPUT, "tool approval choices");
        }
        if (WORKING.matcher(tail).find()) {
            return classified(SessionState.WORKING, "Muse thinking indicator");
        }
        String content = maskChrome(tail).stripTrailing();
        if (content.endsWith("?")) {
            return classified(SessionState.WAITING_FOR_INPUT, "trailing question above composer");
        }
        if (tail.lines().anyMatch(line -> BORDER.matcher(line.strip()).matches())) {
            return classified(SessionState.IDLE, "Muse composer without running-turn indicator");
        }
        return Optional.empty();
    }

    private static Optional<Classification> classified(SessionState state, String reason) {
        return Optional.of(new Classification(state, Confidence.HIGH,
                reason + " (Muse Code 1.4.7)", Classification.Origin.PLUGIN));
    }
}
