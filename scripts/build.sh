#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p .oss-cache/m2
printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n' > .oss-cache/settings.xml
if (( $# == 0 )); then set -- verify; fi
env -u GH_TOKEN -u GITHUB_TOKEN -u GITHUB_ACTOR mvn -B -ntp \
  -s "$ROOT/.oss-cache/settings.xml" -gs "$ROOT/.oss-cache/settings.xml" \
  -Dmaven.repo.local="$ROOT/.oss-cache/m2" "$@"
