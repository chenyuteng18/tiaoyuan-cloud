# 文书模板「上传 → 版本 → 发布 → 渲染签署」能力规格（2026-09-23）

| 项目 | 内容 |
|---|---|
| **日期** | 2026-09-23 |
| **类型** | **能力规格（新增能力 · 跨四件落点）** |
| **依据** | 用户（产品负责人）2026-09-23 裁定：**① 法律文书由我方确认后「上传到平台上」② 承载形态 = 双形态（上传文件 + 在线编辑）③ 正文可填项须系统自动带入** |
| **落点（四件，互不重叠）** | 契约 `_work/contract-t6-api-freeze-2026-09-19.md` · 数据字典 `_work/data-dict-entities-ddl-2026-09-19.md` · PRD `prd-health-mgmt-saas-2026-09-16.md` · 原型 `prototype/index.html` |
| **本件作用** | **只做「命名与口径的唯一真相源」** —— 四个落点必须逐字采用下方命名，否则四件对不上。**本件不替代各件的正文写法。** |

---

## 〇、为什么补这条能力（缺口陈述）

现行设计**只支持「超管在后台在线编辑全文」**，**没有「上传」这条链路**：

| 层 | 现状 | 缺口 |
|---|---|---|
| **契约** | §2 域 A~H；域 H（§2.8）只有电子签回调占位 | **无文书模板的读写 / 上传 / 版本 / 渲染接口**（grep `template` 仅在 §2.8 标题命中） |
| **数据字典** | §一 登记 `doc_template`（★ · P0-27） | **§二 缺 `doc_template` 字段级 DDL**（§二 现止于 §2.26 `band`） |
| **PRD** | `config #46` + `P0-27` 五条验收 = 编辑 / 版本 / 留痕 / 提示 / 不回溯 | **无「上传文件」支**；**无占位符带入机制** |
| **原型** | 06 屏「协议书签署」= 要点清单 + 签署位 | **无正文呈现位 / 版本位 / 上传位** |

⇒ 用户「上传到平台」这一动作，**当前平台无处可接**。本批补的正是这条链路。

---

## 一、实体口径（🛑 四件逐字采用）

### 1.1 `doc_template`（**扩展既有实体**，不新建实体、不改名）

> 沿用 PRD 附录 C.1.8 既有定义；本批**只增字段**，既有字段（`template_id` / `tenant_id` / `doc_type` / `title` / `content` / `version` / `is_active` / `created_by` / `created_at` / `change_reason`）**一字不改**。

| 新增字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 枚举 / 说明 |
|---|---|---|---|---|---|
| **`source_type`** | string | 否 | `'editor'` | CHECK；IDX | **`editor`（在线编辑）/ `upload`（上传文件）** —— 决定该版本以哪种形态存在 |
| `file_ref` | string | 是 | NULL | — | **对象存储引用**（如 `oss://<bucket>/<tenant>/<doc_type>/<version>/<hash>`）；`source_type=upload` 时**必填** |
| `file_name` | string | 是 | NULL | — | **原始文件名**（保留扩展名；仅展示用，不参与寻址） |
| `mime_type` | string | 是 | NULL | CHECK | **仅允许三值**：`application/pdf` / `application/vnd.openxmlformats-officedocument.wordprocessingml.document`（.docx）/ `text/markdown` |
| `file_size` | int | 是 | NULL | CHECK > 0 且 ≤ **10 MiB** | 字节 |
| **`file_hash`** | string | 是 | NULL | — | **SHA-256（hex）**；完整性校验 + 防替换。`source_type=upload` 时**必填** |
| **`placeholder_schema`** | jsonb | 是 | NULL | — | **占位符白名单声明**（见 §二）；未声明则渲染期**只允许零占位符**（fail-closed） |

- **唯一键保持** `(tenant_id, doc_type, version)` —— **不变**。
- **版本不可覆盖**（沿用既有硬约束③）：每次上传/编辑**新增一行**，`version` 递增；旧的置 `is_active=false`。
- 🛑 **`content` 与 `file_ref` 的关系**：`source_type=editor` → `content` 有值、`file_ref` 为 NULL；`source_type=upload` → `file_ref` 有值；**`content` 可为 NULL**（上传件不强制转录）。**两者不得同时为空。**

