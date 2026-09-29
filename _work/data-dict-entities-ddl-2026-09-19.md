# 上医智养堂 · 后端实体数据字典与 DDL 规格（P0-15 多租户底座 · 开工前冻结件）

| 项目 | 内容 |
|---|---|
| 文档类型 | 数据字典 / DDL 规格（Data Dictionary & Schema Spec） |
| 日期 | **2026-09-19**（**2026-09-22 BD-3 回写**：F-2/F-3/F-4 三项建模裁定落盘，见 §2.25 / §2.26 / §2.17【待评审条目 F-2】） |
| 出具 | 数析（数据分析师）· 任务：开工前「后端实体」数据字典（team-lead 派工时原写"20/21"，**本件已实测纠正为 22，见 §一 计数纠正**） |
| 用途 | **开发可直接照此建表 / 写迁移脚本**（第 1 批：后端实体 + 多租户底座 + 三端契约，不受真机 BLE 阻塞） |
| 输入（绝对路径） | `prd-health-mgmt-saas-2026-09-16.md` v1.25（§2.2 / §2.6 / §2.8.7 / **§8** / §10 / 附录 C.1 / C.8 / config #40·#43·#44）· `gtl1-integration-brief-2026-09-18.md`（§6.3 / §8.6 / §8.7）· `_work/gtl1-data-spec-2026-09-18.md` · `_work/gtl1-wx-open-sync-metric-2026-09-19.md` · `_work/gtl1-wx-open-sync-sdk-proto-test-2026-09-19.md` |
| 前置纪律 | **只做数据规范，不做业务策略判断**；未取证指标（N / s / f）一律 **TBD** 占位，不得编造数字 |

---

## 📌 TL;DR（6 行）

1. **实体总数已统一为 28（2026-09-20 回写 · BD-2）**：PRD **§8 实际列 22 个实体**（非"20 / 21"，**其中已含 `band_telemetry`**）；另 **附录 C.1 有 3 个 ★ 实体**（`intake_profile` / `scale_item_bank` / **`doc_template`**，后者为 P0-27、PRD v1.28 新增）→ **建表口径 = 22（§8 正文，含 `band_telemetry`）+ 3（★ 增量）+ 3（手环补拉引擎**新增**三表 `band_sync_probe`/`band_sync_log`/`band_daily_coverage`）= 28**。**依据 `ADR 十五 F-1`「以 PRD §8 为唯一权威 = 28」与 `PRD §8` 自述「凡其他文档引用实体数，一律以本节为唯一权威」。本字典原写"24 个候选实体"系漏同步 `doc_template`，现更正为 28。** 逐一对齐表见 §一。
   > **🛑 成员名单更正（2026-09-22 · BD-3 核对）**：原写"+3（手环表 `band_telemetry`/`band_sync_probe`/`band_daily_coverage`）"**成员列举有误** —— `band_telemetry` **本身已在 §8 的 22 之内**（见 §一 对齐表第 17 行，标注所属 = `§8`），**不是增量**；被漏列的应是 **`band_sync_log`**（§四 ★ 新增表 2）。**28 这个总数不变、算式不变**（22 + 3 + 3 = 28），**仅更正加数名单**。**改动基数须走变更流程，非本校正。**
2. **隔离根 = `tenant_id`**：全部业务实体带 `tenant_id`（存储层行级 scope 拒绝跨租户）；`region` 仅授权无业务数据；**`customer` 归 tenant 不归 store**（owner_store / serving_store 分离）。
3. **不可覆盖（append-only / 版本递增）四类**：`plan`（版本递增）、`baseline_assessment`（锁定只读）、`verdict` + `cycle_assessment` + `refund`（`evidence_snapshot` + `threshold_version` 落库可回放）、`agreement.refund_clause_snapshot`（快照存储）、`scale_item_bank` / `scale`（版本化）。
4. **手环双台账不合并**：`device`（门店级调理设备 1:N，下行，可追责）≠ `band_telemetry`（客户级穿戴 1:1，上行，缺失不得作不利依据）；`band_telemetry` 幂等 upsert 键 = `(device_id, metric, date, hour, minute)`，运动数据单开游标型 `(device_id,'sport',currentSportId)`；**`coverage_flag=null` 表缺失、严禁补 0**；**`synced_at` 必填**。
5. **两条硬披露口径**：`band_telemetry.gap_reason` 仅门店 / 管理端可见（客户端 403）；派生结论（A3 / `AS` / `effect_verdict` / 退款资格）客户端一律 403；**手环数据是客户级、不做门店锁定**。
6. **【2026-09-22 回写 · BD-3】** 三项建模裁定已落盘（依据 `ADR 十五 F-2/F-3/F-4`，**不新开口径**）：**① F-4** → **§2.25 `customer_state_transition`**（14 态状态机实体字段级定义 + 与 `customer.status` 5 值显式映射 + G1/G2 门禁守卫）；`customer.status` **保持 5 值不动**。**② F-3** → **§2.26 `band`**（客户级手环实体，闭合 `band_telemetry` / `band_sync_probe` / `band_daily_coverage` **三表的 `device_id` 悬空 FK**；**与 `device` 两本台账不得合并**）。**③ F-2** → **§2.17【已定案 · F-2】**（两方案对比 + 判据 J1–J5；**2026-09-23 技术负责人按 J3 拍定 = 方案 A 长表**）。**两新实体均属待评审增项，不并入封版实体基数。**

---

## 🎯 核心结论卡片

| 项 | 结论 |
|---|---|
| 实体口径 | **§8 = 22 个**；**派工单"20 / 21"有出入**；含附录 C ★ 增量 = **24 个候选** |
| 主键策略 | 全部实体用 **字符串业务 ID（PK）**（`*_id`）+ `tenant_id` 复合行级 scope；建议 PK `(tenant_id, *_id)` 或全局 ID + `tenant_id` 索引 |
| 隔离策略 | `tenant_id` 全表必带；**存储层拒绝跨租户**（M0 验收：A 租户查 B 租户 403 + 审计日志） |
| 幂等策略 | `band_telemetry` 按 `(device_id, metric, date, hour, minute)` upsert（服务端权威）；运动游标型单开分支 |
| 手环四态 | `sync_state ∈ {syncing, synced, sync_failed, no_data}` + `coverage_flag` + `synced_at` + `gap_reason` |
| 版本不可覆盖 | `plan` / `plan_review` / `baseline_assessment` / `verdict` / `agreement` / `scale` / `scale_item_bank` |
| TBD 占位 | **N（留存窗口）、s（单次同步成功率）、f（观测率）、f\*（临界观测率）** —— 均未取证，字段预留、值 TBD |
| 需新增（带 ★） | `band_sync_probe` / `band_daily_coverage` / `band_sync_log`（手环补拉引擎落库，**超出 §8 22 实体 → 待评审增项**） |

---

## 一、实体总览表（逐一对齐 §8，标注出入）

> **计数纠正（硬性）**：派工单写「20 实体（实际 21 个）」，但 **PRD §8 原文逐行清点 = 22 个实体**。下表按 §8 原文逐一对齐，**行数 = 22**；并列出 **附录 C.1 的 2 个 ★ 实体**（未入 §8，属待裁定增量）。**"20 / 21"两个数字均与原文不符，以本节 22 为准。**

| # | 实体 | 所在 | 层级 | 带 `tenant_id` | 可覆盖性 | 备注 / 出处 |
|---|---|---|---|---|---|---|
| 1 | `tenant` | §8 | 隔离根 | ✅ | 普通 | 隔离根节点；跨租户查询存储层拒绝 |
| 2 | `region` | §8 | 区域 | ✅ | 普通 | **不产生业务数据**，仅汇总 / 督导授权 |
| 3 | `store` | §8 | 门店 | ✅ | 普通 | 业务发生的唯一场所；`franchise_type` 影响可见范围 |
| 4 | `staff` | §8 | 门店/人 | ✅ | 普通 | `role` 决定权限；审核人 / 出方案人可分离 |
| 5 | `device` | §8 | **门店级** | ✅ | 普通 | 调理设备台账（1 门店 : N 台，下行，可追责） |
| 6 | `customer` | §8 | **客户级** | ✅ | 修订留痕不可覆盖 | 归 tenant 不归 store；owner / serving 分离（U1） |
| 7 | `screening_record` | §8 | 客户级 | ✅ | **结论不可删除** | 硬门禁①；命中禁忌 → `REJECTED` |
| 8 | `consent` | §8 | 客户级 | ✅ | 附录声明 | 含 `band_willingness`；拒戴不得降级服务 |
| 9 | `agreement` | §8 | 客户级 | ✅ | **`refund_clause_snapshot` 快照** | 未签阻断执行 |
| 10 | `scale` ★ | §8 | 资产 | ✅ | **版本化** | 量表独立实体；单库 / 双库可配置（config #39） |
| 11 | `baseline_assessment` | §8 | 客户级 | ✅ | **锁定只读** | 全周期参照系；`scale_id` FK |
| 12 | `plan` | §8 | 客户级 | ✅ | **版本不可覆盖** | 变更 → 新版本 + 强制重签 |
| 13 | `plan_review` | §8 | 客户级 | ✅ | 追加留痕 | 退回必填 reason；同人自助通过需二次确认 |
| 14 | `device_dispatch` | §8 | 门店级 | ✅ | 参数快照 | 失败记 `device_push_failed` 可追责 |
| 15 | `visit` | §8 | 客户级 | ✅ | 追加留痕 | 四道闸门校验落库；核销入客户维度全局账本（U2） |
| 16 | `daily_report` | §8 | 客户级 | ✅ | 补填窗口内可改 | 代核须标 `source` |
| 17 | `band_telemetry` | §8 | **客户级** | ✅ | **幂等 upsert** | 穿戴设备（1 客户 : 1 手环）；缺失不补 0 |
| 18 | `cycle_assessment` | §8 | 客户级 | ✅ | **判定依据落库** | 应填天 < 7 标"样本不足" |
| 19 | `verdict` | §8 | 客户级 | ✅ | **依据 + 阈值版本落库** | 对内判定建议 + 置信度，不对外直出 |
| 20 | `refund` | §8 | 客户级 | ✅ | 追加留痕 | 原因码必填；未记原因不可结案 |
| 21 | `retention` | §8 | 客户级 | ✅ | 追加留痕 | 挽留记录必填；入口 B 不经挽留 |
| 22 | `case_archive` | §8 | 客户级 | ✅ | 归档只读 | 脱敏须单独授权；归档须客户签字 |
| ★ | `intake_profile` | **附录 C.1** | 客户级 | ✅ | 补充 + 修订留痕 | **未入 §8**；建档扩展实体（待裁定） |
| ★ | `scale_item_bank` | **附录 C.1** | 资产 | ✅ | **版本化不可覆盖** | **未入 §8**；题库内容资产 · **2026-09-23 已定案并落库（`V4` 迁移）** |
| ★ | `doc_template` | **附录 C.4（P0-27）** | 资产 | ✅ | 全文可编辑 + 变更留痕 | **未入 §8**；**2026-09-20 补录（BD-2）** —— PRD v1.28 新增 P0-27「文书/协议文本模板管理与后台编辑」（业务方 2026-09-19 裁定「管理员后台可全文修改」），挂 `config #46`。**§2.27 已补字段级 DDL（2026-09-23）** |

> **出入登记**：
> - **出入-1（计数）**：派工单"20 / 21"≠ §8 原文 **22**。本字典按 **22** 建表。
> - **出入-2（漏列）**：附录 C.1 / C.1.8 有 **`intake_profile`、`scale_item_bank`**，附录 C.4（P0-27）另有 **`doc_template`**，共 **3 个 ★ 实体**，**均未进 §8 实体表**。**2026-09-20 更正（BD-2）**：依 `ADR 十五 F-1`「以 PRD §8 为唯一权威 = 28」，★ 3 个 + 手环补拉引擎新增 3 表全部纳入 → 实体数 **28**（原写"24"系漏计 `doc_template`）。**建表口径以 28 为准。** **〔2026-09-22 BD-3 核对〕** 此处的"手环 3 表" = **`band_sync_probe` / `band_sync_log` / `band_daily_coverage`（§四 ★ 新增三表）**，**不含 `band_telemetry`**（后者已在 §8 的 22 内）。
> - **出入-3（手环落库缺口）**：§8 22 实体**无任何"同步尝试 / 留存窗口探测 / 逐日覆盖"落库位**，而 §2.8.7③ 补拉引擎强制要求 `date_done` / `pending_dates` / 同步四态 / `gap_reason` 落库 → **本字典提出 3 个 ★ 新增表**（§四），**属待评审增项，不回改封版基数**。
> - **出入-4（BD-3 增项 · 2026-09-22）**：按 `ADR 十五 F-3/F-4` 本轮回写**新增 2 个 ★ 实体** —— **§2.26 `band`**（客户级手环实体，闭合 `band_telemetry` / `band_sync_probe` / `band_daily_coverage` **三表的 `device_id` 悬空 FK**）+ **§2.25 `customer_state_transition`**（14 态状态机实体，承载状态历史 + 门禁守卫）。**二者同属待评审增项 / 待裁定增量，不并入封版实体基数**（封版五值不动）；**`customer.status` 5 值保持不动**。

---

## 二、逐实体字段字典（字段级）

> **通用约定**（所有表默认，下述各表不再重复）：
> - **主键**：`*_id`（业务字符串 ID，或 `bigserial` 代理键 + 唯一业务键）；**行级 scope 键 = `tenant_id`**。
> - **审计字段（全表必带）**：`tenant_id`(FK) / `created_at` timestamptz NOT NULL DEFAULT now() / `updated_at` timestamptz / `created_by`（操作人 `operator_id`）。
> - **逻辑删除**：业务上"不可删除"的表用 `deleted_at` NULL 语义（物理禁删，仅软删留痕）；`screening_record` / `visit` / `refund` / `case_archive` 禁物理删。
> - **时间**：一律 `timestamptz`；手环原始数据另存设备本地时区偏移（见 §四）。
> - **枚举**：以 `text + CHECK` 或 DB enum 落库；**取值必须逐项落库、不得只存中文标签**。
> - **类型标注**：`string` / `int` / `bigint` / `decimal(p,s)` / `bool` / `date` / `timestamptz` / `jsonb` / `text`。

