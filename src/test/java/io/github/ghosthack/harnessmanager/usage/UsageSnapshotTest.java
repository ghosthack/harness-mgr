package io.github.ghosthack.harnessmanager.usage;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class UsageSnapshotTest {

    @Test
    void limitPercentIsClampedForProgressBars() {
        assertEquals(0, new UsageSnapshot.Limit("low", -4, Optional.empty(), Optional.empty()).usedPercent());
        assertEquals(100, new UsageSnapshot.Limit("high", 104, Optional.empty(), Optional.empty()).usedPercent());
    }
}
