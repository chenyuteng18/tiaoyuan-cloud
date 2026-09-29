# 批次九 · 反向验证证据：排除项 × 配置真相源【交叉自证】（N-3 收口）

**脚本**：`verification/107_excluded_items_cross_check_reverse_verification.py`
**门禁**：`ThresholdVersionFingerprintTest#excluded_items_are_cross_checked_against_the_verdict_config_namespace`（新增）
**结果**：**总体 PASS 4/4**（P1/P2/P3 全部被抓 + 还原后复绿），逐字节还原一致。

---

## 一、被登记的事实（一个真实漏项）

`cfg:verdict.*` 命名空间在配置真相源（`dy-config/src/main/resources/db/config/02_slots_seed.sql`）里**恰有 4 个槽位**：

| 槽位 | key | 指纹九段是否消费 | `EXCLUDED_NOTE` 是否点名 | 结论 |
|---|---|---|---|---|
| #9 `branch_rules` | `cfg:verdict.branch_rules` | ❌ 否 | ❌ **否（漏项）** | 🛑 **两侧都不在** |
| #30 `formula_params` | `cfg:verdict.formula_params` | ❌ 否 | ✅ 是 | 已显式排除 |
| #33 `mcid_threshold` | `cfg:verdict.mcid_threshold` | ✅ 是（`mcid` 段） | —（已消费，无需排除） | 已纳入 |
| #45 `confidence_formula` | `cfg:verdict.confidence_formula` | ✅ 是（`confidence` 段） | —（已消费，无需排除） | 已纳入 |

**漏项形态**：#9 `branch_rules` **既不在九段里、也不在排除说明里**。

这正是 N-3 要防的形态 —— **"『有意识地不纳入』与『忘了纳入』在复盘时无法区分"**。
在 N-3 收口前，若有人问"#9 为什么不算进指纹？"，答案只能靠**人回想**；
而"没人想过它"与"想过并决定不纳入"在**文档上长得一模一样**。

> 注：#9 的"不纳入"是**当时正确的判断** —— `EXCLUDED_NOTE` 前半段已说明
> `branch_rules` 的五字面与 `VerdictBranch` 枚举逐字一致，且分支顺序/条件判断 `D1~D5`
> 写死在 `VerdictService` 内（"已声明但运行时未消费"）。
> **本轮改的不是判断，而是"判断没有被钉住"这件事。**

---

## 二、断言机制（为什么这条能抓到"两侧都不在"）

新增断言 `excluded_items_are_cross_checked_against_the_verdict_config_namespace` 的**核心等式**：

```
cfg:verdict.* 全集（机械读 seed 提取）
    == 九段已消费（#33 / #45）
     ∪ EXCLUDED_NOTE 显式点名（#9 / #30）
     ——— 不得有第三个 ———
```

三个要点：

1. **全集侧是机械提取的**，不是手抄：`seedSlotNumbersInNamespace("cfg:verdict")` 直接读
   `02_slots_seed.sql`（classpath 资源），按正则提取 `(N, 'cfg:verdict.xxx'` 的 N。
   ⇒ **真相源新增/改名槽位 ⇒ 自动进全集 ⇒ 必须被归入某一侧**。
2. **两侧各有来源**：已消费侧来自九段实现的语义（测试里断言），排除侧来自 `EXCLUDED_NOTE`
   （`ThresholdVersionFingerprint` 的公开常量，测试**读它**而非重写一份）。
3. **反证 `EXCLUDED_NOTE` 点名的 verdict 槽位真实存在** —— 防"排除了一个根本不在命名空间的槽位"
   （排除说明写错槽号也会红）。

---

## 三、三列证据

| # | 注入内容 | 期望失败点 | 实际失败断言 |
|---|---|---|---|
| **P1** | 从 `EXCLUDED_NOTE` 删掉 `#9` 那两行（**制造"两侧都不在"**） | `excluded_items_are_cross_checked_...` | ✅ 变红（红集恰为该单一方法） |
| **P2** | 把 seed 里 `#35` 的 key 改成 `cfg:verdict.probe_range_rule`（**真相源侧多出一个 verdict 槽位**） | 同上 | ✅ 变红（红集恰为该单一方法） |
| **P3** | 期望集合写成 `Set.of("33","45")`（**期望集与真相源脱节**） | 同上 | ✅ 变红（红集恰为该单一方法） |
| — | 全部还原 | 复绿 | ✅ `exit=0`，字节数一致（`Test=27160B` / `Impl=24750B` / `Seed=29979B`） |