### 2.1 `tenant`（隔离根）

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| tenant_id | string | 否 | — | **PK** | — |
| brand_name | string | 否 | — | UNIQUE(tenant_id) 语义 | — |
| status | string | 否 | 'active' | CHECK | active / suspended / closed |
| created_at | timestamptz | 否 | now() | IDX | — |

- **隔离策略**：**隔离根**。本表**不带** `tenant_id` 自引（自身即根）；其余所有实体 `tenant_id` → FK(tenant)。
- 约束：跨租户查询**存储层直接拒绝**（M0 验收）。

### 2.2 `region`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| region_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| name | string | 否 | — | — | — |
| supervisor_id | string | 是 | NULL | FK staff（督导） | — |

- **隔离策略**：带 `tenant_id`；行级 scope = tenant 全集（区域督导看辖区）。
- **不产生业务数据**（仅授权 / 汇总）。**不承载退款等业务字段**。

### 2.3 `store`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| store_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| region_id | string | 是 | NULL | FK region；IDX | — |
| name | string | 否 | — | — | — |
| **franchise_type** | string | 否 | — | CHECK；IDX | **直营 / 加盟** |
| device_model | string | 是 | NULL | — | 杠2 / 现有（门店级属性，门店无权改） |

- **隔离策略**：带 `tenant_id`；门店角色行级 scope = 本店（`tenant_id + store_id` 双过滤）。
- `franchise_type` **影响可见范围**（加盟商稽核类指标不可见）。

### 2.4 `staff`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| staff_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| store_id | string | 是 | NULL | FK store；IDX | 客服**无系统账号 → 可空且永不建行** |
| **role** | string | 否 | — | CHECK；IDX | **店长 / 调理师 / 经络师 / 客服** |
| status | string | 否 | 'active' | CHECK | active / resigned / suspended |

- **隔离策略**：带 `tenant_id`；按 `store_id` 行级收窄；区域/总部角色越级读。
- **role 决定权限**；审核人 / 出方案人**可分离**（Q1 待裁定，字段需支持 `reviewer_id ≠ issuer_id`）。
- ⚠️ **门店客服无端、无系统账号** → `staff` 表实际**不产生客服行**（业务裁定 2026-09-16）。`role` 枚举含"客服"仅为枚举完整性保留、**与"不建行"不矛盾**。

### 2.5 `device`（门店级调理设备台账）

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| device_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| store_id | string | 否 | — | FK store；IDX | 1 门店 : N 台 |
| model | string | 否 | — | CHECK | 杠2 / 现有 |
| param_template_id | string | 是 | NULL | — | TPL-G2-V3 / TPL-STD-V2 |
| status | string | 否 | 'active' | CHECK | active / maintenance / retired |

- **隔离策略**：带 `tenant_id`；行级 scope 随门店。
- 🛑 **本表 = 门店级调理设备（下行，参数下发）**，**与 `band_telemetry`（客户级穿戴，上行）是两本台账，不得合并**（brief §6.3）。**型号是门店级属性，门店无权改**。
- 本表**不承载**手环数据；**不得**用本表 `device_id` 表示客户手环。

### 2.6 `customer`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| customer_id | string | 否 | — | **PK** | 全品牌唯一 |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| **owner_store_id** | string | 否 | — | FK store；IDX | 归属 / 首诊店（锁定档案） |
| **serving_store_id** | string | 是 | NULL | FK store；IDX | 当前服务店（跨店迁移） |
| name | string | 否 | — | IDX | — |
| phone | string | 否 | — | **UNIQUE(tenant_id, phone)** | 跨店识别键 |
| status | string | 否 | — | CHECK | CREATED / PROFILED / CONSENTED / REJECTED / ARCHIVED（含 `REJECTED`）。🛑 **本字段 = 5 值"粗粒度态"，不是服务主状态机**；14 态状态机与跃迁历史见 **§2.25 `customer_state_transition`（BD-3 · F-4 落盘）**，二者映射表见 §2.25 |
| gender | string | 否 | — | CHECK | 男 / 女 |
| age | int | 否 | — | CHECK 0<age<120 | 建档快照 |
| profile_created_at | timestamptz | 否 | now() | IDX | 建档日期 |

- **隔离策略**：带 `tenant_id`；**归属 tenant 不归属 store**。owner / serving 分离；他店**只读共享 + 补充修订留痕，不可覆盖**（U1）。
- **客户级属性表**：手环等随客户跨店移动。
- 🛑 **`status` 5 值 ≠ 服务主状态机**：PRD §7.2 定义 **14 态（11 活跃 + 3 终态）**服务主状态机；本表 `status` 仅为 5 值粗粒度态。**状态机实体 = §2.25 `customer_state_transition`**（承载 14 态当前态 + 跃迁历史 + 门禁守卫），二者**显式映射**见 §2.25。**依据：`ADR 十五 F-4`「骨架只在 `customer` 表留 `status` 5 值 + 注释指向 14 态状态机实体（待建）」+ 本轮回写（BD-3 · F-4）。**

### 2.7 `screening_record`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| screening_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| items_json | jsonb | 否 | — | — | 禁忌项结构化（pregnancy / acute / risk_history / nonmedical_disclosed…） |
| **result** | string | 否 | — | CHECK；IDX | **通过 / 不通过** |
| operator_id | string | 否 | — | FK staff；IDX | 操作人 |
| submitted_at | timestamptz | 否 | now() | IDX | — |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- **结论不可删除**（禁物理删；软删仅留痕审计）。**不通过 → `customer.status=REJECTED`**（硬门禁①）。
- ⚠️ **条数不一致待裁定**：config #8 为"9 项 + 其他"，01 表 §六仅 4 项 → `items_json` 字段集**待业务统一**（附录 C.6.b①）。

### 2.8 `consent`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| consent_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| auth_scope_json | jsonb | 否 | — | collect_basic / generate_advice / service_record / rights_ack | 分项勾选，可单独拒绝 |
| **band_willingness** | string | 否 | — | CHECK；IDX | **自愿佩戴 / 暂不佩戴**（决定 A3 `applicable`） |
| signed_at | timestamptz | 否 | — | IDX | — |
| evidence_hash | string | 否 | — | — | 证据链哈希 |
| data_source | string | 否 | 'self-report' | CHECK | self-report / device |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- **拒戴不得降级服务**（CHECK 层不强制，业务层保证）。`band_willingness` 变更**只能单向放宽扣分、不能收紧**（brief §5.3，意愿变更留痕 —— 建议新增 `band_willingness_revision` 追加表，**待裁定**）。

### 2.9 `agreement`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| agreement_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| plan_version | int | 否 | — | FK plan(version) | 绑定方案版本 |
| **refund_clause_snapshot** | text / jsonb | 否 | — | **快照存储·不可覆盖** | 退款 / 终止条款（对外不出现绝对化表述） |
| breach_clause_snapshot | jsonb | 否 | — | 快照 | §一~§八条款快照（可选） |
| signed_at | timestamptz | 否 | — | IDX | — |
| signer | jsonb | 否 | — | {客户/经络师/调理师/门店负责人} | 四方签署 + 日期 |
| **doc_template_id** | string | 否 | — | FK `doc_template(template_id)` | **该协议签的是哪个模板**（2026-09-23 增补，见 §2.27；依据 `doc-template-upload-capability-spec` §1.2） |
| **doc_template_version** | int | 否 | — | — | **签的是哪个版本**（与 `doc_template_id` 合起来定位唯一行） |
| **rendered_snapshot** | text | 否 | — | **快照存储 · 不可覆盖** | **占位符已带入后的签署稿正文**（或渲染后的对象存储引用） |
| **rendered_hash** | string | 否 | — | — | **渲染稿 SHA-256** —— 用于事后证明"签的就是当时那一版、且未被改过" |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- 🛑 **`refund_clause_snapshot` 必须快照存储**（条款改版不回溯已签协议）；**未签阻断执行**（硬门禁）。
- 🛑 **`rendered_snapshot` 必须快照存储**（与 `refund_clause_snapshot` 同款纪律）—— **模板改版不回溯已签协议**；`rendered_hash` 与快照**同事务写入**（事后可证"签的就是当时那一版、且未被改过"）。
- **既有字段（`agreement_id` / `tenant_id` / `customer_id` / `plan_version` / `refund_clause_snapshot` / `breach_clause_snapshot` / `signed_at` / `signer`）本批一字不改**；本批仅**增补上方 4 个字段**。

### 2.10 `scale` ★（量表独立实体）

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| scale_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| **scale_type** | string | 否 | — | CHECK；IDX | **primary / calibration** |
| **scale_version** | string | 否 | — | **版本递增·不可覆盖**；UNIQUE(scale_id, scale_version) | — |
| name | string | 否 | — | — | — |
| dimension_set_json | jsonb | 否 | — | 7 维枚举 | 体能精力/面部气色肤质/肩颈腰背筋骨/睡眠质量/记忆专注/代谢体态消化/情绪抗压与抵抗力 |
| status | string | 否 | 'active' | CHECK | active / deprecated |

- **隔离策略**：带 `tenant_id`（允许品牌自定义量表）。
- **双库严格隔离**（config #39 / ISO-1~4）：两库分数**不得相加、不得互相代入改善率分子/分母**；替换**整段替换**；判定依据快照须携 `scale_id + scale_type + scale_version` 三元组。

### 2.11 `baseline_assessment`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| assessment_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| **scale_id** | string | 否 | — | FK scale；IDX | 量表经 `scale_id` 引用 |
| metrics_json | jsonb | 否 | — | 维度分 / 总分 | 维度 0–16（7 维）；总分 0–112 |
| diagnosis_json | jsonb | 否 | — | 结构化多选 + 优先级 | 核心健康问题辨识 |
| **locked** | bool | 否 | true | CHECK = true（锁定只读） | — |
| migratable | bool | 否 | — | IDX | 同源元数据四要素齐备度（★语义修订） |
| age_group_locked | string | 否 | — | CHECK；锁定不可换 | 男16-32/…/女43-49以上（8 组） |
| item_group_id | string | 否 | — | FK scale_item_bank；IDX | 复评调取同源题组 |
| measure_operator | string | 否 | — | FK staff | 同源校验（测量人） |
| assist_operator | string | 是 | NULL | FK staff | — |
| assessed_at | timestamptz | 否 | now() | IDX | — |
| legacy | bool | 否 | false | 由 `created_at < 上线日` 派生；IDX | 不进任何比率分子分母 |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- 🛑 **基线锁定只读**（`locked=true`），全周期参照系；**不可覆盖**（变更走新记录 + 留痕）。
- `migratable`：题组 ID / 量程版本 / 测量人 / 时间戳**四要素齐备 → true**；缺任一 → false → `effect_verdict=NULL` → 计入 ECC 分母、**不计入分子**（只扣一次）。

### 2.12 `plan`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| plan_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| **version** | int | 否 | 1 | **版本递增·不可覆盖**；UNIQUE(plan_id, version) | — |
| treatment_json | jsonb | 否 | — | M1–M5 模块 + 目标 + 经络映射 | 模块 M1睡眠/M2疼痛筋骨/M3代谢消化/M4情绪压力/M5疲劳体能 |
| lifestyle_json | jsonb | 否 | — | 用药 / 饮食 / 运动（三项必填） | — |
| intent_params | jsonb | 否 | — | **意图参数**（非型号） | 参数经 `device_dispatch` 下发 |
| status | string | 否 | 'draft' | CHECK | draft / reviewing / approved / superseded |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- 🛑 **版本不可覆盖**：变更 → **新版本 + 强制重签**（`agreement` 关联新 `plan_version`）。

### 2.13 `plan_review`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| review_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| plan_id | string | 否 | — | FK plan；IDX | — |
| plan_version | int | 否 | — | FK plan(version) | — |
| reviewer_id | string | 否 | — | FK staff；IDX | 复核人（可 ≠ issuer） |
| **result** | string | 否 | — | CHECK | **通过 / 退回** |
| reason | text | 条件 | NULL | **退回必填** CHECK(result='退回' → reason NOT NULL) | — |
| reviewed_at | timestamptz | 否 | now() | IDX | — |
| second_confirm | bool | 否 | false | 同人自助通过需二次确认留痕 | — |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- 退回必填 `reason`；**同人自助通过需二次确认留痕**（Q1 待裁定：默认 C，高风险方案第二人复核）。

### 2.14 `device_dispatch`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| dispatch_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| plan_id | string | 否 | — | FK plan | 绑定方案版本 |
| store_id | string | 否 | — | FK store；IDX | — |
| device_id | string | 否 | — | FK **device**（门店级！）；IDX | ⚠️ **非客户手环** |
| param_snapshot | jsonb | 否 | — | 快照 | 下发参数快照 |
| result | string | 否 | — | CHECK | 成功 / 失败 |
| failed_reason | text | 条件 | NULL | 失败必填 | — |
| event | string | 是 | NULL | IDX | `device_push_failed`（可追责） |
| dispatched_at | timestamptz | 否 | now() | IDX | — |

- **隔离策略**：带 `tenant_id`；行级 scope 随门店。
- 🛑 **下行失败可追责**（≠ 手环上行失败"不得作不利依据"）。**不得与手环缺口混表 / 混语义**（brief §6.3）。

