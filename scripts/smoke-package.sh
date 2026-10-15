#!/usr/bin/env bash
set -euo pipefail
IMAGE="${1:?Pass the jpackage app-image path}"
case "$(uname -s)" in
  Darwin) BIN="$IMAGE/Contents/MacOS"; APP="$IMAGE/Contents/app"; RUNTIME_RELEASE="$IMAGE/Contents/runtime/Contents/Home/release"; EXT= ;;
  Linux) BIN="$IMAGE/bin"; APP="$IMAGE/lib/app"; RUNTIME_RELEASE="$IMAGE/lib/runtime/release"; EXT= ;;
  MINGW*|MSYS*|CYGWIN*) BIN="$IMAGE"; APP="$IMAGE/app"; RUNTIME_RELEASE="$IMAGE/runtime/release"; EXT=.exe ;;
  *) echo 'Unsupported host' >&2; exit 2 ;;
esac
for path in "$BIN/Harness Manager$EXT" "$BIN/jmux$EXT" "$BIN/harness-stimulator$EXT" "$APP/LICENSE" "$APP/THIRD-PARTY.md" "$APP/THIRD-PARTY-LICENSES/CONTENTS.txt"; do
  test -f "$path" || { echo "Missing package input: $path" >&2; exit 1; }
done
PACKAGED_MODULES="$(sed -n 's/^MODULES="\(.*\)"$/\1/p' "$RUNTIME_RELEASE" | tr ' ' '\n')"
for required in java.desktop java.net.http java.logging java.management java.sql jdk.httpserver jdk.unsupported jdk.charsets; do
  grep -Fxq "$required" <<< "$PACKAGED_MODULES" || { echo "Missing module: $required" >&2; exit 1; }
done
env -u JMUX_LAUNCHER "$BIN/jmux$EXT" version
TASK_HELP="$(mktemp)"
trap 'rm -f "$TASK_HELP"' EXIT
set +e
"$BIN/harness-stimulator$EXT" > "$TASK_HELP"
TASK_STATUS=$?
set -e
test "$TASK_STATUS" -eq 2
grep -q 'usage:' "$TASK_HELP"
env -u JMUX_LAUNCHER "$BIN/Harness Manager$EXT" --package-smoke
