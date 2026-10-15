package io.github.ghosthack.harnessmanager.terminal;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TerminalColorMode;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import io.github.ghosthack.harnessmanager.host.SessionConnection;
import io.github.ghosthack.harnessmanager.settings.AppSettings;

import javax.swing.BoundedRangeModel;
import javax.swing.JScrollBar;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseWheelEvent;

/**
 * A {@link JediTermWidget} over an already-open {@link SessionConnection} — a tmux
 * client in a PTY, the stimulator's read-only peek, or a daemon attach stream; the
 * widget does not know the difference. One of these is created per session, lazily,
 * once its connection lands, and then kept alive for the app's lifetime — switching
 * sessions only changes which widget's card is visible. Successor of
 * {@code TmuxTerminal}: opening the connection (which may block) now happens on the
 * host, off the EDT; this class only builds Swing state and is EDT-only.
 */
public final class SessionTerminal {

    public static final int COLUMNS = 120;
    public static final int ROWS = 32;

    private final ThemedJediTermWidget widget;
    private final SessionConnection connection;

    private SessionTerminal(ThemedJediTermWidget widget, SessionConnection connection) {
        this.widget = widget;
        this.connection = connection;
    }

    /**
     * Builds the widget over {@code connection} and starts reading from it. The
     * connection was opened at {@link #COLUMNS}x{@link #ROWS}; the widget's first real
     * layout resizes it. {@code mouseWheelDisabled} swallows every wheel event at the
     * terminal panel (Codex navigates on wheel gestures). Call on the EDT.
     */
    public static SessionTerminal open(SessionConnection connection, boolean mouseWheelDisabled,
                                       String fontFamily, int fontSize) {
        ThemedJediTermWidget widget = new ThemedJediTermWidget(COLUMNS, ROWS,
                new BackgroundAwareSettingsProvider(AppSettings.terminalBackground(),
                        AppSettings.terminalPalette(),
                        AppSettings.terminalScrollbarHidden(), mouseWheelDisabled,
                        fontFamily, fontSize));
        widget.setTtyConnector(new ConnectionTtyConnector(connection));
        widget.start();
        return new SessionTerminal(widget, connection);
    }

    public JediTermWidget widget() {
        return widget;
    }

    /**
     * Recolors this live terminal's default fg/bg pair (theme toggle). Only the
     * defaults flip — cells a program painted with explicit ANSI/256 colors keep them,
     * exactly as at attach time. Call on the EDT.
     */
    public void applyBackground(AppSettings.TerminalBackground background) {
        widget.applyBackground(background);
    }

    /** Matches a UI surface while preserving explicit ANSI colors and reporting its theme. */
    public void applyBackground(Color background, boolean light) {
        widget.applyBackground(background, light);
    }

    /**
     * Swaps this live terminal's 16-slot ANSI palette. Cells hold color <em>indexes</em>,
     * which the panel resolves through the settings provider on every paint — so already
     * painted output recolors too, not just new writes. Call on the EDT.
     */
    public void applyPalette(AppSettings.TerminalPalette palette) {
        widget.applyPalette(palette);
    }

    /** Changes the font and reflows this live connection to its new row/column dimensions. */
    public void applyFont(String family, int size) {
        widget.applyFont(family, size);
    }

    /** Enables or disables the wheel-event filter immediately. Call on the EDT. */
    public void applyMouseWheelDisabled(boolean disabled) {
        widget.applyMouseWheelDisabled(disabled);
    }

    public boolean isAlive() {
        return connection.isAlive();
    }

    /** Detaches: stops the widget and closes the connection. The session itself keeps running. */
    public void detach() {
        widget.close(); // closes the connector, i.e. the connection, and stops the emulator threads
        connection.close();
    }

    /**
     * Local scrollback paging for hosts without their own history navigation
     * ({@code HostFeature.HISTORY_PAGING} absent). {@code TerminalPanel}'s own
     * {@code pageUp()}/{@code pageDown()} are private, so this drives the panel's public
     * scroll model the same way they do: the value is a row offset where 0 is the live
     * screen, negative values look back into history, and the extent is the visible
     * row count. The model clamps out-of-range values itself.
     */
    public void pageUp() {
        BoundedRangeModel model = scrollModel();
        model.setValue(model.getValue() - model.getExtent());
    }

