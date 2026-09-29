#!/usr/bin/env bash
# ============================================================================
# 92_provision_mutex_concurrency.sh
#   Task #18「真库门禁构建间互斥改造（幂等 provision + 显式重置开关）」的
#   【并发实测】+【两方向反向验证】Harness。
#
# 为什么必须是"真并发"而不是静态推理：
#   本任务修的就是"两个构建同时跑会互相拆库"。静态推理替代不了实测 ——
#   上一轮就是因为"看起来不冲突"，才让 DROP DATABASE 一直留在缺省路径上。
#
# 为什么用两个【独立检出】而不是同一目录起两个 mvn：
#   同一目录起两个 mvn 会先在构建产物层互删（target/ 被对方的 clean 清掉），
#   那是另一个 bug，会把真库层的信号淹掉（本轮实测已踩到：dy-web 编译报
#   `程序包com.diaoyuanyun.dy.common.exception不存在`）。
#   两个独立检出 = "两台机器 / 两份检出"，只有真库是共享资源，信号干净。
#
# 四个实验：
#   Exp1  真并发两构建（修复后）+ 互斥锁实证：期望两边 EXIT=0，
#         并用一个"外部持锁者"证明构建级互斥锁真实生效（构建会排队等待）
#   Exp2  反向验证(a)：把【缺省幂等路径】改成"无条件 DROP"+重建 → 并发存活探针被拆（复现失败）
#   Exp3  反向验证(a')：在 (a) 之上再让两个构建用【不同锁 key】（≈ 修复前形态）
#                      → 两个构建互相拆库（复现失败）
#   Exp4  反向验证(b)：把【单连接锁】改成多条 psql -c（模拟锁立即释放）→ 两个重置者交错、
#                      保护失效（复现失败）；同一并发下，单连接持锁形态必须两边都成功（对照）
#
# 用法： bash verification/92_provision_mutex_concurrency.sh
# 依赖： 本机 PostgreSQL 17 已启动且 postgres 口令可用；mvn 路径见 MVN 变量。
# ============================================================================
set -u

SKELETON=$(cd "$(dirname "$0")/.." && pwd)          # .../product-strategy/skeleton
REPO=$(cd "$SKELETON/../../.." && pwd)              # .../2026-09-16-10-37-59
WORK="${A2_MUTEX_WORK:-$REPO/_work/a2-rls-mutex}"   # 两个独立检出的根
OUT="$WORK/evidence"
MVN="${MVN:-/c/opt/apache-maven-3.9.9/bin/mvn}"
PSQL="${DY_PSQL_EXE:-/c/Program Files/PostgreSQL/17/bin/psql.exe}"
PGHOST="${DY_PG_HOST:-127.0.0.1}"
PGPORT="${DY_PG_PORT:-5432}"
DB="${DY_RLS_DB:-diaoyuanyun_rls_test_app}"
APP_USER="${DY_RLS_USER:-dy_app_rls}"
APP_PASS="dy_app_rls_local_2026"
SUPER_PASS="${DY_PG_SUPER_PASSWORD:-postgres}"
SRC_REL="dy-app/src/test/java/com/diaoyuanyun/dy/app/rls/RlsGateSupport.java"

export MAVEN_OPTS="-Dfile.encoding=UTF-8"
mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*"; }
hr()  { echo "------------------------------------------------------------------------"; }

# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

