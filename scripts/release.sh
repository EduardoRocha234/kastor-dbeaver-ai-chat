#!/usr/bin/env bash
# Cuts a release: bump, build + tests, commit, tag, push. Pushing the tag publishes the GitHub release and update site.
# Usage: scripts/release.sh 0.2.0
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NEW="${1:?usage: scripts/release.sh X.Y.Z}"
cd "$ROOT"
[[ "$(git rev-parse --abbrev-ref HEAD)" == "main" ]] || { echo "Release from main." >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Working tree not clean." >&2; exit 1; }
git rev-parse "v$NEW" >/dev/null 2>&1 && { echo "Tag v$NEW already exists." >&2; exit 1; }
"$ROOT/scripts/bump-version.sh" "$NEW"
"$ROOT/scripts/verify.sh"
git add -A
git commit -m "chore(release): v$NEW"
git tag -a "v$NEW" -m "Kastor v$NEW"
read -r -p "Push main and v$NEW to origin? [y/N] " answer
if [[ "$answer" == "y" || "$answer" == "Y" ]]; then
  git push origin main "v$NEW"
else
  echo "Not pushed. Later: git push origin main v$NEW"
fi
