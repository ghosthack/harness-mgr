package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.status.CodexSessionNameResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Transcript lookup over a temporary Claude projects directory and a fake Codex locator. */
final class HarnessTranscriptsTest {

    @TempDir
    Path dir;

    @Test
    void claudeUsesTheSessionsOwnFileElseTheNewestInTheProjectDirectory() throws Exception {
        Path projects = dir.resolve("projects");
        Path project = Path.of("/Users/me/src/my_app.v2");
        Path claudeDir = Files.createDirectories(projects.resolve("-Users-me-src-my-app-v2"));
        Path older = Files.writeString(claudeDir.resolve("aaaa-1.jsonl"), "{}");
        Path newer = Files.writeString(claudeDir.resolve("bbbb-2.jsonl"), "{}");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000));
        HarnessTranscripts transcripts = new HarnessTranscripts(projects,
                new CodexSessionNameResolver(dir, pid -> Optional.empty()));

        assertEquals(Optional.of(newer), transcripts.transcript(HarnessType.CLAUDE_CODE, project, Optional.empty(), -1));
        assertEquals(Optional.of(older),
                transcripts.transcript(HarnessType.CLAUDE_CODE, project, Optional.of("aaaa-1"), -1));
        assertEquals(Optional.empty(),
                transcripts.transcript(HarnessType.CLAUDE_CODE, project, Optional.of("cccc-3"), -1),
                "a known id whose conversation has no message yet is not given another session's file");
        assertEquals(Optional.empty(),
                transcripts.transcript(HarnessType.CLAUDE_CODE, Path.of("/elsewhere"), Optional.empty(), -1));
        assertEquals(Optional.empty(), transcripts.transcript(HarnessType.GROK, project, Optional.empty(), 7));
    }

    @Test
    void codexAsksTheResolverForThePanesOpenRollout() {
        Path rollout = dir.resolve("sessions/rollout-2026-01-01T00-00-00-0199a0b1-0000-7000-8000-000000000001.jsonl");
        HarnessTranscripts transcripts = new HarnessTranscripts(dir.resolve("projects"),
                new CodexSessionNameResolver(dir, pid -> pid == 42 ? Optional.of(rollout) : Optional.empty()));
        assertEquals(Optional.of(rollout), transcripts.transcript(HarnessType.CODEX, Path.of("/p"), Optional.empty(), 42));
        assertEquals(Optional.empty(), transcripts.transcript(HarnessType.CODEX, Path.of("/p"), Optional.empty(), 43));
    }
}
