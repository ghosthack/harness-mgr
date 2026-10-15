<p align="center">
  <img src="packaging/icons/harness-manager.svg" width="128" height="128" alt="Harness Manager torii logo">
</p>

# Harness Manager

A desktop app for running coding-agent terminal sessions side by side. It supports
Claude Code, Codex, Cursor Agent, Grok, OpenCode, Pi, Muse and ordinary shells.
Sessions live in tmux or the bundled jmux daemon, so they survive app restarts.

The sidebar organizes sessions and provider budgets. Settings cover appearance,
harness launch options, session behavior, remote machines and optional MCP control.
Provider usage data comes from existing CLI logins; Harness Manager does not save
provider credentials of its own.

## Download

Self-contained installers will be available from
[GitHub Releases](https://github.com/ghosthack/harness-mgr/releases):

- macOS Apple silicon: DMG.
- Windows x64: EXE installer or portable ZIP.
- Linux x64: Debian package or portable tarball.

Every package includes Java 26, the matching PTY native helpers, jmux, and the
stimulator worker. Java does not need to be installed separately. Coding-agent
CLIs are separate installs. tmux is required for tmux-hosted sessions and
stimulator features on macOS/Linux; Windows uses jmux with ConPTY.

## Build and run

Requires JDK 26, Maven 3.9.x and Python 3.9 or newer for release checks. All Java
dependencies resolve anonymously from Maven Central or JetBrains' public registry.
No GitHub token or sibling project checkout is needed.

```sh
./scripts/build.sh
./run.sh
./package-app.sh app-image 1.0.0
```

The build helper uses empty Maven settings and an isolated `.oss-cache/`.
The app artifact remains `harness-manager`, with Java packages under
`io.github.ghosthack.harnessmanager`. The installed app is **Harness Manager**;
its additional launchers retain the names `jmux` and `harness-stimulator`.
Only this GitHub repository uses the shortened name `harness-mgr`.

## Packaging and releases

See [packaging/README.md](packaging/README.md) and
[packaging/SIGNING.md](packaging/SIGNING.md). Releases use `vX.Y.Z` tags and native
macOS, Windows and Linux runners. Each release includes a platform license
archive and `SHA256SUMS` alongside its installers and portable packages.

## License and source cut

Harness Manager's original code is licensed under the [MIT License](LICENSE).
Dependencies and provider marks retain their upstream licenses and notices; see
[THIRD-PARTY.md](THIRD-PARTY.md) and `licenses/`.

This repository starts from an allowlisted source snapshot. Development history,
internal notes, machine configuration and generated build files are excluded.
Future exports append ordinary public commits.
