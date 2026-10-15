package io.github.ghosthack.harnessmanager.settings;

import io.github.ghosthack.harnessmanager.registry.RegistryPaths;
import io.github.ghosthack.harnessmanager.registry.SessionEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * App-level settings, read from {@code settings.properties} beside the resolved registry
 * file (so a scratch registry gets isolated settings too — see {@link RegistryPaths}).
 * Read on demand rather than cached, so edits take effect on the next use without a
 * restart. A missing file, missing key, or unreadable file all fall back to defaults.
 */
public final class AppSettings {

    private static final Logger LOG = LoggerFactory.getLogger(AppSettings.class);

    /**
     * Serializes every read and load-mutate-store in this process: without it, two
     * saves racing from different threads each rewrite the file from their own stale
     * load and one change is silently lost. Other processes are covered by the atomic
     * replace in {@link #save} — a reader sees the old file or the new one, never a
     * truncated one.
     */
    private static final Object LOCK = new Object();

    public static final int MIN_TERMINAL_FONT_SIZE = 8;
    public static final int MAX_TERMINAL_FONT_SIZE = 36;
    public static final int DEFAULT_TERMINAL_FONT_SIZE = 14;

    public static final int MIN_STATUS_POLL_INTERVAL_MS = 1000;
    public static final int MAX_STATUS_POLL_INTERVAL_MS = 60_000;
    public static final int DEFAULT_STATUS_POLL_INTERVAL_MS = 4000;

    public static final int MIN_MCP_PORT = 1024;
    public static final int MAX_MCP_PORT = 65_535;
    public static final int DEFAULT_MCP_PORT = 7391;

    /** Random bytes behind a generated {@link #mcpToken}: 32, i.e. 43 url-safe base64 chars. */
    private static final int MCP_TOKEN_BYTES = 32;

    /** The two local session hosts a new session can be created on. */
    public static final String TMUX_HOST_ID = SessionEntry.TMUX_HOST;
    public static final String JMUX_HOST_ID = "jmux";

    /** Overrides {@code session.host} for one run: {@code -Dharnessmanager.localHost=jmux}. */
    public static final String LOCAL_HOST_OVERRIDE_PROPERTY = "harnessmanager.localHost";

    /**
     * Which local substrate new local sessions are created on:
     * {@code session.host = tmux | jmux}, overridable per run via
     * {@link #LOCAL_HOST_OVERRIDE_PROPERTY}. Unset or unrecognized falls back to the
     * platform default — tmux on macOS/Linux, the jmux daemon on
     * Windows, where tmux does not run. Read at creation time (like
     * {@link #statusPollIntervalMs}), so a change applies to the next Open without a
     * restart. Only affects <em>new local</em> sessions: resume, duplicate and
     * shell-here inherit the source row's host; Adopt asks the router which host listed
     * the session, falling back to this.
     */
    public static String localHostId() {
        String override = System.getProperty(LOCAL_HOST_OVERRIDE_PROPERTY);
        String configured = override != null && !override.isBlank()
                ? override : load().getProperty("session.host");
        return parseLocalHostId(configured, System.getProperty("os.name", ""));
    }

    /** Persists the local-host choice — always written, so the file documents the default too. */
    public static void saveLocalHostId(String hostId) {
        String normalized = parseLocalHostId(hostId, System.getProperty("os.name", ""));
        save(p -> p.setProperty("session.host", normalized));
    }

    /** Package-visible so the unit suite can pin the platform default per OS name. */
    static String parseLocalHostId(String value, String osName) {
        if (value != null) {
            String trimmed = value.trim();
            if (trimmed.equalsIgnoreCase(TMUX_HOST_ID)) {
                return TMUX_HOST_ID;
            }
            if (trimmed.equalsIgnoreCase(JMUX_HOST_ID)) {
                return JMUX_HOST_ID;
            }
        }
        return defaultLocalHostId(osName);
    }

    /** tmux everywhere it runs; the daemon on Windows, where it doesn't. */
    static String defaultLocalHostId(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows") ? JMUX_HOST_ID : TMUX_HOST_ID;
    }

    /**
     * Terminal font shared by every attached tab. The unset default mirrors
     * JediTerm's platform default exactly: Menlo on macOS, Consolas on Windows,
     * and Java's logical Monospaced family elsewhere.
     */
    public static String terminalFontFamily() {
        String value = load().getProperty("terminal.font-family");
        return value == null || value.isBlank() ? defaultTerminalFontFamily() : value.trim();
    }

