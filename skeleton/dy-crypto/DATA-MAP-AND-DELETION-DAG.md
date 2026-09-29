# A8 字段级加密 —— 数据地图与删除 DAG（DoD ⑥）

> 出处：`sprint1-kickoff-2026-09-16.md` A 类 A8 行 · 架构规格书 §8.3 / §8.4 · ADR-12 第十二节 · 竞析 E.3 / E.6 / H.2-8 / H.2-9 / H.2-10
> 代码对应：`dy-crypto/src/main/java/com/diaoyuanyun/dy/crypto/shred/DerivedStoreRegistry.java` · `DeletionDag.java`
> 生成日期：2026-09-22

---

## 一、加密对象地图（哪些数据、哪一级密钥、哪一列）

| 数据 | 落点（data-dict） | 字段名（代码登记） | 密钥粒度 | 加密后落库形态 |
|---|---|---|---|---|
| 心率 | `band_telemetry.hr` | `hr` | **per-subject DEK** | `dy1:<算法>:<DEK版本>:<b64 nonce>:<b64 密文>` |
| 静息心率 | `band_telemetry.resting_hr` | `resting_hr` | **per-subject DEK** | 同上 |
| 血氧 | `band_telemetry.spo2` | `spo2` | **per-subject DEK** | 同上 |
| 睡眠分期 | `band_telemetry.sleep_json`（jsonb） | `sleep_json` | **per-subject DEK** | 同上（jsonb 文本整体加密） |
| 步数 | `band_telemetry.steps` | `steps` | **per-subject DEK** | 同上 |
| 运动记录 | `band_telemetry.workout` | `workout` | **per-subject DEK** | 同上 |

**主体定义**：`SubjectRef(tenantId, subjectType, subjectId)`。
租户**必须**参与密钥作用域 —— 同一自然人在不同租户下是两条独立数据，
只用 `subjectId` 会让一家租户的删除请求连带影响另一家。

**字段名登记表**：`field/SensitiveField.java`。
DoD ① 点名的三项（心率/血氧/睡眠）由 `SensitiveField.dodRequiredThree()` 显式给出，
测试按 DoD 措辞取被测对象，而不是自己挑集合。

> ⚠️ **与合规扫描面 3 的关系（勿混淆）**：扫描面 3 词表里那组
> 「forbidden health-metric field names」（`blood_sugar` / `uric_acid` / `blood_pressure` / `body_temp`）
> 管的是**客户端不得出现**（GTL1 SDK 能力溢出）；本表管的是**服务端要加密什么**。
> 两者判据不同，**不得**合并成一份清单。

---

## 二、密钥层级图

```
                    ┌──────────────────────────────┐
   租户级（粗粒度）  │  租户 KEK  kek-<tenant>-vN    │  ← 唯一需要交给 KMS 托管的一层
                    │  （本轮：进程内占位实现）       │
                    └───────────┬──────────────────┘
                                │ 包裹（KEK 加密 DEK）
                                ▼
                    ┌──────────────────────────────┐
   主体级（细粒度）  │  每主体 DEK v1, v2, ...        │  ← 删除权的作用对象
                    │  ADR-12 否决了"仅租户级密钥"    │
                    └───────────┬──────────────────┘
                                │ 加密（DEK 加密字段）
                                ▼
                    ┌──────────────────────────────┐
                    │  心率 / 血氧 / 睡眠 密文        │  ← 密文保留；DEK 一销毁即不可恢复
                    └──────────────────────────────┘
```

**为什么两层**：主体数据加密不需要访问 KMS（只用本地 DEK），DEK 本身被 KEK 保护；
KEK 轮换只需重新包裹 DEK，不必重写全部业务密文。
**为什么 DEK 必须 per-subject**：租户级密钥**无法删除租户内的单个用户**，无法响应 PIPL 删除权（规格书 §8.3，ADR-12 已否决"仅租户级密钥"）。

---

## 三、删除 DAG（顺序即安全性质）

