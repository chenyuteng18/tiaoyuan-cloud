#!/usr/bin/env bash
# ============================================================================
# 90_b12_injection.sh — B 类实体验证的【注入实验】(证明 harness 有牙齿)
#
# 为什么必须要这一步：
#   "断言全绿"本身不能证明 harness 有效 —— 一个把断言写错、或根本没跑起来的
#   harness 也会全绿。唯一可信的证法是【注入一个已知错误，证明它必须变红】。
#   本脚本对迁移做三处注入，逐条要求断言脚本报红；任何一处注入后仍然全绿，
#   就说明本 harness 对该缺陷是"瞎的"，脚本判 FAIL。
#
# 🛑 探针残留纪律（任务书硬约束）：
#   skeleton/client-package/ 是构建期词表扫描区，往里留临时文件会造成永久幻影违规。
#   本脚本的全部变异副本写在【仓库外】的临时目录里，并用 trap EXIT 做无条件清理；
#   本脚本【不写入】仓库内任何路径（除自身与证据目录）。
#
# 用法: bash skeleton/verification/90_b12_injection.sh
# 退出码: 0 = 三处注入均按其预期变红(且基线为绿); 非 0 = harness 有盲区
# ============================================================================
set -u -o pipefail

SKELETON="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VER="$SKELETON/verification"
MIG="$SKELETON/dy-app/src/main/resources/db/migration"
V1_REAL="$MIG/V1__baseline_tenant_rls.sql"
V2_REAL="$MIG/V2__b_entities_state_machine_and_band.sql"

PGHOST="${DY_PG_HOST:-127.0.0.1}"
PGPORT="${DY_PG_PORT:-5432}"
SUPERPW="${DY_PG_SUPER_PASSWORD:-postgres}"
DB="diaoyuanyun_rls_test_b12"
APPUSER="dy_app_b12"
APPPW="dy_app_b12_local_2026"
EV="${B12_EVIDENCE_DIR:-$VER/b12-evidence}"
INJEV="$EV/injection"

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
[ -n "$PSQL" ] || { echo "FATAL: 找不到 psql" >&2; exit 2; }

# 仓库外临时目录 + 无条件清理（探针残留纪律）
#
# ⚠️ 本机（Git Bash on Windows）实测坑：`mktemp -d` 返回的是【Windows 风格路径】
#    `C:\Users\...\Temp/b12-injection.XXXXXX`，而 `rm -rf` 无法解析这种反斜杠路径，
#    会静默失败 —— 配 `|| true` 后脚本"看起来"清理成功，实际临时目录【留在盘上】。
#    这正是本脚本早期版本的行为（注入残留纪律的反面教材）。
#    修法：① 归一化为 POSIX 路径 (cygpath -u)，② 清完【复核】，③ 裸报"已清理"
#    改成"已确认不存在"，做不到就明确报红 —— 不允许再出现"声称清了其实没清"。
if command -v cygpath >/dev/null 2>&1; then
  WORK_POSIX="$(cygpath -u "$(mktemp -d "${TMPDIR:-/tmp}/b12-injection.XXXXXX")")"
  WORK="$WORK_POSIX"
else
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/b12-injection.XXXXXX")"
fi
[ -d "$WORK" ] || { echo "FATAL: 临时目录创建失败" >&2; exit 2; }

cleanup() {
  rm -rf "$WORK" 2>/dev/null || true
  if [ -e "$WORK" ]; then
    # 兜底: 再试一次 Windows 原生删除, 仍失败则明确报错(不静默)
    if command -v cmd >/dev/null 2>&1; then
      WIN="$(cygpath -w "$WORK" 2>/dev/null || echo "$WORK")"
      cmd //c "rmdir /s /q \"$WIN\"" >/dev/null 2>&1 || true
    fi
  fi
  if [ -e "$WORK" ]; then
    echo "[cleanup] ⚠️ 警告: 临时目录未能删除, 请手工清理: $WORK" >&2
  else
    echo "[cleanup] 已确认临时目录不存在: $WORK"
  fi
}
trap cleanup EXIT

