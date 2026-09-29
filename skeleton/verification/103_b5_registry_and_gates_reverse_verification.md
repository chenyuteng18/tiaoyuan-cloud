# 批次五 · 反向验证证据（A-8 追认台账 / A-9 出参方向 / N-1 risk_flag CHECK）

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94/98/99/101/102_*.md：乱码为证据原貌，判定以英文方法名/码为准。

- 脚本：`verification/103_b5_registry_and_gates_reverse_verification.py`
- 运行方式：`python.exe verification/103_b5_registry_and_gates_reverse_verification.py`
- Maven 调用口径：Windows `mvn.cmd` + 参数列表 + `shell=False` + `-o -pl dy-app`（**不加 `-am`**，依赖已 install）；
  带 `-Dsurefire.failIfNoSpecifiedTests=false` 与 `-Dsurefire.failIfNoTests=false`（dy-app 的 surefire 有第二个执行 `rls-isolation-gate`，筛选下不匹配会整体失败）；**不用 `-q`**（安静模式不打印 `BUILD SUCCESS`，无法据以判绿）。
- **总体结果：PASS（12/12 项通过）**

---

## 第一版失败记录（必读 · 失败本身是重要事实）

第一版 K2 给 E5 响应注入**受限字段** `gap_reason`，期望 A-9 断言变红，**结果全绿**。

追查结论：`gap_reason ∈ DerivedFields.derivedAndAdjacent()`，会先被 **`DerivedResponseBodyAdvice`（出口兜底 / 纵深防御第一层）** 从响应里**整体摘掉** ——
故 A-9 断言看到的 `data` 里根本没有它，无从变红。**这不是断言有洞，而是选错了注入目标**：

- A-9 断言设的是「**未登记字段**」这条线（白名单闭合，第 ③ 条）；
- 「**受限字段是否出站**」是另一条线，由出口兜底独立守护。

故本组拆为两步，**两者合起来**才说明 A-9 断言在它该管的那条线上有牙齿：

| 步骤 | 注入 | 期望 | 结果 |
|---|---|---|---|
| K2a | **非受限、契约未声明、白名单未登记**的字段 `internal_score` | A-9 必须红 | ✔ 被抓 |
| K2b | **受限字段** `gap_reason` | A-9 应**保持绿**（已被出口兜底剥离） | ✔ 保持绿（纵深防御层 1 生效） |

---

## K1 · A-8 追认台账（`PermissionCodeRegistrationGateTest`）

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/controller/ScaleItemBankController.java`
- 注入：`@RequirePermission("customer:write")` → `@RequirePermission("scale:import")`（贴一个**既不在台账、也不在契约依据清单**的新码）
- 捕捉者：`PermissionCodeRegistrationGateTest`
- 预期失败点：`skeleton_defined_codes_form_a_closed_registry` —— 源码里出现「来历不明的码」⇒ 台账闭合性被击穿。
- **实际结果：变红**，失败断言为 `skeleton_defined_codes_form_a_closed_registry` FAILED。
- 逐字节还原：✔ `ScaleItemBankController.java`（10317 B，与注入前一致）
- 还原后复绿：✔ `PermissionCodeRegistrationGateTest` PASS

🛑 本组的设计意图：A-8 台账最危险的失效模式是「新增第 6 个码时不红」。
本注入证明该门禁**对未登记的新码有反应**，而非只在文档里写一句"须由契约 owner 追认"。

---

## K2a · A-9 出参方向 · 未登记字段（`DerivedVisibilityE2ETest`）

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/domain/BandProbeResult.java`
  + `dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandAvailableDatesService.java`
- 注入：给 `BandProbeResult` record 加 `@JsonProperty("internal_score") String injectedField`，并在 service 构造点补该参数
- 捕捉者：`DerivedVisibilityE2ETest`
- 预期失败点：`e5_success_response_carries_no_client_forbidden_field` 第 ③ 条 —— 出参字段集与契约声明的 5 属性对齐，多出的必须是**已登记的扩展位**；`internal_score` 未登记 ⇒ 必红。
- **实际结果：变红**，失败断言为 `e5_success_response_carries_no_client_forbidden_field` FAILED。
- 逐字节还原：✔ `BandProbeResult.java`（2410 B）+ `BandAvailableDatesService.java`（12526 B）
- 还原后复绿：✔ `DerivedVisibilityE2ETest` PASS

