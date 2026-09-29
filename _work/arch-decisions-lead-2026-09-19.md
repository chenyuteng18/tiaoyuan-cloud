# 架构裁定（主理人方向明 · Lead Decisions）

| 项目 | 内容 |
|---|---|
| **日期** | 2026-09-19 |
| **出具** | 方向明（Fang）· 产品舵手 · 主理人 |
| **性质** | **权威裁定**。本文件是「架构规格书」与「可运行骨架」两条并行线的**唯一共享基准**，两方产物如有冲突一律以本文件为准 |
| **输入** | 竞析《国际顶级商用 SaaS 架构对标研究》`_work/arch-benchmark-2026-09-19.md` · 析客《架构框架需求清单》`_work/arch-constraints-inventory-2026-09-19.md` · 用户决策（Java/Spring Boot 3 · 公有云多租户 SaaS · 规格书+骨架） |

---

## 〇、纲领

> 从国际顶级 SaaS 迁移的是**「约束与契约」**（隔离不靠自觉、错误码可编程、契约先于代码、配置可审计可回滚），**不是「分布式基础设施」**（分片中间件、多区域多活、自研多租户内核、微服务网格）。前者成本极低、收益极高、越早做越便宜；后者是规模驱动的产物，提前引入只会带来灾难。

## 〇之二、体量校准（一切裁定的基准）

调元云 vs 被对标平台差 **3~5 个数量级**（Slack 千万 DAU / Stripe 3000 工程师 / Notion 数百 TB / HubSpot 1000 工程师改造）。因此：**凡"因规模而生"的实践一律不采纳**。竞析 H.3 的 12 条反对全部接受。

---

## 一、ADR-01 多租户隔离模型：Pool（池化）

- **裁定**：**Pool 模型** —— 共享库 + 共享 schema + 全表 `tenant_id` + PostgreSQL **RLS**。
- **否决**：Silo（database-per-tenant）/ Bridge / schema-per-tenant。
- **理由**：租户数为个位数到低三位数，Silo 的成本收益比极差、运维负担线性上升（竞析 H.3-3）。
- **迁移预留**：保留 `tenant.datastore_hint` 字段（值 TBD），未来高付费企业租户可平滑升级 Silo，**本轮不实现**。

## 二、ADR-02 隔离强度：三层纵深防御（不只靠 RLS）

- **第 1 层（应用层）**：所有写入强制注入 `tenant_id`（ORM 拦截 / 基类）。
- **第 2 层（存储层 · 强制）**：RLS `ALTER TABLE … ENABLE ROW LEVEL SECURITY` **+ `FORCE ROW LEVEL SECURITY`**（防表 owner 绕过）；策略用 `USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)`。
  - **`NULLIF` 的精确作用（勿误读）**：把「变量从未设置（→`NULL`）」与「变量被设为空串（→`''`）」两种无上下文形态**归一到同一条 fail-closed 路径**（比较 `tenant_id = NULL` → `UNKNOWN` → **零行**）。
  - 去掉 `NULLIF` 并不会泄漏数据，但**空串形态会因 `''::uuid` 抛异常 → 表现"偶发 500"**；`NULLIF` 的价值在于**堵死"为了修这个 500 而引入默认租户"的路**。
  - **严禁**任何"租户默认值兜底"：`COALESCE(current_setting(...), '<uuid>')` / 配置默认租户 / 未设即用 admin 租户 / `USING (true)`。**未设上下文 = 零行，这是唯一允许的语义**（H-14 fail-closed）。
- **第 3 层（CI）**：**每张新表**必须带租户泄漏测试，缺测试 = 构建失败。
- **强制 `SET LOCAL`，禁用 `SET`**：`SET LOCAL` 随事务结束自动失效，避免连接池归还后残留上下文造成跨请求串租户。
- **否决**：仅依赖单一 RLS（PlanetScale 的批评成立——RLS 不是银弹）。

