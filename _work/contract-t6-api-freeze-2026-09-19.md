# 三端接口契约冻结规格（T-6）· 调元云

> ⚠️ **版本同步注记（2026-09-23）**：本件文件头「上游基准 = PRD **v1.25**」为**出具时点**的基准，**已陈旧**。
> **当前基准 = PRD `v1.33`**。本件正文此后已就地增补：**§2.9 域 I「文书模板与渲染」（占位待冻结）**、**§2.8 域 H 电子签回调缺口**。
> 两版之间与本契约相关的差异：**v1.28 `doc_template` 实体 + `config #46`** ｜ **v1.31 健康资产损益（`config #48`）** ｜ **v1.32 行号引用纪律（本件已按该纪律改用章节号定位）** ｜ **v1.33 占位符白名单**。
> **本件版本号仍为 `api-contract-v1.0.0`（冻结候选）** —— 上游基准变更**不改变契约版本**，只改变其引用的 PRD 章节范围。**权威移交入口见 `../开发移交包-上医智养堂-2026-09-23.md`。**
>
> ⚠️ **行号引用纪律（PRD v1.32 元约定）**：**正文语义区一律不得写行号**，引用一律用**章节号 / 标题 / 条目 ID** 定位 —— 因插入内容会导致行号**静默漂移**。本件原以行号锚定他件的三处已改为语义指针。

| 项目 | 内容 |
|---|---|
| **文档类型** | 接口契约冻结规格（API Contract Freeze Spec） |
| **任务编号** | **T-6**（对应 PRD §2.3 端形态增项账 T-6 / 路线图 §1.1 交付边界） |
| **日期** | **2026-09-19** |
| **版本** | **`api-contract-v1.0.0`（冻结候选）** |
| **契约 owner** | 后端主程（技术单签）· **产品共签**（析客，负责可见性/措辞档位） |
| **出具** | 析客（需求分析师）· 产品战略团队 |
| **上游基准** | PRD **v1.25**（**已同步至 v1.33，见文件头版本同步注记**）· 原型 **`prototype/index.html`**（D1 屏，CI **158** 项全绿）· 协议层测试报告（2026-09-19）· Sprint 1 派工单（S1-1 / S1-7 / S1-8） |
| **支撑任务** | **S1-1**（三端契约冻结 · D3 闸门）／**S1-7**（手环接入桩 + 日期探测契约）／**S1-8**（契约回归框架） |

---

## 📌 TL;DR

- **一件事**：把「客户(小程序) / 调理师·经络师(APP) / 管理员(Web)」三端的接口一次冻结到**字段级可编码**，作为 Sprint 1 全部下游（S1-5 / S1-6 / S1-7 / S1-8）的地基。
- **冻结不等待**：本契约**不因 Q17（端形态：跨端/原生）未签而延后** —— 选型只影响 A1 人日区间，不影响契约内容（路线图正文级条款原文见 §1）。
- **一条红线**：客户不可见的**派生字段一律"服务端 403 硬阻断 + 字段不下发"**，不是"前端不渲染"（PRD §2.4 **X-1**；原型 D1 X-1 断言）。
- **手环专章**：补拉幂等键**按日型 12 条同构、运动数据单开游标型分支**；**N（留存窗口）取运行时 `getValidHistoryDates` 探测值，禁止硬编码**；N / s / f 一律 **TBD 占位**。
- **诚实边界**：本契约为**接口规格**，不含业务策略裁定；Q10 / Q11 / Q17 / Q-W1~Q-W5 一律列入「待裁定」，**不自行拍板**。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|---|---|
| 冻结范围 | **3 端 × 7 业务域 × 32 个接口**（见 §2，**§2 表逐行清点实为 34 行**；D5 一行含 3 个端点，按端点计更多 —— **2026-09-20 更正：验收口径以「34 行 / 全端点」为准，见 D1**）；含请求/响应字段、可见性档位、错误码 |
| 冻结时点 | **Sprint 1 第 3 个工作日（D3）** —— M1 里程碑；本文档为冻结候选稿 |
| 版本号规则 | 语义化 `MAJOR.MINOR.PATCH`；可见性档位 / 字段删除 / 类型变更 = **MAJOR（破坏级）** |
| 权威可见性 | 4 字段组 × 5 角色的**四档矩阵**，**与原型 D1 逐格一致**（§3，含一致性断言） |
| 手环专章 | 同步批次四态 + 日期探测接口 + 双幂等键 + 三类失败分辨（§4） |
| 措辞纪律 | 客户可见字段名与枚举值**不得含"退款""诊断"**（§5） |
| DoD | 三端 SDK 与契约**字段逐一比对，不一致即失败**（S1-8，§6） |
| 风险等级 | **低** —— 无外部依赖；唯一 TBD 集中在手环支线（不上关键路径） |

---

## 1. 契约冻结声明

### 1.1 冻结依据（引用路线图正文级条款）

> 🔴 **路线图 §1.1 正文级条款（逐字引用）**：**「契约冻结不因端形态选型（Q17）未签而延后」** —— 三端契约与"跨端还是原生"是**两件事**：选型只影响 **A1 区间**，**不影响契约内容**。**这条防的是一类常见事故：用一个未决的外部签字，去架空一件并不依赖它的内部工作。**

**推论（本契约执行口径）**：契约内容 = **接口语义 + 字段 + 可见性 + 错误码**；端形态 = **承载方式**。二者解耦 —— **Q17 未签不阻塞本契约冻结**，仅当 Q17 裁定"改走小程序（放弃 APP）"时，**APP 端契约降级为"暂不使用"而非删除**（接口语义不变，重新启用无需重走冻结）。

### 1.2 冻结范围

| 项 | 冻结内容 | 不冻结（本版之外） |
|---|---|---|
| **端** | 小程序(客户) / APP(调理师·经络师) / Web(门店负责人·区域督导·总部运营) | 门店客服（**无端**，无任何接口） |
| **业务域** | 租户与权限 / 客户与档案 / 评估与题库 / 服务与履约 / 设备与手环数据 / 判定与稽核 / 退款与挽留 | 挂号·收费·发药·进销存（Non-goals #1） |
| **接口层** | 请求/响应字段名·类型·必填·约束 / 可见性档位 / 错误码 / 幂等键 / 分页 | 业务判定逻辑（P0-12 公式）、改善率引擎（Phase 1） |
| **契约载体** | OpenAPI 3.0 文档 + 三端 SDK 生成配置 + 本文档（人读权威版） | 前端 UI 组件、运营 SOP |
| **占位条目**（🆕 2026-09-21） | **域 H 电子签回调 `POST /api/v1/esign/callbacks/{provider}`**（见 §2.8）：**仅登记形态** —— 事件枚举（`signed` / `rejected` / `expired`）+ 幂等键（`sign_request_id` + `event_type`）+ 失败处置 + 数据边界 | **字段名 / 载荷结构 / 厂商签名算法**（待电子签厂商选定后冻结）；**本行不改变既有已冻结条目的任何字段** |
| **占位条目**（🆕 2026-09-23） | **域 I 文书模板上传 / 版本 / 渲染**（I1~I8，见 §2.9）：**仅登记形态** —— 方法与路径 + 上传约束（mime 三值 / ≤10 MiB / hash 幂等）+ 幂等键（`(tenant_id, doc_type, file_hash)`）+ 渲染规则 + 权限 + 错误码（新增 `2004`） | **字段名 / 载荷结构 / 对象存储实现（bucket / CDN / 加密）**（**待研发评审**）与**厂商渲染分工**（**待厂商选定**）；**本行不改变既有已冻结条目的任何字段** |

### 1.3 冻结时点

| 节点 | 说明 |
|---|---|
| **冻结候选** | 本文档出具日 **2026-09-19**（S1-1 启动） |
| **冻结生效（D3 闸门）** | **Sprint 1 第 3 个工作日** —— 契约评审通过后打 git tag `api-contract-v1.0.0`，**此后任何变更走 §1.4 流程** |
| **冻结前窗口** | D1~D2 为**意见收集期**，三端代表可提字段增补；D3 之后新增字段须走 MINOR |

### 1.4 版本号规则

`api-contract-v{MAJOR}.{MINOR}.{PATCH}`（语义化，与 OpenAPI `info.version` 同值）

| 段位 | 触发条件 | 示例 |
|---|---|---|
| **MAJOR（破坏级）** | ① 字段**删除 / 改名 / 类型变更**；② **可见性档位变更**（如客户侧 ③④ 放开）；③ 枚举值语义变更；④ 错误码语义变更 | `1.0.0 → 2.0.0` |
| **MINOR（兼容级）** | 新增**可选**请求字段 / 新增接口 / 新增响应字段（不改变既有字段语义） | `1.0.0 → 1.1.0` |
| **PATCH（文档级）** | 注释 / 示例 / 措辞修正，**不动字段与语义** | `1.0.0 → 1.0.1` |

### 1.5 变更流程（谁能改 / 怎么改 / 如何留痕）

| 级别 | 谁签 | 前置 | 留痕 |
|---|---|---|---|
| **PATCH** | 契约 owner **单签** | 无 | CHANGELOG 追加 + PR |
| **MINOR** | owner **+ 三端前端代表** | 无 | CHANGELOG + OpenAPI diff + git tag |
| **MAJOR** | owner **+ 产品（析客）共签**；**若涉可见性档位 → 触发「变更业务裁定」**（※ 与 PRD §2.2 条款 B / §2.6.7 R15 同级处理，**不得按"体验优化"处理**） | 三端联调评审通过 | CHANGELOG + OpenAPI diff + **重走冻结**（新的 D3 式闸门） |

