#!/bin/bash
# GraalVM native-image build for Linux (x86-64), from the same fat jar as the Windows build.
#   native/native.sh            build target/dist/linux/tuifileman (GraalVM CE from $GRAALVM_HOME, else $JAVA_HOME)
#   native/native.sh --agent    run the jar on GraalVM with the tracing agent (use every feature, quit with F10);
#                               the trace is merged into native/config, shared with the Windows build
# Run `mvn package` first. The jar is target/tuifileman-<Cli.VERSION>.jar; the script refuses a jar that is older
# than any file under src/main, so it can never be built from stale classes.
# The build runs in a temporary directory outside the project (the project may live on a slow 9p mount in WSL).
# Needs gcc and zlib-devel. The binary links dynamically against glibc/libz only: it runs on distros with the
# same or a newer glibc than the build machine.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
graal=${GRAALVM_HOME:-${JAVA_HOME:-}}
if [ -z "$graal" ] || [ ! -x "$graal/bin/native-image" ]; then
    echo "GraalVM with native-image not found: set GRAALVM_HOME" >&2
    exit 1
fi
mode=build
case "${1:-}" in
    '') ;;
    --agent) mode=agent ;;
    *) echo "usage: native/native.sh [--agent]" >&2; exit 2 ;;
esac
version=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' "$root/src/main/java/org/ds/tuifileman/cli/Cli.java" | head -n 1)
[ -n "$version" ] || { echo "Cannot read Cli.VERSION." >&2; exit 1; }
jar="$root/target/tuifileman-$version.jar"
[ -f "$jar" ] || { echo "$jar is missing: run mvn package first." >&2; exit 1; }
newer=$(find "$root/src/main" -type f -newer "$jar" | head -n 1)
[ -z "$newer" ] || { echo "$jar is older than $newer: run mvn package again." >&2; exit 1; }
if [ "$mode" = agent ]; then
    "$graal/bin/java" "-agentlib:native-image-agent=config-merge-dir=$root/native/config" \
        --enable-native-access=ALL-UNNAMED -cp "$jar" org.ds.tuifileman.app.App
    exit
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$jar" "$work/tfm.jar"
cp -r "$root/native/config" "$work/config"
(cd "$work" && "$graal/bin/native-image" -H:ConfigurationFileDirectories=config -H:+ReportExceptionStackTraces \
    -H:+AddAllCharsets --enable-native-access=ALL-UNNAMED -cp tfm.jar org.ds.tuifileman.app.App tuifileman)
mkdir -p "$root/target/dist/linux"
cp "$work/tuifileman" "$root/target/dist/linux/tuifileman"
echo "Single-file build: $root/target/dist/linux/tuifileman"