### 2.15 `visit`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| visit_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| serving_store_id | string | 否 | — | FK store；IDX | 标记服务门店 |
| plan_version | int | 否 | — | FK plan(version) | 执行时方案版本 |
| gate_check_json | jsonb | 否 | — | 四道闸门结果 | 禁忌/同意书/协议/方案有效期 |
| **customer_confirmed** | bool | 否 | false | CHECK | 核销须客户确认 |
| executed_at | timestamptz | 否 | now() | IDX | — |
| visit_no | int | 否 | — | UNIQUE(customer_id, visit_no)；IDX | 客户维度全局唯一账本（U2） |
| part_method / duration_min / pre_feedback / post_feedback / abnormal_note | string/int/text | 部分 | — | — | 逐次记录 |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户 + 服务门店。
- **服务次数 = 客户维度全局唯一账本**（跨店累计）；触发计数**只认中央账本**（U2）。

### 2.16 `daily_report`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| report_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| date | date | 否 | — | UNIQUE(customer_id, date)；IDX | — |
| answers_json | jsonb | 否 | — | **全点选**（无开放输入） | — |
| **source** | string | 否 | — | CHECK | **客户 / 代核** |
| submitted_at | timestamptz | 否 | now() | IDX | — |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- 补填窗口 = config #11（默认 2 天，可门店微调）；代核须标 `source`（A2 来源）。

### 2.17 `band_telemetry`（客户级穿戴设备·手环数据）★专章见 §四

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| telemetry_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | 客户级属性 |
| **device_id** | string | 否 | — | FK **`band.band_id`（客户级手环）**；IDX；幂等键成员 | ⚠️ **非门店级 `device`**；**宿主实体已于 BD-3 · F-3 落盘 → §2.26 `band`** |
| metric | string | 否 | — | CHECK；幂等键成员 | sleep / steps / hr / resting_hr / spo2 / workout / bp…（**见 §四枚举**） |
| date | date | 否 | — | 幂等键成员；IDX | 业务日（设备本地时区） |
| hour / minute | int | 是 | NULL | 幂等键成员（分时点） | 日聚合型退化为 NULL |
| value_num | decimal | 是 | NULL | 值 | — |
| sleep_json | jsonb | 是 | NULL | 睡眠分期 | — |
| steps | int | 是 | NULL | — | — |
| hr / resting_hr / spo2 | int | 是 | NULL | — | — |
| **coverage_flag** | bool | **是（NULL）** | NULL | **NULL = 缺失；严禁补 0** | true（该日有数据）/ NULL（缺失） |
| **data_source** | string | 否 | '手环' | CHECK | **手环 / 未接入**。⚠️ **缺陷修正（2026-09-23）**：原默认值写 `'band'`，但 CHECK 取值集是中文 `('手环','未接入')` —— **默认值永远无法满足自身 CHECK**，任何省略该列的 INSERT 必报 `23514`。已改为 `'手环'`。**待业务/技术裁定**：`data_source` 是否应改用英文枚举 token（对齐 `metric`/`gap_reason`/`sync_state` 的英文口径），还是保留中文取值（对齐契约 §4.1 `data_source = "未接入"` 的字面）—— 见 §2.17 缺陷登记 |
| **gap_reason** | string | 是 | NULL | CHECK；**仅门店/管理端可见** | **见 §四枚举（全量）** |
| **sync_state** | string | 否 | 'synced' | CHECK | **落库用 `no_data`；面向客户端契约为 `no_data_today`，二者显式映射**（ADR 十五 F-8）：`syncing / synced / sync_failed / no_data`（落库）↔ `syncing / synced / sync_failed / no_data_today`（契约 **§4.1「同步批次契约（四态）」**的客户端四态 `state`；命名声明另见 **§3.3 冲突点 C-3** 的 `client_sync_state`）。**2026-09-20 已加显式映射表** |
| **synced_at** | timestamptz | **否（NOT NULL）** | — | IDX | **必填理由见 §四** |
| is_wear | int | 是 | NULL | CHECK | 1 佩戴 / 0 脱腕 / (-1,255) 无效 |
| local_tz_offset | int | 是 | NULL | 设备本地时区偏移（分钟） | — |

- **隔离策略**：带 `tenant_id`；**属客户级属性，随客户跨店移动，不做门店锁定**（行级 scope = 客户可见范围）。
- 🛑 **与 `device` 严格分表**（brief §6.3）。
- **宿主实体**：`device_id` 的 FK 目标 = **`band.band_id`**（§2.26，客户级手环实体，**BD-3 · F-3 落盘**）—— 原「FK 指向不存在的 `band` 实体」的悬空外键已闭合。**另见 §2.26 的"另一方向未采纳"留痕**（若研发改判为"非外键业务键"，须技术负责人重拍）。
- **幂等 upsert**：`(device_id, metric, date, hour, minute)`；运动数据单开游标型 `(device_id,'sport',currentSportId)`（见 §四）。

#### 【缺陷登记 · 2026-09-23】`data_source` 默认值不满足自身 CHECK（构建期被反向验证抓出）

> 🐞 **缺陷**：本表（上表装饰行）与 §附 DDL 示意 均写 `data_source NOT NULL DEFAULT 'band'`，而 CHECK 取值集为中文 `('手环','未接入')`。
> `'band'` **永远无法满足** `IN ('手环','未接入')` ⇒ 任何**省略 `data_source` 的 INSERT 必报 SQLSTATE `23514`**。
>
> **发现方式**：**不是**从文档读出来的，而是 **V3 迁移落库后由真库测试反向验证抓出** ——
> `RlsBEntityIsolationTest` 三条用例同时变红（① `band_telemetry` 租户可见性为 0 行；② 合法 `gap_reason` `no_open` 被 23514 拒；
> ③ 幂等键用例报 `band_telemetry_data_source_check` 违规）。根因只有一个：seed 语句按 DDL 语义省略了 `data_source`，撞上这个不可能满足的默认值。
>
> **修正**：字典两处（字段表 + §附 DDL）与 `V3__band_telemetry_metric_long_table.sql` 同步改为 `DEFAULT '手环'`。取值集、`NOT NULL` 语义、口径**均未变**（最小修正）。
>
> **顺带登记的待裁定项（不阻塞）**：`data_source` 的**取值语言**是否应与同表其它枚举（`metric` / `gap_reason` / `sync_state` 全英文 token）统一为英文？
> 现状是**中文取值**，理由是**对齐契约 §4.1 的字面**（`data_source = "未接入"`）与 R6 规则（`data_source: "未接入"` + 数值字段不下发）。
> 二者只能对齐一个：**对齐契约字面（现选）** 还是 **对齐同表枚举英文口径**。**属 D-10「逐项落库」之外的新裁定点**，故登记不代拍。

#### 【已定案 · F-2】`band_telemetry` 建模：宽表 vs 长表（BD-3 · F-2 落盘）

> ✅ **【2026-09-23 裁定落地 · R2】F-2 已定案 = 方案 A（长表 `metric` 化）**，拍板人口径见下。
> **裁定结论**：`band_telemetry` 采用 **方案 A · 长表 `metric` 化** —— 一行一 metric，主键口径 `(device_id, metric, date, hour, minute)`；结构化值（`sleep_json`）入 `value_json`；运动数据走单开游标型分支（`(device_id,'sport',currentSportId)`）。
> **判据依据（逐条给结论，非只表态）**：
> - **J1 是** —— 接受按日型 12 条接口共用同一张表 + 同一幂等键（`D-4`）；
> - **J3 是（决定性）** —— `contract §4.3` 的双分支密钥（按日型 / 游标型）**已按 `metric` 为键成员冻结**；选 B 会使落库口径与已冻结契约**分叉**，故 **A**；
> - **J2 否 / J4 是** —— 客户端 / 门店端若日后需单行多指标同屏，**以"额外物化视图 / 宽视图"满足**（J4），**不以宽表落库换取**（守住"单一权威源"R-8）；
> - **J5 否** —— 不存在 PRD §8 字面必须逐字对齐的硬约束；PRD §8 的宽表写法视为**呈现层口径**，落库以本表长表为准，二者非同一层，**无需文档更正**。
> **拍板人**：**技术负责人**（已按本节结论拍定）；契约侧联动确认 = **析客**（选 A ⇒ 契约幂等键**无需改动**，与 `contract §4.3` 现值一致）。
> **落地载体**：骨架新增迁移 **`V3__band_telemetry_metric_long_table.sql`**（长表 + `gap_reason` 7 值 CHECK + `metric` 枚举 + RLS fail-closed + 闭合 `device_id → band(band_id)` FK）。
> **纪律**：本节结论**已回写**；若日后改判 B，须**技术负责人重拍 + 契约幂等键同步改**，不得单方面变更。

> **依据（存档）**：`ADR 十五 F-2` —— 骨架本轮处置为「**预留位**：骨架不建该表，**规格书写两方案对比 + 决策判据**」；**ADR 十五 F-2 只要求"写对比 + 写判据"，并未替研发做选择** —— 上述"方案 A"为**技术负责人按本节判据作出的研发裁定**，非字典代选（原"由本字典不代选边"的表述由本节结论取代）。

**方案对比**

| 方案 | 写法 | 优点 | 代价 | 对本字典现有设计的影响 |
|---|---|---|---|---|
| **A · 长表 `metric` 化** | 一行一 metric：`(device_id, metric, date, hour, minute) → value_num` | 13 条接口统一落一表；幂等键 `(device_id, metric, date, hour, minute)` **天然成立**（`D-4`）；`metric` 枚举逐项落库（`D-10`） | 查询需 pivot；`sleep_json` 等结构化值需入 `value_json` | **= 本表当前 DDL 写法**（§2.17 / §附 DDL 示意） |
| **B · 宽表（PRD §8 原写法）** | 一行一日/一时点，多列：`sleep_json / steps / hr / resting_hr / spo2 / …` | 单行可读性好；与 PRD §8 字面一致、改文档为零 | 13 类 metric 字段各异 → 列膨胀 / 大量 NULL；**幂等键须按 metric 分支**，与 `D-4` 单一键不一致 | 需**回改本表 DDL 与 §四幂等专章** |
| **C · 双表并存**（宽表展示 + 长表落库） | 两表同源 | 各取所需 | **口径易漂移**（两处维护同一事实） | **不建议**：违反"单一权威源"（R-8）精神 |

**决策判据（评审时逐条给出结论，不得只表态）**

| # | 判据 | 若"是"倾向 |
|---|---|---|
| J1 | 是否接受**按日型 12 条接口共用同一张表 + 同一幂等键**（`D-4`）？ | 是 → **A**；否 → B |
| J2 | 客户端 / 门店端查询是否必须**单行多指标同屏**（宽表 pivot 成本是否不可接受）？ | 是 → 倾向 B（或 A + 视图） |
| J3 | `contract §4.3` 的双分支密钥（按日型 / 游标型）是否已按 `metric` 为键成员冻结？ | 已冻结 → **A**（B 会与契约分叉） |
| J4 | 是否接受为查询便利**额外建物化视图 / 宽视图**（而非宽表落库）？ | 是 → **A + 视图**，仍可满足 J2 |
| J5 | 是否存在**PRD §8 字面必须逐字对齐**的硬约束（对外交付 / 验收对照）？ | 是 → 需先走文档更正，再选 A |

**拍板结论**：**方案 A（长表 `metric` 化）** —— 由**技术负责人**于 **2026-09-23** 按上方判据拍定（J3 决定性）；**契约侧联动确认 = 析客**（选 A ⇒ 契约幂等键无需改动）。**评审结论已回写本节**（见本节开头「已定案」块）。
- **API 字段名提示**：PRD §8 原文用 `sleep_json / steps / hr / resting_hr / coverage_flag / data_source / gap_reason / synced_at`；本表已按 **`metric` 长表化落库**（幂等键与 13 接口统一的前提）。✅ **【2026-09-23 定案】宽 / 长表建模选择 = 方案 A（长表），已回写 §2.17【已定案 · F-2】**；PRD §8 宽表写法视为**呈现层口径**（落库与呈现非同一层）。

### 2.18 `cycle_assessment`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| cycle_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| sequence_no | int | 否 | — | IDX | 第 N 次评估 |
| as_value | decimal(4,3) | 是 | NULL | 0–1 | 依从性总分 AS |
| as_dimensions_json | jsonb | 否 | — | A1/A2/A3/A4 + `applicable` | — |
| metric_snapshot | jsonb | 否 | — | **判定依据快照** | — |
| gap_days | int | 是 | NULL | 样本护栏 | 应填天 < 7 → 标"样本不足" |
| **verdict** | string | 否 | — | CHECK；IDX | **稳定 / 依从不足 / 达标无效 / 全面评估 / 人工复核**（四分支 + 人工复核） |
| effect_verdict | string | 是 | NULL | CHECK | **E1显著改善 / E2部分改善 / E3稳定 / E4无明显改善 / E5加重**（E5 强制人工录入） |
| adherence_state | string | 是 | NULL | CHECK | **达标 / 不足 / 样本不足** |
| improvement_rate | decimal | 是 | NULL | **同源公式·负值不截断** | — |
| module_scores | jsonb | 否 | — | 模块 0–16 | M1–M5 |
| threshold_version | string | 否 | — | IDX | **阈值版本必落库** |
| **band_trend_note** | text | 是 | NULL | **来源 PRD 附录 C.1.6 · 05.§三 手环趋势**；U-15 裁定：补进本表、**不从 PRD 移除** | 手环趋势说明（承载"缺失标 null 不补 0 / 未佩戴不记不利 / 自愿"四条备注语义） |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- **判定依据 + 阈值版本必须落库、可回放**；**不可覆盖**（追加）。
- `gap_days < 7` → 标"样本不足"，**不得用于退款门禁**（config #7）。

