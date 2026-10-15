package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings' MCP applies run one at a time, in order: the latest always wins. */
final class McpApplierTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch firstStartEntered = new CountDownLatch(1);
    private final CountDownLatch releaseFirstStart = new CountDownLatch(1);
    private final AtomicInteger starts = new AtomicInteger();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private McpApplier applier;

    @AfterEach
    void close() {
        releaseFirstStart.countDown();
        if (applier != null) {
            applier.close();
        }
    }

    private McpApplier applier(boolean failSecondStart) {
        return new McpApplier((port, token) -> {
            int n = starts.incrementAndGet();
            if (n == 1) {
                firstStartEntered.countDown();
                try {
                    releaseFirstStart.await(10, TimeUnit.SECONDS); // a slow bind
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failSecondStart && n == 2) {
                throw new IOException("Address already in use");
            }
            calls.add("start " + port + " " + token);
            running.set(true);
        }, () -> {
            calls.add("stop");
            running.set(false);
        }, () -> "tok", () -> "new-tok", (port, e) -> failures.add(port + ": " + e.getMessage()));
    }

    /** Queues a final stop and waits until it ran: everything queued before it is done. */
    private void drain() throws InterruptedException {
        applier.apply(false, 0, false);
        awaitCalls(() -> !calls.isEmpty() && "stop".equals(calls.getLast()));
    }

    private void awaitCalls(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "applies did not finish: " + calls);
            Thread.sleep(5);
        }
    }

    @Test
    void aStopAskedForDuringASlowStartStillWins() throws Exception {
        applier = applier(false);
        applier.apply(true, 7391, false);
        assertTrue(firstStartEntered.await(10, TimeUnit.SECONDS));
        applier.apply(false, 7391, false);
        releaseFirstStart.countDown();
        awaitCalls(() -> calls.size() >= 2);
        assertEquals(List.of("start 7391 tok", "stop"), calls);
        assertFalse(running.get());
    }

    @Test
    void aFailedStartIsReportedAndARegeneratedTokenIsUsed() throws Exception {
        applier = applier(true);
        releaseFirstStart.countDown();
        applier.apply(true, 7391, false);
        applier.apply(true, 7392, false);
        applier.apply(true, 7393, true);
        drain();
        assertEquals(List.of("start 7391 tok", "start 7393 new-tok", "stop"), calls);
        assertEquals(List.of("7392: Address already in use"), failures);
    }

    @Test
    void closeDropsQueuedApplies() throws Exception {
        applier = applier(false);
        applier.apply(true, 7391, false);
        assertTrue(firstStartEntered.await(10, TimeUnit.SECONDS));
        applier.apply(true, 7392, false);
        applier.close(); // interrupts the slow bind in progress; the queued one never runs
        Thread.sleep(50);
        assertFalse(calls.contains("start 7392 tok"), calls.toString());
    }
}
