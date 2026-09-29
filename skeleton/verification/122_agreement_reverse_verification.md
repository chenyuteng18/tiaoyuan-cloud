# 122 · V21「调理协议书离线签署写入通路」反向验证（一次运行抓出三处真实缺陷 + 一条系统性正则缺陷）

> **一句话**：V21 是本仓第五条「不建表、只建 PL/pgSQL 原语」的迁移，承担了
> **闭合 PRD G1「协议签署合规率」取数缺口**这件事（在此之前 `agreement` 是
> **零写入方**的表 ⇒ 分子恒为 0 ⇒ 指标在数学上不可计算）。
> 本脚本用**受控注入**证明 V21 的每一类自证**真的有牙齿**，并在同一次运行里抓出
> **三处真实缺陷**（前两处是**静默假绿**：自证每次都打印"通过"，而判据从未工作）
> 与**一条系统性正则缺陷**（`\b` 在 PG 里是**退格符**，不是词边界）。
> 另抓出 **A-1 自身引入的 2 类真实回归**（domain 反向依赖 service、新载体未登记）。
> 全部已修复：修复后原样自证通过、49 条用例全绿、全量回归 927 例全绿。

## 1. 结论（逐字）

```
===== 122 反向验证结果: 49/49 通过 =====
```

用例矩阵 **49 条** = 34 条注入组（必须报错且**首报错标签精确对上**）
+ 6 条对照组（必须全绿 —— 防"判据收紧后误伤等价形态"）
+ 9 条元门禁（守**载体**本身：C7/C7b/C8/C9/C10/C10b/C11/C12/C13）。

命令：

```bash
python verification/122_agreement_reverse_verification.py
python verification/122_agreement_reverse_verification.py --keep   # 保留临时库排查
```

- 行为验证走 **`diaoyuanyun_dev`**（真库），全部注入包在 `BEGIN … ROLLBACK` 里，**不落地**。
- 等价性验证走临时库 **`diaoyuanyun_agreement122`**（整条迁移链从零重建，跑完即删）。
- 被测文本：`dy-app/src/main/resources/db/migration/V21__agreement_offline_signing_provisioning.sql`。

### 与 118~121 的一处结构差别

**122 的缺陷不在"判据写得对不对"，而在"判据的载体选错了"**（`prosrc` 原文 vs 剥注释代码态）。
⇒ 故 122 比 118~121 多了一整组元门禁（C7/C9/C10）**专门守载体**，而不只是守结论。

## 2. 🛑 本脚本抓出的三处真实缺陷（全部实测）

### 缺陷 ①（核心）：(b5)/(b6) 的载体是**固定长度窗口** ⇒ 被同一函数体内 RAISE 消息的字面量满足 ⇒ 静默假绿

**成因**：`register_agreement` 末尾那条"跨租户撞号"的 RAISE，其给运维看的解释文本里
**逐字**写着 `INSERT ... ON CONFLICT (agreement_id) DO NOTHING 被【主键冲突】拦下`。
而 (b5)/(b6) 的旧载体是：

```sql
substring(v_reg_body from position('INSERT INTO agreement' in v_reg_body) for 3000)
```

**一个没有任何终点语义的固定长度窗口** —— 实测该 INSERT 语句止于函数体的 **2206 字符**处，
而窗口开到 **3000** ⇒ **那条 RAISE 消息被包进了判据的载体**。

**实测**（本脚本 `I(b6-a)` / `I(b6-b)` / `I(b6-c)` / `I(b6-d)`）：

