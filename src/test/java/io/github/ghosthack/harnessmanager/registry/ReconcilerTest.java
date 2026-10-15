package io.github.ghosthack.harnessmanager.registry;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReconcilerTest {

    private static SessionEntry entry(String tmuxSession) {
        return new SessionEntry(tmuxSession, "/Users/x/Projects/foo", HarnessType.CLAUDE_CODE,
                null, Instant.EPOCH, "0.0");
    }

    @Test
    void managedAndLiveIsReportedAsLiveManaged() {
        SessionEntry alive = entry("hm-foo");
        ReconciliationResult result = Reconciler.reconcile(List.of(alive), List.of("hm-foo"));

        assertEquals(List.of(alive), result.liveManaged());
        assertTrue(result.deadRegistered().isEmpty());
        assertTrue(result.unmanagedLive().isEmpty());
    }

    @Test
    void registeredButNotLiveIsDeadRegistered() {
        SessionEntry dead = entry("hm-gone");
        ReconciliationResult result = Reconciler.reconcile(List.of(dead), List.of());

        assertTrue(result.liveManaged().isEmpty());
        assertEquals(List.of(dead), result.deadRegistered());
        assertTrue(result.unmanagedLive().isEmpty());
    }

    @Test
    void liveWithNoRegistryEntryIsUnmanaged() {
        ReconciliationResult result = Reconciler.reconcile(List.of(), List.of("hm-adopt-me"));

        assertTrue(result.liveManaged().isEmpty());
        assertTrue(result.deadRegistered().isEmpty());
        assertEquals(List.of("hm-adopt-me"), result.unmanagedLive());
    }

    @Test
    void nonHmSessionsAreIgnoredEntirely() {
        ReconciliationResult result = Reconciler.reconcile(List.of(), List.of("my-shell", "work"));

        assertTrue(result.liveManaged().isEmpty());
        assertTrue(result.deadRegistered().isEmpty());
        assertTrue(result.unmanagedLive().isEmpty());
    }

    @Test
    void mixedCaseAcrossAllThreeBuckets() {
        SessionEntry alive = entry("hm-alive");
        SessionEntry dead = entry("hm-dead");
        ReconciliationResult result = Reconciler.reconcile(
                List.of(alive, dead),
                List.of("hm-alive", "hm-unmanaged", "unrelated"));

        assertEquals(List.of(alive), result.liveManaged());
        assertEquals(List.of(dead), result.deadRegistered());
        assertEquals(List.of("hm-unmanaged"), result.unmanagedLive());
    }
}
