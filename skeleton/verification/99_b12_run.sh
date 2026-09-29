#!/usr/bin/env bash
# ============================================================================
# 99_b12_run.sh — B 类实体（V2）真库验证总控（可复跑）
#
# 用法:
#   bash skeleton/verification/99_b12_run.sh
#
# 可选环境变量:
#   DY_PG_HOST (默认 127.0.0.1) / DY_PG_PORT (默认 5432)
#   DY_PG_SUPER_PASSWORD (默认 postgres)
#   DY_PSQL_EXE (默认自动探测 C:/Program Files/PostgreSQL/{17,16,15}/bin/psql.exe)
#   B12_EVIDENCE_DIR (默认 <skeleton>/verification/b12-evidence)
#
# 🛑 库名/角色名带 _b12 后缀: 本机的 diaoyuanyun_rls_test 此刻被 dy-config 的
#    落地件占用 (实测 public schema 内有 config_slot / app_config / app_config_history)。
#    本脚本第 1 步是 DROP DATABASE ... WITH (FORCE) —— 破坏性操作，
#    用共享库名会把另一个 worker 正在用的库强杀掉 (RlsGateSupport 类注释里
#    记载过同型事故: 症状是 42P01 "关系 xxx 不存在"，极易误判成业务 DDL 有 bug)。
#    故按该文件已确立的"模块隔离"约定使用独立库/角色。
#
# ── 幂等性怎么证（本脚本的关键设计） ──────────────────────────────────────
# 🔴 2026-09-24 更新（S1-2 验收④）：整条迁移链现在【全链幂等】。
#    旧版实测发现 V1 基线不幂等（V1 L71 裸 CREATE POLICY，重复执行必报
#    42710 duplicate_object），故旧脚本用"拆三步 + 断言 V1 必须失败"绕过。
#    S1-2 验收④ 明令「迁移脚本连跑 2 次幂等、无报错」，V1 已补
#    `DROP POLICY IF EXISTS`（仅令重放安全，策略定义未变）。故现改为：
#   ① 整链(V1+V2+…)应用一次              → schema 指纹 A、行数摘要 A
#   ② 只重复应用 V2（B 类实体交付物）     → 指纹 B == A  → V2 幂等 ✔
#   ③ 只重复应用 V1（基线，现已幂等）     → 必须【成功】且 指纹 C == A  → V1 幂等 ✔
#    （旧版的"V1 必须失败"断言已按验收④ 翻转；反向验证的射程改为
#     "V1 必须成功且指纹不变" —— 若有人塞回非幂等语句，本步照样报红。）
#
# 退出码: 0 = 全部通过; 非 0 = 某一步失败 (脚本不吞错，不静默降级)。
# ============================================================================
set -u -o pipefail

SKELETON="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VER="$SKELETON/verification"
MIG="$SKELETON/dy-app/src/main/resources/db/migration"
V1="$MIG/V1__baseline_tenant_rls.sql"
V2="$MIG/V2__b_entities_state_machine_and_band.sql"

PGHOST="${DY_PG_HOST:-127.0.0.1}"
PGPORT="${DY_PG_PORT:-5432}"
SUPERPW="${DY_PG_SUPER_PASSWORD:-postgres}"
DB="diaoyuanyun_rls_test_b12"
APPUSER="dy_app_b12"
APPPW="dy_app_b12_local_2026"
EV="${B12_EVIDENCE_DIR:-$VER/b12-evidence}"

# psql 定位
if [ -n "${DY_PSQL_EXE:-}" ] && [ -f "$DY_PSQL_EXE" ]; then
  PSQL="$DY_PSQL_EXE"
else
  PSQL=""
  for c in "C:/Program Files/PostgreSQL/17/bin/psql.exe" \
           "C:/Program Files/PostgreSQL/16/bin/psql.exe" \
           "C:/Program Files/PostgreSQL/15/bin/psql.exe" \
           /usr/bin/psql /usr/local/bin/psql /opt/homebrew/bin/psql; do
    if [ -f "$c" ]; then PSQL="$c"; break; fi
  done
  if [ -z "$PSQL" ]; then PSQL="$(command -v psql || true)"; fi
fi
if [ -z "$PSQL" ]; then
  echo "FATAL: 找不到 psql。请设 DY_PSQL_EXE=<psql 绝对路径>" >&2
  exit 2
fi

mkdir -p "$EV"
FAILED=0
RUN_LOG="$EV/run.log"
: > "$RUN_LOG"
log() { echo "$*" | tee -a "$RUN_LOG"; }

# 统一执行器: 记录命令 / 输出 / EXIT 码，原始输出留档，返回 psql 退出码
run_psql() {
  local label="$1"; shift
  local user="$1"; shift
  local pass="$1"; shift
  local database="$1"; shift
  local outfile="$EV/$label.out"
  log "----------------------------------------------------------------------"
  log "[$label] psql -U $user -d $database $*"
  PGPASSWORD="$pass" PGCLIENTENCODING=UTF8 \
    "$PSQL" -X -h "$PGHOST" -p "$PGPORT" -U "$user" -d "$database" \
    -v ON_ERROR_STOP=1 "$@" > "$outfile" 2>&1
  local rc=$?
  cat "$outfile" | tee -a "$RUN_LOG"
  log "[$label] EXIT=$rc"
  return $rc
}

