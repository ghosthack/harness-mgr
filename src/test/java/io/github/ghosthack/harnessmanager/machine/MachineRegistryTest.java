package io.github.ghosthack.harnessmanager.machine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MachineRegistryTest {

    @Test
    void missingFileLoadsEmpty(@TempDir Path dir) {
        MachineRegistry registry = MachineRegistry.load(dir.resolve("machines.json"));
        assertTrue(registry.machines().isEmpty());
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path dir) {
        Path file = dir.resolve("machines.json");
        Machine machine = Machine.create("lan23", "lan23", true);

        MachineRegistry written = MachineRegistry.load(file);
        written.put(machine);

        MachineRegistry reloaded = MachineRegistry.load(file);
        List<Machine> machines = reloaded.machines();
        assertEquals(1, machines.size());
        assertEquals(machine, reloaded.find(machine.id()).orElseThrow());
    }

    @Test
    void putOverwritesExistingEntryForSameId(@TempDir Path dir) {
        MachineRegistry registry = MachineRegistry.load(dir.resolve("machines.json"));
        Machine original = Machine.create("lan23", "lan23", false);
        registry.put(original);
        Machine edited = original.withDetails("lan23-renamed", "user@lan23", true);
        registry.put(edited);

        assertEquals(1, registry.machines().size());
        Machine found = registry.find(original.id()).orElseThrow();
        assertEquals("lan23-renamed", found.label());
        assertEquals("user@lan23", found.destination());
        assertTrue(found.needsKeychainUnlock());
    }

    @Test
    void removeDropsTheEntryAndPersists(@TempDir Path dir) {
        Path file = dir.resolve("machines.json");
        MachineRegistry registry = MachineRegistry.load(file);
        Machine machine = Machine.create("lan23", "lan23", false);
        registry.put(machine);
        registry.remove(machine.id());

        assertTrue(registry.machines().isEmpty());
        assertTrue(MachineRegistry.load(file).machines().isEmpty());
    }

    @Test
    void corruptFileIsToleratedAsEmptyAndBackedUp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("machines.json");
        String garbage = "{ this is not valid json ][";
        Files.writeString(file, garbage);

        MachineRegistry registry = MachineRegistry.load(file);
        assertTrue(registry.machines().isEmpty());

        try (var siblings = Files.list(dir)) {
            Path backup = siblings
                    .filter(p -> p.getFileName().toString().startsWith("machines.json.corrupt-"))
                    .findFirst().orElseThrow();
            assertEquals(garbage, Files.readString(backup));
        }

        // still usable afterward — a mutation overwrites the garbage cleanly
        registry.put(Machine.create("lan23", "lan23", false));
        assertEquals(1, MachineRegistry.load(file).machines().size());
    }

    @Test
    void wellFormedButUnexpectedShapesAreBackedUpBeforeAnOverwrite(@TempDir Path dir) throws Exception {
        for (String content : List.of("[1, 2]", "{\"machines\": {\"id\": \"x\"}}",
                "{\"machines\": [{\"id\": \"ok\", \"label\": \"ok\", \"destination\": \"ok\"}, 42]}")) {
            Path sub = Files.createDirectory(dir.resolve("case" + content.hashCode()));
            Path file = sub.resolve("machines.json");
            Files.writeString(file, content);

            MachineRegistry.load(file).put(Machine.create("lan23", "lan23", false));

            try (var siblings = Files.list(sub)) {
                Path backup = siblings
                        .filter(p -> p.getFileName().toString().startsWith("machines.json.corrupt-"))
                        .findFirst().orElseThrow(() -> new AssertionError("no backup for " + content));
                assertEquals(content, Files.readString(backup));
            }
        }
    }

    @Test
    void editingDetailsNeverChangesId() {
        Machine machine = Machine.create("lan23", "lan23", false);
        Machine edited = machine.withDetails("new label", "new-destination", true);
        assertEquals(machine.id(), edited.id());
    }
}
