package io.github.ghosthack.harnessmanager.stimulator;

import java.nio.file.Path;
import java.util.List;

/**
 * Test-side access to {@link StimulatorClient}'s package-private constructor, for
 * tests in other packages (the UI suite) that need a client whose lock and log
 * directories are temp dirs — never the real {@code ~/.harness-stimulator} — and
 * whose peek command is a stand-in process rather than the stimulator itself.
 */
public final class StimulatorClients {

    private StimulatorClients() {
    }

    /** A client that runs {@code commandPrefix} (plus the subcommand and its
     * arguments) for every subprocess and reads its locks from {@code stateDir}. */
    public static StimulatorClient withCommand(List<String> commandPrefix, Path stateDir, Path logDir) {
        return new StimulatorClient(commandPrefix, stateDir, logDir);
    }
}
