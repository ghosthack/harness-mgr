package io.github.ghosthack.harnessmanager.ui;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Applies {@code mcp.server}/{@code mcp.port} to the MCP service off the EDT, one apply
 * at a time and in the order they were asked for, so a later apply always wins: two
 * quick Saves (on, then off) never leave the server running because the "on" bind
 * finished after the "off". Thread-safe.
 */
final class McpApplier {

    /** Starts the server on a port with a token; {@code McpService::start}. */
    @FunctionalInterface
    interface Starter {
        void start(int port, String token) throws IOException;
    }

    private final Starter starter;
    private final Runnable stopper;
    private final Supplier<String> currentToken;
    private final Supplier<String> regeneratedToken;
    private final BiConsumer<Integer, Exception> onFailure;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().daemon().name("hm-mcp-apply").unstarted(task));

    /**
     * @param currentToken     the saved token (may write settings on its first read)
     * @param regeneratedToken saves and returns a new token
     * @param onFailure        told the port and the error when a start fails (any thread)
     */
    McpApplier(Starter starter, Runnable stopper, Supplier<String> currentToken, Supplier<String> regeneratedToken,
               BiConsumer<Integer, Exception> onFailure) {
        this.starter = Objects.requireNonNull(starter, "starter");
        this.stopper = Objects.requireNonNull(stopper, "stopper");
        this.currentToken = Objects.requireNonNull(currentToken, "currentToken");
        this.regeneratedToken = Objects.requireNonNull(regeneratedToken, "regeneratedToken");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    /** Queues one apply: a regenerated token is saved first; then the server stops, or
     * (re)starts on {@code port} — a running server restarts with a new token. */
    void apply(boolean enabled, int port, boolean regenerateToken) {
        executor.execute(() -> {
            String token = regenerateToken ? regeneratedToken.get() : null;
            if (!enabled) {
                stopper.run();
                return;
            }
            try {
                starter.start(port, token != null ? token : currentToken.get());
            } catch (IOException | RuntimeException e) {
                onFailure.accept(port, e);
            }
        });
    }

    /** Drops queued applies and waits briefly for one in progress, so a stop that
     * follows is not undone by a late start. */
    void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