**留痕字段（强制）**：`谁改 / 何时 / 为什么 / 影响哪些在服务客户`（沿用 PRD config #40 / #43 变更日志口径）。

> ⚠️ **最高价值防护条款**：**客户侧 ③④ 字段（缺口原因 / 派生结论）为硬约束，任何放开请求一律走 MAJOR + 变更业务裁定** —— 与 PRD §2.6.7 **R15**、config **#43** 注脚同源，**防"顺手多给一点"**。

---

## 2. 三端接口清单（字段级）

### 2.0 全局约定（所有接口适用）

| 项 | 约定 |
|---|---|
| **Base Path** | `/api/v1` |
| **认证** | `Authorization: Bearer <token>`；token 内嵌 `tenant_id / role / staff_id` |
| **租户上下文** | 服务端从 token 取 `tenant_id`；请求头 `X-Tenant-Id` 若存在**必须与 token 一致**，否则 **403 TENANT_MISMATCH**。**跨租户资源一律 403**（存储层直接拒绝，**不返回 404 以避免存在性探测**） |
| **响应信封** | `{ "code": 0, "message": "ok", "data": {...}, "trace_id": "..." }`；**`code != 0` 时 `data` 为空** |
| **分页** | 请求 `?page=<int>&page_size=<int≤100>`；响应 `data.{items[], total, page, page_size}` |
| **字段可见性** | 每个接口的响应字段按 §3 矩阵**在服务端裁剪**；无权限字段**不下发**（**不是 null、不是空串**） |
| **403 语义** | 「前置门禁缺失」与「可见性档位不足」统一返回 **403**，`message` 必须**给出缺失项名称 / 档位名称**（PRD P0-08 / §7.2：**不得模糊报错**） |
| **幂等** | 写接口接受 `Idempotency-Key` 请求头（UUID）；服务端在 24h 窗口内去重 |

**统一错误码表**：

| HTTP | `code` | 名称 | 触发条件（摘要） |
|---|---|---|---|
| 400 | 1001 | `VALIDATION_FAILED` | 参数类型/必填/约束不满足 |
| 401 | 1002 | `UNAUTHENTICATED` | 无 token / token 过期 |
| **403** | **2001** | **`VISIBILITY_DENIED`** | **该角色对请求字段组无可见性档位**（§3 矩阵解算为 ❌） |
| **403** | **2002** | **`GATE_MISSING`** | **前置门禁缺失**（禁忌未通过 / 同意书未签 / 协议未签 / 方案未确认），`data.missing_items[]` 给出缺失项名 |
| **403** | **2003** | **`TENANT_MISMATCH`** | 跨租户访问 / 租户头与 token 不符 |
| 404 | 3001 | `NOT_FOUND` | 资源不存在（**仅限本租户内确实不存在**） |
| 409 | 4001 | `VERSION_CONFLICT` | 版本不可覆盖（plan / scale_item_bank / verdict） |
| 409 | 4002 | `IDEMPOTENT_REPLAY` | 幂等键命中已有记录（返回首次结果） |
| 422 | 5001 | `BUSINESS_RULE_VIOLATED` | 业务规则不满足（如题组量程 ≠ 0–4） |
| 429 | 6001 | `RATE_LIMITED` | 触发节流（如 `onShow` 5 分钟窗口） |
| 500 | 9001 | `INTERNAL_ERROR` | 服务端异常 |

---

### 2.1 域 A · 租户与权限

| # | 方法 + 路径 | 说明 |
|---|---|---|
| A1 | `POST /api/v1/auth/login` | 登录，签发 token |
| A2 | `GET /api/v1/auth/me` | 当前身份 + 角色 + **可见性档位解算结果** |
| A3 | `GET /api/v1/stores` | 门店列表（按行级 scope 过滤） |

**A1** `POST /api/v1/auth/login`

| 入参 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `account` | string | 是 | 员工号 / 客户 openid 绑定号 |
| `credential` | string | 是 | 密码 / 短信码 |
| `client_end` | enum{`mp`,`app`,`web`} | 是 | 用于校验端形态白名单 |

```json
// 响应 data
{
  "token": "eyJ...",
  "expires_in": 7200,
  "role": "meridian",          // 客户=client / 调理师=therapist / 经络师=meridian / 门店负责人=manager / 区域督导=area / 总部运营=hq
  "client_end": "app",
  "tenant_id": "T-1001",
  "staff_id": "S-233"
}
```

| 可见性 | 客户(小程序) | 调理师·经络师(APP) | 管理员(Web) |
|---|---|---|---|
| 字段 | `token`/`role`/`client_end` ✅ | 同左 ✅ | 同左 ✅ |
| 差异 | 客户 `role=client`，无 `staff_id` | 有 `staff_id` | 有 `staff_id` + `store_scope` |

**错误码**：`1001`（缺参）｜`1002`（凭证错误）｜**`2003`**（`client_end` 与账号端形态不符）。

**A2** `GET /api/v1/auth/me` —— **可见性档位唯一权威下发点**

```json
// 响应 data
{
  "role": "therapist",
  "band_visibility": { "field_group_1_raw": true, "field_group_2_status": true,
                       "field_group_3_gap_reason": true, "field_group_4_derived": true },
  "refund_visibility": false,          // 调理师 ❌（config #40）
  "store_scope": { "row_level": "own_store", "store_ids": ["ST-01"] }
}
```

| 可见性 | 客户(小程序) | 调理师·经络师(APP) | 管理员(Web) |
|---|---|---|---|
| `band_visibility` | `{1:true, 2:true, 3:false, 4:false}` | `{1:true,2:true,3:true,4:true}` | `{1:true,2:true,3:true,4:true}` |
| `refund_visibility` | **false** | 调理师 false / **经络师 true** | true |
| `store_scope.row_level` | n/a（仅本人） | own_store | 门店负责人 own_store／区域督导 region／总部 all |

> ⚠️ **`A2` 只下发"档位布尔值"，不下发任何业务字段** —— 它是**声明**，不是数据。**三端 UI 依此声明决定渲染分支，但字段仍由服务端裁剪**（X-1：声明 ≠ 授权，二者须一致）。

---

### 2.2 域 B · 客户与档案

| # | 方法 + 路径 | 说明 | 门禁 |
|---|---|---|---|
| B1 | `POST /api/v1/screening-records` | 禁忌筛查提交 | 硬门禁① |
| B2 | `POST /api/v1/customers` | 建档（`POST /archive` 语义） | 需 `screening_result=通过` |
| B3 | `POST /api/v1/customers/{id}/consents` | 签知情同意书 | 需 `PROFILED` |
| B4 | `GET /api/v1/customers/{id}` | 客户详情（**含可见性裁剪**） | — |
| B5 | `GET /api/v1/customers/{id}/intake-profile` | 建档扩展档案 | — |
| B6 | `PATCH /api/v1/customers/{id}/intake-profile` | 补充 + 修订（**append-only 留痕，不可覆盖**） | — |

**B1** `POST /api/v1/screening-records`

| 入参 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `customer_id` | string | 是 | — |
| `items_json` | object | 是 | 含 `pregnancy` / `acute` / `risk_history` / `nonmedical_disclosed`（枚举见 PRD 附录 C.1.2） |
| `operator_id` | ref staff | 是 | 服务端从 token 覆写 |
| `customer_sign` / `operator_sign` | string | 是 | 电子签 |

| 出参 | 类型 | 可见性（客户/APP/Web） |
|---|---|---|
| `screening_id` | string | ✅ / ✅ / ✅ |
| `result` | enum{`通过`,`不通过`} | ✅ / ✅ / ✅ |
| `submitted_at` | datetime | ✅ / ✅ / ✅ |

> **命中禁忌（`result=不通过`）→ 客户状态置 `REJECTED`**，后续 B2/B3 入口 **403 `GATE_MISSING`**（`missing_items=["screening_result"]`）。记录**不可删除**。

**B2** `POST /api/v1/customers`

| 入参 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `name` | string | 是 | 与手机号去重 |
| `gender` | enum{男,女} | 是 | — |
| `age` | int | 是 | 0<age<120 |
| `phone` | string | 是 | 租户内唯一（跨店识别键） |
| `screening_id` | string | 是 | **服务端校验其 `result=通过`** |

| 出参 | 类型 | 可见性 |
|---|---|---|
| `customer_id` | string | ✅ / ✅ / ✅ |
| `status` | enum{`CREATED`,`PROFILED`,`CONSENTED`,`REJECTED`,`ARCHIVED`} | ✅ / ✅ / ✅ |
| `owner_store_id` | string | **不下发** / ✅ / ✅ |
| `serving_store_id` | string | **不下发** / ✅ / ✅ |

**错误码**：**`2002`**（`screening_result` 缺失/未通过，`missing_items=["screening_result"]`）。

