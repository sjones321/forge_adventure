#!/usr/bin/env bash
# Ascendant-Next stage 1: walk the home plane.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/forge-ascendant/target/forge-ascendant-2.0.16-SNAPSHOT-jar-with-dependencies.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Building forge-ascendant..."
  (cd "$ROOT" && mvn -B -pl forge-ascendant -am package -DskipTests)
fi
exec java -Xmx2g -jar "$JAR" \
  --res "$ROOT/forge-gui/res/adventure/common/maps" \
  --seed "${1:-1}" \
  --save "${HOME}/.forge-ascendant-next"