**核心不变式：DEK 销毁必须是最后一步，前置 = 全部衍生存储已确认处理。**

```
① cache                 应用缓存 / Redis                [待人工确认]
② search_index          检索索引                         [待人工确认]
③ analytics_warehouse   数仓 / 指标聚合层                 [待人工确认]
④ application_log       应用日志                         [待人工确认]
⑤ audit_log             append-only 审计日志（⚠️ 冲突）   [待人工确认]
⑥ backup                备份介质                          [待人工确认]
⑦ third_party_processor 第三方处理方                      [外部处理方]
────────────────────────────────────────────────────────────
   全部确认？ ── 否 ──▶ 【拒绝】销毁 DEK；密文仍可读，回执列出待办
                │
                是
                ▼
⑧ primary_db            DEK 销毁 → 全部密文不可恢复        [已确证处理]
```

（原始序见 `DerivedStoreRegistry.all()`；上表按建议的清理顺序编号，`primary_db` 恒为末位。）

**顺序为什么不能反**：先销毁 DEK → 该主体数据在**所有**存储里同时变为不可读 →
清理任务**失去定位手段**（它连"要删哪几行"都读不出来，主体标识本身可能就在加密字段里）→
结果"密钥销毁成功、衍生存储残留"。这是最坏的失败：**合规动作已宣告完成，而数据仍在**。
反过来先清理衍生存储没有任何代价，最后销毁 DEK 是"关掉总闸"，让任何遗漏副本变为不可恢复。

**回执语义**（`DeletionDag.DeletionReceipt`）：
- `completed()==true` 仅在 ⑧ 执行后成立；
- 被拒绝时 `refusalReason` 列出未确认的存储，`pending()` 给出可重试项；
- 处理方**抛异常**一律记为"未完成"（一次网络抖动不得让删除权假完成）。

---

## 四、⚠️ 本轮的真实能力边界（不得含糊陈述）

对外陈述**允许**说：
> "删除权的编排骨架（顺序 + 前置 + 回执）已就位；per-subject DEK 的 crypto-shredding 已验证；
> 衍生存储的清理能力待逐项落地。"

对外陈述**禁止**说：
> ~~"衍生存储已全部自动清理"~~ · ~~"删除权已完整实现"~~ · ~~"已接入生产 KMS"~~

### 逐条如实登记

