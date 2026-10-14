#!/usr/bin/env bash
# Sets the version in every pom, MANIFEST.MF and feature.xml: scripts/bump-version.sh 0.2.0
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/env.sh"
NEW="${1:?usage: scripts/bump-version.sh X.Y.Z}"
[[ "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must look like 1.2.3" >&2; exit 1; }
cd "$ROOT"
mvn -q -B org.eclipse.tycho:tycho-versions-plugin:4.0.10:set-version -DnewVersion="$NEW-SNAPSHOT"
# core is a plain Maven jar outside Tycho's reach: bump its own <version> (the first one, line ~8)
sed -i "0,/<version>[^<]*<\/version>/s//<version>$NEW-SNAPSHOT<\/version>/" core/pom.xml
git diff --stat -- '*pom.xml' '*MANIFEST.MF' '*feature.xml'