### 2.19 `verdict`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| verdict_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| cycle_id | string | 否 | — | FK cycle_assessment；IDX | — |
| **branch** | string | 否 | — | CHECK；IDX | **稳定 / 依从不足 / 达标无效 / 全面评估 / 人工复核** |
| **confidence** | decimal(4,3) | 否 | — | 0–1 | 置信度 |
| **evidence_snapshot** | jsonb | 否 | — | **不可覆盖** | 判定依据快照（AS / 核心指标 / 权重版本） |
| **threshold_version** | string | 否 | — | **不可覆盖**；IDX | 阈值版本 |
| decided_at | timestamptz | 否 | now() | IDX | — |
| effect_verdict / adherence_state / risk_flag | string | 是 | NULL | CHECK | 组合出口（三 enum × → disposition） |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- **结论为「对内判定建议 + 置信度」，不对外直出**；`visible_to_customer` 恒 false（Q10 口径②）。
- **判定依据 + 阈值版本必须落库**（C.1.9 硬约束②）。

### 2.20 `refund`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| refund_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| **entry** | string | 否 | — | CHECK；IDX | **A 门店代录 / B 首周期** |
| **refund_route** | string | 否 | — | CHECK；IDX | **履约类 / 效果类** |
| **liable_store_id** | string | 否 | — | FK store；IDX | 责任主体 = 签约店 |
| reason_code | string | 否 | — | CHECK；**未记录不可结案**；IDX | 效果未达预期 / 症状加重或出现新不适 / 服务体验或沟通问题 / 时间·经济·家庭原因 / 配合度不足导致无明显变化 / 信任或价格异议 |
| **requested_at** | timestamptz | 是 | NULL | 客户提出时间（最早且可核实） | — |
| requested_at_claimed | timestamptz | 是 | NULL | 客户主张、仅留存**不计时** | — |
| **recorded_at** | timestamptz | 否 | now() | 门店代录时间；IDX | — |
| **recording_delay_h** | decimal | 是 | NULL | >24h → 异常名单 + 自动升级 | 代录延迟时长 |
| **sla_due_at** | timestamptz | 是 | NULL | SLA 倒计时 | — |
| **outcome** | string | 否 | — | CHECK | **继续 / 终止 / 归档** |
| amount_split_json | jsonb | 是 | NULL | 损失按次数分摊 | — |
| amount_basis | string | 是 | NULL | CHECK | **协议 / 负责人判定 / 双方协商** |

- **隔离策略**：带 `tenant_id`；行级 scope = 合约门店（liable_store）+ 服务门店（协办）；**客户端与调理师端不露出**（403）。
- **原因码必填；未记原因不可结案**；**履约类 = 规则直退（未消耗次数 × 单次均价）**、**效果类 = 协商工单（人在环）**；**代录者不可审批**（R4b ④）。

### 2.21 `retention`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| retention_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| refund_id | string | 否 | — | FK refund；IDX | — |
| attempts | int | 否 | 0 | — | — |
| script_version | string | 是 | NULL | — | — |
| result | string | 否 | — | CHECK | 接受继续服务 / 接受但需调整 / 不接受进入退款终止 |
| operator_id | string | 否 | — | FK staff；IDX | — |
| analysis / communication | jsonb | 否 | — | 5 维原因分析 + 沟通记录 | — |

- **隔离策略**：带 `tenant_id`；行级 scope 随工单。
- **挽留记录必填；入口 B 不经挽留**。

### 2.22 `case_archive`

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| archive_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| effect_confirm_pdf | string | 是 | NULL | — | — |
| **desensitize_authorized** | bool | 否 | false | CHECK | **脱敏须单独授权** |
| metrics_trend | jsonb | 是 | NULL | — | — |
| archived_at | timestamptz | 否 | now() | IDX | — |
| archive_checklist | jsonb | 否 | — | 6 项；缺项 → 403 阻断结案 | — |
| final_conclusion | string | 条件 | NULL | CHECK | 退款终止时必填 |
| staff_signs | jsonb | 否 | — | 经办人 / 经络师 / 门店负责人 / 日期 | — |

- **隔离策略**：带 `tenant_id`；行级 scope 随客户。
- **脱敏须单独授权**（可拒绝）；**归档须客户签字**；归档后只读。

### 2.23 ★ `intake_profile`（附录 C.1 · 未入 §8 · 待裁定）

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| profile_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；UNIQUE | — |
| job_tag[] | jsonb | 是 | NULL | 多选 | 久坐/久站/体力劳动/高压力/其他 |
| height_cm / weight_kg / waist_cm | decimal | 是 | NULL | >0 | — |
| bp_sys / bp_dia | int | 是 | NULL | mmHg | — |
| hr | int | 是 | NULL | 次/分 | — |
| glucose / uric_acid | decimal | 是 | NULL | mmol/L、umol/L | ⚠️ **GTL1 无此能力**（见 §四注） |
| sleep / diet / exercise / thermal / pain_sites / bowel / female_special | jsonb | 是 | NULL | 多选 | 见 01 表枚举 |
| meridian_self_report | jsonb | 是 | NULL | 6 区 | 调理师只记录、不诊断 |

- **补充 + 修订留痕，不可覆盖**（客户终身）。
- 注：`intake_profile`（建档本体）与 `screening_record` 属**同一次提交链**；**仅 `screening_record.result=通过` 才允许提交 `intake_profile`**。

### 2.24 ★ `scale_item_bank`（附录 C.1.8 · 未入 §8 · **已定案**）

> **状态更正（2026-09-23）**：本行原写「待裁定」系**漏同步** —— 该实体早已由 `ADR 十五 F-1`（★ 3 个纳入 → 实体数 **28**）确定口径，**不存在待裁定事项**。**现已落库**：`dy-app` 迁移 `V4__scale_item_bank.sql`，字段级定义与本表逐项一致（`item_direction` 仅 `symptom`、五级锚点 `NOT NULL`、版本化唯一键、RLS `ENABLE` + `FORCE` + 双 `NULLIF`）。
> **归属**：题库**内容资产**（**总部维护**，非客户级）—— 与 `scale`（客户某次作答记录）是**两本台账，不得合并**。本表**不带 `customer_id` / `store_id`**，该性质由 RLS 真库门禁 `RlsScaleItemBankIsolationTest` 断言守住（它扫 `information_schema` 确认无此二列）。

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| item_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| age_group | string | 否 | — | CHECK；UNIQUE 组成 | 8 组枚举 |
| dimension | string | 否 | — | CHECK | 7 维枚举 |
| item_no | int | 否 | — | UNIQUE 组成 | 1–4 |
| item_text | text | 否 | — | **版本化·不可覆盖** | — |
| anchor_0..anchor_4 | text | 否 | — | 五级锚点原文 | — |
| **item_direction** | string | 否 | **'symptom'** | CHECK **仅允许 `symptom`** | symptom（正向题不得计分） |
| version | string | 否 | — | **版本递增·不可覆盖** | — |

- 唯一键 `(tenant_id, age_group, dimension, item_no, version)`；**题目文本版本化、不可覆盖**。

### 2.25 ★ `customer_state_transition`（14 态状态机实体 · BD-3 · F-4 落盘）

> **依据**：`ADR 十五 F-4` —— 骨架本轮按「**预留位**」实现（`customer` 表只留 `status` 5 值 + 注释指向「14 态状态机实体（待建）」）；**本轮回写（BD-3）补出该实体的字段级定义**。14 态口径来源 = **PRD §7.2「客户服务主状态机（14 态：11 活跃 + 3 终态）」**；守卫口径来源 = **PRD §7.2 准入守卫 + `T-14` / `X-14`（状态跃迁守卫 + 403 携带 `missing_items[]`）**。
> 🛑 **本实体承载"当前态 + 跃迁历史"，`customer.status` 保持 5 值不动**（ADR 十五 F-4 的预留位语义）。**不把 `customer.status` 扩为 14 值**（那样丢历史、无法回答"何时进入 `REFUND_REVIEW`"）。

**实体职责**：一条记录 = 一次状态跃迁（append-only）。**当前态 = 该客户最新一条的 `to_state`**（`is_current=true` 唯一）。

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| transition_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| customer_id | string | 否 | — | FK customer；IDX | — |
| **from_state** | string | 是 | NULL | CHECK（**14 态**）；IDX | 建档首条为 NULL；否则 ∈ 下表 14 态 |
| **to_state** | string | 否 | — | CHECK（**14 态**）；IDX | **14 态全值**（下表 ①） |
| **is_current** | bool | 否 | false | **UNIQUE(customer_id) WHERE is_current = true** | true = 当前态（每客户最多 1 条） |
| trigger_event | string | 否 | — | CHECK | 见下表 ③ |
| guard_result | string | 否 | 'passed' | CHECK | **passed / blocked**（blocked 记 403 缺失项，**不写 `to_state` 变更**） |
| **missing_items** | jsonb | 是 | NULL | 门禁回显 | **403 携带的缺失项名数组**（`X-14`）；`guard_result=blocked` 时必填 |
| operator_id | string | 是 | NULL | FK staff | 系统触发为 NULL |
| occurred_at | timestamptz | 否 | now() | IDX | **跃迁时点（回答"何时进入某态"）** |
| ref_entity / ref_id | string | 是 | NULL | — | 触发该跃迁的实体（如 `consent` / `agreement` / `plan` / `refund`） |

**① 状态枚举（14 值 · 逐项落库，直接取自 PRD §7.2）**

| # | 态 | 类别 | PRD §7.2 上游 → 下游 |
|---|---|---|---|
| 1 | `SCREENING` | 活跃 | 入口 →（通过）`PROFILED` /（有禁忌）`REJECTED` |
| 2 | `REJECTED` | **终态**（不建档） | 不可逆 |
| 3 | `PROFILED` | 活跃 | → `CONSENTED` |
| 4 | `CONSENTED` | 活跃 | → `ASSESS_BASE` |
| 5 | `ASSESS_BASE` | 活跃 | → `PLAN_APPROVED` |
| 6 | `PLAN_APPROVED` | 活跃 | → `AGREEMENT_SIGNED` |
| 7 | `AGREEMENT_SIGNED` | 活跃 | → `CONFIRMED` |
| 8 | `CONFIRMED` | 活跃 | → `IN_TREATMENT` |
| 9 | `IN_TREATMENT` | 活跃 | ↔ `CYCLE_ASSESS` / `REFUND_REVIEW` |
| 10 | `CYCLE_ASSESS` | 活跃 | → `IN_TREATMENT` / `PLAN_REVISING` / `REFUND_REVIEW` |
| 11 | `PLAN_REVISING` | 活跃 | → `PLAN_APPROVED`（须回炉审核 + 重签） |
| 12 | `REFUND_REVIEW` | 活跃 | → `IN_TREATMENT`（挽留成功）/ `TERMINATED` |
| 13 | `CLOSED` | **终态** | 已核销次数 ≥ `plan.planned_sessions` |
| 14 | `TERMINATED` | **终态** | 首周期双不达标 或 挽留失败 |

> ⚠️ **`CLOSED` 的触发条件（"已核销 ≥ 方案总次数"）在 PRD §7.2 自标为「推荐默认值，需业务确认」** —— 本字典**不代拍**。
>
> 【2026-09-23 处置 · R4（技术侧已闭环，业务侧仍待签）】
> - **技术侧裁定（可落地）**：**状态机守卫按 PRD 现值实现** —— `CLOSED` 触发 = `Σ 已核销次数 ≥ plan.planned_sessions`，作为**推荐默认值落 CHECK / 守卫**；`customer_state_transition` 只**落 14 态取值 CHECK**（本表 DDL），`CLOSED` 的**触发判据不写进 DDL 硬约束**，而由**服务层守卫**表达 —— 以便业务方确认后**改守卫不动表结构**。
> - **仍未闭合的部分（🛑 不得由我方推定）**：PRD §7.2 自标"**需业务确认**"。业务方确认前，该触发条件为**临时口径**，**须在交付物与接口文档中显式标注"临时口径 · 待业务确认"**。
> - **变更路径**：业务方确认（含或变更）→ 走**增项流程**改服务层守卫 + 回写本节 + 关闭 blocker 对应项；**表结构不变**（故本项**不阻塞建表**）。
> - **口径纪律**：`CLOSED` 与 `TERMINATED` 均为终态且**同归 `customer.status=ARCHIVED`**（见 ② 映射表）—— 上位聚合态**不区分两种终态**，故业务方口径变化**不影响 `customer.status` 的 5 值**。

**② 与 `customer.status` 5 值的显式映射（口径统一 · 不得有歧义）**

| `customer.status`（5 值） | 对应 14 态（`to_state`） | 说明 |
|---|---|---|
| `CREATED` | `SCREENING` | 已建档键、准入未完成 |
| `PROFILED` | `PROFILED` | 名称同义、一一对应 |
| `CONSENTED` | `CONSENTED` / `ASSESS_BASE` / `PLAN_APPROVED` / `AGREEMENT_SIGNED` / `CONFIRMED` / `IN_TREATMENT` / `CYCLE_ASSESS` / `PLAN_REVISING` / `REFUND_REVIEW` | ⚠️ **一对多**：5 值态是"粗粒度聚合"，服务期内所有活跃态均归 `CONSENTED` |
| `REJECTED` | `REJECTED` | 名称同义、终态 |
| `ARCHIVED` | `CLOSED` / `TERMINATED` | 两个终态均归 `ARCHIVED` |

> 🛑 **映射纪律**：**14 态是唯一权威、5 值是派生聚合**；`customer.status` 由状态机实体**推导刷新**（在跃迁同事务内更新），**不得反向由 5 值推断 14 态**。**须双方一致的真实来源 = `customer_state_transition`**。

**③ 跃迁守卫（`T-14` / `X-14` · 未满足 → 入口 API 403 + `missing_items[]`）**

| 守卫 | 规则（PRD §7.2 准入守卫原文语义） | 403 最小 `missing_items` |
|---|---|---|
| G1 | **未 `PROFILED`（未建档）→ 不得签知情同意书** | `["PROFILED"]` |
| G2 | **未 `CONSENTED`（未签同意书）→ 不得进基线评估** | `["CONSENTED"]` |