| 注入 | 修复前 | 修复后 |
|---|---|---|
| 把真 INSERT 改成 `ON CONFLICT (tenant_id, agreement_id)`（**错误的**改法，跨租户 RAISE 成死代码） | 判据被 RAISE 字面量满足 ⇒ 自证打印**"通过"**（静默假绿） | 自证报 **(b6)** |
| 把真 INSERT 改成 `ON CONFLICT (agreement_id, tenant_id)` | 同上（静默假绿） | 自证报 **(b6-负向)** |
| 只改真 INSERT、在消息里保留正确推断目标（旧载体的**最小复现**） | 静默假绿 | 自证报 **(b6)** |
| 改成 `ON CONFLICT ON CONSTRAINT agreement_pkey`（**语义等价的正确**写法） | 判据不匹配 ⇒ **假红** | **全绿**（C4 同步守住） |

⇒ 处置（① + ② + ③）：
① 新增 **(b0)** 统一构造**剥注释代码态**（`v_reg_code` / `v_lat_code` / `v_reg_nocomment`），
   并新增 **(b0b)** 断言"构造真的发生了"（非空 / 确实短于原文 / 不含注释残留）——
   因为 `NULL !~ '...'` 的结果是 `NULL`，`IF NULL THEN` **不执行** ⇒ 载体为 NULL 时**整段静态判据静默消失**；
② 语句切片改用**语义终点**（`split_part(..., ';', 1)`），不再是拍出来的 3000；
③ 全部静态形态判据的载体切到代码态（`v_reg_code` / `v_lat_code` / `v_reg_nocomment` / `v_slice`）。

### 缺陷 ②：(c1) 的载体是 `prosrc` 原文 ⇒ 被同一函数体的**注释**满足 ⇒ 静默假绿

**成因**：`latest_agreement_of` 的注释里**逐字**写着
`` -- 🛑 同时写 `tenant_id = p_tenant_id`：让这条 SELECT 的… ``
而旧载体是 `v_lat_body`（`prosrc` 原文，**含注释**）。

**实测**（本脚本 `I(c1-a)`）：
删掉真正的 `WHERE tenant_id = p_tenant_id` ⇒ **(c1) 仍被注释满足 ⇒ 自证照常"通过"**。
（实测计数：注释命中 **1** 处、代码命中 **1** 处，两态都为 `True`。）

⇒ 处置：载体切到 `v_lat_code`（剥注释）。
这与 (b9) 同款的"可读性即安全"纪律，在这里被**注释的存在本身**架空了 ——
注释写了这句话，判据就以为代码做到了。

### 缺陷 ③：自证成功时**不打印任何标记** ⇒ 本脚本的 C1 判据**恒假**（验收不可判）

V20 在 (f2) 之后有一条 `RAISE NOTICE 'V20 自证通过: …'`，V21 初版**没有**。
成功时的 psql 输出是六个句子（`DO / CREATE FUNCTION ×2 / DO / INSERT 0 0 / DO`），
**没有任何一个字说明自证跑过**。

后果（按严重度递增）：

1. **验收不可判**：C1（原样重跑必须全绿）的判据是 `ok and "V21 自证通过" in out`
   ⇒ **恒假** ⇒ 会报一条**归因错误**的红（"自证没通过"），而真相是"通过了但没说话"；
2. **"跑过了"与"根本没跑"同形**：DO 块若被误删/被条件包住，输出**逐字相同**；
3. **运维不可判**：生产上跑迁移的人无法回答"自证过了吗"。
   本仓对 V15/V17~V20 都有这条 NOTICE ⇒ V21 缺它属**纪律不一致**。

⇒ 处置：新增 **(g) 成功标记**，内容是"通过了**什么**"（本域特有项逐条列出），
而不只是四个字。本脚本 `I(g)` 反向钉住：抹掉该 NOTICE ⇒ `"V21 自证通过"` **必须消失**
（证明 C1 的判据真的有判别力，而不是恒真）。

## 3. 🛑 本脚本抓出的一条系统性正则缺陷（影响面超出 V21）

### `\b` 在 PostgreSQL 正则里是**退格符**，不是词边界

**实测**（真库直跑，逐字）：

