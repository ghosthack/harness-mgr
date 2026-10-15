package io.github.ghosthack.harnessmanager.host;

/**
 * A session-host operation failed: a tmux command errored or timed out, an SSH hop
 * could not be made, a daemon refused a request. Substrate-neutral successor of the
 * old {@code TmuxException}, so nothing above the {@link SessionHost} seam has to
 * know which substrate it is talking to when it reports a failure.
 */
public class SessionHostException extends RuntimeException {

    public SessionHostException(String message) {
        super(message);
    }

    public SessionHostException(String message, Throwable cause) {
        super(message, cause);
    }
}
