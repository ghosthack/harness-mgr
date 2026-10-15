package io.github.ghosthack.harnessmanager.host;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link SessionConnection} over a pty4j process: a {@code tmux attach-session}
 * client (local, or wrapped in {@code ssh -t}), or harness-stimulator's read-only
 * {@code peek}. Substrate-neutral — the caller supplies the argv, environment and
 * size; this class only owns the PTY lifecycle. A non-writable connection still
 * consumes output but drops every input byte, which is what makes the stimulator's
 * peek card genuinely read-only even when it has keyboard focus.
 */
public final class PtySessionConnection implements SessionConnection {

    private final String name;
    private final PtyProcess process;
    private final InputStream input;
    private final OutputStream output;
    private final boolean writable;
    /** Set by the first {@link #close()}; see there for why a second one must be a no-op. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Package-private so a test can stand a fake {@link PtyProcess} in for a spawned one. */
    PtySessionConnection(String name, PtyProcess process, boolean writable) {
        this.name = name;
        this.process = process;
        this.input = process.getInputStream();
        this.output = process.getOutputStream();
        this.writable = writable;
    }

    /**
     * Spawns {@code command} in a fresh PTY of the given size. The process is started
     * from the user's home directory, not the JVM's cwd — same rationale as
     * {@code ProcessRunner}: a tmux server auto-started by a client inherits its cwd for
     * life, and a cwd deleted after the app launched breaks every later pane.
     */
    public static PtySessionConnection spawn(String name, List<String> command, Map<String, String> env,
                                             int columns, int rows, boolean writable) throws IOException {
        PtyProcess process = new PtyProcessBuilder(command.toArray(String[]::new))
                .setEnvironment(env)
                .setDirectory(System.getProperty("user.home"))
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .start();
        return new PtySessionConnection(name, process, writable);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        return input.read(buf, off, len);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (!writable) {
            return;
        }
        output.write(bytes);
        output.flush();
    }

    @Override
    public void resize(int columns, int rows) {
        if (process.isAlive()) {
            process.setWinSize(new WinSize(columns, rows));
        }
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }

    /**
     * Destroys the client process if it is still running. For a tmux client that is a
     * detach; the session survives. Guarded on both counts because pty4j's
     * {@code destroy()} is a bare {@code kill(pid, SIGTERM)} with no liveness check, and
     * once the client has exited and been reaped its pid is free for the OS to hand to
     * an unrelated process: an unguarded close of a terminal whose client ended hours
     * ago (a shell that exited, a session killed elsewhere) would signal whatever now
     * owns that number. The flag additionally collapses the several closes one detach
     * produces (the widget's connector close, the emulator's end-of-stream, and
     * {@code SessionTerminal.detach} itself) into a single signal.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && process.isAlive()) {
            process.destroy();
        }
    }
}
