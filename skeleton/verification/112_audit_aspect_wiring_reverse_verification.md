# 批次十三 · 反向验证证据 —— AuditFillAspect 接线状态门禁

> 脚本：`verification/112_audit_aspect_wiring_reverse_verification.py`
> 门禁：`dy-audit` · `AuditFillAspectWiringGateTest`（**4 例**）
> 结论：**4/4 PASS**（3 组注入全被抓 + 还原后复绿）

## 一、实测抓出的缺口（未登记过）

盘点"距离商用还差什么"时，静态扫描抓出 `AuditFillAspect`（README 第 88 行列为 ADR-09 落地物）
是一对**从未接线的死代码**：

| 实锤 | 证据 |
|---|---|
| ① 切点零匹配 | 切点 `..save(..)` / `..persist(..)`，而全库**无任何 `save(...)` / `persist(...)` 方法定义**（持久化走 `*Ledger` + `insertXxx`/`append`） |
| ② 载体零实现 | `AuditableEntity` 在 `dy-*/src/main` 里**零子类** |
| ③ 零测试覆盖 | 全仓 `dy-*/src/test` 中**无任何测试引用 `AuditFillAspect`** |

**后果**：这条"自动填充 `created_by` / `updated_at`"的纪律**事实上从未生效**，
且**此前没有任何门禁覆盖**。这是"代码存在、注释完备、被文档引用，唯独没人在运行时路径验证过"的典型。

**如实登记（不夸大为"数据缺失"）**：本仓 `created_by` **已由各 Ledger 显式写入**
（86 处 `INSERT` 列 + 实参）⇒ 审计字段**实际有值**，只是**不由本切面填充**。
故这不是"数据缺失"，而是"**ADR-A5/A-6 的自动填充声明与实际实现不符**"。

## 二、注入表

| 组 | 注入 | 期望变红 | 守的失效模式 |
|---|---|---|---|
| C1 | 在 `ConsentLedger` 里加一个 `save(...)` **方法定义** | `the_pointcut_matches_no_method_definition_in_production_sources` | **真接线** |
| C2 | 新建 `class ReverseProbeAuditable extends AuditableEntity` | `auditable_entity_has_no_subclass_in_production_sources` | **载体到位** |
| C3 | 把切面的 `@Aspect` 注解**注释掉** | `the_aspect_and_its_pointcut_annotation_still_exist` | **切面被静默降级成普通类** |

🛑 **C1/C2 的语义要点**：注入的是"**合法且正确**"的接线动作。门禁变红**不是**因为接线错了，
而是因为"接线是一次**显式动作**、必须连同步文档登记一起做"——
**修好即红、须显式移除登记**。这与 `ConfigSlotConsumptionLedgerTest` 的"差异真在"纪律同款。

## 三、🛑 本脚本抓到"门禁自身的一个假绿缺陷"（最有价值的一次）

**C3 首跑：门禁 exit=0，红集为空 —— 没抓住。**

**根因**：门禁初版写的是 `src.contains("@Aspect")`（对**原始文本**断言）。
把注解"注释掉"（`// @Aspect`）后，字面量**仍在文本里** ⇒ 断言通过 ⇒
切面已被静默降级成普通类，而门禁**仍然绿**。

**这正是纪律 8.4 的镜像**：
```
8.4：源码里【字面出现】 ≠ 运行时被消费   （扫描消费面时必须剥注释）
本次：源码里【字面出现】 ≠ 注解真的生效   （断言注解时必须剥注释）
二者同源：注释里的字面量不是代码。
```

**修复**：门禁改为**先剥注释再断言**（`stripComments(...)` 后 `code.contains("@Aspect")`）。
重跑 ⇒ C3 被抓住（exit=1，红集 = 期望方法）⇒ **4/4 PASS**。

> **这是本轮最强的一条方法论收获**：
> **反向验证不只验证"门禁有没有牙齿"，它还会验证"门禁的牙齿咬的是不是真东西"。**
> 若只跑正向测试（全绿），这个假绿缺陷**永远不会暴露** ——
> 因为"注释掉注解"这个动作在真实开发中几乎不发生，但它恰好是"静默降级"的最简形态。

## 四、C3 的第二次教训：注入必须"可编译"

C3 **初版**注入的是"把类名改成 `AuditFillAspect_RENAMED`" —— Java 要求 public 类名与文件名一致
⇒ **编译失败**，测试没跑到方法级、**红集为空**。

"门禁红了"（exit=1）却"没抓到断言"是**假阳性**：它证明的是**编译器**而不是**门禁**。
故最终改为"注释掉注解"：语法合法、可编译，红的是**断言本身**。

> **判据**：注入后必须确认"红的是断言失败（`.java:` 出现在断言栈里）"，
> 而不是"红的是编译错误（`COMPILATION ERROR`）"。**exit!=0 不足以证明门禁有牙齿。**

## 五、新增门禁用例（`AuditFillAspectWiringGateTest` · 4 例）

| 用例 | 断言 |
|---|---|
| `the_pointcut_matches_no_method_definition_in_production_sources` | 生产源码（**剥注释后**）中 `save(...)`/`persist(...)` 方法定义数 = **0**；含元层自证（扫描到的 .java 文件 > 100，防目录搬家退化成恒绿） |
| `auditable_entity_has_no_subclass_in_production_sources` | `extends AuditableEntity` 的子类数 = **0** |
| `audit_columns_are_written_explicitly_by_ledgers` | `created_by` 在 `dy-app` 源码中出现 **≥ 50** 次 —— 证明"字段有值"由**另一条路径**保证；若该数骤降而切面又是死的，则从"声明不符"升级为"数据缺失" |
| `the_aspect_and_its_pointcut_annotation_still_exist` | 切面文件存在 · `@Aspect`/`@Component` 在**剥注释后的代码**里仍在 · 切点表达式未被改动 |

## 六、纪律遵守情况

- 锚点预检（存在 + 唯一）：C1/C3 各恰 1 次；C2 用"探针文件当前不存在"作为前置（防还原逻辑误删他人文件）。
- 元层判别力自证：基线（全绿）输出里待查方法名**零出现**。
- 逐字节还原：两文件 `bytes` 一致；C2 的新建探针文件被删除并断言不存在；还原后**复绿**（exit=0）。
- 单行锚点（技能 8.7）。
- must_see 锚点一律 ASCII 方法名。
- 只跑被测模块 `dy-audit`，不需 install。