    public void pageDown() {
        BoundedRangeModel model = scrollModel();
        model.setValue(model.getValue() + model.getExtent());
    }

    public void scrollToTop() {
        BoundedRangeModel model = scrollModel();
        model.setValue(model.getMinimum());
    }

    public void scrollToBottom() {
        scrollModel().setValue(0);
    }

    private BoundedRangeModel scrollModel() {
        return widget.getTerminalPanel().getVerticalScrollModel();
    }

    /**
     * Default colors follow the effective terminal background: black background with
     * white text, or the inverse. Only the defaults change — the 256-color palette
     * programs actually use is untouched. Mutable so the theme toggle can recolor a
     * live widget; {@link ThemedJediTermWidget#applyBackground} is the only writer.
     *
     * <p>Overrides {@code getDefaultStyle()} — not the fg/bg getters it feeds — because
     * JediTermWidget seeds its StyleState from the style: overriding only the getters
     * leaves cell text and the (style-inverting) cursor on the built-in black-on-white,
     * i.e. invisible against the black window background.
     */
    private static final class BackgroundAwareSettingsProvider extends DefaultSettingsProvider {
        private AppSettings.TerminalBackground background;
        private Color customBackground;
        private boolean lightBackground;
        private AppSettings.TerminalPalette palette;
        private String fontFamily;
        private int fontSize;
        private boolean mouseWheelDisabled;
        /** Read during super-construction (see {@link ThemedJediTermWidget#createScrollBar()}),
         * so it rides on the provider — the widget's own fields don't exist yet then. */
        final boolean scrollbarHidden;

        BackgroundAwareSettingsProvider(AppSettings.TerminalBackground background,
                                        AppSettings.TerminalPalette palette,
                                        boolean scrollbarHidden,
                                        boolean mouseWheelDisabled,
                                        String fontFamily,
                                        int fontSize) {
            this.background = background;
            this.lightBackground = background == AppSettings.TerminalBackground.WHITE;
            this.palette = palette;
            this.scrollbarHidden = scrollbarHidden;
            this.mouseWheelDisabled = mouseWheelDisabled;
            this.fontFamily = fontFamily;
            this.fontSize = AppSettings.clampTerminalFontSize(fontSize);
        }

        @Override
        public TextStyle getDefaultStyle() {
            TerminalColor color = customBackground == null
                    ? (background == AppSettings.TerminalBackground.WHITE ? TerminalColor.WHITE : TerminalColor.BLACK)
                    : TerminalColor.rgb(customBackground.getRed(), customBackground.getGreen(), customBackground.getBlue());
            return new TextStyle(lightBackground ? TerminalColor.BLACK : TerminalColor.WHITE, color);
        }

        @Override
        public ColorPalette getTerminalColorPalette() {
            return AnsiPalettes.forSetting(palette);
        }

        @Override
        public Font getTerminalFont() {
            return new Font(fontFamily, Font.PLAIN, fontSize);
        }

        @Override
        public float getTerminalFontSize() {
            return fontSize;
        }
    }

    /**
     * A JediTermWidget that can recolor its default style after construction. The
     * widget has no public route to the {@link StyleState} its panel and terminal
     * share, but it does let a subclass build it — so {@link #createDefaultStyle()}
     * (called from the super constructor) captures the instance. Re-seeding it is
     * enough for a live switch: cells written in the default style hold null colors
     * that the panel resolves through this StyleState on every paint, and the window
     * background comes from the same place.
     */
    private static final class ThemedJediTermWidget extends JediTermWidget {
        private StyleState styleState;

        ThemedJediTermWidget(int columns, int rows, BackgroundAwareSettingsProvider settingsProvider) {
            super(columns, rows, settingsProvider);
            syncColorMode();
        }

        /**
         * Reports our actual background to the pty as a light/dark preference,
         * answering a program's {@code CSI ?996n} query (e.g. a CLI choosing its
         * own theme) instead of leaving it unanswered.
         */
        private void syncColorMode() {
            boolean light = ((BackgroundAwareSettingsProvider) mySettingsProvider).lightBackground;
            TerminalColorMode mode = light ? TerminalColorMode.LIGHT : TerminalColorMode.DARK;
            ((JediTerminal) getTerminal()).setColorMode(mode);
        }

