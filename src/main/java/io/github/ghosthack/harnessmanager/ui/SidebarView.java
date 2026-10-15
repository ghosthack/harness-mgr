package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.harness.HarnessType;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Sorting and filtering vocabulary for the sidebar session list. */
final class SidebarView {

    enum SortMode {
        SESSION_NAME("session", "Session"),
        DIRECTORY_NAME("folder", "Folder"),
        STATUS("status", "Status");

        private final String key;
        private final String label;

        SortMode(String key, String label) {
            this.key = key;
            this.label = label;
        }

        String key() {
            return key;
        }

        String label() {
            return label;
        }

        static SortMode fromKey(String key) {
            for (SortMode mode : values()) {
                if (mode.key.equalsIgnoreCase(key)) {
                    return mode;
                }
            }
            return SESSION_NAME;
        }
    }

    /** Every state a row can visibly occupy, including rows not covered by polling. */
    enum FilterStatus {
        WAITING_FOR_INPUT("waiting", "Waiting for input"),
        WORKING("working", "Working"),
        IDLE("idle", "Idle"),
        STATUS_PENDING("pending", "Status pending"),
        DEAD("dead", "Dead"),
        UNMANAGED("unmanaged", "Unmanaged");

        private final String key;
        private final String label;

        FilterStatus(String key, String label) {
            this.key = key;
            this.label = label;
        }

        String key() {
            return key;
        }

        String label() {
            return label;
        }
    }

    /** Managed harness identities plus the unknown family of an unmanaged tmux row. */
    enum HarnessFamily {
        CLAUDE_CODE("claude", HarnessType.CLAUDE_CODE, "Claude"),
        CODEX("codex", HarnessType.CODEX, "Codex"),
        CURSOR_AGENT("cursor", HarnessType.CURSOR_AGENT, "Cursor"),
        GROK("grok", HarnessType.GROK, "Grok"),
        OPENCODE("opencode", HarnessType.OPENCODE, "OpenCode"),
        PI("pi", HarnessType.PI, "Pi"),
        MUSE("muse", HarnessType.MUSE, "Muse"),
        SHELL("shell", HarnessType.SHELL, "Shell"),
        UNMANAGED("unmanaged", null, "Unmanaged");

        private final String key;
        private final HarnessType harnessType;
        private final String label;

        HarnessFamily(String key, HarnessType harnessType, String label) {
            this.key = key;
            this.harnessType = harnessType;
            this.label = label;
        }

        String key() {
            return key;
        }

        HarnessType harnessType() {
            return harnessType;
        }

        String label() {
            return label;
        }
    }

    private static final Comparator<String> TEXT_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    static FilterStatus filterStatus(SidebarEntry entry) {
        return switch (entry.status()) {
            case DEAD -> FilterStatus.DEAD;
            case UNMANAGED -> FilterStatus.UNMANAGED;
            case LIVE -> switch (entry.state()) {
                case WORKING -> FilterStatus.WORKING;
                case WAITING_FOR_INPUT -> FilterStatus.WAITING_FOR_INPUT;
                case IDLE -> FilterStatus.IDLE;
                case null -> FilterStatus.STATUS_PENDING;
            };
        };
    }

    static HarnessFamily harnessFamily(SidebarEntry entry) {
        if (entry.harnessType() == null) {
            return HarnessFamily.UNMANAGED;
        }
        return switch (entry.harnessType()) {
            case CLAUDE_CODE -> HarnessFamily.CLAUDE_CODE;
            case CODEX -> HarnessFamily.CODEX;
            case CURSOR_AGENT -> HarnessFamily.CURSOR_AGENT;
            case GROK -> HarnessFamily.GROK;
            case OPENCODE -> HarnessFamily.OPENCODE;
            case PI -> HarnessFamily.PI;
            case MUSE -> HarnessFamily.MUSE;
            case SHELL -> HarnessFamily.SHELL;
        };
    }

    static boolean isVisible(SidebarEntry entry, Set<FilterStatus> statuses,
                             Set<HarnessFamily> harnessFamilies) {
        return statuses.contains(filterStatus(entry))
                && harnessFamilies.contains(harnessFamily(entry));
    }

    static Comparator<SidebarEntry> comparator(SortMode mode) {
        Comparator<SidebarEntry> primary = switch (mode) {
            case SESSION_NAME -> Comparator.comparing(SidebarEntry::displayName, TEXT_ORDER);
            case DIRECTORY_NAME -> Comparator
                    .comparing((SidebarEntry entry) -> entry.sessionEntry() == null)
                    .thenComparing(SidebarView::directoryName, TEXT_ORDER)
                    // Full path outranks the session name so two folders sharing a
                    // basename cannot interleave — the sidebar's folder groups need
                    // each folder's rows to form one contiguous run.
                    .thenComparing(SidebarView::folderGroupKey, TEXT_ORDER);
            // Enum order is intentional: attention first, then active/quiet, then
            // lifecycle states which do not have a live harness classification.
            case STATUS -> Comparator.comparingInt(entry -> filterStatus(entry).ordinal());
        };
        return primary.thenComparing(SidebarEntry::tmuxSession, TEXT_ORDER);
    }

    static Comparator<SidebarEntry> comparator(SortMode mode, boolean descending) {
        Comparator<SidebarEntry> comparator = comparator(mode);
        return descending ? comparator.reversed() : comparator;
    }

    /** Unique managed harness families which currently have at least one live session. */
    static EnumSet<HarnessType> activeHarnesses(List<SidebarEntry> entries) {
        EnumSet<HarnessType> active = EnumSet.noneOf(HarnessType.class);
        for (SidebarEntry entry : entries) {
            if (entry.status() == SidebarEntry.Status.LIVE && entry.harnessType() != null) {
                active.add(entry.harnessType());
            }
        }
        return active;
    }

    /**
     * What makes two adjacent rows part of the same folder group: their full project
     * path, matched exactly, so two same-named folders in different parents stay apart.
     * Empty for a row with no registered path — an unmanaged session belongs to no
     * folder and never joins a group.
     */
    static String folderGroupKey(SidebarEntry entry) {
        if (entry.sessionEntry() == null || entry.sessionEntry().projectPath() == null
                || entry.sessionEntry().projectPath().isBlank()) {
            return "";
        }
        return entry.sessionEntry().projectPath();
    }

    static String directoryName(SidebarEntry entry) {
        if (entry.sessionEntry() == null || entry.sessionEntry().projectPath() == null
                || entry.sessionEntry().projectPath().isBlank()) {
            return "";
        }
        Path fileName = Path.of(entry.sessionEntry().projectPath()).getFileName();
        return fileName == null ? entry.sessionEntry().projectPath() : fileName.toString();
    }

    private SidebarView() {
    }
}
