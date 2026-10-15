package io.github.ghosthack.harnessmanager.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserPathTest {

    private final Path home = Path.of(System.getProperty("user.home"));

    @Test
    void expandsBareTilde() {
        assertEquals(home, UserPath.expand("~"));
    }

    @Test
    void expandsTildePrefix() {
        assertEquals(home.resolve("Projects/foo"), UserPath.expand("~/Projects/foo"));
    }

    @Test
    void leavesAbsolutePathsAlone() {
        assertEquals(Path.of("/opt/work"), UserPath.expand("/opt/work"));
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertEquals(home.resolve("Projects"), UserPath.expand("  ~/Projects  "));
    }

    @Test
    void doesNotExpandTildeElsewhere() {
        assertEquals(Path.of("/tmp/~x"), UserPath.expand("/tmp/~x"));
    }
}
