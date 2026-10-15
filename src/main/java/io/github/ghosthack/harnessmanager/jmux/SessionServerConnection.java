package io.github.ghosthack.harnessmanager.jmux;

import io.github.ghosthack.harnessmanager.host.PtySessionConnection;
import io.github.ghosthack.harnessmanager.host.SessionConnection;
import io.github.ghosthack.jmux.client.AttachedSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * A {@link SessionConnection} over the daemon's attach stream. Thin by design: the
 * daemon's {@link AttachedSession} was shaped after what a terminal widget needs from a
 * PTY, so every call maps one to one. {@link #close()} sends DETACH and closes the
 * socket; the session — and the daemon — live on, exactly as a tmux client's detach
 * leaves its session.
 */
final class SessionServerConnection implements SessionConnection {

    private static final Logger log = LoggerFactory.getLogger(SessionServerConnection.class);

    private final AttachedSession attached;

    SessionServerConnection(AttachedSession attached) {
        this.attached = attached;
    }

    @Override
    public String name() {
        return attached.sessionName();
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        return attached.read(buf, off, len);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        attached.write(bytes);
    }

    /**
     * Best effort, like {@link PtySessionConnection}'s resize of a dead process: the
     * widget calls this from its layout pass on the EDT, and a socket that breaks
     * underneath it is reported by the reader's next {@link #read} returning -1, not
     * by an exception out of a layout.
     */
    @Override
    public void resize(int columns, int rows) {
        if (!attached.isConnected()) {
            return;
        }
        try {
            attached.resize(columns, rows);
        } catch (IOException e) {
            log.debug("resize of the attach stream for {} failed: {}", name(), e.toString());
        }
    }

    @Override
    public boolean isAlive() {
        return attached.isConnected();
    }

    /** The session's exit code when it ended while attached; 0 after a detach or a lost
     * server, which have no process status — what a detached tmux client exits with. */
    @Override
    public int waitFor() throws InterruptedException {
        attached.waitFor();
        return attached.exitCode().orElse(0);
    }

    @Override
    public void close() {
        attached.close();
    }
}
