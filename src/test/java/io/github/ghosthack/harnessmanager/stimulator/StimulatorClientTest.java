package io.github.ghosthack.harnessmanager.stimulator;

import io.github.ghosthack.harnessmanager.harness.HarnessType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StimulatorClientTest {

    @TempDir
    Path tmp;

    @Test
    void remappedStimulatorMainIsPresentOnTheRuntimeClasspath() throws Exception {
        Class.forName("io.github.ghosthack.harnessstimulator.Main", false,
                StimulatorClientTest.class.getClassLoader());
    }

    @Test
    void runCommandCarriesTheDialogInstructionAsOpeningAndRepeatedStimulus() {
        StimulatorClient client = new StimulatorClient(tmp.resolve("stim.jar"), tmp, tmp.resolve("logs"));
        Path workdir = Path.of("/tmp/project with spaces");
        List<String> command = client.runCommand(new StimulatorClient.StartRequest(
                "hm-app", workdir, HarnessType.CODEX,
                Optional.of("gpt-5.6-sol"), "finish the migration", 12));
        // --dir is workdir.toString(), which renders with the platform's own separator.
        assertEquals(List.of("run", "hm-app", "--dir", workdir.toString(),
                        "--harness", "codex", "--prompt", "finish the migration",
                        "--idle-message", "finish the migration", "--max-stimuli", "12",
                        "--model", "gpt-5.6-sol"),
                command.subList(3, command.size()));
    }

    @Test
    void shellAndNegativeBudgetAreRefusedBeforeStartingAWorker() {
        StimulatorClient client = new StimulatorClient(tmp.resolve("stim.jar"), tmp, tmp);
        assertThrows(IllegalArgumentException.class, () -> client.runCommand(
                new StimulatorClient.StartRequest("x", tmp, HarnessType.SHELL,
                        Optional.empty(), "go", 1)));
        assertThrows(IllegalArgumentException.class, () -> client.runCommand(
                new StimulatorClient.StartRequest("x", tmp, HarnessType.CODEX,
                        Optional.empty(), "go", -1)));
    }

    @Test
    void peekUsesTheHostOwnedEmbeddedView() {
        StimulatorClient client = new StimulatorClient(tmp.resolve("stim.jar"), tmp, tmp);
        List<String> command = client.peekCommand("hm-app");
        assertEquals(List.of("peek", "hm-app", "--embedded"),
                command.subList(command.size() - 3, command.size()));
    }

    @Test
    void nativeLauncherCanBeUsedAsTheSeparateProcessBoundary() {
        StimulatorClient client = new StimulatorClient(
                List.of("/Applications/Harness Manager.app/Contents/MacOS/harness-stimulator"),
                tmp, tmp);
        assertEquals(List.of(
                        "/Applications/Harness Manager.app/Contents/MacOS/harness-stimulator",
                        "peek", "hm-app", "--embedded"),
                client.peekCommand("hm-app"));
    }

    /** The classpath discovery route checks {@code Files.isRegularFile(javaBinary())};
     * without the {@code .exe} it could never succeed on Windows. */
    @Test
    void javaBinaryResolvesTheWindowsLauncherName() {
        assertEquals(Path.of("/jdk", "bin", "java.exe"),
                StimulatorClient.javaBinary("/jdk", "Windows 11"));
        assertEquals(Path.of("/jdk", "bin", "java"),
                StimulatorClient.javaBinary("/jdk", "Mac OS X"));
        assertEquals(Path.of("/jdk", "bin", "java"),
                StimulatorClient.javaBinary("/jdk", "Linux"));
    }

    @Test
    void liveOwnerRequiresAnActuallyLivePid() throws Exception {
        Path lock = tmp.resolve("x.lock");
        Files.writeString(lock, Long.toString(ProcessHandle.current().pid()));
        assertEquals(OptionalLong.of(ProcessHandle.current().pid()), StimulatorClient.liveOwner(lock));
        Files.writeString(lock, "999999999");
        assertEquals(OptionalLong.empty(), StimulatorClient.liveOwner(lock));
    }

    @Test
    void stimuliStatusReadsTheAttachedWorkersLiveSnapshot() throws Exception {
        StimulatorClient client = new StimulatorClient(tmp.resolve("stim.jar"), tmp, tmp);
        assertEquals(Optional.empty(), client.stimuliStatus("x"), "nothing written yet");

        Files.writeString(tmp.resolve("x.status"), "stimuliSent=3\nmaxStimuli=25\n");
        StimulatorClient.StimuliStatus status = client.stimuliStatus("x").orElseThrow();
        assertEquals(3, status.stimuliSent());
        assertEquals(25, status.maxStimuli());
        assertEquals(22, status.remaining());
        assertFalse(status.unlimited());
    }

    @Test
    void zeroMaxStimuliMeansUnlimitedWithNoRemainingCount() throws Exception {
        StimulatorClient client = new StimulatorClient(tmp.resolve("stim.jar"), tmp, tmp);
        Files.writeString(tmp.resolve("y.status"), "stimuliSent=40\nmaxStimuli=0\n");
        StimulatorClient.StimuliStatus status = client.stimuliStatus("y").orElseThrow();
        assertTrue(status.unlimited());
        // nothing to count down from, so the badge counts up instead
        assertEquals(40, status.displayCount());
    }

    @Test
    void boundedBudgetDisplaysRemainingNotSent() {
        assertEquals(22, new StimulatorClient.StimuliStatus(3, 25).displayCount());
    }

    @Test
    void statusFileMissingExpectedKeysIsTreatedAsAbsent() throws Exception {
        StimulatorClient client = new StimulatorClient(tmp.resolve("stim.jar"), tmp, tmp);
        Files.writeString(tmp.resolve("z.status"), "unrelated=1\n");
        assertEquals(Optional.empty(), client.stimuliStatus("z"));
    }

    @Test
    void explicitJarPropertyRemainsACompatibilityDiscoveryRoute() throws Exception {
        Path jar = Files.createFile(tmp.resolve("stimulator.jar"));
        String old = System.getProperty(StimulatorClient.JAR_PROPERTY);
        try {
            System.setProperty(StimulatorClient.JAR_PROPERTY, jar.toString());
            assertEquals(jar.toAbsolutePath().normalize(),
                    StimulatorClient.discover().orElseThrow().jar());
        } finally {
            if (old == null) {
                System.clearProperty(StimulatorClient.JAR_PROPERTY);
            } else {
                System.setProperty(StimulatorClient.JAR_PROPERTY, old);
            }
        }
    }

    @Test
    void explicitLauncherPropertyTakesPriority() throws Exception {
        Path launcher = Files.createFile(tmp.resolve("harness-stimulator"));
        String oldLauncher = System.getProperty(StimulatorClient.LAUNCHER_PROPERTY);
        String oldJar = System.getProperty(StimulatorClient.JAR_PROPERTY);
        try {
            System.setProperty(StimulatorClient.LAUNCHER_PROPERTY, launcher.toString());
            System.clearProperty(StimulatorClient.JAR_PROPERTY);
            assertEquals(List.of(launcher.toAbsolutePath().normalize().toString()),
                    StimulatorClient.discover().orElseThrow().commandPrefix());
        } finally {
            restoreProperty(StimulatorClient.LAUNCHER_PROPERTY, oldLauncher);
            restoreProperty(StimulatorClient.JAR_PROPERTY, oldJar);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