### 1.2 `agreement`（**扩展既有实体**，绑定签署件与模板版本）

| 新增字段 | 类型 | 可空 | 默认 | 约束 / 索引 | 说明 |
|---|---|---|---|---|---|
| **`doc_template_id`** | string | 否 | — | FK `doc_template(template_id)` | **该协议签的是哪个模板** |
| **`doc_template_version`** | int | 否 | — | — | **签的是哪个版本**（与 `doc_template_id` 合起来定位唯一行） |
| **`rendered_snapshot`** | text | 否 | — | **快照存储 · 不可覆盖** | **占位符已带入后的签署稿正文**（或渲染后的对象存储引用） |
| **`rendered_hash`** | string | 否 | — | — | **渲染稿 SHA-256** —— 用于事后证明"签的就是当时那一版、且未被改过" |

- 沿用既有 `refund_clause_snapshot` / `breach_clause_snapshot` / `signed_at` / `signer`，**不动**。
- 🛑 **`rendered_snapshot` 必须快照存储**（与 `refund_clause_snapshot` 同款纪律）—— 模板改版**不回溯**已签协议。

---

## 二、占位符白名单（🛑 本项目最重要的新增设计约束）

### 2.1 允许的占位符 —— **全集仅此 6 个，不得扩展**

| 占位符 | 取值来源 | 渲染示例 | 说明 |
|---|---|---|---|
| `${store.name}` | `store.name`（经 `customer.serving_store_id`） | 竹溪旗舰店 | 门店名 |
| `${customer.name}` | `customer.name` | 李** | 客户姓名 |
| `${customer.phone_masked}` | `customer.phone` 派生 | 138****8800 | **强制脱敏**（前 3 后 4）；原名不得出现在文书内 |
| `${agreement.sign_date}` | 签署动作发生时点 | 2026年9月23日 | 签署日期 |
| `${plan.cycle_count}` | `plan` 疗程次数 | 14 | 疗程次数 |
| `${plan.version}` | `plan.version` | V1.0 | 方案版本 |

**未在白名单内的占位符 → 渲染期报错（fail-closed），不得静默留空。**

### 2.2 🛑 禁止的占位符 —— **从机制上堵死，不得开任何例外**

**一律不得注册为占位符**（含但不限于）：

- 任何**健康类**取值：禁忌结论 / `screening_result` / 健康指数 `H` / `AS` / 依从性维度分 / `effect_verdict` / 退款资格 / 达标判定
- 任何**手环数据**：睡眠 / 步数 / 心率 / 静息心率 / 血氧 / `gap_reason`
- 任何**退款类结论**：退款金额 / 退款比例 / 退款判定

**设计意图（必须写进文档，不得只写在代码里）**：
> **占位符白名单本身就是一道数据边界闸门** —— 它使「禁忌结论等健康数据被无意渲染进送签 PDF」**在机制上不可能发生**，而**不依赖人工在写模板时自觉**。
> ⚠️ **但白名单不构成 Q-E1 的裁定**：Q-E1（送签 PDF 是否回显禁忌结论）**仍是业务方 + 法务的问题**，本机制只是**使"方案 a"成为默认可实现路径**，**不得写成"Q-E1 已关闭"**（🛑 两方案不得合并表述）。

---

## 三、接口口径（契约 · 新增「域 I · 文书模板与渲染」）

> **遵循本项目既有先例**：域 H（§2.8）系 2026-09-21 以「**占位待冻结**」形态新增。**域 I 同款处理** —— 冻结**形态**（方法与路径）、**不冻结**对象存储实现细节（bucket / CDN / 加密方式待研发评审）。

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

**关键约定（逐条）：**