> **【2026-09-23 裁定落地 · R3】`customer.status` 枚举补全（`enum{PROFILED,...}` → 5 值权威拼写）**
> - **原状态**：本件 B3 出参此格原写作 `enum{`PROFILED`,...}` —— **省略号未列全**，三端联调时易各自补一套，属**歧义源**。
> - **裁定结论**：`customer.status` 权威取值 = **数据字典 §2.6 的 5 值粗粒度态**：`CREATED / PROFILED / CONSENTED / REJECTED / ARCHIVED`（**保持 5 值不动**）。
> - **口径澄清（防误读）**：该 5 值是**派生聚合态**，**不是服务主状态机**；14 态主状态机（`SCREENING / REJECTED / PROFILED / CONSENTED / … / CLOSED / TERMINATED`）落在 `customer_state_transition`（字典 §2.25），二者**显式映射**见字典 §2.25：
>   - `CREATED ↔ SCREENING`（名称不同、语义一一对应）；`PROFILED` / `REJECTED` 名称同义；`CONSENTED` 为**一对多聚合**（服务期内所有活跃态均归 `CONSENTED`）；`ARCHIVED` 为 `CLOSED` / `TERMINATED` 两终态之聚合。
> - **基线一致性说明**：骨架 `V1__baseline_tenant_rls.sql` 的 `customer.status` 现值（`pending / active / paused / archived / closed`）为**骨架占位基线**，**与本 5 值权威集不一致**，属**已登记待改项**（见 `verification/94_b12_assert.sql` B6 注释：V2 对 `customer` 表零 DDL 改动）。**本契约以字典 5 值为权威口径**；骨架基线的对齐由后续增项迁移执行，**不在本次裁定范围内**。
> - **影响面**：**仅补全枚举字面**（口径未变），按 §1.4 属**文档级澄清**；**不改任何可见性档位**。

**B4** `GET /api/v1/customers/{id}` —— **可见性裁剪重点接口**

| 出参字段 | 类型 | 客户(小程序) | 调理师·经络师(APP) | 管理员(Web) |
|---|---|---|---|---|
| `customer_id` / `name` / `gender` / `age` | — | ✅ | ✅ | ✅ |
| `intake_profile.*`（体征/生活方式/经络自述） | object | ✅ | ✅ | ✅ |
| `screening_record.result` | enum | ✅ | ✅ | ✅ |
| `band_willingness` | enum{自愿佩戴,暂不佩戴} | ✅ | ✅ | ✅ |
| `owner_store_id` / `serving_store_id` | string | **不下发** | ✅ | ✅ |
| `effect_verdict`（派生） | enum | **403 / 不下发** | ✅ | ✅ |
| `as_value`（派生） | decimal | **403 / 不下发** | ✅ | ✅ |

> 🔴 **客户 token 请求含派生字段的 query（如 `?include=verdict`）→ 403 `VISIBILITY_DENIED`**，`data.denied_fields=["effect_verdict","as_value"]`。

---

### 2.3 域 C · 评估与题库

| # | 方法 + 路径 | 说明 |
|---|---|---|
| C1 | `GET /api/v1/scale-item-banks` | 题库拉取（按分龄组 + 维度） |
| C2 | `POST /api/v1/customers/{id}/assessments/baseline` | 基线评估提交 |
| C3 | `GET /api/v1/customers/{id}/assessments/{assessment_id}` | 评估详情 |
| C4 | `POST /api/v1/customers/{id}/cycle-assessments` | 周期评估提交（每 7 次触发） |

**C2** 入参关键字段：`scale_id` / `item_group_id`（**同源题组锁定**）/ `age_group_locked` / `dimension_scores[7]`（各 0–16）/ `total_score`（0–112）/ `measure_operator`（**同源校验**）。

| 出参 | 类型 | 客户 | APP | Web |
|---|---|---|---|---|
| `assessment_id` / `assessed_at` | — | ✅ | ✅ | ✅ |
| `baseline_conclusion.好转判断` | enum | ✅ | ✅ | ✅ |
| `migratable` | bool | **不下发** | ✅ | ✅ |
| `dimension_scores[7]` | int[] | ✅（**仅本人填答维度**） | ✅ | ✅ |

**错误码**：`5001`（题组量程 ≠ 0–4 / 题组非 `symptom` 向 / 缺分龄锁定）→ **提交阻断**（PRD P0-04 / P0-18）。

---

### 2.4 域 D · 服务与履约

| # | 方法 + 路径 | 说明 | 门禁 |
|---|---|---|---|
| D1 | `POST /api/v1/customers/{id}/visits` | 服务核销 | **四道闸门** |
| D2 | `GET /api/v1/customers/{id}/visits` | 服务记录（客户维度全局账本） | — |
| D3 | `POST /api/v1/customers/{id}/daily-reports` | 每日填报提交 | — |
| D4 | `GET /api/v1/customers/{id}/daily-reports` | 填报记录 | — |
| D5 | `POST /api/v1/plans` / `GET .../plans/{id}` / `POST .../plans/{id}/reviews` | 方案出具 / 查阅 / 审核 | 回炉审核 |
| D6 | `POST /api/v1/device-dispatches` | 设备参数下发 | 需方案已审核 |

**D1** `POST /api/v1/customers/{id}/visits`

| 出参 | 类型 | 客户 | APP | Web |
|---|---|---|---|---|
| `visit_id` / `visit_no` / `executed_at` | — | ✅ | ✅ | ✅ |
| `serving_store_id` | string | ✅（标记服务门店） | ✅ | ✅ |
| `gate_check_json` | object | **不下发** | ✅ | ✅ |
| `customer_confirmed` | bool | ✅ | ✅ | ✅ |
| `abnormal_note` | text | **不下发** | ✅ | ✅ |

**错误码**：**`2002`**（四闸门任一缺失，`missing_items=["agreement_signed"]` 等）｜`4001`（`plan_version` 陈旧）。

**D3** `POST /api/v1/customers/{id}/daily-reports`

| 入参 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `date` | date | 是 | 补填窗口 ≤2 天（可配置） |
| `answers_json` | object | 是 | **全点选，无开放输入** |
| `source` | enum{`客户`,`代核`} | 是 | 代录须标 `代核` |

| 出参 | 类型 | 客户 | APP | Web |
|---|---|---|---|---|
| `report_id` / `date` | — | ✅ | ✅ | ✅ |
| `weekly_count`（本周 X/7 天） | int | ✅ | ✅ | ✅ |
| `last_week_compare` | object | ✅ | ✅ | ✅ |
| `suggestion`（一条可执行建议） | text | ✅ | ✅ | ✅ |
| `source` | enum | ✅ | ✅ | ✅ |

---

### 2.5 域 E · 设备与手环数据（**详见 §4 专章**）

| # | 方法 + 路径 | 说明 | 端 |
|---|---|---|---|
| E1 | `POST /api/v1/band/sync-batches` | 客户端上报同步批次（四态） | 小程序 |
| E2 | `POST /api/v1/band/telemetry` | 客户端上行逐日采集数据（幂等 upsert） | 小程序 |
| E3 | `GET /api/v1/customers/{id}/band/telemetry` | 手环原始数据 + 采集状态（四端按档裁剪） | 全部 |
| E4 | `GET /api/v1/customers/{id}/band/derived` | 派生结果（A3 / AS / effect_verdict / 退款资格） | **仅 staff** |
| E5 | `POST /api/v1/band/available-dates` | 设备可用日期探测结果上报 | 小程序 |
| E6 | `GET /api/v1/customers/{id}/band/sync-status` | 客户端同步状态卡（四态） | 小程序 |

---

### 2.6 域 F · 判定与稽核

| # | 方法 + 路径 | 说明 |
|---|---|---|
| F1 | `POST /api/v1/cycle-assessments/{id}/verdicts` | 判定结论落库（含依据快照 + 阈值版本） |
| F2 | `GET /api/v1/customers/{id}/verdicts` | 判定历史 |
| F3 | `GET /api/v1/audit/signals` | 稽核信号（**总部 / 区域；门店与加盟商不可见**） |
| F4 | `GET /api/v1/audit/coverage` | 可判定覆盖率（三数同显，Phase 2 P1-08） |

**F1** 出参：`verdict_id` / `branch`（四分支）/ `confidence` / `evidence_snapshot` / `threshold_version` / `decided_at`。

| 可见性 | 客户 | APP | Web |
|---|---|---|---|
| `branch` / `confidence` / `evidence_snapshot` | **403 `VISIBILITY_DENIED`** | 调理师 ⚠️（**不得作对客户不利依据**）· 经络师 ✅ | ✅（行级 scope） |

**F3 / F4**：**客户与调理师端一律 403**；门店负责人仅本店、区域督导仅辖区、总部全量。**稽核信号对门店与加盟商完全不可见**（PRD P1-04）；**可判定覆盖率告警动作归 P1-04、对门店不可见**，卡片可给门店（仅本店、三数同显）。

---

### 2.7 域 G · 退款与挽留

| # | 方法 + 路径 | 说明 |
|---|---|---|
| G1 | `POST /api/v1/refunds` | **代录**客户退款诉求（发起方 = 经络师 / 门店负责人） |
| G2 | `GET /api/v1/refunds/{id}` | 退款工单详情 |
| G3 | `POST /api/v1/refunds/{id}/retentions` | 挽留记录 |
| G4 | `POST /api/v1/refunds/{id}/approvals` | 审批（出口集中） |
| G5 | `POST /api/v1/refunds/{id}/receipts` | 回执（三态留痕） |

