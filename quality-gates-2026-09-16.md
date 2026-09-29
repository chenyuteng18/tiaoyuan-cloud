# 调元云 · 质量门禁与验收标准规范

| 项目 | 内容 |
|---|---|
| **文档类型** | 质量门禁与验收标准规范（Quality Gates & Acceptance Standard） |
| **出具** | 析客（需求分析师）· 产品战略团队 |
| **日期** | 2026-09-19（文件名沿用 09-16） |
| **本件解决什么问题** | **防止"测试全绿但实现是错的"这类事故再次发生。** 骨架工程曾报告「`BUILD SUCCESS` + 测试全绿」，但主理人独立复核发现 **7 处缺陷、其中 4 处严重**（`skeleton/README.md` §5.1 L137-143）——包括 `ErrorCode` 整表偏离契约、`RlsSessionAspect` 因取错连接导致 **RLS 永远失效**、未认证请求可自带头自选租户。**测试之所以全绿，是因为断言的是实现者自己的实现，而非外部契约**——实现错、测试也错、一起绿。**本规范就是要把这类"自证式绿色"变成不可通过的红色。** |
| **适用范围** | 骨架工程及后续所有开发任务（Sprint 1 起）；CI 流水线；代码评审；验收签核 |
| **基准（唯一共享）** | `_work/arch-decisions-lead-2026-09-19.md`（ADR-01~ADR-13）· `_work/contract-t6-api-freeze-2026-09-19.md` · `_work/data-dict-entities-ddl-2026-09-19.md` · `PRD §2.4 X-1/X-2/X-3` |
| **纪律** | 所有引用带**文件名 §章节 / 行号**；无出处标「待确认」；**严禁编造行号**；如实区分"已定案（ADR）"与"建议新增" |

> ⚠️ **诚实标注（本文档以身作则）**：本件写作时核对到一处**计数不一致** —— 任务说明称事故发生在"25 测试全绿"，而骨架 `README.md` L94 与 `sprint1-kickoff-2026-09-16.md` L23 均记为「**36 测试全绿**」。**本件一律以可复核的实证来源（骨架 README / build.log）为准**，并在正文标注该差异 —— **这正是本规范第 1 部分要求的行为：断言外部基准，不采信任何一方的自述**。
>
> 📌 **计数更新（2026-09-16，主理人补 A1 后）**：A1「JWT 签名校验补全」已由主理人实施完成，测试数由 **36 → 58**（新增 15 个 JWT 攻击回归 + 6 个过滤器级 + 1 个信封回归），最终 `BUILD SUCCESS` / **58 测试 0 失败 0 错误**（`skeleton/build.log`）。反向验证实证例相应由 2 个增至 **5 个**（§2.2）。**A1 的实施过程恰好印证了本规范：新增的过滤器级测试当场抓出了"错误响应丢失 `trace_id`"这一被掩盖的契约违约**（§4 · G-2/G-12）。

---

# 1. 验收的第一原则：断言外部基准，不断言自身实现

## 1.1 原则

> **任何断言，其"期望值"必须来自一个独立于实现的真相源（契约文件 / 枚举表 / HTTP 语义 / SQL 引擎行为），而不是来自被测代码自身的常量或方法。**

自证式断言是"实现错 → 断言也错 → 一起绿"的根因（`skeleton/README.md` §5.1 #7 L143：「测试为自证式（断言自身实现而非契约）」）。

## 1.2 ❌ 错误写法 vs ✅ 正确写法

