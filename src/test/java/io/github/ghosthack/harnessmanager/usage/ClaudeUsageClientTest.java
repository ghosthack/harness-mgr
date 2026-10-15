package io.github.ghosthack.harnessmanager.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClaudeUsageClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesSubscriptionWindowsAndExtraUsage() throws Exception {
        UsageSnapshot snapshot = ClaudeUsageClient.parse(MAPPER.readTree("""
                {
                  "five_hour": {
                    "utilization": 12.4,
                    "resets_at": "2026-08-22T23:10:00+00:00"
                  },
                  "seven_day": {
                    "utilization": 45,
                    "resets_at": "2026-08-29T05:00:00Z"
                  },
                  "seven_day_overage_included": {
                    "utilization": 63,
                    "resets_at": "2026-08-29T05:00:00Z"
                  },
                  "seven_day_opus": null,
                  "extra_usage": {
                    "is_enabled": true,
                    "monthly_limit": 10000,
                    "used_credits": 750,
                    "utilization": 7.5,
                    "decimal_places": 2,
                    "currency": "USD"
                  }
                }
                """));

        assertEquals("Claude Code subscription", snapshot.heading());
        assertEquals(4, snapshot.limits().size());
        assertEquals("Current session (5 hours)", snapshot.limits().getFirst().label());
        assertEquals(12, snapshot.limits().getFirst().usedPercent());
        assertEquals(Instant.parse("2026-08-22T23:10:00Z"),
                snapshot.limits().getFirst().resetsAt().orElseThrow());
        assertEquals("Current week (Fable)", snapshot.limits().get(2).label());
        assertEquals(63, snapshot.limits().get(2).usedPercent());
        assertEquals("Monthly credits", snapshot.limits().get(3).label());
        assertEquals(8, snapshot.limits().get(3).usedPercent());
        assertEquals("$7.50 of $100.00", snapshot.limits().get(3).detail().orElseThrow());
        assertEquals(new UsageSnapshot.Statistic("Extra usage", "Enabled"),
                snapshot.statistics().getFirst());
        assertTrue(snapshot.note().orElseThrow().contains("vendor-internal"));
    }

    @Test
    void toleratesAnEmptyUsageResponse() throws Exception {
        UsageSnapshot snapshot = ClaudeUsageClient.parse(MAPPER.readTree("{}"));

        assertTrue(snapshot.limits().isEmpty());
        assertTrue(snapshot.statistics().isEmpty());
        assertTrue(snapshot.note().isPresent());
    }

    @Test
    void parsesFableFromNewerModelScopedUsageShape() throws Exception {
        UsageSnapshot snapshot = ClaudeUsageClient.parse(MAPPER.readTree("""
                {
                  "five_hour": {"utilization": 10},
                  "seven_day": {"utilization": 20},
                  "model_scoped": [{
                    "display_name": "Fable",
                    "utilization": 0.38,
                    "resets_at": "2026-08-29T05:00:00Z"
                  }]
                }
                """));

        UsageSnapshot.Limit fable = snapshot.limits().get(2);
        assertEquals("Current week (Fable)", fable.label());
        assertEquals(38, fable.usedPercent());
        assertEquals(Instant.parse("2026-08-29T05:00:00Z"), fable.resetsAt().orElseThrow());
    }

    @Test
    void parsesFableFromWeeklyScopedLimitsFallback() throws Exception {
        UsageSnapshot snapshot = ClaudeUsageClient.parse(MAPPER.readTree("""
                {
                  "limits": [{
                    "kind": "weekly_scoped",
                    "percent": 44,
                    "resets_at": "2026-08-29T05:00:00Z",
                    "scope": {"model": {"id": null, "display_name": "Fable"}}
                  }]
                }
                """));

        assertEquals("Current week (Fable)", snapshot.limits().getFirst().label());
        assertEquals(44, snapshot.limits().getFirst().usedPercent());
    }
}
