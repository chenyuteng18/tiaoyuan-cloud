# GTL1 · M-WX-FG 协议层可行性测试报告（开工前技术验证）

**日期**：2026-09-19
**类型**：技术验证 / 开工前测试
**参与成员**：方向明（主理人，编排 + 直接执行静态验证）
**测试范围**：**纯协议层 / 静态可测性**——不含真机 BLE 联调
**触发指令**：业务方「先测试，测试完我就要马上开始开发了」

---

## 📌 TL;DR（执行摘要）

- **原型回归**：119 项自检 **全通过、0 回归**，D1 手环四端可见性 + 汇聚式数据流断言稳。
- **重大更正①**：此前记录的「SDK 包内 6 文件已还原 ~22KB 源码」**为误判**——`_uniapp_sdk_files.json` 的 `text` 字段是**压缩态原始字节**，非解压源码（源码关键词命中 = 0）。SDK 源码静态审计**目前做不到**，需厂商交付或真机拿包。
- **重大更正②**：厂商 SDK 支持矩阵 = **iOS / Android / uniapp 三平台，无「小程序」**（复证，非新增风险）。**mp-weixin 运行时能否跑通仍是生死项，必须真机实测。**
- **重大利好**：文档新发现 `sdk.getValidHistoryDates(historyType)` —— **设备端直接返回实际存在的有效日期数组**。这一条**把 N（留存窗口）从"必须问厂商"降级为"运行时可实测"**，是补拉设计的关键钥匙。
- **结论**：能静态测的全部测完，**唯一未闭环的是"mp-weixin 运行时可行性"**（需真机）。其余（协议一致性、接口签名、游标、幂等键依赖）均已取证。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| 静态可测项 | 5 组（原型回归 / 包解析 / API 清单 / 签名一致性 / 平台矩阵） |
| 通过 | 4 组通过，1 组**判为不可测**（SDK 源码） |
| 唯一未闭环 | `uniapp → mp-weixin` 运行时可行性（**须真机 BLE 实测**） |
| 关键收益 | `getValidHistoryDates` 解锁 N 实测，问询 #19 可降级 |
| 风险等级 | 中（生死项集中且单一，可一枪打掉） |
| 对开工影响 | **第 1 批（后端 + 契约）不受阻，可立即开工** |

---

## 一、测试项逐一结论

### T1 · 原型自检回归 ✅ 通过

- 命令：`node deliverables/product-strategy/prototype/_proto_verify.js`
- 结果：**119 通过 / 0 失败（合计 119）**，退出码 0。
- 覆盖：端形态矩阵（7 角色）、退款可见性一致性、三条跨端硬约束、config #28 冲突清除、D2 旧设计残留清除、结构完整性、**D1 手环四端可见性（含运行时六角色真跑 P.detail()）**。
- **判定**：原型作为回归基线可用，本轮 M-WX-FG 落盘未引入任何回归。

### T2 · SDK 包解析 ✅ 通过（结论为"不可静态审计"）

| 项 | 结果 |
|----|------|
| 归档 | `_work/_uniapp-sdk.rar`，23699 bytes，RAR5（magic `526172211a070100`）|
| 条目 | 7 个（6 实 + 1 空），`how` 全为 `raw`（压缩方法非 store）|
| `text` 字段性质 | **压缩态原始字节**，可读 ASCII 串中源码关键词命中 = **0** |
| 原生二进制 | **0 个**（无 .aar/.framework/.so/.jar）——*此结论基于文件名清单，仍有效* |

> ⚠️ **更正记录**：此前会话中把该 JSON 的 `text` 当"已还原的 6 文件源码 (~22KB)"用于推理。本轮实测证伪：`getVersion`/`isWear`/`function`/`uni.` 等关键词在 `text` 中命中数为 0，内容为二进制噪声。**SDK 源码静态审计路径作废**，改由厂商文档 + 真机包双轨取证。

### T3 · 厂商 API 清单核验 ✅ 通过

数据源：`_work/_doc_uniapp.html` → 净化为纯文本 42063 字（`_doc_uniapp.txt`）。

| 指标 | 数量 |
|------|------|
| `sdk.*` 方法总数 | **94** |
| `NotifyType` 枚举项 | **81** |
| 平台支持矩阵 | iOS / Android / **uniapp**（索引文档明示，**无「小程序」**）|

**13 条逐日历史接口核验**（补拉核心）：