| 项 | 本轮状态 | 依据 |
|---|---|---|
| per-subject DEK 粒度 | ✅ **已验证**（测试断言两主体 DEK 不同） | `FieldCryptoGateTest.per_subject_means_distinct_deks` |
| 租户 KEK 包裹 DEK | ✅ **已验证**（包裹体不含明文 DEK，可逆） | `dek_is_wrapped_by_tenant_kek` |
| crypto-shredding | ✅ **已验证**（销毁后抛 `SubjectKeyDestroyedException`） | `crypto_shredding_keeps_ciphertext_but_makes_it_unrecoverable` |
| DEK 备份纪律 | ✅ **门已就位**（未就绪即拒绝创建 DEK） | `without_backup_readiness_dek_creation_is_refused` |
| 算法可替换位 | ✅ **骨架就位**（信封带算法位；换实现零改调用点） | `swapping_the_algorithm_implementation_requires_no_caller_change` |
| SM4 是否强制 | ⚠️ **未确认** —— 代码中登记为 `PENDING_CONFIRMATION`，一引用即 fail-closed，**不写死** | ADR 十五 **H.4-1** |
| **生产 KMS 对接** | ❌ **未接入** —— 当前为进程内占位实现，仅测试用 | Sprint1 §Checklist（KMS 为 A8 前置，暂无则先以本地可替换接口占位） |
| **墓碑抗回滚** | ❌ **未落地** —— 默认进程内实现不具备抗回滚能力 | `ShredTombstoneStore` 类注释；反向验证 ② 已证明其承重性 |
| **衍生存储清理** | ❌ **未落地** —— 6 项待人工确认，1 项为外部处理方 | `DerivedStoreRegistry.pendingCount()` |
| **审计日志与删除权的口径冲突** | ❌ **待合规方裁定** | ADR-09 append-only vs PIPL 删除权，见 ⑤ 行；裁定请求件见 `_work/audit-appendonly-vs-pipl-erasure-request-2026-09-22.md` |
| **验收证据口径（退出码属弱证据）** | ⚠️ **已实测并登记** —— `dyc.exit` / `dycrypto.exit` / `final.exit` 为**不可配对**（前两者为不一致名、后者为超窗），退出码不得单独引用；验收以汇总计数 + 逐测试类明细为准；另有 `NO_TEST_EVIDENCE` 档（有 `BUILD SUCCESS` 但无任何 Tests 计数 ⇒ 证据不足，不算通过） | 见 **§六**（含成因、配对双判据、证据分级、可重复的校验工具 `dy-crypto/tools/verify-exit-evidence.py`） |
| **并发原子性（P0）** | ✅ **已修复并反向验证** —— 读-判-写全部收进**同一把键锁**（`bySubject.compute`），value 恒为不可变 List、整体替换；墓碑检查在锁内重做；`destroySubjectKey` 的"记墓碑+删材料"也在同一把锁内。**红态可复现**：把键锁拆掉 ⇒ G1/G2/H4/H5/H9 五项全红 | 见 **§6.6**（红→绿两态留档 `verification/crypto/evidence/red-green/`） |
| **作用域键 NUL 碰撞（P2）** | ✅ **已修复并反向验证** —— `SubjectRef` 构造期【拒绝】含 NUL 的分量（NUL 是作用域键拼接分隔符，允许则两个不同主体共用一把 DEK、删除权互相误伤）。**红态可复现**：撤掉拒绝 ⇒ B7 红 | 见 **§6.6**（`P2-nul-collision-red.txt`） |
| **pom 依赖声明** | ✅ **已订正** —— 曾声明 `postgresql`(test) 并称"只有真库 crypto-shredding 门禁用它"，而 `src/test` 零 JDBC 代码，属"声明的能力比实际多"。依赖已删除，并登记 backlog：真库 crypto-shredding 门禁应落在 `dy-app` 的集成测试 | 见 **§6.6**（`P1-pom-declaration-red.txt`，I3） |

### 为什么"墓碑"是关键缺口

批量登记里最容易被低估的是**备份**与**墓碑**。设想：

1. T0：某主体 DEK 被创建，且按 DoD ④ 被认真备份（正是我们要求的）；
2. T1：该主体行使删除权 → 销毁在线存储的 wrappedDek；
3. T2：一次运维事故后，从 T0 的备份恢复数据库。

若无"已销毁"的痕迹，T2 之后该主体数据**又变得可读** —— 删除权被一次备份恢复**静默撤销**。
墓碑必须落在**不受该次回滚影响**的介质（ADR-09 的 append-only 审计日志，或 WORM 存储）。
本模块提供 `ShredTombstoneStore` 显式表达这一要求，但**默认实现是进程内的，不具抗回滚能力**。

---

## 五、与其它模块的接口边界

| 谁 | 怎么用 | 备注 |
|---|---|---|
| 业务写入路径 | `FieldCipher.encryptText(subject, fieldName, plaintext)` | 调用点**不含任何算法字面量**（有结构性测试守护） |
| 业务读取路径 | `FieldCipher.decryptText(subject, fieldName, envelope)` | 按信封里的 DEK 版本 + 算法位取实现；**不自动创建密钥** |
| 删除权入口 | `DeletionDag.execute(ref, handler)` | handler 由业务侧提供（各衍生存储的清理能力） |
| 未来 KMS | 实现 `TenantKekProvider`，用 `AlgorithmRegistry.of(...)` 装配 | 业务路径零改动 |

**依赖方向**：`dy-crypto` **不依赖** `dy-common` / `dy-tenancy` ——
加密层是"谁能读数据"的最后一层，让它不被上层模块的依赖图牵连，才能保持可独立审计。
当前**无模块依赖** `dy-crypto`（尚无调用点接入）。