> 🛑 **403 必须给出缺失项名称，不返回模糊报错**（PRD §7.2 原文）。`guard_result=blocked` 的跃迁尝试**须留痕**（写入本表但不改 `is_current`）。
> ⚠️ **守卫清单仅列 PRD 明文两条（G1 / G2）** —— 其余跃迁的前置条件（如 `AGREEMENT_SIGNED` 前须 `PLAN_APPROVED`）**PRD 未写成门禁条款**，本字典**不自行发明守卫**；待业务 / 研发评审补充。

- **不可覆盖**：本表 **append-only**（跃迁即新增一行）；**唯一可更新字段 = `is_current`**（旧态置 false、新态置 true，同事务）。
- **隔离策略**：带 `tenant_id`；行级 scope 随客户（同 `customer`）。

### 2.26 ★ `band`（客户级手环实体 · BD-3 · F-3 落盘）

> **依据**：`ADR 十五 F-3` —— 「`band_telemetry.device_id` 外键指向不存在的 `band` 实体」的骨架本轮处置为「**预留位**：规格书登记，**须补"客户级手环实体"或改业务键**」；**方向裁量来源 = `blocker-root-cause-and-today-close-2026-09-20.md` §四 BD-3 行的 R-4 处置**「**按 `ADR 十五 F-3` 补『客户级手环实体』定义，回写 `data-dict §2.17`**」。任务书据此**选"补实体"一支**（另一支"改业务键"不再并列为二选一，见下方"另一方向为何未采纳"）。
> **为什么必须补**：`band_telemetry` / `band_sync_probe` / `band_daily_coverage` 三表的外键**全部悬空**（无 FK 目标）；且"应戴天"分母依赖 **绑定日 → 解绑日**（`gtl1-integration-brief` §6.3 台账口径 / `gtl1-data-spec` R5「`应戴天` 必须由设备台账决定」）—— **该台账无宿主表则分母不可得**。

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| band_id | string | 否 | — | **PK**（= `band_telemetry.device_id` 的引用目标） | 客户级手环唯一 ID |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| **customer_id** | string | 否 | — | FK customer；**UNIQUE(tenant_id, customer_id) WHERE status='active'** | **客户级属性**：1 客户 : 1 有效手环 |
| vendor | string | 否 | — | CHECK | GTL1 / 其他（待厂商确认） |
| model | string | 是 | NULL | — | 型号（**客户级属性，非门店级**） |
| **bound_at** | date | 否 | — | IDX | **绑定日（"应戴天"分母起点）** |
| **unbound_at** | date | 是 | NULL | IDX | **解绑日（分母终点）**；未解绑为 NULL |
| unbind_reason | string | 是 | NULL | CHECK | 主动放弃 / 换机 / 设备损坏 / 其他（**区分"主动放弃"与"技术性缺失"，data-spec M6**） |
| status | string | 否 | 'active' | CHECK；IDX | **active / paused / unbound / retired** |
| pause_period_json | jsonb | 是 | NULL | **合规摘除期**（住院 / 洗浴 / 桑拿，`G8`） | 数组 `[{from, to, reason}]`，**暂停期从 A3 分母剔除** |

- **隔离策略**：带 `tenant_id`；**属客户级属性，随客户跨店移动，不做门店锁定**（与 `customer` 同 scope）。
- 🛑 **与 `device`（门店级调理设备）是两本台账、不得合并** —— 见 §4.1 边界重申表。**本表 `band_id` ≠ `device.device_id`**；**不得**用 `device.device_id` 表示客户手环（§2.5 明令）。
- 🛑 **不得用本表反向承载调理设备参数下发**（方向相反：本表=上行，`device`=下行）。
- **`band_telemetry.device_id` / `band_sync_probe.device_id` / `band_daily_coverage.device_id` 的 FK 目标 = 本表 `band_id`**（原指向"不存在的 `band` 实体"的悬空外键，本轮回写后闭合）。
- **计数影响**：本实体**属待评审增项 / 待裁定增量**，**不并入封版实体基数**（封版五值不动，见"计数口径"）。

> **⚠️ 另一方向为何未采纳（留痕，不得静默）**：`ADR 十五 F-3` 原文给的是**二选一**（"须补实体 **或** 改业务键"），**ADR 本身未指定选哪一支**。本轮回写**按 R-4 处置口径选"补实体"**。**若研发在评审中认为"短期内不建 `band` 表"更合适**（即改判为"`device_id` 为**非外键业务键**，仅索引不加 FK"），**属对 R-4 处置的回退，须由技术负责人重新拍板并留痕**，本字典不代认。
> **⚠️ 字段范围未定部分**：`vendor` / `model` 取值、`pause_period` 申报入口（`U10` 待业务裁定）、`app_uninstall` 等 `T2` 字段，**均待评审 / 待确认，本字典不发明口径**。

### 2.27 ★ `doc_template`（文书/协议文本模板源 · 上传与在线编辑双形态）

> **依据**：PRD **附录 C.1.8 `doc_template` ★ 实体**（**v1.28 新增**，挂 `config #46` / `P0-27`「文书/协议文本模板管理与后台编辑」）；**本批（2026-09-23）用户（产品负责人）裁定「法律文书由我方确认后『上传到平台上』」**，承载形态 = **双形态（上传文件 + 在线编辑）** ⇒ 依 `_work/doc-template-upload-capability-spec-2026-09-23.md` **§1.1** 补出**字段级 DDL**。
> **本实体为既有登记实体**（§一 总览表 ★ 行，所在 = 附录 C.4（P0-27）），本批**只补字段级 DDL，不新建实体、不改名**；既有 10 个字段**一字不改**。

| 字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 |
|---|---|---|---|---|---|
| template_id | string | 否 | — | **PK** | — |
| tenant_id | string | 否 | — | FK tenant；IDX | — |
| doc_type | string | 否 | — | CHECK；IDX | 知情同意书 / 调理协议书 / 手环数据说明 / 隐私与授权须知 / 到店须知 / 其他 |
| title | string | 否 | — | — | — |
| content | text | 是 | NULL | — | 在线编辑形态的正文（**`source_type=editor` 时有值**；`source_type=upload` 时可为 NULL） |
| version | int | 否 | — | **版本递增·不可覆盖**；（与 `tenant_id` / `doc_type` 构成唯一键，见下） | — |
| is_active | bool | 否 | true | IDX | 同 `doc_type` 下**最多 1 个 `true`** |
| created_by | string | 否 | — | FK staff | — |
| created_at | timestamptz | 否 | now() | IDX | — |
| change_reason | string | 是 | NULL | — | 变更留痕（**版本不可覆盖**要求每次新增行必填理由） |
| **`source_type`** | string | 否 | `'editor'` | CHECK；IDX | **`editor`（在线编辑）/ `upload`（上传文件）** —— 决定该版本以哪种形态存在 |
| `file_ref` | string | 是 | NULL | — | **对象存储引用**（如 `oss://<bucket>/<tenant>/<doc_type>/<version>/<hash>`）；`source_type=upload` 时**必填** |
| `file_name` | string | 是 | NULL | — | **原始文件名**（保留扩展名；仅展示用，不参与寻址） |
| `mime_type` | string | 是 | NULL | CHECK | **仅允许三值**：`application/pdf` / `application/vnd.openxmlformats-officedocument.wordprocessingml.document`（.docx）/ `text/markdown` |
| `file_size` | int | 是 | NULL | CHECK > 0 且 ≤ **10 MiB** | 字节 |
| **`file_hash`** | string | 是 | NULL | — | **SHA-256（hex）**；完整性校验 + 防替换。`source_type=upload` 时**必填** |
| **`placeholder_schema`** | jsonb | 是 | NULL | — | **占位符白名单声明**（见 `doc-template-upload-capability-spec` §二）；未声明则渲染期**只允许零占位符**（fail-closed） |

- **唯一键 `(tenant_id, doc_type, version)`** —— **不变**（沿用 §一 既有登记）。
- **版本不可覆盖**（对齐既有硬约束③）：每次上传/编辑**新增一行**，`version` 递增；旧的置 `is_active=false`。**同 `doc_type` 下唯一活跃版本**由 `is_active` 保证。
- **隔离策略**：带 `tenant_id`；行级 scope 随租户（**`super_admin`（tenant 级，不跨租户）** 可写；**门店负责人 / 区域督导只读**，且只读 `title` + `version`，**不含 `content` / `file_ref`**）。
- 🛑 **`content` 与 `file_ref` 不得同时为空** —— `source_type=editor` → `content` 有值、`file_ref` 为 NULL；`source_type=upload` → `file_ref` 有值、**`content` 可为 NULL**（上传件不强制转录）。**两者皆空的行不得存在**（DB 层 CHECK 或服务端强校验，二者择一，**须在迁移脚本中落实**）。
- 🛑 **模板正文不得作为客户端包资产下发**（**QC-6 不变量**）—— 唯一落点 = **服务端** `doc_template.content` / `file_ref`；签署经**服务端渲染页 / 厂商侧页面**呈现。**任何客户端包内不得出现模板正文 / 上传原件**。
- 🛑 **上传件只做字节存储 + 哈希，不做解析执行** —— DOCX **不得解压执行宏**；渲染期**只做文本占位符替换，不做内嵌逻辑求值**（`doc-template-upload-capability-spec` §三 I3 反病毒 / 反脚本约定）。
- **计数影响**：`doc_template` **本就未入 §8 的 22**（是**附录 C.1 的 3 个 ★ 实体之一**，**已在现有 28 算式内**）⇒ **本批补字段级 DDL 不改变实体计数，28 不变**（22 §8 正文 + 3 ★ + 3 手环补拉表 = 28）。**本批不新增实体、不产生第 29 个。**

> **⚠️ 待研发评审 / 待裁定部分（不得写成结论）**：**对象存储实现细节**（bucket / CDN / 加密方式）、**厂商渲染分工**、**`placeholder_schema` 之外的占位符扩展** —— 一律**待研发评审 / 待厂商选定**（沿用「占位待冻结」形态，照契约 §2.8 域 H 先例）；**`config #46` 保持原号不改号**。
> **⚠️ 与 Q-E1 的边界**：本实体字段设计**不构成 Q-E1（送签 PDF 是否回显禁忌结论）的裁定** —— 占位符白名单机制只**使"方案 a"成为默认可实现路径**，**不得写成"Q-E1 已关闭"**（两方案不得合并表述）。

---

## 三、字段披露口径矩阵（数据侧 · 与原型 D1 对齐）

> **四档**：【可见】/【403 不可见】/【不适用】（无端 / 无系统账号）。
> **行 = 敏感字段组；列 = 四端角色。** 硬约束：**一律服务端判定 + 后端 403 兜底**（§2.4 X-1），任何端不得仅靠前端隐藏；配置化见 **config #43 `band_visibility`**（角色 × 字段组，**fail-closed 未配置即拒绝**）。

### 3.1 手环数据字段组（config #43 ④字段组）

| 敏感字段 / 字段组 | 客户（小程序） | 调理师（APP） | 经络师（APP） | 管理员（Web，门店/督导/总部） | 门店客服（无端） |
|---|---|---|---|---|---|
| ① 手环**原始数据**（sleep/steps/hr/resting_hr/spo2/workout） | 【可见·仅本人】 | 【可见·服务关系内客户】 | 【可见·负责客户】 | 【可见·行级 scope 本店/辖区/全量】 | 【不适用】 |
| ② **采集状态**（已采集天数 / 同步时间） | 【可见】 | 【可见】 | 【可见】 | 【可见】 | 【不适用】 |
| ③ **缺口原因分类** `gap_reason` | 🛑【403 不可见】（仅"该日暂无数据"中性描述） | 【可见】 | 【可见】 | 【可见】 | 【不适用】 |
| ④ **派生结论**（A3 佩戴率 / `AS` 值 / `effect_verdict` / 退款资格） | 🛑【403 不可见】（**W-1 硬约束**） | 【可见·但不得作不利依据】 | 【可见】 | 【可见】 | 【不适用】 |
| `coverage_flag` 原始值 | 【403 不可见】（W-2：只给正向计数"已采集 N 天"） | 【可见】 | 【可见】 | 【可见】 | 【不适用】 |
| `sync_state`（同步中/已同步/同步失败/该日暂无数据） | 【可见·四态中性文案】 | 【可见】 | 【可见】 | 【可见】 | 【不适用】 |
| 负向措辞（"缺失 N 天/未佩戴/未达标/数据不全"） | 🛑【403 / 构建期扫描阻断】 | 【可见】 | 【可见】 | 【可见】 | 【不适用】 |
| `is_wear`（佩戴检测原始值） | 【403 不可见】 | 【可见】 | 【可见】 | 【可见】 | 【不适用】 |

### 3.2 退款 / 判定 / 稽核字段组（config #40 `refund_visibility`）

| 敏感字段 | 客户（小程序） | 调理师（APP） | 经络师（APP） | 管理员（Web） | 门店客服（无端） |
|---|---|---|---|---|---|
| `refund.*` 全部（含"退款"字样、金额、进度、到账倒计时） | 🛑【403 不可见·无入口无字样】 | 🛑【403 不可见】 | 【可见·可代录不可审批】 | 【可见·门店负责人完整；督导可见不审批；总部完整+稽核】 | 【不适用】 |
| `verdict.*`（branch / confidence / evidence_snapshot） | 【403 不可见】 | 【403 不可见】 | 【可见】 | 【可见】 | 【不适用】 |
| `cycle_assessment.as_value` / `as_dimensions_json` | 【403 不可见】 | 【403 不可见】 | 【可见】 | 【可见】 | 【不适用】 |
| 稽核信号（P1-04） | 【403 不可见】 | 【403 不可见】 | 【403 不可见】 | **门店 / 加盟商不可见；总部可见** | 【不适用】 |
| `screening_record`（禁忌结论） | 【可见·回显只读】 | 【可见】 | 【可见】 | 【可见】 | 【不适用】 |
| `customer.phone` | 【可见·本人】 | 【可见·服务关系内】 | 【可见·负责客户】 | 【可见·行级 scope】 | 【不适用】 |