> 🔴 **本域全部接口：客户端与调理师端一律 403 `VISIBILITY_DENIED`**（PRD §2.2 + §2.4 **X-1 / X-2 / X-3**）。
>
> **X-2 落地（build-time）**：小程序代码包内**不得包含本域任何接口路径、字段名、"退款"字样** —— 构建期扫描命中即阻断。

**G1** 入参：`customer_id` / `entry`（`A门店代录`/`B首周期`）/ `refund_route`（`履约类`/`效果类`）/ `reason_code`（必填）/ `requested_at`（**最早且可核实**）/ `requested_at_claimed`（客户主张，仅留存不计时）/ `customer_statement`（**append-only 原话**）。
出参：`refund_id` / `liable_store_id` / `sla_due_at` / `recording_delay_h` / `outcome`。

**G5** 出参 `receipt_state`（三态）：`已推送` / `未授权（转线下）` / `推送失败`。

| 可见性 | 客户 | 调理师 | 经络师 | 门店负责人 | 区域督导 | 总部 |
|---|---|---|---|---|---|---|
| G 域全部 | **403** | **403** | ✅ | ✅ | ✅（可见不审批） | ✅ |

**错误码**：`2001`（非授权角色）｜`1001`（`reason_code` 缺失，**未记录原因不可结案**）。

---

### 2.8 域 H · 文书与电子签（🆕 2026-09-21 新增 · 占位待冻结）

> **为什么补这一节**：电子签（签署《知情同意书》《调理协议书》）是 **Phase 0 四条硬前置之一**，卡住 **③ 签署门禁收口**（选型评估见 `_work/esign-vendor-eval-2026-09-21.md`）。但截至本版，**「签署完成 / 拒绝签署 / 过期未签」三个事件如何进入我方系统，在本契约中无任何条目（grep `esign` / `callback` / `回调` = 0 命中）** —— 契约是三端并行开发的唯一接口真相源，缺这条 = 电子签集成**无可并行开发的冻结接口**。
>
> 🛑 **本节为占位条目：已登记形态，字段未冻结。** 本节只冻结**形态**（事件枚举 / 幂等键 / 失败处置 / 数据边界），**不冻结任何字段名、载荷结构、签名算法**；**亦不改变 §2.1~§2.7 任何已冻结条目的字段与语义**。**厂商选定之前，三端不得据本节编码**（仅可据本节预留处理入口）。

| # | 方法 + 路径 | 说明 |
|---|---|---|
| H1 | `POST /api/v1/esign/callbacks/{provider}` | **厂商回调接收端点（占位 · 待厂商选定后冻结）** |

**H1** `POST /api/v1/esign/callbacks/{provider}` —— **服务端对服务端，无客户端可见面**

> 路径取 §2.0 全局约定 `Base Path = /api/v1` 之下；`{provider}` 取值域（如 e签宝 / 腾讯电子签 / 法大大）**与厂商选定同步冻结**。

**事件枚举（本节冻结的形态之一，`event_type` 三值）**：

| `event_type` | 语义 | 我方文书状态处置 |
|---|---|---|
| `signed` | **签署完成** | 推进文书状态 → 放行 06 屏门禁（`PLAN_APPROVED → AGREEMENT_SIGNED`） |
| `rejected` | **拒绝签署** | **保持未签**，**不推进门禁**；登记「拒绝签署」事实 |
| `expired` | **过期未签** | **保持未签**，**不推进门禁**；登记「过期未签」事实 |

**关键约定（逐条）**：

| 项 | 约定 |
|---|---|
| **厂商签名校验** | **必须校验，未通过验签的载荷一律拒绝**。**签名算法（HMAC / 非对称 / 厂商自有时间戳签名等）各家不同，须逐家核实** —— **本契约不预设、不编造算法名**，**需与服务商确认**后方可冻结。 |
| **幂等键** | **`sign_request_id` + `event_type`** —— 同一键**重复投递必须幂等**（返回首次处理结果，语义对齐 §2.0 错误码 `4002 IDEMPOTENT_REPLAY`）。 |
| **失败态不得静默** | 回调**处理失败须可重放 / 可对账**，**不得只写日志**。须明确厂商**重试策略**与**是否支持主动查询补数**（能力有无与语义**需与服务商确认**）；我方本地须留**可对账的入站记录**（非埋点）。 |
| **多租户反解** | 单一回调 URL 收全租户通知，**必须能反解到租户**。依赖厂商支持**透传自定义业务编号** —— **该字段名与是否支持需与服务商确认**，**不得预设**。 |
| 🛑 **数据边界（硬约束）** | **回调载荷不得承载任何健康数据 / 禁忌结论** —— 载荷**允许承载范围（白名单）**仅为「签署事件 + 文书标识 + 租户标识 + 时间戳」；健康相关数据**一律不出租户边界**（与 PRD Q6 口径一致）。**租户标识的取得方式见下行「多租户反解」（厂商透传字段能力未取证，本行不预设其存在）。** **本节与该口径的直接冲突点见文末「待裁定」Q-E1。** |
| **联动（落业务表 + 留痕）** | 文书状态变更**须落到业务表实体**（协议 / 同意书签署记录）并**留痕 `created_by` / `created_at`**，**而非只写日志或埋点**。`signed` 的消费方 = **§2.2 B3**（签知情同意书，`customer_sign` / `operator_sign`）与 **§2.4 D1** 四道闸门（`missing_items=["agreement_signed"]`）。**`signed` 事件所消费的文书，其签署稿由 §2.9 域 I 的 I8 渲染产出**（`POST /api/v1/agreements/{id}/render`：按 `placeholder_schema` 带入白名单值 → 落 `rendered_snapshot` + `rendered_hash` → 绑定 `doc_template_id` / `doc_template_version`）。 |
| **防腐层** | 回调处理**须经 `ESignAdapter`**（`createFlow` / `queryFlow` / `verifyCallback` / `evidenceHash` 四方法，见选型件 §3.3）—— 厂商切换时**业务代码零改动**。 |

> ⚠️ **措辞纪律自查（§5）**：本域**不含任何客户端可见接口**（回调为服务端对服务端），现行无客户端可见字段；若后续因签署入口在客户端暴露字段，**字段名 / 枚举名 / 文案须过 §5.1 R1~R8 校验**（含 `client_end=mp` 可见面的词表校验）。

---

### 2.9 域 I · 文书模板与渲染（🆕 2026-09-23 新增 · 占位待冻结）

> **为什么补这一节**：文书承载形态经用户（产品负责人）**2026-09-23 裁定** —— **①** 法律文书由我方确认后**「上传到平台」**；**②** 承载形态 = **双形态（上传文件 + 在线编辑）**；**③** 正文可填项须**系统自动带入**（渲染期带入，不靠人工抄写）。而现行 **§2 域 A~H 无任何文书模板的读写 / 上传 / 版本 / 渲染接口** —— 「上传」这一动作**在契约层无处可接**，卡住 06 屏「协议书签署」的正文呈现与签署稿产出。
>
> 🛑 **本节为占位条目：已登记形态，字段未冻结。** 本节只冻结**形态**（方法与路径 / 上传与渲染的关键约定 / 错误码 / 数据边界），**不冻结任何字段名、载荷结构、请求体细节**；**亦不改变 §2.1~§2.8 任何已冻结条目的字段与语义**。**对象存储实现（bucket / CDN / 加密方式）待研发评审；厂商渲染分工待厂商选定** —— 二者冻结之前，三端不得据本节编码（仅可据本节预留处理入口）。

| # | 方法 + 路径 | 说明 | 可见角色 |
|---|---|---|---|
| **I1** | `GET /api/v1/doc-templates?doc_type=&is_active=` | 模板列表（按 `doc_type` 过滤） | 超管（全）；**门店负责人 / 区域督导只读**（仅 `is_active=true`，仅 `title` + `version`，**不含 `content` / `file_ref`**） |
| **I2** | `POST /api/v1/doc-templates` | 新建（**`source_type=editor`**，JSON 正文） | 超管 |
| **I3** | `POST /api/v1/doc-templates/uploads` | **上传（`multipart/form-data` → `source_type=upload`）** | 超管 |
| **I4** | `POST /api/v1/doc-templates/{id}/versions` | 新版本（editor / upload 均可）；**不可覆盖** | 超管 |
| **I5** | `GET /api/v1/doc-templates/{id}/versions` | 版本列表（含 `source_type` / `file_hash` / `change_reason`，**不含正文**） | 超管 |
| **I6** | `POST /api/v1/doc-templates/{id}/versions/{version}/publish` | 发布（置 `is_active=true`，同 `doc_type` 其他版本置 `false`） | 超管 |
| **I7** | `GET /api/v1/doc-templates/{id}/download?version=` | 下载原件（**上传形态用**） | **仅超管** |
| **I8** | `POST /api/v1/agreements/{id}/render` | **签署时渲染**：按 `placeholder_schema` 带入白名单值 → 生成 `rendered_snapshot` + `rendered_hash` → 绑定 `doc_template_id` / `doc_template_version` | **服务端 → 电子签厂商**（无客户端可见面） |

**关键约定（逐条）**：

