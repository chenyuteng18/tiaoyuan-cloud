#!/usr/bin/env bash
# ============================================================================
# 92_b12_fk_closure_probe.sh — 证明 V2 第 4 节「条件式 FK 闭合」是活代码，不是死代码
#
# 为什么要这个探针:
#   V2 第 4 节的 FK 闭合块在当前仓库里【必然走跳过分支】(band_telemetry /
#   band_sync_probe / band_daily_coverage 三表尚未落库 —— §2.17【待评审条目 F-2】
#   宽/长表建模未定案)。于是产生一个无法回避的质疑:
#     "这个 DO 块到底是真的能加 FK，还是只是写在那里永不执行的一段死代码?"
#   99_b12_run.sh 的 B12 断言只能证明"跳过是显式的"(存在=0 已闭合FK=0 跳过=3),
#   无法证明"命中时确实会加"。本探针补上这一格。
#
# 做法 (不动仓库、不改 V2 字节):
#   ① 在 【本任务专属库 _b12】 里手工造一张最小 band_telemetry(只含 device_id)
#      —— 模拟"F-2 定案后该表由建表迁移自带"的那一天
#   ② 原样重放 V2 → 期望 NOTICE "已为 band_telemetry 追加 FK"
#   ③ 独立查 pg_constraint → 期望 conname=fk_band_telemetry_device_id_band
#      且 def = FOREIGN KEY (device_id) REFERENCES band(band_id)
#      (⚠️ 必须是 band(band_id) 不是 band(device_id)/device(device_id))
#   ④ 再重放一次 V2 → 期望 NOTICE "已存在, 跳过 (幂等)" 且无报错
#   ⑤ trap 清理探针表, 恢复干净状态 (探针残留纪律)
#
# 🛑 本探针【不创建】band_telemetry 的正式定义 —— 只造 device_id 一列用于验证
#    FK 动作可达。宽/长表怎么建仍归 §2.17 F-2 技术负责人拍板，本脚本不越界。
#    探针表在 trap 里 DROP，不留在库中、不落进任何迁移。
#
# 用法: bash skeleton/verification/92_b12_fk_closure_probe.sh
# 退出码: 0 = 四步全符合预期; 非 0 = 有一步不符（脚本不吞错）
# ============================================================================
set -u -o pipefail

SKELETON="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VER="$SKELETON/verification"
V2="$SKELETON/dy-app/src/main/resources/db/migration/V2__b_entities_state_machine_and_band.sql"

PGHOST="${DY_PG_HOST:-127.0.0.1}"
PGPORT="${DY_PG_PORT:-5432}"
SUPERPW="${DY_PG_SUPER_PASSWORD:-postgres}"
# 用本任务专属库: 探针会造表, 绝不能碰共享的 diaoyuanyun_rls_test
DB="${DY_B12_DB:-diaoyuanyun_rls_test_b12}"
EV="${B12_EVIDENCE_DIR:-$VER/b12-evidence}"
OUT="$EV/fk-closure-probe.out"

if [ -n "${DY_PSQL_EXE:-}" ] && [ -f "$DY_PSQL_EXE" ]; then
  PSQL="$DY_PSQL_EXE"
else
  PSQL=""
  for c in "C:/Program Files/PostgreSQL/17/bin/psql.exe" \
           "C:/Program Files/PostgreSQL/16/bin/psql.exe" \
           "C:/Program Files/PostgreSQL/15/bin/psql.exe" /usr/bin/psql; do
    if [ -f "$c" ]; then PSQL="$c"; break; fi
  done
  [ -n "$PSQL" ] || PSQL="$(command -v psql || true)"
fi
[ -n "$PSQL" ] || { echo "FATAL: 找不到 psql。请设 DY_PSQL_EXE=<绝对路径>" >&2; exit 2; }
[ -f "$V2" ] || { echo "FATAL: 缺 V2: $V2" >&2; exit 2; }

mkdir -p "$EV"
: > "$OUT"
say() { echo "$*" | tee -a "$OUT"; }
q() { PGPASSWORD="$SUPERPW" PGCLIENTENCODING=UTF8 "$PSQL" -X -h "$PGHOST" -p "$PGPORT" \
        -U postgres -d "$DB" -v ON_ERROR_STOP=1 "$@"; }

# ── 探针残留纪律: 无论成败/中断, 探针表必须消失 ──────────────────────────
cleanup() {
  q -c "DROP TABLE IF EXISTS band_telemetry;" >>"$OUT" 2>&1 || true
  say "  [cleanup] 探针表 band_telemetry 已 DROP (残留纪律)"
}
trap cleanup EXIT INT TERM

say "=============================================================="
say " V2 条件式 FK 闭合 —— 命中路径可达性探针   $(date '+%Y-%m-%d %H:%M:%S')"
say " psql = $PSQL"
say " db   = $DB  (本任务专属库)"
say " V2   = $V2"
say " V2 sha256 = $(sha256sum "$V2" | cut -d' ' -f1)"
say "=============================================================="

# 前置: 本库必须先应用过 V1+V2（否则 band 表都不在，探针无意义）
if ! q -t -A -c "SELECT to_regclass('public.band') IS NOT NULL;" 2>/dev/null | grep -q '^t$'; then
  say "FATAL: $DB 里没有 band 表 —— 请先跑 99_b12_run.sh 建库。"
  exit 2