```
SELECT 'a' || chr(8) || 'b' ~ 'a\bb';      -- true  （\b 被当作退格字符）
SELECT 'agreement ' ~ 'agreement\b';        -- false （\b 不是词尾边界）
SELECT '  UPDATE agreement SET x=1;' ~* '\mUPDATE\M\s+agreement\b';   -- false（恒不命中）
SELECT '  UPDATE agreement SET x=1;' ~* '\yUPDATE\y\s+agreement\y';   -- true
```

⇒ **后果**：V21 的 (b8)/(b10)/(b11) 共 **6 处**静态判据写成 `...\b`，
**全部恒不命中** ⇒ 这三条判据（"不得出现警告门禁结构"、"不得出现 `UPDATE agreement`"、
"不得出现 `DELETE FROM agreement`"）**从来没有工作过**。
其中 (b10)/(b11) 尤其要紧：它们守的是**"本迁移不提供任何改写通路"**这条红线 ——
任何"把协议置为已签/未签"的第二通路**等于**开设一条绕过 H1 的旁路。

**为什么之前没被发现**：这三条都是**否定判据**（"不得出现某写法"）。
否定判据恒不命中 ⇒ **恒为"通过"** ⇒ 与"实现正确"在结果上同形。
这正是本仓最怕的形态：**判据存在、每次都打印通过、但从来没有生效**。

**修复**：6 处全部 `\b` → `\M`（PG 的**词尾边界**），与 V17/V18/V19/V20 的用法对齐
（本仓前四条迁移用的都是 `\M`，只有 V21 误写成 `\b` —— 这本身就是一处**纪律漂移**）。

**本脚本的守卫**：`I(b10)` / `I(b11)` 直接往函数体里注入 `UPDATE agreement` /
`DELETE FROM agreement`，必须被 (b10)/(b11) 抓住。修复前这两条**恒假**；
修复后首报错标签精确对上。

## 4. 🛑 A-1 自身引入的 2 类真实回归（由全量回归抓出，全部已修）

反向验证与门禁测试全绿**不能代替全量回归** —— 本轮 927 例全量回归抓出 4 例失败，
两条根因，都是 **A-1 自己引入的**：

### 回归 ①：`agreement` 领域模型**反向依赖服务层**（违反架构门禁 R4，3 处）

```
Method <...app.agreement.domain.AgreementRecord.recomputedRenderedHash()>
  calls method <...app.doctpl.service.DocFileService.sha256([B)>  (AgreementRecord.java:393)
Method <...app.agreement.domain.AgreementRecord.renderedHashMatches()>
  calls method <...app.doctpl.service.DocFileService.sha256([B)>  (AgreementRecord.java:382)
Method <...app.agreement.domain.AgreementSnapshot.renderedHashRecomputedMatches()>
  calls method <...app.doctpl.service.DocFileService.sha256([B)>  (AgreementSnapshot.java:138)
```

**为什么这是真实回归而不是"门禁太严"**：领域模型一旦依赖服务层，就**无法脱离
Spring 上下文独立测试**（DIP）。A-1 为了做"读侧重算核验"（库里那一行现在还是自洽的吗），
在 domain 里引用了 `DocFileService.sha256`。

**🛑 修法选择（三条路，选了第三条）**：

| 选项 | 评价 |
|---|---|
| ① 给 R4 开例外（把 `agreement.domain` 加进白名单） | ❌ 把纪律降级成装饰 —— 下次同类问题会照样发生 |
| ② 在 domain 里**复制一份** SHA-256 实现 | ❌ 造出**两个各自实现、都自称 SHA-256 的口径**。它们今天等价，明天某一边改了编码/大小写就会分叉，而**没有任何判据会红** |
| ③ **下沉到 `dy-common`**（新建 `common.crypto.Hashes`），`DocFileService` 改为**委托** | ✅ 依赖方向合法（`common` 是最底层可被所有层依赖）、口径仍然**只有一个**、R4 从"被绕过"变成"被更强地满足" |

