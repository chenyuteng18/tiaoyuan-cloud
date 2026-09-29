# 119 · V18「设备建档通路」反向验证（含 3 处真实缺陷的抓出与处置）

> **一句话**：V18 是本仓第二条「不建表、只建两个 PL/pgSQL 原语」的迁移，其自证 (b)(c)
> 有 12 条断言全部是**读 `prosrc` 做正则匹配**。本脚本用**受控注入**证明这些断言
> **真的有牙齿**，并在同一次运行里抓出 **3 处真实缺陷** —— 其中一处
> （**(c4) 判据被函数体注释满足**）此前**从未真正生效**，而自证每次都打印"通过"。

## 1. 结论（逐字）

```
===== 119 反向验证结果: 22/22 通过 =====
```

用例矩阵 **22 条** = 17 条注入组（必须报错且**首报错标签精确对上**）+ 3 条对照组
（等价形态必须仍全绿）+ 4 条元门禁（含 2 条本轮新增的常驻断言）。

命令：

```bash
python verification/119_device_provisioning_reverse_verification.py
python verification/119_device_provisioning_reverse_verification.py --keep   # 保留临时库排查
```

- 行为验证走 **`diaoyuanyun_dev`**（真库），全部注入包在 `BEGIN … ROLLBACK` 里，**不落地**。
- 等价性验证走临时库 **`diaoyuanyun_device119`**（整条迁移链从零重建，跑完即删）。

载入模板：`dy-config` 无参与本脚本；被测文本为
`dy-app/src/main/resources/db/migration/V18__device_provisioning_primitive.sql`。

## 2. 🛑 本脚本抓出的 3 处真实缺陷

### 缺陷 ①：V18 缺 (a0)/(a1) 能力守卫 ⇒ BYPASSRLS 角色下报【归因错误】的红

**复现**（逐字，命令 = 用 `postgres` 跑 V18 全文）：

```
psql:V18__device_provisioning_primitive.sql:1314: 错误:
  V18 自证失败(e5): 租户 B 用【租户 A 已占用的 device_id】建档没有抛错
  （返回值 = ALREADY_EXISTS）。🛑 这是本迁移的核心机制二的封堵点：…
```

这条消息读起来像 **`register_device` 坏了**。真因恰好相反：

```
postgres 的 rolbypassrls = t（实测）⇒ BYPASSRLS 绕过行级安全 ⇒
set_config('app.tenant_id', 租户B) 之后，register_device 里那条
    SELECT count(*) FROM device WHERE device_id = p_device_id
仍然看得见租户 A 的那一行 ⇒ v_mine = 1 ⇒ 判定链落到"是我的"分支
⇒ 返回 ALREADY_EXISTS ⇒ (e5) 报红。
```

即：**函数完全正确，是证据的效力前提（角色受 RLS 约束）不成立。**

🛑 这是本仓反复出现的同一族形态：**判据的适用范围 = 它的锚点范围** ——
V18 依赖 RLS 的判据共 4 条（(e3) 不落库 / (e5) 零行 / (e5) 对照 / (e8) 可见性），
它们的有效性**完全建立**在同一个前提上。没有 (a0) 时，这 4 条会以"函数坏了"的面目
报红，而若有人照字面去改函数，会**改坏一个本来正确的实现**。

**处置**：从 V19 **逐字回补** (a0)/(a1) 两条守卫到 V18 的 guard 开头，并同步第 4 节的
设计说明：

```sql
SELECT rolbypassrls INTO v_bypass FROM pg_roles WHERE rolname = current_user;
IF v_bypass IS NULL THEN RAISE EXCEPTION 'V18 自证失败(a0): 查不到当前角色 % 的 rolbypassrls …' , current_user; END IF;
IF v_bypass THEN RAISE EXCEPTION 'V18 自证失败(a0): 当前角色 % 拥有 BYPASSRLS ⇒ 本自证【依赖 RLS 的 4 条判据全部失去效力】…' , current_user; END IF;
IF (SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename='device') <> 1 THEN RAISE EXCEPTION 'V18 自证失败(a1): device 表上的 RLS 策略数 ≠ 1 个 …'; END IF;
IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='device'
               AND qual LIKE '%app.tenant_id%' AND with_check LIKE '%app.tenant_id%')
THEN RAISE EXCEPTION 'V18 自证失败(a1): device 的策略虽然存在，但其 USING / WITH CHECK 未同时引用 app.tenant_id。 …'; END IF;
```

