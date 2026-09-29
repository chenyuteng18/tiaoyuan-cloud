# CHANGELOG —— 三端接口契约

格式：`[版本] YYYY-MM-DD · 级别 · 变更人 · 上游依据`
**级别定义**见 `README.md` §五。任何 MAJOR 变更必须**重走 D3 式冻结闸门**。

---

## [api-contract-v1.0.0] 2026-09-24 · **首次冻结（初始基线）**

**依据**：`_work/contract-t6-api-freeze-2026-09-19.md`（上游权威语义母本）
**动作**：把上游 §2（43 行 / 45 端点）、§3（可见性矩阵）、§4（手环专章）、§5（措辞纪律）
机械转录为 `openapi-v1.0.0.yaml`，**并入** 2026-09-23 R1 裁定（`gap_reason` 5 值 → 7 值）
与域 I 新增错误码 `2004`。

### 冻结面
| 域 | 行数 | 端点 | 备注 |
|---|---|---|---|
| A 租户与权限 | 3 | 3 | `/auth/login` `/auth/me` `/stores` |
| B 客户与档案 | 6 | 6 | 含 `intake-profile`（★实体） |
| C 评估与题库 | 4 | 4 | 题库 + 基线 + 详情 + 周期 |
| D 服务与履约 | 6 | **8** | D5 一行含 3 端点 |
| E 设备与手环 | 6 | 6 | 含 E5 探测接口（**S1-7 并入**） |
| F 判定与稽核 | 4 | 4 | 含 F4 可判定覆盖率 |
| G 退款与挽留 | 5 | 5 | **客户 + 调理师一律 403** |
| H 文书与电子签 | 1 | 1 | 🛑 占位待冻结（仅形态） |
| I 文书模板与渲染 | 8 | 8 | 🛑 占位待冻结（仅形态） |
| **合计** | **43** | **45** | |

### 本版并入的裁定 / 修订
1. **R1 裁定（2026-09-23）**：内部 `gap_reason` 采用 **7 值**；拼写统一为 `compliant_removal`（`-ce` → `-t`）；
   `device_unbound` **退役**，并入 `involuntary_technical`；新增 `involuntary_technical` /
   `beyond_retention_window` / `unknown`。
   - **影响面**：仅**枚举取值集**，**不改任何可见性档位**。
   - 因本件此时仍为**冻结候选**（未打 tag 生效），该修订**并入首次冻结基线**。
2. **域 I 新增错误码 `2004 PLACEHOLDER_OUT_OF_SCOPE`**（HTTP 403）：模板含白名单外占位符时拒绝渲染。
3. **S1-7 探测接口并入**：`POST /band/available-dates`（E5）+ `GET /customers/{id}/band/sync-status`（E6）
   随本版一并冻结 —— 消除「契约要含它、它却等契约」的环路。
4. **冲突点消歧（不改档位值）**：
   - **C-1**：手环接入状态归入 **② 采集状态**（不单列字段组）。
   - **C-2**：「服务调整类文案」归属 config **#40**（退款域），不属 **#43**（手环域）。两配置独立。
   - **C-3**：客户端 `client_sync_state`（4 值）与内部 `gap_reason`（7 值）为**两套独立枚举**。

### 已知的诚实边界（TBD 占位，**不得填数**）
| 位置 | 字段 | 说明 |
|---|---|---|
| `BandAvailableDatesData.retention_window_days`（E5） | `oneOf [integer, "TBD"]` | 留存窗口 N 未定（依赖 A6 厂商可用性） |
| `AuditCoverageData.a3_observability`（F4） | 含 `TBD` | A3 可观测性未定 |

### 未纳入本版（登记，非遗漏）
- **电子签厂商字段名 / 载荷结构 / 签名算法**：待厂商选定后另起 MINOR。
- **业务判定逻辑（P0-12 公式）与改善率引擎**：明确排除在冻结范围外（属实现，非接口）。

### 验证
- `contract/sdk-generator/generate.sh --dry-run` → 通过（43 行 / 45 端点 / 禁入行一致 / TBD 边界在位）。
- `ContractFreezeGateTest`（dy-app，10 用例）→ **全绿**；含 7 条单源扰动反向验证。
  skeleton 全量 **312/312 绿**（dy-app 105 → 115）。

### 转录期发现的表示问题（登记，非契约缺陷）
| # | 现象 | 处置 |
|---|---|---|
| P-1 | 上游 §2.2 的 B2 行说明列含 `POST /archive`，但它**是「建档」的语义别称、不是第二个端点** | 闸门测试以 `SEMANTIC_ALIASES` **显式登记并排除**，不静默丢弃；避免日后被误当真实端点 |
| P-2 | 上游 §2.4 的 D5 行用 `GET .../plans/{id}` 省略基路径；§2.9 的 I7 带 `?version=` | 端点比对前统一归一化（`...`→`/api/v1`、去 base path、去 query）；D5 三端点**逐端点**比对，禁止合并成一行 |
| P-3 | 上游 §2.9 复用错误码 `2003`（见「已知差异」） | 契约按 §2.0 权威取值；冲突**登记**在 `KNOWN_DIFFERENCES` 并由闸门守护 |
| P-4 | 上游 §2.7 未逐项明示 G4 审批角色白名单 | 契约挂 `x-ruling-pending`，标为**推断项**并登记；不得写成上游结论 |

---

## 待办（下一版候选）

