# contract/ —— 三端接口契约（S1-1 · D3 硬闸门）

> **一句话**：本目录是「三端（客户小程序 / 调理师·经络师 APP / 管理员 Web）接口的唯一真相源」，
> 一次冻结并版本化。三端**只**依据本目录编码，不依据口头约定、不依据原型代码、不依据 PRD 正文散句。

---

## 一、目录内容

| 文件 / 目录 | 作用 | 是否有权威性 |
|---|---|---|
| `openapi-v1.0.0.yaml` | **契约本体**（OpenAPI 3.0.3）。43 行 / 45 端点 / 32 schema | ✅ **唯一权威** |
| `sdk-generator/generator-matrix.yaml` | 三端 SDK 生成配置（声明） | 配置，从属于契约 |
| `sdk-generator/_sdk_pipeline.py` | **裁剪管线**（裁剪 → 生成 → 校验不变量），生成路径的唯一入口 | 工具（✅ 权威实现） |
| `sdk-generator/openapitools.json` | 生成器版本锁（`7.10.0`，与矩阵声明一致） | 配置 |
| `sdk-generator/generate.sh` | 静态复核脚本（`--dry-run`，6 步全绿） | 工具（仅静态复核） |
| `sdk/` | **三端 SDK 产物**（已生成：client-mp / therapist-app / admin-web） | 产物（见 §四） |
| `visibility/` | 可见性矩阵导出落点（供端侧构建期消费） | 派生 |
| `README.md` | 本文件 | 说明 |
| `CHANGELOG.md` | 版本变更留痕（MAJOR/MINOR/PATCH） | 留痕 |

**冻结面的唯一权威输入（上游）**：
`../_work/contract-t6-api-freeze-2026-09-19.md`（含 §2 接口清单 / §3 可见性矩阵 / §4 手环专章 / §5 措辞纪律）

**权威关系（不得倒置）**：
```
contract-t6-api-freeze-*.md（上游权威 = 语义母本）
        ↓ 机械转录
openapi-v1.0.0.yaml（契约本体 = 三端编码依据）
        ↓ 生成/派生
sdk/ · visibility/（端侧产物）
```
任何时候三者冲突，**以上游 md 为准**，且必须走 §五 的变更流程 —— **不允许"改 OpenAPI 就算改契约"**。

---

## 二、冻结范围与口径

| 项 | 值 |
|---|---|
| 版本 | `api-contract-v1.0.0` |
| 行数 | **43**（§2.1 A=3 / §2.2 B=6 / §2.3 C=4 / §2.4 D=6 / §2.5 E=6 / §2.6 F=4 / §2.7 G=5 / §2.8 H=1 / §2.9 I=8） |
| 端点数 | **45**（D5 一行含 3 个端点：`POST /plans`、`GET /plans/{id}`、`POST /plans/{id}/reviews`） |
| Base Path | `/api/v1` |
| 响应信封 | `{code, message, data, trace_id}`（`ResultEnvelope`） |
| 已冻结 | 请求/响应**字段名·类型·必填·约束** / 可见性档位 / 错误码 / 幂等键 / 分页 |
| **未冻结（明确排除）** | 业务判定逻辑（P0-12 公式）、改善率引擎（Phase 1）、电子签厂商字段名与签名算法 |

**两处**「占位待冻结」是**有意为之**，不是遗漏：
- 域 H（`/esign/callbacks/{provider}`）：只冻结**形态**（事件枚举三值 / 幂等键 / 载荷白名单 / 数据边界），不冻结字段名与签名算法；厂商选定前**三端不得据本节编码**。
- 域 I（`/doc-templates*`、`/agreements/{id}/render`）：只冻结**方法与路径 + 上传渲染约定 + 错误码 + 数据边界**，不冻结载荷结构。

---

## 三、契约的自审计字段（为什么这份 YAML「可被机器检查」）

普通 OpenAPI 只是文档。本契约额外挂了机器可读的**自审计注解**，使「契约 ↔ 上游 md ↔ 配置 ↔ 原型」四者的不一致能在**构建期**报红：

| 注解 | 挂在哪 | 含义 |
|---|---|---|
| `x-contract-row` | 每个 operation | 回指上游 §2 的行号（`A1`…`I8`），可机械核对**行集完整性** |
| `x-callable-roles` | 每个 operation | 可调用角色白名单（服务端 403 的依据） |
| `x-client-forbidden` | 客户端禁入行 | 客户小程序**不得**生成调用面 |
| `x-visible-to` | 字段级 | 该字段对哪些角色下发（服务端裁剪依据） |
| `x-field-group` | 字段级 | 归属字段组 `raw_data`/`capture_status`/`gap_reason`/`derived_result` |
| `x-tbd-allowed` | 字段级（2 处） | 诚实边界：该字段允许且**必须**允许 `TBD` 字面 |
| `x-visibility-matrix` | 根级 | 4 字段组 × 5 角色权威矩阵（与上游 §3.1 逐格一致） |
| `x-wording-discipline` | 根级 | R1–R8 措辞纪律 + 命名空间隔离 |
| `x-error-codes` | 根级 | 12 个错误码（含域 I 新增 `2004 PLACEHOLDER_OUT_OF_SCOPE`） |

