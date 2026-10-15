package io.github.ghosthack.harnessmanager.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where Claude Code keeps its conversations: one JSONL per session id in a per-project
 * directory, {@code <root>/<project path with every non-alphanumeric character replaced
 * by '-'>} (older builds replaced only {@code /} and {@code .}, so both are tried). The
 * root is {@code $CLAUDE_CONFIG_DIR/projects}, else {@code ~/.claude/projects}; the
 * {@value #OVERRIDE_PROPERTY} system property replaces it (tests).
 */
public final class ClaudeProjects {

    public static final String OVERRIDE_PROPERTY = "harnessmanager.claudeProjects";

    private static final String SESSION_ID = "[A-Za-z0-9-]+";

    private ClaudeProjects() {
    }

    /** {@value #OVERRIDE_PROPERTY} when set, else {@code $CLAUDE_CONFIG_DIR/projects},
     * else {@code ~/.claude/projects}. */
    public static Path root() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String configDir = System.getenv("CLAUDE_CONFIG_DIR");
        Path claude = configDir != null && !configDir.isBlank()
                ? Path.of(configDir)
                : Path.of(System.getProperty("user.home"), ".claude");
        return claude.resolve("projects");
    }

    /** The project's existing transcript directory under {@code root}, if any. */
    public static Optional<Path> directory(Path root, Path project) {
        String absolute = project.toAbsolutePath().normalize().toString();
        for (String dirName : List.of(absolute.replaceAll("[^A-Za-z0-9]", "-"),
                absolute.replaceAll("[/.]", "-"))) {
            Path dir = root.resolve(dirName);
            if (Files.isDirectory(dir)) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    /** The transcript of session {@code id} in {@code project}, if Claude has written it. */
    public static Optional<Path> transcript(Path root, Path project, String id) {
        if (!id.matches(SESSION_ID)) {
            return Optional.empty();
        }
        return directory(root, project)
                .map(dir -> dir.resolve(id + ".jsonl"))
                .filter(Files::isRegularFile);
    }

    /** The project's most recently written transcript — any session's. */
    public static Optional<Path> newest(Path root, Path project) {
        Optional<Path> dir = directory(root, project);
        if (dir.isEmpty()) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir.get())) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p))
                    .max(Comparator.comparing(ClaudeProjects::modified));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}
