#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <iw-core|iw-external> <jar-path>"
}

if [ "$#" -ne 2 ]; then
  usage
  exit 1
fi

SERVICE_NAME="$1"
JAR_SOURCE="$2"

case "$SERVICE_NAME" in
  iw-core|iw-external)
    ;;
  *)
    echo "Unsupported service: $SERVICE_NAME"
    usage
    exit 1
    ;;
esac

if [ ! -f "$JAR_SOURCE" ]; then
  echo "Jar not found: $JAR_SOURCE"
  exit 1
fi

read_external_setting() {
  local key="$1" file="/etc/iw-mixes/iw-external.env" value
  if [ ! -r "$file" ]; then return 0; fi
  value="$(awk -v key="$key" '$0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
    sub(/^[^=]*=[[:space:]]*/, ""); sub(/[[:space:]]*$/, ""); value=$0
  } END {print value}' "$file")"
  case "$value" in
    \"*\") value="${value:1:${#value}-2}" ;;
    \'*\') value="${value:1:${#value}-2}" ;;
  esac
  printf '%s' "$value"
}

verify_external_git() (
  local enabled executable fixture
  enabled="$(read_external_setting IW_ZHAOGANG_CODE_AUTO_DIFF_RETRY_ENABLED)"
  if [ "${enabled:-true}" = false ]; then return; fi
  enabled="$(read_external_setting IW_ZHAOGANG_CODE_GIT_LINE_STATS_ENABLED)"
  if [ "${enabled:-true}" = false ]; then return; fi
  executable="$(read_external_setting IW_ZHAOGANG_CODE_GIT_LINE_STATS_EXECUTABLE)"
  executable="${executable:-/usr/bin/git}"
  if [[ "$executable" != /* || ! -x "$executable" ]]; then
    echo "Dev Hub Git executable is missing or not executable: $executable" >&2
    return 1
  fi
  fixture="$(mktemp -d)"
  trap 'rm -rf -- "$fixture"' EXIT
  export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
  if ! GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=http.sslVerify GIT_CONFIG_VALUE_0=true \
        "$executable" config --get http.sslVerify | grep -qx true \
      || ! "$executable" init --bare --template= --quiet "$fixture" \
      || ! "$executable" -C "$fixture" cat-file --batch-all-objects '--batch-check=%(objectsize)' --unordered >/dev/null \
      || [ ! -x "$("$executable" --exec-path)/git-remote-https" ]; then
    echo "Dev Hub Git lacks required configuration, object scanning or HTTPS support: $executable" >&2
    return 1
  fi
)

if [ "$SERVICE_NAME" = iw-external ]; then verify_external_git; fi

APP_ROOT="${IW_APP_ROOT:-/opt/iw-mixes-server}"
SERVICE_DIR="${APP_ROOT}/${SERVICE_NAME}"
RELEASE_DIR="${APP_ROOT}/releases/${SERVICE_NAME}"
CURRENT_JAR="${SERVICE_DIR}/${SERVICE_NAME}.jar"
VERSION="${GITHUB_SHA:-$(date +%Y%m%d%H%M%S)}"
VERSION="${VERSION:0:12}"
TARGET_JAR="${RELEASE_DIR}/${SERVICE_NAME}-${VERSION}.jar"

install -d -m 0755 "$SERVICE_DIR" "$RELEASE_DIR"
install -m 0644 "$JAR_SOURCE" "$TARGET_JAR"
PREVIOUS_JAR="$(readlink "$CURRENT_JAR" 2>/dev/null || true)"
ln -sfn "$TARGET_JAR" "$CURRENT_JAR"

systemctl daemon-reload
systemctl restart "$SERVICE_NAME"

sleep 5
if ! systemctl is-active --quiet "$SERVICE_NAME"; then
  echo "${SERVICE_NAME} failed to start after deploying ${TARGET_JAR}"
  journalctl -u "$SERVICE_NAME" -n 80 --no-pager || true
  if [ -n "$PREVIOUS_JAR" ] && [ -f "$PREVIOUS_JAR" ]; then
    echo "Rolling back ${SERVICE_NAME} to ${PREVIOUS_JAR}"
    ln -sfn "$PREVIOUS_JAR" "$CURRENT_JAR"
    systemctl restart "$SERVICE_NAME" || true
  fi
  exit 1
fi

find "$RELEASE_DIR" -type f -name "${SERVICE_NAME}-*.jar" -printf "%T@ %p\n" \
  | sort -rn \
  | tail -n +6 \
  | cut -d' ' -f2- \
  | xargs -r rm -f

echo "${SERVICE_NAME} deployed: ${TARGET_JAR}"
systemctl --no-pager --full status "$SERVICE_NAME" | sed -n '1,20p'
