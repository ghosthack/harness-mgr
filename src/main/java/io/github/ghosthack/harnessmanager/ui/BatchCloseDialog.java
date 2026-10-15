package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessPlugins;
import io.github.ghosthack.harnessmanager.host.SessionHostException;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import io.github.ghosthack.harnessmanager.state.SessionState;
import io.github.ghosthack.harnessmanager.status.GracefulCloser;

import java.awt.Window;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * The toolbar's "Batch close…": lets the user hand-pick a set of live sessions, grouped by
 * project folder, and closes all of them through the same graceful ladder as Close/Shutdown
 * — each harness asked to quit, then its shell asked to exit. Unlike {@code shutdownAll()}
 * (which always targets every live session), the selection here defaults to every idle
 * session but is fully editable per-folder or per-session before anything is touched, and
 * the run itself is shown live via {@link BatchOperationDialog}'s progress bar rather than
 * only reported afterward.
 */
final class BatchCloseDialog {

    static void show(Window parent, List<SidebarEntry> candidates, GracefulCloser gracefulCloser,
                     SessionRegistry registry, ExecutorService backgroundExecutor,
                     Consumer<String> dropRuntimeArtifacts, Runnable onFinished) {
        BatchOperationDialog.show(parent,
                "Close a batch of sessions",
                "<html>Idle sessions are pre-selected. Uncheck any folder or session you want "
                        + "to leave running.</html>",
                "Close selected",
                candidates,
                entry -> entry.state() == SessionState.IDLE,
                BatchCloseDialog::stateLabel,
                new BatchOperationDialog.ActionWords("Closing", "Closed", "close"),
                backgroundExecutor,
                entry -> attemptClose(entry, gracefulCloser, registry),
                entry -> dropRuntimeArtifacts.accept(entry.tmuxSession()),
                onFinished);
    }

    private static Optional<String> attemptClose(SidebarEntry entry, GracefulCloser gracefulCloser,
                                                  SessionRegistry registry) {
        SessionEntry sessionEntry = entry.sessionEntry();
        String name = sessionEntry.tmuxSession();
        try {
            GracefulCloser.Result result = gracefulCloser.close(name, sessionEntry.targetPane(),
                    HarnessPlugins.of(sessionEntry.harnessType()), entry.state());
            result.harnessSessionIdOpt().ifPresent(id -> registry.recordHarnessSessionId(name, id));
            return switch (result.outcome()) {
                case CLOSED, ALREADY_GONE -> Optional.empty();
                case BUSY_STATE -> Optional.of(name + " — " + result.detail());
                case BUSY_FOREGROUND -> Optional.of(name + " — '" + result.detail()
                        + "' is still running in its pane");
                case NO_QUIT_SYNTAX -> Optional.of(name + " — no verified graceful-quit syntax");
                case QUIT_TIMEOUT, EXIT_TIMEOUT -> Optional.of(name + " — didn't exit in time");
            };
        } catch (SessionHostException e) {
            return Optional.of(name + " — " + e.getMessage());
        }
    }

    private static String stateLabel(SidebarEntry entry) {
        if (entry.state() == null) {
            return "status pending";
        }
        return switch (entry.state()) {
            case WORKING -> "working";
            case WAITING_FOR_INPUT -> "waiting for input";
            case IDLE -> "idle";
        };
    }

    private BatchCloseDialog() {
    }
}
