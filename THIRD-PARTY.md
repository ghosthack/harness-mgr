# Third-party dependencies

The MIT license covers Harness Manager's original code. Dependencies retain their
own licenses. Release packages include controlling texts and notices in
`THIRD-PARTY-LICENSES`, plus the exact Java runtime's `legal/` tree in each
platform's `harness-manager-X.Y.Z-PLATFORM-licenses.zip`.

| Component | Version | License | Purpose |
|---|---|---|---|
| Azul Zulu OpenJDK | 26 | OpenJDK licenses, including GPLv2 with Classpath Exception; runtime notices bundled | self-contained Java runtime |
| JediTerm core and UI fork | 3.76-ghosthack.2 | Apache-2.0 option of the upstream dual license; original notices retained | terminal model and Swing widget |
| jmux | 0.1.0 | MIT; its dependencies retain their own licenses | persistent sessions and Windows ConPTY backend |
| harness-stim | 0.1.0 | MIT | separate stimulator worker |
| FlatLaf | 3.6 | Apache-2.0 | Swing look and feel |
| pty4j | 0.13.10 | EPL-1.0 and retained native-component notices | native PTY support |
| Jackson databind, core, annotations and JSR-310 | 2.18.2 | Apache-2.0 with retained parser notices | JSON and date/time serialization |
| SLF4J API and simple provider | 2.0.17 | MIT | logging |
| Kotlin standard library | 2.4.0 | Apache-2.0 | JediTerm runtime |
| JNA and JNA platform | 5.14.0 | Apache-2.0/LGPL-2.1 dual license | native access |
| JetBrains annotations | 24.0.1 | Apache-2.0 | dependency annotations |

JUnit Jupiter 5.11.4 is test-only (EPL-2.0), and is not included in the application.
Exact legal metadata is preserved from each resolved runtime dependency JAR.
The source tree also retains JediTerm, pty4j, JNA, Jackson and SLF4J license texts
under `licenses/`. Upstream copyright contacts remain intact.

## Source availability

The original dependency sources are available from their public repositories
and source artifacts: [JediTerm](https://github.com/ghosthack/jediterm),
[jmux](https://github.com/ghosthack/pseudo-terminal-session-service),
[harness-stim](https://github.com/ghosthack/harness-stim),
[pty4j](https://github.com/JetBrains/pty4j),
[FlatLaf](https://github.com/JFormDesigner/FlatLaf),
[Jackson](https://github.com/FasterXML), [SLF4J](https://github.com/qos-ch/slf4j),
[Kotlin](https://github.com/JetBrains/kotlin), and [JNA](https://github.com/java-native-access/jna).
The pty4j source corresponding to the packaged dependency is version 0.13.10.

## Provider icons and external tools

Provider SVGs are included to identify their corresponding entries. Their
provenance and trademark notices are retained in
`src/main/resources/io/github/ghosthack/harnessmanager/ui/provider-icons/NOTICE.md`
and in each installed license bundle. Inclusion does not imply endorsement.

Coding-agent CLIs, tmux and SSH are separate installed programs and are not
bundled or relicensed by this project.
