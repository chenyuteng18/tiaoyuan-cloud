# 118 · V17「手环绑定通路」反向验证（含 4 处真实缺陷的抓出与处置）

> **一句话**：V17 是本仓第一条「不建表、只建两个 PL/pgSQL 原语」的迁移，其自证 (b)(c)
> 有 14 条断言全部是**读 `prosrc` 做正则匹配**。本脚本用**受控注入**证明这些断言
> **真的有牙齿**，并在同一次运行里抓出 **4 处真实缺陷**（两处假绿、一处恒真、一处类型错误）。

## 1. 结论（逐字）

```
===== 118 反向验证结果: 17/17 通过 =====
```

用例矩阵 **17 条** = 10 条注入组（必须报错且**首报错标签精确对上**）+ 3 条对照组
（等价形态必须仍全绿）+ 4 条元门禁（含 DDL 净效果等价性与其判别力自证）。

命令：

```bash
python verification/118_band_binding_reverse_verification.py
python verification/118_band_binding_reverse_verification.py --keep   # 保留临时库排查
```

- 行为验证走 **`diaoyuanyun_dev`**（真库），全部注入包在 `BEGIN … ROLLBACK` 里，**不落地**。
- 等价性验证走临时库 **`diaoyuanyun_band118`**（整条迁移链从零重建，跑完即删）。

## 2. 🛑 本脚本抓出的 4 处真实缺陷

### 缺陷 ①：(b7) / (c7) 的判据**没有锚点** ⇒ 假绿

初版正则（`bind_band` 的换机路径 / `unbind_band` 的解绑路径）：

```sql
v_bind_body   !~ 'AND\s+tenant_id\s*=\s*p_tenant_id'
v_unbind_body !~ 'AND\s+tenant_id\s*=\s*p_tenant_id'
```

而两个函数的**步骤 (4) 各有一条 SELECT**，里面逐字含同样的子句：

```sql
bind  :  WHERE id = p_customer_id AND tenant_id = p_tenant_id;
unbind:  WHERE band_id = p_band_id AND tenant_id = p_tenant_id;
```

⇒ **实测**（`_work/v17_b7c7_specificity_probe.sql`，已落盘）：

| 注入 | 旧判据 | 新判据 |
|---|---|---|
| 单独删掉【换机 UPDATE】的租户维度 | `t`（**没抓住**） | `f`（抓住了） |
| 单独删掉【解绑 UPDATE】的租户维度 | `t`（**没抓住**） | `f`（抓住了） |

即这两条断言对「它们本要保护的那条语句」**完全没有反应** —— 一条在"任何状态下都返回
`t`"的实现同样能让它们全绿。

**处置**：判据**从 `UPDATE band` 起锚、以 `;` 为界**（`[^;]*` 不跨语句）：

```sql
v_bind_body   !~ 'UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id'
v_unbind_body !~ 'UPDATE\s+band\M[^;]*tenant_id\s*=\s*p_tenant_id'
```

**教训**：**判据的适用范围 = 它的锚点范围。** 一个"文内任意位置出现过这句话"的断言，
证明不了"这句话出现在该出现的那条语句里"。

### 缺陷 ②：(a) 在"只缺一个函数"时炸成**与 (a) 无关**的错误

```
错误:  有缺陷的数组常量:"bind_band"
DETAIL:  数组值必须以 "{" 或者维度信息开始。
```

成因：`v_missing` 声明为 `text[]`（且带 `:= ARRAY[]::text[]` 默认值），而 `string_agg()`
返回 `text` ⇒ 赋值时隐式 cast ⇒ 单元素串被当成数组字面量解析。

⇒ 这是"**判据本身也有缺陷**"的形态：它想报 `(a)`，却报了一条没人能读懂的错误。
更坏的是那个 `:= ARRAY[]::text[]` 默认值 —— 它让 `(a)` 在 NULL 情形下退化成
"空数组 ⇒ 永远不报错"，却**不解决**单缺。

**处置**：`v_missing` 改 `text`，判空改 `IS NOT NULL`。用例 `I(a)` 钉住。

### 缺陷 ③：(d2) 在按本仓脚本建的库上**恒真、判别力为零**

**实测**：注入 `REVOKE ... FROM current_user` 之后，自证**仍然打印"自证通过"**。

