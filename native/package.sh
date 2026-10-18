#!/bin/bash
# Packs the Linux release archive: the single binary, the licence files and the README, plus a SHA-256 file.
#   native/package.sh           version is read from Cli.VERSION
# Run after native/native.sh. Output: target/release/tuifileman-<version>-linux-x64.tar.gz (+ .sha256)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
version=${1:-$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' src/main/java/org/ds/tuifileman/cli/Cli.java | head -n 1)}
[ -n "$version" ] || { echo "Cannot read Cli.VERSION; pass the version as argument." >&2; exit 1; }
bin=target/dist/linux/tuifileman
[ -x "$bin" ] || { echo "$bin is missing: run native/native.sh first." >&2; exit 1; }
reported=$("$bin" --version)
case "$reported" in
    *"$version"*) ;;
    *) echo "The binary reports '$reported', expected version $version." >&2; exit 1 ;;
esac
name="tuifileman-$version-linux-x64"
stage="target/release/$name"
rm -rf target/release
mkdir -p "$stage/licenses"
cp "$bin" LICENSE THIRD-PARTY-NOTICES.md README.md "$stage"
cp licenses/* "$stage/licenses"
tar -C target/release -czf "target/release/$name.tar.gz" "$name"
(cd target/release && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256")
cat "target/release/$name.tar.gz.sha256"
