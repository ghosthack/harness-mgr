package io.github.ghosthack.harnessmanager.settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Asks the OS whether it is in dark or light mode. Empty when the platform has no
 * detector or the query fails, so callers fall back to their own default.
 */
final class SystemAppearance {

    private static final long TIMEOUT_MS = 2000;

    /** Queried once per launch: terminal attaches read the theme repeatedly. */
    private static final class Holder {
        static final Optional<AppSettings.UiTheme> DETECTED =
                detect(System.getProperty("os.name", ""));
    }

    static Optional<AppSettings.UiTheme> detected() {
        return Holder.DETECTED;
    }

    static Optional<AppSettings.UiTheme> detect(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                return mac();
            }
            if (os.contains("win")) {
                return windows();
            }
            if (os.contains("linux")) {
                return linux();
            }
        } catch (IOException | RuntimeException e) {
            // no usable detector: the caller's default applies
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    private static Optional<AppSettings.UiTheme> mac() throws IOException, InterruptedException {
        // The key exists only in dark mode; "does not exist" (exit 1) means light.
        Result result = run(List.of("defaults", "read", "-g", "AppleInterfaceStyle"));
        if (result == null) {
            return Optional.empty();
        }
        return Optional.of(parseMac(result.exitCode, result.output));
    }

    static AppSettings.UiTheme parseMac(int exitCode, String output) {
        return exitCode == 0 && output.trim().equalsIgnoreCase("dark")
                ? AppSettings.UiTheme.DARK
                : AppSettings.UiTheme.LIGHT;
    }

    private static Optional<AppSettings.UiTheme> windows() throws IOException, InterruptedException {
        Result result = run(List.of("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme"));
        return result == null || result.exitCode != 0 ? Optional.empty() : parseWindows(result.output);
    }

    static Optional<AppSettings.UiTheme> parseWindows(String output) {
        for (String line : output.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 3 && fields[0].equalsIgnoreCase("AppsUseLightTheme")) {
                String value = fields[fields.length - 1];
                if (value.equalsIgnoreCase("0x0")) {
                    return Optional.of(AppSettings.UiTheme.DARK);
                }
                if (value.equalsIgnoreCase("0x1")) {
                    return Optional.of(AppSettings.UiTheme.LIGHT);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<AppSettings.UiTheme> linux() throws IOException, InterruptedException {
        Result result = run(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
        return result == null || result.exitCode != 0 ? Optional.empty() : parseGnome(result.output);
    }

    /** Only an explicit preference counts: "default" means nothing outside GNOME. */
    static Optional<AppSettings.UiTheme> parseGnome(String output) {
        String value = output.trim().replace("'", "");
        if (value.equals("prefer-dark")) {
            return Optional.of(AppSettings.UiTheme.DARK);
        }
        if (value.equals("prefer-light")) {
            return Optional.of(AppSettings.UiTheme.LIGHT);
        }
        return Optional.empty();
    }

    private record Result(int exitCode, String output) {
    }

    private static Result run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            return null;
        }
        try (InputStream input = process.getInputStream()) {
            return new Result(process.exitValue(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private SystemAppearance() {
    }
}
