#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CORE_HOST="${IW_LOCAL_CORE_HOST:-aliyun87}"
EXTERNAL_HOST="${IW_LOCAL_EXTERNAL_HOST:-aliyun183}"
REMOTE_TMP_DIR="${IW_LOCAL_REMOTE_TMP_DIR:-/tmp/iw-mixes-server-local-deploy}"
JAVA_VERSION="${IW_LOCAL_JAVA_VERSION:-17}"
MVN_BIN="${MVN_BIN:-mvn}"
SSH_CONFIG_FILE=""

cleanup() {
  if [ -n "$SSH_CONFIG_FILE" ] && [ -f "$SSH_CONFIG_FILE" ]; then
    rm -f "$SSH_CONFIG_FILE"
  fi
}

trap cleanup EXIT

usage() {
  cat <<EOF
Usage:
  $0 deploy  <all|core|external>
  $0 status  <all|core|external>
  $0 restart <all|core|external>
  $0 stop    <all|core|external>

Defaults:
  core host:     ${CORE_HOST}
  external host: ${EXTERNAL_HOST}

Override hosts when needed:
  IW_LOCAL_CORE_HOST=iw-core IW_LOCAL_EXTERNAL_HOST=iw-public $0 status all
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

need_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

service_name() {
  case "$1" in
    core) echo "iw-core" ;;
    external) echo "iw-external" ;;
    *) fail "unsupported target: $1" ;;
  esac
}

service_host() {
  case "$1" in
    core) echo "$CORE_HOST" ;;
    external) echo "$EXTERNAL_HOST" ;;
    *) fail "unsupported target: $1" ;;
  esac
}

service_module() {
  case "$1" in
    core) echo "iw-packaging-parent/iw-core" ;;
    external) echo "iw-packaging-parent/iw-external" ;;
    *) fail "unsupported target: $1" ;;
  esac
}

expand_targets() {
  case "$1" in
    all) echo "external core" ;;
    core|external) echo "$1" ;;
    *) fail "target must be one of: all, core, external" ;;
  esac
}

setup_java() {
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$("/usr/libexec/java_home" -v "$JAVA_VERSION")"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
}

build_modules() {
  local target="$1"
  local modules

  setup_java

  case "$target" in
    all)
      modules="iw-packaging-parent/iw-core,iw-packaging-parent/iw-external"
      ;;
    core|external)
      modules="$(service_module "$target")"
      ;;
    *)
      fail "unsupported target: $target"
      ;;
  esac

  echo "==> Building ${target}: ${modules}"
  (cd "$ROOT_DIR" && "$MVN_BIN" -pl "$modules" -am -DskipTests package)
}

latest_jar() {
  local target="$1"
  local service module target_dir

  service="$(service_name "$target")"
  module="$(service_module "$target")"
  target_dir="${ROOT_DIR}/${module}/target"

  local jar
  jar="$(find "$target_dir" -maxdepth 1 -type f -name "${service}-*.jar" \
    ! -name "*-sources.jar" ! -name "*-javadoc.jar" \
    -print | sort | tail -n 1)"

  [ -n "$jar" ] || fail "jar not found under ${target_dir}"
  echo "$jar"
}

git_version() {
  git -C "$ROOT_DIR" rev-parse --short=12 HEAD 2>/dev/null || date +%Y%m%d%H%M
}

setup_ssh_config() {
  if [ -n "$SSH_CONFIG_FILE" ]; then
    return
  fi

  SSH_CONFIG_FILE="$(mktemp)"
  {
    printf 'Include %s\n' "$HOME/.ssh/config"
    printf 'Host *\n'
    printf '  SendEnv -LANG\n'
    printf '  SendEnv -LC_*\n'
  } >"$SSH_CONFIG_FILE"
}

ssh_cmd() {
  local host="$1"
  shift
  setup_ssh_config
  ssh -F "$SSH_CONFIG_FILE" "$host" "$@"
}

ssh_tty_cmd() {
  local host="$1"
  shift
  setup_ssh_config
  if [ -t 0 ]; then
    ssh -t -F "$SSH_CONFIG_FILE" "$host" "$@"
  else
    ssh -F "$SSH_CONFIG_FILE" "$host" "$@"
  fi
}

scp_cmd() {
  setup_ssh_config
  scp -F "$SSH_CONFIG_FILE" "$@"
}

