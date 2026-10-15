package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import java.awt.Image;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppIconTest {

    @Test
    void suppliesNativeWindowSizesFromTheBundledArtwork() {
        List<Image> images = AppIcon.images();

        assertEquals(List.of(16, 20, 24, 32, 48, 64, 128, 256),
                images.stream().map(image -> image.getWidth(null)).toList());
        assertEquals(images.stream().map(image -> image.getWidth(null)).toList(),
                images.stream().map(image -> image.getHeight(null)).toList());
    }
}
