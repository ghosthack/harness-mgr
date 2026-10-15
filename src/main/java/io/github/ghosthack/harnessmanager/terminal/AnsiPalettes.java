package io.github.ghosthack.harnessmanager.terminal;

import com.jediterm.core.Color;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.emulator.ColorPaletteImpl;
import io.github.ghosthack.harnessmanager.settings.AppSettings;

/**
 * The JediTerm palette behind each {@link AppSettings.TerminalPalette} choice. A palette
 * covers only the 16 base ANSI slots — JediTerm resolves indexes 16-255 and truecolor
 * itself, so those look the same under every choice. Values for the non-JediTerm entries
 * are the stock profile colors of the terminal they're named after, which is what makes
 * plain ANSI output (prompts, ls, TUI accents) match that terminal.
 */
final class AnsiPalettes {

    static ColorPalette forSetting(AppSettings.TerminalPalette palette) {
        return switch (palette) {
            case MACOS_TERMINAL -> MACOS_TERMINAL;
            case ITERM2 -> ITERM2;
            case JEDITERM -> ColorPaletteImpl.XTERM_PALETTE;
        };
    }

    private static final ColorPalette MACOS_TERMINAL = fixed(
            0x000000, 0xC23621, 0x25BC24, 0xADAD27, 0x492EE1, 0xD338D3, 0x33BBC8, 0xCBCCCD,
            0x818383, 0xFC391F, 0x31E722, 0xEAEC23, 0x5833FF, 0xF935F8, 0x14F0F0, 0xE9EBEB);

    private static final ColorPalette ITERM2 = fixed(
            0x000000, 0xC91B00, 0x00C200, 0xC7C400, 0x0225C7, 0xCA30C7, 0x00C5C7, 0xC7C7C7,
            0x686868, 0xFF6E67, 0x5FFA68, 0xFFFC67, 0x6871FF, 0xFF77FF, 0x60FDFF, 0xFFFFFF);

    /** The same 16 RGB values for foreground and background use, as terminals do. */
    private static ColorPalette fixed(int... rgb) {
        Color[] colors = new Color[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            colors[i] = new Color(rgb[i]);
        }
        return new ColorPalette() {
            @Override
            protected Color getForegroundByColorIndex(int index) {
                return colors[index];
            }

            @Override
            protected Color getBackgroundByColorIndex(int index) {
                return colors[index];
            }
        };
    }

    private AnsiPalettes() {
    }
}