log "=============================================================="
log " B 类实体（V2）真库验证   $(date '+%Y-%m-%d %H:%M:%S')"
log " psql   = $PSQL"
log " target = $DB   app role = $APPUSER"
log " V1     = $V1"
log " V2     = $V2"
log "=============================================================="

for f in "$V1" "$V2" "$VER/95_b12_reset.sql" "$VER/96_b12_apply.sql" \
         "$VER/97_b12_seed.sql" "$VER/94_b12_assert.sql"; do
  if [ ! -f "$f" ]; then log "FATAL: 缺文件 $f"; exit 2; fi
done
log "V1 sha256 = $(sha256sum "$V1" | cut -d' ' -f1)"
log "V2 sha256 = $(sha256sum "$V2" | cut -d' ' -f1)"

# ---------------------------------------------------------------------------
# 0) 重置环境（破坏性，但库名专属本任务）
# ---------------------------------------------------------------------------
if ! run_psql "00-reset" postgres "$SUPERPW" postgres -f "$VER/95_b12_reset.sql"; then
  log "FATAL: 重置失败"; exit 1
fi

# ---------------------------------------------------------------------------
# 1) 整链（V1 + V2）应用一次
# ---------------------------------------------------------------------------
if ! run_psql "01-apply-chain" postgres "$SUPERPW" "$DB" \
     -v "v1=$V1" -v "v2=$V2" -v "approle=$APPUSER" -f "$VER/96_b12_apply.sql"; then
  log "FATAL: 应用迁移链失败"; exit 1
fi

# schema 指纹: 表集合 + 列集合 + 约束 + 策略 + 索引（跨两次比对需逐字节一致）
schema_fingerprint() {
  PGPASSWORD="$SUPERPW" PGCLIENTENCODING=UTF8 "$PSQL" -X -h "$PGHOST" -p "$PGPORT" \
    -U postgres -d "$DB" -v ON_ERROR_STOP=1 -t -A -f - <<'SQL' 2>&1
SELECT string_agg(sig, E'\n' ORDER BY sig) FROM (
  SELECT format('TABLE %s', table_name) AS sig
    FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'
  UNION ALL
  SELECT format('COL %s.%s %s%s', table_name, column_name, data_type,
                coalesce('(' || character_maximum_length || ')',''))
    FROM information_schema.columns WHERE table_schema='public'
  UNION ALL
  SELECT format('CON %s %s %s', c.relname, pc.conname, pg_get_constraintdef(pc.oid))
    FROM pg_constraint pc JOIN pg_class c ON c.oid = pc.conrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname='public'
  UNION ALL
  SELECT format('POL %s %s', tablename, policyname)
    FROM pg_policies WHERE schemaname='public'
  UNION ALL
  SELECT format('IDX %s %s', tablename, indexname)
    FROM pg_indexes WHERE schemaname='public'
) s;
SQL
}
schema_fingerprint > "$EV/schema-A-after-chain.txt"

# 行数摘要（迁移登记行 + 两实体行数）—— 证明重复执行不会重复插数据
row_summary() {
  PGPASSWORD="$SUPERPW" PGCLIENTENCODING=UTF8 "$PSQL" -X -h "$PGHOST" -p "$PGPORT" \
    -U postgres -d "$DB" -v ON_ERROR_STOP=1 -t -A \
    -c "SELECT format('schema_migration=%s cst=%s band=%s V2_rows=%s',
          (SELECT count(*) FROM schema_migration),
          (SELECT count(*) FROM customer_state_transition),
          (SELECT count(*) FROM band),
          (SELECT count(*) FROM schema_migration WHERE version='V2'))" 2>&1
}
row_summary > "$EV/rows-A-after-chain.txt"

# ---------------------------------------------------------------------------
# 2) 【幂等证明】只重复应用 V2（本任务交付物）→ 指纹必须不变
# ---------------------------------------------------------------------------
if ! run_psql "02-reapply-v2" postgres "$SUPERPW" "$DB" \
     -v "v2=$V2" -v "approle=$APPUSER" -f "$VER/98_b12_apply_v2_only.sql"; then
  log "FATAL: V2 重复应用失败 —— V2 不幂等"; exit 1
fi

schema_fingerprint > "$EV/schema-B-after-v2-reapply.txt"
row_summary > "$EV/rows-B-after-v2-reapply.txt"

if diff -q "$EV/schema-A-after-chain.txt" "$EV/schema-B-after-v2-reapply.txt" >/dev/null; then
  log "[03-v2-idempotent] OK: V2 重复应用后 schema 指纹【逐字节一致】"
  log "[03-v2-idempotent] 指纹行数 = $(wc -l < "$EV/schema-B-after-v2-reapply.txt")"