---

## 六、⚠️ 验收证据口径（退出码属**弱证据**，不得单独引用）

> **本节回答"这个模块的验收到底以什么为依据"。写在这里的理由**：本模块此前的验收材料里同时存在**日志**与**退出码**两类证据，而**退出码这一类存在配对缺陷**，若不写明，下一位复核者会把弱证据当强证据用。

### 6.1 验收依据（唯一）

| 依据 | 内容 | 为什么可信 |
|---|---|---|
| **① 汇总计数**（主） | `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0` | 该行由 **Surefire 在被测代码同一次 fork JVM 生命周期内打印**，不经过那层会挂起的包装进程；且多次独立运行计数**完全一致** |
| **② 逐测试类明细**（主） | `DeletionDagGateTest` 5 例 + `FieldCryptoGateTest` 19 例 + `SubjectKeyStoreConcurrencyGateTest` 3 例 + `DocTestCountAnchorGateTest` 2 例 | 可按类名逐一核对，支持"哪一类红/绿"的定位 |
| **③ 反向验证注入证据** | `verification/crypto/evidence/a8-gate/reverse-verification-evidence.txt` | 注入错误后测试必须变红（三个反向验证：错误 KEK 不能解密 / 墓碑承重 / 篡改必被检出） |
| **④ 对抗探针红→绿两态** | `verification/crypto/evidence/red-green/`（含 `INDEX.md`） | 把 P0/P2/P1 的缺陷**故意注入回去**，同一批断言必须变红；恢复后转绿。用于证明"门禁有牙齿"（见 §6.6） |
| **⑤ 包装进程退出码**（**弱**） | `.exit` 文件 | ⚠️ **见 6.2** —— **不得单独作为验收通过的依据** |

<!-- ★ 机器锚点（勿删）：本模块单元/门禁用例总数 应为 **29**。
     格式约定：`应为 **N**` 是本仓库文档计数锚点的唯一可解析形态（全文只允许出现这一处）。
     解析者（就在本模块内，见下一段"为什么守护测试放在 dy-crypto 而不是 dy-app"）：
       dy-crypto/src/test/java/com/diaoyuanyun/dy/crypto/gate/DocTestCountAnchorGateTest.java
     —— 它把本锚点与「dy-crypto/src/test 下 @Test 方法数」比对，**不等即红**，
     并同时核对 verification/crypto/README.md 的同类锚点。

     演进（每一步都必须改本行，这正是该守护测试存在的理由）：
       23（原始）→ 26（任务 #68 新增 SubjectKeyStoreConcurrencyGateTest 3 例）
       → 28（新增 DocTestCountAnchorGateTest 2 例 = 本守护测试自身，
              故锚点值【包含守护测试自己】）
       → 29（2026-09-23 修复 FieldCryptoGateTest 的概率性假红，为该判据加装
              反向验证 ⑥ reverse_6_literal_scan_would_catch_a_plaintext_passthrough
              1 例 ⇒ FieldCryptoGateTest 18 → 19。
              详见 verification/crypto/README.md 相邻说明与
              dy-crypto/src/test/.../FieldCryptoGateTest#assertEnvelopeHides 的 javadoc）。

     ⚠️ 注意：锚点被改成解析不出来的样子（例如删掉「应为 **N**」）同样判红 ——
     "解析不到"按失败处理，不按通过处理。

     🛑 为什么守护测试放在 dy-crypto，而不是像最初设想的那样放在 dy-app：
     “dy-app -am” 的 reactor 【不含 dy-crypto】（root pom 明写 dy-crypto 刻意不进入
     dy-app 依赖链：`当前无人依赖它`）。放 dy-app 会导致本守护【根本读不到】
     dy-crypto 的计数变化 —— 那是一条恒绿的空门禁。故落在 dy-crypto 自己，
     以“源码级 @Test 计数”作主判据（它在编译期已定，不受测试执行顺序影响）。 -->