mkdir -p "$INJEV"
LOG="$INJEV/injection.log"
: > "$LOG"
log() { echo "$*" | tee -a "$LOG"; }

psql_run() {
  local user="$1" pass="$2" db="$3"; shift 3
  PGPASSWORD="$pass" PGCLIENTENCODING=UTF8 \
    "$PSQL" -X -h "$PGHOST" -p "$PGPORT" -U "$user" -d "$db" -v ON_ERROR_STOP=1 "$@"
}

log "=============================================================="
log " B12 注入实验   $(date '+%Y-%m-%d %H:%M:%S')"
log " 临时目录(仓库外) = $WORK"
log "=============================================================="
log "V1_REAL sha256 = $(sha256sum "$V1_REAL" | cut -d' ' -f1)"
log "V2_REAL sha256 = $(sha256sum "$V2_REAL" | cut -d' ' -f1)"

# ---------------------------------------------------------------------------
# 单次实验骨架: reset -> apply(v1,v2) -> seed -> assert
# 回显: 打印各步 EXIT; 返回 assert 的退出码
# ---------------------------------------------------------------------------
run_case() {
  local name="$1" v1="$2" v2="$3"
  local out="$INJEV/$name"
  mkdir -p "$out"

  if ! psql_run postgres "$SUPERPW" postgres -f "$VER/95_b12_reset.sql" > "$out/0-reset.out" 2>&1; then
    log "[$name] reset 失败"; return 99
  fi
  if ! psql_run postgres "$SUPERPW" "$DB" \
        -v "v1=$v1" -v "v2=$v2" -v "approle=$APPUSER" \
        -f "$VER/96_b12_apply.sql" > "$out/1-apply.out" 2>&1; then
    log "[$name] apply 失败"; tail -15 "$out/1-apply.out" | tee -a "$LOG"; return 98
  fi
  if ! psql_run "$APPUSER" "$APPPW" "$DB" -f "$VER/97_b12_seed.sql" > "$out/2-seed.out" 2>&1; then
    log "[$name] seed 失败"; return 97
  fi
  psql_run "$APPUSER" "$APPPW" "$DB" -f "$VER/94_b12_assert.sql" > "$out/3-assert.out" 2>&1
  return $?
}

PASS=0
FAIL=0

# ---------------------------------------------------------------------------
# 情形 0: 基线（未注入）→ 必须全绿
# ---------------------------------------------------------------------------
log "----------------------------------------------------------------------"
log "[case-0-baseline] 未注入的迁移 -> 断言必须 EXIT=0"
SRC="$WORK/V1.real.sql"; cp "$V1_REAL" "$SRC"
SRC2="$WORK/V2.real.sql"; cp "$V2_REAL" "$SRC2"
run_case "case-0-baseline" "$SRC" "$SRC2"; rc=$?
if [ "$rc" -eq 0 ]; then
  log "[case-0-baseline] OK EXIT=0 (基线绿 —— 后续注入变红才有对照意义)"
  PASS=$((PASS+1))
else
  log "[case-0-baseline] FAIL EXIT=$rc —— 基线本身就红, 无法据此判断注入结果"
  tail -25 "$INJEV/case-0-baseline/3-assert.out" | tee -a "$LOG"
  FAIL=$((FAIL+1))
fi