**落地**：
- 新增 `dy-common/src/main/java/com/diaoyuanyun/dy/common/crypto/Hashes.java`
  （`sha256(byte[])` + `sha256Utf8(String)`，小写 hex；缺算法时**抛异常、绝不回落**）；
- `DocFileService.sha256(byte[])` 保留为**一行委托**（既有调用点无需改动）；
- `AgreementRecord` / `AgreementSnapshot` 改用 `Hashes.sha256Utf8(...)`。

### 回归 ②：`AgreementLedger` 新增 `inTenant` 载体**未登记**

```
以下类新增了 inTenant 载体但未登记 —— 请登记进 RlsInjectionRealityGateTest.LEDGER_CARRIERS
  - dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/repository/AgreementLedger.java
```

⇒ 按 B-13（`case_archive`）同款范式登记，并写明**三处实质差别**：
(a) 缺口的表现形态是"PRD G1 分子恒为 0"；(b) 本批刻意不做 HTTP 映射；
(c) 跨租户撞号有**即时下游消费者**（`CustomerGateGuard` 的
`PLAN_APPROVED → AGREEMENT_SIGNED` 跃迁）。

## 5. 用例矩阵（49 条）

### 注入组（必须报错，且首报错标签精确对上）

| 用例 | 注入 | 期望标签 |
|---|---|---|
| `I(a0)` | 换成 BYPASSRLS 角色跑 | (a0) |
| `I(a1)` | 把 `agreement` 的 RLS 策略改成 `USING(true)` | (a1) |
| `I(a)` | 删掉 `register_agreement` | (a) |
| `I(c)` | 删掉 `latest_agreement_of` | (a) |
| `I(b1)` | 去掉 `set_config('app.tenant_id', ..., true)` | (b1) |
| `I(b2)` | 去掉 `assert_tenant_context()` | (b2) |
| `I(b3)` | 去掉 `current_setting('app.tenant_id')` 一致性守卫 | (b3) |
| `I(b5)` | 从 INSERT 里摘掉 `ON CONFLICT` | (b5) |
| `I(b6-a)` 🎯 | `(agreement_id)` → `(tenant_id, agreement_id)` | (b6) |
| `I(b6-c)` | `(agreement_id)` → `(agreement_id, tenant_id)` | (b6-负向) |
| `I(b6-d)` 🎯 | 只改真 INSERT、消息里保留正确推断目标 | (b6) |
| `I(b9)` | INSERT 不含 `tenant_id`（列与值同步删） | (b9) |
| `I(b7-a)` 🎯 | 从四方键集里去掉 `store_owner` | (b7) |
| `I(b7-b)` 🎯 | 给四方键集加一个第五方（`witness`） | (b7b) |
| `I(b8-a)` 🎯 | 把 V20 的 `v_warn_gate_keys` 结构照抄进来 | (b8) |
| `I(b8-b)` | 代码态里写 `warn_gate` 这个词 | (b8) |
| `I(b12)` | hash 正则退回只收小写 `^[0-9a-f]{64}$` | (b12) |
| `I(b13)` | 删掉 INSERT 里的 `lower(p_rendered_hash)` | (b13) |
| `I(b14)` | 摘掉模板指针成对判定 | (b14) |
| `I(b10)` 🛑 | 给函数体加一条 `UPDATE agreement` | (b10) |
| `I(b11)` 🛑 | 给函数体加一条 `DELETE FROM agreement` | (b11) |
| `I(c1-a)` 🎯 | 删掉 `WHERE tenant_id = p_tenant_id` | (c1) |
| `I(c2-a)` | 去掉 `ORDER BY` 的 tie-breaker | (c2) |
| `I(c2-b)` 🎯 | 排序键 `signed_at` → `created_at` | (c2) |
| `I(c3)` | 去掉 `LIMIT 1` | (c3) |
| `I(d)` | 抹掉迁移登记行 | (d) |
| `I(e4)` | 让方案版本存在性判定恒真 | (e4) |
| `I(e5)` | 摘掉 `plan_version < 1` 的显式判定（落到表 CHECK，报 23514） | (e5) |
| `I(e12)` | 摘掉模板版本相符判定 | (e12) |
| `I(e14)` 🎯 | 幂等判定改成无条件 `RETURN 'ALREADY_EXISTS'` | (e14) |
| `I(e16)` 🎯 | 放宽静态 (c2) 后把排序改成 `created_at`（必须由**行为断言**单独抓住） | (e16) |
| `I(e18)` | 摘掉读函数的上下文一致性守卫（须同时放宽静态 (b3) 读侧） | (e18) |
| `I(g)` 🎯 | 抹掉成功标记 NOTICE | `"V21 自证通过"` 必须消失 |