| # | ❌ 自证式（禁止） | 为什么错 | ✅ 锚定外部真相源 |
|---|---|---|---|
| 1 | `assertEquals(2003, ErrorCode.TENANT_MISMATCH.getCode())` | 右边取自被测枚举自身 → 枚举整体偏移也测不出 | **逐条比对契约 §2.0 的 11 个码值 / 名称 / HTTP**（`contract-t6-api-freeze L107-119`）：`assertCode("TENANT_MISMATCH", 2003, 403)`，期望值**硬编码自契约表**，并加注释指向 `contract §2.0 L113` |
| 2 | `assertTrue(errorCode.getHttpStatus() == 403)` | 读的是实现的返回，不是契约规定的 HTTP 语义 | 断言 **`503`/`5001` 必须是 `422` 而不是 `502`** —— 期望值来自 `contract L117`（`5001 BUSINESS_RULE_VIOLATED` → **422**），与 `ADR-06`（arch-decisions-lead L78）一致 |
| 3 | `assertEquals(expected, masker.mask(field))`，其中 `expected` 由另一个 masker 调用产生 | 两个调用同一个错误实现 → 恒等 | 断言**序列化后的 JSON 字符串里 key 完全不存在**：`assertFalse(json.contains("\"refund\""))` —— 期望值来自 `ADR-07`（arch-decisions-lead L88-90）「序列化前整体移除，JSON 中不出现该 key」，不是来自 `FieldMasker` 的返回值 |
| 4 | `assertEquals(1, repo.count())` 在测试自建的 H2 内存表上 | 测的是测试夹具，不是交付的真实迁移脚本 | 断言必须跑**骨架自己的迁移文件**（`verification/015_apply.sql` L16 用 `\i` **直接引入骨架 V1**，明确注明"不复制粘贴"，否则"验证的是我手里抄的一份 SQL，而不是我们要交付的迁移脚本"） |
| 5 | `assertNotNull(ctx.getTenantId())`（在无 token 场景） | 只要实现"兜底"了就通过，漏洞测不出 | 断言**必须是 `null`**：`expected: <null> but was: <attacker-chosen-tenant>`（`skeleton/README.md` L125）—— 期望值来自 `contract §2.0 L98` / `T-3`（清单 L44）「头若存在必须与 token 一致」 |
| 6 | 只 grep 脚本文本含 `FORCE` 就断言"隔离生效" | **文本存在 ≠ 行为生效** | 断言**行为**：在真实 PG 上「设租户 A 上下文 → 读租户 B 的行 → 必须 0 行」（`verification/03_assert.sql` A1 L28-44） |

## 1.3 落地要求

- **每个测试类头部注明其外部真相源**（文件名 + 行号），无来源的期望值一律视为自证式、评审不通过。
- **契约一致性测试为强制项**：`ErrorCode` / 响应信封四字段 / 枚举值域 / 错误码 HTTP 语义，必须逐条对照 `contract §2.0`（L96-119）。
- **禁止**在测试中重复实现被测逻辑来"交叉验证"（两个相同错误实现会互相印证）。

---

# 2. 反向验证（Regression Injection）——关键逻辑的验收必备步骤

## 2.1 方法论

> 一个"只会在正确实现下通过"的测试，和一个"永远通过"的测试，**外观完全一样**。区分二者的唯一办法 = **故意植入错误，看它是否报错**。
> （`verification/90_break_and_assert.ps1` L4-5）

**标准动作（三步）**：
1. **故意把实现改回错误状态**；
2. 断言测试**必须失败**（记录失败断言原文）；
3. **恢复**实现 → 断言**必须通过**。

## 2.2 本项目已用此法的**五个**实证例

