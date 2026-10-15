package io.github.ghosthack.harnessmanager.ui;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

/**
 * An icon in the kotlin-mosaic "Lucid" style: SVG path data on a 24×24 grid, stroked at
 * a uniform 2/24 weight with round caps and joins, and tinted with the component's
 * foreground at paint time — so a look-and-feel switch recolors it for free, the same
 * way {@code Icons.kt} icons take the theme's colour.
 *
 * <p>Drawn, not shipped: no image resource to load, scale badly, or fail to tint.
 */
final class LucidIcon implements Icon {

    /** The kotlin-mosaic {@code Icon.Screensaver} crescent, path data verbatim. */
    static LucidIcon moon(int size) {
        return new LucidIcon("M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z", size);
    }

    /** A sun in the same hand: 5-radius disc, eight 2-length rays inset from the rim. */
    static LucidIcon sun(int size) {
        return new LucidIcon("M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10z"
                + "M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42"
                + "M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42", size);
    }

    /**
     * The shell "harness": the Lucid {@code SquareTerminal} — a framed prompt-and-cursor,
     * distinct from the filled provider marks by its rounded frame.
     */
    static LucidIcon shell(int size) {
        return new LucidIcon("M7 7l2 2-2 2M11 13h4"
                + "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", size);
    }

    /**
     * Settings: the Lucid {@code SlidersHorizontal} — three slider rails with offset
     * knobs. The classic gear needs cubic curves this parser deliberately doesn't
     * speak; sliders read as "settings" just as well and stay in the pack's dialect.
     */
    static LucidIcon settings(int size) {
        return new LucidIcon("M21 4h-7M10 4H3M21 12h-9M8 12H3M21 20h-5M12 20H3"
                + "M14 2v4M8 10v4M16 18v4", size);
    }

    /** Usage and limits: the Lucid {@code ChartNoAxesColumnIncreasing}. */
    static LucidIcon usage(int size) {
        return new LucidIcon("M4 20V10M10 20V4M16 20v-7M22 20H2", size);
    }

    /** Reload current provider data: two chasing arrows in the Lucid style. */
    static LucidIcon refresh(int size) {
        return new LucidIcon("M20 11a8.1 8.1 0 0 0-15.5-2M4 4v5h5"
                + "M4 13a8.1 8.1 0 0 0 15.5 2M20 20v-5h-5", size);
    }

    /** Sort: the Lucid {@code ArrowDownUp}, one ascending and one descending arrow. */
    static LucidIcon sort(int size) {
        return new LucidIcon("M3 16l4 4 4-4M7 20V4M21 8l-4-4-4 4M17 4v16", size);
    }

    /** Filter: the classic Lucid funnel, narrowing the full set into a smaller result. */
    static LucidIcon funnel(int size) {
        return new LucidIcon("M22 3H2l8 9.46V19l4 2v-8.54L22 3z", size);
    }

    /**
     * The Lucid {@code PanelLeftClose}: a framed panel with its sidebar divider and an
     * inward-pointing chevron — "tuck the sidebar away".
     */
    static LucidIcon panelLeftClose(int size) {
        return new LucidIcon("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"
                + "M9 3v18M16 15l-3-3 3-3", size);
    }

    /** The Lucid {@code PanelLeftOpen}: same frame and divider, chevron pointing back out. */
    static LucidIcon panelLeftOpen(int size) {
        return new LucidIcon("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"
                + "M9 3v18M14 9l3 3-3 3", size);
    }

    /** Open/new session: the Lucid {@code FolderPlus} — a project folder gaining one. */
    static LucidIcon folderPlus(int size) {
        return new LucidIcon("M12 10v6M9 13h6"
                + "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9"
                + "L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z", size);
    }

    /**
     * Open-here: the Lucid {@code FolderOpen} — an already-open folder, for starting
     * another session at a place a current session already is (vs {@link #folderPlus}'s
     * fresh folder for a fresh location).
     */
    static LucidIcon folderOpen(int size) {
        return new LucidIcon("M6 14l1.45-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5"
                + "l-1.55 6a2 2 0 0 1-1.94 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.93"
                + "a2 2 0 0 1 1.66 0.9l0.82 1.2a2 2 0 0 0 1.66 0.9H18a2 2 0 0 1 2 2v2", size);
    }

