# 批次六 · 反向验证证据（契约端点覆盖总账）

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94/98/99/101/102/103_*.md：乱码为证据原貌，判定以英文方法名/码为准。

- 脚本：`verification/104_endpoint_coverage_reverse_verification.py`
- 目标门禁：`dy-app/src/test/java/com/diaoyuanyun/dy/app/EndpointCoverageLedgerTest.java`（4 例）
- **总体结果：PASS（8/8 项通过）**

---

## 第一版失败记录（必读 · 失败本身是教训）

第一版 L1 / L3 直接**删掉 `put(...)` 整行**来模拟"漏登记"，结果红的是**编译器**（悬空字符串字面量 ⇒
`需要';'` / `非法的表达式开始`），**不是断言** —— 即"门禁有牙齿"这件事**未被证明**。

修：改为**改键名**（`put("authLogin",` → `put("authLoginRENAMED",`）。
语义上等价于"该 operationId 不再被登记"，而 Java 语法完全合法 ⇒ 红的是**断言**。

> 🛑 通用教训（值得写进 RV 脚本模板）：**注入必须保持源文件语法合法**。
> 否则"编译失败也算被抓住"会掩盖"断言其实没有判别力"这一事实。

---

## L1 · 差额登记门禁（② `every_contract_operation_is_implemented_or_registered_out_of_scope`）

- 目标文件：`EndpointCoverageLedgerTest.java`
- 注入：`put("authLogin",` → `put("authLoginRENAMED",`（一条出范围登记"消失"）
- 预期失败点：契约端点 `POST /auth/login` 既未实现、又不在 `OUT_OF_SCOPE` ⇒ **红**。
- **实际结果：变红**，失败断言 `every_contract_operation_is_implemented_or_registered_out_of_scope` FAILED。
- 逐字节还原：✔ `EndpointCoverageLedgerTest.java`（32455 B）

🛑 证明的事：**"契约有、实现无"这件事不会被静默放过** —— 它必须要么被实现，要么逐条写明理由。

---

## L2 · 多出登记门禁（③ `every_extra_endpoint_is_registered_as_internal`）

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/settlement/SettlementController.java`
- 注入：新增 `@PostMapping("/unregistered-probe")`（契约 45 之外、`INTERNAL_ENDPOINTS` 未登记）
- 预期失败点：实现侧出现**未登记的出站面** ⇒ **红**。
- **实际结果：变红**，失败断言 `every_extra_endpoint_is_registered_as_internal` FAILED。
- 逐字节还原：✔ `SettlementController.java`（5272 B）

🛑 证明的事：**新开一个内部端点不会静默通过** —— 登记本身就是"为什么它可以出站"的一次书面决定。

---

## L3 · 僵尸登记（③ 同一条断言的另一个方向）

- 目标文件：`EndpointCoverageLedgerTest.java`
- 注入：`put("GET /verdicts/{}",` → `put("GET /verdicts/RENAMED",`
  （端点**仍在**实现侧、但登记键被改 ⇒ 原键成"僵尸登记"，新键成"未登记端点"）
- 预期失败点：僵尸登记 + 未登记端点同时出现 ⇒ **红**。
- **实际结果：变红**，失败断言 `every_extra_endpoint_is_registered_as_internal` FAILED。
- 逐字节还原：✔ `EndpointCoverageLedgerTest.java`（32455 B）

🛑 证明的事：登记表**双向**都守 —— 既防"端点没登记"，也防"端点删了登记还在"
（后者会让登记表慢慢变成"曾经存在过什么"的考古记录）。

---

## 逐字节还原汇总

| 组 | 文件 | 字节 | 结果 |
|---|---|---|---|
| L1 | `EndpointCoverageLedgerTest.java` | 32455 | ✔ 一致 |
| L2 | `SettlementController.java` | 5272 | ✔ 一致 |
| L3 | `EndpointCoverageLedgerTest.java` | 32455 | ✔ 一致 |

还原后基线复绿（`EndpointCoverageLedgerTest` PASS）。

---

## 结论

| 断言 | 守的方向 | 反向验证 |
|---|---|---|
| ② 差额登记 | 契约有 / 实现无 ⇒ 须登记理由 | L1 **红** |
| ③ 多出登记 | 实现有 / 契约无 ⇒ 须登记出站理由 | L2 **红** |
| ③ 僵尸登记 | 端点已删 / 登记还在 ⇒ 须显式归零 | L3 **红** |
| ④ 数字自洽 | 45 = 41 + 4；68 = 41 + 27 | 由 ② ③ 的集合口径间接确认 |

三组注入**全部被抓**（且都红在**断言**而非编译器）、逐字节还原一致、还原后复绿 ⇒ **总体 PASS**。