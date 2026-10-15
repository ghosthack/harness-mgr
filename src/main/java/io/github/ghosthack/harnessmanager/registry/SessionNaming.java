package io.github.ghosthack.harnessmanager.registry;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Derives {@code hm-}-prefixed tmux session names that are safe and, given a collision
 * check, unique. */
public final class SessionNaming {

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_-]+");

    /**
     * Base name from a project directory, e.g. {@code /Users/x/Projects/foo} -> {@code hm-foo}.
     * {@code taken} should report whether a candidate name is already in use (registry entry
     * or live tmux session) so the caller can suffix {@code -2}, {@code -3}, ... until free.
     */
    public static String deriveUnique(Path projectPath, Predicate<String> taken) {
        Path base = projectPath.toAbsolutePath().normalize();
        String rawBasename = base.getFileName() != null ? base.getFileName().toString() : "root";
        String sanitized = UNSAFE.matcher(rawBasename).replaceAll("-");
        if (sanitized.isBlank()) {
            sanitized = "session";
        }
        String candidate = Reconciler.SESSION_PREFIX + sanitized;
        if (!taken.test(candidate)) {
            return candidate;
        }
        for (int suffix = 2; suffix < 10_000; suffix++) {
            String next = candidate + "-" + suffix;
            if (!taken.test(next)) {
                return next;
            }
        }
        throw new IllegalStateException("could not derive a unique session name for " + projectPath);
    }

    private SessionNaming() {
    }
}