成因：迁移由应用角色自己执行 ⇒ 两个函数的 **owner 就是 `current_user`** ⇒ owner 对自有
函数的 `EXECUTE` 是**隐含**的，`has_function_privilege` 永远为 `t`，且 `REVOKE` 撤不掉它。

**实测 `proacl`**（本机 PG 17.11 / `diaoyuanyun_dev`）：

```
proacl = =X/diaoyuanyun | diaoyuanyun=X/diaoyuanyun
```

- `=X/diaoyuanyun` = **PUBLIC 的 EXECUTE**（PG 对函数的默认授权）
- `diaoyuanyun=X/diaoyuanyun` = 第 2 节 GRANT 段显式授的那一条

**处置**：新增 **(d3)** —— 断言 `proacl IS NOT NULL` 且**含显式 ACL 项**，即"第 2 节授权段
真的执行过"。用例 `I(d3)` 钉住（`REVOKE ALL ... FROM PUBLIC, current_user` 后 `proacl`
仍有项，故只有"**删掉授权段 + 函数为新建**"才是 `proacl IS NULL` 的真实形态）。

🛑 **只做处置而不改注入，就等于"用一条没被证明会变红的断言去修一条恒真断言"** ——
故 `I(d2/d3)` 与 `I(d3)` 两条用例都在。

### 缺陷 ④：(d3) 里的 `aclitem ~~ unknown`

```
错误:  操作符不存在: aclitem ~~ unknown
HINT:  没有匹配指定名称和参数类型的操作符. 您也许需要增加明确的类型转换.
```

`aclitem` 类型**没有 `LIKE` 运算符**，`unnest(proacl)` 的元素必须先 `::text`。

## 3. 用例矩阵

| 组 | 用例 | 期望 |
|---|---|---|
| 注入 | `I(a)` 删掉 `bind_band` | 首报错 = **(a)** |
| 注入 | `I(b)` 把 `bind_band` 换成空壳（签名对、没建上下文） | 首报错 = **(b1)** |
| 注入 | `I(b6)` 摘掉 `ON CONFLICT` 的 `WHERE status='active'` | 首报错 = **(b6)**（运行期错误前移到迁移期） |
| 注入 | `I(b7)` 🆕 **只删换机 UPDATE 的租户维度**（步骤(4) SELECT 不动） | 首报错 = **(b7)**（旧判据此处假绿） |
| 注入 | `I(c)` 把 `unbind_band` 换成删除实现（仍含 `UPDATE band`） | 首报错 = **(c2)**（解绑必须是状态迁移） |
| 注入 | `I(c7)` 🆕 **只删状态迁移 UPDATE 的租户维度** | 首报错 = **(c7)**（旧判据此处假绿） |
| 注入 | `I(d)` 抹掉迁移登记行 | 首报错 = **(d)** |
| 注入 | `I(d2/d3)` 摘掉授权段效果 | 首报错 ∈ {(d2), (d3)} |
| 注入 | `I(d3)` 🆕 摘掉授权段 **且** 函数为新建（`proacl IS NULL`） | 首报错 = **(d3)** |
| 注入 | `I(e3)` 把"客户已有有效带子"改成 `CREATED` | 首报错 = **(e3)**（**定义段全过**，只有行为段能抓） |
| 对照 | `C1` 原样重跑 V17 | **全绿**（含修复后的 (b7)/(c7)） |
| 对照 | `C2` 两个函数体首尾加空行 | **全绿**（防判据误伤空白形态） |
| 对照 | `C3` `DECLARE` 段加一个未使用变量 | **全绿** |
| 元 | `C4` 各注入组的**首报错标签必须互不相同** | 防"整段只在一处报错" |
| 元 | `C5` 全部失败分支字面量必须都在迁移文本里 | 防"删掉自证也算通过" |
| 元 | `C6a` 改前/改后文本的 **V17 DDL 净效果完全相同** | 见第 4 节 |
| 元 | `C6b` **判别力自证**：往函数体注入一处真实改动 ⇒ 净效果比对必须变红（撤销后恢复） | 防"净效果比对"是装饰 |

**首报错标签**是刻意的设计：断言的是「**谁先抓住**」，而不是「谁提到过」——
`label_of()` 取输出里**位置最靠前**的标签。

