package io.github.ghosthack.harnessmanager.ui;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A theme-tinted provider mark loaded from the app's audited flat-SVG resources.
 *
 * <p>The source profile is intentionally tiny: a {@code viewBox} and one or more
 * fill-only paths. Supporting that profile directly keeps this Swing app independent
 * of a general SVG library (and of the JavaFX renderer in development-iconography),
 * while preserving the providers' original vector geometry at every display scale.
 */
final class ProviderIcon implements Icon {

    private static final String ROOT =
            "/io/github/ghosthack/harnessmanager/ui/provider-icons/";
    private static final Pattern VIEW_BOX = Pattern.compile(
            "\\bviewBox\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATH_ELEMENT = Pattern.compile(
            "<path\\b([^>]*)/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_DATA = Pattern.compile(
            "\\bd\\s*=\\s*\"([^\"]+)\"");

    /** Matches the fade used by {@link LucidIcon} for a disabled host component. */
    private static final float DISABLED_ALPHA = 0.35f;

    private record GlyphPath(Path2D shape, float opacity) {}

    private final List<GlyphPath> paths;
    private final double sourceX;
    private final double sourceY;
    private final double sourceWidth;
    private final double sourceHeight;
    private final int size;

    static ProviderIcon anthropic(int size) {
        return load("anthropic", size);
    }

    static ProviderIcon openAi(int size) {
        return load("openai", size);
    }

    static ProviderIcon cursor(int size) {
        return load("cursor", size);
    }

    static ProviderIcon grok(int size) {
        return load("grok", size);
    }

    static ProviderIcon opencode(int size) {
        return load("opencode", size);
    }

    static ProviderIcon pi(int size) {
        return load("pi", size);
    }

    static ProviderIcon muse(int size) {
        return load("muse", size);
    }