    /** Terminal font size in points, clamped so hand-edited settings remain usable. */
    public static int terminalFontSize() {
        String value = load().getProperty("terminal.font-size");
        if (value == null) {
            return DEFAULT_TERMINAL_FONT_SIZE;
        }
        try {
            return clampTerminalFontSize(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_TERMINAL_FONT_SIZE;
        }
    }

    public static int clampTerminalFontSize(int size) {
        return Math.max(MIN_TERMINAL_FONT_SIZE, Math.min(MAX_TERMINAL_FONT_SIZE, size));
    }

    /** Persists the app-wide font in one rewrite so family and size cannot get out of step. */
    public static void saveTerminalFont(String family, int size) {
        String normalizedFamily = family == null || family.isBlank()
                ? defaultTerminalFontFamily() : family.trim();
        int normalizedSize = clampTerminalFontSize(size);
        save(p -> {
            p.setProperty("terminal.font-family", normalizedFamily);
            p.setProperty("terminal.font-size", Integer.toString(normalizedSize));
        });
    }

    private static String defaultTerminalFontFamily() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "Consolas";
        }
        if (osName.contains("mac")) {
            return "Menlo";
        }
        return "Monospaced";
    }

    /**
     * Terminal default background. {@code terminal.background=black|white} pins it
     * explicitly; unset (the default), terminals follow {@link #uiTheme()} — black in
     * the dark look, white in the light one.
     */
    public enum TerminalBackground {
        BLACK,
        WHITE;

        /** The background matching an app theme: dark UI, black terminal; light, white. */
        public static TerminalBackground forTheme(UiTheme theme) {
            return theme == UiTheme.LIGHT ? WHITE : BLACK;
        }

        static Optional<TerminalBackground> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            String trimmed = value.trim();
            if (trimmed.equalsIgnoreCase("black")) {
                return Optional.of(BLACK);
            }
            if (trimmed.equalsIgnoreCase("white")) {
                return Optional.of(WHITE);
            }
            return Optional.empty(); // unrecognized -> no pin, follow the theme
        }
    }

    /** The explicit {@code terminal.background} pin, or empty to follow the app theme. */
    public static Optional<TerminalBackground> terminalBackgroundOverride() {
        return TerminalBackground.parse(load().getProperty("terminal.background"));
    }

    /** What a terminal attaching right now should use: the pin if set, else the theme's pair. */
    public static TerminalBackground terminalBackground() {
        return terminalBackgroundOverride().orElseGet(() -> TerminalBackground.forTheme(uiTheme()));
    }

    /**
     * ANSI palette for the 16 base colors: {@code terminal.palette=macos|iterm2|jediterm}.
     * Only those 16 slots — the 256-color cube and truecolor are what they are. Default
     * (unset or unrecognized): macOS Terminal's colors, so plain ANSI output matches what
     * the stock terminal shows; {@code jediterm} restores JediTerm's built-in xterm palette.
     */
    public enum TerminalPalette {
        MACOS_TERMINAL("macos"),
        ITERM2("iterm2"),
        JEDITERM("jediterm");

        private final String key;

        TerminalPalette(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }

        static TerminalPalette parse(String value) {
            if (value != null) {
                String trimmed = value.trim();
                for (TerminalPalette palette : values()) {
                    if (palette.key.equalsIgnoreCase(trimmed)) {
                        return palette;
                    }
                }
            }
            return MACOS_TERMINAL;
        }
    }

    public static TerminalPalette terminalPalette() {
        return TerminalPalette.parse(load().getProperty("terminal.palette"));
    }

    /** Persists the palette choice — always written, so the file documents the default too. */
    public static void saveTerminalPalette(TerminalPalette palette) {
        save(p -> p.setProperty("terminal.palette", palette.key()));
    }

    /**
     * Whether the attach client advertises 24-bit color support to tmux:
     * {@code terminal.truecolor=on|off}. On (the default), truecolor escapes pass through
     * to JediTerm intact; off, tmux quantizes them to the nearest 256-palette entry —
     * the pre-RGB behavior. Read at attach time only: unlike the palette, this is baked
     * into the tmux client, so a change reaches terminals attached from then on.
     */
    public static boolean terminalTrueColor() {
        String value = load().getProperty("terminal.truecolor");
        return value == null || !value.trim().equalsIgnoreCase("off");
    }

    /** Persists the truecolor choice — written explicitly both ways, so the file documents it. */
    public static void saveTerminalTrueColor(boolean on) {
        save(p -> p.setProperty("terminal.truecolor", on ? "on" : "off"));
    }

    /**
     * Whether attached terminals collapse JediTerm's scrollbar: {@code terminal.scrollbar=hidden}.
     * Default (unset or anything else): visible. The bar is useless in this app — tmux keeps
     * the client on its own screen, so JediTerm's scrollback never fills and the thumb
     * permanently spans the whole track. Applies to terminals attached after the change;
     * already-attached widgets keep the bar they were built with.
     */
    public static boolean terminalScrollbarHidden() {
        String value = load().getProperty("terminal.scrollbar");
        return value != null && value.trim().equalsIgnoreCase("hidden");
    }

    /**
     * tmux mouse pin: {@code tmux.mouse=on|off} makes the app set the session's
     * {@code mouse} option every time a session is shown. tmux session options apply
     * live to attached clients, so this reaches running sessions too, not just new
     * ones. Unset or unrecognized (the default): tmux is left entirely alone.
     */
    public static Optional<Boolean> tmuxMouse() {
        String value = load().getProperty("tmux.mouse");
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("on")) {
            return Optional.of(true);
        }
        if (trimmed.equalsIgnoreCase("off")) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    /**
     * Whether toolbar buttons carry a text label next to their glyph:
     * {@code toolbar.labels=on|off}. On (unset, or anything but {@code off} — the
     * default): labeled; off: icon-only, tooltips still name every action.
     */
    public static boolean toolbarLabels() {
        String value = load().getProperty("toolbar.labels");
        return value == null || !value.trim().equalsIgnoreCase("off");
    }

    /** Persists the label choice — written explicitly both ways, so the file documents it. */
    public static void saveToolbarLabels(boolean on) {
        save(p -> p.setProperty("toolbar.labels", on ? "on" : "off"));
    }

    /**
     * Whether the toolbar shows its Page Up/Down controls:
     * {@code toolbar.page-buttons=on|off}. Off (unset, or anything but {@code on} —
     * the default): the controls stay hidden; their optional keyboard mapping remains
     * independent and can still be enabled.
     */
    public static boolean toolbarPageButtons() {
        String value = load().getProperty("toolbar.page-buttons");
        return value != null && value.trim().equalsIgnoreCase("on");
    }

    /** Persists the toolbar paging-control visibility explicitly both ways. */
    public static void saveToolbarPageButtons(boolean on) {
        save(p -> p.setProperty("toolbar.page-buttons", on ? "on" : "off"));
    }

    /**
     * Whether the toolbar shows its stop-ladder controls — take over, detach, close,
     * kill, and shutdown: {@code toolbar.stop-ladder=on|off}. Off (unset, or anything
     * but {@code on} — the default): the controls stay hidden.
     */
    public static boolean toolbarStopLadder() {
        String value = load().getProperty("toolbar.stop-ladder");
        return value != null && value.trim().equalsIgnoreCase("on");
    }

    /** Persists the stop-ladder visibility explicitly both ways. */
    public static void saveToolbarStopLadder(boolean on) {
        save(p -> p.setProperty("toolbar.stop-ladder", on ? "on" : "off"));
    }

    /**
     * Whether the toolbar shows its batch-action controls — Batch close and Remove dead
     * sessions: {@code toolbar.batch-actions=on|off}. On (unset, or anything but
     * {@code off} — the default): the controls are shown, in their own group.
     */
    public static boolean toolbarBatchActions() {
        String value = load().getProperty("toolbar.batch-actions");
        return value == null || !value.trim().equalsIgnoreCase("off");
    }

    /** Persists the batch-action controls' visibility explicitly both ways. */
    public static void saveToolbarBatchActions(boolean on) {
        save(p -> p.setProperty("toolbar.batch-actions", on ? "on" : "off"));
    }

    /** Sidebar surface: the original list gray, the UI panel gray, or theme black/white. */
    public enum SidebarBackground {
        DEFAULT,
        UI_GRAY,
        TERMINAL;

        static SidebarBackground parse(String value) {
            if (value != null) {
                for (SidebarBackground background : values()) {
                    if (background.name().replace('_', '-').equalsIgnoreCase(value.trim())) {
                        return background;
                    }
                }
            }
            return DEFAULT;
        }
    }

    public static SidebarBackground sidebarBackground() {
        return SidebarBackground.parse(load().getProperty("sidebar.background"));
    }

    public static void saveSidebarBackground(SidebarBackground background) {
        save(p -> p.setProperty("sidebar.background",
                background.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    }

    /** Whether the right pane follows the sidebar surface instead of its own background. */
    public static boolean rightPaneMatchesSidebar() {
        return "on".equalsIgnoreCase(load().getProperty("right-pane.match-sidebar", "off").trim());
    }

    public static void saveRightPaneMatchesSidebar(boolean on) {
        save(p -> p.setProperty("right-pane.match-sidebar", on ? "on" : "off"));
    }

    /** Whether main-window toolbar surfaces and buttons follow the sidebar background. */
    public static boolean toolbarMatchesSidebar() {
        return "on".equalsIgnoreCase(load().getProperty("toolbar.match-sidebar", "off").trim());
    }

    public static void saveToolbarMatchesSidebar(boolean on) {
        save(p -> p.setProperty("toolbar.match-sidebar", on ? "on" : "off"));
    }

    /** Preserve matching sidebar sections unless the user explicitly unlinks them. */
    public static boolean sidebarSectionsMatchBackground() {
        return !"off".equalsIgnoreCase(load().getProperty("sidebar.sections-match-background", "on").trim());
    }

    public static void saveSidebarSectionsMatchBackground(boolean on) {
        save(p -> p.setProperty("sidebar.sections-match-background", on ? "on" : "off"));
    }

    /**
     * Whether the sidebar suppresses FlatLaf's blue focus decoration:
     * {@code sidebar.focus-border=hidden|visible}. Hidden (unset, or anything but
     * {@code visible} — the default) keeps the normal neutral boundary but does not
     * recolor or expand it when the session list briefly receives keyboard focus.
     */
    public static boolean sidebarFocusBorderHidden() {
        String value = load().getProperty("sidebar.focus-border");
        return value == null || !value.trim().equalsIgnoreCase("visible");
    }

    /** Persists the sidebar focus-decoration choice explicitly both ways. */
    public static void saveSidebarFocusBorderHidden(boolean hidden) {
        save(p -> p.setProperty("sidebar.focus-border", hidden ? "hidden" : "visible"));
    }

    /**
     * Whether unattached and dead sidebar sessions use the dithered reticule treatment:
     * {@code sidebar.reticule=on|off}. Off (unset, or anything but {@code on} — the
     * default) leaves session rows and dead-session icons unobscured.
     */
    public static boolean sidebarReticule() {
        String value = load().getProperty("sidebar.reticule");
        return value != null && value.trim().equalsIgnoreCase("on");
    }

    /** Persists the sidebar reticule choice explicitly both ways. */
    public static void saveSidebarReticule(boolean on) {
        save(p -> p.setProperty("sidebar.reticule", on ? "on" : "off"));
    }

    /**
     * Whether a run of sidebar sessions sharing a project folder is drawn as one
     * visual group — a folder heading above the run, no repeated path on the rows
     * below: {@code sidebar.folder-groups=on|off}. On (unset, or anything but
     * {@code off} — the default). Only the folder sort can group, since only there is
     * a folder's sessions guaranteed to be adjacent; every other sort renders the
     * plain rows either way.
     */
    public static boolean sidebarFolderGroups() {
        String value = load().getProperty("sidebar.folder-groups");
        return value == null || !value.trim().equalsIgnoreCase("off");
    }

    /** Persists the folder-grouping choice explicitly both ways. */
    public static void saveSidebarFolderGroups(boolean on) {
        save(p -> p.setProperty("sidebar.folder-groups", on ? "on" : "off"));
    }

    /**
     * Whether bare Page Up/Down and Home/End navigate tmux history:
     * {@code tmux.page-keys=on|off}. On (unset, or anything but {@code off} — the
     * default): the app intercepts those four keys for an attached tmux view. Off:
     * JediTerm passes them through to the program in the pane unchanged. The property
     * keeps its original name for compatibility with settings written before Home/End
     * joined the mapping.
     */
    public static boolean tmuxPageKeys() {
        String value = load().getProperty("tmux.page-keys");
        return value == null || !value.trim().equalsIgnoreCase("off");
    }

    /** Persists the Page Up/Down mapping explicitly both ways. */
    public static void saveTmuxPageKeys(boolean on) {
        save(p -> p.setProperty("tmux.page-keys", on ? "on" : "off"));
    }

    /**
     * Persisted sidebar projection. Hidden keys are stored instead of visible keys so a
     * status or harness family introduced by a future build starts visible by default.
     * Empty hidden sets mean "show all"; a set containing every current key means
     * "hide all" and round-trips without a sentinel value.
     */
    public record SidebarViewSettings(
            String sort,
            boolean descending,
            Set<String> hiddenStatuses,
            Set<String> hiddenHarnessFamilies) {
    }

    public static SidebarViewSettings sidebarViewSettings() {
        Properties properties = load();
        return new SidebarViewSettings(
                properties.getProperty("sidebar.sort", "session").trim(),
                properties.getProperty("sidebar.sort-direction", "ascending")
                        .trim().equalsIgnoreCase("descending"),
                parseCsv(properties.getProperty("sidebar.hidden-statuses")),
                parseCsv(properties.getProperty("sidebar.hidden-harnesses")));
    }

    /** Saves the complete sidebar projection in one settings-file rewrite. */
    public static void saveSidebarViewSettings(String sort, boolean descending,
                                               Set<String> hiddenStatuses,
                                               Set<String> hiddenHarnessFamilies) {
        save(p -> {
            p.setProperty("sidebar.sort", sort);
            p.setProperty("sidebar.sort-direction", descending ? "descending" : "ascending");
            p.setProperty("sidebar.hidden-statuses", toCsv(hiddenStatuses));
            p.setProperty("sidebar.hidden-harnesses", toCsv(hiddenHarnessFamilies));
        });
    }

    /** App-wide look and feel: {@code ui.theme=dark|light}. */
    public enum UiTheme {
        DARK,
        LIGHT;

        static UiTheme parse(String value) {
            // unspecified or unrecognized -> dark, the app's default look
            return value != null && value.trim().equalsIgnoreCase("light") ? LIGHT : DARK;
        }
    }

    public static UiTheme uiTheme() {
        return UiTheme.parse(load().getProperty("ui.theme"));
    }

    /** Persists the theme so the next launch starts in it. */
    public static void saveUiTheme(UiTheme theme) {
        save(p -> p.setProperty("ui.theme", theme.name().toLowerCase(Locale.ROOT)));
    }

    /** Persists the background pin; empty clears it so terminals follow the theme again. */
    public static void saveTerminalBackgroundOverride(Optional<TerminalBackground> pin) {
        save(p -> {
            if (pin.isPresent()) {
                p.setProperty("terminal.background", pin.get().name().toLowerCase(Locale.ROOT));
            } else {
                p.remove("terminal.background");
            }
        });
    }

    /** Persists the scrollbar choice — written explicitly both ways, so the file documents it. */
    public static void saveTerminalScrollbarHidden(boolean hidden) {
        save(p -> p.setProperty("terminal.scrollbar", hidden ? "hidden" : "visible"));
    }

    /** Persists the tmux mouse pin; empty clears it so tmux is left alone again. */
    public static void saveTmuxMouse(Optional<Boolean> pin) {
        save(p -> {
            if (pin.isPresent()) {
                p.setProperty("tmux.mouse", pin.get() ? "on" : "off");
            } else {
                p.remove("tmux.mouse");
            }
        });
    }

    /**
     * Whether tmux's own status bar (the bottom chrome line) is shown:
     * {@code tmux.status-bar=on|off}. On (unset, or anything but {@code off} — the
     * default) leaves tmux's built-in status bar as-is. Off sets the session's
     * {@code status} option, hiding it. Applied on every session show, same as
     * {@link #tmuxMouse}, since the option applies live.
     */
    public static boolean tmuxStatusBar() {
        String value = load().getProperty("tmux.status-bar");
        return value == null || !value.trim().equalsIgnoreCase("off");
    }

    /** Persists the status-bar visibility explicitly both ways. */
    public static void saveTmuxStatusBar(boolean on) {
        save(p -> p.setProperty("tmux.status-bar", on ? "on" : "off"));
    }

    /**
     * How often live sidebar sessions are polled for their status badge, in
     * milliseconds: {@code tmux.status-poll-interval-ms}. Each tick forks a real tmux
     * child process per live session, so a shorter interval means more frequent
     * shell-outs; clamped so hand-edited settings remain usable.
     */
    public static int statusPollIntervalMs() {
        String value = load().getProperty("tmux.status-poll-interval-ms");
        if (value == null) {
            return DEFAULT_STATUS_POLL_INTERVAL_MS;
        }
        try {
            return clampStatusPollIntervalMs(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_STATUS_POLL_INTERVAL_MS;
        }
    }

    public static int clampStatusPollIntervalMs(int intervalMs) {
        return Math.max(MIN_STATUS_POLL_INTERVAL_MS, Math.min(MAX_STATUS_POLL_INTERVAL_MS, intervalMs));
    }

    /** Persists the status poll interval, clamped so it always saves a usable value. */
    public static void saveStatusPollIntervalMs(int intervalMs) {
        save(p -> p.setProperty("tmux.status-poll-interval-ms",
                Integer.toString(clampStatusPollIntervalMs(intervalMs))));
    }

    /**
     * Whether live sidebar sessions are polled for their status badge at all:
     * {@code tmux.status-poll-enabled=on|off}. On (unset, or anything but {@code off}
     * — the default). Off stops the tmux capture-pane/display-message shell-outs
     * entirely; the sidebar's working/idle/waiting badge and pane title then freeze
     * at their last known value. Attached terminals and session-death detection are
     * unaffected either way — neither depends on this poll loop.
     */
    public static boolean statusPollingEnabled() {
        String value = load().getProperty("tmux.status-poll-enabled");
        return value == null || !value.trim().equalsIgnoreCase("off");
    }

    /** Persists the polling on/off choice explicitly both ways. */
    public static void saveStatusPollingEnabled(boolean enabled) {
        save(p -> p.setProperty("tmux.status-poll-enabled", enabled ? "on" : "off"));
    }

    /**
     * How the active sidebar tab is kept fresh while {@link #statusPollingEnabled} is
     * off: {@code tmux.active-tab-poll-mode=off|once|scheduled}. Irrelevant (and
     * ignored) while the periodic timer is on — every live session already gets
     * polled every interval then.
     */
    public enum ActiveTabPollMode {
        /** No extra polling; the badge only refreshes via Close/Reload's own catch-up. */
        OFF("off"),
        /** Default. A single catch-up poll fires the moment a live session becomes
         * the active tab; the badge can go stale again if its state changes while
         * still selected. */
        ONCE("once"),
        /** The active tab alone keeps polling on the configured interval for as long
         * as it stays selected — live, but resumes that one tab's tmux shell-outs
         * (and title flicker) that turning the timer off was meant to avoid. */
        SCHEDULED("scheduled");

        private final String key;

        ActiveTabPollMode(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }

        static ActiveTabPollMode parse(String value) {
            if (value != null) {
                String trimmed = value.trim();
                for (ActiveTabPollMode mode : values()) {
                    if (mode.key.equalsIgnoreCase(trimmed)) {
                        return mode;
                    }
                }
            }
            return ONCE;
        }
    }

    public static ActiveTabPollMode activeTabPollMode() {
        return ActiveTabPollMode.parse(load().getProperty("tmux.active-tab-poll-mode"));
    }

    /** Persists the active-tab poll mode — always written, so the file documents the default too. */
    public static void saveActiveTabPollMode(ActiveTabPollMode mode) {
        save(p -> p.setProperty("tmux.active-tab-poll-mode", mode.key()));
    }

    /**
     * Whether wheel events are swallowed before they reach a Codex terminal:
     * {@code codex.mouse-wheel=off|on}. Off (unset, unrecognized, or explicitly
     * {@code off} — the default) prevents an accidental wheel gesture from navigating
     * Codex. On passes wheel events to JediTerm normally.
     */
    public static boolean codexMouseWheelDisabled() {
        String value = load().getProperty("codex.mouse-wheel");
        return value == null || !value.trim().equalsIgnoreCase("on");
    }

    /** Persists whether Codex terminal wheel events are blocked. */
    public static void saveCodexMouseWheelDisabled(boolean disabled) {
        save(p -> p.setProperty("codex.mouse-wheel", disabled ? "off" : "on"));
    }

    /**
     * Whether the app hosts its MCP server, which lets agents running in jmux sessions
     * list, read and drive other sessions: {@code mcp.server=on|off}. Off (unset, or
     * anything but {@code on} — the default). Only sessions created or resumed on the
     * jmux host while the server runs get it registered in their launch command.
     */
    public static boolean mcpServerEnabled() {
        String value = load().getProperty("mcp.server");
        return value != null && value.trim().equalsIgnoreCase("on");
    }

    /** Persists the MCP server on/off choice explicitly both ways. */
    public static void saveMcpServerEnabled(boolean on) {
        save(p -> p.setProperty("mcp.server", on ? "on" : "off"));
    }

    /**
     * The loopback port the MCP server binds: {@code mcp.port}, default
     * {@link #DEFAULT_MCP_PORT}; clamped to the unprivileged range so hand-edited
     * settings remain usable, and unparseable values fall back to the default.
     */
    public static int mcpPort() {
        String value = load().getProperty("mcp.port");
        if (value == null) {
            return DEFAULT_MCP_PORT;
        }
        try {
            return clampMcpPort(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_MCP_PORT;
        }
    }

    public static int clampMcpPort(int port) {
        return Math.max(MIN_MCP_PORT, Math.min(MAX_MCP_PORT, port));
    }

    /** Persists the MCP port, clamped so it always saves a usable value. */
    public static void saveMcpPort(int port) {
        save(p -> p.setProperty("mcp.port", Integer.toString(clampMcpPort(port))));
    }

    /**
     * The shared secret every MCP request must carry: {@code mcp.token}. The first call
     * on a file without one generates a random url-safe token ({@value #MCP_TOKEN_BYTES}
     * bytes from {@link SecureRandom}) and saves it, so the URL baked into a session's
     * launch command stays valid across restarts. The check-and-generate happens inside
     * one load-mutate-store, so two racing first calls agree on the same token. When the
     * file cannot be written the generated token is still returned — usable for this run.
     */
    public static String mcpToken() {
        String existing = load().getProperty("mcp.token");
        if (existing != null && !existing.isBlank()) {
            return existing.trim();
        }
        String[] token = new String[1];
        save(p -> {
            String current = p.getProperty("mcp.token");
            if (current != null && !current.isBlank()) {
                token[0] = current.trim();
            } else {
                token[0] = newMcpToken();
                p.setProperty("mcp.token", token[0]);
            }
        });
        return token[0] != null ? token[0] : newMcpToken();
    }

    /**
     * Replaces {@code mcp.token} with a fresh random one and returns it — returned even
     * when the file cannot be written, so the caller can still serve this run with it.
     * Agents launched with the old token are locked out until their session is resumed.
     */
    public static String regenerateMcpToken() {
        String token = newMcpToken();
        save(p -> p.setProperty("mcp.token", token));
        return token;
    }

    private static String newMcpToken() {
        byte[] bytes = new byte[MCP_TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Load-mutate-store. Best-effort: an unwritable settings file must not break the
     * running app — the in-memory change already took, losing persistence is the lesser
     * harm. Rewrites the file via {@link Properties#store}, so hand-written comments in
     * it don't survive a save. The new contents go to a temp file that atomically
     * replaces the old one, so a crash or full disk mid-write can't leave a truncated
     * file behind; and a file that exists but can't be read is left alone rather than
     * rewritten from defaults, which would wipe every other setting in it.
     */
    private static void save(java.util.function.Consumer<Properties> mutation) {
        synchronized (LOCK) {
            Path file = settingsFile();
            Path tmp = null;
            try {
                Properties properties = readFile(file);
                mutation.accept(properties);
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                tmp = Files.createTempFile(parent, "settings-", ".properties.tmp");
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    properties.store(out, "harness-manager settings");
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // see method doc — logged, never thrown
                LOG.warn("failed to save settings to {}", file, e);
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException cleanup) {
                        LOG.warn("failed to clean up temp file {}", tmp, cleanup);
                    }
                }
            }
        }
    }

    public static Path settingsFile() {
        return RegistryPaths.resolve().toAbsolutePath().resolveSibling("settings.properties");
    }

    private static Set<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new HashSet<>();
        for (String item : value.split(",")) {
            String key = item.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                parsed.add(key);
            }
        }
        return Set.copyOf(parsed);
    }

    private static String toCsv(Set<String> values) {
        return values.stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static Properties load() {
        Path file = settingsFile();
        synchronized (LOCK) {
            try {
                return readFile(file);
            } catch (IOException e) {
                // unreadable settings must never keep the app from starting — use defaults
                LOG.warn("failed to read settings from {} — using defaults", file, e);
                return new Properties();
            }
        }
    }

    /** The file's properties, empty when it doesn't exist; throws when it can't be read. */
    private static Properties readFile(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }
        return properties;
    }

    private AppSettings() {
    }
}
