#!/usr/bin/env bash
# ============================================================================
# 调元云 · 备份恢复（pg_restore 自定义格式）
#
# ⚠️ 恢复 = 用备份覆盖当前库。执行前必须：
#   1. 先做一次当下时点的备份（防止"恢复到坏备份"不可回退）
#   2. 停应用容器（避免恢复期间应用写入半新半旧数据）
#
# 用法:  ./restore.sh backups/dy_20261008_023000.dump
# ============================================================================
set -euo pipefail

DUMP="${1:?用法: $0 backups/dy_YYYYMMDD_HHMMSS.dump}"
DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE="docker compose -f ${DEPLOY_DIR}/docker-compose.prod.yml"

if [ ! -f "${DUMP}" ]; then
    echo "[restore] ERROR: 备份文件不存在: ${DUMP}" >&2
    exit 1
fi
if [ "$(head -c 5 "${DUMP}")" != "PGDMP" ]; then
    echo "[restore] ERROR: ${DUMP} 不是有效的 pg_dump 归档" >&2
    exit 1
fi

echo "[restore] 1/3 先做当下时点备份（防回退不可逆）"
"${DEPLOY_DIR}/backup.sh"

echo "[restore] 2/3 停应用"
"${COMPOSE}" stop app

echo "[restore] 3/3 恢复（--clean 重建对象，--if-exists 防幂等报错）"
"${COMPOSE}" exec -T postgres pg_restore \
    -U "${DB_USERNAME:-diaoyuanyun}" -d "${DB_NAME:-diaoyuanyun_prod}" \
    --clean --if-exists --no-owner < "${DUMP}"

echo "[restore] 完成。启动应用:"
echo "  ${COMPOSE} up -d app"
echo "🛑 启动后必须人工核验: 应用健康检查 200 + 一次真实登录 + 审计链自检端点(/api/v1/audit/log-chain)。"
