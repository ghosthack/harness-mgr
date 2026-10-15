package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The icon paths are hand-authored on a 24-grid, so the risk is a typo the compiler
 * can't see: a path that won't parse, or one whose ink lands outside the icon's box.
 */
final class LucidIconTest {

    @Test
    void usageAndRefreshIconsUseTheRequestedSize() {
        assertEquals(16, LucidIcon.usage(16).getIconWidth());
        assertEquals(16, LucidIcon.refresh(16).getIconHeight());
    }

    @Test
    void everyIconParsesAndPaintsInsideItsBounds() {
        for (Icon icon : icons(16)) {
            assertEquals(16, icon.getIconWidth());
            assertEquals(16, icon.getIconHeight());
            assertTrue(inkCoverage(icon) > 0, "icon painted nothing");
        }
    }

    /** Painted with a margin: ink outside the declared box would collide with the row's
     * text, so the margin must stay clean. */
    @Test
    void iconStaysWithinItsDeclaredBox() {
        int margin = 4;
        int size = 16;
        for (Icon icon : icons(size)) {
            BufferedImage img = paint(icon, margin);
            for (int x = 0; x < img.getWidth(); x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    boolean insideBox = x >= margin && x < margin + size
                            && y >= margin && y < margin + size;
                    if (!insideBox) {
                        assertEquals(0xFFFFFFFF, img.getRGB(x, y),
                                "ink outside the icon box at " + x + "," + y);
                    }
                }
            }
        }
    }

    private static Icon[] icons(int size) {
        return new Icon[]{LucidIcon.moon(size), LucidIcon.sun(size), LucidIcon.shell(size),
                LucidIcon.settings(size), LucidIcon.usage(size), LucidIcon.refresh(size),
                LucidIcon.sort(size), LucidIcon.funnel(size),
                LucidIcon.panelLeftClose(size),
                LucidIcon.panelLeftOpen(size), LucidIcon.folderPlus(size),
                LucidIcon.folderOpen(size), LucidIcon.shellPlus(size),
                LucidIcon.duplicate(size), LucidIcon.heartbeat(size),
                LucidIcon.hourglass(size),
                LucidIcon.pageUp(size), LucidIcon.pageDown(size),
                LucidIcon.takeover(size), LucidIcon.detach(size), LucidIcon.x(size),
                LucidIcon.power(size), LucidIcon.zap(size), LucidIcon.bot(size),
                LucidIcon.code(size), LucidIcon.pi(size), LucidIcon.wifi(size)};
    }

    private static int inkCoverage(Icon icon) {
        BufferedImage img = paint(icon, 0);
        int ink = 0;
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                if (img.getRGB(x, y) != 0xFFFFFFFF) {
                    ink++;
                }
            }
        }
        return ink;
    }

    private static BufferedImage paint(Icon icon, int margin) {
        BufferedImage img = new BufferedImage(icon.getIconWidth() + 2 * margin,
                icon.getIconHeight() + 2 * margin, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        JLabel host = new JLabel();
        host.setForeground(Color.BLACK);
        icon.paintIcon(host, g, margin, margin);
        g.dispose();
        return img;
    }
}
