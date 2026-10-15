package io.github.ghosthack.harnessmanager.ui;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Loads the application mark for unpackaged Swing launches and native taskbars. */
final class AppIcon {

    private static final String RESOURCE =
            "/io/github/ghosthack/harnessmanager/ui/app-icon.png";
    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128, 256};
    private static final List<Image> IMAGES = loadImages();

    static void applyTo(JFrame frame) {
        if (IMAGES.isEmpty()) {
            return;
        }
        frame.setIconImages(IMAGES);

        // Native jpackage bundles get their icon from --icon; replacing it at runtime would
        // swap the Dock icon after launch. java -jar launches still need it set here.
        if (System.getProperty("jpackage.app-path") != null) {
            return;
        }
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(IMAGES.getLast());
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // The frame icon still covers window managers without taskbar integration.
        }
    }

    static List<Image> images() {
        return IMAGES;
    }

    private static List<Image> loadImages() {
        try (InputStream input = AppIcon.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return List.of();
            }
            BufferedImage master = ImageIO.read(input);
            if (master == null) {
                return List.of();
            }
            List<Image> images = new ArrayList<>(SIZES.length);
            for (int size : SIZES) {
                images.add(scaledCopy(master, size));
            }
            return List.copyOf(images);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static BufferedImage scaledCopy(BufferedImage source, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private AppIcon() {
    }
}