| 项 | 约定 |
|---|---|
| **I3 上传约束** | `mime_type` **仅允许三值**（`application/pdf` / `application/vnd.openxmlformats-officedocument.wordprocessingml.document`（.docx）/ `text/markdown`）；`file_size` **≤ 10 MiB**；**落盘前先算 `file_hash`（SHA-256 hex）**，同 `<tenant, doc_type>` 下**同 hash 重复上传 → 返回已有版本号（幂等）** |
| **I3 幂等键** | **`(tenant_id, doc_type, file_hash)`** —— 重复投递语义对齐 §2.0 错误码 `4002 IDEMPOTENT_REPLAY` |
| 🛑 **反病毒 / 反脚本** | 上传文件**不得作为可执行内容被服务端解析执行**；DOCX **不得解压执行宏**（**只做字节存储 + 哈希**，**不做宏解析**）；渲染期**只做文本占位符替换，不做 DOCX 内嵌逻辑求值** |
| 🛑 **QC-6 不变量** | 模板正文（含上传件）**一律不得作为客户端包资产下发** —— 唯一落点 = 服务端 `doc_template.content` / `file_ref`；签署经**服务端渲染页 / 厂商侧页面**呈现 |
| **I8 渲染规则** | ① 占位符**只从白名单取值**，未声明 `placeholder_schema` 时**只允许零占位符**（fail-closed）；② **未在白名单的占位符 → 报错，不静默留空**；③ `customer.phone` **强制脱敏**（前 3 后 4，原名不得出现在文书内）；④ 渲染结果**必须落快照 + hash**（否则事后无法证明签的是哪一版） |
| **权限** | I2~I7 = **`super_admin`（tenant 级，不跨租户）** —— 沿用 PRD §2.2 定义；**I1 对门店负责人 / 区域督导只读**（P0-27「门店只读」） |
| **客户端零面** | 域 I **不含任何客户端可见接口**；若将来在客户端暴露，须过契约 §5.1 R1~R8 词表校验 + ADR-12 变更 |

**错误码（沿用 §2.0 既有码 + 新增一条）：**

| 码 | 语义 |
|---|---|
| `2001` | 非授权角色（门店调 I2~I7） |
| `4002` | 幂等重放（I3 同 hash） |
| `2003` | `mime_type` / `file_size` 越界 |
| **`2004`** 🆕 | **占位符越界**（模板含未在白名单的占位符） |

> ⚠️ **待研发评审 / 待厂商选定（不得写成结论）**：**对象存储实现**（bucket 命名 / CDN / 静态加密方式）与**厂商渲染分工**（渲染在我方服务端还是厂商侧）**均未取证**，本契约**只登记形态**，实现细节**一律标「待研发评审」/「待厂商选定」**。
>
> ⚠️ **占位符白名单的边界（不得越界表述）**：渲染白名单**只允许零占位符或白名单内取值**；白名单**使「健康类取值被无意渲染进送签文书」在机制上不可能发生**，但**不构成 Q-E1 的裁定**（Q-E1 仍为业务方 + 法务问题，见文末「待裁定」）。

> ⚠️ **措辞纪律自查（§5）**：本域**不含任何客户端可见接口**（I8 为服务端对服务端 / 厂商侧，无客户端可见面），现行无客户端可见字段；若后续因正文呈现需求在客户端暴露字段，**字段名 / 枚举名 / 文案须过 §5.1 R1~R8 校验**（含 `client_end=mp` 可见面的词表校验）。

---

## 3. 字段可见性矩阵（权威版）

> **本矩阵为契约级权威版**，直接搬自原型 **D1「客户管理详情 · 手环反馈数据」屏**的「四端可见性矩阵（手环数据 × 字段组）」，**配置化见 config #43 `band_visibility`**（按「角色 × 字段组」配置，**非按端**；fail-closed；变更留痕）。

### 3.1 四档 × 五角色（**与原型 D1 一致**）

**字段组定义**：
- **① 手环原始数据** = 睡眠 / 步数 / 心率 / 静息心率 / 血氧 / 运动类型
- **② 采集状态** = 已采集天数 / 同步时间（`synced_at`，精确到日）/ 接入状态
- **③ 缺口原因分类** = `gap_reason`
- **④ 派生结果** = 依从性维度分（含 **A3**）/ `AS` 值 / `effect_verdict` / 退款资格

| 端 / 角色 | ① 原始数据 | ② 采集状态 | ③ 缺口原因 | ④ 派生结果 |
|---|---|---|---|---|
| **客户端（小程序 · 客户）** | ✅ 可见 | ✅ 可见 | ❌ **不可见** | ❌ **不可见** |
| **调理师（APP）** | ✅ 可见 | ✅ 可见 | ✅ 可见 | ✅ 可见 · **不作不利依据** |
| **经络师（APP）** | ✅ 可见 | ✅ 可见 | ✅ 可见 | ✅ 可见 |
| **管理端（Web · 门店负责人 / 区域督导 / 总部运营）** | ✅ 可见 | ✅ 可见 | ✅ 可见 | ✅ 可见 · **按行级范围**（本店 / 辖区 / 全量） |
| **门店客服** | 无端（不使用系统）· **该界面不成立** | — | — | — |

> ✅ **契约一致性断言（逐格比对，可复核）**：
> 1. 本矩阵与**原型 D1 四端矩阵逐格一致**（① 原始 / ② 采集 / ③ 缺口 / ④ 派生 × 5 角色）；
> 2. 本矩阵与 **config #43 `band_visibility` 建议当前值逐格一致**（客户 ①②✅ ③④❌；调理师 ①②③④ 但 ④ 不作不利依据；经络师 ①②③④；管理端 ①②③④ 按行级 scope；门店客服无端）；
> 3. 本矩阵与 **PRD §2.6.1** 一致（客户 ③④ ❌；调理师 ④ ⚠️ 不作不利依据；管理端行级 scope）；
> 4. 本矩阵与 **S1-1 派工单 §二「必须一次定死的字段组」**一致（手环原始数据 客户✅仅数据本身；依从性/AS/效果判定 客户❌；`gap_reason` 客户❌；手环接入状态 客户✅）。
>
> **结论：四源一致，无冲突。**

### 3.2 客户不可见派生字段的阻断方式（**契约级，非 UI 级**）

| 要求 | 契约表述 |
|---|---|
| **服务端 403 硬阻断** | 客户 token 调 `GET /customers/{id}/band/derived`、`/verdicts`、`include=derived` 等 → **403 `VISIBILITY_DENIED`**；`data.denied_fields[]` 回显被拒字段名 |
| **字段不下发（非 null）** | 客户可调接口的响应体中，③④ 字段**整体不存在**（不是 `null`、不是空串、不是 `""`）—— 与原型 D1 X-1「**客户端 ③④ 是**不下发**，不是前端隐藏**」逐字一致 |
| **验收方法** | **换账号 + 抓包双验**（PRD §2.4 X-1 验收）：客户 / 调理师 token 调任一含派生字段的接口 → 403 且响应体**不含**该字段 |
| **X-2（构建期）** | 小程序代码包 grep `gap_reason` / `effect_verdict` / `AS_refund` / 派生结论文案 → **命中即构建失败** |

### 3.3 冲突扫描结果（**显式列出，不默默改**）

| # | 扫描项 | 结果 | 处置 |
|---|---|---|---|
| 1 | 原型 D1 矩阵 ↔ config #43 | **一致** | — |
| 2 | 原型 D1 ↔ PRD §2.6.1 / §2.6.2 | **一致** | — |
| 3 | S1-1 派工单 §二 ↔ 本矩阵 | ⚠️ **非冲突，需消歧** | 见下 **冲突点 C-1** |
| 4 | 派工单 §二「服务调整类文案」行归属 | ⚠️ **非冲突，需消歧** | 见下 **冲突点 C-2** |
| 5 | 客户端「同步失败」态 ↔ ③ 缺口原因 | ⚠️ **需契约级区分（防泄漏）** | 见下 **冲突点 C-3** |

**冲突点 C-1 · 「手环接入状态」的档位归属歧义（建议裁定，非改数）**
S1-1 派工单 §二把「手环接入状态（未接入/同步中/失败）」单列一行标客户 ✅；而 config #43 把它并入 **② 采集状态**（已采集天数 / 同步时间）。**两者不冲突**（接入状态 ⊂ ②），但**契约需明确归属以免实现分叉**。
→ **建议裁定**：**统一归入 ② 采集状态**（与 config #43 一致），派工单 §二那一行视为 **② 的子集**。**不改任何档位值。**

**冲突点 C-2 · 「服务调整类文案」属于 #40 而非 #43（跨维度误合并）**
S1-1 派工单 §二末行「服务调整类文案」标注「调理师 ⚠️ 仅经络师 + 管理员」——**该行管的是"退款/结算"可见性（config #40 `refund_visibility`），不是手环可见性（#43）**。PRD 明确：**#40 管退款（角色级二分）、#43 管手环数据（角色 × 字段组四档），独立配置、不得合并**。
→ **建议裁定**：**该行从"字段组表"移出，归入 §2.7 退款域可见性**；本契约按此执行（§2.7 已按 #40 定档）。**不改任何档位值。**

