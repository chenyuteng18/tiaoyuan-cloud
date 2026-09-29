#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""
A-3 · 反向验证（Reversal Validation）—— 证明【手环历史补拉通路】的门禁有牙齿。

## 本脚本要证明什么

Task #118 交付了 `BandRefetchGateTest`（12 条判据，真库端到端，连跑两次全绿）。
但"现在绿"本身不构成证据 —— 一个恒绿的测试与一个有牙齿的测试，
在"把代码改对、看它绿不绿"这件事上给出的信心**完全相同**。

⇒ 故本脚本对 A-3 的每一处关键防线做一次
【注入 → 跑门禁 → 断言必须红在【预期的那一条】→ 逐字节还原】。

🛑 判定式是 `caught = (exit != 0) and (expect in got)` ——
`exit != 0` 单独**不足以**证明牙齿：编译错误、环境故障、真库连不上
都会让 exit != 0，而它们证明的是"构建坏了"，不是"断言抓住了缺陷"。
必须同时要求**期望的方法名出现在 surefire 输出里**。

## 验收口径（A-3 判据③）

**反向验证 ≥20 用例全绿**（先例 119=22 / 120=28 / 121=45 / 122=49）。
本脚本当前 **23 组注入 + 复绿 + 逐字节还原 = 25 条**。

## 注入面分三层，其中一层是【活库函数体】

A-3 的防线不在一个地方：

| 层 | 载体 | 注入形态 |
|---|---|---|
| 应用层 | `DailyCoverageRecord` 构造器 | 改 Java 源码 |
| 服务层 | `BandRefetchService` 的审计 action / `target_id` | 改 Java 源码 |
| 仓储层 | `BandRefetchLedger` 的读侧上下文 / 坏数据扫描 SQL | 改 Java 源码 |
| **库层** | **V22 的两个函数体**（`register_sync_probe` / `register_daily_coverage`） | **🛑 必须改【活库里的函数】而不是迁移文件** |

🛑 **为什么库层不能改迁移文件**（118 实测付费记录过）：
`V22__band_refetch_provisioning.sql` 已被 Flyway 应用并登记了 checksum。
改它 ⇒ 启动期 Flyway 校验失败（实测 **13 errors**），
于是**整个 Spring 上下文起不来** ⇒ 门禁跑不了 ⇒ exit != 0，
但红的原因是"迁移校验失败"而**不是**"门禁抓住了库层门禁缺失"。
⇒ 正确做法：把 V22 里那一段抽出来，在**活库**上 `CREATE OR REPLACE FUNCTION`
（不改文件、不动 Flyway 登记），跑完再用原样的函数段 `CREATE OR REPLACE` 还原。

🛑 **活库注入的安全网必须比文件注入更厚**（本脚本独有的一层）：
文件注入最坏情况是"源文件停在注入态"（`git checkout` 可救）；
活库注入最坏情况是"**运行库的函数体停在注入态**"——
那会让**后续每一次构建都跑在一个被改过的库上**，且没有任何红点提示。
⇒ 故本脚本：① 每组活库注入前**核对活库函数与 V22 源码逐字等价**（基线自证）；
② 每组跑完**立即**还原活库并**读回 prosrc 断言**；
③ `--restore-only` 同时还原文件与活库（两个函数都还原）。

🛑 **本脚本的活库注入是【双函数】的**（118 只动 `register_daily_coverage`）：
`register_sync_probe` 的幂等形态（`DO NOTHING`）、上下文建立（`set_config`）、
三段判定（`v_affected = 1`）同样是 A-3 的门禁，必须逐组证明其有牙齿。

## 注入清单（23 组）

### 第一类：**判据语义**组（守"某处被改坏 ⇒ 必须响亮地红"）

| 组 | 注入 | 期望变红 |
|---|---|---|
| C1 | 服务层审计 `target_id` 从 `storedCoverageId` 回退成 `record.coverageId()` | ③ |
| C4 | 活库 cov 段 (5c) 核心门禁 → `IF FALSE THEN` | ⑤ |
| C5 | 活库 cov 段删掉归属 WHERE 的 `AND c.customer_id = p_customer_id` | ⑪ |
| C10 | 活库 probe 段 `ON CONFLICT (probe_id) DO NOTHING` → `DO UPDATE`（证据被抹） | ② |
| C11 | 活库 cov 段 `ON CONFLICT (device_id, date) DO UPDATE` → `DO NOTHING`（不再刷新） | ③ |
| C14 | 活库 cov 段 (5d) → `IF FALSE THEN`（flag × gap_reason 互斥失效） | ⑥ |
| C15 | 活库 cov 段 SET 列表丢掉 `n_at_that_time`（N 不再随探测刷新） | ③ |
| C22 | 活库 cov 段 (5b) 允许集收窄（`ARRAY[0,1,-1,255]` → `ARRAY[0,1,-1]`）⇒ 合法的技术性缺失被误拒 | ⑤ |

### 第二类：**判别力**组（守"不能一行改坏就把判据整条跳过"）