🛑 一处前缀陷阱已核对并写入代码注释：`'自证失败(d)'` **不会**被 `'自证失败(d2)'` 误命中
（前者要求字面量 `(d)` 右括号紧跟 `d`，而 `(d2)` 里 `d` 之后是 `2`；已实测 `find() = -1`）。

## 4. C6：DDL 净效果等价性（本仓"改迁移文本"的处置依据）

### 为什么需要它

本仓纪律（`verification/117` 的文件头）：**迁移文本一改，已应用的库就必须做一次显式处置** ——
要么证明 DDL 净效果相同（⇒ 对齐 checksum），要么承认变了（⇒ 重建库 / 补新迁移）。
**最坏的反应是"把 checksum 改成新值就完事"** —— 那等于用一行 UPDATE 掩盖一个未经验证的断言。

本轮为修上述 3 处缺陷改了 V17 文本 ⇒ 必须给出"净效果不变"的**机械证据**。

### "净效果"的口径

🛑 本脚本**不**做 `pg_dump` 全库对账（那是 117 做的事）。原因：117 对账的是
"已应用库 vs 重建库"，两侧都含 V8 以**数据**形式插入的 `schema_migration` 登记行等差异源。
而本脚本要回答的问题窄得多：**V17 这一条文本的两份版本，客观效果是否相同**。

⇒ 指纹定义为 V17 实际产生/改变的四类东西，恰好覆盖它的第 1/2/3 节
（第 4 节是自证、第 5 节是注释，均无产出）：

1. 函数签名（`proname` + 参数 + 返回类型）
2. **函数体原文**（`prosrc`）—— V17 唯一有内容的产出
3. 函数 `EXECUTE` 权限（第 2 节 GRANT 段的效果）
4. `schema_migration` 中 V17 一行的 `description`（第 3 节登记段的效果）

`before` 的取法是**保守**的：把两处新判据还原为旧判据（**注释差异不回滚**）。
若净效果仍相同，那对完整的改前文本也必然相同。

### 独立证据（三重，互不依赖）

| 证据 | 内容 | 结果 |
|---|---|---|
| ① 117 ⑤ | 已应用库 vs 当前链条重建库的模式对账 | **6/6**（42 表 / 516 列 / 251 约束 / 179 索引 / 38 策略 / 8 函数 / 17 登记行；`pg_dump --schema-only` 归一化后逐字节相同） |
| ② 直接取证 | 库中 `pg_proc.prosrc` 与当前文本 `$v17_bind$…$v17_bind$;` 切片**逐字相同** | `bind` 5633 字符 ✅ / `unbind` 2276 字符 ✅ |
| ③ 编辑范围 | 本轮**全部**编辑都落在 `$v17_guard$`（第 557–1120 行）内 | 纯自证块，**零 DDL** |

⇒ 依此执行 Flyway `repair`（= 对齐 checksum 的语义），从 **1930304913** 对齐到 **587148978**。

### 🛑 repair 的一个操作坑（已实测，值得记住）

Flyway 10.10.0 的 `repair` **只清"失败的迁移"**，**不会**自动对齐"成功迁移"的 checksum。
要在本地仓库找到迁移文件，必须给**绝对路径**的 `filesystem:` location：

```bash
mvn -o -pl dy-app org.flywaydb:flyway-maven-plugin:10.10.0:repair \
  -Dflyway.url="jdbc:postgresql://localhost:5432/diaoyuanyun_dev" \
  -Dflyway.user=diaoyuanyun -Dflyway.password=diaoyuanyun \
  -Dflyway.locations="filesystem:<absolute path>/dy-app/src/main/resources/db/migration"
```

实测的三种 location 结果：

| location | 结果 |
|---|---|
| `classpath:db/migration` | `No failed migration detected`（**未对齐**，且不报错） |
| `filesystem:src/main/resources/db/migration` | `Skipping filesystem location: … (not found)`（相对路径不解析） |
| `filesystem:<绝对路径>` | ✅ `Repairing Schema History table for version 17 … Checksum: 587148978` |

并且**必须**再跑一次 `mvn -pl dy-app process-resources` —— 否则 `target/classes` 里还是旧版
迁移文件（实测 `target/classes` 的 checksum 仍是 1930304913），下一次测试启动会读到旧文本。