    /**
     * Cmd/Ctrl+T's shell-here: a bare prompt chevron with a plus — "one more prompt,
     * right here". Authored in the pack's hand, like {@link #sun}.
     */
    static LucidIcon shellPlus(int size) {
        return new LucidIcon("M4 7 10 12 4 17M17 9v6M14 12h6", size);
    }

    /** Duplicate: the Lucid {@code Copy} — a sheet and the sheet behind it. */
    static LucidIcon duplicate(int size) {
        return new LucidIcon("M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z"
                + "M4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2", size);
    }

    /** The Lucid {@code Activity} pulse — heartbeat/stimulation in progress. */
    static LucidIcon heartbeat(int size) {
        return new LucidIcon("M22 12h-4l-3 9L9 3l-3 9H2", size);
    }

    /** Status has not landed yet: the Lucid {@code Hourglass}. */
    static LucidIcon hourglass(int size) {
        return new LucidIcon("M5 22h14M5 2h14"
                + "M17 22v-4.17a2 2 0 0 0-.59-1.42L12 12 7.59 16.41A2 2 0 0 0 7 17.83V22"
                + "M7 2v4.17a2 2 0 0 0 .59 1.42L12 12l4.41-4.41A2 2 0 0 0 17 6.17V2", size);
    }

    /** Page Up: two chevrons moving a viewport toward older terminal content. */
    static LucidIcon pageUp(int size) {
        return new LucidIcon("M17 11l-5-5-5 5M17 18l-5-5-5 5", size);
    }

    /** Page Down: the matching pair moving toward newer terminal content. */
    static LucidIcon pageDown(int size) {
        return new LucidIcon("M7 6l5 5 5-5M7 13l5 5 5-5", size);
    }

    /** Direct takeover: the inverse of {@link #detach(int)}, entering the live frame. */
    static LucidIcon takeover(int size) {
        return new LucidIcon("M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"
                + "M8 7l-5 5 5 5M3 12h12", size);
    }

    /** Detach: the Lucid {@code LogOut} — an arrow leaving the frame; what's inside
     * keeps running. */
    static LucidIcon detach(int size) {
        return new LucidIcon("M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"
                + "M16 17l5-5-5-5M21 12H9", size);
    }

    /** Graceful close of one session: the Lucid {@code X} — the universal close mark. */
    static LucidIcon x(int size) {
        return new LucidIcon("M18 6 6 18M6 6l12 12", size);
    }

    /** Shutdown-everything: the Lucid {@code Power} — the polite whole-app mark, scoped
     * wider than {@link #x}'s single close. */
    static LucidIcon power(int size) {
        return new LucidIcon("M12 2v10M18.4 6.6a9 9 0 1 1 -12.77 0.04", size);
    }

    /** Kill: the Lucid {@code Zap} bolt — force, as opposed to {@link #power}'s ask. */
    static LucidIcon zap(int size) {
        return new LucidIcon("M13 2 3 14h9l-1 8 10-12h-9l1-8z", size);
    }

    /** Batch close: the Lucid {@code ListChecks} — two checkmarks against list rules,
     * standing for a hand-picked set of rows rather than {@link #power}'s "everything". */
    static LucidIcon listChecks(int size) {
        return new LucidIcon("M3 17l2 2 4-4M3 7l2 2 4-4M13 6h8M13 12h8M13 18h8", size);
    }

    /** Remove dead sessions: the Lucid {@code Trash2} bin, verbatim Lucide path data. */
    static LucidIcon trash(int size) {
        return new LucidIcon("M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
                + "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M10 11v6M14 11v6", size);
    }

    /**
     * The Lucid {@code Bot} glyph: an antenna, a framed face, and two side ears. Used as a
     * neutral stand-in harness identity — see {@code provider-icons/NOTICE.md} — for a
     * harness with no verified first-party mark available yet.
     */
    static LucidIcon bot(int size) {
        return new LucidIcon("M12 8V4H8"
                + "M6 8H18a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2Z"
                + "M2 14h2M20 14h2M15 13v2M9 13v2", size);
    }

