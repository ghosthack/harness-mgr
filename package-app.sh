#!/usr/bin/env bash
set -euo pipefail
exec bash "$(dirname "$0")/scripts/package.sh" "${1:-app-image}" "${2:-1.0.0}"
