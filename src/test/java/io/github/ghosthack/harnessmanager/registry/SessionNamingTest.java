package io.github.ghosthack.harnessmanager.registry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SessionNamingTest {

    @Test
    void derivesFromBasename() {
        String name = SessionNaming.deriveUnique(Path.of("/Users/x/Projects/foo"), taken -> false);
        assertEquals("hm-foo", name);
    }

    @Test
    void sanitizesUnsafeCharacters() {
        String name = SessionNaming.deriveUnique(Path.of("/Users/x/Projects/my project!"), taken -> false);
        assertEquals("hm-my-project-", name);
    }

    @Test
    void suffixesOnCollision() {
        Set<String> existing = Set.of("hm-foo", "hm-foo-2");
        String name = SessionNaming.deriveUnique(Path.of("/Users/x/Projects/foo"), existing::contains);
        assertEquals("hm-foo-3", name);
    }
}