> ⚠️ **判读本模块验收时，必须区分三种"通过"形态 —— 它们互不等价**：
> **①日志出现 `BUILD SUCCESS`** ≠ **②汇总计数全绿** ≠ **③进程退出码 = 0**。
> 本模块的验收**只认 ②（+ ②的逐类明细）**；**只有 ① 与 ③、没有 ② 的运行，等于"什么都没证明"**（见 §6.5）。

### 6.2 为什么退出码是**弱证据**（实测结论）

**成因**：本机 Surefire 的 fork JVM 卡在 shutdown hook（`WinNTFileSystem.delete0(Native Method)` ← `TempFileManager.deleteAll`；主线程 WAITING 于 `Thread.join` ← `ApplicationShutdownHooks.runHooks`）。**Maven 打印完 `BUILD SUCCESS` 之后**，包装进程仍可能挂住数十秒、并以非 0 退出。

**两条实测（互不蕴含，两个方向都出现过）**：

| exit 文件 | 值 | 配对判定 | 对应日志有 `BUILD SUCCESS`？ | 计数 |
|---|---|---|---|---|
| `dycommon.exit` | 0 | ✅ 配对成立（同名且间隔 0.13s） | 有 | 10/0/0 |
| `dyc.exit` | 0 | ❌ **UNPAIRABLE**（**`dyc.log` 不存在**；目录里只有 `dyc3.log` —— **名字就对不上**，其 2.20s 只是"最近邻间隔"，与判定无关） | **无（日志被截断：有汇总行、无 SUCCESS/FAILURE）** | 23/0/0 |
| `dycrypto.exit` | **1** | ❌ **UNPAIRABLE**（**`dycrypto.log` 不存在**；目录里只有 `dycrypto2.log`） | 有 | 23/0/0 |
| `final.exit` | 0 | ❌ **UNPAIRABLE**（距 `final.log` **718.63s**） | 有 | 23/0/0 |

> 🛑 **本表「计数」列是 2026-09-22 快照时点的实测值，已按纪律保持原样未改（改它等于伪造证据）。**
> 其中 `dyc` / `dycrypto` / `final` 三行都是 **dy-crypto 模块**当时的计数 **23/0/0**。
> **当前值为 29/0/0** —— 任务 #68 新增 `SubjectKeyStoreConcurrencyGateTest`（3 例），
> 计数锚点守护测试 `DocTestCountAnchorGateTest`（2 例），以及 2026-09-23 为
> `FieldCryptoGateTest` 的概率性假红修复加装的反向验证 ⑥（1 例）；计数由 23 抬到 29。
> 本表**所有配对判定结论（哪几份 UNPAIRABLE、为什么）不受该数字变化影响**：
> 它们判的是「同名」与「时间窗」两个条件，与计数无关。
> 引用本表时**不得**把 `23/0/0` 当作"当前模块计数"——当前计数见 §6.1 的机器锚点。

⇒ **「日志出现 BUILD SUCCESS」与「进程退出码 = 0」在本机互不蕴含**：
- **有 SUCCESS 却退出码非 0**：`dycrypto` 那次（23/0/0 全绿 —— ⚠️ **该数字为快照时点值，当前为 29/0/0**，见上方注记；包装进程退出码为 1；**但该退出码本身不可配对，仅作"曾出现过非 0"的观察，不得引用为证据**）；
- **有汇总计数却无 SUCCESS 行**：`dyc` 那次（日志被截断，止于汇总行）。

**配对缺陷的根源（必须写明，否则会重犯）**：上述 `.exit` 文件**不是与日志在同一条命令里捕获的**（正确形态应为 `cmd > log 2>&1; rc=$?; echo "EXIT=$rc" > exit`）。它们中有的是**事后按时间接近度探测落盘**。

⇒ **判据是两条都要满足**，缺一即 **UNPAIRABLE**：