| 接口 | 存在 | 入参 | 备注 |
|------|------|------|------|
| `getStepHistory` | ✅ | `{year,month,day}` | 返回 `interval`+`year/month/day`+数组 |
| `getHeartRateHistory` | ✅ | 同上 | |
| `getBloodPressureHistory` | ✅ | 同上 | |
| `getBloodOxygenHistory` | ✅ | 同上 | |
| `getPressureHistory` | ✅ | 同上 | |
| `getMetHistory` | ✅ | 同上 | |
| `getTempHistory` | ✅ | 同上 | |
| `getMaiHistory` | ✅ | 同上 | |
| `getSleepHistory` | ✅ | 同上 | |
| `getRespirationRateHistory` | ✅ | 同上 | |
| `getExerciseHistory` | ✅ | 同上 | 中高强度 + 站立次数 |
| `getBloodSugarHistory` | ✅ | 同上 | |
| `getSportHistory` | ✅ | ⚠️ **`(false)` 单一 boolean** | **不吃 `{year,month,day}`** |

**关键 API 核验**：

| 项 | 结果 |
|----|------|
| `getVersion()` | ✅ 返回 `bufferSize: 4000`（设备缓冲 4000 ）、`version`、`model`、`lcdWidth/Height` |
| `isWear` | ✅ 存在于 `getHealthDetail` 返回值中（number）|
| `getX04HealthIntervals()` | ✅ 存在，返回 `healthIntervalsData[]`（测量间隔 / 存储间隔，双向 GTS10）|
| `interval` 字段 | ✅ 历史接口返回体含 `interval`（采样间隔）|
| **`getValidHistoryDates`** | ✅ **`(historyType: HistoryType)` → `validHistoryDates[]`** |

### T4 · 签名一致性核验 ⚠️ 发现 1 处偏差

- **偏差 D-A**：`sdk.getSportHistory(false)` —— 入参为**单 boolean**（非 `{year,month,day}`），一次取一条，靠返回体 **`sportLength > 1` 作游标**继续拉取。
  → 与其余 12 条"按日拉取"不同构。**补拉的幂等键与断点续传逻辑需为运动数据单开一条分支**（游标型 vs 按日型）。
- 其余 12 条入参 `{year,month,day}` **完全一致**，与 PRD §2.8.7③ 设计相符。

### T5 · 平台支持矩阵核验 🔴 未闭环（生死项）

- 厂商索引文档明示支持：**iOS / Android / uniapp**。
- 全文 **`小程序` 命中 0、`mp-weixin` 命中 0**（复证）。
- 含义：**厂商未声明小程序支持**。`uniapp → mp-weixin` 属社区通用跨端路径，但厂商 SDK 是否调用了小程序运行时缺失的 API（如 `plus.*`、Node 原生模块、`device` 权限等）**文档无法回答**。
- **判定**：**必须真机 BLE 实测**，且是在**微信小程序真机环境**下（非 H5 预览、非开发者工具模拟）。

---

## 二、测试边界（诚实声明）

| 已测（可信） | 未测（不可宣称） |
|--------------|------------------|
| 原型 119 项回归 | mp-weixin 真机能否识别 BLE 设备 |
| SDK 包无原生二进制 | `uni.onBLECharacteristicValueChange` 在小程序真机是否触发 |
| 94 方法 / 81 枚举清单 | 广播名规则、连接参数、MTU 协商 |
| 13 条历史接口签名 | 实际留存窗口 N（**改为运行时用 `getValidHistoryDates` 实测**）|
| `bufferSize`=4000 | 单次同步成功率 `s`、观测率 `f` |
| 平台矩阵 = iOS/Android/uniapp | 厂商 SDK 是否可在小程序环境 require |

> 本报告所有结论均限于**静态 / 协议层**。任何"能跑通"的推断**不得**替代真机实测结论。

---

## 三、对既有文档的影响（建议，未落 PRD）

### 3.1 问询清单 #19（N 留存窗口）→ 建议降级为 P2

- **原状**：#19「N 留存窗口」列为 P0，含 6 个 sub-questions（V1~V6），因为 N 是补拉范围与 `f*` 判定的前置。
- **测试后**：`getValidHistoryDates` 让 **N 可在运行时直接枚举实测**，无需等厂商书面回复。
- **建议**：
  - 保留 #19 但**降为 P2**（仅用于"厂商为何值时不可探测"的兜底）；
  - 新增一条 P0 工程要求：**App/小程序启动时先调 `getValidHistoryDates` 枚举可用日期**，以实测 N 反哺 A3 模型；
  - 补拉起始日公式 `max(last_synced+1, today−N)` 中的 N 改为**运行时探测值**，不再硬编码。

