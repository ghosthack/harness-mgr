package io.github.ghosthack.harnessmanager.machine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A registered LAN machine reachable over passwordless SSH, usable as the target for a
 * remote harness session. {@code id} is a generated identifier, stable for the machine's
 * lifetime — {@link io.github.ghosthack.harnessmanager.registry.SessionEntry#machineId()}
 * references it, so editing {@code label}/{@code destination} must never change {@code
 * id} or every session already created against this machine would be orphaned.
 */
public record Machine(
        String id,
        String label,
        String destination,
        boolean needsKeychainUnlock) {

    @JsonCreator
    public Machine(
            @JsonProperty("id") String id,
            @JsonProperty("label") String label,
            @JsonProperty("destination") String destination,
            @JsonProperty("needsKeychainUnlock") boolean needsKeychainUnlock) {
        this.id = id;
        this.label = label;
        this.destination = destination;
        this.needsKeychainUnlock = needsKeychainUnlock;
    }

    /** New machine registration; {@code id} is generated here, once, for its lifetime. */
    public static Machine create(String label, String destination, boolean needsKeychainUnlock) {
        return new Machine(java.util.UUID.randomUUID().toString(), label, destination,
                needsKeychainUnlock);
    }

    /**
     * Why {@code destination} can't be handed to ssh, or {@code null} when it can. It
     * becomes a bare ssh argument, so a leading {@code -} would be parsed as an option
     * (e.g. {@code -oProxyCommand=…}), and whitespace or control characters can't be a
     * Host alias or {@code user@host}.
     */
    public static String destinationProblem(String destination) {
        if (destination == null || destination.isBlank()) {
            return "the SSH destination is required";
        }
        if (destination.startsWith("-")) {
            return "the SSH destination must not start with '-'";
        }
        if (destination.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return "the SSH destination must not contain spaces or control characters";
        }
        return null;
    }

    /** Returns a copy with edited label/destination/flag — same {@code id}. */
    public Machine withDetails(String label, String destination, boolean needsKeychainUnlock) {
        return new Machine(id, label, destination, needsKeychainUnlock);
    }

    @Override
    public String toString() {
        return label;
    }
}
