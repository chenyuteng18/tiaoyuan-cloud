#!/usr/bin/env bash
# ============================================================================
# 调元云 · PostgreSQL 逻辑备份（pg_dump 自定义格式，压缩）
#
# 挂 crontab（宿主机）:
#   30 2 * * * /opt/diaoyuanyun/deploy/backup.sh >> /var/log/dy-backup.log 2>&1
#
# 保留策略: 最近 14 份每日备份（KEEP_DAYS 可调）。恢复见 restore.sh。
# ============================================================================
set -euo pipefail

KEEP_DAYS="${KEEP_DAYS:-14}"
BACKUP_DIR="$(cd "$(dirname "$0")" && pwd)/backups"
STAMP="$(date +%Y%m%d_%H%M%S)"
OUT="${BACKUP_DIR}/dy_${STAMP}.dump"

mkdir -p "${BACKUP_DIR}"

# 在 compose 网络内用 postgres 容器执行 pg_dump（凭据取自容器环境，不落本机明文）
docker compose -f "$(dirname "$0")/docker-compose.prod.yml" exec -T postgres \
    pg_dump -U "${DB_USERNAME:-diaoyuanyun}" -d "${DB_NAME:-diaoyuanyun_prod}" \
    -Fc -Z 6 > "${OUT}"

# 完整性自检：dump 头必须是 PostgreSQL custom archive（PGDMP）——空文件/报错文本立即报警
if [ "$(head -c 5 "${OUT}")" != "PGDMP" ]; then
    echo "[backup] ERROR: ${OUT} 不是有效的 pg_dump 归档，保留现场供排查" >&2
    exit 1
fi

echo "[backup] OK: ${OUT} ($(du -h "${OUT}" | cut -f1))"

# 清理过期备份
find "${BACKUP_DIR}" -name "dy_*.dump" -mtime "+${KEEP_DAYS}" -print -delete