| # | 注入的缺陷 | 期望失败 | **实际失败断言原文** | 出处 |
|---|---|---|---|---|
| **I-1** | 把 `5001` 的 HTTP 状态从 `422` **改回 `502`**（初版错误） | 契约一致性测试必须失败 | `expected: <422> but was: <502>` | `skeleton/README.md` L124 |
| **I-2** | 让无 token 请求用 `X-Tenant-Id` 头**兜底建立租户上下文**（初版漏洞） | 安全回归测试必须失败 | `expected: <null> but was: <attacker-chosen-tenant>` | `skeleton/README.md` L125 |
| **I-3** | 去掉 JWT 的 `alg` 等值判断（模拟 **`alg:none`** 绕过） | JWT 攻击回归必须失败 | `Expected JwtValidationException to be thrown, but nothing was thrown`（`alg_confusion_rs256_header_must_be_rejected`） | `verification/REVERSE-VERIFICATION-jwt-2026-09-16.md` |
| **I-4** | 去掉 JWT **签名比较**（模拟完全不验签） | 篡改/错密钥两条必须失败 | `tampered_payload_must_be_rejected` + `token_signed_with_wrong_secret_must_be_rejected` **同时失败** | 同上 |
| **I-5** | 去掉 JWT **`exp` 校验**（模拟永久有效 token） | 过期用例必须失败 | `expired_token_must_be_rejected` **立即失败** | 同上 |

> **五例均已还原**，恢复后 `BUILD SUCCESS` / **58 测试全绿**（`skeleton/build.log`）。
> **I-3/I-4/I-5 是同一模式作用于不同攻击面**——说明"反向验证"不是一次性动作，而是**每个安全断言都要各自证明有牙齿**。

## 2.3 RLS 端到端的三个破坏场景（**在本项目真实 PG 上执行过**）

来源：`skeleton/verification/90_break_and_assert.ps1`（脚本注释 L28-59）＋ 实际运行留痕 ``.tools/rls-verify/run4-reverse.txt``：

| 场景 | 注入内容 | 脚本预期失败点 | **实际运行结果（run4-reverse.txt）** |
|---|---|---|---|
| **BREAK-1** | 去掉 `NULLIF`，改用 `COALESCE(..., '<默认租户>')`（fail-open 经典入口） | A3 / A0 段 | **EXIT=3（被抓住）**，A3 失败：「未设上下文时读到 1 行, 应为 0 行 (fail-closed 被破坏!)」 |
| **BREAK-2** | `NO FORCE ROW LEVEL SECURITY`（让 owner 绕过策略） | 脚本注释写"expect FAIL at A6" | **EXIT=3（被抓住）**，但**实际在 A1 就失败**：「租户A 应只见 1 行, 实际 2 行」 |
| **BREAK-3** | `CREATE POLICY ... USING (true) WITH CHECK (true)`（最粗暴全开） | A1 / A3 | **EXIT=3（被抓住）**，A1 失败：「租户A 应只见 1 行, 实际 2 行」 |
| **RESTORE** | 恢复正确策略（`NULLIF` + `FORCE`） | 必须通过 | **EXIT=0**，`ALL RLS ASSERTIONS PASSED (A0-A8)` |

> **⚠️ 诚实修正（本件发现）**：BREAK-2 脚本注释预期"在 A6 失败"，**实际在 A1 先失败** —— 因为关闭 `FORCE` 后 owner 读到全表 2 行，**A1（第一个隔离断言）就先抓住了它**。这不影响"被抓住"的结论，但说明**文档中的预期失败点不可信，必须以实际失败断言原文为准**（这正是本规范要求记录"实际失败断言原文"三栏的原因）。**建议 owner 修正 `90_break_and_assert.ps1` 注释**（仅注释，不动逻辑）。

## 2.4 规范模板（**每个关键逻辑必须附此表**）

```
### 反向验证记录 · <模块/逻辑名>
- 验证人 / 日期：________________
- 所用的正向断言集：<文件/测试类 + 真相源出处>

| 注入内容（改了什么） | 预期失败点 | 实际失败断言原文 | 恢复后是否通过 |
|---|---|---|---|
| | | | |

- 结论：□ 测试有牙齿（注入即失败）  □ 测试无牙齿（注入后仍通过）→ 必须重写测试
```

**关键逻辑的界定（必须做反向验证的）**：多租户隔离（RLS 策略与连接取用）· 字段裁剪（派生字段不下发）· 错误码与 HTTP 语义 · 门禁 403 与 `missing_items[]` · 幂等 upsert 键 · 审计 append-only · 状态机守卫。