## 三、ADR-03 架构风格：模块化单体（Modulith），明确拒绝微服务

- **裁定**：**Maven 多模块 + Spring Modulith**，包私有强制边界。
- **否决**：微服务拆分、Kubernetes、服务网格、自研 PaaS（竞析 H.3-1/H.3-2）。Atlassian（10 万客户）官方承认单一产品公司微服务可能没必要。
- **边界强制**：`ApplicationModules.verify()` 或 ArchUnit 进 CI —— **依赖方向违规 = 构建失败**，不靠 code review 自觉。
- **调用语义**：查询与强事务不变式走**同步调用**；副作用与通知走**事件**（Spring Modulith Event Publication Registry，替代 MQ；须评估其对 append-only 表的侵入性 —— 竞析 H.2-2）。

## 四、ADR-04 模块划分：7 个模块

| 模块 | 职责 | 承载约束 |
|---|---|---|
| `dy-common` | 响应信封 `Result<T>`、错误码枚举、业务异常、trace_id、审计基类 | A-3/A-5/A-6/O-5 · X-4/X-6 |
| `dy-tenancy` | 租户上下文（ThreadLocal→透传）、`X-Tenant-Id` 一致性、RLS 会话变量、行级 scope | T-1~T-3/T-7 · H-14 · X-1/X-2 |
| `dy-security` | JWT 校验、角色×字段组四档、字段裁剪（序列化前）、门禁守卫 | T-4~T-6/H-1/H-4/H-8 · X-3/X-5/X-14 |
| `dy-web` | 幂等键（24h TTL）、全局异常处理、分页、限流水位 | A-7 · X-7 |
| `dy-audit` | 审计字段自动填充、append-only 审计日志 + 哈希链 + 校验端点 | D-3/O-1/O-3 · X-6 |
| `dy-config` | 配置中心（46 条非空 #1–#48，含 #42 空号 / #47 预留）、保存时 fail-closed 校验、变更日志 | §五 · H-13/H-14 · X-8/X-18 |
| `dy-app` | 启动模块、Flyway 迁移基线、业务模块挂载、契约测试 | — |

- **依赖方向（单向，严禁反向）**：`dy-app → {dy-security, dy-config, dy-audit, dy-web} → dy-tenancy → dy-common`。
- 业务模块（customer / band / assessment / …）挂载在 `dy-app` 下，**不得**依赖彼此的内部包。

## 五、ADR-05 跨租户语义：403 而非 404

- **裁定**：跨租户访问 → **HTTP 403 + `code=2003 TENANT_MISMATCH`**。
- **理由**：`404` 会诱导客户端做存在性探测推断（"这个 id 不存在"vs"存在但不属于我"可被区分）→ 信息泄漏。`403` 语义上正确：请求者已认证，只是无权。
- **配套**：可见性不足 → `403 / 2001 VISIBILITY_DENIED`；门禁未过 → `403 / 2002 GATE_MISSING` 且**必须回显 `missing_items[]` 缺失项名称**（严禁模糊报错，H-8）。

## 六、ADR-06 统一响应与错误码

- **信封**：`{code, message, data, trace_id}` —— 四字段固定，**不得增删**。
- **错误码分段（以契约 §2.0 全量表为准，本段为更正版）**：
  | 段 | 语义 | 代表码（HTTP） |
  |---|---|---|
  | `1xxx` | 通用 / 认证参数 | 1001 `VALIDATION_FAILED`(400) · 1002 `UNAUTHENTICATED`(401) |
  | `2xxx` | 权限与租户（**403 三态**） | 2001 `VISIBILITY_DENIED` · 2002 `GATE_MISSING` · 2003 `TENANT_MISMATCH` |
  | `3xxx` | 资源 | 3001 `NOT_FOUND`(404) |
  | `4xxx` | 契约 / 并发冲突 | 4001 `VERSION_CONFLICT`(409) · 4002 `IDEMPOTENT_REPLAY`(409) |
  | `5xxx` | **业务规则** | 5001 `BUSINESS_RULE_VIOLATED`(**422**) |
  | `6xxx` | 限流 | 6001 `RATE_LIMITED`(429) |
  | `9xxx` | 系统 | 9001 `INTERNAL_ERROR`(500) |

  > **⚠️ 本表为对 ADR-06 初稿的更正**：初稿把 `5xxx` 误标为"依赖故障"，但契约原文 **5001 = `BUSINESS_RULE_VIOLATED`（HTTP 422，业务规则不满足，如题组量程 ≠ 0–4）**，与"依赖故障"无关。契约表中**不存在"依赖故障"段**。全量以契约文档 `_work/contract-t6-api-freeze-2026-09-19.md` §2.0（L107–119）为准。
