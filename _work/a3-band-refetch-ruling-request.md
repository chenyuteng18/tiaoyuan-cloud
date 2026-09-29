# A-3 手环补拉通路口径核对 · A-4 RLS 覆盖载体登记方案（裁定请求）

> **文件性质**：勘查与裁定请求，**不是**实施文档。本轮不写生产代码、不改迁移、不改契约。
> **出具**：架构师（高见远） · 2026-09-28
> **硬边界遵守声明**：本轮全程只读；唯一落盘文件 = 本文件。
> 未执行 `mvn` / `psql` / `git`；未修改任何 `.java` / `.sql` / `.yaml` / `.json` / 既有 `.md`。
>
> **引用口径**：本文所有 `文件:行号` 均指向下列绝对路径（下文用简称）：
> - `PRD` = `deliverables/product-strategy/prd-health-mgmt-saas-2026-09-16.md`
> - `冻结件` = `deliverables/product-strategy/_work/contract-t6-api-freeze-2026-09-19.md`
> - `契约` = `deliverables/product-strategy/contract/openapi-v1.0.0.yaml`
> - `字典` = `deliverables/product-strategy/_work/data-dict-entities-ddl-2026-09-19.md`
> - `V5` = `deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V5__remaining_entities_org_journey_verdict_refund.sql`
> - `V3` = `deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V3__band_telemetry_metric_long_table.sql`
> - `规划` = `deliverables/product-strategy/缺口修复总规划-2026-09-27.md`

---

## 0. 结论速览

| 项 | 裁定 | 一句话依据 |
|---|---|---|
| **A-3 补拉口径** | **口径已定（可动工）** —— 附 **6 条待裁**（不代拍），其中 **1 条为三方冲突**，会决定"遍历集合"这一核心参数 | 口径不在 PRD，而在 `字典 §4.6`（补拉引擎 4 步流程）+ `§4.7`（幂等键）+ `冻结件 §4.1~§4.5`；三者对"补什么 / 从哪补 / 补到哪"给出了可逐字引用的完整定义 |
| **A-4 载体登记** | 三表在 `RlsCoverageGateTest` 侧**已登记、已直连隔离**（2026-09-24 V5 收口时完成）；真正缺的是 `RlsInjectionRealityGateTest.LEDGER_CARRIERS` 侧的**写入方载体**，它随 A-1 / A-3 写入方落地而新增（23 → 25 或 26，取决于载体拆分，我不代拍） | `RlsCoverageGateTest.java:212/225/227` 已登记；`RlsV5EntityIsolationTest.java:87/99/112/114` 已有逐表直连断言 |

---

## 1. 🛑 先纠正三条事实性前提（机械核对，非推断）

这三条是本轮勘查先撞上的，且都会改变后续所有结论的落点，故置顶。

### 1.1 任务书写的 `E1(POST /band/telemetry)` 与契约不符 —— E1 是 `/band/sync-batches`

- `契约:738` → `  /band/sync-batches:`
- `契约:742` → `      summary: E1 客户端上报同步批次（四态）`
- `契约:743` → `      x-contract-row: E1`
- `契约:761` → `  /band/telemetry:`
- `契约:765` → `      summary: E2 客户端上行逐日采集数据（幂等 upsert · 双分支）`
- `契约:771` → `      x-contract-row: E2`

⇒ **`E1 = POST /band/sync-batches`；`E2 = POST /band/telemetry`。** 任务书把 E2 的路径安到了 E1 上。
这不是文字差异：E1 落 `band_sync_log`，E2 落 `band_telemetry`，两者是不同的幂等键与不同的落库目标，A-3 的"数据一致性断言"若按错误映射写会断言错对象。

### 1.2 `E5` 是 **POST**，任务书写成了 `GET`

- `契约:839` → `  /band/available-dates:`
- `契约:840` → `    post:`
- `契约:843` → `      summary: E5 设备可用日期探测结果上报（N 运行时探测 · 禁硬编码）`
- `契约:851` → `      x-contract-row: E5`
- `冻结件:585` → `**E5** `POST /api/v1/band/available-dates` —— 客户端调用厂商 `sdk.getValidHistoryDates(historyType)` 后，把**设备上实际存在的有效日期数组**上报服务端。`

⇒ E5 是**客户端上报**语义（把探测结果推给服务端），不是服务端查询。方向写反会把"探测值从哪来"整个搞反 —— 而它正是 `band_sync_probe` 的唯一数据来源。

### 1.3 规划所引「PRD §4.x」**不存在**；正确出处是 `字典 §4.6 / §4.7`

- `规划:102` → `| **A-3** | ... | ⚠️ 上游"补拉口径"是否已定（PRD §4.x）—— **须先核对**，不确定则登记待裁 |`
- PRD 的二级标题实测（`grep -n "^## "`）：`1003:## 4. 竞品对比（来源：竞析）` —— **PRD §4 是竞品对比，不含任何补拉内容**。
- PRD 全文检索 `4.6` / `4.7`：**0 命中**。
- 补拉口径真正的落点：
  - `PRD:470` → `#### 2.8.7 业务方裁定落盘：**M-WX-FG「打开即自动同步 + 设备端历史补拉」**（**业务方 2026-09-19 拍板 · 本节为 v1.25 新增**）`
  - `字典:799` → `### 4.6 N（留存窗口）改为运行时探测值 —— 落库方案`
  - `字典:851` → `### 4.7 幂等键（重复拉取 = 覆盖，非追加）`
  - `冻结件:541` → `## 4. 手环接入契约专章（对应 S1-7）`

⇒ **「PRD §4.x」是引用错误**，它的正确所指是 **`字典 §4.x`**（`V5:25` 亦逐字写着字段级权威来源 = `字典 §2.2~§2.27 + §4.6/§4.7`）。
这条很重要：**口径并没有缺失，是规划把章节号记错了**，从而把一个"已定"的口径误登记成了"须先核对"。

---

## 2. A-3 任务① —— 逐字检索结果

**检索式**（在 PRD / 契约 / 冻结件 / 字典 / V5 上分别执行）：
`补拉|补测|回填|补录|补传|同步探测|coverage|probe|refetch|backfill|retention_window|留存窗口|N-13`