# ---------------------------------------------------------------------------
# 注入 A: 把 V2 里 to_state 的 14 态 CHECK【放宽】一个值 → B2 必须报红
#
# 为什么是"放宽"而不是"删除"：
#   初版注入用正则整段删掉 CHECK，结果产出的变异文件本身有语法错误
#   （to_state VARCHAR(32) NOT NULL)），迁移在 apply 阶段就失败，
#   断言根本没跑到 —— 那证明的是"变异文件坏了"，不是"harness 有牙齿"。
#   放宽取值集是【语义合法】的变异：迁移可正常应用、种子可正常灌入，
#   唯一变化是"多了一个非法态被接受" —— 这才把矛头精确指向 B2。
# ---------------------------------------------------------------------------
log "----------------------------------------------------------------------"
log "[case-A-widen-state-check] 放宽 to_state 取值集(多一个非法态) -> B2 必须报红"
INJ_A="$WORK/V2.widened_check.sql"
python - "$V2_REAL" "$INJ_A" <<'PY'
import io, sys
src, dst = sys.argv[1], sys.argv[2]
s = io.open(src, encoding='utf-8').read()
key = "CHECK (to_state IN ("
i = s.index(key)
mark = "'TERMINATED'"
j = s.index(mark, i)
assert s[j + len(mark)] == ')' , "锚点后不是 ')' —— 变异会产出非法 SQL"
new = s[:j + len(mark)] + ", 'NOT_A_REAL_STATE'" + s[j + len(mark):]
io.open(dst, 'w', encoding='utf-8').write(new)
print("          注入A: to_state 取值集 14 -> 15 (追加 'NOT_A_REAL_STATE'), 长度 %d -> %d" % (len(s), len(new)))
PY
if [ "$(sha256sum "$V2_REAL" | cut -d' ' -f1)" = "$(sha256sum "$INJ_A" | cut -d' ' -f1)" ]; then
  log "[case-A] FAIL: 变异文件与原文完全相同 —— 注入是空操作, 不能作为证据"; FAIL=$((FAIL+1))
else
  run_case "case-A-widen-state-check" "$SRC" "$INJ_A"; rc=$?
  if [ "$rc" -ne 0 ] && grep -q "B2 失败" "$INJEV/case-A-widen-state-check/3-assert.out"; then
    log "[case-A] OK: 断言 EXIT=$rc, 且捕获到 'B2 失败' -> $(grep -m1 'B2 失败' "$INJEV/case-A-widen-state-check/3-assert.out" | tr -d '\r')"
    PASS=$((PASS+1))
  else
    log "[case-A] FAIL: EXIT=$rc 且未捕获 'B2 失败' —— harness 对'CHECK 被放宽'是瞎的"; FAIL=$((FAIL+1))
    tail -20 "$INJEV/case-A-widen-state-check/3-assert.out" | tee -a "$LOG"
  fi
fi

# ---------------------------------------------------------------------------
# 注入 B: 把 V2 两表的 RLS 策略谓词改成 fail-OPEN（USING/WITH CHECK 恒真）→ 隔离断言必须报红
#
# 为什么注入"改成 true"而不是"删掉策略"：
#   策略被【删除】时，RLS 仍 ENABLE+FORCE，等于"全表拒绝" —— 种子数据都灌不进去，
#   管线在 seed 阶段就断了，断言根本跑不到。那证明的是"缺策略会被上游挡住"，
#   而不是"隔离断言能识破 fail-open"。
#   而 USING(true) 是 V1 注释明令禁止的、最危险的形态：管线全程可跑通（读得到、
#   写得到），只有【隔离语义】被悄悄抹掉 —— 这正是本 harness 最该抓住的缺陷。
# ---------------------------------------------------------------------------
log "----------------------------------------------------------------------"
log "[case-B-fail-open-policy] 策略谓词改成 USING/WITH CHECK (true) -> 隔离断言必须报红"
INJ_B="$WORK/V2.fail_open.sql"
python - "$V2_REAL" "$INJ_B" <<'PY'
import io, re, sys
src, dst = sys.argv[1], sys.argv[2]
s = io.open(src, encoding='utf-8').read()
pat = re.compile(
    r"USING\s+\(tenant_id = NULLIF\(current_setting\('app\.tenant_id', true\), ''\)::uuid\)"
    r"\s*\n\s*WITH CHECK \(tenant_id = NULLIF\(current_setting\('app\.tenant_id', true\), ''\)::uuid\)",
    re.S)
