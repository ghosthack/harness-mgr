package io.github.ghosthack.harnessmanager.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Fetches account usage through Codex's local JSON-RPC app server. */
final class CodexUsageClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);

    UsageSnapshot load() throws IOException, InterruptedException {
        String codex = CliLocator.find("codex");
        Process process = new ProcessBuilder(codex, "app-server", "--listen", "stdio://")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return load(process);
    }

    /** Package-private process seam keeps the protocol and shutdown lifecycle testable. */
    UsageSnapshot load(Process process) throws IOException, InterruptedException {
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        try {
            readerExecutor.execute(() -> readMessages(reader, messages));

            send(writer, request(1, "initialize", MAPPER.createObjectNode()
                    .set("clientInfo", MAPPER.createObjectNode()
                            .put("name", "harness-manager")
                            .put("version", "0.1.0"))));
            JsonNode initialized = await(messages, 1);
            ensureSuccess(initialized, "initialize");

            send(writer, MAPPER.createObjectNode().put("method", "initialized"));
            send(writer, request(2, "account/rateLimits/read", null));
            send(writer, request(3, "account/usage/read", null));

            JsonNode rates = null;
            JsonNode usage = null;
            long deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos();
            while ((rates == null || usage == null) && System.nanoTime() < deadline) {
                long remaining = Math.max(1, deadline - System.nanoTime());
                JsonNode message = messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (message == null) {
                    break;
                }
                if (message.path("id").asInt(-1) == 2) {
                    rates = message;
                } else if (message.path("id").asInt(-1) == 3) {
                    usage = message;
                }
            }
            if (rates == null) {
                throw new IOException("Timed out waiting for Codex rate limits");
            }
            ensureSuccess(rates, "rate limits");
            JsonNode usageResult = usage != null && usage.has("result") ? usage.path("result") : null;
            return parse(rates.path("result"), usageResult);
        } finally {
            // The app server intentionally stays alive waiting for more requests. Stop it
            // before waiting on the stdout reader; closing the reader executor first can
            // wait forever on BufferedReader.readLine().
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            process.destroy();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            try {
                reader.close();
            } catch (IOException ignored) {
            }
            readerExecutor.shutdownNow();
        }
    }

    static UsageSnapshot parse(JsonNode rateResult, JsonNode usageResult) {
        List<UsageSnapshot.Limit> limits = new ArrayList<>();
        JsonNode buckets = rateResult.path("rateLimitsByLimitId");
        if (buckets.isObject() && !buckets.isEmpty()) {
            buckets.fields().forEachRemaining(entry -> addSnapshot(limits, entry.getValue()));
        } else {
            addSnapshot(limits, rateResult.path("rateLimits"));
        }

        List<UsageSnapshot.Statistic> statistics = new ArrayList<>();
        JsonNode primary = rateResult.path("rateLimits");
        if (primary.hasNonNull("planType")) {
            statistics.add(new UsageSnapshot.Statistic("Plan", friendly(primary.path("planType").asText())));
        }
        JsonNode credits = primary.path("credits");
        if (credits.isObject()) {
            String value = credits.path("unlimited").asBoolean(false) ? "Unlimited"
                    : credits.path("balance").asText(credits.path("hasCredits").asBoolean() ? "Available" : "None");
            statistics.add(new UsageSnapshot.Statistic("Credits", value));
        }
        JsonNode resetCredits = rateResult.path("rateLimitResetCredits");
        if (resetCredits.hasNonNull("availableCount")) {
            statistics.add(new UsageSnapshot.Statistic("Limit resets",
                    Integer.toString(resetCredits.path("availableCount").asInt())));
        }
        if (usageResult != null && usageResult.isObject()) {
            JsonNode summary = usageResult.path("summary");
            addNumber(statistics, summary, "lifetimeTokens", "Lifetime tokens");
            addNumber(statistics, summary, "peakDailyTokens", "Peak daily tokens");
            addDays(statistics, summary, "currentStreakDays", "Current streak");
            addDays(statistics, summary, "longestStreakDays", "Longest streak");
            if (summary.hasNonNull("longestRunningTurnSec")) {
                statistics.add(new UsageSnapshot.Statistic("Longest turn",
                        formatDuration(summary.path("longestRunningTurnSec").asLong())));
            }
        }

        Optional<String> note = usageResult == null
                ? Optional.of("This Codex version returned limits but not aggregate token usage.")
                : Optional.empty();
        return new UsageSnapshot("Codex account", "Codex app server",
                limits, statistics, note);
    }

    private static void addSnapshot(List<UsageSnapshot.Limit> limits, JsonNode snapshot) {
        if (!snapshot.isObject()) {
            return;
        }
        String name = snapshot.path("limitName").asText("");
        if (name.isBlank()) {
            name = "Codex";
        }
        addWindow(limits, name, snapshot.path("primary"));
        addWindow(limits, name, snapshot.path("secondary"));
        JsonNode individual = snapshot.path("individualLimit");
        if (individual.isObject() && individual.hasNonNull("remainingPercent")) {
            int used = 100 - individual.path("remainingPercent").asInt();
            Optional<Instant> reset = epoch(individual.path("resetsAt"));
            String detail = individual.path("used").asText() + " of " + individual.path("limit").asText();
            limits.add(new UsageSnapshot.Limit(name + " spend control", used, reset, Optional.of(detail)));
        }
    }

    private static void addWindow(List<UsageSnapshot.Limit> limits, String name, JsonNode window) {
        if (!window.isObject() || !window.hasNonNull("usedPercent")) {
            return;
        }
        long minutes = window.path("windowDurationMins").asLong(0);
        String label = minutes > 0 ? name + " · " + windowLabel(minutes) : name;
        limits.add(new UsageSnapshot.Limit(label, window.path("usedPercent").asInt(),
                epoch(window.path("resetsAt")), Optional.empty()));
    }

    private static String windowLabel(long minutes) {
        if (minutes % (7 * 24 * 60) == 0) {
            return (minutes / (7 * 24 * 60)) + " week" + (minutes == 7 * 24 * 60 ? "" : "s");
        }
        if (minutes % (24 * 60) == 0) {
            return (minutes / (24 * 60)) + " day" + (minutes == 24 * 60 ? "" : "s");
        }
        if (minutes % 60 == 0) {
            return (minutes / 60) + " hours";
        }
        return minutes + " minutes";
    }

    private static Optional<Instant> epoch(JsonNode value) {
        return value.isIntegralNumber() ? Optional.of(Instant.ofEpochSecond(value.asLong())) : Optional.empty();
    }

    private static void addNumber(List<UsageSnapshot.Statistic> statistics, JsonNode summary,
                                  String field, String label) {
        if (summary.hasNonNull(field)) {
            statistics.add(new UsageSnapshot.Statistic(label,
                    String.format(Locale.ROOT, "%,d", summary.path(field).asLong())));
        }
    }

    private static void addDays(List<UsageSnapshot.Statistic> statistics, JsonNode summary,
                                String field, String label) {
        if (summary.hasNonNull(field)) {
            long value = summary.path(field).asLong();
            statistics.add(new UsageSnapshot.Statistic(label, value + (value == 1 ? " day" : " days")));
        }
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private static String friendly(String value) {
        String[] words = value.replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return result.toString();
    }

    private static ObjectNode request(int id, String method, JsonNode params) {
        ObjectNode request = MAPPER.createObjectNode().put("id", id).put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        return request;
    }

    private static void send(BufferedWriter writer, JsonNode message) throws IOException {
        writer.write(MAPPER.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }

    private static JsonNode await(BlockingQueue<JsonNode> messages, int id) throws InterruptedException, IOException {
        long deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode message = messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (message == null) break;
            if (message.path("id").asInt(-1) == id) return message;
        }
        throw new IOException("Timed out initializing the Codex app server");
    }

    private static void ensureSuccess(JsonNode response, String action) throws IOException {
        if (!response.has("result")) {
            String message = response.path("error").path("message").asText("unknown error");
            throw new IOException("Codex " + action + " failed: " + message);
        }
    }

    private static void readMessages(BufferedReader reader, BlockingQueue<JsonNode> messages) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    messages.offer(MAPPER.readTree(line));
                }
            }
        } catch (IOException ignored) {
            // Process shutdown closes the pipe; the polling thread owns timeout/error reporting.
        }
    }
}