> 🛑 **硬约束复述**：**`gap_reason` 仅门店 / 管理端可见、客户端不可见**；**派生结论客户端一律 403**；**手环数据是客户级、不做门店锁定**（跨店后新服务门店可见历史手环数据，与 P0-03 同规则）。
> ⚠️ **配置化纪律**：#43 客户侧 ③④ 为**硬约束、不得通过配置放开**；放开属"变更业务裁定"（触发与 §2.2 条款 B 同等返工）。#40 与 #43 **独立配置、不得合并**（粒度与合规依据不同）。

---

## 四、手环数据落库专章

### 4.1 `band_telemetry` 与 `device` 的边界重申（两本台账不合并）

| 维度 | `device`（门店级调理设备） | `band_telemetry`（客户级穿戴设备） |
|---|---|---|
| 对象 | 店内仪器（杠2 / 现有） | 客户腕上智能手环（GTL1） |
| 归属层级 | **门店级属性**（门店无权改） | **客户级属性**（随客户跨店移动） |
| 基数 | **1 门店 : N 台** | **1 客户 : 1 手环** |
| 数据方向 | **下行**（参数 → 设备） | **上行**（设备 → 系统） |
| 异常语义 | 下发失败 → 转待办 + `device_push_failed`（**可追责**） | 未佩戴 / 同步失败 → 标空缺 + 中性 + **不得作不利依据**（合规约束） |
| 落库实体 | `device` / `device_dispatch` | `band`（★ §2.26 客户级手环实体，**BD-3 · F-3 落盘**）/ `band_telemetry`（+ §4.6 手环补拉引擎 ★ 新增三表 `band_sync_probe` / `band_sync_log` / `band_daily_coverage`） |

> **合并为什么错**：① 基数冲突（"门店唯一"与"客户可多台"混表 → 跨店 / 换店 / 换设备必然出错）；② 方向相反（"下发失败"与"同步失败 / 未佩戴"混为一谈）；③ 合规风险（手环类缺项受 ARC-07/08/11「不得反转为阻断」约束，混表后被误用到"下发失败"上，或反之）。

### 4.2 四态如何用字段表达（可直接进验收）

| 四态（客户端可见） | `sync_state` | `coverage_flag` | `synced_at` | `gap_reason` | 客户端文案 |
|---|---|---|---|---|---|
| **同步中** | `syncing` | NULL | last attempt ts | NULL | "正在同步…" |
| **已同步** | `synced` | `true` | 最近一次成功 ts（精确到日） | NULL | "已采集 N 天；最近同步：YYYY-MM-DD" |
| **同步失败** | `sync_failed` | NULL | 最近一次失败 ts | `sync_failed`（②类失败） | "同步失败（原因类别 + 可执行下一步：开蓝牙 / 去授权 / 重试）" |
| **该日暂无数据** | `no_data` | **NULL（缺失）** | 最近一次成功 ts | 见 §4.4 | "该日暂无数据"（中性，**不显示"没戴"**） |

> ⚠️ **四态与 `gap_reason` 不得混用**：`gap_reason` 是**门店 / 管理端排障工具**，客户端只看到四态中的中性文案；**②类失败绝不显示"没戴 / 未佩戴 / 缺失"**（技术失败与未佩戴须在文案层彻底分开，§2.8.7②）。

### 4.3 `coverage_flag=null` 表示缺失，严禁补 0

- **语义**：`coverage_flag=true` = 该日有数据；**`coverage_flag=NULL` = 该日无数据（缺失）**。
- 🛑 **严禁自动补 0**（P0-10 验收硬约束 / §2.6.3 三条不得触碰之①）。
- **为什么必须用 NULL 而非 0**：补 0 会把"缺失"伪装成"测到 0 值" → A3 分子分母双污染、静默虚高（"缺失不得被平均或清洗"原则，*既有原则、复现于本处*）。
- **分母护栏**：A3 分母 = **台账应戴天**（`绑定日→解绑日` 扣除 `pause_period`），**不得用"数据存在性"当分母**（否则 A3 恒 1.0）。

### 4.4 `gap_reason` 全量取值范围 + 三类失败映射

**`gap_reason` 枚举（全量）**：

| 取值 | 含义 | 对应"三类失败" | A3 性质 | 是否可扣分 |
|---|---|---|---|---|
| `no_open` | 客户**未触发**（根本没打开小程序） | **①未触发** | 非失败（"客户没来"） | 否（仅覆盖率分母说明） |
| `sync_failed` | **触发但同步失败**（蓝牙/授权/电量/占用/干扰/平台） | **②触发但同步失败** | **技术性（G7）** | 否——不怪客户 |
| `not_worn` | **同步成功但确实没戴**（`isWear=0` 脱腕 / 长段规律性零数据） | **③同步成功但没戴** | **行为性** | **是——唯一可扣分情形** |
| `compliant_removal` | **合规摘除**（住院/淋浴/桑拿/游泳按说明书摘下） | （附） | 合规摘除（G8） | 否（暂停期从分母剔除） |
| `involuntary_technical` | **技术性缺失**（安卓杀进程 / iOS 系统蓝牙自动回连 / `isWear=(-1,255)` 设备异常） | — | **技术性（G7）** | 否（并入 G7） |
| `beyond_retention_window` | **超留存窗口**（补拉起始日 < today−N，设备不返回） | — | 技术性 / 不可观测 | 否（**A6 未解前一律按技术性处理**） |
| `unknown` | **原因未知**（A6 静默不返回且无原因码） | — | 优先归技术性（C2） | 否（不判行为性） |

> 🛑 **映射纪律**：**三类失败必须分列**，不得同形计入"未佩戴"——**把"客户没打开"读成"客户没戴"是本方案最大的静默风险**（§2.8.7②）。
> 🔴 **A6 未解前**：超窗口无数据（`beyond_retention_window`）+ 未知缺失（`unknown`）**一律按技术性缺失 / 不可观测处理，不得用于行为性判定**（§2.8.7③⑤）。
> **`isWear` 三值 → gap_reason 映射**：`1`→无缺口（正常）；`0`→`not_worn`（行为性）；`(-1,255)`→`involuntary_technical`（**一律不判行为性，宁可少扣不可错扣**）。

### 4.5 `synced_at` 必填的理由

- 🛑 **`synced_at` NOT NULL**：无它则 **"延迟"与"缺失"不可分辨**（W-3②）——客户端会把"昨天没数据"读成"我漏戴了"，门店会被追问。
- 客户端手环视图**必须同屏显示同步时间（精确到日）**；无同步时间 → **验收失败**（W-3）。
- 同构失效链：**R4c「客户以为已提出、系统查不到」↔ 本方案「客户以为已同步、系统没有今天的数据」** —— `synced_at` 把"看起来没同步"改写成"**时点不同**"的可见事实。
- 形态③（健康平台中转）**同步延迟最长**，无 `synced_at` 则在四端**不可分辨**。

### 4.6 N（留存窗口）改为运行时探测值 —— 落库方案

> **背景**：`getValidHistoryDates(historyType)` 让 **N 从"必须问厂商"降级为"运行时可实测"**（proto-test 报告 §TL;DR）。补拉起始日公式 `start = max(last_synced_date+1, today − N_retention)` 中的 **N 改为运行时探测值，不再硬编码**。

**★ 新增表 1：`band_sync_probe`（留存窗口探测结果）—— 待评审增项**

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| probe_id | string | PK | — |
| tenant_id | string | FK；IDX | 隔离 |
| device_id | string | FK band；IDX | 客户级手环 |
| history_type | string | CHECK | 对应 13 条历史接口类型（heartRate/step/temp/…） |
| valid_dates_json | jsonb | — | `getValidHistoryDates` 返回的**实际存在有效日期数组** |
| **retention_window_days** | int | **IDX** | **N 推定值** = `today − min(valid_dates)`（该 history_type 的实测留存窗口） |
| probe_source | string | CHECK | 运行时探测 / 厂商文档 / 保守假设 |
| probed_at | timestamptz | IDX | 探测时点 |
| is_test | bool | 默认 false | 测试/联调数据剔除标记（可正当清洗） |

**★ 新增表 2：`band_sync_log`（同步尝试日志 / 断点续传）—— 待评审增项**

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| sync_log_id | string | PK | — |
| tenant_id / device_id | string | FK；IDX | — |
| attempt_at | timestamptz | IDX | 每次 `onShow` 触发即记 |
| trigger | string | CHECK | onShow / 到店核销 / 每日填报 / 手动 |
| result | string | CHECK | success / failed |
| fail_stage | string | 可空 | **契约 7 值英文枚举为准**：`bt_off` / `unauthorized` / `connect_timeout` / `device_low_battery` / `occupied_by_vendor_app` / `platform_suspended` / `probe_out_of_window`（**2026-09-20 更正：原"蓝牙/授权/电量/占用/干扰/平台"6 项中文档已废；依 `ADR 十五 F-8/F-9` 与 `D-10`「枚举逐项落库、不得只存中文标签」，以 `contract §4.1`「同步批次契约（四态）」入参表的 `fail_reason_class` 枚举声明为权威**） |
| **date_done** | jsonb | — | **每完成一天即持久化**（不等全部完成才写） |
| **pending_dates** | jsonb | — | 失败日 + 指数退避，**不阻断其余日期** |
| consecutive_failures | int | — | 达阈值 → 标 `sync_failed` 并进下沉提示（**不静默**） |
| coverage_start / coverage_end | date | — | 本次覆盖区间 |

**★ 新增表 3：`band_daily_coverage`（逐日覆盖 / 缺口明细）—— 待评审增项**

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| coverage_id | string | PK | — |
| tenant_id / device_id / customer_id | string | FK；IDX | — |
| date | date | UNIQUE(device_id, date)；IDX | 业务日 |
| **coverage_flag** | bool | **NULL = 缺失** | 逐日落库缺失标记 |
| **gap_reason** | string | CHECK（§4.4 枚举） | 缺口归因 |
| is_wear / wear_minutes / effective_wear_minutes | int | 可空 | 来自 `isWear` + `getX04HealthIntervals` |
| **N_at_that_time** | int | 可空 | 该日补拉时使用的 N（**N 随探测刷新，须留痕**） |
| source_sync_log_id | string | FK band_sync_log | 由哪次同步回捞 |

**N 如何被补拉逻辑消费**：
1. 小程序 `onShow` → 先调 `getValidHistoryDates(historyType)` → upsert `band_sync_probe`（刷新 `retention_window_days`）。
2. 补拉引擎读 `N_retention = min(各 history_type 的 retention_window_days)`（**→ A6 未解前按保守假设 ≤7 天兜底**）→ 计算 `start = max(last_synced_date+1, today − N_retention)`。
3. 逐日 × 12 接口补拉 → upsert `band_telemetry` → 写 `band_daily_coverage`（含该日 `N_at_that_time`）。
4. **N 未取证前**：`retention_window_days` 记 **TBD**，UI 明示"最多可回捞 N 天"，**不得对外承诺具体天数**。

### 4.7 幂等键（重复拉取 = 覆盖，非追加）

| 数据类型 | 幂等键 | 说明 |
|---|---|---|
| **12 条按日型**（heartRate/step/temp/bp/spo2/pressure/met/mai/sleep/respiration/exercise/bloodSugar） | **`(device_id, metric, date, hour, minute)`** | 日聚合接口退化为 `(device_id, metric, date)`；上报前 upsert（**服务端权威**） |
| **1 条游标型**（`getSportHistory`，入参 `(false)` 单 boolean，循环条件 `sportLength > 1`） | **`(device_id, 'sport', currentSportId)`** | **单开分支**；不得套用按日型键 |

> ✅ 与"缺失不得被平均或清洗"原则**不冲突** —— 该原则**不约束重复记录去重**（去重属正当清洗）。

### 4.8 ⚠️ 能力溢出防误用（不影响 DDL，但影响字段范围）

- SDK 文档暴露血糖 / 经期 / 血压 / GPS / 联系人等 → **能力溢出 ≠ 需求**；**禁止因 SDK 暴露就写进 PRD**。
- `intake_profile.glucose / uric_acid` —— **GTL1 完全不具备** → **PRD 内部口径不一致**：P0-10 的 7 字段与附录 C `band_telemetry` 的 `{sleep, steps, resting_hr, bp, glucose, uric_acid}` **交集仅 3 项**。**待业务裁定 `bp` / `glucose` / `uric_acid` 的取舍**（data-spec §1.4 / R7）。
- `bloodSugar` / `女性健康` **一律不建字段**；`getVersion().supportSugar` 等布尔能力位**须以设备上报为准、不可按文档假设**。

---

## 五、判定引擎所需数据依赖清单（A1/A2/A3/A4 · AS · 效果判定 · 改善率）

### 5.1 依从性 AS v2（4 维 · 门槛制）

> `AS = Σ(wᵢ × sᵢ) / Σ(wᵢ)`，仅对 `applicable=True` 的维度求和。
> **`AS_refund = 0.571×A1 + 0.286×A3 + 0.143×A4`（0.571+0.286+0.143 = 1.000 ✓）** —— 三维归一权重**唯一口径**（**2026-09-20 更正：原括注「（0.20/0.70）」含义不明、与本式不符，已删除；以 `PRD config #5` 为唯一权威，ADR-11 要求判定可回放、权重口径必须唯一**）。

| 维度 | 权重 | 口径 | **依赖字段** | 来源表 | TBD / 风险 |
|---|---|---|---|---|---|
| **A1 到店履约率** | 0.40 | 实际到店 / 应到店 | `visit.executed_at` / `visit.visit_no` / `visit.customer_confirmed` / `visit.serving_store_id` | `visit` | — |
| **A2 小程序每日填报率** | 0.30 | 实填天 / 应填天 | `daily_report.date` / `source` / `answers_json` | `daily_report` | — |
| **A3 手环有效佩戴率** | 0.20 | 有效佩戴天 / 应戴天（仅"同意佩戴者"适用） | `consent.band_willingness`；`band_daily_coverage.is_wear` / `effective_wear_minutes` / `gap_reason` / `coverage_flag`；绑定日 / 解绑日；`pause_period` | `consent` / `band_daily_coverage` / `band_telemetry` | 🔴 **N / s / f 未取证（TBD）**；A3 可能长期 `applicable=False` |
| **A4 生活方式执行核对达标率** | 0.10 | 到店间隙核对达标 | `agreement.cooperation_ack` / 核对记录 | `agreement` / `visit` | — |

