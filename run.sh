#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./scripts/build.sh package -DskipTests
exec java --enable-native-access=ALL-UNNAMED -jar target/harness-manager-1.0.0-SNAPSHOT.jar "$@"