| 组 | 注入 | 期望变红 |
|---|---|---|
| C2 | 仓储读侧 `SET LOCAL` 删掉 ⇒ 恒返回 0 行（假绿的经典形态） | ① |
| C3 | 应用层核心门禁 `checkNotWornVersusTechnicalWear` 被摘掉 | ⑤ |
| C8 | 活库 cov 段词表收窄 `v_tech_iswear`（`255` 不再是技术性） | ⑤ |
| C9 | 活库 cov 段归因分支 `IF v_was_insert IS NULL` → `IF FALSE`（冲突不再被报告） | ⑪ |
| C12 | 活库 probe 段 `set_config` 摘掉（写侧无上下文） | ① |
| C13 | 活库 probe 段三段判定 `IF v_affected = 1` → `IF FALSE`（首次登记不再 CREATED） | ① |
| C16 | 活库 cov 段上下文一致性守卫 → `IF FALSE` | ⑧ |
| C16b | 活库 probe 段上下文一致性守卫 → `IF FALSE` | ⑧ |
| C20 | 应用层 `checkFlagVersusGapReason` 被摘掉 | ⑥ |
| C21 | 仓储坏数据扫描的过滤从 `not_worn` 改成别的（发现入口失去靶心） | ⑫ |

### 第三类：**门禁自身的实现 / 审计载荷**组

| 组 | 注入 | 期望变红 |
|---|---|---|
| C6 | 门禁测试 `checkInSetOf` 窗口右界从"配对右括号"放宽到"整份文件" | ⑩ |
| C7 | 门禁测试 `checkIntSetFromCatalog` 短路掉规范化形态 | ⑩ |
| C17 | 门禁测试 `assertAudit` 的 `target_id` 断言被削弱（第 47 条缺陷的守门） | ① |
| C18 | 服务层覆盖 action 恒取 `REFRESHED`（首次也算刷新） | ③ |
| C19 | 服务层探针 action 恒取 `REPLAYED`（首次也算重放） | ① |

🛑 **C6/C7/C17 是本文件特有的"元层"注入**：它们注入的不是生产代码，
而是**门禁自己的解析工具 / 审计断言**。理由与 124 的 C2/C3 相同 ——
"必须核到某件东西"型判据天然会退化成恒绿，故必须逐组证明它**真的会红**。

🛑 **多条注入期望同一条断言时必须显式允许、不合并**（124 固化的纪律）：
C5 与 C9 → ⑪（决策语句 vs 归因分支，两个不同环节）；
C4 / C8 / C22 / C3 → ⑤（库层门禁语句 / 词表 / 允许集 / 应用层，四个不同环节）；
C16 与 C16b → ⑧（覆盖侧 vs 探针侧）；
C1 / C11 / C15 / C18 → ③（审计指向 / 刷新形态 / 刷新内容 / action 分派）。

## 🛑 第一版 C6 的教训（保留在案，别把"注入不生效"读成"判据没牙"）

C6 初版注入的是 `checkInSetOf` 的 `int open = m.end() - 1;` → `m.end()`，
**实测未被抓住（exit=0）**。排查结论：**不是判据没牙，是该注入【等价于原样】** ——
V5 里 `IN (` 之后紧跟的是**换行 + 缩进**，
故 `substring(open+1, …)` 与 `substring(open+2, …)` 抽出的字符串字面量**完全相同**
（前导空白不参与 `stringLiteralsIn` 的配对）。

⇒ 改为**把窗口的右界从"配对的右括号"放宽到"整份文件"**
（`int end = sql.indexOf(')', open);` → `int end = sql.length();`）——
这才是"抽取窗口取错"这一缺陷的真实攻击面：窗口一宽，
`stringLiteralsIn` 会把该文件其余部分的字面量一并收进来 ⇒ 核对静默失真。

⇒ **通用规则（本仓既已固化，此处再证一次）**：注入必须先证明"它改变了被测对象的行为"。
   "锚点预检 + 基线零出现"只证明**锚点有判别力**，
   **不**证明**注入有判别力** —— 后者只能靠"注入后确有一个可观测差异"来证。

## 纪律（与 100~124 同口径）

- 锚点预检（存在 + 唯一）· 基线全绿 + **待查方法名在基线中零出现**（元层判别力自证）
- 逐字节还原 + 还原后复绿
- 🛑 **锚点优先用单行**（技能 8.7）
- 🛑 注入必须保持源文件 / SQL **语法合法且可编译/可 CREATE**
- 🛑 `caught = (exit != 0) and (expect in got)`
- 🛑 **install 只做一次**（124 的教训）：原版每组都 install ⇒ N × ~100s ⇒
  外层命令超时把进程 SIGTERM 腰斩，且被杀时源文件停在注入态。
- 🛑 **磁盘兜底快照 + 污染态自检**：快照落盘 `_work/125_snapshots/`；
  启动时若检测到源文件已处于注入态则拒绝覆盖快照并中止；`--restore-only` 事后还原。
- 🛑 **活库注入必须在基线时自证"活库函数 == V22 源码函数"（两个函数都要）**

用法：
    python.exe verification/125_band_refetch_reverse_verification.py
    python.exe verification/125_band_refetch_reverse_verification.py --restore-only
