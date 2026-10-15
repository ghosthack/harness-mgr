package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReticuleTest {

    @Test
    void halfIsACheckerboardOfTheBackgroundOverClearPixels() {
        Color bg = new Color(0x12, 0x34, 0x56);
        BufferedImage cell = Reticule.half(bg).getImage();
        for (int y = 0; y < cell.getHeight(); y++) {
            for (int x = 0; x < cell.getWidth(); x++) {
                int expected = (x + y) % 2 == 0 ? bg.getRGB() : 0x00000000;
                assertEquals(expected, cell.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void veilIsBuiltOncePerBackgroundColour() {
        TexturePaint first = Reticule.half(Color.WHITE);
        assertSame(first, Reticule.half(new Color(0xFF, 0xFF, 0xFF)));
    }
}