🛑 **为什么必须查 `rolbypassrls` 而不是 `current_setting('is_superuser')`**：两者不等价 ——
一个**非超级用户**也可以被授予 BYPASSRLS。只查 `is_superuser` 会漏掉
"次超级用户被授予 BYPASSRLS"这一种环境（本机应用角色 `diaoyuanyun` 恰好
`super=off` 且 `bypassrls=off`，两者一致 —— 但那是本机现状，不是一般规律）。

**证据**：修复前 = (e5) 红；修复后 = 应用角色全绿 + BYPASSRLS 角色报 **(a0)**。
用例 `I(a0)` / `I(a1)` 钉住。

### 缺陷 ②：V18 的 (c4) 判据被函数体【注释】满足 ⇒ 并发正确性这条判据一直没在工作

**成因**：PG 把 `AS $tag$ ... $tag$` 之间的内容**原样**存进 `pg_proc.prosrc`，
**注释也在里面**。V18 的 `retire_device` 函数体注释里逐字写着它自己要做的事：

```sql
--     🛑 谓词里带 `status <> 'retired'` 是为了让这条 UPDATE 在并发下**正确**：
```

而 (c4) 的初版判据是裸的：

```sql
v_retire_body !~ 'status\s*<>\s*''retired'''
```

**既无锚点、也没剥注释** ⇒ **删掉代码处那句谓词完全不会被抓住**。

**实测**（`_work/_probe119_comment_counts.py`，已落盘）：

```
V18 (c4) status <> retired           raw=2 code=1   <== 🛑 假绿风险：注释也能满足
V18 (b5) ON CONFLICT                 raw=3 code=2   <== 🛑 假绿风险
```

**实测**（本脚本 `I(c4)`）：

| 注入 | 修复前 | 修复后 |
|---|---|---|
| 删掉回归语里那句 `AND status <> 'retired'`（代码改动，语法仍合法） | 自证打印**"自证通过"**（静默假绿） | 自证报 **(c4)** |

🛑 注意这不是"少写了一个条件"：它意味着**并发正确性这条判据一直没在工作**，
而自证每次都在绿 —— 本仓定义的「**假绿比假红危险得多**」的教科书案例。

### 缺陷 ③：V18 的 (b5) 判据无锚点 ⇒ 2 处代码 + 1 处注释同时命中

`ON\s+CONFLICT` 在 `register_device` 函数体里有 **2 处代码 + 1 处注释**同时命中
（`raw=3 / code=2`）⇒ 连"**哪一处**满足了判据"都说不清。用例 `I(b5)` 钉住。

### 处置 ②③（同一处置，纯 PL/pgSQL，**不产生任何 DDL**）

把 V18 **已有**的"逐行剥注释"机制（原本只用在 (b1)(b2)(b3) 上）**推广到全部语句形态判据**：

```sql
SELECT string_agg(regexp_replace(ln, '--.*$', ''), E'\n')
  INTO v_register_code
  FROM regexp_split_to_table(v_register_body, E'\n') AS t(ln);

SELECT string_agg(regexp_replace(ln, '--.*$', ''), E'\n')
  INTO v_retire_code
  FROM regexp_split_to_table(v_retire_body, E'\n') AS t(ln);
```

再在这些**代码态**上匹配，**同时保留锚点**：

```sql
IF v_register_code !~ 'INSERT\s+INTO\s+device\M[^;]*ON\s+CONFLICT' THEN   -- (b5)
IF v_retire_code   !~ 'UPDATE\s+device\M[^;]*status\s*<>\s*''retired''' THEN -- (c4)
```

🛑 **只剥注释会丢失适用范围，只加锚点仍会被注释命中 —— 两者都需要。**

## 3. 用例矩阵（22 条，全 PASS）