### 三组的判别力各证明什么（不重复）

- **P1（实现侧"排除说明"被删 ⇒ 红）** —— 证明断言**真的在读 `EXCLUDED_NOTE`**。
  若断言把"已排除项"**手抄**在测试里（而非读常量），删常量就**不会红** ——
  这正是"第二份清单"的失效模式（两份清单会各自漂移）。
- **P2（真相源侧新增槽位 ⇒ 红）** —— 🛑 **本轮最关键的一维**：证明守护**不是只对实现侧生效**。
  若断言只比对"九段 vs 排除说明"这两个**实现侧**的东西，那么**真相源里多出一个
  `cfg:verdict.*` 槽位时，两侧都不会知道** ⇒ 新增的口径槽位会**静默地不纳入指纹** ⇒
  "同一份口径 ⇒ 同一版本号"的承诺被悄悄破坏（版本号本该变的场景下不变）。
  P2 的价值在于：**把真相源拉进了守护面**。
- **P3（期望集脱节 ⇒ 红）** —— 证明断言**不是恒真**：如果"期望集"可以随便写且不影响结果，
  那它就不是守护、只是文档。P3 把一个**合法但错误**的期望集放进去 ⇒ 必须红。

---

## 四、🛑 跨模块注入纪律的【一次通过】验证

**P2 是本仓库第二个跨模块注入**（第一个是批次八 106）。

- **注入对象**：`dy-config/src/main/resources/db/config/02_slots_seed.sql`（**classpath 资源**）
- **被测类**：`dy-app` 的 `ThresholdVersionFingerprintTest`
- **风险**：只跑 `mvn -o -pl dy-app test` ⇒ dy-app 从**本地仓库**解析 dy-config 的 **jar**
  ⇒ 读到 jar 内的**旧资源** ⇒ 注入不生效 ⇒ 会误报"未被抓住"。

**本轮处置**：`NEEDS_CONFIG_INSTALL = {"P2"}` ⇒ 跑 P2 前先 `mvn -o -pl dy-config install -DskipTests`。
**结果**：**P2 一次即中，无须二次追查**。

> **批次八固化的教训（"跨模块注入必须先 install 依赖模块"）本轮首次产生收益**：
> 上次是**事后归因**（首跑 3/4 才发现），这次是**事前规避**（脚本里就写好了前置）。
> 参 §5.1 第 36 条：问的是"证据本身指向的是不是它声称的那件事"。

**顺带核过的边界**：P1（`dy-app/src/main`）与 P3（`dy-app/src/test`）**同在 dy-app**，
不受跨模块问题影响；`F_SEED` 是**资源**不是编译产物，故 `install` 的作用是**把资源刷进 jar**。

---

## 五、配套改动

- **`ThresholdVersionFingerprintTest`**：13 → **14** 例。新增
  `excluded_items_are_cross_checked_against_the_verdict_config_namespace`
  + helper `seedSlotNumbersInNamespace(String namespace)`（静态，读 classpath 资源）。
- **`ThresholdVersionFingerprint.EXCLUDED_NOTE`**：补入 `#9 branch_rules` 条目
  （原文只点名 `#30`）—— 使"不纳入"从**人回想**变成**钉在常量里的事实**。
- **`verification/107_excluded_items_cross_check_reverse_verification.py`**（新建）· 本文件（新建）。

### 🛑 本断言解决什么 / 不解决什么

- **解决**：`cfg:verdict.*` 命名空间的**闭合性** —— 任何槽位必须落在"已消费"或"已显式排除"两侧之一，
  且两侧的**来源各不相同**（实现语义 / seed 真相源），任一侧漂移即红。
- **不解决（有意留在范围外）**：
  - 其他命名空间（`cfg:scale.*` / `cfg:band.*` / …）的同类闭合 —— 若照搬会造成
    N 条同构断言；**方法是把它做成参数化的"命名空间闭合扫描"**（属后续批次，非本轮）。
  - `EXCLUDED_NOTE` 的**文字质量**（"排除理由写得对不对"）—— 机械可判的只有"点了名"，
    理由是否成立仍属**人读**。本断言只保证"不会静默漏掉"，不保证"理由正确"。