"""
from __future__ import annotations

import atexit
import os
import signal
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKEL = HERE.parent

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"
PSQL = r"C:\Program Files\PostgreSQL\17\bin\psql.exe"

PG_HOST, PG_PORT, PG_USER, PG_DB = "127.0.0.1", "5432", "diaoyuanyun", "diaoyuanyun_dev"

GATE_CLASS = "BandRefetchGateTest"

F_SVC = "dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/service/BandRefetchService.java"
F_LED = "dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/repository/BandRefetchLedger.java"
F_REC = "dy-app/src/main/java/com/diaoyuanyun/dy/app/bandrefetch/domain/DailyCoverageRecord.java"
F_TST = "dy-app/src/test/java/com/diaoyuanyun/dy/app/bandrefetch/BandRefetchGateTest.java"
F_V22 = "dy-app/src/main/resources/db/migration/V22__band_refetch_provisioning.sql"

# ---- 待查方法名（判据的 surefire 方法名）----
M_FULL_PROBE = "a_full_probe_call_really_lands_a_readable_record"
M_PROBE_TWO = "the_probe_two_states_are_complete_and_replay_never_rewrites_the_record"
M_COV_TWO = "the_coverage_two_states_are_complete_and_the_second_state_really_refreshes"
M_GATE_NOTWORN = "not_worn_must_never_coexist_with_a_technical_wear_state"
M_FLAG = "a_true_flag_must_not_coexist_with_a_gap_reason"
M_CTX = "an_existing_foreign_tenant_context_blocks_both_paths"
M_VOCAB = "the_path_is_a_component_and_all_four_vocabularies_agree"
M_OWNER = "a_coverage_row_owner_customer_must_not_be_rewritten"
M_NOENTRY = "the_path_deliberately_provides_no_rewrite_or_delete_entry"
METHODS = [M_FULL_PROBE, M_PROBE_TWO, M_COV_TWO, M_GATE_NOTWORN,
           M_FLAG, M_CTX, M_VOCAB, M_OWNER, M_NOENTRY]

# ==================================================================
# 一、文件注入锚点（全部实测唯一）
# ==================================================================

ANCHOR_C1 = "                    storedCoverageId.toString(),"
REPL_C1 = ("                    // 注入 C1：回退成本次传入的 id —— 覆盖态下它与库里那一行不同\n"
           "                    record.coverageId().toString(),")

ANCHOR_C2 = '            jdbc.execute("SET LOCAL app.tenant_id = \'" + tenantId + "\'");'
REPL_C2 = ('            // 注入 C2：读侧不再建立租户上下文（FORCE RLS ⇒ 恒返回 0 行）\n'
           '            jdbc.execute("SELECT 1");')

ANCHOR_C3 = "        checkNotWornVersusTechnicalWear(gapReason, isWear);"
REPL_C3 = "        // 注入 C3：应用层核心门禁被摘掉（只剩库层那一道）"

ANCHOR_C6 = "        int end = sql.indexOf(')', open);"
REPL_C6 = "        int end = sql.length();   // 注入 C6：窗口右界放宽到整份文件"

ANCHOR_C7 = "        if (at >= 0) {"
REPL_C7 = "        if (false) {   // 注入 C7：规范化形态被短路，退回源码形态 `IN (`"

ANCHOR_C17 = '        assertEquals(targetId, row.get("target_id"), "审计 target_id 必须正确");'
REPL_C17 = ('        assertEquals("injected-wrong-target-id", row.get("target_id"),'
            '   // 注入 C17：target_id 断言被削弱\n                "审计 target_id 必须正确");')

ANCHOR_C18 = "                    outcome.firstTime() ? ACTION_COVERAGE_REGISTERED : ACTION_COVERAGE_REFRESHED,"
REPL_C18 = ("                    // 注入 C18：恒取 REFRESHED（首次覆盖也被记成刷新）\n"
            "                    ACTION_COVERAGE_REFRESHED,")

ANCHOR_C19 = "                    outcome.mutatedData() ? ACTION_PROBE_REGISTERED : ACTION_PROBE_REPLAYED,"
REPL_C19 = ("                    // 注入 C19：恒取 REPLAYED（首次探针也被记成重放）\n"
            "                    ACTION_PROBE_REPLAYED,")

ANCHOR_C20 = "        checkFlagVersusGapReason(coverageFlag, gapReason);"
REPL_C20 = "        // 注入 C20：应用层 flag × gap_reason 互斥门禁被摘掉"

ANCHOR_C21 = '                        + " WHERE gap_reason = \'not_worn\' AND is_wear IN (-1, 255)",'
REPL_C21 = ('                        + " WHERE gap_reason = \'sync_failed\' AND is_wear IN (-1, 255)",'
            '   // 注入 C21：坏数据扫描失去靶心')

# ==================================================================
# 二、活库（V22 两个函数体）注入锚点
# ==================================================================
# 🛑 全部锚点都在**所属函数段内**核对唯一性，而不是在整份 V22 里 ——
#    因为 `ON CONFLICT` / `set_config` / `v_ctx_before` 守卫在两个段里各有一份。

# ---- cov 段 ----
LIVE_GATE_OLD = "    IF p_gap_reason = 'not_worn' AND p_is_wear = ANY (v_tech_iswear) THEN"
LIVE_GATE_NEW = "    IF FALSE THEN   -- 注入 C4：库层 (5c) 核心门禁失效"

LIVE_OWNER_OLD = "       AND c.customer_id = p_customer_id\n"
LIVE_OWNER_NEW = ""   # 整行删除

LIVE_VOCAB_OLD = "    v_tech_iswear  int[]  := ARRAY[-1, 255];"
LIVE_VOCAB_NEW = "    v_tech_iswear  int[]  := ARRAY[-1];   -- 注入 C8：词表砍掉 255"

LIVE_REPORT_OLD = "    IF v_was_insert IS NULL THEN"
LIVE_REPORT_NEW = "    IF FALSE THEN   -- 注入 C9：归属冲突不再被报告"

# 🛑 C11 的注入形态改过一次，理由必须逐字记下（避免下次又踩）：
#   初版把 `DO UPDATE` 直接改成 `DO NOTHING`。**那是语法错的** ——
#   紧随其后的 `SET coverage_flag = …` 子句会悬空（`DO NOTHING` 不接受 SET），
#   于是 `CREATE OR REPLACE FUNCTION` 直接失败 ⇒ exit != 0 但红的是"函数没建起来"，
#   而不是"门禁抓住了不刷新" —— 一次典型的**归因错误**的红。
#   ⇒ 改为把 upsert 的 **WHERE 子句整体恒假**（覆盖两行）：
#     `DO UPDATE … WHERE FALSE` 语法合法、CREATE 成功，但 DO UPDATE 路径永不执行 ⇒
#     RETURNING 拿不到行 ⇒ v_was_insert = NULL ⇒ 走归因分支 ⇒ 函数 RAISE。
#     这才是"当日观测事实不再被刷新"这一缺陷的真实攻击面。
LIVE_COV_WHERE_OLD = ("     WHERE c.tenant_id = p_tenant_id\n"
                      "       AND c.customer_id = p_customer_id")
LIVE_COV_WHERE_NEW = "     WHERE FALSE   -- 注入 C11：刷新被短路（当日观测事实不再更新）"

LIVE_FLAG_OLD = "    IF p_coverage_flag IS TRUE AND p_gap_reason IS NOT NULL THEN"
LIVE_FLAG_NEW = "    IF FALSE THEN   -- 注入 C14：flag × gap_reason 互斥失效"

LIVE_NSET_OLD = "           n_at_that_time         = excluded.n_at_that_time,"
LIVE_NSET_NEW = "           /* 注入 C15：N 不再随探测刷新 */"

LIVE_COV_CTX_OLD = ("    IF v_ctx_before IS NOT NULL\n"
                    "       AND btrim(v_ctx_before) <> ''\n"
                    "       AND v_ctx_before <> p_tenant_id::text THEN")
LIVE_COV_CTX_NEW = "    IF FALSE THEN   -- 注入 C16：覆盖侧上下文一致性守卫失效"

LIVE_WEAR_ALLOW_OLD = "    IF p_is_wear IS NOT NULL AND NOT (p_is_wear = ANY (ARRAY[0, 1, -1, 255])) THEN"
LIVE_WEAR_ALLOW_NEW = "    IF p_is_wear IS NOT NULL AND NOT (p_is_wear = ANY (ARRAY[0, 1, -1])) THEN   -- 注入 C22"

# ---- probe 段 ----
LIVE_PROBE_UPSERT_OLD = "    ON CONFLICT (probe_id) DO NOTHING;"
LIVE_PROBE_UPSERT_NEW = ("    ON CONFLICT (probe_id) DO UPDATE"
                         "       SET retention_window_days = excluded.retention_window_days;"
                         "   -- 注入 C10：探针证据被重放改写")

LIVE_PROBE_CTXSET_OLD = "    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);"
LIVE_PROBE_CTXSET_NEW = "    PERFORM 1;   -- 注入 C12：写侧不再建立租户上下文"

LIVE_PROBE_AFFECTED_OLD = "    IF v_affected = 1 THEN"
LIVE_PROBE_AFFECTED_NEW = "    IF FALSE THEN   -- 注入 C13：首次登记不再判为 CREATED"

LIVE_PROBE_CTX_OLD = LIVE_COV_CTX_OLD   # 逐字同款（两个函数各一份）
LIVE_PROBE_CTX_NEW = "    IF FALSE THEN   -- 注入 C16b：探针侧上下文一致性守卫失效"

# 🛑 元组形状统一为 `(tag, kind, target, anchor, repl, expect)`：
#    `kind == "file"` 时 `target` = 相对路径；`kind == "live"` 时 `target` ∈ {"cov","probe"}。
INJECTIONS = [
    # ---- 第一类：判据语义 ----
    ("C1  服务层审计 target_id 回退成本次传入的 id（第 47 条缺陷原形）",
     "file", F_SVC, ANCHOR_C1, REPL_C1, M_COV_TWO),
    ("C4  活库 cov 段：V22 (5c) 核心门禁改为 IF FALSE THEN",
     "live", "cov", LIVE_GATE_OLD, LIVE_GATE_NEW, M_GATE_NOTWORN),
    ("C5  活库 cov 段：V22 归属 WHERE 删掉 AND c.customer_id = p_customer_id",
     "live", "cov", LIVE_OWNER_OLD, LIVE_OWNER_NEW, M_OWNER),
    ("C10 活库 probe 段：ON CONFLICT (probe_id) DO NOTHING → DO UPDATE（证据被抹）",
     "live", "probe", LIVE_PROBE_UPSERT_OLD, LIVE_PROBE_UPSERT_NEW, M_PROBE_TWO),
    ("C11 活库 cov 段：upsert 的 WHERE 恒假（当日观测事实不再刷新）",
     "live", "cov", LIVE_COV_WHERE_OLD, LIVE_COV_WHERE_NEW, M_COV_TWO),
    ("C14 活库 cov 段：(5d) flag × gap_reason 互斥 → IF FALSE THEN",
     "live", "cov", LIVE_FLAG_OLD, LIVE_FLAG_NEW, M_FLAG),
    ("C15 活库 cov 段：SET 列表丢掉 n_at_that_time（N 不再随探测刷新）",
     "live", "cov", LIVE_NSET_OLD, LIVE_NSET_NEW, M_COV_TWO),
    ("C22 活库 cov 段：(5b) 允许集收窄（ARRAY[0,1,-1,255] → ARRAY[0,1,-1]）⇒ 技术性缺失被误拒",
     "live", "cov", LIVE_WEAR_ALLOW_OLD, LIVE_WEAR_ALLOW_NEW, M_GATE_NOTWORN),
    # ---- 第二类：判别力 ----
    ("C2  仓储读侧 SET LOCAL 删掉（恒返回 0 行的经典假绿）",
     "file", F_LED, ANCHOR_C2, REPL_C2, M_FULL_PROBE),
    ("C3  应用层核心门禁被摘掉（构造器不再调 checkNotWornVersusTechnicalWear）",
     "file", F_REC, ANCHOR_C3, REPL_C3, M_GATE_NOTWORN),
    ("C8  活库 cov 段：技术性词表收窄（v_tech_iswear ARRAY[-1,255] → ARRAY[-1]，255 漏过）",
     "live", "cov", LIVE_VOCAB_OLD, LIVE_VOCAB_NEW, M_GATE_NOTWORN),
    ("C9  活库 cov 段：归属冲突不再被【报告】（IF v_was_insert IS NULL → IF FALSE）",
     "live", "cov", LIVE_REPORT_OLD, LIVE_REPORT_NEW, M_OWNER),
    ("C12 活库 probe 段：set_config 摘掉（写侧无租户上下文）",
     "live", "probe", LIVE_PROBE_CTXSET_OLD, LIVE_PROBE_CTXSET_NEW, M_FULL_PROBE),
    ("C13 活库 probe 段：三段判定 IF v_affected = 1 → IF FALSE（首次登记不再 CREATED）",
     "live", "probe", LIVE_PROBE_AFFECTED_OLD, LIVE_PROBE_AFFECTED_NEW, M_FULL_PROBE),
    ("C16 活库 cov 段：上下文一致性守卫 → IF FALSE THEN",
     "live", "cov", LIVE_COV_CTX_OLD, LIVE_COV_CTX_NEW, M_CTX),
    ("C16b 活库 probe 段：上下文一致性守卫 → IF FALSE THEN",
     "live", "probe", LIVE_PROBE_CTX_OLD, LIVE_PROBE_CTX_NEW, M_CTX),
    ("C20 应用层 flag × gap_reason 互斥门禁被摘掉（checkFlagVersusGapReason）",
     "file", F_REC, ANCHOR_C20, REPL_C20, M_FLAG),
    ("C21 仓储坏数据扫描失去靶心（gap_reason 过滤从 not_worn 改成 sync_failed）",
     "file", F_LED, ANCHOR_C21, REPL_C21, M_NOENTRY),
    # ---- 第三类：门禁自身实现 / 审计载荷 ----
    ("C6  门禁测试：checkInSetOf 窗口右界从配对右括号放宽到整份文件",
     "file", F_TST, ANCHOR_C6, REPL_C6, M_VOCAB),
    ("C7  门禁测试：checkIntSetFromCatalog 短路掉规范化形态",
     "file", F_TST, ANCHOR_C7, REPL_C7, M_VOCAB),
    ("C17 门禁测试：assertAudit 的 target_id 断言被削弱（第 47 条缺陷的守门）",
     "file", F_TST, ANCHOR_C17, REPL_C17, M_FULL_PROBE),
    ("C18 服务层覆盖 action 恒取 REFRESHED（首次覆盖也被记成刷新）",
     "file", F_SVC, ANCHOR_C18, REPL_C18, M_COV_TWO),
    ("C19 服务层探针 action 恒取 REPLAYED（首次探针也被记成重放）",
     "file", F_SVC, ANCHOR_C19, REPL_C19, M_FULL_PROBE),
]

# 函数名映射（活库还原/读回用）
FN_BY_SEG = {"cov": "register_daily_coverage", "probe": "register_sync_probe"}
# 每个段在源文件里的抽取头/尾
SEG_HEAD = {
    "cov": "CREATE OR REPLACE FUNCTION register_daily_coverage(",
    "probe": "CREATE OR REPLACE FUNCTION register_sync_probe(",
}
SEG_TAIL = {"cov": "$v22_cov$;", "probe": "$v22_probe$;"}
# 用于"活库 == 源码"基线自证的稳定锚点（每组注入都不得误伤它们）
BASELINE_ANCHORS = {
    "cov": [LIVE_GATE_OLD, LIVE_OWNER_OLD.rstrip("\n"), LIVE_VOCAB_OLD,
            LIVE_REPORT_OLD.split(" THEN")[0], LIVE_COV_WHERE_OLD,
            LIVE_FLAG_OLD, LIVE_WEAR_ALLOW_OLD, LIVE_COV_CTX_OLD.split("\n")[0]],
    "probe": [LIVE_PROBE_UPSERT_OLD, LIVE_PROBE_CTXSET_OLD,
              LIVE_PROBE_AFFECTED_OLD, LIVE_PROBE_CTX_OLD.split("\n")[0]],
}

_SNAPSHOTS: dict[str, bytes] = {}
_V22_TEXT = ""
_V22_SEG: dict[str, str] = {}     # 段名 → 源码段文本（活库还原的真相源）
_RESTORED = False

SNAP_DIR = SKEL / "_work" / "125_snapshots"
SQL_DIR = SKEL / "_work" / "125_sql"


def log(msg: str) -> None:
    print(msg, flush=True)


def _env():
    env = dict(os.environ)
    env["MAVEN_OPTS"] = "-Dfile.encoding=UTF-8"
    env["JAVA_HOME"] = JAVA_HOME_WIN
    env["PATH"] = JAVA_HOME_WIN + r"\bin;" + PG_BIN_WIN + ";" + env.get("PATH", "")
    env["PGPASSWORD"] = "diaoyuanyun"
    return env


# ==================================================================
# 活库：抽取 / 应用 / 读回 / 还原
# ==================================================================

def extract_segment(v22_text: str, seg: str) -> str:
    """抽出某个函数的整段（`CREATE OR REPLACE FUNCTION … $tag$;`）。

    🛑 只抽这一段（而不是整份 V22）：整份里有 `CREATE TABLE` / `ALTER` / `GRANT`，
    重复执行会报"已存在"而**不是**"函数被替换" —— 那会让注入失败的归因变模糊。
    """
    t = v22_text.replace("\r\n", "\n")
    start = t.index(SEG_HEAD[seg])
    marker = SEG_TAIL[seg]
    end = t.index(marker, start) + len(marker)
    return t[start:end]


def psql_apply(sql_text: str, tag: str) -> tuple[int, str]:
    """把 SQL 文本写成临时文件并用 psql 执行（`ON_ERROR_STOP=1`）。"""
    SQL_DIR.mkdir(parents=True, exist_ok=True)
    p = SQL_DIR / f"{tag}.sql"
    p.write_text(sql_text, encoding="utf-8", newline="")
    r = subprocess.run(
        [PSQL, "-h", PG_HOST, "-p", PG_PORT, "-U", PG_USER, "-d", PG_DB,
         "-v", "ON_ERROR_STOP=1", "-q", "-f", str(p)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env=_env(), shell=False, timeout=120)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def live_prosrc(fn: str) -> str:
    r = subprocess.run(
        [PSQL, "-h", PG_HOST, "-p", PG_PORT, "-U", PG_USER, "-d", PG_DB,
         "-tAc", f"SELECT prosrc FROM pg_proc WHERE proname = '{fn}'"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env=_env(), shell=False, timeout=60)
    return (r.stdout or "").strip()


def restore_live(seg: str) -> bool:
    """把活库的某个函数还原成 V22 源码里的那一份。"""
    if seg not in _V22_SEG:
        return False
    fn = FN_BY_SEG[seg]
    code, out = psql_apply(_V22_SEG[seg], f"restore_{seg}")
    if code != 0:
        log(f"  BAD 活库还原失败（{fn}，exit={code}）：{out[-400:]}")
        return False
    got = live_prosrc(fn)
    need = BASELINE_ANCHORS[seg]
    ok = all(n in got for n in need)
    log(f"  {'OK ' if ok else 'BAD'} 活库已还原 {fn}（关键锚点 {sum(1 for n in need if n in got)}/"
        f"{len(need)} 在）")
    return ok


def live_matches_source() -> bool:
    """基线自证：两个活库函数体都必须与 V22 源码**关键锚点逐字一致**。

    🛑 这条自证**必须在跑任何一组之前做**。若上一次运行被硬杀、活库停在注入态，
    那么"复绿"会拿注入态当基线 ⇒ 整个脚本的结论静默无效。
    """
    for seg, fn in FN_BY_SEG.items():
        got = live_prosrc(fn)
        if not got:
            return False
        if not all(n in got for n in BASELINE_ANCHORS[seg]):
            return False
    return True


# ==================================================================
# 快照 / 还原
# ==================================================================

def _file_rels():
    return sorted({t for _tag, kind, t, _a, _r, _e in INJECTIONS if kind == "file"})


def restore_all(use_disk: bool = False) -> None:
    """还原被注入的文件与活库函数。

    🛑🛑 `use_disk` 的默认值必须是 False —— 这是本脚本**实测付费过一次**的坑：
      初版让 `restore_all` 在"磁盘快照目录存在"时就无条件用它还原。
      于是任何一次 `import`（例如离线预检锚点唯一性）都会在 `atexit` 时
      用**上一次运行的、已经过期的磁盘快照**去覆盖当前源文件 ⇒
      **静默擦掉本次会话里对被测文件做的合法修改**（实测：125 首跑后新补的
      ③-0 断言被 stale 快照整段抹掉，而没有任何红点提示）。
      ⇒ 纪律：磁盘兜底快照**只服务于 `--restore-only`**（人工恢复被硬杀的现场）；
        正常退出只还原**本次进程真正注入过**的文件（`_SNAPSHOTS`）。
    """
    global _RESTORED
    if _RESTORED:
        return
    if _SNAPSHOTS:
        log("\n[还原] 逐字节还原本次注入过的源文件 ...")
        for rel in _file_rels():
            data = _SNAPSHOTS.get(rel)
            if data is None:
                if not use_disk:
                    continue
                disk = SNAP_DIR / Path(rel).name
                if not disk.is_file():
                    log(f"  BAD {rel}  内存与磁盘均无快照，无法还原")
                    continue
                data = disk.read_bytes()
                log(f"  ~~ {rel}  内存快照缺失，改用磁盘兜底快照")
            p = (SKEL / rel).resolve()
            p.write_bytes(data)
            now = p.read_bytes()
            log(f"  {'OK ' if now == data else 'BAD'} {rel}  bytes={len(now)}/{len(data)}")
    elif use_disk and SNAP_DIR.is_dir():
        log("\n[还原] 内存无快照（--restore-only）⇒ 用磁盘兜底快照还原源文件 ...")
        for rel in _file_rels():
            disk = SNAP_DIR / Path(rel).name
            if not disk.is_file():
                log(f"  BAD {rel}  磁盘无兜底快照")
                continue
            data = disk.read_bytes()
            p = (SKEL / rel).resolve()
            p.write_bytes(data)
            now = p.read_bytes()
            log(f"  {'OK ' if now == data else 'BAD'} {rel}  bytes={len(now)}/{len(data)}")
    if _V22_SEG:
        if _SNAPSHOTS or use_disk:
            log("[还原] 还原活库的两个函数 ...")
        for seg in _V22_SEG:
            restore_live(seg)
    _RESTORED = True


def _looks_injected(text: str, rel: str) -> bool:
    """清理态识别：该文件的、属于它的文件类锚点必须全部恰 1 次。

    🛑 必须**按文件**核对 —— 若把全部文件类锚点都拿到同一个 `text` 上数，
    那份文本里本就只含其中一部分锚点 ⇒ 恒报"污染"，脚本永远起不来。
    """
    mine = [a for _t, kind, r, a, _r, _e in INJECTIONS if kind == "file" and r == rel]
    return not all(text.count(a) == 1 for a in mine)


def _sig(signum, frame):  # noqa: ARG001
    restore_all()
    sys.exit(1)


atexit.register(restore_all)
signal.signal(signal.SIGINT, _sig)
signal.signal(signal.SIGTERM, _sig)


# ==================================================================
# Maven
# ==================================================================

def install_main() -> tuple[int, str]:
    """🛑 安装主件**只做一次**（124 的教训：每组 install 会被 SIGTERM 腰斩）。"""
    p = subprocess.run(
        [MAVEN_CMD, "-o", "-q", "-pl", "dy-app", "-am", "install", "-DskipTests"],
        cwd=str(SKEL), capture_output=True, text=True, encoding="utf-8",
        errors="replace", env=_env(), shell=False, timeout=1800)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def run_gate():
    p = subprocess.run(
        [MAVEN_CMD, "-o", "-pl", "dy-app", "test",
         f"-Dtest={GATE_CLASS}",
         "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false"],
        cwd=str(SKEL), capture_output=True, text=True, encoding="utf-8",
        errors="replace", env=_env(), shell=False, timeout=1800)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def hits(out: str, methods):
    return {m for m in methods if m in out}


# ==================================================================
# 主流程
# ==================================================================

def main() -> int:
    global _V22_TEXT, _V22_SEG

    log("=" * 78)
    log("A-3 · 手环历史补拉通路（band_sync_probe / band_daily_coverage）· 反向验证（125）")
    log("=" * 78)

    # ---- 快照（先判污染态，再落盘）----
    SNAP_DIR.mkdir(parents=True, exist_ok=True)
    for rel in _file_rels():
        p = (SKEL / rel).resolve()
        assert p.is_file(), f"被测文件不存在：{p}"
        data = p.read_bytes()
        if _looks_injected(data.decode("utf-8"), rel):
            log(f"[中止] {rel} 当前已处于注入态（上次运行被硬杀未还原）。"
                f"请先 `--restore-only` 或手工还原，再重跑。")
            return 2
        _SNAPSHOTS[rel] = data
        (SNAP_DIR / Path(rel).name).write_bytes(data)
        log(f"[快照] {rel} bytes={len(data)}（内存 + 磁盘兜底）")

    texts = {rel: data.decode("utf-8") for rel, data in _SNAPSHOTS.items()}
    _V22_TEXT = (SKEL / F_V22).resolve().read_text(encoding="utf-8", newline="")
    for seg in SEG_HEAD:
        _V22_SEG[seg] = extract_segment(_V22_TEXT, seg)
    log(f"[快照] V22 源码 read（{len(_V22_TEXT)} 字符）· "
        + " / ".join(f"{FN_BY_SEG[s]} 段 {len(_V22_SEG[s])} 字符" for s in _V22_SEG))

    # ---- 锚点预检：存在 + 恰 1 次 ----
    problems = []
    for tag, kind, target, anchor, _repl, _expect in INJECTIONS:
        if kind == "file":
            n = texts[target].count(anchor)
            where = target
        else:
            n = _V22_SEG[target].count(anchor)
            where = f"V22[{target} 段]"
        ok = (n == 1)
        log(f"[预检 {'OK ' if ok else 'BAD'}] {tag} :: 锚点在 {where} 出现 {n} 次"
            f" :: {anchor.strip()[:52]}")
        if not ok:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    # ---- 活库基线自证（本轮独有的安全网；两个函数都要）----
    log("\n[活库基线] 核对两个活库函数 == V22 源码函数（关键锚点逐字）...")
    if not live_matches_source():
        log("[中止] 活库函数与 V22 源码不一致 —— 它可能停在【上一次注入态】。"
            "请先 `--restore-only`，再重跑。")
        for seg, fn in FN_BY_SEG.items():
            log(f"       {fn} prosrc 前 200 字符：{live_prosrc(fn)[:200]!r}")
        return 2
    log("[活库基线 OK] 两个活库函数均与 V22 源码一致")

    # ---- 基线：注入前必须全绿 ----
    log("\n[基线] 安装主件（一次性，之后每组只 test）...")
    ci, outi = install_main()
    if ci != 0:
        log(f"[基线失败] install 主件失败（exit={ci}）—— 后续结论无意义")
        log(outi[-4000:])
        return 2
    log("[基线 OK] install 完成")
    log("[基线] 注入前跑门禁（期望 12 例全绿）...")
    c0, out0 = run_gate()
    if c0 != 0:
        log(f"[基线失败] 注入前门禁未通过（exit={c0}）—— 后续结论无意义")
        log(out0[-4000:])
        return 2
    pre = hits(out0, METHODS)
    if pre:
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(pre)} —— "
            f"锚点恒真，无判别力")
        return 2
    log(f"[基线 OK] 注入前全绿，且 {len(METHODS)} 个待查方法名在基线中【零出现】（锚点有判别力）")

    results = []
    for tag, kind, target, anchor, repl, expect in INJECTIONS:
        log("\n" + "-" * 78)
        log(f"[{tag}] 注入 ...")

        if kind == "file":
            p = (SKEL / target).resolve()
            text = _SNAPSHOTS[target].decode("utf-8")
            assert text.count(anchor) == 1, f"{tag} 锚点不唯一"
            p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")
            assert p.read_bytes() != _SNAPSHOTS[target], f"{tag} 注入后文件字节未变化"
            log(f"   [文件注入] {target}")
        else:
            seg = target
            assert _V22_SEG[seg].count(anchor) == 1, f"{tag} 活库锚点不唯一"
            injected = _V22_SEG[seg].replace(anchor, repl)
            assert injected != _V22_SEG[seg], f"{tag} 活库注入后 SQL 未变化"
            code_i, out_i = psql_apply(injected, f"inject_{tag.split()[0]}")
            if code_i != 0:
                log(f"   [活库注入失败] exit={code_i} :: {out_i[-500:]}")
                restore_live(seg)
                results.append((tag, expect, False, code_i, []))
                continue
            fn = FN_BY_SEG[seg]
            got_prosrc = live_prosrc(fn)
            changed = (anchor not in got_prosrc)
            log(f"   [活库注入] {fn} 已替换 · 原锚点消失={changed}")
            if not changed:
                log("   [活库注入无效] 原锚点仍在 prosrc 里 ⇒ 注入未生效，跳过判定")
                restore_live(seg)
                results.append((tag, expect, False, 0, []))
                continue

        code2, out2 = run_gate()
        got = hits(out2, METHODS)
        caught = (code2 != 0) and (expect in got)
        log(f"   门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        if not caught:
            tail = [ln for ln in out2.splitlines()
                    if ("BandRefetchGate" in ln or "ERROR" in ln
                        or "AssertionFailed" in ln or "必须" in ln or "编译" in ln)]
            log("   —— 诊断片段 ——")
            for ln in tail[:16]:
                log("   " + ln[:180])
        results.append((tag, expect, caught, code2, sorted(got)))

        # ---- 立即还原 ----
        if kind == "file":
            p.write_bytes(_SNAPSHOTS[target])
            assert p.read_bytes() == _SNAPSHOTS[target], f"{tag} 还原后字节不一致"
        else:
            if not restore_live(target):
                log(f"   [严重] {tag} 活库还原失败 —— 中止，避免后续结论污染基线")
                return 2

    # ---- 复绿 ----
    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    c5, out5 = run_gate()
    green = (c5 == 0)
    log(f"[复绿] exit={c5} · 复绿={green}")
    if not green:
        log(out5[-3000:])

    # ---- 还原后核验 ----
    log("\n[还原核验] 逐文件比对字节 ...")
    all_restored = True
    for rel, data in _SNAPSHOTS.items():
        cur = (SKEL / rel).resolve().read_bytes()
        ok = (cur == data)
        all_restored = all_restored and ok
        log(f"  {'OK ' if ok else 'BAD'} {rel}  bytes={len(cur)}/{len(data)}")

    log("[还原核验] 两个活库函数与 V22 源码一致性 ...")
    live_ok = live_matches_source()
    all_restored = all_restored and live_ok
    log(f"  {'OK ' if live_ok else 'BAD'} 两个活库函数已回到源码态")

    log("\n" + "=" * 78)
    log("汇 总")
    log("=" * 78)
    for tag, expect, caught, code, got in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}")
        log(f"         期望 `{expect}` 变红 · exit={code} · 实际红集={got}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")
    log(f"  [{'PASS' if all_restored else 'FAIL'}] 逐字节还原（源文件 + 两个活库函数）")

    passed = (sum(1 for _t, _e, c, _c2, _g in results if c)
              + (1 if green else 0) + (1 if all_restored else 0))
    total = len(results) + 2
    log(f"\n结论: {passed}/{total} {'PASS' if passed == total else 'FAIL'}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    if "--restore-only" in sys.argv:
        _V22_TEXT = (SKEL / F_V22).resolve().read_text(encoding="utf-8", newline="")
        for seg in SEG_HEAD:
            _V22_SEG[seg] = extract_segment(_V22_TEXT, seg)
        if not SNAP_DIR.is_dir():
            log(f"[还原] 找不到磁盘兜底快照目录：{SNAP_DIR}（只还原活库）")
        restore_all(use_disk=True)
        log("\n[还原核验]")
        for rel in _file_rels():
            cur = (SKEL / rel).resolve().read_bytes()
            log(f"  [{'OK ' if not _looks_injected(cur.decode('utf-8'), rel) else 'BAD'}] "
                f"{rel} 已回到清理态 bytes={len(cur)}")
        log(f"  [{'OK ' if live_matches_source() else 'BAD'}] 两个活库函数已在源码态")
        sys.exit(0)
    sys.exit(main())