| # | 条件 | 不满足的实测样本 |
|---|---|---|
| **① 同名** | exit 与 log 必须同名（`dycommon.exit` ↔ `dycommon.log`） | `dyc`（同名 log 不存在，只有 `dyc3.log`）、`dycrypto`（只有 `dycrypto2.log`） |
| **② 时间窗** | `exit.birthtime − log.mtime` ≲ 2.5s | `final`（718.63s，属事后探测） |

**⇒ 因此：`dyc.exit`、`dycrypto.exit`、`final.exit` 三份均为「不可配对」，其退出码属弱证据，不计入"已采集的退出码"。四份里只有 `dycommon.exit` 可用。**

> **本表于 2026-09-22 由 A8 自检订正**：初版曾把 `dyc.exit` 判为"配对成立（间隔 2.20s）"——那是**只看时间窗、漏看「同名」**的结果。补校验器时以「同名」为必要条件复算，`dyc.log` 根本不存在，故该行必须改判为 UNPAIRABLE。**只满足时间接近、名字对不上，仍是事后探测。** `dyc.exit` 与任何日志**都不存在可引用的配对**（`dyc3.log` 只是名字最接近的一份，不是它的日志）。

### 6.3 可重复的校验工具

配对校验不是一次性人工判断，已落成脚本（含三个反自证）：

```
dy-crypto/tools/verify-exit-evidence.py --dir <证据目录>
```

它以 `exit.birthtime − log.mtime` 判配对（默认窗口 2.5s），超窗标 **`UNPAIRABLE`**；**同名日志不存在也标 `UNPAIRABLE`**（名字对不上 ⇒ 同链捕获不成立）；值与日志矛盾则判 **`FAIL`** 并指出是哪一对、差多少、日志事实如何。**`--pair` 形态会先自动去找同名 log** —— 显式指定输入的判决**不比自动发现更弱**（否则矛盾会被吞掉）。

**退出码：0=可引用；1=存在矛盾（`FAIL`）；2=存在不可配对（`UNPAIRABLE`）或无测试证据（`NO_TEST_EVIDENCE`）。**

### 6.4 ⚠️ 一条必须记住的纪律（防误用）

> **本机退出码会出现"假红"（flaky red），不会出现"假绿"。**
> 4 次独立运行计数完全一致（23/0/0）⇒ 被测代码与测试是**稳的**，不稳的只是包装进程退出码。
> ⚠️ **那 4 次是 2026-09-22 快照时点的运行，计数 23/0/0 为时点值（当前 29/0/0，见 §6.1）**。
> 本条的推理**不依赖具体数字**：它靠的是"4 次运行的计数**彼此一致**"这一事实（稳定性），
> 而非"等于 23"。新增并发门禁与计数锚点守护测试后该稳定性结论**仍然成立**，
> 且已被 3 例并发门禁的多轮复跑再次验证。
> **但"假红"同样有害** —— 一个会随机返回 1 的退出码，会诱使团队给验收门禁加跳过参数（本项目已有 "只红不绿的检查会被人直接关掉" 的先例）。
> **⇒ 严禁**把包装进程退出码直接当作 CI 门禁的通过条件；**应判的是日志内的汇总计数**。
> **⇒ 同时**：`exit≠0` 但日志含 `BUILD SUCCESS`**且有汇总计数**的情形**不得判红** —— 校验工具对此判 `NOISY_PASS`，正是为了不让环境噪声被误读成代码缺陷。（注意限定词"且有汇总计数"：只有 `BUILD SUCCESS`、没有计数行的，见 §6.5，那是**证据不足**，不是噪声。）

### 6.5 ⚠️ `NO_TEST_EVIDENCE` —— 有 `BUILD SUCCESS` 却**没有**任何 Tests 计数行

**形态**：日志里能搜到 `BUILD SUCCESS`（或 `BUILD FAILURE`），但**一条 `Tests run:` 汇总行都没有**。

