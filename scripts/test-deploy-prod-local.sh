#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT
ROOT_DIR="$FIXTURE/project"
MAVEN_REPOSITORY="/usr/local/maven/repository"
mkdir -p "$ROOT_DIR/iw-packaging-parent/iw-core/target"
fail() { echo "Error: $*" >&2; exit 1; }
for function_name in service_name service_module latest_jar build_modules check_maven_repository; do
  awk -v name="$function_name" '$0 == name "() {" { selected=1 } selected { print } selected && /^}/ { exit }' "$SCRIPT_DIR/deploy-prod-local.sh" >> "$FIXTURE/functions.sh"
done
source "$FIXTURE/functions.sh"
TARGET_DIR="$ROOT_DIR/iw-packaging-parent/iw-core/target"
touch "$TARGET_DIR/iw-core-0.2.1.jar" "$TARGET_DIR/iw-core-0.2.8-admin-settings-local.jar"
if (latest_jar core) >"$FIXTURE/selection" 2>"$FIXTURE/error"; then
  echo 'FAIL: ambiguous artifacts accepted; historical JAR could be uploaded'
  exit 1
fi
rm "$TARGET_DIR/iw-core-0.2.8-admin-settings-local.jar"
touch "$TARGET_DIR/iw-core-0.2.1-sources.jar" "$TARGET_DIR/iw-core-0.2.1-javadoc.jar" "$TARGET_DIR/iw-core-0.2.1.jar.original"
[ "$(latest_jar core)" = "$TARGET_DIR/iw-core-0.2.1.jar" ]
rm "$TARGET_DIR/iw-core-0.2.1.jar"
if (latest_jar core) >/dev/null 2>&1; then echo 'FAIL: missing artifact accepted'; exit 1; fi
setup_java() { :; }
MVN_BIN="$FIXTURE/mvn"
MAVEN_SETTINGS_FILE=""
cat > "$MVN_BIN" <<'MVN'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$PWD/build-args"
while [ "$#" -gt 0 ]; do
  if [ "$1" = '-s' ]; then cp "$2" "$PWD/build-settings"; fi
  shift
done
MVN
chmod +x "$MVN_BIN"
unset MAVEN_OPTS MAVEN_ARGS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
build_modules core >"$FIXTURE/build-output"
for required in clean package '-Dmaven.repo.local=/usr/local/maven/repository' 'iw-packaging-parent/iw-core'; do
  grep -Fx -- "$required" "$ROOT_DIR/build-args" >/dev/null
done
grep -F '<localRepository>/usr/local/maven/repository</localRepository>' "$ROOT_DIR/build-settings" >/dev/null
if (MAVEN_OPTS='-Dmaven.repo.local=/tmp/wrong-repository' build_modules core) >/dev/null 2>&1; then echo 'FAIL: conflicting environment repository accepted'; exit 1; fi
mkdir -p "$ROOT_DIR/.mvn"
printf '%s\n' '-Dmaven.repo.local=/tmp/wrong-repository' > "$ROOT_DIR/.mvn/maven.config"
if (build_modules core) >/dev/null 2>&1; then echo 'FAIL: conflicting project repository accepted'; exit 1; fi
[ -z "$MAVEN_SETTINGS_FILE" ] || rm -f "$MAVEN_SETTINGS_FILE"
echo 'PASS: ambiguous/missing artifacts rejected, exact artifact selected, clean build with neutral settings, repository conflicts rejected; no SSH or production writes'