- **`message` 与客户端文案解耦**：服务端 message 是**给开发者看的**，绝不直接进客户端 UI（合规红线，竞析 H.1-11）。
- **Base Path**：`/api/v1`。

## 七、ADR-07 字段裁剪：整体移除，而非置 null

- **裁定**：无权限字段在**序列化前**从对象图中移除 → JSON 中**不出现该 key**。
- **否决**：置 `null` / 空串 / 空数组 —— 三者都会让客户端反推字段存在性（客户可反推 A3 → 反推退款判定输入，H-4）。
- **作用域**：**按角色 × 字段组四档**（非按端），以 `A2 /auth/me` 为唯一权威档位下发点（T-5/T-6）。

## 八、ADR-08 配置中心：数据库为真相源

- **裁定**：**DB 表为配置真相源**，政策层只读下发。
- **否决**：外部配置中心（Nacos/Apollo）作为真相源 —— 会破坏"配置-判定"强一致性，制造可回放缺口（竞析 H.3-10）。**外部配置中心仅可作为缓存/推送通道，不可作为真相源。**
- **规模**：**46 条非空配置（#1–#48，含 #42 空号 / #47 预留）**（空号/预留是为了保持既有编号不位移，勿复用）。
- **fail-closed**：未配置即**拒绝**，严禁 default-allow（H-14）。非法组合**保存时校验并拒绝**，不得静默存下（H-13）。
- **代码内零硬编码**：所有业务数字外置；**静态扫描断言代码内无 N 常量**（X-18）。TBD 占位 = 建位不填值。

## 九、ADR-09 可观测性

- **三支柱**：Metrics / Logging / Tracing，统一 `service.name`、`env`、`version` 标签。
- **指标禁止 `tenant_id` 标签**（高基数会打爆时序库，竞析 H.1-13）。租户维度分析走**日志/数仓**，不走 metrics。
- **审计日志与应用日志物理分离**（独立表/独立文件），审计日志 **append-only + 撤销 UPDATE/DELETE 权限 + 哈希链 + 校验端点**（H.1-14/15/16）。
- **Trace 携带 tenant_id** —— 但**必须配套采样控制**（竞析 H.2-7）。
- **逆向要求**：记录对敏感个人信息的**只读访问**也算审计事件（H.1-17）。

## 十、ADR-10 幂等：`Idempotency-Key` 24h TTL

- **请求级**：`Idempotency-Key` 头，24h TTL；重放返回原响应 + `X-Idempotent-Replayed: true`；键冲突（同键不同体）→ `409`；键非法 → `400`。
- **数据级**：`ON CONFLICT` 双分支键（upsert），支撑幂等迁移。

## 十一、ADR-11 溯源与回放

- **三元组强制落库**：`evidence_snapshot`（判定依据）+ `threshold_version`（阈值版本）+ append-only。
- **`threshold_version` 当"数据"管理，不当"配置"管理** —— 不可变、append-only、版本递增、绝不覆盖（竞析 H.1-22）。改规则**不得回溯**已生效判定（Non-goal #7）。

## 十二、ADR-12 合规：构建期扫描进 CI（owner 不可空）

