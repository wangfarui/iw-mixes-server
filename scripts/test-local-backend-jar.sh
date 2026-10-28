#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT
PROJECT_ROOT="$FIXTURE"
service_name() { echo iw-core; }
service_module() { echo module; }
# Exercise the actual selector without invoking the service-management entry point.
awk '/^find_service_jar[(][)] [{]/{selected=1} selected{print} selected && /^[}]/{exit}' "$SCRIPT_DIR/local-backend.sh" > "$FIXTURE/selector.sh"
source "$FIXTURE/selector.sh"
mkdir -p "$FIXTURE/module/target"
touch "$FIXTURE/module/target/iw-core-0.2.8-old.jar"
touch -t 202001010000 "$FIXTURE/module/target/iw-core-0.2.8-old.jar"
touch "$FIXTURE/module/target/iw-core-0.2.1.jar"
[ "$(find_service_jar core)" = "$FIXTURE/module/target/iw-core-0.2.1.jar" ]
IW_CORE_JAR="$FIXTURE/module/target/iw-core-0.2.8-old.jar"
[ "$(find_service_jar core)" = "$IW_CORE_JAR" ]
IW_CORE_JAR=relative.jar
if find_service_jar core >/dev/null 2>&1; then echo 'FAIL invalid override accepted'; exit 1; fi
unset IW_CORE_JAR
rm "$FIXTURE/module/target/"*.jar
[ -z "$(find_service_jar core)" ]
echo 'PASS: newest artifact, explicit override, invalid override and no-artifact cases'
