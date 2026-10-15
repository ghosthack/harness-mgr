package io.github.ghosthack.harnessmanager.harness;

import java.util.Locale;

/**
 * The one OS check the plugins need: npm shims on Windows run {@code node.exe} as the
 * shell's child, so the harnesses installed through npm report as {@code node} there
 * (see {@link ClaudeCodePlugin#ownsProcess} and {@link CodexPlugin#ownsProcess}).
 * Parameterised by the OS name so each plugin gets a unit test per platform.
 */
final class Platform {

    static boolean isWindows() {
        return isWindows(System.getProperty("os.name", ""));
    }

    static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private Platform() {
    }
}