### 对照组（必须仍然全绿 —— 防"判据收紧后误伤等价形态"）

| 用例 | 形态 |
|---|---|
| `C1` | 原样重跑 V21，且**必须含** `"V21 自证通过"` 字样 |
| `C2` | 两个函数体首尾各加空行 |
| `C3` | DECLARE 段加一个未使用的变量 |
| `C4` 🎯 | 用 `ON CONFLICT ON CONSTRAINT agreement_pkey` 重写真正的 INSERT |
| `C5` 🎯 | `WHERE p_tenant_id = tenant_id`（谓词左右交换，语义等价） |
| `C6` 🎯 | 在注释里写 `v_warn_gate_keys` / `warn_gate` / `_warn_key` |

> 🛑 这三条 `C4`/`C5`/`C6` 本轮**各抓出一处"判据收紧过度"**：
> - `C4`：初版 (b6) 只收一种写法 ⇒ 语义等价的约束名写法被**假红**；
> - `C5`：初版 (c1) 只收一种书写顺序 ⇒ 谓词交换被**假红**；
> - `C6`：注释里出现该词**不该**报红 —— 证明 (b8) 的"剥注释"真的生效。
>
> **共同教训**：断言对象必须是**语义**，不是**某一种语法写法**。
> 假红与假绿同源（都把"写法"误当成"语义"）。

### 元门禁（守**载体**本身，这是 122 相对 118~121 新增的一整组）

| 用例 | 判据 |
|---|---|
| `C7` 🛑🛑 | 自证块内不得有**直接作用在 `prosrc` 原文上的正则判据**（第 44 条缺陷的常驻守卫）。
允许的唯一原文用途只有四种：`length(v_reg_body)` 长度比较、`IS [NOT] NULL` 判断、构造代码态的 `regexp_replace(v_reg_body)`、变量声明与 `prosrc INTO` 取值。 |
| `C7b` | **元门禁自证**：把 (b4) 的载体退回 `v_reg_body` ⇒ `C7` 必须能抓住（证明 C7 不是一条永远为真的判据）。 |
| `C8` | 全部自证失败分支字面量必须都在迁移文本里（防"删掉自证也算通过"）。
🛑 本条本轮抓出 **V21 缺 (d3)** —— `"V21 自证失败(d3)"` 在整个迁移文本里**零命中**。 |
| `C9` 🎯 | 代码态载体必须**从彼此派生**（`v_reg_nocomment ← v_reg_code`），不得各自 `regexp_replace(v_reg_body)`（防"两个不同的量"）。 |
| `C10` 🛑🛑 | 迁移文本里不得出现**自己的美元引用标签字面量**（含注释里）。
🛑 实测会让整条迁移**语法错误**：PG 词法分析在识别注释**之前**就先扫标签 ⇒ 注释里的标签会**提前终止** DO 块自身的美元引用（实测报 `语法错误 在 "\`" 或附近`）。 |
| `C10b` | **元门禁自证**：往注释里塞一个裸标签 ⇒ `C10` 必须能抓住。 |
| `C11` 🎯 | 整条迁移链在**干净库**上应用必须成功（Flyway 真实走的通道；V21 的两条复合外键链只有从零建库才暴露）。
🛑 本轮**替代**了 121 的"改前/改后 DDL 净效果相同"主张 —— V21 **不在 git、不在 flyway**
⇒ 没有机械可证的"改前那一份"，故**不主张**一个没有证据的结论。 |
| `C12` 🎯 | `agreement` 包内不得出现任何 HTTP 映射注解（A-1 硬边界：只做离线运维通路；契约 H1 回调被逐字禁止）。 |
| `C13` 🎯 | `agreement` 包的应用层源码里不得出现 `UPDATE` / `DELETE FROM agreement`（协议是举证材料）。 |

