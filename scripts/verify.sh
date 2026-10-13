#!/usr/bin/env bash
# Builds and runs all tests against one DBeaver version: scripts/verify.sh 24.1.0|latest
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/env.sh"
VERSION="${1:-24.1.0}"
"$ROOT/scripts/fetch-dbeaver.sh" "$VERSION"
EXTRA=()
# Newer DBeaver releases ship Eclipse bundles that require Java 21: resolve with JavaSE-21 on a JDK 21.
# The bytecode stays Java 17 (tycho-compiler release 17), so the same jar runs on DBeaver 24.1.
if unzip -p "$ROOT/.dbeaver/$VERSION/dbeaver/plugins"/org.eclipse.jface_*.jar META-INF/MANIFEST.MF 2>/dev/null | grep -q 'JavaSE-21'; then
  export JAVA_HOME="$HOME/.local/opt/jdk-21"
  export PATH="$JAVA_HOME/bin:$PATH"
  EXTRA+=(-Daichat.resolveEE=JavaSE-21)
fi
cd "$ROOT"
mvn clean verify "${EXTRA[@]}"