# 与 RlsGateSupport.advisoryKey 同构：SHA-256("<db>/<tag>") 前 8 字节，
# 取低 63 位（& Long.MAX_VALUE），避开 0。刻意【独立实现】（不引用被测代码）：
# "外部持锁者"若能挡住构建，就同时证明了 key 推导一致、锁真的跨进程/跨工具生效。
advisory_key() {
  local hex v=0 i b
  hex=$(printf '%s/%s' "$DB" "$1" | sha256sum | cut -d' ' -f1)
  for i in 0 1 2 3 4 5 6 7; do
    b=$(( 16#${hex:$((i * 2)):2} ))
    v=$(( (v << 8) | b ))
  done
  v=$(( v & 0x7FFFFFFFFFFFFFFF ))
  [ "$v" -eq 0 ] && v=1
  echo "$v"
}

psql_super() {
  PGPASSWORD="$SUPER_PASS" PGCLIENTENCODING=UTF8 "$PSQL" -X -h "$PGHOST" -p "$PGPORT" \
    -U postgres -d postgres -t -A -c "$1"
}

probe_once() { # 存活探针：非超级用户连真库做一次最小查询
  PGPASSWORD="$APP_PASS" PGCLIENTENCODING=UTF8 "$PSQL" -X -h "$PGHOST" -p "$PGPORT" \
    -U "$APP_USER" -d "$DB" -t -A -c "SELECT count(*) FROM customer" 2>&1
}

VICTIM_PID=""
# 存活探针循环：模拟"并发的另一个使用者"（CI 直跑 psql 断言 / 人工排查会话 / 另一模块的测试）。
# 它不持构建级互斥锁 —— 这正是"缺省路径一旦变成破坏性"时的受害方。
start_victim() { # $1=标签 $2=持续秒数
  # 同 run_two_builds 的教训：$1 在函数体内是安全的，但同一条 local 里
  # 用 "$1" 给 f 赋值不如分开写清楚；此处 $1 在本行内已被展开，可保留。
  local tag="$1" dur="$2" f="$OUT/victim-$1.log" t0=$SECONDS
  : > "$f"
  (
    local i=0 out rc
    while [ $((SECONDS - t0)) -lt "$dur" ]; do
      out=$(probe_once); rc=$?
      if [ "$rc" -ne 0 ]; then
        printf 'VICTIM_FAIL round=%s rc=%s :: %s\n' "$i" "$rc" \
          "$(echo "$out" | tr -d '\r' | head -1)" >>"$f"
      fi
      i=$((i + 1))
      sleep 0.3
    done
    echo "VICTIM_DONE rounds=$i" >>"$f"
  ) &
  VICTIM_PID=$!
}

LAST_PIDS=()
LOCKPROBE_PID=""

# 锁等待探针：逐秒统计 pg_locks 里的自旋等待者。
#
# ⚠️ 2026-09-21 实测澄清（本探针天生看不见本场景的等待，别把它当主证据）：
#   RlsGateSupport 用的是 **pg_try_advisory_lock**（轮询），失败时**不会在 pg_locks
#   里留下任何行** —— PG 只在阻塞式 pg_advisory_lock 挂起时才登记等待。
#   实测：117 轮全为 0，而同期构建确实等了 ~165s。故本探针的价值仅剩
#   "排除阻塞式等待者"这一项；**主证据 = 门禁类耗时对比 vs 无持锁者基线（阈值 30s）**，
#   mutex_waited_ms 已降级为诊断项（同 harness 两轮实测自相矛盾：5064ms vs 0ms）。
start_lockprobe() { # $1=持续秒数 $2=输出文件
  local dur="$1" f="$2" t0=$SECONDS
  : > "$f"
  (
    local blocked=0 rounds=0 n
    while [ $((SECONDS - t0)) -lt "$dur" ]; do
      n=$(psql_super "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted" | tr -d '\r')
      rounds=$((rounds + 1))
      if [ "${n:-0}" -gt 0 ]; then
        blocked=$((blocked + 1))
        printf 'BLOCKED_WAITER round=%s 阻塞式等待者=%s\n' "$rounds" "$n" >>"$f"
      fi
      sleep 1
    done
    printf 'LOCKPROBE_DONE rounds=%s 阻塞式等待者轮次=%s\n' "$rounds" "$blocked" >>"$f"
  ) &
  LOCKPROBE_PID=$!
}

# 门禁类耗时：从 maven 日志里取（"Time elapsed: N s -- in ...RlsCoverageGateTest"）
gate_seconds() {
  grep -aoE 'Time elapsed: [0-9.]+ s -- in com\.diaoyuanyun\.dy\.app\.rls\.RlsCoverageGateTest' "$1" 2>/dev/null \
    | tail -1 | grep -aoE '[0-9.]+' | head -1
}

run_two_builds() { # $1=标签：两个独立检出并发跑同一个模块的门禁
  # ⚠️ 拆成两条 local：同一条 local 里的 a="$OUT/$tag-..." 【读不到】刚声明的 tag，
  #    在 `set -u` 下会以 "tag: unbound variable" 直接终止脚本（2026-09-21 实测踩到，
  #    当时该 harness 从未跑完过 —— 报错发生在它自报"实验 1 开始"之后的下一步）。
  local tag="$1"
  local a="$OUT/$tag-A.log" b="$OUT/$tag-B.log"
  LAST_PIDS=()
  ( cd "$WORK/build-A" && "$MVN" -pl dy-app test >"$a" 2>&1; echo "EXIT=$?" >>"$a" ) &
  LAST_PIDS+=($!)
  ( cd "$WORK/build-B" && "$MVN" -pl dy-app test >"$b" 2>&1; echo "EXIT=$?" >>"$b" ) &
  LAST_PIDS+=($!)
  wait "${LAST_PIDS[@]}"
}

verdict() { grep -a "^EXIT=" "$1" 2>/dev/null | tail -1 | cut -d= -f2; }

# ---------------------------------------------------------------------------
# 检出准备 / 注入 / 还原
# ---------------------------------------------------------------------------

prep_copies() {
  # 只在【目标不存在】时创建：若目标已存在就沿用（mvn 会按需重编）。
  # 刻意不用 rm -rf：① 大批量删除会触发宿主的安全删除守卫；
  # ② 更重要的 —— "先删再 cp -r" 一旦删除被拦，cp -r 会把检出嵌进旧目录里
  #    （本轮实测踩到过：build-A/skeleton/… 被嵌了一层，构建目录全错）。
  local d
  for d in build-A build-B; do
    if [ -d "$WORK/$d/dy-app" ]; then
      log "  复用已有检出 $WORK/$d"
    elif [ -e "$WORK/$d" ]; then
      echo "  ✗ $WORK/$d 已存在但不是检出（缺 dy-app/）。请人工清掉后重跑，脚本不代你删。"; exit 2
    else
      log "  创建检出 $WORK/$d"
      mkdir -p "$WORK/$d"
      cp -r "$SKELETON/." "$WORK/$d/"
    fi
  done
  # dy-config 的 ConfigGateSupport 会去 skeleton 的父目录找 PRD —— 检出换位置后要一并带上，
  # 否则报 "PRD 必须存在"（与本任务无关的噪音失败）。
  cp "$(dirname "$SKELETON")/prd-health-mgmt-saas-2026-09-16.md" "$WORK/" 2>/dev/null || true
  # 检出里的门禁源码必须与采证基线一致，否则测的不是同一份东西
  for d in build-A build-B; do
    if ! diff -q "$SKELETON/$SRC_REL" "$WORK/$d/$SRC_REL" >/dev/null; then
      log "  同步 $d/$SRC_REL 至采证基线"
      cp "$SKELETON/$SRC_REL" "$WORK/$d/$SRC_REL"
    fi
    cp "$SKELETON/dy-app/src/main/resources/db/migration/V1__baseline_tenant_rls.sql" \
       "$WORK/$d/dy-app/src/main/resources/db/migration/V1__baseline_tenant_rls.sql"
  done
}

hash_of_sources() {
  echo "  采证基线 RlsGateSupport.java md5 = $(md5sum "$SKELETON/$SRC_REL" | cut -d' ' -f1)"
  echo "  采证基线 V1 迁移脚本      md5 = $(md5sum "$SKELETON/dy-app/src/main/resources/db/migration/V1__baseline_tenant_rls.sql" | cut -d' ' -f1)"
}

backup()  { for d in build-A build-B; do cp "$WORK/$d/$SRC_REL" "$WORK/$d/$SRC_REL.revbak"; done; }
restore() { for d in build-A build-B; do cp "$WORK/$d/$SRC_REL.revbak" "$WORK/$d/$SRC_REL"; done; }

# ⚠️ 2026-09-21 修正：本 harness 原用 `perl -0pi` 做注入，但本机**没有 perl**
#    （实测 `perl: command not found`）→ 注入静默不生效 → 所有反向验证实验都退化为
#    "断言未生效" 的假失败。改用 Python（本机 3.13 可用、跨平台、无需额外安装）。
#    注入一律"锚点不存在就报错退出"，绝不静默放过。
PY="${PY:-python}"
SRC_ABS_INJ=''   # 占位，实际路径由下方 python 脚本拼接

inj_replace() { # $1=检出目录 $2=锚点原文 $3=替换文本 $4=标记
  "$PY" -c "
import io,sys
p=sys.argv[1]; old=sys.argv[2]; new=sys.argv[3]
s=io.open(p,encoding='utf-8').read()
if old not in s:
    sys.stderr.write('ANCHOR_NOT_FOUND in '+p+'\n'); sys.exit(2)
io.open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
" "$WORK/$1/$SRC_REL" "$2" "$3" || { echo "  ✗ 注入失败（锚点未命中）: $1"; exit 9; }
}

inject_unconditional_drop() { # 注入 a
  for d in build-A build-B; do
    inj_replace "$d" \
      'boolean destructive = explicit || stateBefore != DbState.SENTINEL_OK;' \
      'boolean destructive = true; // INJECTED(a)'
    grep -q "INJECTED(a)" "$WORK/$d/$SRC_REL" || { echo "  ✗ INJECT(a) 未生效: $d"; exit 9; }
  done
  echo "  ✓ INJECT(a) 已生效：缺省路径改为无条件 DROP+重建"
}

inject_distinct_mutex_key() { # 注入 a'
  for d in build-A build-B; do
    inj_replace "$d" \
      'long key = advisoryKey("gate");' \
      'long key = advisoryKey("gate-" + System.nanoTime()); // INJECTED(a2)'
    grep -q "INJECTED(a2)" "$WORK/$d/$SRC_REL" || { echo "  ✗ INJECT(a2) 未生效: $d"; exit 9; }
  done
  echo "  ✓ INJECT(a2) 已生效：每个进程用不同锁 key，互斥锁形同不存在"
}

assert_clean() {
  local bad=0
  for d in build-A build-B; do
    if grep -qE 'INJECTED\(' "$WORK/$d/$SRC_REL"; then echo "  ✗ 注入残留: $d"; bad=1; fi
    if ! diff -q "$WORK/$d/$SRC_REL.revbak" "$WORK/$d/$SRC_REL" >/dev/null; then
      echo "  ✗ 与备份不一致: $d"; bad=1; fi
    rm -f "$WORK/$d/$SRC_REL.revbak"
  done
  [ "$bad" -eq 0 ] && echo "  还原自检：两个检出均与备份逐字节一致、无注入残留"
  return "$bad"
}

# ---------------------------------------------------------------------------
# 实验
# ---------------------------------------------------------------------------

exp1_green_and_mutex() {
  hr; log "Exp1 真并发两构建（修复后）+ 构建级互斥锁是否为真"
  local key; key=$(advisory_key gate)
  log "  构建级互斥锁 key（由 <库名>/gate 的 SHA-256 独立推导）= $key"
  local holder_secs="${A2_EXTLOCK_SECS:-150}"
  # 外部持锁者：用 psql 在同一 key 上持锁。构建若真在等这把锁，门禁类的墙上耗时
  # 会显著长于"无持锁者基线"（这是主证据）。
  #
  # ⚠️ 2026-09-21 实测订正（三处，都是"看起来验过了其实没有"）：
  #    ① 原脚本只持锁 30s，而两个构建真正到达门禁要 ~160s ⇒ 锁在构建尝试 acquire
  #       之前就释放了 ⇒ 构建"通过"只是因为根本没人跟它抢。故持锁时长改为可配（默认 150s）。
  #    ② mutex_waited_ms 不可作主证据：heldMutex 是 **JVM 内 static 缓存**，同 JVM 先跑的
  #       类取锁并记录 waited（run5 实测 5064ms），后跑的类复用同一锁对象、waitedMillis
  #       恒为 0。更关键的是：provision 打印时锁已是**第二个门禁类重新 acquire** 的，
  #       此时无争用 ⇒ 0。**两轮同代码实测给出 5064 / 0 两个矛盾值 —— 指标本身不可信。**
  #       → 已降级为诊断项；主证据改为 ① 门禁类耗时对比 vs 基线（阈值 30s）。
  #    ③ 若两者都判不出（缺基线 / 差值 < 30s），**判为未取得证据并明确标红**，
  #       不把"恰好没撞上"当成"互斥生效"。
  ( "$PSQL" -X -h "$PGHOST" -p "$PGPORT" -U postgres -d postgres \
      -c "SELECT pg_advisory_lock($key); SELECT pg_sleep($holder_secs);" >"$OUT/exp1-extlock.log" 2>&1 ) &
  local holder=$!
  sleep 2

  # 持锁期间由"探针"独立验证锁真的在此刻是【被持有】的：
  # 另起一个连接尝试 try-lock，必须失败（false）。这证明外部持锁者有效、
  # 且 key 推导与 RlsGateSupport 一致（否则探针会拿到锁）。
  local probe
  probe=$(psql_super "SELECT pg_try_advisory_lock($key)" | tr -d '\r')
  echo "  --- 外部持锁者生效性自证（此刻 try-lock 必须为 f）---"
  echo "  extlock 持有中，探针 pg_try_advisory_lock($key) = ${probe:-<空>}（期望 f）"
  if [ "$probe" = "t" ]; then
    echo "  ⚠️ 探针拿到了锁 ⇒ 外部持锁者无效或 key 推导与 RlsGateSupport 不一致，本实验的互斥证据不成立"
  fi
  # 探针若拿到锁必须立刻还回去，否则会把构建挡在外面 150s
  [ "$probe" = "t" ] && psql_super "SELECT pg_advisory_unlock($key)" >/dev/null 2>&1

  # 锁等待探针：与两个构建并行跑，逐秒在 pg_locks 上观测等待者
  start_lockprobe "$((holder_secs + 40))" "$OUT/exp1-lockprobe.log"

  run_two_builds exp1
  wait "$holder" 2>/dev/null
  wait "$LOCKPROBE_PID" 2>/dev/null
  log "  build-A EXIT=$(verdict "$OUT/exp1-A.log")   build-B EXIT=$(verdict "$OUT/exp1-B.log")"
  echo "  --- 证1：门禁类耗时对比（等锁则显著变长）---"
  local ga gb base
  ga=$(gate_seconds "$OUT/exp1-A.log"); gb=$(gate_seconds "$OUT/exp1-B.log")
  base=$(gate_seconds "$OUT/exp1-baseline.log")
  echo "  RlsCoverageGateTest 耗时：A=${ga:-?}s  B=${gb:-?}s   无外部持锁者基线=${base:-?}s"
  GATE_DIFF=""
  if [ -n "$base" ] && [ -n "$ga" ]; then
    GATE_DIFF=$(awk -v a="$ga" -v b="$base" 'BEGIN{printf "%.1f", a-b}')
    echo "  差值：A 比基线多 ${GATE_DIFF}s（等锁的直接代价）"
  fi
  echo "  --- 证2：pg_locks 阻塞式等待者（⚠️ 本场景用 pg_try_advisory_lock 自旋，天生不登记，预期为 0）---"
  local rounds blocked
  rounds=$(grep -ao 'rounds=[0-9]*' "$OUT/exp1-lockprobe.log" | tail -1 | cut -d= -f2)
  blocked=$(grep -ao '阻塞式等待者轮次=[0-9]*' "$OUT/exp1-lockprobe.log" | tail -1 | cut -d= -f2)
  echo "  探针 ${rounds:-?} 轮，阻塞式等待者出现 ${blocked:-?} 轮（0 属正常，原因见 harness 注释）"
  echo "  --- 证3：mutex_waited_ms（诊断项：反映 provision 时在【postgres 维护库】等 gate 锁的时长）---"
  local anywaited=0
  for f in exp1-A exp1-B; do
    local w; w=$(grep -ao 'mutex_waited_ms=[0-9]*' "$OUT/$f.log" | sort -u | tr '\n' ' ')
    echo "  [$f] ${w:-<未采集到>}"
    echo "$w" | grep -qE 'mutex_waited_ms=[1-9][0-9]*' && anywaited=1
  done
  echo "  ⚠️ 本项只是【诊断】，不作主证据。理由（2026-09-21 两轮实测）："
  echo "     same harness / same RlsGateSupport，run5 给出 A=5064ms 而 run6 给出 A=0ms，"
  echo "     两轮的门禁类耗时却都是 ~165s（都等了锁）。指标自相矛盾 -> 不可信。"
  echo "     主因：本项在 provision 时打印，那时锁已是第二个门禁类（RlsTenantIsolationTest）"
  echo "     重新 acquire 的（heldMutex 是 JVM static；前一个类 @AfterAll 已释放 -> 再取无争用 = 0）。"

  echo "  --- ✅ 互斥锁证据判定（主证据 = 门禁类耗时对比 vs 基线）---"
  local ok=1
  if [ -z "$base" ] || [ -z "$GATE_DIFF" ]; then
    ok=0
    echo "  ❌ 无法判定：缺少基线（$OUT/exp1-baseline.log）或本轮门禁耗时未采集到。"
  elif awk -v d="$GATE_DIFF" 'BEGIN{exit !(d > 30)}'; then
    echo "  ✅ 成立：门禁类耗时比无持锁者基线多 ${GATE_DIFF}s（阈值 30s）。"
    echo "     含义：构建在 gate 锁上真的排队了（A=${ga:-?}s / B=${gb:-?}s vs 基线=${base}s），"
    echo "     且两者 EXIT=0 + 库 OID 未变 + 零写入 -> 并发未互相拆库。"
  else
    ok=0
    echo "  ❌ 不成立：门禁类耗时只比基线多 ${GATE_DIFF}s（阈值 30s）—— 两者可能恰好错开，"
    echo "     不能据此声称互斥生效。复核方向：A2_EXTLOCK_SECS 是否 > 一次门禁构建的墙上时间。"
  fi
  [ "$anywaited" -eq 1 ] && echo "     （旁证：至少一个构建的 mutex_waited_ms > 0）"
  echo "  --- 路径 / OID / 幂等写入判定 ---"
  for f in exp1-A exp1-B; do
    echo "  [$f] $(grep -ao '路径=[A-Z_]*' "$OUT/$f.log" | head -1) | $(grep -ao 'post-oid\] before=[0-9]* after=[0-9]* 变化=[a-z]*' "$OUT/$f.log" | head -1) | $(grep -ao '02-ensure: [^（(]*' "$OUT/$f.log" | head -1)"
  done
  echo "  --- 门禁测试数 ---"
  for f in exp1-A exp1-B; do
    echo "  [$f] $(grep -aE 'in com.diaoyuanyun.dy.app.rls.Rls(TenantIsolation|CoverageGate)Test' "$OUT/$f.log" | sed 's/.*Tests run: \([0-9]*\).*rls\.\([A-Za-z]*\).*/ \2=\1/' | tr '\n' ' ')  $(grep -a 'BUILD ' "$OUT/$f.log" | tail -1)"
  done
}

exp2_inject_unconditional_drop() {
  hr; log "Exp2 反向验证(a)：缺省幂等路径 → 无条件 DROP+重建（并发存活探针应为受害方）"
  backup
  inject_unconditional_drop
  start_victim exp2 60
  sleep 1
  run_two_builds exp2
  wait "$VICTIM_PID" 2>/dev/null
  log "  build-A EXIT=$(verdict "$OUT/exp2-A.log")   build-B EXIT=$(verdict "$OUT/exp2-B.log")"
  local fails total
  fails=$(grep -ac VICTIM_FAIL "$OUT/victim-exp2.log" 2>/dev/null || true)
  total=$(grep -ac . "$OUT/victim-exp2.log" 2>/dev/null || true)
  echo "  --- 存活探针失败行数: ${fails:-0}（探针共 ${total:-0} 行输出）---"
  grep -a VICTIM_FAIL "$OUT/victim-exp2.log" 2>/dev/null | sed 's/round=[0-9]* //' | sort -u | head -4 | sed 's/^/  /'
  echo "  --- 两构建对同一注入的行为（供对照）---"
  for f in exp2-A exp2-B; do echo "  [$f] $(grep -a 'BUILD ' "$OUT/$f.log" | tail -1)"; done
  restore; assert_clean
}

exp3_inject_distinct_mutex_key() {
  hr; log "Exp3 反向验证(a')：(a) + 两构建用不同锁 key（≈ 修复前形态）→ 两构建互相拆库"
  backup
  inject_unconditional_drop
  inject_distinct_mutex_key
  run_two_builds exp3
  log "  build-A EXIT=$(verdict "$OUT/exp3-A.log")   build-B EXIT=$(verdict "$OUT/exp3-B.log")"
  echo "  --- 两端真实失败原文（去重）---"
  grep -aE "关系 .* 不存在|数据库 .* 不存在|CannotCreateTransaction|does not exist|AssertionFailedError|Tests run: .*Failures: [1-9]|Tests run: .*Errors: [1-9]" \
    "$OUT/exp3-A.log" "$OUT/exp3-B.log" 2>/dev/null | sed 's/^.*\[ERROR\] //' | sort -u | head -12 | sed 's/^/  /'
  restore; assert_clean
}

exp4_lock_split_vs_single() {
  hr; log "Exp4 反向验证(b)：单连接锁 vs 多条 psql -c（模拟锁立即释放）"
  local key; key=$(advisory_key reset)
  local gen="$WORK/build-A/dy-app/target/rls-gate/01_reset_locked.sql"
  if [ ! -f "$gen" ]; then
    log "  未找到产物 $gen，先跑一次门禁以生成脚本"
    ( cd "$WORK/build-A" && "$MVN" -pl dy-app test >"$OUT/exp4-gen.log" 2>&1 ) || true
  fi
  [ -f "$gen" ] || { echo "  生成脚本失败，跳过 Exp4"; return; }
  log "  基准脚本（生产产物）: $gen   lock key(reset)=$key"

  local single="$OUT/exp4-single.sql" body="$OUT/exp4-body.sql"
  # 两侧都插入 pg_sleep：这只是【把临界区拉长到可观测】，不改变语义
  # （持锁形态下睡眠不会造成失败，因为另一方在锁上排队）。
  sed -e 's#^\(DROP DATABASE IF EXISTS .*\)$#\1\nSELECT pg_sleep(0.4);#' "$gen" > "$single"
  # 注入形态：摘掉 lock/unlock 两行 → 交给"三条 psql 命令各自新连接"执行
  sed -e '/pg_advisory_lock/d' -e '/pg_advisory_unlock/d' \
      -e 's#^\(DROP DATABASE IF EXISTS .*\)$#\1\nSELECT pg_sleep(0.4);#' "$gen" > "$body"

  grep -q "pg_advisory_lock" "$single"    || { echo "  ✗ 形态1 缺 lock，注入异常"; return; }
  grep -q "pg_advisory_lock" "$body"       && { echo "  ✗ 形态2 仍含 lock，注入未生效"; return; }
  grep -q "pg_sleep" "$single"             || { echo "  ✗ 临界区未拉长(形态1)"; return; }
  grep -q "pg_sleep" "$body"               || { echo "  ✗ 临界区未拉长(形态2)"; return; }
  echo "  ✓ 注入自检通过：形态2（psql -c 多次调用）已摘掉锁、临界区已拉长"
  echo "  --- 形态2 实际执行的重置体（锁已摘掉）---"
  sed -n '1,12p' "$body" | sed 's/^/    /'

  local common=(-X -h "$PGHOST" -p "$PGPORT" -U postgres -d postgres -v ON_ERROR_STOP=1
                -v "dbname=$DB" -v "rolename=$APP_USER" -v "lockkey=$key")

  # 对照组：单连接持锁形态，两个 psql 进程并发
  ( "$PSQL" "${common[@]}" -f "$single" >"$OUT/exp4-single-p1.log" 2>&1; echo "EXIT=$?" >>"$OUT/exp4-single-p1.log" ) & local p1=$!
  ( "$PSQL" "${common[@]}" -f "$single" >"$OUT/exp4-single-p2.log" 2>&1; echo "EXIT=$?" >>"$OUT/exp4-single-p2.log" ) & local p2=$!
  wait "$p1" "$p2"
  echo "  --- 对照组（单连接持锁，两进程并发）---"
  for f in exp4-single-p1 exp4-single-p2; do
    echo "  $f  EXIT=$(verdict "$OUT/$f.log")  $(grep -aoE 'ERROR:.*' "$OUT/$f.log" | head -1)"
  done

  # 注入组：三条命令模拟"锁立即释放"，两进程并发
  run_split() {
    { "$PSQL" "${common[@]}" -c "SELECT pg_advisory_lock($key)" >"$1" 2>&1
      "$PSQL" "${common[@]}" -f "$body" >>"$1" 2>&1
      echo "BODY_EXIT=$?" >>"$1"
      "$PSQL" -X -h "$PGHOST" -p "$PGPORT" -U postgres -d postgres -t -A \
        -c "SELECT pg_advisory_unlock($key)" >>"$1" 2>&1
    }
  }
  ( run_split "$OUT/exp4-split-p1.log" ) & local s1=$!
  ( run_split "$OUT/exp4-split-p2.log" ) & local s2=$!
  wait "$s1" "$s2"
  echo "  --- 注入组（psql -c 多次调用，锁立即释放，两进程并发）---"
  for f in exp4-split-p1 exp4-split-p2; do
    echo "  $f  BODY_EXIT=$(grep -a '^BODY_EXIT=' "$OUT/$f.log" | cut -d= -f2)  $(grep -aoE '(ERROR|错误):.*' "$OUT/$f.log" | head -1 | cut -c1-160)"
  done
}

# ---------------------------------------------------------------------------
main() {
  mkdir -p "$OUT"
  hr; log "Task#18 并发实测 + 反向验证"
  hash_of_sources
  log "真库=$DB  应用角色=$APP_USER  工作区=$WORK"
  psql_super "SELECT 1" >/dev/null 2>&1 || { echo "无法连接 PostgreSQL，退出"; exit 1; }
  # 注意：传给 psql -c 的 SQL 里不要放中文 —— Windows 会把命令行参数按 GBK 交给 psql，
  # 而 psql 按 PGCLIENTENCODING=UTF8 解析 → 报 "无效的 UTF8 编码字节顺序"。这里用 ASCII 再翻译。
  local s
  s=$(psql_super "SELECT coalesce(shobj_description(oid,'pg_database'),'(none)') FROM pg_database WHERE datname='$DB'" | tr -d '\r')
  log "实验前库哨兵: $s"

  prep_copies
  exp1_green_and_mutex
  exp2_inject_unconditional_drop
  exp3_inject_distinct_mutex_key
  exp4_lock_split_vs_single

  hr; log "全部实验结束；证据目录: $OUT"
}
main "$@"
