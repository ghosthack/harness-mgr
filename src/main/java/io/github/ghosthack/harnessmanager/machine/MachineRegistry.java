package io.github.ghosthack.harnessmanager.machine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registered-machines store: id -> {@link Machine}, backed by a JSON file (default
 * {@code ~/.harness-manager/machines.json}, see {@code RegistryPaths.resolveMachines()}).
 * Mirrors {@link io.github.ghosthack.harnessmanager.registry.SessionRegistry}'s
 * load/atomic-save shape, simplified: an entry this build can't bind carries no
 * unrecoverable derived state (unlike a session's {@code harnessSessionId}), so it is
 * dropped with a warning rather than preserved as raw JSON — though the file is backed
 * up first, since registry rows still reference that machine's id. Any file whose
 * contents would be lost is backed up before the first save can overwrite it, and if
 * the backup fails this run never saves over it.
 */
public final class MachineRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(MachineRegistry.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final Map<String, Machine> entries;

    /** False when the file held contents this run would drop but couldn't back up. */
    private final boolean persistent;

    private MachineRegistry(Path file, Map<String, Machine> entries) {
        this(file, entries, true);
    }

    private MachineRegistry(Path file, Map<String, Machine> entries, boolean persistent) {
        this.file = file;
        this.entries = entries;
        this.persistent = persistent;
    }

    public static MachineRegistry load(Path file) {
        if (!Files.exists(file)) {
            return empty(file);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(file.toFile());
        } catch (Exception e) {
            return new MachineRegistry(file, new LinkedHashMap<>(), backUpCorrupt(file, e.getMessage()));
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            // an empty file — nothing to preserve
            return empty(file);
        }
        if (!root.isObject()) {
            return new MachineRegistry(file, new LinkedHashMap<>(),
                    backUpCorrupt(file, "top-level value is not an object"));
        }
        JsonNode machines = root.get("machines");
        Map<String, Machine> byId = new LinkedHashMap<>();
        if (machines != null && !machines.isNull() && !machines.isArray()) {
            return new MachineRegistry(file, byId, backUpCorrupt(file, "\"machines\" is not an array"));
        }
        boolean dropped = false;
        if (machines != null && machines.isArray()) {
            for (JsonNode node : machines) {
                if (node == null || node.isNull()) {
                    continue;
                }
                try {
                    Machine machine = MAPPER.treeToValue(node, Machine.class);
                    if (machine != null && machine.id() != null) {
                        byId.put(machine.id(), machine);
                    } else {
                        LOG.warn("machine registry at {} has an entry with no id — dropping it", file);
                        dropped = true;
                    }
                } catch (Exception e) {
                    LOG.warn("machine registry at {} has an entry this build can't read ({}) —"
                            + " dropping it", file, e.getMessage());
                    dropped = true;
                }
            }
        }
        boolean persistent = !dropped || backUpCorrupt(file, "some entries could not be read");
        return new MachineRegistry(file, byId, persistent);
    }

    private static MachineRegistry empty(Path file) {
        return new MachineRegistry(file, new LinkedHashMap<>());
    }

    public synchronized List<Machine> machines() {
        return List.copyOf(entries.values());
    }

    public synchronized Optional<Machine> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id));
    }

    public synchronized void put(Machine machine) {
        entries.put(machine.id(), machine);
        save();
    }

    public synchronized void remove(String id) {
        entries.remove(id);
        save();
    }

    private void save() {
        if (!persistent) {
            LOG.warn("not saving machine registry to {}: contents this build couldn't read"
                    + " could not be backed up, so this run's changes are kept in memory only", file);
            return;
        }
        Path tmp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = Files.createTempFile(parent, "machines-", ".json.tmp");
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode machines = root.putArray("machines");
            for (Machine machine : entries.values()) {
                machines.add(MAPPER.valueToTree(machine));
            }
            MAPPER.writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("failed to save machine registry to {}", file, e);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanup) {
                    LOG.warn("failed to clean up temp file {}", tmp, cleanup);
                }
            }
        }
    }

    /** Copies the file aside so the next save can't destroy what this build couldn't
     * read. Returns whether the copy was made. */
    private static boolean backUpCorrupt(Path file, String why) {
        Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            LOG.warn("machine registry at {} is not fully readable ({}); the original was backed up"
                    + " to {}", file, why, backup);
            return true;
        } catch (IOException e) {
            LOG.error("machine registry at {} is not fully readable ({}) and could not be backed up"
                    + " ({}) — this run will not save over it", file, why, e.getMessage());
            return false;
        }
    }
}
