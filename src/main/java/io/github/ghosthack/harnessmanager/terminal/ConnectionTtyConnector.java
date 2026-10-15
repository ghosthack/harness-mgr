package io.github.ghosthack.harnessmanager.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import io.github.ghosthack.harnessmanager.host.SessionConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Bridges a {@link SessionConnection} to a JediTerm widget: decodes the connection's
 * UTF-8 output into the chars the emulator reads, forwards input bytes and resizes.
 * Successor of the pty4j-specific {@code PtyTtyConnector} — the widget no longer knows
 * whether a PTY, an SSH hop or a daemon socket is behind the bytes. Input is passed
 * straight through: a read-only connection (the stimulator's peek) drops it itself.
 */
public final class ConnectionTtyConnector implements TtyConnector {

    private final SessionConnection connection;
    private final Reader reader;

    public ConnectionTtyConnector(SessionConnection connection) {
        this.connection = connection;
        this.reader = new InputStreamReader(new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = connection.read(one, 0, 1);
                return n <= 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return connection.read(b, off, len);
            }
        }, StandardCharsets.UTF_8);
    }

    @Override
    public String getName() {
        return connection.name();
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        return reader.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        connection.write(bytes);
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return connection.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return connection.waitFor();
    }

    /** Only decoder-buffered chars count: JediTerm uses this purely to decide whether
     * to run its before-blocking-wait hook, so a conservative answer is harmless. */
    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public void resize(TermSize termSize) {
        connection.resize(termSize.getColumns(), termSize.getRows());
    }

    /** Detaches; the session behind the connection survives. */
    @Override
    public void close() {
        connection.close();
    }
}
