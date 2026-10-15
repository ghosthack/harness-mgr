package io.github.ghosthack.harnessmanager.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal MCP server over "Streamable HTTP": JSON-RPC 2.0 on {@code POST /mcp}, every
 * answer a plain {@code application/json} body (no SSE stream — {@code GET} is 405, which
 * the spec allows). Serves the tools of one {@link McpToolset}.
 *
 * <p>The URL path carries the caller's identity: {@code http://127.0.0.1:<port>/mcp/<caller>}
 * (see {@link #callerUrl}); {@code /mcp} without a path segment is an anonymous caller.
 * The shared secret goes in an {@code Authorization: Bearer <token>} header — how the
 * app's own launched agents send it — or, for manual clients whose config is a single
 * URL string, in the query: {@code ?token=<token>} (see {@link #urlFor}).
 *
 * <p>Security: binds the loopback interface only; rejects requests carrying an
 * {@code Origin} header that is not localhost (403 — DNS-rebinding guard) and requests
 * without the token (401, constant-time compare); bodies over 1 MiB get 413. Toolset
 * exceptions become {@code isError} tool results, never HTTP failures.
 *
 * <p>Requests are served on virtual threads, one per exchange; a slow tool call does not
 * hold up others.
 */
public final class McpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpServer.class);

    /** Newest first; the first one is answered when the client asks for anything else. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final String SERVER_NAME = "harness-manager";
    static final int MAX_BODY_BYTES = 1 << 20;
    static final String CONTEXT = "/mcp";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    static final String INSTRUCTIONS = """
            harness-manager lets you see and drive the other terminal sessions it hosts \
            (coding agents and shells running side by side with you).
            - Start with `list` to see the sessions, their harness, state and transcript path.
            - `read` is incremental and cheap: it returns only what changed since your last read \
            of that session (or "unchanged"); pass full=true for the whole screen.
            - Answer prompts and menus with `keys`, one step at a time, and read the screen it \
            returns before the next step.
            - To wait for another session, use `notify` instead of polling with `read`: you get a \
            line typed into your session when the event happens.
            - The screen is only the visible terminal; a session's history lives in its transcript \
            file — tail or grep it with your file tools rather than loading it whole.""";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final int requestedPort;
    private final byte[] token;
    private final String tokenText;
    private final McpToolset tools;
    private final String serverVersion;

    private HttpServer server;
    private ExecutorService executor;

    /**
     * @param port          TCP port on 127.0.0.1; 0 picks a free one (see {@link #port()})
     * @param token         the shared secret every request must present
     * @param tools         the tools to serve
     * @param serverVersion reported as {@code serverInfo.version}
     */
    public McpServer(int port, String token, McpToolset tools, String serverVersion) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        Objects.requireNonNull(token, "token");
        if (token.isEmpty()) {
            throw new IllegalArgumentException("empty token");
        }
        this.requestedPort = port;
        this.tokenText = token;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.tools = Objects.requireNonNull(tools, "tools");
        this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
    }

    /** Binds 127.0.0.1 and starts serving. Throws if the port is taken or already started. */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("already started");
        }
        HttpServer s = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        s.setExecutor(ex);
        s.createContext(CONTEXT, this::handle);
        s.start();
        server = s;
        executor = ex;
    }

    /** The bound port once started (the real one when constructed with 0), else the requested port. */
    public synchronized int port() {
        return server != null ? server.getAddress().getPort() : requestedPort;
    }

    /** The token-free URL for session {@code caller} — the token then goes in a bearer
     * header; {@code null} caller gives the anonymous URL. */
    public String callerUrl(String caller) {
        String path = caller == null ? CONTEXT : CONTEXT + "/" + encode(caller);
        return "http://127.0.0.1:" + port() + path;
    }

    /** {@link #callerUrl} with the token in the query, for a manual client whose MCP
     * config is a single URL string. */
    public String urlFor(String caller) {
        return callerUrl(caller) + "?token=" + encode(tokenText);
    }

    /** Stops immediately; idempotent. In-flight exchanges are abandoned. */
    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            executor.shutdownNow();
            server = null;
            executor = null;
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                serve(exchange);
            } catch (IOException e) {
                LOG.debug("MCP exchange failed: {}", e.toString());
            } catch (RuntimeException e) {
                LOG.warn("MCP request failed", e);
                sendStatus(exchange, 500, "internal error");
            }
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        CallerPath path = parsePath(uri.getRawPath());
        if (path == null) {
            sendStatus(exchange, 404, "not found");
            return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !isLocalOrigin(origin)) {
            sendStatus(exchange, 403, "forbidden origin");
            return;
        }
        if (!authorized(uri.getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"))) {
            sendStatus(exchange, 401, "missing or invalid token");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendStatus(exchange, 405, "method not allowed (POST JSON-RPC; no SSE stream offered)");
            return;
        }
        byte[] body = readBody(exchange);
        if (body == null) {
            sendStatus(exchange, 413, "request body over " + MAX_BODY_BYTES + " bytes");
            return;
        }

        Exchange result = dispatch(body, path.caller());
        if (result.response() == null) {
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        if (result.initialized()) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
        }
        sendJson(exchange, 200, MAPPER.writeValueAsBytes(result.response()));
    }

    /** The decoded caller; {@code null} when the path is not under {@code /mcp}. */
    record CallerPath(String caller) {}

    static CallerPath parsePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        if (rawPath.equals(CONTEXT) || rawPath.equals(CONTEXT + "/")) {
            return new CallerPath(null);
        }
        if (!rawPath.startsWith(CONTEXT + "/")) {
            return null; // e.g. "/mcpx"
        }
        String segment = rawPath.substring(CONTEXT.length() + 1);
        if (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        if (segment.isEmpty() || segment.contains("/")) {
            return null;
        }
        String caller;
        try {
            // Path semantics: '+' is literal, only %XX escapes are decoded.
            caller = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return caller.isEmpty() ? new CallerPath(null) : new CallerPath(caller);
    }

    static boolean isLocalOrigin(String origin) {
        try {
            String host = new URI(origin.trim()).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private boolean authorized(String rawQuery, String authorization) {
        if (authorization != null) {
            String a = authorization.trim();
            if (a.regionMatches(true, 0, "Bearer ", 0, 7) && matches(a.substring(7).trim())) {
                return true;
            }
        }
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals("token")) {
                    String value;
                    try {
                        value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    if (matches(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean matches(String candidate) {
        return MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** The body, or {@code null} when it exceeds {@link #MAX_BODY_BYTES}. */
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length.trim()) > MAX_BODY_BYTES) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the bounded read
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }

    private static void sendStatus(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode body = errorResponse(NullNode.getInstance(), -32000, message);
        sendJson(exchange, status, MAPPER.writeValueAsBytes(body));
    }

    private static void sendJson(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // ---------------------------------------------------------------- JSON-RPC

    /** A POST's answer: {@code null} response means 202 with no body. */
    private record Exchange(JsonNode response, boolean initialized) {}

    private Exchange dispatch(byte[] body, String caller) {
        JsonNode message;
        try {
            message = MAPPER.readTree(body);
        } catch (IOException e) {
            return new Exchange(errorResponse(NullNode.getInstance(), PARSE_ERROR, "parse error"), false);
        }
        if (message == null || message.isMissingNode()) {
            return new Exchange(errorResponse(NullNode.getInstance(), PARSE_ERROR, "parse error: empty body"), false);
        }
        boolean[] initialized = {false};
        if (message.isArray()) {
            if (message.isEmpty()) {
                return new Exchange(errorResponse(NullNode.getInstance(), INVALID_REQUEST, "empty batch"), false);
            }
            ArrayNode responses = JSON.arrayNode();
            for (JsonNode m : message) {
                JsonNode r = process(m, caller, initialized);
                if (r != null) {
                    responses.add(r);
                }
            }
            return new Exchange(responses.isEmpty() ? null : responses, initialized[0]);
        }
        return new Exchange(process(message, caller, initialized), initialized[0]);
    }

    /** One message's response, or {@code null} for notifications and client responses. */
    private JsonNode process(JsonNode message, String caller, boolean[] initialized) {
        if (!message.isObject()) {
            return errorResponse(NullNode.getInstance(), INVALID_REQUEST, "invalid request");
        }
        JsonNode method = message.get("method");
        JsonNode id = message.get("id");
        if (method == null && (message.has("result") || message.has("error"))) {
            return null; // a response to a server request; we never send any, ignore
        }
        boolean validId = id == null || id.isTextual() || id.isNumber() || id.isNull();
        JsonNode replyId = id != null && validId ? id : NullNode.getInstance();
        if (method == null || !method.isTextual()
                || !"2.0".equals(message.path("jsonrpc").asText(null)) || !validId) {
            return errorResponse(replyId, INVALID_REQUEST, "invalid request");
        }
        if (id == null) {
            return null; // notification (e.g. notifications/initialized): nothing to answer
        }
        JsonNode params = message.get("params");
        try {
            return switch (method.asText()) {
                case "initialize" -> {
                    initialized[0] = true;
                    yield resultResponse(id, initialize(params));
                }
                case "ping" -> resultResponse(id, JSON.objectNode());
                case "tools/list" -> resultResponse(id, toolsList());
                case "tools/call" -> {
                    if (params == null || !params.path("name").isTextual()) {
                        yield errorResponse(id, INVALID_PARAMS, "tools/call needs a string 'name'");
                    }
                    yield resultResponse(id, toolsCall(caller, params.get("name").asText(), params.get("arguments")));
                }
                default -> errorResponse(id, METHOD_NOT_FOUND, "method not found: " + method.asText());
            };
        } catch (RuntimeException e) {
            LOG.warn("MCP {} failed", method.asText(), e);
            return errorResponse(id, -32603, "internal error: " + e.getMessage());
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params == null ? null : params.path("protocolVersion").asText(null);
        String version = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0);
        ObjectNode result = JSON.objectNode();
        result.put("protocolVersion", version);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", serverVersion);
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = JSON.objectNode();
        ArrayNode list = result.putArray("tools");
        for (ToolDef def : tools.tools()) {
            ObjectNode t = list.addObject();
            t.put("name", def.name());
            t.put("description", def.description());
            t.set("inputSchema", def.inputSchema());
        }
        return result;
    }

    private ObjectNode toolsCall(String caller, String name, JsonNode arguments) {
        ToolResult outcome;
        Set<String> known = new HashSet<>();
        for (ToolDef def : tools.tools()) {
            known.add(def.name());
        }
        if (!known.contains(name)) {
            outcome = ToolResult.error("unknown tool: " + name);
        } else {
            JsonNode args = arguments == null || arguments.isNull() ? JSON.objectNode() : arguments;
            try {
                outcome = tools.call(caller, name, args);
                if (outcome == null) {
                    outcome = ToolResult.error(name + ": no result");
                }
            } catch (RuntimeException e) {
                LOG.debug("MCP tool {} failed", name, e);
                String message = e.getMessage();
                outcome = ToolResult.error(message == null || message.isBlank() ? e.toString() : message);
            }
        }
        ObjectNode result = JSON.objectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", outcome.text());
        result.put("isError", outcome.isError());
        return result;
    }

    private static ObjectNode resultResponse(JsonNode id, JsonNode result) {
        ObjectNode r = JSON.objectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private static ObjectNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode r = JSON.objectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        ObjectNode error = r.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return r;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
