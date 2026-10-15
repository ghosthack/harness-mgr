package io.github.ghosthack.harnessmanager.registry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The session registry: tmux session name -> {@link SessionEntry}, backed by a JSON file
 * at {@code ~/.harness-manager/sessions.json} (path overridable, see {@link RegistryPaths}).
 * Loaded once on startup; every mutating call re-saves immediately, so the file on disk is
 * never more than one mutation stale.
 */
public final class SessionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Jackson's default numeric-timestamp encoding round-trips Instant through a
            // double, which can't hold nanosecond precision at today's epoch magnitude and
            // silently rounds it — harmless on a clock coarse enough to always land on a
            // millisecond boundary, but Windows' higher-resolution clock exposed it as an
            // entry no longer equal to itself after a save/load. ISO-8601 strings round-trip
            // exactly regardless of clock resolution.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final Map<String, SessionEntry> entries;

    /**
     * Raw {@code sessions[]} elements this build could not bind (most plausibly an entry
     * written by a newer build with a harness type this one doesn't know). Preserved
     * verbatim and written back on every {@link #save()}, so a mutation here never
     * destroys another build's entries — resume ids are not recoverable once lost.
     * A {@link #put} or {@link #remove} under the same tmux-session key supersedes the
     * preserved node (the user's explicit action wins over a stale foreign entry).
     */
    private final List<JsonNode> unknownEntries;

    /**
     * The raw node each bound entry was loaded from, keyed by tmux session. On save,
     * fields a newer build wrote that this build's {@link SessionEntry} doesn't know
     * (silently dropped by binding — {@code FAIL_ON_UNKNOWN_PROPERTIES} is off) are
     * merged back from here, so rewriting an entry doesn't strip them.
     */
    private final Map<String, JsonNode> originalNodes;

    /**
     * False when the file on disk couldn't be read <em>and</em> couldn't be backed up —
     * the only copy of its contents is still that file, so this run keeps its registry
     * in memory and never overwrites it. Resume ids are not recoverable once lost.
     */
    private final boolean persistent;

    private SessionRegistry(Path file, Map<String, SessionEntry> entries, List<JsonNode> unknownEntries,
                            Map<String, JsonNode> originalNodes) {
        this(file, entries, unknownEntries, originalNodes, true);
    }

    private SessionRegistry(Path file, Map<String, SessionEntry> entries, List<JsonNode> unknownEntries,
                            Map<String, JsonNode> originalNodes, boolean persistent) {
        this.file = file;
        this.entries = entries;
        this.unknownEntries = unknownEntries;
        this.originalNodes = originalNodes;
        this.persistent = persistent;
    }

    /**
     * Loads the registry from {@code file}. A missing file is a fresh install (empty
     * registry, no warning). Tolerance is per-entry, not per-file: an element that fails
     * to bind is kept as raw JSON (see {@link #unknownEntries}) rather than discarded,
     * and a null element or missing/null {@code sessions} list is simply empty. Only a
     * file that isn't parseable JSON at all is treated as corrupt — and even then it is
     * backed up beside itself before the empty registry's first save can overwrite it,
     * because whatever wrote garbage there, the user still needs a working session
     * manager <em>and</em> a shot at recovering the old contents.
     */
    public static SessionRegistry load(Path file) {
        if (!Files.exists(file)) {
            return empty(file);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(file.toFile());
        } catch (Exception e) {
            // Also reached on an I/O error (permissions, EIO), where the backup copy is
            // likely to fail too — then the file must survive this run untouched.
            return emptyReplacing(file, e.getMessage());
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            // an empty file — nothing to preserve
            return empty(file);
        }
        if (!root.isObject()) {
            return emptyReplacing(file, "top-level value is not an object");
        }

        Map<String, SessionEntry> byName = new LinkedHashMap<>();
        List<JsonNode> unknown = new ArrayList<>();
        Map<String, JsonNode> originals = new LinkedHashMap<>();
        JsonNode sessions = root.get("sessions");
        if (sessions == null || sessions.isNull()) {
            // "{}" or {"sessions": null} — a valid, empty registry
            return new SessionRegistry(file, byName, unknown, originals);
        }
        if (!sessions.isArray()) {
            return emptyReplacing(file, "\"sessions\" is not an array");
        }
        for (JsonNode node : sessions) {
            if (node == null || node.isNull()) {
                LOG.warn("session registry at {} contains a null entry — skipping it", file);
                continue;
            }
            SessionEntry entry;
            try {
                entry = MAPPER.treeToValue(node, SessionEntry.class);
            } catch (Exception e) {
                LOG.warn("session registry at {} has an entry this build can't read ({}) — keeping it"
                        + " as-is; it will be preserved unchanged on save", file, e.getMessage());
                unknown.add(node);
                continue;
            }
            if (entry == null || entry.tmuxSession() == null) {
                LOG.warn("session registry at {} has an entry with no tmuxSession — keeping it"
                        + " as-is; it will be preserved unchanged on save", file);
                unknown.add(node);
                continue;
            }
            byName.put(entry.tmuxSession(), entry);
            originals.put(entry.tmuxSession(), node);
        }
        return new SessionRegistry(file, byName, unknown, originals);
    }

    private static SessionRegistry empty(Path file) {
        return new SessionRegistry(file, new LinkedHashMap<>(), new ArrayList<>(), new LinkedHashMap<>());
    }

    /** An empty registry in place of an unusable file: saved over it only once the
     * original is safely backed up, otherwise kept in memory for this run. */
    private static SessionRegistry emptyReplacing(Path file, String why) {
        boolean backedUp = backUpCorrupt(file, why);
        return new SessionRegistry(file, new LinkedHashMap<>(), new ArrayList<>(), new LinkedHashMap<>(),
                backedUp);
    }

    public static SessionRegistry loadDefault() {
        return load(RegistryPaths.resolve());
    }

    public synchronized List<SessionEntry> sessions() {
        return List.copyOf(entries.values());
    }

    public synchronized Optional<SessionEntry> find(String tmuxSession) {
        return Optional.ofNullable(entries.get(tmuxSession));
    }

    /** Adds a new entry, or overwrites the existing one for that tmux session. Also
     * supersedes any preserved unknown entry under the same key — otherwise the file
     * would carry duplicate keys and a build that can bind both would resolve them by
     * array order, letting the stale foreign node win. */
    public synchronized void put(SessionEntry entry) {
        entries.put(entry.tmuxSession(), entry);
        unknownEntries.removeIf(node -> entry.tmuxSession().equals(unknownKeyOf(node)));
        save();
    }

    public synchronized void remove(String tmuxSession) {
        entries.remove(tmuxSession);
        originalNodes.remove(tmuxSession);
        unknownEntries.removeIf(node -> tmuxSession.equals(unknownKeyOf(node)));
        save();
    }

    /**
     * Persists a conversation id/name recovered from a harness's graceful-exit receipt.
     * This is atomic with respect to other registry mutations, so a concurrent rename
     * cannot be overwritten by a stale {@link SessionEntry} snapshot from close.
     */
    public synchronized void recordHarnessSessionId(String tmuxSession, String harnessSessionId) {
        SessionEntry current = entries.get(tmuxSession);
        if (current == null || harnessSessionId == null || harnessSessionId.isBlank()
                || harnessSessionId.strip().equals(current.harnessSessionId())) {
            return;
        }
        entries.put(tmuxSession, current.withHarnessSessionId(harnessSessionId));
        save();
    }

    private static String unknownKeyOf(JsonNode node) {
        JsonNode key = node.get("tmuxSession");
        return key != null && key.isTextual() ? key.asText() : null;
    }

    private void save() {
        if (!persistent) {
            LOG.warn("not saving session registry to {}: its unreadable original could not be"
                    + " backed up, so this run's changes are kept in memory only", file);
            return;
        }
        Path tmp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = Files.createTempFile(parent, "sessions-", ".json.tmp");
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode sessions = root.putArray("sessions");
            for (SessionEntry entry : entries.values()) {
                JsonNode serialized = MAPPER.valueToTree(entry);
                // fields a newer build wrote that binding dropped are merged back in
                JsonNode original = originalNodes.get(entry.tmuxSession());
                if (serialized instanceof ObjectNode out && original instanceof ObjectNode source) {
                    source.fieldNames().forEachRemaining(field -> {
                        if (!out.has(field)) {
                            out.set(field, source.get(field));
                        }
                    });
                }
                sessions.add(serialized);
            }
            for (JsonNode node : unknownEntries) {
                // put()/remove() already supersede same-key unknowns; this guards a
                // foreign-written file that carried the duplicate from the start
                String key = unknownKeyOf(node);
                if (key == null || !entries.containsKey(key)) {
                    sessions.add(node);
                }
            }
            MAPPER.writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // A failed save must not crash a UI mutation the user already sees reflected in the
            // sidebar; the in-memory registry stays authoritative for this run either way.
            LOG.error("failed to save session registry to {}", file, e);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanup) {
                    LOG.warn("failed to clean up temp file {}", tmp, cleanup);
                }
            }
        }
    }

    /** Copies an unparseable registry file aside so the next save can't destroy it.
     * Returns whether the copy was made. */
    private static boolean backUpCorrupt(Path file, String why) {
        Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            LOG.warn("session registry at {} is unreadable ({}) — starting with an empty registry;"
                    + " the original was backed up to {}", file, why, backup);
            return true;
        } catch (IOException e) {
            LOG.error("session registry at {} is unreadable ({}) and could not be backed up ({})"
                    + " — starting with an empty registry that will not be saved this run,"
                    + " so the original is left intact", file, why, e.getMessage());
            return false;
        }
    }
}