**A3 分子 / 分母定义（补拉模式下）**：
- **分母 `T` = 台账应戴天**（`绑定日→解绑日` 日历天数，扣 `pause_period`）—— **不得用"数据存在性"当分母**。
- **分子 `N_obs` = 补拉历史天 + 当次同步当天中判为"有效佩戴天"的天数**（历史天**计入分子**）。
- **有效佩戴天判定**：`is_wear=1` 且有效测量时长 ≥ 阈值（D2 方案，**阈值待校准**）。

### 5.2 效果判定 / 改善率

| 计算项 | 依赖字段 | 来源表 | 前置断言 |
|---|---|---|---|
| **改善率 IR** | `baseline_assessment.metrics_json`（基线）；`cycle_assessment.module_scores`（本次）；`scale_id + scale_type + scale_version` | `baseline_assessment` / `cycle_assessment` / `scale` | **同源断言**：题组 ID 同源 ∧ 量程=0–4 ∧ 测量人同一；任一不满足 → IR=NULL 转人工 |
| **`effect_verdict`（E1–E5）** | `improvement_rate` + MCID（config #33）+ 核心困扰复评 | `cycle_assessment` | `S_base=0` → 标 `baseline_zero`；`S_cur>S_base` → 保留负值不截断 |
| **`adherence_state`** | `as_value` / `as_dimensions_json` / `gap_days` | `cycle_assessment` | 应填天 < 7 → "样本不足" |
| **`disposition`（四分支出口）** | `effect_verdict` × `adherence_state` × `risk_flag` 三 enum 组合 | `verdict` | 不得混入单一 enum |
| **`migratable`** | 题组 ID / 量程版本 / 测量人 / 时间戳 **四要素齐备度** | `baseline_assessment` | 缺任一 → false → `effect_verdict=NULL` → 计入 ECC 分母、不计入分子 |
| **ECC / 可判定覆盖率** | `migratable` / `legacy` / `effect_verdict` | `baseline_assessment` / `cycle_assessment` | 三数同显（ECC / 分母 / 覆盖率） |

### 5.3 ⚠️ 当前为 TBD（未取证）的字段 / 参数清单

| # | TBD 项 | 影响 | 状态 |
|---|---|---|---|
| **TBD-1** | **N（设备留存窗口）** | 决定补拉覆盖几天 → A3 采样上界 | ❓ 待厂商 + 运行时探测（`band_sync_probe`） |
| **TBD-2** | **s（单次前台同步成功率）** | 决定观测率天花板、是否跨 `f*` | ❓ 待真机实测（`band_sync_log`） |
| **TBD-3** | **f（观测率）/ f\***（≈91.5%，**对应 `c≈0.82`，非普适阈值**） | A3 是否可算准 | ❓ 依赖 N、s |
| **TBD-4** | **A6（无数据语义）** | 决定"不补 0"能否成立 | ❓ 待厂商（未解） |
| **TBD-5** | **`isWear=(-1,255)` 触发条件 + 设备异常原因码** | 决定"脱腕"与"设备异常"能否区分 | ❓ 待厂商 |
| **TBD-6** | 有效佩戴时长阈值（D2 校准） | 决定"有效佩戴天"分子 | ❓ 待校准 |
| **TBD-7** | **未知缺失占比门阈值**（建议初始 30%） | A3 整维 `applicable=False` 触发 | ❓ 待校准 |
| **TBD-8** | **MCID（config #33，建议 ≥3 分 ≈18.8%）** | 改善判定 | 建议值，需真实数据校准 |
| **TBD-9** | **AS 阈值 0.8 / 四维权重**（config #4/#5） | 达标判定 | 建议值，需真实数据校准 |

> 🛑 **判定链降级态（须显式留痕）**：A3 不可得时 → **整维 `applicable=False` 权重重归一 → 依从性模型退化为三维 `0.80·A1 + 0.20·A4`** —— 是"零安装"的真实定价，**须记录为降级态、不得静默发生**。

---

## 六、迁移与合规要求

### 6.1 DDL 迁移可重复执行（幂等迁移）

- **建表**：`CREATE TABLE IF NOT EXISTS`；**加列**：`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`；**枚举扩值**：`ALTER TYPE ... ADD VALUE IF NOT EXISTS`（PG 语义）或走 `CHECK` 约束重建。
- **索引**：`CREATE INDEX IF NOT EXISTS`。
- **约束**：`AFTER` 校验通过后再 `ADD CONSTRAINT`；唯一键幂等键用 `ON CONFLICT (…) DO UPDATE`。
- **迁移版本表**：`schema_migration(version PK, applied_at, checksum, operator_id)` —— **每次迁移留痕、可回滚**。
- **数据回填**：`legacy`（基线创建 < 上线日）一次性回填、可重跑（幂等 UPDATE）。

### 6.2 审计字段（全表强制）

| 字段 | 类型 | 说明 |
|---|---|---|
| `created_at` | timestamptz DEFAULT now() | 全表必带 |
| `updated_at` | timestamptz | 变更即刷（触发器） |
| `created_by` / `operator_id` | string FK staff | 操作人（**代录人 / 代录时间强制落库**：`requested_at` / `recorded_at` / `recording_delay_h`） |
| `tenant_id` | string FK tenant | **全表必带**（除 tenant 根） |
| `deleted_at` | timestamptz | 软删留痕（禁物理删类） |

- **不可覆盖四类**：`plan` / `plan_review` / `baseline_assessment`（locked）/ `verdict` / `agreement.refund_clause_snapshot` / `scale` / `scale_item_bank` —— **append-only 或版本号递增**。
- **判定依据落库**：`verdict` / `cycle_assessment` / `refund` 结论须携 `evidence_snapshot` + `threshold_version`，**可回放**。

### 6.3 数据保留与脱敏

| 项 | 要求 |
|---|---|
| **脱敏授权** | `case_archive.desensitize_authorized` —— **脱敏须单独授权**（可拒绝）；未授权不得入案例库（P1-01） |
| **归档签字** | `case_archive.staff_signs` + 客户签字；**缺项 → 403 阻断结案**（ARC-13） |
| **敏感个人信息** | 心率 / 血氧 / 睡眠 / 血压属 PIPL 第 28 条敏感个人信息 → **单独同意 + 最小必要**（`consent.auth_scope_json` 分项勾选） |
| **数据落点 / 出境** | 形态①（我方服务器·境内可控）/ ②（厂商服务器）/ ③（Apple/Google）—— **形态③出境风险最高**；**待 A21（数据主权 / DPA）** |
| **免责表述** | 手环数据**一律不得表述为"医疗 / 诊断 / 疗效判定依据"**；对外须含"非医疗设备、仅供参考、不用于诊断"（`agreement` / 客户端文案层） |
| **保留期** | **待法务裁定**（本次不拍板）；建议：`refund` / `case_archive` 按举证要求长期保留（中山中院判词：凭证须可提交） |

---

## 附：DDL 示意（SQL · 关键表，字段 / 类型 / 约束为准，不必逐字可执行）

> 方言以 PostgreSQL 示意；`CHECK` 枚举逐项列全。幂等迁移用 `IF NOT EXISTS` / `ON CONFLICT`。