### 3.2 补拉设计 → 需为运动数据单开"游标型"分支

- 12 条按日型：入参 `{year,month,day}`，幂等键 `(device_id, metric, date, hour, minute)` upsert。
- 1 条游标型（`getSportHistory`）：入参 boolean，循环条件 `sportLength > 1`，幂等键需改用 **`(device_id, 'sport', currentSportId)`** 或返回体自带的时间戳。

### 3.3 生死项收敛为"一枪" → 建议立即排真机验证

- 前置：拿到厂商 SDK 真包（`libs/` 投放）+ 一台安卓真机 + 微信小程序体验版。
- 最小验证：配对 → `getVersion()` → 一次 `getStepHistory({今日})` → 一次 `getValidHistoryDates`。
- **任一步不通，M-WX-FG 需回退到 M-APP**（config #44 已保留该选项，回退成本可控）。

---

## 四、对"马上开工"的影响判定

| 批次 | 内容 | 是否受本轮测试阻塞 | 结论 |
|------|------|--------------------|------|
| **第 1 批** | 后端 22 实体 / P0-15 多租户底座 / **三端契约冻结（T-6）** / P0-17 题库引擎骨架 | ❌ 不受阻 | **可立即开工** |
| **第 2 批** | P0-19 改善率引擎 / P0-12 判定引擎 / P0-14 退款门禁 | ⚠️ 受裁定单签字阻塞（Q10/Q11/Q17）| 等签字 |
| **第 3 批** | M-WX-FG 手环端到端 | 🔴 受真机实测阻塞 | 真机验证通过后开工 |

> **要点**：**生死项单一且不含糊**——真机一测即知。第 1 批（占比最大、跨端契约是后续一切的地基）**今天即可动工**。

---

## 五、行动清单

| # | 行动 | 负责方 | 时间窗 |
|---|------|--------|--------|
| 1 | 立即启动第 1 批开发（见派工单） | 工程 | 今天 |
| 2 | 向厂商索取 SDK 真包（非 RAR 占位）+ 小程序支持书面确认 | 采购/对接 | 今天发函 |
| 3 | 排真机验证窗口（安卓真机 + 小程序体验版） | 工程 | 本周内 |
| 4 | 真机验证脚本按 §3.3 最小 4 步编写 | 工程 | 与 #3 同步 |
| 5 | 把 `getValidHistoryDates` 写入后端探测接口契约 | 工程 | 第 1 批内 |
| 6 | 裁定单签字催促（Q10/Q11/Q17） | 产品 | 本周内 |
| 7 | 问询 #19 降级 + 新增运行时探测要求（改 PRD v1.26） | 产品 | 与签字同步 |

---

## ⚠️ 待确认 / 假设 / Non-goals

- **待确认**：厂商是否愿意为小程序出书面支持承诺（大概率不会——文档已明确只有三端）。
- **假设**：`uniapp` SDK 编译到 mp-weixin 的产物在真机可运行（**未验证**）。
- **假设**：`getValidHistoryDates` 对全部 `HistoryType` 生效（文档只给了单个返回示例）。
- **Non-goals**：本轮**不做**真机 BLE 联调、不做 SDK 源码级审计、不做 H5 预览可行性判定。

---

## 📚 数据来源 & 证据索引

| 证据 | 路径 | 说明 |
|------|------|------|
| 原型自检结果 | `prototype/_proto_verify_result.txt` | 119 PASS / 0 FAIL |
| 原型自检脚本 | `prototype/_proto_verify.js` | Node 执行，真跑 P.detail() |
| 厂商文档（纯净文本） | `_doc_uniapp.txt` | 42063 字，来源 `_work/_doc_uniapp.html` |
| SDK 包解析结论 | `_work/_uniapp_sdk_files.json` | `text` = 压缩态字节（非源码）|
| 接口上下文摘录 | `_sdk_api_ctx.txt` | 13 历史接口 + getVersion/isWear 原文 |
| RAR 归档 | `_work/_uniapp-sdk.rar` | RAR5，23699 bytes |

---

> 本报告由产品战略团队 AI 协作生成，**技术结论以真机实测为准**，重要决策请由产品负责人审定。