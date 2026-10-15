package io.github.ghosthack.harnessmanager.control;

import io.github.ghosthack.jmux.client.SessionServerClient;
import io.github.ghosthack.jmux.session.CaptureFormat;
import io.github.ghosthack.jmux.session.Session;
import io.github.ghosthack.jmux.session.SessionEvent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

/** {@link JmuxControl} over the jmux client library — a pass-through; the client never
 * spawns a daemon for any of these calls. */
public final class ClientJmuxControl implements JmuxControl {

    private final SessionServerClient client;

    public ClientJmuxControl(SessionServerClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<Session.Info> list() {
        return client.list();
    }

    @Override
    public Optional<Session.Capture> capture(String name, CaptureFormat format) {
        return client.capture(name, format);
    }

    @Override
    public void input(String name, String text, boolean paste, boolean requireBracketedPaste, List<String> keys) {
        client.input(name, text, paste, requireBracketedPaste, keys);
    }

    @Override
    public Session.WaitResult awaitQuiet(String name, long sinceBytes, long quietMs, long timeoutMs) {
        return client.awaitQuiet(name, sinceBytes, quietMs, timeoutMs);
    }

    @Override
    public Watch watch(long quietMs, Consumer<SessionEvent> onEvent) {
        SessionServerClient.Watch watch = client.watch(null, quietMs, onEvent);
        return new Watch() {
            @Override
            public boolean isOpen() {
                return watch.isOpen();
            }

            @Override
            public void close() {
                watch.close();
            }
        };
    }

    @Override
    public void sendKeys(String name, String text, boolean enter) {
        client.sendKeys(name, text, enter);
    }

    @Override
    public OptionalLong daemonPid() {
        return client.ping().map(info -> OptionalLong.of(info.pid())).orElse(OptionalLong.empty());
    }

    @Override
    public boolean ping() {
        return client.isServerRunning();
    }

    @Override
    public void kill(String name) {
        client.kill(name);
    }

    @Override
    public void label(String name, Map<String, String> changes) {
        client.label(name, changes);
    }
}
