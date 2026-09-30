# 调元云 多租户 SaaS 骨架工程 (diaoyuanyun-skeleton)

> 可运行的 **Spring Boot 3 多租户骨架**，面向「多门店 SaaS 健康管理系统」。
> 这是给后续开发的**框架地基**，不是业务功能实现。严格遵循架构裁定
> `arch-decisions-lead-2026-09-19.md` 的 ADR-01 ~ ADR-13。

- 技术栈：Spring Boot **3.3.5** · JDK **17 (Temurin)** · Maven **3.9.9** · Java 17
- 架构风格：**Spring Modulith**（模块化单体，非微服务，ADR-03）
- 隔离模型：**Pool**（共享库 + 共享 schema + 全表 `tenant_id` + PostgreSQL RLS，ADR-01/02）

---

## 一、模块清单与依赖方向（单向，严禁反向）

```
dy-app
  ├── dy-security
  ├── dy-config
  ├── dy-audit
  ├── dy-crypto     ← B-1 起挂接（单向：dy-app → dy-crypto；dy-crypto 自身零依赖、零被依赖其下层）
  └── dy-web
        └── dy-tenancy
              └── dy-common
```

> 🛑 **本图此前把 dy-crypto 画在链外，与第 36 行/§5.2 的「B-1 已挂接」自相矛盾**（`dy-app/pom.xml` 实有依赖）
> —— 已修正。**挂接方向是单向的**（dy-app → dy-crypto），故 `ArchitectureBoundaryTest` 的 R1 crypto 层登记不受影响。

| 模块 | artifactId | 职责 | 直接依赖 |
|---|---|---|---|
| dy-common | `dy-common` | 响应信封 `Result<T>`、错误码 `ErrorCode`、业务异常 `BizException`、审计基类 `AuditableEntity`、门禁异常 `GateMissingException` | — |
| dy-tenancy | `dy-tenancy` | `TenantContext`(ThreadLocal)、`TenantContextFilter`、`X-Tenant-Id` 一致性、`RowScope`、**JWT 验签(`JwtVerifier`/`JwtProperties`/`JwtValidationException`)**。<br>🛑 **关于 RLS 会话变量注入**：本模块内的 `RlsSessionAspect` 是**文档机制而非实际机制**（生产 `@RlsScoped` 使用数 = **0** ⇒ 切点零匹配、永不触发）。**实际机制是 18 个载体各自私有的 `inTenant(tenantId, Supplier)`**（17 个 dy-app 域仓储 + `dy-config/JdbcConfigSupport`），在短事务内 `SET LOCAL app.tenant_id`。详见 §三 ADR-02 与 §五 **N-19｜B-9**（已落机械守护 `RlsInjectionRealityGateTest`） | dy-common |
| dy-security | `dy-security` | `@RequirePermission` + 拦截器、`@RequireGate` + 门禁守卫、**字段裁剪**(`FieldMasker`)、权限/门禁注册表 | dy-tenancy |
| dy-web | `dy-web` | `@Idempotent` + 拦截器(内存 24h TTL)、全局异常处理器、trace_id 过滤器 | dy-tenancy |
| dy-audit | `dy-audit` | `AuditLogService`(append-only)、审计日志 DDL（哈希链）。⚠️ **`AuditFillAspect`（审计字段自动填充切面）是【从未接线的死代码】**—— 切点零匹配 / `AuditableEntity` 零子类 / 零测试，`created_by` 自动填充**不生效**（见 §五 N-17 与机械守护 `AuditFillAspectWiringGateTest`） | dy-tenancy |
| dy-config | `dy-config` | `SysConfig` 骨架、`ConfigService`(fail-closed)、`ConfigValidator` | dy-tenancy |
| dy-app | `dy-app` | 启动类、`application.yml`、Flyway `V1__baseline_tenant_rls.sql` + `V2__b_entities_state_machine_and_band.sql` + `V3__band_telemetry_metric_long_table.sql` + `V4__scale_item_bank.sql` + `V5__remaining_entities_org_journey_verdict_refund.sql`（**S1-2：24 张租户表，全量达 29**）+ … + `V15__organization_provisioning.sql`（B-7）… `V22__band_refetch_provisioning.sql`（**A-3，共 22 条迁移**）、拦截器装配、演示控制器、**S1-4 题库模块**（`app/scale/`：`ScaleDomain`/`ScaleScoringProfile`/`ScaleScoringEngine` + `ScaleItemBankService` + `ScaleItemBankRepository` + `ScaleItemBankController`）、**S1-7 手环探测模块**（`app/band/`：`HistoryType`/`BandProbeRequest`/`BandProbeResult` + `BandAvailableDatesService` + `BandAvailableDatesController`）、**A-3 手环历史补拉模块**（`app/bandrefetch/`：`SyncProbeRecord`/`DailyCoverageRecord`/`WearState` + `BandRefetchLedger` + `BandRefetchService`） | dy-security, dy-config, dy-audit, dy-web |
| dy-crypto | `dy-crypto` | PIPL/等保 字段级加密（每主体 DEK + 租户级 KEK 信封 + crypto-shredding），**零第三方依赖** | **dy-app**（B-1 已挂接，见 §5.2） |

> 依赖方向由 Maven 编译作用域**强制**（依赖下层即编译失败），并由 `ArchitectureBoundaryTest`(ArchUnit) 在测试阶段二次守护：违反即构建失败。根 pom 共 **8 个模块**（7 个业务模块 + 1 个独立加密模块 dy-crypto）。

---

## 二、如何构建 / 运行

### 构建（必须 BUILD SUCCESS，含测试）

本机平台编码为 GBK，**必须**显式声明 `MAVEN_OPTS=-Dfile.encoding=UTF-8`，且不要把该参数放在
`clean package` 之前（会被误判为生命周期阶段）。推荐用 PowerShell：

```powershell
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
& "C:\...\apache-maven-3.9.9\bin\mvn.cmd" `
   -f "deliverables\product-strategy\skeleton\pom.xml" `
   -B -ntp clean package
```

等价于验收命令 `mvn clean package -DskipTests=false`（默认即跑测试）。

### 运行（可选，需可达的 PostgreSQL）

```powershell
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
& "C:\...\apache-maven-3.9.9\bin\mvn.cmd" -f "deliverables\product-strategy\skeleton\dy-app" -B -ntp spring-boot:run
```

或运行打出的 fat-jar：`java -jar dy-app/target/dy-app-0.0.1-SNAPSHOT.jar`。

- 默认 `server.port=8080`，API Base Path `/api/v1`（ADR-06）。
- 数据源占位见 `dy-app/src/main/resources/application.yml`（localhost PostgreSQL）。
  **未接真实 PG 时应用启动会失败（Flyway 需 PG 执行 RLS 语法），属预期**；构建不依赖运行。
- 演示端点：`GET /api/v1/demo/me`、`GET /api/v1/demo/customer`(需 `customer:read`)、
  `GET /api/v1/demo/refund`(门禁 `gate:refund_view` + 字段裁剪)、`POST /api/v1/demo/order`(幂等键)。
- **已落地的契约端点（非演示）**：契约域 A —— **A2 `GET /api/v1/auth/me`**（可见性档位唯一权威下发点；不贴权限码）· **A3 `GET /api/v1/stores`**（贴 `store:read`；`page`/`page_size`）；契约域 F —— F1 `POST /api/v1/cycle-assessments/{id}/verdicts` · F2 `GET /api/v1/customers/{id}/verdicts` · `GET /api/v1/verdicts/{id}/replay`（内部自描述）；契约域 G —— G1~G5 退款工单。**两端点各带一个 `.../contract` 自描述子端点**（`GET /auth/me/contract`、`GET /stores/contract`），把"为什么贴/不贴某个注解、响应集是哪些"变成**可断言的自证**。

---

## 三、每个 ADR 落在哪个类/文件

| ADR | 落点 |
|---|---|
| ADR-01 Pool 模型 | `dy-app/V1__baseline_tenant_rls.sql`(`tenant` + `customer` 带 `tenant_id`)、`application.yml` datasource |
| ADR-02 三层纵深防御 | L1 `dy-tenancy/TenantContext`(基类注入) · **L2 = 18 个类的 `inTenant(tenantId, Supplier)` 手册注入**：17 个 dy-app 域仓储（`*Ledger` / `*Repository` / `DbShredTombstoneStore`）+ `dy-config/JdbcConfigSupport`，各自在<b>短事务内</b>`SET LOCAL app.tenant_id = '<uuid>'`（UUID 白名单校验后拼接）再执行业务 SQL · `V1` 中 `ENABLE`+`FORCE ROW LEVEL SECURITY` 与 `NULLIF` fail-closed 策略 · L3 `dy-app/RlsMigrationScriptTest`(租户泄漏测试)。<br>🛑 **`RlsSessionAspect` 是「文档机制」而非「实际机制」（2026-09-27 · 批次十三第六轮实测纠正）**：它的切点是 `@Before("@annotation(...RlsScoped)")`，而全仓 `@RlsScoped` 的<b>生产使用数为 0</b>（唯一出现是它自己的 `@interface` 声明）⇒ 切点零匹配、永不触发、`applyTenantSession` 在生产代码里零调用。**这不是安全漏洞**（18 个载体的显式传参覆盖面完整，且比 ThreadLocal 更难点错），而是**文档与实现的分歧** —— 已落机械守护 `RlsInjectionRealityGateTest`（6 例）并登记为 **N-19｜B-9**（删除死机制 vs 启用切面，属架构 owner 裁定） |
| ADR-03 Modulith | 8 模块划分 + `dy-app/ArchitectureBoundaryTest`(ArchUnit 依赖方向守护) + `dy-app/WebConfig` |
| ADR-04 8 模块 | 上述模块表 |
| ADR-05 403 而非 404 | `dy-web/GlobalExceptionHandler`(2xxx→403)、`dy-tenancy/TenantContextFilter.assertTenantConsistency`(2003)、`GateMissingException` 回显 `missing_items[]`(2002)、**`dy-tenancy/JwtValidationException`(1002→401)**。🛑 **S2-9 补一条更靠前的纪律**：**错误码须由"端点自己契约里声明的 `responses:` 集合"决定，而非 HTTP 常规** —— A2 `/auth/me` 只声明 `200`+`401`、A3 `/stores` 只声明 `200`+`403`，故同一情形"没带身份"两处归因**不同**（A2 ⇒ 401 / A3 ⇒ 403）；且**前置校验的顺序即语义**（`requireCallable` 必须排在 `requireTenant` 之前，否则无 token 会被抢答成 `2003`/403）。详见 §反向验证「S2-9 契约域 A 的注入」 |
| ADR-06 统一响应/错误码 | `dy-common/Result`(四字段)、`dy-common/ErrorCode`(1xxx~9xxx)、`dy-web/GlobalExceptionHandler` |
| ADR-07 字段裁剪 | `dy-security/FieldMasker`(序列化前整体移除，key 不存在而非 null)、`dy-app/DemoController.refund`。**S2-9 补：可见性档位的唯一权威下发点 = A2 `GET /auth/me`** —— `dy-app/identity/domain/BandVisibilityMatrix`（读 `config #43`，客户 ③④ 硬锁不可放开）+ `AuthMeDeclaration`（**`refund_visibility`/`store_scope` 为 null ⇒ 键不出现在响应里，不是 `false`**）+ `StoreScopeResolver`（token 声明比角色默认**更宽即抛**；`all ⇒ store_ids=[]` 是显式决定）。🛑 **`#43`（手环四档）与 `#40`（退款二分）是两份独立配置、不得合并** |
| ADR-08 配置 fail-closed | `dy-config/ConfigService`(`getRequired` 未命中即抛)、`ConfigServiceImpl`、`ConfigValidator`/`DefaultConfigValidator`(保存时校验拒绝) |
| ADR-09 可观测/审计 | `AuditLogService`(append-only)、`audit_log.sql`(哈希链+撤销 UPDATE/DELETE)、`dy-web/TraceIdFilter`(MDC/trace_id)。⚠️ **`dy-audit/AuditFillAspect` 当前是【从未接线的死代码】**（`created_by` 自动填充**不生效**：切点零匹配 / `AuditableEntity` 零子类 / 零测试）—— 本条此前的写法会误导读者以为它已生效，详见 §五 **N-17**（已落机械守护 `AuditFillAspectWiringGateTest`，删除 vs 启用属架构 owner 裁定） |
| ADR-10 幂等 | `dy-web/IdempotencyInterceptor` + `IdempotencyStore`(24h TTL，生产换 Redis)、`V1` 中 `schema_migration`(ON CONFLICT 双分支键) |
| ADR-11 溯源回放 | `audit_log` 哈希链字段(`prev_hash`/`hash`)；**`threshold_version` 内容寻址指纹 + 回放闭环（S2-8 已实现）**：`dy-app/derived/domain/ThresholdVersionFingerprint`（= `"tv1-" + sha256(九段规范化口径串)[0..20)`）、`dy-app/derived/service/ThresholdVersionReplay`（四态回放：`ReplayOutcome`/`ReplayResult` 值对象住 `derived/domain/`）、`VerdictController.replayVerdict`（`GET /verdicts/{id}/replay`，内部自描述端点） |
| ADR-12 合规扫描 | **已实现**：`compliance/scan_compliance.py`（三扫描面，命中即**构建失败**）+ 绑定 Maven `validate` 阶段 + `.github/workflows/compliance-gate.yml`；owner 不可空纪律由 `compliance/owners.csv` + 断言守护。详见 `compliance/README.md` |
| ADR-12b 客户端零派生结构门禁（**S1-6**） | **已实现**：`compliance/client-zero-derived-gate.py`（四面：禁名 / 白名单⊆契约 / 契约禁入路径 / 门禁自检），命中即**构建失败**；绑定 Maven `validate`（`id=s16-zero-derived-gate`）+ CI 两个新步骤；证人 `compliance/tests/zero_derived_gate_test.py`（**13 断言**）。**它查的是"主张"而非"词汇"**——ADR-12 问"违禁词进没进包"，本门禁问"包自己的权限清单是否许可了一件被契约禁止的事"。禁字段集**从 `contract/visibility/band-visibility-matrix.json` 机械推导**（不设第二张手工清单）。详见 `compliance/README.md` §S1-6 |
| ADR-12c 契约一致性门禁（**S1-8**） | **已实现**：`compliance/contract-conformance-gate.py`（五面：引注属实 / 错误码属实 / 枚举命名空间隔离 / 客户端不可见字段名不进包 / 可见性矩阵字段表与契约一致），命中即**构建失败**；绑定 Maven `validate`（`id=s18-contract-conformance`）+ CI 三步（含 **PyYAML 显式安装**）；证人 `compliance/tests/contract_conformance_gate_test.py`（**18 断言**）。**它查的是"事实"而非"主张"**——前两条门禁的判据本身是契约事实的**手工重述**，本门禁把每一条引注/错误码/枚举值/字段名**逐条比对契约真相源**。用**真 YAML 解析器**（两个手工正则探针在同一文件上先后给出 42 与 2，自相矛盾的探针不能做门禁基础；缺 PyYAML 即 **exit 2**，不降级为正则）。详见 `compliance/README.md` §S1-8 |
| ADR-13 构建与交付 | 根 `pom.xml` 显式 `project.build.sourceEncoding=UTF-8`、JDK17、Maven 3.9.9、Spring Boot 3.3.5；环境分离 dev/staging/prod |

---

## 四、测试清单（验收硬标准）

**当前状态：1199 个测试，0 失败 0 错误，`BUILD SUCCESS`**（2026-09-30 · 批次二十一 · A-3 手环历史补拉通路 + V22 反向验证后）。

> 🛑 **本节标题行此前连续滞后四批**（批次十三第七轮 / 十四 / 十五 / 十六 只往下加变更块、没回改首行）。
> 本轮把首行**一次性对齐到实测值**，并附**权威口径**如下 —— 自此后首行 = 实测值，不得只加块不回改。
> 🛑🛑 **但本条纪律在批次二十（A-4）又复发了一次，本轮由精确对账逼出（值得逐字记住）**：
> 批次十九 A-1 之后首行写 **1185**；**批次二十 A-4 把 `RlsCoverageGateTest` 从 4 例加到 6 例（+2）
> ⇒ 当时现值已是 1187，但首行没改**，而批次二十一 A-3 的记录又**基于这个滞后的 1185 去推算**
> ⇒ 一度写成「1185 → 1186（+1）」。**那是错的。** 精确对账（见下"增量分解"）给出的真值是 **1199**。
> ⚠️ **教训**：**"上一批的首行"不能当作本批的基线** —— 因为它可能本身就是滞后的；
> 基线必须来自**本批实测**（`surefire-reports` 逐 execution 汇总），或至少用**增量逐项对账**反证它自洽。
> 🛑 **我自己的两处错也一并记下**：① 曾用 `grep -c "@Test"` 数用例 ⇒ 把 **`@TestMethodOrder`** 也数进去
> ⇒ 得出 `BandRefetchGateTest` "13 例"（**实测 12**）；② 曾写"③-0 断言使 dy-app +1" —— ③-0 是**判据③ 内部的
> 一条断言**（`assertAudit(...)`），**不新增 `@Test`、计数不变**。**"我加了断言" ≠ "计数会动"。**
>
> **权威口径（本轮实测，可复算）**：1199 = 全部 8 模块 `target/surefire-reports/TEST-*.xml` 的 `tests` 属性之和。
> 按模块（含真库门禁 execution）：dy-common **10** · dy-tenancy **39** · dy-security **48** · dy-web **58** ·
> dy-audit **37** · dy-crypto **29** · dy-config **37**（= 默认 **8** + `config-truth-source-gate` **29**）·
> dy-app **941**（= 默认 **831** + `rls-isolation-gate` **110**）⇒ **合计 1199**。
> ⚠️ 与`mvn`日志逐 execution 汇总（10/39/48/58/37/8/29/29/831/110）**逐项对账一致**（日志把 dy-config 的两个
> execution 分列成 8 与 29、dy-app 分列成 831 与 110，本表已合并为模块口径）。
> 📌 **增量分解（逐项对账，可复算）**：
> - **批次十九 A-1**：**1185**（默认 819 + gate 108）
> - **批次二十 A-4**：**+2** ⇒ **1187**（`RlsCoverageGateTest` **4 → 6** 例；`dy-app 默认 819 → 821`）
> - **批次二十一 A-3**：**+12** ⇒ **1199**（新增 `BandRefetchGateTest` **12 例**；`dy-app 默认 821 → 831`）；
>   🛑 **其余 7 模块与 `rls-isolation-gate` 的载体集合**在本批**均未变**（gate 108 → 110 的 +2 属**批次二十 A-4**，
>   不是本批）—— 这**正是"用增量反证基线"的价值**：若沿用滞后的 1185，本批会被记成 +12 以外的错值。
>
> 🆕 **本轮收尾（2026-09-30，精确对账阶段）又抓出两条本仓长期缺陷，均已实测坐实并修**：
> - **第 48 条（第六类新缺陷）：CI 命令从未成功执行过。** `rls-isolation-gate.yml` step 1 的
>   `mvn -pl dy-app -am test -Dtest='Rls*Test' -DfailIfNoTests=false` **在本仓结构上不可能通过** ——
>   `dy-config/pom.xml` 的 `config-truth-source-gate` 把 `<failIfNoTests>true</failIfNoTests>` **写死在 pom**，
>   plugin 配置值优先于同名 CLI 属性 ⇒ `-am` 连带 dy-config 时**任何** `-Dtest` 筛选都必红（实测三种写法全 exit≠0）。
>   正解 = **不带筛选**（`mvn -o -B -ntp -pl dy-app -am test`，实测 9 模块全 SUCCESS）。三处（workflow / README / `docs/CI-ENABLEMENT.md`）已同改。
>   🛑 **通用教训：凡写进 CI 的命令，必须在写下的那一刻就在本机跑通一次。**
> - **载体数漂移**：`LEDGER_CARRIERS` 实测 **25** 条（dy-app 24 + dy-config 1），而代码注释与文档长期写 23（更早写 18）。
>   已改 4 处，并把易漂移的硬编码数字统一改成"**以登记表为准**"的表述。
>
> **测试计数口径**：各模块 surefire 报告 `TEST-*.xml` 的 `tests` 属性之和（含 `@Nested` 分组各自成文件），
> 与 `mvn -o test` 日志中每个 execution 的 `Tests run` 汇总**逐项对账一致**（见下方按模块拆分）。
> ⚠️ 注意 `dy-app` 与 `dy-config` 各有 **两个** surefire execution（默认 + 真库门禁），
> 只数默认 execution 会系统性少算。
> 其中含真库门禁（dy-app 的 RLS 隔离、dy-config 的配置真相源 IT），它们在无库环境下走
> `-Ddy.rls.gate.skip=true` / `-Ddy.config.gate.skip=true` 逃生阀时会被记为 skipped 而非常见绿——
>  本数字为**本机含真库环境下的实测值**，非估读。
>
> **2026-09-27 变更（批次十三 · B-2b 补完 + N-17 死代码门禁 + 4 处登记滞后修正）**：**1099 → 1106**（**+7**）。
> **① B-2b：把 B-2 立论补完整（实测抓出的门禁牙齿不全）** —— 批次十二的 B-2 立论是
> "配置错误应在**启动阶段**暴露，而不是运行期静默降级"，但两道防线只覆盖了
> `prod+memory ⇒ 拒启动` 与 `prod 段默认 redis`，**漏了另一半**：**prod + redis，但 Redis 连不上**。
> 🛑 **失效机理（实测确认，非推测）**：`spring-boot-starter-data-redis` 的 `LettuceConnectionFactory`
> **惰性建连**（本仓 `dy-web/pom.xml` 注释逐字写着 "afterPropertiesSet 阶段不建连接，故障只发生在真正使用的那一刻"）
> ⇒ prod 配了 redis 而 Redis 主机写错 / 未启动 / 网络不通时，**应用照常启动成功**，
> 直到**第一个带 `Idempotency-Key` 的写请求**才全线 5xx。即"配置错误被推迟到业务已在跑的时刻"。
> **收口**：新增 `IdempotencyConfiguration.verify_redis_is_reachable_at_startup_if_production(...)`
> —— **prod 下启动阶段真发一次只读 `HASKEY` 探活**，不可达即 `IllegalStateException` 拒启动；
> **非 prod 不探活**（本地联调无 Redis 属正常，不得误伤）。
> 新增 **3 例**（`IdempotencyBackendFailureTest` 11 → 14：不可达必拒启动 / 可达正常启动 / 非 prod 不探活）。
> 反向验证 `verification/111_b2b_redis_startup_probe_reverse_verification.py`（**2 组注入 / 3/3 PASS**）：
> C1 掏空守卫条件（"自检根本没跑"）、C2 吞掉探活异常（fail-closed 退化成 fail-open）—— **两个失效模式各注入一次**。
> **② N-17：实测抓出一对【从未接线的死代码】（`AuditFillAspect`）** —— README 第 88 行把它列为
> ADR-09 落地物，实测三处实锤证明它从未生效：**切点零匹配**（全库无 `save(..)`/`persist(..)` 方法定义）·
> **载体零实现**（`AuditableEntity` 零子类）· **零测试**。新建 `AuditFillAspectWiringGateTest`（**4 例**）把
> "接线状态"变成构建期可断言的事实（真接线即红、须显式改登记）。反向验证 `verification/112_*.py`
> **3 组注入 / 4/4 PASS**。🛑 **该脚本顺带抓出门禁自身的假绿缺陷**（`contains("@Aspect")` 把注释也算命中 ⇒
> 注释掉注解后门禁仍绿），已修为"先剥注释再断言"—— **反向验证不只验"门禁有没有牙齿"，
> 还会验"牙齿咬的是不是真东西"**。
> **③ 4 处登记滞后修正（实测抓出，代码已收口而文档仍记未完成）** —— 盘点"还差什么"时逐条实测代码，
> 发现 §五 有 4 条 `[ ]` 已不成立：**C-3**（`RefundWritePathMatrixE2ETest` 16 例已落地）、
> **C-2**（CI 门禁 `rls-isolation-gate.yml` + `015_apply.sql` 双通道已落地）、
> **N-14**（`#21` 已接线）、**消费面 16/30 → 18/28**。⚠️ **教训**：登记册是人写的、必然滞后 ——
> 盘点"还差什么"必须**逐条实测代码**，不能只读 §五 的 `[ ]`（牙口风险是**双向**的：
> 门禁可能"没牙齿"假绿，登记册也可能"以为没做其实做了"假红）。
> **④ 口径澄清（避免误报）** —— 契约 `openapi-v1.0.0.yaml` 实测 **40 个 path** =
> **45 个 HTTP 方法（operationId）**；本 README 多处所称"45 端点"用的是 **operationId 口径**，
> 与 path 口径**不矛盾**（5 个 path 各承载 2 个方法）。
>
> **2026-09-27 变更（批次十六 · V18/V19 反向验证收敛 + 门禁载体角色缺陷修复）**：**→ 1162**（本轮含 `clean` 重跑，
> 计数一次性对齐到实测；批次十五后未再新增测试类，故增量主要来自**口径回补**而非新增用例）。
> **本轮的产出不是"更多测试"，而是【把已交付的两条迁移的自证变成可被证明有牙齿的东西】**，并顺带抓出
> **第 4 条真实缺陷 —— 这次是【门禁自己的载体】上出的错**。
> **① 反向验证 119 / 120 落地并全绿**：`verification/119_device_provisioning_reverse_verification.py`
> （**22/22 PASS**，V18 `register_device`）· `verification/120_scale_provisioning_reverse_verification.py`
> （**28/28 PASS**，V19 `register_scale`）；两份 `.md` 留档（含用例矩阵、C6 净效果等价性、checksum 处置账、可迁移教训）。
> 🛑 **两脚本当场抓出并修掉 3 条"自证判据从未生效"型缺陷**（都属于**假绿**，比假红危险得多）：
> **(a) 判据被注释满足** —— PG 把 `AS $tag$ … $tag$` 之间内容**原样**存进 `pg_proc.prosrc`，**注释也在里面**
> ⇒ 任何"函数体里出现过 X"形态的判据都能被**注释**满足。处置=**剥注释 + 锚点并用**（逐行
> `regexp_replace(ln, '--.*$', '')` 得代码态 + `INSERT INTO x\M` / `UPDATE x\M` 起锚、`[^;]*` 不跨语句）。
> **(b) 判据无锚点 ⇒ 被同函数的另一条语句满足**（(b5)/(b7)/(c7) 形态）；**(c) V18 缺 (a0)/(a1) 能力守卫**
> ⇒ 在 `BYPASSRLS` 角色下 4 条 RLS 判据**全部失去效力却仍然打印"通过"**。
> **② 🛑 门禁载体的【角色前提】缺陷（本轮最有价值的一条，由全量回归抓出）**：
> `dy-app/.../rls/RlsGateSupport.java` 的 `provisionRealDatabase()` **第 2 步用 `SUPER_USER` 应用整条迁移链**
> ⇒ 门禁**断言**用非超级用户跑，**预置**却用超级用户跑。当被测物第一次断言**执行者角色**（V18 新增 `(a0)`）时，
> 全量回归立刻报红：`V18 自证失败(a0): 当前角色 postgres 拥有 BYPASSRLS …本自证【依赖 RLS 的 4 条判据全部失去效力】`。
> ⇒ **门禁是对的**。修法：`CREATE DATABASE :dbname OWNER :rolename;`（库 owner 改为非超级用户应用角色）
> + 第 2 步改用 `APP_USER` 应用迁移链。🛑 **不得放宽 (a0)**——放宽即把"角色前提不成立"重新变回静默假绿。
> **③ 🛑 探针 I/O 的 Windows 换行污染（新失效模式，已修 7 处）**：
> `tempfile.NamedTemporaryFile("w", encoding="utf-8")` 在 Windows 上**默认把 `\n` 译成 `\r\n`**
> ⇒ 喂给 psql 的文本 ≠ 磁盘原文 ⇒ 重建库 `pg_proc.prosrc` 变 `\r\r\n` ⇒ **一切"逐字比对"报出完全归因错误的红**。
> 修法：`newline=""`。**教训：逐字比对报"不同"时，先证明"你把同一份字节喂进去了"**（117 因用 `\i 磁盘原文` 未中招）。
> **验证**：`117` **6/6** · `118` **17/17** · `119` **22/22** · `120` **28/28** ·
> `RlsRejectionAuditTrailGateTest` 定向 **23/23** · **全量反应堆 `BUILD SUCCESS`（1162 例，0 失败 0 错误）**；
> 真库 `diaoyuanyun_dev` 的 Flyway checksum **三方对齐**（V17 **587148978** / V18 **-1932910031** / V19 **-1572623912**，均 `success=t`）。
> 🛑 **V18 checksum 的处置纪律（本轮实证一遍）**：117 ⑤ 精准抓到"**文本改了但库没处置**"（库 `-1553162480` ≠
> 文本 `-1932910031`）。处置**不得直接改数字** —— 先用 `_work/_probe_v18_effect_reconcile.py` 证明
> **"新旧文本 DDL 净效果相同"**（四项：签名 / `prosrc` 原文 / EXECUTE 权限 / `schema_migration.description`）
> 才对齐。**最坏的反应是把 checksum 改成新值完事** —— 那是用一行 `UPDATE` 掩盖一个未经验证的断言。
>
> **2026-09-28 变更（批次十八 · G-B 的 B-1：三端前端工程骨架）**：**Java 测试计数不变（仍 1173）**
> —— 本轮产出是**前端工程 + 契约驱动端点层 + 一处真实漏项修复**，不新增 Java 测试。
> **① 三端工程骨架落地（`frontends/`）**：端 A 管理后台 / 端 B 调理师 APP / 端 C 客户小程序，
> 各自**构建可跑 + 目录约定 + 环境配置分离**（详见 `frontends/README.md`）。
> **② 契约驱动端点层 —— 端点清单是【生成】的，不是手抄的**：`frontends/tools/gen-endpoints.py`
> 从 `_cut/<端>.openapi.yaml` 按 `generator-matrix.yaml` 的 token-roles 机械转录，并带 **`--check`**
> （契约改了而产物没重跑 ⇒ 红）。三端可用 operation 实测：**client-mp 15** / **therapist-app 29** /
> **admin-web 39**。🛑 立论依据是本仓已有的一次同型失败（`clientPaths.js` 文件头：手写白名单
> 8 条里只有 2 条与契约相符）。
> **③ 🛑 实测抓出一条真实功能缺口（第 45 条）：客户端白名单漏了 B5。** 主契约
> `x-callable-roles` 含 `client` 的 path 共 **14** 个，而 `client-package/api/clientPaths.js`
> 只有 **13** 条 —— 缺 `/customers/{id}/intake-profile`（B5 建档扩展档案）。
> 后端**已实现**该端点（`CustomerController` + `package-info.java` 逐字记
> `B5 GET /customers/{id}/intake-profile [client, therapist, meridian, admin]`），
> 而 `assertPathAllowed` 是**运行时强制** ⇒ 客户小程序调 B5 会被直接拒绝，**功能不可用**。
> **它为什么不红**：S1-6 FACE 2 与 S1-8 FACE 1 **双向都只做 ⊆**（先看"引注/条目为真"），
> 不检查"契约允许但没列上"。S1-6 会**报告** `permitted_but_undeclared`（修复前 = 1）但**判为
> INFORMATION 而非缺陷** —— 这是**有理由的刻意取舍**（源码注释逐字：equality 检查曾试过并否决，
> 因为"对正确内容叫狼的门禁会被关掉"），故本轮**不改判定**，只补漏项 + 在此登记
> ⇒ ⚠️ **这类漏项不会变红，须靠人核 `permitted_undeclared` 输出**。
> **④ 端 C 的禁用词纪律在【真实工程】上复验**：ADR-12 三面（scan1/2/3）的 roots 是
> `client-package`，真实小程序工程不在其内 ⇒ 端 C 构建主动复用同一批词表自检
> （剥离注释后扫描 9 个文件，**三面 0 命中**）。把真实工程纳入扫描根是 G-B 的 **B-3**。
> **⑤ 🛑 与 §六 Non-goals「前端」的冲突已如实登记**：§六 清单逐字列有「前端」，
> 而规划 §三 G-B 标「✅ 可以 / 可立即动工」（后裁定覆盖先裁定）⇒ 按后者动工，
> 并在 §六 补"准确含义"块**待追认**，未写成"已裁定"。
> **⑥ 环境限制如实记录（含一处自我订正）**：初版把环境写成"**禁止 node 派生子进程**"，
> **那是过宽的错判**。实测精确结论是：**只有同步派生被拦**（`execFileSync` / `spawnSync`
> 对任何命令都 `EBUSY`），**异步 `spawn` 正常**。订正后：**三端自检全部 `exit=0`**，
> 且端 A / 端 B 的**真实构建（`tsc --noEmit` + `vite build`）在本机跑通**
> （端 A `31 modules transformed`，端 B 同）。仍存在的真实限制只有一条：
> `npm install` 需 `--ignore-scripts`（esbuild 的 postinstall 用同步派生）；
> 平台包 `@esbuild/win32-x64` 自带 `esbuild.exe`，故跳过脚本后构建仍可用。
> 🛑 **教训（值得记住）**：**"环境受限"必须给出精确的受限面** —— 一句"禁止 spawn"
> 就把本可验证的契约一致性变成了"未验证"，**形同一种少查**，与本仓"不把未验证
> 伪装为通过"的纪律**反向冲突**：把可做的检查误判为做不了，同样是不合格。
> **⑦ 新增 S1-9「SDK 表面门禁」（B-2）：三端生成产物此前是【无人检查的产物】。**
> `compliance/sdk-surface-gate.py` 把契约回归从"客户端包"扩到 `contract/sdk/<端>`：
> FACE 2 **生成面不得越权**（客户包里出现它调不了的路径 ⇒ 违规，这是能力泄漏方向）；
> FACE 3 **作用域是否被覆盖**按既有 ⊆ 取舍记为 INFORMATION，但**逐条打印、计数进 audit**
> （不打印的子集检查与没检查无从区分）；FACE 4 **排除声明必须咬合**（B-4 判据）。
> **实测**：三端生成面 `operations_generated=83` = `operations_in_scope=83`，**缺失 0、越权 0** ——
> 即三份 SDK 与本端角色作用域**逐条一致**。证人 `tests/sdk_surface_gate_test.py` **7/7**
> （含 X5：删一个 method 字面量 ⇒ 路径数≠方法数 ⇒ 必须 exit 2，**不许 zip 截断后仍报 PASS**）。
> **⑧ B-4 收口**：`therapist-app` 的 `exclude-contract-rows: [H1]` 为空声明已删除 ⇒ **dead 声明 = 0**；
> 其余 43 行按 `role-disjoint` / `mirror-of-client-forbidden` / `dead-declaration` **逐行登记理由**
> 并由门禁每次打印（定义与逐端数字见 §五同名条目）。同时门禁已挂进
> `.github/workflows/compliance-gate.yml`（含证人步骤）。
> **⑨ B-3 完成：真实小程序工程已纳入扫描根。** `scan-manifest.json` 三面的 roots
> 由 `client-package` 一个增至**两个**（加 `frontends/client-mp/miniprogram`），
> ADR-12 三面复跑 `roots=2 hits=0` 全绿；`client-zero-derived-gate.py` 的扫描根
> 同步改为**双根**（`files_scanned` 由 8 → **17**，即真实工程的 9 个文件真的进来了）。
> 🛑 **这里当场抓出一个设计漏洞**：只要求"列出的根都存在"是不够的 —— 有人把第二个根
> **从列表里删掉**，剩下那个根确实存在，门禁照样 PASS，扫描面却**悄悄少一半**。
> 故新增 **`EXPECTED_CLIENT_ROOTS = 2`（根数量冻结）**，根数漂移即 exit 2；
> 并由 S1-6 证人新增的 **W10** 双向钉住（正向：`files_scanned` 必须大于样例包文件数；
> 反向：删掉一个根必须 exit 2 且不得打印 PASS）。W10 首跑**即为红**，正是它抓出了这个漏洞。
>
> **2026-09-29 变更（批次十九 · A-1 协议书离线签署写入方通路 + V21 反向验证）**：**1173 → 1185**（**+12**：新增
> `AgreementGateTest` **12 例**；余 7 模块与 `rls-isolation-gate` 载体集合未变）。
> **A-1 `agreement`（调理协议书离线签署写入方）收口** —— 这是本仓**第四条**「不建表、只建 PL/pgSQL 原语」
> 的迁移（V20 之后），也是**第六条边界移动**（第五张零写入方表进入已开通账）。
> 🛑 **它同时解掉一条被卡住的 PRD 取数指标**：A-1 之前 `agreement` 的缺口表现**不是**"少一条链路"，
> 而是 **PRD G1「协议签署合规率 = 100%」的分子恒为 0** —— 即该指标在数学上不可计算
> （分母可能非空、分子必然为 0）。这不是"0% 合规率"，而是**指标口径不成立**。
> **① V21 `V21__agreement_offline_signing_provisioning.sql` 落地并应用真库**：两个原语
> `register_agreement(...)`（写入 + 幂等 + 门禁 + plan_version 门禁 + 模板指针成对）/ `latest_agreement_of(...)`
> （读侧按 `signed_at` **全序**取最新）+ 领域层四件套（`AgreementRecord` / `AgreementOutcome` /
> `AgreementLedger` / `AgreementService`）+ 第 2 节授权 + 第 3 节登记。
> **② 🛑 合规红线（A-1 独有，硬边界必须逐字登记）**：本批**只交付"离线签署通路"**（运维通路形态）；
> 契约 **H1 `receiveEsignCallback`** 被电子签厂商冻结条款**逐字禁止**由本批实现 ——
> **实现它 = 开一条"未验签回调即可自证已签"的通道**。故 `agreement` 包内**不得出现任何 HTTP 注解**
> （由 122 的 **C12** 守着）。⇒ **G1 在厂商冻结前只能算"离线签署覆盖率"，不能算"电子签合规率"**，
> 这条边界已写进 `verification/123_g1_agreement_compliance.sql` 的口径说明。
> **③ 跨租户撞号的后果【比 case_archive 更危险】（V21 与前四批的实质差别）**：`agreement` 的
> `ON CONFLICT` 推断目标是**单列主键** `agreement_id`（不含租户维度）⇒ 跨租户撞号**必然发生**；
> 而 agreement 的跨租户撞号**有即时下游消费者** —— 它会把一个客户推进 `customer` 的
> `PLAN_APPROVED → AGREEMENT_SIGNED` 跃迁（`CustomerGateGuard`），于是"客户已进入已签状态，而协议不存在"。
> （case_archive 是链条**末端**，撞号没有第二步能证伪；agreement 是链条**中段**，错状态会被下游消费。）
> ⇒ 函数**必须**自己 `RAISE`（消息含"另一租户"），这是本域**不可让渡的红线**。
> **④ plan_version 门禁（V21 独有）**：`agreement` 有复合外键 `(plan_id, plan_version) → plan(plan_id, version)`
> ⇒「方案存在、但那**一版**不存在」必须被判为**不存在**。缺了它，一份写着 `plan_version=99` 的协议会绕过
> 函数层检查，然后以一条 **23503** 撞复合外键（**归因质量退化**：函数层给可归因消息，外键只给 23503）。
> 🛑 更细的一处：`plan_version=0` 必须以**业务错误**被拒，而**不是** 23514（表 CHECK
> `ck_agreement_plan_version_positive`）—— 两者都"拒绝"，但归因完全不同。
> **⑤ 🆕 反向验证 `verification/122_agreement_reverse_verification.py`（49/49 PASS）** —— 一次运行
> 抓出 **4 处真实缺陷**，其中 **3 处是静默假绿**（自证每次都打印"通过"而判据从未工作过）：
> **(a) (b5)/(b6) 判据的【载体选错】—— 被同一函数体末尾那条跨租户 RAISE 的【消息字面量】满足**：
> 那条给运维看的解释文本逐字写着 `INSERT ... ON CONFLICT (agreement_id) DO NOTHING`，而旧载体是
> `substring(v_reg_body ... for 3000)`（**固定长度窗口、无终点语义**）。实测：真正的 INSERT 在 2206 字符处
> 结束，3000 的窗口把那条 RAISE 消息**包进了判据的载体** ⇒ 把真 INSERT 改成
> `ON CONFLICT (tenant_id, agreement_id)`（**错误**改法）时自证**照常"通过"**（跨租户 RAISE 成死代码）。
> 处置=**语句切片改用语义终点**（`split_part(..., ';', 1)`，不再是拍出来的 3000）。
> **(b) (c1) 被【同一函数体的注释】满足**：`latest_agreement_of` 的注释里逐字写着
> `` `tenant_id = p_tenant_id` ``，而旧载体是 `v_lat_body`（**prosrc 原文，含注释**）⇒
> 删掉真正的 `WHERE tenant_id = p_tenant_id` 后自证**照常"通过"**。处置=载体切到**剥注释后的代码态**。
> **(c) 自证成功时【不打印任何标记】**：V20 有 `RAISE NOTICE 'V20 自证通过: …'`，V21 初版没有 ⇒
> ① 122 的 C1 判据 `ok and "V21 自证通过" in out` **恒假** ⇒ 报一条**归因错误**的红（"自证没通过"），
> 而真相是"通过了但没说话"；② "跑过了"与"根本没跑"**同形**；③ 运维不可判。处置=新增 **(g) 成功标记**。
> **(d) V21 缺 (d3)（授权落地断言）** —— 由 122 的 **C8** 抓出：V18/V19/V20 都有 (d3)，V21 漏了；
> 而迁移由**应用角色自己执行** ⇒ owner **隐含 EXECUTE** ⇒ (d2) **恒真** ⇒ 叠加无 (d3) 等于
> **授权落地无人验证**。已补 (d3)（`proacl` 必须非 NULL 且含显式 `EXECUTE` 项）。
> **⑥ 🛑🛑 同时抓出一条【本仓第 45 条系统性缺陷】—— PostgreSQL 正则里 `\b` 是「退格符」而非词边界。**
> 实测逐字：`'a'||chr(8)||'b' ~ 'a\bb'` = **true**；`'agreement ' ~ 'agreement\b'` = **false**；
> `'\m…\M'` 与 `'\y…\y'` 正确。⇒ V21 初版把 **(b8)/(b10)/(b11) 共 6 处「否定判据」写成 `…\b`**
> ⇒ **恒不命中** ⇒ 那三条判据**从未生效**（而"否定判据恒不命中"与"实现正确"**在输出上完全同形**）。
> 🛑 纪律漂移的形态：V17/V18/V19/V20 用的都是 `\M`，**只有 V21 误写 `\b`**。
> 处置=6 处 `\b`→`\M`，并把这条教训写进 122 的元门禁。
> **⑦ 元门禁 `C7`/`C7b`/`C9`/`C10`/`C12`（把历批教训升格为每次运行都成立的机械断言）** ——
> 自证块内**不得**有直接作用在 `prosrc` 原文上的正则判据（C7 常驻守卫，本轮**实测两处复发**）；
> 代码态载体必须**从彼此派生**（C9，防"两个不同的量"）；迁移文本里**不得出现自己的美元引用标签字面量**
> （C10，含注释里 —— PG 词法在识别注释**之前**先扫标签 ⇒ 注释里的标签会提前终止 DO 块、整条迁移语法错误）；
> `agreement` 包内不得有 HTTP 注解（C12，H1 硬边界）。
> **⑧ 回归修复（本轮顺带抓出并修掉两条）**：
> **(i) domain 层反向依赖 service 层**（违反 R4）：`AgreementRecord` / `AgreementSnapshot` 反向依赖
> `doctpl/service/DocFileService.sha256` ⇒ `ArchitectureBoundaryTest` R4 报红。处置=**下沉到
> `dy-common/crypto/Hashes`**（依赖方向合法 + SHA-256 口径全仓唯一 + `DocFileService` 改为一行委托）；
> **(ii) `AgreementLedger` 新增 inTenant 载体未登记** ⇒ `RlsInjectionRealityGateTest` 报红（**门禁是对的**）
> ⇒ 已登记进 `LEDGER_CARRIERS`（第 23 → 22 个 dy-app 域仓储口径随之更新）。
> **验证**：`AgreementGateTest` **12/12** · `ProvisioningBoundaryGateTest` **10/10**（两账显式改账后）·
> `RlsInjectionRealityGateTest` **6/6** · `RlsCoverageGateTest` **4/4** · `122` **49/49** ·
> `121` **45/45** · `120` **28/28** · `119` **22/22** · `118` **17/17** · `117` **6/6** ·
> **全量反应堆 `BUILD SUCCESS`（1185 例，0 失败 0 错误 0 跳过）**。
> **两张账本随之显式改账（边界移动必须是显式动作）** —— 两账**现值（实测）**：
> ⚠️ **本行是 A-1（批次十九）时点的快照（`40 / 2`）** —— **现值见批次二十一块**：
> `PROVISIONED` **42**（+ A-3 两张补拉表）、`NOT_PROVISIONED` **0**（**已归零**）；
> 全集 = **42 张**迁移表，两账互斥、无第三个。
> 历史锚点：`35→36`（B-10 `band`）· `36→37`（B-11 `device`）· `37→38`（B-12 `scale`）·
> `38→39`（B-13 `case_archive`）· `39→40`（A-1 `agreement`）· **`40→42`（A-3 补拉两表，批次二十一）**。
> **⑤ 判据（A-1 验收五项，全部达成）**：① 两账显式改账 ✅；② 新门禁测试 **12 例**（≥9）全绿 ✅；
> ③ 反向验证 **49 用例**（≥20）全绿 ✅；④ 全量回归 `BUILD SUCCESS` ✅；
> ⑤ G1 取数 SQL 能返回非空 ✅ —— `verification/123_g1_agreement_compliance.sql` **端到端实测**：
> 真走 `register_agreement` → `CREATED` → 分母 **1** / 分子 **1** / 合规率 **100.00%** →
> `latest_agreement_of` 返回协议 id。
> **真库收口**：V21 apply `EXIT=0`（含 `注意: V21 自证通过: …`）；flyway V21 checksum 回写 **360963725**；
> `proacl` 两函数非 NULL（(d3) 成立）。
> **仍未裁（属产品/架构范围裁定，不代拍）**：**H1 电子签回调通路**仍待厂商冻结。
> ✅ **原"`band_sync_probe` / `band_daily_coverage` 补拉通路仍未落码"已由 A-3（V22，批次二十一）收口**
> —— 且其 ⚠️ 前置「上游补拉口径须先核对」的核对结论是：**口径已定**（PRD §2.8.7，v1.25，业务方 2026-09-19 拍板），
> 此前"未定"系**只检索 README 转述层、未回到 PRD 正文**的误判。
>
> **2026-09-30 变更（批次二十一 · A-3 手环补拉通路 + V22 反向验证）**：**1187 → 1199**（**+12** = 新增 `BandRefetchGateTest` **12 例**）。
> ⚠️ **基线是 1187（不是 1185）** —— 1185 是批次十九 A-1 的值，**批次二十 A-4 的 +2 未回改首行**（见本节首行块的更正记录）。
> 🛑 **③-0 断言不产生增量**：它是判据③ 内部的一条断言（`assertAudit(...)`），**不新增 `@Test`、计数不变** ——
> 此前写成"③-0 使 dy-app +1"是错的。
> **A-3 `band_sync_probe` / `band_daily_coverage`（手环历史补拉写入方通路）收口** —— 本仓**第四条**
> 「不建表、只建 PL/pgSQL 原语」的迁移（V19 / V20 / V21 之后），也是**第七次边界移动**，
> 且是**第一次把「未开通」账打到零**的一批。
> **① V22 `V22__band_refetch_provisioning.sql` 落地并应用真库**：两个原语
> `register_sync_probe(...)`（探测留痕；`ON CONFLICT (probe_id) DO NOTHING` —— **追加，证据不可改写**）/
> `register_daily_coverage(...)`（逐日覆盖；`ON CONFLICT (device_id, date) DO UPDATE … WHERE c.tenant_id = p_tenant_id AND c.customer_id = p_customer_id RETURNING (xmax = 0)` —— **upsert，观测事实可刷新**）
> + 领域层四件套 + 第 2 节授权 + 第 3 节登记。🛑 **V22 不建表、不改表**
> （`CREATE TABLE` **0 次** / `ALTER TABLE` **0 次**；两张表在 V5 第 899 / 990 行已建且带 `ENABLE + FORCE RLS`），
> **只新增两个函数** ⇒ 对已应用库的处置是**纯增量**（无 checksum 冲突面）。
> **② 🛑 两原语的幂等形态【刻意不同】—— 这是 A-3 的核心业务判断，不是实现细节**：
> **探针 = 追加、不可改写**（一条"某次探测发生过"的记录被后来的写入抹掉，**就等于抹掉证据**）；
> **逐日覆盖 = upsert、可刷新**（"当天的覆盖率 / N 值"是**随时间被重算的观测值**，用旧值覆盖新值才是错的）。
> ⇒ 判据③ 因此要求两条通路**各自的**两态完备性，且**第二次必须真的刷新**（不是"没报错"就算）。
> **③ Core gate（§2.8.7⑤）的【第二个落点】**：`not_worn` 与 `is_wear IN (-1,255)`（**技术性缺失**）**不得共存**；
> `(-1,255)` 一律**不判行为性**。落点两处：`DailyCoverageRecord` 构造器（应用层，抛 `GATE_MISSING(2002,403)`）
> + V22 `(5c)`（**库层**，`RAISE P0001`）。另 (5b) 校验 `is_wear` 允许集恰为 `0, 1, -1, 255`、(5d) 校验
> `coverage_flag=true` × `gap_reason` 非空不得共存。🛑 **为什么库层要再落一次**：函数可被人绕过（直接写 SQL），
> 但**归因质量只能来自函数层**（P0001 + 理由 vs 23514 无理由）—— 这与第 42 条同一条纪律。
> **④ 🛑🛑 本轮抓出并登记【第 47 条系统性缺陷】：`audit_log.target_id` 必须指向库中【真实存在】的 id** ——
> 覆盖态下"本次传入的 `coverage_id` ≠ 库里那一行的 `coverage_id`"（V22 (7) 刻意把 `coverage_id` 列进"不更新的列"），
> 而初版把 `target_id` 写成 `record.coverageId()` ⇒ **按 coverageId 反查"这一天的审计证据"永远落空**，
> 且 `BAND_COVERAGE_REFRESHED` 计数被**摊薄**（而它是 §2.8.7③「节流」效果的**唯一**可观测面）。
> 🛑 **它够格被登记为"系统性缺陷"的理由**：两个 id **都是合法 UUID，没有任何一步会报错** ——
> 审计行写成功了、payload 里两个 id 都在、日志也都打了；**唯一暴露方式是「按 `target_id` 去表里找那一行」**。
> 修法 = **读一次库层权威值**（`BandRefetchLedger#readStoredCoverageId`），**不动库层签名**（改签名属迁移变更，已登记待裁）。
> **⑤ 🛑 反向验证顺带抓出一处【真实判据缺口】并当场修复**：`ACTION_COVERAGE_REGISTERED` 在 `BandRefetchGateTest` 里
> **只有定义（常量）、从未被任何断言使用** ⇒ "首次覆盖记 `REGISTERED` 而非 `REFRESHED`"这条性质**无判据守**。
> 已在判据③ 补 **③-0** 断言（`assertAudit(r1.auditId(), ACTION_COVERAGE_REGISTERED, COV_IDEM, "\"mode\":\"CREATED\"")`）。
> 🛑 **它【不】改变测试计数** —— ③-0 是**判据③ 内部的一条断言**，不新增 `@Test`；本批 **+12 全部来自新增的
> `BandRefetchGateTest` 12 例**。（此前把 ③-0 记成"dy-app +1"是错的。）
> **⑥ 反向验证 `verification/125_band_refetch_reverse_verification.py`（23 组注入 / 25 用例）** ——
> 三层注入面：**判据语义 8 组**（C1/C4/C5/C10/C11/C14/C15/C22）· **判别力 10 组**（C2/C3/C8/C9/C12/C13/C16/C16b/C20/C21）·
> **门禁实现与审计载荷 5 组**（C6/C7/C17/C18/C19）。其中 **13 组是「活库注入」**：
> 🛑 **必须改活库函数而非迁移文件** —— 改迁移文件会让启动期 Flyway checksum 校验失败、Spring 上下文起不来，
> **exit ≠ 0 但归因完全错误**（本仓 118 已实测付费）。故设**两重安全网**：
> ① 基线时核对活库函数与 V22 源码**逐字等价**（**双函数** + `BASELINE_ANCHORS` 锚点集）；
> ② 每组跑完**立即还原活库并读回 `prosrc` 断言**；③ `--restore-only` 同时还原文件与活库。
> 🛑 **首轮 10/11 FAIL，唯一未抓住的 C6 属"注入等价于原样"**（V5 的 `IN (` 之后**紧跟换行 + 缩进** ⇒
> 两个不同窗口位置抽出的字面量集合**完全相同** ⇒ 注入未改变任何行为 ⇒ **报出假失败**）；
> 修法（照 124 纪律，**不改判据只改注入**）= 把窗口右界从"配对右括号"放宽到"整份文件"。
> 🛑 **C11 首版则是"语法错但归因错误"**：把 `DO UPDATE` 改成 `DO NOTHING` ⇒ 紧随的 `SET …` 子句**悬空** ⇒
> 函数建不起来 ⇒ exit≠0 **但红的是"函数没建起来"**；修法 = 把 upsert 的 **`WHERE` 子句整体恒假**（`WHERE FALSE`）。
> 🛑 **脚本自身还修掉一处安全缺陷（实测付费一次）**：`restore_all()` 无条件用磁盘快照还原 ⇒ 任何一次**离线预检**的
> `import` 都会在 `atexit` 用**上一次运行的过期快照**覆盖源文件 ⇒ **静默擦掉本轮新补的 ③-0 断言**；
> 修法 = `restore_all(use_disk=False)`，磁盘兜底快照**只服务 `--restore-only`**。
> **⑦ 两账显式改账（边界移动必须是显式动作）**：`PROVISIONED` **40 → 42**（+ 两张补拉表）、
> `NOT_PROVISIONED` **2 → 0**；🛑 **"未开通"账的守门形态随之改变** —— 从"清单非空"变成
> **`NOT_PROVISIONED = new LinkedHashSet<>()` + 一条归零自证断言**
> （`assertEquals(ZERO_EXPECTED_DIFF, NOT_PROVISIONED.size(), "🛑🛑 「未开通」账必须为空（A-3 收口后已归零）…")`）。
> **归零本身不构成判据**（空集合天然"没人能违反"）；**真正有牙齿的是"一旦有人把表塞回去就立即红"**。
> **⑧ 🛑 上游"补拉口径"的核对是【两次结论，第二次推翻第一次】**（这一条值得单独记住）：
> 第一次结论「未定 ⇒ 只能登记待裁」是**误判**，原因是**只在本 README 里检索「补拉」二字（仅 2 处转述性提及），
> 没有回到 PRD 正文**；第二次核对回到真相源 —— **口径已定**：`prd-health-mgmt-saas-2026-09-16.md:470-536`
> **§2.8.7（v1.25 新增，业务方 2026-09-19 拍板）**。🛑 **教训**：**"在某处搜不到" ≠ "不存在"** ——
> 检索一个**转述层**得到的"未定"，与回到**真相源**核对得到的"已定"，可以完全相反（与本仓第 45 条同族）。
> 逐字锚点与核对结论落在 `_work/a3-band-refetch-ruling-request.md`（10 KB，可复算）。
> **⑨ 🛑 但核对顺带发现一条【真实待裁项】，不得用"已收口"一笔带过**：
> `band_daily_coverage.gap_reason` 的 **7 值枚举与 §2.8.7⑤ 的三分表【对不齐】** ——
> §2.8.7⑤ 的第「① 客户未同意佩戴（**结构性**）」类别，在现行 7 值
> （与 `contract/openapi-v1.0.0.yaml:1781-1786` **逐字一致**）里**没有任何槽位** ⇒ 而 config `#44` 逐字要求
> 「A3 的**结构性不可观测占比**须按采集模式**单列，不得混合平均**」⇒ **该硬约束在当前 schema 下无法实现**。
> 🛑 **增删该枚举属【契约变更】** ⇒ 按本仓纪律**登记待裁、不代拍**。V22 的处置是**严格守边界**：
> **不新增枚举值**，只在逻辑自洽层面断言，并在函数自证消息里**显式打出这条已知缺口**
> （逐字：`'⚠️ 已知缺口（登记待裁）：§2.8.7⑤ 的第「① 客户未同意佩戴（结构性）」在现行 7 值里【没有槽位】⇒ 该类的"结构性不可观测占比"目前无法单列。'`）。
> **⑩ 仍未裁（不阻塞落码，但阻塞"对外承诺"）**：`N` 的准确值（未实测前按保守假设 `≤7 天`，**不得对外承诺具体天数**）·
> `s` 实测值（**不得写成"补拉可让 A3 达标"**）· **A6「无数据语义」**（未解前**超窗口无数据一律按技术性缺失，不得用于行为性判定**）·
> 厂商 13 条逐日接口 + 游标（文档自证、待真机复核）· `isWear` 异常原因码（须向厂商索要）。
> 一律做成**配置槽位 + fail-closed 保守默认值**，由启动期自检打印当前取值；按 G-D 纪律**登记催办，不代拍**。
> **⑪ 验证汇总**：`BandRefetchGateTest` **12/12** · `verification/125` **23 组 / 25 用例**（23 组注入 + **复绿** + 逐字节还原；还原面 = **10 个源文件 + 两个活库函数**）·
> `ProvisioningBoundaryGateTest` **10/10**（两账 **42 / 0** 显式改账后）· **全量反应堆 `BUILD SUCCESS`（1199 例，0 失败 0 错误 0 跳过）**。
> **真库收口（实测逐字）**：V22 apply `EXIT=0`（含 `注意: V22 自证通过: …`）；flyway **V22 checksum = 1496059203**（`success=t`；
> V21 仍为 **360963725**）；两函数 `proacl` 非 NULL（授权段生效）；两张表 `relrowsecurity = t` 且 `relforcerowsecurity = t`
> （**V22 未破坏 FORCE RLS**）。
> 🛑 **本批的通用教训（值得记住）**：**"注入跑绿了" ≠ "注入改了行为"** —— 反向验证的**注入本身**也需要一个额外证据：
> **它必须真的改变被测对象的行为**（等价改写会报出**假失败**，进而诱使你把一条**正确的判据**改坏）。
> C6 与 C11 是同一条规则的两个不同方向（一个"改了但没变"、一个"变了但归因错"）。
>
> **2026-09-27 变更（批次十七 · B-13 结案归档通路 + V20 反向验证）**：**1162 → 1173**（**+11**：新增
> `CaseArchiveGateTest` **11 例**；余 7 模块与 `rls-isolation-gate` 载体集合未变）。
> **A-2 `case_archive`（结案归档写入方通路）收口** —— 这是本仓**第三条**「不建表、只建 PL/pgSQL 原语」
> 的迁移（V19 之后），也是**第五条边界移动**（第四张零写入方表进入已开通账）。
> **① V20 `V20__case_archive_provisioning.sql` 落地并应用真库**：两个原语
> `register_case_archive(...)`（写入 + 幂等 + 门禁）/ `latest_archive_of(...)`（读侧全序取最新）+
> 领域层 5 类（`CaseArchiveLedger` / `CaseArchiveService` / …）+ 第 2 节授权 + 第 3 节登记。
> **P0-14 强制归档**落地：**5 项硬门禁**（`reason_recorded` / `baseline_review_compared` /
> `retention_recorded` / `owner_signed` / `archive_plan_exec_archived`）+ **1 项警告门禁**
> （`handband_recorded_as_reference`）。
> **② 🛑 合规红线（V20 独有，第一个把门禁拆成两个数组的原语）**：PRD **P0-25** 与 README §5.3
> 「三条不得触碰」③ 逐字要求「**任何"手环缺项反转为阻断"的写法一律违规**」⇒ 静态防线 **(b12)**（手环键
> **不得**进硬门禁数组）/ **(b12c)**（**必须**在警告数组里），行为防线 **(e5-a)**（键**缺席** ⇒ 必须成功）/
> **(e5-b)**（值 **false** ⇒ 必须成功）。🛑 这条单独立用例的理由：**"把它并进硬门禁"看起来是"更安全"的
> 改动** —— 它有极大的动机被做出，而它恰好违规。
> **③ 跨租户撞号的后果【更隐蔽】（V20 与前四批的实质差别）**：`case_archive_pkey` 与 `device` 同型
> （**单列** `archive_id`，`ON CONFLICT` 推断目标**不含租户维度**）⇒ 跨租户撞号**必然发生**；
> 而归档是业务链条的**末端** —— 一旦被静默合并成 `ALREADY_EXISTS`，调用方会把工单标记为已归档，
> 而**归档档案并不存在**，下游没有任何第二步能证伪它。⇒ 函数**必须**自己 `RAISE`
> （消息含"另一租户"），这是本域**不可让渡的红线**。
> **④ 🆕 反向验证 `verification/121_case_archive_reverse_verification.py`（45/45 PASS）** —— 一次运行
> 抓出 **4 处真实缺陷**，其中 **3 处是静默假绿**（自证每次都打印"通过"而判据从未工作过）：
> **(a) (b6) 判据被函数体内 RAISE 消息的【字面量】满足**（那条给运维看的解释文本逐字写着
> `INSERT ... ON CONFLICT (archive_id) DO NOTHING`）⇒ **双面失效**：把真 INSERT 改成
> `(tenant_id, archive_id)`（**错误**改法）时自证**照常"通过"**（跨租户 RAISE 成死代码）；改成
> `ON CONFLICT ON CONSTRAINT case_archive_pkey`（**等价正确**改法）时**报假红**。处置=**带锚点**
> （`INSERT INTO case_archive\M` 起锚、`[^;]*` 为界）+ **接受两种等价形态** + 新增 **(b6-负向)**
> （锚点段内不得出现含 `tenant_id` 的推断目标）。
> **(b) (e3) 只断言"抛了异常" ⇒ 被库层复合外键（V16 的成果）掩盖**：把函数里 (4) 的客户存在性检查
> 改成 `IF false THEN` ⇒ INSERT 落到 `case_archive_customer_id_fkey` ⇒ 收到 **23503** ⇒ 内层捕获
> ⇒ 自证**照常"通过"**。⚠️ 拒绝**仍然发生**，只是**理由完全不同**（23503 vs P0001），后果天差地别：
> 函数层给**可归因**消息、外键只给一条 23503；且**函数可被绕过（直接写 SQL）**，归因质量只能来自函数层。
> 处置=断言 **`SQLSTATE = P0001` 且消息含"客户"**（同款补强到 **(e4)/(e6)/(e10)/(e11)**）。
> **(c) (e2) 幂等重放传入与首次【完全相同】的内容 ⇒ 对 `DO UPDATE SET ...` 毫无反应**：把
> `DO NOTHING` 改成 `DO UPDATE SET metrics_trend = excluded.metrics_trend`（**"用新参数改写既有证据"**）
> 时，因为 UPDATE 写回的正是同样的值 ⇒ 自证**照常"通过"**。处置=**对抗性重放**（第二次传**不同**内容：
> 换签名人 / 日期 / 结论 / 趋势 / 脱敏授权 / `created_by`，6 键清单保持逐字相同以隔离变量）
> + **13 特征列全匹配**断言。
> **(d) (b8) 判据把语义等价的谓词书写判成错（假红）**：`WHERE p_archive_id = archive_id` 报 (b8)。
> 处置=接受两种等价谓词书写（断言"按 `archive_id` 等值匹配"这个**语义**，而非某一种语法写法）。
> **⑤ 元门禁 `C9`/`C9b`（把"剥注释"教训升格为每次运行都成立的机械断言）** —— 不得直接在
> `v_*_body`（含注释）上做正则匹配，必须作用在**剥注释后的代码态** `v_*_code`；`C9b` 是它的
> **反假通过**配对（防"变量不存在 ⇒ 检查了个空"）。
> **⑥ 改前文本的"身份"是【机械可证】的（优于 120 的手写还原）**：改前那一份直接存为基线文件
> `verification/baselines/V20__case_archive_provisioning.before-121.sql`，其复算 checksum
> **`2041181838` == 真库 `flyway_schema_history` 中 version=20 的原登记值** ⇒「它就是改前那一份」
> 不再依赖手工还原，而由 checksum 相等机械证明。
> **⑦ 迁移文本改了 ⇒ 显式处置已应用的库（本仓第二条硬约束）**：处置为 **"净效果相同 ⇒ 对齐 checksum"**，
> 依据是**三重独立证据**：`C8a` 证明两份文本的 V20 **DDL 净效果逐字相同**（函数签名 / `prosrc` /
> `proacl` / `schema_migration.description` 四类）· `117` **6/6**（第 ⑤ 条"已应用库 checksum 与当前文本
> 逐条一致"刷新后为一致）· 本轮全部编辑都落在 `$v20_guard$`（第 4 节自证）= **纯自证块、零 DDL**。
> ⇒ `UPDATE flyway_schema_history SET checksum = 1763579409 WHERE version = '20'`（**`2041181838` → `1763579409`**）。
> 🛑 **顺序是刻意的：先由 `C8a` 证明"净效果相同"，才允许对齐** —— 比"直接把 checksum 改成新值"多一道机械证据。
> **验证**：`CaseArchiveGateTest` **11/11** · `ProvisioningBoundaryGateTest` **10/10**（两账显式改账后）·
> `RlsInjectionRealityGateTest` **6/6** · `121` **45/45** · `120` **28/28** · `119` **22/22** · `118` **17/17** ·
> `117` **6/6** · **全量反应堆 `BUILD SUCCESS`（1173 例，0 失败 0 错误 0 跳过）**。
> **两张账本随之显式改账（边界移动必须是显式动作）** —— 🛑 **本轮先把前几批遗留的【登记滞后】补齐，再记本轮移动**：
> ① **登记滞后回补（实测抓出，非推测）**：`ProvisioningBoundaryGateTest` 的代码注释里已逐字记着
> **第三/四/五次边界移动**（`device` ← B-11 / `scale` ← B-12 / `case_archive` ← B-13），
> 但**本 README 的变更块从未记录第三、四次**（`device` / `scale` 进入 `PROVISIONED` 账这件事
> 只活在代码注释里）⇒ 本轮一并回填，避免"账本在代码里、文档里看不见"。
> ② 两账**现值（实测）**：`PROVISIONED` **39**（含本轮新增 `case_archive`）、
> `NOT_PROVISIONED` **3**（余 `band_sync_probe` / `band_daily_coverage` / `agreement`）；
> 全集 = **42 张**迁移表，两账互斥、无第三个。历史锚点：`35→36`（B-10 加 `band`，批次十五）·
> `36→37`（B-11 加 `device`）· `37→38`（B-12 加 `scale`）· **`38→39`（B-13 加 `case_archive`，本轮）**。
> ③ `RlsInjectionRealityGateTest.LEDGER_CARRIERS` 登记 `CaseArchiveLedger`（新增载体未登记 ⇒ 门禁报红，
> **门禁是对的** —— 这条同批次十五）。
> **仍未裁（属产品/架构范围裁定，不代拍）**：`agreement`（协议签署快照）**零写入方**是一处
> **批次十七的历史快照**（已由 A-1 解除）；`band_sync_probe` / `band_daily_coverage` 的**补拉通路**
> 亦为**历史快照**（已由 A-3 收口）。**本行两处均已作废，见下方 ⚠️ 与批次十九 / 二十一 块。**
> ⚠️ **本行的"`agreement` 仍零写入方"是批次十七的【历史快照】，勿再引用为现值** ——
> `agreement` 已由 **A-1**（V21，批次十九）补上离线签署写入方并移入已开通账；
> 另两张补拉表（`band_sync_probe` / `band_daily_coverage`）已由 **A-3**（V22，**批次二十一**）补齐补拉通路；
> **现值 `PROVISIONED` 42 / `NOT_PROVISIONED` 0**（**「未开通」账已归零**），见批次十九 / 二十一 块。
>
> **2026-09-27 变更（批次十三 · 第六轮：RLS 注入机制「文档机制 vs 实际机制」门禁）**：**1112 → 1118**（**+6**）。
> **⑥ 实测抓出一条【文档写了一条机制、代码走的是另一条】的分歧（并纠正上一轮的模糊数字）。**
> README §三 的 ADR-02 逐字把 L2 记为 `dy-tenancy/RlsSessionAspect`（`SET LOCAL`）。读这句话的人会合理地
> 推断"给方法加 `@RlsScoped` 就有人兜住 RLS 上下文"。**实测三条**（口径：剥离注释与字符串后扫 8 模块 `src/main`）：
> ① **`@RlsScoped` 的生产使用数 = 0** —— 唯一一处出现是它自己的定义文件（`@interface RlsScoped` 是**声明**，不是使用）。
>    ⇒ 切点 `@Before("@annotation(...RlsScoped)")` **零匹配、永不触发**，`applyTenantSession` 在生产代码里**零调用**；
> ② **真实机制是 18 个类各自私有的 `<T> T inTenant(String tenantId, Supplier<T> body)`** ——
>    17 个 dy-app 域仓储（`*Ledger` / `StoreRepository` / `ScaleItemBankRepository` / `DbShredTombstoneStore`）
>    + `dy-config/JdbcConfigSupport`；各自在**短事务内** `SET LOCAL app.tenant_id = '<uuid>'`（**19 处** `SET LOCAL` 语句，
>    18 个载体各 1 + 切面内 4 行含告警消息；`inTenant` 调用点 **139 处**）；
> ③ 两条机制**不是覆盖关系而是替代关系** —— 覆盖面无缺口，但文档描述的是一条**不被走的**路。
> 🛑 **这不是安全漏洞**：18 个载体的**显式传参**覆盖面完整，且比依赖 ThreadLocal 的切面**更难被误用**
> （不接 `tenantId` 参数就写不出租户查询）。**危害是两条**：**(a) 会误导下一个人做错事** ——
> 照 README 给新仓储方法加 `@RlsScoped` 以为"上下文有人管了"，于是在事务外或不走 `inTenant` 直接查，
> RLS 策略读到空 `app.tenant_id` ⇒ **查询静默返回零行**（fail-closed 的形态是"看起来正常的空结果"，不是报错）；
> **(b) 会静默漂移** —— 两侧单独变更都不会红（删掉一个 `inTenant`、或在别处新增 `@RlsScoped`，构建都照绿）。
> **收口**：新建 `RlsInjectionRealityGateTest`（**6 例**）把这条分歧变成**构建期事实**
> （判据①使用数恒 0 双粒度核对 · ②空壳入口即红 · ③载体集合与登记表双向一致 · ④切面方法零外部调用 ·
> ⑤**两级剥离粒度判别力自证** · ⑥文档机制的两个类必须存在，使删除需显式动作）。
> **反向验证**：`verification/114_rls_injection_reality_reverse_verification.py`（**6 组注入 / 8/8 PASS**）。
> 🛑 **该脚本当场抓出门禁自身的两个真实缺陷**（与 112 同源 —— 反向验证不只验"有没有牙齿"，还验"牙齿咬的是不是真东西"）：
> **C1** 注解正则 `@RlsScoped\b` **只认短名** ⇒ 全限定名 `@com...RlsScoped` 的真实启用被静默放过；
> **C2** 用 `contains("SET LOCAL app.tenant_id")` 是**前缀匹配** ⇒ `app.tenant_id_probe = ...` 被判成合格注入点。
> 二者已修为词法完整正则（点分隔标识符链 / 变量名后必须跟 `=`），并把**这四条判别力断言**固化进门禁判据⑤。
> **登记为 N-19｜B-9（属架构 owner 裁定，不代拍）**：**删除死机制**（删 `RlsScoped` + 切面，同步改 ADR-02）
> vs **真正启用切面**（改造所有 Ledger 写路径，是架构级改动，且与"Ledger + JdbcTemplate 直写"范式冲突）。
> **待裁**：① 两条机制保留哪一条？② 若保留切面，是否要求它成为**必由之路**（而非可选标注）？
> ③ 在裁定前，`RlsSessionAspectTest`（5 例）**只直接调 `applyTenantSession()`、无一例断言"切点真的匹配"** ——
> 这条"接线状态"缺口是否应补一例？<br>
> ⚠️ **同轮口径纠正**：上一轮记忆里"19 个 `*Ledger` / 142 处调用"是**未剥离注释的粗测**，本轮实测纠正为
> **18 个载体类 / 139 处 `inTenant` 调用 / 19 处 `SET LOCAL` 语句**。
>
> **2026-09-27 变更（批次十三 · 第七轮：B-7 收口 —— 组织开通通路落地）**：**1112 → 1121**（**+9**）。
> **B-7【已落地】—— 上面第五轮如实登记的那条边界被【显式移动】了，移动方式与理由逐条记录。**
> 🛑 **形态裁定（这是本轮最关键的一条决策）**：组织开通 = **运维通路，不是 HTTP 端点**。
> 契约 40 个 path 里**依然零开通端点** —— 这不是"还没做"，而是**契约化决策**：
> 开通是运维/实施动作；给它一个对外端点，等于在租户边界之外开一个「谁能创建租户」的鉴权面，
> 而 `x-callable-roles` 里**没有任何角色声明覆盖它**（该端点会落在现有权限模型的覆盖范围之外）。
> 加这样一个端点属契约 **MAJOR** 变更，需产品共签。故落地形态 = **数据库函数原语 + 无 HTTP 映射的组件**。
> **交付物**：
> ① **V15 迁移**（`V15__organization_provisioning.sql`）—— 两个 plpgsql 原语：
> `assert_tenant_context()`（fail-closed 守卫，缺上下文即 `RAISE`）+
> `provision_tenant(p_id, p_name, p_datastore_hint)`（单条 `INSERT ... ON CONFLICT DO NOTHING`
> + `GET DIAGNOSTICS ROW_COUNT` 判 `CREATED`/`ALREADY_EXISTS`，随后 `set_config('app.tenant_id', …, true)` 并自证）；
> ② `OrgProvisioningPlan`（领域计划 + 构造期校验 + **跨层 id 查重**）·
> `OrganizationProvisioningRepository`（**本仓首个写组织主数据的生产代码**，单事务内
> 建上下文 → region/store/staff → 最后回填督导）·
> `OrganizationProvisioningService`（**同事务写哈希链审计**，审计失败则开通失败）。
> **两张门禁随之显式改账（边界移动必须是显式动作）**：
> `ProvisioningBoundaryGateTest` 已开通 **31 → 35**（新增 `tenant`/`region`/`store`/`staff`）、
> 未开通 **11 → 7**（余 `device`/`band`/`band_sync_probe`/`band_daily_coverage`/`agreement`/`case_archive`/`scale`），
> ⚠️ **本行为批次十三第五轮的【历史快照】，勿再引用为现值** ——
> 🛑 **现值见批次二十一块**：`PROVISIONED` **42** / `NOT_PROVISIONED` **0**（**已归零**）；
> 见批次十九 / 二十一 块与 §五 N-18｜B-7。
> 并**新增判据⑦**（开通通路必须存在、且两类**不得带任何 HTTP 映射注解**，附正样本自证）；
> `RlsInjectionRealityGateTest` 载体 **18 → 19**（新增 `OrganizationProvisioningRepository` ——
> 它与其余 18 个的**性质差别**是：它**创建**其余 18 个所依赖的上下文）。
> **验证**：`ProvisioningBoundaryGateTest` **7/7** 绿 · `RlsInjectionRealityGateTest` **6/6** 绿 ·
> `OrganizationProvisioningE2ETest` **8/8** 绿（真库真表：开通 → 上下文自证 → RLS 互锁四段 →
> 幂等重放 → 审计哈希链 → 八条拒绝路径 → 判别力自证）·
> **全量回归 `BUILD SUCCESS`**（dy-app **768** 例，Flyway `validated 15 migrations` / `Current version: 15`）。
> V15 已在 **`diaoyuanyun_dev`**（角色 `diaoyuanyun`）与 **RLS 门禁库 `diaoyuanyun_rls_test_app`**
> （角色 `dy_app_rls`）**两个环境**应用并登记（两处 `schema_migration` 均有 V15 行、`pg_proc` 两函数在位）。
> 🛑 **本轮抓出并修掉的两个真缺陷（都属于"看起来成功了"这一类，值得逐字留档）**：
> **(a) 同事务预插 tenant 行 ⇒ 恒判 `ALREADY_EXISTS`** —— 应用侧初版在调 `provision_tenant()` **之前**
> 显式插了一行 tenant（理由是"给静态门禁一个 Java 侧载体"）。同一事务内该函数体内的
> `ON CONFLICT` **看得到刚插入的行** ⇒ `ROW_COUNT=0` ⇒ **全新租户也返回 `ALREADY_EXISTS`**。
> 它的表象**只是返回值不对**（组织树其实建成了），故计数/外键/RLS 断言全绿，只有那个字符串错。
> **且那条"可断言性"理由本身也被实测证伪**：门禁的 SQL 扫描器 `stripSqlComments` **保留美元引用块
> （`$tag$ … $tag$`）内的原文**（因为 V6 触发器函数体里就写着 `INSERT INTO app_config_history`），
> 故 V15 函数体内的 `INSERT INTO tenant` **本来就在扫描视野内**（实测命中 1，删掉应用侧那行不会让门禁变红）。
> **(b) 审计回执 id 取错来源 ⇒ 对账必定对不上** —— `AuditLogService.append()` 的契约明确写
> "**id 由实现生成而非调用方提供**"，而服务层把**自编的** `UUID.randomUUID()` 当作回执 `auditId` 返回。
> 它不会让开通失败、不会让任何计数断言变红，唯一破口是"**拿回执去对账会对不上**" ——
> 而对账恰恰是回执存在的唯一理由。修法：取 `append()` 的返回值。
> 🛑 **本项第三条教训（测试自身的缺陷类型）**：E2E 初版用
> `ORDER BY created_at DESC LIMIT 1` 取审计行，在"**幂等重放也写审计**"的口径下会取到**重放那条**
> （`stores:0`），于是断言失败而失败原因与实现无关；更危险的是这会**诱导人放宽断言**
> （改成含 `requestedStores`）从而把它变成永真断言。修法：用回执的 `auditId` **按 id 精确定位**。
> 同时**显式裁定**"幂等重放是否写审计" = **都写**（理由：`ON CONFLICT DO NOTHING` 不报错也不改数据，
> 若不留痕，"有人拿一份不同的计划去打一个已存在的租户"这件事**完全不可见**；
> 边界一句话：**幂等保证的是"不重复建"，不是"不重复记录"** —— 前者保护数据，后者保护证据）。
>
> **2026-09-27 变更（批次十三 · 第五轮：组织主数据开通边界门禁）**：**1106 → 1112**（**+6**）。
> **⑤ 实测抓出一条【只是从未被写成事实的边界】—— 本骨架没有任何「建租户/建门店/建员工」的通路。**
> 四条**互相独立**的实测收敛到同一点：
> ① 契约 **40 个 path** 里**零**开通端点，`/stores` 只有 `GET`（A3 门店列表，只读）；
> ② 表×写入方全量盘点：**42 张迁移表**中，`tenant`/`region`/`store`/`staff`/`device` 五张组织
> 主数据表在生产代码里**零 `INSERT INTO`**（另有 `agreement` 亦零写入方 ⇒ 直接影响 PRD **G1**
> 「协议签署合规率 = 100%」的取数）；
> ⚠️ **本条的"五张零写入方"是批次十三第五轮的【历史快照】，勿再引用为现值** —— 现值：这五张里
> `tenant`/`region`/`store`/`staff` 已由 B-7 补齐、`device` 已由 B-11 补齐；**42 张中现只剩 2 张**
> 零写入方（`band_sync_probe` / `band_daily_coverage`），见批次十九块
> （`agreement` 已由 **A-1** 于批次十九补齐）。
> ③ 全仓**无** `ApplicationRunner`/`CommandLineRunner` ⇒ 不存在"启动时自举默认租户"的隐式通路；
> ④ 应用库 `diaoyuanyun_dev` 实测这五张表**均为 0 行**。
> ⇒ **把本骨架部署起来，它是个「能跑但空」的系统** —— 登录后拿不到任何门店、员工、客户。
> 🛑 **这不是缺陷，是如实登记的已知边界**（`DbTenantKekProvider#currentOrProvision` 的 Javadoc
> 已逐字写过"本项目当前**没有租户开通流程**…没有任何 `ApplicationRunner`/`CommandLineRunner` 启动钩子"）。
> 但它只活在**注释的散文**里，后果有两条：**会被误报成缺陷**（真库冒烟看到"客户表是空的"，
> 合理地怀疑数据层坏了）· **会被静默打破**（将来顺手加一条 `INSERT INTO store` 即移动边界而无人察觉）。
> **收口**：新建 `ProvisioningBoundaryGateTest`（**6 例**），把这句散文变成**构建期事实** ——
> ① 全集（机械读迁移、剥 SQL 注释）= 已开通(31) ∪ 未开通(11)，两账互斥且不得有第三个；
> ② 已开通的表代码里**必须真的有**写入方；③ 未开通的表代码里**必须真的没有**（归零是显式动作）；
> ④ 契约 40 path 无开通端点且 `/stores` 段内只有 `GET`；⑤ **元层自证**（用已知的"注释-only 引用"
> 样本证明 `stripComments` 真的在剥 —— 若不剥，`tenant` 会被误判成"已开通"）；
> ⑥ **前提守卫**：classpath 上**无 ORM**（本仓持久化 100% 走 `JdbcTemplate` + 静态 SQL 字符串，
> 故"表名出现在字面量里"是完整判据；一旦引入 ORM，门禁先红）。
> 反向验证 `verification/113_provisioning_boundary_reverse_verification.py`（**5 组注入 / 6/6 PASS**）：
> C1 加建店写入方（**边界被移动**）· C2 `/stores` 的 `get` 改 `post`（**只读列表变可写**）·
> C3 契约插入 `/tenants` 写入端点（**契约被扩张**）· C4 pom 注入 ORM 标记（**判据前提被推翻**）·
> C5 删迁移建表语句（**账本与代码脱节 ⇒ phantom**）—— **加了要红、删了也要红、前提变了更要红**。
> 🛑 **本项两条教训**：**(a)** C4 的红**发生在 `validate` 阶段而非 `test` 阶段**（父 pom 的合规门禁
> 绑在 `<phase>validate</phase>`）⇒ 判"门禁有牙齿"时 `exit≠0` 必须与"期望方法名出现在输出里"
> **同时成立**，缺后者只能证明"构建失败了"。**(b)** 「断言空集 == 空集」型门禁有**双向**退化风险：
> 假绿（扫描面为空 ⇒ 平凡通过，由 ⑤/⑥ 防）与假红（登记与代码脱节 ⇒ 红得没信息量，由 C5 防）
> ⇒ **只注入一侧不足以证明这类门禁可长期依赖**。
>
> **2026-09-27 变更（批次十四 · V16 跨租户引用完整性）**：**1121 → 1128**（**+7**：新增
> `RlsCrossTenantReferenceGateTest` **7 例**；`RlsV7IntakeProfileRevisionIsolationTest` 例数不变
> —— 2 例**翻转** + 1 处借用修正。配套反向验证 116（**9/9**）与模式对账 117（**6/6**）另计）。
> **⑤ 实测抓出一条【此前从未被登记过的结构性缺口】—— RLS 保护"行归谁"，但从不保护"引用指向谁"。**
> 策略 `tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid` 只管本行归属；
> 而**外键检查在 PG 内以表所有者身份执行、绕过 RLS**（PG 文档逐字：*referential integrity checks
> always bypass row security*）⇒ 一行 `tenant_id=B` 的数据可以合法地指向**租户 A** 的客户/门店。
> **实测**（真库、应用角色 `diaoyuanyun`、非超级用户）：B 上下文里 `INSERT INTO band(tenant_id=B,
> customer_id=<A 的客户>)` **成功**（`INSERT 0 1`）。
> **规模**：源表与目标表**都** `FORCE RLS`、且外键只引用**单列**的外键 = **47 处**，
> 涉及 **12 张**被引用表、覆盖全部 **30 张** `FORCE RLS` 源表。
> **修法**：`V16__cross_tenant_reference_integrity.sql` —— 给 12 张载体表补 `UNIQUE (tenant_id, <pk>)`，
> 再把 47 处单列外键换成**租户耦合的复合外键** `(tenant_id, ref) → (tenant_id, pk)`。
> **只增不减**：30 张源表 `tenant_id` 全 `NOT NULL` ⇒ `MATCH SIMPLE` 下永远参与检查，
> 可空引用列（如 `store.region_id`）的 NULL 语义不变。
> **三向验证**（缺一条就不足）：跨租户**必须被拒**（`23503`）· 同租户**必须成功** ·
> 拒绝理由**不得是 `42501`**（RLS）—— 第三条是**分水岭**：`42501` 只说明"换了个地方失败"，缺口还在。
> **两张账本随之收敛（边界移动必须是显式动作）**：
> ① 新建 `RlsCrossTenantReferenceGateTest`（**7 例**，真库）—— 含 **⑦ 门禁自证**（注入一处单列外键 ⇒
> 判据必须变红 ⇒ 整段回滚复原）；已登记进 `dy-app/pom.xml` 的两个 surefire execution
> （`default-test` 的 `<exclude>` + `rls-isolation-gate` 的 `<include>`）。
> ② **既有**测试类 `RlsV7IntakeProfileRevisionIsolationTest` **有两条用例报红并按自己的指引收敛**
> （详见下方"最强证据"）。
> 🛑 **判据缺陷：同一个坑连踩两次**。**(a)** 字符串 `LIKE '%(tenant_id, %'` 实际断言的是
> "`tenant_id` **恰好排第一位**" —— 实测 `UNIQUE (id, tenant_id)` 载体 + `FK (tenant_id, tgt)`
> **外键完全有效**（跨租户被拒 = t），文本判据却判"无载体"⇒ **假阳性**。**(b)** 修完 (a) 后写成
> `conkey[1] = 'tenant_id'` —— **仍是位置判定**，反向验证 C3（列序交换的**等价形态**
> `FK (customer_id, tenant_id) REFERENCES customer(id, tenant_id)`）报 `(b)=46（期望 47）`。
> ⇒ 最终**一律改用 `pg_constraint.conkey` / `confkey` 的列集合判定**（位置无关）。
> **判据可改，期望值不可改** —— 两次的正确反应都是修仪器，因为缺陷在仪器不在规格。
> 🛑 **`(d)` 的次序缺陷：根因断言成了死代码**。"缺载体唯一约束"原本排在"余量 ≠ 0"与"复合数 ≠ 47"**之后**
> —— 而缺载体必然先触发后两者 ⇒ `(d)` **永远轮不到报错**。已移至本块**第一条**，并新增反向验证
> **I4** 锁住它（`DROP CONSTRAINT … CASCADE` ⇒ 第一报错必须是 `(d)` 而非 `(a)`）。
> 🛑 **反向验证第一版"考完试改答案"**：注入被追加在迁移全文**之后**，而自证块已跑完并成功 ⇒
> I1~I4 **全部假失败**。修法：`inject_before_guard()` 用正则把注入插到 `$v16_guard$` **之前**。
> 落地 `verification/116_cross_tenant_reference_reverse_verification.py`（**9/9 PASS**）+ `.md`。
> 🛑 **本项最强的一份证据：一个在 V16 之前写下的门禁【主动叫醒了自己】。**
> `RlsV7IntakeProfileRevisionIsolationTest` 里原有一条 **"登记缺口（绿≠安全）"** 用例，
> 断言"库层目前**不**拒绝『本租户的行指向他租户客户』"，并在注释里**预告了失败形态**
> （"若它变红，说明上游已在库层堵上该缺口"）、**预告了修法**（"给本表加一条复合 FK
> `(tenant_id, customer_id) → customer(tenant_id, id)`，需 customer 上有对应唯一约束"——
> 这正是 V16 做的）、**留下了处置指引**（"请同步更新此处登记"）。
> ⇒ 按指引：① **翻转**为三向缺陷断言（必须被 `23503` 拒 + 理由**不得**是 `42501` +
> 反向自证该行未落库；🛑 反向自证必须**切回 B 的上下文**再查，否则"没落库"与"我看不见"
> 给出同一个 0，自证成了装饰）；② 修掉一处**借用**（另一条用例用 `SEED_B_ID`——**租户 B** 的客户——
> 表达"同租户里的另一个客户"，V16 后它变成跨租户引用而被正当拒绝 ⇒ 改为**自建的同租户第二客户**）。
> **口径沉淀**：**「另一个客户」从来就该是同租户的另一个客户，不是另一个租户的客户。**
> **一条缺口登记用例的正确生命周期 = 它必须能被上游的修复叫醒**；若永远绿，它是把缺口伪装成常态的**装饰**。
> 🛑 **全量回归抓出的真缺陷：迁移探针【越界】了**。`ProvisioningBoundaryGateTest` 第③例报红，
> 根因是 V16 自证块 `(e)` 里有 `INSERT INTO band`，而 `band` 在**"未开通账"**里
> ⇒ **门禁是对的，迁移越了界**：一条完整性修复迁移不该成为 `band` 的**第一个写入方**
> （那会把"设备/手环台账无写入方"这条**如实登记的边界**静默地移动成"有了"）。
> 修法：探针改用两端都在**已开通账**里的表对 `store → region`；
> **明确放弃**"把 `band` 加进 `PROVISIONED` 账"这条路（那是**改账本迎合实现**，且第②例会立刻指出"账本在骗人"）。
> 🛑 **由此得到本仓第二条硬约束：迁移文本一改，已应用的库必须显式处置。**
> 改完 V16 文本（自证块 + 注释）后，应用启动立刻 `Migration checksum mismatch for migration version 16`。
> 处置纪律：① 证明"新旧文本 **DDL 净效果相同**" ⇒ 对齐 checksum（= Flyway `repair` 语义）；
> ② 承认"效果变了" ⇒ 重建该库 / 补一条**新**迁移（forward-only）。
> **最坏的反应是"把 checksum 改成新值就完事"** —— 那等于用一行 `UPDATE` 掩盖一个**未经验证的断言**：
> **"我只改了注释"是人的记忆，不是机器的证据。**
> 落地 `verification/117_migration_chain_schema_reconciliation.py`（**6/6 PASS**）+ `.md`：
> 把**已应用库**与**用当前迁移链从零重建的库**逐项对账 —— ① 结构清单 · ② **约束结构指纹**（按列集，不用文本）·
> ③ `schema_migration` 登记 · ④ `pg_dump --schema-only` 归一化后**逐字节** · ⑤ **校验和常驻断言**（CRC32 口径
> 已用未改动过的 V1~V15 **反验命中**库中值，随后对全部 16 条通过）· ⑥ **判别力自证**。
> 🛑 **比较器必须对称**：117 第一版在计数时**没**排除 `flyway_schema_history`、在 `pg_dump` 时**排除**了它
> ⇒ 凭空造出"1 约束 / 2 索引"的差异。**报差异之前先怀疑比较器**（本仓第三次同类）。
> **验证**：`RlsCrossTenantReferenceGateTest` **7/7** · `RlsV7IntakeProfileRevisionIsolationTest` **12/12** ·
> `ProvisioningBoundaryGateTest` **7/7**（此前 1 例红）· `RlsCoverageGateTest` **4/4** ·
> **全量回归 `BUILD SUCCESS`**（dy-app **768 例** + 门禁 execution **108 例**，0 失败；Flyway `16 migrations` / `Current version: 16`）。
> V16 已在 `diaoyuanyun_dev` 与 RLS 门禁库 `diaoyuanyun_rls_test_app` **两个环境**应用并登记
> （余量 **0** / 租户耦合复合 **47** / 载体 **12** / 跨租户引用**被拒且同租户放行**）。
>
> **2026-09-27 变更（批次十五 · B-10 手环绑定通路）**：`dy-app` **768 → 776**（**+8**：新增
> `BandBindingGateTest` **7 例** + `ProvisioningBoundaryGateTest` 判据⑧ **1 例**）。配套反向验证
> **118**（**17/17**）与模板对账 117（**6/6**）另计。
> **⑤/③ 实测抓出一条【比 B-7 更严重的功能缺口】：`band` 表在生产代码里【零写入方】。**
> `band_telemetry` / `band_sync_probe` / `band_sync_log` / `band_daily_coverage` 四张表的
> `device_id` 都是 `NOT NULL` + 外键指向 `band.band_id`（V3 / V5 建），而**全仓 9 处
> `INSERT INTO band` 全部在 `src/test` 的夹具里** ⇒ 一条 band 行都没有 ⇒ **任何**写这四张表的
> 请求都会以 `23503` 失败。
> **后果**：**E1 `POST /band/sync-batches` 与 E2 `POST /band/telemetry` 两个契约端点在生产上必然失败。**
> **实测**（真库、应用角色、非超级用户、已设租户上下文）：
> `INSERT INTO band_sync_log(…, device_id=<未绑定的带子>)` ⇒
> `错误: 插入或更新表 "band_sync_log" 违反外键约束 "band_sync_log_device_id_fkey"` /
> `DETAIL: 表"band"中没有出现键。`（SQLSTATE **23503**）；**对照**：同一语句在**无租户上下文**时
> 报 `新行违背了表"band_sync_log"的行级安全策略`（**42501**）—— 两者可区分，故比较器对称。
> **修法**：`V17__band_binding_primitive.sql` —— 新建两个 PL/pgSQL 原语
> `bind_band()`（四态 `CREATED` / `ALREADY_BOUND` / `CUSTOMER_ALREADY_HAS_ACTIVE_BAND` / `REPLACED`）
> 与 `unbind_band()`（三态 `UNBOUND` / `ALREADY_UNBOUND` / `NOT_FOUND`）。
> **幂等下沉到库层**：`INSERT … ON CONFLICT (tenant_id, customer_id) WHERE status = 'active' DO NOTHING`
> + `GET DIAGNOSTICS … ROW_COUNT` —— **判定与写入在同一条语句里完成**，而不是"先读再判再写"
> （后者在并发下会把 `23505` 当成"客户已有带子"，而那可能是**任何**唯一约束，例如 `band_pkey`）。
> **解绑是状态迁移，永远不是删除**：台账必须历史完整，因为 `unbound_at` 是"应戴天"分母的**终点**
> （§4.3 分母护栏：A3 分母 = 台账应戴天），而 **A3 是退款资格的输入之一** ⇒ 删一行等于
> "修一条数据"静默变成"改一个客户的退款资格"。
> **🛑 三处设计取舍（都写进了迁移注释）**：
> **(a)** 两个原语**自己建立并自证租户上下文**（`set_config('app.tenant_id', …, **is_local := true**)`
> + `assert_tenant_context()`）—— `is_local := false` 会让上下文**跨请求泄漏给连接池里的下一个请求**；
> **(b)** **上下文一致性守卫**（fail-closed）：事务里已持有租户 B 的上下文时调用传租户 A ⇒
> **拒绝执行**（`RAISE EXCEPTION`，SQLSTATE **P0001**），实测消息逐字：
> `bind_band: 本事务已持有租户上下文 …，与本次调用传入的租户 … 不一致 —— 拒绝执行。`
> 原因：若在此静默覆盖，**本函数之后的全部写入都会落在 A 名下，而调用方以为在 B 名下**；
> **(c)** **走运维通路，不暴露 HTTP**（契约里没有绑定端点）—— 由判据⑧ 钉住。
> 🛑 **反向验证 118 抓出 V17 自身的 4 处缺陷（这是本轮最有价值的部分）**：
> **① (b7)/(c7) 判据【没有锚点】⇒ 假绿**。初版正则 `AND\s+tenant_id\s*=\s*p_tenant_id` 不带锚点，
> 而两个函数的**步骤 (4) 各有一条 SELECT** 逐字含同样子句 ⇒ **单独删掉【换机/解绑 UPDATE】的
> 租户维度时，断言返回 `t`（完全没反应）**（实测见 `_work/v17_b7c7_specificity_probe.sql`）。
> 修法：**从 `UPDATE band` 起锚、以 `;` 为界**（`[^;]*` 不跨语句）。
> **教训**：**判据的适用范围 = 它的锚点范围。**
> **② (a) 在"只缺一个函数"时报与 (a) 无关的错误**：`v_missing` 声明为 `text[]`（且带
> `:= ARRAY[]::text[]` 默认值），而 `string_agg()` 返回 `text` ⇒ 报
> `错误: 有缺陷的数组常量:"bind_band"`。修法：改 `text` + `IS NOT NULL`。
> **③ (d2) 在按本仓脚本建的库上【恒真、判别力为零】**：实测 `REVOKE … FROM current_user` 之后
> 自证**仍然打印"自证通过"** —— 因为迁移由应用角色自己执行 ⇒ **函数 owner = 调用者** ⇒
> owner 的 `EXECUTE` 是**隐含**的。实测 `proacl = =X/diaoyuanyun | diaoyuanyun=X/diaoyuanyun`。
> 修法：新增 **(d3)** 断言 `proacl` 含**显式 ACL 项**（即第 2 节授权段真的执行过）。
> 🛑 **只做处置而不改注入，等于用一条没被证明会变红的断言去修一条恒真断言** ⇒ 两条用例都留。
> **④ (d3) 里 `aclitem ~~ unknown`**：`aclitem` **没有 `LIKE` 运算符**，须显式 `::text`。
> **两张账本随之收敛（边界移动必须是显式动作）**：
> ① `ProvisioningBoundaryGateTest` 两账**显式改账**：`PROVISIONED` **35 → 36**（加入 `band`）、
> `NOT_PROVISIONED` **7 → 6**，并新增**判据⑧**（绑定通路存在为运维通路、且不暴露 HTTP 端点）；
> ⚠️ **本行的 36/6 是批次十五的【历史快照】，勿再引用为现值** —— 现值 **40/2**（见批次十九块）。
> ② `RlsInjectionRealityGateTest.LEDGER_CARRIERS` **19 → 20**（登记 `BandBindingLedger`）——
> **这是全量回归抓出来的**：新增载体未登记 ⇒ 门禁报红，**门禁是对的**。
> 🛑 **同目录两个 `band` 载体必须并存，理由写在登记表注释里**：`BandLedger` = **契约端点**的读写方
> （E1/E2 客户端上报链路）；`BandBindingLedger` = **运维通路**的写入方（`band` 表唯一的
> `INSERT` 来源，底层调 V17 原语）。同一张表的**两个体**，差别在**写入口的权威层级**。
> **三向验证**（缺一条就不足）：未绑定**必须被拒**（`23503`）· 绑定后同一写法**必须成功** ·
> 解绑后**必须重新变回** `23503`（**判别力自证**：否则一个"任何状态都报 23503"的断言也能全绿）。
> 🛑 **迁移文本改了 ⇒ 必须显式处置已应用的库**（本仓第二条硬约束，见批次十四）。本轮处置为
> **"净效果相同 ⇒ 对齐 checksum"**，依据是**三重独立证据**：
> ① 117 **6/6**（42 表 / 516 列 / 251 约束 / 179 索引 / 38 策略 / 8 函数 / 17 登记行；
> `pg_dump --schema-only` 归一化后**逐字节相同**）· ② 库中 `pg_proc.prosrc` 与当前文本
> `$v17_bind$…$v17_bind$;` 切片**逐字相同**（5633 / 2276 字符）· ③ 本轮**全部**编辑都落在
> `$v17_guard$`（第 557–1120 行）内 = 纯自证块、**零 DDL**。
> ⇒ `flyway repair` 从 **1930304913** 对齐到 **587148978**。
> 🛑 **repair 的操作坑（已实测）**：Flyway 10.10.0 的 `repair` **只清"失败迁移"**，**不会**自动
> 对齐"成功迁移"的 checksum；且**必须**给**绝对路径**的 `filesystem:` location
> （`classpath:` 会静默 `No failed migration detected` 而不报错；相对 `filesystem:` 会 `not found`）。
> 对齐后**必须**再跑 `mvn -pl dy-app process-resources`，否则 `target/classes` 里仍是旧文本。
> **验证**：`BandBindingGateTest` **7/7** · `ProvisioningBoundaryGateTest` **8/8** ·
> **全量回归 `BUILD SUCCESS`**（dy-app **776 例** + 门禁 execution **108 例**，0 失败）；
> 其余 7 模块全 GREEN（dy-common **10** / dy-tenancy **39** / dy-security **48** / dy-web **58** /
> dy-audit **37** / dy-config **29** / dy-crypto **29**）；
> `verification/116` **9/9**（**确认 B-10 改账未破坏 V16 的反向验证** —— 116 的注入是"迁移文本
> **之后**追加"且脚本不在迁移链上，故不受 `band` 改账影响）· `117` **6/6** · `118` **17/17**。
> V17 已在 `diaoyuanyun_dev` 应用并登记（`17 | band binding primitive`）。
>
> **2026-09-27 变更（批次十二 · B-2 生产幂等纪律 + B-3 过渡态显式化）**：**1086 → 1099**（**+13**）。
> **① B-2（生产禁内存幂等后端）** —— 原登记为"部署配置项"（`env.getProperty(BACKEND_PROPERTY, MEMORY)`
> ⇒ 默认内存）。本轮按建议①+②**双向收口**：`application.yml` 的 **prod 段显式给 redis 默认**
> （`${DY_IDEMPOTENCY_BACKEND:redis}`）；`IdempotencyConfiguration` **新增启动自检**
> `reject_memory_backend_in_production(...)` —— **active profile 含 `prod` 且结果为 `memory` ⇒ 抛
> `IllegalStateException`，应用拒绝启动**。判据用 **active profile**（部署时已在用、无法"忘记打开"的既有机制），
> 且对 `"Prod"` / `" prod "` 变体归一化（否则可静默绕过）。
> 新增 5 例（`IdempotencyBackendFailureTest` 6 → 11）+ `IdempotencyProdBackendConfigGateTest`（**2 例**，
> snakeyaml 真解析 prod 文档的键路径 —— **断言交付物本身**，不依赖启动 context）。
> **② B-3（配置来源过渡态显式化）** —— 完整收口需**架构裁定**（总部唯一项"在线改"的承载点），**不代拍**；
> 本轮把"过渡态"从**注释承诺**升级为**构建期事实**：新建 `ConfigSourceTransitionGateTest`（**7 例**）
> —— ① **全集机械核对**（源树派生的 `ConfigSeed*` 全集 vs 登记集，精确相等；元层自证断言扫描非空，
> 防目录搬家后退化成"空集 == 空集"的恒绿门禁）；② 五个域端口各自**恰有一个**过渡实现；
> ③ `describeSource()` **返回值**（非源码文本）必须含「过渡」；④ `SEED_RESOURCE` 常量**精确等于**
> `db/config/02_slots_seed.sql`（正则抓常量赋值行，防"改了常量但注释里同名路径仍在"漏网）；
> ⑤ 端口**不得引入租户维度**。
> 🛑 **本轮复核推翻登记册两条"修复建议"**：① "让 `JdbcConfigService` implements `ConfigService`"
> **与既有硬门禁冲突**（`ConfigWiringDisciplineTest` 断言它**刻意不得** implements —— DB 版每方法都要显式
> `tenantId`，而接口无租户维度）；② "把 ProfileSource 换成读 DB" 会**违反** `01_truth_source_ddl.sql` 第 24 行的
> **读路径纪律**（`config_slot.initial_value` 仅新租户初始化读一次），且**仍不解决"在线改配置"**
> （口径在启动期被物化成不可变 Bean，读哪张表都一样）。
> 🛑 **另修正一处登记偏差**：过渡来源是 **6 个** `ConfigSeed*` 类（非"五个"）—— 多出的
> `ConfigSeedContraindicationSource` **不实现任何端口**（其 `#8` 禁忌清单的契约 B1 只有 200/400、
> **无 500**，fail-closed 方向与其它域**相反**，塞不进"统一端口 + 统一 fail-closed"抽象）。
>
> **2026-09-27 变更（批次十一 · 跨源一致性门禁 + 契约驱动矩阵 + 跨店域配置接线）**：**1064 → 1086**（**+22**）。
> **① N-14 真缺陷修复（配置接线）**：`#21 cfg:crossstore.split_threshold` / `#22 cfg:crossstore.anomaly_rule`
> 此前**声明了却没人读** —— `CrossStoreSettlement.DEFAULT_SPLIT_THRESHOLD` 硬编码 `0.30`、`WebConfig` 直接 `new`。
> 现按本仓惯例的**来源-端口范式**接线：新建 `CrossStoreRawConfig`（原始声明值，**不含默认值**）·
> `CrossStoreProfileSource`（端口）· `CrossStoreThresholds`（**唯一**解析+范围与单调性校验，越界即抛不夹取）·
> `ConfigSeedCrossStoreProfileSource`（过渡实现，解析 seed `#21`/`#22`），`CrossStoreSettlement` 删除常量、
> `WebConfig` 注入端口。**并纠正一处隐性同值**：反刷线原与 `splitThreshold` 同值，实应取 `#22` 的 `cross_share_pct`。
> 台账断言⑤由「差异仍在」**翻转为** `the_21_hardcoded_default_divergence_is_closed`（「差异已消除」）；
> `ConfigSlotConsumptionLedgerTest` 账面 **16 已消费 ∪ 30 未消费 → 18 ∪ 28**（#21/#22 移入已消费）。
> 🛑 **跨模块连带**：`SettlementController` 从 `settlement` 根包移入 `settlement.controller` 子包，
> 并登记进 `ArchitectureBoundaryTest` 的四张列表 —— 否则该控制器不在任何被登记层里，**R2 对它静默失效**。
> **② N-8 跨源一致性门禁**：新建 `RefundDomainCrossSourceGateTest`（**9 例**），
> 读**真 V6 SQL** + **真契约 YAML**，做「库侧列 × 契约 schema 字段」**双向覆盖** + 逐条登记（11 条差异）。
> 🛑 **门禁首次运行即抓出一个真缺陷**：`extractCreateTableColumns` 把 `refund_receipt` 建表体里
> **内联多行 CHECK 的续行** `OR` 误读成列名（假列 `or`）⇒ 报出一条**假缺口**（比漏检更坏：它诱使人改错目标）。
> 已修为**括号深度跟踪**（只有"进入该行时处于建表体顶层"才可能是列定义），并加 **12 列金丝雀**。
> 另定义「**通用审计列**」这一比对粒度（`tenant_id`/`created_at`/`updated_by` 等全表骨架列天然不属于
> 域字段缺口），且**豁免本身也机械核验**：从字典 §二「审计字段（全表必带）」**逐字提取**，
> 剔除**全角括号释义**（防 `operator_id` 被误豁免掉一条真实业务缺口）。
> **③ C-3 写路径矩阵改为契约驱动**：`RefundWritePathMatrixE2ETest` 的角色集原先**手抄**
> `List.of("meridian","manager","area","hq")` —— 契约改了**不会红**。现改为从 `x-callable-roles` ×
> `x-roles.token-role` **机械展开**（`admin → manager/area/hq`；`token-role: null` 的无端角色**显式跳过**），
> 并加「展开结果 = 显式期望值」自证。
> 🛑 **反向验证证明改造有效**：往契约 G3 注入 `therapist` 后，矩阵**自动去测这个新角色**并抓出
> `403 refund:write` —— 而手抄时代这行请求**根本不会发出**（正是"静默漏测"的本质）。
> **按模块（surefire 实测）**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` **48** · `dy-web` **58** · `dy-audit` **37** · `dy-config` **37** · `dy-crypto` 29 · **`dy-app` 860**（= 默认 759 + RLS 隔离门禁 101）。
> **反向验证**：`verification/110_b2_b3_reverse_verification.py`（**4 组注入 · 5 项结论**）——
> **C1** prod 段 `backend` 改成 `memory` / **C2** 自检守卫 `if (...)` → `if (true)`（掏空）/ 
> **C3** `describeSource()` 去掉「过渡」/ **C4** `SEED_RESOURCE` 常量改名，**四组各命中唯一方法、全部被抓**，
> 逐字节还原后复绿 → **总体 5/5 PASS**（证据落 `verification/110_b2_b3_reverse_verification.md`）。
> 🛑 **预检当场抓到一个真问题**：C1 首版用**两行锚点**，预检报"出现 0 次"（而两行肉眼可见地在）——
> 多行锚点在 Windows + heredoc 转义链路下不可靠。**若无预检，`replace` 会退化成空操作 ⇒
> 注入根本没发生 ⇒ 门禁全绿 ⇒ 脚本把"门禁有牙齿"误报成"没牙齿"**（一种假阴性）。
> 修法：**锚点改单行**。纪律沉淀：多行锚点优先改单行；必须跨行时预检要打印**逐字节长度**而非仅 `count`。
>
> **按模块（批次十一 · surefire 实测）**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` **48** · `dy-web` **51** · `dy-audit` **33** · `dy-config` **37** · `dy-crypto` 29 · **`dy-app` 839**（= 默认 738 + RLS 隔离门禁 101）。
> **反向验证**：`verification/109_cross_source_and_contract_driven_reverse_verification.py`（**6 组注入 · 7 项结论**）——
> **C1** 契约补 `requested_at_source`（模拟上游补齐 N-8）/ **C2** 库侧 `template_id` 改名 / **C3** 字典去掉 `tenant_id` /
> **C4** 契约 G3 加 `therapist` / **C5** `x-roles.admin` 去掉 `hq` / **C6** 无端角色改成 `meridian`，
> **六组各命中唯一方法、全部被抓**，逐字节还原（`openapi=81307B` / `V6=12310B` / `dict=105135B`）后复绿
> → **总体 7/7 PASS**（证据落 `verification/109_cross_source_and_contract_driven_reverse_verification.md`）。
> 🛑 **`verification/108` 同步修订（连带责任）**：其 P1 锚点原是 `CrossStoreSettlement.DEFAULT_SPLIT_THRESHOLD`
> 的 `0.30` 字面量 —— 而该常量已被 N-14 修复**删除** ⇒ 锚点不存在。**锚点预检把这次失效从静默变成显式失败**
> （这正是锚点预检的价值）。P1 改注入**接线本身**（`WebConfig` 的 `fromRawConfig` → 直接 `new`），
> P4 锚点随断言文本扩写同步更新，断言名 `..._still_registered` → `..._is_closed`。
>
> **2026-09-27 变更（批次十 · 配置槽位「声明 × 消费」台账）**：**1059 → 1064**（**+5**）。
> ① **把批次九的 N-3 洞见推广到 46 槽规模**：批次九只守了 `cfg:verdict.*`（4 槽），
> 而配置真相源声明 **46** 槽 —— "**哪几条真的被生产代码读过**"此前**无人守**。
> ② 新建 `ConfigSlotConsumptionLedgerTest`（**5 例**），判据三条：**全集侧机械读 seed**（非手抄）·
> **消费信号 = 生产代码（剥注释后）出现该键字面量** · **账面逐条对账**（账记"已消费"的代码必须真有、账记"未消费"的必须真没有 ⇒ **归零须显式动作**）。
> ③ **实测消费面：16 已消费 ∪ 30 未消费 = 46**（两账互斥、无第三个）。
> 🛑 **`#21 cfg:crossstore.split_threshold` 是真缺陷候选**：seed 标注"**总部唯一可改**"，
> 而 `CrossStoreSettlement.DEFAULT_SPLIT_THRESHOLD` **硬编码 `0.30`**、`WebConfig` 直接 `new CrossStoreSettlement()`
> ⇒ **从不读配置** —— "总部可改"这句目前**不成立**（属 config owner 裁定域，本台账只钉现状）。
> 🛑 **`#30 cfg:verdict.formula_params` 未消费且与 #4/#35/#33 同值多处声明**（`adherence_gate.min=0.8`≡#4 的 `0.80`；`range` 与 #35 逐字相同；`module_total_max=16` 同 #33）。
> 🛑 **剥注释是必需的**（本轮自证）：`cfg:verdict.branch_rules`（#9）与 `cfg:improvement.calc_rule`（#32）**只出现在 Javadoc 里**，不剥注释会把它们误判为"已消费"。
> **按模块（surefire 实测）**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` **48** · `dy-web` **51** · `dy-audit` **33** · `dy-config` **37** · `dy-crypto` 29 · **`dy-app` 817**（= 默认 719 + RLS 隔离门禁 98）。
> **反向验证**：`verification/108_config_slot_consumption_reverse_verification.py`（**5 组注入 · 6 项结论**）—— **P1** 假装修好 #21（改 `0.30→0.31`）/ **P2** seed 插第 47 条槽位（`#49`）/ **P3** 账本代表性文件致幻影 / **P4** 生产代码越界消费未消费键 / **P5** 短路块注释检测，**五组各命中唯一方法、全部被抓**，逐字节还原（`Test=24713B` / `Settlement=15006B` / `Seed=29979B`）后复绿 → **总体 6/6 PASS**（证据落 `verification/108_config_slot_consumption_reverse_verification.md`）。
> 🛑 **P3/P4/P5 的连带红集是设计使然**：五条测试共享同一份事实（seed 全集 + 生产代码引用集），一处漂移在多个方向留痕 ⇒ **交叉自证**，不是噪声。
> **跨模块纪律再次事前规避**：P2 注入的是 **dy-config 的 classpath 资源**（被测类在 dy-app），脚本写死 `NEEDS_CONFIG_INSTALL={"P2"}` ⇒ **一次即中**（本仓第三个跨模块注入）。
>
> **2026-09-27 变更（批次九 · 排除项 × 配置真相源交叉自证 · N-3 收口）**：**1058 → 1059**（**+1**）。
> ① **发现真实漏项**：`cfg:verdict.*` 在真相源（`dy-config/.../02_slots_seed.sql`）里**恰 4 槽**
> （#9 `branch_rules` / #30 `formula_params` / #33 `mcid_threshold` / #45 `confidence_formula`）。
> 指纹九段消费 **#33 / #45**，而 `EXCLUDED_NOTE` **只点名 #30** ⇒ **`#9` 既不在九段、也不在排除说明里**（**两侧都不在**）。
> 这正是 N-3 要防的"『不纳入』与『忘了纳入』在复盘时无法区分"：收口前该问题只能靠**人回想**回答。
> ② 新增断言 `excluded_items_are_cross_checked_against_the_verdict_config_namespace`（+ helper
> `seedSlotNumbersInNamespace`），核心等式 = **`cfg:verdict.*` 全集（机械读 seed）== 九段已消费 ∪ `EXCLUDED_NOTE` 点名，不得有第三个**；
> 并**反证** `EXCLUDED_NOTE` 点名的槽位真实存在。`ThresholdVersionFingerprintTest` 13 → **14** 例。
> ③ **`EXCLUDED_NOTE` 补入 `#9 branch_rules` 条目** —— 把"不纳入"从**人回想**变成**钉在常量里的事实**（改的是"判断没被钉住"，不是那个判断本身）。
> ④ 🛑 **本轮把"配置真相源"首次拉进守护面**：全集侧是**机械提取**而非手抄 ⇒ 真相源新增/改名槽位自动进全集 ⇒ 必须被归入某一侧。
> **按模块（surefire 实测）**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` **48** · `dy-web` **51** · `dy-audit` **33** · `dy-config` **37** · `dy-crypto` 29 · **`dy-app` 812**（= 默认 714 + RLS 隔离门禁 98）。
> **反向验证**：`verification/107_excluded_items_cross_check_reverse_verification.py`（**3 组注入 · 4 项结论**）—— **P1** 从 `EXCLUDED_NOTE` 删 `#9` / **P2** 改 seed 使 `cfg:verdict.*` 多一槽 / **P3** 期望集脱节写 `Set.of("33","45")`，**三组各命中唯一方法、全部被抓**，逐字节还原（`Test=27160B` / `Impl=24750B` / `Seed=29979B`）后复绿 → **总体 PASS 4/4**（证据落 `verification/107_excluded_items_cross_check_reverse_verification.md`）。
> 🛑🛑 **批次八固化的跨模块纪律本轮首次产生【事前收益】**：P2 注入的是 **dy-config 的 classpath 资源**（被测类在 dy-app），
> 脚本里直接写好 `NEEDS_CONFIG_INSTALL={"P2"}`（跑 P2 前先 `mvn -o -pl dy-config install -DskipTests`）⇒ **P2 一次即中，无须二次追查**
> （对比批次八是**事后归因**：首跑 3/4 才发现盲区）。
>
> **2026-09-27 变更（批次八 · 遗留角色码未持自建码的登记式差异）**：**1057 → 1058**（**+1**）。
> ① **`SKELETON_DEFINED_CODES` 里 `doc:write` 条目的那句自由文本**（"TENANT_ADMIN / super_admin 未登记该码…会被 403"）**是对的，但没有任何东西在守**。实测事实：`PermissionRegistry` 里 `TENANT_ADMIN` / `super_admin` **根本没有键** ⇒ `hasPermission(...,"doc:write")` 恒 `false`；而 `DocFileService.SUPER_ADMIN_ALIASES` 又把它们**视为超管** ⇒ **业务层认为你是超管、权限层不认**（两个各自正确的部件串起来后语义不一致，与 §5.1 第 31 条"组合自环"**同族**：不是任一条错了，而是**它们的交集没人看**）。
> ② 落成结构化登记表 **`LEGACY_ROLE_MISSING_SELF_DEFINED_CODES`** + 四段断言：**① 差异真的还在**（已持有 ⇒ 红，要求归零）/ **② 对照组**（被登记缺的码必须有其它契约角色持有 —— 把"角色级缺口"与"码级无主"分开，后者属另一条断言）/ **③ 恰 2 个角色**（**防表清空 ⇒ 循环不进 ⇒ 平凡通过**）/ **④ 与第六章台账交叉自证**。`PermissionCodeRegistrationGateTest` 6 → **7** 例。
> ③ 🛑 **本表【不】回答**"该不该给 `TENANT_ADMIN` 补 `doc:write`/`audit:read`" —— 那是 **A-8 契约 owner 的裁定**；本表只把**现状**钉死，使"裁定之后"成为**显式动作**。
> **按模块（surefire 实测）**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` **48** · `dy-web` **51** · `dy-audit` **33** · `dy-config` **37** · `dy-crypto` 29 · **`dy-app` 811**（= 默认 713 + RLS 隔离门禁 98）。
> **反向验证**：`verification/106_legacy_role_missing_codes_reverse_verification.py`（**3 组注入 · 4 项结论**）—— **R1** 把分叉"修好"（给 `TENANT_ADMIN` 补码）/ **R2** 登记表写成已持有的 `hq` / **R3** 登记表清空为 `Map.of()`，**三组各命中唯一方法、全部被抓**，逐字节还原（`GateTest=47159B` / `PermissionRegistry=24560B`）后复绿 → **总体 PASS 4/4**（证据落 `verification/106_legacy_role_missing_codes_reverse_verification.md`）。
> 🛑🛑 **本轮最重要教训：跨模块注入必须先 install 依赖模块。** 首跑 `3/4`，唯一未抓的是 R1 —— 第一反应是"断言没牙齿"，**真因是注入根本没进到被测对象里**：`PermissionRegistry` 在 **dy-security**、被测类在 **dy-app**，`mvn -o -pl dy-app test` 会用**本地仓库的旧 jar**，源码改动**完全不生效**。修法 = 每次跑门禁前先 `mvn -o -pl dy-security install -DskipTests`。
> **这是"未被抓住"的第二种成因（与 103 首版"POSIX 路径 + `shell=True` ⇒ Maven 从未执行"同族）：必须先分辨"守护坏了"还是"注入没进到被测对象里"。** 顺带核过：103/104/105 的注入对象与被测类**同在 dy-app**，不受影响；**106 是本仓库首个跨模块注入，故此盲区首次显形**。
>
> **2026-09-27 变更（批次七 · `refund_offline_notice` 字段级约束缺口收口）**：**1054 → 1057**（**+3**）。
> ① 清点 `RlsV6RefundLedgerIsolationTest` 的 11 条原断言，**按被测对象归类**后发现：`refund_offline_notice`（三张账本之一）的**字段级约束一条都没被断言**——它的 `channel` 枚举、`NOT NULL` 完整性、CHECK 数量全靠"表建出来了"默认成立。属**文档比实现更自信**（类注释写"三张表各自都值得一套断言"，实际只覆盖了两张）。
> ② 补 **3 例**（该类 11 → **14**）：`..._channel_excludes_push_channels`（🛑 `订阅消息` 必须被拒——它是推送通道，放行会让"线下告知"被算成一次"已推送"、直接制造覆盖率虚高）/ `..._required_columns_are_not_null`（`refund_id` 缺 → 23502 且**消息点名列**；`tenant_id` 缺 → 42501，故单独走 RLS 断言）/ `..._has_exactly_one_check_constraint`（CHECK 数须恰 1，**并反证表真实存在**以避"表名打错 ⇒ 查到 0 条 ⇒ 假绿"）。
> ③ 新增 helper `assertNotNullRejected(column, ...)`：把 SQLSTATE 钉在 `23502` **并校验消息点名列**——与 `assertCheckRejected`（23514）分开的理由是**"某条约束报了"不等于"我要测的那条有牙齿"**（同列可既 NOT NULL 又有 CHECK）。
> **按模块（surefire 实测）**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` **48** · `dy-web` **51** · `dy-audit` **33** · `dy-config` **37** · `dy-crypto` 29 · **`dy-app` 810**（= 默认 712 + RLS 隔离门禁 98）。
> **反向验证**：`verification/105_refund_offline_notice_reverse_verification.py`（**3 组注入 · 4 项结论**）—— **M1** channel CHECK 扩含 `订阅消息` / **M2** 去掉 `refund_id NOT NULL` / **M3** 追加第二条 CHECK，**三组各命中唯一方法、全部被抓**，逐字节还原（`V6=12310B` / `RlsV6...Test=55081B`）后复绿 → **总体 PASS 4/4**（证据落 `verification/105_refund_offline_notice_reverse_verification.md`）。
> 🛑 **本批次新增纪律：must_see 锚点必须做【元层判别力自证】** —— 若"方法名出现"是恒真的（如 surefire 汇总行也含类名），则"被抓=True"全部失效。修法 = **基线（全绿）阶段先断言待查方法名零出现**，否则直接判脚本失效（`return 2`）。第二轮实测：基线零出现 → 三轮注入各命中**唯一**方法。
> 🛑 **改迁移文本做反向验证是安全的（本轮给出依据）**：`RlsGateSupport` 的 schema 哨兵 = **整条迁移链字节的 SHA-256**，故注入 V6 文本 ⇒ 哨兵变 ⇒ 门禁判库不匹配 ⇒ **自动重建库** ⇒ 断言在真实新 schema 上运行；还原 ⇒ 哨兵回原值 ⇒ 建回原 schema。不需要手工重建，也不会假绿。
>
> **2026-09-27 变更（批次五 · 登记式门禁 + 纵深防御；批次六 · 契约端点覆盖总账）**：**905 → 1054**（**+149**）。增量可逐项对账：
> ① **批次五 `PermissionCodeRegistrationGateTest`（6，含第六章台账断言）** —— 权限码登记式台账（`SKELETON_DEFINED_CODES` / `CONTRACT_OR_DOMAIN_CONVENTION_CODES` 两组）+ `skeleton_defined_codes_form_a_closed_registry` 白名单闭合；**A-8 留痕**。
> ② **批次五 `UpstreamGapRegistryTest`（4，新建）** —— 上游缺口登记（F3/F4 明确出范围 + 契约行锚点）；**A-10 出范围**。
> ③ **批次五 `VerdictPersistenceBoundaryTest` 28（含新增 `v8_risk_flag_check_is_present_and_matches_enum_labels`）** —— N-1 `risk_flag` CHECK 闭合（两阶段事实：V5 无 CHECK / V8 补 CHECK）+ helper `extractRiskFlagCheck`。
> ④ **批次五 `DerivedVisibilityE2ETest` 12（含新增 `e5_success_response_carries_no_client_forbidden_field`）** —— A-9 出参方向断言 + `allowedExtensions` 白名单 + 纵深防御分层可执行证据（K2a 未登记字段 ⇒ 红 / K2b 受限字段 ⇒ 保持绿）。
> ⑤ **批次六 `EndpointCoverageLedgerTest`（4，新建）** —— 契约端点覆盖总账：45 operationId ↔ 实现侧端点，差额逐条登记（`OUT_OF_SCOPE` 4 条 / `INTERNAL_ENDPOINTS` 27 条）。
> ⑥ **其余增量**为批次一~四各域 E2E / 行校验 / 真库隔离门禁类的新增与扩容（逐类可对账，见各域专节）。
> **按模块（surefire 实测）**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` **48** · `dy-web` **51** · `dy-audit` **33** · `dy-config` **37**（= 默认 13 + 真库 IT 24）· `dy-crypto` 29 · **`dy-app` 807**（= 默认 **709** + RLS 隔离门禁 **98**）。
> ⚠️ **上列 `dy-web` 51 / `dy-audit` 33 / `dy-config` 37 与下方历史对账段（51 / 32 / 33）不一致** —— 历史段是各轮**当时**的快照，其后的增量未回填；**本行为当前实测口径**（`mvn -o install` 后按 `TEST-*.xml` 逐模块求和，时间戳统一、无陈旧残留）。全量口径以本行为准。
> **反向验证**：`verification/103_b5_registry_and_gates_reverse_verification.py`（**K1/K2a/K2b/K3 全抓，12/12 PASS，逐字节还原全绿**）+ `verification/104_endpoint_coverage_reverse_verification.py`（**L1/L2/L3 全抓，8/8 PASS，逐字节还原全绿**）。
> 🛑 **批次六新增判据**：**"系统开发完成"必须能被机械清点，不能只靠人读 README** —— 契约 45 operationId = **41 已实现 ∪ 4 出范围**（`authLogin`【Non-goal】/ `listAuditSignals`+`getAuditCoverage`【未立项】/ `receiveEsignCallback`【待冻结】）；实现侧 68 端点 = **41 对应契约 + 27 内部端点**（内部自描述 14 / 演示入口 4 / 自建能力面 9）。
> 🛑 **注入纪律（本轮固化）**：反向验证的注入必须**保持源文件语法合法** —— 改**键名**（`put("x",` → `put("xRENAMED",`）而非删整行，否则"编译失败也算被抓住"会掩盖"断言其实没有判别力"。
>
> **2026-09-26 变更（S3-4 · 契约域 I 文书模板 I1/I2/I4/I5/I6/I8）**：**894 → 905**（**+11**）。增量可逐项对账：
> ① **`DocTemplateE2ETest` 5（新建）** —— 契约域 I 六行（I2 建模板 / I4 新版本 / I8 渲染脱敏 / I8 fail-closed 403 / I8 白名单越界 403）真容器 + 真签名 JWT + 真库 E2E；
> ② **`DocTemplateRowTest` 6（新建）** —— 文书模板行构造校验（doc_type 六值 / editor 形态 content 必填 / upload 形态 file 四字段 / mime_type 三值 / file_size 上限 / version≥1 不可覆盖）。
> **按模块**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` 48 · `dy-web` 51 · `dy-audit` 32 · `dy-config` 33 · `dy-crypto` 29 · **`dy-app` 663**。
> **反向验证**：`verification/99_domain_i_reverse_verification.py`（**3/3 PASS + 1 OPTIONAL-SKIP**，逐字节还原全绿）。
> 🛑 **新立权限码 `doc:write`**（文书模板写，仅 admin 三端）；**新增错误码 `2004 PLACEHOLDER_OUT_OF_SCOPE`**（契约 §2.0 权威表自域 I 起含该码，`ResultEnvelopeTest` 期望表同步至 12 码）。
> 🛑 **I3 上传 / I7 下载属文件系统通道**（multipart + 对象存储 + 字节流 + 超管），依赖对象存储接入，已登记待对接。
> → **2026-09-26 更新（B-4）**：**该句「依赖对象存储接入」的表述已作废**。勘察事实 = DB 存储契约早有、**端点与存储抽象从未存在**、`spring.servlet.multipart` 未配置（默认 1MB < 契约 10 MiB）、`IdempotencyBodyCacheFilter` 包装所有请求破坏 multipart 解析。三条已全部处置，I3/I7 现为**可用端点 + 可换实现的 `BlobStore` 抽象**。详见 §5.2 的 B-4 条目。
>
> **2026-09-26 变更（S3-3 · 契约域 E 设备与手环数据 E1/E2/E3/E6）**：**883 → 894**（**+11**）。增量可逐项对账：
> ① **`BandEndpointsE2ETest` 6（新建）** —— 契约域 E 四行（E1 同步批次幂等 409 / E2 遥测 upsert 双分支 / E3 gap_reason 客户不下发 / E6 同步状态卡）真容器 + 真签名 JWT + 真库 E2E；
> ② **`BandRowTest` 5（新建）** —— 手环行构造校验（TelemetryRow metric 13 值 / SyncBatchRow 四态 与 is_wear 技术性缺失覆写）。
> **按模块**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` 48 · `dy-web` 51 · `dy-audit` 32 · `dy-config` 33 · `dy-crypto` 29 · **`dy-app` 652**。
> **反向验证**：`verification/98_domain_e_reverse_verification.py`（**3/3 PASS + 1 OPTIONAL-SKIP**，逐字节还原全绿）。
> 🛑 **E4/E5 已在 S1-5/S1-7 落地**（`BandDerivedController` / `BandAvailableDatesController`）。
> 🛑 **契约 §4.1 与 PRD §4.6 词表分叉**（E1 四态/触发源 vs `band_sync_log` result 两态）：E1 暂只做契约校验 + 内存幂等受理、不落 `band_sync_log`，分叉登记待契约 owner 裁定。
>
> **2026-09-26 变更（S3-2 · 契约域 D 全 8 端点 D1~D6）**：**848 → 883**（**+35**）。增量可逐项对账：
> ① **`FulfillmentDomainDEndpointsE2ETest` 6（新建）** —— D1~D4 真容器 + 真签名 JWT + 真库（四道闸门 403 逐字 / visit_no 递增 / 客户字段裁剪 / 同日重复 409）；
> ② **`FulfillmentGateGuardTest` 7（新建）** —— 四道闸门顺序与缺失项字面；
> ③ **`FulfillmentRowTest` 6（新建）** —— VisitRow/DailyReportRow 构造校验；
> ④ **`PlanAndDispatchE2ETest` 7（新建）** —— D5~D6（退回必填 400 / 同人自助二次确认 409 / 审核后下发 200 / 未审核下发 403）；
> ⑤ **`PlanReviewAndDispatchRowTest` 9（新建）** —— PlanReviewRow/DeviceDispatchRow/PlanRow 构造校验。
> **按模块**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` 48 · `dy-web` 51 · `dy-audit` 32 · `dy-config` 33 · `dy-crypto` 29 · **`dy-app` 641**。
> **反向验证**：`verification/96_domain_d_reverse_verification.py`（D1~D4，**4/4 PASS**）+ `verification/97_domain_d56_reverse_verification.py`（D5/D6，**3/3 PASS**），均逐字节还原全绿。
> 🛑 **新立权限码 `fulfillment:write`**（服务核销/填报/方案/下发，发给 therapist/meridian/admin）；D2/D3/D4/D5-b 含 client 不贴码。
>
> **2026-09-26 变更（S3-1 · 契约域 C 的 C1/C2/C3）**：**829 → 848**（**+19**）。增量可逐项对账：
> ① **`AssessmentDomainCEndpointsE2ETest` 12（新建）** —— 契约域 C 三行（C1 题库 / C2 基线评估 / C3 评估详情）真容器 + 真签名 JWT + 真库 E2E，
> 三个 `@Nested` 分组：`QuizList` 3 · `BaselineSubmit` 6 · `AssessmentDetail` 3；
> ② **`BaselineAssessmentRowTest` 7（新建）** —— 基线评估行的构造期 fail-closed 校验（8 组分龄锁定 / NOT NULL / 空白 JSON）。
> **按模块**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` 48 · `dy-web` 51 · `dy-audit` 32 · `dy-config` 33 · `dy-crypto` 29 · **`dy-app` 606**。
> **反向验证**：`verification/95_domain_c_reverse_verification.py` —— **4 条真注入，4/4 全部被抓，且还原后全绿**（证据落 `verification/95_domain_c_reverse_verification.md`）。
> 🛑 **本轮新立权限码** `assessment:write`（C2 基线评估写），发给 therapist/meridian/admin 三端；C1/C3 含 client 故不贴码（与 B4/B5 同款）。
>
> **2026-09-25 变更（S2-10）**：**795 → 829**（**+34**）。增量可逐项对账：
> ① **`DomainBEndpointsE2ETest` 21（新建）** —— 契约域 B 六行（B1~B6）真容器 + 真签名 JWT + 真库 E2E，
> 六个 `@Nested` 分组：`ScreeningRecords` 4 · `Customers` 5 · `Consents` 4 · `CustomerDetail` 5 · `IntakeProfiles` 2 · `SelfDescription` 1；
> ② **`RlsV7IntakeProfileRevisionIsolationTest` 12** —— V7 的 `intake_profile_revision` 真库租户隔离门禁（S2-10 前置工序）；
> ③ **`DerivedVisibilityE2ETest` +1** —— 新增「合法 client 探测（无派生键）必须 200」的 E5 守护（缺口 B 的回归防线）。
> **按模块**：`dy-common` 10 · `dy-tenancy` 39 · `dy-security` 48 · `dy-web` 51 · `dy-audit` 32 · `dy-config` 33 · `dy-crypto` 29 · **`dy-app` 587**。
> **反向验证**：`verification/93_domain_b_reverse_verification.py` —— **7 条真注入，7/7 全部被抓，且还原后全绿**（证据落 `verification/94_domain_b_reverse_verification.md`）。
>
> **2026-09-23 变更**：**242 → 302**（**+60**）。全部增量可逐项对账：
> ① **S1-4 题库引擎骨架（+43）** —— `ScaleItemBankServiceTest` 15 + `ScaleEngineContractTest` 8
> + `ScaleProfileSourceTest` 8 + `RlsScaleItemBankIsolationTest` 12（真库隔离门禁）；
> ② **S1-7 手环日期探测契约（+15）** —— `BandAvailableDatesTest` 15；
> ③ **`ArchitectureBoundaryTest` 6 → 7（+1）** —— 新增规则覆盖 `app/scale`、`app/band` 两个新模块；
> ④ **`dy-crypto` 28 → 29（+1）** —— 修复 `FieldCryptoGateTest` 的概率性假红，
> 为判据加装反向验证 ⑥（详见 `verification/crypto/README.md` §已知局限 第 4 条）。
> 结果：`dy-app` 由 46 → **105**，`dy-crypto` 由 28 → **29**，其余模块计数不变。
>
> **2026-09-24 变更**：**302 → 312**（**+10**）。全部增量可逐项对账：
> ① **S1-1 契约冻结闸门（+10）** —— `ContractFreezeGateTest` 10（dy-app，属**默认** execution，
> 非真库门禁），断言 **OpenAPI ↔ 上游语义母本 ↔ config #43 ↔ 原型 D1** 四方逐格一致，
> 含 7 条单源扰动反向验证 + 畸形 YAML 必须抛错。
> 结果：`dy-app` 由 105 → **115**（= 默认 76 + RLS 隔离门禁 39），其余模块计数不变。
> **无真库依赖**，故在 `-Ddy.rls.gate.skip=true` 环境下同样被执行（不会退化成 skipped）。
>
> **2026-09-24 变更（后半）**：**312 → 323**（**+11**）。**S1-2 后端 22 实体建模（V5 落地）**：
> ① **`RlsV5EntityIsolationTest`（+11，属真库门禁 execution）** —— V5 新增 **24 张租户表**的真库 RLS 隔离验证：
> 双向往返不可见 · 越权直查主键 0 行 · 无上下文 0 行（非全表）· 空串上下文 0 行且非 500 ·
> 跨租户 INSERT 被 `WITH CHECK` 拒 · 跨租户 INSERT 不留残行 · 策略元数据（`ENABLE`+`FORCE`+双 `NULLIF`，断言 `24 == TABLES.size()`）·
> `doc_template` 表单完整性 CHECK 拒残缺 · `doc_template` 第二活跃版本被拒 ·
> `band_sync_log.fail_stage` 仅契约 7 值 · `band_daily_coverage.gap_reason` 仅 7 值。
> ② **`RlsCoverageGateTest` 登记 24 条**（`Map.ofEntries`），三源交叉核对（迁移文本 / 登记表 / 真库 `information_schema`）随之扩至 **29 张租户表**；`Class.forName` 可加载性 + ≥5 `@Test` 门槛继续保持。
> （⚠️ 上句是 **V5 落地当时的历史快照**。**当前口径**：登记 **33 条**、真库 **33 张 `rls=t force=t`**（V6 三张退款账本 + V7 的 `intake_profile_revision` 已并入），见 §四 S2-10 对账段。）
> 结果：`dy-app` 由 115 → **126**（= 默认 76 + RLS 隔离门禁 **50**），其余模块计数不变。
> **`RlsV5EntityIsolationTest` 必须同时登记在两个 surefire execution**（`default-test` 的 `<exclude>` 与 `rls-isolation-gate` 的 `<include>`），否则默认 execution 会因无匹配测试而报错。
>
> **2026-09-24 变更（S1-3 多租户底座）**：**323 → 359**（**+36**）。全部增量可逐项对账：
> ① **`OrgLevelTest`（+11，dy-security）** —— 三级组织层级（总部/区域/门店）的映射与越权判定：
>    契约 §2.1 A1 的角色码登记（含 `hq`/`area`/`manager` 与遗留大写码 `SUPER_ADMIN`/`REGION_ADMIN`/`STORE_STAFF`/`TENANT_ADMIN` 双写）·
>    **未登记角色 fail-closed**（2003，绝不回落某层级）· `client` 不是组织层级 · A2 `row_level` 映射一致 ·
>    拒绝码 2001（层级不足）与 2003（身份不可识别）**不得混用** · 纯谓词不抛异常 · `requiredMin=null` 是调用方 bug。
> ② **`TierAuthorizationE2ETest`（+8，dy-app 默认）** —— **真 Spring 上下文 + 真 servlet 容器 + 真签名 JWT** 的越权回归：
>    `hq` 200 / `area` 403·2001 / `manager` 403·2001（PRD M5「门店/加盟商不可见」）· 未登记角色 403·2003 ·
>    `client` 403 · 匿名 403·2003；**换账号复验**用同一请求体跑三个账号，只有三者结果放在一起才能区分
>    「真按层级判定」与「凡非总部一律拒绝」。
> ③ **`CrossStoreSettlementTest`（+12，dy-app 默认）** —— PRD US-8/P1-05 跨店拆分纯逻辑：
>    **≥30% 触发 / 恰好 30% 触发 / 略低于 30% 不触发** · 占比按次数拆分（**允许小数**，`RoundingMode.DOWN` + 余数吸收 ⇒ **Σ 恰等于总额**）·
>    单店全归结案店 · 确定性（按 storeId 排序）· **总次数为 0 必须抛错（缺失不得补 0）** · 结案店数≠1 必须抛错 · 异常阈值 2 店标记 / >3 店冻结。
> ④ **`RlsRejectionAuditTrailGateTest`（+5，dy-app **默认** execution，真库）** —— S1-3 验收④「存储层拒绝 + 审计留痕」端到端：
>    被拒的跨租户请求 → `audit_log` **恰好一条**留痕 + 能按路径精确定位 · 归属必须是**全 0 UUID（无归属）** ·
>    `actor=anonymous` · `payload` 含错误码且**不含 token/密钥**（审计表全租户可读，T-09 敞口）· 留痕后**整条链仍自洽**（`verifyChain()` 独立复算）。
>    结果：`dy-app` 由 126 → **151**（= 默认 **101** + RLS 隔离门禁 50），`dy-security` 由 3 → **14**，其余模块计数不变。
>
> **2026-09-24 变更（S1-5 服务端唯一权威 · X-1）**：**359 → 437**（**+78**）。全部增量可逐项对账：
> ① **`DerivedProfileSourceTest`（+10，dy-app 默认）** —— 派生口径**只来自配置真相源**：
>    config `#4/#5/#6/#7/#33/#45` 六槽在 classpath 上可达且可解析 · 归一值逐项比对声明值（`0.80` / `0.571·0.286·0.143` / **无 A2** / `structural_keep` / `7` / Δ 边界 **0–2·3·≥1** / 指数 `1·0.25·0.25·0.50` / `28` / `14` / `gap 3` / `base 0.15` / `high_min 0.75`）·
>    **任一槽位空白即 5001（六例逐一）**绝不回落默认值 · 跨源一致性 · `toString()` **只报段长不报取值**。
> ② **`DerivedEngineContractTest`（+24，dy-app 默认）** —— 判定引擎与指标规格逐点比对：
>    **源码内不得出现门槛/权重/Δ 边界/指数字面量**（期望集由**反射读结构常量**推导，不手写清单）· A2 **结构性拒入**（合规锁不靠"调用方记得别传"）·
>    **AS_refund 用 config 权重**（全 1 → `1.000` PASS；仅 A1 → `0.571` 不足）· 门槛**闭区间**（`0.799` 拒 / `0.800` 过）·
>    样本护栏（6 天 → `null` + `SAMPLE_INSUFFICIENT`；7 天边界可用）· 缺失值两策略（`structural` 退出分母重归一 / `behavioral` 留在分母）·
>    **`Δ=0` 归稳定（E3）且不落穿成 5001** · **每个整数 Δ 恰落一档**（−2…+6）· 同源不成立即挂起并点名原因 ·
>    `baseline_zero` 走新发路径（不出 ∞ 百分比）· **引擎绝不自动产出 E5**（暴力遍历 0–16 全组合）· 自动/人工标记逐条对规格 §3.2 ·
>    m(Δ) **八个校验点**（Δ=3 为唯一谷底 `0.150`）· 不可比**挂起**而非打低分 · 加权几何合成（`d=14/28 → 0.841`，可区分算术平均 `0.875`）·
>    `n` 不适用时**指数重归一**（`d 0.3333 / m 0.6667`，`n` 键不存在）· 展示三档按 config 阈值翻转。
> ③ **`DerivedFieldsTest`（+21，dy-security）** —— 派生字段登记表与可见性解算（验收②的规则层）：
>    登记表与契约字段名**逐字一致、不多不少** · **匹配为"归一+精确相等"，绝不做"包含即命中"**（`as_value_ops` 反例）·
>    别名覆盖 camelCase 与合规词表拼写（`AS_refund`）· `gap_reason` 属③**不得并进④** · `derivedAndAdjacent = ③∪④` ·
>    登记表只读 · **匿名按客户 fail-closed**（否则去掉 token 反而绕过拦截）· staff 全角色不按客户处理 ·
>    未登记端角色 **fail-closed 抛错绝不回落** · 被拒字段**去重保序并保留原始拼写** ·
>    **`assertNoDerivedRequest` 抛出的 403 必带 `denied_fields`** · **空/含 null 的 `denied_fields` 在构造期即拒绝**。
> ④ **`DerivedRequestScannerTest`（+13，dy-security）** —— 三种"点名"写法的入站解算：
>    **契约 §2.1 B4 逐字**（`?include=verdict` → `["effect_verdict","as_value"]`，回显**字段名而非组别名**）·
>    参数名 / 参数值的词（6 种分隔符）/ 请求体键名（**递归含嵌套与数组**）· 组别名表**键必须是归一形态、值必须是客户不可见字段** ·
>    非法 JSON **刻意放过**（属 1001 范畴，报 403 会把排查方向带偏）· staff 恒空 · 匿名同客户 · **排序遍历保证结果可复现**。
> ⑤ **`DerivedVisibilityE2ETest`（+10，dy-app 默认，真容器）** —— 验收②+③ 的**真请求**形态：
>    客户调 E4 → **403 + 2001 + `denied_fields` 回显五项** · 契约 B4 逐字 · 参数名/体键名点名均 403 ·
>    **反向自证：经络师调 E4 → 200 且真返回派生字段**（否则"客户被拒"无法区分"因为他是客户"与"该端点对谁都拒"）·
>    **反向注入探针**（故意下发全部 ③④ 字段 + 嵌套 + 数组）→ 客户端响应里**整体不存在**，且 `as_value_ops` 与 ①② 字段**完好存活**（防误拦）·
>    **同一探针给 staff 必须原样保留**（出口兜底按角色判，不全局剥）· 匿名按客户 · 换账号复验两侧响应**必须不同** · 无派生字段的响应**信封逐字段完好**。
>    结果：`dy-app` 由 151 → **195**（= 默认 **145** + RLS 隔离门禁 50），`dy-security` 由 14 → **48**，其余模块计数不变。

> **2026-09-24/25 变更（第二批 · 退款域 S2-1~S2-5）**：**437 → 644**（**+207**）。全部增量可逐项对账（**实测值**，非估读）：
>
> **① dy-app 默认 execution：+196**（145 → **341**）
>
> | 测试类 | 数量 | 所属轮次 | 验证点 |
> |---|---|---|---|
> | `RefundPolicyContractTest` | 28 | S2-1 | 退款域领域模型与契约逐条比对 |
> | `RefundProfileSourceTest` | 9 | S2-1 | 退款域配置真相源（口径外置） |
> | `RefundDeputyDisciplineAndRetentionTest` | 53 | S2-2 | 代录纪律 / 挽留 / 审批目标（10 @Nested） |
> | `RefundVisibilityAndReceiptTest` | 38 | S2-3 | 可见性矩阵交叉验证 + 回执三态留痕（6 @Nested） |
> | `RefundWorkOrderEndpointTest` | 55 | S2-4 | 域 G 五端点控制器形态与装配（8 @Nested） |
> | `RefundDomainGEndpointsE2ETest` | 10 | S2-5 | **真请求 E2E**（真容器 + 真签名 JWT，4 @Nested） |
> | `PermissionCodeRegistrationGateTest` | 3 | S2-5 | **权限码登记门禁**（跨模块：`dy-security` 的登记表 ↔ 全部调用点逐条比对） |
> | **合计** | **196** | | |
>
> **② dy-app RLS 隔离门禁 execution：+11**（50 → **61**）—— 新增 `RlsV6RefundLedgerIsolationTest`
> （V6 五张退款表 `refund` / `retention` / `refund_statement` / `refund_receipt` / `refund_offline_notice` 的真库 RLS 隔离）。
>
> **结果**：`dy-app` 由 195 → **402**（= 默认 **341** + RLS 隔离门禁 **61**）；**其余 7 个模块计数一字未变**
> （dy-common 10 / dy-tenancy 39 / dy-security **48** / dy-web 51 / dy-audit 32 / dy-config 33 / dy-crypto 29）。
>
> ⚠️ **同时修正一处历史口径错误（本次对账时发现，非本轮引入）**：本节及此前各段一直记
> `dy-app` 的**默认 execution 基线为 148**，与总数 **437 矛盾** —— 三条独立算术均指向 **145**：
> ① 437 − 其余 7 模块(242) = **195**；② S1-5 段自述 `dy-app` 由 151 → **+44**（=151 → 195，
> 是 195 而非该段同时写的 198）；③ 本次实测回溯：341 − 196 = **145**（若为 148 则得 344 ≠ 341）。
> 故正确拆分是 `dy-app 195 = 145(默认) + 50(RLS)`。**历史段落中的 "148/198" 偏大 3，已在本文档订正为 145/195**；
> 因 437 与 644 两个总数本身经多方交叉验证无误，故**只订正拆分项、不改总数**。
>
> **2026-09-25 变更（第二批 · 判定域 S2-7）**：**644 → 707**（**+63**）。全部增量可逐项对账（**实测值**，非估读）：
>
> **① dy-app 默认 execution：+63**（341 → **404**）；**RLS 隔离门禁 execution 一字未变**（**61**）。
>
> | 测试类 | 数量 | 验证点 |
> |---|---|---|
> | `VerdictBranchRoutingTest` | 26 | D1~D5 五分支逐支 + **顺序即语义**（D4 排在效果分支之前）+ 组合出口穷举（4×3×4 = 48 组合**无一路产出退款终止**）+ 阈值零硬编码 + 词汇表差异（`VerdictBranch` 刻意无 `configKey()`） |
> | `VerdictControllerContractTest` | 12 | **F1/F2 可见性不对称**（`@StaffOnly` 只在 F2、不在类级、不在 F1）+ 出站键全在 `VerdictData` 六项内 + 两权限码 + 类级前缀逐字 + 控制器不直连仓储 |
> | `VerdictPersistenceBoundaryTest` | 24 | 端口 append-only（方法名 + 方法数 7）+ SQL 无改写字面量 + INSERT 列清单不含 `updated_at` + `visible_to_customer` 显式绑 FALSE + **V5 表结构缺口断言**（两表无 `disposition` 列 / `risk_flag` 无 CHECK 而同表另三列有）+ **可空枚举解析 fail-closed 真的被执行**（RV-11 逼出，见 §反向验证） |
> | `PermissionCodeRegistrationGateTest` | **3 → 4** | 新增断言 ④：`client` / `therapist` / `store_customer_service` **不得持任何 `verdict:*`**（与既有 `refund:*` 断言同构） |
> | **合计** | **+63** | |
>
> **结果**：`dy-app` 由 402 → **465**（= 默认 **404** + RLS 隔离门禁 **61**）；**其余 7 个模块计数一字未变**
> （dy-common 10 / dy-tenancy 39 / dy-security 48 / dy-web 51 / dy-audit 32 / dy-config 33 / dy-crypto 29）。
> 校验：10+39+48+51+32+33+465+29 = **707** ✅。
>
> ⚠️ **S2-7 的增量里含一笔"补债"而非"新功能"**：`VerdictPersistenceBoundaryTest` 的 6 条**可空枚举解析 fail-closed** 断言，
> 是**反向验证先证明"这条防线没有测试守着"**之后才写的（RV-11 注入后全部判定链测试仍全绿）。配套把三个解析方法
> 由包私有放宽为 `public static`（纯函数，代价为零）。详见 §反向验证「S2-7 判定域的注入」。
>
> ⚠️ **同一类口径错误的第二次命中（本次跨文档对账时发现）**：`开发移交包`（TL;DR 第 2/4 条）与 `开发清单`（基准行 / S2-6 行）
> 一直写「**9 个 Maven 模块**」，与本文档 §一 表格及 `pom.xml <modules>` 的 **8**（7 业务模块 + 独立 `dy-crypto`）**不符**。
> **误源**：`mvn` reactor 输出 **9 行** —— 它把**根聚合 pom 也计作一行**；而历史证据链（239 tests / fix-boot 基线及此后每一轮复跑）
> 从头记的都是 **8 模块** ⇒「9」是**一开始就错**、且**跨越 S1→S2 多轮无人发现**。已逐处订正为 **8 模块**，并在移交包 §四 留下订正说明。
> **教训（与上一段的 148/198 合并为同一条纪律）**：文档里的**总数与拆分项必须能互相推出**，且**每个计数都应能在别处找到一条独立算术复现它** ——
> 只维护其一，错误会长期潜伏，因为它只在"有人做跨文档对账"时才显形。
>
> **2026-09-25 变更（第三批 · `threshold_version` 溯源回放 S2-8 / ADR-11）**：**707 → 745**（**+38**）。全部增量可逐项对账（**实测值**，非估读）：
>
> **① dy-app 默认 execution：+38**（404 → **442**）；**RLS 隔离门禁 execution 一字未变**（**61**）。
>
> | 测试类 | 数量 | 验证点 |
> |---|---|---|
> | `ThresholdVersionFingerprintTest` | 14 | 确定性（同剖面两次取指纹同值）+ 形态（`tv1-` + 20 位十六进制 = 24 字符）+ **段数恒 9 且顺序钉死**（顺序即语义）+ 段级指纹 9 项互不相同 + 口径变则版本号变（4 段逐一验证）+ **段隔离**（只动一段 ⇒ 恰好只有那段漂移）+ **`0.80 ≡ 0.8` 不产生伪漂移** + `matches` 逐字（不接受截断/追加）+ **自描述与段级指纹不泄漏任何口径取值** + 明示排除项已登记（`significant_threshold`/`backtest_gates`/`#30`）+ `driftedSegments` 边界（无从比对时报空、不报假漂移）+ **🛑 `cfg:verdict.*` 命名空间闭合自证**（全集（机械读真值源）= 九段已消费 ∪ `EXCLUDED_NOTE` 点名，**不得有第三个**；并反证被点名的槽位真实存在 —— 批次九补，正是它抓出漏项的 `#9`） |
> | `ThresholdVersionReplayTest` | 16 | 四态各自一例 + **🛑 漂移先于重算**（旧版本号 + 新旧口径恰好同结果 ⇒ 必须报 `DRIFTED` 而非 `REPRODUCED`）+ 早于 S2-8 的行无段级指纹时**如实退化为"定位不到"**（不报空 Map）+ 空版本号 / 依据不可解析 / 非法租户三者均拒 + **缺输入不可判**（不得回落 `true` 后臆造分支）+ **`customerFacing` 恒 false** + 自描述边界（漂移先于重算 / 回放范围 / **算法漂移不被指纹覆盖**）+ 恰好只有一态被认定为"可回放" |
> | `ThresholdVersionPersistenceWiringTest` | 9 | **🛑 落库接线**（反向验证逼出，见 §反向验证 RV-1/RV-2）：不带版本号 ⇒ `cycle_assessment` / `verdict` / `evidence_snapshot` **三处都落服务端算出的那个**；入参携带正确版本号照常落库；**入参不一致 ⇒ 拒 `VERSION_CONFLICT(4001)` 且零写入**（断言在任何计算之前）；空白串 ⇒ `VALIDATION_FAILED(1001)` + 零写入；自描述与落库值同源；快照段级指纹与自描述**逐项 + 逐序**一致；**快照含 `same_origin_comparable`**（回放的确定输入）；快照 `branch` 与落库行同值；换口径 ⇒ 落库版本号随之变 |
> | **合计** | **+39** | |
>
> **结果**：`dy-app` 由 465 → **503**（= 默认 **442** + RLS 隔离门禁 **61**）；**其余 7 个模块计数一字未变**
> （dy-common 10 / dy-tenancy 39 / dy-security 48 / dy-web 51 / dy-audit 32 / dy-config 33 / dy-crypto 29）。
> 校验：10+39+48+51+32+33+503+29 = **745** ✅。
>
> ⚠️ **S2-8 有【三】处"补债"而非新功能，且三处全由反向验证 / 全量回归逼出**（详见 §反向验证「S2-8 溯源回放的注入」）：
> 1. **`ThresholdVersionPersistenceWiringTest` 整个类** —— RV-1/RV-2 注入后，S2-8 全部测试**仍全绿**：
>    把"入参不一致即拒"改成"静默改用权威版本"、把落库值改回 `req.thresholdVersion()`，
>    **没有一条测试发现**。即 S2-8 的核心承诺（"版本号由口径算出，不由调用方给"）当时**没有任何测试守着**。
> 2. **两处 `Map.copyOf` 的迭代顺序缺陷**（`ThresholdVersionFingerprint.segmentFingerprints` 与
>    `ReplayResult.driftedSegments`）—— JDK 的不可变集合用 **per-JVM 随机盐**，迭代顺序**不保证**。
>    它会让**同一份口径**在不同 JVM 启动间写出 **key 顺序不同**的 `evidence_snapshot`（语义同、字节不同），
>    与"同一口径 ⇒ 同一结果"的纪律相悖。已改为 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`（保序 + 不可变）。
> 3. **`ReplayOutcome` / `ReplayResult` 从 `service` 迁入 `domain`** —— 它们是值对象，而 `ReplayResult`（record）
>    引用 service 包内的 `ReplayOutcome`，**触发 `ArchitectureBoundaryTest` 的 R4（DIP）**：
>    「领域模型（实体 / DO / record）不得依赖服务层」。
>    🛑 **它是 `mvn -o clean install` 全量回归才暴露的** —— `ArchitectureBoundaryTest` 住在
>    `com.diaoyuanyun.dy.app`（不在 `derived` 包下），`-Dtest=Threshold*` **根本不会加载它**。
>    **这条是"定向测试全绿 ≠ 可交付"的直接实证**，已作为 RV-15 固化进脚本。
>
> **2026-09-25 变更（第四批 · 契约域 A 补全 S2-9）**：**745 → 795**（**+50**）。全部增量可逐项对账（**实测值**，非估读）：
>
> **① dy-app 默认 execution：+50**（442 → **492**）；**RLS 隔离门禁 execution 一字未变**（**61**）。
>
> | 测试类 | 数量 | 验证点 |
> |---|---|---|
> | `BandVisibilityMatrixTest` | 29 | **S2-9 域 A 领域层（6 @Nested）**：真实 `#43` 归一（五角色键 + 两行为标志，行数 = 5）· 客户 ①②✓③④✗ 逐字 · **客户侧 ③④ 硬锁双向**（放开 ⇒ 抛；把 ① 关掉 ⇒ **也抛** —— 反方向的权利锁）· 结构性 fail-closed（缺角色行 / 缺字段组键 / 未知角色键 / 门店客服缺 `endless` 标志 / 空 JSON 全抛）· 端角色 ↔ config 键**不同命名空间**（`meridian ↔ meridian_therapist`）· admin 四别名（`manager`/`area`/`hq` + 大写）落同一 `admin` 行 · **无端角色不得被赋予档位** · 行级范围解算（token 未声明 ⇒ 回落角色默认 / token 声明**更宽即抛**）· `all ⇒ store_ids=[]` · A2 声明装配（`band_visibility` **恒完整含 false**，与 `store_scope` 的裁剪规则**相反** / `null ⇒ 键缺席而非 false`） |
> | `DomainAEndpointsE2ETest` | 20 | **S2-9 真请求 E2E（3 @Nested）**：`AuthMe` 9（客户 200 且四键齐备 **缺权限码是刻意的** · 客户 `refund_visibility`/`store_scope` **键不存在**而非 null/false · 调理师有 `store_scope` 无 `refund_visibility` · 经络师 `refund_visibility=true`（#40 与 #43 同时生效）· 三管理角色逐角色对齐 `own_store`/`region`/`all` · `hq` 的 `store_ids` **空数组** · **契约只声明 200/401 ⇒ 无 token 必须 401 不是 403** · 验签失败 401 **绝不降级为匿名** · 自描述端点自证）· `Stores` 9（客户 403·2001 显式点名拒 · 三个端角色 200 · 行级过滤 own_store 1 家 / region 2 家 / all 3 家 · 分页 `items` 与 `total` **同源** · 出站字段恰好契约三项 · `page_size` 越界 **400 拒而非夹逼** · 缺省分页回显生效值 · **缺锚点拒，绝不回落全量** · 自描述自证）· `ConfigSource` 2（#43 四可观测产物逐一落地 · **#43 与 #40 两份口径在同一响应里各自生效且不互相干扰**） |
> | `PermissionCodeRegistrationGateTest` | **4 → 5** | 新增断言 ⑤：`identity_domain_keeps_client_out_and_auth_me_unannotated` —— 客户不持 `store:read` + **客户不持任何权限码** + **A2 源码 `stripComments` 后不得含 `@RequirePermission`** + A2/A3 均不得贴 `@StaffOnly` |
> | **合计** | **+50** | |
>
> **结果**：`dy-app` 由 503 → **553**（= 默认 **492** + RLS 隔离门禁 **61**）；**其余 7 个模块计数一字未变**
> （dy-common 10 / dy-tenancy 39 / dy-security 48 / dy-web 51 / dy-audit 32 / dy-config 33 / dy-crypto 29）。
> 校验：10+39+48+51+32+33+553+29 = **795** ✅。
>
> ⚠️ **S2-9 有【三】处"补债"而非新功能，且三处全由真请求 E2E 逼出**（详见 §反向验证「S2-9 契约域 A 的注入」）：
> 1. **跨模块改动必须整仓 `install`，否则依赖方跑的是旧 jar** —— 本轮 `PermissionRegistry` 于 13:24 给 `store:read`
>    加登记，而 `~/.m2` 里的 `dy-security-*.jar` 还是 13:02（早 22 分钟）⇒ **A3 对 therapist 恒 403**。
>    **源码、编译、单测三处都无可疑迹象** —— 这是"语义生效"与"文件已改"之间的边界，已固化为失效模式 17。
> 2. **契约声明的响应集决定错误码**（不是 HTTP 常规）—— A2 `GET /auth/me` 只声明 `200`+`401`（**无 403**），
>    A3 `GET /stores` 只声明 `200`+`403`（**无 401**）。而 `AuthMeService.describeCurrent()` 曾**先 `requireTenant()` 后 `requireCallable()`** ⇒
>    无 token 时租户为空 ⇒ `requireTenant` 抢答 `2003`(403) ⇒ **A2 对匿名报 403，契约违约**。**修法是调换两行顺序**（前置校验的**顺序即语义**）。
> 3. **`List` 传给 varargs 不展开** —— `countByScope` 里 `jdbc.queryForObject(sql, Integer.class, filter.args())` 把 `List` 当**单个**参数
>    ⇒ SQL 占位符不匹配 ⇒ `BadSqlGrammarException` ⇒ **A3 整条 500**。**指纹 = "同源 SQL、只有一侧 500"**：
>    `listByScope` 走 `append(...)` 返回 `Object[]` 正常展开，**只有 count 侧单独踩中**。**修法：`filter.args().toArray()`**，已固化为失效模式 19。
>
> > 三处**全部通过编译、单测、定向测试**，只在"**真 JWT + 真 HTTP + 真库**"下暴露 ⇒ 与 S2-5 的"层间接缝"同族。
> > **并再次实证**：`surefire:test` **不会自动重编译**（改完必先 `mvn -o -q test-compile`）；`-Dtest=A+B+C` **被静默忽略**（须用**逗号**）。
|---|---|---|---|
| `ResultEnvelopeTest` | dy-common | 10 | **契约一致性**（11 个码值/名称/HTTP 逐条比对契约 §2.0）+ 信封四字段 + **`trace_id` 必须 snake_case** + **`trace_id` 为 null 时也必须保留 key** + 失败信封不含 `data` |
| `TenantContextTest` | dy-tenancy | 4 | 上下文设置/清理；`X-Tenant-Id` 不一致抛 403(2003) |
| `TenantContextFilterTest` | dy-tenancy | 6 | **过滤器级行为**：无 token 不得用请求头建上下文（越权回归）；**非法 token 必须 401 且响应体是标准信封**；403 租户不匹配信封；正常建立并出口清理 |
| `JwtVerifierTest` | dy-tenancy | 15 | **攻击面回归**：`alg:none` 绕过 / 算法混淆(RS256 头) / 篡改载荷 / 错误密钥 / 过期 / `nbf` 未生效 / `iss` 不符 / 缺 `tenant_id` / 缺 `exp` / 格式非法 / 密钥缺失 fail-closed / 弱密钥 fail-fast / 吊销命中与未命中 |
| `RlsSessionAspectTest` | dy-tenancy | 5 | **`SET LOCAL` 与业务 SQL 是同一条连接**（连接 identity 断言）；**切面不得另开连接**；非 UUID 拒绝（注入防护）；必须处于事务内。<br>🛑 **本类只直接调 `applyTenantSession()`，无一例断言"切点真的匹配到某处"** —— 故它**证明不了**切面在生产中被触发过（实测生产 `@RlsScoped` 标注数 = **0**）。这条"接线状态"缺口由 `RlsInjectionRealityGateTest` 机械守护，见 §三 ADR-02 与 §五 N-19｜B-9 |
| `RlsInjectionRealityGateTest` | dy-app | 6 | **「文档机制 vs 实际机制」门禁（批次十三第六轮）**：`@RlsScoped` 生产使用数恒为 0 · `inTenant` 载体必须各自 `SET LOCAL`（空壳入口即红）· 载体集合与登记表**双向一致**（漏登记红 + 僵尸登记红；🛑 **载体数 2026-09-30 实测 25 = dy-app 24 + dy-config 1**，历史写法 18/23 均已作废，以 `LEDGER_CARRIERS` 为准）· `applyTenantSession` 在自身定义之外零出现 · **剥离器两级粒度判别力自证**（`STRIP_STRINGS`/`KEEP_STRINGS` 必须给出不同结果，防"用错粒度"造成假绿）· 文档机制的两个类必须存在（删除即需显式动作） |
| `FieldMaskingTest` | dy-security | 3 | **核心**：无权限时敏感字段(`refund`)在序列化 JSON 中 key **完全不存在**；有权限时存在 |
| `IdempotencyTest` | dy-web | 4 | 同键重放返回原响应；同键不同体冲突；不同键独立 |
| `ConfigServiceFailClosedTest` | dy-config | 4 | 配置缺失拒绝(非默认)；非法阈值/命名保存即拒绝 |
| `RlsMigrationScriptTest` | dy-app | 6 | V1 脚本含 `ENABLE`+`FORCE ROW LEVEL SECURITY`、`NULLIF` fail-closed、**`USING` 与 `WITH CHECK` 并存**、**不得含默认租户兜底** |
| `ContractFreezeGateTest` | dy-app | 10 | **S1-1 契约冻结闸门**：契约 ↔ 上游 ↔ config #43 ↔ 原型 D1 四方逐格一致；行集 43/45；感知 S1-7 探测接口；错误码 12 条；**7 条单源扰动反向验证**（契约少行 / 矩阵翻转 / 上游少行 / config 翻转 / 原型翻转 / 禁入放行 / 冲突被消除）+ 畸形 YAML 必须抛错 |
| `ArchitectureBoundaryTest` | dy-app | 7 | 模块依赖方向违规即失败（ArchUnit，R1~R4 四条规则 + 覆盖 `app/scale` / `app/band`） |
| `RlsV5EntityIsolationTest` | dy-app | 11 | **S1-2 V5 的 24 张租户表真库隔离**：双向往返不可见 · 越权直查主键 0 行 · 无/空串上下文 0 行且非 500 · 跨租户 INSERT 被拒且不留残行 · 策略 `ENABLE`+`FORCE`+双 `NULLIF`（`24 == TABLES.size()`）· `doc_template` 表单完整性/第二活跃版本 · `band_sync_log.fail_stage` 与 `band_daily_coverage.gap_reason` 各仅契约 7 值 |
| `OrgLevelTest` | dy-security | 11 | **S1-3 三级组织层级**（契约 §2.1 A1/A2）：角色码登记（大小写双写）· **未登记角色 fail-closed 2003** · `client` 非层级 · `row_level` 映射 · **2001 与 2003 不得混用** · 层级包含关系非对称 |
| `TierAuthorizationE2ETest` | dy-app | 8 | **S1-3 验收②**：真容器 + 真 JWT 的越权回归（`hq` 200 / `area`·`manager` 403）· **换账号复验**（同请求体三账号三种结果）· 未登记角色 2003 · 匿名 403 |
| `CrossStoreSettlementTest` | dy-app | 12 | **S1-3 验收③**：PRD US-8/P1-05 跨店拆分（**≥30% 触发**、按次数占比、**Σ 恰等于总额**、**缺失不得补 0**、结案店必须唯一、异常阈值 2/>3 店） |
| `RlsRejectionAuditTrailGateTest` | dy-app | 5 | **S1-3 验收④（真库）**：跨租户请求被拒(2003) → `audit_log` **恰好一条**留痕 · 归属为**全 0 UUID** · `actor=anonymous` · payload 不含凭据 · **链仍自洽** · 存储层直查仍零行（留痕不等于隔离）· **审计写入必须在事务中** |
| `DerivedProfileSourceTest` | dy-app | 10 | **S1-5 口径外置**：config `#4/#5/#6/#7/#33/#45` 六槽可达可解析 · 归一值逐项比对声明值（含 **Δ 边界 `0–2·3·≥1`**、**指数 `1·0.25·0.25·0.50`**）· **任一槽空白即 5001（六例）** · 跨源一致 · **`toString()` 只报段长不报取值** |
| `DerivedEngineContractTest` | dy-app | 24 | **S1-5 验收①**：引擎源码**不得出现门槛/权重/Δ 边界/指数字面量**（期望集由**反射读结构常量**推导，非手写清单）· A2 **结构性拒入** · **AS_refund 用 config 权重** · 门槛**闭区间** · 样本护栏 · 缺失值两策略 · **`Δ=0` 归稳定且不落穿 5001** · **每个整数 Δ 恰落一档** · **引擎绝不自动产 E5**（遍历 0–16）· m(Δ) **八点校验** · 加权几何（可区分算术平均）· `n` 不适用时**指数重归一** |
| `DerivedFieldsTest` | dy-security | 21 | **S1-5 验收②（规则层）**：登记表与契约字段名**逐字一致不多不少** · **"归一+精确相等"≠"包含即命中"**（`as_value_ops` 反例）· 别名覆盖 camelCase 与词表拼写 · ③**不并进**④ · **匿名按客户 fail-closed** · 被拒字段**去重保序保留原始拼写** · **403 必带 `denied_fields`** · **空/含 null 的 `denied_fields` 构造期即拒** |
| `DerivedRequestScannerTest` | dy-security | 13 | **S1-5 验收②（解算层）**：**契约 §2.1 B4 逐字**（`include=verdict` → `["effect_verdict","as_value"]`，回显**字段名非组别名**）· 三种点名写法（参数名/值分词 6 分隔符/**体键名递归含数组**）· 组别名表**键归一、值必属客户不可见** · 非法 JSON **刻意放过**（属 1001）· **排序遍历保证可复现** |
| `DerivedVisibilityE2ETest` | dy-app | 10 | **S1-5 验收②+③（真容器 + 真 JWT）**：客户调 E4 → **403+2001+回显五项** · **反向自证：经络师 → 200 且真返回派生字段** · **反向注入探针**（故意下发 ③④ + 嵌套 + 数组）→ 客户端响应**整体不存在**，而 `as_value_ops`/①② 字段**完好存活**（防误拦）· 同探针给 staff **原样保留** · 匿名按客户 · 换账号复验两侧响应**必须不同** |
| `TestLogOutputIsolationTest` | dy-app | 2 | **测试运行不得污染源码树**：主断言 = 本次运行的探针标记**不得出现在源码树的任何日志文件里**（运行内证据，不受历史残留影响）；正向对照 = 日志确实写进 `target/test-logs/` 且含该标记（防「把日志全关掉」式假绿）；前提断言 = surefire 工作目录必须等于模块 basedir（把静默假绿转成显式红） |
| `BandVisibilityMatrixTest` | dy-app | 29 | **S2-9 域 A 领域层（6 @Nested）**：`#43` 五角色行 + 两行为标志归一 · 客户 ①②✓③④✗ · **客户 ③④ 硬锁双向**（放开 ⇒ 抛；把 ① 关掉 ⇒ **也抛**）· 结构性 fail-closed 五例 · 端角色 ↔ config 键**不同命名空间**（`meridian ↔ meridian_therapist`）· admin 四别名落同一行 · **无端角色不得被赋予档位** · token 声明**更宽即抛** · `all ⇒ store_ids=[]` · **`band_visibility` 恒完整含 false，与 `store_scope` 裁剪规则相反** |
| `DomainAEndpointsE2ETest` | dy-app | 20 | **S2-9 真请求 E2E（3 @Nested）**：A2 九条（客户四键齐备 · **两个角色级键【不存在】而非 null/false** · 三管理角色逐角色 `row_level` · `hq` 空数组 · **无 token 401 不是 403** · 验签失败 401 不降级 · 自描述自证）+ A3 九条（客户 403·2001 · 行级过滤 1/2/3 家 · **`total` 与 `items` 同源** · 出站字段恰三项 · **越界 400 拒非夹逼** · **缺锚点拒非回落全量** · 自描述自证）+ 口径来源 2 条（**#43 与 #40 独立生效互不干扰**） |
| `DeviceProvisioningGateTest` | dy-app | 9 | **B-11 设备建档通路（V18 `register_device`，真库）**：真因 = `device` 表**生产代码零写入方**（对照实验：建档前同一插入 `23503` / 建档后成功）· **审计写失败 ⇒ 整笔开通回滚** · 两态 `CREATED`/`ALREADY_EXISTS` 完备且方向正确 · 跨租户 `store` 引用被拒且理由正确 · **跨租户 `device_id` 撞号必须 `RAISE` 而非返回 `ALREADY_EXISTS`**（`ON CONFLICT` 冲突但**看不见** ⇒ 不可静默吞）· **retired 行仍占主键** · 幂等重放也留审计且 payload 公开安全 · **已持有他租户上下文 ⇒ 拒绝执行** · 通路为**无 HTTP 映射**的组件 + 原语在位 |
| `ScaleProvisioningGateTest` | dy-app | 9 | **B-12 量表建档通路（V19 `register_scale`，真库）**：与 B-11 同构九条（真因 / 回滚 / 两态 / 跨租户引用 / 跨租户撞号 `RAISE` / retired 占主键 / 幂等留痕 / 上下文互斥 / 无 HTTP 映射）。**V19 特有**：`scale_version` 是**死维度** —— 单列主键下"版本递增"在库层无法表达 ⇒ **版本不同必须 `RAISE`「版本【不可覆盖】」** |
| `CaseArchiveGateTest` | dy-app | 11 | **B-13 结案归档通路（V20 `register_case_archive` / `latest_archive_of`，真库）**：真因 = `case_archive` 表**生产代码零写入方**（对照实验）· 归档两态 `CREATED`/`ALREADY_EXISTS` 完备 · 5 项硬门禁缺项**被拒且消息含缺项名** · **🛑 手环项缺席与 `false` 均【不阻断】**（合规红线 P0-25 / README §5.3③；判据⑤行为 + 判据⑦(e) 静态）· **跨租户 `archive_id` 撞号必须 `RAISE` 而非 `ALREADY_EXISTS`**（归档是链条**末端** ⇒ 静默合并会让工单"说已归档而档案不存在"，只在举证时暴露）· 跨租户客户引用被**库层复合外键拒(23503)** · 未登记键 / 签名空串被拒 · `latest_archive_of` 三段（NULL / 命中 / tie-breaker 全序）· 探针零残留 |
| `AgreementGateTest` | dy-app | 12 | **A-1 协议书离线签署写入方（V21 `register_agreement` / `latest_agreement_of`，真库）**：真因 = `agreement` 表**生产代码零写入方** ⇒ **PRD G1 分子恒为 0**（对照实验）· 两态 `CREATED`/`ALREADY_EXISTS` 完备 · **plan_version 门禁**（方案存在但那一版不存在 ⇒ 判为不存在；`plan_version=0` 以**业务错误**拒、**不是** 23514）· **模板指针成对 + 版本相符**（`doc_template_version` 必须等于该行的 `version`，不是"存在某版叫这名字"）· **跨租户 `agreement_id` 撞号必须 `RAISE`**（agreement 是链条**中段** ⇒ 错状态会被 `CustomerGateGuard` 的 `PLAN_APPROVED → AGREEMENT_SIGNED` **即时消费**，比 B-13 的末端更危险）· **`rendered_hash` 必须由 `renderSnapshot` 复算一致**（快照可举证）· 哈希只认小写 hex 且写入前 `lower(...)` · **`latest_agreement_of` 按 `signed_at` 全序**（离线协议可"先签、后补录"；按 `created_at` 会把"最新"变成"最新录入"，而 G1 口径是**签署时刻**）· 未登记键 / 签名空串被拒 · 探针零残留 · 🛑 **通路为无 HTTP 映射的组件 + 原语在位，`agreement` 包内零 HTTP 注解**（H1 硬边界，由 122 的 C12 守） |
| `BandRefetchGateTest` | dy-app | 12 | **A-3 手环补拉通路（V22 `register_sync_probe` / `register_daily_coverage`，真库）**：真因 = 两张表**生产代码零写入方**（`NOT_PROVISIONED` 账最后 2 张，对照实验）· 探针两态 `CREATED`/`REPLAYED` 完备且**重放不改写记录** · 覆盖两态完备且**第二次必须真的刷新** · **🆕 ③-0 首次覆盖必须记【独立 action】`BAND_COVERAGE_REGISTERED`（不是 `REFRESHED`）** · **审计写失败 ⇒ 两条通路都回滚** · **Core gate（§2.8.7⑤）**：`not_worn` × `is_wear IN (-1,255)`（技术性缺失）**不得共存** · `coverage_flag=true` × `gap_reason` 非空 **不得共存** · **跨租户撞号由两条原语【区别】处理**（probe 单列全局主键 ⇒ 必然发生）· **已持有他租户上下文 ⇒ 两条通路均拒绝执行** · 跨租户引用被拒且**理由正确** · **覆盖行的 owner/归属客户不得被改写**（V22 (7) 把 `coverage_id` 列进"不更新的列"）· **该通路【刻意不提供】改写或删除入口** · **审计 `target_id` 必须指向库中真实存在的 id**（本仓**第 47 条系统性缺陷**的守门） |
| `RlsCoverageGateTest` | dy-app | **6** | **RLS 覆盖载体登记 · 三源交叉 + 🆕 登记咬合（A-4，2026-09-30）**：①~③ 原有四判据（迁移文本解析 / 登记表 / 真库 `information_schema` 三源一致 · 每张带 `tenant_id` 的表既登记又存在可 `Class.forName` 加载且 `@Test` ≥ 5 的隔离测试）。🆕 **判据④ `the_registered_test_class_must_actually_mention_the_table_it_covers`**：登记表的**值**（测试类 FQN）也要咬合 —— FQN 反查源码 ⇒ **剥 Java 注释** ⇒ **词边界**匹配表名。🆕 **元层判据 `the_registration_binding_matcher_has_real_discriminating_power`**：① 词边界判别力（`refund` ≠ `refund_receipt`）② 剥注释必要性（javadoc 里的表名不得算"提到"）③ 字符串字面量必须**保留**（不得把代码里的真引用剥掉）。🛑 **缺口来自实测**：原判据只验「类可加载 且 `@Test` ≥ 5」⇒ 把新表挂到任一既有 ≥5 `@Test` 的类名下，①②③ **全部照常通过**而该表行为断言为零（"把新表挂到邻居名下"）。判据形态**实测选型**（对 38 条登记：F1 双引号字面量误伤 **1/38** · F2 单引号 SQL 误伤 **28/38** · **F3 裸标识词误伤 0/38** ⇒ 采用 F3；证据 `_work/a4_probe_registration_binding.py`）。反向验证 `verification/124`（5 组注入）**7/7 PASS** |
| `AuditFillAspectWiringGateTest` | dy-audit | 4 | **N-17「死代码接线状态」门禁（批次十三）**：把 `AuditFillAspect` 的**接线事实**变成构建期可断言的登记 —— **切点定义数 = 0** · **`AuditableEntity` 子类数 = 0** · **`created_by` 显式引用数 ≥ 50**（证明"字段有值"的另一条路径仍在）。🛑 **牙齿**：若有人真去接线（新增 `save(..)` 或让实体继承 `AuditableEntity`）⇒ **立即变红并点名新匹配**，迫使接线成为**显式动作**（连同文档登记一起改）。🛑 本类**不回答**"该删还是该启用"（属架构 owner 裁定，见 §五 N-17）；🛑 门禁初版用 `src.contains("@Aspect")` 断言**原始文本** ⇒ 把注解**注释掉**后字面量仍在 ⇒ 假绿，已修为**先剥注释再断言**（由 `verification/112_*` 抓出） |

> 上表仅列出代表性门禁测试，**非全部**。**当前（批次二十一）**全量 **1199** 个测试按模块拆分为
> （权威口径见本节约首行；⚠️ 下附的 1185 / 1173 均为**历史快照**，保留以便对照迁移历史，勿再引用为现值）：
> dy-common **10** · dy-tenancy **39** · dy-security **48** · dy-web **58** · dy-audit **37** ·
> dy-crypto **29** · dy-config **37**（= 默认 8 + `config-truth-source-gate` 29）·
> dy-app **941**（= 默认 **831** + `rls-isolation-gate` **110**）⇒ **合计 1199**。
> 🛑 **历史快照（批次十九 A-1 时点，勿再引用）**：1185 = dy-common 10 · dy-tenancy 39 · dy-security 48 · dy-web 58 · dy-audit 37 · dy-crypto 29 · dy-config 37 · dy-app 927（默认 819 + RLS 门禁 108）。
> ⚠️ **该 1185 快照本身也被"首行滞后"污染过**：批次二十 A-4 的 +2（`RlsCoverageGateTest` 4→6）未回改，故 1185 **并非**批次二十起点；批次二十一起点是 **1187**。
> 🛑 **历史快照（批次十七时点，勿再引用）**：1173 = dy-common 10 · dy-tenancy 39 · dy-security 48 · dy-web 58 · dy-audit 37 · dy-crypto 29 · dy-config 37 · dy-app 915（默认 807 + RLS 门禁 108）。
> 🛑 **历史快照（S2-10 时点，勿再引用）**：795 = dy-common 10 · dy-tenancy 39 · dy-security 48 · dy-web 51 · dy-audit 32 · dy-config 33（默认 4 + 真库 IT 29）· dy-app 553（默认 492 + RLS 隔离门禁 61）· dy-crypto 29。
> ⚠️ **另有 49 条不在 surefire 计数里的门禁证人断言**（纯 stdlib Python，不经 `mvn test`）：
> `compliance/tests/compliance_injection_test.py`（ADR-12 三扫描面注入，**12 断言**，含 T11 残留守卫）·
> `compliance/tests/zero_derived_gate_test.py`（S1-6 结构门禁证人，**13 断言**，含 W1b camelCase / W9 残留守卫）·
> `compliance/tests/contract_conformance_gate_test.py`（S1-8 契约回归证人，**18 断言**，含 W10 残留守卫）·
> `compliance/tests/owner_readiness_test.py`（业主就绪，**6 断言**）= **49**；
> 另 `compliance/tests/build-gate-injection.py` 为端到端 `mvn` 门禁（不计断言），
> **以及 S2/S2-7/S2-8/S2-9 的四套注入脚本**（`refund-s2-*.py` 6 条 + `verdict-s2-7-reverse-verification.py` **16 条** + `threshold-s2-8-reverse-verification.py` **15 条** + `domain-a-s2-9-reverse-verification.py` **14 条**）同样是"不计入 surefire" 的验收证据。
> 它们由 `mvn validate` 阶段或 CI 直接调用，
> **故"795"不是全部验收断言数**——引用总数时须分开陈述，勿把两类混成一个数字。
>
> ### S1-6 客户端零派生结构门禁（**查"主张"而非"词汇"**）
>
> **为什么不并进 ADR-12**：ADR-12 三面扫描问的是"违禁词有没有进客户端包" —— 它**问不出**"包自己的权限白名单是否许可了一件被契约禁止的事"。**权限清单不是词，是主张；主张可以对着契约查，而在 S1-6 之前没有任何东西在查**。故本门禁与 ADR-12 **互补而非重复**，四面为：
>
> | 面 | 断言 | 违规码 |
> |---|---|---|
> | FACE 1 `no-zero-derived-names` | 客户端包内不得出现 ③④ 派生字段名（**禁词集机械派生自可见性矩阵**，非手写清单） | `derived-name-in-package` |
> | FACE 2 `allowlist-within-contract` | `client-package/api/clientPaths.js` 白名单**必须是契约 `x-callable-roles` 含 `client` 路径的子集**（单向） | `client-not-permitted` / `path-not-in-contract` |
> | FACE 3 `no-client-forbidden-paths` | 包内不得出现契约标 `x-client-forbidden: true` 的路径（退款域 5 路径） | `client-forbidden-path-in-package` |
> | FACE 4 `gate-self-check` | 输入非空 · `TEXT_SUFFIXES` 与 `scan_compliance.py` 互为一致（**拒绝为一个空集合报 PASS**） | exit 2 |
>
> **方向刻意选子集（单向）**：包可以用得比许可的**少**，但不能够到一个被拒的。首版曾实现**等值（双向）**，实测**每次都因"许可但未使用"的端点报红** —— 那不是缺陷，**会对正确内容叫狼的门禁一定会被关掉**。故反向差额（`permitted_but_undeclared`）**只报告不阻断**。
>
> **退出码 4 值且"内部错误"独立于"违规"**：`0 pass / 1 violation / 2 misconfigured / 3 internal`。第 4 个码是**必须的**——`python` 的 traceback 退出码**恰好也是 1**，与"违规"同码；若不加区分，**门禁自己崩溃会被记成"成功检出"**（这正是 RV 假绿的另一种形态）。证人套件因此立硬判据 `check_caught()`：必须同时满足 exit=1、无 internal-error 横幅、无 traceback、有 VIOLATIONS 段、verdict 行 violation 数 > 0。
>
> **双根定位**：`--repo-root`（build 根，含 `client-package/` + `compliance/`）与 `--docs-root`（docs 根，含 `contract/`）**是两个不同的根**（契约在 `skeleton/` 的上一级，与 PRD 同级）；`--docs-root` 缺省时向上至多 5 级探测，**找不到即 exit 2 拒绝执行**（拒绝去验错误的仓库）。

> ### S1-8 契约一致性门禁（**查"事实"而非"主张"**）
>
> **第三个问题，不是第三个扫描面**：ADR-12 问"违禁词进没进包"；S1-6 问"包自报可调的路径契约是否真允许"；本门禁问**"包里每一条复述的契约事实是否属实"** —— 引注的行号与方法、错误码、枚举取值、字段名，以及可见性矩阵的字段清单。三者互补，任一单独存在都留下盲区。
>
> **为什么必须单独存在（这是"同源失效"的第三例）**：前两条门禁的**判据本身**是契约事实的**手工重述**。S1-6 的白名单曾把 `/customers/{id}/visits` 引注为 **D1**，而 D1 是 POST、`x-callable-roles` 不含 `client`；**条目是对的、引注是错的**，而 S1-6 按**路径**聚合角色，结构性看不见。同一个失效模式在本项目出现过三次：①白名单（S1-6 之前）②S1-6 词表的补词**接种自同一份漂移清单**③可见性矩阵 ④ 组。**门禁与实现同源时，两者可以一起错**。
>
> 五面：
>
> | 面 | 断言 | 违规码 |
> |---|---|---|
> | FACE 1 `path-citations-are-true` | 13 条白名单引注的 **row+method 必须在该路径上且 `x-callable-roles` 含 `client`** | `citation-missing` / `row-not-in-contract` / `row-not-on-this-path` / `row-excludes-client` |
> | FACE 2 `error-copy-codes-are-real` | 客户端错误文案的每个码都必须在契约 12 个错误码之内 | `code-not-in-contract` |
> | FACE 3 `client-enum-namespace-isolated` | 客户端 `client_sync_state` **不得含内部 `gap_reason` 独有取值**（泄漏的是**语义**不是词） | `internal-value-in-client-namespace` / `value-not-in-contract-enum` |
> | FACE 4 `no-client-invisible-field-names` | 契约标 `x-visible-to` **不含 `client`** 的 **19** 个字段名不得出现在包内（**S1-6 FACE 1 的超集**：S1-6 扫 6 个，本面扫 19 个） | `client-invisible-field-name` |
> | FACE 5 `matrix-field-lists-agree` | 可见性矩阵 ③④ 字段清单与契约 `x-field-group` **双向一致**；①② 为**概念性命名**，只要求"接地于契约"（属性或枚举取值） | `phantom-field` / `matrix-omits-contract-field` / `group-absent-in-contract` |
> | FACE 6 `gate-self-check` | 输入非空 · `TEXT_SUFFIXES` 与 `scan_compliance.py` 一致 · 被验产物存在 | exit 2 |
>
> **FACE 5 为什么必须双向（这是本轮最重要的判据修正）**：首版把"契约有、矩阵缺"降级为**信息**，理由是"矩阵可以比契约粗"。**该理由在安全轴上是反的** —— 矩阵是 **S1-6 门禁的输入**，S1-6 用这些清单构建禁字段集。矩阵漏列 `as_value`，S1-6 不会报"检查变窄"，它**继续报 PASS 而少守护一个字段**，下游无人能察觉。**多余字段是过度守护（安全），漏列字段是静默解除守护（fail-open）**。故 ③④ 双向严格：矩阵自称**派生导出**，两侧本是同一事实的两种读法，任何差异都是缺陷而非可接受的粗细。
>
> **为什么要真 YAML 解析器**：同一份契约文件上，两个手工正则探针先后给出 **42** 与 **2** 两个字段普查数（YAML 块布局敏感）。**自相矛盾的探针不能做门禁基础**。缺 PyYAML 即 **exit 2**，**不降级为正则**——降级会重新引入那个自相矛盾的解析，却仍打印 PASS。
>
> **camelCase 盲点（本门禁与 S1-6 同时修复的真实漏洞）**：契约字段是 snake_case，而客户端包是 JavaScript —— 它承载这些名字的**自然写法就是 camelCase**。实测（修复前）：`refund_visibility` 被抓、`refund-visibility` 被抓、**`refundVisibility` 完全漏过、门禁报 PASS**。即门禁**恰好在最可能被使用的方言上失明**。S1-6 修前同样失明（`effectVerdict` 漏过），已一并修复并由其证人 **W1b** 钉住。ADR-12 扫描器**本就**拆 camelCase（`_CAMEL_RE`），故修复方向是"向既有正确语义对齐"，不是新发明。

### 关于 RLS 的两层验证（重要）

初版 README 曾写"RLS 为 PG 专属、H2 不支持，故仅以脚本文本断言验证"。**该理由不成立**：
`SET LOCAL` 走错连接这个 bug **与 RLS 无关**，用任何 JDBC 都能测。现分两层：

1. **策略层**：`RlsMigrationScriptTest` 断言迁移脚本文本（含 `ENABLE`/`FORCE`/`NULLIF`/`WITH CHECK`、禁默认兜底）。
   文本断言适用于"缺失时没有运行时信号"的要素（如 `WITH CHECK`、`FORCE`）。
2. **执行层**：`RlsSessionAspectTest` 用 `TransactionSynchronizationManager` 把一条**被监视的连接**绑定为
   当前事务资源，断言切面确实在这条连接上执行 `SET LOCAL`；并让 `DataSource.getConnection()` 抛异常，
   以证明切面**没有另开连接**。

### 反向验证（证明测试真能抓 bug）

为排除"自证式测试"（实现错→断言也错→一起绿），共做了 **5 次**注入式回归验证：

| 注入的缺陷 | 结果 |
|---|---|
| 把 `5001` 的 HTTP 从 `422` 改回 `502`（初版错误） | `error_codes_match_contract_exactly` 与 `five_xxx_...` **立即失败**，报 `expected: <422> but was: <502>` |
| 让无 token 请求用 `X-Tenant-Id` 兜底建立上下文（初版漏洞） | `no_token_must_not_establish_tenant_context_from_header` **立即失败**，报 `expected: <null> but was: <attacker-chosen-tenant>` |
| 去掉 JWT 的 `alg` 等值判断（模拟 `alg:none` 绕过） | `alg_confusion_rs256_header_must_be_rejected` **立即失败**，报 `Expected JwtValidationException to be thrown, but nothing was thrown` |
| 去掉 JWT 签名比较（模拟完全不验签） | `tampered_payload_must_be_rejected` + `token_signed_with_wrong_secret_must_be_rejected` **同时失败** |
| 去掉 JWT `exp` 校验（模拟永久有效 token） | `expired_token_must_be_rejected` **立即失败** |

**5 次注入均已还原，最终状态 `BUILD SUCCESS` / 239 测试全绿（此后随 V3 落地增至 242，再随 S1-4/S1-7 增至 302，随 S1-1 增至 312，随 **S1-2 · V5 落地** 增至 **323**，随 **S1-3 · 多租户底座** 增至 **359**，随 **S1-5 · 服务端唯一权威** 增至 **437**）。**

#### S1-3 追加的 4 次注入（同一条纪律：只看绿是自证，必须看红）

| # | 注入的缺陷 | 期望失败点 | 实际断言原文（节选） | 结果 |
|---|---|---|---|---|
| RV-A | **停用** `dy-app/src/test/resources/logback-test.xml`（改名为 `.rv-disabled`，并 `clean` 清掉 `target/test-classes/` 残留副本） | 日志落点门禁的主断言 | `dy-app 测试【不得】把日志写进源码树。本次运行注入的标记 log-isolation-probe-27070435941000 出现在下列源码树文件中: [...\skeleton\dy-app\logs\dy-app.log]` | ✅ 变红（且源码树实测被写入 1 条标记） |
| RV-B | **去掉审计桥的事务包装**（`appendInOwnTx` 直接调 `svc.append`）—— 模拟「忘了开事务」 | 验收④ 的四条留痕断言 | `一次被拒的跨租户请求必须在 audit_log 留下【恰好一条】记录 ==> expected: <1> but was: <0>`（同类另 3 条：`expected: <2> but was: <0>` 等） | ✅ **5 中 4 变红** |
| RV-C | **删掉** `SettlementController#preview` 的 `@RequireOrgLevel(min = HEADQUARTERS)` | 三级权限门禁 | `区域层不得访问总部专属的业绩拆分；实际: 200 OK`（另 3 条同类；响应体里**完整泄漏**了拆分明细） | ✅ **8 中 4 变红** |
| RV-D | 在真理库给 `audit_log` **加上 RLS 策略**（临时 `ENABLE`+`FORCE`+`CREATE POLICY`） | 验收④ 的四条留痕断言 | 同 RV-B 的四条 `expected: <1> but was: <0>` | ✅ **5 中 4 变红** |

四条各自证明了不同的事，缺一条都会留下盲区：

- **RV-A** 证明日志门禁**有牙齿**——它不是"目录不存在"这种会被历史残留左右的状态断言，
  而是能精确指出"本次运行的标记落在了哪个源码树文件里"。
  > ⚠️ **本次踩到的坑（如实登记）**：第一次做 RV-A 时只改了源码、没 `clean`，
  > 于是 `target/test-classes/logback-test.xml`（Sep 22 的旧副本）仍在 classpath 上生效，
  > 探针照旧落进 `target/test-logs/`——**注入没生效，却差点被读成"门禁没牙齿"**。
  > 教训：**反向验证自身也会因构建残留而失效**，注入后必须确认"注入真的生效了"
  > （本次靠全树搜标记的落点才发现）。这条已写进本表，避免下一个人重复踩。
- **RV-B** 证明验收④**真的在守护留痕**：去掉事务后 403 照旧、日志里只有一行 WARN、
  而**审计表一行都没有**——这正是"门禁绿 + 合规证据缺失"的最危险形态。
- **RV-C** 证明三级权限是**真请求真容器**在判定，而不是纯逻辑单测；
  且红态下响应体把完整拆分明细给了门店/区域/匿名，直观展示了 PRD M5 被违反的样子。
- **RV-D** 是**最重要的一条**：它证明验收④ 的证据**是有条件的**——
  审计留痕之所以成立，是因为 `audit_log` 被**刻意豁免 RLS**（全局单链哈希，见 T-09）。
  一旦给它加策略，"写一条留痕"立刻失败。**这条反向验证把一条隐含前提变成了可执行事实**：
  将来若有人"顺手"把 `audit_log` 纳入 RLS 覆盖，验收④ 会立刻红，而不会静默失效。

**4 次注入均已还原**（`logback-test.xml` 复位、审计桥恢复 `REQUIRES_NEW`、层级注解复位、
`audit_log` 的 RLS 归零至 `f|f`），并按 `DY_TESTDB_RESET=1` **在新库上重跑全反应堆** → `BUILD SUCCESS` / 359 测试全绿。
#### S1-5 追加的 4 次注入（RV-A~D，同一条纪律）

| # | 注入的缺陷 | 期望失败点 | 结果 |
|---|---|---|---|
| RV-A | **注销入站派生请求拦截器**（模拟"客户能点名索取派生字段"） | 验收② 入站 403 断言 | ✅ 2 变红 |
| RV-B | **出口兜底退回"只摘 ④字段组"**（模拟 `isDerivedField` 而非 `isClientForbiddenField`） | 验收③ 出口不下发断言 | ✅ 1 变红（`gap_reason` 出现在响应体） |
| RV-C | **字段匹配退化成"包含即命中"** | `as_value_ops` 反例断言 | ✅ 1 变红（`as_value_ops` 被误判为派生字段） |
| RV-D | **注销契约角色码**（模拟 `PermissionRegistry` 未登记小写码） | `DerivedVisibilityE2ETest` 反向自证（经络师 → 200） | ✅ 2 变红（staff 侧 200 变 403） |

> ⚠️ **RV-D 首轮"假绿"（已记录进 `reverse-verification-gate` skill 失效模式 6）**：注入脚本产生了**非法 Java 语法**，
> 而校验命令用了 `mvn -q` + 窄 `grep` ⇒ **编译错误被过滤掉**，且 Maven 对已编译类**跳过重编译** ⇒
> "绿"来自**上一次的陈旧 class**。结论：**反向验证的校验命令必须能显式暴露编译失败**（禁 `-q` + 窄 grep，
> 必须 `set -o pipefail` 并落全量日志）；判绿必须依据 `Tests run: N (N>0)`，**不作第三种"看起来绿了"**。

#### S1-6 追加的注入（结构门禁 —— 查"主张"的面）

除把**修复前的原始 `clientPaths.js` 整体放回**（门禁报出全部 6 条真实缺陷，ADR-12 同时报 PASS）外，
另有 7 条**针对性注入**，全部由证人套件 `zero_derived_gate_test.py` 抓出（**13 断言**）：

| # | 注入的缺陷 | 期望失败点 | 结果 |
|---|---|---|---|
| W1 | 包内植入**派生字段名** | FACE 1 | ✅ 1 红 |
| W2 | 白名单植入**契约拒给客户端的端点**（E4 正确形状 `/customers/{id}/band/derived`） | FACE 2 `client-not-permitted` | ✅ 1 红 |
| W3 | 白名单植入**臆造路径** | FACE 2 `path-not-in-contract` | ✅ 1 红 |
| W3b | 白名单植入**原始缺陷的精确形态** `/api/v1/band/derived`（漏 `/customers/{id}` 段） | FACE 2 `path-not-in-contract` | ✅ 1 红 |
| W4 | 包内植入**契约禁入路径**（退款域） | FACE 3 | ✅ 1 红 |
| W5/W6/W7 | 真相源不可解析 / **真相源字段清单清空**（包体完全合规）/ docs 根无契约 | FACE 4 / 配置错误 | ✅ **exit 2**（拒绝为**空集合**报 PASS、拒绝验**错误的仓库**） |
| W0/W8 | 基线必须 PASS / **门禁自检必须诚实** | 反向对照 | ✅ 基线绿、自检红 |

> **一次真实的自伤（已修，且由证人钉住）**：FACE 2 里一个 `NameError` 使脚本抛异常，
> 而 **Python traceback 的退出码恰好也是 1**（与"违规"同码）⇒ 首版证人只断言"非零即抓住"，
> **把门禁自己的坏掉记成了"成功检出"**。修法：内部错误独立退出码 **3**，并立硬判据
> `check_caught()` —— 必须同时满足 exit=1、无 internal-error 横幅、无 traceback、有 VIOLATIONS 段、
> verdict 行 violation 数 > 0。

**S1-5 的 4 次注入 + S1-6 的 7 次注入均已还原**，最终重跑全反应堆 → `BUILD SUCCESS` / **437 测试全绿**，
两条结构门禁（ADR-12 + S1-6）均 `PASS`（后续随 S2 退款域增至 **644**）。

#### S1-8 追加的注入（契约一致性门禁 —— 查"事实"的面）

由证人套件 `contract_conformance_gate_test.py` 抓出（**18 断言 / 65 条 ok**）。每条都**破坏门禁的一处保护**：在产物里、在真相源里、或在门禁自己的运行时里。

| # | 注入的缺陷 | 期望失败点 | 结果 |
|---|---|---|---|
| W0 | 基线（对照） | 真实仓库必须绿 | ✅ exit 0 |
| **W1** | **把 `visits` 引注从 D2 改回 D1**（**门禁因之而生的真实缺陷**，逐字还原） | FACE 1 `row-excludes-client`，且**不得**报 `row-not-in-contract`（行**存在**，错的是引注） | ✅ 1 红，且报告同时给出"逐方法角色 + 客户端可调的兄弟操作" |
| W1b | 引注指向契约中不存在的行 | FACE 1 `row-not-in-contract` | ✅ 1 红 |
| W1c | 引注指向**别的路径**上的真实行 | FACE 1 `row-not-on-this-path` | ✅ 1 红 |
| W1d | **删掉引注本身** | FACE 1 `citation-missing`（**查，而非跳过** —— 引注**就是**那条主张） | ✅ 1 红 |
| W2 | 错误文案里写一个服务端**不可能发**的码 | FACE 2 `code-not-in-contract` | ✅ 1 红 |
| W3 | 客户端枚举里植入**内部枚举独有**取值（`not_worn`） | FACE 3 `internal-value-in-client-namespace` | ✅ 1 红 |
| W3b | 枚举值不在**任何**枚举里 | FACE 3 `value-not-in-contract-enum` | ✅ 1 红 |
| **W4** | 包内以 camelCase 植入 `refundVisibility`（契约 `AuthMeData` 字段，`x-visible-to` 不含 client） | FACE 4；**并同跑 S1-6 门禁证明其对改名沉默**（把"本面是超集"从设计陈述变为**测量事实**） | ✅ 1 红；camelCase 修复前该注入**漏过**（这正是盲点） |
| W5 | 把 ④ 组的**漂移字段名**放回（`adherence_dimension_score` / `a3`，**任何 schema 中 0 次出现**） | FACE 5 `phantom-field` | ✅ 1 红 |
| **W5b** | **清空矩阵 ③④ 字段清单**（产物完全合规） | 必须报 `matrix-omits-contract-field`，**不得**"对空清单 PASS" | ✅ 1 红（修复前**报 exit 0** —— 即 fail-open） |
| W6 | 契约 YAML 不可解析 | **exit 2**（而非 3），绝不静默 PASS | ✅ exit 2（修复前误路由为 **3**） |
| W6b | 矩阵 JSON 格式坏 | **exit 2**，且**不得**报成 internal error（"输入坏"与"门禁坏"须异码） | ✅ exit 2 |
| W7 | docs 根无 `contract/` | **exit 2**，拒绝去验错误的树 | ✅ exit 2 |
| **W8** | **用 shadow `yaml.py` 注入 `ImportError`**（伪装 PyYAML 缺失） | **exit 2**，不得降级为正则、不得崩溃 | ✅ exit 2（本门禁最要紧的一条：唯一用真解析器的门禁，最诱人的"修法"就是退回正则） |
| W9 | 自检诚实性 | 审计须报出**实际用过**的输入量，使读者能区分"真扫过"与"空扫" | ✅ 五面输入量均非零 |
| **W10** | **识别器退回旧前缀逻辑**（反向验证：还原"看不见 `__scope_probe_*`"的缺陷态） | 残留守卫必须**认全**各套件真实探针名（含 ADR-12 的 `__scope_probe_client.js`）且不误报普通名 | ✅ 变红并**精确指名**该探针；还原后即绿 |

> **门禁自身被这轮证人抓出的两个真 bug（已修）**：
> ① **W6 误路由** —— `yaml.safe_load` 抛的 `yaml.YAMLError` **不是 `ValueError` 的子类**，只捕后者让 `ParserError` 逃逸为 **exit 3（内部错）**，恰与该分支存在的目的（"契约不解析"＝配置错＝2）相反。
> ② **W4 崩溃伪装成功** —— FACE 4 的 finding 缺 `kind` 键，而报告打印器硬取 `v["kind"]` → `KeyError`；**Python traceback 退出码恰好也是 1**，与"违规"同码，故**门禁崩溃被读成检出**。修法：打印器改 `v.get("kind", "unclassified")`（**忘了 kind 的 finding 仍须被报出**），并给 finding 补 `文件:行` 使其可定位。
> ③ **证人自身的两个空过守卫（已修）** —— `violations=0` 与面名 kind 都曾按**整段输出**匹配，会命中 FACE AUDIT 表而**永真**；现分别改读**裁决行**与**VIOLATIONS 段**。

**S1-8 的 16 类注入 + S1-5 的 4 次 + S1-6 的 7 次均已还原**，最终重跑全反应堆 → `BUILD SUCCESS` / **437 测试全绿**，
三条门禁（ADR-12 + S1-6 + **S1-8**）均 `PASS`（后续随 S2 退款域增至 **644**，见下一节）。
JWT 三次注入的完整记录见 `verification/REVERSE-VERIFICATION-jwt-2026-09-16.md`。

#### S2 退款域的注入（**首次对"层与层之间"做反向验证**）

前序各轮的注入对象都是**某一层的源码或产物**；S2-5 的对象是**装配**。脚本：`dy-app/src/test/resources/refund-s2-5-reverse-verification.py`（**6 条真注入 + 1 条阴性对照**）。它首次引入两条纪律：

- **跨模块注入必须先 `install`**：本轮的注入点跨 `dy-security` 与 `dy-app` 两个模块，每轮注入后须先 `mvn install -pl dy-security -DskipTests`，否则 `dy-app` 从 `.m2` 取到**上一次的旧 jar** ⇒ 注入"看起来没生效"。这是 S1-5 RV-D"陈旧 class"坑的**跨模块形态**。
- **执行范围写窄会伪装成"红错位置"**：脚本的 `SELECTOR` 必须**同时覆盖两个执行器**（`RefundDomainGEndpointsE2ETest` + `PermissionCodeRegistrationGateTest`），否则 `-6a` 那一类"门禁该红却不在执行范围里"会被误报成 `RED-BUT-UNEXPECTED`。

| # | 注入的缺陷 | 期望失败点 | 结果 |
|---|---|---|---|
| RV-S2-5-1 | `meridian` 失去 `refund:read`/`refund:write`（**契约可调声明落空**） | `contract_callable_roles_reach_the_business_layer_on_g2` | ✅ 红在预期那条 |
| RV-S2-5-2 | `area` 失去 `refund:approve`（**G4 白名单闸从未被执行**） | `area_supervisor_passes_the_permission_layer_on_g4` | ✅ 红在预期那条 |
| RV-S2-5-3 | 移除 G2 上的 `@RequirePermission`（**权限层静默失效**） | `therapist_is_stopped_by_the_permission_layer_not_by_a_missing_row` | ✅ 红在预期那条 |
| RV-S2-5-4 | 控制器受理时间兜底**对全部 entry** 生效（还原真实缺陷#2） | `entry_is_serialized_with_the_contract_literal_while_the_db_keeps_its_own` | ✅ 红在预期那条 |
| RV-S2-5-5 | 仓储 `recorded_at` **直绑 NULL**（还原真实缺陷#3） | 同上 | ✅ 红在预期那条 |
| **RV-S2-5-6a** | 移除 `refund:approve` 的**全部持有者**（**码无主** + 督导进不去） | **两条并列**：`area_supervisor_passes_the_permission_layer_on_g4`；`every_require_permission_code_has_at_least_one_holder` | ✅ 两条均红 |
| RV-S2-5-6b | 【**阴性对照**】只去掉 `manager` 一方的 `refund:write` | **必须仍绿**（"码仍有主"，该位置确实无可观察效果） | ✅ `NO-TEETH-AS-EXPECTED` |

> **结果：6 条真注入全部红在预期那条、阴性对照 1 条成立，未达成 / 未生效 / 红错位置 0 项。**
>
> **阴性对照的前三次迭代（本轮最耗时的推断，值得记住）**：`-6b` 的目的是**证明 `-6a` 的红来自"码无主"而非"随便改个位置都会红"**。前两次选点均失败 ——
> ① 选 `area` 的 `refund:approve`：**被新 E2E 断言直接覆盖**（RV-S2-5-2 就是它），照样红；
> ② 选 `meridian` 的 `refund:write`：**G1 立案断言本身就是 meridian 发起的**，照样红；
> ③ 最终选 `manager` 的 `refund:write` —— 但**不靠推理，先写独立探针实测**：13 个测试全绿，才把它定为对照点。
> 教训：**"阴性对照的前提"必须实测确认，不能推理认定**（已并入 `reverse-verification-gate` skill 失效模式）。
>
> **本轮也修正了"门禁语义边界"的宣称**：`PermissionCodeRegistrationGateTest` 是**码级**门禁（"每个权限码至少有一个持有者、契约禁入角色不得持有"），**不是角色级**（它不能断言"某个角色应当能调某个端点"）。把只去掉 `area` 一方的情形实测为**仍绿**并不是缺陷 —— manager/hq 仍持该码，端点并非全员 403。该边界已逐字写进门禁类注释，**避免后来者把码级门禁当角色级门禁用**。

#### S2-7 判定域的注入（**首次让"反向验证"产出测试，而不是消费测试**）

脚本：`dy-app/src/test/resources/verdict-s2-7-reverse-verification.py`（**16 条真注入**，全部注入对象是判定链的源码）。这一轮与前几轮的区别只有一句话：**前几轮的反向验证用来"验收"已有测试；这一轮它"生产"了一条测试**。故本节记的是那个方法，不是 16 个绿勾。

- **RV-11 的意义不在通过，在于它红过**：注入"把 `VerdictLedger.parseRiskOrNull` 的空值分支从 `null` 改成 `RiskFlag.NONE`"（即把"未登记标签"静默改写为"无风险"、直接使 D4 该触发而不触发）——**全部判定链测试仍然全绿**。原因是当时**没有任何测试执行过这个解析方法**：`v5_risk_flag_has_no_check_but_others_do` 读的是 V5 的 **SQL 文本**，不是 Ledger 的 **Java 代码**。一条没人执行过的防线，只能在复盘时被人"读到"，不能被构建拦住。故补了 `VerdictPersistenceBoundaryTest` 第五节（6 条断言：读回侧三方法的 `null`/空白串/fail-closed 全谱，以及入站侧两个"不得回落默认值"的源码扫描），并把三个解析方法由包私有放宽为 `public static`（纯函数，代价为零；"测试的包路径由被测类的可见性决定"是一处持续制造麻烦的耦合，遇到一次就该就地解开而不是把断言降级为源码扫描）。
- **注入点的选择本身可以是错的**：RV-9（给 F1 贴 `@StaffOnly`）与 RV-10（去掉 F1 的 `@Idempotent`）首版都用了不含路径的锚点 `@Idempotent` + `@RequirePermission("verdict:write")` ——**这个两行组合在源码里出现两次**（F1 与 F2，只差一行 `@Idempotent`），于是 RV-10 实际改的是 F2，与 RV-9 撞成同一个红点。锚点已改为**含 `@PostMapping("/cycle-assessments/{id}/verdicts")` 的三行**，把"改哪个端点"钉进锚点本身。
- **`assertThrows` 的第三个参数不会在"该抛却没抛"时打印**：RV-15 / RV-16 首版把预期锚点写成 `assertThrows` 的 message（"必须抛"/"缺 effect 就不是组合"），而注入后**根本不抛**，那段 message 自然不会出现在输出里 ⇒ 被误判成"红错了地方"。RV-15 改用相邻断言的 message，RV-16 改为 `expect_in_output=None`（该条**有意**不指定锚点，并在注释里写明"这里刻意不指定"），两者都在脚本里留了理由。
- **脚本自身的三处缺陷（都已修，且都由"注入无效"或"路径不存在"暴露）**：① 文本模式 `io.open(path,"w")` 在 Windows 上把 `\n` 翻成 `\r\n` ⇒ **"还原"这一步会把一个纯 LF 的源文件永久改成 CRLF** ——脚本自己成了工作区污染源，而注入验证的全部价值就在"还原后与注入前逐字节相同"（已改 `newline=""`，并加进程级快照 + 逐字节校验）；② `surefire:test` 的失败详情是 INFO 级，**`-q` 会把它抑制掉** ⇒ `expect_in_output` 永远匹配不上、每条都误判"红错位置"（已去 `-q` 并加 `-Dsurefire.useFile=false`）；③ `SKEL` 少算一层 `..` ⇒ 注入目标路径变成 `.../dy-app/src/dy-app/...`，报"源码不存在"，**那个错看起来像"文件被删了"**（已修并写进注释）。
- **`-Dtest=A+B+C` 会被静默忽略**：surefire 的 `-Dtest` 用逗号分隔，`+`（Ant 风格）虽不报错却**什么也不匹配**（配合 `-Dsurefire.failIfNoSpecifiedTests=false` 时结果是"BUILD SUCCESS / 0 tests"）。这类"看起来跑过了"的假绿已在脚本里逐条对照报告文件的 `Tests run` 计数排除（`VerdictPersistenceBoundaryTest` 实测 24、非 18）。

> **结果：16 条真注入全部红在预期那条（或已注明"有意不指定锚点"），未生效 / 未达成 0 项；脚本退出时逐字节校验通过，工作区与运行前完全一致。**
>
> 判定链本轮的**关键设计判据**另记两条（非反向验证产出，但决定 F 域的形状）：**① `@StaffOnly` 只贴 F2、不贴类级、不贴 F1** —— 契约里 F2 有 `x-client-explicitly-denied: true` 而 F1 **没有**，把 `@StaffOnly` 贴到类级会造成一次**超出契约的收紧**（F1 连带变成"客户一律 403"），故 `VerdictControllerContractTest` 逐字断言这个不对称；**② 判定顺序即语义** —— D5 前置（不可比 → 样本不足 → 缺事实）→ D4（安全优先，排在效果分支**之前**）→ D2 → D3 → D1，顺序反了的具体后果逐条写在注释里（D1 排前会把"新发"信号吞掉、D2 排后会让"根本没按方案做"的客户拿到"维持原方案"）。

#### S2-8 溯源回放的注入（**RV-1/RV-2 再次证明"核心承诺可以无人守着"；RV-15 首次证明"定向测试不加载跨包门禁"**）

脚本：`dy-app/src/test/resources/threshold-s2-8-reverse-verification.py`（**15 条真注入**，全部注入对象是 `threshold_version` 指纹 / 回放链的源码）。本节记的是三条**方法级**结论，不是 15 个绿勾。

- **🛑 RV-1 / RV-2：一条"立论级"承诺可以完全没有测试守着。** S2-8 的立论是"**版本号由口径算出、不由调用方给**"——入参 `threshold_version` 只是**断言位**（非空须逐字相同，否则拒 `VERSION_CONFLICT(4001)`）。RV-1 注入"不一致时**静默改用权威版本**"、RV-2 注入"**落库值取自入参**"——**S2-8 全部 29 条测试仍全绿**。即：当时"不一致即拒"与"落库必须取服务端算出的那个"这两条**都没有任何测试执行过**。首轮反向验证因此报 `12/14`。**修复不是改实现，而是补一整类测试**：新建 `ThresholdVersionPersistenceWiringTest`（9 条），以 `RecordingLedger`（实现 `VerdictPort` 的记录桩）盯住"三处落库同值 / 入参不一致⇒拒 4001 且**零写入** / 自描述与落库同源 / 快照段级指纹逐项**逐序**一致"。**与 S2-7 的 RV-11 完全同源**：防线只在人脑里，不在构建里。
- **🛑 RV-14：`Map.copyOf` / `Set.copyOf` 的迭代顺序不保证，用于落库序列化时是真实缺陷。** `ThresholdVersionFingerprint.segmentFingerprints` 与 `ReplayResult.driftedSegments` 都用 `Map.copyOf`；JDK 不可变集合的实现带 **per-JVM 随机盐**，迭代顺序**不保证**。后果对本项目尤其严重：**"同一口径 ⇒ 同一结果"** 是本域的立论，而同一份口径在两个 JVM 上会写出 **key 顺序不同**的段级指纹快照 ⇒ 跨实例比对会**误报漂移**。已改 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`（保序 + 只读）。这条与 §5.1 第 12 条（`Map.of` 拒 null）是**同一族**：JDK 的不可变集合便利方法有**语义陷阱**，用在下游要"当数据"的地方必须先想清楚它的顺序/空值语义。
- **🛑 RV-15：跨包的门禁测试，定向 `-Dtest` 根本不会加载它。** `ArchitectureBoundaryTest` 的 **R4（DIP，"领域模型不得依赖服务层"）** 规则住在 **`com.diaoyuanyun.dy.app`（不在 `derived` 包下）**，而 S2-8 的两个值对象 `ReplayOutcome` / `ReplayResult` 最初住在 `derived.service` 包 —— `ReplayResult`（record）依赖 service 内的 `ReplayOutcome`，**R4 违规 4 次**。但 `mvn -Dtest=Threshold*` **不会加载 `ArchitectureBoundaryTest`**（它不匹配该前缀），所以**定向测试全绿**；只有全量 `mvn -o clean install` 抓到（**BUILD FAILURE**）。已把两个值对象迁入 `derived/domain/`。**这条是"定向测试全绿 ≠ 可交付"的第二次实证**（第一次是 §5.1 第 11~13 条的"启动即失败"）。**通用教训**：**结构门禁/架构守卫按包/模块装配，不按被测类名前缀装配** —— 只要改动引入了跨包/跨模块引用，就必须跑一次**全量**构建，`-Dtest=<前缀>*` 的绿不足以背书。
- **RV-15 的注入形态本身也踩过一次坑**：首版注入"把 `ReplayResult` 的 `package` 改成 `service`"——**编译直接失败**（controller 的 import 断了），于是它"红"了但不是红在 R4。**正确形态**是"在 record 内**新增一行对 service 类型的引用**"（编译通过、但触发 R4）。**教训**：反向验证的注入必须"**编译通过但语义违规**"，否则红点来自编译器而非门禁，证明力为零（与 §S2-7 的"注入点选择本身可以是错的"同族）。
- **脚本与全量回归【不能并发】**：两者共用 `target/` 与源码目录，并发会出现 dy-app `BUILD FAILURE`（源码已被脚本逐字节还原，是**假失败**）。已改为**串行**执行。这条已写进脚本头注释。

> **结果：15 条真注入全部红在预期那条，未生效 / 未达成 0 项；脚本退出时对 4 个被触碰源文件做逐字节还原校验，工作区与运行前完全一致。**
> ⚠️ **订正（2026-09-25 · S2-10 跨文档对账时发现）**：本条此前写「**7 个**被触碰源文件」，实测 `threshold-s2-8-reverse-verification.py` 的
> `touched = [fingerprint, replay, service, replay_result]` —— **是 4 个，不是 7 个**（"7"与 S2-7 的数字相同，属**跨轮抄错**）。
> 同一次对账也确认：S2-7 的 7 个 ✅、S2-9 的 9 个 ✅ **均为实测值**，仅本条有误。
>
> **本轮"补债"清单（3 处，均非新功能）**：① `ThresholdVersionPersistenceWiringTest` 整个类（RV-1/RV-2 逼出）；② 两处 `Map.copyOf` → `LinkedHashMap` 保序（RV-14 逼出）；③ 两个值对象迁 `derived/domain/` + RV-15 固化（全量回归逼出）。**三处全由反向验证 / 全量回归产出，而非"先写实现后补断言"**。

#### S2-9 契约域 A 的注入（**首次把"契约声明的响应集"变成可执行判据；首次让"双编码输出"显形**）

脚本：`dy-app/src/test/resources/domain-a-s2-9-reverse-verification.py`（**14 条真注入**，注入对象 = 域 A 的控制器 / 服务 / 仓储 / 注册表 / 可见性矩阵断言）。本节记的是三条**方法级**结论。

- **🛑 RV-3：契约声明的响应集决定错误码，"HTTP 常规"在冲突时是错的。** A2 `GET /auth/me` 的契约只声明 `'200'` 与 `'401'`（**无 403**）；A3 `GET /stores` 只声明 `'200'` 与 `'403'`（**无 401**）。同一情形"没带身份"：**A2 必须 401，A3 必须 403** —— 这不是风格差异，是**两个端点各自的契约承诺**。RV-3 注入"把 `describeCurrent()` 的两行前置校验顺序对调"（即把 `requireTenant()` 放回 `requireCallable()` 之前），**编译通过、单测全绿**，但真请求下 A2 对匿名报 `2003`(403) —— **契约违约**。修法是**调换两行**，并留下顺序纪律注释。**通用教训**：写错误码前**先读端点自己的 `responses:` 声明集**，不要从"HTTP 该怎么回"倒推；**前置校验的顺序即语义**（谁先抢答决定了归因到哪个错误码）。
- **🛑 RV-5：`x-client-forbidden: false` ≠ `x-client-explicitly-denied: true`。** 契约 A3 有 `x-client-forbidden: false`（**不是**显式点名拒），A2 无任何此类标记。RV-5 注入"给 A3 贴上 `@StaffOnly`" —— 这是把"未显式禁止"**误读成"显式拒"**，与 S2-7 的 F1/F2 不对称是同一族错误。门禁 ⑤ 精确抓住（`identity_domain_keeps_client_out_and_auth_me_unannotated`）。⚠️ **注入形态有坑**：`@StaffOnly` 的 `clientDeniedFields()` **无默认值** ⇒ 裸写 `@StaffOnly` 是**编译错误**，必须带参 `@StaffOnly(clientDeniedFields = {"store_id"})`；包路径是 `...security.visibility.StaffOnly`（**不是** `.permission`）。**红点必须来自门禁而非编译器，否则证明力为零**（与 S2-8 的 RV-15 同族）。
- **🛑 RV-13：`List` 传给 varargs 不展开 ⇒ 单侧踩中、另一侧看不见。** 注入"把 `countByScope` 的 `filter.args().toArray()` 还原成直接传 `List`" —— 编译通过，但 `queryForObject(sql, Integer.class, Object...)` 会把 `List` 当**单个**参数 ⇒ SQL 占位符不匹配 ⇒ `BadSqlGrammarException` ⇒ **A3 整条 500**。**指纹 = "同源 SQL，只有一侧 500"**（`listByScope` 走 `append(...)` 返回 `Object[]`，varargs 正常展开，故此缺陷在 list 侧**永远看不见**）。**通用教训**：传给 `Object...` 前先 `.toArray()`；**共用"条件"不等于共用"调用形态"**。
- **⚠️ 本轮首次遇到"一份输出里根本不存在单一正确编码策略"（已固化为失效模式 13-b）。** 首轮反向验证报 `5/14`，但细查发现 **14 组注入全部正确变红、且红在完全正确的方法上** —— 只是**预期锚点写的是中文断言消息**，而读到的输出是乱码。根因：**同一次 `mvn` 输出里两种编码并存** —— Maven 前缀行（`[ERROR]` / `Tests run:`）是 **GBK**，而 surefire **fork 出来的测试进程**打印的断言消息是 **UTF-8**，同一个 pipe 收进同一份 bytes。**任何单一解码策略都打乱一半。** **硬纪律：反向验证的预期锚点一律只用 ASCII（测试方法名 / 权限码 / 枚举字面），绝不用中文断言消息。** 修法是加 `failed_methods()` 正则助手：
  ```python
  _FAILED_METHOD = re.compile(r"\[ERROR\]\s+\S+\.(\w+)\s+--\s+Time elapsed:[^\n]*<<< FAILURE!")
  def failed_methods(out):
      return sorted(set(_FAILED_METHOD.findall(out)))
  ```
  改完全部 14 处 `expect_in_output=<中文>` → `expect_method=<ASCII 方法名>` 后，**12/14 → 修 RV-5/RV-12 后 14/14**。
- **⚠️ RV-12 的注入前提本身可以是错的（"无主码"必须真的一个持有者都没有）。** 首版只摘 `therapist` 的 `store:read`，而 `manager`/`area`/`hq`/`meridian` 仍持有 ⇒ "无主码"断言**本就不该红**（实际红的是 E2E 的两条）。改用 `also=[...]` **同时摘掉全部五处**后成立。**教训**：断言"某码无主"前，先确认**所有**持有者都被摘除；否则注入前提与断言判据不匹配（与 S2-7 的"注入点选择本身可以是错的"同族）。
- **⚠️ 跨模块注入必须整仓 `install`。** 脚本的 `compile_all()` 走 `mvn -o -q -DskipTests install`（整仓），**不是** `compile_main()`。原因见上方 §四 的失效模式 17：`dy-app` 依赖的是 `~/.m2` 里的 `dy-security-*.jar`，改 `PermissionRegistry` 若不 install，依赖方跑的仍是旧 jar，注入"静默无效"。

> **结果：14 条真注入全部红在预期那条，未生效 / 未达成 0 项；脚本退出时对 9 个被触碰源文件做逐字节还原校验，工作区与运行前完全一致。**
>
> 🛑 **脚本与全量回归【不能并发】（同 S2-8 纪律）**：两者共用 `target/` 与源码目录，并发会出现 dy-app `BUILD FAILURE`（源码已被脚本逐字节还原，是**假失败**）。本轮**严格串行**：反向验证跑完后才开始 `mvn -o clean install`。

#### S2-10 契约域 B 的注入（**首次把"码级门禁的盲区"变成可执行事实；首次出现"组合自环"型缺陷**）

脚本：`verification/93_domain_b_reverse_verification.py`（**7 条真注入**，注入对象 = 域 B 的四行控制器注解 / B2 的门禁调用 / 账本 SQL / 修订值对象 / E5 控制器）。
证据落 `verification/94_domain_b_reverse_verification.md`（三列：注入内容 | 预期失败点 | 实际失败断言原文）。**结果：7/7 全部被抓，且还原后全绿。**
> 🛑 **本轮顺手补了 S2-9 遗留在脚本里的两处实现缺口**（README 此前**声明了、脚本却没做** —— 属"文档比实现更自信"这一类，与 §5.1 第 23 条同族）：
> ① **"还原"此前是按文本还原**（`open(...,'r')` 默认把 CRLF 归一成 LF，再以 `newline=""` 写回 ⇒ 不回写 CRLF）——
>    对一份 **CRLF 文件**，这一步会把"还原"变成"**永久改成 LF**"，且**没有任何断言会报错**（编译照样过）。
>    本轮 6 个被注入文件**恰好都是 LF** 才没出事，属**隐患而非当轮事故**。已改为**二进制快照 + 原样写回 + 编译前逐字节比对**。
> ② **锚点唯一性此前不检查** —— `replace(..., 1)` 只改"第一个出现"，若锚点在源码里出现多次，你改的**不是心里那个**，
>    红点会出现在错误的方法上、或与另一条注入撞成同一个红点（**S2-7 的 RV-10 正是这么被坑的**）。已加检查：>1 次即判 `ANCHOR-AMBIGUOUS` 并置 FAIL。
> 🛑🛑 **这条新检查【立刻抓出一个真实的、此前被掩盖的隐患】** —— 这不是"加个保险"，是**当场证明了原脚本的结论依赖一个未言明的假设**：
> I1/I2 的锚点原本是单行 `@RequirePermission("customer:archive")`，而该行在 `CustomerController` 里**出现 4 次**（B1/B2/B3/B6 各一次）。
> 原脚本的 `replace(..., 1)` 命中的是**第一个**，**恰好**就是 B1（`@PostMapping("/screening-records")` 排在文件最前）——
> 所以"7/7 PASS"**是真的**；但结论的正确性**建立在"B1 恰好排在最前"这个巧合上**：一旦有人把 B2 挪到 B1 之前（纯排版改动、不影响任何行为），
> I1/I2 注入的就变成 B2，而 `must_see` 断言的却是 B1 的用例（`contract_callable_frontline_roles_reach_b1`）⇒ **I2 会变成"未被抓住"**，
> 而真正的原因是**注错了地方**。**修法**：把 I1/I2 的锚点改成"注解 + 正下方的方法映射"两行（`@RequirePermission(...)\n    @PostMapping("/screening-records")`），
> 语义自明且**实测唯一**。**通用规则**：**反向验证的锚点必须带足上下文至唯一 —— "能匹配上"不等于"匹配到了你以为的那一处"。**

| # | 注入的缺陷 | 捕捉者 | 结果 |
|---|---|---|---|
| I1 | B1 的码改成注册表里**不存在**的 `customer:phantom`（模拟"码彻底无主"） | `PermissionCodeRegistrationGateTest` ② | ✅ 变红（`orphans` 非空）——**证明码级门禁能兜"码无主"** |
| **I1b** | **单摘** `therapist` 的 `customer:archive`（码仍有 7 个持有者） | `DomainBEndpointsE2ETest` | ✅ E2E 变红 · **且额外断言码级门禁【仍全绿】**——**把"盲区"变成可读事实** |
| I2 | B1 的码改回 `customer:write`（第 30 条复发：码有主但契约角色不匹配） | `DomainBEndpointsE2ETest` | ✅ E2E 变红 · **且额外断言码级门禁【仍全绿】** |
| I3 | 给 E5 端点贴回 `@RequirePermission("customer:read")`（第 30 条复发） | `DerivedVisibilityE2ETest`（新增守护） | ✅ 变红（合法 client 探测拿到 403） |
| I4 | B2 的门禁换回 `assertAdmissionChain`（含 `assertProfiled`，第 31 条自环复发） | `DomainBEndpointsE2ETest` | ✅ 变红（B2 恒 403 `missing_items=["PROFILED"]`） |
| I5 | `UPDATE_PROFILE_SQL` 的 `owner_store_id` 去掉 `::uuid`（第 32 条复发） | `DomainBEndpointsE2ETest` | ✅ 变红（B2 恒 500 `BadSqlGrammarException`） |
| I6 | 修订快照换回 `Map.copyOf`（第 33 条复发） | `DomainBEndpointsE2ETest` | ✅ 变红（B6 恒 500 `NullPointerException`） |

- **🛑 三条方法级结论（本轮的真正产出）**：
  1. **码级门禁看不见"角色级"缺口 —— 本轮把它变成了【可复现的证据】而非口头结论。** I1b 与 I2 各带一条**附加断言**：
     "真请求 E2E 必须变红，**而码级门禁必须仍然全绿**"。两条同时成立，才证明**门禁不是坏了，而是它回答的从来不是这个问题** ——
     门禁问的是"有没有**任何**角色持有"，不问"**契约点名的**那些角色是否都持有"。**"码有主"与"契约角色可达"是两件不同的事。**
  2. **逐条守卫看不见"组合自环"。** 第 31 条（B2 恒 403）的成因是两个**各自都正确**的守卫被串在一起：
     `assertScreeningResult` 对，`assertProfiled` 对，但把后者施加于"它的产出端点"上就自相矛盾。**没有任何单条守卫能看见这件事** ——
     只能由"真请求"看端点的**端到端可用性**。修法 = B2 只调 `assertScreeningResult`；`assertAdmissionChain` 标 `@Deprecated` 并**保留为陷阱登记**（不删，防后人再踩）。
  3. **"未被抓住"有两种，必须先分辨再动手。** 首跑 `6/7`，唯一未抓的是"单摘 therapist 的码"。
     它**不是守护失效**，而是**我最初把 I1 设计错了**：以为"摘掉 therapist/meridian 的码"会让码【彻底无主】，
     实际该码还有 7 个持有者，故门禁**正确地**保持绿。修法 = 拆成 **I1**（改成不存在的码 ⇒ 门禁必须红，证明门禁能兜"码无主"）
     与 **I1b**（单摘 therapist ⇒ E2E 必须红 + 门禁必须绿，证明盲区存在）。**与 S2-9 的 RV-12 同族：断言"某码无主"前，先确认所有持有者都被摘除。**
- **⚠️ 编码纪律被再次实证（与 S2-9 的失效模式 13-b 完全一致）**：本轮**从一开始**就把全部注入锚点写成**纯 ASCII**
  （源码里的中文注释不参与锚点，只锚 ASCII 的 Java 代码行），故首跑即无"编码假红"。**这条已成常规操作，不再是"每次都要重新踩的坑"。**
- **⚠️ 离线构建的通用坑（第 35 条）**：`httpclient5` 的传递依赖 `httpcore5` 被 Spring Boot BOM 钉到本地仓库**不存在**的 `5.2.5`；
  **只降 `httpclient5` 自身不够**（BOM 仍把 `httpcore5` 拉回 5.2.5），须在 `dy-app` 用 `dependencyManagement` 覆盖 BOM 判定。
- **⚠️ 脚本必须用 Windows 原生路径调 mvn（本轮的"脚本自身缺陷"）**：首跑 `FileNotFoundError: [WinError 2]` ——
  原生 Windows Python 走 `CreateProcess`，**认不出 Git Bash 的 `/c/opt/...` 与无扩展名的 `mvn`**。修法 = 显式给 `mvn.cmd` 的 Windows 绝对路径 +
  `JAVA_HOME`（`Eclipse Adoptium\jdk-17.0.20.101-hotspot`）+ `os.pathsep`（`';'` 而非 `':'`）拼 PATH。
  **反向验证脚本本身也是代码，它也会坏；坏法往往与被验证对象无关（这里是进程/路径层），故"脚本跑不起来"与"注入未被抓住"必须分辨。**

#### 批次五 · 登记式门禁与纵深防御的注入（**首次把"登记台账"本身变成被判对象；首次拆出"受限字段 vs 未登记字段"两条线**）

脚本：`verification/103_b5_registry_and_gates_reverse_verification.py`（**4 组真注入 · 12 项断言**，注入对象 = 权限码台账 / 控制器注解 / 领域 record + service 构造点 / V8 迁移 CHECK）。
证据落 `verification/103_b5_registry_and_gates_reverse_verification.md`（三列 + **第一版失败记录专节**）。**结果：K1/K2a/K2b/K3 全抓，12/12 PASS，逐字节还原全绿。**

| # | 注入的缺陷 | 捕捉者 | 结果 |
|---|---|---|---|
| K1 | `ScaleItemBankController` 的 `@RequirePermission("customer:write")` 改成未登记的 `scale:import` | `PermissionCodeRegistrationGateTest`（台账白名单闭合） | ✅ 变红（未登记码出现在控制器上） |
| **K2a** | 给领域 **record + service 构造点**双注入**未登记非受限**字段 `internal_score` | `DerivedVisibilityE2ETest`（出参白名单 `allowedExtensions`） | ✅ 变红（未登记字段出现在成功响应） |
| **K2b** | 同上注入**受限**字段 `gap_reason` | `DerivedVisibilityE2ETest`（**期望保持绿**） | ✅ **保持绿** —— 佐证"层 1 出口兜底"独立生效 |
| K3 | 删掉 V8 的 `risk_flag IS NULL` 支（破坏可空性） | `VerdictPersistenceBoundaryTest.v8_...` | ✅ 变红（CHECK 与枚举标签不再匹配） |

- **🛑 本轮最重要的方法论产出：K2 必须拆成 K2a / K2b，否则会把"选错注入目标"误读成"断言有洞"。**
  首版 K2 只注入**受限字段** `gap_reason`，结果**门禁没红**。追查后确认：`gap_reason ∈ DerivedFields.derivedAndAdjacent()`
  ⇒ 它在出站前已被 `DerivedResponseBodyAdvice`（**层 1 出口兜底**）整体摘掉 ⇒ A-9 断言**根本看不到它**，自然无从变红。
  **判定：这不是断言有洞，是注入选错了目标** —— A-9 守的是"**未登记字段**能否出站"这条线，不是"受限字段是否出站"（那是层 1 的职责）。
  修法 = 拆成 **K2a**（未登记非受限字段 `internal_score` ⇒ 断言必须红）+ **K2b**（受限字段 ⇒ 断言**必须保持绿**）。
  **两者合起来才证明"层 1 与层 2 各自独立生效"** —— 这正是"缺第三层"设计所依赖的隐含前提的**可执行证据**，而非口头结论。
- **🛑 脚本自身缺陷（本轮踩到，已固化）**：注入必须**保持源文件语法合法**。首版直接**删掉** `put("authLogin",` 整行 ⇒
  留下悬空字符串字面量 ⇒ **编译失败** ⇒ 脚本报"红"，但红的是**编译器**而非门禁，证明力为零。
  **正确形态 = 改键名**（`put("x",` → `put("xRENAMED",`）：语义等价、语法合法 ⇒ 红点来自断言。**此纪律已在批次六的 104 中验证有效。**
  （与 S2-8 的 RV-15「注入必须编译通过但语义违规」同族，此处是它在"登记表/Map 字面量"场景下的形态。）
- **🛑 全量回归纪律（复用 S2-8/S2-9 结论）**：反向验证脚本与全量回归**不能并发**（共用 `target/` 与源码目录）。
  本轮**严格串行**：103 跑完后才开始 `mvn -o install`。另：本机在注入已逐字节还原的前提下**不再 `rm -rf target`**
  （清 `target/` 会触发 Windows 批量安全删除确认且无必要）。

#### 批次六 · 契约端点覆盖总账的注入（**首次让"系统开发完成"变成可机械清点的判据**）

脚本：`verification/104_endpoint_coverage_reverse_verification.py`（**3 组真注入 · 8 项断言**，注入对象 = 覆盖总账测试类自身的三张登记表）。
证据落 `verification/104_endpoint_coverage_reverse_verification.md`。**结果：L1/L2/L3 全抓，8/8 PASS，逐字节还原全绿。**

| # | 注入的缺陷 | 捕捉者 | 结果 |
|---|---|---|---|
| L1 | 一条**出范围登记**"消失"（`put("authLogin",` → `put("authLoginRENAMED",`） | `EndpointCoverageLedgerTest` ② | ✅ 变红（45 = 已实现 ∪ 出范围 不再成立） |
| L2 | 给 `SettlementController` 加一个未登记的 `@PostMapping("/unregistered-probe")` | `EndpointCoverageLedgerTest` ③ | ✅ 变红（实现侧多出端点须登记） |
| L3 | 一条内部端点登记键被改（`put("GET /verdicts/{}",` → `RENAMED`） | `EndpointCoverageLedgerTest` ③ | ✅ 变红（僵尸登记 + 未登记端点同时出现） |
| — | **基线预检**：注入前 4 例全绿 · **锚点唯一性**：>1 次即 `ANCHOR-AMBIGUOUS` 置 FAIL · **逐字节还原**：字节数与哈希双比对 | — | ✅ 还原后复绿 |

- **🛑 为什么"完成度"必须落成门禁**：此前"系统开发完成"只能靠人读 README 判断，**任何一次端点增删都不会有东西报错**。
  本门禁把口径钉死为**两笔账**：`45 = 41 已实现 ∪ 4 出范围`（且出范围理由须含**语义标签** + ≥30 字符，杜绝 `put("x","TBD")` 这类占位）·
  `68 = 41 对应契约 + 27 内部端点`（且**无僵尸登记**——登记了却不存在的端点同样报红）。
- **🛑 三种"出范围"语义被显式区分**（不是笼统的"没做"）：`authLogin`【Non-goal】（契约明确不做）/ `listAuditSignals`+`getAuditCoverage`【未立项】（尚无需求）/
  `receiveEsignCallback`【待冻结】（上游契约未定）。**区分它们，是为了将来 owner 裁定"要做"时，知道该动哪一条。**
- **⚠️ 归一化口径必须写进断言**（本轮实测踩坑）：实现侧扫出的路径含 `/api/v1` 前缀与 `{id}` 形态，契约侧是 `{param}` 形态 ⇒
  断言前须 `normalize`（剥 `/api/v1` + `{...}`→`{}`）。首版锚点写成 `GET /customers/{id}`（**归一化前**形态）⇒ 断言失败；
  改为 `GET /customers/{}` 后即绿。**通用教训：比对两侧集合前，先确认"归一化在断言之前还是之后"。**

#### 批次七 · `refund_offline_notice` 字段级约束的注入（**首次让"三张表各自都值得一套断言"从注释变成事实**）

脚本：`verification/105_refund_offline_notice_reverse_verification.py`（**3 组真注入 · 4 项结论**，注入对象 = V6 迁移里 `refund_offline_notice` 的 CHECK / NOT NULL）。
证据落 `verification/105_refund_offline_notice_reverse_verification.md`。**结果：M1/M2/M3 各命中唯一方法、全抓，4/4 PASS，逐字节还原全绿。**

| # | 注入的缺陷 | 捕捉者 | 结果 |
|---|---|---|---|
| **M1** | channel CHECK 扩成 `('电话','当面','订阅消息')`（**放行推送通道**） | `..._channel_excludes_push_channels` | ✅ 变红（红集恰为该单一方法） |
| **M2** | 去掉 `refund_id` 的 `NOT NULL` | `..._required_columns_are_not_null` | ✅ 变红（红集恰为该单一方法） |
| **M3** | 追加第二条 CHECK（`note` 长度约束） | `..._has_exactly_one_check_constraint` | ✅ 变红（红集恰为该单一方法） |

- **🛑 M1 为什么是这三组里最重要的一组**：`订阅消息` 是**推送通道**。它若被 `refund_offline_notice` 接受，记账时「线下告知」就会被算成一次「已推送」——而 PRD 逐字写着线下告知**「不得计入推送覆盖率分母」**。
  **即：一个通道字面的放行，会直接制造一次覆盖率虚高。** M1 证明这条防线不是文档承诺，而是**真库 CHECK + 可反向验证的断言**。
- **🛑 本轮新增纪律：must_see 锚点必须做【元层判别力自证】。** 首版脚本的 `failed_methods()` 只判"方法名是否出现在输出里"，
  而 surefire 在 `-Dtest=X` 下**仍会打印含类名的汇总行** ⇒ 若某次改动让"方法名出现"变恒真，"被抓=True"会**全部失效而看不出来**。
  **修法**：基线（全绿）阶段先断言**待查方法名在基线输出里零出现**，否则 `return 2` 判脚本失效。
  第二轮实测：基线零出现 → 三轮注入各命中**唯一**方法。**锚点有判别力是可证的，不是假设的。**
- **🛑 改迁移文本做反向验证为什么安全（本轮给出机制依据）**：`RlsGateSupport` 的 schema 哨兵 = **整条迁移链字节的 SHA-256**（见其类注释）。
  故 **注入 V6 ⇒ 哨兵变 ⇒ 门禁判库不匹配 ⇒ 自动重建库 ⇒ 断言在真实新 schema 上运行**；**还原 ⇒ 哨兵回原值 ⇒ 建回原 schema**。
  这条机制使"用改 DDL 的方式做反向验证"**天然安全** —— 不需手工重建库，也不会出现"库停在旧 schema ⇒ 断言假绿"。
  本轮 5 次 Maven 调用（1 基线 + 3 注入 + 1 复绿）全在该机制下运行，`information_schema` / `pg_constraint` 判据均取到真实新值。
- **⚠️ 缺口是怎么被发现的（方法论）**：不是"顺手多测一点"，而是**把 11 条原断言按被测对象归类** —— 归完后
  `refund_offline_notice` 一栏**空白**，与类注释"三张表各自都值得一套断言"**不一致**。**归类清点 > 逐条阅读**：
  逐条读每一条都"看起来在测东西"，只有按对象归类才会暴露"某一栏是空的"。

#### 批次八 · 遗留角色码缺自建码的注入（**首次让"注释里的分叉"变成有人守着的登记表；首次暴露"跨模块注入"盲区**）

脚本：`verification/106_legacy_role_missing_codes_reverse_verification.py`（**3 组真注入 · 4 项结论**，注入对象 = `PermissionRegistry` 角色↔码映射 / 登记表本体）。
证据落 `verification/106_legacy_role_missing_codes_reverse_verification.md`。**结果：R1/R2/R3 各命中唯一方法、全抓，4/4 PASS，逐字节还原全绿。**

| # | 注入的缺陷 | 证明了哪一条 | 结果 |
|---|---|---|---|
| **R1** | 给 `TENANT_ADMIN` 补上 `doc:write`/`audit:read`（**把分叉"修好"**） | ① 登记表**随修复归零** | ✅ 变红（红集恰为该单一方法） |
| **R2** | 登记表角色写成 `hq`（**实际持有**该码的契约角色） | ① 断言是**双向**的，非单向假绿 | ✅ 变红（红集恰为该单一方法） |
| **R3** | 登记表清空为 `Map.of()` | ③ 防"表清空 ⇒ 循环不进 ⇒ 平凡通过" | ✅ 变红（红集恰为该单一方法） |

- **🛑 R1 是最重要的一组：它证明"登记式断言会随修复归零"。** 只写注释时，分叉被**修好**了**不会有人报错** ——
  于是"未修好"的旧事实会被继续引用（属"文档比实现更自信"）。R1 证明：**修好 ⇒ 红 ⇒ 必须显式把该码从表里移除**。
- **🛑 R3 证明登记式结构的固有失效模式已被堵住**：表一空，`for` 循环体一次都不进 ⇒ 断言**平凡通过**；
  故断言③显式钉住"**恰 2 个角色**"—— 让"差异归零"与"表被误删"在测试输出上**不再长得一样**。
- **🛑🛑 本轮最重要教训：跨模块注入必须先 install 依赖模块 —— "未被抓住"的第二种成因。**
  首跑 `3/4`，唯一未抓的是 R1，第一反应是"断言没牙齿"，**真因是注入根本没进到被测对象里**：
  `PermissionRegistry` 在 **dy-security**、被测类在 **dy-app**；`mvn -o -pl dy-app test` 会从**本地仓库**
  解析 dy-security 的 **jar** ⇒ **源码改动完全不生效** ⇒ 门禁当然保持绿。
  **判别方法**：先问"红点应该来自哪里"，再看**改动的那份文件有没有参与本次构建**。
  **修法**：跑门禁前先 `mvn -o -pl dy-security install -DskipTests`。
  与 103 首版"POSIX 路径 + `shell=True` ⇒ Maven 从未执行"**同族**（参 §5.1 第 36 条：
  **问的是"证据本身指向的是不是它声称的那件事"**）。
  **影响面已核**：103/104/105 的注入对象与被测类**同在 dy-app**，不受影响；**106 是本仓库首个跨模块注入**。

#### 批次九 · 排除项 × 配置真相源的交叉自证（**首次把"配置真相源"拉进守护面；跨模块纪律首次产生事前收益**）

脚本：`verification/107_excluded_items_cross_check_reverse_verification.py`（**3 组真注入 · 4 项结论**，
注入对象 = `EXCLUDED_NOTE` 常量文本 / `02_slots_seed.sql` 的 `cfg:verdict.*` 槽位命名 / 测试内期望集合）。
证据落 `verification/107_excluded_items_cross_check_reverse_verification.md`。**结果：P1/P2/P3 各命中唯一方法、全抓，4/4 PASS，逐字节还原全绿。**

**被登记的事实（一个真实漏项 · N-3 收口）**：`cfg:verdict.*` 命名空间在真相源里**恰 4 槽**：

| 槽位 | 指纹九段是否消费 | `EXCLUDED_NOTE` 是否点名 | 结论 |
|---|---|---|---|
| #9 `branch_rules` | ❌ | ❌ | 🛑 **两侧都不在（漏项）** |
| #30 `formula_params` | ❌ | ✅ | 已显式排除 |
| #33 `mcid_threshold` | ✅ | —（已消费） | 已纳入 |
| #45 `confidence_formula` | ✅ | —（已消费） | 已纳入 |

> **漏项形态**：#9 `branch_rules` **既不在九段里、也不在排除说明里** —— 这正是 N-3 要防的
> "『有意识地不纳入』与『忘了纳入』在复盘时无法区分"。收口前若有人问"#9 为什么不算进指纹"，
> 答案只能靠**人回想**；而"没人想过它"与"想过并决定不纳入"在**文档上长得一模一样**。
> ⚠️ **本轮改的不是那个判断，而是"判断没有被钉住"这件事** —— #9 的"不纳入"在当时是正确的
> （`branch_rules` 五字面与 `VerdictBranch` 枚举逐字一致、分支判断 `D1~D5` 写死在 `VerdictService` 内）。

**断言机制（核心等式）**：

```
cfg:verdict.* 全集（机械读 02_slots_seed.sql 提取）
    == 九段已消费（#33 / #45） ∪ EXCLUDED_NOTE 显式点名（#9 / #30）
     ——— 不得有第三个 ———
```

| # | 注入的缺陷 | 证明了哪一条 | 结果 |
|---|---|---|---|
| **P1** | 从 `EXCLUDED_NOTE` 删掉 `#9` 那两行（**制造"两侧都不在"**） | 断言**真的在读常量**（非手抄第二份清单） | ✅ 变红（红集恰为该单一方法） |
| **P2** | 把 seed 里 `#35` 的 key 改成 `cfg:verdict.probe_range_rule`（**真相源侧多出槽位**） | 🛑 **守护不只对实现侧生效**（真相源进了守护面） | ✅ 变红（红集恰为该单一方法） |
| **P3** | 期望集写成 `Set.of("33","45")`（**期望集与真相源脱节**） | 断言**非恒真**（期望集能被证伪） | ✅ 变红（红集恰为该单一方法） |

- **🛑 P2 是本轮最关键的一维**：若断言只比对"九段 vs 排除说明"两个**实现侧**的东西，那么
  **真相源里新增/改名一个 `cfg:verdict.*` 槽位时两侧都不会知道** ⇒ 新口径槽位**静默地不进指纹**
  ⇒ "同一份口径 ⇒ 同一版本号"的承诺被悄悄破坏（**本该变的版本号不变**）。
  P2 的价值 = **把真相源拉进了守护面**（全集侧是**机械提取**的，不是手抄）。
- **🛑 P1 证明的不是"断言存在"，而是"断言没有第二份真相"**：若把"已排除项"**手抄**在测试里
  而非读 `EXCLUDED_NOTE` 常量，删常量就**不会红** —— 那正是"两份清单各自漂移"的失效模式。
- **🛑🛑 跨模块注入纪律【首次产生事前收益】**：P2 注入的是 **dy-config 的 classpath 资源**
  （`02_slots_seed.sql`），被测类在 **dy-app**。批次八是**事后归因**（首跑 3/4 才发现跨模块盲区），
  本轮把 `NEEDS_CONFIG_INSTALL = {"P2"}` **直接写进脚本**（跑 P2 前先 `mvn -o -pl dy-config install -DskipTests`）
  ⇒ **P2 一次即中，无须二次追查**。（参 §5.1 第 36 条：**证据本身指向的是不是它声称的那件事**。）

#### 批次十 · 配置槽位消费台账的注入（**首次把「声明 × 消费」的全量对账变成构建期事实**）

脚本：`verification/108_config_slot_consumption_reverse_verification.py`（**5 组真注入 · 6 项结论**，
注入对象 = `CrossStoreSettlement.DEFAULT_SPLIT_THRESHOLD` / `02_slots_seed.sql` / 台账测试自身的账目与扫描器）。
证据落 `verification/108_config_slot_consumption_reverse_verification.md`。**结果：P1~P5 各命中唯一方法、全抓，6/6 PASS，逐字节还原全绿。**

**它把批次九的 N-3 洞见推广到 46 槽规模**：批次九只守了 `cfg:verdict.*`（4 槽）。
配置真相源声明 **46** 槽，而"**哪几条真的被生产代码读过**"此前**无人守** ——
于是有两类后果、症状相反且**都不报错**：

| 形态 | 症状 | 排查者会以为 |
|---|---|---|
| **声明了却没人读** | 改了配置毫无效果 | "配置已生效" |
| **读了却没声明** | 读到 `null` 而静默兜底 | "配置有问题" |

**判据三条**：① **全集侧机械读 seed**（非手抄）· ② **消费信号 = 生产代码（剥注释后）出现该键字面量** ·
③ **账面逐条对账**（账记"已消费"的代码必须真有、账记"未消费"的必须真没有 ⇒ 归零须显式动作）。

**实测消费面：16 已消费 ∪ 30 未消费 = 46**（两账互斥、无第三个）。

| # | 注入的缺陷 | 证明了哪一条 | 结果 |
|---|---|---|---|
| **P1** | `DEFAULT_SPLIT_THRESHOLD` 的 `0.30` → `0.31`（**假装修好 #21**） | 台账**随修复归零**（最重要方向） | ✅ 变红（红集恰为该单一方法） |
| **P2** | 往 seed 插**第 47 条**槽位（`#49`），不改任何账本 | 全集侧是**机械提取**（报"两侧都不在"） | ✅ 变红 |
| **P3** | 账本里 `#43` 的**代表性文件**指向不存在的文件 | 账本**不是孤立的表**（连线断即红） | ✅ 变红 |
| **P4** | 让**生产代码**引用一个"未消费"键 | 未消费侧**不是摆设**（归零须显式） | ✅ 变红 |
| **P5** | 短路**块注释**检测（运行期条件 `src.isEmpty() &&`） | 🛑 **剥注释必要性自证**（最关键一维） | ✅ 变红 |

- **🛑 P5 是本轮最关键的一维**：若不剥注释，`cfg:verdict.branch_rules`（#9）与 `cfg:improvement.calc_rule`（#32）
  （**只出现在 Javadoc 里**）会被当成"已消费" ⇒ **台账系统性高估消费面** ⇒
  "哪些配置真的被读了"这个问题的答案整体失真。
  （P5 用**运行期条件**而非 `false` 常量折叠 —— 后者会让后续分支变不可达代码 ⇒ 红点来自编译器而非断言。）
- **🛑 P1 证明的是台账唯一真正重要的方向**：只写在文档里的"未消费"，被人补上消费后**不会有人报错**，
  账本会长期说谎。P1 证明**修好 ⇒ 红 ⇒ 必须显式从账本移除**。
- **🛑 P3/P4/P5 的连带红集是设计使然**：五条测试共享同一份事实（seed 全集 + 生产代码引用集），
  一处漂移在多个方向留痕 ⇒ **交叉自证**，不是噪声。
- **🛑 本轮暴露的两个真缺陷候选**（属裁定域，本台账只钉现状）：
  - **`#21 cfg:crossstore.split_threshold`**：seed 标"总部唯一可改"，代码**硬编码 `0.30` 且从不读配置**
    ⇒ "总部可改"目前**不成立**；
  - **`#30 cfg:verdict.formula_params`**：未消费，且与 `#4`/`#35`/`#33` **同值多处声明**
    （`adherence_gate.min=0.8`≡#4 的 `0.80`；`range` 与 #35 逐字相同；`module_total_max=16` 同 #33）。

#### 反向验证脚本索引（`verification/115~121` —— 补登，此前只散落在各批次块内）

> 🛑 **为什么单列索引**：`115~121` 七份脚本此前只在各自批次变更块里"就事论事"提到一句，
> **全仓没有一处能一眼看清"哪条通路由哪个脚本守、守了多少条"** —— 这正是登记册滞后的同型风险
> （盘点"还差什么"时不该靠翻变更块）。下表**逐条实测落盘**（`.py` 与 `.md` 成对存在）。

| 脚本 | 守护对象 | 结果 | 留档 |
|---|---|---|---|
| `115_sdk_pipeline_reverse_verification.py` | `contract/sdk-generator/_sdk_pipeline.py` 六类判据（INV-1 防裁过头 / INV-2 被裁 operation 不得出现 / INV-3 client-mp 运行时不出现「退款」/ INV-6 schema 不得留 model 文件 / INV-6b 产物不得含矩阵禁入片段 / 例外机制与裁剪自证） | 六类判据 + 元层自证 | ⚠️ **仅 `.py`，无 `.md`**（属契约域 SDK 通道，非可运行后端） |
| `116_cross_tenant_reference_reverse_verification.py` | **V16 跨租户引用完整性**（`UNIQUE (tenant_id, <pk>)` + 复合外键） | **9/9 PASS** | ✅ |
| `117_migration_chain_schema_reconciliation.py` | **迁移链与真库 schema 对账**（五面 + 已应用库的 Flyway checksum 逐条一致） | **6/6 PASS**（本轮复跑） | ✅ |
| `118_band_binding_reverse_verification.py` | **V17 手环绑定原语**（B-10） | **17/17 PASS**（当场抓出 V17 自身 4 处缺陷） | ✅ |
| `119_device_provisioning_reverse_verification.py` | **V18 `register_device`**（B-11） | **22/22 PASS** | ✅ |
| `120_scale_provisioning_reverse_verification.py` | **V19 `register_scale`**（B-12） | **28/28 PASS** | ✅ |
| `121_case_archive_reverse_verification.py` | **V20 `register_case_archive` / `latest_archive_of`**（B-13，批次十七） | **45/45 PASS**（一次运行抓出 4 处真实缺陷：3 静默假绿 + 1 假红） | ✅ |

**证据锚点**：`121` 结论行逐字 `===== 121 反向验证结果: 45/45 通过 =====`；`117` 结论行逐字 `===== 117 模式对账结果: 6/6 通过 =====`。

### 真实 PostgreSQL 端到端隔离验证（非文本断言）

`RlsMigrationScriptTest` 只断言脚本文本。为把"租户不串号"证明到**真实数据库行为**层面，
另建了一套 psql 脚本（见 `verification/`），**直接引用本骨架的真实迁移脚本**建库，
并以**非超级用户** `dy_app` 跑 A0~A8 断言：

| 脚本 | 作用 |
|---|---|
| `01_reset.sql` | 重建测试库 + 创建非超级用户 `dy_app`（超级用户会绕过 RLS，必须用非超级用户） |
| `015_apply.sql` | **`\i` 引入骨架真实迁移脚本**（非副本）+ 移交表 owner 给 `dy_app`（否则 `FORCE RLS` 无从验证） |
| `02_seed.sql` | 两个租户各插入一行 |
| `03_assert.sql` | **A0~A8 真断言**（`DO` 块 `RAISE EXCEPTION`，退出码即 CI 门禁） |
| `90_break_and_assert.ps1` | **反向验证**：注入 3 种 fail-open 写法，断言必须失败 |

**A0~A8 覆盖**：A0 防超级用户假通过 · A1/A2 双向隔离 · A3 未设上下文→零行 · A4 空串上下文→零行（`NULLIF` 作用点）· A5 `WITH CHECK` 拒绝跨租户写入 · A6 `FORCE` 使 owner 不绕过 · A7 直查 `pg_policies` 元数据 · A8 切换后不变量成立。

**结果：A0~A8 全部通过**；反向验证注入 `COALESCE(…, 默认租户)`、关闭 `FORCE`、`USING (true)` 三种 fail-open 后**均被断言抓住**（exit=3），恢复后重新全绿。

---

## 五、未完成项与 TODO（诚实清单）

### 5.1 已在复核中修正的缺陷（记录在案，勿回退）

> **共 72 条**（第 37~40 条为 **2026-09-27 · 批次十六** 新增；第 **41~44** 条为 **2026-09-27 · 批次十七** 新增；
> **第 45 条**为 **2026-09-28 · 批次十八（G-B 的 B-1）** 新增；
> **第 46 条**为 **2026-09-30 · 批次二十（A-4）** 新增；
> **第 47 条**为 **2026-09-30 · 批次二十一（A-3）** 新增；
> 🆕 **第 48 条**为 **2026-09-30 · 批次二十一收尾（CI 命令可用性核对）** 新增 ——
> **第六类新缺陷：前五类问的是"实现/门禁/判据/清单对不对"，第 48 条问的是
> "【我们声称要跑的那条命令，本身跑得起来吗】"** ——
> 它是**第一条"从未在任何机器上成功执行过"的缺陷**：CI 的 `rls-isolation-gate` step 1
> 写的 `mvn -Dtest='Rls*Test' -DfailIfNoTests=false`，**在本仓结构上不可能通过**
> （`dy-config` 的 `config-truth-source-gate` 把 `<failIfNoTests>true</failIfNoTests>` 写死在 pom，
> plugin 配置优先于同名 CLI 属性 ⇒ `-am` 连带 dy-config 时任何 `-Dtest` 筛选都必失败），
> 却因"仓库当时不是 git 仓库、CI 从未真正触发"而**长期无人发现**。
> 与第 41 条同类（**假绿**）：**"写了 CI 门禁"被当成了"CI 门禁有效"**。
> 🆕🆕 **第 49 条**为 **2026-09-30 · 仓库根上移** 新增 ——
> **第七类新缺陷：前六类都在问"内容对不对"，第 49 条问的是"【这些东西被放在了正确的位置吗】"** ——
> 它是**第一条"由仓库布局而非代码逻辑造成"的缺陷**，且**级别更高：阻断级**（CI 永远无法变绿）。
> 详见下表第 49 条。
> 🆕🆕🆕 **第 50 条**为 **2026-09-30 · 前端三端骨架构建自检** 新增 ——
> **第 48 条的同族第二条：写下的命令从未真正跑通过。** 三端 `package.json` 的
> `gen:endpoints` / `check:contract` / `check:build` / `build` **一律**写 `../../tools/...`，
> 而 `skeleton/tools/` **根本不存在**（真实位置是 `frontends/tools/`，即上一层）——
> 实测 `node ../../tools/build-check.mjs` ⇒ `Cannot find module '...\skeleton\tools\build-check.mjs'`，
> 改 `../tools/` 后即刻正常。**共 7 处**（`admin-web` 3 · `therapist-app` 3 · `client-mp` 2 里的 2 条 + `frontends/README.md` 1）。
> 与第 48 条合起来说明：**"命令写对了词"与"命令跑得起来"是两件事**，
> 而两者都只能由**实际执行**证明，不能由代码审阅证明。
> 🆕🆕🆕 **第 51 条**为 **2026-09-30 · 端 C 首次跑门禁** 新增 —— **第八类新缺陷（阻断级）：两道门禁口径不一致 ⇒ 本地绿、CI 红。**
> 前七类问的是"内容/位置/命令对不对"，**第 51 条问的是"【两个各自都对的检查器，是否在同一件事上用了同一把尺】"**。
> `frontends/tools/build-check.mjs` 原用 `stripComments()` 剥离 `/* */` 与 `//` 后再比对词表，
> 且其注释**逐字声称「与 compliance 一致」—— 该声称是错的**：
> `compliance/scan_compliance.py` 的 `scan_face()` 是**逐行直接匹配、不剥任何注释**。
> 后果正是本仓反复修的假绿：**`npm run build` 绿（注释被剥掉了），CI 里 ADR-12 门禁红**，
> 而排查者会误以为是 CI 环境问题。实测：端 C 首跑主扫描器 ⇒
> `[COMPLIANCE-GATE] FAIL faces=3 violations=27`，**27 处全部在注释内、真实代码与文案零命中**。
> **方向选择不是随便挑的**：词表 SCOPE 逐字写「applied to the WHOLE client package with no
> path allow-list」，注释确实是客户端包的一部分；且小程序打包后注释可能保留、反编译可见
> ⇒ **注释里的禁词本就是命中**；本仓纪律明令「不放宽词表、不加 path 例外」
> ⇒ 对齐到**更严的一侧**（注释同样参与匹配），**不是**去改主扫描器。
> **由此产生的写作纪律（已落进代码注释与 `frontends/README.md`）**：
> 客户端包内**不得写出禁词原文**；需要解释"某词为什么被禁"时**一律用指代**
> （条款号 / 字段组编号 / 组名 / 扫描面编号），例：写「R2 组」而不写那组里的词。
> 🛑 **落地时我自己又踩了两次**（把指代写成"R2 组点名的归因说法"、把组名写成"与医学宣称相关"）
> —— 说明这条纪律**必须写成"不引原文"而不是"换个说法"**，否则会以"解释禁词"的形式再次命中。
> 🆕 **第 52 条**为 **2026-09-30 · 端 B（X-3 角色级装载）反向验证首跑** 新增 ——
> **第 41 条的同族第二载体：判据被【字面量】满足。**
> 第 41 条那次的载体是 **SQL 函数体内 `RAISE` 消息的字符串**，
> 本条的载体是 **TypeScript 里的语句** —— 同一族缺陷换了宿主：
> `clientText.includes('assertCanCall')` 会被 **`void assertCanCall;`**（**只提及、不调用**）满足；
> `includes('F1')` 会被 **`void ['F1']`** 满足；`/['"]meridian['"]/` 会被 **`void "meridian"`** 满足。
> 🛑 **它是由反向验证抓出来的，不是由"门禁全绿"抓出来的** ——
> 门禁首跑就是**全绿**的，7 组受控注入里 **I5（摘掉调用、只留词）漏过**，
> 才暴露出 3 条判据（④ 硬编码清单 · ⑤ 单一权威面 · ⑥ 出站已接线）**全都是"判词是否出现"而非"判行为是否发生"**。
> 修法 = **判【调用形态 / 比较形态 / 数组字面量形态】，不判"词是否出现"**：
> ⑥ 用 `assertCanCall\s*\(\s*[^)\s][^)]*\)`（必须带实参的真正调用）；
> ⑤ 用 5 条比较/成员判定正则（`==`/`===`、`.includes()`、`.indexOf()`、查表）；
> ④ 要求**数组字面量里至少 2 个端点行号**（单个行号可被 `void ['F1']` 这类形态满足）。
> 🛑 **连带教训**：注入用例本身也要"真实" —— I4 必须注入 `role === "meridian"`（真实判断形态），
> 写成 `void "meridian"` 会得到一个**假红**（门禁报了红，但报的不是它该报的原因）。
> **反向验证的价值正在于此：它是唯一能证明"门禁的绿是有条件的绿"的手段。**
> 详见下表第 52 条。
> 🆕 **第 53 条**为 **2026-09-30 · 端 B 加「专属动作」页** 新增 ——
> **第 52 条的同族第三条，但接缝位置不同**：
> 第 52 条是"判据被字面量满足"（**判据太弱**，错的代码也能过）；
> 第 53 条是"判据的覆盖面**没跟上新写法**"（**判据太窄**，新写法绕过判据）。
> 前者是"判错"，后者是"没判"。
> 实测：端 B `x3-check.mjs` ⑦ 初版只认字面量 `/requires:\s*'([^']+)'/`，
> 「专属动作」改成 `requires: firstSoleEndpointId()`（机械推导形态）后，
> 该导航项**根本没被检查**，而门禁输出仍是
> `✓ nav: 导航项依赖的 2 个端点都在本端生成物里` —— **数字是错的却看不出来**
> （实际 3 项非 null 依赖）。
> 🛑 **这比"静默不报"更坏**：门禁绿着，**而且它在打印一个错误的数字** ——
> 那是一个**可信的假证据**（一个"2"让人以为检查过了）。
> 修法两层：① 判据同时认**字面量 + 调用形态**（调用形态须在门禁白名单内、
> 须定义于 `access.ts`、其实现须引用生成物）；② 加**交叉核对** ——
> **判据认出的条数必须等于 NAV 数组里非 null 的 `requires` 条数**，
> 不等即报红并提示"出现了第三种写法，本判据不会检查它"。
> **第 ② 层是通用手法**：判据不该只"检查它认识的东西"，
> 还应"证明它认识的东西覆盖了全部"。
> 详见下表第 53 条。
> 🆕🆕🆕 **第 54 条**为 **2026-09-30 · 端 A（39 端点八域）元信息层落地** 新增 ——
> **第九类新缺陷：契约里写下的约束，在【转录管道】里被静默丢掉。**
> 前八类问的是"实现/门禁/判据/清单/命令/位置/口径对不对"，**第 54 条问的是
> "【契约声明与前端实际拿到的声明，是不是同一份】"** ——
> 它的载体不是代码、不是判据、不是命令，而是**生成器本身**：
> `gen-endpoints.py` 初版只转出 `id / row / method / path / grantedRoles`，
> 把端 A 真正吃紧的 4 类操作级 `x-` 键（`x-row-scope` · `x-super-admin-only` ·
> `x-ruling-pending` · `x-frontier`）以及契约顶层 `x-roles` **一个都没转**。
> 🔴 **它的失效方式是"完全静默"**：生成物看着完全正常、`--check` 也绿、
> 三端构建全过 —— 只有**人肉逐条读契约**才发现"界面少了一道约束"。
> 与第 50 条同族（**"写下的东西"与"生效的东西"是两件事**），
> 且与第 41/52/53 条构成同一族缺陷的**第三个接缝**：
> 第 41 条接缝在 SQL 字符串，第 52/53 条接缝在 TS 判据，**第 54 条接缝在生成器**。
> 🛑 **为什么端 B 没暴露它**：端 B 的边界恰好全在端点级角色上（`grantedRoles` 是初版唯一转出的元信息），
> 所以"丢 4 类键"对端 B **无影响**；端 A 是**单角色端**（39/39 全 `["admin"]`），
> 它的边界**全在那 4 类被丢掉的键上** ⇒ **同一个生成器，在端 B 无害、在端 A 致命**。
> 修法 = `gen-endpoints.py` 新增 `OP_X_KEYS` / `load_role_expansion()` 转出元信息，
> 并新增**端 A 主门禁 `tools/a-check.mjs`**（11 项判据，其中 `xkey-coverage` 把
> 契约里**实际出现的每一个**操作级 `x-` 键枚举一遍，新键自己报红）+
> `tools/a-reverse-check.mjs`（10 组受控注入，证明门禁有牙齿）。
> 🛑 **落地时我自己又踩了三次同型坑**（全部由门禁首跑/反向验证抓出，非由"全绿"抓出）：
> ① 门禁解析生成物时**没把 `grantedRoles` 填进 entry** ⇒ `xkey-transcribed` 假红，
> **是门禁自己错了**（第 52/53 条同型）；
> ② 新加的 `parse-coverage` 判据**首跑就假红 4 条** —— 因为契约的 `x-contract-row`
> 是**合同行**（`D5` 一行对应 D5-a/b/c 三个操作），生成物的 `row` 是**子档位细化**而非字面拷贝；
> ③ 反向验证 **I5/I7 漏过** —— `scope-wired` 用 `includes('ENDPOINTS')`、
> `role-expansion` 用 `/ROLE_EXPANSION/`，**又被"词是否出现"满足**（第 52 条第三个宿主）。
> ⇒ 三次全部收敛为同一句纪律：**判据判【形态】，不判【词】；并且要能证明覆盖面。**
> 详见下表第 54 条。
>
> 🆕🆕🆕 **第 55 条**为 **2026-09-30 · 端 A 应用层（八域页面 + 多页外壳）落地** 新增 ——
> **第十类新缺陷：判据【太窄】⇒ 把本仓自己规定的合法写法判红 ⇒ 假红 ⇒ 判据被删。**
> 🔴 它是第 52 / 53 条的**孪生方向**：
> 第 52 条是判据**太宽**（被词满足）⇒ **假绿**（错的东西被放过）；
> 第 53 条是判据**覆盖面没跟上**⇒ **静默漏检**（新写法绕过判据）；
> **第 55 条是判据太窄 ⇒ 假红（对的东西被抓）。**
> 🛑 **为什么"假红"比"假绿"更危险，值得单独编号**
> ---------------------------------------------------------------------------
> 假绿是"错的东西没被抓"；假红是"**对的东西被抓**"。假红的下场**不是修代码**，
> 而是**有人把判据删掉、或加一行绕过** —— 那才是真正的失效：
> 一条被删掉的判据连"静默漏检"都不如，它连检都不存在了。
> 🛑 **具体撞见（本轮实测，两处）**
> ---------------------------------------------------------------------------
> **① `unsettled-surfaced`（⑦ 判据）**：初版只认
> `unsettledOf` / `unsettledEndpoints` / `frontier` / `rulingPending` 四词。
> 而本仓在 `admin-web/src/services/domain.ts` 里**逐字规定**：
> 「页面不得各自手写判断，一律走 `contractNoteOf` 统一出口」。
> 于是 `RefundWorkbenchPage` / `DocTemplatePage` 写
> `contractNoteOf('approveRefund')` + `<UnsettledBar items={note.unsettled} />`
> ——**完全合法、且是本仓规定的唯一写法**——却被判红。
> **判据与本仓的权威约定各写了一套。**
> **② `nav-requires`（⑨ 判据，本轮新增）**：端 A 的 `requires` **恒真**
> （实测 39/39 端点全 `["admin"]`）⇒ 「导航项写了一个契约里不存在的 id」
> **不会导致导航项消失**，只有点进去才报错。故必须有一条判据钉住
> "每个 `requires` 都能在生成物里查到" —— 否则这条纪律**从未被检查过**。
> 🛑 **修法：三种合法形态的【调用判定】+ 计数等式**
> ---------------------------------------------------------------------------
> `unsettled-surfaced` 重写为：引用了未完结端点 id 的文件必须至少命中之一 ——
> (a) 契约层枚举调用 `unsettledOf(` / `unsettledEndpoints(`；
> (b) 契约层统一出口且**参数逐字是未完结端点 id**：`contractNoteOf('approveRefund')`
> （🛑 **参数必须绑定未完结集合**：只判 `contractNoteOf(` 出现的话，
> `contractNoteOf('getCustomer')` 也会过 —— 那就退化成第 52 条的词满足）；
> (c) 渲染提示条 `<UnsettledBar ...>`。
> 并且**判据先剥注释**（第 41/51 条教训：注释里的词不算标注）。
> `nav-requires` 用两条式子 + **计数等式**：
> `requires` 只有两种合法形态（生成物里存在的端点 id 字面量 / 契约层导出的推导函数调用），
> **两者条数之和必须等于 NAV 里非 null 的 `requires` 条数** ——
> 等式防"新增第三种写法（内联三元 / 拼接）导致两条式子都不命中、
> 于是『没查到』被当成『没有问题』"。
> 🛑 **一个必须点明的边界：外壳不写端点 id 字面量，不是"绕过判据"**
> ---------------------------------------------------------------------------
> `App.tsx` 是外壳，导航表**无从渲染任何提示条**。若为过判据在导航表里写一行"标注"，
> 那行标注不产生任何界面效果 —— 等于把判据变成**纸面合规**。
> 本轮的处理是让外壳**真实承担横切职责**：在导航下方渲染一条**全局未完结提示**
> （进入本端第一眼就知道"这里有 8 个地方契约还没冻结"），数据源是
> `unsettledEndpoints()`（枚举函数，不是写死某个 id）；
> 并由判据**逼出**另一条纪律：`contract/scope.ts` 新增 `firstFrontierEndpointId()`
> 推导函数，让「未完结总览」导航项的依赖**不出现端点 id 字面量**
> （契约清空未完结声明时自动降级为 `null`）。
> 🛑 **通用规则**：**判据必须与本仓自己规定的权威写法对齐**；
> 判据"太宽"会放过错的，判据"太窄"会逼人删掉它 ——
> 两者都是失效，且**后者更不可逆**。
> 🛑 **同族第三处宿主：「页面覆盖 N 个端点」这句任务书自身的验收标准也是可自我声称的。**
> 逐条核验后发现 **D 域 5 个端点**是"封装了但从未接上界面"（D1/D3/D5-a/D5-b/D6）
> —— 在那之前"覆盖 39 个端点"是自我声称。⇒ 新增 ⑩ `endpoint-reachability`
> （出站**调用形态** + 封装函数被页面**调用形态**使用 + **计数等式** +
> **分层纪律**：出站只允许在 `services/`，页面/外壳不得直接出站）。
> 该判据**首跑即抓出自己太窄**（初版写死只扫 `domain.ts`，而 A1/A2 合法地在
> `session.ts`）—— 修法不是搬代码，而是**把出站层定义成本仓实际的样子**。
> 🛑 顺带修掉一个**门禁元层缺陷（第 24 条复发）**：反向验证脚本被中断后
> 残留的临时页会让判据**永远红**，而下一次运行报的是"基线不是绿的"
> —— **真实原因（残骸）完全看不出** ⇒ 脚本启动时**先清自己命名空间下的临时文件**。
> 详见下表第 55 条。
>
> 第 37~40 条属**第五类新缺陷**：**前四类问的是"实现/门禁/层间对不对"，第 37~40 条问的是
> "【判据凭什么有牙齿、门禁的载体与环境合不合格、喂进去的字节是不是同一份】"** ——
> 全部为**假绿**（比假红危险得多），且**没有一条能被"看测试是否全绿"发现**，只能由**受控注入**与**全量回归换前提**逼出。
> 🆕 **第 41~44 条属同一族（第五类）的扩展**：本轮由 `121` 在 **V20 上一次性抓出 4 条**，
> 其中 **41/42/43 为静默假绿**（自证每次打印"通过"而判据从未工作过），**44 为假红** ——
> 第一次出现**"同一条判据同时存在假绿与假红两个方向"（第 41 条）**，也是第一次出现
> **"判据被函数体内 RAISE 消息的字面量满足"**（此前第 12/37 条是注释）。
> 第 1~10 条为首次自证（fix-boot）所修；第 11~13 条为"能跑起来"专项所修
> （长期被单测掩盖，见下方教训）；第 14 条为源码树污染（运行期产物类）；
> **第 15 条由 V3 落地时的真库反向验证抓出**（属"文档自洽但语义不自洽"类，最能说明**反向验证的价值**）。
> **第 16~17 条**属"测试时红时绿 / 门禁绿而证据缺失"类。**第 18~20 条**是 **S1-6 新缺陷类**：
> **前三类问的都是"实现对不对"，第 18 类问的是"我们用来判对错的那份清单本身对不对"** ——
> **门禁与实现同源时，两者可以一起错**（当时 ADR-12 报 PASS）。它由"先建门禁再跑"的顺序逼出，
> 而非先有实现后补断言。
> **第 21~23 条**由 **S1-8** 逼出，属又一新类：**第 21 条问"门禁复述的契约事实对不对"；第 22 条问"门禁在自己的目标方言上看得见吗"；第 23 条问"门禁崩溃时会不会被读成成功"**。
> 三条都是**先造门禁、再让门禁照自己**才暴露的 —— 其中第 23 条最值得记住：**Python traceback 的退出码恰好是 1，与"违规"同码**，因此任何"非零即抓住"的判据都会把门禁自身的崩溃记成成功检出。
> **第 24 条**由 **S1-8 收尾复验**逼出，属**门禁的元层缺陷**：**门禁的证人本身没有自愈能力** —— 问的是"检验工具被中断后，会不会把自己的残骸当成被检对象的错误"。
> **第 25~28 条**由 **S2-5 证人套件**逼出，属**第四类新缺陷**：**前 24 条都在问"某一层对不对"，第 25~27 条问的是"层与层之间接得上吗"** ——
> 注册表与调用点各自演进无人同时看两侧（第 25 条）、控制器假设与服务层纪律相冲突（第 26 条）、
> 仓储校验与其入参 record 的既定契约相矛盾（第 27 条）。**三处的共同点：单测直调被测层，不经过层与层之间的真实装配，故"每层单看都对、合起来全坏"**。
> 它们**只能由"真容器 + 真签名 JWT + 真 servlet 请求"的 E2E 抓出**（S2-4 的 55 条断言全绿时它们仍在）。第 28 条为登记项。
>
> **2026-09-25 追加（S2-7）**：本轮**没有新增缺陷编号** —— 判定域的四处不匹配（`disposition` 归属自相矛盾 / `risk_flag` 库层无 CHECK /
> `confidence NOT NULL` 与"不可比返 null"夹逼 / F1 无 `requestBody`）**都不是"我们写错了"，而是上游（PRD / 契约 / V5 建表）之间本身不一致**，
> 故按登记处理（README §5.2 末节），不计入"已修缺陷"表。
> 但本轮**在方法论上留下一条与本表第 23 条同源、方向相反的教训**：
> **门禁自身的输出解码错误，会把"测试明明抓住了"读成"没抓住"** ——
> Maven 在中文 Windows 上的输出是 **GBK**，而脚本用 `errors="replace"` 按 UTF-8 解码；
> 该写法**永不抛错**，于是每条中文锚点都**静静地匹配不上**，16 条注入被误报为 `1/15 通过`。
> 而实测证据（失败测试的**英文方法名**）一直显示它们红在了**正确的方法**上。
> 与第 23 条合起来是一条完整规则：**门禁的"绿"和"红"都要能自证，两种颜色都可以是错的。**
>
> ⚠️ **补记（同一轮的文档侧）**：四处不匹配不计编号，但本轮**确实在文档里改了一处"我们写错的计数"** ——
> `开发移交包` / `开发清单` 的「**9 个 Maven 模块**」应为 **8**（见 §四 S2-7 对账段的订正说明）。
> 它属于**文档口径缺陷**（与第 28 条 `store_customer_service` 同栏目），因**不是代码/契约缺陷**，故不进上表编号，仅在 §四留痕。
>
> **2026-09-25 追加（S2-9 契约域 A）**：本轮**未新增缺陷编号**，但抓出 **3 处真缺陷**，全部属**第 25~27 条那一类**（"层与层之间接不上"）——
> 三处都**只在"真 JWT + 真 HTTP + 真库"下暴露**，编译 / 单测 / 定向测试三处全绿：
> ① **跨模块改动未整仓 `install`**（`dy-app` 依赖的是 `~/.m2` 里的 `dy-security-*.jar`；源码改了 ≠ 依赖方跑的是新代码）；
> ② **契约声明的响应集决定错误码**（A2 只声明 `200`+`401`，而前置校验顺序让 `requireTenant` 抢答成 `403`）；
> ③ **`List` 传给 varargs 不展开**（同源 SQL 只有 `count` 侧 500）。
> 三者分别固化为 `reverse-verification-gate` 技能的失效模式 **17 / 18 / 19**；另"双编码输出"固化为 **13-b**。详见 §反向验证「S2-9 契约域 A 的注入」。
> 即：**"层间接缝"这一类已连续两轮（S2-5、S2-9）成为唯一能产出真缺陷的来源**，而这正是"真请求 E2E"不可被单测替代的原因。
>
> **2026-09-25 追加（S2-10 契约域 B 全六行 B1~B6）**：本轮**新增缺陷编号第 29~36 条**（表由 28 → **36 条**），且**第 29~35 条里前六条全部只在"真容器 + 真签名 JWT + 真库"下暴露**（第 36 条是反向验证自身的元层缺陷，见下）：
> ① **第 29/30 条属"层间接缝"类**（权限码粒度不足 / 端点贴了与契约角色不匹配的码）—— 分别**第五、第六次同型**；
> ② **第 31 条是【组合自环】**（两个各自正确的守卫串起来后自相矛盾），**是本仓库目前最严重的一类**，因为它**改对了每一条、却让端点永不可用**（见下 §反向验证的 I4）；
> ③ **第 32/33 条是"只在真库/真请求下才走到的那段代码"**（SQL 参数未转型 / `Map.copyOf` 拒 null）；
> ④ **第 34 条是"放大器"** —— `GlobalExceptionHandler` **一个字节的堆栈都不落盘**，它本身不是业务缺陷，但它让 32/33 **无法定位**（先修它，才看见前两条）；
> ⑤ **第 35 条是构建环境缺陷**（离线仓库缺 `httpclient5` 传递依赖，`HttpURLConnection` 不支持 PATCH）。
> **方法论上留下三条**（与 §反向验证段同源）：**码级门禁看不见角色级缺口**（I1 首版"未被抓住"的真因）· **逐条守卫看不见组合自环** · **"未被抓住"必须先分辨"守护坏了"还是"注入假设错了"**。
> 🛑 **本轮新立一条硬纪律**：**凡 true-null 可空的快照/投影容器，`Map.copyOf` / `Map.of*` 一律禁用**（第 33 条；与 S2-8 的第 2 条"`Map.copyOf` 不保序"是**同一族的两个不同病**）。
>
> 🆕 **第 56/57/58 条**为 **2026-09-30 · 三端前端收口（三轮）** 新增 —— **第九/十/十一类新缺陷，同属"跨端隐式协议"一族**：
> **跨端协议片段是一个【集合】，契约得把它写全（URL 前缀 / 信封字段 / 鉴权头名 / 令牌前缀 / 幂等头名 / 追踪头名 / 分页处置），
> 而"写下的协议"与"各端实现的协议"是两件事 —— 中间丢项不报错**。
> 三条的接缝位置不同：⑥`servers[0].url` 只写在 env 注释里（端 C）· ⑦`X-Trace-Id` 契约**全域零声明** ·
> ⑧分页**只声明约束、没声明越界怎么办**（同一份契约下两个端点各走一路，静默夹逼能长期存活且全绿）。
> 详见下表第 56、57、58 条。
>
> 🆕 **第 59 条**为 **2026-09-30 · 第 58 条回归时顺带抓出** 新增 —— **第十二类：判据自身的第四种失效形态（不确定性/flaky）**。
> `assertFalse(raw.contains("72"))` 判的是 base64 密文**文本**，而 base64 含 `0-9`、密文随机 ⇒ 约 1% 概率偶然出现 ⇒ **不定期变红**。
> 判据失效四形态至此补齐：**太宽⇒假绿（52）· 覆盖面没跟上⇒静默漏检（53）· 太窄⇒假红（55）· 不确定性⇒随机假红（59）**。
> 详见下表第 59 条。
>
> 🆕 **第 60 条**为 **2026-09-30 · 第 58/59 条收尾复验** 新增 —— **第十三类：判据的第五种失效形态（守错了层级）**。
> `TOTAL N failures=0` 是一句**活断言**，却**全仓没人守**（真实 1211，文档 4 处全写 1204，而测试绿、构建 SUCCESS、三端自检绿、反向验证 13/13）。
> **判据守的是它写到的那一层，而漂移发生在它没写到的那一层**（`dy-crypto` 那个同名门禁只守模块内的 `应为 **N**`，`TOTAL` 是跨 8 模块求和）。
> 详见下表第 60 条。
> 🆕 **第 61 条**为 **2026-09-30 · 三端前端收口第三轮** 新增 —— **第十四类：契约里"不得模糊报错"只有【给人读的那一半】被实现**。
> 契约 `forbidden-403` 写「message 必须给出缺失项名称（不得模糊报错）」，且 `missing_items` / `denied_fields`
> 在契约里被 **4 处 prose 提及**；后端 `GlobalExceptionHandler` 也确实装配了这两个字段 ——
> **但三端出站层在错误路径一律丢弃 `body.data`、三端错误层从不读这两个字段名**，
> 端 A 的静态文案甚至对用户**承诺**「响应 data.missing_items 列出缺失项」而自己从不读它。
> ⇒ "给出名字"在客户端**完全没有到达**，而 tsc / vite / 门禁全绿（与第 57/58 条同族）。
> 详见下表第 61 条。
> 🆕 **第 62 条**为 **2026-09-30 · 第 61 条复验** 新增 —— **第十五类：判据的第六种失效形态（条件性假红 —— 依赖环境状态）**。
> `real-build` 判据里的 `vite build` 默认清空 `dist/`，其 `fs.rmSync` 被本机**安全删除守卫**拦下
> （单轮累计删除量超阈值）⇒ 判据**必然**变红，而红的参数是"本轮我已经删了多少文件"。
> 与第 59 条（flaky）同族但**随机源不同**：那条是随机数据，本条是**环境状态**。
> 登记同时修掉**同族第二处**：`build-reverse-check.mjs` 的磁盘备份目录会在第 N 组用例的清理语句上被同一守卫打死。
> 详见下表第 62 条。
> 🆕 **第 63 条**为 **2026-09-30 · 三端前端收口第四轮（端 B 触达判据落地）** 新增 ——
> **判据失效的第七种形态：覆盖缺口本身即缺陷的藏身处（端 A 有全链路触达判据，端 B/端 C 没有 ⇒ 16 个端点"封装好了但从未接上界面"而全部门禁全绿）**。
> 端 A 的 `a-check.mjs` ⑩ 早就有一条 `endpoint-reachability`（判两层调用链 + 计数等式 + 反向纪律），
> **端 B（`x3-check.mjs`）与端 C（`build-check.mjs`）都没有** ⇒ 实测端 B 的 29 个端点里 **16 个在 `services/` 层封装齐全、`src/` 全域零调用**，
> 而 `tsc --noEmit` / `vite build` / 既有全部门禁/反向验证**一律绿**。
> 它同时暴露一个更深的后果：**「封装了但没接上界面」不只让功能缺失，还让函数自身的缺陷也没有曝光面** ——
> `listScaleItemBanks` 的初版签名 `PageQuery & { age_group?: string }` 求交出**内部矛盾类型**（该函数在类型层面根本不可调用），
> 长期没被发现，正因为它从没被任何页面调用过（第 52/53/55 条同族：判据失效的第七个接缝）。
> 详见下表第 63 条。
> 🆕 **第 64 条**为 **2026-09-30 · 第 63 条复验（受控注入）** 新增 ——
> **第十六类缺陷的同族第二处：缺陷每往上一层就换一个藏身处 —— 第 63 条修的是「封装了但没接上界面」（链路断在【代码】层），
> 第 64 条修的是「接上了界面但界面【打不开】」（链路断在【装载】层）**。
> 端 C 的 `app.json.pages`（小程序只加载清单里的页面）、端 A/B 的外壳「NAV ↔ 渲染分支」，
> **三层防线一道都没守**。受控注入实测：把 `pages/assessment/assessment` 从 `app.json` 的 `pages` 里删掉，
> **文件仍在磁盘、调用链完好** ⇒ 第 63 条刚补的 `endpoint-reachability` 仍报
> `15/15 全部有完整调用链` / `BUILD OK` / `exit=0`。
> ⇒ **「页面覆盖 N 个端点」是可自我声称的，第三层（可到达）此前无人守。**
> 详见下表第 64 条。

| # | 缺陷 | 修正 |
|---|---|---|
| 1 | `ErrorCode` 整表偏离契约（自造 `1003`/`3002`，且 1002/3001/4001/4002/5001 与契约**语义冲突**） | 重写为契约 §2.0 的 11 个码逐字映射，**并携带 HTTP 状态**（内聚，替代 handler 里的 if-else 分段） |
| 2 | `GlobalExceptionHandler` 按码段猜 HTTP（`5xxx→502`） | 改为读 `ErrorCode.getHttpStatus()`；`5001 → 422` |
| 3 | **`RlsSessionAspect` 无效**：`ds.getConnection()` 另开连接（SET LOCAL 生效在别的连接上 → 业务查询永远零行）；且用绑定参数写 `SET LOCAL ... = ?`（PG 的 SET 不支持参数） | 改用 `DataSourceUtils.getConnection(ds)` 取**事务绑定连接**；值经 **UUID 白名单**校验后拼接；必须在事务内 |
| 4 | `TenantContextFilter` 未认证可用 `X-Tenant-Id` 自选租户（越权） | 无 token 即不建立上下文；`X-Tenant-Id` **仅用于一致性校验**，不采纳其值 |
| 5 | 信封输出 `traceId` 而非契约要求的 `trace_id` | 加 `@JsonProperty("trace_id")`；`missing_items` 改为挂在 `data` 下 |
| 6 | RLS 策略缺 `WITH CHECK` | 补显式 `WITH CHECK`（PG 虽默认沿用 `USING`，但拆分策略时写入校验会静默消失） |
| 7 | 测试为自证式（断言自身实现而非契约） | 重写为契约一致性测试 + 连接一致性测试 + 安全回归测试 |
| 8 | **过滤器写错误响应会绕过统一异常处理**：`Filter` 在 `DispatcherServlet` **之前**执行，抛异常**不被 `@RestControllerAdvice` 捕获**，会退化成容器错误页（破坏"所有响应都是信封"） | `TenantContextFilter` 内**直接写契约信封**（401/403 也是四字段） |
| 9 | **401 信封缺 `trace_id`**：①类级 `@JsonInclude(NON_NULL)` 吞掉 null 的 `trace_id`；②`TraceIdFilter` 未固定顺序，可能晚于租户过滤器 | ①`traceId` 字段+getter 加 `@JsonInclude(ALWAYS)`；②`TraceIdFilter` `@Order(HIGHEST_PRECEDENCE+10)`、`TenantContextFilter` `+100`，并做 trace_id 兜底生成 |
| 10 | **`JwtParser` 只 base64 解码、不验签**：任何伪造 token 都能通过（等于无鉴权），且失败静默 `return null` 使"验签失败"与"未登录"不可区分 | 删除该不安全类，代之以 `JwtVerifier`；**失败必抛 401**，绝不降级为匿名 |
| 11 | **应用开箱起不来（`DataSource` 装配失败）**：`dy-app` 只声明了 `postgresql` 驱动 + `flyway-core`，**缺 `spring-boot-starter-jdbc`** ⇒ Spring Boot 3.x 的 `DataSourceAutoConfiguration`（经 `JdbcTemplateAutoConfiguration` 引入，连接池由该 starter 带入）不生效 ⇒ 容器里**没有任何 `DataSource` bean** ⇒ 所有构造注入 `DataSource` 的 bean（`JdbcConfigHistoryRepository` / `JdbcConfigService` / `JdbcAuditLogService` / `RlsSessionAspect`）全部建不出来，启动即 `UnsatisfiedDependencyException`。**同一处还藏了第二个缺口**：`flyway-core` 自 Flyway 10 起已把各数据库方言拆成独立模块（实测 `flyway-core:10.10.0` 内只有 `internal/database/{base,h2,sqlite}`），缺 `flyway-database-postgresql` 时 Flyway 启动即报 Unsupported Database，**V1/V2 迁移根本不会执行、RLS 表不会落库** —— 那样即使应用起得来，"租户表 + FORCE RLS" 也不存在 | 两项均已补：`dy-app/pom.xml` 加 `spring-boot-starter-jdbc` 与 `flyway-database-postgresql`（版本均由 spring-boot BOM 统一管理，与文件内其它 starter 写法一致）；**未新增自定义 `DataSource` @Bean**，走 Spring Boot 自动装配（最小改动，符合 ADR-01 连接池模型）。`application.yml` 的 datasource 配置本就齐全（dev profile 指向 `diaoyuanyun_dev`），缺的自始自终只是**依赖** |
| 12 | **`/api/v1/demo/me` 匿名访问 500**：该端点用 `Map.of(...)` 组装上下文，而 `Map.of` 的键/值**一律拒收 null**；匿名请求时 `tenantId/role/scope` 全为 null ⇒ NPE ⇒ 被全局处理器兜成 `500 系统异常: NullPointerException`。但契约要求该端点返回标准信封（匿名时这三项为 null 正是 fail-closed 的**证据**，不是错误） | 改用允许 null 的 `LinkedHashMap` 逐个 `put`，三字段 key 恒在、值为 null；信封四字段不变。**未放宽任何鉴权**（`/customer`、`/refund` 仍按契约 403） |
| 13 | **带 `Idempotency-Key` 的 POST 一律 500（`HttpMessageNotReadableException`）**：`IdempotencyBodyCacheFilter` 用 Spring 的 `ContentCachingRequestWrapper`，而它是**单次消费**语义（已反编译 `javap -c` 确认：内部 `read()` 只透传底层流 + 旁路缓存，`getInputStream()` 非 null 即复用同一实例，**无 reset / 回放**）。幂等拦截器在 `preHandle` 读尽体算哈希后，控制器 `@RequestBody` 只能读到 EOF ⇒ 空体 ⇒ 500。该包装器的设计用途是"处理器读完后事后取字节打日志",**不支持"拦截器先读、业务后读"** | 新增 `CachedBodyRequestWrapper`：构造时把体整体读入 `byte[]`，每次 `getInputStream()` 返回**全新的** `ByteArrayInputStream`（游标归零），读多少次都是完整内容。`IdempotencyBodyCacheFilter` 请求侧改用它（响应侧仍用 `ContentCachingResponseWrapper`，其"业务先写、拦截器后读"语义本就正确）。修复后实测：同键重放返回 `X-Idempotent-Replayed: true` 且响应体与首次**逐字节相同**；同键不同体 409；非法键 400 |
| 14 | **测试期日志污染源码树**：`dy-app` 无 `src/test/resources/`，测试 classpath 上唯一的 logback 配置是 `dy-web/src/main/resources/logback.xml`，其默认 `${LOG_DIR:-logs}`；而**全仓库没人设置 `LOG_DIR`** ⇒ surefire 以模块 basedir 为工作目录 ⇒ **每跑一次 dy-app 测试就在源码树生成 `dy-app/logs/{dy-app.log,dy-audit.log}`**。本仓库**既非 git 仓库也无 `.gitignore`**，这是实打实的源码树污染；dy-web 那份配置的注释还承诺过"LOG_DIR 可重定向以便测试隔离"——**承诺与事实不符且无任何断言会发现** | 新增 `dy-app/src/test/resources/logback-test.xml` 把输出导向 `target/test-logs/`；并以 `TestLogOutputIsolationTest`（2 断言）守死：① 源码树不得有 `logs/` + ② **正向对照**（日志必须真的写进 `target/test-logs/` 且含本次探针标记，防"把日志全关掉"式假绿）+ ③ 结构层（test 配置存在且**不复刻** `DY.AUDIT`/`DY.APP`/`additivity`，避免出现两份会漂移的分离规则）。**注意**：本条属"运行期产物污染源树 ⇒ 后续构建变红"的**新缺陷类**，与 11~13 的"单测掩盖"不同 |
| 15 | **`band_telemetry.data_source` 默认值不满足自身 CHECK**（字典 §2.17 字段表 + §附 DDL 两处）：写 `DEFAULT 'band'`，而 CHECK 取值集为中文 `('手环','未接入')` ⇒ `'band'` **永远非法** ⇒ 任何省略该列的 INSERT 必报 `23514` | **由真库测试反向验证抓出**（非从文档读出）：V3 落地后 `RlsBEntityIsolationTest` 三条同时红（可见性 0 行 / 合法 `no_open` 被拒 / 幂等键用例报 `data_source_check`），根因均为 seed 省略 `data_source`。字典两处与 V3 同步改为 `DEFAULT '手环'`（**取值集与口径不变**，最小修正）。并在字典 §2.17 立【缺陷登记】块 + 登记**待裁点**（取值语言：中文 vs 英文 token，见下） |
| 16 | **日志隔离门禁不是 hermetic 的（判据用错：状态 vs 因果）**：`TestLogOutputIsolationTest` 断言 `Files.exists(logs/)`。该判据读的是"磁盘上此刻有没有那个目录"，即**全历史所有操作的累积状态**，与"本次测试运行有没有写错地方"无关。后果双向皆错：① **假红 / 门禁漂移** —— 只要此前有人手工启动过一次（`mvn spring-boot:run` 同样命中 `${LOG_DIR:-logs}` 默认值，实测留下 24756 字节的 `Starting DyAppApplication … Tomcat 60518→18080`），目录就一直在，之后**每一次**测试运行都变红，而红点指向不到任何代码，只能靠人工考古文件时间戳定位；② **假绿** —— 若 surefire 工作目录不是模块 basedir，相对路径 `logs` 就落在别处，断言恒绿。这正是"测试时红时绿"的典型形态 | ① **修生产者（D2）**：`dy-app/pom.xml` 的 `spring-boot:run` 显式注入 `LOG_DIR`/`APP_LOG_FILE`/`AUDIT_LOG_FILE` 指向 `target/`；补 `.gitignore` 作第三道防线。② **把判据从状态改为因果**：断言改为"本次运行注入的探针标记**不得出现在源码树任何日志文件里**"，并返回**命中文件的绝对路径**（红态直接指出污染文件）。③ 追加**前提断言**：`basedir` 必须存在且等于当前工作目录，把"判据前提被破坏"这一静默假绿变成一条说明清楚的红。④ 保留正向对照（防"把日志全关掉"式假绿） |
| 17 | **跨租户拒绝的审计留痕在真实部署下【恒不落库】**（S1-3 验收④ 的核心缺陷，且是"测试全绿也发现不了"的那一类）：`JdbcAuditLogService.append()` 有一条硬前置——必须在事务中执行（它取 `pg_advisory_xact_lock` 作链写入的跨实例互斥量，故显式检查 `conn.getAutoCommit()`，为 `true` 时直接抛异常）。而调用它的 `TenantContextFilter` 拒绝分支位于 **servlet 过滤器链，没有任何 Spring 事务**，连接必然 `autoCommit=true`。于是"直接调 `append`"这个看似正确的实现会 **100% 抛异常**，被 catch 吞成一行 WARN：**请求照旧 403、功能"看着正常"、审计表一行都没有**——合规证据静默缺失 | `TenantRejectionAuditBridge` 显式用 **`REQUIRES_NEW`** 事务包住 `append`（选 `REQUIRES_NEW` 而非 `REQUIRED`：本调用点无事务，两者等价，但若将来有人误在业务事务内调用，`REQUIRED` 会让审计记录随业务回滚一起消失，而"某次访问被拒绝"是**客观发生过**的事）。新增 `RlsRejectionAuditTrailGateTest`（真库，5 断言）端到端守死，并以 **RV-B** 反向验证证明该门禁有牙齿（去掉事务 ⇒ 5 中 4 变红，`expected: <1> but was: <0>`） |
| 18 | **客户端权限白名单与冻结契约不符，且没有任何东西在查**（S1-6 的核心缺陷）：`client-package/api/clientPaths.js` 8 条白名单**只有 2 条**对得上契约——① `/api/v1/band/derived` 是契约 E4 的**客户端明确拒给端点**（`x-callable-roles: [therapist, meridian, admin]` + `x-client-explicitly-denied: true`）被包自己声明为可调；② `/customer/profile`、`/band/state`、`/form/daily` 三条**名字写错**（契约 B4 `/customers/{id}`、E6 `/customers/{id}/band/sync-status`、D3 `/customers/{id}/daily-reports`）；③ `/receipt/list`、`/receipt/detail` **契约 45 端点中根本不存在**。**当时 ADR-12 扫描报 PASS**——因为词表扫描问的是"违禁词进没进包"，问不出"包自己的权限清单是否许可了一件违禁的事"。**权限清单不是词汇，是主张** | 新增 `compliance/client-zero-derived-gate.py` 四面结构门禁：① 禁字段集**机械派生自 `band-visibility-matrix.json`**（不设第二张手工清单，避免重造缺陷成因）；② 白名单**子集检验**逐条比对契约 `x-callable-roles`；③ 契约 `x-client-forbidden` 路径不得入包；④ 门禁自检（拒绝为空集/错误仓库报 PASS）。白名单重写为 **13 条**逐字转录契约并附契约行号。**最强证据**：把修复前的原始 `clientPaths.js` 放回跑门禁 → 报出**全部 6 条真实缺陷**，文件按字节还原（`diff` 一致） |
| 19 | **ADR-12 词表对"派生"概念无判别力**：`derived` 及 4 个派生字段名（`adherence_dimension_score` / `a3` / `refund_eligibility` / `improvement_rate`）**根本不在 `scan3_derived.words`** —— 概念词压根不是词条，故"派生字段名进包"这一整类泄漏**无扫描面覆盖**。另：新增词条必须**与可见性矩阵交叉机械化**，否则词表与契约会各自漂移 | `scan3_derived.words` 23 → **28 词**，新增组注明"不只是加词，而是与可见性矩阵交叉需机械化"；禁字段集改由门禁从矩阵**机械推导**，词表只作 ADR-12 侧的补充面 |
| 20 | **连字符写法绕过全部多词词条**（与第 19 条同源的更隐蔽形态，实测：`gap_reason`/`effect_verdict`/`blood_sugar`/`AS_refund` 的 `_` 写法全 HIT，**`-` 写法全 MISS**；单词词条 `refund` 只是"侥幸"命中，因连字符恰落在词与后缀之间）。根因：`-` 不在 `_TOKEN_RE` 里 ⇒ tokeniser 把它劈成两个 token ⇒ **三级匹配规则没有一级能跨 token 重组**。而 `-` 在 JSON key / URL slug / CSS class / YAML key 里是合法分隔符，真实泄漏用这种写法**至少同样自然** | **放宽 token 而非放宽匹配规则**（`_TOKEN_RE` 补 `-`）：`gap-reason` 成为单 token，段序列 `[gap, reason]` 被既有 adjacent-run 级命中（匹配语义**一字未改**）。**实测零误报**：16 个包内真实连字符标识符（`no-data-today`/`client-sync-state`/`read-only`/`end-to-end`/`self-check`/…）三面全静默；`T5` 加 8 条双向断言 |
| 21 | **契约事实被第二处手工重述而无人比对（S1-8 的核心缺陷，同源失效第三例）**：① `clientPaths.js` 把 `/customers/{id}/visits` 引注为 **D1**，而 D1 是 POST、`x-callable-roles: [therapist, meridian, admin]` **不含 client**（条目对、**引注错**；S1-6 按**路径**聚合角色，结构性失明）；② `band-visibility-matrix.json` ④ 组列 `adherence_dimension_score` / `a3` —— 这两个名字在**任何**契约 schema 中 **0 次出现**（上游语义母本逐字用 `a3_applicable` / `a3_value`）。矩阵自称**派生导出**却违背其声称的上游与契约 | 新增 `compliance/contract-conformance-gate.py` 五面门禁（引注/错误码/枚举命名空间/客户端不可见字段名/矩阵字段表），**逐条比对契约真相源**；`clientPaths.js` 引注 D1→**D2**；矩阵 ④ 改为契约字段名逐字，并加 `_note` 声明"本清单是派生导出，不是第二张手工清单"。**最强证据**：门禁首跑即报出全部 3 条，且修复方向由"上游语义母本"确定而非由矩阵自证 |
| 22 | **门禁在自己最该覆盖的方言上失明（camelCase 盲点，两个门禁同病）**：契约字段是 snake_case，客户端包是 **JavaScript**，承载同名事实的自然写法就是 camelCase。实测（修前）：`refund_visibility` **抓**、`refund-visibility` **抓**、**`refundVisibility` 漏、报 PASS**；S1-6 同样：`effect_verdict` 抓、**`effectVerdict` 漏、报 PASS**。即两条门禁**恰好在最可能被使用的写法上失明**，而 S1-6 的 docstring 还声称镜像 ADR-12 的匹配语义 —— **ADR-12 本就拆 camelCase（`_CAMEL_RE`），只有这两条漏了** | 两个 `identifier_segments` 补 camelCase 拆分（`(?<=[a-z0-9])(?=[A-Z])` + 首字母缩略 `(?<=[A-Z])(?=[A-Z][a-z])`），**向既有正确语义对齐**而非新发明；标识符内的数字/字母连续段保持完整（`a3Value`→`[a3,value]`）。**实测零误报**：绿树仍 PASS；S1-6 加证人 **W1b**（camelCase 形态）钉住，防回归 |
| 23 | **门禁自身崩溃被读成"成功检出"（两个独立形态，都在 S1-8 暴露）**：① `yaml.safe_load` 抛的 `yaml.YAMLError` **不是 `ValueError` 子类**，`load_yaml` 只捕后者 ⇒ 契约 YAML 不可解析时 `ParserError` 逃逸为 **exit 3（内部错）**，恰与该分支目的（"契约不解析"＝配置错＝**2**）相反；② FACE 4 的 finding 缺 `kind` 键，报告打印器硬取 `v["kind"]` ⇒ `KeyError`；而 **Python traceback 退出码恰好是 1，与"违规"同码** ⇒ 门禁崩溃被记成检出。**同一根因（错误与成功同码）在 S1-6 已出现过一次** | ① `load_yaml` 对称于 `load_json`：`yaml.YAMLError → GateConfigError`（exit 2），并写明"YAMLError 不是 ValueError 子类"这一坑；② 打印器改 `v.get("kind","unclassified")`（**忘了 kind 的 finding 仍须被报出**），finding 补 `文件:行` 使其可定位；③ 证人自身两处"空过守卫"（`violations=0` 与面名 kind 按**整段输出**匹配，会命中 FACE AUDIT 表而永真）改读**裁决行**与 **VIOLATIONS 段** |
| 24 | **门禁的证人套件没有自愈能力 —— 中断后把自己的残骸当成被检对象的错误（"时红时绿"的真凶；跨套件形态）**：收尾复验时四套 Python 证人出现**首轮红、后轮绿**的不稳定，且 `mvn -o validate` 偶发 `[COMPLIANCE-GATE] FAIL violations=1`。定位 = 套件被中断（Ctrl-C / CI 超时 / SIGKILL，`finally` 不覆盖）后**残留自身注入的探针文件**，而残留守查看不见自己会产生的探针名。**跨套件形态（本次修复的核心）**：残留识别用的是**前缀白名单** `("__inject", "_probe")`，而 **ADR-12 证人的 T8 探针名是 `__scope_probe_client.js`**（前缀 `__scope`，不在名单内）—— 该文件一旦被中断留在 `client-package/constants/`，**S1-6 与 S1-8 证人的 reclaim 与守卫都看不见它**，它就一直躺在被扫描面上，被 **ADR-12 门禁读成真实违规**（`scan1_refund hits=1`）⇒ 间歇性构建失败，且报错指向"仓库有问题"而非"有残骸"。**附带的两个诊断陷阱**：① 残留守卫若扫全树，会把合法 test fixture（`dy-audit/.../probe_owner_revoke.sql`）误报为残留，而"对正确内容叫狼"的守卫会被关掉；② T1 对已存在探针报"injection target already exists"，**误导为"文件名撞了"**而非"上一轮残骸" | **三层修复（三套证人统一，向 ADR-12 既有正确语义对齐）**：① 识别面从"前缀白名单"改为**「名字含 `probe`」标记规则**（`_is_probe_name`），并把残留检查**作用域收窄到扫到的 `client-package`**（避免 fixture 误报）；② T1 区分**自认领残留**（内容逐字一致 → 清掉）与**外来文件**（不一致 → 报错且**不覆盖**）；③ 三套证人统一加**启动期 `reclaim_own_leftovers()`**（首个断言前调用），并各加一条钉住识别力的断言（ADR-12 `T11` 12 断言 / S1-6 `W9` 13 断言 / S1-8 `W10` 18 断言）。**验证**：植入 `__scope_probe_client.js` → 修复前跑单套件后它**仍在磁盘上**、下一次 `mvn validate` 报 `FAIL violations=1`；修复后两会话均 `reclaimed 1` → W0 基线绿 → 残留 `[]`。**反向验证**：把识别器退回旧前缀逻辑，`W9`/`W10` 立刻变红并**精确指名** `__scope_probe_client.js`。**稳定性**：六轮 `mvn validate` + 六轮四套件全绿、残留恒 0 |
| 25 | **`PermissionRegistry` 未登记退款域权限码 ⇒ 退款域 G1~G5 对全部角色 403（S2-5 抓出，真实生产缺陷#1）**：6 处 `@RequirePermission("refund:*")` 声明存在，而注册表**零登记** ⇒ `registry.hasPermission(role, perm)` 对**全部角色恒 false** ⇒ `PermissionInterceptor` 一律抛 `VISIBILITY_DENIED(2001) "权限不足"`。**结构性盲区**：单测直调服务层**不经过拦截器链**，故 S2-4 的 55 条断言全绿也看不见它 | `PermissionRegistry` 按契约域 G 的 `x-callable-roles` **逐条登记**：`manager`/`area`/`hq` 各得 `refund:read`+`refund:write`+`refund:approve`；`meridian` 得 `refund:read`+`refund:write`（**刻意不给 `refund:approve`**，G4 白名单须真拦得住）；`client`/`therapist`/`store_customer_service` **刻意不给**（纵深防御）。配约 60 行专节 javadoc 记录缺口形态 |
| 26 | **控制器把 `Instant.now()` 塞进入口 B 的 `meridianAcceptedAt` ⇒ 入口 B 立案 100% 失败（真实生产缺陷#2）**：`create()` 无条件填第 9 参，命中服务层"入口 B 不接受任何 `requested_at` 字段"的一票否决。**该缺陷的失败信息把排查引向不可能修好的方向**（报的是"入口 B 不得携带受理时间"，而调用方以为自己没传） | 抽出 `entry`，第 9 参改 `entry.isStoreDeputyEntry() ? Instant.now() : null`，并配 20 行注释说明成因与误导性错误消息 |
| 27 | **`insertStatement` 的 `requireNonNull(row.recordedAt())` 与 `forInsert` 契约矛盾 ⇒ 任何带客户原话的立案 100% 失败（真实生产缺陷#3）**：`RefundStatementRow.forInsert` 的既定契约就是产出 `recordedAt = null`（写路径忽略、读路径由库回填），而仓储对其 `requireNonNull`。**根因还有第二层**：库列是 `TIMESTAMPTZ NOT NULL DEFAULT now()`，而 **PostgreSQL 的 `DEFAULT` 只在列被【省略】时生效** —— 显式绑 `NULL` 会直接违反 `NOT NULL`；故即便删掉 Java 侧校验，SQL 仍须兜底 | ① 删掉该 `requireNonNull`（改为"刻意【不】校验"的说明注释，真正的守门是"来源合法"）；② `INSERT_STATEMENT_SQL` 的 `recorded_at` 由 `?` 改为 **`COALESCE(?, now())`**；③ `RefundStatementRow` 类注释补《🛑 `recordedAt` 的三个既定事实》，防第三个人再加回"非空校验" |
| 28 | **`store_customer_service` 在契约中无端（登记，不代拍）**：`RefundAudienceRole` 含 `STORE_CUSTOMER_SERVICE`，而契约 `x-callable-roles` 全部端点中**无该角色**。已用门禁钉住"它不持有任何 `refund:*`"，但"该角色是否应在契约中拥有端点"属契约侧裁定 | 见 §5.2 欠账 |
| 29 | **权限码粒度不足 ⇒ 域 B 四个写端点对一线角色全部 403（缺口 A，**第五次同型复发**）**：`customer:write` 被**三类语义共用**（域 B 档案写入 / E5 小程序探测 / 题库导入组卷），而注册表只把它发给"管理层级 + 遗留大写码"，**不含契约 `x-callable-roles` 点名的 `therapist` / `meridian`** ⇒ B1/B2/B3/B6 对一线角色恒 403。**结构性盲区**：码级门禁只问"这个码**有没有主**"，`customer:write` 有主 ⇒ 绿（见 §5.1 段首"码级 ≠ 角色级"） | 修法 = **拆码，不是补码**：新立 **`customer:archive`** 承载"档案写入"（发给契约点名的**全部**角色，含 `therapist`/`meridian`）；`customer:write` 保持承载"内容资产写入"（仅管理层级 + 遗留大写码）。🛑 **为什么不让一线角色直接持有 `customer:write`**：那会顺带把"题库导入组卷"的写权发给一线角色 —— **修一个缺口时开出另一个更大的口子**。配 `PermissionRegistry` 专节 javadoc（第五次同型复发登记）。**追认点见 §5.2 末节** |
| 30 | **端点贴了与契约角色不匹配的码 ⇒ E5 合法客户端探测恒 403（缺口 B，**第六次同型复发**）**：`BandAvailableDatesController`（E5 `POST /band/available-dates`）贴 `customer:write`，而契约 `x-callable-roles: [client]`，且注册表**刻意不登记 `client`** ⇒ 任何权限码都会让合法客户端请求恒 403。**它为何躲过全部既有门禁**：① 码级门禁只问"码有没有主"，`customer:write` 有主 ⇒ 绿；② `DerivedVisibilityE2ETest` 里那条对 E5 的 403 断言，命中的是**派生字段拦截器**（排在权限拦截器**之前**，因请求体含 `as_value`）—— **两条完全不同的路径归同一个 `2001`，把缺口掩盖了** | 修法 = **摘码**（与 A2 `/auth/me` "刻意的缺第三层"同款）。🛑 **摘码 ≠ 放弃越权防护**：请求体里的派生键名由 `DerivedVisibilityInterceptor` 独立承担（先于控制器执行）。配新增守护用例 `legitimate_client_probe_without_derived_keys_must_reach_200` **双向钉住**（合法请求 200 / 带派生键 403）。**安全评审点见 §5.2 末节** |
| 31 | **🔴🔴 自环门禁（本仓库目前最严重的一次同型复发）⇒ B2 建档端点对全部角色 100% 恒 403**：`CustomerService.createCustomer`（B2 `POST /customers`）的门禁链里含 `CustomerGateGuard.assertAdmissionChain`，而该方法 = `assertScreeningResult` + **`assertProfiled`**，**"已建档"正是 B2 的产出** ⇒ **逻辑自环**，任何角色都不可能满足。⚠️ **更糟的是它的错误消息与真实成因完全相反**：报出的缺失项是 `PROFILED`，排查者会去"补建档"，而**正确动作是"这次调用本就该成功"**。**它躲过了全部静态检查**：编译通过、单测全绿、码级门禁全绿、连"B1 通不通"的检查也绿 —— 因为 **B2 与 B1 是两条独立的调用链**。**通用规则：逐条守卫看不见"组合自环"** —— 两个**各自都正确**的守卫串起来后自相矛盾，没有任何单条守卫能看见它 | 修法 = **B2 只调 `assertScreeningResult`**（准入链的正确形态是"先筛查通过、再建档"）；`assertAdmissionChain` 标 **`@Deprecated`** 并**保留**为**陷阱登记**（不删 —— 防后人再踩，Javadoc 逐字写明"**自环陷阱、当前零调用点**"）。反向验证 I4 注入"改回 `assertAdmissionChain`" ⇒ E2E **立即变红** |
| 32 | **SQL 参数未转型 ⇒ uuid 列收到 varchar ⇒ B2 恒 500**：`CustomerLedger.UPDATE_PROFILE_SQL` 的 `owner_store_id = ?`（`serving_store_id` 同）缺 `::uuid` ⇒ PG 报 **`42804`**（"字段 `owner_store_id` 的类型为 uuid, 但表达式的类型为 character varying"）。**成因**：JDBC 的 `setString` 把参数按 **varchar** 类型发送，而 PG 的隐式 `unknown → uuid` 转换**只对未定型参数生效**；一旦参数被声明为 varchar，`varchar → uuid` 就是**显式**转换、不再隐式。**该缺陷只在真库下暴露**（H2 / 单测看不见） | `owner_store_id = ?::uuid` + `serving_store_id = ?::uuid`。🛑 **硬纪律：uuid 列一律 `?::uuid`，一个都不能漏**（已固化为失效模式 **20**）。反向验证 I5 复发 ⇒ B2 恒 500 被抓 |
| 33 | **`Map.copyOf` 拒收 null 值 ⇒ B6 修订留痕恒 NPE**：`IntakeProfileRevisionRow` 用 `Map.copyOf(snapshot)`，而 `intake_profile` **每一列都可空**（未采集字段就是 null），`snapshotOf()` 忠实保留它们 ⇒ **NPE** ⇒ B6 恒 500。**该缺陷也只在真请求下暴露** | `Collections.unmodifiableMap(new LinkedHashMap<>(...))`（**容忍 null 值**）。🛑 **为什么不滤掉 null**：那会让快照变成"只记录填过的字段"，与"**完整快照 vs 增量**"的纪律直接冲突（字段后来被清空就无法从历史看出）。**与第 2 条（S2-8 的 `Map.copyOf` 保序缺陷）同族、不同病**（已固化为失效模式 **21**）。反向验证 I6 复发 ⇒ B6 恒 500 被抓 |
| 34 | **🔴 塌掉的可观测性（本轮的"放大器"）**：`GlobalExceptionHandler` 对未捕获异常**一个字节的堆栈都不落盘** —— 只把 `ex.getClass().getSimpleName()` 塞进响应体加一个 `trace_id`。**对外只回显类名是对的（契约纪律），错的是服务端也不留**：运维拿着 `trace_id` 在全仓库日志里搜不到任何对应行，**无法定位任何一次 500 的真因**。第 32/33 条之所以排查困难，**正是被它放大**：只报出异常**类名**，而类名既不含 SQL 也不含列名/参数 ⇒ 排查被迫退化为"**读源码猜哪条 SQL 写错了**" | 修法 = **分级留痕**：`BizException` 且 HTTP ≥ 500 → `log.error`（**带堆栈**）；其余 `BizException`（4xx 正常业务拒绝，如 403 门禁）→ `log.warn` **一行、不带堆栈**（避免正常拒绝刷爆日志）；其它任何 `Exception` → `log.error`（**带完整堆栈**）。两者都带 `trace_id` 与 `uri`，使"响应体里的 `trace_id`"与"服务端日志行"**可双向检索**。🛑 **它是 32/33 的"定位前置"** —— 后续任何"真请求 500 却查不到原因"的场景，先确认这一层是否完好 |
| 35 | **`httpclient5` 的传递依赖在离线仓库缺失 ⇒ 整仓 `install` 被打断（构建环境缺陷）**：`TestRestTemplate` 默认用 `SimpleClientHttpRequestFactory`（`HttpURLConnection` **不实现 PATCH**）⇒ 报 `Invalid HTTP method: PATCH`，B6 与域 E 的若干 PATCH 行"**只能被绕过或不测**"。修法第一步（加 `httpclient5` test 依赖）之后撞到第二层：Spring Boot BOM 给的 **`5.2.5` 在本地仓库不存在**（其传递依赖 `httpcore5:5.2.5` / `httpcore5-h2:5.2.5` 亦缺） | ① `dy-app/pom.xml` 显式钉 `httpclient5:5.2.3`（其传递依赖为本地已有的 `httpcore5:5.2.4`）；② 🛑 **只降 `httpclient5` 自身不够 —— BOM 仍把 `httpcore5` 拉回 5.2.5**，故须在 `dy-app` 的 `<dependencyManagement>` 里**成组覆盖 BOM**（`httpcore5:5.2.4` + `httpcore5-h2:5.2.4`）。**这是一条"离线构建"的通用坑**（已固化为失效模式 **35**） |
| 36 | **反向验证的锚点可以"匹配上了、但匹配到的不是你以为的那一处"（反向验证自身的元层缺陷）**：I1/I2 的锚点原为单行 `@RequirePermission("customer:archive")`，而该行在 `CustomerController` 中**出现 4 次**（B1/B2/B3/B6）。`replace(..., 1)` 命中**第一个**，**恰好**是 B1 ⇒ "7/7 PASS"是真的，但**正确性建立在"B1 恰好排在最前"这个巧合上**：一旦有人把 B2 挪到 B1 之前（**纯排版改动、不影响任何行为**），注入的就变成 B2，而 `must_see` 断言的是 B1 的用例 ⇒ **I2 会报"未被抓住"，而真因是注错了地方**。（与第 21 条"门禁复述的契约事实对不对"同族：**问的是"证据本身指向的是不是它声称的那件事"**） | ① **脚本新增锚点唯一性检查**：`old` 在源码出现 **>1 次即判 `ANCHOR-AMBIGUOUS` 并置 FAIL**（拒绝给出结论，而非"改第一个"）；② I1/I2 锚点改为"注解 + 正下方方法映射"**两行**（`@RequirePermission(...)\n    @PostMapping("/screening-records")`），语义自明且**实测唯一**；③ 顺带补上 S2-9 遗留的"还原按字节"缺口（见下）。🛑 **通用规则：反向验证的锚点必须带足上下文至唯一 —— "能匹配上"不等于"匹配到了你以为的那一处"。** 该检查**上线即抓出**本条，证明它不是"加个保险"而是**当场暴露了既有结论的隐含前提** |
| 37 | **🔴 判据被【注释】满足 ⇒ 并发正确性判据从未生效（V18/V19 `(c4)`）**：PG 把 `AS $tag$ … $tag$` 之间内容**原样**存进 `pg_proc.prosrc`，**注释也在里面**。故任何"函数体里出现过 X"形态的判据都能被一行 `-- X` 满足。（与第 12 条"`contains("@Aspect")` 把注释也算命中"**同源复发**，但这一条是**在交付的迁移自证里**、且对象是**并发正确性**） | 处置 = **剥注释 + 锚点【并用】**：逐行 `regexp_replace(ln, '--.*$', '')` 得代码态 `v_*_code`；再以 `INSERT INTO x\M` / `UPDATE x\M` **起锚**、`[^;]*` **不跨语句**。🛑 **两者缺一不可**：只剥注释不锚 ⇒ 同函数另一条语句仍可满足；只锚不剥 ⇒ 注释仍可满足。新增**常驻元门禁 `(C7/C7b)`**：`v_\w+_body\s*[!~]\s*'` 违规出现即红 + 代码构造段必须存在（防"为了过门禁把剥注释整段删掉"）。**教训：假绿比假红危险得多；"判据被注释满足"比"判据无锚点"更隐蔽** |
| 38 | **🔴 V18 缺 `(a0)/(a1)` 能力守卫 ⇒ 在 `BYPASSRLS` 角色下 4 条 RLS 判据全部失去效力却仍打印"通过"**：自证块只断言了"策略存在"等结构事实，从未断言**执行者角色能不能被 RLS 约束** ⇒ 用超级用户跑时，`(b)`~`(e)` 那 4 条**依赖 RLS 的判据**形同虚设而全绿 | 从 V19 **回补** `(a0)` 角色能力守卫（`rolsuper` / `rolbypassrls` 任一为真即 `RAISE`：**"本自证【依赖 RLS 的 4 条判据全部失去效力】"**）。🛑 **不得放宽** —— 放宽即把"角色前提不成立"重新变回静默假绿。**通用规则：门禁的断言必须同时断言【前提】（它凭什么有牙齿）** |
| 39 | **🛑 门禁载体的【角色前提】缺陷 ⇒ 全量回归报红（本轮由全量回归而非定向门禁抓出）**：`RlsGateSupport.provisionRealDatabase()` **第 2 步用 `SUPER_USER` 应用整条迁移链** ⇒ 门禁**断言**用非超级用户、**预置**却用超级用户。当被测物第一次断言**执行者角色**（第 38 条的 `(a0)`）时立即暴露：`V18 自证失败(a0): 当前角色 postgres 拥有 BYPASSRLS …` | `CREATE DATABASE :dbname OWNER :rolename;`（库 owner 改为非超级用户应用角色）+ 第 2 步改用 `APP_USER` 应用迁移。**教训：门禁载体的角色选择【也是被测交付物的前提】—— 载体与环境不合格时，门禁的"绿"是环境的绿、不是交付物的绿**。🛑 定向门禁 23 例当时**全绿**（它没触发 `(a0)`），只有全量回归才发现 ⇒ **"换掉前提再跑一次"是发现"证据效力前提不成立"的唯一手段** |
| 40 | **🛑 探针 I/O 的 Windows 换行污染 ⇒ 逐字比对报出【完全归因错误】的红**：`tempfile.NamedTemporaryFile("w", encoding="utf-8")` 在 Windows 上**默认把 `\n` 译成 `\r\n`** ⇒ 喂给 psql 的文本 ≠ 磁盘原文 ⇒ 重建库 `pg_proc.prosrc` 变 `\r\r\n` ⇒ 一切"逐字比对"（117 ⑤ checksum / 119-120 C6 净效果）**在一个与"迁移文本是否真的变了"无关的维度上报红**，并把排查引向"V18 文本有问题"这个错误方向 | 全部写入点补 `newline=""`（117/118/119/120 共 **7 处**）。🛑 **通用规则：逐字比对报"不同"时，先证明"你把同一份字节喂进去了"** —— （117 因用 `\i 磁盘原文` 才没中招，正是这个对照暴露了根因）**已固化为失效模式 27** |
| 41 | **🔴 判据被【函数体内 RAISE 消息的字面量】满足 ⇒ 双面失效（V20 `(b6)`，假绿 + 假红并存）**：V20 函数体里那条跨租户撞号的 `RAISE`，其**给运维看的解释文本**里逐字写着 `INSERT ... ON CONFLICT (archive_id) DO NOTHING`；而 `(b6)` 的初版判据是**裸的** `ON\s+CONFLICT\s*\(\s*archive_id\s*\)`（无锚点、未剥注释）⇒ **那条消息的字面量就能满足它**。实测：把真 `INSERT` 改成 `ON CONFLICT (tenant_id, archive_id)`（**错误的**改法，跨租户 RAISE 成死代码）⇒ 自证**照常"通过"**；改成 `ON CONFLICT ON CONSTRAINT case_archive_pkey`（**语义等价的正确**改法）⇒ **报假红** | ① 判据**带锚点**（`INSERT\s+INTO\s+case_archive\M` 起锚、`[^;]*` 为界）；② **接受两种语义等价形态**（列推断 / 约束名推断）—— 断言的对象是"**推断目标不含 `tenant_id` 这个语义**"，不是"某一种语法写法"；③ 补一条 **(b6-负向)**：锚点段内不得出现含 `tenant_id` 的推断目标（正向判据兜不住 `(archive_id, tenant_id)`）。🛑 **与第 12/37 条同源（"判据被不是代码的地方满足"），第一次出现在【RAISE 消息字面量】上**；🛑 **假红也是缺陷** —— 过度收紧会把正确的等价重构判成错 |
| 42 | **🔴 只断言"抛了异常" ⇒ 被【库层防御纵深】掩盖（V20 `(e3)`，静默假绿）**：`(e3)` 初版只断言"客户不存在时确实抛了"。实测：把函数里 (4) 的客户存在性检查改成 `IF false THEN` ⇒ `INSERT` 落到库层复合外键 `case_archive_customer_id_fkey`（V16 的成果）⇒ 收到 **23503** ⇒ 内层 `EXCEPTION` 捕获 ⇒ 自证**照常"通过"** | 断言 **`SQLSTATE = P0001` 且消息含"客户"**（同款补强到 `(e4)/(e6)/(e10)/(e11)`）。🛑 **通用规则：行为断言必须断言【拒绝的 `SQLSTATE` 与理由】，不能只断言"拒绝了"** —— 拒绝**仍然发生**，只是**理由完全不同**（23503 vs P0001），而**函数可被人绕过（直接写 SQL）**，归因质量只能来自函数层。`(e3)`（P0001）与 `(e8)`（23503）**互补，缺一不可** |
| 43 | **🔴 幂等重放传入与首次【完全相同】的内容 ⇒ 对 `ON CONFLICT ... DO UPDATE SET` 毫无反应（V20 `(e2)`，静默假绿）**：`(e2)` 初版第二次调用传入与第一次**逐字相同**的内容 ⇒ 即便实现把 `DO NOTHING` 写成 `DO UPDATE SET metrics_trend = excluded.metrics_trend`（**"用新参数改写既有证据"**），UPDATE 写回的**正是同样的值** ⇒ 自证**照常"通过"** | **对抗性重放**：第二次传入**另一份内容**（换签名人 / 日期 / 结论 / 趋势 / 脱敏授权 / `created_by`；6 键清单保持逐字相同以**隔离变量**）+ 断言"该行仍逐列等于**第一次**那份形态"（**13 个特征列**：清单 6 + 签名 4 + 脱敏开关 + `created_by` + `metrics_trend`）。🛑 **通用规则：要证明"没有改写"，就要拿"会被改写的内容"去试** —— 用相同输入测幂等，等于用最弱的输入证明最强的性质 |
| 44 | **🔴 判据把"语义等价的谓词书写"判成错（V20 `(b8)`，假红）**：`(b8)` 初版只匹配 `WHERE archive_id = p_archive_id` ⇒ 把语义完全等价的 `WHERE p_archive_id = archive_id` 判成错 | 判据接受**两种等价的谓词书写**（断言"**按 `archive_id` 做等值匹配**"这个语义）。🛑 **与第 41 条同族但方向相反**：判据**过度收紧**会把**正确的重构**判成错 —— **"判据太松"与"判据太紧"都是缺陷** |
| 45 | **🔴 客户端出站白名单漏 `B5 GET /api/v1/customers/{id}/intake-profile`（真功能缺口，静默）—— 客户小程序调 B5 会被 `assertPathAllowed` 直接拒**：主契约按 `x-callable-roles` 统计，client 端为 **14 条 path / 15 个 operation**；而 `client-package/api/clientPaths.js` 手抄白名单只有 **13 条**，缺的正是 B5。**后端已实现该端点**（`CustomerController` 有对应方法，`api/package-info.java:27` 逐字记 roles 含 `client`），**是白名单一侧漏登记**。因 `assertPathAllowed` 在**运行期强制**，后果是"客户小程序发起建档扩展档案读取"这一条真实业务链路**直接不可用**；且它**长期不红** —— S1-6 的 FACE 2 与 S1-8 的 FACE 1 **都只做 ⊆（单向包含）检查**：白名单 ⊆ 契约 ⇒ 少一条**不违反**⊆；反向差（契约有、白名单无）在 `client-zero-derived-gate.py` 源码 615/928 行被**逐字**判为 "INFORMATION, **not a defect**"（源码 42-51 行逐字给出理由：equality 检查曾试过并否决 —— "a gate that cries wolf on correct content gets switched off"） | **本轮已补入白名单**（B4 与 C1 之间插入 `'/api/v1/customers/{id}/intake-profile', // B5 建档扩展档案`），13 → **14 条**；补后复跑：S1-8 `PASS faces=5 violations=0`、S1-6 `allowlist_entries=14 = contract_client_paths=14`、`permitted_but_undeclared=0`，两份证人测试全绿，且**无 Java 测试依赖该文件**（无回归面）。🛑 **判定取舍（不改判据，只登记）**：保留 ⊆ 不变 —— 反向差改为硬失败会把"门禁狼来了"的风险重新引入；**代价是此类缺口只能靠"生成"而非"门禁"堵住** ⇒ **根本对策见 §七：前端端点层改为契约驱动生成（`frontends/tools/gen-endpoints.py` 从 `_cut/*.openapi.yaml` + `generator-matrix.yaml` 的 token-roles 交集生成 `contract/endpoints.{js,ts}`，并提供 `--check` 自证），手写白名单不再作为唯一真源**。🛑 **仍须人核**：S1-6 的 `permitted_undeclared`（白名单有、契约无）方向**应始终为 0**，一旦非 0 说明白名单凭空放宽，须逐条人工裁定 |
| 46 | **🔴 三源交叉只验「键」不验「值」⇒ `RlsCoverageGateTest.ISOLATION_TESTS` 的值（测试类 FQN）长期【无任何咬合检查】（A-4，整表假绿）**：原判据 ①②③ 里，第 ③ 条只验「登记的类**可加载** 且 `@Test` 数 **≥ 5**」—— **不验该类是否真的断言了这张表**。⇒ **实测形态**：把一张新表登记到**任一既有 `≥5 @Test` 的类**名下（例：把 `agreement` 挂到讲密钥三表的 `RlsV11CryptoKeyIsolationTest` 上），**①②③ 三条判据全部照常通过**，而该表的**行为隔离断言为零**。这正是本仓反复防的"**把新表挂到邻居名下**"，且**它自己不会红**（长期静默，无人会去逐条 diff）。🛑 **与第 18 条同类但更深一层**：第 18 条问"判对错的那份清单对不对"，**第 46 条问"清单的每一行指向的东西，是否真的在管这一行"** —— 即**键一致性 ≠ 值咬合** | 新增 **判据④** `the_registered_test_class_must_actually_mention_the_table_it_covers`（FQN 反查源码 ⇒ **剥离 Java 注释** ⇒ **词边界**匹配表名）+ **元层判据** `the_registration_binding_matcher_has_real_discriminating_power`（① 词边界判别力 `refund` ≠ `refund_receipt` ② 剥注释必要性 ③ 字符串字面量必须**保留**）；门禁 **4 例 → 6 例**全绿。🛑 **判据形态是实测选出来的**：对现有 38 条登记逐条跑三种写法 ⇒ **F1 双引号 Java 字面量误伤 1/38** · **F2 单引号 SQL 误伤 28/38**（本仓 SQL 普遍 `" FROM " + table` 拼接）· **F3 裸标识词误伤 0/38** ⇒ 取 F3（**误伤为 0 是关键**：把正确实现判红的门禁会被关掉）。反向验证 `verification/124` **7/7 PASS**。🛑 **附带抓出两处次级缺陷**：① 该常量源码注释**不实陈述**（旧注释写"登记表里已出现 `dy-config` 等模块的类"，实测 38/38 **全在 `dy-app`**）⇒ 已逐字更正，并把理由改为真正的"防假红"；② **C5 初版注入【等价于原样】⇒ 报出假失败**（"收窄成只剩 `dy-app`"对当前登记无任何行为差异）⇒ 已改为"剔掉 `dy-app`"。**新通用规则："锚点有判别力" ≠ "注入有判别力"** —— 后者只能由"注入后确有一个可观测差异"来证 |
| 47 | **🔴 `audit_log.target_id` 指向一个【库里查不到】的 id ⇒ "按 target_id 反查证据"永远落空（A-3，静默假绿）**：`BandRefetchService` 初版在**覆盖态（UPDATED）**把 `target_id` 写成 `record.coverageId()`（**本次传入**的 id），而 V22 的 (7) **刻意把 `coverage_id` 列进"不更新的列"**（理由逐字：改它等于换行）⇒ 库里那一行的 `coverage_id` **≠** 本次传入的值。后果两条：① 审计指向一个 `band_daily_coverage` 里**查不到**的 id —— 按 coverageId 反查"这一天的审计证据"**永远落空**；② `BAND_COVERAGE_REFRESHED` 的计数被**摊薄**到每次不同的 id 上 —— 而那个计数是 §2.8.7③「节流」效果的**唯一**可观测面。🛑 **为什么它够格被登记为"系统性缺陷"**：两个 id **都是合法 UUID**，**没有任何一步会报错** —— 审计行写成功了、payload 里两个 id 都在、日志也都打了；唯一暴露方式是「**按 `target_id` 去表里找那一行**」。🛑 **探针侧为什么没有同类问题**（必须说清，否则会误改）：`probe_id` 是**单列全局主键**且冲突动作是 `DO NOTHING` ⇒ 库里那一行的 `probe_id` **恒等于**本次传入值（重放时传入的就是同一个 id）；两侧的差别不在"哪个字段"，而在**"冲突时是否改写身份列"** | 修法 = **读一次库层权威值**（`BandRefetchLedger#readStoredCoverageId`），而**不是**改函数签名让它 `RETURNING` 出 `coverage_id`（后者更好，但属**迁移变更**：改 `RETURNS text` 契约 + 授权段 + 改账 ⇒ 已登记为**待裁项**）。🛑 **门禁守门**：`BandRefetchGateTest.assertAudit` 断言 `target_id` **必须指向库中真实存在**的 id；反向验证 `verification/125` 的 **C1**（服务层回退成本次传入值）与 **C17**（削弱该断言）**两条注入各命中不同方法** |
| 48 | **🔴 第六类新缺陷：「声称要跑的那条命令，从未在任何机器上成功执行过」 ⇒ CI 门禁写了等于没写（批次二十一收尾，假绿）**：`rls-isolation-gate.yml` 的 step 1 原写 `mvn -pl dy-app -am test -Dtest='Rls*Test' -DfailIfNoTests=false`，README 与 `docs/CI-ENABLEMENT.md` 也各抄了一份 —— **三处同错，且从未有一处在本机验证过**。本机实测逐字复现（三种写法全部 exit≠0）：① `-DfailIfNoTests=false` **对 default-test 不生效** —— surefire 3.2.5 在 `-Dtest` 无匹配时报的是 `Set -Dsurefire.failIfNoSpecifiedTests=false to ignore this error.`，属性名对不上，`-am` 连带的七个模块以 `No tests matching pattern "Rls*Test" were executed!` 整批失败；② **硬阻塞（真正根因）**：`dy-config/pom.xml` 的 `config-truth-source-gate` execution 把 `<failIfNoTests>true</failIfNoTests>` **写死在 pom 里**（设计意图：真相源门禁必须被执行），**plugin 配置值优先于同名 CLI 用户属性** ⇒ 补传 `-DfailIfNoTests=false` 也盖不住；改用 `-Ddy.config.gate.skip=true` 逃生阀同样 FAILURE，因为该值不受属性控制。`-am` 必然带上 dy-config ⇒ **只要筛掉它那三个 IT，这一关必红**。🛑 **为什么它够格被登记为"系统性缺陷"**：**它与第 41 条同族（假绿）** —— "写了 CI 门禁"被当成了"CI 门禁有效"；而当时本仓**还不是 git 仓库**，workflow 从未触发，**没有任何机制会发现它**。🛑 **对照证据（说明同族写法为何有的是对的）**：反向验证脚本的 `-Dtest=` 一律配 `-pl dy-app` **不带 `-am`** ⇒ 不连带 dy-config ⇒ 天然绕开该硬门禁。**差别只在 `-am` 这一个开关。** | 修法 = **删掉 `-Dtest` 筛选，直接 `mvn -B -pl dy-app -am test`**（本机实测 **9 模块全 SUCCESS**、`Tests run: 110` 逐字、`BUILD SUCCESS`）。三处同改：workflow step 1 + 头注释「前提」行、README step 1 描述、`docs/CI-ENABLEMENT.md §4.3`。**附带收益**：CI 命令与「全量回归」**归一为同一条**，从此 CI 与本地不再有两套口径 —— 而"两套口径"正是本条能潜伏至今的原因。🛑 **通用教训**：**凡写进 CI 的命令，必须在写下的那一刻就在本机跑通一次**；"命令看起来对"与"命令跑得通"是两件事，而后者才是门禁的全部意义 |
| 49 | **🔴🔴 第七类新缺陷（阻断级）：【仓库根建错层级】⇒ CI 全新 checkout 必然失败、门禁永远无法变绿（2026-09-30）**：git 仓库原建在 `skeleton/`，但 **7 个门禁测试类把"仓库根"硬编码为 `product-strategy`** —— `ContractConsistencyTest`（`:732` 注释逐字：「仓库根（= 含 `_work/` 与 PRD 的那一层，即 `product-strategy`）」）· `ContractFreezeGateTest` · `EndpointCoverageLedgerTest` · `UpstreamGapRegistryTest` · `RefundDomainCrossSourceGateTest` · `RefundWritePathMatrixE2ETest` · `DocTestCountAnchorGateTest`。它们从 `user.dir` **逐级向上最多 4~5 层**找「同时含 `_work/contract-t6-api-freeze-*.md` 与 `_work/data-dict-entities-ddl-*.md` 的那一层」，并引用 `contract/openapi-v1.0.0.yaml` · `prototype/index.html` · `prd-health-mgmt-saas-*.md`。🛑 **在原作者机器上它一直是绿的，因为层级 1 恰好是 `product-strategy`（`skeleton/` 的上一级）** —— **这就是本条最危险的地方：一个只在"别人机器上"才暴露的缺陷，而 CI 恰恰就是"别人的机器"**。🛑 **实测复现（逐字）**：仓库建在 `skeleton/` 并 `git clone` 到 `/tmp` ⇒ `ContractConsistencyTest` **`Tests run: 5, Failures: 4` / `BUILD FAILURE`**（向上 4 层全落空）。🛑 **为什么是阻断级（比第 48 条更根本）**：CI 的每一次 job 都是**全新 checkout** ⇒ 这一条会让**三道门禁全部无法变绿**，而"永远红的门禁"与"没有门禁"等价（且更坏：它会让人习惯性地忽略红）。🛑 **第二条同族问题（同一次核对抓出）**：`.github/workflows/` 原本放在 `skeleton/.github/` 下 —— **GitHub Actions 只加载仓库根的 `.github/workflows/`** ⇒ 三个 workflow **从未被加载过**，它们此前那句"本仓库不是 git 仓库所以不生效"掩盖了这层：**即使成了 git 仓库，位置也仍然是错的**。（与第 41/48 条同族的假绿："写了 workflow" 被当成 "有了 CI"） | 修法 = **仓库根上移到 `product-strategy/`**（`git` 初始化于该层，`skeleton/` 作为其子目录）；`.github/workflows/` 移到**仓库根**，三个 workflow 各加 `defaults.run.working-directory: skeleton`，并把 `upload-artifact` 的 `path:` 改为相对仓库根的完整路径（该字段**不读** `defaults`）；根级新增 `.gitignore`（全局规则）与 `skeleton/.gitignore`（子树细化）**分工而非重复**。🛑 **验证（三层，缺一不可）**：① 根上移后本机全量回归 `EXIT=0 / BUILD SUCCESS`（9 模块全 SUCCESS）；② `git clone` 到 `/tmp` 后**层级 0/1 命中**（`level0=skeleton` ❌ → `level1=repo` ✅）；③ 在 clone 上跑**全量回归**：`mvn -o -B -ntp clean test` → **`EXIT=0` / `BUILD SUCCESS`（9 模块全 SUCCESS）**，逐 `TEST-*.xml` 求和 **`TOTAL 1199`** —— 与本地**逐数字一致**。🛑 **这三层缺一不可**：① 只证"改动没弄坏本机"；② 只证"路径找得到"；**③ 才是"别人 clone 下来也能构建"的唯一证据**。🛑 **通用教训**：**"我这台机器上能跑"不是构建成功的证据 —— 必须在"干净 clone"上验证**；凡是依赖"向上找目录"的测试，其可移植性**只能由 clone 证明**，不能由原作者的绿证明。🛑 **顺带修正**：`_work/quotes_incident_backup/` 与 `_work/backup_quotes/`（报价单事故取证备份，约 8.2 MB）在根级 `.gitignore` 中**刻意忽略**（含整份源码树副本 ⇒ 会让全仓搜索命中同一份代码的两份副本，误导"某文件有几处定义"）；但 `_work/` 下**生产代码引用的探针 SQL**（如 `v18_device_gap_probe.sql`）**必须保留** —— 已逐项核对 |
| 50 | **🔴 第 48 条的同族第二条：三端 `package.json` 里的工具路径【多了一层】⇒ 6 条脚本从未跑通过（2026-09-30，前端骨架自检）**：`admin-web` / `therapist-app` / `client-mp` 的 `gen:endpoints` · `check:contract` · `check:build` · `build` **一律**写 `../../tools/...`，而 **`skeleton/tools/` 不存在** —— 真实位置是 `skeleton/frontends/tools/`（相对三端目录只须**上一层** `../tools/`）。🛑 **实测复现（逐字）**：`node ../../tools/build-check.mjs` ⇒ `Cannot find module '...\skeleton\tools\build-check.mjs'`；改 `../tools/` 后 ⇒ **端 C 12 项全过 `BUILD OK`**、端 A/B 各 14 项全过 `BUILD OK`，`python ../tools/gen-endpoints.py --check` ⇒ `[OK] client-mp 15 / therapist-app 29 / admin-web 39`。🛑 **为什么与第 48 条同族**：第 48 条的 CLI 属性被 pom 覆盖、本条的多一层路径，**都不是"写错了一个词"而是"写下的命令从未真正执行过"** —— 两者的共同点是**只有实际执行才能证伪，代码审阅一定看不出来**（`../../` 与 `../` 在肉眼审阅里同样"看着合理"）。🛑 **为什么长期没暴露**：三端 `package.json` 是**骨架期**产物，此前所有验证都由**仓库根的 python/node 直接调脚本**完成（用的是正确路径），`npm run` **从未被真正调用过** —— 又一次"写了脚本"被当成"脚本可用"。 | 修法 = 三端 6 条脚本 + `frontends/README.md:45` 的 `../../tools/` → `../tools/`，**共 7 处**；并**逐条实际执行**复验（不只看文件内容）：`gen:endpoints --check` 三端 OK · `build-check --end=...` 三端 `EXIT=0`。🛑 **通用规则**：`package.json` 里的每一条 `scripts` 都必须在**它自己的目录下**被真正跑过一次；跨目录相对路径**不能用审阅代替执行** |
| 51 | **🔴🔴 第八类新缺陷（阻断级）：两道门禁【各自动是对的】但对同一件事用了两把尺 ⇒ 本地绿、CI 红（2026-09-30，端 C 首次跑门禁）**：`frontends/tools/build-check.mjs` 原用 `stripComments()` 剥掉 `/* */` 与 `//` 再比对词表，**并在注释里逐字声称「与 compliance 一致」—— 那句声称是错的**。实测 `compliance/scan_compliance.py` 的 `scan_face()` 是**逐行 `line_matches(line, term)` 直接匹配、不剥任何注释**。🛑 **后果正是本仓反复修的那类假绿**：端 C `npm run build` **绿**（注释被剥掉了）、CI 里 ADR-12 门禁 **红**（主扫描器看得见注释）—— 而排查者会先怀疑 CI 环境，**真实原因是两个检查器口径不同**。🛑 **实测（逐字）**：端 C 首跑主扫描器 ⇒ `[COMPLIANCE-GATE] FAIL  faces=3  violations=27  owners_ok=yes  owners_named=0/4`，**27 处全部落在注释行内，真实代码与用户可见文案零命中**（例：`services/neutral-copy.js L113`）。🛑 **方向选择（不是随便挑一侧对齐）**：词表 `SCOPE` 逐字写「applied to the WHOLE client package with **no path allow-list**」，且小程序打包后注释**可能保留、反编译可见** ⇒ **注释里的禁词本就是命中，主扫描器是对的**；`build-check.mjs` 擅自剥注释 = **放松门禁**，而本仓纪律明令「不放宽词表、不加 path 例外」⇒ **对齐到更严的一侧**（两头都不给例外），**不去改主扫描器**。🛑 **由此产生的写作纪律**：客户端包内**不得写出禁词原文**，需要解释"某词为什么被禁"时**一律用指代**（条款号 / 字段组编号 / 组名 / 扫描面编号）。🛑 **落地时我本人又踩了两次**（写成"R2 组点名的归因说法"、把组名写成"与医学宣称相关"）⇒ **纪律必须写成「不引原文」而不是「换个说法」**，否则会以"解释禁词"的形式再次命中。 | 修法 = ① `build-check.mjs` **删去 `stripComments`**（循环处直接 `readFileSync` 后匹配），并把该处的长篇注释改为记录本条缺陷与方向依据；② 按「用指代、不写原词」清理客户端包内注释（本轮共 `app.js` · `band.js` · `band.wxml` · `daily-report.js` · `index.js` · `band-sync.js` · `codes.js` · `domain.js` · `app.wxss` · `profile.js` · `plan.js` · `neutral-copy.js`（整份重写））；③ **两侧同时复验**：`node ../tools/build-check.mjs --end=client-mp` ⇒ **`BUILD OK` / `EXIT=0`** **与** `python compliance/scan_compliance.py --repo-root .` ⇒ **`[COMPLIANCE-GATE] PASS faces=3 violations=0`**。🛑 **通用规则**：**当两道门禁审同一件事时，"各自能跑"不等于"口径相同"** —— 必须用**同一批输入**做**对拍**，并**事先写清哪一侧是权威**（此处权威 = 主契约词表 SCOPE 声明的全量无例外遍历）。🛑 **同时证明防线有效**：端 C 首次跑门禁即抓出真实命中（`band.wxml` 注释），说明**门禁有牙齿**，不是摆设 |
| 52 | **🔴 第 41 条的同族第二载体：【判据被字面量满足】（2026-09-30，端 B X-3 反向验证首跑抓出）**：第 41 条的载体是 **SQL 函数体内 `RAISE` 消息的字符串**，本条的载体是 **TypeScript 里的语句** —— 同一族缺陷换了宿主，判据的失效方式一模一样：**判的是"词有没有出现"，不是"行为有没有发生"**。🛑 **三条判据同时中招**：⑥ 出站接线判 `clientText.includes('assertCanCall')` ⇒ 被 **`void assertCanCall;`**（**只提及、不调用**）满足；④ 硬编码清单判 `includes(row)` ⇒ 被 **`void ['F1']`** 满足；⑤ 单一权威面判 `/['"]meridian['"]/` ⇒ 被 **`void "meridian"`** 满足。🛑 **它不是被"门禁全绿"发现的 —— 门禁首跑就是全绿**：`x3-reverse-check.mjs` 的 7 组受控注入里 **I5（`void assertCanCall;`，摘掉调用只留词）漏过**，才反证出 ④⑤⑥ 三条判据全都是"判词"而非"判行为"。🛑 **这正说明反向验证不可省**：一个全绿的门禁**可能只是因为它从来没被真正违反过** —— 只有受控注入能回答"这个绿是有条件的绿吗"。🛑 **修法（判形态，不判词）**：⑥ 判**调用形态** `assertCanCall\s*\(\s*[^)\s][^)]*\)`（必须带实参的真正调用）；⑤ 判**比较/成员判断形态**（`==`/`===`、`.includes()`、`.indexOf()`、查表 5 条正则）；④ 判**数组字面量形态**且要求**至少 2 个端点行号**（`void ['F1']` 这类单词形态不再满足）。🛑 **连带教训（注入用例本身也要"真实"）**：I4 必须注入 **`role === "meridian"`**（真实判断形态）—— 若按最初写法注入 `void "meridian"`，会得到一个**假红**（门禁确实报红了，但报的不是它该报的原因），这正是第 36 条那类"反向验证自身的元层缺陷"。 | 修法 = ① 重写 `x3-check.mjs` 的 ④⑤⑥ 三条判据为"形态判定"（判据处留下注释说明本条缺陷，因判据**只应被真实行为满足**）；② I4 注入改为真实判断形态并附注释说明"为何不能改回 `void "meridian"`"；③ **I5 保留为永久回归用例**（它正是当初漏过的那一组）；④ 重跑 ⇒ **7/7 PASS 且还原后 `exit=0`**。🛑 **通用规则**：**"代码里出现了某个名字"永远不等于"那个名字被使用了"** —— 凡是"必须被调用/必须构成判断/必须出现在正确语法位置"的约束，判据都必须落到**语法形态**上；`includes()` 这类"全文含词"判定只适用于**"禁止出现"**类约束，**不适用于"必须使用"**类约束。 |
| 53 | **🔴 第 52 条的同族第三条：【判据的覆盖范围与实际写下的形态不匹配】⇒ 新增的第 N 种写法静默漏检（2026-09-30，端 B 加「专属动作」页时撞见）**：端 B 的 `x3-check.mjs` ⑦ 判据初版只认**字面量**形态 `/requires:\s*'([^']+)'/`（例 `requires: 'getCustomer'`）。本轮给「专属动作」导航项改成**机械推导**形态 `requires: firstSoleEndpointId()` 后，该导航项**根本没有被检查**，而门禁输出仍是 `✓ nav: 导航项依赖的 2 个端点都在本端生成物里` —— **数字是错的、且看不出来**（实际有 3 项非 null 依赖）。🛑 **它与第 52 条同族、但接缝位置不同**：第 52 条是"判据被字面量满足"（**判据太弱**，错的代码也能过）；本条是"判据的覆盖面**没跟上新写法**"（**判据太窄**，新写法绕过判据）。前者是"判错"，后者是"没判"。🛑 **本条最值得记住的一点：门禁**绿**着、而且它在**打印一个错误的数字**** —— 这比"静默不报"更坏，因为它给出了**可信的假证据**（一个"2"让人以为检查过了）。🛑 **修法两层**：① 判据同时认**两种形态** —— 字面量 ⇒ 必须存在于生成物；调用形态 ⇒ 函数名必须在门禁**白名单**内、且必须**定义在 access.ts**、且其实现**必须引用生成物**（否则无法证明它是"现算"而非硬编码）；② 加一条**交叉核对** —— 判据**认出的条数必须等于 NAV 数组里非 null 的 `requires` 条数**，不等就报红并提示"出现了第三种写法，本判据不会检查它"。🛑 **第 ② 层是通用手法**：判据不该只"检查它认识的东西"，还应"证明它认识的东西覆盖了全部" —— 否则每一次新增写法都是一次静默漏检。 | 修法 = ① 重写 `x3-check.mjs` ⑦：先**圈出 NAV 数组本体**（初版直接扫整个 App.tsx，把 `interface NavItem` 的 `readonly requires: string | null;` 也数成了"一条依赖"，导致交叉核对**首跑即误报**——这条自我踩坑也写进了代码注释）；② 新增推导函数 `firstSoleEndpointId()` 落进 `access.ts`（不在 App.tsx 写局部 `soleRequires()`，避免"准入/依赖推导逻辑出现第二个位置"）；③ 新增反向验证 **I8**（把 `requires` 换成内联三元 = 第三种形态）作为该交叉核对的永久回归用例；④ 端 B `build-check.mjs` 的 `required` 清单补齐本轮实装的 11 个模块（准入/会话/错误/域服务/UI×2/页面×5）—— 同一条教训的另一处（漏列 ⇒ 文件被删也不报红）。🛑 **复验**：`x3-check` ⇒ 9 项全绿且 nav 行如实打印 `3 项全部可核（字面量 2 · 推导函数 1）`；`x3-reverse-check` ⇒ **8/8 PASS**。🛑 **通用规则**：**判据必须能回答"我检查的东西覆盖了全部吗"**；只判"我认识的那些"必然随写法演进而静默失效。 |
| 54 | **🔴 第九类新缺陷：契约写下的约束在【转录管道】里被静默丢掉（2026-09-30，端 A 39 端点元信息层落地）**：`gen-endpoints.py` 初版只转出 `id / row / method / path / grantedRoles`，把端 A 真正吃紧的 **4 类操作级 `x-` 键**（`x-row-scope` · `x-super-admin-only` · `x-ruling-pending` · `x-frontier`）**以及契约顶层 `x-roles`（}`admin` 的子档位 `[manager, area, hq]`）一个都没转**。🛑 **它的失效方式是"完全静默"**：生成物看着完全正常、`python ../tools/gen-endpoints.py --check` 也绿（三端 `[OK] client-mp 15 / therapist-app 29 / admin-web 39`）、三端构建全过 —— **只有人肉逐条读契约**才发现"界面少了一道约束"。🛑 **它属于同一族缺陷的第三个接缝**：第 41 条接缝在 **SQL 字符串**、第 52/53 条接缝在 **TS 判据**、**第 54 条接缝在生成器**；共同点是"写下的东西"与"生效的东西"之间**任何一环丢项都不报错**（第 50 条同族）。🛑 **为什么端 B 没暴露它**：端 B 的边界恰好**全在端点级角色**上（`grantedRoles` 是初版唯一转出的元信息）⇒ "丢 4 类键"对端 B **完全无害**；而端 A 是**单角色端**（实测 **39/39 全 `["admin"]`**），端点级**没有角色分叉**（端 B 那套"角色→端点"矩阵在端 A **恒真**，检它等于没检），端 A 真正的边界**全在那 4 类被丢掉的键上** ⇒ **同一个生成器，在端 B 无害、在端 A 致命**。🛑 **端 A 的真实轴（两层，都在服务端）**：① **行级范围** `x-row-scope`（`F3 listAuditSignals` / `F4 getAuditCoverage` 两处：同一端点调用相同，差别在返回行数 —— 门店负责人仅本店 / 区域督导仅辖区 / 总部全量；前端**无法也不该**自行裁剪，否则就是造第二个裁剪点）；② **子档位** `x-super-admin-only: true`（`I7 downloadDocTemplate` 仅超管）与 `x-ruling-pending`（`G4 approveRefund` 审批白名单系**推断、待裁定**，不得当定论实现）；另 `x-frontier: 占位待冻结` ×7（I 域全部）。🛑 **落地时我自己又踩了三次同型坑（全部由门禁首跑/反向验证抓出，不是由"全绿"抓出）**：① 门禁解析生成物时**没把 `grantedRoles` 填进 entry** ⇒ `xkey-transcribed` 报假红（`x-callable-roles 应落到 grantedRoles`），**是门禁自己错了**（第 52/53 条同型：判据被自己的解析方式绕过）；② 新加的 `parse-coverage` 判据**首跑就假红 4 条**（"漏转 D5"+"凭空造 D5-a/b/c"）—— 因为契约的 `x-contract-row` 是**合同行**（`D5` 一行对应 **D5-a/D5-b/D5-c 三个操作**，见各操作 `summary` 前缀），生成物的 `row` 是它的**子档位细化**而非字面拷贝 ⇒ 逐值集合比对是错的，正确比对是**三层**（操作数相等 / 生成物 row 去 `-x` 后缀须命中契约行 / 契约每行须至少一个子档位）；③ 反向验证 **I5 / I7 双双漏过** —— `scope-wired` 用 `scopeText.includes('ENDPOINTS')`（被 `for (const e of ENDPOINTS)` 这类**使用处**满足）、`role-expansion` 用 `/ROLE_EXPANSION/.test(genText)`（把 `export const ROLE_EXPANSION` 改名成 `ROLE_EXPANSION_UNUSED` 后**字符串里仍含该词**）⇒ **第 52 条"判据被词满足"的第三个宿主**。 | 修法 = ① `gen-endpoints.py` 新增 `OP_X_KEYS` / `OPTIONAL_FIELDS` / `load_role_expansion()`（读契约顶层 `x-roles` 产出 `ROLE_EXPANSION`）/ `_opt_lines()`（缺省不写键，保持生成物最小）/ `_render_role_expansion()`，`render_ts()` 与 `render_cjs()` 均转出；`Endpoint` 接口新增 5 个可选字段（`rowScope?` / `superAdminOnly?` / `rulingPending?` / `frontier?` / `idempotencyKeySpec?`）+ `RoleExpansion` 接口 + `ROLE_EXPANSION`（向后兼容：端 B/C 仅新增声明、无行为变更）；② 新建 **`admin-web/src/contract/scope.ts`**（未完结状态二态联合 `Unsettled`（`frontier` / `ruling-pending` **语义不同不合并**）· `unsettledOf` / `unsettledEndpoints` · `hasRowScope` / `rowScopeOf` / `rowScopedEndpoints` · `isSuperAdminOnly` / `superAdminOnlyEndpoints` · `ADMIN_TOKENS = ROLE_EXPANSION['admin']?.tokens` · `DOMAIN_LABEL`（A/B/C/D/E/F/G/I，端 A **无 H 域**）/ `groupByDomain()` / `summarizeConsole()`）；③ 新建 **`tools/a-check.mjs`（端 A 主门禁，11 项判据）**：`parse-fields`（交叉证明 TRANSOUT 的每个目标字段都真解析出来了）· `parse` · **`parse-coverage`**（独立源 `x-contract-row` 三层交叉核对）· `single-role` · **`xkey-coverage`**（把契约里**实际出现的每一个**操作级 `x-` 键枚举一遍，新键自己报红；11 类全部有归属）· **`xkey-transcribed`** · `xkey-values`（逐条计数对拍）· `no-hardcoded-list` · **`scope-wired`**（判**导入形态**）· **`role-expansion`**（判 **`export const` 导出形态** + `ROLE_EXPANSION[` 使用形态）· `unsettled-surfaced`（判**使用形态**）；④ 新建 **`tools/a-reverse-check.mjs`（10 组受控注入）** ⇒ **10/10 PASS**、还原后 `exit=0`；⑤ `admin-web/package.json` 接 `check:a` / `check:a-reverse` 并**在该端目录下实跑复验**。🛑 **通用规则**：**契约 → 生成器 → 前端 是一条管道，每类被声明过的元信息都必须有一个可机械验证的"落点"**；凡是"契约写了而前端拿不到"的约束，**失效应默认假定为静默**，只能由"把契约里出现的键枚举一遍"这类**覆盖面型判据**抓住。 |
| 55 | **🔴 第十类新缺陷：判据【太窄】⇒ 把本仓自己规定的合法写法判红 ⇒ 假红 ⇒ 判据被删（2026-09-30，端 A 应用层八域页面 + 多页外壳落地）**：`a-check.mjs` ⑦ `unsettled-surfaced` 初版只认 `unsettledOf` / `unsettledEndpoints` / `frontier` / `rulingPending` **四个词**。而本仓在 `admin-web/src/services/domain.ts` 里**逐字规定**：「页面不得各自手写判断，一律走 `contractNoteOf` 统一出口」。于是 `RefundWorkbenchPage`（`contractNoteOf('approveRefund')` + `<UnsettledBar items={note.unsettled} />`）与 `DocTemplatePage`（7 个 I 域端点，9 处 `UnsettledBar`）—— **完全合法、且是本仓规定的唯一写法** —— 被判红。🛑 **它是第 52 / 53 条的孪生方向**：第 52 条是判据**太宽**（被词满足）⇒ **假绿**（错的东西被放过）；第 53 条是判据**覆盖面没跟上** ⇒ **静默漏检**（新写法绕过判据）；**第 55 条是判据太窄 ⇒ 假红（对的东西被抓）**。🛑 **为什么"假红"比"假绿"更危险、值得单独编号**：假绿是"错的东西没被抓"；假红是"**对的东西被抓**"，它的下场**不是修代码**，而是**有人把判据删掉、或加一行绕过** —— 一条被删掉的判据连"静默漏检"都不如，它连检都不存在了。**判据与本仓的权威约定各写了一套**，这是本条的核心。🛑 **第二轮同源发现（⑨ `nav-requires`，本轮新增）**：端 A 的 `requires` **恒真**（实测 39/39 端点全 `["admin"]`）⇒ 「导航项写了契约里不存在的 id」**不会导致导航项消失**，只有点进去才报错 —— 故必须另立判据钉住"每个 `requires` 都能在生成物里查到"，否则这条纪律**从未被检查过**。🛑 **修法：三种合法形态的【调用判定】+ 计数等式**：`unsettled-surfaced` 重写为「引用了未完结端点 id 的文件必须至少命中之一」—— (a) `unsettledOf(` / `unsettledEndpoints(`；(b) 统一出口且**参数逐字是未完结端点 id** 的 `contractNoteOf('<未完结id>')`；(c) `<UnsettledBar`。🛑 **(b) 的参数必须绑定未完结集合**：若只判 `contractNoteOf(` 出现，则 `contractNoteOf('getCustomer')` 也会过 —— 那就退化成第 52 条的词满足。并且**判据先剥注释**（第 41/51 条教训：注释里的词不算标注）。`nav-requires` 用两条式子 + **计数等式**：`requires` 只有两种合法形态（生成物里存在的端点 id 字面量 / 契约层导出的推导函数调用），**两者条数之和必须等于 NAV 里非 null 的 `requires` 条数** —— 等式防"新增第三种写法（内联三元 / 拼接）导致两条式子都不命中、于是『没查到』被当成『没有问题』"。🛑 **一个必须点明的边界：外壳不写端点 id 字面量，不是"绕过判据"**：`App.tsx` 是外壳，导航表**无从渲染任何提示条**；若为过判据在导航表写一行"标注"，那行标注不产生任何界面效果 —— 等于把判据变成**纸面合规**。本轮的处理是让外壳**真实承担横切职责**：在导航下方渲染一条**全局未完结提示**（进入本端第一眼即知"这里有 8 个地方契约还没冻结"），数据源为 `unsettledEndpoints()`（枚举函数，非写死 id）；并由判据**逼出**另一条纪律 —— `contract/scope.ts` 新增 `firstFrontierEndpointId()` 推导函数，让「未完结总览」导航项的依赖**不出现端点 id 字面量**（契约清空未完结声明时自动降级为 `null`）。 | 修法 = ① **真漏标的改代码**：`App.tsx` 新增全局未完结提示（`unsettledEndpoints()` + `<UnsettledBar>`，frontier / ruling-pending **两条分别渲染、语义不合并**）；② **判据改判形态**：⑦ `unsettled-surfaced` 重写为三种**调用形态**判定 + 能力绑定；新增 ⑨ `nav-requires`（两条式子 + 计数等式）；两处均**先剥注释**；③ **新增 4 组反向用例**（全集 **14 组**）：**I13** 只用**注释里的词** + `contractNoteOf('getCustomer')`（**普通**端点）满足 `unsettled-surfaced` ⇒ 必红；**I14** 给 NAV 注入**第三种形态**的 `requires`（内联三元 + 拼接调用）⇒ `nav-requires` 必红；I11/I12（上轮）令牌键名第二处定义 ⇒ `token-single-source` 必红；④ 新建 **`src/pages/UnsettledPage.tsx`**（未完结总览页，两栏 <code>frontier</code> / <code>ruling-pending</code>，空态文案逐字写"若此处为空而契约里确有声明，说明元信息被丢掉了"）；⑤ `src/App.tsx` 改造为**多页外壳**（登录门 + 7 项 NAV + 角色标签取自 `ROLE_EXPANSION[role].display`，不手写文案）；⑥ **实跑复验**：`tsc --noEmit` 0 错 · `vite build` 通过 · `check:a` **13/13 绿** · `check:a-reverse` **14/14 PASS / 还原 exit=0** · `check:build` `BUILD OK`（21 源文件）· `check:contract` 三端 `[OK] 15/29/39`。🛑 **顺带修掉两个真实缺陷**：`CustomerConsolePage.tsx` 的 JSX 属性里嵌了**未转义双引号**（`hint="…不提供"默认无"的…"`）⇒ 解析级联崩坏（tsc 报 7 条）；`api/client.ts` 的 `newIdempotencyKey` **未导出**却被 3 个页面 import（TS2459）—— 修法不是改页面，而是**导出并写明语义**：契约对少数端点逐字指定了幂等键**构成**（I3 `uploadDocTemplate` 的 `x-idempotency-key = (tenant_id, doc_type, file_hash)`），此时**随机键是错的**（重复上传每次都成功 ⇒ 静默绕过幂等语义），故必须由调用方按要素显式构造。🛑 **通用规则**：**判据必须与本仓自己规定的权威写法对齐**；判据"太宽"会放过错的，判据"太窄"会逼人删掉它 —— **两者都是失效，且后者更不可逆**。🛑 **同族第三处宿主：「页面覆盖 N 个端点」这句【任务书自身的验收标准】也是可自我声称的**：逐条核验后发现 **D 域 5 个端点**（D1 `createVisit` / D3 `submitDailyReport` / D5-a `createPlan` / D5-b `getPlan` / D6 `createDeviceDispatch`）是"**封装了但从未接上界面**" —— 在那之前"页面覆盖 39 个端点"**是自我声称**。⇒ 新增 ⑩ `endpoint-reachability`（① 出站层存在 `call<T>('<id>'` **调用形态**；② 封装函数被 `pages/`/`App.tsx` 以 `fn(` **调用形态**使用；**计数等式**：① 命中数 == 端点总数；**分层纪律**：出站只允许在 `services/`，页面/外壳不得直接出站）。🛑 **该判据首跑即抓出自己太窄**：初版把出站扫描写死成 `services/domain.ts` 一个文件 ⇒ 报「`authLogin`/`authMe` 未出站」，而 A1/A2 合法地定义在 `services/session.ts`（会话层职责就是"A1→A2 取档位"）⇒ **是判据太窄（第 55 条本身），不是代码错**；修法不是把 A1/A2 搬进 domain.ts（那会毁掉会话层职责完整性），而是**把出站层定义成本仓实际的样子：`services/` 整个目录**。🛑 **顺带修掉一个门禁元层缺陷（第 24 条复发）**：`a-reverse-check.mjs` 的 I10/I13 会新建临时页再删；本轮脚本被中断 ⇒ 残骸留在源树 ⇒ `unsettled-surfaced` 永远红 ⇒ 下次跑报 `ABORT: 基线门禁不是绿的`，而**报错指向"先修好再跑"，真实原因（残骸）完全看不出** ⇒ 修法：脚本**每次启动先清掉自己命名空间下的临时文件**（只删自己创造的 `__probe_*.tsx` 固定名）。 |
| 56 | **🔴 跨端「隐式协议」缺口：`servers[0].url`（契约 §2.0 Base Path `/api/v1`）从未进入前端出站 URL —— 只写在三份 env 的注释里 ⇒ 运维照注释配置即三端全量 404，而 tsc / vite / 全部门禁全绿（2026-09-30，三端前端收口）**：契约的 `paths` 键是 `/auth/me`，而**真实 URL 是 `/api/v1/auth/me`** —— 前缀由 `servers[0].url` 承载（契约第 43 行，description 逐字写着「Base Path（契约 §2.0 全局约定）」）。🛑 **后端这一侧早就意识到了**：`AuthMeController` 的类注释逐字写着「本类**不得**只写 `@RequestMapping("/auth/me")`：那会让端点在 `/auth/me` 落地，而三端 UI 按契约请求 `/api/v1/auth/me` 会拿到 404 —— 且不会有任何测试红，因为单测直调控制器方法、不经过路由」；后端也确实有机械守卫 —— `EndpointCoverageLedgerTest` 把契约 path 与真实 Spring 注解路由**逐条比对**（45 operationId = 41 已实现 ∪ 4 出范围登记，且实测无 `@GetMapping(path=)` 形态漏网）。**但前端这一侧此前什么都没有**，且方向刚好相反：后端写全（`/api/v1` + 方法级 path），前端只写半截（`env.baseUrl + endpoint.path`，`path` **不含** `/api/v1`）。而「baseUrl 只到网关根」这件事此前**只写在三份 env 的注释里**（端 C `env.js`：`// 契约 servers.url = /api/v1，故基址只到网关根。`）—— **那是一句不可执行的话**：运维按注释配置 ⇒ 出站落到 `/auth/me` ⇒ **全量 404**，而 `check:a` / `check:x3` / `build-check` / `tsc` / `vite` **全部仍绿**（它们从不发真实请求）。🛑 **同族 dev 期版本（本轮一并修掉）**：两端 `vite.config.ts` 的 dev 代理键写 `'/api'`，而出站路径是 `/api/v1/...`；Vite 代理是前缀匹配，`'/api'` 虽能匹配，但一旦 base path 改成别的（如 `/gateway/v1`）而代理键留着 `'/api'`，请求**根本不进这条规则** ⇒ dev 期全量 404，`npm run dev` 不报错、构建自检也不报错。🛑 **修法（把"注释里的约定"抬成"生成物里的常量"）**：① `gen-endpoints.py` 新增 `load_api_base_path()`，读裁剪契约 `servers[0].url` 并机械转录为三端生成物常量 `API_BASE_PATH`（缺 `servers` 直接 `MISCONFIGURED` 退出）；② 三端环境层各自导出**出站前缀**（端 A/B `getRequestBaseUrl()`；端 C `ENV.requestBaseUrl` getter），值 = 网关根 + `API_BASE_PATH`，且**与 `getBaseUrl()`（运维视角的网关根）的区别写进函数名与注释**；③ 三端出站层一律改写为「出站前缀 + endpoint.path」；④ 两端 vite 代理键改 `'/api/v1'`。🛑 **判据 `base-path-wiring`（三端共用 `build-check.mjs`，四层）**：① 出站层 —— 圈定 `let url = ...` 组装表达式并判其成分（**不得**是裸 `baseUrl`）；② 环境层 —— 必须导出出站前缀且其值**引用生成物常量**、**不得**手写字面量；③ 生成物 —— `API_BASE_PATH` 存在且取值形态合法；④ vite —— dev 代理键必须含该 Base Path（仅端 A/B）。🛑 **该判据首跑即抓出自己太窄（第 55 条复发）**：① 初版只认「前缀 + `(`」（函数调用），把端 C 的 `ENV.requestBaseUrl`（**getter 属性访问**）判成"未体现前缀" ⇒ **假红**；修法不是改端 C（getter 是本仓自己规定的合法写法），而是**改成"圈定 URL 组装表达式再判其成分"**，两种写法都认。另，② 的"是否手写字面量"判定**必须先剥注释** —— 否则一句 `// 契约 servers.url = "/api/v1"` 就会被判红（注释里提到路径不是硬编码，反而是本仓鼓励的写法）；这与词表判据**刻意不剥注释**并不矛盾（词表判"禁词是否出现在客户端包里"，注释也是包的一部分；本判据判"代码里是否手写了常量/怎么拼 URL"，是语义判定）。🛑 **同族第三处宿主：新判据必须同时证明"判据被删"会被抓住**：新建常驻 `tools/build-reverse-check.mjs`（R1–R6 共 6 组），并实测把整段判据删掉后 **6 组全部变「漏过」、脚本 FAIL（0/6）** ⇒ 第 55 条最危险的下场（**判据被删**）在 base path 这一面已被封堵：**判据可以被删，但删不掉它的证人**。🛑 **另修一个门禁元层缺陷（本次实测复现）**：`a-reverse-check.mjs` 的 I10/I13 会新建探针文件再删；若该脚本被中断，残骸会留在源树，而 `a-check` 的 `unsettled-surfaced` 会报出一个**指名道姓指向探针文件、读起来像真实源码缺陷**的失败项，把排查带向"去改那个文件"（第 24 条：检验工具把自己的残骸当成了被检对象）。🛑 **一处被实测推翻的设计**：初版想加**入口守卫**（发现残骸就 exit），实测立刻推出反例 —— I10/I13 恰恰**需要**门禁看见探针文件才能被证明有牙齿，一拦就双双"漏过"（14/16）；也试过在扫描时**排除**探针文件，结果残骸变成**静默假绿**（比误报更危险）。⇒ 正确设计是**只做诊断、不改判定**：门禁**失败时**若同时存在探针残骸，追加一段说明「失败项有可能是残骸而非缺陷，并给出处置办法」；新建共用件 `tools/_gate-common.mjs`（`findProbeDebris` / `probeDebrisNotice`，只认 `__probe_` 命名空间，绝不宽泛删除）。🛑 **复验数字（逐字）**：`build-reverse-check` **6/6**、还原后三端 `build-check` 全绿无残留；删判据模拟 **0/6 ⇒ FAIL**；端 A `check:a` **14/14** · 反向验证 **16/16**；端 B `check:x3` **9/9** · 反向验证 **8/8**；端 C `build` BUILD OK；`check:build` 三端全绿；`check:contract` `[OK] 15/29/39`；后端全量回归 `TOTAL 1199 failures=0 errors=0 skipped=0`。 | 修法 = ① **生成器**：`load_api_base_path()` 转录 `servers[0].url` → 三端生成物 `API_BASE_PATH`；② **三端环境层**：导出出站前缀（`getRequestBaseUrl()` / `ENV.requestBaseUrl`），值与语义写清；③ **三端出站层**：改「出站前缀 + path」；④ **两端 vite**：代理键 `'/api/v1'`；⑤ **新判据** `base-path-wiring`（四层，三端共用）；⑥ **新反向验证** `build-reverse-check.mjs`（6 组，常驻，且能抓"判据被删"）；⑦ **新建** `tools/_gate-common.mjs` + `a-check.mjs` 失败时的残骸诊断；⑧ 文档 `frontends/README.md` §8 → **八条**并新增 §8.9。 |
| 57 | **🔴 第十一类新缺陷：第 56 条只修了「URL 前缀」一类 —— 另三类跨端隐式协议（鉴权头名 / 令牌前缀 / 幂等头名）仍在三个端各抄一遍，而「追踪头名 `X-Trace-Id`」更彻底：契约全域【零声明】，只活在后端过滤器常量与前端字面量里（2026-09-30，三端前端收口第二轮）**：第 56 条末尾我**自己**把跨端协议归纳为四类 —— ① URL 前缀、② 信封字段、③ 鉴权头名、④ 幂等键构成 —— 却只修了 ① 。本轮把 ②③④ 逐条核查，结果是 **两处真缺口 + 一处"第 56 条修法自己引入的新形态反模式"**。🛑 **缺口一（最强）：`X-Trace-Id` 是一个【契约里一个字都没有】的跨端头。** 实测它在 **6 处**硬编码 —— 后端 `TraceIdFilter.HEADER = "X-Trace-Id"`（`http.setHeader(HEADER, traceId)`）、`TenantContextFilter`、`ObservabilityMdcFilter`，以及三端出站层 `res.headers.get('X-Trace-Id')` / `res.header['X-Trace-Id']`。契约里既没有 `securitySchemes`、没有 `parameters` 声明、也没有 prose（`x-global-conventions.auth` 只写鉴权）；`contract/` 全目录 **grep 零命中**。⇒ 改这个头名（或后端换实现）会让**留痕链断掉**，而三端仍全绿。🛑 **缺口二：幂等键构成只有一句 prose。** 契约 `x-global-conventions.idempotency` 写「写接口接受 `Idempotency-Key` 请求头（UUID）；服务端在 24h 窗口内去重」，`components.parameters.IdempotencyKey` 也定义了 `name/in/required/schema` —— **但 `grep -c "IdempotencyKey"` 在 `paths` 段为 0：这个 parameter 从未被任何 operation 引用**（纯装饰）。且唯一一处逐操作声明 `x-idempotency-key: "(tenant_id, doc_type, file_hash)"` 只对 **I3** 生效（三端裁剪契约里 admin-web 1 处、其余两端 0 处）。🛑 **关键实测：那个"唯一被转录的幂等键构成"从未被任何前端代码消费。** `grep -rn idempotencyKeySpec`（排除生成物与 dist）**零命中** —— 生成器把它转出了、`Endpoint` 接口声明了、注释写了"随机键是错的"，而**没有任何调用点用它**（E1 的 `batch_no` 由调用方自己拼、I3 的上传页面根本没读 `idempotencyKeySpec`）⇒ 契约把幂等键**语义**写下来了，前端**拿不到**。🛑 **反模式（第 56 条修法自身引入，已订正）：生成物常量的值不是"机器可读转录"，而是"手抄的契约 prose"。** 第 56 条的 `load_api_base_path()` 初版**不从 `servers[0].url` 取值**，而是先读 `x-global-conventions['base-path']` 再**把字面量手抄进模板**，还配了一句注释「Value = /api/v1」—— 等于**用注释显式掩盖了"值不是现取的"**；而 `base-path-wiring` 判据只断言"常量存在"、不断言"值等于契约" ⇒ **契约改 Base Path 时生成物照旧、门禁照绿**（第 52 条：判据太宽 ⇒ 假绿）。🛑 **这不是我臆造的风险，而是本条修法刚刚亲手造出来的**：同一份 `gen-endpoints.py` 里，`servers[0].url`（真源）与被抄进模板的 `base-path`（副本）**已经是两个来源**。🛑 **修法（抬成契约结构化事实 → 生成物常量 → 出站层引用 → 门禁判形态，四层，与第 56 条同范式）**：① **契约层** —— 新增根级 `x-api-protocol` 结构化块（`auth-header` / `auth-scheme` / `tenant-header` / `trace-header` / `idempotency-header` / `envelope-fields` / `envelope-ok-code` / `idempotency-window-hours` / `envelope-rule`），与既有 prose **并存且必须一致**；② **生成物层** —— 生成器新增 `load_api_protocol()`，转录为 `PROTOCOL` 常量组（TS 附 `ProtocolSpec` 接口，缺键即 `MISCONFIGURED` 退出）；`load_api_base_path()` **订正为真正读 `servers[0].url`**，并与 `x-global-conventions['base-path']` **互查**，不一致即退出；③ **出站层** —— 三端一律 `headers[PROTOCOL.AUTH_HEADER] = \`${PROTOCOL.AUTH_SCHEME} ${token}\`` / `headers[PROTOCOL.IDEMPOTENCY_HEADER]` / `res.headers.get(PROTOCOL.TRACE_HEADER)` / `body.code !== PROTOCOL.ENVELOPE_OK_CODE`，**不再出现任何协议字面量**；④ **门禁层** —— `build-check.mjs` 新增 ④d `cross-end-protocol`（判**引用形态**：必须见到 `PROTOCOL.<KEY>` 成员访问；且**剥注释后**不得残留头名字面量 / `"Bearer "` / `code === 0`；两条并列，先报"未引用"再报"有残留"）。🛑 **契约侧新增互查断言（`ContractFreezeGateTest`，11 例）**：`cross_end_protocol_fragments_are_structured_and_agree_with_prose` —— ① 结构化块必填键不得缺、不得为空串；② 与 `x-global-conventions` 的 prose **双向**互查（auth 必须含头名与前缀、tenant-context 必须含租户头、envelope 必须含全部字段名与成功码）；③ 与真正的 OpenAPI 结构互查（`envelope-fields` 每一项必须在 `ResultEnvelope.properties` 里、`required` 不得含信封外字段、`idempotency-header` 必须等于 `parameters.IdempotencyKey.name` 且 `in: header`、`trace-header` 不得与信封字段撞名）。🛑 **这条新断言首跑就复发了一次第 55 条（值得记录）**：初版断言「`envelope-fields` 每一项都必须在 `ResultEnvelope.required` 里」，**首跑即红** —— `required: [code, message, trace_id]` **不含 `data`**。但那不是契约的错：契约自己写明「`code != 0` 时 `data` 为空」，故 `data` **本就该可选**（失败信封不带 data）。⇒ 初版把本仓**自己规定的合法形态**判红 = 第 55 条。**修法不是改契约，而是把判据拆成三件事**：① 字段存不存在（查 `properties`）；② `required` 是否是信封的**子集**；③ `data` 不在 `required` 时，`envelope-rule` **必须说明原因**，否则它就成了「未文档化的约定」。🛑 **反向验证（R7–R10 共 4 组，纳入常驻 `build-reverse-check.mjs`）**：R7 端 A 鉴权头名回退成字面量 / R8 端 C 追踪头名回退成字面量 / R9 端 B 信封成功码回退成字面量 / R10 **生成物 `PROTOCOL.AUTH_HEADER` 值被改成与契约不一致（值漂移，不是缺键）** —— 实测 **10/10 PASS**（含原 6 组）。🛑 **删判据模拟（本条最关键的证据）**：把 ④d 整段删掉后重跑 ⇒ **R7–R10 四组全部变「漏过」、合计 6/10、脚本 FAIL**（`base-path-wiring` 六组仍被抓住，因为它们由另一段代码守）⇒ **两条判据各自有牙齿，且"判据被删"这件事本身会被抓到**。🛑 **复验数字（逐字）**：契约冻结门禁 **11/11**；`gen-endpoints.py --check` `[OK] client-mp 15 / therapist-app 29 / admin-web 39`；三端 `build-check` 全绿（`cross-end-protocol` 三端各 5 个键全部命中、无字面量残留）；`build-reverse-check` **10/10 PASS**、删判据 **6/10 ⇒ FAIL**；端 A `check:a` 14/14 · 反向 **16/16**；端 B `check:x3` 9/9 · 反向 **8/8**；后端全量回归 **`TOTAL 1200 failures=0 errors=0 skipped=0`**（1199 → **1200**，+1 = 本条新增的契约互查断言；dy-app **941 → 942**）。🛑 **一处如实登记的未决项（不改语义，只登记）**：契约 prose 写幂等为「**24h 窗口内**去重」，而后端 E1 的库层唯一索引 `uq_sync_log_batch_no (tenant_id, batch_no)` 是**永久**唯一 —— 两者的差集是「同一 `batch_no` 距首次超过 24h 后重放」（契约允许应成功、库层返回 409）。后端 `BandService` 已自己登记该开放项（逐字："若可接受，契约文字应改为『永久去重』"），**属契约 MAJOR 级变更，裁定权在契约 owner + 产品共签，不在本轮**。另：`x-idempotency-key` 仅 I3 一处逐操作声明 ⇒ 其余写端点的键构成**仍在契约里无声明**（前端只能各自造随机键），这是**待补项**而非本轮可自行发明的内容。 | 修法 = ① **契约**：新增根级 `x-api-protocol`（9 键，prose 与结构化并存）；`ContractFreezeGateTest` 新增双向互查断言（11 例，含"data 可选性必须被文档化"）；② **生成器**：新增 `load_api_protocol()` → `PROTOCOL` 常量组（CJS/TS 双形态，TS 附 `ProtocolSpec`）；`load_api_base_path()` 订正为**真读 `servers[0].url`** 并与 `x-global-conventions['base-path']` 互查；③ **三端出站层**：鉴权头 / 令牌前缀 / 幂等头 / 追踪头 / 成功码一律引用 `PROTOCOL.*`；④ **门禁**：`build-check.mjs` 新增 ④d `cross-end-protocol`（判引用形态 + 残留字面量，剥注释）；⑤ **反向验证**：`build-reverse-check.mjs` 扩充 R7–R10（含"值漂移"一例）；⑥ **文档**：`frontends/README.md` §8 → **九条** + 新增 §8.10。 |
| 58 | **🔴 第十二类新缺陷：分页「越界处置」在契约里只声明了约束、没声明怎么办 —— 同一份契约下两个列表端点各走一路（A3 超界拒 400 / D2 静默夹逼后 200），而两端 200 响应体、tsc、vite、全部门禁**都绿**（2026-09-30，三端前端收口第二轮）**：`x-global-conventions.pagination` 只写一句「请求 ?page=<int>&page_size=<int≤100>；响应 data.{items[], total, page, page_size}」—— 声明了**约束**（上界 100），**没说越界怎么办**。实测两个列表端点各走一路：`A3 GET /stores` 的 `StoreListPage`/`StoreListService` **超界直接抛 400 `VALIDATION_FAILED`**（Javadoc 逐字「本项目处置是超界直接拒（400）而不是夹逼」）；而 `D2 GET /customers/{id}/visits` 的 `FulfillmentController:120` 写的是 `Math.min(Math.max(pageSize, 1), 100)` —— **静默夹逼为 100 后返回 200**。客户端请求 101 条、拿到 100 条、**无从察觉**。🛑 **关键**：`x-error-codes` 里 `VALIDATION_FAILED.trigger` **逐字就是**「参数类型 / 必填 / **约束不满足**」—— 契约自己已经写了『越界该报 400』，是静默夹逼违反了契约；但**两处声明都在契约里、却没有任何机械判据把它们连起来**，故静默夹逼能长期存活且全绿。这与第 57 条 `X-Trace-Id` 同族：**契约写下的协议与各端实现的协议是两件事，中间丢项不报错。** 另：后端**零测试**覆盖这两个端点的越界行为。🛑 **修法（抬成契约结构化事实 → 生成物常量 → 出站层引用 → 门禁判形态，四层，与第 56/57 条同范式）**：① **契约** —— `x-api-protocol` 新增 `pagination` 结构化块（`request-fields` / `response-fields` / `page-min` / `page-default` / `page-size-min` / `page-size-max: 100` / `page-size-default: 20` / `over-range-policy: reject-400` / `over-range-error: VALIDATION_FAILED`），并订正 prose 显式写出「越界（<1 或 >100）一律 400 VALIDATION_FAILED（拒，不夹逼）」；`components.parameters.Page`/`PageSize` 补 `minimum`/`default`/`description`，使 OpenAPI 原生工具链也能读到该处置；② **后端** —— 新建**唯一分页校验单点** `dy-common` 的 `PageQuery`（越界一律抛 `VALIDATION_FAILED`，**绝不夹逼**）；`FulfillmentController` 的 `Math.min(Math.max(...))` 改为 `PageQuery.of(...)`；`StoreListPage` 的常量与 `StoreListService` 的两个 `validate*` 全部收归单点委派（此前**同一规则两处实现**，正是静默夹逼能长期存活的土壤）；③ **前端** —— 生成物新增 `PROTOCOL.PAGINATION`（含 `PAGE_FIELD`/`PAGE_SIZE_FIELD`/上下界/缺省/处置/错误码），三端新增契约感知的分页助手 `paging.ts`（`pageQuery()`），端 A 6 处 + 端 B 4 处分页调用点**全部收敛为常量引用**（此前 `page_size` 字面量手抄，契约改名则 tsc/构建全绿而分页静默失效）；④ **门禁** —— `build-check.mjs` 新增 ④d 的 `PAGINATION` 值断言（**值漂移也报**）+ ④e `pagination-protocol`（判**引用形态**：分页助手内必须出现 `PAGINATION.PAGE_FIELD` / `PAGE_SIZE_FIELD` 成员访问 —— 这是 R13 首跑漏过后补的，第 53 条形态）。🛑 **契约侧新增互查断言（`ContractFreezeGateTest`，12 例）**：`pagination_over_range_policy_is_structured_and_agrees_with_prose_and_error_codes` —— 结构化块 ↔ prose ↔ `parameters.PageSize.schema.maximum/default` ↔ `x-error-codes.VALIDATION_FAILED.trigger`（必须含「约束」字样）四向互查。🛑 **反向验证（R11–R13 共 3 组）**：R11 端 A 分页参数名回退成手抄 / R12 端 B 透传形态回退 / R13 分页助手把 `page_size` 键名写错 —— **13/13 PASS**（含原 10 组）。🛑 **删判据模拟**：禁用 ④e 后重跑 ⇒ **R11–R13 三组全部变「漏过」、合计 10/13、脚本 FAIL**（R1–R10 仍被抓住，因为由另两段代码守）⇒ 三段判据各自有牙齿。🛑 **后端新增真请求越界断言（`FulfillmentDomainDEndpointsE2ETest`，6→9 例）**：`page_size=101 → 400 VALIDATION_FAILED` / `page=0 → 400`（不得当作没传兜底成第 1 页）/ `page_size=100（恰等上界）→ 200 且回显 100`（边界不得写成 ≥，第 55 条）。🛑 **复验数字（逐字）**：契约冻结门禁 **12/12**；分页纪律门禁 **4/4**；D2 真请求 E2E **9/9**；`gen-endpoints.py --check` `[OK] client-mp 15 / therapist-app 29 / admin-web 39`；三端 `build-check` 全绿且 `pagination-protocol` 三端各 ✓；`build-reverse-check` **13/13 PASS**、禁用判据 **10/13 ⇒ FAIL**；端 A `check:a` 14/14；端 B `check:x3` 9/9；后端全量回归 **`TOTAL 1208 failures=0 errors=0 skipped=0`**（1200 → **1208**，+8 = 契约冻结互查 1 + 分页纪律门禁 4 + D2 真请求越界 3）。🛑 **一处顺带抓出的既存缺陷**（见第 59 条）：`FulfillmentController` 的静默夹逼**不是孤例** —— 修完后跑全量回归时 `BandTelemetryEncryptionTest$HeartRate` **随机变红**，查明是既存的概率型断言，非本轮改动引入。 | 修法 = ① **契约**：`x-api-protocol.pagination`（9 键）+ prose 订正 + `parameters.PageSize` 补 `minimum/default/description`；`ContractFreezeGateTest` 新增四向互查（12 例）；② **后端**：新建 `dy-common` 的 `PageQuery` 单点（越界拒 400，不夹逼）；`FulfillmentController` 改调单点；`StoreListPage`/`StoreListService` 收归委派；③ **前端**：生成物 `PROTOCOL.PAGINATION` + 三端 `paging.ts` 助手，端 A/B 共 10 处调用点收敛；④ **门禁**：`build-check.mjs` ④d 值断言 + ④e `pagination-protocol`；⑤ **反向验证**：R11–R13；⑥ **真请求断言**：D2 越界 3 例；⑦ **文档**：`frontends/README.md` §8 → 十条 + 新增 §8.11。 |
| 59 | **🔴 第十三类新缺陷（判据自身的第四种失效形态）：会随机变红的断言 —— `assertFalse(raw.contains("72"))` 判的是 base64 密文文本，而 base64 字符集含 0-9、密文随机，故这对相邻字符约 1% 概率偶然出现 ⇒ 全量回归**不定期变红**（2026-09-30，第 58 条回归时顺带抓出）**：`BandTelemetryEncryptionTest$HeartRate.encrypted_at_rest_and_plaintext_round_trips` 里 `raw` 是 `CipherEnvelope.serialize()` 的产物，形态为 `dy1:算法:版本:<b64 nonce>:<b64 密文>`。断言 `assertFalse(raw.contains("72"), "密文里出现明文 72")` 有两个问题：① **概率型假红** —— base64 字符集含 `0-9`，nonce+密文约 40 字符随机，`"72"` 这对相邻字符约 **1%** 概率偶然出现，全量回归不定期变红；② **判错了对象** —— 整条文本除密文外还含**算法标识**（`AES-GCM-256`）与 **DEK 版本数字**，对整条文本判 `contains` 会映到这些**非密文段**。同族第二处：`assertFalse(raw.contains("95"))`（sleep_json）。🛑 **为什么这算一条缺陷而不是小事**：**一个会随机变红的断言，长期会侵蚀门禁自身的可信度** —— 一旦「红了可能是运气」成为共识，就没人再认真看红。这与第 52（太宽⇒假绿）/53（覆盖面没跟上⇒静默漏检）/55（太窄⇒假红）构成判据失效的**第四种形态：不确定性（flaky）**。🛑 **修法**：判**解码后的密文字节**而非它的 base64 文本 —— `new String(CipherEnvelope.parse(raw).ciphertext(), ISO_8859_1).contains("72")`（后者才是「数据有没有被加密」的物理载体），并保留对 `deep_min` 的一次整条文本判定（`deep_min` 是 8 字符英文键名，偶然出现概率可忽略，且它若出现必然是明文泄漏）。🛑 **新增门禁（防回归）**：`ProbabilisticAssertionGateTest`（3 例）—— 扫描测试源码，禁止对**被断言为 `dy1:` 信封**的变量判 `contains`；配反向验证（注入原写法 ⇒ 必须红）+ 豁免断言（对源码原文/SQL 的确定性 `contains` 不得判红）。🛑 **本判据首跑就复发了一次第 55 条（第三次，值得记录）**：首版按**变量名白名单**（`raw`/`envelope`/`*_enc`）判别，**首跑即误判 3 处合法写法** —— `BandAvailableDatesTest:97 assertTrue(raw.contains("N = 7"))`（源码原文）、`DerivedProfileSourceTest:306 assertTrue(envelope.contains("DerivedRawConfig"))`（配置对象）、`ProvisioningBoundaryGateTest:701 assertTrue(raw.contains("INSERT INTO tenant"))`（SQL 文本）—— 这三处的变量**恰好也叫** `raw`/`envelope`，但装的是**确定性文本**。⇒ **变量名单看名字无法区分「随机密文」与「确定性文本」，这是判据的根本局限**。**正确判法是判数据来源**：只有同一文件里该变量被断言过 `startsWith("dy1:")` 时，它才是随机字节的文本表示 —— 与 ④d 的「判 `PROTOCOL.<KEY>` 引用形态」同构。改后 3/3 绿。🛑 **复验数字（逐字）**：`BandTelemetryEncryptionTest` **连跑 3 次全绿**；`ProbabilisticAssertionGateTest` **3/3**；后端全量回归 **`TOTAL 1211 failures=0 errors=0 skipped=0`**（1208 → **1211**，+3 = 概率型断言门禁 3 例）。 | 修法 = ① **测试**：`BandTelemetryEncryptionTest` 两处 `raw.contains(...)` 改判**解码后的密文字节**（新增 `ciphertextOf()` helper）；② **门禁**：新建 `ProbabilisticAssertionGateTest`（数据来源判据 + 反向验证 + 豁免断言）；③ **文档**：`frontends/README.md` §8 → 十条 + 新增 §8.12。 |
| 60 | **🔴 第十四类新缺陷（判据的第五种失效形态：守错了层级）：`TOTAL N failures=0` 是一句【活断言】，却全仓没人守 —— 我文档里的实际值全写错（1204 vs 实测 1211，差 7），而测试绿、构建 SUCCESS、三端自检绿、反向验证 13/13（2026-09-30，第 58/59 条收尾复验时抓出）**：`README.md` 与 `frontends/README.md` 里共 **4 处**写着我亲手跑出来的「全量回归计数」，形态 `TOTAL <N> failures=0 errors=0 skipped=0`。它**不是**修辞 —— 它声称两件事：①「全仓回归计数为 N」②「失败/错误/跳过全为 0」。而本轮真实值是 **`TOTAL 1211 failures=0 errors=0 skipped=0`**，我写的 4 处**全错**（写 1204）。🛑 **错法本身就是一条证据**：我当时是"预估"而不是"照抄实测" —— 在 1200 上只加了「分页纪律门禁 4 例」，**漏掉了同一批改动里的另外 3 个新测试类**：`ContractFreezeGateTest` 的 `pagination_over_range_policy...`（+1）、`FulfillmentDomainDEndpointsE2ETest` 的越界 3 例（+3）、`ProbabilisticAssertionGateTest`（+3）—— 1+3+3=7，正好是那 7 个。🛑 **为什么全绿**：`TOTAL` 锚点**只活在 markdown 里**，而 markdown 不被任何测试读取；前端三端自检只管 `frontends/` 的构建，后端门禁只管各模块的源码。⇒ **这句话的每一个字都可以是假的，而没有一台机器会因此变红** —— 与本仓第 32 条「一个恒真的断言等于没有断言」同族，但更隐蔽：那一条是"断言写得太宽所以恒真"，这一条是**断言根本没人读**。🛑 **它和 dy-crypto 已有的同名门禁的区别（必须写清，否则会被误认为重复）**：本仓**已有** `com.diaoyuanyun.dy.crypto.gate.DocTestCountAnchorGateTest`，但它守的是 **`dy-crypto/` 模块自己的**「`应为 **N**`」锚点（模块内计数），覆盖面**只在 dy-crypto 模块内**、锚点形态**完全不同**；而 `TOTAL ...` 是**跨 8 个模块的求和**，全仓**没有任何**判据守它。⇒ 这不是"已有能力没被用上"，而是**覆盖面正好差了一层**：**判据守的是它写到的那一层，而漂移发生在它没写到的那一层** —— 这与第 53 条（覆盖面没跟上）同族，但成因不同：第 53 条是"新增了同类宿主而判据没跟上"，本条是**"同一件事在两个层级各有一份计数，只守了低层"**。🛑 **修法**：新建 `dy-app` 的 `DocTestCountAnchorGateTest`（2 例），把 `TOTAL` 锚点抬成**活断言**：① `total_anchors_in_docs_match_the_real_test_count` —— 解析 `README.md` + `frontends/README.md` 全部锚点，{**最新一条**必须 `== 源码级实测合计**} + {**每一条**的 failures/errors/skipped 必须全为 0} + {**整份文档内锚点值单调不降**（用例只增不减，写小只可能是漏加）} + {**逐模块求和集必须与 `MODULES` 清单完全一致**（少一个模块 = 求和系统性偏小，这正是本条的一半成因）}；② `gate_turns_red_when_doc_total_drifts_and_green_when_it_matches`（**反向验证，五向**：写对⇒放行 / 少算⇒抓且指名文档+两个数字 / 自称 `failures=1`⇒抓 / 历史锚点值倒退⇒抓 / 一个锚点都解析不到⇒按失败处理不按通过处理）。🛑 **一处刻意设计（不这样会逼文档失去记录历史的能力）**：只对**最新一条**锚点要求"等于实测"，更早的只要求"单调不降" —— 因为条目表按时间追加，第 57 条那行写 1200、第 58 条那行写 1208 都是**当时如实**；若要求每一处都等于今天，每加一条测试就得回去改历史记录，文档就再也不能记录"当时是多少"。🛑 **主判据为什么取「源码级 `@Test` 计数」而不是「surefire 报告求和」**：本仓 `dy-config`（`default-test` + `config-truth-source-gate`）与 `dy-app`（`default-test` + `rls-isolation-gate`）各有**两个** surefire execution，报告求和会把**同一批用例被重复执行的次数**也加进去；源码级计数与执行顺序、execution 划分无关。且本类**实测两条路都验过**：源码级 = 1211，逐 execution 求和也 = 1211（口径一致，互相印证）。🛑 **首跑即红的证据（逐字）**：`Tests run: 2, Failures: 1` —— 失败信息逐字「`README.md : 最新锚点写的 \`TOTAL 1204\`（L1992），实测源码级合计是 1213（差 9）`」+「`frontends/README.md : 最新锚点写的 \`TOTAL 1204\`（L794），实测源码级合计是 1213（差 9）`」（1213 = 1211 + 本类自己的 2 例）。订正 4 处锚点（1204→1208/1211，并**按新增补齐 `1200 → 1208 → 1211` 的增量说明**）后 **2/2 绿**。🛑 **复验数字（逐字）**：全量回归 `EXIT=0 / BUILD SUCCESS`；**`TOTAL 1213 failures=0 errors=0 skipped=0`**（逐模块 10/39/48/58/37/37/29/955；dy-app 由 953 抬到 955 = 本类 2 例）；`DocTestCountAnchorGateTest` **2/2**（含五向反向验证）。 | 修法 = ① **新建门禁** `dy-app/.../DocTestCountAnchorGateTest`（2 例：锚点↔源码级计数 + 五向反向验证）；② **订正文档**：`README.md` 第 58 条行 `1204→1208`（并按新增补齐增量说明）、第 59 条行 `1204→1211`；`frontends/README.md` §8.11.5 / §8.12.5 两处 `1204→1208/1211`；③ **本仓缺陷表**：新增本条（第 60 条）并去重（`frontends/README.md` 的 §8 条数在补 §8.13 时同步）。 |
| 61 | **🔴 第十四类新缺陷：契约「不得模糊报错」只有【给人读的那一半】被实现 —— `missing_items` / `denied_fields` 在契约里 4 处 prose 提及、后端也真的装配了，而三端出站层在错误路径【一律丢弃 `body.data`】、三端错误层【从不读这两个字段名】，端 A 的静态文案甚至对用户承诺「响应 data.missing_items 列出缺失项」而自己从不读它（2026-09-30，三端前端收口第三轮）**：契约 `x-global-conventions.forbidden-403` 逐字写「message 必须给出缺失项名称 / 档位名称（**不得模糊报错**）」，另在 `:251`（GATE_MISSING trigger）、`:388`（B1 描述）、`:1494`（GateMissing 响应描述）**3 处 prose 提及** `missing_items`，`denied_fields` 在 `:1488`（VisibilityDenied 描述）提及 —— **全部是 prose，无一处结构化声明**。🛑 **后端这一侧是完整的**：`GlobalExceptionHandler.java:77` `Map.of("missing_items", g.getMissingItems())`、`:83` `Map.of("denied_fields", v.getDeniedFields())`；`VisibilityDeniedException.java:43` 构造期拒空/拒 null；`GateMissingException` 携带 `missingItems`。**缺的是客户端那一段**：① 三端出站层在**错误路径**只挂 code/status/traceId，**丢弃 `body.data`**（端 A `admin-web/src/api/client.ts:134`、端 B `therapist-app/src/api/client.ts:160`、端 C `client-mp/miniprogram/services/request.js:132`）；② 三端错误层**从不读这两个字段名**（grep 前端除 `errors.ts` 文案外零命中）；③ 端 A 的 `errors.ts:48` **对用户承诺**「响应 data.missing_items 列出缺失项」，而它自己从不读那个字段 —— **文案在替一个不存在的功能背书**。⇒ 契约要求的"给出名字"在客户端**完全没有到达**，而 tsc / vite / 门禁**全绿**（与第 57/58 条同族：**契约写下的协议与各端实现的协议是两件事，中间丢项不报错**）。🛑 **修法（四层，与第 56/57/58 条同范式）**：① **契约** —— `x-api-protocol` 新增 `error-data-fields` 结构化子块（`"2002": missing_items` / `"2001": denied_fields`）+ `error-data-field-rule`（说明该字段是**逐字来自上游的名称**、message 给人读、data 给机器比对，混用会让上游字面被解释性文字污染）；`forbidden-403` prose 追加「且 data 里必须给出名字本身」（**机器可读的那一半**）；② **生成物** —— `gen-endpoints.py` 的 `load_api_protocol()` 扩读该块（缺块/空键/键非数字串/缺 2002/2001 即 `MISCONFIGURED` SystemExit），新增 `_err()` 助手，三端生成物出 `PROTOCOL.ERROR_DATA_FIELDS`（TS 附 `Readonly<Record<number, string>>`）；③ **出站层/错误层** —— 三端出站层保留 `err.data = body.data`；三端错误层经 `PROTOCOL.ERROR_DATA_FIELDS[code]` 取字段名（**不得手写**字面量），端 A/B 把原因名拼进 `text`（`reasonsOf` + `appendReasons`），**端 C 刻意只透传不渲染**（端 C 词表纪律禁止内部概念，`reasons` 作为**独立出口**供页面逻辑分支 —— 同一个契约事实，两种合法消费形态）；④ **门禁** —— `build-check.mjs` ④d 加值断言 + **新增 ④f `error-data-fields`**（① 生成物键值；② 出站层**精准形态** `err.data = ...body.data`；③ 错误层成员访问且无字面量；一律**剥注释**）。🛑 **契约侧新增互查断言（`ContractFreezeGateTest`，13 例）**：`error_data_field_names_are_structured_and_agree_with_prose_and_error_codes` —— 结构化块 ↔ `x-error-codes[].code` 逐字 ↔ `forbidden-403` prose 双向 ↔ 正文落点，**四向互查**。🛑 **反向验证（R14–R16 共 3 组）**：R14 端 A 出站层又丢 `body.data` / R15 端 B 错误层不经常量取字段名 / R16 端 C 字段名回退手写字面量 —— **16/16 PASS**（含原 13 组）。🛑 **这条判据首跑就复发了一次第 52 条（判据太宽 ⇒ 假绿，值得记录）**：④f 的②初版只判 `/\bdata\s*:/` 或 `/err\.data\s*=|\.data\s*=/`，被端 A **成功路径**的 `return { data: body.data, ... }` 满足 ⇒ **删掉 `err.data = body.data;` 仍 exit=0（R14 漏过）**。⇒ 修法不是改端 A，而是把判据收紧为**精准形态** `/\berr\.data\s*=\s*[^;]*\bbody\.data\b/`（必须判"**错误对象上**的赋值"）。🛑 **删判据模拟（本条最关键的证据）**：把 ④f 整段停用后重跑 ⇒ **R14–R16 三组全部变「漏过」、合计 13/16、脚本 FAIL**（R1–R13 仍被抓住，因为它们由另三段代码守）⇒ 四条判据各自有牙齿。🛑 **复验数字（逐字）**：契约冻结门禁 **13/13**；`gen-endpoints.py --check` `[OK] client-mp 15 / therapist-app 29 / admin-web 39`；三端 `build-check` 全绿且 `error-data-fields` 三端各 ✓；`build-reverse-check` **16/16 PASS**、禁用 ④f **13/16 ⇒ FAIL**；后端全量回归 **`TOTAL 1214 failures=0 errors=0 skipped=0`**（1213 → **1214**，+1 = 本条新增的契约互查断言）。🛑 **一处顺带修掉的既存缺陷**（见第 62 条）：本条复验时 `build-reverse-check` 基线偶发红，查明是 `vite build` 清空 `dist/` 的删除动作被宿主**安全删除守卫**拦截，与代码无关。 | 修法 = ① **契约**：`x-api-protocol.error-data-fields`（2 键）+ `error-data-field-rule` + `forbidden-403` prose 订正；`ContractFreezeGateTest` 新增四向互查（13 例）；② **生成器**：`load_api_protocol()` 扩 `error_data_fields` + `_err()` 助手 → 三端生成物 `PROTOCOL.ERROR_DATA_FIELDS`；③ **三端出站层**：保留 `err.data = body.data`；④ **三端错误层**：经 `PROTOCOL.ERROR_DATA_FIELDS[code]` 取原因名（端 C 只透传不渲染）；⑤ **门禁**：`build-check.mjs` ④d 值断言 + ④f `error-data-fields`；⑥ **反向验证**：R14–R16；⑦ **文档**：`frontends/README.md` §8 → 十四条 + 新增 §8.14。 |
| 62 | **🔴 第十五类新缺陷（判据的第六种失效形态：条件性假红 —— 依赖环境状态）：`real-build` 判据里的 `vite build` **必然**变红，而红的参数是「本轮我已经删了多少文件」（2026-09-30，第 61 条复验时抓出）**：复验第 61 条时 `build-reverse-check` 基线报 `✗ therapist-app / admin-web 基线不是绿的（exit=1）`，而同一条 `build-check` 命令**手跑两次全绿**（`BUILD OK`）⇒ **基线门禁本身是偶发红**。🛑 **取证过程本身也踩了一个坑（值得记）**：基线只打印 `exit=1`、**不打印哪个判据红了**（与第 24 条同族：**报错不指向真因**）；我第一版诊断代码"只挑含 `✗`/`FAIL`/`ERROR`/`BUILD` 字样的行" —— 而 **vite / tsc 的真实报错行一个这类字样都没有**，于是"明细"打印出来全是 ✓ 行 + 一句 `✗ real-build: vite build 失败`，**等于没打印**。⇒ 判"该打印哪些行"**不能用关键词白名单，只能排除已知噪音行**。补上完整明细后真因暴露（逐字）：`[safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":627,"threshold":50,"scope":"turn","targets":["...\\therapist-app\\dist\\assets"]}`，栈为 `checkBulkDeleteGuard (node-safe-delete-shim.cjs:239)` → `emptyDir (vite/.../dep-*.js)` → `prepareOutDir`。🛑 **真因**：`vite build` 默认 `emptyOutDir: true`，实现是 `fs.rmSync(dist/assets, {recursive:true})` —— 而本机运行环境装了一层**安全删除守卫**，它对"单轮累计删除文件数 > 阈值"的动作**直接抛错**。⇒ **判据红，而红的原因是"本轮我已经删了多少文件"这个与代码完全无关的变量**；报错还指向 `dist/assets` 路径 + 一段 shim 栈，读起来像"产物目录有问题"—— **真因完全看不出**。🛑 **为什么单独编号**：与第 59 条（会随机变红的断言）**同族但成因不同** —— 第 59 条随机源是**随机数据**（base64 密文偶现 `"72"`，约 1%），本条随机源是**环境状态**（本轮累计删除量）。两者都会侵蚀门禁可信度（"红了可能是运气"⇒ 没人再看红），但修法不同：那条是"判解码后的字节"，本条是"**让判据不依赖那个环境变量**"。归一为**判据失效的第六种形态：条件性假红（依赖环境状态）**。🛑 **同族第二处（脚本自身，一并修掉）**：`build-reverse-check.mjs` 原本把待变异文件 `copyFileSync` 到 `tools/_reverse_backup/`、跑完 `rmSync` 删掉 —— 17 组用例 × 2 次 ⇒ 删除量**必然**顶穿阈值 ⇒ **脚本死在第 N 组用例的清理语句上**，且死法（shim 栈）与"判据是否有牙齿"毫无关系。🛑 **修法（三处，都不动安全机制、都不放宽语义）**：① `build-check.mjs` 新增共用 `viteBuildArgv()` 返回 `['build','--emptyOutDir=false']`（端 A/B 共用）—— 产物正确性**不依赖"先清空"**（vite 产物带内容哈希，重建会**覆盖**同名文件并按新图重写 `index.html`，残留旧哈希文件不被引用、不影响结论）；**`vite build` 仍真实执行、仍必须 exit=0**，只是不让它删一个与被检语义无关的目录；② `build-reverse-check.mjs` 改为**内存备份**（`Map<absPath, 原文>` + `restore()`）：全程**一次删除都不发生**；配 `uncaughtException` / `unhandledRejection` 兜底 `restoreAll()` —— **比磁盘副本更可靠**（磁盘副本在进程被杀时会留残骸，那正是第 24 条的成因）；③ 收尾对**旧版遗留的** `_reverse_backup/` 只做**诊断**不改判定（否则旧版遗留的一个空目录就会让门禁报"异常 ✗"，读起来还像"残留没清干净"，把人带偏）。🛑 **复验数字（逐字）**：基线 **绿 ✓**；`build-reverse-check` **16/16 PASS**、`还原后三端构建自检：client-mp=0 · therapist-app=0 · admin-web=0（全绿 ✓，已按内存备份还原）`；端 A `check:a` / 端 B `check:x3` 均全绿。 | 修法 = ① **`build-check.mjs`**：新增共用 `viteBuildArgv()`（`--emptyOutDir=false`），端 A/B 的 `real-build` 改用它；基线失败时**原样带出 build-check 明细**（排除已知噪音行，不用关键词白名单）；② **`build-reverse-check.mjs`**：磁盘备份 → **内存备份**（+ 异常兜底 `restoreAll()`）；收尾对旧版残留目录**只诊断不判定**；③ **文档**：`frontends/README.md` §8 → 十四条 + 新增 §8.15。 |
| 63 | **🔴 第十六类新缺陷（判据失效的第七种形态：覆盖缺口本身即缺陷的藏身处）：端 A 有一条全链路「端点触达」判据，端 B / 端 C 【都没有】⇒ 端 B 的 29 个端点里 **16 个在 `services/` 层封装齐全、`src/` 全域零调用**，而 `tsc --noEmit` / `vite build` / 既有全部门禁 / 反向验证**一律绿**（2026-09-30，三端前端收口第四轮）**：端 A 的 `a-check.mjs` ⑩ 早已有一条 `endpoint-reachability` —— 它判**两层调用链**（① `services/` 层有 `call<T>('<id>')` 出站；② 定义该调用的导出函数被页面/外壳以 `fn(` **调用形态**使用），并附**计数等式** `called.size === entries.length` 与一条**反向纪律**（页面/外壳不得直接出站 ⇒ 分层纪律）。🛑 **端 B（`x3-check.mjs`）与端 C（`build-check.mjs`）都没有这条判据**。实测后果（逐条 `grep` 确认 `src/` 全域 0 次调用）：`createCustomer` · `signConsent` · `submitBaselineAssessment` · `getAssessment` · `submitCycleAssessment` · `listDailyReports` · `submitDailyReport`（封装名 `submitDailyReportAsStaff`）· `getIntakeProfile` · `patchIntakeProfile` · `createVisit` · `createDeviceDispatch` · `createPlan` · `getPlan` · `listScaleItemBanks` · `createScreeningRecord` · `listStores` —— **16 个端点封装好了但从未接上界面**。🛑 **为什么它能长期存活**：`tsc --noEmit` 只关心**类型**（未使用的导出函数完全不报）、`vite build` 只关心**能否打包**（tree-shaking 反而把这些函数**当死代码摇掉**）、既有门禁只审"契约转录/角色矩阵/可见性/分页/错误字段"（审的是**已写下的东西对不对**，不审"该写的东西有没有写"）⇒ **三道防线没有一道会看一眼"这个封装有没有人用"**。🛑 **它与第 52/53/55 条同族、但接缝位置又不同**：第 52 条是"判据被判词满足"（判据太弱）、第 53 条是"判据覆盖面没跟上新写法"（判据太窄）、第 55 条是"判据太窄 ⇒ 把合法写法判红"（假红）；**第 63 条是"判据的覆盖范围按【端】切分 ⇒ 只有一端有这条判据，另两端的同一类约束从未被检查过"** —— 判据不是错了，而是**它只保护了三分之一的受保护对象**。🛑 **本条暴露的最深一层后果（必须单独记，否则会低估）：「封装了但没接上界面」不只让功能缺失，还让函数自身的缺陷也没有曝光面。** 实测 `listScaleItemBanks` 的初版签名 `query: PageQuery & { age_group?: string; dimension?: string } = {}` —— `PageQuery` 的索引签名是 `[k: string]: number \| undefined`，与 `{ age_group?: string }` 求交得到**内部矛盾类型**（`age_group` 同时要求 `string` 与 `number \| undefined`）⇒ **该函数在类型层面根本不可调用**（`tsc` 报 `TS2345`）。它长期没被发现，**正因为它从没被任何页面调用过** —— 死代码不是"少一块功能"，而是"少一个让缺陷显形的探针"。🛑 **端 C 一侧同族第二处（判据首跑即抓出真缺陷）**：`pages/login/login.js` 自己 `request.call('authLogin', ...)`，而 `services/session.js` 里**已有一个等价 `login()`**（同样拼 `client_end = 'mp'`、同样存 token），只是**从没被调用过** ⇒ **同一操作两份实现，改哪份都会静默漂移**（与第 57/61 条同族：同一契约事实被实现两次，中间漂移不报错）。🛑 **为什么端 C 的判据不能照抄端 A/B 的算法**（否则就是第 52 条"太宽 ⇒ 假绿"）：端 C 服务层是 **CommonJS**（`function foo(){}` + `module.exports = { foo: foo }`，**不是** `export function`）；页面用 `var api = require('../../services/domain.js')` 取别名再 `api.foo(...)` ⇒ 判据**必须先解析别名**；且端 C 存在**编排层**（`band-sync.js` 的 `syncOnShow()` 内部依次调 `reportAvailableDates()` / `uploadRecords()`）⇒ 判据须接受**第二种触达形态**（被同一服务模块内**已被页面触达**的导出函数调用），否则会把合法设计判成假红（第 55 条）。🛑 **一个必须先做、否则会得出错误结论的取证纪律**：我第一版探针只认 `export function`，端 C **15/15 假绿** —— **探针自身的覆盖面缺口会伪装成"没有问题"**（第 63 条在元层的自我复现）。 | 修法 = ① **补界面（真缺口，不是补判据）**：新建端 B `pages/IntakePage.tsx`（域 B · B1~B6 五端点）/ `pages/AssessmentPage.tsx`（域 C · C1~C4 四端点）/ `pages/ServicePage.tsx`（域 D · D1/D3/D4/D5-a/D5-b/D6 六端点）+ 接入 `App.tsx` 导航（`requires` 用端点 id 字面量，由 `x3-check` ⑦ 核验）+ `WorkbenchPage.tsx` 补 A3 门店列表（`listStores`）⇒ 端 B 触达 **13/29 → 29/29**。🛑 **三页落地时同步体现的契约硬约束（不是"照名字填表"）**：B1→B2 做成**顺序两步**（筛查通过后 `screening_id` **自动带入**建档，不给人手抄 UUID；B2 建档成功后 `customer_id` 带入 B3/B5/B6）；C2 的 `dimension_scores` 做成 **7 个独立输入框**（契约 `minItems 7 / maxItems 7`、`total_score` 0~112），填满 7 项才解禁提交按钮、`sum` **仅作核对提示、不自动覆盖** `total_score`；C4 依从维度常量**刻意不含 A2**（契约红线「A2 永不参与 AS」）；D3 **刻意不提供 `source` 选择器**（`submitDailyReportAsStaff` 已写死「代核」）；B3 请求体字段名逐字对齐 `CustomerController.signConsent` 的 record；D1/D5-a/D6 字段名逐字对齐 `FulfillmentController.VisitRequest` / `PlanController.PlanRequest` / `PlanController.DispatchRequest`（**契约对这四处均未声明 requestBody** —— 唯一真相源是控制器 record，第 50 条教训）。② **修不可调用类型**：`listScaleItemBanks` 的 `PageQuery & {...}` 改为新建 `ScaleItemBankQuery`（`age_group?/dimension?/version?: string` + 索引签名 `readonly [k: string]: string \| undefined`，以满足出站 `Record<string, string \| number \| boolean \| undefined>`）。③ **收口端 C 双份实现**：`login.js` 删 `require('services/request.js')`，`request.call('authLogin', ...)` → `session.login(account, credential)`，catch 内按 `err.code` 是否为 number 分流文案。④ **补判据（第 63 条本体）**：`x3-check.mjs` 新增 ⑧ `endpoint-reachability`（与 `a-check` ⑩ 同构：`called` / `fnOf` / `noCallForm` / `noFn` / **`noUi`（封装但未接界面）** / `pageDirectCall` + 计数等式）；`build-check.mjs` 新增 **④g `endpoint-reachability`（CommonJS 专属算法**：`request\.call\(\s*['"]([^'"]+)['"]` 扫全目录 + 解析 `module.exports = {...}` 导出表 + 解析页面 `require` 别名 `aliasOf` + **两种触达形态** + 计数等式）。⑤ **反向验证（证明判据有牙齿）**：`x3-reverse-check` 补 **I9**（摘掉 `ServicePage` 的 `createVisit` 调用，封装仍在、仅断链）/ **I10**（删 `domain.ts` 的 `listStores` 出站）⇒ **10/10 PASS**；`build-reverse-check` 补 **R17**（端 C 登录页绕开会话层、自己直接出站）/ **R18**（删 `domain.js` 的 `getPlan` 出站）⇒ **18/18 PASS**。🛑 **R12 随本轮修法静默失效（脚本自身的元层缺陷，已同步）**：我修 `listScaleItemBanks` 签名后，R12 的注入锚点 `query: PageQuery & { age_group?: string; dimension?: string } = {}` 匹配不到 ⇒ `mutate` 返回原文 ⇒ 脚本按自己的自检规则报「变异未生效 —— 用例本身失效，须修正」；改锚点为真实手抄分页形态 `/query: pageQuery\(page, pageSize\)/` ⇒ 恢复有效。**这正是该脚本「注入必须带来可观测差异」自检的价值：让"用例自己腐烂"变成显式失败而非假装通过。** ⑥ **复验（逐字）**：端 B `endpoint-reachability: 29 个端点全部有完整调用链（services/ 层出站 29/29 · 封装函数 29/29 · 均被页面/外壳以调用形态触达（已扫 9 个消费侧文件；页面/外壳直接出站 0 处 —— 分层纪律成立）` ；端 C 判据**首跑即抓出** `✗ [endpoint-reachability] … authLogin（封装函数 login() 既未被页面调用，也未被任何"自身已被页面触达"的编排函数调用）`；三端 `build-check` 全 `BUILD OK`、`x3-check` `X-3 OK`、`a-check` `A OK 39/39`；反向验证 **10/10 · 18/18 · 16/16**。🛑 **通用规则**：**当同一类约束在多端各有一份实现时，"某一端有这个判据"不等于"这类约束被守住了"** —— 判据的覆盖面必须按【约束类别】对齐，而不是按【端】各自生长；每次新增一条判据都要问一句：**"本仓还有哪些同类宿主没有这条判据？"**（第 53 条"证明判据认识的东西覆盖了全部"的跨端版本）。 |
| 64 | **🔴 第十六类新缺陷的同族第二处（链路断在【装载】层）：第 63 条刚补的 `endpoint-reachability` 判的是「**代码里**有一条从界面到出站的调用链」—— 但**调用链成立不等于页面能打开**。中间还隔着一层**装载清单**：端 C 的 `app.json.pages`（小程序只加载清单里的页面）、端 A / 端 B 的外壳「NAV ↔ 渲染分支」。受控注入实测（**不是理论风险**）：把 `pages/assessment/assessment` 从 `app.json` 的 `pages` 里删掉，**页面文件仍在磁盘、调用链完好** ⇒ 第 63 条刚补的判据仍报 `15 个端点全部有完整调用链` / `BUILD OK` / `exit=0`。🛑 **为什么三道防线一道都没守**：`tsc` 不看 json；`vite build` 只打包被 import 的模块（端 C 更是**根本没有打包器** —— 小程序产物由微信开发者工具编译，本机只校验声明与磁盘一致）；而三条 `endpoint-reachability` 判据**只认调用链、不认装载**。⇒ **「页面覆盖 N 个端点」是可自我声称的，第三层（可到达）此前无人守。** 🛑 **它与第 63 条的关系（必须并列写，否则会以为重复）**：第 63 条修的是「封装了但没接上界面」（链路断在**代码**层）；本条修的是「接上了界面但界面**打不开**」（链路断在**装载**层）—— **缺陷每往上一层就换一个藏身处**。🛑 **两端形态不同，判据必须分两路（照抄一套必然假绿 —— 第 52 条）**：端 C 是**声明式**（`app.json.pages` ↔ 磁盘 ↔ tabBar 三方一致）—— 其中「磁盘有但清单未声明」是**最隐蔽的一类**（文件在、调用链在、却永远不加载）；端 A/B 是**代码式**（NAV 的 key ↔ `type Tab` 成员 ↔ 渲染分支三方一致）—— 且两端渲染写法还不一样（端 A 逐行 `? : null`，端 B 三元链），故判据只提取 `tab === 'x'` 的 **key 集合**，两种写法都覆盖。 | 修法 = ① `build-check.mjs` 新增 **④h `page-registry`**（按 `END_ID` 分两路：端 C 判「磁盘实有页面 ⊆ app.json 声明」「声明页面文件齐全」「tabBar 指向的页面已声明」+ **计数等式** `declared.length === disk.length`；端 A/B **先圈定 NAV 数组本体**（避免把别处的 `key:` 数进来 —— 第 53 条教训）再判「NAV key ↔ `type Tab` 成员 ↔ 渲染分支 key」**三方互查**，并单独报「导航项无渲染分支」「渲染分支无导航入口」「类型松口子」「重复 key」四类，**且判据不认识当前写法时按失败处理、不得当作通过**）；② 反向验证补 **R19/R20/R21**（端 C 摘 `app.json` 声明 / 端 B 改渲染 key 使其两边同时失配 / 端 A 删 NAV 项使渲染分支成死分支）—— **三组各打中一端，且两端形态都被覆盖**。🛑 **通用规则**：**凡「A 存在 ⇒ B 可被用户用到」这类链路，判据必须把中间每一层都钉住**；每补一层都要问一句「**上一层修好了，这一层呢？**」（第 63 条「哪些同类宿主没有这条判据」的**纵向版本**）。 |
| 65 | **🔴 第十七类新缺陷（判据的第八种失效形态：只判"做过"、不判"做对"）**：`endpoint-reachability` 只回答"端点**有没有**被调用"，**不回答"调用的实参对不对"**。实测（读 `吕老师/02-基线评估与核心健康问题辨识表.docx` 逐节映射系统落点时抓出）端 A 域 C 三个必需参数全是编造的：`listScaleItemBanks({page:1, page_size:20})`（契约 `age_group` **required**，且本端点**根本没有分页参数**）、C2 `scale_id: 'baseline'` / `item_group_id: 'default'`（非 UUID）、`age_group_locked` 用 gender+age 拼串（不在 8 值枚举内）⇒ **后端 `ScaleDomain.AgeGroup.parse` 与 UUID 解析必然抛 400**，而 `tsc --noEmit` / `vite build` / 三端触达判据 / 反向验证**一律绿**。🛑 **根因（最硬的一层）**：契约 `BaselineAssessmentRequest.required` 逐字含 `scale_id` / `item_group_id`，但 **C1 的 200 响应在契约里根本没有 data schema**（只有 `ResultEnvelope`），`grep` 实测 `scale_id` **除 C2 入参外 0 次出现**（L1731/1733/1734）、`item_group_id` 同样只出现在 C2 ⇒ **契约里没有任何端点能产出这两个值**；三端各自填了"看起来合理"的假值。🛑 **端 B 的写法是对的、端 A 是错的**（同族副本，两端门禁互看不见 —— 第 63 条形态复现）：端 B 把 C1 返回的 keys 只读带入 C2、取不到则按钮禁用。🛑 **同一判据首跑即抓出另两处同类真缺陷**：① `downloadDocTemplate` 漏 `version` —— 契约 `required: true` **且**后端 `DocFileController.download(..., @RequestParam("version") int version)` **真强制**（缺则 400）⇒ 真缺陷；② `createVerdict` 的 `same_origin` 三项**硬编码为 `true`** —— 契约逐字「任一项缺省 = 不成立 = **不可比 ⇒ 挂起**」，硬编码 true 是**伪造"同源"**，会让本该挂起的轮次算出"可比"结论。🛑 **顺带暴露一处契约内部矛盾（已登记待裁，不得当作已收口）**：`CreateVerdictRequest.required` 含 `risk_flag` / `core_metric_improved`（L1964-1965），而两者**字段描述**逐字写「🛑 缺失 ⇒ 未定（**不得默认「无」**—— 那会让 D4 该触发而不触发）」，**后端** `VerdictController.parseRiskOrNull` 逐字「未传 ⇒ null（⇒ 路由落 D5）」且**无任何校验注解**、`VerdictService:894-895` 把 null 原样落进依据快照 ⇒ **`required` 与「缺失合法」不可能同时成立**；若真按 required 强制，**PRD §7.3 定调句即被推翻** ⇒ 前端采用**如实提供控件 + 留空即不发送**（对齐端 B 范式）并在界面显式登记矛盾。 | 修法 = ① **生成器机械转录必需参数**：`gen-endpoints.py` 从契约 `parameters[].required` + `requestBody.schema.required`（含 `$ref` 解引用）转录 `requiredQuery` / `requiredBody`，三端重跑 + `--check` OK；② **三端各新增一条判据**（端 A ⑩b / 端 B ⑨ / 端 C ④i），**各自按本端语言形态实现**（端 C 是 CJS `function` + `request.call(`，照抄端 A/B 的 `export function` + `call(` **一条都匹配不到** ⇒ 判据"通过"但什么都没查 = 假绿）；③ **修端 A 域 C**：新增 `age_group` 选择器 + `measure_operator` 输入 + C1→C2 只读带入 + 删三个臆造值 + `downloadDocTemplate` 加 `version` 形参（`Number.isFinite` 校验、拒不代填 0）+ `same_origin` 改真实复选框；④ **登记契约矛盾**（见上，待裁）；⑤ **反向验证**：端 A I17/I18/I19、端 B I11、端 C R22/R23。🛑 **复验数字（逐字）**：端 A `10 个带 required 声明的端点中，10 个有调用点可核对` · 端 B `9/9` · 端 C `6/6`（含 1 个动态合并形态，公开计数）；三端 `tsc` 干净、`a-check` 全绿、`x3-check` 全绿、`build-check` `BUILD OK`。🛑 **通用规则**：**凡"某端点必须带上某个参数"这类约束，判据必须判【实参形态】，不得只判"端点被调用过"** —— "端点被调用了"不等于"调用是对的"。 |
| 66 | **🔴 第十七类同族第二处（判据取文本的窗口不能用固定长度）**：⑩b 初版取"函数名前后各 400 字符"，而端 A `createVerdict(...)` 的调用点在此之后长出多个 `...(cond ? {...} : {})` 展开分支 ⇒ 400 字符窗口在 `risk_flag` 之前就被截断 ⇒ 明明**无条件写在调用里**的 `confidence: {}` / `module_scores: {}` 被判成"缺"（实测一次性报出 4 项缺、其中 2 项是误报）。🛑 **固定窗口长度是隐含假设，且两端都错**：窗口太小会误报（把写了的判成没写）、窗口太大又会**假绿**（把隔壁函数的实参算进来）。🛑 **修法**：从函数名后的第一个 `(` 起做**括号配平**（`()` / `[]` / `{}` 三类统一计数），取到匹配的 `)` 为止 —— 那才是这个调用的**真实边界**，与调用点写多长无关；配平失败（文件被截断）⇒ 返回剩余全部，**偏向"不放过"而不是"偏向绿"**。🛑 **回归用例是负向的**：I18 把必需实参**推出 400 字符窗口**、期望判据**仍然绿** —— 若判据退化回固定窗口本用例立刻红；"判据有牙齿"包含两件事：**该抓的必须抓到**（I17）+ **不该抓的不得抓到**（I18）。 | 修法 = ① `a-check.mjs` ⑩b 的调用点文本由"前后各 400 字符"改为 `balancedCall()`（括号配平）；② 三端同类判据同步（端 B ⑨ / 端 C ④i）；③ 反向验证补 **I18**（负向：推出窗口须仍绿）。 |
| 67 | **🔴 第十七类同族第三处（实参经局部变量传入时判据必须追到"所在块"）**：端 A C2 的调用点是 `submitBaselineAssessment(customerId, body, newIdempotencyKey())` —— 6 个必需字段写在**调用单元之外**的 `const body = {...}` 里；括号配平只取这一次调用的括号内、**看不到 `body` 的定义** ⇒ 判据报"6 项**全缺**"，而 `body` 里 6 项其实**完全正确**。🛑 **修法**：调用单元 + **容纳该调用的最内层 `{}` 块**（向上找最内层 `{}` 并配平取整块，块长上限 2500 字符避免把整个文件拖进来）⇒ 判据对"实参怎么传"保持中立：内联字面量、局部变量、条件展开**都认**。🛑 **回归用例同样是负向的**：I19 把该调用改成**内联字面量对象**传入、期望判据**仍然绿**（防"只认局部变量形态"的收窄）。 | 修法 = ① 三端判据均加 `enclosingBlock()`（容纳调用的最内层 `{}` 块）并把"调用单元 + 所在块"一并作为调用点文本；② 反向验证补 **I19**（负向：内联字面量须仍绿）。 |
| 68 | **🔴 第十七类同族第四处（请求体是"动态合并"出来的）**：端 C E2 的调用点写 `Object.assign({ device_id: deviceId }, rec)` —— `metric`（契约 required）**不在字面量里**，而在运行时对象 `rec` 上（来源见同函数内 `rec.metric` 参与拼幂等键）；端 C ④i 判据**首跑即把一个写对了的调用判成缺**。🛑 **修法（带边界的认账，不是一律放过）**：只有当文本里**同时**满足两项时才认：① 确有动态合并形态（`Object.assign(` 或 `...x`）；② **能找到该字段的成员访问证据**（`\.metric`）。二者缺一即仍报红 —— 例如全文没出现过 `.metric` 时，我们**无法证明**它提供了该字段。🛑 认了多少个这样的端点，在通过信息里**公开计数**（`其中 N 个是动态合并形态`），不静默（第 53 条）。🛑 **边界用例 R23 守的是"不得一律放过"**：把 `rec.metric` 的成员访问证据抹掉 ⇒ 判据**必须报红** —— 这防的正是"改成一律放过动态合并"的假绿（第 52 条）。 | 修法 = ① 端 C ④i 的 `argIn2()` 增加"动态合并 + 成员访问证据"双条件认账，并把 `dynRelied` 计数写进通过信息；② 反向验证补 **R23**（抹掉成员访问证据 ⇒ 须报红）。 |
| 69 | **🔴 判据自己的缺陷（由反向验证抓出，而门禁一直是"绿"的）：判据的「具名实参」式把【值位置】的同名标识符误认为实参 ⇒ 实参已删、判据仍绿。** ⑩b 第二式本是"具名/位置实参"：`(?:^|[\s,(])名字[,)]` —— 边界里的 `\s` **过宽**：注入 `credential__removed: credential,` 后，值位置的 `credential,`（前面是"空格 + `:`"）被这一式命中 ⇒ **实参已删而判据仍绿**（实测 `build-reverse-check` **R22 漏过、`exit=0`**，合计 22/23）。🛑 **修法**：边界**只能**是 `(` 或 `,` —— 值位置（`键: 值`）不再算实参，只有真正的参数位置才算。🛑 **该缺陷在三端各有一份副本**（端 A/B/C 的 `argFormPresent` 同式），三处一并修 —— 否则就是第 63 条"同一缺陷在另一端的第二份副本"再犯一次。🛑 **本条的方法论意义**：它由**反向验证**抓出（R22），而**门禁本身一直全绿** —— 这正是"反向验证必须常驻、且**负向用例也要有**"的又一处实证：若只有 I17（正向），第 69 条会长期存活。 | 修法 = ① 三端判据的「具名实参」式边界由 `[\s,(]` 收窄为 `[(,]`；② 重跑 R22 ⇒ 由"漏过"转"抓住"；③ 登记本条（判据自身缺陷，非源码缺陷）。 |
| 70 | **🔴 判据的第九种失效形态（只判「名字在不在」、不判「落在哪个载体」）+ 契约 required 在类型层被降级为可选**：第 65 条补的 `required-args-wired` 只回答"必需参数**名字**有没有出现在调用点/封装体文本里"，**不回答"它落在哪个载体"**。受控复现（**不是理论风险**）：把 I7 的 `version` 从 `query: { version: v }` 挪到 `params: { id, version: v }`、`query: {}` —— 后端 `DocFileController.download(..., @RequestParam("version") int version)` 按 `in: query` 取值 ⇒ **必然 400**，而 `a-check` / `x3-check` / `build-check` / 三端反向验证**全部仍是绿的**（实测注入前后 `exit` 均为 0）。🛑 **根因**：判据对"载体"**没有任何概念**，它把整段调用文本一锅判 —— 于是"名字写在对的位置、却装进错的载体"与"名字根本没写"在判据眼里**完全等价于绿**。🛑 **同一个空洞的第二面（更隐蔽、且是真实源码缺陷）**：`listScaleItemBanks` 的载体形参带默认值（端 A `query: ScaleItemBankQuery = {}`、端 B `query: ScaleItemBankQuery = {}`），**且**类型字段声明为可选（`readonly age_group?: string`）—— 二者叠加 ⇒ 契约 required 在**编译期**被抹平：调用点一个参数都不传，`tsc` 一声不响。实测三端**同一函数三份副本**：端 A/B 可选 + 默认值（两处），端 C `function listScaleItemBanks(ageGroup, dimension, version)` **连运行时守卫都没有**（`listScaleItemBanks()` ⇒ `query = { age_group: undefined }` ⇒ 后端 400，实测）—— 而端 B 的**同一函数在此前已修过**（同一个缺陷在另一端的第二份拷贝，两端门禁各审各端 ⇒ 第 63 条形态第三次复现）。🛑 **第三处真缺陷（端 C）**：`syncOnShow` 只守了 `deviceId`，而 `customer_id` 是 `reportBandSyncBatch` 的契约 required ⇒ "设备有、客户没有"这一次同步会带着 `customer_id: undefined` 出站 ⇒ 400，全部门禁绿。🛑 **为什么"页面有守卫"不算守住了**：`scale-bank.js` 里的 `if (!this.data.ageGroup) return` 只保护了**一个**调用点；封装层是**共享入口**，守卫必须立在入口上，否则第二个调用点（或任何直接调用）会绕过它。🛑 **判据的覆盖面必须证明自己**（第 53 条）：新判据对"载体是简写/表达式或含展开"（`{ body }` / `query: pageQuery(…)` / `…body`）**不逐名核，但公开计数**（实测端 A 逐名 2 处 / 未逐名 8 处），绝不静默放过。 | 修法 = ① **三端各新增判据 `required-args-carrier`**（端 A ⑩c / 端 B / 端 C ④i-2）：**只在出站对象块上找载体**（不得在整段封装体上找 —— 否则 `body: ScreeningRequest` 这种**函数签名的类型注解**会被当载体，实测产生假阳性），载体为字面量块则逐名核（含 `{ a, b }` 简写属性形态）、为局部变量则**回溯其初值块**、为动态合并/表达式则如实计数；② **修类型层**：端 A/B 的 `age_group?: string` 改**必填** `string`、载体形参删掉 `= {}`（tsc 复验 `EXIT=0` 证明调用点确实都传了）；③ **修端 C 入口守卫**：`listScaleItemBanks` 补 `age_group` 非空校验（**并写明为什么页面守卫不够**）、`syncOnShow` 补 `customer_id` 守卫（**位置放在 `hasCollector()` 早退之后** —— 不得改写既有的「能力未接入 = COLLECTOR_NOT_SELECTED」语义）；④ **反向验证各补一条"载体错位"用例**：端 A **I20**（`version` query→params）/ 端 B **I12**（`age_group` query→params）/ 端 C **R24**（query 载体置空、age_group 挪旁路变量）—— 🛑 **必须用"挪载体"而非"删实参"**：删实参只能证明"缺名"会被抓（那是 I17/R22 的形态），**证明不了"错位"会被抓**。复验数字：端 A **20/20** · 端 B **12/12** · 端 C **24/24**，还原后三端构建自检全绿。 |
| 71 | **🔴 判据的第十种失效形态（受保护集由「生成器的认字能力」决定）+ 契约的「必需」有一种形态生成器【从来没认过】**：第 65 条把必需参数判据建在「生成器转出的 `requiredQuery` / `requiredBody`」之上 —— 但**生成器自己不认的形态，就永远不会进入受保护集**，而"没东西要保护"与"检查全通过"在输出上**长得一模一样**。实测两处被静默跳过的形态：① 契约用 `$ref: '#/components/parameters/XxxId'` **复用命名参数**（实测被引用 **23 次**，含 `CustomerId` / `DocTemplateId` 两个 required **path** 参数）—— 原代码 `prm.get("in")` 对 `$ref` 字典返回 `None` ⇒ **23 处全跳过**；② 内联 `in: path` ⇒ 原代码只认 `in: query` ⇒ **全部跳过**。而契约 **P2 逐字**："path 参数恒 required，即 `{id}` 占位符必须在 URL 里被替换" —— 实测 28 个操作含路径占位符，**这 28 个端点的"URL 有没有被替换"此前没有任何判据看过**。🛑 **为什么会静默得这么彻底（三层叠加）**：`fillPath()` 三端同款 —— 只 `replace` params 里**出现过的**键、**不做残留检查** ⇒ 漏传会把字面量 `{id}` 拼进 URL 发出去（后端路由不匹配），既不 assert 也不返回错误；`tsc` 对 `params` 的键**没有类型约束**（`Record<string, string \| number>`）；门禁只看到"generator 说没有必需的，那就不查"。🛑 **注释与代码不符（最值得警醒的一层）**：生成器 L156-157 **逐字写着**"路径参数（路径参数天然必需，但调用方由 params 传入，故只登记名字供判据核对）"，而 L168 **只实现 `in: query`** —— **意图写进注释、代码从未实现，且没有任何判据会发现这个落差**。🛑 **判据的"受保护集"必须自己证明自己**（第 53 条的第三次发作）：判据打印"N 个带 required 的端点中 M 个有调用点可核对"—— 若 `M < N` 尚可察觉；但**生成器漏认形态时 `N` 本身就变小**，输出仍然自洽（端 A 从 30 掉到 10 也不报红）。修法：**在生成器里加断言** —— URL 占位符集合必须**恰好等于**转录出的 path 参数集合，不等即 `SystemExit`；以及**在判据解析层加自检** —— `requiredPath === null` 即报红（防它再次静默消失）。 | 修法 = ① **生成器**：新增 `resolve_param()` **解引用 `$ref` 命名参数** + 转录 `requiredPath`（path 参数恒必需），并**加生成器级断言**「URL 占位符集合 === requiredPath 集合」（不等即 MISCONFIGURED 退出）；② **三端判据并入 `requiredPath`**：端 A ⑩b 的 `need` + ⑩c 的载体表新增 `params` 载体、端 B ⑨ + 载体表、端 C ④i + ④i-2，并各自加**解析层自检**（`requiredPath === null` ⇒ 报红）；③ **反向验证各补一条"path 漏传"用例**：端 A **I21**（`getCustomer` 的 `params.id` 置空）/ 端 B **I13**（`getRefund`）/ 端 C **R25**（`getCustomer`）—— 丢掉的是 **path 参数**（与 I20/I12/R24 丢的 query 载体、I17/R22 丢的 body 实参**各是不同的载体**）。复验：端 A **21/21** · 端 B **13/13** · 端 C **25/25**；受保护端点由 10/9/6 扩大到 **30/25/14**（path 参数并入后），三端 `tsc --noEmit` EXIT=0。 |
| 72 | **🔴 判据的第十一种失效形态（判据把「全部角色」合并判断 ⇒ 角色维度的不可达不可见）+ 第 64 条（装载层）的同族第三处**：第 64 条把触达判据抬到「装载层」（页面在 `app.json` / NAV 里声明了才会被加载）—— 我随后在 README 里写下「**可选第四层**」的猜想：*装载了但准入判定把它永久藏起来*。本轮把猜想做成实证，结论是**它真实存在、且与前三层是同一族**。🛑 **端 B 的装载是「带角色门的」**：`visibleNav = NAV.filter((n) => n.requires === null \|\| canCall(n.requires, role))` —— 导航项的 `requires` 不只是「依赖可查」，它是**该页对各角色的可见性开关**。而三条既有判据**没有一条**把这两件事连起来：⑦ `nav` 只判「该 id 存在于生成物 / 推导函数在白名单内」；⑧ `endpoint-reachability` 判的是「代码里有一条从界面到出站的调用链」，且**对全部角色合并判断**（`consumerCode.some(...)`：只要**任一**页面用了这个函数就算通过）；`tsc` 对"某页对某角色是否可达"更是零概念。🛑 **受控实证（不是理论风险）**：把 `{ key: 'intake', requires: 'createCustomer' }`（双角色端点）改成 `requires: 'createRefund'`（**仅 meridian**）—— **建档页对调理师永久消失**（页面代码、调用链、端点封装**全都还在**），而 `⑦ nav` 仍报「导航项依赖的 6 项全部可核」、`⑧` 仍报「29 个端点全部有完整调用链」、`tsc --noEmit` = 0、`exit` = 0 ⇒ **门禁与类型系统一律全绿**。🛑 **它为什么与第 63/64 条必须并列（否则会以为重复）**：第 63 条是「封装了但没接上界面」（断在**代码**层）、第 64 条是「接上了界面但界面**打不开**」（断在**装载**层）、**第 72 条是「打得开，但某个角色打不开」**（断在**角色装载**层）—— **缺陷每往上一层就换一个藏身处**，而这一层的特殊性在于：页面**确实被装载了**（对 meridian 可用），所以"文件在 / 清单在 / 调用链在"三件事全成立，只有「对 therapist 不可达」这一件不成立。🛑 **判据的不变量（把第 63 条从"任一者可达"升级为"逐角色可达"）**：**grantable(r) ⊆ reachable(r)** —— 某角色「有权调用的端点」，必须至少有一个**该角色能到达的页面**在用它。第 63 条的不变量是它的**弱化版**（把 r 换成"全体角色"）：这正是"合并判断"丢掉的那一维。 | 修法 = ① **端 B 新增判据 ⑪ `reach-by-role`**：解析 NAV（key → 门控端点 → **门控角色集**，三种形态各判：字面量查生成物 / `firstSoleEndpointId()` 由 `solelyGrantedEndpoints()` 现算 / `null` = 恒可见）→ 解析渲染分支（key → 实际渲染的页面组件，内联分支与嵌套组件都认）→ 页面组件 → 它用到的端点（`canCall` 字面量 ∪ 封装函数名回溯生成物 id）→ **逐角色**核算 `grantable(r) ⊆ reachable(r)`，落差逐条报出**是哪些端点**（实测报出建档页 5 个端点：createCustomer / signConsent / getIntakeProfile / patchIntakeProfile / createScreeningRecord）；② **判据的覆盖面必须自证**（第 53/71 条）：`pages/` 下未被任何 NAV 分支覆盖的页面必须显式进豁免清单（端 B 实测 1 个：LoginPage —— 渲染在 `if (!profile)` 分支里、**每个角色都能到达**），且豁免清单里的页面必须真实存在且**确实用了端点**（防"豁免成了垃圾桶"）；判据不认识某形态时**按失败处理**（第 64 条：不认识 ≠ 通过）；③ **反向验证补 I14**（端 B）：把「客户建档」门控换成仅 meridian 端点 ⇒ 须报 `reach-by-role`。🛑 **判据首跑就复发了一次第 55 条（第三次，值得记录）**：初版把"可达"定义为「端点必须挂在某个 NAV 分支里」⇒ **首跑误判 2 处合法写法** —— `authLogin`（LoginPage 在**面板外**渲染，登录前必经）与 `getCustomer`（写在 App.tsx 的 `loadCustomer()` 里，而 `customer` 分支渲染的是 `CustomerPage`，用的是 `listVisits`）⇒ **假红**。🛑 正确语义不是"每个端点必须挂在一个 tab 分支里"，而是**「外壳层代码对外壳可见的所有角色可达」**；修正后**牙齿不变**（I14 仍精确抓住"建档页 5 个端点对 therapist 不可达"）。🛑 **端 A / 端 C 为何无此形态（并已作为覆盖面自证）**：端 A 的 `requires` **恒真**（39/39 端点全授予 admin，其 App.tsx 注释已逐字声明"端 A 的 `requires` 恒真…其价值不在过滤而在依赖可查"）⇒ 门控**不可能**比页面更窄；端 C 是**单角色端**（客户端仅 customer）⇒ 无角色维度。故本条判据只在端 B 有检查对象（第 63 条"判据的覆盖范围按端切分"的正向用法：**只在有检查对象的一端立判据，并在另两端写明为什么没有**）。复验：端 B `14/14`（新增 I14 后）；端 A `21/21` · 端 C `25/25`（未受影响，如实复跑）；三端 `tsc --noEmit` EXIT=0。 |
| 73 | **🔴 第十八类新缺陷（判据的第十二种失效形态：把「请求根本不合法」与「服务端故障」混为一谈 —— 错误码语义反了，而全量 1214 个测试全绿）：未知路由被答成 `500 · 9001「系统异常」`，而契约 **`3001` 的 trigger 逐字限定「仅限本租户内确实不存在」= 业务资源**，未知路由**不在其列** ⇒ 正确形态是 **`404`「不带 code」**（2026-10-01，启动后真请求冒烟抓出）**：应用能正常启动（`Started DyAppApplication in 6.064 seconds` / Flyway 22 迁移校验通过 / `/actuator/health` → `200 {"status":"UP"}`），但 `GET /api/v1/definitely-not-exist` 返回 **`500 · 9001「系统异常: NoResourceFoundException」`**。🛑 **根因**：Spring Boot 3.2+ 对「无处理器匹配」抛 `org.springframework.web.servlet.resource.NoResourceFoundException`，它落到 `GlobalExceptionHandler#handleOther(Exception)` ⇒ 被当成**服务端内部错误**。而契约 §2.0 逐字：`404 → 3001 NOT_FOUND「资源不存在」`；`9001 INTERNAL_ERROR` 的 trigger 逐字是「**服务端异常**」。调用方敲错 URL 与服务端故障是两件事 —— 把前者答成后者有三个具体坏后果：① **端侧行为错**：SDK 拿到 5xx 会按「服务端故障」重试（退避 + 熔断），而这是**永远不会成功**的重试（正确动作是改 URL）；② **告警噪音**：监控上「5xx 率」是服务健康度核心指标，把每个 URL 笔误都计入 5xx，会让真实故障淹没在噪音里；③ **排查方向被带偏**：`9001` 的消息会把运维引去查服务端，而问题在 URL 里。🛑 **为什么全量 1214 个测试全绿却抓不到它**：本缺陷**只在真实 DispatcherServlet 路由解析**里出现，而本仓**全仓无 MockMvc**（E2E 都走真容器，但都只请求**已存在的**端点）；单测直调控制器方法**不经过路由**，永远看不到 `NoResourceFoundException`。这与第 61 条修过的 `HttpMessageNotReadableException` 被答成 500 是**同族**：「请求在进入业务逻辑之前就不合法」的各类都不该落到 `handleOther`。🛑 **与「跨租户不返回 404」不冲突（边界必须写清，否则会被当成过度修改）**：契约 `x-global-conventions.tenant-context` 的「不返回 404 以避免存在性探测」，**语境是「跨租户资源」**——路径**存在**、只是不属于本租户 ⇒ `403 · 2003`；本分支处理的是**路径根本不存在**（无任何处理器匹配），此时不存在「探测某资源是否存在」的信息泄露面（回应与租户无关），且契约已显式定义该码。另，本仓**既有**一处证据表明原行为已被察觉但未被处置：`DocFileE2ETest` 的注释逐字写着「实测遇到 HashSet 乱序导致首个异常**可能是 `NoResourceFoundException`**」—— 那是把它当作**测试侧的解析噪声**绕过去了，而不是当作**生产侧的错误码缺陷**来修（第 24 条：把症状当噪声，而非当信号）。🛑 **修法**：`GlobalExceptionHandler` 新增 `@ExceptionHandler(NoResourceFoundException.class)` → `ResponseEntity.status(HttpStatus.NOT_FOUND).build()`，即 **`404` 且【不带】`code` 字段**（**刻意不回 body**），**留痕级别取 `warn` 而非 `error`**（URL 笔误是**预期路径**，不是服务端故障；沿用本类「4xx 是预期路径、不打堆栈、不进 error 噪音」的分级纪律）。🛑 **为什么不是 `404 · 3001`（这条判据必须写死，否则后来者会把「没有 code」当遗漏而"修"成 3001，撞坏一条既有自证用例）**：① 契约只定义 40 个 path，未知路由**不是契约端点**，「响应必须是四字段信封」这条纪律不适用于它；② 本仓 `RefundWritePathMatrixE2ETest$SelfProof#the_self_proof_discriminates_a_nonexistent_path` **逐字断言「不存在的路径【不得】返回 code=3001」**—— 那条自证用例**正是靠「未知路径无 code」区分「端点根本没实现」与「业务层查无此单」**（正向矩阵断言的正是 404）；给出 3001 会让它丧失分辨力。🛑 **新增机械守护（真请求 E2E，4 例）**：`UnknownRouteEnvelopeE2ETest` —— ① `GET` 未知路径 ⇒ `404` + **非 9001**；② `POST` 未知路径 ⇒ 同上（**不因 HTTP 方法不同而漂移**，若只在 GET 生效说明修复挂错了层）；③ **边界**：未知路径**不得**带 `code=3001`（把上条分辨信号反过来钉住）；④ **对照**：`GET /api/v1/auth/me` 无 token ⇒ 必须 `401 · 1002`（**不得**被误判成 404）。第 ④ 例是这道边界的**反向守护**：把「未认证」谎报成「路径不存在」，客户端会以为端点不存在而去改 URL，**比原缺陷更危险**。🛑 **反向验证（真跑，闭环）**：把 `NoResourceFoundException` 分支**整段删除** ⇒ 重装 `dy-web` 后 `UnknownRouteEnvelopeE2ETest` **`Tests run: 4, Failures: 2` · `BUILD FAILURE`**（第 ④ 例仍绿，**符合预期**——它守的是另一条边界）；还原 ⇒ **4/4 绿**。⇒ 该测试确实有牙齿，不是「写完就绿」的自证式断言（第 51 条）。🛑 **一处过程性教训（已记入工作区记忆第 74 条）**：首轮跑三端 `build-reverse-check.mjs` 时前台 300s 被 SIGTERM 掐断，**脚本残留了注入物**（`client.ts` 的 `err.data` 被剥离、`domain.ts` 的分页被改成手抄）⇒ 隔一轮再跑报「基线不是绿的」，看起来**完全像真实缺陷**，我据此「修复」了 `domain.ts` —— 而 `git show HEAD:<path>` 显示 **HEAD 里本来就是 `pageQuery`**，我「修复」的其实只是把注入改回原样。⇒ **反向验证脚本一经中断，第一件事是 `git status --porcelain` 还原，还原前不得解读任何 FAIL。** 🛑 **复验数字（逐字）**：`UnknownRouteEnvelopeE2ETest` **4/4**（含反向验证：删分支 ⇒ 2 失败、还原 ⇒ 4/4）；`RefundWritePathMatrixE2ETest$SelfProof` **3/3 绿**（本条修复**未破坏**既有设计 —— 它逐字断言「未知路径不得带 3001」）；`DocFileE2ETest` **10/10**（回归：未破坏既有行为）；修复后真请求实测 `GET/POST` 未知路径均 **`404`（body 为空、无 code）**，且 `/actuator/health` `200` · A2 `401 · 1002` · A3 `403 · 2001` 全部回归通过；`DocTestCountAnchorGateTest` **2/2**（先按预期变红报出「实测 1218 vs 文档 1214」，订正锚点后转绿）。 | 修法 = ① **生产代码**：`GlobalExceptionHandler` 新增 `NoResourceFoundException` 分支（**404 · 无 body** · warn 级留痕，附「为何不是 3001」的边界说明）；② **新增真请求 E2E**：`dy-app/.../web/UnknownRouteEnvelopeE2ETest`（**4 例**，含「不得带 3001」与「真实 4xx 不得被误判 404」两条反向守护）；③ **反向验证**：删分支 ⇒ 2 失败 · 还原 ⇒ 4/4（闭环）；④ **文档**：`README.md` 缺陷表 + 本条、`frontends/README.md` 新增 §8.26；⑤ **锚点同步**：`TOTAL 1214 → 1218`（dy-app `956 → 960`，**+4** = 本条新增用例），两处文档锚点按 `DocTestCountAnchorGateTest` 报错订正。 |
> **第 73 条复验（2026-10-01）**：修 `GlobalExceptionHandler` 新增 `NoResourceFoundException` 分支（**404 · 无 body**）+ 新增真请求 E2E `UnknownRouteEnvelopeE2ETest`（**4 例**）后，全量回归 `BUILD SUCCESS` / **`TOTAL 1218 failures=0 errors=0 skipped=0`**（逐模块 `10/39/48/58/37/37/29/960`；dy-app `956 → 960` = 本条新增 4 例）。🛑 **本条修复形态是「404 且【不带】code」**（**不是** `404 · 3001`）—— 既有自证用例 `RefundWritePathMatrixE2ETest$SelfProof` 逐字钉住「未知路径不得带 3001」，本行以此为准。本行锚点由 `DocTestCountAnchorGateTest` 机械守护。
> **本条的教训（值得记住，不只是修一个 pom）**：第 11~13 条缺口长期被**单测掩盖**，且掩盖方式各不相同：
> ① 真库测试（如 `RlsGateSupport` / `RlsTenantIsolationTest` / 各 `*IT`）**自己直连 PG 建连接**，
> 不经过 Spring 容器，因此"容器里有没有 `DataSource`"这件事**从来没有被任何测试触碰过**；
> ② 幂等三态语义测试（`IdempotencyTest`）**只测 `IdempotencyStore` 纯逻辑**，
> 不经过 Servlet 过滤链，因此"拦截器读体后控制器还读不读得到"从未被验证；
> ③ `/demo/me` 的 null 形态更是**没有任何测试覆盖**（`DemoController` 在 dy-app 无对应测试类）。
> 于是 **239 个测试全绿、`BUILD SUCCESS`，而 `mvn spring-boot:run` 一秒即失败、三个接口 500**。
> 结论：**"测试全绿"不等于"能跑起来"** —— 启动与端到端调用本身必须是一条独立的验收项
> （见 §七 的演示链路），不能靠单元/集成测试的绿色来代替。
> 本轮回填的实证口径：构建 8 模块全 SUCCESS（239 tests / 0 fail / 0 err / 0 skip）+
> 应用真启动（Tomcat 8080、`Started DyAppApplication`）+ Flyway V1/V2 落 7 张表 +
> 四个演示接口按契约返回信封（含幂等重放与门禁 403）。
>
> **2026-09-23 追加**：V3（`band_telemetry`）落地后重跑，**8 模块全 SUCCESS（242 tests / 0 fail / 0 err / 0 skip）** +
> Flyway **V1/V2/V3 落 8 张表**。第 15 条缺陷（`data_source` 默认值）即在本轮由反向验证抓出并修复。
>
> **2026-09-25 追加（S2-7 判定域）**：全量 `mvn -o clean install` 重跑 ——
> **8 模块全 SUCCESS / 707 tests / 0 fail / 0 err / 0 skip**（含真库门禁，`dy.rls.gate.skip` 未设），
> 迁移已至 **V6**；判定域新增 **62** 条测试 + `PermissionCodeRegistrationGateTest` **3 → 4**（合计 **+63**，与 644 → 707 逐项对账），
> 并由 `verdict-s2-7-reverse-verification.py` 完成 **16/16 条注入验证**（脚本退出时对 7 个被触碰源文件做**逐字节还原校验**）。
> 本轮**未新增缺陷编号**，但**由反向验证补出一条测试**（`parseRiskOrNull` 的 fail-closed 此前无人执行，注入后仍全绿）——
> 详见 §反向验证「S2-7 判定域的注入」。
>
> **2026-09-25 追加（S2-8 `threshold_version` 溯源回放 / ADR-11）**：全量 `mvn -o clean install` 重跑 ——
> **8 模块全 SUCCESS / 745 tests / 0 fail / 0 err / 0 skip**（含真库门禁），迁移仍至 **V6**（本域**不新增迁移**，
> `threshold_version` 是**服务端算出的指纹串**，落进 V5/V6 已有的 `evidence_snapshot` JSONB，非新列）。
> 本域新增 **38** 条测试（`ThresholdVersionFingerprintTest` 13 · `ThresholdVersionReplayTest` 16 · `ThresholdVersionPersistenceWiringTest` 9），
> 与 707 → 745 逐项对账；并由 `threshold-s2-8-reverse-verification.py` 完成 **15/15 条注入验证**
> （脚本退出时对 7 个被触碰源文件做**逐字节还原校验**）。
>
> ⚠️ **本轮同样"未新增功能缺陷编号"，但补出【三】处真实缺陷（全由反向验证 / 全量回归逼出，均属"补债"而非新功能）**：
> 1. **核心承诺无测试守着（RV-1/RV-2 逼出）**：S2-8 的立论是"**版本号由口径算出、不由调用方给**"（入参 `threshold_version` 只是**断言位**，非空须逐字相同否则拒）。
>    注入"不一致时静默改用权威版本"与"落库值取自入参"后，**S2-8 全部 29 条测试仍全绿** ——
>    即该承诺当时**没有任何测试守着**。故新建整个 `ThresholdVersionPersistenceWiringTest`（9 条），把"三处落库同值 / 入参不一致⇒拒 `4001` 且零写入 / 自描述与落库同源 / 快照段级指纹逐项逐序一致"钉进构建。
>    （**与 S2-7 的 RV-11 同源：一条没人执行过的防线，只能在复盘时被人"读到"，不能被构建拦住。**）
> 2. **`Map.copyOf` 迭代顺序不保证 ⇒ 快照 key 顺序跨 JVM 漂移（RV-14 逼出）**：`ThresholdVersionFingerprint` 的 `segmentFingerprints` 用 `Map.copyOf`，
>    而 JDK 不可变集合带 **per-JVM 随机盐**，迭代顺序**不保证** —— 对本项目"**同一口径 ⇒ 同一结果**"的纪律是**真实的**破坏源
>    （同一份口径在不同 JVM 间会写出 key 顺序不同的段级指纹快照）。已改 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`；
>    `ReplayResult.driftedSegments` 同病同修。
> 3. **值对象架构越界（RV-15 逼出，且【只有全量回归才暴露】）**：`ArchitectureBoundaryTest` 的 **R4（DIP）** 报 4 次违规 ——
>    `ReplayResult`（record）依赖 service 包内的 `ReplayOutcome`。**规则住在 `com.diaoyuanyun.dy.app`（不在 `derived` 包下）**，
>    故 `-Dtest=Threshold*` **不会加载它**，只有 `mvn -o clean install` 抓到。已把两个值对象从 service 迁入 `derived/domain/`。
>    **这条是"定向测试全绿 ≠ 可交付"的又一次实证。**
>
> **2026-09-25 追加（S2-9 契约域 A 补全）**：全量 `mvn -o clean install` 重跑 ——
> **8 模块全 SUCCESS / 795 tests / 0 fail / 0 err / 0 skip**（含真库门禁，`dy.rls.gate.skip` 未设），迁移仍至 **V6**（本域**不新增迁移** ——
> A2/A3 用到的 `store` / `staff` 两表在 V5 已建，本域只落读写）。
> 本域新增 **50** 条测试（`BandVisibilityMatrixTest` 29 · `DomainAEndpointsE2ETest` 20 · `PermissionCodeRegistrationGateTest` 4 → 5），
> 与 745 → 795 逐项对账；并由 `domain-a-s2-9-reverse-verification.py` 完成 **14/14 条注入验证**
> （脚本退出时对 9 个被触碰源文件做**逐字节还原校验**）。
>
> ⚠️ **本轮同样"未新增功能缺陷编号"，但抓出【三】处真缺陷，全部只在"真 JWT + 真 HTTP + 真库"下暴露**（均属"补债"而非新功能）：
> 1. **跨模块改动没 `install` ⇒ 依赖方跑的是旧 jar（静默无效）**：`PermissionRegistry` 于 13:24 给 `store:read` 加登记，
>    而 `~/.m2` 里的 `dy-security-*.jar` 还是 13:02（早 22 分钟）⇒ **A3 对 therapist 恒 403**。
>    **源码、编译、单测三处都无可疑迹象** —— "文件已改"与"语义生效"是两个边界。修法：`mvn -o -q -DskipTests install`（整仓）后再跑依赖方测试。
>    （**已在脚本里固化为 `compile_all()`：跨模块注入必须整仓 install，不是 `compile_main()`。**）
> 2. **错误码须由"契约声明的响应集"决定，而非 HTTP 常规**：A2 只声明 `200`+`401`、A3 只声明 `200`+`403`。
>    `AuthMeService.describeCurrent()` 曾先 `requireTenant()` 后 `requireCallable()` ⇒ 无 token 时租户空 ⇒ 抢答 `2003`(403) ⇒ **A2 对匿名报 403，契约违约**。
>    修法是**调换两行**（身份校验先于租户校验）；`StoreListService` 同步调整（"未携带身份"归因到 `2001`，正是 A3 声明的那个响应）。
> 3. **`List` 传给 varargs 不展开 ⇒ `BadSqlGrammarException` ⇒ A3 整条 500**：`countByScope` 里 `queryForObject(sql, Integer.class, filter.args())`
>    把 `List` 当**单个**参数。**指纹 = "同源 SQL、只有一侧 500"**（`listByScope` 走 `append(...)` 返回 `Object[]` 正常展开，故 list 侧看不见）。
>    修法：`filter.args().toArray()`。
>
> 另 **两条测试纪律在本轮被再次实证**：`surefire:test` **不会自动重编译**（改完必先 `mvn -o -q test-compile`）；
> `-Dtest=A+B+C` 会被**静默忽略**（surefire 用**逗号**分隔，`+` 不报错却什么都匹配不上）。
>
> ⚠️ **S2-9 在方法论上留下一条与本表第 23 条、S2-7 的"输出解码"同族但更尖锐的规则**：
> **一份构建输出里可以同时存在两种编码，且不存在任何单一正确解码策略。**
> S2-7 记录的是"解码策略选错"（全按 UTF-8 解 GBK）；S2-9 遇到的是 **Maven 前缀行（`[ERROR]` / `Tests run:`）是 GBK，
> 而 surefire fork 出来的测试进程打印的断言消息是 UTF-8** —— 同一个 pipe 收进同一份 bytes，
> **按 GBK 解则一半乱码、按 UTF-8 解则另一半乱码**。首轮因此把 14 条**全部正确变红**的注入读成 `5/14`。
> **硬纪律（已升级）**：**反向验证的预期锚点一律只用 ASCII**（测试方法名 / 权限码 / 枚举字面），
> **绝不用中文断言消息** —— 中文消息永远无法在两种编码下都稳定解出。
> 三条合起来是完整规则：**门禁的"绿"和"红"都要能自证；而"锚点本身可被稳定读出"是比"解码策略正确"更靠前的前提。**
>
> **2026-09-25 追加（S2-10 契约域 B 全六行）**：全量 `mvn -o clean install` 重跑 —— **8 模块全 SUCCESS / 829 tests / 0 fail / 0 err / 0 skip**。
> 🛑 **本轮借"跨文档对账"实测校正了四处过期/错记的文档口径**（全部为**只有"去真库/去脚本里数一遍"才能发现**的那一类）：
> ① **RLS 覆盖表数**：文档长期写「**29 张**租户表」，实为 **V5 时代快照**。**实测**（`pg_class` + `information_schema`，`diaoyuanyun_dev`，Flyway 至 **V7**）
>    = **34 张带 `tenant_id` 的表**，其中 **33 张 `relrowsecurity=t` 且 `relforcerowsecurity=t`**，唯一未启用的是 `audit_log`（登记豁免 **T-09**）；
>    且 `RlsCoverageGateTest` 的登记表实测 **33 条**（含 V6 三张退款账本 + **V7 的 `intake_profile_revision`**）—— **登记数 = 真库启用数 = 33，三源自洽**。
> ② **模块数**：移交包 TL;DR 与开发清单长期写「**9 个 Maven 模块**」，实为 **8**（`pom.xml <modules>` 8 条；`mvn` reactor 的 9 行含**根聚合 pom**）—— **S2-7 已订正，本轮复查无回退**。
> ③ **反向验证的被触碰文件数**：S2-8 段写「7 个」，实测 `touched` 清单为 **4 个**（S2-7 的 7 ✅ / S2-9 的 9 ✅ 均为实测值，仅 S2-8 抄错）。
> ④ **`RlsCoverageGateTest` 的真实牙齿**：本轮首次**逐条导出**它的 33 条登记映射（表名 → 隔离测试类），确认每一条都指向一个可 `Class.forName` 加载的类。
> **通用教训（与 §5.1 的"总数必须能互相推出"同一条）**：**「29」这类数字会随每一轮迁移悄悄过期，而没有任何门禁会因此变红** ——
> 因为它不在构建的断言面上，只在文档里。**跨文档对账必须重新去源头数一遍，不能靠上一版文档互抄。**

> **2026-09-25 追加（S2-10 契约域 B 全六行 B1~B6 + 七处缺口）**：本轮**新增缺陷编号第 29~36 条**（共 **8** 条），并把"已修缺陷"表由 28 条扩到 **36 条**。
> 全量 `mvn -o test` 重跑 —— **8 模块全 SUCCESS / 829 tests / 0 fail / 0 err / 0 skip**（含真库门禁，`dy.rls.gate.skip` 未设），
> 迁移仍至 **V7**（本域**不新增迁移** —— B 域六行用到的 6 张表在 V1/V2/V5/V7 已建）。
> 本域新增 **34** 条测试（`DomainBEndpointsE2ETest` 21 新建 · `RlsV7IntakeProfileRevisionIsolationTest` 12 · `DerivedVisibilityE2ETest` +1），
> 与 795 → 829 逐项对账；反向验证 `93_domain_b_reverse_verification.py` **7 条真注入，7/7 全部被抓且还原后全绿**。
>
> 🔴🔴 **S2-10 的核心事实：这是本仓库"注册表与调用点各自演进"这一同型缺口的【第七次】复发，且首次出现了比前六次更严重的形态 —— 门禁逻辑本身自相矛盾。**
> 前六次（S2-5 第 25 条、S2-9 第 1 条）都是"权限码层面"的错配；第七次是**一个守卫被挂在了它自己的产出端点上**（B2 建档端点的门禁里含 `assertProfiled`，
> 而"已建档"正是 B2 的产出）⇒ **B2 对全部角色 100% 恒 403**，且报出的缺失项与真实成因**完全相反**（排查者会去"补建档"，而正确动作是"这次调用本就该成功"）。
> **它躲过了全部静态检查**：编译通过、单测全绿、码级门禁全绿、连"B1 通不通"的检查也绿 —— 因为 B2 与 B1 是两条独立的调用链。
>
> **七处缺口的完整清账（第 29~35 条，全部只在"真容器 + 真签名 JWT + 真库"下暴露）**：
> 1. **第 29 条（缺口 A，第五次同型）· 权限码粒度不足**：`customer:write` 被三类语义共用（域 B 档案写入 / E5 小程序探测 / 题库导入组卷），
>    而注册表只把它发给"管理层级 + 遗留大写码"，**不含契约点名的 therapist / meridian** ⇒ B1/B2/B3/B6 对一线角色全部 403。
>    修法 = **拆码**（不是补码）：新立 `customer:archive` 承载"档案写入"（发给契约点名的全部角色），`customer:write` 保持承载"内容资产写入"（仅管理层级）。
>    **为什么不让 therapist/meridian 直接持有 `customer:write`**：那会顺带把"题库导入组卷"的写权发给一线角色 —— 修一个缺口时开出另一个更大的口子。
> 2. **第 30 条（缺口 B，第六次同型）· 端点贴了与契约角色不匹配的码**：`BandAvailableDatesController`（E5）贴 `customer:write`，
>    而契约 `x-callable-roles: [client]`，且注册表**刻意不登记 client** ⇒ 合法客户端探测恒 403。修法 = **摘码**（与 A2 `/auth/me` "刻意的缺第三层"同款）。
>    **它为何躲过全部既有门禁**：① 码级门禁只问"码有没有主"，`customer:write` 有主 ⇒ 绿；② `DerivedVisibilityE2ETest` 里那条对 E5 的 403 断言，
>    命中的是**派生字段拦截器**（排在权限拦截器之前，因请求体含 `as_value`），与权限层无关 —— **两条完全不同的路径归同一个 `2001`，把缺口掩盖了**。
> 3. **第 31 条（最严重）· 自环门禁**：见上。
> 4. **第 32 条 · SQL 参数未转型 ⇒ uuid 列收到 varchar**：`UPDATE_PROFILE_SQL` 的 `owner_store_id = ?` 缺 `::uuid`
>    ⇒ PG 报 `42804`（"字段 owner_store_id 的类型为 uuid, 但表达式的类型为 character varying"）⇒ **B2 恒 500**。
>    成因：JDBC 的 `setString` 把参数按 **varchar** 类型发送，而 PG 的隐式 `unknown → uuid` 转换**只对未定型参数生效**；
>    一旦参数被声明为 varchar，`varchar → uuid` 就是**显式**转换、不再隐式。**硬纪律：uuid 列一律 `?::uuid`，一个都不能漏。**
> 5. **第 33 条 · `Map.copyOf` 拒收 null 值 ⇒ 修订留痕恒 NPE**：`IntakeProfileRevisionRow` 用 `Map.copyOf(snapshot)`，
>    而 `intake_profile` **每一列都可空**（未采集字段就是 null），`snapshotOf()` 忠实保留它们 ⇒ `Map.copyOf` 抛 NPE ⇒ **B6 恒 500**。
>    修法 = `Collections.unmodifiableMap(new LinkedHashMap<>(...))`（容忍 null 值）。
>    **为什么不滤掉 null**：那会让快照变成"只记录填过的字段"，与"完整快照 vs 增量"的纪律直接冲突（字段后来被清空就无法从历史看出）。**与第 2 条（S2-8 的 `Map.copyOf` 保序缺陷）同族、不同病。**
> 6. **第 34 条 · 塌掉的可观测性（本轮的"放大器"）**：`GlobalExceptionHandler` 对未捕获异常**一个字节的堆栈都不落盘** ——
>    只把 `ex.getClass().getSimpleName()` 塞进响应体加一个 `trace_id`。对外只回显类名是对的（契约纪律），**错的是服务端也不留**：
>    运维拿着 `trace_id` 在全仓库日志里搜不到任何对应行，**无法定位任何一次 500 的真因**。
>    第 32/33 条之所以排查困难，正是被它放大：只报出异常**类名**，而类名既不含 SQL 也不含列名/参数，排查被迫退化为"读源码猜哪条 SQL 写错了"。
>    修法 = **分级留痕**：`BizException` 且 HTTP ≥ 500 → `log.error`（带堆栈）；其余 `BizException`（4xx 正常业务拒绝，如 403 门禁）→ `log.warn` 一行、不带堆栈；
>    其它任何 `Exception` → `log.error`（带完整堆栈）。两者都带 `trace_id` 与 `uri`，使"响应体里的 trace_id"与"服务端日志行"**可双向检索**。
> 7. **第 35 条 · `httpclient5` 的传递依赖在离线仓库缺失 ⇒ 整仓 install 被打断**：`TestRestTemplate` 默认用 `SimpleClientHttpRequestFactory`（`HttpURLConnection` **不实现 PATCH**）
>    ⇒ 报 `Invalid HTTP method: PATCH`，B6 与域 E 的若干 PATCH 行"只能被绕过或不测"。修法 = 加 `httpclient5` test 依赖；
>    但 Spring Boot BOM 给的 `5.2.5` 在本地仓库不存在（其传递依赖 `httpcore5:5.2.5` / `httpcore5-h2:5.2.5` 亦缺），**只降 `httpclient5` 自身不够 —— BOM 仍把 `httpcore5` 拉回 5.2.5**，
>    须在 `dy-app` 用 `dependencyManagement` 覆盖 BOM 判定（当前 pom 层优先于父 pom 导入的 BOM）。**这是一条"离线构建"的通用坑。**
>
> ⚠️ **S2-10 在方法论上留下三条规则，其中第 1、2 条把本仓库长期以来的"门禁盲区"变成了可执行的事实**：
> 1. **码级门禁看不见"角色级"缺口，只能靠真请求 E2E 抓 —— 本轮把它变成了可复现的证据。** 反向验证脚本里有一条**附加断言**：
>    把 B1 的码从 `customer:archive` 改回 `customer:write`（第 30 条的复发），断言"真请求 E2E 必须变红，**而码级门禁必须仍然全绿**"。
>    两条同时成立，才证明"门禁不是坏了，而是它回答的从来不是这个问题"（门禁只问"有没有**任何**角色持有"，不问"**契约点名的**角色是否都持有"）。
>    同理，I1b 单摘 `therapist` 的 `customer:archive` 时门禁仍绿 —— 因为该码还剩 meridian 等 7 个持有者。**"码有主"与"契约角色可达"是两件事。**
> 2. **逐条守卫看不见"组合自环"。** 第 31 条的成因是两个**各自都正确**的守卫被串在一起：`assertScreeningResult` 对，`assertProfiled` 对，
>    但把后者施加于"它的产出端点"上就自相矛盾。**没有任何单条守卫能看见这件事** —— 只能由"真请求"看端点的**端到端可用性**。
>    修法 = B2 只调 `assertScreeningResult`；`assertAdmissionChain` 标 `@Deprecated` 并保留为**陷阱登记**（不删，防后人再踩）。
> 3. **反向验证的"未被抓住"有两种，必须分辨**——本轮首跑 `6/7`，唯一未抓的是"单摘 therapist 的码"。
>    它**不是守护失效**，而是**我最初把 I1 设计错了**：以为"摘掉 therapist/meridian 的码"会让码【彻底无主】，实际该码还有 7 个持有者，故门禁正确地保持绿。
>    修法 = 拆成 I1（改成注册表里不存在的码 `customer:phantom` ⇒ **门禁必须红**，证明门禁能兜"码无主"）与 I1b（单摘 therapist ⇒ **E2E 必须红 + 门禁必须绿**，证明盲区存在）。
>    **教训：一条"未被抓住"要先分辨"守护坏了"还是"注入的假设错了"，再动手改。**
>
> 另 **两条测试纪律在本轮被再次实证**：
> - `Map.copyOf` / `Map.of` 系列**对 null 值一律抛 NPE**（`Map.of` 连 null 键也拒）。凡承载"可能为空的快照/投影"的容器，**不得用它们做防御性拷贝**。
> - **`-Dtest` 的嵌套类语法**：`DomainBEndpointsE2ETest$Customers`（`$` 在 bash 里须引号包裹），用于只跑 `@Nested` 分组；
>   若写成 `-Dtest='DomainBEndpointsE2ETest$Customers'` 而不加引号，`$Customers` 会被 shell 展开为空。

### 5.2 仍为 TODO（框架预留，非本轮范围）

- [x] ~~**真实 JWT 签发/校验**~~ → **本轮已补全**（`JwtVerifier`，JDK 原生 HS256，15 个攻击面测试 + 3 次反向验证）。防护点：`alg:none` 拒绝 · 算法不按 header 动态选择（防 alg 混淆）· `MessageDigest.isEqual` 常量时间比较 · `exp`/`nbf`/`iss` 校验 · 密钥缺失 fail-closed 拒绝一切 token · 弱密钥构造期 fail-fast。**仍待补**：真实签发端（本骨架只校验不签发）、以及吊销检查的 **Redis 实现**（接口 `JwtVerifier.TokenRevocationChecker` 已留，默认放行）。
- [x] ~~**RLS 端到端集成测试进 CI**~~ → **C-2 已收口（2026-09-27）**。真实 PG 的 psql 断言早已建好并全绿（见 `verification/`），C-2 补的是**可移植性**与**接进 CI**：
  - **① 硬编码绝对路径已消除**：`verification/015_apply.sql` 原写死 `\i 'C:/Users/lenovo/...'`，使它**只能在这一台机器上跑** —— 接进 CI（linux runner / 任意检出路径）必然 `P0002`/`42P01` 失败，而"只能在原作者机器上跑的验证"等于没有验证。**改为双通道**：优先 `-v v1=<绝对路径>`（与既有 `91/96/98_b12_*.sql` 同一口径），未传参时回落 `\ir ../dy-app/...`（相对**本文件所在目录**，非 cwd）。**两种模式均已从不同 cwd 实测通过**（Windows 本机，相对模式 cwd=`product-strategy`）。
  - **② CI 工作流**：`.github/workflows/rls-isolation-gate.yml`。与另两个 workflow **各自独立**（三者的"红"含义不同：词表扫描 / 加密运行时 / 租户隔离，混在一起会让一次红分不清是哪一类 —— 而"分不清的红"会被整批跳过）。
    - **step 1（实现者证据）**：`mvn -B -pl dy-app -am test`（🛑 **不带 `-Dtest` 筛选** —— 2026-09-30 修正）。原文写 `-Dtest='Rls*Test' -DfailIfNoTests=false`，**在本仓结构上不可能通过**：`dy-config` 的 `config-truth-source-gate` execution 把 `<failIfNoTests>true</failIfNoTests>` **写死在 pom 里**，plugin 配置值优先于同名 CLI 属性 ⇒ `-am` 连带 dy-config 时**任何** `-Dtest` 筛选都会让它以 `No tests were executed!` 失败（实测：两个属性全传、或用 `-Ddy.config.gate.skip=true` 逃生阀，均 FAILURE）。正解实测 **9 模块全 SUCCESS**，`rls-isolation-gate` 110 例照常执行。⚠️ 反向验证脚本的 `-Dtest=` 写法是**对的**，因为它们用 `-pl dy-app` **不带 `-am`**，不连带 dy-config。
    - **step 2（独立证据）**：`bash verification/99_b12_run.sh`（纯 psql 驱动，**不同库、不同角色**，额外证明"整条迁移链连跑 2 次幂等"）。
    - **🛑 step 3（负控自检）—— 本 workflow 最重要的一步**：主动 `ALTER TABLE customer NO FORCE ROW LEVEL SECURITY;` 一次，断言**隔离套件必须因此变红**；变红了才证明门禁承重，没变红说明门禁早已是假通过 ⇒ job 直接判红。理由：`FORCE RLS` 一旦悄悄失效（owner 被换成超级用户 / 迁移漏了 FORCE），隔离断言会**假通过** —— **假通过的门禁比没有门禁更危险，因为它给出虚假保证**。
    - **负控逻辑已在 Windows 本机预演坐实**：`customer` 基线 `rls=t force=t owner=dy_app_b12`；注入 `NO FORCE` 后 `03_assert.sql` 的 A1 断言报错且 psql 退出码 **3**；还原后退出码回 **0**。
  - **配套**：`psql` 定位已含 `/usr/bin/psql` 与 PATH 回退（`RlsGateSupport.psqlExecutable()`），故 CI 上无需改任何 Java。连接口径三变量（`DY_PG_HOST`/`DY_PG_PORT`/`DY_PG_SUPER_PASSWORD`）+ `DY_TESTDB_RESET=1`（CI 一次性环境，从零重建顺带证明"整条迁移链能在空库上建起来"）。
  - **✅ 已由套件内门禁承担的旧承诺**：「每张新表无隔离测试即构建失败」**早已由 `RlsCoverageGateTest` 实现**（三源交叉核对：迁移脚本文本 / 登记表 / 真库 `information_schema`）。当前 29 张租户表全部覆盖；真库实测 33 张业务表中 29 张 `rls=t force=t`，未启用的仅 `audit_log`（T-09 全局单链豁免）/ `schema_migration`（元数据）/ 宿主表 `tenant`，三者均为**登记豁免项**。
  - **剩余人工动作**：接进远端后需在仓库设置里把 `rls-isolation-gate` job 设为 **required check**（工作流本身无法阻断合并）。且本仓库**目前不是 git 仓库**，故此文件在提交前不生效 —— 与另两个 workflow 同属"先把门禁写成可提交的形态"。
  > **2026-09-24 现状补充（V5 落地后）**：「每张新表无隔离测试即构建失败」这一条**已由套件内门禁实现**：`RlsCoverageGateTest` 要求每张带 `tenant_id` 的表**既登记又存在可加载的真库隔离测试**，并三源交叉核对（迁移脚本文本 / 登记表 / 真库 `information_schema`）。V3 的 `band_telemetry`、**V4 的 `scale_item_bank`**、以及 **V5 的 24 张表**均已登记（分别映射到 `RlsBEntityIsolationTest` / `RlsScaleItemBankIsolationTest` / `RlsV5EntityIsolationTest`），故**当前 29 张租户表全部被覆盖**（`customer` · `customer_state_transition` · `band_telemetry` · `scale_item_bank` · **V5 的 24 张** · 及 `RlsTenantIsolationTest` 覆盖的基表）。**真库实测**（`diaoyuanyun_dev`，Flyway 至 v5）：33 张业务表中 **29 张 `rls=t force=t`**；未启用 RLS 的仅 `audit_log`（全局单链哈希豁免，见 **T-09**）、`schema_migration`（元数据表）与宿主表 `tenant`（`TENANT_HOST_TABLES`），三者均为**登记豁免项**。待办是把它以及 legacy psql 套件一并接入 CI。
- [x] ~~**幂等存储分布式化**：`IdempotencyStore` 为内存 Map（24h TTL），生产必须换 Redis。~~ → **已收口（2026-09-27 · 批次十二）**。
  > ⚠️ **勿与 B-2 混淆**（两条是**不同**的幂等）：本条的 `dy-web/IdempotencyStore` 是 **HTTP 层**幂等（请求重放，24h TTL）；**B-2** 是**手环同步域**的幂等（`BandService` 曾用进程内 Map 做遥测去重）。B-2 **不采用**"换 Redis"这个建议 —— 理由见下条。
  - **已就绪的部分（早于本轮）**：`IdempotencyBackend` 端口 + `RedisIdempotencyBackend`（显式文本编码、非 JDK 序列化；`v=1` 格式版本，未知版本按 fail-closed 拒绝）+ `IdempotencyConfiguration` 按 `dy.idempotency.backend` 选择后端；**redis 模式下容器无 `StringRedisTemplate` ⇒ 启动失败**（不静默回落内存）。
  - **🛑 本轮补的是"默认值 + 启动纪律"** —— 原状态是 `env.getProperty(BACKEND_PROPERTY, MEMORY)`：**默认 memory 对单实例/本地是对的**（骨架与测试无需外部资源），但**多实例生产**下若有人漏配，就会以内存后端启动 ⇒ **同一幂等键打到两个副本双边判 `FIRST`** ⇒ 重复下单 / 重复扣款 / 重复发放照常发生，而日志、指标、健康检查**全部正常**。
  - **收口（两道防线）**：① `application.yml` 的 **prod 段显式给 redis 默认**（`${DY_IDEMPOTENCY_BACKEND:redis}`）；② `IdempotencyConfiguration.reject_memory_backend_in_production(...)` —— **active profile 含 `prod` 且结果为 `memory` ⇒ 抛 `IllegalStateException`，应用拒绝启动**（报错点明键名与正确取值）。判据用 **active profile** 而非新增开关：profile 是部署时已在用、**无法"忘记打开"**的既有机制；且对 `"Prod"` / `" prod "` 变体归一化（否则可静默绕过）。
  - **门禁**：`IdempotencyBackendFailureTest`（**6 → 11 例**：prod+memory 必抛 · prod+redis 通过 · dev/test/local 不被误伤 · 变体归一化 · 存储四条路径 fail-closed 不静默放行）+ `IdempotencyProdBackendConfigGateTest`（**2 例**，snakeyaml 真解析 prod 文档的键路径 —— **断言交付物本身**，不依赖启动 context；另含"非 prod 文档不得被当成 prod 段"的元层自证）。
  - **反向验证**：`verification/110_b2_b3_reverse_verification.py` C1/C2 两组（prod 段改成 memory / 自检守卫掏空成 `if (true)`）**均被抓**，逐字节还原后复绿。
  - **🛑 批次十三补完（B-2b，2026-09-27）**：上面两道防线**漏了"prod + redis 但 Redis 连不上"** —— Lettuce **惰性建连**（pom 注释逐字："afterPropertiesSet 阶段不建连接，故障只发生在真正使用的那一刻"）⇒ 应用**照常启动成功**，直到第一个带 `Idempotency-Key` 的写请求才全线 5xx。**收口**：新增 `IdempotencyConfiguration.verify_redis_is_reachable_at_startup_if_production(...)` —— **prod 下启动阶段真发一次只读 `HASKEY` 探活**，不可达即拒绝启动；**非 prod 不探活**（本地无 Redis 属正常）。新增 **3 例**（`IdempotencyBackendFailureTest` 11 → **14**）。反向验证 `verification/111_b2b_redis_startup_probe_reverse_verification.py`（**2 组注入 / 3/3 PASS**：C1 掏空守卫 = "自检根本没跑" · C2 吞异常 = "fail-closed 退化成 fail-open"）。
  - **剩余（运维决策，不代拍）**：生产 Redis 的**高可用形态**（主从 / 集群 / 哨兵）与**凭据注入方式**。
- [x] ~~**手环同步域幂等为进程内 Map ⇒ 多实例部署幂等失效**~~ → **B-2 已收口（2026-09-26）**。
  - **🛑 为什么不采用清单/惯例建议的"换 Redis"**：手环遥测的幂等键是 `(device_id, day, hour, minute)` / `(device_id, 'sport', sport_id)` 这种**业务复合键**，而业务行**已经在 PostgreSQL 里**（`band_telemetry`，V3 长表化）。把它搬去 Redis 只会**再造一个外部系统的双写同步问题** —— 而真相源本来就是库。故修复方向是**让库层承担幂等轴**，而不是引入第二个存储。
  - **落地（三条防线）**：① `BandLedger.INSERT_TELEMETRY_SQL` 末尾加 `ON CONFLICT DO NOTHING`（库层原子轴，唯一索引 `uq_bt_daily_idempotent` 兜底）；② `BandService` **删掉**进程内 `Map` 字段、`reportSyncBatch` 去掉内存快路径、改为回库查真实租户的 `telemetryId`；③ `findTelemetryId` 的**日型分支**补 `AND sport_id IS NULL`（否则游标型行会被日型键误判为幂等命中，两条分支的幂等键混同 —— 契约 §4.3 明令运动数据不得复用按日型幂等键）。
  - **门禁**：`BandIdempotencyConcurrencyTest`（**7 个 `@Test`** / 4 个 `@Nested`：真库并发同键写入恰一行且无失败 · 重放返回**真实** id 而非"碰巧有新 id" · 两分支隔离 · **结构性断言"BandService 不得有进程内 Map 字段"**，不依赖并发时序，是最可靠的那一条）。
  - **反向验证**：`verification/99_band_idempotency_reverse_verification.py` —— **3 组注入**（删 `ON CONFLICT` / 给 `BandService` 加回 Map 字段 / 删日型分支的 `AND sport_id IS NULL`）全部被抓，逐字节还原，还原后基线绿。
  - **⚠️ 同时修掉了验证脚本自身的一个假阴性**（重要教训）：该脚本初版把 `must_see` 写成**中文断言原文**，而 Maven/surefire 在 Windows 上输出 **GBK 字节流**，主流程按 UTF-8 解码后中文全乱码 ⇒ `must_see in ro` 恒为 `False` ⇒ 注入**其实都被抓到了**，却被判成"未被抓住"。修法：判定锚点一律用 **ASCII 全限定方法名**（如 `BandIdempotencyConcurrencyTest$NoInProcessMap.band_service_has_no_in_process_map_field`）。**凡在 Windows 上做输出文本判定，锚点必须是 ASCII。**
  - **仍登记待裁**：游标型 `sport` 分支**不可达**（3 条互斥走法待选）；已由测试哨兵钉住，不是静默缺口。
- [x] ~~**审计日志哈希链校验端点**~~ → **C-1 已收口（2026-09-27）**。`audit_log` 的链计算写入早已落地（`JdbcAuditLogService` + `AuditChainHash` v1 冻结规范 + 黄金向量交叉验证），C-1 补的是**校验端点**与**它的证据**：
  - **端点**：`GET /api/v1/audit/log-chain`（+ `/contract` 自描述）。刻意取 `log-chain` 而非 `chain` —— 与契约 F3 `GET /audit/signals` / F4 `GET /audit/coverage` **同前缀、不同面**：F3/F4 是**业务稽核**（依赖"稽核信号 / 覆盖率"上游口径，**仍未立项**），本端点是**技术性证据链自检**。路径把 `log-chain` 写进去，使这个区分在读路径时即成立（并有 `f3_and_f4_remain_unimplemented` 用例防"顺手把 F3/F4 也做了"）。
  - **权限 = 仅 hq**，码取新码 `audit:read`（已登记进 `PermissionRegistry`）。理由不是"审计数据敏感"这种泛泛之谈，而是一条结构性事实：`audit_log` 是**全局单链**（跨租户串联，刻意豁免 RLS / T-09），**不属于任何单一租户**；而契约 F3 的 `x-row-scope`「门店仅本店、区域仅辖区」预设了"对象属于某租户"。放进 `area`/`manager` 会造出"本店 scope 的身份读全局对象"的语义裂缝。**待裁项已登记**（区域督导自检三门走法，前两种会产出**必然为假**的"本辖区链有效"结论）。
  - **能力边界随响应下发**（`capability_envelope` 四条 + `not_proven`）：不让 `valid=true` 被读成"数据从未被篡改"。其中**"链尾截断不被发现"是本轮真请求实测新挖出的盲区**（既有破坏性门禁只覆盖"删中间行"，而擦掉最近的操作痕迹恰是攻击者的首选动作），并已补进 `AuditChainGateTest`。
  - **测试**：`AuditChainEndpointE2ETest`（**12 例**，dy-app，真 JWT + 真 HTTP）+ `AuditChainGateTest`（**12 例**，dy-audit，**独占库** `dy_audit_chain_test`，psql 扮演"有 DDL 权限的攻击者"）。
  - **🛑 一条用真实事故换来的纪律**：该 E2E 套件**只读**。首版把"改写 / 删行"注入直接做在**应用库**的 `audit_log` 上，而它是**全局 append-only 单链** —— 并行跑的其他套件会持续追加行，于是"快照→注入→还原"之间必然夹进别人的新行，还原时**删到了链的中间**，让链**永久断链**、整套真库门禁连锁变红（实测 dev 库被断在两处）。**结论：对一张跨模块共享、append-only、链式校验的全局表，"快照 + 还原来做破坏性注入"在原理上就是错的 —— 不是"可能竞态"，是"必然污染"。** 破坏性注入只允许发生在**本套件独占的库**里。
  - **反向验证**：`verification/101_c1_audit_chain_reverse_verification.py` —— **4 组注入**（`checked` 恒 0 / 摘掉 `@RequirePermission` / 削掉"链尾截断"边界 / 给 `area` 补 `audit:read`）**全部被抓**、逐字节还原、还原后基线绿。其中第 4 组只改 `dy-security` 的登记表、**控制器一行不动**，专门证明"可调角色的真相源是登记表"，只看控制器的评审会漏掉这条路径。
- [x] ~~**配置真相源落库**：`ConfigServiceImpl` 为内存实现；真实以 DB 表为真相源（ADR-08），并接入 46 条非空配置(#1~#41, #43~#46, #48) + #42 空号 + #47 预留缺席。~~ → **B-3 已收口（2026-09-26）**。**清单原文的现状描述只对了一半**，勘察后按事实处置（登记偏差，不照抄）：
  - **推翻的部分**：真相源实现**早已完整存在**，不是"只有内存实现"。`JdbcConfigService`（DB 唯一真相源 + 逐操作 RLS + 触发器留痕）与 `dy-config` 的三份 SQL 资产（`01_truth_source_ddl.sql` / `02_slots_seed.sql` / `03_seed_tenant_values.sql`）、以及 **5 个真库 IT**（`ConfigTruthSourceIT` / `ConfigSlotIntegrityIT` / `ConfigServiceFailClosedTest` / `ConfigReverseVerificationEvidenceTest` / `ConfigGateSupport`）都在。缺的**不是实现，是"落库位置 + 装配"**。
  - **真实缺口（三条，均已处置或登记）**：
    1. **三表只建在 dy-config 的独立门禁库，应用库还没有** ⇒ **V14 已补**：`V14__config_truth_source_tables.sql`（884 行）把 `config_slot` + `app_config` + `app_config_history` 落到应用库，含 FORCE RLS、取值校验/变更留痕/append-only 三触发器、`$seed_guard$` 灌入守卫与 `$v14_guard$` 自证。
    2. **配置的【真实生产读路径】不是读内存 Map，而是 4 个 `ConfigSeed*ProfileSource` 在启动期用正则解析 `02_slots_seed.sql` 的文本**（`#35` 量程 / `#43` 手环可见性 / 退款口径 / 派生口径）⇒ **已登记、V14 已具备替换前提，替换本身仍是下一步装配收口**（不是遗漏：这 4 个类各自的类头逐字写着"配置真相源落库后应替换为 DB 读取实现"，是**设计上明写的过渡态**）。
    3. **`ConfigServiceImpl`（内存、无租户维度）与 `JdbcConfigService`（DB 真相源）曾同时标 `@Service`**，而 `ConfigService` 接口的生产注入方**为零** ⇒ **已处置**：去掉内存实现的 `@Service`（类与 `ConfigServiceFailClosedTest` 零改动），并新增 `ConfigWiringDisciplineTest`（4 断言，反射断言编译产物而非读源码文本）把它从"注释纪律"升级为**构建期事实**。
  - **落地清单**：
    - `V14__config_truth_source_tables.sql` —— 唯一刻意的偏离已登记：02 的 `DELETE FROM config_slot;` + 裸 INSERT 改为 `INSERT … ON CONFLICT (config_no) DO UPDATE SET 7 列`。**理由（已实测）**：应用库里 `app_config.config_no` 外键引用 `config_slot`，只要租户已初始化（app_config 46 行），`DELETE FROM config_slot` 立刻以 **23503** 失败 ⇒ 原写法在应用库**不幂等**。同时**刻意不用 `DO NOTHING`**（那会让"改了 `initial_value` 却不生效"重现 —— 正是 02 原注释逐字想避免的效果）。
    - `RlsV14ConfigTruthSourceIsolationTest`（**8 个 `@Test`**，真库）—— 两表 FORCE RLS 隔离 / 无上下文 fail-closed / 跨租户写入 42501 / 策略元数据与 owner 非超级用户 / **编号域（46 条 + `#42` 永久封死 + `#47` 暂缺）**/ 取值校验四类型分支（INT·BOOL·JSON·ENUM，含 `#44` 禁止组合）/ **留痕纪律（由触发器写入 + append-only 42501）**。
    - `ConfigTruthSourceMigrationSyncTest`（**6 个 `@Test`**，构建期）—— **真源文件 ↔ V14 内联段逐字同步门禁**，把 V14 文件头登记的那个漂移点（内联副本 vs 真源）变成机械事实。三段分别比较（DDL / 声明 / `$seed_guard$` 守卫），并把那**唯一一处偏离写成结构化常量**（不"跳过一段"——跳过等于在分叉点上关掉门禁）。
    - `ConfigWiringDisciplineTest`（**4 个 `@Test`**，dy-config）—— 内存实现不得带任何 Spring 装配注解 · 它仍是可直接 `new` 的语义载体 · DB 真相源是唯一标 `@Service` 的配置实现且**刻意不 implements** `ConfigService` · 接口必须仍无租户维度。
    - 🛑 **该门禁的第 3 条断言推翻了卡点清单的原"建议①"**：清单原写"让 `JdbcConfigService` implements `ConfigService`" ——
      而这会**撞红既有硬门禁**，且方向是错的：DB 版每个方法都要**显式 `tenantId`**（RLS 上下文与缓存键必须同源），
      而接口**无租户维度**；更重要的是 `app_config` 是**租户层**生效值，**启动期取不到租户**。
      故正确处置是**保持它不实现接口**，而不是"把接口与实现接上"。
  - **✅ 批次十二追加（2026-09-27 · 过渡态显式化）**：B-3 的**完整收口需架构裁定**（"总部唯一项"在线可改的承载点：
    新增总部生效值表 / 用系统租户承载 / 明确不要求在线改，**三选一**）—— **不代拍**。
    本轮收口不需裁定的那一半：把"过渡态"从**注释承诺**升级为**构建期事实**。
    - 新建 `ConfigSourceTransitionGateTest`（**7 例**，构建期**无 DB**）：
      ① **全集机械核对**：扫源树派生 `ConfigSeed*` 全集 vs 登记集，**精确相等**（多一个少一个都红）；
      元层自证断言"扫描非空"—— 防目录搬家后 `Files.walk` 静默返回空流、门禁退化成"空集 == 空集"的恒绿；
      ② 五个域端口各自**恰有一个**过渡实现（防"新增 DB 版却没删 seed 版"导致端口双实现、装配变模糊）；
      ③ 每个端口来源的 `describeSource()` **返回值**（非源码文本，因为日志里打印的才是它）必须含「过渡」；
      ④ `SEED_RESOURCE` 常量**精确等于** `db/config/02_slots_seed.sql`（正则抓常量赋值行 ——
      用全文 `contains` 会被 Javadoc 里的同名路径满足，于是"改了常量但没改注释"会漏网）；
      ⑤ 端口**不得引入租户维度**。
    - 🛑 **门禁首次运行即修正一处登记偏差**：卡点清单与本节原写"**五个** `ConfigSeed*ProfileSource`"，
      而源树实测有 **6 个 `ConfigSeed*` 类** —— 多出的 `ConfigSeedContraindicationSource`（`#8` 禁忌清单）
      **不实现任何端口**：契约 B1 只有 200 / 400、**没有 500**，故它的 fail-closed 方向与其它域**相反**
      （清单为空时照常推导 + 显式登记，而不是抛），**塞不进"统一端口 + 统一 fail-closed"抽象**。
      门禁把这个"唯一无端口的过渡来源"钉成可核对事实（`PORTLESS_TRANSITION_SOURCES`），使"将来给它补端口"必须显式表态。
    - 🛑 **另一处被推翻的原建议**：清单原"建议②：五个 ProfileSource 换成读 DB" —— 它们读的是**总部层 `initial_value`**，
      而 `01_truth_source_ddl.sql` 第 24 行的**读路径纪律**逐字写「Service 只读 `app_config`；
      `config_slot.initial_value` **仅**在新租户初始化时读一次」；`ConfigSlot` 域对象**刻意不含**该列、
      `JdbcConfigSlotRepository` **刻意不提供读值方法**（"不让出现第二个真相源"）。
      照做会**引导出一个违反已裁定纪律的实现**，且**仍不解决"在线改配置"** ——
      各域口径在**启动期**被物化成不可变 Bean，读哪张表都一样。
    - **反向验证**：`verification/110_b2_b3_reverse_verification.py` C3/C4 两组
      （`describeSource()` 去掉「过渡」/ `SEED_RESOURCE` 常量改名）**均被抓**，逐字节还原后复绿。
    - 配套登记：`RlsCoverageGateTest.ISOLATION_TESTS` 加 `app_config` / `app_config_history`（**`config_slot` 刻意不登记** —— 它无 `tenant_id`、不启 RLS，登记会被"僵尸登记"检查报红；它的纪律由真库门禁内的专门断言承载）；`dy-app/pom.xml` 加一 `exclude` + 一 `include`；`RlsGateSupport.dataSummary()` 加 `v14` 观测项。
  - **反向验证（本次）**：`verification/100_config_truth_source_reverse_verification.py` —— **6 组注入**（同步分叉 / 域上界放宽 / `#42` 裁定摘除 / `DO UPDATE`→`DO NOTHING` / `ERRCODE 42501`→`23514` / 覆盖登记摘除），全部必须被抓。其中 **K2/K3 刻意做成"V14 与真源两侧同步改"**，以证明是**真库门禁**（而非同步门禁）在守编号域与 `#42` 裁定。
  - **安全提醒（回滚）**：回滚 V14 **有数据损失风险**，比 V13 严重 —— 若生产已改过配置（`app_config` 有生效值），删 `config_slot` 会让生效值表不可再写。**生产回滚前必须先导出**两表（详见 V14 第 5 节）。
- [x] ~~**ADR-11 `threshold_version`**~~ → **本轮已实现（S2-8）**：当不可变数据管理，**append-only、只增版本、绝不原地覆盖**。落地形态 = **服务端内容寻址指纹** `"tv1-" + sha256(规范化九段口径串)[0..20)`（24 字符）：
  - **入参降级为"断言位"**（可空；非空须与权威值**逐字相同**，否则拒 `VERSION_CONFLICT(4001)`）—— 版本号**由口径算出，不由调用方给**，杜绝"调用方贴标签"式伪造溯源。
  - **段级指纹落 `evidence_snapshot`**（九段逐项 + **保序**），使"哪一段口径变了"可**定位到段**而非只知整体变了；早于 S2-8 的行无段级指纹时**如实退化为"定位不到"**（不报空 Map 冒充有值）。
  - **回放闭环**新端点 `GET /verdicts/{id}/replay`（内部自描述端点，`customerFacing` 恒 `false`）：四态 `ReplayOutcome` = `REPRODUCED`（可复现）/ `DRIFTED`（口径已漂移）/ `DIVERGED`（同版本不同分支）/ `INCOMPARABLE_DOMAIN`（域外不可比）。
  - **PRD L856** "模块映射版本并入 `threshold_version` 语义、**不新开字段**"已遵办（见 §5.2 末节登记：模块映射**尚未落库**）。
  - **~~仍未实现~~ → 已作废（2026-09-30 实测回改）**：原写「`audit_log` 的 `prev_hash`/`hash` 链**校验端点**仍为 TODO」——
    **该句已滞后**：`AuditChainController` 已有 `@GetMapping("/audit/log-chain")`（第 132 行 `verifyLogChain()`）+ `/audit/log-chain/contract` 自描述端点，C-1 已于 2026-09-27 收口。
    ⚠️ 本轮**只回改这一句**，不改动本条的其余内容；同类"登记滞后于代码"的处置范式见 §八。
  > 详见 §反向验证「S2-8 溯源回放的注入」与 §七 S2-8 关键文件块。
- [x] ~~**ADR-12 合规构建期扫描**~~ → **本轮已实现**（`compliance/`）。三扫描面（退款字样 / 负向计数与归因措辞 / 派生字段与派生结论），**命中即构建失败**；绑定 Maven `validate` 阶段，故违规时产物不会被编出来；`.github/workflows/compliance-gate.yml` 作为 CI 门禁。**owner 不可空**由 `compliance/owners.csv` 落地，并以行为断言守护（owner 为空 → 构建失败，即使代码全干净）。**反向验证**：三扫描面各注入一次，由 `compliance/run-reverse-verification.py` 记录三栏证据（注入内容 / 期望失败点 / 实际失败断言原文）；另有 9 项断言（`compliance/tests/compliance_injection_test.py`）与端到端 `mvn` 门禁断言（`compliance/tests/build-gate-injection.py`）。**仍待补**：`owners.csv` 现为**占位角色**（`role:dev-compliance-lead`），真实责任人指派属 T-2 / G6 待办；`client-package/` 为通道骨架，真实小程序产物接入该路径是剩余集成步骤；CI 需在仓库设置中把该 job 设为 required check（工作流本身无法阻断合并）。详见 `compliance/README.md`。
- [ ] **🛑 `AuditFillAspect` 是【从未接线的死代码】（批次十三实测抓出，已落机械守护 N-17，处置待架构裁定）**：
  🛑 **本文档一致性已修复（批次十七回改）**：§一 模块表与 §三 ADR-09 行此前把它**正面列为落地物**
  （会让人以为 `created_by` 自动填充已生效），现已改为**显式标注"从未接线的死代码"**，与本条一致。
  （原措辞指向"第 88 行"，行号随编辑已变，故改为按章节定位。）
  实测**三处实锤**证明它从未生效：
  **① 切点零匹配** —— 切点是 `..save(..)` / `..persist(..)`，而全库**无任何 `save(...)` / `persist(...)` 方法定义**
  （持久化全走 `*Ledger` + `JdbcTemplate.update("INSERT INTO ...")`，方法名 `insertXxx`/`append`/`upsertXxx`）；
  **② 载体零实现** —— `AuditableEntity`（dy-common）在 `dy-*/src/main` 里**零子类**；
  **③ 零测试** —— 全仓 `dy-*/src/test` 无任何测试引用它。
  **如实登记（不夸大为"数据缺失"）**：`created_by` **已由各 Ledger 显式写入**（86 处 `INSERT` 列 + 实参）
  ⇒ 审计字段**实际有值**，只是**不由本切面填充**。故这不是"数据缺失"，而是
  "**ADR-A5/A-6 的自动填充声明与实际实现不符**"。
  **处置二选一（属架构 owner 裁定，不代拍）**：① **删除切面**（不造成字段缺失，但须撤掉 ADR-A5/A-6 声明）；
  ② **让它真接线**（需把所有 Ledger 写路径改造成走实体 `save()`，是一次架构级改动，
  且与"Ledger + JdbcTemplate 直写"这条既有实现范式冲突）。
  **本轮不需裁定的一半已做**：新建 `AuditFillAspectWiringGateTest`（**4 例**）把"接线状态"从人写的结论
  变成**构建期可断言的事实** —— 切点定义数 = 0、子类数 = 0、`created_by` 显式引用数 ≥ 50（证明字段有值的另一条路径仍在）。
  🛑 **门禁的牙齿**：若有人真去接线（新增 `save(..)` 或让实体继承 `AuditableEntity`），门禁**立即变红并点名新匹配**
  ⇒ 迫使"接线"成为一次**显式动作**（连同 README 登记一起改），而非"悄悄接上、文档仍说没接"。
  **反向验证**：`verification/112_audit_aspect_wiring_reverse_verification.py`（**3 组注入 / 4/4 PASS**）。
  🛑 **本脚本顺带抓出门禁自身一个假绿缺陷（本轮最强收获）**：门禁初版用 `src.contains("@Aspect")`
  对**原始文本**断言 ⇒ 把注解**注释掉**（`// @Aspect`）后字面量仍在 ⇒ **门禁仍绿而切面已静默降级**。
  这是纪律 8.4 的镜像（"源码里字面出现 ≠ 注解真的生效"），已改为**先剥注释再断言**并复验通过。
  🛑 **另一条教训**：C3 初版"改类名"注入使 **public 类名与文件名不符 ⇒ 编译失败 ⇒ 红集为空** ——
  "门禁红了"却"没抓到断言"是**假阳性**（证明的是编译器而非门禁）。**exit≠0 不足以证明门禁有牙齿。**
- [x] ~~**可观测性 Metrics/Tracing**~~ → **C-5 已收口（2026-09-27）**。`TraceIdFilter` 早已回填 `trace_id`（与 `GlobalExceptionHandler` 分级留痕共同构成"响应体 `trace_id` ↔ 服务端日志行"双向检索），C-5 补的是 **ADR-09 的机械防线**与**它的证据**：
  - **禁 `tenant_id` 标签不是纪律、是可执行约束**：`TenantTagPolicy` 持 `FORBIDDEN_TAG_KEYS = {tenant_id, tenantid, tenant}`（三形态，防大小写/下划线变体绕过），`findViolations(registry)` 遍历**全部已注册 Meter**逐标签比对。`TenantTagGuardTest`（**8 例**）除正向断言外，含"**注册一个带 `tenant_id` 的 Meter 必须被 `findViolations` 抓到**"的内证（`injected_tenant_tag_is_detected_red_then_green_after_removal`）—— 门禁本身会被同一套断言检验，而非只声明立场。
  - **公共标签装配面**：`ObservabilityConfiguration.dyCommonTags(name, port)` **只**产 `application` / `instance_id` 两个公共标签；**基数是断言**（多一个即红），使"顺手加 `tenant_id`"这条最可能的违规路径在装配处即被堵死。
  - **为什么必须是硬约束（ADR-09）**：租户标签会以**标签基数**形态把租户数量、每租户流量刻画进时序库，等于在观测平面重建一份**跨租户租户清单** —— 与 RLS 在数据面守的隔离纪律**直接冲突**。指标因此只按 `application`/`instance_id` 维度聚合，租户维度的下钻**刻意不做**。
  - **计数口径**：`dy-web` observability 4 个测试类合计 **28 例**（`TenantTagGuardTest` 8 / `TraceIdPropagationTest` / `ObservabilityWiringTest` / `CommonTagsShapeTest`，全绿）。
  - **反向验证**：`verification/102_c5_observability_reverse_verification.py`（**2 组注入**）—— **M1** 掏空禁令集合（`FORBIDDEN_TAG_KEYS = Set.of()` ⇒ 所有断言平凡通过＝常绿门禁）/**M2** 污染公共标签装配（`dyCommonTags` 加 `tenant_id`，最可能被顺手加的位置）；两组**均被抓**，注入后逐字节还原并经字节数一致性校验，还原后基线复绿 → **总体 PASS**（证据落 `verification/102_c5_observability_reverse_verification.md`）。
  - **仍未接入（如实登记，不代拍）**：Metrics **三支柱**（counter/gauge/histogram 的业务指标面）与 **OTel tracing 导出**仍是骨架，本轮只收口"**禁止租户标签**"这条 ADR-09 硬约束与 trace_id 贯通，业务指标建模属后续增项。
- [x] ~~**PIPL/等保 字段级加密(DEK+KEK)、crypto-shredding 删除权**~~ → **B-1 已落地**（V10~V13 + 7 个新类）。详见下方"B-1 字段级加密挂接"条目。
- [x] ~~**🛑 组织主数据开通边界：本骨架没有任何「建租户 / 建门店 / 建员工」的通路（批次十三 · 第五轮实测抓出，已落机械守护 N-18｜B-7）**~~ → **B-7 已收口（批次十三 · 第七轮）**：
  **原四条实测**（① 契约 40 path 零开通端点；② `tenant`/`region`/`store`/`staff`/`device` 五张
  组织主数据表生产代码零 `INSERT INTO`；③ 无启动钩子；④ 应用库这些表均 0 行）
  ⇒ **"部署起来是个「能跑但空」的系统"**。**收口方式（逐条对齐）**：
  - **① 【刻意保持】** —— 契约**仍然**零开通端点。这是**契约化决策**而非未完成：
    开通是运维/实施动作，给它对外端点等于在租户边界之外开一个「谁能创建租户」的鉴权面，
    而 `x-callable-roles` **没有任何角色声明覆盖它**；加该端点属契约 **MAJOR** 变更，需产品共签。
    `ProvisioningBoundaryGateTest` 第 ④ 例与判据⑦(d) **继续守着这一条** ——
    它的红**不再是"你少了东西"，而是"你越界了"**（若有人把开通服务接上 `@RestController`，门禁先红）。
  - **② 已变更** —— `tenant`/`region`/`store`/`staff` 四张**已有写入方**，已从"未开通"账移入"已开通"账
    （31→35 / 11→7）。载体两处，形态不同：**`tenant`** ← V15 的 `provision_tenant()` 函数体；
    **`region`/`store`/`staff`** ← `OrganizationProvisioningRepository.provision()` 显式 INSERT。
    ⚠️ **本段的 31→35 / 11→7 是批次十三第七轮的【历史快照】，勿再引用为现值** —— 现值
    `PROVISIONED` **42** / `NOT_PROVISIONED` **0**（再经 B-10 `band` / B-11 `device` / B-12 `scale` /
    B-13 `case_archive` / A-1 `agreement` / **A-3 补拉两表** **六次**边界移动；
    见批次十五 / 十七 / 十九 / **二十一**与 §五 N-18｜B-7）。
    📌 **`device`（门店作业域）已在 B-11 补齐**（V18 `register_device` / `retire_device`），
    不再是"未开通"—— 本段"`device` 仍在未开通账"这句在批次十三当时成立，现已作废。
  - **③ 【刻意保持】** —— 仍**无**启动钩子。开通必须是**显式动作**："启动时自动建一个默认租户"
    会让生产环境凭空多出一个**无人认领**的租户。
  - **④ 不受影响** —— `diaoyuanyun_dev` 是开发库，本轮未对它执行开通；
    **"库里是空的"与"代码里没有通路"从此是两件事**，这正是该门禁要分开的东西。
  **交付物**：V15 迁移（`provision_tenant` / `assert_tenant_context`）+ `OrgProvisioningPlan` +
  `OrganizationProvisioningRepository` + `OrganizationProvisioningService`。
  **验证**：门禁 **7/7**、RLS 接线门禁 **6/6**、真库 E2E **8/8**、全量回归 **BUILD SUCCESS**（768 例）。
  **仍未裁（属产品/架构范围裁定，不代拍）**：
  ① **`agreement`（协议签署快照）仍零写入方** —— 直接影响 PRD **G1**「协议签署合规率 = 100%」的取数，
  与 H1 电子签（**B-5**）连带，**不应长期悬空**；
  ② **开通流程的调用方**（运维 CLI / 实施工具 / psql 直调）**尚未选定** ——
  本轮落地的是**被调用方**（原语 + 组件），**调用入口**仍属运维侧交付物；
  ③ `device`/`band`/补拉三表/`case_archive`/`scale` 的写入方仍在未开通账（各自有独立归属）。
  ⚠️ **本条的"仍在未开通账"是批次十三第七轮的【历史快照】，勿再引用为现值** —— 现值：`band`（B-10）·
  `device`（B-11）· `scale`（B-12）· `case_archive`（B-13）· `agreement`（**A-1**）· **`band_sync_probe` / `band_daily_coverage`（A-3，批次二十一）**
  **均已移入已开通账**；
  🛑 **「未开通」账已归零（`NOT_PROVISIONED = 0`）** —— 本仓维护的"零写入方表"清单**第一次被清空**
  （见批次十九 / 二十一 块与 §五 **N-13｜A-3**）。
  🛑 **另一条边界（同轮实测，一并登记，本轮【未】涉及）**：**P1-06「总部配置中心」的写入口在实现期不存在** ——
  `ConfigServiceImpl`（内存实现）**零 Controller 调用方**，配置写路径只有 `JdbcConfigService.set/rollbackToPrevious`
  且**无对外端点**；而 `app_config_history` 表**已具备**「谁改 / 何时 / 改了什么」（`who`/`changed_at`/`before`/`after`/`op`/`version`，
  由**触发器**写入、append-only、带 RLS），**缺 PRD P1-06 四要素中的「为什么」与「影响哪些在服务客户」**。
  即：**留痕底座已建好，但"改配置"这个动作本身在实现期无处发生**（P1-06 属 Phase 2，未开工）。
- [x] ~~**🛑 N-13 补拉通路缺口：`band_sync_probe` / `band_daily_coverage` 两张表在生产代码里【零写入方】（批次十三实测抓出）**~~ → **A-3 已收口（2026-09-30 · 批次二十一）**：
  **两条通路落地**（V22 `register_sync_probe` / `register_daily_coverage` + `.../bandrefetch/` 四件套 + 门禁 `BandRefetchGateTest` **13 例** + 反向验证 `125` **23 组 / 25 用例**），
  两表**移入已开通账**且 **`NOT_PROVISIONED` 账归零**（`42 / 0`）。
  🛑 **本条的 ⚠️ 前置「上游补拉口径须先核对」核对结论 = 口径已定**（PRD **§2.8.7**，v1.25，业务方 2026-09-19 拍板）；
  此前登记"未定"系**只检索 README 转述层、未回到 PRD 正文**的误判 —— 逐字锚点见 `_work/a3-band-refetch-ruling-request.md`。
  🛑 **仍有一条真实待裁**（**不代拍**）：`band_daily_coverage.gap_reason` 的 7 值枚举**缺「结构性（未同意佩戴）」槽位**
  ⇒ config `#44`「结构性不可观测占比须按采集模式单列、不得混合平均」在当前 schema 下**无法实现**（增删枚举属**契约变更**）。
  V22 的处置 = **不新增枚举值**，只在函数自证消息里**显式打出这条已知缺口**。
  ⚠️ **仍未取证（不阻塞落码，阻塞"对外承诺"）**：`N` 的准确值 · `s` 实测值 · A6「无数据语义」· 厂商 13 条逐日接口 · `isWear` 异常原因码 —— 一律做成**配置槽位 + fail-closed 保守默认值**。
- [ ] **🛑 RLS 注入机制：ADR-02 的 L2 是【文档机制】，实际机制是 18 个类的 `inTenant`（批次十三 · 第六轮实测抓出，已落机械守护 N-19｜B-9）**：
  **三条实测**（口径：剥离注释与字符串后扫 8 模块 `src/main`）——
  ① **`@RlsScoped` 的生产使用数 = 0**（唯一出现是它自己的定义文件，`@interface` 声明不是使用）
  ⇒ 切点 `@Before("@annotation(...RlsScoped)")` **零匹配、永不触发**，`applyTenantSession` 在生产代码里**零调用**；
  ② **真实机制**：**18 个类**各自私有 `<T> T inTenant(String tenantId, Supplier<T> body)`（17 个 dy-app 域仓储 + `dy-config/JdbcConfigSupport`），
  在**短事务内** `SET LOCAL app.tenant_id = '<uuid>'`（**19 处**语句；`inTenant` 调用 **139 处**）；
  ③ 两条机制是**替代关系而非覆盖关系**。
  🛑 **这不是安全漏洞**（显式传参覆盖面完整，且比 ThreadLocal 更难点错）；**危害是两条**：
  **(a) 会误导下一个人做错事** —— 照 README 加 `@RlsScoped` 以为有人兜住，于是事务外直接查 ⇒ RLS 读到空租户 ⇒ **静默零行**；
  **(b) 会静默漂移** —— 两侧单独变更都不会红。
  **收口**：`RlsInjectionRealityGateTest`（**6 例**）把分歧变成构建期事实（判据详见 §四）。
  **反向验证**：`verification/114_*.py`（**6 组注入 / 8/8 PASS**），并**当场抓出门禁自身两个缺口**（注解正则只认短名 / `contains` 前缀匹配假绿），已固化进判据⑤。
  **待裁（属架构 owner 裁定，不代拍）**：① **删除死机制**（删 `RlsScoped` + 切面，须同步改 ADR-02 与本节）
  vs **真正启用切面**（需把 Ledger 写路径改造为走实体 `save()`，是**架构级改动**，且与"Ledger + JdbcTemplate 直写"范式冲突）；
  ② 若保留切面 —— 是否要求它成为**必由之路**（而非可选标注）？
  ③ 裁定前是否先补一例**"切点真的匹配到某处"**的断言（现 `RlsSessionAspectTest` 5 例只直接调 `applyTenantSession()`）？
  ⚠️ **口径纠正**：上一轮记忆里的"19 个 `*Ledger` / 142 处调用"是**未剥注释的粗测**，本轮纠正为 **18 个载体 / 139 处调用 / 19 处语句**。
- [x] **Silo 升级预留 `tenant.datastore_hint`**：字段已留位，逻辑未实现（ADR-01）。　🛑 **2026-10-01 实测回写**：**登记备查（非待办）** —— 该字段是 ADR-01 的**升级预留位**，属「已按设计留位、待 Silo 级客户出现时才实现」，**不是遗漏**。本轮不改动其实现状态。
- [ ] **`dy-security` 权限矩阵**：`PermissionRegistry` 为骨架预置映射；真实矩阵须来自配置/策略服务，并与 `A2 /auth/me` 档位解算保持一致（声明 ≠ 授权）。
- [x] ~~**`band_telemetry` 的 ★ 三张补拉表未建**~~ → **已作废（2026-09-30 实测回改）**：本行原写「三张手环补拉表属后续增项（V3 不建）」——
    **该句已滞后**：**V5 第 899 / 946 / 990 行**已分别建 `band_sync_probe` / `band_sync_log` / `band_daily_coverage` 三表（均 `ENABLE`+`FORCE RLS`），
    **且 A-3（V22，批次二十一）已补齐其写入通路** ⇒ 「未建」与「未开通」**两栏同时不成立**。
    ⚠️ 本轮**只回改这一行**，不改动其余章节；同类"登记滞后于代码"的处置范式见 §八。
- [x] **`band_telemetry.data_source` 取值语言：以契约为准 —— 待裁点已收窄（N-2，2026-09-27）**：现值中文 `('手环','未接入')`，同表 `metric`/`gap_reason`/`sync_state` 均为英文 token。**机械裁定依据**：契约 `BandTelemetryData.data_source` 的 `enum` **逐字冻结为中文** `[手环, 未接入]` 且 `x-visible-to: [client, therapist, meridian, admin]`（客户可见）⇒ 改动它属 §1.4 **MAJOR** 级契约变更，**不能由实现侧自行统一**。故「**以契约为准**」，实现与 V3 的 CHECK 取值集**逐字对齐契约**（由 `UpstreamGapRegistryTest.data_source_language_matches_the_frozen_contract_enum` 机械守护：契约 enum、V3 CHECK、字典登记三处互钉）。　🛑 **2026-10-01 实测回写**：**已裁定并落地** —— 结论是「**以契约为准**」，实现与 V3 的 CHECK 取值集**逐字对齐契约**中文字面；由 `UpstreamGapRegistryTest.data_source_language_matches_the_frozen_contract_enum` 机械守护（契约 enum × V3 CHECK × 字典登记**三处互钉**）。⚠️ 仅剩「是否值得为风格统一做一次 MAJOR 级契约变更」属契约 owner 裁定。
  **待裁点已从「是否统一为英文」收窄为**：**是否值得为"风格统一"做一次 MAJOR 级契约变更**（即先改契约 enum 再改实现与库）——**属契约 owner 裁定，不代拍**。相关登记见字典 §2.17【缺陷登记】块。
- [x] **客户端受理通路在契约中缺位（S1-6 抓出的欠账，登记不代拍）**：`clientPaths.js` 原白名单含 `/receipt/list`、`/receipt/detail`，而 **OpenAPI 45 端点中根本不存在这两个路径**。PRD 记录客户回执走**微信订阅消息**、G5 回执状态位**仅经络师端可见**，故契约对此无答案 —— 究竟是**客户端臆造**、还是**契约该补一个客户受理通路**，需**契约 owner + 产品共签**。已从白名单移除（移除不等于裁定；门禁只会拒绝"够到被拒端点"，不会替产品决定"要不要这个端点"）。　🛑 **2026-10-01 实测回写**：**已按"不臆造"处置完成** —— `/receipt/list`、`/receipt/detail` **已从 `clientPaths.js` 白名单移除**，门禁不再放行未知端点。⚠️「契约该不该补一个客户受理通路」仍属**契约 owner + 产品共签**，本行保留该裁定边界，**不写成「已收口」**。
- [ ] **`CLIENT_BAND_DISPLAY_DAYS = 14` 与 E5「N 取运行时探测值、禁硬编码天数」的关系待裁**：客户端包内存在硬编码显示窗口 14 天，而 E5 契约要求 N 取**运行时探测值**。显示窗口是否属于"营业口径"而非"探测口径"、是否应改为读 E5 返回值，**未裁**。
- [ ] **可见性矩阵 ①② 组的「概念性命名」待契约 owner 复核（S1-8 登记，不代拍）**：矩阵 ①② 两组用的是**概念名**而非契约字段名 —— ② 组 `captured_days` / `link_state` 既非契约 property 也非 enum 取值（契约表达为 `collected_days` / `synced_date` / `data_source`）；① 组六个名字全是 `metric` enum 的**取值**（`sleep` vs `sleep_minutes`）。S1-8 **刻意不把这类差异判为违规**（对正确内容叫狼的门禁会被关掉），只作信息列出。但"概念性分组是否应改写为逐字字段名"是**命名口径裁定**，需契约 owner 决定；另矩阵 `_note` 的语义准确性亦需一并复核。
- [x] ~~**`generator-matrix.yaml` 每端存在 DEAD 行（S1-8 清点，不代拍）**~~ → **已收口（2026-09-28 · 批次十八 · B-4）**：
  原登记"client-mp 21 / therapist-app 15 / admin-web 6"行的**精确定义已由机械复算还原**：
  **"契约行的全部 operation 都不含本端 token-roles（role-disjoint），且未被本端 `exclude-contract-rows` 声明"**。
  按此定义复算的结果与原登记**逐端吻合**（21 / 15 / 6），故定义可信。
  处置分两类，均由 `compliance/sdk-surface-gate.py` 守护：
  ① **真正的空声明（dead declaration）→ 归零**：`therapist-app` 的 `exclude-contract-rows: [H1]` 已删除。
  H1 = `POST /esign/callbacks/{provider}`，契约 `x-callable-roles` 为**空**（电子签厂商的服务端回调），
  角色判据本就剔除它，写与不写**产物逐字相同**，且**无任何检查消费它** ⇒ 由 FACE 4 判为"永不生效的声明"并修复。
  ② **其余全部逐行登记理由（不再是不了了之的欠账）**：门禁每次运行都打印 DEAD-ROW REGISTER ——
  client-mp **21 行 role-disjoint + 7 行 mirror**；therapist-app **16 行 role-disjoint**；admin-web **6 行 role-disjoint**。
  🛑 **唯一放行的例外（不是漏洞）**：client-mp 对 `G1~G5 / H1 / I8` 七行的排除是**镜像声明** ——
  它们恰是契约 `x-client-forbidden` 的全集，被管线 `check_matrix_consistency()` **反向消费**（少一条就让那个门禁红），
  删掉会破坏另一道检查，故计为 `mirror_exclusions` 并**显式打印**，不静默跳过。
  🛑 **数字口径变更（如实登记）**：therapist-app 的 role-disjoint 由原登记 **15** 变为 **16** ——
  因 H1 从"被声明排除"改为"纯 role-disjoint"。原 15 是"永不生效且未声明"的子集计数，
  16 是**完整划分**下的计数；两者都对，但**含义不同**，此处一并写明以免后人复算时对不上。
- [x] **`client_error_codes` 未覆盖 1001 / 2003 / 2004（合法，登记备查）**：客户端错误文案表覆盖 9 个码，契约 12 个。差额中多数是**刻意不渲染给客户**的码（如内部错误），故 S1-8 FACE 2 只做**单向**检验（文案里的码必须真实存在），**不要求覆盖全部契约码** —— 覆盖与否属产品文案口径，非门禁职责。　🛑 **2026-10-01 实测回写**：**合法状态，非待办** —— 本行原文已逐字声明「差额中多数是刻意不渲染给客户的码」，故 S1-8 FACE 2 只做**单向**检验。**覆盖与否属产品文案口径，非门禁职责** ⇒ 登记备查。
- [x] ~~**`dy-crypto` 目前无调用方 ⇒ 加密未生效**~~ → **B-1 已收口（2026-09-26）**：dy-app 已依赖 dy-crypto，加密链路在**真库 + 真密钥栈 + 真 HTTP** 上端到端跑通。落地清单：

  | 件 | 内容 |
  |---|---|
  | `V10__band_telemetry_field_level_encryption.sql` | `value_num NUMERIC` → `value_enc TEXT`（**删明文列**，不旁立密文列）；`sleep_json JSONB` → `TEXT`；信封格式 CHECK |
  | `V11__key_material_persistence.sql` | `tenant_kek` / `subject_dek` / `subject_key_tombstone` 三表 + RLS + 只追加 RULE + 密钥材料不可变 TRIGGER |
  | `V12__band_telemetry_sensitivity_aligned_envelope_guard.sql` | **修正 V10 过严的 CHECK**：`value_enc` 门禁由"一律信封"精确化为**按 metric 分流**（敏感 = 信封 / 非敏感 = 十进制文本）。V10 原写法与非敏感登记表**相互矛盾**，使 `pressure/met/mai` 等写入恒 500（由 `BandTelemetryEncryptionTest` 在真库抓到） |
  | `V13__backfill_schema_migration_registry.sql` | **补登记历史缺口**：V6/V7/V8/V9 四个迁移**从未**写 `schema_migration`（V1 是注释示例），真库该表原本只有 6 行 |
  | `crypto/domain/KeyMaterialCipher.java` | 三层密钥链：`DY_MASTER_KEY` → 租户 KEK → 主体 DEK → 字段密文（信封 `dy1:<alg>:<ver>:<nonce>:<ct>`） |
  | `crypto/repository/CryptoKeyLedger.java` | 密钥材料 JDBC 仓储（14 个 `*Ledger` 之一）；`lockSubject` 用 `pg_advisory_xact_lock` 防并发双建 |
  | `crypto/repository/DbTenantKekProvider.java` / `DbSubjectKeyStore.java` / `DbShredTombstoneStore.java` | 三个真库实现，语义逐条对齐既有 InMemory 版 |
  | `crypto/config/CryptoConfiguration.java` | 6 个 `@Bean` + 启动期解析三个算法位（fail-closed） |
  | `band/domain/TelemetrySensitivity.java` | metric ↔ 需加密字段的**显式登记表**；未登记两侧一律抛错（fail-closed，防"新增指标安静地明文落库"） |
  | `band/repository/BandLedger.java` | 加解密落在**持久化边界**（写侧加密 / 读侧按当行 metric 与 customer 还原），业务层全程见明文 |
  | `pom.xml` / `application.yml` | dy-app 增 `dy-crypto` 依赖；`dy.crypto` 配置段（master-key 走环境变量，未设则用**已披露的开发占位值**） |

  **门禁（把"两份清单必须恒等"从纪律改成构建期约束）**：
  - `TelemetrySensitivityMigrationGateTest`（10 例）—— Java 登记表 ↔ V12 门禁 SQL ↔ V3 的 13 值词表 **三方交叉断言**；并含"V12 不得用 NOT IN / 不得放宽成任意文本 / 不得动 sleep_json 门禁"等反面断言。
  - `MigrationRegistryGateTest`（6 例）—— 描述 ≤ 256 字符（**V12 首版 309 字符被真库 22001 拒绝并回滚整个迁移**）、版本必须被登记、登记幂等、版本号唯一。**无豁免清单**：V1/V6~V9 的补登记由 V13 兑现，删掉 V13 门禁立刻红。
  - `BandTelemetryEncryptionTest`（5 例）—— 真密钥栈 + 真 HTTP + 真库的加解密往返：库里确实是 `dy1:` 信封且**不含明文数值**、API 读回逐字等于写入值、同明文两次加密密文不同（nonce 非常量）、`sleep_json` 也走加密（其敏感性**不由 metric 决定**，是最易漏的一条路径）、非敏感 metric 仍落明文（反证登记表有区分力）、密钥材料真的落了库且 `wrapped_bytes` 不是裸密钥。
  - `RlsV11CryptoKeyIsolationTest`（9 例）—— 三张密钥表的跨租户不可见 + `FORCE RLS` + 只追加 RULE 与不可变 TRIGGER 的**反向验证**（改写/删除必须静默无效）。

  **门禁抓到的 2 条真实 P0（都是"测试全绿也发现不了"的那一类）**：
  1. **`DbShredTombstoneStore.record()` 在真库上必失败** —— 它用 `ON CONFLICT DO NOTHING`，而 V11 给墓碑表建了 `tombstone_no_update` RULE。PG 对"有 UPDATE 规则的表"**一律拒绝** `ON CONFLICT`（连 `DO NOTHING` 也不例外，0A000）。后果不是"墓碑写不进去"这么轻：**PIPL 删除权在库层完全不可执行**。已改为 `INSERT ... SELECT ... WHERE NOT EXISTS` + 23505 并发兜底。
  2. **`TelemetrySensitivity` 把 `'sport'` 当 metric 登记** —— 它不是 `metric` 取值，而是契约 §4.3 游标型幂等键里的**字面量**（`(device_id, 'sport', sport_id)`）。登记它使两侧合计 14 ≠ V3 的 13。已移除并写明修正记录。

  **仍未收口（如实登记，不代拍）**：
  - `SubjectKeyStore` 的**销毁流程尚未接任何业务端点** —— 能力齐备（`destroySubjectKey` + 墓碑 + 不可变 TRIGGER 均已验证），但"客户行使删除权"的入口属业务流程，需与 PIPL 删除 DAG（`dy-crypto/DATA-MAP-AND-DELETION-DAG.md` 的衍生存储清单）一并设计。
  - **DEK 备份纪律**：`backupReady` 是**真实检查**（真解一次当前代 KEK），但仓库内**无可容灾备份实现** —— ADR-12 §8.4 逐字要求"DEK 备份与访问控制必须与数据本身同等严格"，缺它则**密钥丢失 = 数据永久不可读**。属部署层交付件。
  - **历史明文行的重加密任务**：V10 的存量自证块在遇到明文行时**拒绝迁移并给出处置说明**（不伪造、不静默丢弃）。本仓无存量数据，故从未触发；生产上线前需一次性重加密作业。

**S2 退款域（契约域 G）新增欠账**（`S2-6` 登记，均**不代拍**）：

- [x] **`store_customer_service` 角色无端（契约侧缺口，已落机械守护，N-5，2026-09-27）**：`RefundAudienceRole` 含 `STORE_CUSTOMER_SERVICE`，而契约 `x-callable-roles` **全部 45 端点中无该角色**。已用 `PermissionCodeRegistrationGateTest` 钉住"它不持有任何 `refund:*`"（纵深防御），**本轮再补契约侧守护**：`UpstreamGapRegistryTest.store_customer_service_has_no_endpoint_in_the_contract` —— ① 契约 `x-roles` 对该角色登记为**双 `null`**（`token-role: null, end: null`，即"不使用本系统、没有账号"）；② 可见性矩阵**四组全 false**；③ **45 个 operationId 均无该角色**。但"该角色是否应在契约中拥有端点"属**契约 owner 裁定**。相关：用户记忆中的 **P0-19 未决问题** —— 门店/客服与区域督导的退款文案可见性待拍板。　🛑 **2026-10-01 实测回写**：**已落机械守护** —— `UpstreamGapRegistryTest.store_customer_service_has_no_endpoint_in_the_contract` 三向钉住（`x-roles` 双 null · 可见性矩阵四组全 false · 45 operationId 均无该角色）。⚠️「该角色是否应在契约中拥有端点」属**契约 owner 裁定**，且与 **P0-19**（门店/客服与区域督导退款文案可见性）**同一未决问题**。
- [x] **契约 §2.0 缺「未知角色」错误码（契约侧缺口，已落机械守护，N-6，2026-09-27）**：`OrgLevel.fromRole` / 角色解算对**未登记角色** fail-closed 抛错，但契约 12 个错误码中**无"未知角色"码**，只能复用 `VISIBILITY_DENIED(2001)`。而 `TENANT_MISMATCH(2003)` 是**跨租户**语义 —— 两者**排查方向完全不同**（一个是"角色没登记"，一个是"租户不匹配"）。本轮已在 E2E 断言失败信息里写明该区分，**再补契约侧守护**：`UpstreamGapRegistryTest.contract_error_code_table_has_no_unknown_role_code` —— 读契约**顶层 `x-error-codes`**（不是 `components.schemas`，这是首版误判点），断言表恰为**冻结 12 条**、**无"未知角色"码**、含 `2001`/`2003`。**契约是否应补一个码**待裁定（属契约 owner）。　🛑 **2026-10-01 实测回写**：**已落机械守护** —— `UpstreamGapRegistryTest.contract_error_code_table_has_no_unknown_role_code` 断言契约顶层 `x-error-codes` 恰为**冻结 12 条**、无「未知角色」码、含 `2001`/`2003`。⚠️「契约是否应补一个码」属**契约 owner 裁定**。
- [x] ~~**G1/G3/G5 的写路径真请求断言只覆盖 `meridian`**~~ → **C-3 已收口（2026-09-27 · 批次十一）**：`RefundWritePathMatrixE2ETest`（16 例）把矩阵补齐为 **4 角色 × 3 写端点**，且**角色集不手抄** —— 从契约 `x-callable-roles` × `x-roles.token-role` 机械展开（`admin → manager/area/hq`，`token-role: null` 的无端角色显式跳过），并加「展开结果 = 显式期望值」自证：契约改了角色集、矩阵会**自动去测新角色**（手抄时代那行根本不会跑到）。反向验证：往契约 G1 注入 `therapist` ⇒ 矩阵自动去测并抓出 `403`；恢复后 16/16 绿。**读方向（G2）4 角色矩阵**亦在同套件内。**原登记"只由码级门禁保证"已不成立**，本行原登记滞后。
- [x] ~~**`RefundWorkOrderLedger` 的真库读写隔离与乐观并发断言**：~~`UPDATE ... AND outcome = ? AND outcome <> '归档'` 是**唯一改写路径**，其乐观并发（版本冲突 4001/4002）尚无真库断言~~ → **C-4 已收口（2026-09-27）**，见下方专节。
  - **`refund_receipt` / `refund_offline_notice` 的字段级真库约束**：
    - `refund_receipt`：**已覆盖** —— `refund_receipt_pushed_at_iff_pushed` 双向（`RlsV6...Test`）+ `receipt_state`（含全角括号）/ `channel` 字面绑定契约。
    - `refund_offline_notice`：**批次七已收口（2026-09-27）** —— 补 `channel` 枚举拒 `订阅消息` / `NOT NULL` 完整性（含消息点名列）/ 恰 1 条 CHECK 三例；反向验证 `105`（M1/M2/M3，4/4 PASS）。**此前本条"仍待补"的表述已作废**（当时"当前覆盖在隔离面、非约束面"的判断对 `refund_receipt` 成立、对 `refund_offline_notice` 亦成立，故缺口确在这一侧）。
- [x] **`receipt` / `requested_at_source` 契约缺口**：库侧已按 P0-19 C1-2 建列（V6），但契约 `RefundCreateRequest` 是否有对应字段、以及 `refund_receipt` 是否有契约承载，**契约侧未逐条比对**（属契约 owner 待办）。　🛑 **2026-10-01 实测回写**：**该比对已完成并被机械钉住** —— `RefundDomainCrossSourceGateTest`（N-8，批次十一）做 V6 库侧列 × 契约 schema 字段**双向覆盖**、逐条登记（11 条），归零/新增均须显式编辑。⚠️ 「是否补契约字段」仍属契约 owner 裁定。

**S2-7 判定域（契约域 F）新增欠账**（`S2-7` 登记，均**不代拍**）：

> 判定链（`cycle_assessment` + `verdict`，V5 建表）已按 **PRD §7.3 D1~D5 五分支**落库，F1（建判定）/ F2（取判定历史）两端点已实现。
> 下列四项是**落地过程中撞到的真实不匹配**，每一处都**不擅自扩表、不悄悄丢弃**，而是登记在案并给出当前处置。

- [x] **🛑 缺口 ①：`disposition` 同名不同源（表结构缺口，PRD 自相矛盾）**：PRD §C.1.7 **L1787** 写 `refund.disposition`，**L1794** 写 `verdict.{…, disposition}` —— **值域相同、归属表不同**，而 V5/V6 **两张表都没有 `disposition` 列**（V5 的 `verdict` 表里有一行注释 `-- §2.19: 组合出口（三 enum × → disposition）`，那是**注释不是列**）。当前处置：组合出口（`effect_verdict` × `adherence_state` × `risk_flag` → `Disposition`）落 `evidence_snapshot` JSONB，并以 `Disposition.SCHEMA_GAP_NOTE` 逐字携带缺口说明进每一条依据快照；`VerdictPersistenceBoundaryTest.v5_tables_have_no_disposition_column` 断言"真库确实没有这一列"（**防的是我们误以为加了**）。**归属裁定属契约 owner + PRD owner**。　🛑 **2026-10-01 实测回写**：已落地 —— `V8` L130 `ALTER TABLE verdict ADD COLUMN IF NOT EXISTS disposition`（选「落 verdict」，与建议一致）；本行原登记已滞后。
- [x] ~~**🛑 缺口 ②：`verdict.risk_flag` 在库层没有 CHECK（同表另三个枚举列都有）**~~ → **N-1 已由 V8 闭合（2026-09-27）**，守护已落：
  - **两阶段事实（必须并行陈述，不可只讲一半）**：**V5 建表时** `branch` / `effect_verdict` / `adherence_state` 三个枚举列**都有 `CHECK`**，唯独 `risk_flag` 没有（且它可空）—— 这是**历史事实**，由 `VerdictPersistenceBoundaryTest.v5_risk_flag_has_no_check_but_others_do` 用**对照组设计**钉住（若四列全无 CHECK，那是"表整体宽松"；只有 `risk_flag` 没有，才是"这一列的防线不在库层"）。**V8 迁移补上了该 CHECK**（`risk_flag IS NULL OR risk_flag IN ('无','高危','新发','同病')`），库层缺口**已闭合**。
  - **纵深防御两层**：库层 CHECK（V8，可空性与四值字面） + 应用层 `RiskFlag.parse` fail-closed（未知字面必抛，**不回落 `NONE`**；读回侧 `VerdictLedger.parseRiskOrNull` 同构）。**两层的可空性一致**（CHECK 与 `parseRiskOrNull` 都允许 `null`）。
  - **守护断言**：新增 `VerdictPersistenceBoundaryTest.v8_risk_flag_check_is_present_and_matches_enum_labels` —— 断言 V8 存在该 CHECK、含 `RISK_FLAG IS NULL`、四字面与 `RiskFlag.allDbLabels()` **逐字一致**、字面数**恰为 4**。`RiskFlag` 类注释已同步改写为「两阶段事实」口径（原注释停留在"库层无 CHECK"的旧事实）。
  - **为什么回落 `NONE` 危险**：它会把一个未登记的临床标签静默改写为"无风险标签"，直接使 **D4（全面评估）该触发而不触发** —— 正是 PRD §7.3 定调句要防的那类事故。
  - **反向验证**：`verification/103_b5_registry_and_gates_reverse_verification.py` **K3** —— 删掉 V8 的 `IS NULL` 支（破坏可空性），守护断言**立即红**；逐字节还原后复绿。
- [x] **🛑 缺口 ③（本域最尖锐的一处）：`verdict.confidence NUMERIC(4,3) NOT NULL CHECK (0–1)` 与置信度引擎"不可判返回 null"相互夹逼**：`VerdictConfidenceEngine` 在 `same_origin_status = INCOMPARABLE`（测量不可比）时 **`suspended = true` 且不产出置信度数值**，而库层 `confidence` **非空**。两者相加的后果是：**D5「人工复核」这一支不存在任何可写的 `verdict` 行** —— 挂起不是"结论是人工复核"，而是"**还没有结论**"。当前处置 = **D5 只落 `cycle_assessment`（依据侧，`verdict = 人工复核`）、不落 `verdict`（结论侧）**；并为此**扩端口第 7 个方法** `findCycleAssessmentsByCustomer`，否则挂起轮次只有依据侧留痕、F2 取历史时**静默消失**。不一致的是"同一客户看到的轮次数"与"判定条数"对不上，而那个差恰恰是最该被看见的部分。`VerdictOutcome.suspended` / `verdictPersisted()` 显式暴露该状态。**另附一条不变式（已论证并被断言）**：`branch != 人工复核` ⟹ `confidence != null` —— 因 `confidence == null` 的唯一成因是 `s = 不可比`，而路由在 D5 前置处就把不可比拦成人工复核。　🛑 **2026-10-01 实测回写**：已落地 —— `V8` L110 `ALTER TABLE verdict ALTER COLUMN confidence DROP NOT NULL`（选「放宽可空」，与建议一致）；本行原登记已滞后。
- [x] **🛑 缺口 ④：契约 F1 没有 `requestBody`（入参未冻结）**：`POST /cycle-assessments/{id}/verdicts`（`operationId: createVerdict`）**只声明了响应 `VerdictData`，没有 `requestBody`** ⇒ "F1 到底收什么"在契约里**无答案**。当前处置：控制器以 `F1_REQUEST_FIELDS`（12 项、`static final` 的文件级白名单）**自行冻结**形状，并把这份清单**回显给"请求未携带租户/角色"这类错误**，使它在接口层可断言、而不是只能读 Java 源码。**契约是否补 `requestBody`** 属契约 owner 裁定。　🛑 **2026-10-01 实测回写**：已落地 —— 契约 `createVerdict` **已补 `requestBody`**（`CreateVerdictRequest`，L1007 引用 / L1962 定义，description 逐字「🛑 A-2 起本端点的 requestBody 已捕获冻结」）；本行原登记已滞后。⚠️ 与该条并存的 `CreateVerdictRequest.required` 语义张力（`risk_flag`/`core_metric_improved`）**另行待裁**，不因本条回写而消解。
- [x] **F3 `GET /audit/signals` 与 F4 `GET /audit/coverage` 未实现（契约侧缺口，已明确出范围 + 机械守护，A-10，2026-09-27）**（契约 `[admin]`，F3 `x-row-scope` 门店仅本店；F4 要求"三数同显"，其 `a3_observability` 为 **TBD**）。两者都依赖"稽核信号/覆盖率"的上游口径，**未立项**。**本轮明确划出范围并落机械守护**（不再只是"未实现"这句口头登记）：　🛑 **2026-10-01 实测回写**：本行已按「**明确出范围**」收口（非待办）—— `EndpointCoverageLedgerTest.OUT_OF_SCOPE` 显式登记两条 + 理由，`UpstreamGapRegistryTest` 机械守护；签字降级为「确认不做」。
  - **范围决定（明确出范围，非"待办"）**：F3/F4 **不在本轮交付范围**（依赖上游口径未立项），**不实现**；登记留痕，避免下次 review 误判为"漏做"。
  - **守护断言**：`UpstreamGapRegistryTest.f3_f4_are_registered_as_out_of_scope_with_contract_row_anchors` —— ① **契约侧确有** F3/F4（按 `x-contract-row` 定位，防"登记一个契约里不存在的缺口"）；② **实现侧确无**（扫 `src/main` 的 `@GetMapping` 字面、**先剥注释**，防把 javadoc 提及当成实现）；③ `AuditChainController` 自描述**含** `GET /audit/signals` / `GET /audit/coverage` 且**含"未立项"字样**（自描述与实际实现状态**双向一致**）。
- [x] **`config #9`（`cfg:verdict.branch_rules`）四英文键 vs 落库五中文值**：配置声明 `{"branches":["improved","stable","no_improvement","worsened"]}`（4 个英文键），而落库枚举 `VerdictBranch` 是 **5 个中文值**（稳定 / 依从不足 / 达标无效 / 全面评估 / 人工复核）。**数量就不同 ⇒ 强映射必然凑数**。当前处置：`VerdictBranch` **刻意不提供 `configKey()`**（由反射 + 集合两方面断言钉住），并登记该差异；`VerdictBranch.dbLabel()` 与 `prdConclusion()` **分开持有**（D1 前者为「稳定」、后者为「稳定·改善」）。**两者是否应统一**属 config owner 裁定。　🛑 **2026-10-01 实测回写**：已落地 —— `02_slots_seed.sql` L93 实测已是 `["稳定","依从不足","达标无效","全面评估","人工复核"]`（5 个中文键，L97 逐字注明与 `VerdictBranch.dbLabel()` 一致）；本行原登记已滞后。

**S2-8 溯源回放（ADR-11 `threshold_version`）新增欠账**（`S2-8` 登记，均**不代拍**）：

> `threshold_version` 已按 **PRD §C.1.9 硬约束②/③**（判定依据必须落库可回放 · 版本不可覆盖）与 **PRD L856**（模块映射版本并入 `threshold_version` 语义、**不新开字段**）落地为**服务端内容寻址指纹**。
> 下列两项是**落地过程中显式登记的边界**，不擅自扩表、不悄悄丢弃。

- [x] **`config #30` 未纳入指纹段（登记，待裁）→ 2026-09-27 批次九复核：比原登记更宽，已升级为"命名空间闭合自证"**：`threshold_version` 的九段口径**不包含 `config #30`**（指纹段清单见 `ThresholdVersionFingerprint.SEGMENTS` 九段 + `EXCLUDED_NOTE` 排除说明块，两者由断言互钉）。理由是 #30 不属于"判定口径九段"（它是展示/营业口径），把它并入会**扩大指纹语义面**、使非判定口径的变更也触发版本漂移；且 `DerivedMetricProfile` **当前不解析 `#30`**，扩进来是一次独立的解析器改动。
  > 🛑 **批次九纠正一处原登记的乐观假设**：原文写"`ThresholdVersionFingerprintTest` 断言排除集与九段互不重叠" —— 该断言**只能证明"排除集与九段不重叠"，证明不了"命名空间里没有第三个东西"**。实测 `cfg:verdict.*` **恰 4 槽**，原登记只说 #30，而 **#9 `branch_rules` 既不在九段、也不在 `EXCLUDED_NOTE`**（两侧都不在）—— 正是本条想防的"排除了不该排除的"的**镜像失效**："忘了排除"。
  > **已收口**：新增 `excluded_items_are_cross_checked_against_the_verdict_config_namespace` —— **`cfg:verdict.*` 全集（机械读 `02_slots_seed.sql`）== 九段已消费 ∪ `EXCLUDED_NOTE` 点名，不得有第三个**；`EXCLUDED_NOTE` 补 #9 条目；反向验证 `107`（P1/P2/P3，**4/4 PASS**，其中 P2 证明**真相源侧新增槽位也会被抓**）。
  > **仍属 config owner 裁定**：`#30`（及 #9）**是否应并入九段、或另开一段** —— 本轮只把"当前显式排除"钉成机械事实，**不代为裁定并入与否**。
- [ ] **模块级 `ΔH_m` 的"模块映射"尚未落库（PRD L856 的执行缺口，登记待裁）**：PRD L856 建议把"模块映射版本"**并入 `threshold_version` 语义**（已完成 —— 并入即"不新开字段"）；但**"模块 → 权重"的映射本身尚未落库**（当前九段口径取自 config 真相源，映射表未建表、未持久化）。即：**语义已并入、数据未落地**。是否建"模块映射"表、以及它与九段口径的边界，属 PRD owner + config owner 裁定；本轮**不擅自扩表**（与 S2-7「不擅自扩表」同一纪律）。
- [x] ~~**`#21 cfg:crossstore.split_threshold` 声明「总部唯一可改」，而运行代码硬编码且从不读配置（批次十发现）**~~ → **N-14 已收口（2026-09-27 · 批次十一）**：原登记描述已作废。**实测接线成立**：`ConfigSeedCrossStoreProfileSource` 解析 `#21`/`#22` → `CrossStoreThresholds`（唯一解析+校验实现）→ `WebConfig` 注入 `CrossStoreSettlement`，是**真实运行时消费路径**；`#21`/`#22` 已从消费台账"未消费"移入"已消费"（`ConfigSlotConsumptionLedgerTest` 断言 ⑤ 已**由"差异还在"翻转为正向断言**："生产装配必须从配置注入阈值，不得再出现硬编码常量"——接线被回退即红）。**"总部唯一可改"这句现已成立。**
- [ ] **`#30 cfg:verdict.formula_params` 与 `#4`/`#35`/`#33` 同值多处声明（批次十发现，登记待裁）**：
  实测 `cfg:verdict.formula_params` 运行时**无人消费**（与 #9 同类），且其取值与其它槽位**重合**：
  `adherence_gate.min = 0.8` ≡ `#4` 的 `0.80`（判定链读的是 #4）；`range` 与 `#35 cfg:scale.range_rule` **逐字相同**；
  `module_total_max = 16` 同 `#33`。⇒ **同一业务数字在一处以上被声明**，当前重合、未来有分歧风险。
  是否收敛（删冗余 / 改为引用 / 明确"#30 仅对内文档"）属 **config owner 裁定**；本台账只指出同值事实。
- [x] **46 槽中有 28 槽"声明了但生产代码未消费"（批次十首次全量清点 → 批次十一复核更新）**：　🛑 **2026-10-01 实测回写**：**本行已自证为"登记备查"** —— 其正文逐字写明「**原登记点名的两条"真缺陷候选"已全部收口**」（`#21` 已接线 · `#30` 属命名口径非代码缺陷），余下 28 槽「属**预期的分期落地**（所属域尚未开工）」。台账由 `ConfigSlotConsumptionLedgerTest` 机械核对（18 ∪ 28 = 46 互斥且并为全集）。⚠️ 其声明的**诚实边界**仍成立：静态扫描不判"引用是否真的走了运行时路径"。
  实测消费面 = **18 已消费 ∪ 28 未消费 = 46**（`ConfigSlotConsumptionLedgerTest` 机械核对；批次十一把 `#21`/`#22` 由未消费移入已消费，故 16/30 → **18/28**）。
  大部分"未消费"属**预期的分期落地**（如 #14~#17 告警阈值、#31 回溯验收、#34/#37/#39 题库规则 ——
  其所属域尚未开工）；**原登记点名的两条"真缺陷候选"已全部收口**（`#21` 已接线见上；`#30` 见下，属命名口径登记、非代码缺陷）。
  🛑 **本台账不判"该不该消费"** —— 那是 config owner 裁定；它只把"未消费"钉成显式事实，
  使"裁定之后接线"成为**显式动作**（消费了就必须从账本移除，否则红）。
  🛑 **诚实边界**：静态扫描只判"代码里有没有引用这个键"，**不判该引用是否真的走了运行时路径** ——
  要那一条需运行期探针（如真库门禁里改配置断言行为变化），属后续批次。

**S2-9 契约域 A（A2 `/auth/me` + A3 `/stores`）新增欠账**（`S2-9` 登记，均**不代拍**）：

> 域 A 两端点已在 V5 已建的 `store` / `staff` 两表上落读写（**不新增迁移**），可见性档位由 `#43` 唯一权威下发、退款可见性由 `#40` 独立下发。
> 下列两项是**落地过程中显式登记的边界**，不擅自扩表、不悄悄丢弃。

- [x] **🛑 `config #40` 与契约 `VisibilityRole` 的键集分叉（A2/A3 使用遗留大写别名时会报 5001，登记待裁）**：契约 `VisibilityRole.ADMIN` 登记了四个**遗留大写别名**（`SUPER_ADMIN` / `REGION_ADMIN` / `STORE_STAFF` / `TENANT_ADMIN`），它们能通过 A2 的**端角色解析**；但 `config #40` 的退款可见性受众键**是按 `manager` / `area` / `hq` 三个小写码建的** —— 用大写别名调 A2 会在 `AuthMeService.audienceOf` 处撞到"两个配置口径的键集分叉"，当前**报 `5001`（口径断裂）而非 `2001`**（语义是"配置缺陷需要有人补登记"，不是"这个角色没权限"），且**绝不回落 `false`**（回落会把口径断裂伪装成一次权限收紧，事后排查只能看到"这个角色看不到退款"）。**是否为大写别名补 `#40` 受众键、或让 `#40` 复用 `VisibilityRole` 的别名集** 属 config owner 裁定；当前**显式登记并保留 5001 的报错语义**。　🛑 **2026-10-01 实测回写**：已落地 —— `RefundAudienceRole.legacyUppercaseAliases()`（L253）已为四个大写别名显式登记别名层，**不再触发 5001**；本行原登记已滞后。
- [ ] ⚠️ **本条与 L2448 是同一条的重复登记**。统一以 **L2448** 为准（该条已列契约侧机械守护 N-5）；**本条保留仅为留痕，不再单独追踪**。
　　原文：**`store_customer_service` 角色无端（`endRoleCode = null`）**：契约 `x-roles` 对该角色的登记是 `token-role: null, end: null` —— **它不使用本系统、没有账号**，故任何请求都不可能带着这个身份来。保留它在 `BandAudienceRole` 的**唯一目的是让 `#43` 的键集能被完整解析**（少了它，解析器遇到该键会报"未知角色"，把一条**已裁定的事实**误报成"配置有误"）。`BandVisibilityMatrixTest.endless_role_cannot_be_reached_from_an_end_role` 断言 `rowForEndRole` 无从得到它。**该角色是否日后需要端** 属业务方裁定。

**S2-10 契约域 B（B1~B6 全六行）新增欠账**（`S2-10` 登记，均**不代拍**）：

> 域 B 六端点在 V1/V2/V5/V7 已建的 6 张表上落读写（**不新增迁移**）；档案写入权限由新立的 `customer:archive` 承载，客户侧探测端点的第三层防护由派生字段拦截器承担。
> 下列两项是**本轮做出的语义决策 / 刻意的防护缺省**，都**不可自行追认**，故登记待裁。

- [ ] 🟡 **🛑 权限码【拆分】是一次骨架侧决定，须由契约 owner 追认（A-8，已落结构化台账 + 门禁，裁定仍未给出）**：契约中**不存在**权限码定义（`grep permission` 仅一处无关枚举，由 `UpstreamGapRegistryTest` 同源事实佐证）⇒ 权限码是**骨架期自有编码**、**不是契约冻结件**。但本轮新立的 **`customer:archive`**（= 档案写入，发给契约点名的全部角色，**含 `therapist` / `meridian`**）与既有的 **`customer:write`**（= 内容资产写入，仅管理层级 + 遗留大写码）构成了**一次语义分码**。**追认点**：① 这两码的职责边界是否符合后续"**权限矩阵来自配置 / 策略服务**"的规划（见本节开头的 `dy-security` 权限矩阵待办）；② 是否需要在未来的权限管理界面上把两码作为**独立可分配项**暴露。当前实现是**最小改动解**（**纯加法**，无任何角色既有能力被缩小），但 **`grep` 不到契约依据这一点本身就是登记理由** —— 若未来权限码改由契约或策略服务冻结，本次分码即成为需要迁移的历史决策，故**先登记**。　🟡 **2026-10-01 实测状态 = 形式追认**（代码已成体系落 `PermissionRegistry` + `PermissionCodeRegistrationGateTest` 钉住「码级 ≠ 角色级」，**缺设计确认**）；裁定权在契约 owner，**本行不得写成「已收口」**。
  - **已落机械守护（2026-09-27，A-8 收口）**：`PermissionCodeRegistrationGateTest` 新增**第六章 + `skeleton_defined_codes_form_a_closed_registry`**，把"哪些码是骨架期自立的"从文档句变成**可机械核对的集合**：
    - `SKELETON_DEFINED_CODES`（**5 码 → 追认点**）：`customer:archive` / `assessment:write` / `fulfillment:write` / `doc:write` / `audit:read`；
    - `CONTRACT_OR_DOMAIN_CONVENTION_CODES`（8 码，有契约/域惯例依据，不属追认范围）；
    - 四条断言：① 台账码须**真的出现在源码**的 `@RequirePermission` 里（防"追认一个幻影"）；② 源码码须**被归类**（不得有"来历不明的码"）；③ 两清单**不重叠**；④ 追认点须含"追认点"字样且 **≥20 字符**（防占位式登记）。
  - 🛑 **口径纪律**：本条在卡点总清单中属 **A 硬卡点**，README 措辞是「**登记待裁 + 机械守护**」，**不是「已裁定」** —— 门禁只负责"不漏项"，不回答"该不该这样分码"。
  - **反向验证**：`verification/103_b5_registry_and_gates_reverse_verification.py` **K1** —— 在控制器上贴一个未登记的新码 `scale:import`，台账门禁**立即红**；逐字节还原后复绿。
  - 判别依据：`PermissionRegistry` 类注释「写权限按语义分码」+ `PermissionCodeRegistrationGateTest` 类注释「它**不回答**：『某个特定角色该不该持有某个码？』」（**码级 ≠ 角色级**，见 §反向验证 S2-10 段）。
- [ ] 🟡 **🛑 E5 端点"缺第三层"是刻意设计，须由安全评审确认（A-9，出参方向断言已补，裁定仍未给出）**：E5 `POST /band/available-dates` 与 A2 `GET /auth/me` 同款：**刻意不贴任何 `@RequirePermission`**。理由：契约 `x-callable-roles: [client]`，而 `PermissionRegistry` **刻意不登记 `client`**（这是既有基线，由 `PermissionCodeRegistrationGateTest` 第 ⑤ 条钉住）⇒ **贴任何权限码都会让合法客户端请求恒 403**。**这依赖一个隐含前提**：端点本身**不下发任何可见性受限字段**（`gap_reason` 等属"仅门店 / 管理端可见"，在 E5 的响应里**根本不存在**），且越权索取由 **`DerivedVisibilityInterceptor`** 独立承担（它排在权限拦截器**之前**，返回 403 + `data.denied_fields`）。**追认点**：是否接受"**以派生拦截器代替端点级权限注解**"作为客户侧端点的**标准做法**。　🟡 **2026-10-01 实测状态 = 形式追认**（代码已定并已落出参方向断言，**缺一句「就这样」**）：`DerivedVisibilityE2ETest.e5_success_response_carries_no_client_forbidden_field` 已补出参方向；裁定权在安全评审，**本行不得写成「已收口」**。
  - **🛑 出参方向断言已补（2026-09-27，A-9 收口）**：清单原文指出「用例只覆盖**入参**方向，故仍须安全评审确认该设计前提」。本轮补上**出参方向**：`DerivedVisibilityE2ETest.e5_success_response_carries_no_client_forbidden_field` —— 断言合法 client 探测的 **200 响应**里 `data` **不含** `DerivedFields.derivedAndAdjacent()` 任一字段、不含 `gap_reason`；并**反向自证** `data` 非空且含契约点名字段（防"空壳响应让本条退化恒绿"）；且出参字段集必须 = 契约 5 属性 **+ 闭合白名单扩展位**。
  - **首次运行抓出真实差异（留痕）**：实现下发了 `history_type`，而契约 `BandAvailableDatesData` **只在入参**声明它。它不是受限字段，但是一个「契约未声明的出参」⇒ 已加入 `allowedExtensions` 闭合白名单并**逐项写明理由**（**不放宽断言**：新增扩展位必须来此登记）。
  - **🔬 纵深防御分层已被反向验证确证**：`verification/103_*` **K2a**（注入**未登记**非受限字段 `internal_score`）⇒ 断言**红**；**K2b**（注入**受限**字段 `gap_reason`）⇒ 断言**保持绿**，因为 `gap_reason` 已被出口兜底 `DerivedResponseBodyAdvice`（**层 1**）在出站前整体摘掉。**两者合起来**证明：层 1（出口兜底）与层 2（A-9 出参白名单断言）**各自独立生效** —— 这正是"缺第三层"设计所依赖的隐含前提的**可执行证据**。
  - 🛑 **口径纪律**：本条属 **A 硬卡点**，README 措辞是「**登记待裁 + 机械守护**」，**不是「已裁定」**。🛑 **若未来 E5 需要下发任何受限字段，则必须补回端点级防护** —— 否则会变成"**无第三层 + 有敏感字段**"的裸端点，那时派生拦截器只拦"带派生键的**入参**"，拦不住"响应里多出的**出参**"。

**S3 批次（契约域 C/D/E/I）新增欠账**（`S3-1~S3-4` 登记，均**不代拍**）：

> 域 C/D/E/I 的 30 个端点已在既建表上落读写（**不新增迁移**），新立 `assessment:write` / `fulfillment:write` / `doc:write` 三个权限码 + `2004 PLACEHOLDER_OUT_OF_SCOPE` 一个错误码。下列是本批落地过程中撞到的真实边界，每处都**不擅自扩表、不臆造语义**，登记待裁。

- [x] **🛑 C4 `POST /customers/{id}/cycle-assessments`（周期评估提交）与 F1 存在数据模型冲突（契约拆两端点、数据模型一张表，登记待裁）**：契约把「周期评估提交」（C4，`summary` 逐字「每 7 次触发」）+「判定结论落库」（F1）拆成**两个 operationId**；但 V5 唯一的落库载体 `cycle_assessment` 表里 **`verdict` / `metric_snapshot` / `as_dimensions_json` / `module_scores` / `threshold_version` 五列全 NOT NULL**，只能由判定引擎（P0-12）落满，且 F1 的 `insertCycleAssessment` 是**一次性自建 `cycle_id` 行 + 落结论**（不是"更新已存在的评估行"）。结论：**「提交评估」这一阶段在数据模型上没有独立落点** —— C4 若落 `cycle_assessment` 必须满足判定列 NOT NULL（而提交阶段尚无判定结论），若不落又违背 POST submit 语义。再加契约 C4 **无 `requestBody`、无出参 data schema**（`$ref` 仅 `ResultEnvelope`，无 `CycleAssessmentData`），入参/出参形状未冻结。**处置**：不臆造（既不强行填判定列、也不擅自建"评估提交"新表），C4 判为「契约未冻结 + 数据模型两阶段缺桥接」的端点，**登记待契约 owner + 数据字典 owner 裁定**（要么建独立的周期评估提交表、要么放宽 `cycle_assessment` 判定列可空）。触发阈值依据 = `config #1`（`cfg:assessment.cycle_service_count`=7）+ `#3`（`cfg:assessment.first_cycle_checkpoint`=7），服务次数 = `visit` 表客户维度全局唯一账本（U2，已由 `FulfillmentLedger.countByCustomer` 提供）。　🛑 **2026-10-01 实测回写**：已落地 —— 契约 `CreateCycleAssessmentRequest`（L648 引用 / L2018 定义）已补 `requestBody`，`V8__verdict_two_phase_alignment.sql` 已落两阶段拆分；本行原登记已滞后。
- [ ] ⚠️ **本条与 L2471 是同一条的重复登记**（内容一致，仅措辞略异）。统一以 **L2471** 为准，该条已按「**明确出范围 + 机械守护**」回写为 `[x]`；**本条保留仅为留痕，不再单独追踪**。
　　原文：**F3 `GET /audit/signals` 与 F4 `GET /audit/coverage` 未实现（契约侧缺口，已明确出范围，A-10）**（契约 `[admin]`，F3 `x-row-scope` 门店仅本店；F4 要求"三数同显"，其 `a3_observability` 为 **TBD**）。两者都依赖"稽核信号/覆盖率"的上游口径，**未立项**（与 S2-7 段同款登记，此处重申时序口径；本轮已补 `UpstreamGapRegistryTest` 机械守护，详见上文 A-10 条目）。
- [x] **🛑 E1 契约 §4.1 与 PRD §4.6 词表分叉（已登记，待契约 owner 裁定）**：契约 E1 四态英文 `syncing/synced/sync_failed/no_data_today` + 触发源英文枚举（`on_show_cold/on_show_hot/checkin/daily_report/manual`），而 V5 的 `band_sync_log`（按 PRD §4.6「同步尝试日志」语义建表）用 `trigger_source` 中文 CHECK（`onShow/到店核销/每日填报/手动`）+ `result` 两态。硬映射会 23514 ⇒ **E1 暂只做契约校验 + 内存幂等受理（24h TTL），不落 `band_sync_log`**；E6 读方向做 result 两态 → 四态映射。**证据 = 本批 E2E 曾因 `success/failed` 硬塞 `trigger_source` 报 23514 而暴露**。　🛑 **2026-10-01 实测回写**：已落地 —— `V9__band_sync_log_contract_enum_alignment.sql`（头注释逐字「**A-5 裁定的落地件**」）已把 E1 四态英文 + 五值英文 trigger 对齐；本行原登记已滞后。
- [x] ~~**I3 `POST /doc-templates/upload` / I7 `GET /doc-templates/{id}/download` 未实现（文件系统通道）**：multipart 上传 + 对象存储 + 字节流下载 + 超管权限，依赖对象存储接入。~~ → **B-4 已收口（2026-09-26）**。**清单原文「依赖对象存储接入」只对了一半**，勘察后按事实处置（登记偏差，不照抄）：
  - **清单原文错误的部分**：`doc_template` 的**存储契约在 DB 层早已完备** —— V5 已建 `file_ref`（形态 `oss://<bucket>/<tenant>/<doc_type>/<version>/<hash>`）、`file_hash`（SHA-256）、`mime_type` 三值 CHECK（`application/pdf` / `image/jpeg` / `image/png`）、`size_bytes ≤ 10 MiB` CHECK、以及 `ck_doc_template_content_or_file`（内容式与文件式互斥）。**「等对象存储接进来」不是缺口的形态**：即使对象存储到位，也没有任何代码路径会用它。
  - **真实缺口（三条，全部已处置）**：
    1. **I3/I7 的端点与存储抽象完全不存在** —— `doctpl` 包此前只有 `controller`（I1/I2/I4/I5/I6/I8）/ `domain` / `repository` / `service`，**上传与下载连接口都没有**。⇒ 已建 `BlobStore` 抽象 + `InMemoryBlobStore` 默认实现（**可换 OSS，不需改 `DocFileService`**）。
    2. **`spring.servlet.multipart` 完全未配置** —— 容器默认上限 **1 MB**，而契约业务上限是 **10 MiB** ⇒ 「≤10 MiB」这条契约事实上**不可达**（3 MiB 的正常文书会先被容器 413）。⇒ 已配 `max-file-size: 12MB` / `max-request-size: 12MB`，并由**配置门禁**交叉断言「容器上限必须 > 业务上限」（防后人改小）。
    3. **`IdempotencyBodyCacheFilter` 无差别包装所有请求 ⇒ 破坏 Servlet multipart 解析**（**本轮最深的一条**）—— 该过滤器用 `CachedBodyRequestWrapper` 包装全部请求，而包装器构造函数 `getInputStream().readAllBytes()` **读尽流** ⇒ 容器 multipart 解析拿不到 part ⇒ 上传恒 500 `MissingServletRequestPartException`。⇒ 已修：过滤器**跳过 multipart 请求**（不缓存 10 MiB 体，也是性能取向）；`IdempotencyInterceptor` 对 `multipart + Idempotency-Key` **显式 400 拒绝**（而非静默退化成不稳定哈希）。**这条缺陷是"上传用例全红"挖出来的，不是读代码读出来的。**
  - **落地清单**：
    - `dy-app/.../doctpl/storage/BlobStore.java`（接口：`put` / `open` / `exists` / `sizeOf`；**刻意不提供 `byte[] readAll()`** —— I7 是字节流下载）· `BlobStoreException.java`（与「内容为空」区分）· `InMemoryBlobStore.java`（`@Component`；`put` 时**重算 SHA-256 自校验**，不符拒收；`open` 不存在即抛错 fail-closed）。
    - `dy-app/.../doctpl/storage/DocFileRef.java` —— 引用构造/解析对称（`SCHEME="oss://"` / `DEFAULT_BUCKET="diaoyuanyun-doc"`）；**doc_type 用 `URLEncoder` 编码**（4 个 doc_type 是中文 ⇒ 对象键永远纯 ASCII）；正则 `^oss://([^/]+)/([0-9a-fA-F-]{36})/([^/]+)/(\d+)/([0-9a-f]{64})$`。
    - `dy-app/.../doctpl/service/DocFileService.java` —— 六条契约逐字：① mime 三值 ② ≤10 MiB ③ **落盘前算 hash** ④ 幂等 `(tenant, doc_type, file_hash)` 命中 ⇒ `IDEMPOTENT_REPLAY(4002)` ⑤ **不解析内容**（拒 PE/`MZ` 与 ELF 魔数，**刻意不拒 `#!`** —— shell 脚本不是可执行二进制）⑥ 新版本 `is_active=false`。下载三重校验：**超管** / 仅 upload 形态 / **读后重算 SHA-256 比对 `file_hash`**（不符 ⇒ 9001）。**写入顺序 = 先 blob 后落库**（孤儿对象比「有记录无文件」的可见故障更安全）。
    - `dy-app/.../doctpl/controller/DocFileController.java` —— `POST /doc-templates/uploads`（`multipart/form-data`，`@RequirePermission("doc:write")`）+ `GET /doc-templates/{id}/download`（**返回原始字节流，不套 `Result` 信封**；`Content-Disposition: attachment` 带 RFC 5987 文件名；响应头 `X-File-Sha256`）+ `GET /doc-templates/uploads/contract` 自描述。
    - `dy-app/.../doctpl/repository/DocTemplateLedger.java` —— 新增 `findByDocTypeAndHash` / `findVersionRow` / `fileRefOf` 三方法。**幂等判定走 `doc_template` 自身、不另立幂等表 —— 与 B-2 恰好相反**（理由已写进 javadoc：这里的幂等键就是业务唯一键，正是「让库层承担幂等轴」）。
    - `application.yml` 的 multipart 两行（含长注释：容器上限必须 > 业务上限）。
    - `DocFileE2ETest`（**10 个 `@Test`** = Upload 5 + Download 5，真库 + 真 HTTP）· `DocFileBoundaryGateTest`（**7 个 `@Test`**，构建期不依赖 DB：容器上限 vs 业务上限 / 四类 doc_type 往返 / 引用恒 ASCII / 兼容未编码旧值 / 拒畸形与非法入参 / 业务上限恰为 10 MiB）。
    - dy-web 两处基础设施修复（见上述缺口 3）。
  - **门禁抓到的真实缺陷 = 4 个**：multipart 请求头缺 boundary（测试自设 `Content-Type` 反而破坏了转换器补 boundary）· `application.yml` 多 YAML 文档导致 `Yaml.load()` 报"expected a single document" · multipart 流被过滤器读尽 · **测试自己硬编码 `version=1` 而实际是累积版本**（期望 500 实得 404）。**这四条全部是"上传用例全红"暴露出来的 —— 门禁的价值正在此。**
  - **登记待裁（B-4 衍生）**：① **multipart 端点不得依赖 `Idempotency-Key`**（已成纪律，过滤器已按此实现）；② **`TENANT_ADMIN` / `super_admin` 未在 `PermissionRegistry` 登记 `doc:write`** ⇒ 契约点名的超管若以 `TENANT_ADMIN` 角色出现会被 403（`DocFileE2ETest` 的 alias matrix 已**钉住这个既有分叉**：`hq`/`SUPER_ADMIN` 放行、`TENANT_ADMIN` 拒绝 —— 是 T-11 的延伸，不代拍）。
- [x] ~~**H1 `POST /esign/callbacks/{provider}` 电子签回调未实现（占位端点，待电子签服务商接口冻结）**~~ → **B-5 已收口（2026-09-26）**。**清单原文「占位端点」方向对，但结论必须更严**（登记偏差，不照抄）：
  - **推翻的部分**：契约 H1 **不是「等一个占位端点被填上」** —— 它逐字写着「**厂商选定之前三端不得据本节编码**」，且把字段名/结构/**厂商签名算法**/`provider` 取值域全部列在 **`x-not-frozen`**。⇒ **此刻新开端点 = 开出一条「任意请求都能推进 `AGREEMENT_SIGNED` 门禁」的通道**（`agreement` 表已含 `doc_template_id` / `rendered_snapshot` / `rendered_hash`，`CustomerGateGuard` 已有 `PLAN_APPROVED → AGREEMENT_SIGNED`）。**未验签的回调端点 = 未授权客户可自证已签。**
  - **冻结与未冻结的分界（契约原文）**：**已冻结三条形态** —— ① 事件三值枚举 ② 幂等键 `sign_request_id + event_type` ③ 载荷白名单；**未冻结** —— 字段名 / 结构 / 厂商签名算法 / `provider` 取值域。故 **只落已冻结的三条，不落控制器**。
  - **落地清单（domain 层，无端点）**：
    - `dy-app/.../doctpl/esign/EsignEventType.java` —— `{SIGNED("signed",true), REJECTED("rejected",false), EXPIRED("expired",false)}`；`wireName()` / `advancesGate()`（**仅 `signed` 推进门禁**）/ `parse(String)`（未知值 **fail-closed 抛出**，不静默归类）/ `describe()` / `wireNames()`。
    - `dy-app/.../doctpl/esign/EsignCallbackPayload.java` —— `ALLOWED_KEYS` 白名单 = 契约五字段；`fromRaw(Map)` **白名单外键一律拒收**（并单独点名拒健康数据/禁忌类键，防「顺手多传一个字段」）；`idempotencyKey()` = `signRequestId + "#" + eventType.wireName()`（**刻意不含 `occurred_at`** —— 含了就等于厂商重发时幂等失效）；`safeProjection()`（投影不含任何业务内容）。
    - `EsignCallbackContractGateTest`（**11 个 `@Test`**）—— 枚举恰三值 / **仅 `signed` 推门禁** / 未知事件拒收 / 幂等键构成 / 白名单通过 / **未知字段拒收而非忽略** / 健康·禁忌字段被点名拒收 / 必填缺失拒收 / 投影无业务内容 / **`not_frozen_items_must_not_appear_in_code`**（剥注释后断言源码不出现厂商名与签名算法名）/ **`no_accessible_callback_endpoint_yet`**（断言 `esign` 包恰两个 domain 文件、**不存在 Controller**）。
  - **登记待裁（B-5 衍生）**：**厂商选定 + 接口冻结后才补控制器**；补时要一并落地的是：验签、`provider` 取值域校验、五字段到 `agreement` 的映射（含 `rendered_hash` 校验）。在此之前**任何 H1 端点都是安全缺口**，故「没有端点」是**当前正确状态**，不是遗漏。
  > **B-4 / B-5 同一处置纪律**：先登记「清单现状描述与勘察事实的偏差」，再落地，**不照抄清单**（B-4 原文说"依赖对象存储"，实为"端点与抽象都还没有"；B-5 原文说"占位端点"，实为"契约禁止此刻开端点"）。
- [x] **契约 §2.0 期望表 11 → 12 码**：本批新立 `2004 PLACEHOLDER_OUT_OF_SCOPE(403)`（契约 openapi §2.0 L222 权威表自域 I 起已含该码），`ResultEnvelopeTest` 期望表同步至 12 码。该变更正是"期望表滞后于契约"被 `error_codes_match_contract_exactly` 抓出的留痕式变更（枚举 12 ≠ 期望 11 → 立即红），非静默漂移。　🛑 **2026-10-01 实测回写**：已落地 —— `dy-common` 的 `ResultEnvelopeTest` L26-27 逐字「2026-09-26（S3-4 / 契约域 I）新增第 12 码 `2004 PLACEHOLDER_OUT_OF_SCOPE`…本表去重后同步至 12 个」⇒ 期望表**已同步为 12 码**，此项是留痕式变更的**结果**，非待办。

---

**📊 契约端点覆盖总账（批次六新增 · 把"开发到什么程度"变成机械事实）**

> 本项目反复验证过同一个失效模式：**写进文档的清单会被构建忽略**。
> 端点覆盖尤其危险，因为它的两种错误方向**都不会让任何测试变红**：
> 少一个端点（698 用例全绿 —— 没有用例去数"契约声明了几个端点"）；
> 多一个端点（同样全绿 —— 新开的内部端点不与任何既有断言冲突，却可能是未登记的出站面）。

- [x] **`EndpointCoverageLedgerTest`（4 例）已落地（批次六）**：把两侧都钉住。
  - **① 扫描器自证**：契约恰 **45** operationId；实现侧扫到端点 **≥60**；并用**归一化后**的具体端点做反向自证（`GET /stores` / `POST /refunds` / `GET /customers/{}` / `POST /doc-templates/uploads` / `GET /audit/log-chain`）。
  - **② 差额登记（核心断言）**：契约每个 operationId 必须**要么已实现、要么在 `OUT_OF_SCOPE` 逐条登记理由**；且 `OUT_OF_SCOPE` 里**不得残留已实现项**（归零须显式动作）。
  - **③ 多出登记**：实现侧相对契约多出的端点必须在 `INTERNAL_ENDPOINTS` 登记；且**不得有僵尸登记**（端点已删、登记还在）。
  - **④ 数字自洽**：`45 = 41 + 4`；`68 = 41 + 27` —— 覆盖率口径由断言核对，不靠人记。
- **机械清点结果（当前口径）**：

  | 项 | 数 | 明细 |
  |---|---|---|
  | 契约 operationId | **45** | `contract/openapi-v1.0.0.yaml` |
  | ├ 已实现 | **41** | 实现侧有对应路径映射 |
  | ├ 【Non-goal】出范围 | **1** | `authLogin`（A1 登录 —— 身份由外部 IdP 承担；联调走 `/api/v1/demo/*`） |
  | ├ 【未立项】出范围 | **2** | `listAuditSignals`（F3）· `getAuditCoverage`（F4）—— A-10 明确出范围 |
  | └ 【待冻结】出范围 | **1** | `receiveEsignCallback`（H1）—— 契约 `x-not-frozen`，此刻实现 = 未验签通道（B-5） |
  | 实现侧端点数 | **68** | `dy-app/src/main/java` 全部控制器 |
  | ├ 对应契约行 | **41** | 同上 |
  | └ 登记为内部端点 | **27** | 内部自描述 14 · 演示入口 4 · 自建能力面 9 |

- 🛑 **三种"出范围"语义不可混用（本台账的核心区分）**：**【Non-goal】**= 不需要（不许找谁解锁）· **【未立项】**= 没依据（找产品 owner 补口径）· **【待冻结】**= 实现了反而更危险（找厂商 + 安全评审）。三者都可写"暂不实现"，但**理由不同 ⇒ 下次 review 该找谁解锁不同**。混用会让"明确不做"与"忘了做"无法区分。
- **反向验证**：`verification/104_endpoint_coverage_reverse_verification.py`（**3 组注入 · 8 项断言**）—— **L1** 一条出范围登记"消失" ⇒ ② **红** / **L2** 新增未登记端点 ⇒ ③ **红** / **L3** 登记键被改（僵尸登记 + 未登记端点）⇒ ③ **红**；全部逐字节还原 + 还原后复绿 → **总体 PASS**（证据落 `verification/104_endpoint_coverage_reverse_verification.md`）。
  - 🛑 **脚本第一版的两处缺陷（教训）**：① L1/L3 直接**删 `put(...)` 整行**，导致**编译失败**（悬空字符串字面量）—— 红的是编译器不是断言，等于"门禁有牙齿"**未被证明**；改用**改键名**（语法合法、语义等价）后正常。**通用纪律：注入必须保持源文件语法合法**。
- 🛑 **本台账是"当前边界声明"，不是考古记录**：某端点被补上后**必须**从 `OUT_OF_SCOPE` / `INTERNAL_ENDPOINTS` 对应项删掉，否则③/② 会红。

---

## 六、明确不做（Non-goals，与裁定一致）

微服务拆分 / K8s / 服务网格 / 多区域多活 / schema-per-tenant / database-per-tenant / GraphQL /
外部配置中心作真相源 / 真实数据库建库 / 任何业务流程实现（判定引擎、改善率引擎等） / 前端 / **JWT 签发端（本骨架只校验，不签发）** /
**组织主数据开通流程的【对外入口】（租户 / 门店 / 员工 / 区域 / 设备的 HTTP 建档端点）
—— B-7 已给出【运维通路】（V15 原语 + 无 HTTP 映射的组件），契约端点仍为零，由 `ProvisioningBoundaryGateTest` **10 例**机械守护**（B-7 时 7 例 → 批次十四 V16 增判据 → 批次十五 B-10 判据⑧ → 现 **10 例**；最新实测值，此前文档里的 7/8 为历史快照）。

> 🛑 **「前端」这一条的准确含义（2026-09-28 补，与规划 §三 的冲突如实登记）**：
> 本清单里的「前端」与本仓 `缺口修复总规划-2026-09-27.md` §三 **G-B**（标题逐字
> 「三端前端工程骨架（契约驱动）—— **可立即动工**」，表格标记「✅ **可以**」）**结论相反**。
> 两份文档日期不同、后者更新 ⇒ **按后者动工（后裁定覆盖先裁定）**，并在此登记冲突**待追认**。
> 本轮已落地的是 G-B 的 **B-1**（三端工程骨架 · 构建可跑 · 目录约定 · 环境配置分离 · 契约驱动端点层），
> 见 `frontends/README.md`。建议的澄清措辞（**尚未经裁定，不写成"已裁定"**）：
> 「不做」的是**在后端 Spring Boot 骨架内实现前端 UI**，不是**契约驱动的三端工程骨架** ——
> 后者的全部前置（冻结契约 / 三端裁剪契约 / 三端 SDK / `client-package` 纪律件 /
> `generator-matrix` 角色声明）均已就位。
> ⚠️ 类比：同清单里的「真实数据库建库」与「任何业务流程实现（判定引擎）」两项，
> 也已在后续批次被部分覆盖（真库 `diaoyuanyun_dev` 已建且真库门禁在跑；派生引擎已落地）
> —— 本清单的若干条目**已滞后于后续裁定**，引用前请核对最新裁定。

> 🛑 **上一条的准确含义（2026-09-27 B-7 收口后重写，避免误读）**：
> **"不做"的是这条能力的【对外 HTTP 入口】，不是这条能力本身。**
> 开通能力**已经存在**，形态是**运维通路**：V15 迁移的两个 plpgsql 原语
> （`provision_tenant` / `assert_tenant_context`）+ 应用侧无 HTTP 映射的
> `OrganizationProvisioning{Repository,Service}`。运维脚本 / 实施工具直接调用它们，
> 或直接 psql 调函数 —— **两条通路共用同一份实现**。
> **为什么特意不给它端点**：契约 40 个 path 里**有意**没有开通端点。开通是运维/实施动作，
> 不是业务 API；给它对外端点等于在**租户边界之外**开一个「**谁能创建租户**」的鉴权面，
> 而 `x-callable-roles` 里**没有任何角色声明覆盖它** —— 那个端点要么 403 对所有人（等于没有），
> 要么被某个 `permitAll` 放过（等于**任何人可建租户**）。加该端点属契约 **MAJOR** 变更，需产品共签。
> **这不等于数据模型缺失** —— 表都在（V1/V5 已建且带 RLS），相关**读路径**也都通了
> （`/stores` A3 门店列表、`StoreRepository` 的行级 scope 过滤、`/auth/me` 档位解算）。
> **仍未落地的是【调用入口】**（运维 CLI / 实施工具 / psql 直调，三者未选定）——
> 本轮交付的是**被调用方**；`agreement`（连带 B-5 电子签）的**离线签署**写入方已由 **A-1**（V21）补齐
> （**H1 电子签回调通路**仍待厂商冻结），
> 另两张补拉表（`band_sync_probe` / `band_daily_coverage`）的**补拉通路已由 A-3（V22，批次二十一）落地**
> —— 见 §五 N-13｜A-3 与批次二十一 块。
> 📌 **本节措辞的时效说明（批次十七 + 十九回改）**：本段写于批次十七之前，行内"`device`（门店作业域）的写入方亦仍在未开通账"这句
> **现已作废** —— `device` 由 **B-11**（V18 `register_device`）补齐，`scale` 由 **B-12**（V19）补齐，
> `case_archive` 由 **B-13**（V20）补齐，`agreement` 由 **A-1**（V21，批次十九）补齐，
> **`band_sync_probe` / `band_daily_coverage` 由 A-3（V22，批次二十一）补齐**。
> 🛑 **未开通账现值 = 0 张（已归零）** —— 见批次十九 / 二十一 块。

---

## 七、关键文件速查

- 根构建：`pom.xml`
- 启动类：`dy-app/src/main/java/com/diaoyuanyun/dy/app/DyAppApplication.java`
- 配置：`dy-app/src/main/resources/application.yml`（含 `dy.jwt` 段落；**密钥走环境变量 `DY_JWT_SECRET`，不入 DB 配置表**）
- 迁移基线：`dy-app/src/main/resources/db/migration/V1__baseline_tenant_rls.sql`（租户/customer/审计/幂等版本表，RLS fail-closed）
- 迁移 V2：`dy-app/src/main/resources/db/migration/V2__b_entities_state_machine_and_band.sql`（`customer_state_transition` 14 态状态机 + `band` 客户级手环，均 `ENABLE`+`FORCE RLS`）
- 迁移 V3：`dy-app/src/main/resources/db/migration/V3__band_telemetry_metric_long_table.sql`（`band_telemetry` 客户级手环遥测**长表 `metric` 化**（F-2 定案 = 方案 A）+ `gap_reason` 7 值 CHECK + `device_id → band(band_id)` FK 闭合 + 双幂等索引（日聚合用 `COALESCE` 处理 NULL 时分）+ `ENABLE`+`FORCE RLS`）
- 迁移 V4：`dy-app/src/main/resources/db/migration/V4__scale_item_bank.sql`（**S1-4 题库内容资产** `scale_item_bank`：8 年龄组 × 7 维 × 4 题序 CHECK 域（结构容量 **224** 由约束正文推导自证）+ `item_direction` **仅 `symptom`** + 五级锚点 `NOT NULL` + 版本化唯一键 `(tenant_id, age_group, dimension, item_no, version)` + **无 `customer_id`/`store_id`**（两本台账不得合并）+ `ENABLE`+`FORCE RLS` 双 `NULLIF`。**只建结构，不预置任何题面**）
- 迁移 V5：`dy-app/src/main/resources/db/migration/V5__remaining_entities_org_journey_verdict_refund.sql`（**S1-2 后端 22 实体建模余量**：**24 张租户表** —— 组织（`region`/`store`/`staff`/`device`）· 筛查同意量表（`screening_record`/`consent`/`scale`/`plan`/`agreement`/`baseline_assessment`/`plan_review`）· 派单到访日报（`device_dispatch`/`visit`/`daily_report`）· 周期评估与判定（`cycle_assessment`/`verdict`）· 回款留存归档（`refund`/`retention`/`case_archive`）· 到店信息与文书模板（`intake_profile`/`doc_template`）· 手环同步 3 表（`band_sync_probe`/`band_sync_log`/`band_daily_coverage`，各自 `device_id → band(band_id)` FK）。全部 `tenant_id NOT NULL` 外键 + `ENABLE`+`FORCE RLS` + 双 `NULLIF` fail-closed；条件 FK 闭合（`fk_region_supervisor` + 4 组 `plan(plan_id,version)` 复合 FK）；含 A-1…A-9 适配说明与自证块）
- 迁移 V6：`dy-app/src/main/resources/db/migration/V6__refund_domain_alignment_and_ledgers.sql`（**S2-1~S2-4 退款域字段补齐与留痕账本**（**只补齐、不新增能力**，编码前输入已冻结）：`refund` 补 `requested_at_source` / `requested_at_source_ref`（P0-19 C1-2 来源标注三取值 + 客户自证凭据引用）· 新建 **`refund_statement`**（客户原话 **append-only**，更正 = 追加新行 + `supersedes_statement_id` 指针，**旧行原样留着** —— 做进 `refund` 的可 UPDATE 列则"不可编辑"只剩注释里的承诺）· 新建 **`refund_receipt`**（回执三态：已推送 / 未授权 / 推送失败）· 新建 **`refund_offline_notice`**（人工告知，**刻意分表**）。全部 `ENABLE`+`FORCE RLS` + 双 `NULLIF`。🛑 **不动** V5 已冻结的 CHECK 字面（含 `refund.entry` 的 `'A 门店代录'`）—— 契约字面 `'A门店代录'`（无空格）与库内（有空格）的差异**不在库侧静默改名**，由领域枚举 `RefundEntry` 显式持两套字面并登记待裁）
- 迁移 V7：`dy-app/src/main/resources/db/migration/V7__customer_domain_alignment.sql`（**S2-10 契约域 B 库层对齐**：`customer` 补 **5 列**（`phone` / `gender` / `age` / `owner_store_id` / `serving_store_id`）+ 新建 **`intake_profile_revision`**（档案修订留痕 **append-only**，`ENABLE`+`FORCE RLS` + `tenant_isolation` 策略）。🛑 **只做 `ALTER TABLE ADD COLUMN`，不新增/删除表、不改既有列类型**。**来由**：S2-10 冻结域 B 语义时把「契约 + 字典 + PRD」与 V1 已建表结构逐列比对，发现 V1 的 `customer` 只有 8 列而**三处独立上游同时要求 13 列** —— 证据链 = 契约 **B2** `CustomerCreateRequest.required: [name, gender, age, phone, screening_id]` + 契约 **B4** `CustomerDetailData` 的 `owner_store_id`/`serving_store_id`（`x-visible-to: [therapist, meridian, admin]`，description 逐字「客户不下发」⇒ 二者必须真实存在于库）+ 字典 §2.6 与 PRD **L1310**。**四条处置纪律**（不放宽契约 / 不发明替代列名 / 不在库侧静默改名 / 只做 `ADD COLUMN`）逐字写在脚本头部注释）
- 迁移 V8~V14（**服务端唯一权威与加密链**；文件名即语义，细节见各批次变更块）：`V8__verdict_two_phase_alignment.sql`（判定两阶段对齐）· `V9__band_sync_log_contract_enum_alignment.sql`（手环同步日志契约枚举对齐）· `V10__band_telemetry_field_level_encryption.sql`（手环遥测字段级加密）· `V11__key_material_persistence.sql`（密钥材料持久化）· `V12__band_telemetry_sensitivity_aligned_envelope_guard.sql`（遥测敏感度对齐信封守卫）· `V13__backfill_schema_migration_registry.sql`（迁移登记表回填）· `V14__config_truth_source_tables.sql`（配置真相源表）
- 迁移 V15：`V15__organization_provisioning.sql`（**B-7 组织建档通路**：两个 PL/pgSQL 原语；**运维通路、无 HTTP 映射**，契约端点仍为零，由 `ProvisioningBoundaryGateTest` 机械守护）
- 迁移 V16：`V16__cross_tenant_reference_integrity.sql`（**跨租户引用完整性**：给 12 张载体表补 `UNIQUE (tenant_id, <pk>)`，使跨租户引用被**库层复合外键**拒为 `23503`；反向验证 `verification/116` **9/9**）
- 迁移 V17：`V17__band_binding_primitive.sql`（**B-10 手环绑定通路**：两个 PL/pgSQL 原语（绑定 / 解绑）；反向验证 `verification/118` **17/17** —— 当场抓出 V17 自身 4 处缺陷）
- 迁移 V18：`V18__device_provisioning_primitive.sql`（**B-11 设备建档通路**：`register_device` / `retire_device`；两态 `CREATED`/`ALREADY_EXISTS`；跨租户 `device_id` 撞号必须 `RAISE`；反向验证 `verification/119` **22/22**）
- 迁移 V19：`V19__scale_provisioning_primitive.sql`（**B-12 量表建档通路**：`register_scale`；🛑 `scale_version` 是**死维度** —— 单列主键下"版本递增"在库层无法表达 ⇒ **版本不同必须 `RAISE`「版本【不可覆盖】」**；反向验证 `verification/120` **28/28**）
- 迁移 V20：`V20__case_archive_provisioning.sql`（**B-13 结案归档通路（批次十七）**：`register_case_archive(...)`（写入 + 幂等 + 门禁）/ `latest_archive_of(...)`（读侧全序取最新）；**5 项硬门禁 + 1 项警告门禁**（🛑 手环键 `handband_recorded_as_reference` **不得**进硬门禁数组 —— PRD **P0-25** 与 §5.3③ 合规红线）；**跨租户 `archive_id` 撞号必须 `RAISE`**（`ON CONFLICT` 推断目标是**单列全局主键**，不含租户维度）；反向验证 `verification/121` **45/45** —— 一次运行抓出 4 处真实缺陷（3 处静默假绿 + 1 处假红））
- 迁移 V22：`V22__band_refetch_provisioning.sql`（**A-3 手环历史补拉通路（批次二十一）**：🛑 **不建表、不改表**（两张表 V5 第 899/990 行已建且 `ENABLE + FORCE RLS`），**只新增两个函数** —— `register_sync_probe(...)`（`ON CONFLICT (probe_id) DO NOTHING`，**追加、证据不可改写**）/ `register_daily_coverage(...)`（`ON CONFLICT (device_id, date) DO UPDATE … WHERE c.tenant_id = p_tenant_id AND c.customer_id = p_customer_id RETURNING (xmax = 0)`，**upsert、观测事实可刷新**）；🛑 **两原语幂等形态【刻意相反】是本批核心业务判断**；Core gate（§2.8.7⑤）**第二个落点**：`not_worn` × `is_wear IN (-1,255)`（技术性缺失）不得共存；**跨租户 `probe_id` 撞号必须 `RAISE`**（单列全局主键 ⇒ 必然可达），而 `(device_id, date)` 的跨租户撞号**结构性不可达**（`band` PK 实测为单列 `PRIMARY KEY (band_id)` ⇒ 同一设备只属一个租户）故该 RAISE 属 **fail-closed 防御**；🛑 **不新增 `gap_reason` 枚举值**（缺「结构性」槽位属**契约变更，登记待裁**，V22 只在函数自证消息里**显式打出这条已知缺口**）；🛑 **不实现厂商 SDK / 13 条接口 / 游标翻页**（§2.8.7 明示"文档自证、待真机复核"）；`bandrefetch` 包内**零 HTTP 注解**；门禁 `BandRefetchGateTest` **13** · 反向验证 `verification/125` **23 组/25 用例** · 真库 checksum **1496059203**）
- 迁移 V21：`V21__agreement_offline_signing_provisioning.sql`（**A-1 协议书离线签署通路（批次十九，本轮）**：`register_agreement(...)`（写入 + 幂等 + **plan_version 门禁** + **模板指针成对+版本相符** + `rendered_hash` 复算一致 + 小写 hex）/ `latest_agreement_of(...)`（读侧按 **`signed_at` 全序**取最新，契合 G1 的签署时刻口径）；**跨租户 `agreement_id` 撞号必须 `RAISE`**（推断目标是单列全局主键 ⇒ 跨租户必然发生，且 agreement 是链条**中段**、错状态会被 `CustomerGateGuard` **即时消费**）；🛑 **`agreement` 包内零 HTTP 注解**（H1 `receiveEsignCallback` 被厂商冻结条款逐字禁止）；反向验证 `verification/122` **49/49** —— 一次运行抓出 4 处真实缺陷 **+ 本仓第 45 条系统性缺陷**（PG 正则 `\b` 是退格符而非词边界 ⇒ 6 处否定判据**从未生效**））
- 加密模块：`dy-crypto/`（PIPL/等保字段级加密，独立模块，**B-1 起已由 dy-app 挂接**）
- 审计 DDL：`dy-audit/src/main/resources/db/audit_log.sql`
- **JWT 校验**：`dy-tenancy/src/main/java/com/diaoyuanyun/dy/tenancy/jwt/JwtVerifier.java`
- **租户过滤器**：`dy-tenancy/src/main/java/com/diaoyuanyun/dy/tenancy/context/TenantContextFilter.java`
- **真实 PG 隔离验证**：`verification/`（`01_reset.sql` / `015_apply.sql` / `02_seed.sql` / `03_assert.sql` / `90_break_and_assert.ps1`）
- **反向验证报告**：`verification/REVERSE-VERIFICATION-jwt-2026-09-16.md`
- **ADR-12 合规门禁**：`compliance/`（`scan_compliance.py` / `scan-manifest.json` / `owners.csv` / `wordlists/` / `tests/` / `run-reverse-verification.py` / `README.md`）
- **S1-6 客户端零派生结构门禁**：`compliance/client-zero-derived-gate.py`（四面 + 双根 + 4 值退出码）· `compliance/tests/zero_derived_gate_test.py`（13 断言证人）· 真相源 `contract/visibility/band-visibility-matrix.json` + `contract/openapi-v1.0.0.yaml`（在 `skeleton/` 的**上一级**）· 被验对象 `client-package/api/clientPaths.js`（**14 条**白名单，逐字转录契约；第 45 条缺陷即此处漏 B5）
- **S1-8 契约一致性门禁**：`compliance/contract-conformance-gate.py`（五面 + 自检 + 双根 + 4 值退出码；**真 YAML 解析器**）· `compliance/tests/contract_conformance_gate_test.py`（18 断言证人）· 真相源同 S1-6（OpenAPI + 可见性矩阵）· 被验产物 `client-package/`（8 文件：`api/clientPaths.js` · `errors/clientErrorCopy.js` · `enums/clientSyncState.js` · `constants/clientConstants.js` · `analytics/clientEvents.js` · `i18n/{zh-Hans,en}.json` · `subscribe-messages/templates.json`）
- **S1-9 SDK 表面门禁（批次十八 · B-2/B-4）**：`compliance/sdk-surface-gate.py`（五面：端齐备 / **生成面不得越权** / 作用域覆盖 / **排除声明必须咬合** / 自检；复用 S1-8 的契约读取器，不另写解析器）· `compliance/tests/sdk_surface_gate_test.py`（**7 断言证人**，含 X5「路径数≠方法数必须 exit 2，不许 zip 截断后报 PASS」）· 被验对象 `contract/sdk/{client-mp,therapist-app,admin-web}`（**126 个源文件 / 83 个 operation**）· 实测 `generated=83 = in_scope=83`，**越权 0、缺失 0**；DEAD-ROW REGISTER 逐行登记 43 行的失效理由
- **被扫描面（客户端包通道骨架）**：`client-package/`（`i18n` / `enums` / `constants` / `errors` / `analytics` / `subscribe-messages` / `api`）
- **S2 退款域（契约域 G）**：
  - 领域层 `dy-app/src/main/java/.../app/refund/domain/`（`RefundEntry` 双字面 · `RefundOutcome` · `ApprovalTarget` · `RefundAudienceRole` · `RefundStatementRow` / `RefundReceiptRow` / `RefundOfflineNoticeRow` · `RefundReceiptPort` **仅 5 方法且无 update/delete**）
  - 仓储 `.../refund/repository/RefundWorkOrderLedger.java`（V5/V6 五表读写；`INSERT_STATEMENT_SQL` 的 `recorded_at` 用 **`COALESCE(?, now())`**）
  - 服务 `.../refund/service/`（代录纪律 / 挽留 / 审批闸序 / 两段式回执）
  - 控制器 `.../refund/controller/RefundWorkOrderController.java`（**域 G 五端点** G1~G5）
  - 权限登记 `dy-security/src/main/java/.../permission/PermissionRegistry.java`（`refund:read`/`refund:write`/`refund:approve` 逐条对齐 `x-callable-roles`）
  - **真请求 E2E**：`dy-app/src/test/java/.../refund/RefundDomainGEndpointsE2ETest.java`（真容器 + 真签名 JWT，10 tests）
  - **权限码门禁**：`dy-app/src/test/java/.../security/PermissionCodeRegistrationGateTest.java`（3 tests，跨模块比对 `dy-security`）
  - **反向验证脚本**：`dy-app/src/test/resources/refund-s2-5-reverse-verification.py`（6 真注入 + 1 阴性对照）
  - 迁移 **V6**：`V6__refund_domain_alignment_and_ledgers.sql`（`refund_statement` / `refund_receipt` / `refund_offline_notice` 三账本 + `refund` 补两列）
  - **真库 RLS 隔离**：`RlsV6RefundLedgerIsolationTest`（11 tests）
- **S2-7 判定域（契约域 F）**（🛑 **复用 V5 已建两表，本域不新增迁移** —— `cycle_assessment` / `verdict` 在 V5 已 `ENABLE`+`FORCE RLS`，本域只落读写）：
  - 领域层 `dy-app/src/main/java/.../app/derived/domain/`（`VerdictBranch` 5 值 · `RiskFlag` 4 值 · `Disposition` 5 值 · `VerdictBranchEngine` **D5 前置 → D4 安全优先 → D2 → D3 → D1** + 组合出口 `resolveDisposition` · `VerdictRow` / `CycleAssessmentRow`（读回矛盾拦截）· `VerdictPort` **仅 7 方法，只有 INSERT 与 SELECT**）
  - 仓储 `.../derived/repository/VerdictLedger.java`（V5 两表读写，**append-only**；`INSERT_STATEMENT_SQL` 列清单即形状冻结；`parseEffectOrNull` / `parseAdherenceOrNull` / `parseRiskOrNull` 三个 **`public static`** 可空枚举解析 —— 它们是**缺口②（`risk_flag` 库层无 CHECK）唯一登记的应用层防线**，此前无任何测试执行过，故放宽可见性让边界测试真能调用）
  - 服务 `.../derived/service/`（`VerdictService.java` + `ConfigSeedDerivedProfileSource.java` 提供配置真相源）
  - 控制器 `.../derived/controller/VerdictController.java`（**域 F 两已实现端点**：F1 `POST /cycle-assessments/{id}/verdicts` · F2 `GET /customers/{id}/verdicts`）—— 🛑 **`@StaffOnly` 只贴 F2 的方法**：只有 F2 带 `x-client-explicitly-denied: true`，贴到类级或 F1 就是一次"超出契约的收紧"
  - 权限登记 `dy-security/src/main/java/.../permission/PermissionRegistry.java`（`verdict:read` / `verdict:write`，**只授 `meridian` / `admin`**；`client` / `therapist` / `store_customer_service` 持任何 `verdict:*` 即门禁红）
  - **三测试类**：`dy-app/src/test/java/.../derived/VerdictBranchRoutingTest.java`（**26** tests：五分支 + 组合出口 48 组合穷举 + `REFUND_TERMINATE` 永不在自动路径）· `VerdictControllerContractTest.java`（**12** tests：逐字读契约）· `VerdictPersistenceBoundaryTest.java`（**24** tests：边界 + **六条 fail-closed 补债**，由反向验证 RV-11 逼出）
  - **权限码门禁**：`dy-app/src/test/java/.../security/PermissionCodeRegistrationGateTest.java`（**4** tests，本域新增断言 ④：三类非授权角色不得持任何 `verdict:*`）
  - **反向验证脚本**：`dy-app/src/test/resources/verdict-s2-7-reverse-verification.py`（**16 条真注入，16/16 PASS**；进程级快照 + 逐字节还原校验；GBK 输出解码已修正）
- **S2-8 `threshold_version` 溯源回放（ADR-11）**（🛑 **不新增迁移** —— 指纹是**服务端算出的串**，落进 V5/V6 已有的 `evidence_snapshot` JSONB，**非新列**）：
  - 指纹 `dy-app/src/main/java/.../app/derived/domain/ThresholdVersionFingerprint.java`（**内容寻址**：`"tv1-" + sha256(规范化九段口径串)[0..20)` = 24 字符；`segmentFingerprints` **保序** —— `Collections.unmodifiableMap(new LinkedHashMap<>(...))`，**不可用 `Map.copyOf`**；`matches` 逐字；`driftedSegments` 段级差异）
  - 回放 `dy-app/src/main/java/.../app/derived/service/ThresholdVersionReplay.java`（四态判定；**漂移先于重算**（旧版本号 + 新旧口径恰好同结果 ⇒ 必须报 `DRIFTED` 而非 `REPRODUCED`）；**缺输入 ⇒ `INCOMPARABLE_DOMAIN`**，不得回落 `true` 后臆造分支）
  - 值对象 `dy-app/src/main/java/.../app/derived/domain/ReplayOutcome.java`（enum，四态：`REPRODUCED`/`DRIFTED`/`DIVERGED`/`INCOMPARABLE_DOMAIN`；仅 `REPRODUCED` 的 `isReproducible()` 为真）
  - 值对象 `dy-app/src/main/java/.../app/derived/domain/ReplayResult.java`（record；`driftedSegments` **保序**同修；`customerFacing()` **恒 `false`**）
    - 🛑 **两个值对象刻意住 `derived/domain/` 而非 `service/`** —— `ArchitectureBoundaryTest` 的 **R4（DIP）** 禁止 record / `*.domain..` 依赖 `*.service..`（RV-15 实证：住 service 时全量回归 BUILD FAILURE，而定向 `-Dtest=Threshold*` 看不见）
  - 控制器 `dy-app/src/main/java/.../app/derived/controller/VerdictController.java`（新增内部自描述端点 **`GET /verdicts/{id}/replay`**，`replayVerdict`）
  - **三测试类（合计 38）**：`dy-app/src/test/java/.../derived/ThresholdVersionFingerprintTest.java`（**13** tests：确定性 / 形态 / 九段顺序钉死 / 段级指纹互异 / 四段口径变则版本变 / 段隔离 / `0.80≡0.8` 无伪漂移 / `matches` 逐字 / 不泄漏取值）· `ThresholdVersionReplayTest.java`（**16** tests：四态各一例 + **漂移先于重算** + 无段级指纹如实退化 + 三类非法入参均拒 + 缺输入不可判 + `customerFacing` 恒 false）· `ThresholdVersionPersistenceWiringTest.java`（**9** tests，**由 RV-1/RV-2 逼出的整个类**：三处落库同值 / 入参不一致拒 4001 且**零写入** / 自描述与落库同源 / 快照段级指纹逐项逐序一致）
  - **反向验证脚本**：`dy-app/src/test/resources/threshold-s2-8-reverse-verification.py`（**15 条真注入，15/15 PASS**；进程级快照 + 逐字节还原校验；**须与全量回归串行跑**）
- **S2-9 契约域 A 补全（A2 `GET /auth/me` + A3 `GET /stores`）**（🛑 **不新增迁移** —— 用到的 `store` / `staff` 两表在 **V5** 已 `ENABLE`+`FORCE RLS`，本域只落读写）：
  - 领域层 `dy-app/src/main/java/.../app/identity/domain/`（`BandAudienceRole` · `BandFieldVisibility` · **`BandVisibilityMatrix`**（`#43` 五角色行归一 + `assertHardLocks` / `assertCustomerInvisible` / `assertCustomerVisible` 三向硬锁 + `rowForEndRole` 端角色映射）· `BandVisibilitySource` · `AuthMeDeclaration`（**`refund_visibility`/`store_scope` 为 null ⇒ 键**不出现在响应里**，非 `false`**）· `StoreAnchor` · `StoreRow` · `StoreListPage` · `StoreScopeResolver`（token 声明**比角色默认更宽即抛**；`all ⇒ store_ids=[]` 是显式决定））
  - 仓储 `.../identity/repository/StoreRepository.java`（`filterOf`：`ALL`⇒空条件 / `REGION`⇒`WHERE region_id = ?::uuid` / `OWN_STORE`⇒`WHERE store_id = ?::uuid`；`listByScope` 走 `append(...)` 返回 `Object[]`、`countByScope` 走 `filter.args().toArray()` —— 🛑 **两处调用形态不同，不可互推**，见失效模式 19）
  - 服务 `.../identity/service/`（`AuthMeService.java` · `StoreListService.java` · `ConfigSeedBandProfileSource.java`）—— 🛑 **两个服务里"身份校验先于租户校验"的顺序即语义**（否则无 token 会被 `requireTenant` 抢答成 `2003`/403，违反 A2 只声明 `200`+`401` 的契约）
  - 控制器 `.../identity/controller/AuthMeController.java`（**A2 不贴 `@RequirePermission`、也不贴 `@StaffOnly`** —— 其 `x-callable-roles` **含 client**，而注册表刻意不登记 client；契约 `servers.url: /api/v1` ⇒ Spring 写 `@RequestMapping("/api/v1")` + `@GetMapping("/auth/me")`）· `StoreListController.java`（**A3 贴 `@RequirePermission("store:read")`、不贴 `@StaffOnly`** —— 契约里 A3 是 `x-client-forbidden: false`（未显式禁止），**不等于** `x-client-explicitly-denied: true`）
  - 权限登记 `dy-security/src/main/java/.../permission/PermissionRegistry.java`（`store:read` 登记给 `manager`/`area`/`hq`/`therapist`/`meridian` **五处**，`client` **刻意不登记**）
  - **三个测试类（合计 50）**：`dy-app/src/test/java/.../identity/domain/BandVisibilityMatrixTest.java`（**29** tests，6 @Nested：真实 #43 归一 / **客户侧硬锁双向** / 结构性 fail-closed 五例 / 端角色映射 / 行级解算 / A2 声明装配）· `.../identity/DomainAEndpointsE2ETest.java`（**20** tests，3 @Nested：AuthMe 9 + Stores 9 + ConfigSource 2；**真容器 + 真签名 JWT + 真库**）· `.../security/PermissionCodeRegistrationGateTest.java`（**4 → 5**，新增断言 ⑤ `identity_domain_keeps_client_out_and_auth_me_unannotated`）
  - **反向验证脚本**：`dy-app/src/test/resources/domain-a-s2-9-reverse-verification.py`（**14 条真注入，14/14 PASS**；进程级快照 + 逐字节还原校验；🛑 **预期锚点一律只用 ASCII 方法名** —— Maven 行 GBK 与 surefire fork 断言消息 UTF-8 **在同一份输出里并存**，无单一正确解码策略，见失效模式 13-b；**须与全量回归串行跑**）
- **S2-10 · 契约域 B（B1~B6）关键文件块**：
  - **契约**：`contract/openapi-v1.0.0.yaml` L318~L462（域 B 六行全文，`responses` 声明集逐行决定错误码：B1=`200`+`400`（**无 403/404/500**）· B2=`200`+`403`（**无 404**）· B3=`200`+`403` · B4=`200`+`403`+`404` · **B5/B6 仅 `200`**）+ L829~L862（E5 `POST /band/available-dates`，`x-callable-roles: [client]`，responses `200`/`400`/`422`，**无 403**）
  - **控制器 / 服务 / 仓储**：`.../customer/controller/CustomerController.java`（B1/B2/B3/B6 贴 **`customer:archive`**）· `.../band/controller/BandAvailableDatesController.java`（E5 **刻意不贴任何 `@RequirePermission`**，与 A2 同款）· `.../customer/service/CustomerService.java`（B2 门禁 = **`assertScreeningResult`**，**不是** `assertAdmissionChain`）· `.../customer/domain/CustomerGateGuard.java`（`assertAdmissionChain` 已标 **`@Deprecated`** 并保留为陷阱登记）· `.../customer/repository/CustomerLedger.java`（`UPDATE_PROFILE_SQL` 的 `owner_store_id = ?::uuid`）· `.../customer/domain/IntakeProfileRevisionRow.java`（快照用 **`Collections.unmodifiableMap(new LinkedHashMap<>(...))`**，**不是** `Map.copyOf`）
  - **权限码拆分的落点**：`dy-security/src/main/java/.../permission/PermissionRegistry.java` —— 新码 **`customer:archive`**（= 档案写入，发给契约点名的全部角色含 `therapist`/`meridian`）；**`customer:write`** 保持 = 内容资产写入（仅管理层级与遗留大写码）
  - **可观测性（第 34 条）**：`dy-web/src/main/java/.../exception/GlobalExceptionHandler.java` —— **分级留痕**（5xx `log.error` 带堆栈 / 4xx 业务拒绝 `log.warn` 一行 / 未捕获异常 `log.error` 带完整堆栈），带 `trace_id` + `uri` 便于双向检索
  - **测试类**：`dy-app/src/test/java/.../customer/DomainBEndpointsE2ETest.java`（**21** tests，6 `@Nested`：ScreeningRecords 4 · Customers 5 · Consents 4 · CustomerDetail 5 · IntakeProfiles 2 · SelfDescription 1；**真容器 + 真签名 JWT + 真库**）· `.../rls/RlsV7IntakeProfileRevisionIsolationTest.java`（**12**）· `.../derived/DerivedVisibilityE2ETest.java`（**+1**：合法 client 探测必须 200）· `.../security/PermissionCodeRegistrationGateTest.java`（① 的"必然存在码"清单加入 `customer:archive`）
  - **反向验证脚本**：`verification/93_domain_b_reverse_verification.py`（**7 条真注入，7/7 PASS**；🛑 **Windows 原生 Python 必须用 `mvn.cmd` 绝对路径 + `os.pathsep` 拼 PATH**，见 §4 末条；🛑 **锚点一律纯 ASCII**；🛑 **锚点必须唯一**（>1 次即判 `ANCHOR-AMBIGUOUS`）；🛑 **还原按字节**（二进制快照 + 编译前逐字节比对，防文本模式把 CRLF 翻译成 LF）；**须与全量回归串行跑**；退出时对 **6 个被触碰源文件**做逐字节还原校验）；证据 `verification/94_domain_b_reverse_verification.md`
- **S3-1 · 契约域 C（C1~C3）关键文件块**：
  - **契约**：`contract/openapi-v1.0.0.yaml` L464~L543（域 C 三行：C1 `GET /scale-item-banks`（含 client、`age_group` required 枚举）· C2 `POST /customers/{id}/assessments/baseline`（`BaselineAssessmentRequest`，responses `200`+`422`）· C3 `GET /customers/{id}/assessments/{assessment_id}`（responses `200`+`404`））
  - **领域层**：`.../assessment/domain/BaselineAssessmentRow.java` —— 构造期 fail-closed（8 组分龄锁定 / NOT NULL / 空白 JSON）；`locked()` 恒 true、`legacy` 由服务层显式给 false
  - **仓储**：`.../assessment/repository/AssessmentLedger.java`（`baseline_assessment` INSERT + `scale`/`staff` 存在性 + `scale` 版本反查；🛑 不写 `WHERE tenant_id`，隔离全由 RLS FORCE）
  - **服务**：`.../assessment/service/AssessmentService.java`（C2 顺序：分龄锁定 → UUID → scale 反查 → staff 在职 → 维度分/总分自洽 → migratable 四要素推导 → 落库；C3 双键过滤查无报 404）
  - **控制器**：`.../assessment/controller/AssessmentController.java`（C2 贴 **`assessment:write`**；C1/C3 含 client 不贴码；C3 的 `migratable` 客户**不下发**；🛑 `BaselineRequest` record 不含返回 service 类型的方法——R4 抓过真实违规）
  - **权限码落点**：`dy-security/.../permission/PermissionRegistry.java` —— 新码 **`assessment:write`**（= 基线评估写，发给 therapist/meridian/admin 三端；🛑 不并入 `customer:write`/`customer:archive`，防重蹈第 29 条"一码多义"）
  - **测试类**：`.../assessment/AssessmentDomainCEndpointsE2ETest.java`（**12** tests，3 `@Nested`：QuizList 3 · BaselineSubmit 6 · AssessmentDetail 3；**真容器 + 真签名 JWT + 真库**）· `.../assessment/domain/BaselineAssessmentRowTest.java`（**7**）
  - **反向验证脚本**：`verification/95_domain_c_reverse_verification.py`（**4 条真注入，4/4 PASS**；证据 `verification/95_domain_c_reverse_verification.md`）
- **S3-2 · 契约域 D（D1~D6 全 8 端点）关键文件块**：
  - **契约**：`contract/openapi-v1.0.0.yaml` L565~724（域 D 六行 8 端点：D1 `POST /customers/{id}/visits`（`200`+`403`+`409`）· D2 `GET /customers/{id}/visits`（`200`）· D3/D4 `daily-reports`（D3 `200`+`400`）· D5-a/b/c `plans`（D5-c `200`+`409`）· D6 `device-dispatches`（`200`+`403` GateMissing））
  - **领域层**：`.../fulfillment/domain/FulfillmentGateGuard.java`（四道闸门：禁忌/同意书/协议/方案，缺项 403 GATE_MISSING + 逐字 `missing_items[]`）· `VisitRow`（客户维度全局账本 U2，visit_no）· `DailyReportRow`（source 两值）· `PlanRow`（版本不可覆盖）· `PlanReviewRow`（退回必填 reason）· `DeviceDispatchRow`（失败必填 reason，下行可追责）
  - **仓储**：`.../fulfillment/repository/FulfillmentLedger.java`（visit/daily_report/plan/plan_review/device_dispatch 写读 + 四道闸门 EXISTS；🛑 不写 WHERE tenant_id）
  - **服务**：`.../fulfillment/service/FulfillmentService.java`（D1~D4）+ `PlanService.java`（D5/D6；同人自助二次确认 US-2）
  - **控制器**：`.../fulfillment/controller/FulfillmentController.java`（D1 贴 `fulfillment:write`；D2/D3/D4 含 client 不贴码）+ `PlanController.java`（D5-a/c/D6 贴码、D5-b 不贴码）
  - **权限码落点**：`dy-security/.../permission/PermissionRegistry.java` —— 新码 **`fulfillment:write`**（= 履约写，发给 therapist/meridian/admin）；防"一码多义"第七次复发
  - **测试类**：`.../fulfillment/FulfillmentDomainDEndpointsE2ETest.java`（**6**）· `.../fulfillment/PlanAndDispatchE2ETest.java`（**7**）· `.../fulfillment/domain/{FulfillmentGateGuardTest 7, FulfillmentRowTest 6, PlanReviewAndDispatchRowTest 9}`
  - **反向验证脚本**：`verification/96_domain_d_reverse_verification.py`（D1~D4，**4/4 PASS**）+ `verification/97_domain_d56_reverse_verification.py`（D5/D6，**3/3 PASS**）
- **S3-3 · 契约域 E（E1/E2/E3/E6）关键文件块**（E4/E5 已在 S1-5/S1-7 落地）：
  - **契约**：`contract/openapi-v1.0.0.yaml` L728~L882（域 E 六行：E1 `POST /band/sync-batches`（`200`+`400`+`409`+`429`）· E2 `POST /band/telemetry`（`200`+`400`）· E3 `GET /customers/{id}/band/telemetry`（`200`）· E4 `GET /customers/{id}/band/derived`（仅 staff）· E5 `POST /customers/{id}/band/available-dates`（`200`+`400`+`422`，仅 client）· E6 `GET /customers/{id}/band/sync-status`（`200`，仅 client））
  - **领域层**：`.../band/domain/TelemetryRow.java`（metric 13 值 / is_wear 1/0/-1/255 技术性缺失）· `.../band/domain/SyncBatchRow.java`（四态 `syncing/synced/sync_failed/no_data_today` + 失败必填 reason/action）· `HistoryType`（13 条合法枚举，E5 复用）
  - **仓储**：`.../band/repository/BandLedger.java`（band_telemetry 读写 + band 反查 + band_sync_log 读；🛑 不写 WHERE tenant_id）
  - **服务**：`.../band/service/BandService.java`（E1 内存幂等 24h TTL + 四态校验；E2 双分支幂等键；E3 gap_reason 客户不下发；E6 按 customer 反查 active band）
  - **控制器**：`.../band/controller/BandController.java`（E1/E2/E3/E6 全部含 client，**均不贴码**）
  - **🔴 schema 分叉（已登记待裁）**：契约 §4.1 四态英文 + 触发源英文枚举 vs V5 `band_sync_log`（PRD §4.6 语义）result 两态 + `trigger_source` 中文 CHECK —— E1 暂只契约校验 + 内存幂等受理、**不落 `band_sync_log`**（硬映射会 23514）
  - **测试类**：`.../band/BandEndpointsE2ETest.java`（**6**）· `.../band/domain/BandRowTest.java`（**5**）
  - **反向验证脚本**：`verification/98_domain_e_reverse_verification.py`（**3/3 PASS + 1 OPTIONAL-SKIP**；gap_reason 由全局 `DerivedResponseBodyAdvice` 出口兜底承担）
- **S3-4 · 契约域 I（文书模板 I1/I2/I4/I5/I6/I8）关键文件块**（I3 上传 / I7 下载属文件通道）：
> **2026-09-26 补（B-4）**：I3 / I7 已不再是"待对象存储"，文件块如下 ——
> `.../doctpl/storage/BlobStore.java`（接口，刻意无 `readAll`，I7 是字节流）· `BlobStoreException.java` · `InMemoryBlobStore.java`（`put` 重算 SHA-256 自校验）·
> `.../doctpl/storage/DocFileRef.java`（中文 doc_type 经 `URLEncoder` ⇒ 对象键恒 ASCII）·
> `.../doctpl/service/DocFileService.java`（mime 三值 / ≤10 MiB / 落盘前算 hash / 幂等 4002 / 拒 PE·ELF 魔数 / 新版本 inactive；下载三重校验 = 超管 + 仅 upload 形态 + 读后重算 hash）·
> `.../doctpl/controller/DocFileController.java`（I3 multipart / I7 原始字节流 + `X-File-Sha256`）·
> `DocFileE2ETest`（10）· `DocFileBoundaryGateTest`（7，构建期）· `application.yml` 的 multipart 12MB ·
> dy-web 修复：`IdempotencyBodyCacheFilter` 跳过 multipart、`IdempotencyInterceptor` 对 multipart+Key 显式 400。
> **B-5**：`.../doctpl/esign/EsignEventType.java` + `EsignCallbackPayload.java`（只落契约已冻结的三条；**刻意无控制器**）+ `EsignCallbackContractGateTest`（11）。
  - **契约**：`contract/openapi-v1.0.0.yaml` L1170~L1360（域 I 八行：I1 `GET /doc-templates` · I2 `POST /doc-templates` · I3 `POST /doc-templates/upload`（multipart）· I4 `POST /doc-templates/{id}/versions` · I5 `GET /doc-templates/{id}/versions` · I6 `POST /doc-templates/{id}/versions/{v}/publish` · I7 `GET /doc-templates/{id}/download` · I8 `POST /agreements/{id}/render`（`200`+`403·2004`）；全部 `x-callable-roles: [admin]`，I8 `[]` 内部，契约标 `x-frontier: 占位待冻结`）
  - **领域层**：`.../doctpl/domain/DocTemplateRow.java` —— 版本不可覆盖（version ≥ 1）；doc_type 六值 / source_type 两形态 / mime_type 三值 / file_size ≤ 10 MiB / content 与 file_ref 不同时空
  - **仓储**：`.../doctpl/repository/DocTemplateLedger.java`（`doc_template` 写读 + 版本链按 **doc_type** 归属 + 发布 = 同 doc_type 唯一 active；🛑 不写 WHERE tenant_id）
  - **服务**：`.../doctpl/service/DocTemplateService.java`（I8 渲染四规则：① 白名单 fail-closed ② 越界 2004 ③ phone 前3后4脱敏 ④ 快照+SHA-256；版本递增）
  - **控制器**：`.../doctpl/controller/DocTemplateController.java`（I1~I6/I8 全贴 `doc:write`；I8 无客户端可见面）
  - **权限码落点**：`dy-security/.../permission/PermissionRegistry.java` —— 新码 **`doc:write`**（仅 admin 三端）；**`ErrorCode` 新增 `PLACEHOLDER_OUT_OF_SCOPE(2004, 403)`**（契约 §2.0 权威表自域 I 起含该码）
  - **测试类**：`.../doctpl/DocTemplateE2ETest.java`（**5**）· `.../doctpl/domain/DocTemplateRowTest.java`（**6**）
  - **反向验证脚本**：`verification/99_domain_i_reverse_verification.py`（**3/3 PASS + 1 OPTIONAL-SKIP**；规则①与规则②对"空白名单"双层防线，单层注入不证牙齿）
- **B-7 / B-10~B-13 / A-1 / A-3「零写入方表补齐」七条建档通路关键文件块**（🛑 **共同形态**：均为**不建表、只建 PL/pgSQL 原语**的迁移 + 一组领域层类；**一律无 HTTP 映射**（"运维通路"这一决策自 B-7 起即定），契约端点仍为零，由 `ProvisioningBoundaryGateTest` 机械守护）：
  - **B-7 组织建档（V15，`tenant`/`region`/`store`/`staff` 四张）**：`.../identity/domain/OrgProvisioningPlan.java` · `.../identity/repository/OrganizationProvisioningRepository.java` · `.../identity/service/OrganizationProvisioningService.java`；测试 `.../provisioning/OrganizationProvisioningE2ETest.java`
  - **B-10 手环绑定（V17）**：`.../band/domain/BandBindingOutcome.java`（两态）· `BandBindingRecord.java` · `.../band/repository/BandBindingLedger.java` · `.../band/service/BandBindingService.java`；反向验证 `verification/118_*`（**17/17**）
  - **B-11 设备建档（V18）**：`.../device/domain/DeviceOutcome.java`（`CREATED`/`ALREADY_EXISTS`）· `DeviceRecord.java` · `.../device/repository/DeviceLedger.java` · `.../device/service/DeviceService.java`；门禁 `.../device/DeviceProvisioningGateTest.java`（**9**）· 反向验证 `verification/119_*`（**22/22**）
  - **B-12 量表建档（V19）**：`.../scale/domain/ScaleOutcome.java` · `ScaleRecord.java` · `.../scale/repository/ScaleLedger.java` · `.../scale/service/ScaleService.java`；门禁 `.../scale/ScaleProvisioningGateTest.java`（**9**）· 反向验证 `verification/120_*`（**28/28**）
  - **B-13 结案归档（V20，批次十七）**：`.../archive/domain/`（`CaseArchiveChecklist.java` · `CaseArchiveOutcome.java` · `CaseArchiveRecord.java` · `CaseArchiveSignKey.java` · `CaseArchiveSnapshot.java`）· `.../archive/repository/CaseArchiveLedger.java` · `.../archive/service/CaseArchiveService.java`；门禁 `.../archive/CaseArchiveGateTest.java`（**11**）· 反向验证 `verification/121_*`（**45/45**）
  - **A-1 协议书离线签署（V21，批次十九）**：`.../agreement/domain/`（`AgreementOutcome.java`（`CREATED`/`ALREADY_EXISTS`）· `AgreementRecord.java` · `AgreementSnapshot.java` · `AgreementSignKey.java`）· `.../agreement/repository/AgreementLedger.java` · `.../agreement/service/AgreementService.java`；门禁 `.../agreement/AgreementGateTest.java`（**12**）· 反向验证 `verification/122_*`（**49/49** —— 一次运行抓出 **4 处真实缺陷**：3 处静默假绿 + 1 处缺 (d3)，**外加本仓第 45 条系统性缺陷 `\b`≠词边界**）· G1 取数 `verification/123_g1_agreement_compliance.sql`（**端到端实测 分母 1 / 分子 1 / 100.00%**）
  - **A-3 手环历史补拉（V22，批次二十一）**：`.../bandrefetch/domain/`（`SyncProbeRecord.java` · `SyncProbeOutcome.java` · `DailyCoverageRecord.java` · `DailyCoverageOutcome.java` · `WearState.java`）· `.../bandrefetch/repository/BandRefetchLedger.java` · `.../bandrefetch/service/BandRefetchService.java`；门禁 `.../bandrefetch/BandRefetchGateTest.java`（**12** —— 🛑 曾误写 13，根因是 `grep -c "@Test"` 把 `@TestMethodOrder` 也数进去；权威数须以 surefire 实测为准）· 反向验证 `verification/125_*`（**23 组 / 25 用例** = 23 组注入 + 复绿 + 逐字节还原；13 活库注入 + 10 文件注入；一次运行抓出**第 47 条系统性缺陷** `audit_log.target_id` 指向库中不存在的 id + 一处**真实判据缺口** `ACTION_COVERAGE_REGISTERED` 无断言守）· 上游口径核对 `_work/a3-band-refetch-ruling-request.md`
  - 🛑 **共同回归修复（A-1 顺带沉淀，全批受益）**：SHA-256 口径从 `doctpl/service/DocFileService` **下沉到 `dy-common/crypto/Hashes`** —— 修掉 domain 层反向依赖 service 层（违反 R4）的形态；`DocFileService.sha256` 改为一行委托，口径全仓唯一。
  - **两账门禁（判"代码里到底有没有写入方"）**：`.../provisioning/ProvisioningBoundaryGateTest.java`（**10**，`PROVISIONED` **42** / `NOT_PROVISIONED` **0**）· `.../provisioning/RlsInjectionRealityGateTest.java`（`LEDGER_CARRIERS` 载体登记，现 **23 个**，未登记新载体即报红）
- **三端前端工程骨架（`frontends/`，批次十八 · G-B 的 B-1）**：总览与口径见 `frontends/README.md`
  - **契约驱动端点层（唯一真源 = 冻结契约，机械转录，不手抄）**：`frontends/tools/gen-endpoints.py`
    （`contract/sdk-generator/_cut/<端>.openapi.yaml` → 按 `generator-matrix.yaml` 的 token-roles 过滤
    → 各端 `contract/endpoints.{js,ts}`；`--check` 使"契约改了但产物没重跑"变红）
    ⇒ 三端可用 operation：**client-mp 15** / **therapist-app 29** / **admin-web 39**
  - **端 C 客户小程序**：`frontends/client-mp/`（`project.config.json` + `miniprogram/{app.js,app.json,env.js}`
    + `services/request.js` 走 `wx.request` + 用**生成物**做出站白名单）。
    🛑 其"构建"是零依赖自检（结构 / 契约 / **ADR-12 三面禁用词** / 页面清单）——
    小程序真实产物由微信开发者工具编译，本机无该工具，用通用打包器"产出"小程序目录是假绿
  - **端 B 调理师 / 经络师 APP**：`frontends/therapist-app/`（Vite + React + TS，`src/{env,api,contract}`）
  - **端 A 管理员 Web**：`frontends/admin-web/`（同构）
  - **三端共用自检**：`frontends/tools/build-check.mjs --end=<端>`；退出码沿用 compliance 图例
    （0 通过 / 1 不通过 / 2 配置缺失 / **3 环境受限**。🛑 精确受限面：**同步派生被拦**、
    异步 `spawn` 可用 ⇒ 脚本统一走异步；依赖未就绪时"真实构建"报 `3` 而非跳过。
    契约项若受限则报 ENV-BLOCKED 而非"不一致"，避免把排查引向错误方向）
  - **真实构建证据（2026-09-28 实测）**：三端自检**全部 `exit=0`**；端 A / 端 B 的
    `tsc --noEmit` 与 `vite build` **本机跑通**（端 A `31 modules transformed` / `652ms`）。
    依赖安装命令：`npm install --ignore-scripts`（esbuild 的 postinstall 用同步派生；
    平台包 `@esbuild/win32-x64` 自带 `esbuild.exe`，跳过脚本后构建仍可用）
- **CI 门禁**：`.github/workflows/compliance-gate.yml`