new, n = pat.subn("USING      (true)\n    WITH CHECK (true)", s)
assert n == 2, "注入B 期望改掉 2 条策略, 实际匹配 %d" % n
io.open(dst, 'w', encoding='utf-8').write(new)
print("          注入B: 已把 %d 条 tenant_isolation 策略改成 fail-open (USING(true))" % n)
PY
if [ "$(sha256sum "$V2_REAL" | cut -d' ' -f1)" = "$(sha256sum "$INJ_B" | cut -d' ' -f1)" ]; then
  log "[case-B] FAIL: 变异文件与原文相同 —— 空操作"; FAIL=$((FAIL+1))
else
  run_case "case-B-fail-open-policy" "$SRC" "$INJ_B"; rc=$?
  if [ "$rc" -ne 0 ] && grep -qE "B4 失败|B4b 失败|B5 失败|B5b 失败|B5c 失败|B8 失败" "$INJEV/case-B-fail-open-policy/3-assert.out"; then
    log "[case-B] OK: 断言 EXIT=$rc, 且捕获到隔离类失败 -> $(grep -m1 -E 'B4 失败|B4b 失败|B5 失败|B5b 失败|B5c 失败|B8 失败' "$INJEV/case-B-fail-open-policy/3-assert.out" | tr -d '\r')"
    PASS=$((PASS+1))
  else
    log "[case-B] FAIL: EXIT=$rc 且未捕获隔离类失败 —— harness 对'fail-open 策略'是瞎的"; FAIL=$((FAIL+1))
    tail -20 "$INJEV/case-B-fail-open-policy/3-assert.out" | tee -a "$LOG"
  fi
fi

# ---------------------------------------------------------------------------
# 注入 B2 (补充观测, 不计入通过数): 完全【删除】两表策略
#   预期: 管线在 seed 阶段即被全表拒绝挡住 (RLS ENABLE+FORCE 且无策略 = 全拒)。
#   记录这条是为了说明"删策略"与"改 fail-open"是两类不同的失败面,
#   本 harness 的强项在后者(语义被抹掉但仍可运行)。
# ---------------------------------------------------------------------------
log "----------------------------------------------------------------------"
log "[case-B2-policy-deleted] 完全删除两表策略 -> 预期管线在 seed 阶段即断(全表拒绝)"
INJ_B2="$WORK/V2.no_policy.sql"
python - "$V2_REAL" "$INJ_B2" <<'PY'
import io, re, sys
src, dst = sys.argv[1], sys.argv[2]
s = io.open(src, encoding='utf-8').read()
pat = re.compile(r"CREATE POLICY\s+tenant_isolation\s+ON\s+\w+\s+FOR ALL\s+USING.*?WITH CHECK.*?;", re.S)
new, n = pat.subn("-- [INJECTION B2] tenant_isolation policy removed", s)
assert n == 2, "注入B2 期望删掉 2 条策略, 实际 %d" % n
io.open(dst, 'w', encoding='utf-8').write(new)
print("          注入B2: 已删除 %d 条 tenant_isolation 策略" % n)
PY
run_case "case-B2-policy-deleted" "$SRC" "$INJ_B2"; rc=$?
if [ "$rc" -ne 0 ]; then
  log "[case-B2] OK(预期失败): 管线 EXIT=$rc —— 缺策略时 RLS 全拒, 种子阶段即被挡住"
  if [ -f "$INJEV/case-B2-policy-deleted/2-seed.out" ]; then
    log "[case-B2] seed 阶段失败原文(尾部):"
    tail -4 "$INJEV/case-B2-policy-deleted/2-seed.out" | tee -a "$LOG"
  fi
else
  log "[case-B2] FAIL: 删掉策略后管线竟然跑通 —— RLS 形同虚设"; FAIL=$((FAIL+1))
fi

