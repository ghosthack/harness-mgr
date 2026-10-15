package io.github.ghosthack.harnessmanager.ui;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The not-attached dither for a sidebar row — text-browser's mosaic reticule ported to
 * Swing: the 8-bit trick of faking a veil with a screen door rather than a translucent
 * layer.
 *
 * <p>Text-browser's scale reads "more dither, less behind it"; this is its checkerboard
 * — the density it uses for <em>empty</em> — because a not-attached row has nothing
 * behind it yet either: no terminal exists until the row is first selected. The mark
 * pixels take the row's own background colour, so the veil screens the row's text and
 * icons toward whatever the theme (or the selection highlight) paints behind them,
 * while leaving every other pixel intact — dimmed, but still legible enough to pick
 * the session to attach.</p>
 *
 * <p>Each {@link TexturePaint} is a 4×4 motif in logical pixels, built once per
 * background colour. Only ever touched from the EDT during a paint, so the cache is
 * deliberately unsynchronized.</p>
 */
final class Reticule {

    /** Cell edge in logical pixels. Small enough to read as texture, not stripes. */
    private static final int CELL = 4;

    /** One paint per row background: the list's, the selection's, per theme. */
    private static final Map<Color, TexturePaint> CACHE = new HashMap<>();

    private Reticule() {
    }

    /** The 50% checkerboard veil in {@code background}, built on first use. */
    static TexturePaint half(Color background) {
        return CACHE.computeIfAbsent(background, bg -> {
            var cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < CELL; y++) {
                for (int x = 0; x < CELL; x++) {
                    if ((x + y) % 2 == 0) {
                        cell.setRGB(x, y, bg.getRGB());
                    }
                }
            }
            return new TexturePaint(cell, new Rectangle(0, 0, CELL, CELL));
        });
    }
}
