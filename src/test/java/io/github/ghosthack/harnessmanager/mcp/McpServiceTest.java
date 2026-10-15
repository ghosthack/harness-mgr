package io.github.ghosthack.harnessmanager.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The service's lifecycle over real loopback sockets — always on free ports, never the
 * configured default, so a running app on this machine is never disturbed. */
final class McpServiceTest {

    private final McpService service = new McpService(McpService.NO_TOOLS, "test");

    @AfterEach
    void stop() {
        service.stop();
    }

    @Test
    void stoppedServiceHasNoUrl() {
        assertFalse(service.isRunning());
        assertEquals(-1, service.port());
        assertNull(service.urlFor("hm-x"));
        assertNull(service.linkFor("hm-x"));
        service.stop(); // idempotent
    }

    @Test
    void startServesAndUrlCarriesCallerAndToken() throws Exception {
        service.start(0, "tok");
        assertTrue(service.isRunning());
        int port = service.port();
        assertTrue(port > 0);
        assertEquals("http://127.0.0.1:" + port + "/mcp/hm-a%20b?token=tok", service.urlFor("hm-a b"));
        assertEquals(200, ping(service.urlFor("hm-x")));

        service.stop();
        assertNull(service.linkFor("hm-x"));
        assertFalse(service.isRunning());
        assertNull(service.urlFor("hm-x"));
        assertThrows(IOException.class, () -> ping("http://127.0.0.1:" + port + "/mcp?token=tok"));
    }

    @Test
    void linkKeepsTheTokenOutOfTheUrlAndTheBearerHeaderIsAccepted() throws Exception {
        service.start(0, "tok");
        McpService.McpLink link = service.linkFor("hm-a b");
        assertEquals("http://127.0.0.1:" + service.port() + "/mcp/hm-a%20b", link.url());
        assertEquals("tok", link.token());
        assertFalse(link.toString().contains("tok"), link.toString());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(link.url()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals(401, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(request.header("Authorization", "Bearer " + link.token()).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void toolsetIsToldWhenTheServerStartsAndStops() throws Exception {
        java.util.List<String> lifecycle = new java.util.concurrent.CopyOnWriteArrayList<>();
        McpService tracked = new McpService(new McpToolset() {
            @Override
            public java.util.List<ToolDef> tools() {
                return java.util.List.of();
            }

            @Override
            public ToolResult call(String caller, String tool, com.fasterxml.jackson.databind.JsonNode arguments) {
                return ToolResult.error("none");
            }

            @Override
            public void serverStarted() {
                lifecycle.add("started");
            }

            @Override
            public void serverStopped() {
                lifecycle.add("stopped");
            }
        }, "test");
        try {
            tracked.start(0, "tok");
            tracked.start(0, "other"); // a restart: stopped, then started again
            tracked.stop();
            tracked.stop(); // idempotent: no second notice
        } finally {
            tracked.stop();
        }
        assertEquals(java.util.List.of("started", "stopped", "started", "stopped"), lifecycle);
    }

    @Test
    void restartingOnANewPortOrTokenClosesTheOldSocket() throws Exception {
        int first = freePort();
        service.start(first, "one");
        service.start(first, "one"); // same port and token: no-op
        assertEquals(first, service.port());
        service.start(first, "two"); // new token: rebound
        assertEquals(401, ping("http://127.0.0.1:" + first + "/mcp?token=one"));
        assertEquals(200, ping(service.urlFor(null)));

        int second = freePort();
        service.start(second, "two");
        assertEquals(second, service.port());
        assertThrows(IOException.class, () -> ping("http://127.0.0.1:" + first + "/mcp?token=two"));
    }

    @Test
    void aTakenPortFailsCleanlyAndLeavesTheServiceStopped() throws Exception {
        try (ServerSocket squatter = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            service.start(0, "tok");
            int running = service.port();
            assertNotEquals(squatter.getLocalPort(), running);
            assertThrows(IOException.class, () -> service.start(squatter.getLocalPort(), "tok"));
            assertFalse(service.isRunning());
            assertNull(service.urlFor("hm-x"));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static int ping(String url) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }
}
