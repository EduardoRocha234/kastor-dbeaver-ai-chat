#!/usr/bin/env bash
# Downloads a DBeaver CE Linux build and points releng/target/dbeaver.target at it.
# Usage: scripts/fetch-dbeaver.sh 24.1.0   |   scripts/fetch-dbeaver.sh latest
set -euo pipefail
VERSION="${1:-24.1.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/.dbeaver/$VERSION"
if [ ! -d "$DEST/dbeaver/plugins" ]; then
  if [ "$VERSION" = "latest" ]; then
    URL="https://dbeaver.io/files/dbeaver-ce-latest-linux.gtk.x86_64.tar.gz"
  else
    URL="https://dbeaver.io/files/$VERSION/dbeaver-ce-$VERSION-linux.gtk.x86_64-nojdk.tar.gz"
  fi
  mkdir -p "$DEST"
  curl -fSL -o "$DEST/dbeaver.tar.gz" "$URL"
  tar -xzf "$DEST/dbeaver.tar.gz" -C "$DEST"
  rm "$DEST/dbeaver.tar.gz"
fi
sed "s#@DBEAVER_HOME@#$DEST/dbeaver#" "$ROOT/releng/target/dbeaver.target.template" > "$ROOT/releng/target/dbeaver.target"
grep -m1 '^version=' "$DEST/dbeaver/.eclipseproduct"