### 2.1 【有原文依据】补拉口径的四层来源（按权威层级自上而下）

| 层 | 位置 | 逐字原文（节选） | 定义的是什么 |
|---|---|---|---|
| **L1 业务裁定** | `PRD:472` | `> **业务方原话（裁定）**：「**先做小程序吧，必须保证打开小程序自动获取智能手环数据。**」` | 载体 = 微信小程序；时序 = 打开即自动同步 |
| **L2 流程口径** | `PRD:499` | `**③ 关键能力 —— 设备端历史补拉（把"每天必须打开"降为"每 N 天内打开一次"）**` | 补拉这一能力的定位 |
| **L2 流程口径** | `PRD:504` | `` \| **起始日** \| `start = max(last_synced_date + 1, today − N_retain)`；**上界 = today（含当日）**；🔴 **不得回溯超过 `floor`** \| `` | **从哪补（起点/上界）** |
| **L2 流程口径** | `PRD:505` | `` \| **分页/游标** \| 逐日 × 13 接口；单接口内以 `sportLength > 1` 判定继续；**每日每接口设最大翻页上限**（防死循环） \| `` | 遍历与翻页 |
| **L2 流程口径** | `PRD:506` | `` \| **幂等** \| **业务唯一键 = `(device_id, metric, date, hour, minute)`**（日聚合接口退化为 `(device_id, metric, date)`）；**上报前 upsert（服务端权威）** → 重复拉取 = 覆盖、非追加 \| `` | **幂等键** |
| **L2 流程口径** | `PRD:507` | `` \| **断点续传** \| **每完成一天即持久化 `date_done`**（不等全部完成才写）；失败日入 `pending_dates` + 指数退避 \| `` | 续传 |
| **L2 流程口径** | `PRD:508` | `` \| **节流** \| `onShow` 在"切客服会话再切回""扫码再回来"时也会触发 → **须时间窗节流（建议 5 分钟内不重复发起）** \| `` | 节流 |
| **L3 落库口径** | `字典:845-848` | `**N 如何被补拉逻辑消费**：`<br>` 1. 小程序 `onShow` → 先调 `getValidHistoryDates(historyType)` → upsert `band_sync_probe`（刷新 `retention_window_days`）。`<br>` 2. 补拉引擎读 `N_retention = min(各 history_type 的 retention_window_days)`（**→ A6 未解前按保守假设 ≤7 天兜底**）→ 计算 `start = max(last_synced_date+1, today − N_retention)`。`<br>` 3. 逐日 × 12 接口补拉 → upsert `band_telemetry` → 写 `band_daily_coverage`（含该日 `N_at_that_time`）。` | **补拉引擎的完整 4 步**（补到哪、顺序、谁写谁） |
| **L3 落库口径** | `字典:849` | `4. **N 未取证前**：`retention_window_days` 记 **TBD**，UI 明示"最多可回捞 N 天"，**不得对外承诺具体天数**。 | N 的 TBD 纪律 |
| **L3 落库口径** | `字典:855` | `` \| **12 条按日型**（heartRate/step/temp/bp/spo2/pressure/met/mai/sleep/respiration/exercise/bloodSugar） \| **`(device_id, metric, date, hour, minute)`** \| `` | 按日型集合（⚠️ 含 bloodSugar，见 §5 C-1） |
| **L3 落库口径** | `字典:856` | `` \| **1 条游标型**（`getSportHistory`，入参 `(false)` 单 boolean，循环条件 `sportLength > 1`） \| **`(device_id, 'sport', currentSportId)`** \| `` | 游标型分支 |
| **L4 契约口径** | `冻结件:611` | `**E2** `POST /api/v1/band/telemetry` —— 逐日采集数据上行，**服务端权威 upsert**（重复拉取 = 覆盖，非追加）。` | E2 语义 |
| **L4 契约口径** | `冻结件:598` | `` \| `pull_start_date` \| date \| **服务端计算**：`start = max(last_synced_date + 1, today − retention_window_days)`；**上界 = today（含当日）** \| `` | E5 出参起始日 |
| **L4 契约口径** | `冻结件:597` | `` \| `retention_window_days` \| int \| **`TBD`** \| **运行时探测值**（= 探测到的可用日期跨度）；**未探测到 → 返回 `TBD`，不得回落为硬编码值** \| `` | N 的契约表达 |
| **L4 契约口径** | `冻结件:628` | `` \| 数据缺失 \| `coverage_flag = null` \| **不得自动补 0** \| `` | 缺失语义 |
| **L4 契约口径** | `冻结件:645` | `> 🔴 **`isWear` 双刃性处置**：`1`=佩戴／`0`=脱腕（行为性）／**`(-1,255)`=技术性缺失 → 一律不判行为性**（宁可少扣、不可错扣）。契约层：`is_wear = -1 或 255` → 服务端强制覆写为技术性缺失，**不得落入 ③**。` | is_wear 语义 |
| **L5 已落库 DDL** | `V5:1000` | `    coverage_flag          BOOLEAN,` （上一行 `V5:999`：`    -- 🛑 §4.3: NULL = 缺失（严禁补 0）；true = 该日有数据`） | 库层缺失语义 |
| **L5 已落库 DDL** | `V5:1003-1005` | `    gap_reason             VARCHAR(32) CHECK (gap_reason IS NULL OR gap_reason IN (` / `'no_open','sync_failed','not_worn','compliant_removal',` / `'involuntary_technical','beyond_retention_window','unknown')),` | 缺口归因 7 值 |

### 2.2 【有原文依据】"待定"的部分（同样是逐字）

| 位置 | 逐字原文 |
|---|---|
| `冻结件:652` | `` \| **N**（设备留存窗口） \| `retention_window_days` \| **`TBD`** \| **运行时由 `getValidHistoryDates` 探测**；未探测到前**不得对外给具体天数**（Q-W4 阻塞） \| `` |
| `冻结件:653` | `` \| **s**（单次前台同步成功率） \| — \| **`TBD`** \| 未取证；**不得写入任何"补拉可让 A3 达标"的断言** \| `` |
| `冻结件:656` | `> 🛑 **契约纪律**：凡涉及 N / s / f 的字段，**一律 `TBD` 占位**；**第 1 批只落地探测接口，不填假值**（Sprint 1 派工单 §六 TBD 条款）。` |
| `冻结件:746` | `` \| **Q-W4** \| **N（设备留存窗口）由厂商确认** \| **阻塞采集 SOP 口径**；契约侧以 `TBD` 占位（§4.5） \| 厂商 \| `` |
| `PRD:545` | `` \| **④** \| **A6「无数据语义」**（返回"无记录"标记 vs 静默不返回） \| 厂商 \| 无法区分 → **补拉结果不得用于行为性判定** \| **补拉可解释性** \| `` |
| `V5:1114-1115` | `--        ⚠️ 【登记为待裁定】：history_type 的正式词汇表（SDK camelCase vs 内部 metric` / `--        小写）尚未由上游拍定，本文件不代拍；亦不刻录已废的 bloodSugar。` |
| `字典:1219` | `4. **`band_sync_probe` / `band_sync_log` / `band_daily_coverage` 三表是否立项**（属待评审增项）。` |

---

## 3. A-3 任务② —— E1 / E2 / E5 的角色与 schema ⇒ "补什么 / 从哪补 / 补到哪"

### 3.1 三行契约行的 `x-callable-roles` 实测

| 行 | 方法 + 路径 | `x-contract-row` | `x-callable-roles` | 其他 |
|---|---|---|---|---|
| `契约:738-744` | `POST /band/sync-batches` | `E1` | **`[client]`** | `x-client-forbidden: false`；响应 400/409/429 |
| `契约:761-772` | `POST /band/telemetry` | `E2` | **`[client]`** | `x-client-forbidden: false`；响应 409/422 |
| `契约:839-852` | `POST /band/available-dates` | `E5` | **`[client]`** | `x-client-forbidden: false`；响应 400/422 |

⇒ **三行全部只由 `client` 调用**。这是一个强信号：**补拉通路的数据入口全在客户端（小程序）**，服务端没有"主动去设备拉数据"的端点（这与 `PRD:479`「打开即自动触发同步」一致，也与 `规划:85`「本方案不依赖后台（打开即前台同步）」一致）。

### 3.2 请求 / 响应 schema 要点（逐字）

**E1**（`契约:1712-1737` `BandSyncBatchRequest`）
```
required: [device_id, customer_id, batch_no, trigger, state, synced_at]
trigger: enum [on_show_cold, on_show_hot, checkin, daily_report, manual]
state:   enum [syncing, synced, sync_failed, no_data_today]
fail_reason_class: enum [bt_off, unauthorized, connect_timeout, device_low_battery,
                         occupied_by_vendor_app, platform_suspended, probe_out_of_window]