else
  log "[03-v2-idempotent] FAIL: V2 重复应用改变了 schema"
  diff -u "$EV/schema-A-after-chain.txt" "$EV/schema-B-after-v2-reapply.txt" | tee -a "$RUN_LOG"
  FAILED=1
fi

if [ "$(cat "$EV/rows-A-after-chain.txt")" = "$(cat "$EV/rows-B-after-v2-reapply.txt")" ]; then
  log "[03-v2-idempotent] OK: 行数摘要不变 -> $(cat "$EV/rows-B-after-v2-reapply.txt")"
  log "[03-v2-idempotent]     (schema_migration 未重复插行, 两实体行数未翻倍)"
else
  log "[03-v2-idempotent] FAIL: 行数摘要变化 $(cat "$EV/rows-A-after-chain.txt") -> $(cat "$EV/rows-B-after-v2-reapply.txt")"
  FAILED=1
fi

# ---------------------------------------------------------------------------
# 3) 【幂等证明·V1 侧】只重复应用 V1 → 必须【成功】且 schema 指纹与 A 逐字节一致
#
#    🔴 2026-09-24 预期翻转（S1-2 验收④「迁移脚本连跑 2 次幂等、无报错」）:
#    旧版此处断言「V1 重复应用必须【失败】于 duplicate_object」，依据是
#    "V1 L71 是裸 CREATE POLICY，且 V1 是不可动的既有基线"。
#    该依据在 S1-2 被【明令推翻】: 验收④ 要求整条迁移链可重复应用，
#    而 V1 的裸 CREATE POLICY 使"连跑 2 次"在 V1 这一步就整体失败。
#    故 V1 已补 `DROP POLICY IF EXISTS tenant_isolation ON customer;`
#    （策略定义完全一致，仅让重放安全）—— 见 V1 L40-56 的修复说明。
#    期望随之翻转为"必须成功"，且证明强度【更高】: 现在整链（含 V1）都受幂等断言覆盖。
#
#    🦷 这一版仍保留"有牙齿"的性质:
#      若有人往 V1 里塞回一条非幂等语句（或删掉 DROP POLICY 守卫），
#      本步会以"重复应用失败"报红 —— 反向验证的射程只是从"V1 必须失败"改成了
#      "V1 必须成功且指纹不变"，而不是被删除。
#      至于"harness 到底有没有在执行迁移"，由第 1 步（整链应用后取指纹 A，
#      且 06 步 B0-B12 断言表确实存在）与 V2 侧第 2 步共同兜住。
# ---------------------------------------------------------------------------
if ! run_psql "04-v1-reapply-must-succeed" postgres "$SUPERPW" "$DB" \
     -v "v1=$V1" -v "approle=$APPUSER" -f "$VER/91_b12_apply_v1_only.sql"; then
  log "[04-v1-reapply] FAIL: 重复应用 V1 失败 —— V1 仍非幂等（S1-2 验收④不成立）"
  tail -8 "$EV/04-v1-reapply-must-succeed.out" | tee -a "$RUN_LOG"
  FAILED=1
else
  log "[04-v1-reapply] OK: V1 重复应用成功（幂等）"
fi

# 指纹必须仍未变 —— 这才是"幂等"的实质证明（成功但改坏了 schema 同样是缺陷）
schema_fingerprint > "$EV/schema-C-after-v1-reapply.txt"
if diff -q "$EV/schema-A-after-chain.txt" "$EV/schema-C-after-v1-reapply.txt" >/dev/null; then
  log "[04-v1-reapply] OK: V1 重复应用后 schema 指纹与整链后【逐字节一致】"
else
  log "[04-v1-reapply] FAIL: V1 重复应用改变了 schema"
  diff -u "$EV/schema-A-after-chain.txt" "$EV/schema-C-after-v1-reapply.txt" | tee -a "$RUN_LOG"
  FAILED=1
fi

# ---------------------------------------------------------------------------
# 4) 灌种子数据（非超级用户，证明策略连通）
# ---------------------------------------------------------------------------
if ! run_psql "05-seed" "$APPUSER" "$APPPW" "$DB" -f "$VER/97_b12_seed.sql"; then
  log "FATAL: 种子数据灌入失败"; exit 1
fi

# ---------------------------------------------------------------------------
# 5) 断言 B0-B12（非超级用户）
# ---------------------------------------------------------------------------
if ! run_psql "06-assert" "$APPUSER" "$APPPW" "$DB" -f "$VER/94_b12_assert.sql"; then
  log "FATAL: 断言脚本失败"; FAILED=1
else
  log "[06-assert] 全绿"
fi

# ---------------------------------------------------------------------------
# 6) 汇总
# ---------------------------------------------------------------------------
log "=============================================================="
if [ "$FAILED" -eq 0 ]; then
  log " B12 VERIFICATION: ALL PASSED"
  log " 证据目录: $EV"
  log "=============================================================="
  exit 0
else
  log " B12 VERIFICATION: FAILED (见上方 FAIL/FATAL)"
  log "=============================================================="
  exit 1
fi