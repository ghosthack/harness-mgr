package io.github.ghosthack.harnessmanager.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Reads subscription limits through the same OAuth usage endpoint as Claude Code. */
final class ClaudeUsageClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI USAGE_URI = URI.create("https://api.anthropic.com/api/oauth/usage");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient httpClient;

    ClaudeUsageClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }

    ClaudeUsageClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    UsageSnapshot load() throws IOException, InterruptedException {
        String accessToken = readAccessToken();
        HttpRequest request = HttpRequest.newBuilder(USAGE_URI)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("anthropic-beta", "oauth-2025-04-20")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("Claude login expired; run 'claude auth login' and retry");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Claude usage API returned HTTP " + response.statusCode());
        }
        return parse(MAPPER.readTree(response.body()));
    }

    static UsageSnapshot parse(JsonNode root) {
        List<UsageSnapshot.Limit> limits = new ArrayList<>();
        addWindow(limits, root, "five_hour", "Current session (5 hours)");
        addWindow(limits, root, "seven_day", "Current week (all models)");
        addWindow(limits, root, "seven_day_opus", "Current week (Opus)");
        addWindow(limits, root, "seven_day_sonnet", "Current week (Sonnet)");
        addWindow(limits, root, "seven_day_overage_included", "Current week (Fable)");
        addModelScopedWindows(limits, root);

        List<UsageSnapshot.Statistic> statistics = new ArrayList<>();
        addExtraUsageCredits(limits, statistics, root);

        Optional<String> note = limits.isEmpty()
                ? Optional.of("Claude returned no active subscription limit windows.")
                : Optional.of("Claude Code uses a vendor-internal OAuth endpoint; its schema may change.");
        return new UsageSnapshot("Claude Code subscription", "Claude Code OAuth usage",
                limits, statistics, note);
    }

    private static void addWindow(List<UsageSnapshot.Limit> limits, JsonNode root,
                                  String field, String label) {
        JsonNode window = root.path(field);
        if (!window.isObject() || !window.hasNonNull("utilization")) {
            return;
        }
        int percent = (int) Math.round(window.path("utilization").asDouble());
        Optional<Instant> reset = parseInstant(window.path("resets_at").asText(null));
        Optional<String> detail = Optional.empty();
        if (window.hasNonNull("used_dollars") && window.hasNonNull("limit_dollars")) {
            detail = Optional.of(String.format(Locale.ROOT, "$%.2f of $%.2f",
                    window.path("used_dollars").asDouble(), window.path("limit_dollars").asDouble()));
        }
        limits.add(new UsageSnapshot.Limit(label, percent, reset, detail));
    }

    /** Newer Claude responses can carry named weekly model buckets instead of (or in
     * addition to) the legacy seven_day_* fields. */
    private static void addModelScopedWindows(List<UsageSnapshot.Limit> limits, JsonNode root) {
        JsonNode scoped = root.path("model_scoped");
        if (!scoped.isArray()) {
            scoped = root.path("rate_limits").path("model_scoped");
        }
        if (scoped.isArray()) {
            for (JsonNode window : scoped) {
                String model = window.path("display_name").asText("");
                JsonNode utilization = window.path("utilization");
                if (!model.isBlank() && utilization.isNumber() && !hasModelWindow(limits, model)) {
                    double fraction = utilization.asDouble();
                    int usedPercent = (int) Math.round(fraction <= 1 ? fraction * 100 : fraction);
                    addModelWindow(limits, model, usedPercent,
                            parseInstant(window.path("resets_at").asText(null)));
                }
            }
        }

        JsonNode rawLimits = root.path("limits");
        if (rawLimits.isArray()) {
            for (JsonNode window : rawLimits) {
                if (!"weekly_scoped".equals(window.path("kind").asText())) continue;
                String model = window.path("scope").path("model").path("display_name").asText("");
                if (!model.isBlank() && window.path("percent").isNumber()
                        && !hasModelWindow(limits, model)) {
                    addModelWindow(limits, model, window.path("percent").asInt(),
                            parseInstant(window.path("resets_at").asText(null)));
                }
            }
        }
    }

    /** Credit-metered (e.g. Enterprise) accounts report spend against a monthly dollar
     * allotment instead of the five_hour/seven_day rate-limit windows above, so surface
     * it as a limit too — otherwise it silently disappears whenever no rate-limit window
     * is present. */
    private static void addExtraUsageCredits(List<UsageSnapshot.Limit> limits,
                                             List<UsageSnapshot.Statistic> statistics, JsonNode root) {
        JsonNode extra = root.path("extra_usage");
        if (!extra.isObject()) {
            return;
        }
        boolean enabled = extra.path("is_enabled").asBoolean(false);
        statistics.add(new UsageSnapshot.Statistic("Extra usage", enabled ? "Enabled" : "Disabled"));
        if (!extra.hasNonNull("monthly_limit") || !extra.hasNonNull("used_credits")
                || !extra.hasNonNull("utilization")) {
            return;
        }
        // used_credits/monthly_limit are minor units (e.g. cents), scaled by decimal_places;
        // the API has no reset-date field for this window.
        double scale = Math.pow(10, extra.path("decimal_places").asInt(2));
        double used = extra.path("used_credits").asDouble() / scale;
        double limit = extra.path("monthly_limit").asDouble() / scale;
        String currency = extra.path("currency").asText("USD");
        int percent = (int) Math.round(extra.path("utilization").asDouble());
        limits.add(new UsageSnapshot.Limit("Monthly credits", percent, Optional.empty(),
                Optional.of(money(used, currency) + " of " + money(limit, currency))));
    }

    private static void addModelWindow(List<UsageSnapshot.Limit> limits, String model,
                                       int usedPercent, Optional<Instant> reset) {
        limits.add(new UsageSnapshot.Limit("Current week (" + model + ")",
                usedPercent, reset, Optional.empty()));
    }

    private static boolean hasModelWindow(List<UsageSnapshot.Limit> limits, String model) {
        String marker = "(" + model + ")";
        return limits.stream().anyMatch(limit -> limit.label().equalsIgnoreCase("Current week " + marker));
    }

    private static Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String money(double value, String currency) {
        String symbol = "USD".equalsIgnoreCase(currency) ? "$" : currency + " ";
        return symbol + String.format(Locale.ROOT, "%.2f", value);
    }

    private static String readAccessToken() throws IOException, InterruptedException {
        Optional<String> keychain = readMacKeychain();
        if (keychain.isPresent()) {
            return tokenFromCredentials(keychain.get());
        }
        Path credentials = Path.of(System.getProperty("user.home"), ".claude", ".credentials.json");
        if (Files.isRegularFile(credentials)) {
            return tokenFromCredentials(Files.readString(credentials));
        }
        throw new IOException("Claude Code OAuth credentials were not found; run 'claude auth login'");
    }

    private static Optional<String> readMacKeychain() throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return Optional.empty();
        }
        Process process = new ProcessBuilder("/usr/bin/security", "find-generic-password",
                "-s", "Claude Code-credentials", "-w")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out reading Claude Code credentials from Keychain");
        }
        if (process.exitValue() != 0) {
            return Optional.empty();
        }
        return Optional.of(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String tokenFromCredentials(String json) throws IOException {
        JsonNode credentials = MAPPER.readTree(json);
        String token = credentials.path("claudeAiOauth").path("accessToken").asText("");
        if (token.isBlank()) {
            throw new IOException("Claude Code credentials do not contain an OAuth access token");
        }
        return token;
    }
}