next_action: enum [open_bluetooth, grant_permission, retry, none]
```
⚠️ 注意 `probe_out_of_window` 在 E1 的失败枚举里 —— **"超窗口"是一个已定义的失败类别**，说明契约已预期"补拉会撞到窗口边界"。

**E2**（`契约:1739-1759` `BandTelemetryRequest`）
```
required: [device_id, metric]
metric: enum [sleep, steps, hr, resting_hr, spo2, workout, bp, temp, pressure, met, mai, respiration, exercise]   ← 13 值
date / hour / minute / value / interval / is_wear / raw_source / current_sport_id / sport_payload / sport_length
is_wear: "getHealthDetail.isWear；1=佩戴 / 0=脱腕 / (-1,255)=技术性缺失（服务端强制覆写、不判行为性）"
```

**E5**（`契约:1814-1846`）
```
BandAvailableDatesRequest required: [device_id, history_type, valid_history_dates, probed_at]
  history_type: "与厂商枚举对齐（13 条逐日接口对应的 HistoryType；12 按日 + 1 游标）"
  valid_history_dates: "getValidHistoryDates 原样返回（设备端实际存在的日期）"
BandAvailableDatesData: probe_id / retention_window_days(oneOf int | 'TBD') /
                        pull_start_date / earliest_available / latest_available