**冲突点 C-3 · 客户端「同步失败」态与内部 `gap_reason` 可能同形泄漏（需契约级隔离）**
PRD §2.8.7② 要求客户端四态含「**同步失败（原因类别 + 可执行下一步）**」；而 §2.6.1 又要求客户端**不可见"缺口原因分类"（③）**。若客户端"失败原因类别"直接复用内部 `gap_reason` 枚举（含"未佩戴"），**技术失败与未佩戴将在文案层同形 → 违反 §2.8.7②「②类失败绝不显示"没戴/未佩戴/缺失"」**。
→ **建议裁定（本契约已按此实现，标注为"契约级隔离"）**：**客户端可见的"同步状态枚举"与内部 `gap_reason` 枚举必须是两套独立枚举**：
  - 客户端可见 `client_sync_state` ∈ {`syncing`,`synced`,`sync_failed`,`no_data_today`}（**无"未佩戴"取值**）；
  - 内部 `gap_reason` ∈ {`no_open`,`sync_failed`,`not_worn`,`compliant_removal`,`involuntary_technical`,`beyond_retention_window`,`unknown`}（**仅 ③ 可见角色可读**）。
  → 此为**隔离设计确认**，**不改变任何档位**；若业务方认为需进一步收紧，属**变更业务裁定**。

> **【2026-09-23 裁定落地 · R1】`gap_reason` 取值集修订（5 值 → 7 值 · 拼写统一）**
> - **裁定结论**：内部 `gap_reason` 取值集**以数据字典 §4.4 的 7 值为权威**，与本件原声明的 5 值不一致处**按 7 值修订**：
>   - **拼写统一** → `compliant_removal`（**-t 结尾**）。本件原拼写 `compliance_removal`（-ce 结尾）**作废**，属同一语义的笔误。
>   - **补入 3 值** → `involuntary_technical`（`isWear=(-1,255)` 设备异常）、`beyond_retention_window`（超留存窗口 N，A6 未解前按技术性处理）、`unknown`（A6 静默不返回且无原因码）。
>   - **`device_unbound` 退役** → 设备解绑归因**并入 `involuntary_technical`**（设备级技术性缺失，**仍不判行为性**，符合"宁可少扣不可错扣"纪律）。**不单列为行为性取值**。
> - **依据**：blocker `R-10`（F-9 失败原因枚举）裁「以契约 **7 值英文枚举**为准，改 `data-dict §4.6`（满足 `D-10` 逐项落库）」；字典侧 `§4.4` 表与 DDL CHECK 已按 7 值自洽落库；`§2.8.7` 三类失败分辨（①未触发 / ②触发但同步失败 / ③同步成功但没戴）要求技术性与行为性**在枚举层可分**，5 值不足以表达「技术性缺失 / 超窗口 / 未知」三类归因。
> - **若后续需要"客户主动解绑"单列为行为性归因**：须走**增项流程**新增专门取值，**不得复用 `device_unbound`**。
> - **影响面**：**仅枚举取值集**；**不改任何可见性档位**（`gap_reason` 客户端一律 403 不变，见 §3 与 §4）。按 §1.4，触及"枚举取值集"属 **MAJOR 级**修订 —— 因本件仍为 `api-contract-v1.0.0` **冻结候选**（尚未打 tag 生效），本修订**并入首次冻结基线**；**冻结生效后任何同类变更须走 MAJOR + 变更业务裁定**。
> - **联动件**：`data-dict-entities-ddl-2026-09-19.md` §4.4 / §2.17（DDL CHECK）· `blocker-root-cause-and-today-close-2026-09-20.md` R-10 · `skeleton` 的 `ContractConsistencyTest` 登记表（已同步更新为"差异归零"）。

---

## 4. 手环接入契约专章（对应 S1-7）

> **基准**：PRD §2.8.7（`M-WX-FG`「打开即自动同步 + 设备端历史补拉」）· config #44（`band_capture_mode` 冻结值 = `M-WX-FG`）· 协议层测试报告（2026-09-19）。

### 4.1 同步批次契约（四态）

**E1** `POST /api/v1/band/sync-batches` —— 客户端（小程序）每次同步尝试后上报一条批次记录。

| 入参 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `device_id` | string | 是 | **客户级**穿戴设备 ID（非门店调理设备） |
| `customer_id` | string | 是 | 服务端从 token 校验归属 |
| `batch_no` | string(uuid) | 是 | 幂等键（`Idempotency-Key` 同值） |
| `trigger` | enum{`on_show_cold`,`on_show_hot`,`checkin`,`daily_report`,`manual`} | 是 | 合流触发点（PRD §2.8.7⑧-4） |
| `state` | enum{`syncing`,`synced`,`sync_failed`,`no_data_today`} | 是 | **四态**（见下） |
| `synced_at` | datetime | 是 | 精确记录；**客户端展示精确到日** |
| `last_success_date` | date | 条件 | `state=synced` 时必填（最近一次成功同步日） |
| `fail_reason_class` | enum{`bt_off`,`unauthorized`,`connect_timeout`,`device_low_battery`,`occupied_by_vendor_app`,`platform_suspended`,`probe_out_of_window`} | 条件 | `state=sync_failed` 时必填 |
| `next_action` | enum{`open_bluetooth`,`grant_permission`,`retry`,`none`} | 条件 | `state=sync_failed` 时必填（**可执行下一步**） |

**四态定义（客户端可见，契约级）**：

| `state` | 含义 | 客户端文案（中性） | 是否可扣分 |
|---|---|---|---|
| `syncing` | 同步中 | 「正在同步手环数据…」 | — |
| `synced` | 已同步（**含最近一次同步时间，精确到日**） | 「已同步（最近一次：X月X日）」 | — |
| `sync_failed` | 同步失败（**原因类别 + 可执行下一步**） | 「暂时没连上手环…**这不影响任何服务**。［重试/去开启］」 | **否**（技术性，不怪客户） |
| `no_data_today` | 该日暂无数据 | 「该日暂无数据」 | **否** |

> 🔴 **三条硬约束（进验收）**：
> ① `sync_failed` **必须携带 `next_action`**，**不得只给一句"同步失败"**（PRD §2.8.7②）；
> ② `sync_failed` 的 `fail_reason_class` **取值域内不得出现"未佩戴"类语义** —— 技术失败与未佩戴在**枚举层彻底分开**（冲突点 C-3）；
> ③ 客户端**不得**出现"缺失 N 天""未佩戴""未达标"等负向计数（W-2，入 X-2 构建期扫描词表）。

| 可见性 | 客户(小程序) | 调理师·经络师(APP) | 管理员(Web) |
|---|---|---|---|
| `state` / `synced_at`（**日粒度**）/ `next_action` | ✅ | ✅ | ✅ |
| `fail_reason_class`（技术类） | ✅（**仅技术类，无未佩戴**） | ✅ | ✅ |
| `gap_reason`（内部，含 `not_worn`） | **403 / 不下发** | ✅ | ✅ |

**错误码**：`1001`（`sync_failed` 缺 `next_action`）｜`4002`（`batch_no` 重放，返回首次结果）｜`6001`（`onShow` 5 分钟节流窗口内重复发起）。

### 4.2 设备可用日期探测接口（**N 运行时探测，禁硬编码**）

**E5** `POST /api/v1/band/available-dates` —— 客户端调用厂商 `sdk.getValidHistoryDates(historyType)` 后，把**设备上实际存在的有效日期数组**上报服务端。

| 入参 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `device_id` | string | 是 | 客户级设备 ID |
| `history_type` | enum{13 条逐日接口对应的 HistoryType} | 是 | 与厂商枚举对齐 |
| `valid_history_dates` | date[] | 是 | **`getValidHistoryDates` 原样返回**（设备端实际存在的日期） |
| `probed_at` | datetime | 是 | 探测时刻 |

| 出参 | 类型 | 说明 |
|---|---|---|
| `probe_id` | string | 探测记录 ID |
| `retention_window_days` | int \| **`TBD`** | **运行时探测值**（= 探测到的可用日期跨度）；**未探测到 → 返回 `TBD`，不得回落为硬编码值** |
| `pull_start_date` | date | **服务端计算**：`start = max(last_synced_date + 1, today − retention_window_days)`；**上界 = today（含当日）** |
| `earliest_available` | date | 探测数组最早日 |
| `latest_available` | date | 探测数组最晚日 |

> 🔴 **N 取运行时探测值，禁止硬编码（S1-7 硬性验收）**：
> - 服务端**不得**内置 `N = 7` / `N = 14` 等任何常量；**`retention_window_days` 一律取自本接口的 `valid_history_dates` 计算结果**；
> - **不得回溯超过探测到的窗口** —— 超窗口日期设备不返回 → **只会制造"假缺失"**（PRD §2.8.7③）；
> - **未探测到值时的兜底**：返回 `TBD`，**UI 与文案不得给出具体天数**，**不得按"数据齐全"预设布局**（§2.6.4 #4）。

**错误码**：`1001`（`valid_history_dates` 为空且非首次绑定）｜`5001`（`history_type` 不在 13 条枚举内）。

### 4.3 补拉幂等键（**双分支**）

**E2** `POST /api/v1/band/telemetry` —— 逐日采集数据上行，**服务端权威 upsert**（重复拉取 = 覆盖，非追加）。

| 分支 | 覆盖接口 | 幂等键（业务唯一键） | 语义 |
|---|---|---|---|
| **按日型 × 12** | `getStepHistory` / `getHeartRateHistory` / `getBloodPressureHistory` / `getBloodOxygenHistory` / `getPressureHistory` / `getMetHistory` / `getTempHistory` / `getMaiHistory` / `getSleepHistory` / `getRespirationRateHistory` / `getExerciseHistory` / `getBloodSugarHistory` | **`(device_id, metric, date, hour, minute)`** —— 日聚合接口退化为 `(device_id, metric, date)` | **upsert** |
| **游标型 × 1** | `getSportHistory(false)` | **`(device_id, 'sport', currentSportId)`** | **单开分支** |

