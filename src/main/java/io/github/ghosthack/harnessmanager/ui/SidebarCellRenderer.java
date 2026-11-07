package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.state.SessionState;
import io.github.ghosthack.harnessmanager.stimulator.StimulatorClient;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.SwingConstants;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Sidebar row rendering, laid out like a macOS source list:
 *
 * <ul>
 *   <li><b>Avatar</b> (left): the harness's glyph on a soft rounded tile, with the
 *   session's status as a presence badge on the tile's corner — green working (it
 *   breathes while attached), gray idle, a hollow ring while the first poll is
 *   pending, a cross for a dead session, whose whole avatar also fades.</li>
 *   <li><b>Text</b> (middle): the session's name, and under it the project path in a
 *   muted, smaller face, clipped from the left so the project folder survives.</li>
 *   <li><b>Marks</b> (right): a heartbeat and stimuli count on a stimulated session, a
 *   Wi-Fi mark on a remote one, and an attention pill only for a session waiting on the
 *   human, the one state worth interrupting for. Everything calm stays quiet.</li>
 * </ul>
 *
 * <p>An unmanaged row has an empty, faded tile and no badge: nothing knows what an
 * un-adopted session runs. Selection and hover are rounded fills inset from the list's edges rather than
 * full-width bars. With folder grouping on (see {@link #setFolderGrouping}), each run of
 * rows sharing a project folder is headed once by the folder's name, its parent path and
 * its session count, and its rows drop their own path line.
 *
 * <p>When the sidebar-reticule setting is enabled, a live row that isn't attached yet
 * is veiled with the {@link Reticule} screen door.
 */
final class SidebarCellRenderer extends JPanel implements ListCellRenderer<SidebarEntry> {

    /** Client property on the list holding the hovered row's index (an Integer), kept
     * up to date by the list's mouse listeners; absent when the pointer is elsewhere. */
    static final String HOVER_INDEX = "harnessmanager.sidebar.hoverIndex";

    static final Color WORKING = new Color(0x30, 0xD1, 0x58);
    static final Color WAITING = new Color(0xFF, 0x9F, 0x0A);
    static final Color IDLE = new Color(0x8E, 0x8E, 0x93);

    private static final Icon WORKING_BADGE = new Badge(Badge.Kind.DOT, WORKING);
    private static final Icon PULSING_WORKING_BADGE = new Pulsing(WORKING_BADGE);
    private static final Icon WAITING_BADGE = new Badge(Badge.Kind.DOT, WAITING);
    private static final Icon IDLE_BADGE = new Badge(Badge.Kind.DOT, IDLE);
    private static final Icon PENDING_BADGE = new Badge(Badge.Kind.RING, IDLE);
    private static final Icon DEAD_BADGE = new Badge(Badge.Kind.CROSS, IDLE);
    private static final Icon RETICULATED_DEAD_BADGE = new Reticulated(DEAD_BADGE);
    private static final Icon HEARTBEAT_ICON = LucidIcon.heartbeat(16);
    private static final Icon REMOTE_ICON = LucidIcon.wifi(16);
    private static final Color STIMULATED = new Color(0xE0, 0x5A, 0x6F);
    private static final int MARK_GAP = 6;

    /** The rounded selection/hover fill sits this far in from the list's sides. */
    private static final int PILL_INSET_X = 6;
    /** …and this far from the row's top and bottom, leaving a hairline between pills. */
    private static final int PILL_INSET_Y = 2;
    private static final int PILL_ARC = 10;
    /** Content inset from the list's sides: the pill inset plus the pill's own padding. */
    private static final int CONTENT_INSET_X = PILL_INSET_X + 8;
    private static final int ROW_PAD_Y = 6;
    private static final int GROUPED_ROW_PAD_Y = 4;
    private static final int GROUP_GAP = 12;

    private final Avatar avatar = new Avatar();
    private final JLabel avatarLabel = new JLabel(avatar);
    private final ClippedLabel name = new ClippedLabel(false);
    private final ClippedLabel detail = new ClippedLabel(true);
    private final GroupHeader groupHeader = new GroupHeader();
    private final JLabel heartbeat = new JLabel();
    private final JLabel stimuliCount = new JLabel();
    private final JLabel remote = new JLabel();
    private final AttentionPill pill = new AttentionPill();
    private final JPanel marks = new JPanel();

    private boolean reticuleEnabled;
    private boolean folderGrouping;

    /** Per-cell paint state, set in {@link #getListCellRendererComponent}. */
    private boolean veiled;
    private Color pillFill;

    SidebarCellRenderer() {
        this(false);
    }

    SidebarCellRenderer(boolean reticuleEnabled) {
        super(new BorderLayout(10, 0));
        this.reticuleEnabled = reticuleEnabled;
        // titles and paths are arbitrary text: never let a "<html>" prefix become markup
        name.putClientProperty("html.disable", Boolean.TRUE);
        detail.putClientProperty("html.disable", Boolean.TRUE);
        groupHeader.putClientProperty("html.disable", Boolean.TRUE);
        groupHeader.setVisible(false);
        add(groupHeader, BorderLayout.NORTH);
        avatarLabel.setVerticalAlignment(SwingConstants.CENTER);
        add(avatarLabel, BorderLayout.WEST);
        JPanel lines = new JPanel();
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        lines.setOpaque(false);
        name.setAlignmentX(LEFT_ALIGNMENT);
        detail.setAlignmentX(LEFT_ALIGNMENT);
        detail.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));
        lines.add(javax.swing.Box.createVerticalGlue());
        lines.add(name);
        lines.add(detail);
        lines.add(javax.swing.Box.createVerticalGlue());
        add(lines, BorderLayout.CENTER);
        marks.setOpaque(false);
        // GridBagLayout, not BoxLayout: it centers each visible mark at its full height, where
        // BoxLayout squeezes a pill that is the row's only visible mark to half its height
        marks.setLayout(new java.awt.GridBagLayout());
        for (JLabel mark : new JLabel[]{heartbeat, stimuliCount, remote}) {
            mark.setBorder(BorderFactory.createEmptyBorder(0, MARK_GAP, 0, 0));
            marks.add(mark);
        }
        pill.setBorder(BorderFactory.createEmptyBorder(0, MARK_GAP, 0, 0));
        marks.add(pill);
        add(marks, BorderLayout.EAST);
    }

    void setReticuleEnabled(boolean enabled) {
        reticuleEnabled = enabled;
    }

    void setFolderGrouping(boolean enabled) {
        folderGrouping = enabled;
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends SidebarEntry> list, SidebarEntry entry,
                                                  int index, boolean isSelected, boolean cellHasFocus) {
        Color background = list.getBackground();
        boolean hovered = !isSelected && Integer.valueOf(index).equals(list.getClientProperty(HOVER_INDEX));
        pillFill = isSelected ? list.getSelectionBackground()
                : hovered ? blend(list.getForeground(), background, 0.07) : null;
        Color rowBackground = pillFill != null ? pillFill : background;
        Color foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
        Color muted = blend(foreground, rowBackground, isSelected ? 0.72 : 0.55);
        boolean dead = entry.status() == SidebarEntry.Status.DEAD;

        setOpaque(true);
        setBackground(background);
        setForeground(foreground);

        Font font = list.getFont();
        name.setFont(font);
        name.setForeground(dead ? muted : foreground);
        name.setText(entry.nameText());
        detail.setFont(font.deriveFont(font.getSize2D() - 2f));
        detail.setForeground(muted);
        detail.setText(entry.detailText());

        // null for an unmanaged row: nothing knows what an un-adopted session runs
        avatar.glyph = entry.harnessType() == null ? null : HarnessIcons.forType(entry.harnessType());
        avatar.badge = statusIcon(entry, reticuleEnabled);
        avatar.faded = entry.status() != SidebarEntry.Status.LIVE;
        avatar.ring = rowBackground;
        avatar.tile = blend(foreground, rowBackground, isDark(rowBackground) ? 0.13 : 0.08);
        avatarLabel.setForeground(foreground);

        boolean waiting = !dead && entry.state() == SessionState.WAITING_FOR_INPUT;
        pill.setVisible(waiting);
        pill.setFont(font.deriveFont(Font.BOLD, font.getSize2D() - 2.5f));
        // a white chip on the saturated accent selection; amber everywhere else,
        // including the gray selection an unfocused list shows
        pill.onAccent = isSelected && saturation(rowBackground) > 0.35f;
        pill.rowBackground = rowBackground;

        Color stimulatedTint = isSelected ? foreground : STIMULATED;
        setMark(heartbeat, entry.isStimulated() ? HEARTBEAT_ICON : null, stimulatedTint);
        StimulatorClient.StimuliStatus stimuli = entry.stimuliStatus();
        setMark(stimuliCount, stimuli == null ? null : new StimuliCountIcon(stimuli.displayCount()), stimulatedTint);
        boolean isRemote = entry.sessionEntry() != null && entry.sessionEntry().isRemote();
        setMark(remote, isRemote ? REMOTE_ICON : null, muted);
        marks.setVisible(waiting || heartbeat.isVisible() || stimuliCount.isVisible() || remote.isVisible());

        veiled = reticuleEnabled && entry.status() == SidebarEntry.Status.LIVE && !entry.isAttached();
        applyFolderGroup(list, entry, index, background);
        return this;
    }

    /** An absent mark is hidden, so it leaves no gap behind. */
    private static void setMark(JLabel mark, Icon icon, Color tint) {
        mark.setIcon(icon);
        mark.setForeground(tint);
        mark.setVisible(icon != null);
    }

    /**
     * Heads a same-folder run once and drops the repeated path from its rows. Group
     * boundaries come from the neighbouring rows of the list model, so a group is
     * exactly a maximal run of rows sharing a {@link SidebarView#folderGroupKey}.
     */
    private void applyFolderGroup(JList<? extends SidebarEntry> list, SidebarEntry entry, int index,
                                  Color background) {
        ListModel<? extends SidebarEntry> model = list.getModel();
        String key = SidebarView.folderGroupKey(entry);
        boolean grouped = folderGrouping && !key.isEmpty();
        boolean groupStart = grouped && !key.equals(groupKeyAt(model, index - 1));

        groupHeader.setVisible(groupStart);
        groupHeader.setText(groupStart ? entry.abbreviatedPath() : "");
        if (groupStart) {
            int count = 1;
            while (key.equals(groupKeyAt(model, index + count))) {
                count++;
            }
            groupHeader.count = count;
            Font font = list.getFont();
            groupHeader.setFont(font.deriveFont(Font.BOLD, font.getSize2D() - 2f));
            groupHeader.secondaryFont = font.deriveFont(Font.PLAIN, font.getSize2D() - 2f);
            // the heading sits on the list, never inside a selection pill
            groupHeader.setForeground(blend(list.getForeground(), background, 0.78));
            groupHeader.secondary = blend(list.getForeground(), background, 0.5);
            groupHeader.setBorder(BorderFactory.createEmptyBorder(index == 0 ? 6 : GROUP_GAP, 0, 3, 0));
        }
        detail.setVisible(!grouped);
        int pad = grouped ? GROUPED_ROW_PAD_Y : ROW_PAD_Y;
        setBorder(BorderFactory.createEmptyBorder(groupStart ? 0 : pad, CONTENT_INSET_X,
                pad, CONTENT_INSET_X));
        // with the header on top, the row's own top padding moves below the header
        avatarLabel.setBorder(groupStart ? BorderFactory.createEmptyBorder(pad, 0, 0, 0) : null);
        marks.setBorder(groupStart ? BorderFactory.createEmptyBorder(pad, 0, 0, 0) : null);
        ((JComponent) name.getParent()).setBorder(
                groupStart ? BorderFactory.createEmptyBorder(pad, 0, 0, 0) : null);
    }

    /** Empty past either end of the model, so the first and last rows read as
     * boundaries without a separate index check. */
    private static String groupKeyAt(ListModel<? extends SidebarEntry> model, int index) {
        if (index < 0 || index >= model.getSize()) {
            return "";
        }
        return SidebarView.folderGroupKey(model.getElementAt(index));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (pillFill == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(pillFill);
        int top = groupHeader.isVisible() ? groupHeader.getY() + groupHeader.getHeight() : 0;
        g2.fill(new RoundRectangle2D.Float(PILL_INSET_X, top + PILL_INSET_Y,
                getWidth() - 2 * PILL_INSET_X, getHeight() - top - 2 * PILL_INSET_Y, PILL_ARC, PILL_ARC));
        g2.dispose();
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        // over paint(), not paintComponent(): the veil must land on top of the children
        if (veiled) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(Reticule.half(getBackground()));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    static Icon statusIcon(SidebarEntry entry) {
        return statusIcon(entry, false);
    }

    /** The presence badge for a row; the same box size for every state, so a pending
     * ring turning into a dot never shifts anything. */
    static Icon statusIcon(SidebarEntry entry, boolean reticuleEnabled) {
        if (entry.status() == SidebarEntry.Status.DEAD) {
            return reticuleEnabled ? RETICULATED_DEAD_BADGE : DEAD_BADGE;
        }
        if (entry.status() != SidebarEntry.Status.LIVE) {
            return null;
        }
        if (entry.state() == null) {
            return PENDING_BADGE;
        }
        return switch (entry.state()) {
            // attached rows' working badges breathe; a not-attached row's stays still
            case WORKING -> entry.isAttached() ? PULSING_WORKING_BADGE : WORKING_BADGE;
            case WAITING_FOR_INPUT -> WAITING_BADGE;
            case IDLE -> IDLE_BADGE;
        };
    }

    // ------------------------------------------------------------------
    // Colour helpers
    // ------------------------------------------------------------------

    /** {@code amount} of {@code top} over {@code base}, as an opaque colour. */
    static Color blend(Color top, Color base, double amount) {
        double a = Math.clamp(amount, 0, 1);
        return new Color(
                (int) Math.round(top.getRed() * a + base.getRed() * (1 - a)),
                (int) Math.round(top.getGreen() * a + base.getGreen() * (1 - a)),
                (int) Math.round(top.getBlue() * a + base.getBlue() * (1 - a)));
    }

    static float saturation(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[1];
    }

    static boolean isDark(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) < 128;
    }

    private static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    // ------------------------------------------------------------------
    // Pieces
    // ------------------------------------------------------------------

    /** The harness glyph on a rounded tile, with the status badge on its corner. The
     * badge sits in a ring of the row's own background so it reads as cut out of the
     * tile, in either theme and under a selection. */
    private static final class Avatar implements Icon {
        private static final int TILE = 26;
        private static final int BADGE = 12;
        private static final int RING = 2;
        private static final int SIZE = TILE + 4;

        Icon glyph;
        Icon badge;
        boolean faded;
        Color ring;
        Color tile;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = smooth(g);
            if (faded) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            }
            g2.setColor(tile);
            g2.fill(new RoundRectangle2D.Float(x, y, TILE, TILE, 9, 9));
            if (glyph != null) {
                glyph.paintIcon(c, g2, x + (TILE - glyph.getIconWidth()) / 2,
                        y + (TILE - glyph.getIconHeight()) / 2);
            }
            g2.dispose();
            if (badge != null) {
                Graphics2D b = smooth(g);
                int bx = x + TILE - BADGE / 2 - 1;
                int by = y + TILE - BADGE / 2 - 1;
                b.setColor(ring);
                b.fill(new Ellipse2D.Float(bx - RING, by - RING, BADGE + 2 * RING, BADGE + 2 * RING));
                badge.paintIcon(c, b, bx, by);
                b.dispose();
            }
        }
    }

    /** One status mark in a fixed 12px box: a filled dot, a hollow ring (pending), or a
     * cross on a gray disc (dead). Drawn, never loaded, so it tracks any scale. */
    private static final class Badge implements Icon {
        enum Kind { DOT, RING, CROSS }

        private static final int SIZE = 12;

        private final Kind kind;
        private final Color color;

        Badge(Kind kind, Color color) {
            this.kind = kind;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = smooth(g);
            switch (kind) {
                case DOT -> {
                    g2.setColor(color);
                    g2.fill(new Ellipse2D.Float(x + 1, y + 1, SIZE - 2, SIZE - 2));
                }
                case RING -> {
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.6f));
                    g2.draw(new Ellipse2D.Float(x + 2, y + 2, SIZE - 4, SIZE - 4));
                }
                case CROSS -> {
                    g2.setColor(color);
                    g2.fill(new Ellipse2D.Float(x + 1, y + 1, SIZE - 2, SIZE - 2));
                    g2.setColor(isDark(color) ? Color.WHITE : new Color(0x2A, 0x2A, 0x2C));
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    float a = x + 4f, b = x + SIZE - 4f, t = y + 4f, u = y + SIZE - 4f;
                    g2.draw(new java.awt.geom.Line2D.Float(a, t, b, u));
                    g2.draw(new java.awt.geom.Line2D.Float(a, u, b, t));
                }
            }
            g2.dispose();
        }
    }

    /** Breathes another icon on a shared wall-clock cosine, so every pulsing badge beats
     * in sync; MainWindow's pulse timer repaints the rows that carry one. Never fades out
     * entirely, so the state stays legible at the trough. */
    private static final class Pulsing implements Icon {
        private static final int PERIOD_MS = 1400;

        private final Icon icon;

        Pulsing(Icon icon) {
            this.icon = icon;
        }

        @Override
        public int getIconWidth() {
            return icon.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return icon.getIconHeight();
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            double phase = (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
            float alpha = (float) (0.75 + 0.25 * Math.cos(2 * Math.PI * phase));
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            icon.paintIcon(c, g2, x, y);
            g2.dispose();
        }
    }

    /** Screens an icon behind the {@link Reticule} dither, scoped to its box. */
    private static final class Reticulated implements Icon {
        private final Icon icon;

        Reticulated(Icon icon) {
            this.icon = icon;
        }

        @Override
        public int getIconWidth() {
            return icon.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return icon.getIconHeight();
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            icon.paintIcon(c, g, x, y);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(Reticule.half(c.getBackground()));
            g2.fillRect(x, y, icon.getIconWidth(), icon.getIconHeight());
            g2.dispose();
        }
    }

    /** "Waiting", as an amber pill: the one row state that asks for the human. */
    private static final class AttentionPill extends JComponent {
        private static final String TEXT = "Waiting";

        boolean onAccent;
        Color rowBackground;

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            Insets in = getInsets();
            return new Dimension(fm.stringWidth(TEXT) + 14 + in.left + in.right,
                    fm.getHeight() + 4 + in.top + in.bottom);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            FontMetrics fm = g2.getFontMetrics(getFont());
            Insets in = getInsets();
            int w = fm.stringWidth(TEXT) + 14;
            int h = fm.getHeight() + 2;
            int x = getWidth() - in.right - w;
            int y = in.top + (getHeight() - in.top - in.bottom - h) / 2;
            Color background = rowBackground == null ? Color.BLACK : rowBackground;
            g2.setColor(onAccent ? new Color(255, 255, 255, 0x38)
                    : blend(WAITING, background, isDark(background) ? 0.22 : 0.16));
            g2.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
            g2.setFont(getFont());
            g2.setColor(onAccent ? Color.WHITE
                    : isDark(background) ? WAITING : WAITING.darker().darker());
            g2.drawString(TEXT, x + 7, y + (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /**
     * A folder group's heading: the folder's own name in the label's strong face, its
     * parent path after it in a lighter one (clipped from the left, so the nearest
     * parents survive), and the group's session count at the far right. {@link #getText}
     * stays the full abbreviated path, which is what the row stands for.
     */
    private static final class GroupHeader extends JLabel {
        int count;
        Font secondaryFont;
        Color secondary;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, super.getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            String text = getText();
            if (text == null || text.isEmpty()) {
                return;
            }
            int slash = Math.max(text.lastIndexOf('/', text.length() - 2),
                    text.lastIndexOf('\\', text.length() - 2));
            String folder = slash < 0 ? text : text.substring(slash + 1);
            String parent = slash <= 0 ? "" : text.substring(0, slash);
            Graphics2D g2 = smooth(g);
            Insets in = getInsets();
            FontMetrics strong = g2.getFontMetrics(getFont());
            FontMetrics light = g2.getFontMetrics(secondaryFont);
            int baseline = in.top + strong.getAscent();
            int left = in.left;
            int right = getWidth() - in.right;

            String countText = Integer.toString(count);
            int countWidth = light.stringWidth(countText);
            g2.setFont(secondaryFont);
            g2.setColor(secondary);
            g2.drawString(countText, right - countWidth, baseline);
            right -= countWidth + 10;

            String shownFolder = ClippedLabel.clipRight(folder, strong, right - left);
            g2.setFont(getFont());
            g2.setColor(getForeground());
            g2.drawString(shownFolder, left, baseline);
            int after = left + strong.stringWidth(shownFolder) + 6;
            if (!parent.isEmpty() && shownFolder.equals(folder) && right - after > light.stringWidth("…/")) {
                g2.setFont(secondaryFont);
                g2.setColor(secondary);
                g2.drawString(ClippedLabel.clipLeft(parent, light, right - after), after, baseline);
            }
            g2.dispose();
        }
    }

    /**
     * A label whose text never widens the list (preferred width 0) and clips to the
     * width it is given: on the right for names, on the left for paths, whose telling
     * part is the end.
     */
    static final class ClippedLabel extends JLabel {
        private final boolean clipLeft;

        ClippedLabel(boolean clipLeft) {
            this.clipLeft = clipLeft;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, super.getPreferredSize().height);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, super.getMinimumSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            String text = getText();
            if (text == null || text.isEmpty()) {
                return;
            }
            Graphics2D g2 = smooth(g);
            g2.setFont(getFont());
            g2.setColor(getForeground());
            FontMetrics fm = g2.getFontMetrics();
            Insets insets = getInsets();
            int available = getWidth() - insets.left - insets.right;
            String shown = clipLeft ? clipLeft(text, fm, available) : clipRight(text, fm, available);
            g2.drawString(shown, insets.left,
                    insets.top + fm.getAscent() + (getHeight() - insets.top - insets.bottom - fm.getHeight()) / 2);
            g2.dispose();
        }

        /** The longest tail that fits after a leading ellipsis. */
        static String clipLeft(String text, FontMetrics fm, int available) {
            if (fm.stringWidth(text) <= available) {
                return text;
            }
            int width = fm.stringWidth("…");
            int start = text.length();
            while (start > 0) {
                int next = text.offsetByCodePoints(start, -1);
                int w = fm.stringWidth(text.substring(next, start));
                if (width + w > available) {
                    break;
                }
                width += w;
                start = next;
            }
            return "…" + text.substring(start);
        }

        /** The longest head that fits before a trailing ellipsis. */
        static String clipRight(String text, FontMetrics fm, int available) {
            if (fm.stringWidth(text) <= available) {
                return text;
            }
            int width = fm.stringWidth("…");
            int end = 0;
            while (end < text.length()) {
                int next = text.offsetByCodePoints(end, 1);
                int w = fm.stringWidth(text.substring(end, next));
                if (width + w > available) {
                    break;
                }
                width += w;
                end = next;
            }
            return text.substring(0, end) + "…";
        }
    }

    /**
     * The stimuli counter riding alongside the heartbeat badge — stimuli left on a
     * bounded budget, or stimuli sent so far when the budget is unlimited and there is
     * nothing to count down from (see {@link StimulatorClient.StimuliStatus#displayCount}).
     * A thin rounded-rect outline with no fill, digits centered inside, both painted in
     * whatever color the label is tinted with — so the count reads as one more mark in
     * the row's icon language rather than a separate notification chip. Width is fixed
     * to fit three digits regardless of how many are actually shown, so the row doesn't
     * reflow as the count changes (9 -> 10 -> 100).
     */
    private static final class StimuliCountIcon implements Icon {
        private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        private static final int WIDTH = 24;
        private static final int HEIGHT = 14;
        private static final int ARC = 7;

        private final String text;

        StimuliCountIcon(int remaining) {
            this.text = Integer.toString(Math.min(remaining, 999));
        }

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.getForeground());
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(x, y, WIDTH - 1, HEIGHT - 1, ARC, ARC);
            g2.setFont(FONT);
            FontMetrics fm = g2.getFontMetrics();
            int textX = x + (WIDTH - fm.stringWidth(text)) / 2;
            int textY = y + (HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, textX, textY);
            g2.dispose();
        }
    }
}
