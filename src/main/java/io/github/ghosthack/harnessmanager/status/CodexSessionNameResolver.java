package io.github.ghosthack.harnessmanager.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves Codex's saved {@code /rename} name without reading conversation content.
 * Codex keeps the name in {@code session_index.jsonl}; the active rollout file held by
 * the pane's Codex process supplies the thread id used to select the matching row.
 * Public so the MCP session controller can also point agents at a live Codex session's
 * transcript ({@link #transcript}). Thread-safe; every call may block for a couple of
 * seconds ({@code lsof}), so never call it on the EDT. */
public final class CodexSessionNameResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern THREAD_ID = Pattern.compile(
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.jsonl$");

    private final Path codexHome;
    private final LongFunction<Optional<Path>> rolloutLocator;
    private final ConcurrentHashMap<Path, String> previewCache = new ConcurrentHashMap<>();

    /** Over {@code $CODEX_HOME}, else {@code ~/.codex}. */
    public CodexSessionNameResolver() {
        this(codexHome());
    }

    private CodexSessionNameResolver(Path codexHome) {
        this(codexHome, paneProcessId -> locateOpenRollout(paneProcessId,
                codexHome.toAbsolutePath().normalize().resolve("sessions")));
    }

    /** Over an explicit Codex home, finding a pane's open rollout file with
     * {@code rolloutLocator} — tests inject one instead of {@code lsof}. */
    public CodexSessionNameResolver(Path codexHome, LongFunction<Optional<Path>> rolloutLocator) {
        this.codexHome = codexHome.toAbsolutePath().normalize();
        this.rolloutLocator = rolloutLocator;
    }

    /**
     * The rollout ({@code rollout-…-<thread id>.jsonl}) the Codex process under the
     * pane's shell {@code paneProcessId} currently has open — the session's transcript —
     * or empty when no Codex runs there or it cannot be told.
     */
    public Optional<Path> transcript(long paneProcessId) {
        if (paneProcessId <= 0) {
            return Optional.empty();
        }
        return rolloutLocator.apply(paneProcessId).map(path -> path.toAbsolutePath().normalize());
    }

    /** The session's saved {@code /rename} name, else its first real prompt, else empty. */
    public Optional<String> resolve(long paneProcessId) {
        if (paneProcessId <= 0) {
            return Optional.empty();
        }
        Optional<Path> rollout = rolloutLocator.apply(paneProcessId);
        if (rollout.isEmpty()) {
            return Optional.empty();
        }
        Matcher id = THREAD_ID.matcher(rollout.get().getFileName().toString());
        if (!id.find()) {
            return Optional.empty();
        }
        Optional<String> savedName = nameFromIndex(id.group(1));
        if (savedName.isPresent()) {
            return savedName;
        }
        // Codex's history UI uses the first real user prompt as the preview for an
        // unnamed thread. Mirror that instead of falling all the way back to the cwd.
        Path rolloutPath = rollout.get().toAbsolutePath().normalize();
        String preview = previewCache.computeIfAbsent(rolloutPath,
                path -> previewFromRollout(path).orElse(""));
        return preview.isEmpty() ? Optional.empty() : Optional.of(preview);
    }

    private Optional<String> nameFromIndex(String threadId) {
        Path index = codexHome.resolve("session_index.jsonl");
        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }
        String latest = null;
        try (BufferedReader lines = Files.newBufferedReader(index)) {
            for (String line; (line = lines.readLine()) != null; ) {
                try {
                    JsonNode row = MAPPER.readTree(line);
                    if (threadId.equals(row.path("id").asText())) {
                        String name = row.path("thread_name").asText().strip();
                        if (!name.isEmpty()) {
                            latest = name;
                        }
                    }
                } catch (IOException ignored) {
                    // One partial/corrupt append must not hide an earlier valid rename.
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.ofNullable(latest);
    }

    private static Optional<String> previewFromRollout(Path rollout) {
        if (!Files.isRegularFile(rollout)) {
            return Optional.empty();
        }
        try (BufferedReader lines = Files.newBufferedReader(rollout)) {
            for (String line; (line = lines.readLine()) != null; ) {
                JsonNode row;
                try {
                    row = MAPPER.readTree(line);
                } catch (IOException ignored) {
                    continue;
                }
                JsonNode payload = row.path("payload");
                if (!"response_item".equals(row.path("type").asText())
                        || !"message".equals(payload.path("type").asText())
                        || !"user".equals(payload.path("role").asText())) {
                    continue;
                }
                for (JsonNode content : payload.path("content")) {
                    if (!"input_text".equals(content.path("type").asText())) {
                        continue;
                    }
                    String text = content.path("text").asText().strip();
                    // The CLI records launch context as a synthetic user item before
                    // the user's actual prompt. It is metadata, never a chat preview.
                    if (text.isEmpty() || text.startsWith("<environment_context>")) {
                        continue;
                    }
                    String oneLine = text.replaceAll("[\\r\\n\\t]+", " ")
                            .replaceAll(" {2,}", " ").strip();
                    return Optional.of(oneLine);
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Finds the rollout file open by the Codex descendant of tmux's pane shell. */
    private static Optional<Path> locateOpenRollout(long paneProcessId, Path sessionsRoot) {
        Optional<ProcessHandle> pane = ProcessHandle.of(paneProcessId);
        if (pane.isEmpty()) {
            return Optional.empty();
        }
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(pane.get()), pane.get().descendants())
                .filter(CodexSessionNameResolver::isCodexProcess)
                .map(ProcessHandle::pid)
                .map(processId -> openRolloutForProcess(processId, sessionsRoot))
                .flatMap(Optional::stream)
                .max(Comparator.comparingLong(CodexSessionNameResolver::lastModified));
    }

    private static boolean isCodexProcess(ProcessHandle process) {
        return process.info().command()
                .map(command -> Path.of(command).getFileName().toString().equals("codex"))
                .orElse(false);
    }

    private static Optional<Path> openRolloutForProcess(long processId, Path sessionsRoot) {
        Process process = null;
        try {
            process = new ProcessBuilder("lsof", "-a", "-p", Long.toString(processId), "-Fn")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            Path newest = null;
            try (BufferedReader output = process.inputReader()) {
                for (String line; (line = output.readLine()) != null; ) {
                    if (line.startsWith("n") && line.contains("/rollout-")
                            && line.endsWith(".jsonl")) {
                        Path candidate = Path.of(line.substring(1)).toAbsolutePath().normalize();
                        if (!candidate.startsWith(sessionsRoot)) {
                            continue;
                        }
                        if (newest == null || lastModified(candidate) > lastModified(newest)) {
                            newest = candidate;
                        }
                    }
                }
            }
            return Optional.ofNullable(newest);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static Path codexHome() {
        String configured = System.getenv("CODEX_HOME");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".codex");
    }
}
