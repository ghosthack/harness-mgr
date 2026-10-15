package io.github.ghosthack.harnessmanager.host;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the {@link SessionConnection#close()} contract on the pty4j substrate: idempotent,
 * and never a signal to a process that has already exited. pty4j's {@code destroy()} is
 * a bare {@code kill(pid)} with no liveness guard, and a reaped client's pid is free for
 * reuse — so an unguarded close of a long-dead terminal would SIGTERM whatever unrelated
 * process the OS handed that number to since.
 */
final class PtySessionConnectionTest {

    /** A {@link PtyProcess} whose liveness and destroy count the test controls. */
    private static final class FakePtyProcess extends PtyProcess {
        boolean alive;
        int destroyCount;

        FakePtyProcess(boolean alive) {
            this.alive = alive;
        }

        @Override
        public void setWinSize(WinSize winSize) {
        }

        @Override
        public WinSize getWinSize() {
            return new WinSize(80, 24);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        /** {@link Process#isAlive()} is defined in terms of this: alive while it throws. */
        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyCount++;
            alive = false;
        }

        @Override
        public long pid() {
            return 4242;
        }
    }

    @Test
    void closeAfterExitDoesNotSignal() {
        FakePtyProcess process = new FakePtyProcess(false);
        PtySessionConnection connection = new PtySessionConnection("t", process, true);
        connection.close();
        connection.close();
        assertEquals(0, process.destroyCount, "a reaped client's pid may already belong to someone else");
    }

    @Test
    void closeSignalsOnceWhileAlive() {
        FakePtyProcess process = new FakePtyProcess(true);
        PtySessionConnection connection = new PtySessionConnection("t", process, true);
        connection.close();
        connection.close(); // one detach produces several closes (widget, emulator, SessionTerminal)
        assertEquals(1, process.destroyCount);
        assertFalse(connection.isAlive());
    }

    /** The same guard on the real pty4j path: a client that has already exited is never
     * signalled, and closing it twice is harmless. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void closingAnExitedRealClientIsANoOp() throws Exception {
        PtySessionConnection connection = PtySessionConnection.spawn("t",
                List.of("/bin/sh", "-c", "exit 0"), System.getenv(), 80, 24, true);
        assertEquals(0, connection.waitFor());
        assertFalse(connection.isAlive());
        assertDoesNotThrow(connection::close);
        assertDoesNotThrow(connection::close);
    }
}
