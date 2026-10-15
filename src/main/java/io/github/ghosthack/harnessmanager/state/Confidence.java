package io.github.ghosthack.harnessmanager.state;

/** Detector confidence. Copied from ../harness-stimulator; the manager doesn't gate any
 * action on it (no stimulation here), but it's useful in the tooltip and in tests. */
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
}
