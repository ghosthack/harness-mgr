package io.github.ghosthack.harnessmanager.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock advanced explicitly by the test. Copied from ../harness-stimulator. */
public final class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-08-17T00:00:00Z");

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