| 组 | 用例 | 期望 |
|---|---|---|
| 注入 | `C1` 原样重跑 V18（含回补的 (a0)/(a1)） | **全绿** |
| 注入 | `I(a0)` 🆕 换成 BYPASSRLS 角色跑 | 首报错 = **(a0)** |
| 注入 | `I(a1)` 🆕 把 `device` 的 RLS 策略改成 `USING(true)` | 首报错 = **(a1)** |
| 注入 | `I(a)` 删掉 `register_device` | 首报错 = **(a)** |
| 注入 | `I(b4)` 把 `INSERT INTO device` 写成 `INSERT INTO "device"` | 首报错 = **(b4)** |
| 注入 | `I(b5)` 🆕 从 INSERT 语句里摘掉 `ON CONFLICT`（语法仍合法） | 首报错 = **(b5)**（旧判据无锚点） |
| 注入 | `I(b7)` 把 RAISE 里的"另一租户"改成"其他租户"（注释里仍有该词） | 首报错 = **(b7)** |
| 注入 | `I(b9)` 让 INSERT 不含 `tenant_id`（列与值同步删） | 首报错 = **(b9)** |
| 注入 | `I(c1)` 把 `UPDATE device` 写成 `UPDATE "device"` | 首报错 = **(c1)** |
| 注入 | `I(c4)` 🎯 **从状态迁移 UPDATE 里删掉 `status <> 'retired'`** | 首报错 = **(c4)**（旧判据**假绿**） |
| 注入 | `I(c5)` 只删状态迁移 UPDATE 的租户维度（步骤(4)的 SELECT 不动） | 首报错 = **(c5)** |
| 注入 | `I(c5-note)` 锚点必要性自证：注入后函数体里**仍含**不带锚点就会命中的 `AND tenant_id = p_tenant_id` | **True**（否则 I(c5) 论证力失效） |
| 注入 | `I(d)` 抹掉迁移登记行 | 首报错 = **(d)** |
| 注入 | `I(d3)` 摘掉授权段 **且** 函数为新建（`proacl IS NULL`） | 首报错 = **(d3)**（`(d2)` 此处恒真） |
| 注入 | `I(e1)` 让 e1 建档的 model 与断言值不符 | 首报错 = **(e1)**（定义段全过） |
| 注入 | `I(e6)` 让 e6 的裸 INSERT 违反 NOT NULL | 首报错 = **(e6)** |
| 注入 | `I(d)` / `I(e1)` 等其余 3 条 | 见脚本 |
| 对照 | `C2` 两个函数体首尾各加空行 | **全绿**（防判据误伤空白形态） |
| 对照 | `C3` `DECLARE` 段加一个未使用变量 | **全绿** |
| 元 | `C4` 各注入组的**首报错标签必须互不相同** | 防"整段只在一处报错" |
| 元 | `C5` 全部失败分支字面量必须都在迁移文本里 | 防"删掉自证也算通过" |
| 元 | `C6a` 改前/改后文本的 **V18 DDL 净效果完全相同** | 见第 4 节 |
| 元 | `C6b` **判别力自证**：往函数体注入一处真实改动 ⇒ 净效果比对必须变红（撤销后恢复） | 防"净效果比对"是装饰 |

**首报错标签**是刻意的设计：断言的是「**谁先抓住**」，而不是「谁提到过」——
`label_of_exact()` 取输出里**位置最靠前**的标签。

🛑 一处前缀陷阱已核对并写入代码注释：`'自证失败(a)'` 是 `'自证失败(a0)'` / `'自证失败(a1)'`
的**前缀** ⇒ `find()` 位置相同 ⇒ 必须按 **`min((pos, -len(lab), lab))`** 取**最长匹配**，
否则 `I(a)` 会被 `I(a0)` 误判。V19 的 `(a)`/`(a0)`/`(a1)` 同款（120 已处置）。

## 4. 🛑 缺陷 ④：门禁载体用【超级用户】应用迁移链 ⇒ (a0) 正确报红（全量回归抓出）

**这不是 V18 的缺陷，而是"V18 的 (a0) 第一次生效后暴露出来的门禁载体缺陷"**
—— 由本轮的 dy-app 全量回归抓出，记录在此因为它是 (a0) 的**直接下游影响**。

**症状**：792 例回归中 1 例 ERROR —— `RlsRejectionAuditTrailGateTest`。
栈顶 `RlsGateSupport.requireZero(:1028)` ← `provisionRealDatabase(:982)`，日志逐字：

```
psql:…V18__device_provisioning_primitive.sql:1481: 错误:
  V18 自证失败(a0): 当前角色 postgres 拥有 BYPASSRLS ⇒
  本自证【依赖 RLS 的 4 条判据全部失去效力】…
```

