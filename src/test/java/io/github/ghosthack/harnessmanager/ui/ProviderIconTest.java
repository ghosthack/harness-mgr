package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderIconTest {

    @Test
    void everyProviderMarkLoadsAndPaintsInsideItsDeclaredBox() {
        int size = 16;
        int margin = 4;
        for (Icon icon : providerIcons(size)) {
            assertEquals(size, icon.getIconWidth());
            assertEquals(size, icon.getIconHeight());

            BufferedImage image = paint(icon, margin, Color.BLACK, true);
            assertTrue(inkCoverage(image) > 0, "provider icon painted nothing");
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    boolean inside = x >= margin && x < margin + size
                            && y >= margin && y < margin + size;
                    if (!inside) {
                        assertEquals(0, image.getRGB(x, y) >>> 24,
                                "ink outside the icon box at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void providerMarksRemainDistinctAtSidebarSize() {
        Icon[] icons = providerIcons(16);
        BufferedImage[] images = new BufferedImage[icons.length];
        for (int i = 0; i < icons.length; i++) {
            images[i] = paint(icons[i], 0, Color.BLACK, true);
        }
        for (int first = 0; first < images.length; first++) {
            for (int second = first + 1; second < images.length; second++) {
                assertTrue(differingPixels(images[first], images[second]) > 0,
                        "provider marks render identically");
            }
        }
    }

    @Test
    void providerMarksUseTheHostForegroundAndFadeWhenDisabled() {
        Color tint = new Color(24, 120, 210);
        for (Icon icon : providerIcons(24)) {
            BufferedImage enabled = paint(icon, 0, tint, true);
            BufferedImage disabled = paint(icon, 0, tint, false);

            int enabledPixel = mostOpaquePixel(enabled);
            int disabledPixel = mostOpaquePixel(disabled);
            assertEquals(tint.getRGB() & 0x00FFFFFF, enabledPixel & 0x00FFFFFF);
            assertColorClose(tint, disabledPixel, 1);
            assertEquals(0xFF, enabledPixel >>> 24);
            assertTrue((disabledPixel >>> 24) < 0xFF,
                    "disabled provider icon did not fade");
        }
    }

    private static Icon[] providerIcons(int size) {
        return new Icon[]{ProviderIcon.anthropic(size), ProviderIcon.openAi(size),
                ProviderIcon.cursor(size), ProviderIcon.grok(size), ProviderIcon.opencode(size),
                ProviderIcon.pi(size), ProviderIcon.muse(size)};
    }

    private static BufferedImage paint(Icon icon, int margin, Color foreground, boolean enabled) {
        BufferedImage image = new BufferedImage(icon.getIconWidth() + 2 * margin,
                icon.getIconHeight() + 2 * margin, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        JLabel host = new JLabel();
        host.setForeground(foreground);
        host.setEnabled(enabled);
        icon.paintIcon(host, graphics, margin, margin);
        graphics.dispose();
        return image;
    }

    private static int inkCoverage(BufferedImage image) {
        int ink = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    ink++;
                }
            }
        }
        return ink;
    }

    private static int differingPixels(BufferedImage first, BufferedImage second) {
        int differing = 0;
        for (int x = 0; x < first.getWidth(); x++) {
            for (int y = 0; y < first.getHeight(); y++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        return differing;
    }

    private static int mostOpaquePixel(BufferedImage image) {
        int best = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) > (best >>> 24)) {
                    best = pixel;
                }
            }
        }
        return best;
    }

    /** Alpha-premultiplied raster storage can round a disabled channel down by one. */
    private static void assertColorClose(Color expected, int actual, int tolerance) {
        assertTrue(Math.abs(expected.getRed() - ((actual >>> 16) & 0xFF)) <= tolerance);
        assertTrue(Math.abs(expected.getGreen() - ((actual >>> 8) & 0xFF)) <= tolerance);
        assertTrue(Math.abs(expected.getBlue() - (actual & 0xFF)) <= tolerance);
    }
}
