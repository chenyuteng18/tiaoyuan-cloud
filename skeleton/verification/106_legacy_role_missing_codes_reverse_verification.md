# 批次八 · 反向验证证据：遗留角色码未持自建码的【登记式差异】

**脚本**：`verification/106_legacy_role_missing_codes_reverse_verification.py`
**门禁**：`PermissionCodeRegistrationGateTest`（第七章 · 新增）
**结果**：**总体 PASS 4/4**（R1/R2/R3 全部被抓 + 还原后复绿），逐字节还原一致。

---

## 一、被登记的事实（此前只写在注释里）

`SKELETON_DEFINED_CODES`（A-8 台账）里 `doc:write` 条目原写着一句**自由文本**：

> ⚠️ 已知分叉：TENANT_ADMIN / super_admin 未登记该码，契约点名的超管若以 TENANT_ADMIN 角色出现会被 403（T-11 延伸）。

**这句话是对的，但它没有任何东西在守。** 具体事实（`PermissionRegistry` 实测）：

| 角色写法 | 在 `PermissionRegistry` 里 | `hasPermission(role, "doc:write")` |
|---|---|---|
| `hq`（契约小写码） | 有键，含 `doc:write` | `true` |
| `SUPER_ADMIN`（遗留大写） | 有键，`Set.of("*")` | `true`（通配） |
| **`TENANT_ADMIN`** | **无键** | **`false`** |
| **`super_admin`**（PRD v1.28 写法） | **无键** | **`false`** |

而 `DocFileService.SUPER_ADMIN_ALIASES` = `{hq, SUPER_ADMIN, TENANT_ADMIN, super_admin}` ——
即 `TENANT_ADMIN` / `super_admin` **被业务层视为超管**，却**到不了那第二道判定**：
第一道 `@RequirePermission("doc:write")` 就把它们 403 拦下了。

**这与常见缺陷的形态相反**：多数缺陷是"权限给多了"，这一处是
**"业务层认为你是超管、权限层不认"** —— 两个各自正确的部件串起来后语义不一致
（与 §5.1 第 31 条"组合自环"**同族**：不是任一条错了，而是**它们的交集没人看**）。

---

## 二、三列证据

| # | 注入内容 | 期望失败点 | 实际失败断言 |
|---|---|---|---|
| **R1** | 给 `PermissionRegistry` 加 `TENANT_ADMIN → {doc:write, audit:read}`（**把分叉修好**） | `legacy_role_codes_missing_self_defined_permissions_are_registered_not_assumed` | ✅ 变红（红集恰为该单一方法） |
| **R2** | 登记表角色写成 `hq`（**实际持有该码**的契约角色） | 同上 | ✅ 变红（红集恰为该单一方法） |
| **R3** | 登记表清空为 `Map.of()` | 同上 | ✅ 变红（红集恰为该单一方法） |
| — | 全部还原 | 复绿 | ✅ `exit=0`，字节数一致（`GateTest=47159B` / `PermissionRegistry=24560B`） |

### 三组的判别力各证明什么（不重复）

- **R1（登记表会随修复归零）** —— 这是登记式断言**唯一真正重要的方向**：
  只写注释时，分叉被修复了**不会有人报错**，于是"未修好"的旧事实会被继续引用。
  R1 证明：**修好 ⇒ 红 ⇒ 必须显式把该码从表里移除**（归零要求动作）。
- **R2（反向也红）** —— 证明它不是"单向假绿"：登记一个**已持有**该码的角色同样报错。
  即断言是**双向**的（登记了却持有 ⇒ 红；持有却登记缺失 ⇒ 不适用，因本表只登"缺失"方向）。
- **R3（防表清空 ⇒ 平凡通过）** —— 这是**登记式结构的固有失效模式**：
  表一空，`for` 循环体一次都不进 ⇒ 断言平凡通过。
  故断言 ③ 显式钉住"**恰 2 个角色**"：**清空也必须是一次显式编辑**（并在此处报红说明），
  否则"差异归零"与"表被误删"在测试输出上**长得一模一样**。

---

## 三、🛑 本轮最重要的教训：跨模块注入必须先 install 依赖模块

**首跑 `3/4`，唯一未抓的是 R1。** 追查过程与结论：

- **现象**：R1 改了 `dy-security/PermissionRegistry`，门禁 `exit=0`（保持全绿）。
- **第一反应（错）**："① 断言没牙齿"。
- **真因**：**注入根本没进到被测对象里**。`PermissionRegistry` 在 **dy-security**，
  被测类在 **dy-app**；脚本用 `mvn -o -pl dy-app test` ⇒ dy-app 从**本地仓库**
  解析 dy-security 的 **jar** ⇒ **源码改动完全不生效**。
- **判别方法**：先问"红点应该来自哪里"，再看**改动的那份文件有没有参与本次构建**。
- **修法**：每次跑门禁前先 `mvn -o -pl dy-security install -DskipTests` 把依赖模块装进本地仓库。

> **这是"未被抓住"的第二种成因，且它伪装得很好。**
> 与 103 首版"POSIX 路径 + `shell=True` ⇒ Maven 从未执行"**同族**：
> **"注入未被抓住"必须先分辨"守护坏了"还是"注入没进到被测对象里"。**
> （参 §5.1 第 36 条：问的是"证据本身指向的是不是它声称的那件事"。）

### 对既有脚本的影响评估（本轮顺带核过）

| 脚本 | 注入对象所在模块 | 被测类所在模块 | 是否受影响 |
|---|---|---|---|
| 103（批次五） | `dy-app`（控制器 / 领域 record / 服务 / V8 迁移 / 门禁测试） | `dy-app` | ✅ 同模块，**不受影响** |
| 104（批次六） | `dy-app`（`EndpointCoverageLedgerTest` / `SettlementController`） | `dy-app` | ✅ 同模块，**不受影响** |
| 105（批次七） | `dy-app`（V6 迁移 / `RlsV6...Test`） | `dy-app` | ✅ 同模块，**不受影响** |
| **106（批次八）** | **`dy-security` + `dy-app`（跨模块）** | `dy-app` | 🛑 **受影响，已修** |

**结论**：本次是**首个跨模块注入**，故此盲区首次显形。
后续凡注入 `dy-common` / `dy-security` / `dy-config` / `dy-crypto` 等**上游模块**的脚本，
**必须先 install 该模块**，否则"未被抓住"的结论不可信。

---

## 四、配套改动

- **`PermissionCodeRegistrationGateTest`**：6 → **7** 例。新增第七章
  `LEGACY_ROLE_MISSING_SELF_DEFINED_CODES`（结构化登记表）+ 四段断言：
  ① 差异**真的还在**（已持有 ⇒ 红，要求归零）·
  ② 对照组：被登记缺的码必须**有其它契约角色持有**（区分"角色级缺口"与"码级无主"，后者属另一条断言）·
  ③ **恰 2 个角色**（防表清空 ⇒ 平凡通过）·
  ④ 与第六章台账**交叉自证**（本章只管"骨架自立码 × 遗留角色码"的交集）。
- **`verification/106_legacy_role_missing_codes_reverse_verification.py`**（新建）· 本文件（新建）。
- 🛑 **本表不回答**"该不该给 `TENANT_ADMIN` 补 `doc:write` / `audit:read`" —— 那是
  **A-8 契约 owner 的裁定**。本表只把**现状**钉死，使"裁定之后"成为一个**显式动作**。