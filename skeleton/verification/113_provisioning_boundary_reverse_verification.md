# 113 · 组织主数据开通边界门禁 · 反向验证证据

> 日期：2026-09-27 · 批次十三（第五轮）
> 被测门禁：`dy-app/src/test/java/com/diaoyuanyun/dy/app/provisioning/ProvisioningBoundaryGateTest.java`（**6 例**）
> 脚本：`verification/113_provisioning_boundary_reverse_verification.py`
> 结论：**6/6 PASS**（5 组注入全部被抓 + 还原后复绿），4 份被注入文件**逐字节还原**

---

## 一、这个门禁守的是什么（先讲清被测对象）

本轮「还差什么」盘点从**四条互相独立的实测**收敛到同一点：

| 探测面 | 实测结果 | 命令/依据 |
|---|---|---|
| 契约 | 40 个 path 中**无**任何租户/门店/员工开通端点；`/stores` 只有 `GET`（A3 门店列表，只读） | `grep -nE "^  /" contract/openapi-v1.0.0.yaml` |
| 生产代码 | `tenant`/`region`/`store`/`staff`/`device` 五张组织主数据表**零 `INSERT INTO`** | 表×写入方盘点（42 张表） |
| 启动钩子 | 全仓**无** `ApplicationRunner` / `CommandLineRunner` ⇒ 无「启动时自举默认租户」的隐式通路 | `grep -rn "ApplicationRunner\|CommandLineRunner" dy-*/src/main` |
| 真库 | `diaoyuanyun_dev` 中 `tenant`/`store`/`staff`/`agreement`/`customer` **均 0 行** | `psql -c "SELECT count(*) ..."` |

⇒ **把本骨架部署起来，它是个「能跑但空」的系统** —— 登录后拿不到任何门店、员工、客户，
因为让它们存在的那条路径在本期范围内**不存在**。

🛑 **这不是缺陷，是如实登记的已知边界。** 它已在 `DbTenantKekProvider#currentOrProvision`
的 Javadoc 里被逐字写过：

> 本项目当前**没有租户开通流程**（租户由运维/测试直接落 `tenant` 表，
> 全仓 `dy-app/src/main` 与 `dy-config/src/main` 里没有 `INSERT INTO tenant`，
> 也没有任何 `ApplicationRunner` / `CommandLineRunner` 启动钩子）

本类**不新增任何能力**，只把这句散文变成构建期事实。理由两条：
① 它会被**误报成缺陷**（任何人在真库跑冒烟都会看到"客户表是空的"，并合理地怀疑数据层坏了）；
② 它会**静默地被打破**（将来某次变更若顺手加一条 `INSERT INTO store`，边界就被移动而无人察觉）。

## 二、门禁的 6 条判据

| # | 判据 | 守的失效模式 |
|---|---|---|
| ① | 全集（机械读迁移，剥 SQL 注释）= 已开通 ∪ 未开通，两账互斥，不得有第三个 | 新增迁移表而**未表态**开通状态 |
| ② | 登记为「已开通」的 31 张表，代码里**必须真的有** `INSERT INTO` | 账本**骗人**（记账与代码脱节） |
| ③ | 登记为「未开通」的 11 张表，代码里**必须真的没有**写入方 | **边界被移动**（归零必须是一次显式编辑） |
| ④ | 契约 40 path 无开通端点，且 `/stores` 段内只有 `GET` | **只读端点变可写** / 契约被扩张 |
| ⑤ | **元层自证**：`stripComments` 真的剥掉注释（用已知的注释-only 样本自证） | 剥注释失效 ⇒ `tenant` 被**误判成"已开通"** |
| ⑥ | 前提守卫：classpath 上**无 ORM** | 判据前提被推翻（ORM 会隐式写表，字面量扫描扫不到） |

**为什么「表名出现在 SQL 字面量里」是可靠判据**：本仓**无 ORM**（`pom.xml` 里无
`spring-boot-starter-data-jpa` / `mybatis` / `hibernate`；仅 dy-web 引入 `spring-data-redis`），
持久化 100% 走 `JdbcTemplate` + 静态 SQL 字符串 ⇒ 「表名是否出现在源码字面量里」就是
「是否存在写入路径」的**完整**判据。**该前提本身由第 ⑥ 例机械守着** —— 一旦引入 ORM，门禁先红。

## 三、注入表（五组，覆盖每一条判据）

| 组 | 注入 | 期望变红 | 守的失效模式 |
|---|---|---|---|
| **C1** | `StoreRepository` 出站列常量后加一条 `INSERT INTO store`（Java 字符串字面量内） | ③ `..._have_no_writer_in_production` | **边界被移动**（有人加了建门店通路） |
| **C2** | 契约 `/stores` 段的 `get:` 改成 `post:` | ④ `the_contract_exposes_no_provisioning_endpoint` | **只读列表变可写**（凭空长出建门店通路） |
| **C3** | 契约插入新 path `/tenants:` + `post:` | ④ 同上（path 数 40→41 先行红） | **契约被扩张**（开通端点出现 + path 数哨兵） |
| **C4** | 父 `pom.xml` 加 `mybatis` 标记 | ⑥ `no_orm_is_on_the_classpath_...` | **判据前提被推翻** |
| **C5** | V14 迁移里把 `app_config_history` 建表语句改名 | ① `every_migrated_table_is_classified_...` | **账本与代码脱节**（表没了但两账还登记着 ⇒ phantom） |

