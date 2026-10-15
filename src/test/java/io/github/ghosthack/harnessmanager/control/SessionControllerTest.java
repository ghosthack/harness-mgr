package io.github.ghosthack.harnessmanager.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ghosthack.harnessmanager.harness.HarnessType;
import io.github.ghosthack.harnessmanager.mcp.McpService;
import io.github.ghosthack.harnessmanager.mcp.ToolDef;
import io.github.ghosthack.harnessmanager.mcp.ToolResult;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import io.github.ghosthack.harnessmanager.registry.SessionRegistry;
import io.github.ghosthack.harnessmanager.testutil.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller's tools against a fake jmux, a fake spawner and a mutable clock; the
 * monitor is driven by calling {@code tick()} directly, deliveries run inline.
 */
final class SessionControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOTICE_TAIL = ". Use the read tool to see it.";

    @TempDir
    Path dir;

    private FakeJmux jmux;
    private SessionRegistry registry;
    private MutableClock clock;
    private FakeSpawner spawner;
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private SessionController controller;

    @BeforeEach
    void setUp() {
        jmux = new FakeJmux();
        registry = SessionRegistry.load(dir.resolve("sessions.json"));
        clock = new MutableClock();
        spawner = new FakeSpawner();
        controller = controller(SessionController.Timings.DEFAULT);
    }

    private SessionController controller(SessionController.Timings timings) {
        return controller(timings, Runnable::run);
    }

    private SessionController controller(SessionController.Timings timings, java.util.concurrent.Executor deliveries) {
        return new SessionController(jmux, registry, spawner, clock,
                (harness, project, id, pid) -> switch (harness) {
                    case CODEX -> Optional.of(Path.of("/codex/rollout-x.jsonl"));
                    case CLAUDE_CODE -> Optional.of(Path.of("/claude" + project, id.orElse("newest") + ".jsonl"));
                    default -> Optional.empty();
                },
                sleeps::add, deliveries, timings);
    }

    // ------------------------------------------------------------------ helpers

    private ToolResult call(String caller, String tool, String json) {
        try {
            return controller.call(caller, tool, JSON.readTree(json));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String ok(String caller, String tool, String json) {
        ToolResult result = call(caller, tool, json);
        assertFalse(result.isError(), () -> "expected success, got: " + result.text());
        return result.text();
    }

    private String error(String caller, String tool, String json) {
        ToolResult result = call(caller, tool, json);
        assertTrue(result.isError(), () -> "expected an error, got: " + result.text());
        return result.text();
    }

    private FakeJmux.FakeSession shell(String name) {
        FakeJmux.FakeSession s = jmux.add(name);
        s.labels.put("harness", "shell");
        return s;
    }

    /** A claude-code session showing {@code screen}, claude in the foreground. */
    private FakeJmux.FakeSession agent(String name, String screen) {
        FakeJmux.FakeSession s = jmux.add(name);
        s.labels.put("harness", "claude-code");
        s.foreground = "claude";
        return s.show(screen);
    }

    /** Claude Code idle at its prompt. */
    private static final String CLAUDE_IDLE = "Done.\n❯ \n? for shortcuts\n";
    /** Claude Code asking for a permission. */
    private static final String CLAUDE_APPROVAL = "Bash: rm -rf build\nDo you want to proceed?\n❯ 1. Yes\n"
            + "  2. No, and tell Claude what to do differently (esc)\n";

    /** Lets {@code seconds} pass, one monitor tick a second. */
    private void ticks(int seconds) {
        for (int i = 0; i < seconds; i++) {
            clock.advance(Duration.ofSeconds(1));
            controller.tick();
        }
    }

    private void register(String name, HarnessType harness, String display) {
        SessionEntry entry = SessionEntry.newlyCreated(name, "/proj/" + name, harness, null, "0.0", null, "jmux");
        registry.put(display != null ? entry.withDisplayName(display) : entry);
    }

    private static String lines(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.toString();
    }

    private List<FakeJmux.Input> inputsTo(String session) {
        return jmux.inputs.stream().filter(i -> i.session().equals(session)).toList();
    }

    // ------------------------------------------------------------------ tools/list

    @Test
    void offersTheSevenToolsWithDescribedSchemas() {
        List<String> names = controller.tools().stream().map(ToolDef::name).toList();
        assertEquals(List.of("list", "read", "send", "keys", "notify", "spawn", "kill"), names);
        for (ToolDef def : controller.tools()) {
            assertEquals("object", def.inputSchema().path("type").asText());
            def.inputSchema().path("properties").forEach(p ->
                    assertFalse(p.path("description").asText().isBlank(), def.name()));
        }
        ToolDef keys = controller.tools().get(3);
        assertTrue(keys.description().contains("C-c"));
        assertTrue(keys.description().contains("PageDown"));
        JsonNode harnesses = controller.tools().get(5).inputSchema().path("properties").path("harness").path("enum");
        assertEquals(HarnessType.values().length, harnesses.size());
    }

    @Test
    void unknownToolIsAnError() {
        assertTrue(error(null, "explode", "{}").contains("unknown tool"));
    }

    // ------------------------------------------------------------------ resolution

    @Test
    void resolvesByNameRegistryDisplayNameAndDisplayLabel() {
        shell("hm-a").show("alpha");
        jmux.add("hm-b").show("beta");
        register("hm-b", HarnessType.SHELL, "Backend");
        FakeJmux.FakeSession c = shell("hm-c").show("gamma");
        c.labels.put("display", "Docs");

        assertTrue(ok(null, "read", "{\"session\":\"hm-a\"}").startsWith("hm-a · shell"));
        assertTrue(ok(null, "read", "{\"session\":\"backend\"}").startsWith("hm-b · shell"));
        assertTrue(ok(null, "read", "{\"session\":\"DOCS\"}").startsWith("hm-c · shell"));
        assertTrue(error(null, "read", "{\"session\":\"nope\"}").contains("no such session: nope"));
    }

    @Test
    void refusesSessionsThatAreNotOnJmux() {
        registry.put(SessionEntry.newlyCreated("hm-t", "/p", HarnessType.CODEX, null, "0.0", null, "tmux")
                .withDisplayName("Tmuxy"));
        assertTrue(error(null, "read", "{\"session\":\"hm-t\"}").contains("not a jmux session"));
        assertTrue(error(null, "send", "{\"session\":\"tmuxy\",\"text\":\"hi\"}").contains("not a jmux session"));
    }

    @Test
    void ambiguousDisplayNameIsAnError() {
        shell("hm-a").labels.put("display", "Twin");
        shell("hm-b").labels.put("display", "twin");
        assertTrue(error(null, "read", "{\"session\":\"Twin\"}").contains("ambiguous"));
    }

    // ------------------------------------------------------------------ list

    @Test
    void listsOneLinePerSessionWithStateProjectTranscriptAndTheCallersMark() {
        jmux.quietProbe = true; // both long idle: primed straight to a quiescent state
        jmux.add("hm-api").show("› ready").foreground = "codex";
        register("hm-api", HarnessType.CODEX, "API");
        shell("hm-sh").show("$ ").workdir = Path.of("/tmp/sh");

        String list = ok("hm-sh", "list", "{}");
        String[] rows = list.split("\n");
        assertEquals(2, rows.length);
        assertTrue(rows[0].startsWith("hm-api · API · codex · "), rows[0]);
        assertTrue(rows[0].endsWith(" · /proj/hm-api · transcript: /codex/rollout-x.jsonl"), rows[0]);
        assertEquals("hm-sh · - · shell · IDLE · /tmp/sh · transcript: - (you)", rows[1]);
    }

    @Test
    void aClaudeTranscriptIsNeverGuessedWhenAnotherClaudeSessionSharesTheProject() {
        jmux.quietProbe = true;
        for (String name : List.of("hm-one", "hm-two", "hm-solo")) {
            jmux.add(name).show("> ").foreground = "claude";
        }
        registry.put(SessionEntry.newlyCreated("hm-one", "/shared", HarnessType.CLAUDE_CODE, "id-1", "0.0", null, "jmux"));
        registry.put(SessionEntry.newlyCreated("hm-two", "/shared", HarnessType.CLAUDE_CODE, null, "0.0", null, "jmux"));
        registry.put(SessionEntry.newlyCreated("hm-solo", "/alone", HarnessType.CLAUDE_CODE, null, "0.0", null, "jmux"));

        String[] rows = ok(null, "list", "{}").split("\n");
        assertTrue(rows[0].endsWith("transcript: /claude/shared/id-1.jsonl"), "its own file: " + rows[0]);
        assertTrue(rows[1].contains("transcript: /claude/shared/ (id unknown"), "the folder, not a guess: " + rows[1]);
        assertTrue(rows[2].endsWith("transcript: /claude/alone/newest.jsonl"), "alone in its project: " + rows[2]);
    }

    @Test
    void listWithoutSessionsSaysSo() {
        assertTrue(ok(null, "list", "{}").startsWith("no jmux sessions"));
    }

    // ------------------------------------------------------------------ read

    @Test
    void readIsWholeThenUnchangedThenIncrementalThenFullOnRequest() {
        FakeJmux.FakeSession s = shell("hm-a").show(lines(1, 10) + "$ ▮\n\n\n");
        String first = ok("hm-me", "read", "{\"session\":\"hm-a\"}");
        String[] firstLines = first.split("\n");
        assertTrue(firstLines[0].startsWith("hm-a · shell · WORKING ("), firstLines[0]);
        assertEquals(12, firstLines.length, "header + 11 lines, trailing blanks trimmed");

        String again = ok("hm-me", "read", "{\"session\":\"hm-a\"}");
        assertTrue(again.endsWith(" · unchanged"), again);
        assertEquals(1, again.split("\n").length);

        // scrolled by three lines: only the new ones and the prompt come back
        s.show(lines(4, 13) + "$ ▮\n");
        String incremental = ok("hm-me", "read", "{\"session\":\"hm-a\"}");
        List<String> rows = incremental.lines().toList();
        assertTrue(rows.get(1).startsWith("… (7 lines above already seen"), rows.get(1));
        assertEquals(List.of("line 11", "line 12", "line 13", "$ ▮"), rows.subList(2, rows.size()));

        String full = ok("hm-me", "read", "{\"session\":\"hm-a\",\"full\":true}");
        assertEquals(12, full.split("\n").length);
        assertTrue(full.contains("line 4\n"));

        // seen-tracking is per caller: another reader starts with the whole screen
        assertEquals(12, ok("hm-other", "read", "{\"session\":\"hm-a\"}").split("\n").length);
    }

    @Test
    void chromeOnlyChangesReadAsOnlyStatusLinesChangedWithJustThoseLines() {
        FakeJmux.FakeSession s = agent("hm-c", "Answer: 42\n❯ \n? for shortcuts\n");
        ok(null, "read", "{\"session\":\"hm-c\"}");
        s.show("Answer: 42\n❯ typing▮\n? for shortcuts    auto mode on\n");
        String read = ok(null, "read", "{\"session\":\"hm-c\"}");
        List<String> rows = read.lines().toList();
        assertTrue(rows.getFirst().endsWith(" · only status lines changed"), read);
        assertEquals(List.of("❯ typing▮", "? for shortcuts    auto mode on"), rows.subList(1, rows.size()));
    }

    @Test
    void sameTextWithMovedMarkersOrSameOutputCountIsUnchanged() {
        FakeJmux.FakeSession s = shell("hm-a").show("$ ls\n▶ a\nb\n$ ▮\n");
        ok(null, "read", "{\"session\":\"hm-a\"}");
        s.show("$ ls\na\n▶ b\n$▮ \n"); // only the highlight and the cursor moved
        assertTrue(ok(null, "read", "{\"session\":\"hm-a\"}").endsWith(" · unchanged"));
        s.text = "$ ls\na\nb\n$ x▮\n"; // no output counted (a repaint the daemon did not count)
        assertTrue(ok(null, "read", "{\"session\":\"hm-a\"}").endsWith(" · unchanged"));
    }

    @Test
    void redrawnScreenComesBackWhole() {
        FakeJmux.FakeSession s = shell("hm-a").show(lines(1, 10));
        ok(null, "read", "{\"session\":\"hm-a\"}");
        s.show(lines(50, 59));
        assertEquals(11, ok(null, "read", "{\"session\":\"hm-a\"}").split("\n").length);
    }

    @Test
    void longScreensAreCappedKeepingTheTail() {
        String wide = "x".repeat(200);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append(i).append(' ').append(wide).append('\n');
        }
        shell("hm-a").show(sb.toString());
        String read = ok(null, "read", "{\"session\":\"hm-a\"}");
        String body = read.substring(read.indexOf('\n') + 1);
        assertTrue(body.length() <= SessionController.MAX_BODY_CHARS, "" + body.length());
        assertTrue(body.startsWith("… ("), body.substring(0, 30));
        assertTrue(body.contains("earlier lines omitted"));
        assertTrue(body.endsWith("59 " + wide));
    }

    // ------------------------------------------------------------------ send / keys

    @Test
    void sendPastesThenPressesEnterAfterTheDelay() {
        shell("hm-a").show("$ ");
        assertEquals("sent to hm-a", ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"echo hi\\nworld\"}"));
        assertEquals(List.of(new FakeJmux.Input("hm-a", "echo hi\nworld", true, null),
                new FakeJmux.Input("hm-a", null, false, List.of("Enter"))), inputsTo("hm-a"));
        assertEquals(List.of(Duration.ofMillis(400)), sleeps);

        ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"draft\",\"enter\":false}");
        assertEquals(new FakeJmux.Input("hm-a", "draft", true, null), inputsTo("hm-a").getLast());
    }

    @Test
    void sendAndKillRefuseTheCallersOwnSession() {
        shell("hm-me").show("$ ");
        assertTrue(error("hm-me", "send", "{\"session\":\"hm-me\",\"text\":\"x\"}").contains("your own session"));
        assertTrue(error("hm-me", "kill", "{\"session\":\"hm-me\"}").contains("your own session"));
        assertTrue(jmux.inputs.isEmpty());
        assertTrue(spawner.killed.isEmpty());
    }

    @Test
    void keysPressesWaitsForQuietSinceTheInputAndReturnsTheWholeScreen() {
        FakeJmux.FakeSession s = shell("hm-a").show("Pick one\n▶ 1. Yes\n  2. No\n");
        long before = s.outputBytes;
        ok("hm-me", "read", "{\"session\":\"hm-a\"}");
        String screen = ok("hm-me", "keys", "{\"session\":\"hm-a\",\"keys\":[\"Down\",\"Enter\"]}");
        assertEquals(List.of(new FakeJmux.Input("hm-a", null, false, List.of("Down", "Enter"))), inputsTo("hm-a"));
        assertEquals(List.of(new FakeJmux.Wait("hm-a", before, 300, 3000)), jmux.waits);
        assertTrue(screen.contains("▶ 1. Yes"), "whole screen even though nothing changed: " + screen);
        // and it counts as seen
        assertTrue(ok("hm-me", "read", "{\"session\":\"hm-a\"}").endsWith("unchanged"));
    }

    @Test
    void keysReportsBadKeyNames() {
        shell("hm-a").show("$ ");
        assertTrue(error(null, "keys", "{\"session\":\"hm-a\",\"keys\":[\"Bogus\"]}").contains("unknown key: Bogus"));
        assertTrue(error(null, "keys", "{\"session\":\"hm-a\",\"keys\":[]}").contains("keys is required"));
    }

    // ------------------------------------------------------------------ notify

    @Test
    void notifyNeedsACaller() {
        shell("hm-a").show("$ ");
        assertTrue(error(null, "notify", "{\"session\":\"hm-a\",\"on\":\"finished\"}")
                .contains("only works from a session"));
    }

    @Test
    void finishedOnASessionThatIsNotWorkingRegistersNothing() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("$ ");
        String reply = ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"finished\"}");
        assertEquals("hm-a is already IDLE (not working); nothing registered", reply);
        assertFalse(controller.hasPending());
    }

    @Test
    void finishedIsHeldWhileTheCallerWorksThenTypedIntoIt() {
        FakeJmux.FakeSession me = agent("hm-me", "thinking 0");
        FakeJmux.FakeSession target = shell("hm-a").show("building 0");
        assertEquals("will notify hm-me when hm-a finished",
                ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"finished\"}"));

        // the target goes quiet and turns IDLE while the caller is still busy
        for (int i = 1; i <= 7; i++) {
            clock.advance(Duration.ofSeconds(1));
            me.show("thinking " + i);
            controller.tick();
        }
        assertTrue(inputsTo("hm-me").isEmpty(), "held while the caller works");
        assertTrue(controller.hasPending());

        // the caller ends its turn: once it is quiet at its prompt the notice is typed in
        me.show(CLAUDE_IDLE);
        for (int i = 0; i < 7; i++) {
            clock.advance(Duration.ofSeconds(1));
            controller.tick();
        }
        assertEquals(List.of(
                new FakeJmux.Input("hm-me", "[harness-manager] hm-a finished (IDLE)" + NOTICE_TAIL, true, null),
                new FakeJmux.Input("hm-me", null, false, List.of("Enter"))), inputsTo("hm-me"));
        assertTrue(inputsTo("hm-a").isEmpty(), "nothing is ever typed into the target");
        assertFalse(controller.hasPending(), "one-shot");
        assertEquals("building 0", target.text);
    }

    @Test
    void exitedAndAnyChangeFire() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("$ ");
        FakeJmux.FakeSession b = shell("hm-b").show("$ ");
        String reply = ok("hm-me", "notify", "{\"sessions\":[\"hm-a\"],\"on\":\"exited\"}");
        assertEquals("will notify hm-me when hm-a exited", reply);
        ok("hm-me", "notify", "{\"session\":\"hm-b\",\"on\":\"any-change\"}");

        controller.tick();
        assertTrue(inputsTo("hm-me").isEmpty());

        jmux.kill("hm-a");
        b.show("$ make\nok");
        controller.tick();
        assertEquals("[harness-manager] hm-a exited (EXITED)" + NOTICE_TAIL + "\n"
                        + "[harness-manager] hm-b changed (WORKING)" + NOTICE_TAIL,
                inputsTo("hm-me").getFirst().text(), "fired notices for one caller go in one paste");
        assertFalse(controller.hasPending());
    }

    @Test
    void undeliverableNotificationsAreDroppedAfterThirtyMinutes() {
        FakeJmux.FakeSession me = agent("hm-me", "busy 0");
        shell("hm-a").show("$ ");
        jmux.quietProbe = false;
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        jmux.kill("hm-a");
        for (int i = 1; i <= 32; i++) {
            clock.advance(Duration.ofMinutes(1));
            me.show("busy " + i);
            controller.tick();
        }
        assertTrue(inputsTo("hm-me").isEmpty());
        assertFalse(controller.hasPending());
    }

    @Test
    void notificationsForAGoneCallerAreDropped() {
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("building");
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"finished\"}");
        jmux.kill("hm-me");
        controller.tick();
        assertFalse(controller.hasPending());
    }

    // ------------------------------------------------------------------ spawn / kill

    @Test
    void spawnLaunchesThroughTheSpawnerAndTypesThePromptOnceReady() {
        shell("hm-me").show("$ ");
        String reply = ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\","
                + "\"name\":\"Helper\",\"prompt\":\"ls -la\"}");
        assertEquals("hm-new-1 started (shell in " + dir + "); the prompt will be typed in once it is ready at its prompt",
                reply);
        SessionSpawner.SpawnRequest request = spawner.requests.getFirst();
        assertEquals(HarnessType.SHELL, request.harness());
        assertEquals(dir, request.project());
        assertEquals(Optional.of("Helper"), request.displayName());
        assertEquals("hm-me", request.labels().get(SessionController.LABEL_SPAWNED_BY));

        controller.tick(); // first look: output still changing
        assertTrue(inputsTo("hm-new-1").isEmpty());
        clock.advance(Duration.ofSeconds(6));
        controller.tick();
        assertEquals(List.of(new FakeJmux.Input("hm-new-1", "ls -la", true, null),
                new FakeJmux.Input("hm-new-1", null, false, List.of("Enter"))), inputsTo("hm-new-1"));
        assertFalse(controller.hasPending());
    }

    @Test
    void aSpawnThatNeverGetsReadyTellsTheRequesterInsteadOfTyping() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        spawner.harnessLabel = "claude-code"; // its shell stays in front: claude never started
        ok("hm-me", "spawn", "{\"harness\":\"claude-code\",\"project\":\"~\",\"prompt\":\"hi\"}");
        for (int i = 0; i < 13; i++) {
            clock.advance(Duration.ofSeconds(10));
            controller.tick();
        }
        assertTrue(inputsTo("hm-new-1").isEmpty());
        assertTrue(inputsTo("hm-me").getFirst().text()
                .startsWith("[harness-manager] hm-new-1 did not reach its prompt within 120 s"));
    }

    @Test
    void spawnValidatesHarnessAndProject() {
        assertTrue(error(null, "spawn", "{\"harness\":\"emacs\",\"project\":\"/\"}").contains("unknown harness"));
        assertTrue(error(null, "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir.resolve("missing") + "\"}")
                .contains("not an existing directory"));
        assertTrue(error(null, "spawn", "{\"harness\":\"shell\",\"project\":\"rel/path\"}").contains("absolute"));
        assertTrue(spawner.requests.isEmpty());
    }

    @Test
    void killEndsAnotherSession() {
        shell("hm-a").show("$ ");
        assertEquals("killed hm-a", ok("hm-me", "kill", "{\"session\":\"hm-a\"}"));
        assertEquals(List.of("hm-a"), spawner.killed);
    }

    // ------------------------------------------------------------------ old daemon

    @Test
    void anOldDaemonIsReadAsPlainTextAndAnUnknownCounterNeverMeansUnchanged() {
        jmux.old = true;
        FakeJmux.FakeSession s = shell("hm-a").show("line 1\n▶ choice\n$ ▮\n");
        String first = ok(null, "read", "{\"session\":\"hm-a\"}");
        assertTrue(first.lines().findFirst().orElseThrow().endsWith(" " + SessionController.OLD_DAEMON_NOTE), first);
        assertFalse(first.contains("▮"), first);
        assertFalse(first.contains("▶"), first);
        int refused = jmux.unsupportedCalls.get(); // annotated capture and the quiet probe, once each
        assertEquals(2, refused);

        s.show("line 1\nchoice\n$ ls▮\n"); // the old daemon reports outputBytes -1 both times
        String second = ok(null, "read", "{\"session\":\"hm-a\"}");
        assertFalse(second.endsWith("unchanged"), second);
        assertTrue(second.endsWith("$ ls"), second);
        assertTrue(ok(null, "read", "{\"session\":\"hm-a\"}").endsWith(" · unchanged"), "same text");
        assertEquals(refused, jmux.unsupportedCalls.get(), "remembered: never asked again");
    }

    @Test
    void sendOnAnOldDaemonFallsBackToSendKeysAndRefusesMultiLineText() {
        jmux.old = true;
        shell("hm-a").show("$ ");
        assertEquals("sent to hm-a", ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"echo hi\"}"));
        ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"draft\",\"enter\":false}");
        assertEquals(List.of(new FakeJmux.SendKeys("hm-a", "echo hi", true), new FakeJmux.SendKeys("hm-a", "draft", false)),
                jmux.sentKeys);
        assertTrue(sleeps.isEmpty(), "the server pauses before Enter itself");

        String refused = error("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"one\\ntwo\"}");
        assertTrue(refused.contains("predates pasting") && refused.contains("one line at a time"), refused);
        assertTrue(refused.contains("(pid 100)"), refused);
        assertEquals(2, jmux.sentKeys.size(), "nothing typed");
        assertTrue(jmux.inputs.isEmpty());
    }

    @Test
    void keysOnAnOldDaemonExplainsWhyAndWhenItGoesAway() {
        jmux.old = true;
        jmux.pid = 91806;
        shell("hm-a").show("$ ");
        String expected = "keys needs a newer jmux daemon; the running one (pid 91806) predates it. It is replaced "
                + "automatically once every jmux session has ended and a new one starts.";
        assertEquals(expected, error(null, "keys", "{\"session\":\"hm-a\",\"keys\":[\"Enter\"]}"));
        assertEquals(expected, error(null, "keys", "{\"session\":\"hm-a\",\"keys\":[\"Enter\"]}"));
        assertTrue(jmux.sentKeys.isEmpty());
    }

    @Test
    void aNewDaemonIsUsedFullyOnceThePidChanges() {
        jmux.old = true;
        shell("hm-a").show("$ ");
        ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"x\"}");
        assertEquals(1, jmux.sentKeys.size());

        jmux.old = false;
        ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"x\"}");
        assertEquals(2, jmux.sentKeys.size(), "same pid: still taken for the old daemon");

        jmux.pid = 200;
        ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"x\"}");
        assertEquals(2, jmux.sentKeys.size());
        assertEquals(List.of(new FakeJmux.Input("hm-a", "x", true, null),
                new FakeJmux.Input("hm-a", null, false, List.of("Enter"))), inputsTo("hm-a"));
        assertFalse(ok(null, "read", "{\"session\":\"hm-a\"}").contains(SessionController.OLD_DAEMON_NOTE));
    }

    @Test
    void anOldDaemonGetsSpawnPromptsAndNotificationsThroughSendKeys() {
        jmux.old = true; // no labels either: the registry names the caller's harness
        agent("hm-me", CLAUDE_IDLE);
        register("hm-me", HarnessType.CLAUDE_CODE, null);
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"make\"}");
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"one\\ntwo\"}");
        ticks(14); // no quiet probe on an old daemon: the caller needs its own 5 s look
        assertEquals(List.of(new FakeJmux.SendKeys("hm-new-1", "make", true)),
                jmux.sentKeys.stream().filter(k -> k.session().equals("hm-new-1")).toList());
        assertTrue(jmux.sentKeys.stream().noneMatch(k -> k.session().equals("hm-new-2")));
        List<FakeJmux.SendKeys> toMe = jmux.sentKeys.stream().filter(k -> k.session().equals("hm-me")).toList();
        assertEquals(1, toMe.size(), "the multi-line prompt is refused and its requester told: " + toMe);
        assertTrue(toMe.getFirst().text().startsWith("[harness-manager] hm-new-2 could not be given its prompt: "
                + "the running jmux daemon"), toMe.getFirst().text());
        assertTrue(toMe.getFirst().enter());
        assertFalse(controller.hasPending());
        assertTrue(jmux.inputs.isEmpty());
    }

    @Test
    void theMonitorStopsAskingAnOldDaemonForAWatchAndResumesWithANewOne() throws Exception {
        jmux.old = true;
        controller = controller(new SessionController.Timings(Duration.ofMillis(10), Duration.ZERO,
                Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofMinutes(30), Duration.ofSeconds(120),
                300, 3000, 500, Duration.ofMillis(5), Duration.ofMillis(20)));
        McpService service = new McpService(controller, "test");
        service.start(0, "token");
        try {
            awaitTrue(() -> jmux.pings.get() >= 5); // ticking along, checking the daemon
            assertEquals(1, jmux.watchAttempts.get(), "asked once per daemon");
            assertTrue(jmux.watches.isEmpty());

            jmux.old = false;
            jmux.pid = 200;
            awaitTrue(() -> !jmux.watches.isEmpty());
        } finally {
            service.stop();
        }
    }

    // ------------------------------------------------------------------ delivery safety

    @Test
    void notifyRefusesAPlainShellCaller() {
        shell("hm-me").show("$ ");
        shell("hm-a").show("building");
        String refused = error("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        assertTrue(refused.contains("plain shell session") && refused.contains("run as a command"), refused);
        assertFalse(controller.hasPending());
    }

    @Test
    void aNotificationIsHeldWhileTheCallerWaitsForAnAnswerOrSitsAtAShell() {
        FakeJmux.FakeSession me = agent("hm-me", CLAUDE_APPROVAL);
        shell("hm-a").show("building");
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        jmux.kill("hm-a");
        ticks(10);
        assertTrue(inputsTo("hm-me").isEmpty(), "never typed (and Enter pressed) into a permission dialog");

        me.foreground = "zsh"; // claude exited: a shell prompt would run the line
        me.show("$ ");
        ticks(10);
        assertTrue(inputsTo("hm-me").isEmpty(), "never typed into a bare shell");

        me.foreground = "claude";
        me.show(CLAUDE_IDLE);
        ticks(7);
        assertEquals("[harness-manager] hm-a exited (EXITED)" + NOTICE_TAIL, inputsTo("hm-me").getFirst().text());
    }

    @Test
    void finishedAfterASpawnPromptWaitsUntilThePromptWasTypedAndWorkedOn() {
        agent("hm-me", CLAUDE_IDLE);
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"make\"}");
        assertEquals("will notify hm-me when hm-new-1 finished",
                ok("hm-me", "notify", "{\"session\":\"hm-new-1\",\"on\":\"finished\"}"),
                "a session about to be given its prompt counts as working");
        ok("hm-me", "notify", "{\"session\":\"hm-new-1\",\"on\":\"needs-input\"}");

        ticks(7); // the shell settles at its prompt: the prompt goes in
        assertEquals(2, inputsTo("hm-new-1").size());
        ticks(10); // typed, but nothing started yet
        assertTrue(inputsTo("hm-me").isEmpty(), "not finished before it even started");

        jmux.session("hm-new-1").show("$ make\nbuilding…").foreground = "make";
        ticks(1);
        assertTrue(inputsTo("hm-me").isEmpty());
        jmux.session("hm-new-1").show("$ make\nbuilt\n$ ").foreground = "zsh";
        ticks(14); // the caller is first looked at once there is something to deliver
        assertEquals("[harness-manager] hm-new-1 finished (IDLE)" + NOTICE_TAIL, inputsTo("hm-me").getFirst().text());
        assertTrue(controller.hasPending(), "needs-input is still armed");
    }

    @Test
    void aTransientCaptureErrorIsNotTakenForAnExit() {
        jmux.quietProbe = true; // the caller is long idle
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("building");
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"ls\"}");
        jmux.captureFailures.put("hm-a", 3);
        jmux.captureFailures.put("hm-new-1", 3);
        ticks(3);
        assertTrue(inputsTo("hm-me").isEmpty(), "no spurious 'exited', no 'exited before its prompt'");
        ticks(7);
        assertEquals(2, inputsTo("hm-new-1").size(), "the prompt still goes in");
        assertTrue(inputsTo("hm-me").isEmpty());

        jmux.kill("hm-a");
        jmux.captureFailures.put("hm-me", 2);
        ticks(2);
        assertTrue(inputsTo("hm-me").isEmpty(), "held, not dropped, while the caller cannot be captured");
        ticks(1);
        assertEquals("[harness-manager] hm-a exited (EXITED)" + NOTICE_TAIL, inputsTo("hm-me").getFirst().text());
    }

    @Test
    void notifyRegistersNothingWhenAnySessionIsBadAndRepeatingIsIdempotent() {
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("building");
        assertTrue(error("hm-me", "notify", "{\"sessions\":[\"hm-a\",\"hm-typo\"],\"on\":\"exited\"}")
                .contains("no such session: hm-typo"));
        assertTrue(error("hm-me", "notify", "{\"sessions\":[\"hm-a\",\"hm-me\"],\"on\":\"exited\"}")
                .contains("not your own"));
        assertFalse(controller.hasPending(), "nothing registered by the failed calls");

        assertEquals("will notify hm-me when hm-a exited",
                ok("hm-me", "notify", "{\"sessions\":[\"hm-a\",\"hm-a\"],\"on\":\"exited\"}"));
        assertEquals("already registered: will notify hm-me when hm-a exited",
                ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}"));
        jmux.kill("hm-a");
        ticks(7);
        assertEquals("[harness-manager] hm-a exited (EXITED)" + NOTICE_TAIL, inputsTo("hm-me").getFirst().text(),
                "one notice, not two");
    }

    @Test
    void aFailedEnterIsRetriedAloneWithoutPastingAgain() {
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("building");
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        jmux.kill("hm-a");
        java.util.concurrent.atomic.AtomicInteger enterFailures = new java.util.concurrent.atomic.AtomicInteger(1);
        jmux.failInput = i -> i.keys() != null && enterFailures.getAndDecrement() > 0;
        ticks(7);
        assertEquals(List.of(new FakeJmux.Input("hm-me", "[harness-manager] hm-a exited (EXITED)" + NOTICE_TAIL, true,
                        null), new FakeJmux.Input("hm-me", null, false, List.of("Enter"))), inputsTo("hm-me"),
                "pasted once, Enter retried");
        assertFalse(controller.hasPending());
    }

    @Test
    void aSpawnPromptsFailedEnterIsRetriedAlone() {
        agent("hm-me", CLAUDE_IDLE);
        java.util.concurrent.atomic.AtomicInteger enterFailures = new java.util.concurrent.atomic.AtomicInteger(1);
        jmux.failInput = i -> i.keys() != null && enterFailures.getAndDecrement() > 0;
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"ls\"}");
        ticks(15);
        assertEquals(List.of(new FakeJmux.Input("hm-new-1", "ls", true, null),
                new FakeJmux.Input("hm-new-1", null, false, List.of("Enter"))), inputsTo("hm-new-1"));
        assertFalse(controller.hasPending());
    }

    @Test
    void aDeliveryInFlightIsNotStartedTwice() {
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        controller = controller(SessionController.Timings.DEFAULT, queued::add);
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("building");
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"ls\"}");
        jmux.kill("hm-a");
        ticks(10);
        assertEquals(2, queued.size(), "one notification delivery and one prompt delivery: " + queued.size());
        queued.forEach(Runnable::run);
        assertEquals(2, inputsTo("hm-me").size());
        assertEquals(2, inputsTo("hm-new-1").size());
        assertFalse(controller.hasPending());
    }

    @Test
    void spawnRefusesADisplayNameAlreadyInUse() {
        shell("hm-a").labels.put("display", "Helper");
        jmux.add("hm-b").show("x");
        register("hm-b", HarnessType.SHELL, "Backend");
        assertEquals("name already in use by hm-a; choose another",
                error("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"helper\"}"));
        assertEquals("name already in use by hm-b; choose another",
                error("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"Backend\"}"));
        assertTrue(spawner.requests.isEmpty());
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"Fresh\"}");
    }

    @Test
    void anyChangeIgnoresTheComposerButReadShowsIt() {
        agent("hm-me", CLAUDE_IDLE);
        FakeJmux.FakeSession c = agent("hm-c", "Answer: 42\n❯ \n? for shortcuts\n");
        ok("hm-me", "notify", "{\"session\":\"hm-c\",\"on\":\"any-change\"}");
        c.show("Answer: 42\n❯ half-typed\n? for shortcuts\n");
        ticks(7);
        assertTrue(inputsTo("hm-me").isEmpty(), "composer-only changes do not fire any-change");
        assertTrue(controller.hasPending());
    }

    // ------------------------------------------------------------------ unreachable daemon

    @Test
    void anUnreachableDaemonIsNeitherAnExitNorAMissedPrompt() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("building");
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"ls\"}");

        jmux.down = true; // list comes back empty, ping fails
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(1)); // past the prompt deadline even
            controller.tick();
        }
        jmux.down = false;
        assertTrue(inputsTo("hm-me").isEmpty(), "no 'exited', no 'did not reach its prompt'");
        assertTrue(controller.hasPending());
        assertEquals(SessionController.DAEMON_UNREACHABLE.substring(0, 20),
                errorWhileDown("read", "{\"session\":\"hm-a\"}").substring(0, 20));

        // the daemon dies between the list and the captures: still unknown, not gone
        jmux.afterList = () -> jmux.down = true;
        controller.tick();
        jmux.afterList = () -> { };
        jmux.down = false;
        assertTrue(inputsTo("hm-me").isEmpty());

        // once it answers again, a real exit still fires
        jmux.kill("hm-a");
        ticks(7);
        assertTrue(inputsTo("hm-me").getFirst().text().startsWith("[harness-manager] hm-a exited (EXITED)"),
                inputsTo("hm-me").toString());
    }

    private String errorWhileDown(String tool, String json) {
        jmux.down = true;
        try {
            return error("hm-me", tool, json);
        } finally {
            jmux.down = false;
        }
    }

    // ------------------------------------------------------------------ spawn prompt and notify

    /** Spawns a claude-code session with {@code prompt} and has it reach its idle prompt. */
    private FakeJmux.FakeSession spawnClaudeWithPrompt(String prompt) {
        spawner.harnessLabel = "claude-code";
        ok("hm-me", "spawn", "{\"harness\":\"claude-code\",\"project\":\"" + dir + "\",\"prompt\":"
                + JSON.valueToTree(prompt) + "}");
        FakeJmux.FakeSession s = jmux.session("hm-new-1");
        s.foreground = "claude";
        s.show(CLAUDE_IDLE);
        return s;
    }

    @Test
    void finishedAndNeedsInputAfterASpawnPromptFireWhenTheSessionAsksAQuestion() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        FakeJmux.FakeSession s = spawnClaudeWithPrompt("do it");
        ok("hm-me", "notify", "{\"session\":\"hm-new-1\",\"on\":\"finished\"}");
        ok("hm-me", "notify", "{\"session\":\"hm-new-1\",\"on\":\"needs-input\"}");
        ticks(7);
        assertEquals(2, inputsTo("hm-new-1").size(), "the prompt went in");

        jmux.quietProbe = false;
        s.show(CLAUDE_APPROVAL);
        ticks(30);
        String delivered = inputsTo("hm-me").getFirst().text();
        assertTrue(delivered.contains("hm-new-1 finished (WAITING_FOR_INPUT)"), delivered);
        assertTrue(delivered.contains("hm-new-1 needs input (WAITING_FOR_INPUT)"), delivered);
        assertFalse(controller.hasPending());
    }

    @Test
    void finishedAfterASpawnPromptFiresWhenTheHarnessFallsBackToAShell() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        FakeJmux.FakeSession s = spawnClaudeWithPrompt("do it");
        ok("hm-me", "notify", "{\"session\":\"hm-new-1\",\"on\":\"finished\"}");
        ticks(7);
        assertEquals(2, inputsTo("hm-new-1").size());

        s.foreground = "zsh"; // claude quit straight away
        s.text = "$ ";
        ticks(10);
        assertTrue(inputsTo("hm-me").getFirst().text().startsWith("[harness-manager] hm-new-1 finished ("),
                inputsTo("hm-me").toString());
        assertFalse(controller.hasPending());
    }

    @Test
    void aRegistrationWaitingForASpawnPromptIsDroppedAfterThirtyMinutes() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        spawnClaudeWithPrompt("do it");
        ok("hm-me", "notify", "{\"session\":\"hm-new-1\",\"on\":\"finished\"}");
        ticks(7);
        assertEquals(2, inputsTo("hm-new-1").size());
        // it never reacts: idle at its prompt, screen unchanged
        for (int i = 0; i < 29; i++) {
            clock.advance(Duration.ofMinutes(1));
            controller.tick();
        }
        assertTrue(controller.hasPending(), "still armed before the TTL");
        clock.advance(Duration.ofMinutes(2));
        controller.tick();
        assertFalse(controller.hasPending());
        assertTrue(inputsTo("hm-me").isEmpty());
    }

    @Test
    void spawnStripsTheDisplayNameAndChecksItStripped() {
        shell("hm-a").labels.put("display", "Helper");
        assertEquals("name already in use by hm-a; choose another",
                error("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"  helper \"}"));
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"  Fresh  \"}");
        assertEquals(Optional.of("Fresh"), spawner.requests.getFirst().displayName());
    }

    @Test
    void concurrentSpawnsCannotTakeTheSameDisplayName() throws Exception {
        java.util.concurrent.CountDownLatch inSpawn = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        spawner.onSpawn = () -> {
            inSpawn.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ToolResult> first = pool.submit(() -> call("hm-me", "spawn",
                    "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"Twin\"}"));
            assertTrue(inSpawn.await(10, java.util.concurrent.TimeUnit.SECONDS));
            spawner.onSpawn = () -> { };
            String refused = error("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir
                    + "\",\"name\":\" twin\"}");
            assertTrue(refused.contains("already in use"), refused);
            ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"Other\"}");
            release.countDown();
            assertFalse(first.get(10, java.util.concurrent.TimeUnit.SECONDS).isError());
            assertEquals(2, spawner.requests.size());
            // released once created: the name is now taken by the live session's label
            jmux.session("hm-new-1").labels.put("display", "Twin");
            jmux.session("hm-new-2").labels.put("display", "Other");
            String taken = error("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir
                    + "\",\"name\":\"twin\"}");
            assertTrue(taken.startsWith("name already in use by hm-"), taken);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedSpawnReleasesItsDisplayName() {
        spawner.onSpawn = () -> {
            throw new IllegalStateException("boom");
        };
        assertTrue(error("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"X\"}")
                .contains("boom"));
        spawner.onSpawn = () -> { };
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"name\":\"X\"}");
    }

    // ------------------------------------------------------------------ an owed Enter

    @Test
    void aPastedSpawnPromptsEnterIsRetriedPastTheDeadlineAndWhateverTheSessionLooksLike() {
        agent("hm-me", CLAUDE_IDLE);
        java.util.concurrent.atomic.AtomicInteger enterFailures = new java.util.concurrent.atomic.AtomicInteger(3);
        jmux.failInput = i -> i.session().equals("hm-new-1") && i.keys() != null
                && enterFailures.getAndDecrement() > 0;
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"ls\"}");
        ticks(7);
        assertEquals(List.of(new FakeJmux.Input("hm-new-1", "ls", true, null)), inputsTo("hm-new-1"));
        // now busy (not ready), and the 120 s deadline passes: the Enter is still owed
        jmux.session("hm-new-1").show("busy").foreground = "make";
        clock.advance(Duration.ofSeconds(200));
        controller.tick();
        controller.tick();
        controller.tick();
        assertEquals(List.of(new FakeJmux.Input("hm-new-1", "ls", true, null),
                new FakeJmux.Input("hm-new-1", null, false, List.of("Enter"))), inputsTo("hm-new-1"));
        assertFalse(controller.hasPending(), "no 'not sent' notice either");
    }

    @Test
    void anEnterThatKeepsFailingIsReportedAsPastedButNotSubmitted() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        jmux.failInput = i -> i.session().equals("hm-new-1") && i.keys() != null;
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"ls\"}");
        ticks(7 + SessionController.MAX_ENTER_ATTEMPTS + 7);
        assertEquals(List.of(new FakeJmux.Input("hm-new-1", "ls", true, null)), inputsTo("hm-new-1"));
        String notice = inputsTo("hm-me").getFirst().text();
        assertTrue(notice.startsWith("[harness-manager] hm-new-1 had its prompt pasted but not submitted"), notice);
    }

    @Test
    void aPastedNotificationsEnterGoesInEvenOnceTheCallerIsBusyAndPastTheTtl() {
        FakeJmux.FakeSession me = agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("building");
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        jmux.kill("hm-a");
        java.util.concurrent.atomic.AtomicBoolean enterFails = new java.util.concurrent.atomic.AtomicBoolean(true);
        jmux.failInput = i -> i.keys() != null && enterFails.get();
        ticks(7);
        assertEquals(1, inputsTo("hm-me").size(), "pasted, Enter failed");

        me.show(CLAUDE_APPROVAL); // the caller is now asking something
        clock.advance(Duration.ofMinutes(40));
        enterFails.set(false);
        controller.tick();
        assertEquals(new FakeJmux.Input("hm-me", null, false, List.of("Enter")), inputsTo("hm-me").getLast());
        assertFalse(controller.hasPending());
    }

    @Test
    void aNotificationWhoseEnterKeepsFailingIsDroppedEventually() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        shell("hm-a").show("building");
        ok("hm-me", "notify", "{\"session\":\"hm-a\",\"on\":\"exited\"}");
        jmux.kill("hm-a");
        jmux.failInput = i -> i.keys() != null;
        ticks(SessionController.MAX_ENTER_ATTEMPTS + 2);
        assertFalse(controller.hasPending());
        assertEquals(1, inputsTo("hm-me").size(), "pasted once, never pasted again");
    }

    // ------------------------------------------------------------------ multi-line pastes

    @Test
    void multiLineSendNeedsBracketedPasteAndSaysSoWhenTheSessionLacksIt() {
        FakeJmux.FakeSession s = shell("hm-a").show("$ ");
        s.bracketedPaste = false;
        assertEquals(SessionController.MULTI_LINE_REFUSED,
                error("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"one\\ntwo\"}"));
        assertTrue(inputsTo("hm-a").isEmpty(), "nothing written, Enter not pressed");
        assertEquals(1, jmux.bracketedRequired.size());

        assertEquals("sent to hm-a", ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"one\"}"));
        assertEquals(1, jmux.bracketedRequired.size(), "a single line needs no bracketed paste");
        assertEquals(2, inputsTo("hm-a").size());

        s.bracketedPaste = true;
        assertEquals("sent to hm-a", ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"one\\ntwo\"}"));
        assertEquals(new FakeJmux.Input("hm-a", "one\ntwo", true, null), inputsTo("hm-a").get(2));
    }

    @Test
    void controlCharactersOnOneLineAlsoNeedBracketedPaste() {
        FakeJmux.FakeSession s = shell("hm-a").show("$ ");
        s.bracketedPaste = false;
        assertEquals(SessionController.MULTI_LINE_REFUSED,
                error("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"see \\u001b[31merror\"}"));
        assertEquals(SessionController.MULTI_LINE_REFUSED,
                error("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"stop\\u0003\"}"));
        assertTrue(inputsTo("hm-a").isEmpty(), "nothing written");
        assertEquals("sent to hm-a", ok("hm-me", "send", "{\"session\":\"hm-a\",\"text\":\"a\\tb\"}"));
        assertEquals(2, jmux.bracketedRequired.size(), "a tab is text, not a key");
    }

    @Test
    void aMultiLineSpawnPromptTheHarnessCannotTakeIsNotTypedAndTheRequesterIsTold() {
        jmux.quietProbe = true;
        agent("hm-me", CLAUDE_IDLE);
        spawner.onSpawn = () -> { };
        ok("hm-me", "spawn", "{\"harness\":\"shell\",\"project\":\"" + dir + "\",\"prompt\":\"one\\ntwo\"}");
        jmux.session("hm-new-1").bracketedPaste = false;
        ticks(14);
        assertTrue(inputsTo("hm-new-1").isEmpty());
        String notice = inputsTo("hm-me").getFirst().text();
        assertTrue(notice.startsWith("[harness-manager] hm-new-1 could not be given its prompt: "
                + SessionController.MULTI_LINE_REFUSED), notice);
        assertFalse(controller.hasPending());
    }

    // ------------------------------------------------------------------ monitor

    @Test
    void monitorReconnectsTheWatchWithBackoffAndStopsWithTheMcpService() throws Exception {
        jmux.watchFailures = 2;
        controller = controller(new SessionController.Timings(Duration.ofMillis(10), Duration.ZERO,
                Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofMinutes(30), Duration.ofSeconds(120),
                300, 3000, 500, Duration.ofMillis(5), Duration.ofMillis(20)));
        McpService service = new McpService(controller, "test");
        service.start(0, "token");
        try {
            assertTrue(controller.monitorRunning());
            awaitTrue(() -> !jmux.watches.isEmpty());
            assertEquals(3, jmux.watchAttempts.get());

            // the daemon restarts: the stream ends and is opened again
            jmux.watches.getFirst().close();
            awaitTrue(() -> jmux.watches.size() == 2);
        } finally {
            service.stop();
        }
        assertFalse(controller.monitorRunning());
        assertFalse(jmux.watches.getLast().isOpen(), "stopping closes the stream");
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(5);
        }
    }

    // ------------------------------------------------------------------ fakes

    private final class FakeSpawner implements SessionSpawner {
        final List<SpawnRequest> requests = new CopyOnWriteArrayList<>();
        final List<String> killed = new CopyOnWriteArrayList<>();
        String harnessLabel;
        /** Runs at the start of every spawn, e.g. to hold one in flight. */
        volatile Runnable onSpawn = () -> { };

        @Override
        public String spawn(SpawnRequest request) {
            onSpawn.run();
            requests.add(request);
            String name = "hm-new-" + requests.size();
            FakeJmux.FakeSession s = jmux.add(name);
            s.labels.put("harness", harnessLabel != null ? harnessLabel : request.harness().id());
            s.show("$ ");
            return name;
        }

        @Override
        public void kill(String session) {
            killed.add(session);
            jmux.kill(session);
        }
    }
}
