# 批次七 · 反向验证证据：`refund_offline_notice` 字段级约束

**脚本**：`verification/105_refund_offline_notice_reverse_verification.py`
**门禁**：`RlsV6RefundLedgerIsolationTest`（真库 · 非超级用户）
**结果**：**总体 PASS 4/4**（3 组注入全部被抓 + 还原后复绿），逐字节还原一致。

---

## 一、缺口是怎么被发现的（不是"顺手多测一点"）

清点 `RlsV6RefundLedgerIsolationTest` 的 11 条原断言，按**被测对象**归类：

| 断言 | 被测对象 |
|---|---|
| 跨租户双向不可见 / 直查主键 0 行 / 无上下文 0 行 / 空串 0 行 | RLS（三表共用的循环） |
| 跨租户 INSERT 被 42501 拒 / 越权行未落库 | RLS（循环） |
| ENABLE+FORCE / 双 NULLIF / 回执三态齐备 | RLS 元数据（循环） |
| 「已推送 ⟺ 有 pushed_at」双向 | **`refund_receipt`** |
| `receipt_state` / `channel` 字面绑定契约 | **`refund_receipt`** |
| 更正 = 追加 + 反向指针 | **`refund_statement`** |
| 三表无 `updated_at` | 三表（结构性） |

**结论**：`refund_offline_notice` 的**字段级约束一条都没被断言** ——
它的 `channel` 枚举、`NOT NULL` 完整性、CHECK 数量，全靠"表建出来了"这一事实默认成立。
这与"三张表各自都值得一套断言"的类注释**不一致**，属**文档比实现更自信**这一类
（与 §5.1 第 23 条同族）。故本批次补三例，并配本脚本证明它们**有牙齿**。

---

## 二、三列证据

| # | 注入内容（改 V6 迁移文本） | 期望失败点 | 实际失败断言 |
|---|---|---|---|
| **M1** | `refund_offline_notice.channel` CHECK 扩成 `('电话','当面','订阅消息')` | `refund_offline_notice_channel_excludes_push_channels` | ✅ 变红（红集恰为该单一方法） |
| **M2** | 去掉 `refund_offline_notice.refund_id` 的 `NOT NULL` | `refund_offline_notice_required_columns_are_not_null` | ✅ 变红（红集恰为该单一方法） |
| **M3** | 追加第二条 CHECK `refund_offline_notice_note_len_check` | `refund_offline_notice_has_exactly_one_check_constraint` | ✅ 变红（红集恰为该单一方法） |
| — | 全部还原 | 复绿 | ✅ `exit=0`，字节数一致（`V6=12310B` / `RlsV6...Test=55081B`） |

### 为什么 M1 是这三组里最重要的一组

`订阅消息` 是**推送通道**。它若被 `refund_offline_notice` 接受，则记账时
「线下告知」会被算成一次「已推送」—— 而 PRD 逐字写着线下告知
**「不得计入推送覆盖率分母」**（分母 = `已推送 + 未授权 + 推送失败`）。
即：**一个通道字面的放行，会直接制造一次覆盖率虚高**。
M1 证明这条防线不是"文档承诺"，而是**真库 CHECK + 可反向验证的断言**。

---

## 三、三条纪律（本轮实证）

### 1. 🛑 must_see 锚点必须做【元层判别力自证】

首版脚本的 `failed_methods()` 只做「方法名是否出现在输出里」。
但 surefire 在 `-Dtest=X` 下**仍会打印包含类名的汇总行** —— 若某次改动让"方法名出现"变成恒真，
则"被抓=True"全部失效。**修法**：基线（全绿）阶段先断言
**待查方法名在基线输出里零出现**，否则直接 `return 2` 判脚本失效。
第二轮实测：基线零出现 → 三轮注入各命中唯一方法。**锚点有判别力是可证的，不是假设的。**

### 2. 🛑 注入必须保持源文件语法合法（承 104 教训）

本脚本注入的是 **SQL 迁移文本**：改 CHECK 字面 / 删 `NOT NULL` / 加一条 `ADD CONSTRAINT`，
三种形态**语法均合法**。故红点只能来自**断言**，不会来自 psql 解析器或编译器。
（104 第一版"删整行 ⇒ 悬空字面量 ⇒ 编译失败"的教训，在此体现为**选注入形态时的硬约束**。）

### 3. 🛑 改迁移文本会触发 schema 哨兵失效 ⇒ 自动重建库（安全性依据）

`RlsGateSupport` 的哨兵 = **整条迁移链字节的 SHA-256**。故：
**注入 V6 文本 ⇒ 哨兵变 ⇒ 门禁判定库不匹配 ⇒ 自动重建库 ⇒ 在新 schema 上跑断言**；
**还原 ⇒ 哨兵回原值 ⇒ 再建回原 schema**。
这条机制使"改迁移做反向验证"**天然安全** —— 不需要手工重建库，
也不会出现"库停在旧 schema 上导致断言假绿"。本轮 5 次 Maven 调用（1 基线 + 3 注入 + 1 复绿）
全部在该机制下运行，`information_schema` / `pg_constraint` 判据均取到真实新值。

---

## 四、配套改动

- **`RlsV6RefundLedgerIsolationTest`**：11 → **13** 例（+2 方法）。
  - `refund_offline_notice_channel_excludes_push_channels`：`订阅消息` / 空串 / `微信` 必须被拒；
    `电话` / `当面` 必须被接受（反证不是全拒）。
  - `refund_offline_notice_required_columns_are_not_null`：`tenant_id` 缺 → 42501（RLS）·
    `refund_id` / `channel` 缺 → 23502（NOT NULL，且**错误消息点名该列**）；四列齐备 → 通过。
  - `refund_offline_notice_has_exactly_one_check_constraint`：CHECK 数必须恰为 1，
    且**反证表真实存在**（避免表名打错 ⇒ 查到 0 条 ⇒ 假绿）。
  - 新增 helper `assertNotNullRejected(String column, ...)`：把 SQLSTATE 钉在 `23502`
    **并校验消息点名列** —— 与 `assertCheckRejected` 分开的理由是
    **"某条约束报了"不等于"我要测的那条有牙齿"**（同列可既 NOT NULL 又有 CHECK）。
- **`verification/105_refund_offline_notice_reverse_verification.py`**（新建）· 本文件（新建）。