🛑 **C1/C2/C3 的语义要点**：注入的都是「**语法合法**的变更」，门禁变红**不是**因为变更写错了，
而是因为**边界被移动是必须显式登记的事**。这正是「差异真在」型门禁的牙齿：
**修好即红、须显式移除登记**。C5 同理 —— 它是"登记必须与代码同步"的臂，
与 C1/C2/C3 守的是同一个病的两个方向（假红 / 假绿）。

## 四、执行结果（逐字）

```
[预检 OK ] C1 锚点出现 1 次 :: private static final String SELECT_COLUMNS = "store_id, na
[预检 OK ] C2 锚点出现 1 次 ::   /stores:\n    get:
[预检 OK ] C3 锚点出现 1 次 ::   /audit/signals:\n    get:
[预检 OK ] C4 锚点出现 1 次 :: <java.version>17</java.version>
[预检 OK ] C5 锚点出现 1 次 :: CREATE TABLE IF NOT EXISTS app_config_history

[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）

[C1] 门禁 exit=1 · 期望红 `tables_declared_as_not_provisioned_have_no_writer_in_production` 被抓=True
[C2] 门禁 exit=1 · 期望红 `the_contract_exposes_no_provisioning_endpoint` 被抓=True
[C3] 门禁 exit=1 · 期望红 `the_contract_exposes_no_provisioning_endpoint` 被抓=True
[C4] 门禁 exit=1 · 期望红 `no_orm_is_on_the_classpath_which_is_what_makes_the_literal_scan_sound` 被抓=True
[C5] 门禁 exit=1 · 期望红 `every_migrated_table_is_classified_as_provisioned_or_not` 被抓=True

[复绿] exit=0 · 复绿=True

结论: 6/6 PASS
```

**还原核对**（逐字节）：

```
OK  ../contract/openapi-v1.0.0.yaml                              bytes=81307/81307
OK  dy-app/src/main/resources/db/migration/V14__...sql           bytes=56843/56843
OK  dy-app/src/main/java/.../identity/repository/StoreRepository.java  bytes=13940/13940
OK  pom.xml                                                      bytes=12637/12637
```

## 五、纪律清单（与 100~112 同口径）

- ✅ 锚点预检（存在 + 恰 1 次）—— 5/5 OK
- ✅ 元层判别力自证（基线输出里待查方法名**零出现**）
- ✅ 逐字节还原 + 还原后复绿
- ✅ **锚点优先用单行**（技能 8.7）—— C1/C4/C5 全为单行；C2/C3 为"path 行 + 方法行"两行组合，仍无歧义
- ✅ **注入必须保持语法合法**（112 的教训：改类名 ⇒ 编译失败 ⇒ 红的是编译器不是断言）
- ✅ **must_see 锚点一律 ASCII**（表名 / `mybatis` / `<java.version>`）
- ✅ 只改 dy-app / 契约 / pom ⇒ 只跑 dy-app（被测模块）

## 六、本轮新增的两条教训

### 教训一：C4 的红**发生在 `validate` 阶段，而不是 `test` 阶段**

父 `pom.xml` 的三处 exec 合规门禁绑定在 `<phase>validate</phase>`。
向 pom 注入 `mybatis` 后，红的是与 ORM 完全**无关**的合规门禁（它扫的是词表），
而非我们的第 ⑥ 例 —— 但在**本仓语境下这是正确行为**：引入 ORM 是「应在构建最早期被拦下」的事。

**通用纪律**：**判「门禁有牙齿」时，`exit≠0` 必须与「期望的方法名出现在输出里」同时成立**，
缺后者就只能证明"构建失败了"，不能证明"我的断言真的抓到了这个注入"。
本脚本的 `caught = (code2 != 0) and (expect in got)` 正是这条纪律的机械兑现。

### 教训二：「断言空集 == 空集」型门禁有**双重**退化风险，故需**两侧**注入

- **退化方向 A（假绿）**：目录搬家 / 正则失效 / 注释未剥 ⇒ 扫描面为空 ⇒ 断言平凡通过。
  → 由 ⑤（元层自证）与 ⑥（前提守卫）防。
- **退化方向 B（假红）**：登记表与代码脱节 ⇒ 门禁红得没有信息量、被人习惯性忽略。
  → 由 C5 证明：**登记与代码不同步会立刻红**，使"归零"必须是一次显式动作。

⇒ **只注入一侧（只证明"加了会红"）不足以证明这类门禁可长期依赖**。
必须双向：**加了要红（C1/C2/C3）、删了也要红（C5）、前提变了更要红（C4）**。

## 七、与 N-17 / 112 的呼应

| | N-17（`AuditFillAspect`） | 本项（开通边界） |
|---|---|---|
| 病 | 定义了但**从未接线** | 边界**真实存在**但只在注释里 |
| 危险 | README 把它列为落地物 ⇒ **声明与实现不符** | 会在真库冒烟时被**误报成缺陷** |
| 处置 | 把"接线状态"变成构建期事实 + 反向验证 112（4/4） | 把"边界"变成构建期事实 + 反向验证 113（6/6） |
| 共同点 | **两者都是「注释/文档里的一句话」与「构建期可断言的事实」之间的距离** | |

**本轮沉淀的通用范式**：
> 但凡一处结论只能靠"读注释 / 读 README"才能知道，
> 它就迟早会被误报成缺陷，或被静默打破。
> **能变成构建期事实的，就不要留在散文里。**