## 6. 🛑 `C8` 抓出的第四处缺陷：V21 缺 (d3)

`C8` 扫源时发现 `"V21 自证失败(d3)"` 在整个迁移文本里**零命中**，而
V18 / V19 / V20 **都有** (d3)。缺它的后果是：

- (d2) 用 `has_function_privilege` 问"当前角色能不能执行"，但本迁移由**应用角色自己执行**
  ⇒ 函数 owner = 调用者，而 owner 对自有函数的 `EXECUTE` 是**隐含**的
  ⇒ **(d2) 在该情形下恒为真**；
- ⇒ 叠加"没有 (d3)"，等于**授权是否落地完全无人验证**：第 2 节的 GRANT 段可被整段删除而自证照常通过。

⇒ 已补 (d3)：断言 `proacl IS NOT NULL` 且存在**显式 ACL 项**（`a.item::text LIKE '%=%/%'`）。
实测真库 `proacl` = `{=X/diaoyuanyun,diaoyuanyun=X/diaoyuanyun}` ⇒ 本条在真库上成立。

## 7. 与 A-1 验收判据的对应（逐条）

| 判据（逐字） | 状态 | 证据 |
|---|---|---|
| ① `ProvisioningBoundaryGateTest` 两账显式改账 | ✅ | `PROVISIONED` 39→40（新增 `"agreement"`）、`NOT_PROVISIONED` 3→2；文案同步 |
| ② 新门禁测试 **≥9 例**全绿 | ✅ | `AgreementGateTest` **12 例**全绿 |
| ③ 反向验证 **≥20 用例**全绿 | ✅ | 122：**49/49** 全绿 |
| ④ 全量回归 `BUILD SUCCESS` | ✅ | dy-app **819** 例 + 其余模块 **108** 例 = **927** 例，0 失败 |
| ⑤ G1 取数 SQL 能返回非空 | ✅ | `verification/123_g1_agreement_compliance.sql`；端到端实测：真走 `register_agreement` → `CREATED` → G1 取数返回**分母 1 / 分子 1 / 合规率 100.00%** |

### 判据⑤ 的"非空"具体指什么

🛑 **不是**"恰好有数据"，而是**口径可执行**：`123` 的第 2 节用**不带 `GROUP BY` 的聚合**，
**恒返回一行**（空库下返回 `分母 0 / 分子 0 / 合规率 NULL`）。

🛑 分母为 0 时**返回 `NULL` 而不是 0%** —— 因为"分母 0 算 0%"是一个**没有依据的结论**；
`NULL` 说的是"这个指标在当前数据下不可计算"，与该域的 NULL 纪律（"NULL 是一等返回值"）一致。

## 8. 真库收口记录（逐字）

