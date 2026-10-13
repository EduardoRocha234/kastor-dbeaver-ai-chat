#!/usr/bin/env bash
# Builds and runs all tests against one DBeaver version: scripts/verify.sh 24.1.0|latest
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/env.sh"
"$ROOT/scripts/fetch-dbeaver.sh" "${1:-24.1.0}"
cd "$ROOT"
mvn clean verify