> **验收方法**：`ContractFreezeGateTest`（`skeleton/dy-app/src/test/java/com/diaoyuanyun/dy/app/ContractFreezeGateTest.java`）
> 用 snakeyaml 真解析本文件，逐项核对上表，并对**上游 md 正文**做反向比对。禁自证式测试。

---

## 四、SDK 生成边界与裁剪管线（✅ 已生成，附机器可验的证明）

**三端 SDK 已在本机真实生成**，落在 `contract/sdk/`（`client-mp` / `therapist-app` / `admin-web`）。
`contract/visibility/` 仍为空目录（可见性矩阵导出属后续步骤）。

### 4.1 为什么不能把契约直接丢给生成器

`openapi-generator` **不认识** OpenAPI 的 vendor extension ——
`x-callable-roles` / `x-client-forbidden` / `x-contract-row` 对它一律是"未知注解"，**它只按 `paths` 全量生成**。

实测后果（已修复，留档警示）：直接把契约丢给生成器，**客户小程序包里会长出 `/refunds`**（退款域全部调用面），
且 `components/schemas` 里的 32 个 schema **无论是否被引用都会生成 model 文件** ——
客户包里实测出现 `RefundData.js` / `RefundCreateRequest.js` / `RefundReceiptData.js` /
`VerdictData.js` / `BandDerivedData.js` / `AuditCoverageData.js`。这同时违反 INV-2 与 INV-3。

### 4.2 正确做法：生成前裁剪 spec（而不是生成后过滤产物）

```
openapi-v1.0.0.yaml
      ↓  ① 角色判据：target 的 token-role ∈ operation 的 x-callable-roles
      ↓  ② 显式排除：operation 的 x-contract-row ∉ target 的 exclude-contract-rows
      ↓  ③ schema 可达性：从裁剪后的 paths 出发沿 $ref 做传递闭包，闭包外的 schema 全删
_cut/<target>.openapi.yaml
      ↓  openapi-generator（版本锁 7.10.0）
contract/sdk/<target>/
      ↓  校验 INV-1 / INV-2 / INV-3 / INV-6 / INV-6b
```

**为什么裁剪 spec 而不是过滤产物**：生成产物的 operation 散布在 `api/` + `docs/` + `test/` 多类文件中，
后处理要同时改多类文件、且每换一个 generator 就得重写一遍；而裁剪 spec 是**一次性、与 generator 无关**的，且能被静态复核。

### 4.3 实测裁剪口径（`audit` 模式输出）

| 端 | 角色 | 保留 operation | 裁掉 operation | 裁掉 schema |
|---|---|---|---|---|
| `client-mp` | `client` | **15** | 30 | **18** |
| `therapist-app` | `therapist` · `meridian` | **29** | 16 | 7 |
| `admin-web` | `admin` | **39** | 6 | 5 |

### 4.4 生成后必须成立的不变量（缺一即非零退出，不静默降级）

| 不变量 | 判据 |
|---|---|
| **INV-1** | 该端应保留的 operation **必须**出现在产物里（防"裁过头"） |
| **INV-2** | 被裁掉的 operation **不得**出现在产物里（真正的牙齿） |
| **INV-3** | `client-mp` **运行时源码**（剥离注释后）内不得出现「退款」二字 |
| **INV-6** | 被裁掉的 schema **不得**以 model 文件形式留在产物里 |
| **INV-6b** | 产物 model 目录不得含矩阵禁入片段文件（含 generator 派生的包装类型） |

🛑 **两处判据细节，都是实测踩出来的**（已由反向验证 115 逐条钉住）：

- **INV-3 必须剥离注释**。生成器会把契约 `info.description` 抄成每个源码文件的头注释，
  而契约的措辞纪律条文里**自己就写着**"不得含『退款』"—— 不剥注释 ⇒ 全线假红（实测 112 文件命中，全部是注释行）。
- **INV-6b 必须覆盖派生类型**。生成器还会从 path 内联 response **凭空派生**契约里没有的包装类型
  （实测 axios 端有 `create-refund200-response.ts`），按"被裁 schema 原名精确比对"**完全抓不到**，
  必须用 `forbid-path-fragments` 做兜底。

### 4.5 片段例外必须具名（不许靠放宽判据消假红）

