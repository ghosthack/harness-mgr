package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.harnessmanager.harness.HarnessPlugin;
import io.github.ghosthack.harnessmanager.harness.HarnessPlugins;
import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.harness.StateDetector;
import io.github.ghosthack.harnessmanager.state.Classification;
import io.github.ghosthack.harnessmanager.state.Screen;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The controller's own view of one session's state: a {@link StateDetector} for the
 * session's harness, fed by every capture the controller takes (reads, keys, list, the
 * monitor), independent of the sidebar poller's.
 *
 * <p>A fresh detector has no history, so its first look at a long-idle session would say
 * "screen output changing" (WORKING). Before that first look the tracker asks the daemon
 * how long the output has already been quiet (a non-blocking quiet probe) and backdates
 * the detector's baseline by that much, so an idle session reads as idle at once.
 */
final class SessionTracker {

    /** The quiet probe: has {@code session} produced no output for this long, right now? */
    @FunctionalInterface
    interface QuietProbe {
        boolean quietFor(Duration duration);
    }

    final HarnessType harness;
    final HarnessPlugin plugin;
    private final OffsetClock clock;
    private final StateDetector detector;
    private final Duration shortQuiescence;
    private final Duration longQuiescence;
    private boolean primed;

    SessionTracker(HarnessType harness, Clock base, Duration shortQuiescence, Duration longQuiescence) {
        this.harness = harness;
        this.plugin = HarnessPlugins.of(harness);
        this.clock = new OffsetClock(base);
        this.detector = new StateDetector(plugin, clock, shortQuiescence, longQuiescence);
        this.shortQuiescence = shortQuiescence;
        this.longQuiescence = longQuiescence;
    }

    /** Classifies {@code screen} (plain text), priming the detector on its first real look. */
    synchronized Classification classify(Screen screen, QuietProbe probe) {
        if (!primed && !screen.paneDead() && !atShell(screen.foregroundCommand())) {
            primed = true;
            Duration quiet = probe.quietFor(longQuiescence) ? longQuiescence
                    : probe.quietFor(shortQuiescence) ? shortQuiescence : Duration.ZERO;
            if (!quiet.isZero()) {
                clock.offset = quiet.negated();
                try {
                    detector.classify(screen); // sets the baseline, backdated
                } finally {
                    clock.offset = Duration.ZERO;
                }
            }
        }
        return detector.classify(screen);
    }

    /** The plain-text {@link Screen} for an annotated capture. */
    static Screen screen(String plainText, boolean alive, Instant at, String foreground, long pid, String title) {
        return new Screen(plainText, !alive, at, foreground == null ? "" : foreground, pid,
                title == null ? "" : title);
    }

    /** Whether the harness's process is running in the pane (a shell harness always is
     * once its shell is in the foreground). */
    boolean harnessRunning(String foreground) {
        return foreground != null && !foreground.isEmpty() && plugin.ownsProcess(foreground);
    }

    /** Whether a shell, not the harness, is in the foreground — the harness exited or
     * never started (never true for the shell harness, which owns its shell). */
    boolean atShell(String foreground) {
        return foreground != null && !foreground.isEmpty() && !plugin.ownsProcess(foreground)
                && StateDetector.isShell(foreground);
    }

    /** The controller's clock shifted by {@link #offset} — only ever non-zero while
     * {@link #classify} primes the detector, under the tracker's lock. */
    private static final class OffsetClock extends Clock {
        private final Clock base;
        volatile Duration offset = Duration.ZERO;

        OffsetClock(Clock base) {
            this.base = base;
        }

        @Override
        public Instant instant() {
            return base.instant().plus(offset);
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
