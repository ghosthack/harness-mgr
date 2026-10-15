package io.github.ghosthack.harnessmanager.session;

import java.util.Map;

/**
 * Sets daemon-side labels on a jmux session ({@code harness}, {@code project},
 * {@code display}), so other jmux clients — and the session controller after a restart —
 * can tell what runs in it without the registry. Best effort by contract: callers
 * swallow failures, since a label is never worth failing a launch over.
 */
@FunctionalInterface
public interface SessionLabeller {

    /** Labels nothing — for hosts without labels, and tests. */
    SessionLabeller NONE = (session, labels) -> {
    };

    /** Merges {@code labels} into {@code session}'s labels; a null value removes a key. */
    void label(String session, Map<String, String> labels);
}
