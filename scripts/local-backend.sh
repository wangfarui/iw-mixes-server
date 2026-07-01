#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${IW_LOCAL_LOG_DIR:-${PROJECT_ROOT}/logs/local}"
PROFILE="${IW_LOCAL_PROFILE:-dev}"
JAVA_OPTS="${IW_LOCAL_JAVA_OPTS:--Xms128m -Xmx768m}"
SKIP_BUILD="${IW_LOCAL_SKIP_BUILD:-0}"

usage() {
  cat <<'USAGE'
# 同时启动 iw-external 和 iw-core，用于前端访问 http://localhost:18000
scripts/local-backend.sh all

# 也可以分别启动
scripts/local-backend.sh start external
scripts/local-backend.sh start core

# 查看状态、跟日志、重启、停止服务
scripts/local-backend.sh status
scripts/local-backend.sh logs core
scripts/local-backend.sh restart all
scripts/local-backend.sh stop all
USAGE
}

ensure_log_dir() {
  mkdir -p "$LOG_DIR"
}

resolve_java() {
  if [ -n "${IW_JAVA_HOME:-}" ]; then
    export JAVA_HOME="$IW_JAVA_HOME"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local java_home
    java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    if [ -n "$java_home" ]; then
      export JAVA_HOME="$java_home"
    fi
  fi

  if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi

  if ! command -v java >/dev/null 2>&1; then
    echo "java not found. Install JDK 17 or set IW_JAVA_HOME."
    exit 1
  fi

  local java_version
  java_version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  case "$java_version" in
    17.*)
      ;;
    *)
      echo "JDK 17 is required, current java version is: ${java_version:-unknown}"
      echo "Set IW_JAVA_HOME to a JDK 17 path if needed."
      exit 1
      ;;
  esac
}

resolve_maven() {
  if ! command -v mvn >/dev/null 2>&1; then
    echo "mvn not found. Install Maven first."
    exit 1
  fi
}

service_name() {
  case "$1" in
    core) echo "iw-core" ;;
    external) echo "iw-external" ;;
    *) echo "$1" ;;
  esac
}

service_module() {
  case "$1" in
    core) echo "iw-packaging-parent/iw-core" ;;
    external) echo "iw-packaging-parent/iw-external" ;;
    *) return 1 ;;
  esac
}

service_port() {
  case "$1" in
    core) echo "${IW_CORE_PORT:-18000}" ;;
    external) echo "${IW_EXTERNAL_PORT:-18006}" ;;
    *) return 1 ;;
  esac
}

service_port_env_name() {
  case "$1" in
    core) echo "IW_CORE_PORT" ;;
    external) echo "IW_EXTERNAL_PORT" ;;
    *) return 1 ;;
  esac
}

pid_file() {
  echo "${LOG_DIR}/$(service_name "$1").pid"
}

log_file() {
  echo "${LOG_DIR}/$(service_name "$1").log"
}

is_running() {
  local target="$1"
  local pid_path
  pid_path="$(pid_file "$target")"
  if [ ! -f "$pid_path" ]; then
    return 1
  fi

  local pid
  pid="$(cat "$pid_path")"
  if [ -z "$pid" ] || ! kill -0 "$pid" >/dev/null 2>&1; then
    rm -f "$pid_path"
    return 1
  fi

  return 0
}

current_pid() {
  local target="$1"
  if is_running "$target"; then
    cat "$(pid_file "$target")"
  fi
}

port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  else
    return 1
  fi
}

tail_log_on_failure() {
  local target="$1"
  local log_path
  log_path="$(log_file "$target")"
  if [ -f "$log_path" ]; then
    echo "Last log lines from ${log_path}:"
    tail -n 80 "$log_path" || true
  fi
}

find_service_jar() {
  local target="$1"
  local name module target_dir
  name="$(service_name "$target")"
  module="$(service_module "$target")"
  target_dir="${PROJECT_ROOT}/${module}/target"

  if [ ! -d "$target_dir" ]; then
    return 0
  fi

  find "$target_dir" -maxdepth 1 -type f -name "${name}-*.jar" ! -name "*.original" \
    | sort \
    | tail -n 1
}

build_service() {
  local target="$1"
  local module log_path
  module="$(service_module "$target")"
  log_path="$(log_file "$target")"
  ensure_log_dir

  if [ "$SKIP_BUILD" = "1" ]; then
    echo "Skipping Maven build for $(service_name "$target") because IW_LOCAL_SKIP_BUILD=1."
    return
  fi

  {
    echo
    echo "==== $(date '+%Y-%m-%d %H:%M:%S') build $(service_name "$target") ===="
  } >> "$log_path"

  echo "Building $(service_name "$target") ..."
  if ! (cd "$PROJECT_ROOT" && mvn -pl "$module" -am -DskipTests package >> "$log_path" 2>&1); then
    echo "Build failed for $(service_name "$target")."
    tail_log_on_failure "$target"
    exit 1
  fi
}