---

# 3. 租户隔离的三层门禁（引用 ADR-02）

> 依据：`ADR-02`（arch-decisions-lead L29-38）· `architecture-saas §2.2`（L185-195）。

| 层 | 机制 | 门禁要求 | 出处 |
|---|---|---|---|
| **第 1 层 · 应用层** | 所有写入**强制注入 `tenant_id`**（ORM 拦截 / 基类） | 未注入 tenant_id 的写路径**不得存在**；上下文**一次解析、链路透传**；**绝不信请求体 / 查询参数 / 请求头的 `tenant_id`** | `ADR-02` L31 · `T-1`（清单 L42）· `T-3`（清单 L44）· `C-4`（arch-decisions-lead L35） |
| **第 2 层 · 存储层（强制）** | RLS `ENABLE` + **`FORCE ROW LEVEL SECURITY`** + 策略 `USING`/**`WITH CHECK`** 双 `NULLIF` fail-closed | `FORCE` 不可缺（否则 owner 绕过）；策略必须 `FOR ALL` 且 **`USING` 与 `WITH CHECK` 并存**；**严禁任何租户默认值兜底**（`COALESCE(...,'<uuid>')` / `USING (true)`） | `ADR-02` L32-35 · `H-14`（清单 L34）· `S2.3.1`（arch-saas L216-220） |
| **第 3 层 · CI** | **每张新表**必须带租户泄漏测试，**缺测试 = 构建失败** | 新表 PR 若未附隔离测试 → **流水线阻断** | `ADR-02` L36 · `architecture-saas §2.4` L273-277 |

**强制 `SET LOCAL`，禁用 `SET`**（`ADR-02` L37）：连接池（transaction pooling）下会话级 `SET` 会残留在归还的连接上 → **跨请求串租户**。

## 3.1 本项目已建立的真实 PostgreSQL 端到端验证（**非文本 grep**）

位置：`deliverables/product-strategy/skeleton/verification/`（同一套已同步到 `.tools/rls-verify/`）。**验证的是行为，不是文本**（`03_assert.sql` L7：「断言的是【行为】(查询返回几行)，不是【文本】」）。

| 文件 | 作用 | 关键点 |
|---|---|---|
| `01_reset.sql` | 重建库 + 建**非超级用户** `dy_app` | L6-7：超级用户**总是绕过 RLS**，用 postgres 测会"假通过"（`ADR-02 §2.3.4` 陷阱 3） |
| `015_apply.sql` | **以超级用户应用骨架真实迁移**，再把表 owner 移交 `dy_app` | L6-7：用 `\i` **直接引入骨架 V1**、不复制粘贴；L8-11：owner 移交给**非超级用户**，`FORCE` 才有真实证明力 |
| `02_seed.sql` | 两个"租户"各插一行 | 证明"设对上下文"的写入**被放行**（策略连通，不是全表拒绝） |
| `03_assert.sql` | **A0~A8 八条真断言**（`DO` 块 + `RAISE EXCEPTION`）；`psql -v ON_ERROR_STOP=1` 返回码即门禁 | 见 §3.2 |
| `90_break_and_assert.ps1` | 反向验证，证明断言"有牙齿" | 见 §2.3 |

## 3.2 `03_assert.sql` 的 A0~A8 各断言什么（逐条）

| 断言 | 断什么 | 为什么必须有 | 行号 |
|---|---|---|---|
| **A0** | **当前连接不是超级用户**（`rolsuper` 检查） | **防"超级用户假通过"** —— 超级用户绕过 RLS，若误用超级用户跑本脚本，**所有隔离断言都会假通过** | L17-23 |
| **A1** | 租户 A 上下文下：`count(*)=1` **且**看不到租户 B 的行 | 隔离核心（**双向之一**） | L28-44 |
| **A2** | 租户 B 上下文下：`count(*)=1` **且**看不到租户 A 的行 | **对称性**（防"只单向隔离"） | L49-62 |
| **A3** | **完全未设上下文 → 零行**（不是全表） | fail-closed 形态 ① ：变量从未设置 | L67-75 |
| **A4** | **上下文被显式设为空串 → 零行** | fail-closed 形态 ② ：**`NULLIF` 的作用点**（把空串也归一到"零行"，而非退化成 `''::uuid` 异常） | L82-93 |
| **A5** | 租户 A 上下文下**不能插入属于租户 B 的行**（跨租户写入被拒） | **写入侧 `WITH CHECK`** —— 只测 `USING`（读侧）会漏掉写侧越权 | L98-115 |
| **A6** | **`FORCE` 已启用**（读 `relforcerowsecurity`）**且** owner 也读到 0 行 | **防表 owner 绕过全部策略**（`ADR-02` 陷阱 1） | L122-134 |
| **A7** | 查 **`pg_policies` 元数据**：策略存在、`USING` 与 `WITH CHECK` **都含 `NULLIF`** | **策略覆盖面**——直接读引擎元数据，比文本 grep 权威（防"拆策略后写入校验静默消失"） | L140-155 |
| **A8** | 多次切换（A→B）后**"可见行 = 当前租户"这一不变量仍成立** | **切换后不变量**（防连接池残留上下文导致的串租户） | L162-173 |

> **反向验证证明其"有牙齿"**：注入三种 fail-open（① `COALESCE` 默认租户 / ② 关 `FORCE` / ③ `USING(true)`）**均被抓住**（§2.3）。**一个只 grep 脚本的检查无法区分这三者**——这正是"行为断言 > 文本断言"的证据。

---

# 4. CI 门禁清单（逐条给出"失败即阻断"判定）

> 依据：`architecture-saas §1.1/§2.4/§4.5/§8.5` · `ADR-02/03/12/13` · `contract §6.2`（L592-605）。下方每条**任一不满足即构建失败、PR 阻断**。

| # | 门禁 | 判定（失败即阻断） | 出处 |
|---|---|---|---|
| **G-1** | **ArchUnit 依赖方向** | `dy-app → {security,config,audit,web} → tenancy → common` 单向；**反向依赖 / 跨层跳跃 / 业务模块互赖内部包 = 构建失败** | `ADR-03/04` L59-60 · `architecture-saas §1.1` L125-129 · `A-3`（清单 L57） |
| | ⚠️ **ArchUnit 陷阱** | Surefire 3.5.3 存在 bug（TNG/ArchUnit#1442）致 `@ArchTest` **静默不执行** → 表现为"构建全绿但 `Tests run: 0`"。**必须核对 `Tests run` 计数**，不能只看构建颜色 | `architecture-saas §1.1` L131（竞析 B.3） |
| **G-2** | **契约一致性测试** | `ErrorCode` 全量逐条比对 `contract §2.0`（11 个码值 / 名称 / **HTTP**）；信封四字段；`trace_id` 必须 snake_case；`code != 0` 时 `data` 为空 | `contract L96-119` · `A-3/A-6`（清单 L67·L70）· 骨架 `ResultEnvelopeTest`（README L98） |
| | ⚠️ **信封陷阱（本项目实证）** | `@JsonInclude(NON_NULL)` 会**吞掉 null 的 `trace_id`**，使错误响应不满足"四字段固定"；**修复方式**：`traceId` 显式 `@JsonProperty("trace_id")` + `@JsonInclude(ALWAYS)`。**门禁要求：断言"`trace_id` key 必须存在"（而非"值非空"）**，并对 `code != 0` 的成功/失败两条路径各测一次 | `skeleton/README.md` §三·修正表 · `A-1`（本件 §1 反面例） |
| **G-3** | **契约回归（三端 SDK 与契约字段逐一比对）** | 从 OpenAPI 抽 `(path, method, request_fields, response_fields)`；**任一端字段集 ≠ 契约字段集（缺/多字段）→ FAIL**；类型不匹配 → FAIL；可见性断言与 §3 矩阵任一格不符 → FAIL；**`N` 硬编码断言**（grep `retention_window_days\s*=\s*\d+` 命中即 FAIL）；幂等键断言（重复提交须 upsert 而非追加） | `contract §6.2` L592-605 · `A-10`（清单 L74）· `A-13`（清单 L77） |
| **G-4** | **ArchUnit 模块边界**（同 G-1，独立列出以对齐 ADR-04） | 7 模块边界；业务模块不得互赖内部包 | `ADR-04` L60 · `architecture-saas §1.2` L147 |
| **G-5** | **RLS 真库断言** | 在真实 PostgreSQL 上以**非超级用户**执行 `03_assert.sql`（A0~A8）；**`psql -v ON_ERROR_STOP=1` 退出码即门禁**（非 0 → 阻断） | `verification/03_assert.sql` L11 · `ADR-02` L32-36 · `A-2`（清单 L56） |
| **G-6** | **每表租户泄漏测试（缺表即失败）** | **每张新表**必须附"租户 A 建行 → 切租户 B → 读必返零行"用例；**缺测试 = 构建失败** | `ADR-02` L36 · `architecture-saas §2.4` L273-277 · `A-2` |
| **G-7** | **构建期合规扫描（三扫描面，owner 不可空）** | 见 §4.1；命中即失败 | `ADR-12` L118-124 · `H-2/H-11/H-12`（清单 L22·L31·L32）· `A-10`（清单 L64） |
| **G-8** | **OpenAPI lint（Spectral）** | 对 `client_end=mp` 可见字段做词表校验，命中即 PR 阻断 | `contract L572` · `A-11`（清单 L75） |
| **G-9** | **破坏性变更检测（oasdiff）+ 生成物漂移检查** | 重生成类型后 `git diff --exit-code` 非零 → 阻断；可见性档位变更 / 字段删除 / 类型变更 = **MAJOR** | `architecture-saas §9.5` L780 · `A-8/A-10`（清单 L72·L74） |
| **G-10** | **显式 UTF-8** | 根 `pom.xml` 含 `<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>`；本机平台编码 GBK，**不声明必乱码** | `ADR-13` L129 · `A-12`（清单 L66） |
| **G-11** | **测试计数核对** | 报告"测试全绿"时必须附 `Tests run` 计数（防 G-1 陷阱的"0 测试假绿"） | `architecture-saas §1.1` L131 |
| **G-12** | **过滤器响应契约** | Servlet `Filter` 内抛异常**不进** `@RestControllerAdvice`（成为容器错误页）→ 所有过滤器必须**自写契约信封**；**门禁：过滤器级测试须断言 401/403 响应体含 `trace_id`** | `skeleton/README.md` §三·过滤器陷阱 · 建议的过滤器级测试 `TenantContextFilterTest` |
| **G-13** | **JWT 攻击面回归** | 必须覆盖 `alg:none` / 算法混淆 / 篡改 payload / 错密钥 / 过期 / 非法格式；**签名比较必须恒时**（`MessageDigest.isEqual`）；**禁用只解码不验签的解析器** | `verification/REVERSE-VERIFICATION-jwt-2026-09-16.md` · `A1`（sprint1-kickoff） |

## 4.1 构建期合规扫描（G-7 展开：**owner 不可空**）

> 依据：`ADR-12`（arch-decisions-lead L118-124）· `architecture-saas §8.5`（L682-705）· `contract §5.2`（L566-575）。

| 扫描面 | 内容 | 覆盖范围（逐类） |
|---|---|---|
| **扫描面 1 · 退款字样** | 小程序包内**不得含「退款」** | **i18n 文案表 / 枚举名 / 常量 / 报错文案 / 埋点名 / 订阅消息模板**（"页面改了、推送没改等于没改"） |
| **扫描面 2 · 负向措辞** | 客户端**不得出现负向计数 / 归因措辞** | "缺失 N 天""未佩戴""未达标""数据不全"（`PRD §2.6.2` W-2 L289） |
| **扫描面 3 · 派生字段** | **派生字段不得下发** | grep `gap_reason` / `effect_verdict` / `AS_refund` / 派生结论文案（`PRD §2.6.7` R15 L345 · `contract §3.2` L374） |

**硬要求**：
- **命中即构建失败**；**`owner` 字段不可为空**（为空 = 扫描在第一次加新文案后**静默失效**，而失效是看不见的——与 R4d「转交静默丢失」同型，`README L134`）。
- **禁用接口扫描**：小程序包内 grep §2.7 退款域路径 → 命中即失败（`contract §6.2` L603）。
- **枚举命名空间隔离**：客户可见枚举以 `client_*` 前缀；内部枚举以 `internal_*` / 原名；**两套不得共用类型定义**（`contract L571`）。
- **抓包双验**：客户 token 抓包，响应体 grep 禁用词 → 命中即失败（`contract §5.2` L573）。
- **服务端 `message` 必须先过合规词表**，否则会把红线词直接送到小程序界面（`ADR-06` · arch-saas §4.3 L420）。

> ⚠️ **当前状态（骨架未完成项）**：ADR-12 构建期扫描**尚未实现**（`skeleton/README.md` §5.2 L153：「未实现」）；`FieldMasker` 已从源头消除字段存在性泄露，但**包级文案扫描另需**。**G-7 是 Sprint 1 必须补齐的门禁。**

---

# 5. 可观测性验收口径

> 依据：`ADR-09`（arch-decisions-lead L100-106）· `architecture-saas §7`（L577-633）。

## 5.1 指标标签：**禁止给指标打 `tenant_id` 标签**（必须）

| 规则 | 说明 | 出处 |
|---|---|---|
| **指标上禁止 `tenant_id` 标签** | **高基数会打爆时序库** | `ADR-09` L103 · `H.1-13` · `architecture-saas §7.2` L591-599 |
| **租户维度分析走日志 / 数仓，不走 metrics** | 需要按租户看时，用日志或数仓，不进指标 | `ADR-09` L103 |
| **Trace 携带 `tenant_id`** | **但必须配套采样控制**，否则 Trace 存储同样被高基数拖垮 | `ADR-09` L105 · 竞析 H.2-7 |
| **统一标签** | 只用低基数：`service.name` / `env` / `version` | `ADR-09` L102 |

**验收判定**：CI / 评审中若发现任何指标定义携带 `tenant_id`（或其它高基数租户维度）标签 → **阻断**。

## 5.2 审计日志验收口径

| 要求 | 判定 | 出处 |
|---|---|---|
| **审计日志与应用日志物理分离**（独立表 / 独立文件） | 保留期、访问控制、完整性要求均不同 | `ADR-09` L104 · 竞析 D.4 |
| **撤销所有用户（含应用服务账号）对 `audit_log` 的 UPDATE/DELETE 权限** | 应用只能 INSERT；**即使被攻破也无法删除日志** | 竞析 D.4 · 骨架 `audit_log.sql`（README L84） |
| **哈希链** `entry_hash = SHA256(prev_hash + event_data)` | 校验 = 重算整条链；**校验端点未实现 = 未完成项** | `ADR-09` L104 · `README §5.2` L150 |
| **记录敏感个人信息的"只读访问"**（`action_type` 含 `VIEW_PII`） | 心率/血氧/睡眠属 PIPL 敏感个人信息，访问须留痕 | `ADR-09` L106 · 竞析 H.1-17 |
| **跨租户访问须留审计日志** | M0 验收：A 租户查 B 租户 → 403 **且** 审计日志有记 | `PRD §11.3 M0` L1043 · `O-2`（清单 L187） |

## 5.3 SLO / SLI 验收口径

- 定义 **3~5 个以用户旅程为中心的 SLI/SLO**（调理师 APP 核心路径 / 客户小程序核心路径 / **判定链路正确率**），**而非笼统可用率**（`architecture-saas §7.4` L624）。
- ⚠️ **医疗健康场景中"判定正确率"比"接口 200 率"更接近用户价值**（同件 L625）。
- **不引入** Stripe 式分层可观测体系、**不引入**独立 SIEM（`ADR-14` / 清单 H.3-11/H.3-12）。
- **构建期扫描结果 = 可观测的合规门禁**（命中即失败，**owner 不可空**，否则静默失效）。

---

# 6. 附录：本规范与本项目缺陷清单的对应

> 骨架复核发现的 7 处缺陷（`skeleton/README.md` §5.1 L137-143）→ 本规范哪一条能防住：

| # | 缺陷（README §5.1） | 防住它的条款 |
|---|---|---|
| 1 | `ErrorCode` 整表偏离契约（自造码值） | **§1.2 表 #1**（逐条比对契约）+ **G-2** |
| 2 | `GlobalExceptionHandler` 按码段猜 HTTP（`5xxx→502`） | **§1.2 表 #2**（断言 422 而非 502）+ **G-2** |
| 3 | **`RlsSessionAspect` 无效**（`ds.getConnection()` 另开连接 → RLS 永远失效） | **§3 第 1/2 层** + **G-5** + 骨架 `RlsSessionAspectTest`（连接 identity 断言，README L100） |
| 4 | `TenantContextFilter` 未认证可用 `X-Tenant-Id` 自选租户 | **§1.2 表 #5** + **§2.2 例 I-2** + **G-5** |
| 5 | 信封输出 `traceId` 而非 `trace_id` | **G-2**（字段名比对契约） |
| 6 | RLS 策略缺 `WITH CHECK` | **§3.2 断言 A5/A7**（写入侧 + 元数据双 NULLIF） |
| 7 | **测试为自证式**（断言自身实现而非契约） | **§1 全节** + **§2 反向验证** + **G-11** |
| 8 | **错误响应丢失 `trace_id`**（`@JsonInclude(NON_NULL)` 吞掉 null 字段）→ 违反"四字段固定" | **G-2 ⚠️信封陷阱** + **G-12**（过滤器须断言 `trace_id` key 存在） |
| 9 | **`TraceIdFilter` 顺序不确定**（可能晚于租户过滤器 → 401/403 无 trace） | **G-12**（过滤器须 `@Order` 前置） |
| 10 | **只解码不验签的 `JwtParser` 存在**（随时可能被误用而绕过验签） | **G-13**（禁用该解析器；已删除） |

---

> **本件由析客（需求分析师）依骨架 README / 架构规格书 / ADR-01~13 / 契约 / 验证脚本精读产出，2026-09-19。所有引用带"文件名 §章节 / 行号"；发现并标注了"任务说明 25 测试 vs 骨架 36 测试""BREAK-2 预期失败点与实测不符"两处不一致，**均以可复核实证为准、未编造行号**。本件为质量门禁规范，不改任何其他文件；未新增需求编号、未改封版数值。建议均与 ADR-01~ADR-13 一致。**
>
> **更新记录（2026-09-16，主理人）**：A1 完成后同步更新 —— 测试计数 36 → **58**；反向验证实证例 2 → **5**（新增 I-3/I-4/I-5，JWT 三攻击面）；新增 **G-12（过滤器响应契约）** 与 **G-13（JWT 攻击面回归）**；§1 缺陷对照表由 7 条扩至 **10 条**（新增 8/9/10，均为本轮实测发现）。**所有更新以 `skeleton/build.log` 与 `verification/REVERSE-VERIFICATION-jwt-2026-09-16.md` 为实证来源。**