- **扫描面**：小程序包内**不得含「退款」字样** —— 覆盖 i18n 文案表、枚举名、常量、报错文案、**埋点名**、**订阅消息模板**（"页面改了、推送没改等于没改"）。
- **扫描面 2**：客户端**不得出现负向计数/归因措辞**（"缺失 N 天"/"未佩戴"/"未达标"/"数据不全"）。
- **扫描面 3**：派生字段不得下发。
- **命中即构建失败**。owner 字段**不可为空**（T-2）。
- **合规框架**：PIPL（主）+ 等保 2.0 三级。字段级加密用**每主体 DEK + 租户级 KEK 包裹**（才能响应删除权，竞析 H.2-8）；删除权用 crypto-shredding，**但必须配套 DEK 备份纪律**，否则密钥丢失 = 数据永久不可读（H.2-9）。

## 十三、ADR-13 构建与交付

- **JDK 17（Temurin）+ Maven 3.9.9 + Spring Boot 3.3.5**。不用 Gradle。
- **显式 UTF-8**：`<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>` + `MAVEN_OPTS=-Dfile.encoding=UTF-8`。**本机平台编码为 GBK**，不显式声明必然乱码。
- **环境分离**：dev / staging / prod；**非生产只用脱敏或合成数据**（H.1-21）。
- **灾备**：**Pilot Light**，RTO 1~4h、数据库低 RPO；做 ADR 记录并**年度复核**（竞析 H.2-11）。
- **契约优先**：OpenAPI 契约进 CI（Spectral + oasdiff + 生成物漂移检查），三端 SDK 生成、零手改（H.1-9）。

## 十四、本轮明确不做（Non-goals）

微服务 / K8s / 服务网格 / 多区域多活 / schema-per-tenant / database-per-tenant / 每租户专属连接池 / GraphQL / 日期版本化 API / Force.com 式 UDD 内核 / 独立 SIEM / Stripe 式分层可观测 / 外部配置中心作真相源。
（完整 16 条见析客清单 §十 N-1~N-16，**全部生效**。）

---

## 十五、必须裁定才能设计的开口（不裁定则骨架按"预留位"实现）

| # | 开口 | 骨架本轮处置 |
|---|---|---|
| **F-1** | 实体计数口径：PRD §8 说 28（3★+3表），data-dict 说 24，派工单说 27 | **以 PRD §8 为唯一权威 = 28**；规格书须列出待补 `doc_template` 的两处文档 |
| **F-2** | `band_telemetry` 宽表 vs 长表 | **预留位**：骨架不建该表，规格书写两方案对比 + 决策判据 |
| **F-3** | `band_telemetry.device_id` 外键指向不存在的 `band` 实体 | **预留位**：规格书登记，须补"客户级手环实体"或改业务键 |
| **F-4** | 状态机 14 态 vs `customer.status` 5 值 | **预留位**：骨架只在 `customer` 表留 `status` 5 值 + 注释指向 14 态状态机实体（待建） |
| **F-5** | 配置计数：46 条非空（#1–#48，含 #42 空号 / #47 预留，非"44 项"） | **以 46 条非空（#1–#48）为准**；规格书须列出 README/dev-handover 的更正项 |
| **F-8/F-9** | 枚举名契约层 vs 落库层不一致 | 骨架**只建 `dy-common` 的枚举骨架**，不固化具体值；规格书写显式映射表 |
| **H.4-1** | 国密 SM4 是否强制 | **不写入规格书**，标 ⚠️ 待测评机构确认 |
| **H.4-2** | 是否触发 PIPL 跨境传输 | **不写入规格书**，标 ⚠️ 待合规方确认 |
| **H.4-3** | `Idempotency-Key` 是否已成 RFC | 骨架按 IETF 草案语义实现（不依赖 RFC 编号） |

---

> 本文件由主理人方向明裁定，2026-09-19。**架构规格书与可运行骨架均以本文件为基准；任何偏离须回报主理人。**