> ⚠️ **偏差 D-A（协议层测试报告）**：`sdk.getSportHistory(false)` 入参为**单 boolean**（游标型，靠返回体 **`sportLength > 1`** 循环），**与其余 12 条"按日拉取"不同构**。
> 🔴 **契约强制**：**运动数据不得复用按日型幂等键** —— 必须走 `(device_id,'sport',currentSportId)` 游标分支；**每接口设最大翻页上限防死循环**（PRD §2.8.7③）。

**入参（逐日型）**：`device_id` / `metric`（enum）/ `date` / `hour` / `minute` / `value`（按 metric 的 Schema）/ `interval`（采样间隔）/ `is_wear`（**`getHealthDetail.isWear`**）/ `raw_source`。

**入参（游标型 sport）**：`device_id` / `current_sport_id` / `sport_payload` / `sport_length`（**循环判据**）。

**缺失语义（延续 §2.6.3 / P0-10）**：
| 场景 | 落库 | 约束 |
|---|---|---|
| 数据缺失 | `coverage_flag = null` | **不得自动补 0** |
| 未接入 | `data_source = "未接入"` | **不得显示 0 / 空图表 / 示例数据** |
| 同步时间 | `synced_at` 必填 | **无它则"延迟"与"缺失"不可分辨**（W-3②） |
| 缺口原因 | `gap_reason`（**仅 ③ 可见角色可读**） | 属客户级属性，**不做门店锁定** |

**错误码**：`4001`（同键并发写入冲突，重试）｜`5001`（metric 与 value Schema 不匹配）。

### 4.4 三类失败在契约中的区分与表达

> **本方案最大的静默风险**：把"客户没打开"读成"客户没戴"（PRD §2.8.7② M2/U1 同根）。**契约级三分类如下：**

| # | 类别 | 契约表达 | 是否可扣分 | 归属 |
|---|---|---|---|---|
| **①** | **未触发**（客户根本没打开小程序） | `band_sync_batch.state` **无记录** → 服务端派生 `gap_reason = no_open`（**仅 ③ 可见**） | **否**（不是失败，是"客户没来"） | 仅作覆盖率分母说明 |
| **②** | **触发但同步失败**（蓝牙/授权/电量/占用/平台） | `band_sync_batch.state = sync_failed` + `fail_reason_class` + `next_action` | **否** —— **不怪客户** | 并入技术性缺失（G7） |
| **③** | **同步成功但确实没戴** | `band_sync_batch.state = synced` ∧ 数据流 `is_wear = 0`（**且非 `(-1,255)`**） | **是 —— 唯一可扣分情形** | 进 A3 行为性 |

> 🔴 **`isWear` 双刃性处置**：`1`=佩戴／`0`=脱腕（行为性）／**`(-1,255)`=技术性缺失 → 一律不判行为性**（宁可少扣、不可错扣）。契约层：`is_wear = -1 或 255` → 服务端强制覆写为技术性缺失，**不得落入 ③**。
> 🔴 **A3 `applicable=False` 重归一（未达标口径）**：无有效同步数据（从未成功同步一次）→ `applicable=False`、权重重归一、**不扣分**（PRD §2.8.7④/⑤）。**契约层**：`band/derived` 响应含 `a3_applicable: bool`，**为 false 时不返回 `a3_value`**（避免"0 值"被误读为"戴了 0 天"）。

### 4.5 诚实边界（**TBD 占位，不得填数字**）

| 参数 | 契约字段 | 值 | 说明 |
|---|---|---|---|
| **N**（设备留存窗口） | `retention_window_days` | **`TBD`** | **运行时由 `getValidHistoryDates` 探测**；未探测到前**不得对外给具体天数**（Q-W4 阻塞） |
| **s**（单次前台同步成功率） | — | **`TBD`** | 未取证；**不得写入任何"补拉可让 A3 达标"的断言** |
| **f**（A3 观测率） | `a3_observability`（预留，**本版不返回值**） | **`TBD`** | 上限 = `s`；跨 `f*`≈91.5% 需 `s>0.915`（引用须带 `c`≈0.82） |

> 🛑 **契约纪律**：凡涉及 N / s / f 的字段，**一律 `TBD` 占位**；**第 1 批只落地探测接口，不填假值**（Sprint 1 派工单 §六 TBD 条款）。

---

## 5. 措辞纪律在契约层的体现

> **依据**：Non-goals **#9**（禁"无效退款"类承诺性表述）/ **#10**（不做"概不退款"）· 竹溪县市监局 2026-09-03 合规提示 · PRD §2.2（退款是内部事务）· 派工单 §五。

### 5.1 客户端可见字段的命名规则（**硬性**）

| # | 规则 | 反例（✗） | 正例（✓） |
|---|---|---|---|
| **R1** | 客户可见字段名 / 枚举值**不得含"退款"** | `refund_status` / `refund_amount` / `refund_*` | `service_adjustment_state` / `fund_handling_*`（如确有需要） |
| **R2** | 客户可见字段名 / 枚举值**不得含"诊断"** | `diagnosis_result` | `assessment_summary` / `service_conclusion` |
| **R3** | 客户可见**不出现派生结论** | `a3_value` / `as_value` / `effect_verdict` / `refund_eligibility` | **字段整体不下发** |
| **R4** | 客户可见**同步时间精确到日，不得到分秒** | `synced_at: "2026-09-16T14:32:07"` | `synced_date: "2026-09-16"` |
| **R5** | 客户可见**正向计数 + 中性描述** | `missing_days` / `not_worn_days` | `collected_days`（已采集 N 天）/ `no_data_today`（该日暂无数据） |
| **R6** | **未接入态**不得用 0 / 空图表 / 示例数据顶替 | `steps: 0`（未接入时） | `data_source: "未接入"` + 数值字段**不下发** |
| **R7** | 客户可见**不得出现"达标/未达标"评价** | `is_compliant` / `achievement_rate` | 无（不提供） |
| **R8** | 派生结论侧**禁用"不利"色 / 措辞** | `danger` 色 + "偏低/不达标" | 中性色，不与判定色混淆 |

### 5.2 契约级落地机制

| 机制 | 说明 |
|---|---|
| **构建期扫描（X-2）** | 小程序产物 grep 禁用词表（`退款`/`诊断`/`未佩戴`/`缺失 N 天`/`达标`…）+ 禁用接口路径 + 枚举名 → **命中即构建失败**；`owner` = 研发侧指定（T-2，待指认） |
| **枚举命名空间隔离** | 客户可见枚举以 `client_*` 前缀（如 `client_sync_state`）；内部枚举以 `internal_*` / 原名（如 `gap_reason`）—— **两套不得共用类型定义** |
| **OpenAPI lint** | 契约 CI 对 `client_end=mp` 可见字段做词表校验，命中即 PR 阻断 |
| **抓包双验** | 客户 token 抓包，响应体 grep 禁用词 → 命中即失败（X-1 验收） |

> ⚠️ **覆盖推送通道**：**"客户端不出现退款字样"同样适用于小程序订阅消息模板**（PRD §2.2 + P0-19）—— **页面改了、推送没改等于没改**。

---

## 6. 验收标准（Definition of Done · S1-8）

### 6.1 契约冻结 DoD

- [ ] **D1** OpenAPI 3.0 文档产出，`info.version = api-contract-v1.0.0`，覆盖 §2 全部接口（**§2 表逐行清点 = 34 行；原写"32 个接口"口径已更正，验收以「34 行 / 全端点，含 D5 的 3 个端点」为准**），无 `TODO` 占位字段（TBD 仅允许出现于 §4.5 的 N/s/f）。
- [ ] **D2** 每个接口的**请求/响应字段**均标注类型 / 必填 / 约束；**字段名、类型与 §3 可见性矩阵一致**。
- [ ] **D3** **三端 SDK 由契约生成**（小程序 TS / APP Dart 或 TS / Web TS），生成产物**零手改**。
- [ ] **D4** **可见性档位**：客户 token 调 ③④ 相关接口 → **403 `VISIBILITY_DENIED`**，且响应体**不含**该字段（抓包双验）。
- [ ] **D5** **门禁 403**：四道闸门任一缺失 → **403 `GATE_MISSING`** 且 `data.missing_items[]` **给出缺失项名称**（非模糊报错）。
- [ ] **D6** **手环专章**：`/band/sync-batches` 四态齐备、`sync_failed` 必带 `next_action`；`/band/available-dates` 返回 `retention_window_days`（**运行时探测值，代码内无 `N` 常量**）；`/band/telemetry` 幂等键**按日型 12 条 + 游标型 1 条双分支**。
- [ ] **D7** **措辞纪律**：小程序产物 grep 禁用词表 → **0 命中**；客户可见字段无"退款""诊断"字样。
- [ ] **D8** 契约评审通过，打 tag `api-contract-v1.0.0`，CHANGELOG 首条落盘。

### 6.2 自动化检查思路（**三端 SDK 与契约字段逐一比对，不一致即失败** · S1-8）

