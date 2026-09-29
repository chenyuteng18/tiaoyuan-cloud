# A8 反向验证（红→绿）留档索引

> 本目录是 **dy-crypto P0/P2/P1 反向验证证据的永久位置**。
> 由 team-lead 于 2026-09-22 从 `dy-crypto/target/a8-crypto-gate/red-green/` 迁出并独立复核。

## 0. 为什么迁到这里 —— 原位置会被静默删除

原留档位置 `dy-crypto/target/a8-crypto-gate/red-green/` **位于 `target/` 之下**。
`target/` 是 Maven 的构建输出目录，**`mvn clean` 会整体删除**，且本仓库
**没有 `.gitignore`、也不是 git 仓库**（`git check-ignore` → `fatal: not a git repository`）
⇒ 原留档**既不受版本控制保护、也不受构建保护**。

**关键区分（避免过度声称）** —— 同一目录下的文件分两类：

| 类别 | 例子 | `mvn clean` 后 | 是否需迁 |
|---|---|---|---|
| **① 自动生成物** | `reverse-verification-evidence.txt`、`deletion-dag-evidence.txt`、`probe-N.sql`、`p0p2p1-fix.log` | **删除，但重跑 `mvn test` 会再生**（由 `CryptoTestHarness.dumpEvidence(...)` 等测试代码写出） | 不必迁（属正常 Maven 惯例：构建产物在 `target`） |
| **② 不可再生留档** | **本目录 `P0-concurrency-*.txt` / `P1-*.txt` / `P2-*.txt`** | **删除即永久丢失** —— a8 自承这些两态**没有用官方 runner 跑**，是他在**仓库外手工做的一次性实验**留档，**没有任何代码会重新生成** | **必须迁** |

**故本条处置针对的是 ②，不是 ①。** 一次全量 `mvn clean package` 会让本项目
唯一的 P0/P2/P1 反向验证留档**静默消失**，而没人会注意到。

**纪律（精确表述）：不可再生的结论性留档（手工一次性实验、外部验证结论）
一律落在 `verification/<domain>/evidence/`，不得落在任何模块的 `target/` 下。
自动生成的构建证据可留在 `target/`（Maven 惯例，clean 后可再生）。**

## 1. 文件清单

| 文件 | 内容 | 形态 |
|---|---|---|
| `P0-concurrency-red.txt` | P0 并发原子性【红态】：键锁拆掉 → `PASS=34 FAIL=5` | a8 手工采集（无 RUNNER 抬头） |
| `P0-concurrency-green.txt` | P0 并发原子性【绿态】：恢复修复 → `PASS=39 FAIL=0` | a8 手工采集（无 RUNNER 抬头） |
| `P2-nul-collision-red.txt` | P2 NUL 碰撞【红态】：撤 NUL 拒绝 → B7 红 | a8 手工采集 |
| `P1-pom-declaration-red.txt` | P1 pom 声明不实【红态】：假 repo-root → I3 红 | a8 手工采集 |
| `GREEN-runner-form.txt` | 【绿态】官方 runner 输出形态：抬头 + `RUNNER_EXIT=0` | **team-lead 用 `run_crypto_adversarial.sh` 实跑** |
| `README-交付总览.txt` | a8 的完整交付说明（三处修改 + 两态 + Tests run + 自承未做到项） | a8 原文（迁入，未改内容） |
| `INDEX.md` | 本文件 | team-lead |

`GREEN-runner-form.txt` 的用途：补齐 a8 自承的缺口 —— 他未能用官方 runner 跑两态，
故其留档**无 RUNNER 抬头行**。本文件由 team-lead 用官方脚本实跑取得，证明
"官方 runner 形态下绿态同样 exit 0"。

## 2. ★ 红项计数：「6」与「7」不是矛盾，是两个口径

a8 报告里同时出现「6 FAIL」与「此前 7 个红项」两处，易被误读为不一致。**实测四份留档原文**：

