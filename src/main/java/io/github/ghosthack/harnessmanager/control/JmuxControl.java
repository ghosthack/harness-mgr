package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * What the {@link SessionController} needs from the jmux daemon — a seam so tests can
 * drive the controller without a daemon. The production implementation is
 * {@link ClientJmuxControl} over the app's {@code SessionServerClient}. Every call may
 * block on a socket round trip and may throw a {@link RuntimeException} (no server, no
 * such session, a refused request); the controller turns those into tool errors.
 *
 * <p>A daemon outlives app upgrades, so the one running may predate some of these calls
 * (input, wait, watch, label, capture formats other than text): it refuses them with a
 * {@code SessionServerException} whose {@code isUnsupported()} is true, and capture
 * reports {@code outputBytes} as -1 (unknown). The controller degrades on those.
 */
public interface JmuxControl {

    /** Every session the daemon hosts; empty when no daemon runs. */
    List<Session.Info> list();

    /** The screen in {@code format}; empty when the session (or the daemon) is gone. */
    Optional<Session.Capture> capture(String name, CaptureFormat format);

    /** {@code text} (null = none) pasted or typed, then the named {@code keys} (null or
     * empty = none). An unknown key name fails before anything is written. */
    default void input(String name, String text, boolean paste, List<String> keys) {
        input(name, text, paste, false, keys);
    }

    /** As above; with {@code requireBracketedPaste} a paste into a program that has not
     * enabled bracketed paste is refused with nothing written — a
     * {@code SessionServerException} whose message is
     * {@code Protocol.ERROR_BRACKETED_PASTE_DISABLED} — instead of being typed raw. A
     * daemon that predates the flag ignores it. */
    void input(String name, String text, boolean paste, boolean requireBracketedPaste, List<String> keys);

    /** Blocks until the output is quiet; see {@code Session#awaitQuiet}. */
    Session.WaitResult awaitQuiet(String name, long sinceBytes, long quietMs, long timeoutMs);

    /** Streams the events of every session, including later ones, to {@code onEvent}
     * until closed or until the daemon ends the stream ({@link Watch#isOpen} false). */
    Watch watch(long quietMs, Consumer<SessionEvent> onEvent);

    /** The old {@code send-keys} op every daemon supports: {@code text} typed as is (not a
     * paste — a newline in it submits), then when {@code enter} the server's 400 ms pause
     * and Enter. Only used when the daemon predates {@link #input}. */
    void sendKeys(String name, String text, boolean enter);

    /** The running daemon's process id (from ping); empty when none answers. */
    OptionalLong daemonPid();

    /** Whether a daemon answers a ping right now — what tells "the session is gone" from
     * "the daemon is unreachable" when {@link #list} or {@link #capture} comes back empty. */
    boolean ping();

    void kill(String name);

    /** Merges {@code changes} into the session's labels (a null value removes a key). */
    void label(String name, Map<String, String> changes);

    /** A live event stream. */
    interface Watch extends AutoCloseable {
        boolean isOpen();

        @Override
        void close();
    }
}