fi

say ""
say "── 步骤 1: 造最小 band_telemetry(仅 device_id) 模拟 'F-2 定案后该表已存在' ──"
# ⚠️ 本步骤的 SQL 必须【纯 ASCII】: 本机 shell locale 是 GBK, 若把中文注释写进
#    psql -c 的内联 SQL, 而同时设了 PGCLIENTENCODING=UTF8, psql 会把 GBK 字节当
#    UTF-8 解析并直接报 "无效的 UTF8 编码字节顺序" (RC=1) —— 表现为"建表静默没建"。
#    (99_b12_run.sh 全用 -f <UTF-8 文件> 故不受影响; 本探针用内联 -c 故必须避坑。)
#    故此处用 here-doc 走 stdin, 注释一律留在 shell 的 say 里。
q -f - >>"$OUT" 2>&1 <<'SQL'
CREATE TABLE IF NOT EXISTS band_telemetry (
    device_id uuid,
    tenant_id uuid,
    payload   jsonb
);
-- 前置保险: 若历史残留已带该 FK, 先摘掉, 保证下面看到的是本次新加的
ALTER TABLE band_telemetry DROP CONSTRAINT IF EXISTS fk_band_telemetry_device_id_band;
SQL
STEP1_RC=$?
if [ "$STEP1_RC" -ne 0 ]; then
  say "  FAIL: 步骤 1 建探针表失败 EXIT=$STEP1_RC (见 $OUT)"
  exit 1
fi
# 前置信: 表必须真的存在, 否则下面的"命中"根本无从谈起
if ! q -t -A -c "SELECT to_regclass('public.band_telemetry') IS NOT NULL;" | grep -q '^t$'; then
  say "  FAIL: 步骤 1 执行后 band_telemetry 仍不存在 —— 探针前置未成立, 结论不可信"
  exit 1
fi
say "  OK: band_telemetry 已造出并复核存在(仅 3 列, 探针用途), 历史 FK 已摘净"

say ""
say "── 步骤 2: 原样重放 V2 —— 期望命中并追加 FK ──"
STEP2=$(q -f "$V2" 2>&1); echo "$STEP2" >>"$OUT"
if ! printf '%s' "$STEP2" | grep -q "已为 band_telemetry 追加 FK"; then
  say "  FAIL: 重放 V2 未出现 '已为 band_telemetry 追加 FK' —— 命中分支不可达"
  say "$STEP2" | grep -i "band_telemetry" | tee -a "$OUT"
  exit 1
fi
say "  OK: 命中分支触发 —— $(printf '%s' "$STEP2" | grep -o 'V2: 已为 band_telemetry 追加 FK[^ ]*.*' | head -1)"

say ""
say "── 步骤 3: 独立查 pg_constraint —— 期望指向 band(band_id) ──"
FK=$(q -t -A -c "SELECT conname || ' :: ' || pg_get_constraintdef(oid)
                  FROM pg_constraint WHERE conname='fk_band_telemetry_device_id_band';")
say "  实查 = ${FK:-<空>}"
echo "$FK" >>"$OUT"
if [ -z "$FK" ]; then
  say "  FAIL: FK fk_band_telemetry_device_id_band 不存在"
  exit 1
fi
case "$FK" in
  *"FOREIGN KEY (device_id) REFERENCES band(band_id)"*)
    say "  OK: FK 指向 band(band_id) —— 正确闭合到客户级手环台账" ;;
  *)
    say "  FAIL: FK 定义与预期不符(可能指向了 device 而非 band): $FK"
    exit 1 ;;
esac

say ""
say "── 步骤 4: 再重放一次 V2 —— 期望 '已存在, 跳过 (幂等)' 且无报错 ──"
if ! STEP4=$(q -f "$V2" 2>&1); then
  say "  FAIL: 第二次重放 V2 报错 —— 条件式 FK 幂等性破坏"
  printf '%s\n' "$STEP4" >>"$OUT"
  exit 1
fi
echo "$STEP4" >>"$OUT"
if ! printf '%s' "$STEP4" | grep -q "band_telemetry 的 FK 已存在, 跳过"; then
  say "  FAIL: 第二次重放未走 '已存在, 跳过' 分支"
  printf '%s' "$STEP4" | grep -i "band_telemetry" | tee -a "$OUT"
  exit 1
fi
say "  OK: 幂等 —— 第二次重放走跳过分支, 未重复 ADD CONSTRAINT"

say ""
say "=============================================================="
say " 结论: V2 第 4 节的条件式 FK 闭合【命中分支可达且幂等】"
say "   · 目标表存在 -> 追加 FK device_id -> band(band_id)  (实测)"
say "   · 目标表不存在 -> 显式跳过 + NOTICE 报出表名        (99 的 B12 断言覆盖)"
say "   · 重复执行 -> 跳过, 不报错                          (实测)"
say " 🛑 仍未定案的是这三张表的【宽/长表建表方式】(§2.17 F-2, 拍板人=技术负责人);"
say "    本探针只证明 FK 动作可达, 不对表结构做任何选择。"
say "=============================================================="
exit 0