package io.github.ghosthack.harnessmanager.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * The app's handle on its one {@link McpServer}: started at launch when the
 * {@code mcp.server} setting is on, restarted from Settings when the port changes,
 * stopped on Settings-off and on shutdown. Thread-safe — the UI starts and stops it on
 * the EDT while session launches on background threads ask {@link #linkFor} which URL
 * and token to give a launched agent.
 *
 * <p>The toolset is fixed for the service's lifetime; only the listening socket comes
 * and goes, and the toolset is told about each ({@link McpToolset#serverStarted},
 * {@link McpToolset#serverStopped}) so its background work runs only while it serves.
 * A bind failure (the port is taken) leaves the service stopped, is logged, and is
 * rethrown for the caller to report — it never takes the app down.
 */
public final class McpService {

    private static final Logger LOG = LoggerFactory.getLogger(McpService.class);

    /** A toolset with no tools, for windows and tests that host no controller. */
    public static final McpToolset NO_TOOLS = new McpToolset() {
        @Override
        public List<ToolDef> tools() {
            return List.of();
        }

        @Override
        public ToolResult call(String caller, String tool, JsonNode arguments) {
            return ToolResult.error("unknown tool: " + tool);
        }
    };

    private final McpToolset tools;
    private final String serverVersion;

    /** Guarded by {@code this}; null while stopped. */
    private McpServer server;
    private String token;

    public McpService(McpToolset tools) {
        this(tools, defaultVersion());
    }

    public McpService(McpToolset tools, String serverVersion) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
    }

    /**
     * Serves on 127.0.0.1:{@code port} (0 = any free port, for tests) with {@code token}.
     * Already running with the same port and token is a no-op; with a different one the
     * old socket is closed first, so a failed restart leaves the service stopped rather
     * than on the stale port.
     *
     * @throws IOException when the port cannot be bound; the service is then stopped
     */
    public synchronized void start(int port, String token) throws IOException {
        if (server != null && port != 0 && server.port() == port && token.equals(this.token)) {
            return;
        }
        stop();
        McpServer candidate = new McpServer(port, token, tools, serverVersion);
        try {
            candidate.start();
        } catch (IOException | RuntimeException e) {
            candidate.close();
            LOG.warn("MCP server could not start on 127.0.0.1:{}: {}", port, e.toString());
            throw e;
        }
        server = candidate;
        this.token = token;
        LOG.info("MCP server listening on 127.0.0.1:{}", candidate.port());
        tools.serverStarted();
    }

    /** Closes the socket; idempotent. In-flight calls are abandoned. */
    public synchronized void stop() {
        if (server != null) {
            server.close();
            LOG.info("MCP server stopped");
            server = null;
            token = null;
            tools.serverStopped();
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /** The bound port, or -1 while stopped. */
    public synchronized int port() {
        return server != null ? server.port() : -1;
    }

    /**
     * The URL an agent running in {@code session} reaches the server at — the session
     * name rides in the path as the caller identity, the token in the query — or null
     * while stopped. For manual clients; launched agents get {@link #linkFor}.
     */
    public synchronized String urlFor(String session) {
        return server != null ? server.urlFor(session) : null;
    }

    /**
     * The token-free URL for {@code session} and the token to send as a bearer header,
     * read together so a concurrent restart can never pair one server's URL with another's
     * token — or null while stopped, which callers read as "launch without MCP".
     */
    public synchronized McpLink linkFor(String session) {
        return server != null ? new McpLink(server.callerUrl(session), token) : null;
    }

    /** A running server's per-session URL (no token in it) and its bearer token. */
    public record McpLink(String url, String token) {
        @Override
        public String toString() {
            return "McpLink[url=" + url + "]";
        }
    }

    private static String defaultVersion() {
        String version = McpService.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