🛑 为什么选「未登记的非受限字段」而不是「受限字段」：见上「第一版失败记录」。
本条证明的是：**任何新增出参字段都必须显式登记**（登记本身就是"为什么它可以出站"的一次书面决定），
否则就可能在绕过全部可见性审查的情况下把内部口径带出去。

---

## K2b · 纵深防御第一层佐证 · 受限字段（`DerivedVisibilityE2ETest`）

- 目标文件：同上两件
- 注入：给 E5 响应加**受限字段** `gap_reason`（契约 §3.1 注 ③「客户恒 403」的硬约束字段）
- 捕捉者：本应无捕捉者 —— 它由 `DerivedResponseBodyAdvice` 在出站前摘掉
- 期望：A-9 断言**保持绿**
- **实际结果：保持绿**（`e5_success_response_carries_no_client_forbidden_field` PASS）
- 逐字节还原：✔
- 还原后复绿：✔（同 K2 的统一复绿）

🛑 本条**不是缺陷**，是**分层证据**：
「客户看不到受限字段」由**两层**共同保证 —— 层 1 = 出口兜底（`DerivedResponseBodyAdvice` 按 `DerivedFields` 剥离）、
层 2 = A-9 出参白名单断言。本注入打在层 2 看不见的位置，正说明层 1 独立生效。
若哪天层 1 被摘掉，受限字段会落到 A-9 面前并被白名单那条抓住 —— 安全网仍有效（届时本步会记「未被兜底剥离，但被 A-9 抓住」）。

---

## K3 · N-1 risk_flag CHECK（`VerdictPersistenceBoundaryTest`）

- 目标文件：`dy-app/src/main/resources/db/migration/V8__verdict_two_phase_alignment.sql`
- 注入：`risk_flag IS NULL OR risk_flag IN ('无','高危','新发','同病')` → `risk_flag IN ('无','高危','新发','同病')`
  （**删掉 `IS NULL` 支**，破坏可空性 ⇒ 未录入的 `null` 变成库层不可能 ⇒ D5「人工复核」那一支被夹死）
- 捕捉者：`VerdictPersistenceBoundaryTest`
- 预期失败点：`v8_risk_flag_check_is_present_and_matches_enum_labels` —— 断言 CHECK 含 `RISK_FLAG IS NULL`、四字面与 `RiskFlag.allDbLabels()` 逐字一致。
- **实际结果：变红**，失败断言为 `v8_risk_flag_check_is_present_and_matches_enum_labels` FAILED。
- 逐字节还原：✔ `V8__verdict_two_phase_alignment.sql`（15824 B）
- 还原后复绿：✔ `VerdictPersistenceBoundaryTest` PASS

🛑 N-1 的真相是「两阶段事实」：V5 建表时 `risk_flag` **无 CHECK**（历史事实，由 `v5_risk_flag_has_no_check_but_others_do` 对照钉住），
**V8 已补 CHECK 闭合该缺口**（由本条守护）。本注入证明 V8 的守护**对可空性破坏有反应**，
而不是只断言"存在一个 CHECK"（那样删掉 `IS NULL` 支仍会绿）。

---

## 逐字节还原校验汇总

| 组 | 文件 | 字节 | 结果 |
|---|---|---|---|
| K1 | `ScaleItemBankController.java` | 10317 | ✔ 一致 |
| K2 | `BandProbeResult.java` | 2410 | ✔ 一致 |
| K2 | `BandAvailableDatesService.java` | 12526 | ✔ 一致 |
| K3 | `V8__verdict_two_phase_alignment.sql` | 15824 | ✔ 一致 |

还原后三套断言全部复绿（A-8 / A-9 / N-1 各自 PASS）。

---

## 结论

- **A-8 追认台账有牙齿**：贴未登记码即红 —— 台账与源码是**双向机械约束**，不是文档摆设。
- **A-9 出参方向有牙齿**：新增未登记出参字段即红；受限字段则由出口兜底先拦（分层证据）。
- **N-1 由 V8 闭合且有守护**：破坏可空性即红 —— 守护断言查的是**CHECK 的完整语义**，不是"存在性"。

三组注入**全部被抓**、逐字节还原一致、还原后复绿 ⇒ **总体 PASS**。