| 红态 | 原文统计 | 红项 |
|---|---|---|
| P0 | `PASS=34 FAIL=5 N/A=0 RUNNER_EXIT=1` | G1 / G2 / H4 / H5 / H9 |
| P2 | `PASS=33 FAIL=6 N/A=0 RUNNER_EXIT=1` | **B7** + 上列 5 项（同因） |
| P1 | `PASS=37 FAIL=1 N/A=1 RUNNER_EXIT=1` | I3 |

- **口径 A（单缺陷专属）：6** = `{G1,G2,H4,H5,H9}`（P0 专属，5 条）∪ `{B7}`（P2 专属，1 条）
  —— 即"**每注入一个缺陷，其专属断言必须变红**"的 6 条 `mandatory` 契约。
- **口径 B（红项并集）：7** = `{B7,G1,G2,H4,H5,H9}` ∪ `{I3}` —— 跨三个红态取并集、
  **去重**后的总数。a8 原文的「此前 7 个红项 B7/G1/G2/H4/H5/H9/I3」正是这个并集。
- 两者都自洽：P2 红态之所以是 6 而非 1，是因为该副本**同时保留了 P0 降级**（a8 原文明写"同因"）；
  若 P2 单独注入，红项即只有 B7。

**结论：无矛盾。** `mandatory` 6 条（口径 A）是门禁契约；「7 个红项」是留档叙事的并集（口径 B）。

## 3. 两态总表（口径 A）

| 项 | 注入的缺陷 | 红态 | 绿态 | 专属红项 |
|---|---|---|---|---|
| P0 | 键锁拆掉、改回就地 `add` | `FAIL=5` | `FAIL=0` | G1/G2/H4/H5/H9 |
| P2 | `SubjectRef` 撤掉 NUL 拒绝 | `FAIL=6`（含 P0 叠加） | `FAIL=0` | B7 |
| P1 | 假根重新声明 postgresql + 真库门禁 | `FAIL=1` | `FAIL=0` | I3 |

绿态：`PASS=39 FAIL=0 N/A=0 RUNNER_EXIT=0`（本目录 `GREEN-runner-form.txt` 复现同一结果）。

## 4. 已知局限（不得当作"已全验"）

1. **P1 红态走"假 repo-root"**，未实测"真实 pom 被改坏再改回"那一态（I3 只读 pom 文本 + 扫 `src/test`，判据等价）。
2. ~~**模块自测 `src/test` 零并发代码**（无 `Thread`/`Executor`/`CountDownLatch`）
   ⇒ `Tests run: 23` 全绿 **不等价于**"并发正确"；并发断言**只存在于对抗探针**。~~
   > **已于 2026-09-22 关闭（任务 #68）**：原为快照时点的真实局限，现不再成立。
   > 现况：`dy-crypto/src/test/.../gate/SubjectKeyStoreConcurrencyGateTest`（3 例）已用门控确定性手法
   > 覆盖并发 `getOrCreateDek` / 并发 `rotateDek` / 销毁×并发创建交错；模块计数 **23 → 26**
   > （再因计数锚点守护测试 `DocTestCountAnchorGateTest` +2 例，**当前 28**）。
   > 反向验证：非原子态注入下 **20/20 红**（变体 I）、**15/15 红**（变体 M），绿态 **15/15 全绿**。
   > ⇒ 并发断言**现在同时存在于**模块自测与对抗探针两处，两者不再互相替代。
   > ⚠️ 仍未闭环：该测试的 CME 判据在"就地 `add`"坏实现上恒为 0（无法变红），已在测试内降级为附加观察项；
   > 确定性主判据是 `versionsDistinct`。**本条其余各项（P1 假 repo-root、J1 PASS 语义反向、真库门禁 backlog）不受影响。**
3. **J1 的 PASS 语义与其它条相反** —— PASS = 探针有牙齿，**不能**推断实现正确。
4. `dy-crypto` 的 `b/pom.xml` 声明已删除，但**真库 crypto-shredding 门禁尚未在 `dy-app` 集成测试落地**（backlog）。