## 5. 与 116 / 117 的分工

| 脚本 | 回答的问题 |
|---|---|
| `116` | V16 迁移**逻辑**是否正确（跨租户引用完整性） |
| `117` | 迁移**文本**与已应用**库**是否一致（防"改了文本没处置库"） |
| **`118`** | **V17 迁移的自证是否真的有牙齿**（本文件）+ "改判据后 DDL 净效果不变"的证据 |

三者**正交**：118 全绿也可能出现 checksum 失配（本轮就是），而 117 会抓住那件事。

## 6. 判别力自证链（门禁自己也要被证明会变红）

| 层级 | 自证 | 位置 |
|---|---|---|
| V17 迁移内 | (e) 行为段：7 态实测（`CREATED`/`ALREADY_BOUND`/`CUSTOMER_ALREADY_HAS_ACTIVE_BAND`/`REPLACED`/`UNBOUND`/`ALREADY_UNBOUND`/`NOT_FOUND`） | `$v17_guard$` |
| V17 迁移内 | (f) 探针清场 + 清场自证（**一行业务数据都不许留下**） | `$v17_guard$` |
| 本脚本 | `C6b` 往函数体注入真实改动 ⇒ 净效果比对变红 ⇒ 撤销恢复 | 本文件 |
| 本脚本 | `C4` 首报错标签互不相同（防"整段只在一处报错"） | 本文件 |
| 应用层 | `BandBindingGateTest` **7 例**（含 **①g 判别力自证**：`purgeCustomerBands` 后同语句必须**重新**变回 `23503`） | `dy-app/src/test/…/band/BandBindingGateTest.java` |
| 账本层 | `ProvisioningBoundaryGateTest` **8 例**（含判据⑧：手环绑定走运维通路、**不暴露 HTTP**） | `dy-app/src/test/…/provisioning/` |
| 载体层 | `RlsInjectionRealityGateTest` 载体集合**双向一致**（本轮登记第 20 个载体 `BandBindingLedger`） | `dy-app/src/test/…/provisioning/` |

## 7. 复现清单（本轮全部实测命令）

```bash
# 1) 反向验证（17/17）
python verification/118_band_binding_reverse_verification.py

# 2) 模式对账（6/6，含 checksum ⑤）
python verification/117_migration_chain_schema_reconciliation.py

# 3) V16 反向验证（9/9 —— 确认 B-10 改账未破坏它）
python verification/116_cross_tenant_reference_reverse_verification.py

# 4) 应用层门禁（7 例）
mvn -o -pl dy-app test -Dtest=BandBindingGateTest -Dsurefire.failIfNoSpecifiedTests=false

# 5) 全量回归（776 例）
mvn -o -pl dy-app test
```

逐字结果：

```
118: ===== 118 反向验证结果: 17/17 通过 =====
117: ===== 117 模式对账结果: 6/6 通过 =====
116: ===== 反向验证结果: 9/9 通过 =====
dy-app: Tests run: 776, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
RLS 门禁 execution: Tests run: 108, Failures: 0, Errors: 0, Skipped: 0
其余 7 模块: dy-common 10 / dy-tenancy 39 / dy-security 48 / dy-web 58
             / dy-audit 37 / dy-config 29 / dy-crypto 29 —— 全 GREEN
```

## 8. 仍在的边界（诚实）

- 本脚本**不判**"这些自证是否覆盖了全部业务语义"。它只证明"写进迁移的每一类断言，
  都对它声称要保护的那件事有反应"。**没写的断言不在覆盖面内** —— 那由
  `BandBindingGateTest`、`ProvisioningBoundaryGateTest`、`RlsCoverageGateTest` 分担。
- `I(e3)` 只覆盖了四态中的一态（`CUSTOMER_ALREADY_HAS_ACTIVE_BAND` 被改成 `CREATED`）。
  其余三态的**行为正确性**由 V17 自证 (e1)~(e9) 与 `BandBindingGateTest` 判据③覆盖；
  本脚本对它们的职责是"证明 (e) 段整体有牙齿"（`I(b)`/`I(b6)`/`I(c)` 三条从定义侧进入）。
- C6 的"净效果"口径是**本脚本自定义的四项**，不等于"全库模式"。全库模式的等价性由 **117** 保证。