package io.github.ghosthack.harnessmanager.host;

import java.io.IOException;

/**
 * A live, bidirectional byte stream plus resize, attached to one session on one
 * {@link SessionHost} — what a terminal widget renders. Substrate-neutral: a tmux
 * client running in a PTY, a stimulator peek, or a daemon attach stream all look the
 * same from here. {@link #close()} detaches; it never kills the session.
 */
public interface SessionConnection extends AutoCloseable {

    /** Short label for diagnostics (the widget's connector name). */
    String name();

    /** Reads output bytes; {@code -1} once the connection has ended. Blocks. */
    int read(byte[] buf, int off, int len) throws IOException;

    /** Sends input bytes. A read-only connection drops them silently. */
    void write(byte[] bytes) throws IOException;

    void resize(int columns, int rows);

    boolean isAlive();

    /** Blocks until the connection ends; the exit status of the underlying process,
     * or 0 where the substrate has no such notion. */
    int waitFor() throws InterruptedException;

    /** Detaches. Idempotent; never kills the session. */
    @Override
    void close();
}