**为什么它不是"通过"**：这正是 **`-DskipTests` / 测试阶段被跳过**的形态 —— Maven 照样打印 `BUILD SUCCESS`，进程照样以 0 退出，但**没有任何测试被跑过**。它只能证明「进程以某码退出 + Maven 打印过结论行」，**不能证明任何测试通过**。

**处置**（已在校验工具中实现）：

| 情形 | 判定 | 计入"可引用"？ |
|---|---|---|
| 有 SUCCESS/FAILURE + **无**任何 Tests 计数行（无论 exit 为 0 或非 0） | **`NO_TEST_EVIDENCE`** | ❌ **否** |
| 有 SUCCESS + **有**计数行 + exit≠0 | `NOISY_PASS`（环境噪声，见 §6.4） | ✅ 是（计数为其背书） |
| 有 SUCCESS + **有**计数行 + exit=0 | `PAIRED_OK` | ✅ 是 |
| 有计数行但无 SUCCESS/FAILURE（截断） | `TRUNCATED` | ⚠️ 仅证"进程 0 退出"，不证"构建成功" |

**⇒ 纪律**：**`NO_TEST_EVIDENCE` 属"证据不足"，既不算通过、也不算矛盾** —— 遇到它必须回到"这一次到底跑了哪些测试类"，而不是拿 `BUILD SUCCESS` 交差。

### 6.6 ⚠️ 反向验证（P0/P2/P1）—— "门禁有牙齿"的证据形态

> **本节回答"凭什么说上面那些断言是承重的、不是摆设"，以及"凭什么说这三处修复真的修好了"。**

**方法**：把缺陷**故意注入回去**，看同一批断言是否**变红**；再恢复，看是否**转绿**。两态用**同一份探针源码**编译，保证可比。

**留档位置**：`verification/crypto/evidence/red-green/`（含 `INDEX.md` 索引与口径说明）

> ⚠️ **2026-09-22 位置订正**：本留档原先写在 `dy-crypto/target/a8-crypto-gate/red-green/`。
> 该路径在 **`target/` 之下 —— `mvn clean` 会整体删除**，而本仓库**无 `.gitignore`、非 git 仓库**
> ⇒ 一次全量 `mvn clean package` 就会让这些**不可再生**的留档（手工一次性实验，无代码会重新生成）
> **静默消失**。已迁至上述永久位置。
> **纪律：不可再生的结论性留档一律落在 `verification/<domain>/evidence/`，不得落在任何模块的 `target/` 下；
> 自动生成的构建证据（如本表 ③，由 `dumpEvidence` 在 `mvn test` 时写出）留在 `target/` 属正常 Maven 惯例。**

| 项 | 缺陷 | 红态（注入回去） | 绿态（已修复） |
|---|---|---|---|
| **P0** | 并发原子性（`key/InMemorySubjectKeyStore.java`） | `P0-concurrency-red.txt` —— **G1/G2/H4/H5/H9 五项全红** | `P0-concurrency-green.txt` —— 五项全绿 |
| **P2** | 作用域键 NUL 碰撞（`envelope/SubjectRef.java`） | `P2-nul-collision-red.txt` —— **B7 红**（共用同一 DEK=true；销毁 x 后 y 由可读变不可读） | 同一探针下 B7 PASS |
| **P1** | pom 声明不实（`dy-crypto/pom.xml`） | `P1-pom-declaration-red.txt` —— **I3 红**（声称有真库门禁、test 树零 JDBC） | 同一探针下 I3 PASS |

**"注入"只在【仓库外】副本上进行** —— 共享树的 `src/main`、`pom.xml`、`target/classes` 全程零改动（三份修复态文件的 md5 在红绿循环前后完全一致）。P1 的注入走"假 repo-root"（在 `/tmp` 下造一个重新声明 postgresql 的 `dy-crypto/pom.xml`），因此**不动机器上任何共享 pom**。

**红态关键数字**（与 team-lead 独立实测一致）：