    private ProviderIcon(List<GlyphPath> paths, double[] viewBox, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("icon size must be positive: " + size);
        }
        this.paths = List.copyOf(paths);
        this.sourceX = viewBox[0];
        this.sourceY = viewBox[1];
        this.sourceWidth = viewBox[2];
        this.sourceHeight = viewBox[3];
        this.size = size;
    }

    private static ProviderIcon load(String name, int size) {
        String resource = ROOT + name + ".svg";
        try (InputStream in = ProviderIcon.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("missing provider icon resource: " + resource);
            }
            String svg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(svg, name, size);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read provider icon: " + resource, e);
        }
    }

    private static ProviderIcon parse(String svg, String name, int size) {
        Matcher viewBoxMatch = VIEW_BOX.matcher(svg);
        if (!viewBoxMatch.find()) {
            throw new IllegalArgumentException(name + " icon has no viewBox");
        }
        double[] viewBox = parseViewBox(viewBoxMatch.group(1), name);

        List<GlyphPath> paths = new ArrayList<>();
        Matcher elements = PATH_ELEMENT.matcher(svg);
        while (elements.find()) {
            Matcher data = PATH_DATA.matcher(elements.group(1));
            if (data.find() && !data.group(1).isBlank()) {
                Matcher opacityMatch = Pattern.compile("\\bopacity\\s*=\\s*\"([^\"]+)\"")
                        .matcher(elements.group(1));
                float opacity = opacityMatch.find() ? Float.parseFloat(opacityMatch.group(1)) : 1f;
                if (!Float.isFinite(opacity) || opacity < 0 || opacity > 1) {
                    throw new IllegalArgumentException(name + " icon has invalid path opacity");
                }
                paths.add(new GlyphPath(parsePath(data.group(1)), opacity));
            }
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException(name + " icon has no drawable paths");
        }
        return new ProviderIcon(paths, viewBox, size);
    }

    private static double[] parseViewBox(String value, String name) {
        String[] parts = value.strip().split("[\\s,]+");
        if (parts.length != 4) {
            throw new IllegalArgumentException(name + " icon has a malformed viewBox: " + value);
        }
        double[] box = new double[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                box[i] = Double.parseDouble(parts[i]);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " icon has a malformed viewBox: " + value, e);
        }
        if (box[2] <= 0 || box[3] <= 0) {
            throw new IllegalArgumentException(name + " icon has a non-positive viewBox: " + value);
        }
        return box;
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
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g2.setColor(iconForeground(c));

            double scale = size / Math.max(sourceWidth, sourceHeight);
            double offsetX = (size - sourceWidth * scale) / 2.0 - sourceX * scale;
            double offsetY = (size - sourceHeight * scale) / 2.0 - sourceY * scale;
            g2.translate(x + offsetX, y + offsetY);
            g2.scale(scale, scale);
            for (GlyphPath path : paths) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                        path.opacity() * (c.isEnabled() ? 1f : DISABLED_ALPHA)));
                g2.fill(path.shape());
            }
        } finally {
            g2.dispose();
        }
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

    // The audited provider resources use M/L/H/V/C/A/Z. Both absolute and
    // relative forms and SVG's implicit command repetition are supported.
    private static Path2D parsePath(String data) {
        Path2D.Double path = new Path2D.Double();
        Cursor in = new Cursor(data);
        char command = 0;
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;

        while (in.hasMore()) {
            char next = in.command();
            if (next != 0) {
                command = next;
            } else if (command == 'M') {
                command = 'L';
            } else if (command == 'm') {
                command = 'l';
            } else if (command == 0) {
                throw new IllegalArgumentException("path data starts with a number: " + data);
            }

            boolean relative = Character.isLowerCase(command);
            switch (Character.toUpperCase(command)) {
                case 'M' -> {
                    x = coordinate(in.number(), relative, x);
                    y = coordinate(in.number(), relative, y);
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                }
                case 'L' -> {
                    x = coordinate(in.number(), relative, x);
                    y = coordinate(in.number(), relative, y);
                    path.lineTo(x, y);
                }
                case 'H' -> {
                    x = coordinate(in.number(), relative, x);
                    path.lineTo(x, y);
                }
                case 'V' -> {
                    y = coordinate(in.number(), relative, y);
                    path.lineTo(x, y);
                }
                case 'C' -> {
                    double baseX = x;
                    double baseY = y;
                    double x1 = coordinate(in.number(), relative, baseX);
                    double y1 = coordinate(in.number(), relative, baseY);
                    double x2 = coordinate(in.number(), relative, baseX);
                    double y2 = coordinate(in.number(), relative, baseY);
                    x = coordinate(in.number(), relative, baseX);
                    y = coordinate(in.number(), relative, baseY);
                    path.curveTo(x1, y1, x2, y2, x, y);
                }
                case 'A' -> {
                    double rx = Math.abs(in.number());
                    double ry = Math.abs(in.number());
                    double rotation = in.number();
                    boolean largeArc = in.number() != 0;
                    boolean sweep = in.number() != 0;
                    double endX = coordinate(in.number(), relative, x);
                    double endY = coordinate(in.number(), relative, y);
                    if (Math.abs(rotation) > 1e-9) {
                        throw new IllegalArgumentException(
                                "rotated arcs are outside the provider SVG profile");
                    }
                    appendArc(path, x, y, rx, ry, largeArc, sweep, endX, endY);
                    x = endX;
                    y = endY;
                }
                case 'Z' -> {
                    path.closePath();
                    x = startX;
                    y = startY;
                }
                default -> throw new IllegalArgumentException(
                        "unsupported provider path command '" + command + "'");
            }
        }
        return path;
    }

    private static double coordinate(double value, boolean relative, double origin) {
        return relative ? origin + value : value;
    }

    /** SVG endpoint arc parameters converted to Java2D's centre parameterization. */
    private static void appendArc(Path2D.Double path, double x0, double y0,
                                  double rx, double ry, boolean largeArc, boolean sweep,
                                  double x1, double y1) {
        if (rx == 0 || ry == 0) {
            path.lineTo(x1, y1);
            return;
        }
        if (x0 == x1 && y0 == y1) {
            return;
        }

        double dx2 = (x0 - x1) / 2.0;
        double dy2 = (y0 - y1) / 2.0;
        double lambda = dx2 * dx2 / (rx * rx) + dy2 * dy2 / (ry * ry);
        if (lambda > 1) {
            double scale = Math.sqrt(lambda);
            rx *= scale;
            ry *= scale;
        }

        double rxSquared = rx * rx;
        double rySquared = ry * ry;
        double numerator = rxSquared * rySquared
                - rxSquared * dy2 * dy2 - rySquared * dx2 * dx2;
        double denominator = rxSquared * dy2 * dy2 + rySquared * dx2 * dx2;
        double coefficient = (largeArc == sweep ? -1 : 1)
                * Math.sqrt(Math.max(0, numerator / denominator));
        double centreX = coefficient * rx * dy2 / ry + (x0 + x1) / 2.0;
        double centreY = -coefficient * ry * dx2 / rx + (y0 + y1) / 2.0;
        double startAngle = Math.atan2((y0 - centreY) / ry, (x0 - centreX) / rx);
        double endAngle = Math.atan2((y1 - centreY) / ry, (x1 - centreX) / rx);
        double extent = endAngle - startAngle;
        if (sweep && extent < 0) {
            extent += 2 * Math.PI;
        } else if (!sweep && extent > 0) {
            extent -= 2 * Math.PI;
        }
        path.append(new Arc2D.Double(centreX - rx, centreY - ry, 2 * rx, 2 * ry,
                -Math.toDegrees(startAngle), -Math.toDegrees(extent), Arc2D.OPEN), true);
    }

    /** Token cursor for SVG path numbers, including compact sign-separated values. */
    private static final class Cursor {
        private final String data;
        private int position;

        private Cursor(String data) {
            this.data = data;
        }

        boolean hasMore() {
            skipSeparators();
            return position < data.length();
        }

        char command() {
            skipSeparators();
            char value = data.charAt(position);
            if (Character.isLetter(value)) {
                position++;
                return value;
            }
            return 0;
        }

        double number() {
            skipSeparators();
            int start = position;
            if (position < data.length()
                    && (data.charAt(position) == '-' || data.charAt(position) == '+')) {
                position++;
            }
            boolean digit = consumeDigits();
            if (position < data.length() && data.charAt(position) == '.') {
                position++;
                digit |= consumeDigits();
            }
            if (!digit) {
                throw new IllegalArgumentException(
                        "expected number at " + start + " in: " + data);
            }
            if (position < data.length()
                    && (data.charAt(position) == 'e' || data.charAt(position) == 'E')) {
                int exponent = position++;
                if (position < data.length()
                        && (data.charAt(position) == '-' || data.charAt(position) == '+')) {
                    position++;
                }
                if (!consumeDigits()) {
                    throw new IllegalArgumentException(
                            "malformed exponent at " + exponent + " in: " + data);
                }
            }
            return Double.parseDouble(data.substring(start, position));
        }

        private boolean consumeDigits() {
            int start = position;
            while (position < data.length() && Character.isDigit(data.charAt(position))) {
                position++;
            }
            return position > start;
        }

        private void skipSeparators() {
            while (position < data.length()
                    && (Character.isWhitespace(data.charAt(position))
                    || data.charAt(position) == ',')) {
                position++;
            }
        }
    }
}
