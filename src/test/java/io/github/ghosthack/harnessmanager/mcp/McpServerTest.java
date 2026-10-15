package io.github.ghosthack.harnessmanager.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {

    private static final String TOKEN = "s3cret-token_ABC";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records every call; "echo" answers with caller and arguments, "boom" throws, "fail" returns an error. */
    private static final class FakeToolset implements McpToolset {
        final List<String> callers = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<ToolDef> tools() {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("msg").put("type", "string");
            ObjectNode empty = JsonNodeFactory.instance.objectNode().put("type", "object");
            return List.of(
                    new ToolDef("echo", "Echoes msg and the caller.", schema),
                    new ToolDef("boom", "Throws.", empty),
                    new ToolDef("fail", "Returns an error result.", empty));
        }

        @Override
        public ToolResult call(String caller, String tool, JsonNode arguments) {
            callers.add(String.valueOf(caller));
            return switch (tool) {
                case "echo" -> ToolResult.ok(caller + ":" + arguments.path("msg").asText(""));
                case "boom" -> throw new IllegalStateException("kaboom");
                case "fail" -> ToolResult.error("nope");
                default -> throw new AssertionError("server must not forward unknown tools: " + tool);
            };
        }
    }

    private final FakeToolset tools = new FakeToolset();
    private final HttpClient http = HttpClient.newHttpClient();
    private McpServer server;

    @BeforeEach
    void start() throws IOException {
        server = new McpServer(0, TOKEN, tools, "9.9.9-test");
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> post(String url, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(String url, String body) throws Exception {
        HttpResponse<String> r = post(url, body);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("application/json", r.headers().firstValue("Content-Type").orElse(null));
        return MAPPER.readTree(r.body());
    }

    private static String call(int id, String tool, String argsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + argsJson + "}}";
    }

    @Test
    void urlForEncodesCallerAndUsesActualPort() {
        assertNotEquals(0, server.port());
        assertEquals("http://127.0.0.1:" + server.port() + "/mcp/my%20sess%2Fx?token=" + TOKEN,
                server.urlFor("my sess/x"));
        assertEquals("http://127.0.0.1:" + server.port() + "/mcp?token=" + TOKEN, server.urlFor(null));
        assertEquals("http://127.0.0.1:" + server.port() + "/mcp/my%20sess%2Fx", server.callerUrl("my sess/x"));
        assertEquals("http://127.0.0.1:" + server.port() + "/mcp", server.callerUrl(null));
    }

    @Test
    void initializeEchoesKnownProtocolVersion() throws Exception {
        HttpResponse<String> r = post(server.urlFor("a"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\","
                        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Mcp-Session-Id").isPresent());
        JsonNode res = MAPPER.readTree(r.body());
        assertEquals("2.0", res.get("jsonrpc").asText());
        assertEquals(1, res.get("id").asInt());
        JsonNode result = res.get("result");
        assertEquals("2025-03-26", result.get("protocolVersion").asText());
        assertFalse(result.at("/capabilities/tools/listChanged").asBoolean(true));
        assertEquals("harness-manager", result.at("/serverInfo/name").asText());
        assertEquals("9.9.9-test", result.at("/serverInfo/version").asText());
        assertTrue(result.get("instructions").asText().contains("notify"));
    }

    @Test
    void initializeWithUnknownVersionAnswersLatest() throws Exception {
        JsonNode res = rpc(server.urlFor(null),
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals("x", res.get("id").asText());
        assertEquals("2025-06-18", res.at("/result/protocolVersion").asText());
    }

    @Test
    void pingAnswersEmptyObject() throws Exception {
        JsonNode res = rpc(server.urlFor("a"), "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");
        assertTrue(res.get("result").isObject());
        assertTrue(res.get("result").isEmpty());
    }

    @Test
    void toolsListReturnsDefinitions() throws Exception {
        JsonNode res = rpc(server.urlFor("a"), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonNode list = res.at("/result/tools");
        assertEquals(3, list.size());
        assertEquals("echo", list.get(0).get("name").asText());
        assertEquals("Echoes msg and the caller.", list.get(0).get("description").asText());
        assertEquals("string", list.get(0).at("/inputSchema/properties/msg/type").asText());
    }

    @Test
    void toolsCallOk() throws Exception {
        JsonNode res = rpc(server.urlFor("caller-1"), call(3, "echo", "{\"msg\":\"hi\"}"));
        assertEquals(3, res.get("id").asInt());
        assertEquals("text", res.at("/result/content/0/type").asText());
        assertEquals("caller-1:hi", res.at("/result/content/0/text").asText());
        assertFalse(res.at("/result/isError").asBoolean(true));
    }

    @Test
    void toolsCallErrorResult() throws Exception {
        JsonNode res = rpc(server.urlFor("a"), call(4, "fail", "{}"));
        assertEquals("nope", res.at("/result/content/0/text").asText());
        assertTrue(res.at("/result/isError").asBoolean());
    }

    @Test
    void toolsetExceptionBecomesErrorResult() throws Exception {
        JsonNode res = rpc(server.urlFor("a"), call(5, "boom", "{}"));
        assertNull(res.get("error"));
        assertEquals("kaboom", res.at("/result/content/0/text").asText());
        assertTrue(res.at("/result/isError").asBoolean());
    }

    @Test
    void unknownToolIsErrorResult() throws Exception {
        JsonNode res = rpc(server.urlFor("a"), call(6, "nosuch", "{}"));
        assertTrue(res.at("/result/isError").asBoolean());
        assertTrue(res.at("/result/content/0/text").asText().contains("nosuch"));
        assertTrue(tools.callers.isEmpty());
    }

    @Test
    void missingArgumentsAreAnEmptyObject() throws Exception {
        JsonNode res = rpc(server.urlFor("a"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\"}}");
        assertEquals("a:", res.at("/result/content/0/text").asText());
    }

    @Test
    void callerIsUrlDecodedFromPath() throws Exception {
        JsonNode res = rpc(server.urlFor("proj é/1+2"), call(1, "echo", "{}"));
        assertEquals("proj é/1+2:", res.at("/result/content/0/text").asText());
        // A raw '+' in the path is literal, not a space.
        String raw = "http://127.0.0.1:" + server.port() + "/mcp/a+b%20c?token=" + TOKEN;
        assertEquals("a+b c:", rpc(raw, call(2, "echo", "{}")).at("/result/content/0/text").asText());
    }

    @Test
    void anonymousCallerIsNull() throws Exception {
        rpc(server.urlFor(null), call(1, "echo", "{}"));
        String slash = "http://127.0.0.1:" + server.port() + "/mcp/?token=" + TOKEN;
        rpc(slash, call(2, "echo", "{}"));
        assertEquals(List.of("null", "null"), tools.callers);
    }

    @Test
    void bearerTokenIsAccepted() throws Exception {
        String url = "http://127.0.0.1:" + server.port() + "/mcp/b";
        HttpResponse<String> r = post(url, call(1, "echo", "{}"), "Authorization", "Bearer " + TOKEN);
        assertEquals(200, r.statusCode());
        assertEquals("b:", MAPPER.readTree(r.body()).at("/result/content/0/text").asText());
    }

    @Test
    void badOrMissingTokenIs401() throws Exception {
        String base = "http://127.0.0.1:" + server.port() + "/mcp/a";
        assertEquals(401, post(base + "?token=wrong", call(1, "echo", "{}")).statusCode());
        assertEquals(401, post(base, call(1, "echo", "{}")).statusCode());
        assertEquals(401, post(base, call(1, "echo", "{}"), "Authorization", "Bearer wrong").statusCode());
        assertEquals(401, post(base + "?token=" + TOKEN + "x", call(1, "echo", "{}")).statusCode());
        assertTrue(tools.callers.isEmpty());
    }

    @Test
    void foreignOriginIs403() throws Exception {
        HttpResponse<String> r = post(server.urlFor("a"), call(1, "echo", "{}"), "Origin", "https://evil.example");
        assertEquals(403, r.statusCode());
        assertEquals(403, post(server.urlFor("a"), call(1, "echo", "{}"), "Origin", "null").statusCode());
        assertTrue(tools.callers.isEmpty());
        assertEquals(200, post(server.urlFor("a"), call(1, "echo", "{}"), "Origin", "http://localhost:3000").statusCode());
        assertEquals(200, post(server.urlFor("a"), call(1, "echo", "{}"), "Origin", "http://127.0.0.1").statusCode());
        assertEquals(200, post(server.urlFor("a"), call(1, "echo", "{}"), "Origin", "http://[::1]:8080").statusCode());
    }

    @Test
    void getAndDeleteAre405() throws Exception {
        HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(server.urlFor("a")))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());
        assertEquals("POST", get.headers().firstValue("Allow").orElse(null));
        HttpResponse<String> delete = http.send(HttpRequest.newBuilder(URI.create(server.urlFor("a")))
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, delete.statusCode());
    }

    @Test
    void foreignPathIs404() throws Exception {
        String url = "http://127.0.0.1:" + server.port() + "/mcpx?token=" + TOKEN;
        assertEquals(404, post(url, call(1, "echo", "{}")).statusCode());
        String nested = "http://127.0.0.1:" + server.port() + "/mcp/a/b?token=" + TOKEN;
        assertEquals(404, post(nested, call(1, "echo", "{}")).statusCode());
    }

    @Test
    void batchAnswersRequestsOnly() throws Exception {
        String body = "[" + call(1, "echo", "{\"msg\":\"one\"}") + ","
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}]";
        JsonNode res = rpc(server.urlFor("z"), body);
        assertTrue(res.isArray());
        assertEquals(3, res.size());
        assertEquals("z:one", res.get(0).at("/result/content/0/text").asText());
        assertEquals(-32601, res.get(1).at("/error/code").asInt());
        assertEquals(2, res.get(1).get("id").asInt());
        assertEquals(3, res.get(2).get("id").asInt());
    }

    @Test
    void notificationOnlyIs202WithEmptyBody() throws Exception {
        HttpResponse<String> one = post(server.urlFor("a"),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, one.statusCode());
        assertEquals("", one.body());
        HttpResponse<String> batch = post(server.urlFor("a"),
                "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                        + "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}]");
        assertEquals(202, batch.statusCode());
        assertEquals("", batch.body());
    }

    @Test
    void unknownMethodIs32601() throws Exception {
        JsonNode res = rpc(server.urlFor("a"), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertEquals(-32601, res.at("/error/code").asInt());
        assertEquals(1, res.get("id").asInt());
    }

    @Test
    void parseErrorIs32700() throws Exception {
        JsonNode res = rpc(server.urlFor("a"), "{not json");
        assertEquals(-32700, res.at("/error/code").asInt());
        assertTrue(res.get("id").isNull());
    }

    @Test
    void invalidRequestIs32600() throws Exception {
        assertEquals(-32600, rpc(server.urlFor("a"), "42").at("/error/code").asInt());
        assertEquals(-32600, rpc(server.urlFor("a"), "[]").at("/error/code").asInt());
        JsonNode noVersion = rpc(server.urlFor("a"), "{\"id\":4,\"method\":\"ping\"}");
        assertEquals(-32600, noVersion.at("/error/code").asInt());
        assertEquals(4, noVersion.get("id").asInt());
        JsonNode batch = rpc(server.urlFor("a"), "[1,{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}]");
        assertEquals(-32600, batch.get(0).at("/error/code").asInt());
        assertEquals(5, batch.get(1).get("id").asInt());
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        String big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\""
                + "x".repeat(McpServer.MAX_BODY_BYTES) + "\"}}";
        assertEquals(413, post(server.urlFor("a"), big).statusCode());
    }

    @Test
    void closeIsIdempotentAndStopsServing() throws Exception {
        String url = server.urlFor("a");
        server.close();
        server.close();
        try {
            post(url, call(1, "echo", "{}"));
            throw new AssertionError("expected connection failure after close");
        } catch (IOException expected) {
            // refused
        }
    }
}
