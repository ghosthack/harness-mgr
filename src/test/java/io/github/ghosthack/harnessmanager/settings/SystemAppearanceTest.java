package io.github.ghosthack.harnessmanager.settings;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SystemAppearanceTest {

    @Test
    void macReportsDarkOnlyWhenTheKeySaysSo() {
        assertEquals(AppSettings.UiTheme.DARK, SystemAppearance.parseMac(0, "Dark\n"));
        assertEquals(AppSettings.UiTheme.LIGHT, SystemAppearance.parseMac(1,
                "The domain/default pair of (kCFPreferencesAnyApplication, AppleInterfaceStyle) does not exist\n"));
    }

    @Test
    void windowsReadsAppsUseLightTheme() {
        String dark = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n"
                + "    AppsUseLightTheme    REG_DWORD    0x0\r\n\r\n";
        assertEquals(Optional.of(AppSettings.UiTheme.DARK), SystemAppearance.parseWindows(dark));
        assertEquals(Optional.of(AppSettings.UiTheme.LIGHT),
                SystemAppearance.parseWindows(dark.replace("0x0", "0x1")));
        assertEquals(Optional.empty(), SystemAppearance.parseWindows("ERROR: not found"));
    }

    @Test
    void gnomeCountsOnlyAnExplicitPreference() {
        assertEquals(Optional.of(AppSettings.UiTheme.DARK), SystemAppearance.parseGnome("'prefer-dark'\n"));
        assertEquals(Optional.of(AppSettings.UiTheme.LIGHT), SystemAppearance.parseGnome("'prefer-light'"));
        assertEquals(Optional.empty(), SystemAppearance.parseGnome("'default'"));
    }

    @Test
    void unknownPlatformsHaveNoDetector() {
        assertEquals(Optional.empty(), SystemAppearance.detect("Plan 9"));
    }
}