```

### 3.3 由 3.1 + 3.2 推出的"补什么 / 从哪补 / 补到哪"

> 以下**结论均有上文逐字依据支撑**；标注【推断】的才是我自己的推论。

| 问题 | 答案 | 依据 |
|---|---|---|
| **补什么** | 设备端**逐日历史数据**：按日型 12 条接口 + 游标型 1 条（sport）= 13 条；粒度 = `(device_id, metric, date[, hour, minute])` | `字典:855-856`；`冻结件:615-616`；`契约:1746` |
| **从哪补** | 从**设备（手环）经厂商 SDK**，由**客户端小程序**在 `onShow` 触发；**服务端不主动拉**（三行 `x-callable-roles` 全为 `[client]`） | `契约:744/772/852`；`字典:846`；`PRD:479` |
| **补到哪** | 三张落库表，顺序固定：`band_sync_probe`（探测结果，先）→ `band_telemetry`（upsert 逐日数据）→ `band_daily_coverage`（服务端写逐日覆盖/缺口明细，含 `n_at_that_time`） | `字典:846-848`；`V5:1011` `n_at_that_time`；`V5:1013` `source_sync_log_id ... REFERENCES band_sync_log` |
| **起点** | `start = max(last_synced_date + 1, today − N_retention)`，上界 = today（含当日） | `PRD:504`；`契约:1844`；`冻结件:598`；`字典:847` |
| **N 取哪** | `N_retention = min(各 history_type 的 retention_window_days)`；未探测到 → `TBD`（**禁止回落硬编码**） | `字典:847`；`契约:1840`（`未探测到 → 返回 TBD，不得回落为硬编码值`）；`契约:846-848` |
| **幂等** | 按日型 `(device_id, metric, date, hour, minute)`（日聚合退化为 `(device_id, metric, date)`）；游标型 `(device_id,'sport',currentSportId)`；**运动数据不得复用按日型键** | `PRD:506`；`字典:855-856`；`冻结件:613-616`；`契约:767-770` |
| **缺失语义** | `coverage_flag = NULL`（严禁补 0）；`is_wear ∈ (-1,255)` → 强制覆写为技术性缺失、**不判行为性** | `V5:999-1000`；`契约:1755`；`冻结件:645`；`PRD:309` |

**【推断】**（标注，不属于原文）E5 是 `band_sync_probe` 的唯一入口，E2 是 `band_telemetry` 的唯一入口，而 `band_daily_coverage` **没有任何契约端点写它** —— 它只能由服务端在 E2 upsert 之后派生（`字典:848` 的"→ 写 `band_daily_coverage`"）。这一点决定了 A-3 的实现形态是**"两个客户端端点 + 一个服务端派生步骤"**，而不是"一个补拉接口写三张表"。

---

## 4. A-3 任务③ —— 三选一裁定

### 4.1 裁定：**口径已定**（第一项）

**可立即动工**，但动工范围须按 §4.3 切分，且 §5 的 6 条待裁**不代拍**。

### 4.2 机械依据（为什么是"已定"）

1. **"补什么 / 从哪补 / 补到哪"三问均有逐字出处**（见 §3.3 表，每行都给了 `文件:行号`）—— 这三问正是"补拉通路口径"的定义域，无一项落空。
2. **流程是编号可执行的**：`字典:845-849` 给出 **1→2→3→4 四步**，含 `upsert band_sync_probe` / `upsert band_telemetry` / `写 band_daily_coverage（含该日 N_at_that_time）`，并给出 `N_retention` 的取值公式。这不是原则性描述，是可照写的流程。
3. **PRD 与契约在关键公式上逐字一致**，无冲突：
   - `PRD:504` `start = max(last_synced_date + 1, today − N_retain)`
   - `契约:849-850` `pull_start_date = max(last_synced_date + 1, today − retention_window_days)，上界 = today（含当日）`
   - `冻结件:598` 同式。
4. **幂等键三方一致**：`PRD:506` ≡ `字典:855` ≡ `冻结件:613-616` ≡ `契约:767-769`。
5. **`N / s / f` 的 `TBD` 不是"口径未定"，而是"口径就是 TBD"** —— `冻结件:656` 逐字："凡涉及 N / s / f 的字段，**一律 `TBD` 占位**；**第 1 批只落地探测接口，不填假值**"。把 TBD 当成"未定"会得出"永远不能动工"的错误结论；契约刻意把 N 设计成**运行时探测值**（`字典:801`：「让 **N 从"必须问厂商"降级为"运行时可实测"**」）。

### 4.3 为什么不是另两项

- **不是「口径未定」**：见 4.2。§5 列的 6 条是**实现层决策点**，不是"通路口径"缺口；其中 5 条有既有兜底（TBD 机制 / A6 保守记账 / DDL 已选边）。
- **不是「契约与 PRD 冲突」**：PRD 与契约在起始日公式、幂等键、缺失语义、is_wear 处置上**逐字一致**（见 4.2 第 3、4 点）。真正的冲突是 **契约 ↔ 字典 ↔ 已落库 DDL** 三方（§5 C-1），**PRD 不是冲突方**（PRD 全文检索 `bloodSugar` = 0 命中）。故不套用第三项，而把它作为**独立冲突登记 C-1**。

### 4.4 动工范围切分（建议，非代拍）

| 子项 | 口径状态 | 可否动工 |
|---|---|---|
| **A-3a** `band_sync_probe` ← E5 探测结果落库 | 已定（`冻结件:583-607` + `契约:1814-1846` + `V5:898-943`） | ✅ 可动工 |
| **A-3b** `band_telemetry` ← E2 upsert（已有 `BandLedger`，`PROVISIONED` 账内） | 已定，且已实现 | ✅ 已完成（`ProvisioningBoundaryGateTest.java:208`） |
| **A-3c** `band_daily_coverage` ← 服务端逐日派生 | 流程已定（`字典:848`），**字段级派生规则未定**（见 §5 D-1/D-2） | ⚠️ **部分**：流程可动工，`effective_wear_minutes` / `wear_minutes` / `is_wear` 的取值规则待裁 |

---

## 5. 🛑 待裁清单 —— 逐条列出，不合并，不代拍

### C-1【三方冲突】`bloodSugar` 是否进补拉遍历集合 —— **决定遍历集合，会撞 CHECK**

| 方 | 位置 | 逐字原文 |
|---|---|---|
| 契约 §4.3 | `冻结件:615` | `**按日型 × 12** \| `getStepHistory` / `getHeartRateHistory` / `getBloodPressureHistory` / `getBloodOxygenHistory` / `getPressureHistory` / `getMetHistory` / `getTempHistory` / `getMaiHistory` / `getSleepHistory` / `getRespirationRateHistory` / `getExerciseHistory` / **`getBloodSugarHistory`** \|` |
| 字典 §4.7 | `字典:855` | `**12 条按日型**（heartRate/step/temp/bp/spo2/pressure/met/mai/sleep/respiration/exercise/**bloodSugar**）` |
| 字典 §4.8 | `字典:864` | `- `bloodSugar` / `女性健康` **一律不建字段**；`getVersion().supportSugar` 等布尔能力位**须以设备上报为准、不可按文档假设**。` |
| 已落库 DDL | `V5:914-916` | `history_type VARCHAR(32) NOT NULL CHECK (history_type IN (` / `'sleep','steps','hr','resting_hr','spo2','workout',` / `'bp','temp','pressure','met','mai','respiration','exercise','sport')),` —— **无 bloodSugar** |
| V5 已登记为待裁 | `V5:1113-1115` | `故此处从 metric 词汇，不刻录已废的` / `bloodSugar；命名口径统一本身登记为待裁定项，不在本文件代拍。` |

**机械后果（可复算）**：若补拉引擎按「契约 §4.3 的 12 接口」遍历并写 `band_sync_probe.history_type = 'bloodSugar'`，将撞 `V5:914` 的 CHECK → **PG 23514**。
**需裁**：`bloodSugar` 到底在不在补拉集合内？（若不在，须同步修 `冻结件:615` 与 `字典:855`；若在，须先裁定是否推翻 `字典:864` 并改 `V5` 迁移 —— 后者触发"迁移文本改了 ⇒ 117 对账 + checksum 对齐"硬约束，`规划:190`。）

### C-2【契约内部不一致】"13" 在四处不是同一集合

| 位置 | 逐字原文 | 集合 |
|---|---|---|
| `契约:1821` | `与厂商枚举对齐（13 条逐日接口对应的 HistoryType；**12 按日 + 1 游标**）` | 12 按日 + sport |
| `契约:1746`（E2 metric） | `enum: [sleep, steps, hr, resting_hr, spo2, workout, bp, temp, pressure, met, mai, respiration, exercise]` | **13 个非游标值** |
| `字典:855` | 12 条按日型（camelCase，含 bloodSugar） | 12 按日 |
| `V5:914-916` | 上述 13 metric **+ 'sport'** = **14 值** | 14 |

**差集（机械核对）**：
- 契约 §4.3 的 12 接口 → 映射到 metric 得 **11 个**（`bloodSugar` 无对应）；
- E2 的 metric 13 值中，`resting_hr` / `workout` **在 12 接口名单里没有对应接口**；
- 故 12 接口集合 ≠ metric 13 值集合，差集 = `{bloodSugar}` ↔ `{resting_hr, workout}`。
- `V5:1109` 亦实测：`两者 13 项中仅 9 项同名`。

**需裁**：补拉引擎遍历时以哪个集合为准？（三个候选：契约 12 接口名单 / E2 metric 13 值 / V5 CHECK 14 值）

### D-1【未定义】`band_daily_coverage.wear_minutes` 与 `effective_wear_minutes` 的计算差

- `V5:1008-1009` → `    wear_minutes           INT,` / `    effective_wear_minutes INT,`
- `字典:841` → `` \| is_wear / wear_minutes / effective_wear_minutes \| int \| 可空 \| 来自 `isWear` + `getX04HealthIntervals` \| ``

⇒ 字典只说两者都"来自 `isWear` + `getX04HealthIntervals`"，**未定义"有效"与"原始"的差值规则**（例如是否扣除 `is_wear=0` 的脱腕时段、是否扣除 `pause_period`）。**需裁**：`effective_wear_minutes` 的扣减规则。

### D-2【未定义】`band_daily_coverage.is_wear` 的日聚合规则

- `V5:1007` → `    is_wear                INT         CHECK (is_wear IS NULL OR is_wear IN (-1, 0, 1, 255)),`
- 但 E2 上报的 `is_wear` 是**按采样点**（`契约:1748-1751` 有 `hour` / `minute`），而 `band_daily_coverage` 是**按日**（`V5:998` `date DATE NOT NULL` + `V5:1017` `UNIQUE (device_id, date)`）。
⇒ **多采样点 → 单日取值**的归并规则（取最小值 / 取众数 / 任一点为 0 即 0 / 出现 (-1,255) 如何与其他值共存）**在契约与 PRD 中均无逐字定义**。**需裁**。

### D-3【未定义】`band_sync_probe.probe_source` 与 `is_test` 的取值来源

- `V5:924-925` → `probe_source VARCHAR(32) CHECK (probe_source IS NULL OR probe_source IN (` / `'运行时探测','厂商文档','保守假设')),`
- `V5:928` → `    is_test               BOOLEAN     NOT NULL DEFAULT FALSE,`
- 但 E5 的请求体（`契约:1816`）`required: [device_id, history_type, valid_history_dates, probed_at]` —— **不含 `probe_source`，也不含 `is_test`**。
⇒ 服务端依据什么判定这次探测是"运行时探测 / 厂商文档 / 保守假设"？无契约输入。**需裁**：两列的赋值来源（服务端自判规则，或 E5 增字段 —— 后者属契约 MAJOR 变更）。

### D-4【未取证】A6「无数据语义」

- `PRD:510` → `若无法区分"超出留存窗口"与"客户确实没戴"，就会把"窗口外丢失"误记为"客户没戴"`
- `PRD:545` → 钥匙④，归属厂商
- **已有兜底**（不阻塞）：`PRD:511` → `**A6 未解前，超窗口无数据一律按技术性缺失/不可观测处理，不得用于行为性判定**`；`PRD:536` 给出 C1~C6 保守记账（含"未知缺失占比门建议初始 30%，待校准"）。
⇒ **需裁**：仅一项 —— `PRD:536` 的"未知缺失占比门"初始 30% 是否确认（它是"建议初始"值，字典/契约均未落为 config 项）。

### D-5【立项未批】补拉引擎本身是"待评审增项"

- `PRD:557` → `` \| **③** \| **待评审增项 12~25.5** = SDK 集成 + mp-weixin 编译真机打通 4~9 ＋ **补拉引擎 4~9** ＋ 授权与降级引导 2~3.5 ＋ 测试与真机 2~4 \| **12~25.5** \| **登记「待评审增项」**（第二本账） \| ``
- `PRD:1300` → `② **手环补拉引擎 3 张落库表**（`band_sync_probe` / `band_daily_coverage` / `band_sync_log`）= **待评审增项**`
- `字典:1206` → `` \| 8 \| **★ `band_sync_probe` / `band_sync_log` / `band_daily_coverage` 三表立项**（待评审增项） \| 产品 + 工程 \| §4.6 \| ``
- `字典:1219` → `4. **`band_sync_probe` / `band_sync_log` / `band_daily_coverage` 三表是否立项**（属待评审增项）。`

⇒ **状态错位（需人裁）**：三张表**已经在 V5 里建出来了**（2026-09-24），但**"是否立项"仍是待评审增项**。`规划:102` 把 A-3 列为 G-A「可立即动工」组，与"立项未批"存在张力。
**我不代拍**：是"先建表、立项后补批"，还是"立项批准前 A-3 不动工"？

### D-6【未取证】`getValidHistoryDates` 是否对全部 HistoryType 生效

- `冻结件:754` → `- 厂商 `getValidHistoryDates(historyType)` **对全部 HistoryType 生效**（文档仅给单例返回示例）——**待真机复核**。`
⇒ 若不成立，`字典:847` 的 `N_retention = min(各 history_type 的 retention_window_days)` 无法求值。**需裁**：真机实测前，min() 的降级取值（建议沿用 `字典:847` 已有的"≤7 天兜底"，但那句是"N 未取证前"的兜底，不是"部分 history_type 探测失败"的兜底 —— 后者**无定义**）。

---

## 6. A-4 —— 三表 RLS 覆盖载体登记方案

### 6.1 机械现状清点（先摆事实，避免"以为缺、其实已有"）

| 表 | 迁移文本（`CREATE TABLE` 含 `tenant_id`） | `RlsCoverageGateTest.ISOLATION_TESTS` 登记 | 真库 RLS | 直连隔离测试 |
|---|---|---|---|---|
| `agreement` | ✅ `V5:298/301` | ✅ `RlsCoverageGateTest.java:212` → `Map.entry("agreement", V5)` | ✅ `V5:332-338` ENABLE+FORCE+策略 | ✅ `RlsV5EntityIsolationTest.java:81`（TABLES）+ `:99`（PK=agreement_id）+ `:683`（INSERT 夹具） |
| `band_sync_probe` | ✅ `V5:899/902` | ✅ `RlsCoverageGateTest.java:225` → `Map.entry("band_sync_probe", V5)` | ✅ `V5:937-943` | ✅ `RlsV5EntityIsolationTest.java:87` + `:112`（PK=probe_id）+ `:725`（夹具） |
| `band_daily_coverage` | ✅ `V5:990/993` | ✅ `RlsCoverageGateTest.java:227` → `Map.entry("band_daily_coverage", V5)` | ✅ `V5:1023-1029` | ✅ `RlsV5EntityIsolationTest.java:87` + `:114`（PK=coverage_id）+ `:499-526`（`gap_reason` 7 值专项断言）+ `:729`（夹具） |

**现值实测（可复算）**：
- `RlsCoverageGateTest.ISOLATION_TESTS` = **38 条**（`Map.entry` 计数）
- `RlsV5EntityIsolationTest.TABLES` = **24 条**，且 `:389` 有硬断言 → `assertEquals(24, TABLES.size(), "本类声明的表数必须恰为 24（V5 交付范围）");`
- `RlsInjectionRealityGateTest.LEDGER_CARRIERS` = **23 条**（实测 `awk` 计数；与 `:217` 注释"当前为 23 个"、`:448` 断言文案"实测应为 23"一致）

> 🛑 **一处必须写明的发现**：`规划:103` 写 A-4 = "三张新表各自的直连隔离测试"。但实测表明 —— **三表的直连隔离测试在 2026-09-24（V5 收口）就已随 `RlsV5EntityIsolationTest` 落地**。
> 故 A-4 的**真实缺项不是"直连隔离测试"**，而是 **`RlsInjectionRealityGateTest.LEDGER_CARRIERS` 侧的写入方载体**（三表目前零写入方 ⇒ 零载体）。这一点若照抄规划的字面去做，会做出重复劳动。

### 6.2 表清单 + 租户隔离键

| # | 表名 | PK（`RlsV5EntityIsolationTest.PK_COLUMNS`） | **租户隔离键（RLS 策略列）** | 其他租户相关键 | 落库迁移 |
|---|---|---|---|---|---|
| 1 | `agreement` | `agreement_id`（`V5:300`） | **`tenant_id`**（`V5:301`，`NOT NULL REFERENCES tenant(id)`） | `customer_id`（`V5:302`） | `V5:298-338` |
| 2 | `band_sync_probe` | `probe_id`（`V5:901`） | **`tenant_id`**（`V5:902`） | `device_id`（`V5:904`，`REFERENCES band(band_id)`） | `V5:899-943` |
| 3 | `band_daily_coverage` | `coverage_id`（`V5:992`） | **`tenant_id`**（`V5:993`） | `device_id`（`V5:995`）+ `customer_id`（`V5:996`） | `V5:990-1029` |

**三表的 RLS 策略形态统一**（`V5:337-338` / `942-943` / `1028-1029`）：
```sql
USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
```
⇒ **隔离键一律是单列 `tenant_id`**；`device_id` / `customer_id` 是业务外键，**不参与 RLS 判定**。这一点决定了直连隔离测试的构造方式：跨租户反证只需切 `app.tenant_id`，不需要造跨租户的 device/customer。

### 6.3 需登记进 `RlsInjectionRealityGateTest` 的载体名

> **登记表口径提醒**：`LEDGER_CARRIERS` 登记的是**定义了 `<T> T inTenant(` 的类文件路径**（`RlsInjectionRealityGateTest.java:136-138`），**不是表名**。判据③（`:426-450`）做的是"扫描到的载体集合 == 登记表集合"的**双向集合相等**，与表数无关。
> ⇒ 因此"三表共用一个载体"**不会**减少登记行数，只是少一个新类。这是选型时必须知道的机械事实。

| 方案 | 新增载体（相对 skeleton 根的路径） | 登记后 `LEDGER_CARRIERS` 规模 | 说明 |
|---|---|---|---|
| **方案 A（一表一载体，与既有 `*Ledger` 命名一致）** | `dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/repository/AgreementLedger.java`<br>`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandSyncProbeLedger.java`<br>`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandDailyCoverageLedger.java` | 23 → **26** | 与 `BandLedger`（`band_telemetry`+`band_sync_log`）/`BandBindingLedger`（`band`）并列，各自名字不撒谎 |
| **方案 B（补拉两表合一载体）** | `AgreementLedger.java` + `dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandRefetchLedger.java` | 23 → **25** | 补拉两表同源同流程（`字典:846-848` 一步链），合一类可读性好；但需注意名字不再能"一望知表" |

**🛑 我不代拍 A / B**。裁定时需要的机械依据：
- 判据②（`:396-418`）要求：**每个定义了 `inTenant` 的类必须在同文件出现** `SET LOCAL app.tenant_id`（正则 `SET\s+LOCAL\s+app\.tenant_id\s*=`，`:152-153`）；
- 判据③（`:426-450`）要求：漏登记红 **且** 僵尸登记红，且 `LEDGER_CARRIERS.size() == 实际扫描数`；
- 判据 `:447-449` 有下限断言 `assertTrue(actual.size() >= 2, ...)` 且文案写"实测应为 23" —— **新增载体后该文案必须同步改**（否则虽不红，但注释开始说谎；这与本仓"名字说谎的测试类"纪律冲突）。
- `every_tenant_table_has_a_loadable_isolation_test`（`RlsCoverageGateTest.java:391-409`）要求登记的隔离测试类 **≥5 个 `@Test`**；若为新载体另起隔离测试类，须满足此下限。

**⚠️ 一处硬约束（决定不能另起隔离测试类）**：`RlsV5EntityIsolationTest.java:389` 断言 `TABLES.size() == 24`。若把 `agreement` / `band_sync_probe` / `band_daily_coverage` 从 `TABLES` 移出去另建隔离类，该断言立即红。
⇒ **推荐保持三表留在 `RlsV5EntityIsolationTest` 内**（即 A-4 的"直连隔离测试"沿用既有类，只补"写入方存在后"的新断言），**不另起类**。

### 6.4 "三源交叉"的机械判据写法

三源 = ① 迁移文本 ② `ISOLATION_TESTS` 登记表 ③ 真库 `information_schema`。
既有实现已覆盖 ①②③ 的**集合相等**（`RlsCoverageGateTest.java:356-387` `three_sources_must_agree_on_the_set_of_tenant_tables`）。针对 A-4 三表，需在其上补**逐表、逐要素**的判据。

**判据 ① —— 迁移文本（静态解析，复用既有解析器）**
```java
// 复用 RlsCoverageGateTest.tenantTablesFromMigrations()（:461-484）
// 其解析器只认 CREATE TABLE ... ( 且要求建表体内出现独立 tenant_id 列名（:465）
Set<String> fromMigration = tenantTablesFromMigrations();
assertTrue(fromMigration.containsAll(
        Set.of("agreement", "band_sync_probe", "band_daily_coverage")),
    "三表必须在迁移文本中被解析为租户表（含独立 tenant_id 列）: " + fromMigration);
```
自证要求（防假绿）：解析器必须能解析出**非空**集合（`RlsCoverageGateTest.java:338` `:386` 已有同类断言）。

**判据 ② —— 登记表（双向差集）**
```java
// 正向：三表已登记（现状已满足：:212 / :225 / :227）
for (String t : Set.of("agreement", "band_sync_probe", "band_daily_coverage")) {
    assertTrue(ISOLATION_TESTS.containsKey(t), "租户表未登记隔离测试: " + t);
}
// 反向：无僵尸登记（现状已由 :350-353 覆盖）
Set<String> stale = new LinkedHashSet<>(ISOLATION_TESTS.keySet());
stale.removeAll(fromMigration);
assertTrue(stale.isEmpty(), "僵尸登记: " + stale);
```

**判据 ③ —— 真库 `information_schema`（三源交叉的第三源）**
```sql
-- 期望：恰 3 行
SELECT table_name
FROM information_schema.columns
WHERE table_schema = 'public'
  AND column_name = 'tenant_id'
  AND table_name IN ('agreement','band_sync_probe','band_daily_coverage')
ORDER BY table_name;
```
```java
Set<String> fromRealDb = new LinkedHashSet<>(jdbc.queryForList(
    "SELECT table_name FROM information_schema.columns "
  + "WHERE table_schema = 'public' AND column_name = 'tenant_id' "
  + "AND table_name IN ('agreement','band_sync_probe','band_daily_coverage')",
    String.class));
assertEquals(Set.of("agreement","band_daily_coverage","band_sync_probe"), fromRealDb,
    "三表在真库中必须确有 tenant_id 列（迁移未真正应用 / 被手工建表都会在此暴露）");
```

**判据 ④ —— 真库 RLS 强制要素（逐表，四要素全查）**
```sql
-- 期望：3 行；relrowsecurity=t, relforcerowsecurity=t, policies>=1, owner=应用用户（非超级用户）
SELECT c.relname,
       c.relrowsecurity,
       c.relforcerowsecurity,
       pg_get_userbyid(c.relowner)                                   AS owner,
       (SELECT count(*) FROM pg_policies p WHERE p.tablename = c.relname) AS policies
FROM pg_class c
WHERE c.relkind = 'r'
  AND c.relname IN ('agreement','band_sync_probe','band_daily_coverage');
```
四要素缺一即红（对齐 `RlsCoverageGateTest.java:428-440` 的既有口径：未 ENABLE / 未 FORCE / owner 非应用用户 / 零策略）。

**判据 ⑤ —— 直连隔离（真库行为断言，逐表）**
```java
// 形态沿用 RlsV5EntityIsolationTest 的"按 PK 直查反证"：
//   ① 以 TENANT_A 上下文 INSERT 一行（PK = 固定 UUID）
//   ② 切 app.tenant_id = TENANT_B
//   ③ SELECT 1 FROM <table> WHERE <pk> = ?  → 期望「0 行」
//   ④ 切回 TENANT_A → 期望「1 行」（自证：不是"表里根本没数据"的假绿）
// 三表 PK 分别：agreement_id / probe_id / coverage_id（RlsV5EntityIsolationTest.java:99/112/114）
```
🛑 ④ 这一步**不可省** —— `RlsInjectionRealityGateTest.java:61-62` 已逐字警告：fail-closed 策略的形态是"看起来正常的空结果"，故"查不到"必须靠"同键在正确租户下查得到"来证伪。

**判据 ⑥ —— 载体登记双向一致（A-1 / A-3 写入方落地后触发）**
```java
// 既有判据（RlsInjectionRealityGateTest.java:426-450）无需改写，只需：
//   ① 在 LEDGER_CARRIERS 增加 §6.3 的新载体路径；
//   ② 每个新载体类内必须出现  SET LOCAL app.tenant_id = ...（判据②，:152 正则）
//   ③ 同步 :447-449 的规模断言文案（现写"实测应为 23"）
// 新增载体而未登记 → 判据③ 红；登记了而类被删 → 僵尸登记红。
```

**判据 ⑦ —— 三源交叉的"第四重"：两账改账（与 A-4 强耦合）**
A-1 / A-3 落地写入方后，`ProvisioningBoundaryGateTest` 的两账必须**显式改账**（`规划:91` ⑤、`规划:189`）：
- `NOT_PROVISIONED`（现值 **3** 张，`ProvisioningBoundaryGateTest.java:344-348`）：`band_sync_probe` / `band_daily_coverage` / `agreement` → 全部移出；
- `PROVISIONED`（现值 **39** 条，`ProvisioningBoundaryGateTest.java:182-279`）→ 增至 **42**。

⚠️ **两处计数漂移，需同步核对（非本轮修复范围，仅登记）**：
1. `ProvisioningBoundaryGateTest.java:177` 注释写"生产代码里**真实存在写入方**的表（**38 张**）"，但实测 `PROVISIONED` 条目数 = **39**（`case_archive` 由 B-13 从 `NOT_PROVISIONED` 移入后，注释未同步）。
2. `规划:48` 写"`PROVISIONED` 账现值 = **42 张**"，`规划:39` 写"`NOT_PROVISIONED` 账现值 = **4 张**" —— 而代码现值分别为 **39** 与 **3**（`case_archive` 已由 B-13 收口）。42 = 39 + 3 是**全集**口径，与"PROVISIONED 账 = 42"的表述不一致。
   ⇒ 这与 `规划:211` 自身第 1 条约束（"三处汇总必须同改"）冲突，属**规划文档滞后于代码**，**不在本轮修复**（硬边界：不改既有 `.md`）。

---

## 7. 我查不到依据、需要人来裁的点（汇总，逐条）

> 每条都标注：**为什么我裁不了**（缺什么原文）+ **它阻塞什么**。

| # | 待裁点 | 出处 | 我裁不了的原因 | 阻塞对象 |
|---|---|---|---|---|
| **R-1** | `bloodSugar` 是否在补拉遍历集合内 | 冲突见 §5 C-1 | 三方（契约 / 字典 §4.8 / V5 DDL）互相矛盾，且 V5 已明示"不在本文件代拍"；任一侧都需**上游**推翻另两方之一 | A-3c 遍历集合；若改 DDL 则触发 117 对账 |
| **R-2** | 遍历集合以哪个为准（12 接口 / metric 13 值 / V5 CHECK 14 值） | §5 C-2 | 契约内部即不一致，"13" 在三处指不同集合 | A-3c |
| **R-3** | `effective_wear_minutes` 与 `wear_minutes` 的差值规则 | §5 D-1 | 字典只写"来自 isWear + getX04HealthIntervals"，无扣减定义；PRD / 契约 0 命中 | `band_daily_coverage` 写入方；A3 分子口径（`字典:879`） |
| **R-4** | `band_daily_coverage.is_wear` 的多采样点→单日归并规则 | §5 D-2 | 契约只定义采样点级 `is_wear`，未定义日级归并；`(-1,255)` 与 `0/1` 同日共存时如何取，无原文 | `band_daily_coverage` 写入方；A3 行为性判定（唯一可扣分路径，`PRD:530`） |
| **R-5** | `band_sync_probe.probe_source` / `is_test` 的赋值来源 | §5 D-3 | E5 请求体不含这两个字段（`契约:1816`），服务端无输入可依；补字段属契约 **MAJOR** 变更（`ProvisioningBoundaryGateTest.java:55` 同款口径：需产品共签） | A-3a 落库；若为补字段则改契约 |
| **R-6** | A6"未知缺失占比门"初始 30% 是否确认 | §5 D-4（`PRD:536`） | PRD 写"建议初始 30%，**待校准**"，未落为 config 项；字典 / 契约 0 命中 | A3 `applicable` 判定门限 |
| **R-7** | 补拉引擎 / 三表**立项**是否已批（现为"待评审增项"） | §5 D-5（`PRD:557` / `PRD:1300` / `字典:1206` / `字典:1219`） | 表已建但立项未批，属产品+工程的立项裁定，非架构可拍 | **A-3 整体是否动工的前置** |
| **R-8** | `getValidHistoryDates` 未对部分 HistoryType 生效时，`min()` 如何降级 | §5 D-6（`冻结件:754`） | 契约把它列为"假设·未验证"；`字典:847` 的 ≤7 天兜底只覆盖"N 未取证"，不覆盖"部分探测失败" | A-3c 起始日计算 |
| **R-9** | A-4 载体拆分：一表一载体（26）还是补拉两表合一（25） | §6.3 | 两种都满足判据③（集合相等），差别在可读性与命名纪律，属工程风格裁定 | `LEDGER_CARRIERS` 现值 23 的增量 |
| **R-10** | `ProvisioningBoundaryGateTest` / `规划` 的账目计数漂移（38 vs 39；42 vs 39+3） | §6.4 判据⑦ | 需确认"42"是"PROVISIONED 账"还是"全集"，并决定改哪一侧（改代码 or 改规划）；本轮硬边界禁止我改任一处 | 门禁第 6 条期望值（`规划:184`） |
| **R-11** | `E3` 的 `collected_days`（`契约:1769-1773`）是否改为读 `band_daily_coverage` | `契约:1769` `collected_days: ... 已采集天数（正向计数）`；`PRD:1861` W-2"仅'已采集 N 天'" | 契约未指定取数来源；现 `BandDerivedController.java:96` 是桩（"桩：A3 = 1.0"）。是否由 A-3 顺带收口，属范围裁定 | A-3 是否含 E3 取数改造 |

---

## 8. 本文件的自我约束与复核方法

1. **可复核**：每条结论都给了 `文件:行号`；无行号的段落已显式标注【推断】。
2. **不代拍**：§5 的 6 条、§7 的 11 条全部只给"裁定所需的机械依据"，不给结论。
3. **未越界**：本轮未执行 `mvn` / `psql` / `git`；未修改任何 `.java` / `.sql` / `.yaml` / `.json` / 既有 `.md`。唯一新增文件 = 本文件。
4. **复核顺序建议**（供裁定者用）：
   - 先裁 **R-7**（立项）—— 它决定 A-3 是否动工，其余 10 条在"不动工"前提下都不必裁；
   - 若 R-7 = 动工，再裁 **R-1 / R-2**（遍历集合）—— 它们决定 A-3c 的代码形状，且 R-1 可能触发迁移改动（117 对账硬约束）；
   - **R-9** 可并行裁（只影响 `LEDGER_CARRIERS` 增量，无阻塞）；
   - **R-10** 属文档卫生，可并入下一批文档汇总回改。
5. **本文件的失效条件**：`契约` / `字典` / `冻结件` 任一方被修改，或 R-1/R-2 被裁定后，§2/§4/§5 的对应行号与结论须重跑一遍。