        /**
         * Collapses the scrollbar when {@code terminal.scrollbar=hidden}: the widget's
         * TerminalLayout sizes the bar from its preferred width, so zeroing it hands the
         * column back to the terminal. The bar must still exist (and be returned) —
         * TerminalPanel wires its scroll model into it. Called from the super
         * constructor; {@code mySettingsProvider} is assigned before this runs, which is
         * why the flag lives on the provider rather than on this class.
         */
        @Override
        protected JScrollBar createScrollBar() {
            JScrollBar scrollBar = super.createScrollBar();
            if (((BackgroundAwareSettingsProvider) mySettingsProvider).scrollbarHidden) {
                scrollBar.setPreferredSize(new Dimension(0, 0));
            }
            return scrollBar;
        }

        @Override
        protected StyleState createDefaultStyle() {
            StyleState state = super.createDefaultStyle();
            styleState = state; // no field initializer, so the super-constructor write survives
            return state;
        }

        @Override
        protected TerminalPanel createTerminalPanel(SettingsProvider settingsProvider,
                                                    StyleState styleState,
                                                    TerminalTextBuffer terminalTextBuffer) {
            return new WheelFilteringTerminalPanel(
                    (BackgroundAwareSettingsProvider) settingsProvider,
                    styleState, terminalTextBuffer);
        }

        void applyBackground(AppSettings.TerminalBackground background) {
            BackgroundAwareSettingsProvider provider = (BackgroundAwareSettingsProvider) mySettingsProvider;
            provider.background = background;
            provider.customBackground = null;
            provider.lightBackground = background == AppSettings.TerminalBackground.WHITE;
            styleState.setDefaultStyle(provider.getDefaultStyle());
            syncColorMode();
            getTerminalPanel().repaint();
        }

        void applyBackground(Color background, boolean light) {
            BackgroundAwareSettingsProvider provider = (BackgroundAwareSettingsProvider) mySettingsProvider;
            provider.customBackground = background;
            provider.lightBackground = light;
            styleState.setDefaultStyle(provider.getDefaultStyle());
            syncColorMode();
            getTerminalPanel().repaint();
        }

        /** The panel asks the provider for the palette on every paint, so this is the whole switch. */
        void applyPalette(AppSettings.TerminalPalette palette) {
            ((BackgroundAwareSettingsProvider) mySettingsProvider).palette = palette;
            getTerminalPanel().repaint();
        }

        void applyFont(String family, int size) {
            BackgroundAwareSettingsProvider provider =
                    (BackgroundAwareSettingsProvider) mySettingsProvider;
            provider.fontFamily = family;
            provider.fontSize = AppSettings.clampTerminalFontSize(size);
            ((WheelFilteringTerminalPanel) getTerminalPanel()).refreshFont();
            revalidate();
        }

        void applyMouseWheelDisabled(boolean disabled) {
            ((BackgroundAwareSettingsProvider) mySettingsProvider).mouseWheelDisabled = disabled;
        }
    }

    /** Stops wheel events before JediTerm can turn them into local or remote scrolling. */
    private static final class WheelFilteringTerminalPanel extends TerminalPanel {
        private final BackgroundAwareSettingsProvider settingsProvider;

        WheelFilteringTerminalPanel(BackgroundAwareSettingsProvider settingsProvider,
                                    StyleState styleState,
                                    TerminalTextBuffer terminalTextBuffer) {
            super(settingsProvider, terminalTextBuffer, styleState);
            this.settingsProvider = settingsProvider;
        }

        @Override
        protected void processMouseWheelEvent(MouseWheelEvent event) {
            if (settingsProvider.mouseWheelDisabled) {
                event.consume();
                return;
            }
            super.processMouseWheelEvent(event);
        }

        /** Exposes JediTerm's protected live font refresh to the owning widget. */
        void refreshFont() {
            reinitFontAndResize();
            revalidate();
            repaint();
        }
    }
}
