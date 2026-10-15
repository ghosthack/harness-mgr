package io.github.ghosthack.harnessmanager.ui;

import java.nio.file.Path;

/**
 * Turns user-typed paths into absolute ones. No shell sits between the path fields and
 * tmux, so a leading {@code ~} must be expanded here — passed through literally it makes
 * the chooser and the session fall back to the OS default home directory.
 */
final class UserPath {

    static Path expand(String text) {
        String stripped = text.strip();
        if (stripped.equals("~")) {
            return home();
        }
        if (stripped.startsWith("~/")) {
            return home().resolve(stripped.substring(2));
        }
        return Path.of(stripped);
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private UserPath() {
    }
}