| 检查 | 方法 | 失败条件 |
|---|---|---|
| **字段集比对** | 从 OpenAPI 抽取 `(path, method, request_fields, response_fields)` 集合；从三端 SDK 反射抽取同结构集合 | **任一端字段集 ≠ 契约字段集**（缺字段 / 多字段）→ FAIL |
| **类型比对** | 比对字段类型映射（`string`↔`String` 等） | 类型不匹配 → FAIL |
| **可见性断言** | 按 §3 矩阵生成用例：对每个 (角色 × 字段组) 发起请求，断言「✅→字段存在」/「❌→403 且字段不存在」 | 任一格与矩阵不符 → FAIL |
| **四态断言** | 对 `sync-batches` 四态各造一条 fixture，断言响应与文案枚举 | 缺 `next_action` / 出现"未佩戴"语义 → FAIL |
| **N 硬编码断言** | 静态扫描服务端代码，grep `retention_window_days\s*=\s*\d+` / 类似常量 | 命中常量赋值 → FAIL |
| **幂等键断言** | 重复提交同一 `(device_id, metric, date, hour, minute)` → 断言 upsert（记录数不变）；运动数据走游标键 | 追加而非覆盖 → FAIL |
| **措辞扫描** | 对小程序构建产物 grep 禁用词表 | 命中 → FAIL |
| **禁用接口扫描** | 小程序包内 grep §2.7 退款域路径 | 命中 → FAIL |

> **CI 门禁**：上述任一 FAIL → **构建失败、PR 阻断**（对齐 §2.4 X-2 精神）。**契约变更须同步更新用例集**（否则用例漂移）。

---

## ✅ 行动清单

| # | 行动 | 负责方 | 时间窗 |
|---|---|---|---|
| 1 | 按本文档产出 OpenAPI 3.0 契约文件 | 后端主程 | D1~D2 |
| 2 | 三端代表会签「可见性矩阵 + 错误码」 | 前端 3 端 + 产品 | D2 |
| 3 | **D3 冻结**：评审通过 + 打 tag `api-contract-v1.0.0` | 后端主程 + 析客 | **D3（M1 闸门）** |
| 4 | 落 `/band/available-dates` 探测接口（**N 运行时探测，无硬编码**） | 后端 | D1~D2（S1-7） |
| 5 | 建契约回归用例集（字段比对 + 可见性 + 四态 + 措辞扫描） | 测试 | D3~D6（S1-8） |
| 6 | 对接 S1-5（服务端唯一权威 403 兜底）/ S1-6（客户端零派生） | 后端 + 前端 | D3 起 |
| 7 | 指定扫描词表 owner（T-2，0 人日但不可空） | 研发侧 | D1 |

---

## ⚠️ 待确认 / 假设 / Non-goals

### 待裁定（**不自行拍板**，交业务方 / 主理人）

| # | 待裁定 | 影响 | 归属 |
|---|---|---|---|
| **Q10** | 「没效果能退钱」还算不算对外承诺 | 退款域契约（G 域）字段口径；迟定工程返工 ≈1~2 周（⚠️ 量级已于 2026-09-20 统一：Q10 单项工程返工 ≈1~2 周；原「3~5 周」为 Q10+Q11 合计，Q11 已冻结） | 业务方 |
| **Q11** | 疗程口径（15~45 次 vs 2 调优周期 + 基础服务期） | 状态机 / visit 计数契约 | 业务方 |
| **Q17** | 端形态（跨端 8~16 / 原生 13~27 / 改走小程序 3~9） | **不影响本契约内容**（见 §1.1）；仅影响 A1 人日 | 业务方 |
| **Q-W1** | 是否接受"客户每 N 天须打开一次"作为"零安装"定价 | 客户端 SOP，不阻塞契约 | 业务方 |
| **Q-W2** | 客户端"今天同步成功了吗"状态卡是否立项 | `E6 sync-status` 是否启用 | 业务方 |
| **Q-W3** | 门店端"缺失原因推断"是否区分 `no_open` / `sync_failed` | ③ `gap_reason` 枚举细化 | 业务方 |
| **Q-W4** | **N（设备留存窗口）由厂商确认** | **阻塞采集 SOP 口径**；契约侧以 `TBD` 占位（§4.5） | 厂商 |
| **Q-W5** | 安卓定位权限说明话术是否经法务确认 | 客户端授权文案 | 法务 |
| **C-1 / C-2 / C-3** | §3.3 三处**消歧建议**（接入状态归属 / 服务调整文案维度归属 / 客户端枚举隔离） | 契约实现分叉风险 | 主理人裁定 |
| **Q-G1 / Q-G2** | 手环归属 / 回传路径 | 不影响本契约冻结（手环不上关键路径） | 业务方 |
| **Q-E1** | **《知情同意书》送签 PDF 是否需要回显禁忌结论**（🆕 2026-09-21 登记 · 选型件 §4.5 🔴 冲突点） | 若送签 PDF **回显禁忌结论**，则该 PDF **含敏感个人信息（健康相关）**，上送第三方电子签平台即构成**健康数据出域**，与 PRD Q6 默认口径「**健康数据不出租户边界**」（**PRD §10「待确认问题」· Q6**）**存在张力**；直接决定 **§2.8 H1 回调载荷的数据边界**与《个人信息授权》的授权范围（是否须写入「电子签数据出域」并约束存储地域 / 不出境）。**卡住 06 屏「协议书签署」文本定稿与电子签合规口径** | **业务方 / 法务**（⚠️ **业务 + 法务问题，非产品 / 非工程问题，契约 owner 不得拍板**；选型件已给方案 a（送签文本不含逐人健康细节）/ 方案 b（含则须走 DPA）**仅供裁定参考，不构成结论**）；**本项 = PRD §10「待确认问题」· Q6 的具体化（收窄到“送签 PDF 是否含禁忌结论回显”），不新开问题**；Q6 被裁定后**必须回头核对本项**。 |

### 假设（未验证）

- 厂商 `getValidHistoryDates(historyType)` **对全部 HistoryType 生效**（文档仅给单例返回示例）——**待真机复核**。
- `uniapp → mp-weixin` 编译 + 真机 BLE 可运行（**生死项，未实测**）。
- 门店现场网络基本可用（**不做离线同步**）。
- **电子签回调（§2.8 H1）的投递语义未取证**：`signed` / `rejected` / `expired` 三事件的**实际投递语义** —— 是否**保证至少一次（at-least-once）**、**是否有重试策略**、**是否支持主动查询补数** —— **均未取证**，**须以选定厂商官方文档为准**；在此之前 H1 **只冻结形态，不冻结具体投递策略**。
  - 🔗 **〔同源收口 · 2026-09-21 登记〕本条 = 选型件 `_work/esign-vendor-eval-2026-09-21.md` 的 `VQ-3`，是同一个问题。** 🛑 **两处不得各记一次、不得各答一次** —— **收口 owner = 本契约（H1）**；厂商答复回来后**由本契约回填投递语义**，选型件据本契约更新幂等实现估计（是否需要"主动查询补数"分支）。**未回填前，两处一律维持"未取证"，不得任一处先写结论。**

### Non-goals（本契约不做）

- **不做**门店客服端任何接口（无端）。
- **不做**挂号 / 收费 / 发药 / 进销存接口。
- **不做**判定引擎公式 / 改善率引擎（Phase 1；本契约只冻结接口形态与字段）。
- **不做**离线同步 / 本地缓存 / 冲突合并。
- **不打**具体数字进 N / s / f（一律 `TBD`）。

---

## 📚 数据来源

| 证据 | 路径 |
|---|---|
| PRD **v1.33**（出具时为 v1.25；见文件头版本同步注记。相关章节：§2.2 / §2.3 / §2.4 / §2.6 / §2.7 / §2.8.7 / §2.9 / §2.10 / §8 / §10 / 附录 C） | `deliverables/product-strategy/prd-health-mgmt-saas-2026-09-16.md` |
| 原型 D1 屏 + **158 项自检基线**（出具时为 119 项；见 `_proto_verify.js` 现行基线） | `deliverables/product-strategy/prototype/index.html` |
| 协议层测试报告（94 方法 / 81 NotifyType / 13 逐日接口 / `getValidHistoryDates` / 偏差 D-A） | `_work/gtl1-wx-open-sync-sdk-proto-test-2026-09-19.md` |
| Sprint 1 派工单（S1-1 / S1-7 / S1-8） | `_work/dev-sprint1-kickoff-2026-09-19.md` |
| 开工就绪度核对（四类卡口 / 裁定单未签） | `_work/dev-kickoff-readiness-check-2026-09-19.md` |
| 客户侧体验评估（同步四态 / 文案 / 授权时机） | `_work/gtl1-wx-open-sync-ux-2026-09-19.md` |
| A3 观测率评估（N / s / f 模型） | `_work/gtl1-wx-open-sync-metric-2026-09-19.md` |
| 路线图（§1.1 正文级条款「契约冻结不因 Q17 未签而延后」） | `roadmap-health-mgmt-saas-2026-09-16.md` |

---

> 本契约由析客（需求分析师）依 PRD **v1.25**（**已于 2026-09-23 同步注记至 v1.33**，见文件头）/ 原型 D1 / 协议层测试报告 / Sprint 1 派工单产出，2026-09-19。**封版五值零改动**（132~200 / 165~258 / 14~24 / 5.5~8.5 / 端形态账封顶 35.5）。**本件为接口契约规格，不含业务策略裁定**；N / s / f 一律 TBD，待运行时与真机取证。**重要决策请由产品负责人审定。**