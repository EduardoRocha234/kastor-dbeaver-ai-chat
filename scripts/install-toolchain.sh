#!/usr/bin/env bash
# Installs JDK 17 (Temurin) and Maven 3.9.9 under ~/.local/opt, no sudo needed.
set -euo pipefail
OPT="$HOME/.local/opt"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OPT"
if [ ! -x "$OPT/jdk-17/bin/java" ]; then
  curl -fsSL -o "$TMP/jdk.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$OPT/jdk-17"
  tar -xzf "$TMP/jdk.tar.gz" -C "$OPT/jdk-17" --strip-components=1
fi
if [ ! -x "$OPT/maven/bin/mvn" ]; then
  curl -fsSL -o "$TMP/maven.tar.gz" "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz"
  mkdir -p "$OPT/maven"
  tar -xzf "$TMP/maven.tar.gz" -C "$OPT/maven" --strip-components=1
fi
"$OPT/jdk-17/bin/java" -version
JAVA_HOME="$OPT/jdk-17" "$OPT/maven/bin/mvn" -v
