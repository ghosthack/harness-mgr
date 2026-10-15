package io.github.ghosthack.harnessmanager.state;

/**
 * Result of one detector pass over a captured screen. {@code origin} records whether a
 * harness plugin's textual pattern recognized the screen or the generic behavioral
 * heuristic (screen quiescence) produced the state. Adapted from
 * ../harness-stimulator's {@code Classification}, dropping {@code textAnswerable} —
 * that field only matters to code that types an answer back in, which this app never
 * does.
 */
public record Classification(SessionState state, Confidence confidence, String reason, Origin origin) {

    public enum Origin { BEHAVIORAL, PLUGIN }
}