```sql
-- ========== 通用：迁移版本表 ==========
CREATE TABLE IF NOT EXISTS schema_migration (
  version      text PRIMARY KEY,
  applied_at   timestamptz NOT NULL DEFAULT now(),
  checksum     text,
  operator_id  text
);

-- ========== 隔离根 ==========
CREATE TABLE IF NOT EXISTS tenant (
  tenant_id   text PRIMARY KEY,
  brand_name  text NOT NULL,
  status      text NOT NULL DEFAULT 'active'
              CHECK (status IN ('active','suspended','closed')),
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- ========== 门店 ==========
CREATE TABLE IF NOT EXISTS store (
  store_id        text PRIMARY KEY,
  tenant_id       text NOT NULL REFERENCES tenant(tenant_id),
  region_id       text REFERENCES region(region_id),
  name            text NOT NULL,
  franchise_type  text NOT NULL CHECK (franchise_type IN ('直营','加盟')),
  device_model    text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz,
  created_by      text
);
CREATE INDEX IF NOT EXISTS idx_store_tenant ON store(tenant_id);
CREATE INDEX IF NOT EXISTS idx_store_franchise ON store(franchise_type);

-- ========== 门店级调理设备（1 门店 : N 台 · 下行 · 可追责） ==========
CREATE TABLE IF NOT EXISTS device (
  device_id          text PRIMARY KEY,
  tenant_id          text NOT NULL REFERENCES tenant(tenant_id),
  store_id           text NOT NULL REFERENCES store(store_id),
  model              text NOT NULL CHECK (model IN ('杠2','现有')),
  param_template_id  text,
  status             text NOT NULL DEFAULT 'active'
                     CHECK (status IN ('active','maintenance','retired')),
  created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_device_store ON device(tenant_id, store_id);
-- 🛑 本表 ≠ 客户手环；不得与 band_telemetry 合并

-- ========== 客户级穿戴设备数据（1 客户 : 1 手环 · 上行 · 缺失不得作不利依据） ==========
CREATE TABLE IF NOT EXISTS band_telemetry (
  telemetry_id   text PRIMARY KEY,
  tenant_id      text NOT NULL REFERENCES tenant(tenant_id),
  customer_id    text NOT NULL,                     -- FK customer
  device_id      text NOT NULL,                     -- 客户级手环（≠ device.device_id）；FK 目标 = band.band_id（§2.26）
  metric         text NOT NULL
                 CHECK (metric IN ('sleep','steps','hr','resting_hr','spo2','workout',
                                   'bp','temp','pressure','met','mai','respiration','exercise')),
  date           date NOT NULL,
  hour           int,
  minute         int,
  value_num      numeric,
  sleep_json     jsonb,
  coverage_flag  boolean,                           -- ⚠️ NULL = 缺失；严禁补 0
  -- ⚠️ 缺陷修正（2026-09-23）：原写 DEFAULT 'band'，而 CHECK 取值集为中文 ('手环','未接入')，
  --    默认值【永远不满足自身 CHECK】⇒ 任何省略该列的 INSERT 必报 23514。已改为 '手环'。
  --    待裁定：data_source 是否改用英文 token（对齐 metric/gap_reason/sync_state），或保留中文（对齐契约 §4.1 字面）。
  data_source    text NOT NULL DEFAULT '手环' CHECK (data_source IN ('手环','未接入')),
  gap_reason     text CHECK (gap_reason IN (
                   'no_open','sync_failed','not_worn','compliant_removal',
                   'involuntary_technical','beyond_retention_window','unknown')),
  sync_state     text NOT NULL DEFAULT 'synced'
                 CHECK (sync_state IN ('syncing','synced','sync_failed','no_data')),
  synced_at      timestamptz NOT NULL,              -- 🛑 必填：无它则"延迟"与"缺失"不可分辨
  is_wear        int,
  local_tz_offset int,
  created_at     timestamptz NOT NULL DEFAULT now(),
  -- 幂等键（12 条按日型）；运动数据走游标型键、单开分支
  UNIQUE (device_id, metric, date, hour, minute)
);
CREATE INDEX IF NOT EXISTS idx_band_cust_date ON band_telemetry(tenant_id, customer_id, date);

-- 运动数据游标型（单开分支）
CREATE TABLE IF NOT EXISTS band_sport_cursor (
  cursor_id        text PRIMARY KEY,
  tenant_id        text NOT NULL REFERENCES tenant(tenant_id),
  device_id        text NOT NULL,
  current_sport_id text NOT NULL,                   -- 幂等键 = (device_id,'sport',current_sport_id)
  value_json       jsonb,
  synced_at        timestamptz NOT NULL,
  UNIQUE (device_id, current_sport_id)
);

-- ========== ★ 新增（待评审增项）：N 运行时探测 ==========
CREATE TABLE IF NOT EXISTS band_sync_probe (
  probe_id              text PRIMARY KEY,
  tenant_id             text NOT NULL REFERENCES tenant(tenant_id),
  device_id             text NOT NULL,
  history_type          text NOT NULL,
  valid_dates_json      jsonb,
  retention_window_days int,                        -- N（未取证前 TBD）
  probe_source          text CHECK (probe_source IN ('runtime','vendor_doc','conservative_assumption')),
  probed_at             timestamptz NOT NULL DEFAULT now(),
  is_test               boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_probe_device ON band_sync_probe(tenant_id, device_id, history_type);

-- ========== ★ 新增（待评审增项）：同步尝试日志 / 断点续传 ==========
CREATE TABLE IF NOT EXISTS band_sync_log (
  sync_log_id          text PRIMARY KEY,
  tenant_id            text NOT NULL REFERENCES tenant(tenant_id),
  device_id            text NOT NULL,
  attempt_at           timestamptz NOT NULL DEFAULT now(),
  trigger              text CHECK (trigger IN ('onShow','checkin','daily_report','manual')),
  result               text CHECK (result IN ('success','failed')),
  fail_stage           text,
  date_done            jsonb,
  pending_dates        jsonb,
  consecutive_failures int NOT NULL DEFAULT 0,
  coverage_start       date,
  coverage_end         date
);

-- ========== ★ 新增（待评审增项）：逐日覆盖 / 缺口明细 ==========
CREATE TABLE IF NOT EXISTS band_daily_coverage (
  coverage_id             text PRIMARY KEY,
  tenant_id               text NOT NULL REFERENCES tenant(tenant_id),
  device_id               text NOT NULL,
  customer_id             text NOT NULL,
  date                    date NOT NULL,
  coverage_flag           boolean,                  -- NULL = 缺失
  gap_reason              text,
  is_wear                 int,
  effective_wear_minutes  int,
  N_at_that_time          int,
  source_sync_log_id      text REFERENCES band_sync_log(sync_log_id),
  UNIQUE (device_id, date)
);

-- ========== ★ 新增（BD-3 · F-3 落盘）：客户级手环实体（1 客户 : 1 有效手环 · 上行） ==========
-- 🛑 与 device（门店级调理设备 · 下行）两本台账、不得合并
CREATE TABLE IF NOT EXISTS band (
  band_id            text PRIMARY KEY,             -- = band_telemetry.device_id 的引用目标
  tenant_id          text NOT NULL REFERENCES tenant(tenant_id),
  customer_id        text NOT NULL REFERENCES customer(customer_id),
  vendor             text NOT NULL,                -- GTL1 / 其他（待厂商确认）
  model              text,
  bound_at           date NOT NULL,                -- 绑定日（A3 分母起点）
  unbound_at         date,                         -- 解绑日（分母终点）；未解绑 NULL
  unbind_reason      text CHECK (unbind_reason IN ('主动放弃','换机','设备损坏','其他')),
  status             text NOT NULL DEFAULT 'active'
                     CHECK (status IN ('active','paused','unbound','retired')),
  pause_period_json  jsonb,                        -- 合规摘除期 [{from,to,reason}]，从 A3 分母剔除
  created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_band_customer ON band(tenant_id, customer_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_band_active_customer
  ON band(tenant_id, customer_id) WHERE status = 'active';

-- ========== ★ 新增（BD-3 · F-4 落盘）：14 态状态机实体（append-only · 承载状态历史） ==========
-- 🛑 customer.status 保持 5 值不动；本表承载 14 态当前态 + 跃迁历史 + 门禁守卫
CREATE TABLE IF NOT EXISTS customer_state_transition (
  transition_id   text PRIMARY KEY,
  tenant_id       text NOT NULL REFERENCES tenant(tenant_id),
  customer_id     text NOT NULL REFERENCES customer(customer_id),
  from_state      text CHECK (from_state IN (
                    'SCREENING','REJECTED','PROFILED','CONSENTED','ASSESS_BASE',
                    'PLAN_APPROVED','AGREEMENT_SIGNED','CONFIRMED','IN_TREATMENT',
                    'CYCLE_ASSESS','PLAN_REVISING','REFUND_REVIEW','CLOSED','TERMINATED')),
  to_state        text NOT NULL CHECK (to_state IN (
                    'SCREENING','REJECTED','PROFILED','CONSENTED','ASSESS_BASE',
                    'PLAN_APPROVED','AGREEMENT_SIGNED','CONFIRMED','IN_TREATMENT',
                    'CYCLE_ASSESS','PLAN_REVISING','REFUND_REVIEW','CLOSED','TERMINATED')),
  is_current      boolean NOT NULL DEFAULT false,  -- 每客户最多 1 条
  trigger_event   text NOT NULL,
  guard_result    text NOT NULL DEFAULT 'passed' CHECK (guard_result IN ('passed','blocked')),
  missing_items   jsonb,                           -- 403 携带的缺失项名数组（X-14）
  operator_id     text,
  occurred_at     timestamptz NOT NULL DEFAULT now(),
  ref_entity      text,
  ref_id          text
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cst_current
  ON customer_state_transition(customer_id) WHERE is_current = true;
CREATE INDEX IF NOT EXISTS idx_cst_customer_time
  ON customer_state_transition(tenant_id, customer_id, occurred_at);

-- ========== 方案（版本不可覆盖） ==========
CREATE TABLE IF NOT EXISTS plan (
  plan_id        text NOT NULL,
  tenant_id      text NOT NULL REFERENCES tenant(tenant_id),
  customer_id    text NOT NULL,
  version        int  NOT NULL DEFAULT 1,
  treatment_json jsonb NOT NULL,
  lifestyle_json jsonb NOT NULL,
  intent_params  jsonb,
  status         text NOT NULL DEFAULT 'draft'
                 CHECK (status IN ('draft','reviewing','approved','superseded')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     text,
  PRIMARY KEY (plan_id, version)                     -- 🛑 版本递增、不可原地覆盖
);

-- ========== 量表（版本化 · 双库隔离） ==========
CREATE TABLE IF NOT EXISTS scale (
  scale_id           text NOT NULL,
  tenant_id          text NOT NULL REFERENCES tenant(tenant_id),
  scale_type         text NOT NULL CHECK (scale_type IN ('primary','calibration')),
  scale_version      text NOT NULL,
  name               text NOT NULL,
  dimension_set_json jsonb NOT NULL,
  status             text NOT NULL DEFAULT 'active' CHECK (status IN ('active','deprecated')),
  PRIMARY KEY (scale_id, scale_version)              -- 🛑 版本递增
);

-- ========== 退款（追加留痕 · 原因码必填 · 客户端 403） ==========
CREATE TABLE IF NOT EXISTS refund (
  refund_id         text PRIMARY KEY,
  tenant_id         text NOT NULL REFERENCES tenant(tenant_id),
  customer_id       text NOT NULL,
  entry             text NOT NULL CHECK (entry IN ('A 门店代录','B 首周期')),
  refund_route      text NOT NULL CHECK (refund_route IN ('履约类','效果类')),
  liable_store_id   text NOT NULL REFERENCES store(store_id),
  reason_code       text NOT NULL CHECK (reason_code IN (
                      '效果未达预期','症状加重或出现新不适','服务体验或沟通问题',
                      '时间/经济/家庭原因','配合度不足导致无明显变化','信任或价格异议')),
  requested_at      timestamptz,
  requested_at_claimed timestamptz,
  recorded_at       timestamptz NOT NULL DEFAULT now(),
  recording_delay_h numeric,
  sla_due_at        timestamptz,
  outcome           text NOT NULL CHECK (outcome IN ('继续','终止','归档')),
  amount_split_json jsonb,
  amount_basis      text CHECK (amount_basis IN ('协议','负责人判定','双方协商')),
  created_by        text
);
CREATE INDEX IF NOT EXISTS idx_refund_liable ON refund(tenant_id, liable_store_id);
```

---

## ✅ 行动清单

| # | 行动 | 负责方 | 依据 |
|---|---|---|---|
| 1 | **按 §一 22 实体口径建表**（纠正"20/21"计数） | 工程 | §一 出入-1 |
| 2 | `tenant_id` 全表落地 + 存储层行级拒绝（M0 双验证） | 工程 | §二 通用约定 |
| 3 | **不可覆盖四类**落地（版本递增 / 锁定 / 快照） | 工程 | §2.12 / §2.11 / §2.19 / §2.9 |
| 4 | `device` / `band_telemetry` **分表**（禁合并）；**`band` 客户级手环实体落盘**（§2.26） | 工程 | §4.1 / §2.26 |
| 5 | `band_telemetry` 幂等 upsert + 运动游标型分支（**✅ 宽 / 长表已定案 = 方案 A 长表，见 §2.17【已定案 · F-2】**） | 工程 + 技术负责人 | §4.7 / §2.17 |
| 6 | **`coverage_flag=NULL` + `synced_at` NOT NULL** 落库 | 工程 | §4.3 / §4.5 |
| 7 | `gap_reason` 全量枚举落库（§4.4 七值） | 工程 | §4.4 |
| 8 | **★ `band_sync_probe` / `band_sync_log` / `band_daily_coverage` 三表立项**（待评审增项） | 产品 + 工程 | §4.6 |
| 9 | 字段披露矩阵落 `band_visibility`（fail-closed）+ 客户端 403 | 工程 | §三 |
| 10 | 判定依赖字段先建位、**TBD 值占位不冻结** | 数析 + 工程 | §5.3 |
| 11 | 幂等迁移 + `schema_migration` 版本表 | 工程 | §6.1 |
| 12 | **14 态状态机实体建表**（§2.25 `customer_state_transition`）+ **`customer.status` 5 值映射刷新**（同事务）+ **G1/G2 门禁守卫 + 403 `missing_items[]`** | 工程 | §2.25 / §2.6 |
| 13 | **`band` 实体建表**（§2.26）+ **`band_telemetry` / `band_sync_probe` / `band_daily_coverage` 三表的 `device_id` FK 指向 `band.band_id`**（闭合悬空外键） | 工程 | §2.26 / §2.17 |

## ⚠️ 待确认 / 假设 / Non-goals

**需业务 / 产品裁定（本字典不代拍）**
1. **实体计数口径**：§8 = 22 vs 派工单"20/21" 的差异以 22 为准，**须确认**。
2. **`intake_profile` / `scale_item_bank` 是否纳入建表**（附录 C ★ 实体，关联 Q10–Q13 / Y1–Y4）。
3. **`bp` / `glucose` / `uric_acid` 取舍**（GTL1 无能力；P0-10 口径 vs 附录 C 口径冲突）。
4. **`band_sync_probe` / `band_sync_log` / `band_daily_coverage` 三表是否立项**（属待评审增项）。
5. **数据保留期与脱敏细则**（待法务）。
6. **`band_willingness_revision` 意愿变更追加表是否立项**（单向放宽扣分）。
7. **A6 / N / s 未取证前 A3 是否默认 `applicable=False`**（口径裁定）。
8. **【BD-3 · F-4】状态机实体已按 ADR 十五 F-4 落盘（§2.25 `customer_state_transition`）**，但 **`CLOSED` 触发条件（"已核销 ≥ 方案总次数"）PRD §7.2 自标"推荐默认值，需业务确认"** → **须业务方确认**；**门禁守卫仅落 PRD 明文两条（G1/G2），其余跃迁前置条件待业务 / 研发评审补充**。
9. **【BD-3 · F-3】`band` 实体已落盘（§2.26）**，但 **`vendor` / `model` 取值 + `pause_period` 申报入口（`U10`）+ `T2` 字段（`app_uninstall` 等）待评审 / 待确认**；**若研发改判为"`device_id` 非外键业务键"，须技术负责人重拍（§2.26 留痕）**。
10. **【BD-3 · F-2】宽 / 长表建模 = ✅ 已定案（2026-09-23）**（§2.17【已定案 · F-2】），**判据 J1–J5 + 拍板人 = 技术负责人**（析客契约联动），**结论 = 方案 A 长表 `metric` 化**。
11. **`band` / `customer_state_transition` 两实体的计数归属**：**均属待评审增项 / 待裁定增量，不并入封版实体基数**（封版五值不动；若纳入则实体数自 28 续增，**须走增项流程**）。

**假设**
- 手环落库以"长表 `metric`"建模（便于幂等键与 13 接口统一）；**PRD §8 原文为宽表写法** → ✅ **宽 / 长表建模选择 = 已定案（2026-09-23）= 方案 A 长表**（BD-3 · F-2），判据与拍板人见 §2.17【已定案 · F-2】；PRD §8 宽表写法为**呈现层口径**，落库以长表为准，二者非同一层。
- 时区：手环原始数据按**设备本地时区**（`local_tz_offset`）落 `date`，服务端以 UTC 存 `synced_at`。

**Non-goals**
- 本字典**不含**真机 BLE 联调、SDK 源码审计、"能否编译到 mp-weixin"判定（属第 3 批，受真机阻塞）。
- **不因 SDK 暴露血糖/经期/血压/GPS 就建字段**（能力溢出 ≠ 需求）。
- 不为"绕过微信后台限制"设计任何字段 / 机制（平台级限制不可工程绕过）。

## 📚 数据来源

| 来源 | 用途 |
|---|---|
| `prd-health-mgmt-saas-2026-09-16.md` v1.25 —— **§8** | 22 实体 + 关键字段 + 关键约束（本字典主骨架） |
| 同上 —— §2.2 / §2.3 / §2.4（X-1/X-2/X-3） | 披露矩阵、403 兜底、端形态 |
| 同上 —— §2.6 / §2.6.1 / §2.6.2（W-1/W-2/W-3）/ §2.6.3 | 手环可见性四端矩阵、三条硬约束、缺口口径 |
| 同上 —— §2.8.7（② 三类失败 / ③ 补拉 / ⑤ 三重缺失 / ⑥ 钥匙 / ⑨ Q-W1~W5） | 手环落库专章、四态、幂等、N 探测 |
| 同上 —— §10（Q1–Q16 / Q-G5）/ 附录 C.1 / C.8 / config #40·#43·#44 | 待裁定、字段级映射、双题库隔离、可见性配置 |
| `gtl1-integration-brief-2026-09-18.md` §6.3 / §8.6 / §8.7 | `device` vs `band_telemetry` 两本台账；补拉对缺口模型的影响；三类失败 |
| `_work/gtl1-data-spec-2026-09-18.md` | 字段映射矩阵、A3 失真、G0–G8 重归一化、最小字段集 M1–M7 |
| `_work/gtl1-wx-open-sync-metric-2026-09-19.md` | N / s / f 模型、`isWear` 双刃、A6 保守记账 C1–C6、三重缺失判定表 |
| `_work/gtl1-wx-open-sync-sdk-proto-test-2026-09-19.md` | 13 条历史接口、`getValidHistoryDates`→N 实测、运动游标型、幂等键依赖 |

---

*本件由数析（数据分析师）产出，2026-09-19。**只做数据规范，不做业务策略判断**；未取证指标（N / s / f）一律 TBD 占位。自证检查：§8 实体逐行清点 = **22** ✓；`AS_refund` 权重 0.571+0.286+0.143 = 1.000 ✓；幂等键覆盖 12 按日型 + 1 游标型 = 13 ✓。**本件不构成"数据可得"或"判定达标"背书** —— N / s / A6 未取证前，手环相关字段值一律按 TBD / 未缓解记账。*