    /**
     * The Lucid {@code Code} glyph: two inward-facing chevrons framing a snippet. Used as a
     * neutral stand-in harness identity — see {@code provider-icons/NOTICE.md} — for a
     * harness with no verified first-party mark available yet.
     */
    static LucidIcon code(int size) {
        return new LucidIcon("M16 18 22 12 16 6M8 6 2 12 8 18", size);
    }

    /**
     * A lowercase Greek pi (π): a top bar over two legs, each flicking outward at the
     * foot. Authored in the pack's hand, like {@link #sun} and {@link #shellPlus} — used
     * as a neutral stand-in harness identity — see {@code provider-icons/NOTICE.md} —
     * for a harness with no verified first-party mark available yet, and named for what
     * it draws rather than borrowed from a harness that already has its own icon here.
     */
    static LucidIcon pi(int size) {
        return new LucidIcon("M3 7H21M7 7V17L4 20M17 7V17L20 20", size);
    }

    /**
     * Remote session badge: the Lucid {@code Wifi} glyph — three nested signal arcs plus
     * the "connected" dot, verbatim Lucide path data (the dot is a near-zero-length
     * {@code h.01}, which the round line cap renders as a dot, the same trick Lucide's
     * own source SVG uses).
     */
    static LucidIcon wifi(int size) {
        return new LucidIcon("M12 20h.01"
                + "M2 8.82a15 15 0 0 1 20 0"
                + "M5 12.859a10 10 0 0 1 14 0"
                + "M8.5 16.429a5 5 0 0 1 7 0", size);
    }

    /** The grid every path is authored on, and the stroke weight on that grid. */
    private static final float VIEWPORT = 24f;
    private static final float STROKE = 2f;

    /** How much of the tint survives on a disabled component — roughly the fade
     * FlatLaf's disabled text carries. */
    private static final float DISABLED_ALPHA = 0.35f;

    private final Path2D path;
    private final int size;

