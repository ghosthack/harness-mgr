package io.github.ghosthack.harnessmanager.usage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Provider-neutral usage data rendered by the usage/limits dialog. */
public record UsageSnapshot(String heading,
                            String source,
                            List<Limit> limits,
                            List<Statistic> statistics,
                            Optional<String> note) {

    public UsageSnapshot {
        limits = List.copyOf(limits);
        statistics = List.copyOf(statistics);
        note = note == null ? Optional.empty() : note;
    }

    /** A percentage-based provider limit and its optional reset time. */
    public record Limit(String label, int usedPercent, Optional<Instant> resetsAt,
                        Optional<String> detail) {
        public Limit {
            usedPercent = Math.clamp(usedPercent, 0, 100);
            resetsAt = resetsAt == null ? Optional.empty() : resetsAt;
            detail = detail == null ? Optional.empty() : detail;
        }
    }

    /** A non-window aggregate such as lifetime tokens or an account credit balance. */
    public record Statistic(String label, String value) {
    }
}
