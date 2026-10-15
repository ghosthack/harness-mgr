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

    /** Returns a copy with edited label/destination/flag — same {@code id}. */
    public Machine withDetails(String label, String destination, boolean needsKeychainUnlock) {
        return new Machine(id, label, destination, needsKeychainUnlock);
    }

    @Override
    public String toString() {
        return label;
    }
}