| 候选变更 | 级别（预判） | 阻塞于 |
|---|---|---|
| 域 H 字段名 / 载荷结构解冻 | MINOR | 电子签厂商选定 |
| 域 I 载荷结构解冻 | MINOR | 模板引擎选型 |
| `retention_window_days` 由 TBD 转实数 | PATCH | A6 厂商云 API 可用性核实 |
| `band_telemetry.data_source` 取值语言统一 | MINOR | 待裁定（见阻塞登记册） |
---

## [toolchain] 2026-09-27 · **工具链（不改契约）· SDK 裁剪管线落成 + 两个真实 P0 修复**

**级别**：无契约变更（PATCH 级：仅工具与文档）。契约本体 `openapi-v1.0.0.yaml` **逐字节未改**（sha256 `bd09f05d1d76…`）。

### 背景：README §四 的"本机未生成任何 SDK"已作废
上一版 §四 逐字写着"**本机未生成任何 SDK。** `contract/sdk/` 与 `contract/visibility/` 当前为**空目录**"。
该陈述在当时为真（本机无 generator 可执行件），现已不成立 —— 三端 SDK 已在本机真实生成。

### 新增
1. **`sdk-generator/_sdk_pipeline.py`** —— 裁剪管线（`cut` / `verify` / `audit` / `emit` 四模式）。
   核心是 **生成前裁剪 spec**：① 角色判据 ② 显式排除 ③ **schema 可达性（$ref 传递闭包）**。
2. **`sdk-generator/openapitools.json`** —— 把生成器版本**锁到 7.10.0**（与矩阵声明一致；不锁会下 7.14.0）。
3. **`skeleton/verification/115_sdk_pipeline_reverse_verification.py`** —— 反向验证，9 组注入（A–I），
   实测 **18/18 PASS**。

### 修复的两个真实 P0（此前未被发现，因为从未真正生成过产物）
| # | 缺陷 | 硬证据 | 修法 |
|---|---|---|---|
| **P0-1** | **客户小程序包长出退款域全部调用面** | `/refunds` 系列 endpoint 出现在客户包 | 生成前裁剪 spec（角色判据 + 显式排除） |
| **P0-2** | **客户包 model 目录仍含退款域数据结构** | `RefundData.js` / `RefundCreateRequest.js` / `RefundReceiptData.js` / `VerdictData.js` / `BandDerivedData.js` / `AuditCoverageData.js` | **schema 可达性裁剪**（③）—— 只裁 `paths` 挡不住：生成器为全部 32 个 schema 无条件生成 model |

修后实测：`client-mp` 产物从 **174 文件降到 102 文件**，`model 泄漏 0`。

### 修复的校验器缺陷（7 处，均为"假红"，判据本身是对的）
| 坑 | 现象 | 根因 |
|---|---|---|
| 1 | client-mp 误报 INV-2 | 路径**子串**污染（E3 命中 E2）⇒ 改 (path, method) 联合判定 |
| 2 | 同上 | 路径**前缀**污染（`/customers` 是 `/customers/{id}/visits` 的前缀）⇒ 同上 |
| 3 | client-mp 误报 INV-3（112 文件） | 生成器把契约 `info.description` 抄成**文件头注释**，而纪律条文自含"退款"字样 |
| 4 | 未检出 model 泄漏 | 只裁 `paths` 未裁 `components/schemas` ⇒ 新增 `_cut_unreachable_schemas` |
| 5 | admin-web 误报 INV-1（缺 25） | path→method 距离实测**中位 430 / 最大 521**，固定 400 字符窗口漏检 ⇒ 改**按下一个 path 切块** |
| 6 | INV-3 全线假红 | 同坑 3 ⇒ 扫描前**剥离注释**（块注释 + 行注释） |
| 7 | model 泄漏漏检 | model 文件命名**因 generator 而异**（axios=`refund-data.ts` kebab / js=`RefundData.js` Pascal）⇒ 按 generator 映射 |

### 矩阵修订（不涉契约）
- 新增 `forbid-fragment-exceptions`：**点名放行 `BandAvailableDatesDataRetentionWindowDays`**，附理由。
  `retention` 在本产品**同词异义**：G3 退款挽留（禁入） vs E5 手环数据保留窗口（`x-visible-to: [client]`，合法可见）。
  🛑 **不删 `retention`** —— 删了会永久失去对 G3 的字符串兜底；改为"点名放行、其余全拦"，
  并新增**僵尸例外检测**（例外未在任何产物里命中 ⇒ 报错）。

### 反向验证脚本自身修的三处（留档，防重犯）
1. 注入必须构造 **(path, method) 成对** 的字面量（只塞孤立 path 永远配不上 method ⇒ 注入"生效"实则无效）；
2. 注入点必须在**块注释结束之后**（文件头是抄自 `info.description` 的 `/** ... */`）；
3. 就地注入 → 逐字节还原在本机**不可靠**（沙箱按 turn 累计删除配额，拦截对子进程亦生效 ⇒ 伪文件残留 ⇒ 下次基线变红）
   ⇒ 改为**在系统临时目录的副本上注入**，并以**逐文件哈希比对**自证真实树零写入。

### 影响
- 三端 SDK 产物真实入仓：`contract/sdk/{client-mp,therapist-app,admin-web}`。
- `audit` 实测口径：client **15 保留 / 30 裁掉 / 18 schema 裁掉**；therapist **29 / 16 / 7**；admin **39 / 6 / 5**。
- `generate.sh --dry-run` 6 步仍全绿（未改动）。