| 断言 | 红态实测 | 含义 |
|---|---|---|
| G1 | 全部 12 线程进入创建路径=true；**不同 DEK 数=12**；`wrappedOf(c,1)` 还原不出所取那把 | 同一主体并发下生成 12 把 DEK ⇒ 历史密文按版本解不开 |
| G2 | **回读成功=1/12**；回读失败(认证类)=11 | 并发写入的 12 条密文，11 条永久不可读 |
| H4 | 两线程各建 v1；**不同 DEK 数=2**；`wrappedOf(c,1)` 还原出的那把不在其中 | 最小确定性复现：读-判-写不原子 |
| H5 | **版本号集合=[1]**（大小 1，应为 8）；不同 DEK 数=8；v1 还原出的不是上报的那把 | 同一版本号对应 8 把 DEK |
| H9 | 期间墓碑已记=true；放行后"创建成功"；**销毁后仍有活跃密钥材料=true（liveKeyCount=1）** | 删除权被静默撤销：**墓碑在，材料复活** |
| B7 | **共用同一 DEK=true**；销毁前 y 可读=true → **销毁后 false** ⇒ 可读性不变=false | 删除权误伤另一个主体 |
| I3 | pom 声称 postgresql 为『真库门禁用』=true；含 JDBC/真库代码的文件=**[]** | 声明的门禁不存在 |

> **B7 的判据要点（勿写错）**：误伤判据是「销毁 x 前后 **y 的可读性不变**」，**不是**「y 仍可读」—— 后者在"二者本就共用 DEK"时**恒为假**，无法区分"被误伤"与"本就共享"。

**⇒ 门禁有效性自证（探针新增 `J1`）**：探针用一个内嵌的"旧节奏替身"（与修复前的 `InMemorySubjectKeyStore` 同构）跑同一套门控手法，结果 `版本号种类数=1 / 不同 DEK 数=8` —— 与 H5 的红态数字**一致**。这条证据的意义是：**证明 G1/H5 的断言一旦跑在未修版本上必然变红**，即"门禁不是摆设"。

**⚠️ 一个已于 2026-09-22 关闭的盲点（保留原文以便追溯）**：本段原先写着 —— `dy-crypto/src/test` 下**零并发代码**（无 `Thread`/`Executor`/`CountDownLatch`，也无 `rotateDek` 调用），因此 P0 的"版本号重复 / 按版本号取回失败"断言**只存在于对抗探针 `verification/crypto/`**，模块自测抓不住 P0，把并发断言补进模块自测属于 backlog。

> **该盲点已关闭（任务 #68）**：新增 `SubjectKeyStoreConcurrencyGateTest`（3 例，`dy-crypto/src/test/.../gate/`），用**门控确定性**手法覆盖并发 `getOrCreateDek` / 并发 `rotateDek` / 销毁×并发创建交错三条路径。此后**模块自测本身**就抓得住 P0：
> - 反向验证（仓库外注入非原子态）：**变体 I 连跑 20/20 红**、变体 M **15/15 红**，绿态 **15/15 全绿**；
> - 模块计数随之由 **23 → 26**（再因本文件的计数锚点守护测试 +2 例、2026-09-23 的假红修复反向验证 +1 例，当前为 **29**，见 §6.1 与 `verification/crypto/README.md`）。
>
> 因此上面那句话的**当前**版本应读作：并发断言**同时存在于**模块自测与对抗探针两处，二者不再互相替代（刻意分开计数，避免实现者用例与验证者探针混进同一个 `Tests run`）。
>
> ⚠️ **仍未闭环的部分（不得省略）**：`SubjectKeyStoreConcurrencyGateTest` 的 CME 判据在"就地 `add`"的坏实现上**恒为 0、无法变红**（已实测：变体 M 连跑 15 次 CME 全为 0；256 条版本链加强版下仍为 0）。该判据已在测试代码内**显式降级为附加观察项**，确定性主判据是 `versionsDistinct`。真库 crypto-shredding 门禁应落在 `dy-app` 集成测试，**仍属 backlog**（与"真库 crypto-shredding 门禁应落在 `dy-app` 集成测试"同批）。