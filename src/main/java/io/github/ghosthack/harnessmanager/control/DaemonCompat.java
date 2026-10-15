package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.jmux.client.SessionServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * What the running jmux daemon is known not to support. The daemon outlives app upgrades
 * — it keeps running until its last session ends — so the controller may talk to one that
 * predates input, wait, watch or annotated capture. The first refusal of a feature
 * ({@link #isUnsupported}) is remembered for that daemon, identified by the pid its ping
 * reports, so the controller stops asking and takes its fallback at once; everything is
 * forgotten once a different daemon (or none) answers.
 *
 * <p>Thread-safe; never holds its lock across a daemon round trip.
 */
final class DaemonCompat {

    private static final Logger LOG = LoggerFactory.getLogger(DaemonCompat.class);

    /** A protocol feature newer daemons have and old ones refuse. */
    enum Feature {
        ANNOTATED_CAPTURE, INPUT, WAIT, WATCH
    }

    private final Supplier<OptionalLong> pid;
    /** Guarded by {@code this}, as is {@link #missingPid}. */
    private final EnumSet<Feature> missing = EnumSet.noneOf(Feature.class);
    /** The daemon {@link #missing} describes; -1 = unknown (its ping did not answer). */
    private long missingPid = -1;

    DaemonCompat(Supplier<OptionalLong> pid) {
        this.pid = pid;
    }

    /** Whether {@code e} is a daemon's refusal of an op it predates. */
    static boolean isUnsupported(Throwable e) {
        return e instanceof SessionServerException s && s.isUnsupported();
    }

    /** Whether {@code feature} is worth trying: not known to be missing from the running daemon. */
    synchronized boolean has(Feature feature) {
        return !missing.contains(feature);
    }

    /** Whether the running daemon is known to predate anything. */
    synchronized boolean isOld() {
        return !missing.isEmpty();
    }

    /** The old daemon's pid as its ping reported it, -1 when unknown. */
    synchronized long pid() {
        return missingPid;
    }

    /** Records that the running daemon refused {@code feature}; true the first time
     * (for this daemon), so the caller can log it once. */
    boolean markMissing(Feature feature) {
        long current = ping();
        synchronized (this) {
            if (missing.isEmpty()) {
                missingPid = current;
            }
            boolean first = missing.add(feature);
            if (first && missing.size() == 1) {
                LOG.info("the running jmux daemon (pid {}) predates {}; harness-manager falls back until it is "
                        + "replaced (once every jmux session has ended)", missingPid, feature);
            }
            return first;
        }
    }

    /** Forgets what was missing once the daemon that lacked it is gone: a different pid (or
     * none) answers the ping. A no-op — no round trip — while nothing is missing. */
    void revalidate() {
        long known;
        synchronized (this) {
            if (missing.isEmpty()) {
                return;
            }
            known = missingPid;
        }
        long current = ping();
        synchronized (this) {
            if (current < 0 || current != known) {
                if (!missing.isEmpty()) {
                    LOG.info("the jmux daemon changed (pid {} -> {}); trying every feature again", known,
                            current < 0 ? "none" : current);
                }
                missing.clear();
                missingPid = -1;
            }
        }
    }

    private long ping() {
        try {
            OptionalLong p = pid.get();
            return p.isPresent() ? p.getAsLong() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