    private LucidIcon(String data, int size) {
        this.path = parse(data);
        this.size = size;
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // Swing only derives disabled icons from images (GrayFilter), so a drawn icon
        // on a disabled button would paint at full strength — fade it here instead,
        // and the alpha tracks any foreground in any theme.
        if (!c.isEnabled()) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_ALPHA));
        }
        g2.translate(x, y);
        double factor = size / (double) VIEWPORT;
        g2.scale(factor, factor);
        g2.setColor(iconForeground(c));
        g2.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(path);
        g2.dispose();
    }

    /**
     * FlatLaf paints a dialog's default button with its own accent foreground
     * ({@code Button.default.foreground}) directly in {@code paintText}, without ever
     * calling {@code setForeground} on the button — so a drawn icon reading
     * {@code getForeground()} would stay in the ordinary (unhighlighted) color while the
     * label right next to it paints in the accent-contrast color FlatLaf computed.
     */
    private static Color iconForeground(Component c) {
        if (c instanceof JButton button && button.isDefaultButton()) {
            Color defaultForeground = UIManager.getColor("Button.default.foreground");
            if (defaultForeground != null) {
                return defaultForeground;
            }
        }
        return c.getForeground();
    }

    // ------------------------------------------------------------------
    // Just enough of an SVG path-data parser for the Lucid pack's dialect:
    // M/L/H/V/A/Z, absolute and relative, with implicit command repetition.
    // ------------------------------------------------------------------

    private static Path2D parse(String data) {
        Path2D.Float path = new Path2D.Float();
        Cursor in = new Cursor(data);
        char cmd = 0;
        float x = 0, y = 0, startX = 0, startY = 0;
        while (in.hasMore()) {
            char c = in.peekCommand();
            if (c != 0) {
                cmd = c;
            } else if (cmd == 'M') {
                cmd = 'L'; // implicit repetition after a moveto is a lineto
            } else if (cmd == 'm') {
                cmd = 'l';
            } else if (cmd == 0) {
                throw new IllegalArgumentException("path data starts with a number: " + data);
            }
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    x = in.number() + (rel ? x : 0);
                    y = in.number() + (rel ? y : 0);
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                }
                case 'L' -> {
                    x = in.number() + (rel ? x : 0);
                    y = in.number() + (rel ? y : 0);
                    path.lineTo(x, y);
                }
                case 'H' -> {
                    x = in.number() + (rel ? x : 0);
                    path.lineTo(x, y);
                }
                case 'V' -> {
                    y = in.number() + (rel ? y : 0);
                    path.lineTo(x, y);
                }
                case 'A' -> {
                    float rx = in.number();
                    float ry = in.number();
                    in.number(); // x-axis rotation: always 0 in this pack
                    boolean largeArc = in.number() != 0;
                    boolean sweep = in.number() != 0;
                    float ex = in.number() + (rel ? x : 0);
                    float ey = in.number() + (rel ? y : 0);
                    appendArc(path, x, y, rx, ry, largeArc, sweep, ex, ey);
                    x = ex;
                    y = ey;
                }
                case 'Z' -> {
                    path.closePath();
                    x = startX;
                    y = startY;
                }
                default -> throw new IllegalArgumentException("unsupported path command '" + cmd + "'");
            }
        }
        return path;
    }

    /**
     * SVG endpoint parameterization to a Java2D {@link Arc2D}, for unrotated ellipses
     * (spec appendix F.6.5). Arc2D measures angles counterclockwise in a y-up frame
     * while user space is y-down, so the start angle and extent both flip sign.
     */
    private static void appendArc(Path2D.Float path, double x0, double y0, double rx, double ry,
                                  boolean largeArc, boolean sweep, double x1, double y1) {
        double dx2 = (x0 - x1) / 2.0;
        double dy2 = (y0 - y1) / 2.0;
        // scale radii up if no ellipse of the given radii reaches both endpoints
        double lambda = (dx2 * dx2) / (rx * rx) + (dy2 * dy2) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
        }
        double rxs = rx * rx;
        double rys = ry * ry;
        double num = rxs * rys - rxs * dy2 * dy2 - rys * dx2 * dx2;
        double den = rxs * dy2 * dy2 + rys * dx2 * dx2;
        double co = (largeArc == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cx = co * rx * dy2 / ry + (x0 + x1) / 2.0;
        double cy = -co * ry * dx2 / rx + (y0 + y1) / 2.0;
        double theta1 = Math.atan2((y0 - cy) / ry, (x0 - cx) / rx);
        double theta2 = Math.atan2((y1 - cy) / ry, (x1 - cx) / rx);
        double delta = theta2 - theta1;
        if (sweep && delta < 0) {
            delta += 2 * Math.PI;
        } else if (!sweep && delta > 0) {
            delta -= 2 * Math.PI;
        }
        path.append(new Arc2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry,
                -Math.toDegrees(theta1), -Math.toDegrees(delta), Arc2D.OPEN), true);
    }

    /** Character-level cursor over path data: commands, numbers, separators. */
    private static final class Cursor {
        private final String data;
        private int pos;

        Cursor(String data) {
            this.data = data;
        }

        boolean hasMore() {
            skipSeparators();
            return pos < data.length();
        }

        /** Consumes and returns the next command letter, or 0 if a number is next. */
        char peekCommand() {
            skipSeparators();
            char c = data.charAt(pos);
            if (Character.isLetter(c)) {
                pos++;
                return c;
            }
            return 0;
        }

        float number() {
            skipSeparators();
            int start = pos;
            if (pos < data.length() && (data.charAt(pos) == '-' || data.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < data.length() && (Character.isDigit(data.charAt(pos)) || data.charAt(pos) == '.')) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("expected number at " + start + " in: " + data);
            }
            return Float.parseFloat(data.substring(start, pos));
        }

        private void skipSeparators() {
            while (pos < data.length() && (data.charAt(pos) == ' ' || data.charAt(pos) == ',')) {
                pos++;
            }
        }
    }
}
