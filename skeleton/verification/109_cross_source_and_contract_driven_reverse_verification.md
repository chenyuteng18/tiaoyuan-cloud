# 批次十一 · 反向验证证据（109）

> 2026-09-27 · 脚本 `verification/109_cross_source_and_contract_driven_reverse_verification.py`
> 对象：`RefundDomainCrossSourceGateTest`（N-8 跨源一致性门禁，9 例）
> ＋ `RefundWritePathMatrixE2ETest`（C-3 写路径矩阵 · 契约驱动层，16 例）

## 一、结论

**总体 7/7 PASS** —— 6 组注入 + 1 项还原后复绿。

## 二、注入明细

| 组 | 注入对象 | 注入内容 | 期望红 | 实际红集 | 结果 |
|---|---|---|---|---|---|
| **C1** | `contract/openapi-v1.0.0.yaml` | `RefundCreateRequest` 补 `requested_at_source`（模拟上游补齐 N-8） | `contract_refund_create_request_lacks_the_source_fields` | 同名（唯一） | ✅ |
| **C2** | `V6__refund_domain_alignment_and_ledgers.sql` | `refund_receipt.template_id` → `template_id_x`（模拟列被改） | `registered_gaps_have_no_zombie_entries` | `..._zombie_entries` + `..._either_covered_...` + `..._extracted_mechanically` | ✅ |
| **C3** | `_work/data-dict-entities-ddl-2026-09-19.md` | 字典「审计字段（全表必带）」把 `tenant_id` 改成 `tenant_zz` | `generic_audit_columns_are_read_from_the_dictionary` | `..._read_from_the_dictionary` + `..._either_covered_...` | ✅ |
| **C4** | `contract/openapi-v1.0.0.yaml` | G3 `x-callable-roles` 加 `therapist`（模拟契约角色面变更） | `callable_token_roles_are_derived_from_the_contract` | `..._derived_from_the_contract` + `is_reachable_by_all_contract_callable_staff_roles` | ✅ |
| **C5** | `contract/openapi-v1.0.0.yaml` | `x-roles.admin.token-role` 去掉 `hq`（模拟展开面变更） | `callable_token_roles_are_derived_from_the_contract` | 同名（唯一） | ✅ |
| **C6** | `contract/openapi-v1.0.0.yaml` | `x-roles.store_customer_service.token-role` 由 `null` 改成 `meridian` | `the_endless_role_expands_to_nothing` | 同名（唯一） | ✅ |

## 三、判据有效性论证（元层自证）

- **锚点预检**：6 组锚点在各自文件中均**恰出现 1 次**。首次运行 C4 锚点出现 0 次
  （`operationId: createRetention` 与 `x-callable-roles` 之间还夹着 `tags`/`summary`/`x-contract-row`）
  ⇒ 预检**直接拦下并中止**，而非让后续跑出一堆假绿。**这正是锚点预检存在的意义**：
  它把"注入失效"从静默失败变成显式失败。
- **元层判别力自证**：基线（注入前全绿）输出里，7 个待查方法名**零出现**
  ⇒ 后续"被抓"不是因为方法名出现在正常日志里（否则 must_see 恒真、无判别力）。
- **逐字节还原**：三文件全部 `OK`（`openapi=81307B` / `V6=12310B` / `dict=105135B`）。
- **还原后复绿**：exit=0 ⇒ 红是注入引起的，不是残留状态。

## 四、连带红集是设计使然（不是噪声）

- **C2** 触发 3 条：库侧列改名同时意味着 ① 12 列金丝雀对不上（`..._extracted_mechanically`）·
  ② 登记表里 `refund_receipt.template_id` 成了僵尸（`..._zombie_entries`）·
  ③ 新列名 `template_id_x` 无人认领（`..._either_covered_...`）。
  三者共享同一份事实（V6 库侧列集），一处漂移在**多个方向留痕** ⇒ **交叉自证**而非冗余。
- **C3** 触发 2 条：字典骨架改动同时 ① 豁免集读不到 `tenant_id`
  ② `refund_receipt.tenant_id` 从"被豁免"变成"无人认领"。
- **C4** 触发 2 条：契约加角色后 ① 展开结果 ≠ 期望值 ② 矩阵**真的去测了该新角色**并因
  `therapist` 无 `refund:write` 而 403。

🛑 **C4 是本批次最有说服力的一条**：它证明"契约驱动"确实生效 ——
手抄时代往契约加一个角色，**矩阵不会有任何反应**（那行请求根本不会发出去）；
改造后，契约一变，矩阵**自动去测新角色**，于是漏测在**同一构建内**就暴露。

## 五、发现并已修的缺陷（门禁首次运行的产出）

`RefundDomainCrossSourceGateTest` 第一次运行时红了一条 —— 但红的是**两类完全不同**的东西：

1. **解析器真缺陷**：`extractCreateTableColumns` 按"行首标识符"取列名，于是
   `refund_receipt` 建表体里两处**内联多行 CHECK 的续行**（`'已推送', '未授权（转线下）', '推送失败'))`
   与 `OR (receipt_state <> '已推送' ...)`）分别让行首的 `'or'` / `OR` 被读成列名 ⇒
   解析结果凭空多出一个列 `or` ⇒ 下游报**假缺口**。
   已修为**括号深度跟踪** + **12 列金丝雀**。
2. **比对粒度未定义**：`tenant_id`/`created_at`/`created_by` 是字典 §二「审计字段（全表必带）」的
   全表骨架列，天然不属于域字段缺口。已定义「通用审计列」豁免，且**豁免本身机械核验**
   （从字典逐字提取 + 剔除全角括号释义 + 窄性断言防吞业务列）。
3. **豁免提取的二次修正**：字典原句里 `created_by`（**操作人** `operator_id`）用**全角括号**写释义，
   若把它也读成列名，`operator_id` 会被误豁免 —— 而它恰是**已登记的业务列**
   （staff 外键、可空 = 系统自动推送）⇒ 会让一条真实缺口被静默吞掉。
   已按**字形差异**（半角类型标注 vs 全角释义）区分，并加反向断言钉住这一区分。