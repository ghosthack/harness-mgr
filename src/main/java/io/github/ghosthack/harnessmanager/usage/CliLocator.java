package io.github.ghosthack.harnessmanager.usage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds harness CLIs even when a macOS app launch supplies a minimal PATH. */
final class CliLocator {

    static String find(String executable) {
        Path direct = Path.of(executable);
        if (direct.isAbsolute() && Files.isExecutable(direct)) {
            return direct.toString();
        }

        List<Path> directories = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    directories.add(Path.of(entry));
                }
            }
        }
        Path home = Path.of(System.getProperty("user.home"));
        directories.add(home.resolve(".local/bin"));
        directories.add(home.resolve(".cargo/bin"));
        directories.add(Path.of("/opt/homebrew/bin"));
        directories.add(Path.of("/usr/local/bin"));
        directories.add(Path.of("/usr/bin"));

        for (Path directory : directories) {
            Path candidate = directory.resolve(executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException(executable + " is not installed or is not on PATH");
    }

    private CliLocator() {
    }
}