deploy_one() {
  local target="$1"
  local service host jar version

  service="$(service_name "$target")"
  host="$(service_host "$target")"
  jar="$(latest_jar "$target")"
  version="$(git_version)"

  echo "==> Deploying ${service} to ${host}"
  ssh_cmd "$host" "mkdir -p '${REMOTE_TMP_DIR}'"
  scp_cmd "$jar" "${host}:${REMOTE_TMP_DIR}/${service}.jar"
  scp_cmd "${ROOT_DIR}/deploy/scripts/deploy-jar.sh" "${host}:${REMOTE_TMP_DIR}/deploy-jar.sh"
  ssh_tty_cmd "$host" "if [ \"\$(id -u)\" -eq 0 ]; then env GITHUB_SHA='${version}' bash '${REMOTE_TMP_DIR}/deploy-jar.sh' '${service}' '${REMOTE_TMP_DIR}/${service}.jar'; else sudo env GITHUB_SHA='${version}' bash '${REMOTE_TMP_DIR}/deploy-jar.sh' '${service}' '${REMOTE_TMP_DIR}/${service}.jar'; fi"
}

remote_status() {
  local target="$1"
  local service host

  service="$(service_name "$target")"
  host="$(service_host "$target")"

  echo "==> ${service} status on ${host}"
  ssh_cmd "$host" "set +e
active_state=\$(systemctl is-active '${service}' 2>/dev/null)
service_props=\$(systemctl show '${service}' -p SubState -p MainPID -p ActiveEnterTimestamp 2>/dev/null)
sub_state=\$(printf '%s\n' \"\$service_props\" | sed -n 's/^SubState=//p')
main_pid=\$(printf '%s\n' \"\$service_props\" | sed -n 's/^MainPID=//p')
started_at=\$(printf '%s\n' \"\$service_props\" | sed -n 's/^ActiveEnterTimestamp=//p')

echo \"active: \${active_state:-unknown}\"
echo \"sub_state: \${sub_state:-unknown}\"
echo \"pid: \${main_pid:-0}\"
echo \"started_at: \${started_at:-unknown}\"

if [ -n \"\$main_pid\" ] && [ \"\$main_pid\" != \"0\" ]; then
  echo
  echo 'process:'
  ps -p \"\$main_pid\" -o pid= -o ppid= -o etime= -o pcpu= -o pmem= -o comm= -o args=
else
  echo
  echo 'process: not running'
fi"
}

remote_restart() {
  local target="$1"
  local service host

  service="$(service_name "$target")"
  host="$(service_host "$target")"

  echo "==> Restarting ${service} on ${host}"
  ssh_tty_cmd "$host" "set -e
if [ \"\$(id -u)\" -eq 0 ]; then
  systemctl restart '${service}'
else
  sudo systemctl restart '${service}'
fi
sleep 5
if ! systemctl is-active --quiet '${service}'; then
  systemctl --no-pager --full status '${service}' | sed -n '1,40p' || true
  exit 1
fi
systemctl --no-pager --full status '${service}' | sed -n '1,25p'"
}

remote_stop() {
  local target="$1"
  local service host

  service="$(service_name "$target")"
  host="$(service_host "$target")"

  echo "==> Stopping ${service} on ${host}"
  ssh_tty_cmd "$host" "set -e
if [ \"\$(id -u)\" -eq 0 ]; then
  systemctl stop '${service}'
else
  sudo systemctl stop '${service}'
fi
systemctl --no-pager --full status '${service}' | sed -n '1,25p' || true"
}

main() {
  local command="${1:-}"
  local target="${2:-}"

  if [ "$command" = "help" ] || [ "$command" = "-h" ] || [ "$command" = "--help" ]; then
    usage
    exit 0
  fi

  if [ -z "$command" ] || [ -z "$target" ] || [ "${3:-}" != "" ]; then
    usage
    exit 1
  fi

  need_command ssh
  need_command scp

  case "$command" in
    deploy|release|publish)
      need_command "$MVN_BIN"
      build_modules "$target"
      for item in $(expand_targets "$target"); do
        deploy_one "$item"
      done
      ;;
    status)
      for item in $(expand_targets "$target"); do
        remote_status "$item"
      done
      ;;
    restart)
      for item in $(expand_targets "$target"); do
        remote_restart "$item"
      done
      ;;
    stop)
      for item in $(expand_targets "$target"); do
        remote_stop "$item"
      done
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
