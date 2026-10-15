package io.github.ghosthack.harnessmanager.ui;

import io.github.ghosthack.harnessmanager.settings.AppSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The theme -> AppKit appearance mapping and its platform guard. */
final class MacWindowAppearanceTest {

    private String saved;

    @BeforeEach
    void rememberProperty() {
        saved = System.getProperty(MacWindowAppearance.AWT_PROPERTY);
        System.clearProperty(MacWindowAppearance.AWT_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (saved == null) {
            System.clearProperty(MacWindowAppearance.AWT_PROPERTY);
        } else {
            System.setProperty(MacWindowAppearance.AWT_PROPERTY, saved);
        }
    }

    @Test
    void mapsEachThemeToItsAppKitAppearance() {
        assertEquals("NSAppearanceNameAqua",
                MacWindowAppearance.appearanceName(AppSettings.UiTheme.LIGHT));
        assertEquals("NSAppearanceNameDarkAqua",
                MacWindowAppearance.appearanceName(AppSettings.UiTheme.DARK));
    }

    @Test
    void setsTheAwtPropertyOnMac() {
        MacWindowAppearance.applyBeforeAwt(AppSettings.UiTheme.LIGHT, "Mac OS X");
        assertEquals("NSAppearanceNameAqua", System.getProperty(MacWindowAppearance.AWT_PROPERTY));

        MacWindowAppearance.applyBeforeAwt(AppSettings.UiTheme.DARK, "Mac OS X");
        assertEquals("NSAppearanceNameDarkAqua", System.getProperty(MacWindowAppearance.AWT_PROPERTY));
    }

    @Test
    void leavesThePropertyAloneOffMac() {
        MacWindowAppearance.applyBeforeAwt(AppSettings.UiTheme.LIGHT, "Windows 11");
        assertNull(System.getProperty(MacWindowAppearance.AWT_PROPERTY));

        MacWindowAppearance.applyBeforeAwt(AppSettings.UiTheme.DARK, "Linux");
        assertNull(System.getProperty(MacWindowAppearance.AWT_PROPERTY));
    }

    @Test
    void recognizesMacOsNames() {
        assertTrue(MacWindowAppearance.isMac("Mac OS X"));
        assertTrue(MacWindowAppearance.isMac("macOS"));
        assertFalse(MacWindowAppearance.isMac("Windows 11"));
        assertFalse(MacWindowAppearance.isMac("Linux"));
        assertFalse(MacWindowAppearance.isMac(null));
    }

    /** Never throws, whatever the platform — a failed chrome switch must not break the toggle. */
    @Test
    void applyIsSafeToCallAnywhere() {
        MacWindowAppearance.apply(AppSettings.UiTheme.LIGHT);
        MacWindowAppearance.apply(AppSettings.UiTheme.DARK);
    }
}
