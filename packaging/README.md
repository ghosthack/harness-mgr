# Harness Manager packaging

`scripts/package.sh` uses JDK 26's `jpackage` to build self-contained applications
for the host platform. Release CI uses native runners and Azul Zulu Java 26:

| Platform | Outputs |
|---|---|
| macOS arm64 | `harness-manager-X.Y.Z-macos-arm64.dmg` |
| Windows x64 | `harness-manager-X.Y.Z-windows-x64.exe` and `.zip` |
| Linux x64 | `harness-manager-X.Y.Z-linux-x64.deb` and `.tar.gz` |

Each platform also produces `harness-manager-X.Y.Z-PLATFORM-licenses.zip`.
The publishing job adds `SHA256SUMS`. The application is released through GitHub
Releases; its own app artifact is not deployed to Maven Central.

## Build modes

```sh
scripts/package.sh app-image 1.0.0
scripts/package.sh installer 1.0.0
scripts/package.sh all 1.0.0
```

`app-image` builds the shaded application JAR, stages runtime dependency licenses
and the matching pty4j native helpers, then creates and smoke-tests an image.
`installer` reuses that exact image. Windows CI uses the split to sign all three
native launchers before creating the installer and portable ZIP, then signs
and verifies the EXE installer.

The Maven version supplied with `-Drevision=X.Y.Z`, the JAR manifest, launcher
properties, app bundle, installer and asset filenames use the same version.
The installed name remains **Harness Manager**; its Java package and macOS
bundle ID remain `io.github.ghosthack.harnessmanager`. Worker names remain
`jmux` and `harness-stimulator`.

## Package verification

`smoke-package.sh` checks the actual bundled runtime and all three launchers.
The hidden `--package-smoke` entry initializes UI classes without opening a
window, resolves HTTP support, starts an isolated jmux daemon and non-login
shell, verifies PTY input/capture, then shuts down and removes its scratch state.
Windows must use ConPTY. The check never launches coding agents or opens an
existing session registry.

The pty4j helpers are staged once under the packaged app's `pty4j-native`
directory. All launchers reference that directory through `$APPDIR`, avoiding
per-launch extraction of those files. Other dependencies retain their own
native resource loading behavior.

Runtime module roots:

| Module | Purpose |
|---|---|
| `java.desktop` | Swing, AWT, fonts, image/icon rendering and JediTerm UI |
| `java.net.http` | provider usage HTTP clients |
| `java.logging` | runtime logging compatibility |
| `java.management` | runtime and UI compatibility |
| `java.sql` | Jackson's optional standard SQL date types |
| `jdk.httpserver` | optional local MCP HTTP server |
| `jdk.unsupported` | JNA/native memory compatibility |
| `jdk.charsets` | terminal character encodings |

These roots are asserted against the packaged runtime metadata. jlink includes
their transitive dependencies. Update the script and smoke ledger together
when introducing another required module.

## Notices and licenses

The installed `THIRD-PARTY-LICENSES` tree contains the application's MIT license,
provider-icon notice, declared license texts and exact legal metadata extracted
from resolved dependency JARs. Each platform license ZIP additionally includes
the `legal/` tree from that exact packaged Java runtime.

## Signing and release safety

See [SIGNING.md](SIGNING.md) for certificate, notarization, GitHub environment
and Azure Artifact Signing setup. Local builds are unsigned unless signing
variables are supplied. Signing setup is not required to test an unsigned image.

With complete macOS release credentials, CI signs the completed DMG with a
timestamp and strictly verifies its signature before submitting it to Apple.
After notarization is accepted, CI staples and validates the DMG ticket. The app
inside remains unstapled; there is no second notarization submission.

Release CI has a manual `publish=false` mode for packaging/signing checks.
Only an approved tag push or manual `publish=true` publishes the GitHub release.
Existing versions are rejected; use a higher patch version for every rebuild
of a published installer. Windows uses one permanent upgrade UUID, recorded in
`package.sh`. CI covers clean install, subsequent-release upgrade and uninstall.

## External tools and platform requirements

Java, the PTY helpers, jmux and the stimulator are bundled. Coding-agent CLIs,
SSH and tmux are separate installs. tmux and stimulator functionality are
macOS/Linux features; Windows sessions use jmux. Linux builds are tested on
Ubuntu 24.04 x64; desktop operation requires the system's usual X11/desktop
libraries and fonts.
