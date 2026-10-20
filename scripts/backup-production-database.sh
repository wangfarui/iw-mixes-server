#!/bin/bash
set -euo pipefail
umask 077

DUMP_BIN="$(command -v mysqldump || true)"
if [[ -z "$DUMP_BIN" && -x /usr/local/mysql/bin/mysqldump ]]; then
  DUMP_BIN=/usr/local/mysql/bin/mysqldump
fi
if [[ -z "$DUMP_BIN" ]]; then
  echo '未找到 mysqldump，请先安装或配置 MySQL 8 客户端。' >&2
  exit 1
fi

read -r -p '生产 MySQL 地址：' DB_HOST
read -r -p '端口 [3306]：' DB_PORT
DB_PORT="${DB_PORT:-3306}"
read -r -p '数据库账号：' DB_USER
read -r -p '需要完整备份的库名：' DB_NAME
if [[ -z "$DB_HOST" || -z "$DB_USER" || -z "$DB_NAME" || "$DB_NAME" == -* ]]; then
  echo '地址、账号、库名不能为空，库名不能以 - 开头。' >&2
  exit 1
fi

OUT_DIR="$HOME/iw-db-backups/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$OUT_DIR"
BACKUP="$OUT_DIR/full-database.sql.gz"
PARTIAL="$BACKUP.partial"
printf '目标：%s:%s / %s\n' "$DB_HOST" "$DB_PORT" "$DB_NAME"
echo '下面输入数据库密码，输入时不会显示。'

if ! "$DUMP_BIN" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --password \
  --default-character-set=utf8mb4 --single-transaction --quick \
  --hex-blob --routines --triggers --events --no-tablespaces \
  --set-gtid-purged=OFF --column-statistics=0 --databases "$DB_NAME" \
  | gzip > "$PARTIAL"; then
  echo "备份失败！请勿执行迁移。未完成文件：$PARTIAL" >&2
  exit 1
fi

if ! gzip -t "$PARTIAL"; then
  echo "压缩包校验失败！请勿执行迁移。未完成文件：$PARTIAL" >&2
  exit 1
fi
mv "$PARTIAL" "$BACKUP"
shasum -a 256 "$BACKUP" > "$BACKUP.sha256"
printf '\n备份导出及压缩完整性检查通过：\n%s\n校验文件：\n%s\n' "$BACKUP" "$BACKUP.sha256"
ls -lh "$BACKUP"