**真因**：`RlsGateSupport.provisionRealDatabase()` 第 2 步的注释逐字是
「以超级用户应用【被测交付物】的真实迁移链」—— 它**一直用 `postgres` 跑整条链**。
这在 (a0) 出现之前"能跑通"，因为**此前没有任何迁移断言过执行者的角色属性**。

**(a0) 是对的** ⇒ 正确处置是**换角色**，而不是放宽 (a0)、更不是改被测函数。

**处置**（两处，**只动测试载体、不动被测交付物**）：

1. `destructiveResetScript()`：`CREATE DATABASE :dbname;`
   → `CREATE DATABASE :dbname OWNER :rolename;`（库 owner 改为非超级用户应用角色）
2. `provisionRealDatabase()` 第 2 步：`runScript(SUPER_USER, superPassword(), …)`
   → `runScript(APP_USER, APP_PASSWORD, …)`

**实测**：
- 手工验证路径可行：非超级用户（作为库 owner）跑整条链 **EXIT=0**，V18/V19 自证全过；
- 修复后定向门禁（`RlsRejectionAuditTrailGateTest` + `RlsCoverageGateTest` +
  `RlsBEntityIsolationTest`）= **23/23 GREEN**；
- 修复后 dy-app 全量回归 = **796/796 GREEN，BUILD SUCCESS**。

🛑 **教训**：**门禁载体自身的角色选择，也是被测交付物的前提之一。**
一条"从没红过"的载体，可能只是因为**被测物从没断言过它的角色**。
这与 (a0) 防的是同一件事 —— 只不过前者在迁移里、后者在门禁里。

**影响面排查**：全仓只有 `RlsGateSupport`(dy-app) 与 `ConfigGateSupport`(dy-config) 两处
应用过迁移链；后者只应用 config DDL（不含 V18/V19），**不受影响**，未改。

## 5. C6：DDL 净效果等价性（本仓"改迁移文本"的处置依据）

### 为什么需要它

本仓纪律（`verification/117` 文件头）：**迁移文本一改，已应用的库就必须做一次显式处置** ——
要么证明 DDL 净效果相同（⇒ 对齐 checksum），要么承认变了（⇒ 重建库 / 补新迁移）。
**最坏的反应是"把 checksum 改成新值就完事"** —— 那等于用一行 UPDATE 掩盖一个未经验证的断言。

本轮为修上述 3 处缺陷改了 V18 文本 ⇒ 必须给出"净效果不变"的**机械证据**。

### "净效果"的口径（与 118 一致）

指纹 = V18 实际产生/改变的四类东西，恰好覆盖它的第 1/2/3 节
（第 4 节是自证、第 5 节是注释，均无产出）：

1. 函数签名（`proname` + 参数 + 返回类型）
2. **函数体原文**（`prosrc`）—— V18 唯一有内容的产出
3. 函数 `EXECUTE` 权限（`proacl` + `has_function_privilege`）
4. `schema_migration` 中 V18 一行的 `description`

`before` 的取法是**保守**的：`strip_capability_guards()` 只把 (a0)/(a1) 两段**可执行语句**
切掉（**保留全部注释**），长度差 **2611 字符**。若净效果仍相同，
那对"连注释都没有"的完整改前文本也必然相同。

### 本轮实际改动清单（全部落在第 4 节自证）

| 改动 | 内容 | 是否含 DDL |
|---|---|---|
| A | 第 4 节文件头插入 (a0)/(a1) 的设计说明 | 否（注释） |
| B | guard 开头回补 (a0)/(a1) 两条能力守卫 | 否（PL/pgSQL） |
| C | `DECLARE` 增加 `v_bypass` / `v_register_code` / `v_retire_code` | 否 |
| D | 新增两条 `SELECT string_agg(...) INTO v_*_code` | 否 |
| E | (b4)(b5)(b6)(b7)(b8)(b9)(c1)~(c5) 从 `_body` 切到 `_code`，其中 **(b5)/(c4) 新增锚点** | 否 |

⇒ C6a 实测：改前/改后 V18 DDL 净效果**逐字相同**（前提对账通过后据此对齐 checksum）。

### checksum 处置（实测账）

| 版本 | 改前 | 首次回补 (a0)/(a1) 后 | **最终（含 code 化）** |
|---|---|---|---|
| V17 | 587148978 | 587148978（未动） | **587148978** |
| V18 | -103080096 | 1788833408 | **-1553162480** |
| V19 | 1448281196 | 1448281196（未动） | **-1572623912** |