| 项 | 约定 |
|---|---|
| **I3 上传约束** | `mime_type` **仅允许三值**（PDF / DOCX / Markdown）；`file_size` **≤ 10 MiB**；**落盘前先算 `file_hash`**，同 `<tenant, doc_type>` 下**同 hash 重复上传 → 返回已有版本号（幂等）** |
| **I3 幂等键** | `(tenant_id, doc_type, file_hash)` —— 重复投递语义对齐 §2.0 `4002 IDEMPOTENT_REPLAY` |
| **🛑 反病毒 / 反脚本** | 上传文件**不得作为可执行内容被服务端解析执行**；DOCX **不得解压执行宏**（**只做字节存储 + 哈希**，**不做宏解析**）；渲染期**只做文本占位符替换，不做 DOCX 内嵌逻辑求值** |
| **🛑 QC-6 不变量** | 模板正文（含上传件）**一律不得作为客户端包资产下发** —— 唯一落点 = 服务端 `doc_template.content` / `file_ref`；签署经**服务端渲染页 / 厂商侧页面**呈现（沿用 PRD 配置表末 QC-6 块） |
| **I8 渲染规则** | ① 占位符**只从白名单取值**，未声明 `placeholder_schema` 时**只允许零占位符**（fail-closed）；② **未在白名单的占位符 → 报错，不静默留空**；③ `customer.phone` **强制脱敏**；④ 渲染结果**必须落快照 + hash**（否则事后无法证明签的是哪一版） |
| **权限** | I2~I7 = **`super_admin`（tenant 级，不跨租户）** —— 沿用 PRD §2.2 定义；**门店只读**（P0-27「门店只读」） |
| **客户端零面** | 域 I **不含任何客户端可见接口**；若将来在客户端暴露，须过契约 §5.1 R1~R8 词表校验 + ADR-12 变更 |

**错误码（沿用既有段 + 新增两条）：**

| 码 | 语义 |
|---|---|
| `2001` | 非授权角色（门店调 I2~I7） |
| `4002` | 幂等重放（I3 同 hash） |
| `2003` | `mime_type` / `file_size` 越界 |
| `2004` 🆕 | **占位符越界**（模板含未在白名单的占位符） |

---

## 四、四件落点分工（🛑 互不重叠，防同时改同一文件）

| 落点 | 文件 | 改什么 |
|---|---|---|
| **① 契约** | `_work/contract-t6-api-freeze-2026-09-19.md` | 新增 **§2.9 域 I · 文书模板与渲染（占位待冻结）**（I1~I8 + 关键约定 + 错误码）；§1.2 冻结范围增占位条目；§2.8 域 H 的 H1 联动句补「渲染稿由 I8 产出」 |
| **② 数据字典** | `_work/data-dict-entities-ddl-2026-09-19.md` | 新增 **§2.27 `doc_template`**（字段级 DDL，含 §1.1 七个新增字段）；**§2.9 `agreement`** 补 §1.2 四个新增字段；§一 总览表 `doc_template` 行补「§2.27 已补字段级 DDL」 |
| **③ PRD** | `prd-health-mgmt-saas-2026-09-16.md` | `P0-27` 验收**增补「上传支」+「占位符白名单」两条**；`config #46` 语义补「支持上传与在线编辑双形态」；**新增占位符白名单机制段**（含 §2.2 禁止项与设计意图）；**卡口 2 第 1 条降级登记**（法务文本 → 平台承接、文本后补） |
| **④ 原型** | `prototype/index.html` | **06 屏**增：**文书正文呈现位**（含版本号）· **版本切换** · **上传位（管理员视角）** · **占位符渲染示例**（客户名脱敏显示）；**保持**既有门禁横幅与签署位；**须同步两套自检脚本**（`_proto_verify.js` / `_verify_pnl.js`） |

---

## 五、纪律（🛑 四条，逐条遵守）

1. **不得改动既有数字与结论**：封版五值（132~200 / 165~258 / 14~24 / 5.5~8.5 / 端形态账 35.5）**零改动**；P0 仍 19 项、P1 仍 9 项、P2 仍 3 项；`P0-27` 仍为**待评审增项**（人日 ≈4~6 为建议值，**本批不重估**）。
2. **沿用「占位待冻结」形态**（照 §2.8 域 H 先例），**不把未取证的东西写成结论**：对象存储实现、CDN、加密方式、厂商渲染分工 **一律标「待研发评审 / 待厂商选定」**。
3. **编号不得移动**：`config #46` 保持原号（**不得改号**）；`#47` 归属仍未裁定、`#48` 已占 —— **本批不得新占任何 config 号**。
4. **行号引用纪律**：正文语义区**一律用章节号 / 标题 / 条目 ID 定位，不写行号**（PRD 元约定 + `blocker-register` §七）。

---

*本件为能力规格（命名与口径唯一真相源），2026-09-23 由 team-lead 出具。**封版五值零改动**；**不构成 Q-E1 / Q-E2 的裁定**。*