# ---------------------------------------------------------------------------
# 注入 C: 给 customer.status 加一个 CHECK 约束（模拟"5 值口径被改动"）→ B6 必须报红
# ---------------------------------------------------------------------------
log "----------------------------------------------------------------------"
log "[case-C-customer-status-changed] 给 customer.status 加 CHECK -> B6 必须报红"
INJ_C="$WORK/V1.status_check.sql"
python - "$V1_REAL" "$INJ_C" <<'PY'
import io, sys
src, dst = sys.argv[1], sys.argv[2]
s = io.open(src, encoding='utf-8').read()
anchor = "CREATE INDEX IF NOT EXISTS idx_customer_tenant ON customer (tenant_id);"
assert anchor in s, "注入C 未找到锚点, 变异为空操作"
add = ("\n-- [INJECTION C] 模拟 customer.status 5 值口径被改动\n"
       "ALTER TABLE customer ADD CONSTRAINT ck_customer_status_probe\n"
       "    CHECK (status IN ('pending','active','paused','archived','closed'));\n")
new = s.replace(anchor, anchor + add, 1)
io.open(dst, 'w', encoding='utf-8').write(new)
print("          注入C: 已给 customer.status 追加 CHECK 约束")
PY
if [ "$(sha256sum "$V1_REAL" | cut -d' ' -f1)" = "$(sha256sum "$INJ_C" | cut -d' ' -f1)" ]; then
  log "[case-C] FAIL: 变异文件与原文相同 —— 空操作"; FAIL=$((FAIL+1))
else
  run_case "case-C-customer-status-changed" "$INJ_C" "$SRC2"; rc=$?
  if [ "$rc" -ne 0 ] && grep -q "B6 失败" "$INJEV/case-C-customer-status-changed/3-assert.out"; then
    log "[case-C] OK: 断言 EXIT=$rc, 且捕获到 'B6 失败' -> $(grep -m1 'B6 失败' "$INJEV/case-C-customer-status-changed/3-assert.out" | tr -d '\r')"
    PASS=$((PASS+1))
  else
    log "[case-C] FAIL: EXIT=$rc 且未捕获 'B6 失败' —— harness 对'customer.status 被改'是瞎的"; FAIL=$((FAIL+1))
    tail -20 "$INJEV/case-C-customer-status-changed/3-assert.out" | tee -a "$LOG"
  fi
fi

# ---------------------------------------------------------------------------
# 收尾: 把库恢复成"未注入"的干净态, 避免后续读者拿到带注入残留的库
# ---------------------------------------------------------------------------
log "----------------------------------------------------------------------"
log "[restore] 用未注入的迁移重建库, 清除注入残留"
psql_run postgres "$SUPERPW" postgres -f "$VER/95_b12_reset.sql" >> "$LOG" 2>&1
psql_run postgres "$SUPERPW" "$DB" -v "v1=$SRC" -v "v2=$SRC2" -v "approle=$APPUSER" \
  -f "$VER/96_b12_apply.sql" >> "$LOG" 2>&1
psql_run "$APPUSER" "$APPPW" "$DB" -f "$VER/97_b12_seed.sql" >> "$LOG" 2>&1
psql_run "$APPUSER" "$APPPW" "$DB" -f "$VER/94_b12_assert.sql" > "$INJEV/restored-assert.out" 2>&1
RC_RESTORE=$?
log "[restore] 恢复后断言 EXIT=$RC_RESTORE (期望 0)"

log "=============================================================="
log " 注入实验汇总: 通过 $PASS / 失败 $FAIL （共 4 个情形: 基线 + 3 注入）"
log " 仓库外临时目录: $WORK （将在退出时由 trap 删除并复核，见下方 [cleanup] 行）"
log "=============================================================="
if [ "$FAIL" -eq 0 ] && [ "$RC_RESTORE" -eq 0 ]; then
  log " B12 INJECTION: ALL PASSED (harness 有牙齿)"
  exit 0
else
  log " B12 INJECTION: FAILED"
  exit 1
fi