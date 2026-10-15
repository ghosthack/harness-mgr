package io.github.ghosthack.harnessmanager.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CodexSessionNameResolverTest {

    @Test
    void resolvesLatestSavedNameForActiveRollout(@TempDir Path codexHome) throws Exception {
        String id = "01a0299e-d978-7651-871e-e746fedd1186";
        Path rollout = codexHome.resolve("sessions/2026/08/22/rollout-2026-08-22T00-00-00-" + id + ".jsonl");
        Files.createDirectories(rollout.getParent());
        Files.writeString(rollout, "");
        Files.writeString(codexHome.resolve("session_index.jsonl"), """
                {"id":"%s","thread_name":"old name","updated_at":"2026-08-22T00:00:00Z"}
                {not-yet-complete
                {"id":"%s","thread_name":"new name","updated_at":"2026-08-22T00:01:00Z"}
                """.formatted(id, id));

        CodexSessionNameResolver resolver = new CodexSessionNameResolver(
                codexHome, ignored -> Optional.of(rollout));

        assertEquals("new name", resolver.resolve(42).orElseThrow());
    }

    @Test
    void missingRenameFallsBackCleanly(@TempDir Path codexHome) throws Exception {
        Path rollout = codexHome.resolve("rollout-01a0299e-d978-7651-871e-e746fedd1186.jsonl");
        Files.writeString(rollout, "");
        Files.writeString(codexHome.resolve("session_index.jsonl"), """
                {"id":"someone-else","thread_name":"not this chat"}
                """);

        CodexSessionNameResolver resolver = new CodexSessionNameResolver(
                codexHome, ignored -> Optional.of(rollout));

        assertTrue(resolver.resolve(42).isEmpty());
        assertTrue(resolver.resolve(-1).isEmpty());
    }

    @Test
    void unnamedExistingChatUsesFirstRealUserPromptAsPreview(@TempDir Path codexHome) throws Exception {
        String id = "01a0299e-d978-7651-871e-e746fedd1186";
        Path rollout = codexHome.resolve("rollout-" + id + ".jsonl");
        Files.writeString(rollout,
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\","
                        + "\"content\":[{\"type\":\"input_text\",\"text\":"
                        + "\"<environment_context>generated metadata</environment_context>\"}]}}\n"
                        + "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\","
                        + "\"content\":[{\"type\":\"input_text\",\"text\":"
                        + "\"Fix the sidebar\\nname inheritance\"}]}}\n");
        Files.writeString(codexHome.resolve("session_index.jsonl"), "");
        CodexSessionNameResolver resolver = new CodexSessionNameResolver(
                codexHome, ignored -> Optional.of(rollout));

        assertEquals("Fix the sidebar name inheritance", resolver.resolve(42).orElseThrow());
    }

    @Test
    void transcriptIsTheOpenRolloutAndEmptyWithoutAPane(@TempDir Path codexHome) {
        Path rollout = codexHome.resolve("sessions/../rollout-01a0299e-d978-7651-871e-e746fedd1186.jsonl");
        CodexSessionNameResolver resolver = new CodexSessionNameResolver(
                codexHome, pid -> pid == 42 ? Optional.of(rollout) : Optional.empty());

        assertEquals(rollout.toAbsolutePath().normalize(), resolver.transcript(42).orElseThrow());
        assertTrue(resolver.transcript(7).isEmpty());
        assertTrue(resolver.transcript(0).isEmpty());
    }
}
