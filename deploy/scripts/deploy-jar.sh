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