三者已与真库 `flyway_schema_history` 对齐（均 `success=true`）。

🛑 **Flyway checksum 复算算法**（本脚本 `flyway_checksum()` 用同一实现，
故"文本复算 == 库中登记"是一条可机械核对的前提）：
CRC32 逐行（**不含行终止符**）按 UTF-8 编码，取**有符号** int；开头 BOM 需剥离。

## 5. 与 116 / 117 / 118 的分工

| 脚本 | 回答的问题 |
|---|---|
| `116` | V16 迁移**逻辑**是否正确（跨租户引用完整性） |
| `117` | 迁移**文本**与已应用**库**是否一致（防"改了文本没处置库"） |
| `118` | V17 迁移的**自证**是否真的有牙齿（手环绑定通路） |
| **`119`** | **V18 迁移的自证是否真的有牙齿**（本文件）+ 3 处缺陷的处置 |

四者**正交**：119 全绿也可能出现 checksum 失配（本轮就是），而 117 会抓住那件事。

## 6. 判别力自证链（门禁自己也要被证明会变红）

| 层级 | 自证 | 位置 |
|---|---|---|
| V18 迁移内 | (a0)/(a1) 前提守卫（**本轮回补**） | `$v18_guard$` |
| V18 迁移内 | (e) 行为段：建档两态（`CREATED` / `ALREADY_EXISTS`，含跨租户撞号 RAISE） | `$v18_guard$` |
| V18 迁移内 | (f) 探针清场 + 清场自证（**一行业务数据都不许留下**） | `$v18_guard$` |
| 本脚本 | `C6b` 往函数体注入真实改动 ⇒ 净效果比对变红 ⇒ 撤销恢复 | 本文件 |
| 本脚本 | `C4` 首报错标签互不相同（防"整段只在一处报错"） | 本文件 |
| 本脚本 | 前提对账：库中 V18 checksum == 文本复算值 | 本文件（比 118 多一步） |
| 应用层 | `DeviceProvisioningGateTest` / `ProvisioningBoundaryGateTest` 判据 | `dy-app/src/test/…/provisioning/` |
| 载体层 | `RlsInjectionRealityGateTest` 载体集合双向一致 | `dy-app/src/test/…/provisioning/` |

## 7. 本轮留下的可迁移教训

1. **「判据被注释满足」是一种独立于「判据无锚点」的假绿形态**，且更隐蔽
   （自证每次都绿）。处置必须"**剥注释 + 锚点**"**并用**。
2. **「换掉前提再跑一次」是发现"证据效力前提不成立"类缺陷的唯一手段**
   —— 已固化为各迁移的 (a0)/(a1) 与反向验证的 `as_super=True` 用例。
3. **「常驻元门禁」优于一次性检查**：`C7`/`C7b` 把"语句形态判据必须作用在代码态上"
   变成每次运行都成立的机械断言（120 起用）。
4. **Python 侧坑（本轮实测）**：`\M` 在 Python `re` 里是 `bad escape` ⇒
   校验锚点计数时**必须用子串 `count()`** 而非 `re.findall`。
5. **ASCII 双引号坑（第 7 次）**：Python f-string / 中文串里混入 ASCII `"`
   会语法错误 ⇒ 一律用中文引号「」或 `'''`。

## 8. 仍在的边界（诚实）

- 本脚本**不判**"这些自证是否覆盖了全部业务语义"。它只证明"写进迁移的每一类断言，
  都对它声称要保护的那件事有反应"。**没写的断言不在覆盖面内** —— 那些由
  `DeviceProvisioningGateTest`、`ProvisioningBoundaryGateTest`、`RlsCoverageGateTest` 分担。
- `I(e1)` / `I(e6)` 各只覆盖了一类注入。其余行为正确性由 V18 自证 (e1)~(e9) 与
  应用层门禁覆盖；本脚本对它们的职责是"证明 (e) 段整体有牙齿"。
- **(c2) 的反向断言**（`v_retire_code ~ 'DELETE\s+FROM\s+device\M'` ⇒ 报错）在函数体里
  `raw=0 code=0`，属**正确**（函数体里本就没有 DELETE，该断言是"反向断言"），
  非缺陷。已在探针输出中标注"写法差别，需人工确认"。