```
V21 apply        : EXIT=0
                   DO / CREATE FUNCTION / CREATE FUNCTION / DO / INSERT 0 0
                   注意:  V21 自证通过: 函数 2 / 登记 1 / 代码态载体 4 条（剥注释+压空白+语句切片）
                         / 函数体断言全中 / 建档两态（CREATED→ALREADY_EXISTS）且重放不改写证据
                         / 四方签署逐方缺项皆 RAISE / ... / 探针零残留
                   DO
flyway V21       : checksum = 360963725, success = t   （回写前为 -1738909261，是 V21 被修改前的旧值）
pg_proc          : register_agreement / latest_agreement_of 两个函数均在
proacl           : 两个函数均非 NULL ⇒ (d3) 在真库上成立
agreement 行数   : 0（A-1 只交付通路，不预置业务数据）
```

🛑 **为什么必须回写 checksum**：V21 此前是用 `psql` 直连应用的（那是本仓验收入口），
而 Flyway 通道**看不到**它 ⇒ 下一次 Spring 启动会尝试重跑 V21。
V21 是幂等的（`CREATE OR REPLACE` + `ON CONFLICT`），但 Flyway 会先报
`Detected resolved migration not applied to database`（或 out-of-order）⇒ **启动被拦**。

## 9. 本轮的纪律沉淀（可复用到后续迁移）

1. **载体与断言对象必须同构**：断言"代码有没有某语句" ⇒ 载体必须是**代码态**；
   断言"文档有没有写某口径" ⇒ 载体才是原文。
2. **切片必须有语义终点**：`for 3000` 是拍出来的边界；`split_part(..., ';', 1)` 才有语义。
3. **载体为 NULL ⇒ 判据静默消失**（`IF NULL` 不执行）⇒ 构造代码态**必须自证真的发生了**。
4. **成功必须可观测**：自证通过要**打印标记**，且内容是"通过了什么"，不是四个字。
5. **否定判据要用对词边界**：PG 里 `\b` 是**退格符**，词边界是 `\m` / `\M` / `\y`。
6. **元门禁自己也要被反向验证**（C7b/C10b）—— "判据存在但从不生效"是本轮的主题。
7. **库层做不到的事不要假装做到**：`(c9)` 显式把"能否扫源"移交 122，而不是写一条
   恒真/恒假的查询来充数（"把它做成看起来做了，比不做更坏"）。
8. **反向验证 + 门禁全绿 ≠ 全量回归**：本轮的 2 类真实回归（domain 依赖 service、
   新载体未登记）**只有全量回归**能抓出。
9. **判据收紧过度也是缺陷**：C4/C5 抓出的"假红"与缺陷①②的"假绿"**同源** ——
   都把"写法"误当成"语义"。

## 10. 文件清单

| 文件 | 说明 |
|---|---|
| `verification/122_agreement_reverse_verification.py` | 本脚本（49 用例） |
| `verification/122_agreement_reverse_verification.md` | 本文档 |
| `verification/123_g1_agreement_compliance.sql` | G1 取数 SQL（验收判据⑤） |
| `dy-app/src/main/resources/db/migration/V21__agreement_offline_signing_provisioning.sql` | 被测迁移 |
| `dy-app/src/main/java/com/diaoyuanyun/dy/app/agreement/**` | A-1 产物（domain / repository / service） |
| `dy-common/src/main/java/com/diaoyuanyun/dy/common/crypto/Hashes.java` | 回归①的修法（摘要下沉） |
| `dy-app/src/test/java/com/diaoyuanyun/dy/app/agreement/AgreementGateTest.java` | 门禁（12 例） |
| `dy-app/src/test/java/com/diaoyuanyun/dy/app/provisioning/ProvisioningBoundaryGateTest.java` | 两账（40/2） |

---

**一句话收口**：V21 的三处缺陷与一条系统性正则缺陷，共同构成了一个**完整的"判据失效学"样本** ——
载体选错（①②）、成功不可观测（③）、词边界用错（系统性）。
它们全部满足同一个失败画像：**判据存在、每次都打印通过、但从来没有生效**。
122 的价值不在于"又跑绿了一组测试"，而在于**把这一族失败变成了可机械检出的**
（C7/C8/C9/C10 + 34 条注入用工例各钉一类）。