wait_for_port() {
  local target="$1"
  local port="$2"
  local pid="$3"
  local i

  for i in $(seq 1 60); do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      echo "$(service_name "$target") exited while starting."
      tail_log_on_failure "$target"
      exit 1
    fi

    if port_in_use "$port"; then
      return 0
    fi

    sleep 1
  done

  echo "$(service_name "$target") is running with PID ${pid}, but port ${port} was not detected yet."
  echo "Check logs: $(log_file "$target")"
}

start_service() {
  local target="$1"
  local name port log_path jar_path pid
  name="$(service_name "$target")"
  port="$(service_port "$target")"
  log_path="$(log_file "$target")"
  ensure_log_dir

  if is_running "$target"; then
    echo "${name} already running, PID $(current_pid "$target")."
    return
  fi

  if port_in_use "$port"; then
    echo "Port ${port} is already in use. Stop the existing process or set $(service_port_env_name "$target")."
    exit 1
  fi

  build_service "$target"

  jar_path="$(find_service_jar "$target")"
  if [ -z "$jar_path" ] || [ ! -f "$jar_path" ]; then
    echo "Jar not found for ${name}. Expected under $(service_module "$target")/target."
    exit 1
  fi

  {
    echo
    echo "==== $(date '+%Y-%m-%d %H:%M:%S') start ${name} ===="
    echo "jar: ${jar_path}"
    echo "profile: ${PROFILE}"
    echo "port: ${port}"
    echo "java opts: ${JAVA_OPTS}"
  } >> "$log_path"

  echo "Starting ${name} on port ${port} ..."
  (
    cd "$PROJECT_ROOT"
    # shellcheck disable=SC2086
    nohup java ${JAVA_OPTS} -jar "$jar_path" --spring.profiles.active="$PROFILE" >> "$log_path" 2>&1 &
    echo $! > "$(pid_file "$target")"
  )

  pid="$(current_pid "$target")"
  wait_for_port "$target" "$port" "$pid"
  echo "${name} started, PID ${pid}, log ${log_path}"
}

stop_service() {
  local target="$1"
  local name pid_path pid
  name="$(service_name "$target")"
  pid_path="$(pid_file "$target")"

  if ! is_running "$target"; then
    echo "${name} is not running."
    return
  fi

  pid="$(cat "$pid_path")"
  echo "Stopping ${name}, PID ${pid} ..."
  kill "$pid" >/dev/null 2>&1 || true

  local i
  for i in $(seq 1 30); do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      rm -f "$pid_path"
      echo "${name} stopped."
      return
    fi
    sleep 1
  done

  echo "${name} did not stop in time, killing PID ${pid}."
  kill -9 "$pid" >/dev/null 2>&1 || true
  rm -f "$pid_path"
}

status_service() {
  local target="$1"
  local name port pid
  name="$(service_name "$target")"
  port="$(service_port "$target")"

  if is_running "$target"; then
    pid="$(current_pid "$target")"
    echo "${name}: running, PID ${pid}, port ${port}, log $(log_file "$target")"
  else
    echo "${name}: stopped, port ${port}, log $(log_file "$target")"
  fi
}

logs_service() {
  local target="$1"
  local log_path
  log_path="$(log_file "$target")"

  if [ ! -f "$log_path" ]; then
    echo "Log file does not exist: ${log_path}"
    return
  fi

  tail -n "${IW_LOCAL_LOG_LINES:-120}" -f "$log_path"
}

for_each_target() {
  local target="$1"
  local callback="$2"

  case "$target" in
    core|external)
      "$callback" "$target"
      ;;
    all)
      case "$callback" in
        start_service)
          "$callback" external
          "$callback" core
          ;;
        stop_service)
          "$callback" core
          "$callback" external
          ;;
        *)
          "$callback" external
          "$callback" core
          ;;
      esac
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

if [ "$#" -eq 0 ]; then
  usage
  exit 0
fi

ACTION="$1"
TARGET="${2:-all}"

case "$ACTION" in
  core|external|all)
    TARGET="$ACTION"
    ACTION="start"
    ;;
esac

case "$ACTION" in
  start|stop|restart|status|logs)
    ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage
    exit 1
    ;;
esac

case "$ACTION" in
  start|restart)
    resolve_java
    resolve_maven
    ;;
esac

case "$ACTION" in
  start)
    for_each_target "$TARGET" start_service
    ;;
  stop)
    for_each_target "$TARGET" stop_service
    ;;
  restart)
    for_each_target "$TARGET" stop_service
    for_each_target "$TARGET" start_service
    ;;
  status)
    for_each_target "$TARGET" status_service
    ;;
  logs)
    if [ "$TARGET" = "all" ]; then
      echo "Use one of these commands to follow a single service log:"
      echo "  scripts/local-backend.sh logs core"
      echo "  scripts/local-backend.sh logs external"
      exit 0
    fi
    logs_service "$TARGET"
    ;;
esac