矩阵的 `forbid-path-fragments` 含 `retention`。该词在本产品里**同词异义**：

- **G3 退款挽留** `/refunds/{id}/retentions` → 客户禁入（fragment 要拦的就是它）；
- **E5 手环数据保留窗口** `retention_window_days` → 客户**合法可见**
  （契约逐字标 `x-visible-to: [client]`；矩阵 INV-5 亦逐字点名它为客户端字段）。

若为了让 E5 过检而把 `retention` 从判据里删掉，就会**永久失去对 G3 的字符串兜底**。
故采用 **「点名放行、其余全拦」**：`forbid-fragment-exceptions` 逐条具名 + 写理由，
命中例外会在校验报告里**显式打印**（不静默跳过），且例外清单自身受"僵尸条目"检测
（未在任何产物里命中 ⇒ 报错，防止判据宽度与实际不符）。

### 4.6 怎么跑

```bash
cd contract/sdk-generator
# 静态自审（不接触产物）：裁剪逻辑是否与矩阵自洽
python _sdk_pipeline.py audit  ../openapi-v1.0.0.yaml generator-matrix.yaml
# 生成 + 校验（要 node 与已安装的 generator）
python _sdk_pipeline.py emit   ../openapi-v1.0.0.yaml generator-matrix.yaml <node> <generator-main.js> _cut
# 只校验已有产物
python _sdk_pipeline.py verify ../openapi-v1.0.0.yaml generator-matrix.yaml
```

反向验证（证明判据有牙齿，而非"恰好没红"）：
```bash
python skeleton/verification/115_sdk_pipeline_reverse_verification.py
```
—— 9 组注入（A–I），含两组**"期望仍绿"**的注入（钉住判据不过度），
并在系统临时目录的**副本**上运行，末尾以**逐文件哈希比对**自证真实树零写入。<br>
实测结论：**18/18 PASS**。

**本仓不做的事**：不伪造产物、不把"配置已写"说成"SDK 已生成"、不在缺件时静默降级、不用放宽判据的方式消假红。

---

## 五、变更流程（谁能改 / 怎么改 / 如何留痕）

| 级别 | 触发条件 | 审批 | 留痕 |
|---|---|---|---|
| **MAJOR**（`1.0.0 → 2.0.0`） | ① 字段**删除 / 改名 / 类型变更**；② **可见性档位变更**；③ 枚举值语义变更；④ 错误码语义变更 | owner **+ 产品共签**；**若涉可见性档位 → 触发「变更业务裁定」**（不得按"体验优化"处理） | CHANGELOG + OpenAPI diff + **重走冻结闸门** |
| **MINOR**（`1.0.0 → 1.1.0`） | 新增字段（向后兼容） / 新增端点 | owner | CHANGELOG |
| **PATCH**（`1.0.0 → 1.0.1`） | 文案 / 注释 / 文档级澄清（**不改任何档位、不改枚举取值集**） | owner | CHANGELOG |

**硬约束**：
- 🔴 **客户侧 ③ 缺口原因 / ④ 派生结果 = 硬边界**。任何"通过配置放开"都不成立 —— 放开属**变更业务裁定**。
- 🔴 可见性一律**服务端 403 裁剪**；前端不渲染**不算**实现（X-1：声明 ≠ 授权，二者须一致）。
- 🔴 客户可见字段名 / 枚举值**不得含**「退款」「诊断」，不得含「未佩戴 / 缺失 / 达标 / 不达标」。
- 🔴 **TBD 不得填数**；**缺失不得补 0**。带 `x-tbd-allowed` 的字段类型必须允许 TBD 字面。
- 🔴 `gap_reason` 客户端一律 403（不因 C-3 客户端同步态与内部枚举**同形**而泄漏）。

---

## 六、常见误用（写在最前面，避免重犯）

| ❌ 误用 | ✅ 正确做法 |
|---|---|
| 把 §2.7 退款域可见性（config **#40**，角色级二分）与手环可见性（config **#43**，角色 × 字段组四档）合并 | 两者**独立配置、不得合并**（契约 §3.1 冲突点 C-2） |
| 把「手环接入状态」单列成第 5 个字段组 | 归入 **② 采集状态**（contract §3.3 冲突点 C-1） |
| 客户端"同步失败原因"直接复用内部 `gap_reason` 枚举 | 两套**独立枚举**：`client_sync_state`（4 值，无"未佩戴"）↔ 内部 `gap_reason`（7 值）（C-3） |
| 用 `compliance_removal` 拼写 | 正确拼写 **`compliant_removal`**（R1 裁定，`-t` 结尾） |
| 客户小程序里"隐藏"派生字段 | 服务端**不下发**（字段整体不存在，不是 `null`、不是空串） |