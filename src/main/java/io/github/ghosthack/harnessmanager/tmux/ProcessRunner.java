package io.github.ghosthack.harnessmanager.tmux;

import io.github.ghosthack.harnessmanager.host.SessionHostException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one local process to completion with a bounded ceiling: a wedged/slow process
 * produces a {@link SessionHostException} instead of a hang. Shared by {@link RealTmux}'s
 * {@code exec} (local tmux, or the SSH-wrapped remote transport) and {@link SshExec}'s
 * one-off, non-tmux commands (resolving the remote {@code tmux} path, unlocking a
 * keychain) — both need the exact same spawn/timeout/drain shape, so it's written and
 * fixed exactly once.
 */
final class ProcessRunner {

    record Result(int exitCode, String output) {
    }

    /** {@code label} is only for the timeout/failure messages — e.g. {@code "tmux
     * has-session ..."} or {@code "ssh"}. */
    static Result run(List<String> cmd, long timeoutSeconds, String label) {
        try {
            // Spawn from the home directory, not the JVM's cwd: a tmux server
            // auto-started by this command inherits this cwd for life, and on tmux 3.7
            // a server whose cwd is later deleted spawns every new pane in that dead
            // directory, silently ignoring new-session's -c.
            Process p = new ProcessBuilder(cmd)
                    .directory(new File(System.getProperty("user.home")))
                    .redirectErrorStream(true)
                    .start();
            // Read on a separate thread so the timeout below can actually fire: a
            // wedged process that never closes its pipe would otherwise block
            // readAllBytes forever.
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    in.transferTo(buffer);
                } catch (IOException ignored) {
                    // stream closed below on timeout, or the process died — partial output is fine
                }
            });
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                closeQuietly(in); // unblocks the reader even if something still holds the pipe
                reader.join(1000);
                throw new SessionHostException(label + " timed out after " + timeoutSeconds + "s");
            }
            // The process has exited, but the pipe can outlive it if it leaked its write
            // end to a child — bound the drain so that corner can't hang past the ceiling.
            if (!reader.join(Duration.ofSeconds(timeoutSeconds))) {
                closeQuietly(in);
                reader.join(1000);
            }
            return new Result(p.exitValue(), buffer.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SessionHostException("failed to run " + label, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionHostException("interrupted running " + label, e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    private ProcessRunner() {
    }
}
