# CI 门禁接入交接文档

> **一句话结论：仓库已 git init、仓库根已修正、workflow 已就位，但 CI 目前仍然拦不住任何合并。**
> Workflow 文件被提交 ≠ 门禁生效。要真正拦合并，还差 §2 的一次人工配置。
> 本文件里所有 job 名、命令都是从仓库现有文件里逐字抄来的，没有改写、没有补写。

---

## 0. 当前真实状态（已核实，非推断）

> 🛑 **2026-09-30 全面重写本节**：旧版本记的是「仓库根 = `skeleton/`」时代的状态
> （基线提交 `ba22251`、默认分支 `master`），那些记录**已全部作废**。
> 根因是**第 49 条系统性缺陷**（仓库根建错层级 ⇒ CI 永远无法变绿），已随仓库根上移修复。
> 下方每一行的"证据"列都是本轮实际跑出来的输出，不是推断。

| 项目 | 状态 | 证据 |
|---|---|---|
| 仓库是 git 仓库 | **已完成** | `git log --oneline -1` = `073b338` |
| **仓库根层级正确** | **✅ 已完成（本轮修复）** | 仓库根 = `product-strategy/`；clone 到 `/tmp` 后 7 个门禁测试类全部命中根，`TOTAL 1199` / `BUILD SUCCESS` |
| `_work/` 根锚点文件已入库 | **✅ 已完成** | `git ls-files` 实测：`_work/contract-t6-api-freeze-2026-09-19.md`、`_work/data-dict-entities-ddl-2026-09-19.md` 均 `[TRACKED]`，且 `git check-ignore` 返回「未被忽略」 |
| 根锚点同类文件已入库 | **✅ 已完成** | `contract/openapi-v1.0.0.yaml`、`prototype/index.html`、`prd-health-mgmt-saas-2026-09-16.md` 均在**仓库根**且 `[TRACKED]` |
| workflow 位于仓库根 | **✅ 已完成（本轮修复）** | 原在 `skeleton/.github/` ⇒ GitHub Actions **从不加载**；已移到仓库根 `.github/workflows/`，三文件 `[TRACKED]` |
| workflow 已纳入版本库 | **已完成** | 三个 `.yml` 均被 git 跟踪（`git ls-files .github/workflows/` 返回 3 行） |
| 有 remote | **未完成（必须人工）** | `git remote -v` 输出为空；本机无 `gh` CLI，建远端仓库需人工 |
| workflow 在 GitHub 上跑过 | **未完成** | 没有 remote，GitHub 看不到这个仓库 |
| 三个 job 配为 required status check | **未完成（必须人工）** | 见 §2 |
| 默认分支名 | **✅ 已一致** | 本地已 `git branch -M main`，当前分支 `main`；与三个 workflow 的 `pull_request.branches: [main]` 一致，无需再改名 |

### 0.1 剩余的两个人工项（这是本文件存在的唯一理由）

| # | 人工步骤 | 为什么不能自动化 |
|---|---|---|
| 1 | 在 GitHub 建仓库并 `git remote add origin <url>` + `git push -u origin main` | 需要账号凭据与建仓权限；本机未装 `gh` CLI |
| 2 | Settings → Branches → 把三个 check 名配为 required status check | 需要仓库 admin 权限，无 API 凭据可用；且**不配就等于没有闸门**（§3） |

**除这两项外，其余 CI 前置条件本轮已全部落地并验证。**

人工项 1 的可直接复制命令（**在 `product-strategy/` 目录下执行，不是 `skeleton/`**）：

```bash
cd <path-to>/product-strategy
git remote add origin https://github.com/<org>/<repo>.git
git push -u origin main
# 推送后确认：
git remote -v          # 应有 origin 两行
git ls-files .github/workflows/ | wc -l   # 应为 3
```

> 本机 `~/.git-credentials` 里已有 `github.com` 的凭据（`credential.helper=store`），
> 故 HTTPS 推送通常不需要重新登录。若仓库属组织且开了 SSO，需先授权该 token。
> 注：本机 git 配置里另有一条 `credential.https://gitee.com.provider=generic`，
> 那只是 provider 映射项，**不代表托管平台是 Gitee**；实际凭据主机为 `github.com`，
> 三个 workflow 用的也是 GitHub Actions 语法，口径一致。

### 关于「四个门禁 job」的更正

交接要求里写的是「四个门禁 job」。**逐字核对 `.github/workflows/*.yml` 后，实际只有 3 个 CI job。**
下面 §1.1 的三个是全部，没有第四个 —— 本文不会为了凑数编一个名字出来。

「四」这个数字的来源是 `pom.xml`：里面有 **四个绑定在 `validate` 阶段的门禁执行**，
但它们**全部跑在同一个 CI job 里**（§1.2）。所以：

- 需要配进 branch protection 的是 **3 个 check 名**（§1.1）；
- 本地 `mvn -o -B validate` 会跑的是 **4 个门禁**（§1.2）。

---

## 1. 门禁的确切名字

### 1.1 三个 CI job —— 逐字抄自 workflow 文件

| workflow 文件 | `jobs.<id>` | `name:`（**这一列才是 required status check 要填的名字**） | 触发条件 |
|---|---|---|---|
| `.github/workflows/compliance-gate.yml` | `adr12-compliance-scan` | `ADR-12 compliance scan (build-time gate)` | push 到任意分支 / PR 到 `main` / 手动 |
| `.github/workflows/crypto-adversarial-gate.yml` | `crypto-adversarial` | `dy-crypto adversarial verification (A8 gate)` | push 到任意分支 / PR 到 `main` / 手动 |
| `.github/workflows/rls-isolation-gate.yml` | `rls-isolation` | `RLS tenant isolation (ADR-02 L3 gate)` | push 到任意分支 / PR 到 `main` / 手动 |

**填哪个字段？填 `name:`，不是 `jobs.<id>`。**

GitHub 官方文档（About protected branches）明确：required status check 用**作业名**匹配，
并且要求「job names 在所有 workflow 之间必须唯一，否则会产生歧义的状态检查结果并阻塞 PR」。
本仓库三个 `name:` 互不相同，该前提已满足。

（若日后有人给这三个 job 改 `name:`，branch protection 里那条旧名字会永远停在
"Expected — Waiting for a status report"，PR 会被永久卡住。改名必须同步改 §2 的配置。）

### 1.2 四个 Maven 门禁执行 —— 都在上面第一个 job 里跑

来自 `pom.xml`，四个 execution 全部 `<phase>validate</phase>`：

| execution id | 对应脚本 |
|---|---|
| `adr12-compliance-scan` | `compliance/scan_compliance.py` |
| `s16-zero-derived-gate` | `compliance/client-zero-derived-gate.py` |
| `s18-contract-conformance` | `compliance/contract-conformance-gate.py` |
| `s19-sdk-surface-gate` | `compliance/sdk-surface-gate.py` |

即：`mvn -o -B validate` 一条命令就把这四道都跑了。它们的产物是
`compliance/reports/last-build*.json`，已在根级 `.gitignore` 中忽略（见 §5）。

---

## 2. 配成 required status check —— 逐步操作

**前提 A：仓库必须先推到 GitHub。** 本仓库目前 `git remote -v` 为空（§0.1 人工项 1），
本文档无法代替这一步。
**前提 B：这三个 check 必须先在该分支上跑过一次**，否则设置页的搜索框里搜不到它们。
三个 workflow 都带 `workflow_dispatch`，可以先手动跑一次。

### 步骤

1. 打开 GitHub 仓库页面 → **Settings**（需仓库 admin 权限）。
2. 左侧 **Branches**（路径：Settings → Branches）。
3. **Add branch protection rule**（若 `main` 已有规则，则点右侧 **Edit**）。
4. **Branch name pattern** 填 `main` —— 必须与三个 workflow 里
   `pull_request.branches: [main]` 一致；填成 `master` 的话 PR 触发不到门禁。
   （本地分支已统一为 `main`，见 §0 最后一行，此项无需再改。）
5. 勾选 **Require status checks to pass before merging**。
6. 建议同时勾选 **Require branches to be up to date before merging**
   （否则可以拿一个过时的分支骗过门禁）。
7. 在下方搜索框里**逐个**添加这三个名字，加完一个再加下一个：

   ```
   ADR-12 compliance scan (build-time gate)
   dy-crypto adversarial verification (A8 gate)
   RLS tenant isolation (ADR-02 L3 gate)
   ```

8. **搜不到怎么办**：说明这些 check 从未在该分支上跑过。去 **Actions** 页 →
   左侧选对应 workflow → **Run workflow**（选 `main` 分支）→ 等它跑完 →
   回到第 7 步再搜。GitHub 也允许在输入框里直接粘贴确切名字而不依赖自动补全。
9. 页尾 **Save changes**。
10. 验证它真的生效（别只信配置页）：开一个 PR，故意往 `client-package/` 里注入一个
    违禁词，确认 PR 的 Merge 按钮被禁用并提示 required check 失败。
    **没做这一步验证，就等于没配上。**

> 替代路径：GitHub 正在把 branch protection 迁到 **Settings → Rules → Rulesets**。
> 若仓库界面已切到 rulesets，用 "Require status checks to pass"，check 名同样是上面三个。

---

## 3. 已知限制（本仓登记过的未收口项 —— 不是已解决）

**一个 workflow 自己不能拦合并。**

这是三个 workflow 文件里各自写明的、以及 `compliance/README.md`
「Handover: what is still open」一节登记过的事实，原话是：

> The workflow fails the job, but a GitHub workflow cannot block a merge on its own.
> The job **MUST** be added as a required status check in the repository settings;
> until that is done the gate cannot block a merge.

具体来说，job 变红只会让 PR 上出现一个红叉；只要没配 required status check，
任何人依然可以直接点 Merge。**红叉是提示，required check 才是闸门。**

所以完整的因果链是：

```
git init（已完成）
   ↓
仓库根放到正确层级 + workflow 放到仓库根（已完成 2026-09-30，修第 49 条缺陷）
   ↓
加 remote 并 push（未完成 ← 人工项 1）
   ↓
GitHub 真正执行 workflow（未完成）
   ↓
把 3 个 job 配成 required status check（未完成 ← 人工项 2；这一步之前，一切都拦不住合并）
```

> 📌 **为什么本轮要专门修"仓库根层级"这一步**：在它修好之前，CI 即使配齐了
> remote 与 required check，**每一次都必然是红的**（7 个门禁测试类在 clone 上找不到仓库根）。
> 那会让 required check 变成"永远关不上的门"——比没有门禁更糟，因为所有人都会学会无视红叉。
> 这也是为什么本轮把它当作**阻断级**缺陷优先修掉，而不是登记待办。

`compliance/README.md`（「Handover: what is still open」一节）里登记的第二条同源限制，
**本轮已同步改写**，现措辞为：

> The repository is under git now (as of 2026-09-30), but it has **no remote yet**,
> so the workflow still cannot run. … Until (1) happens the workflow is inert —
> being committed locally is not enough.

即：这条的「不是 git 仓库」已解决，**「没有 remote」仍未解决**（同一人工项 1）。

另有一条与 CI 无关的 P0 阻塞项，顺带记录，不要与 CI 混为一谈：
`compliance/owners.csv` 目前全是占位 owner（`role:dev-compliance-lead` 等），
`mvn -Pgo-live validate` 会因此判红 —— **这个红是门禁在正常工作，不是构建坏了**，
处置方式是研发侧写入实名 owner，不是加跳过开关。

---

## 4. 本地等价验证（离线，不需要 GitHub）

在仓库根目录执行。注意：本机的平台默认编码是 GBK，故显式钉 UTF-8
（`compliance/README.md`「Wordlists and their encoding」一节要求这么做）。

> 🛑🛑 **2026-09-30 重要修正：「仓库根目录」指的是 `product-strategy/`，不是 `skeleton/`。**
> 本文档此前多处按「cwd = skeleton」书写，那是**错的**，已随仓库根上移一并更正。
> 理由（详见骨架 README 第 49 条系统性缺陷）：
> - **7 个门禁测试类**（`ContractConsistencyTest` / `ContractFreezeGateTest` /
>   `EndpointCoverageLedgerTest` / `UpstreamGapRegistryTest` / `RefundDomainCrossSourceGateTest` /
>   `RefundWritePathMatrixE2ETest` / `DocTestCountAnchorGateTest`）把"仓库根"**硬编码**为
>   「同时含 `_work/contract-t6-api-freeze-*.md` 与 `_work/data-dict-entities-ddl-*.md` 的那一层」，
>   并从 `user.dir` **逐级向上查找**。那一层是 `product-strategy/`。
> - 仓库若建在 `skeleton/`，则 **clone 到任意位置后向上 4 层都找不到** ⇒
>   实测 `ContractConsistencyTest` **5 例 4 失败 / BUILD FAILURE**。CI 每次都是全新 checkout，故**必然红**。
> - `.github/workflows/` 也已从 `skeleton/.github/` 移到**仓库根** —— 原先的位置
>   **GitHub Actions 根本不会加载**（它只认仓库根的 `.github/workflows/`），
>   三个 workflow 因此**从未生效**。移动后各 job 加了 `defaults.run.working-directory: skeleton`。
> - **改回的前提**：若日后要换层级，7 个测试类与 `verification/*.py` 的根解析必须**一并改账**
>   （或统一改用 `-Ddy.docs.root=<abs>`），**不得只改一处**。

```bash
# Git Bash / PowerShell 通用前缀
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8
# PowerShell 里改写成：
#   $env:PYTHONUTF8="1"; $env:PYTHONIOENCODING="utf-8"
```

> 📌 **下文中凡 `cd skeleton` 或 `python compliance/...` 的命令，请按下述口径理解**：
> **工作目录 = `skeleton/`**（因为 `compliance/`、`dy-*/`、`verification/` 都在骨架内），
> 而「仓库根」= 其上一级 `product-strategy/`。CI 里由 `defaults.run.working-directory: skeleton` 承担这个切换。

### 4.1 一条命令跑完四道门禁（对应 CI job `adr12-compliance-scan`）

```bash
C:/opt/apache-maven-3.9.9/bin/mvn.cmd -o -B validate
```

会依次执行 `adr12-compliance-scan` / `s16-zero-derived-gate` /
`s18-contract-conformance` / `s19-sdk-surface-gate`。
四道里任意一道非 0 退出即构建失败。

### 4.2 各证人套件（逐字来自 `compliance/README.md`）

```bash
PY="C:/Users/lenovo/.workbuddy/binaries/python/versions/3.13.12/python.exe"

# 门禁本体（CI 里就是这么跑的）
"$PY" compliance/scan_compliance.py --repo-root . --report compliance/reports/ci-scan.json

# 证人：12 条断言，证明 ADR-12 门禁仍然承重
"$PY" compliance/tests/compliance_injection_test.py

# 证人：6 条断言，证明占位 owner 不等于 owner
"$PY" compliance/tests/owner_readiness_test.py

# 发布门禁：同上扫描，但占位 owner 一律判失败
"$PY" compliance/scan_compliance.py --repo-root . --require-named-owners
C:/opt/apache-maven-3.9.9/bin/mvn.cmd -Pgo-live validate

# 反向验证：每个 face 注入一次，产出证据报告
"$PY" compliance/run-reverse-verification.py

# 端到端：注入一个命中，证明 mvn package 失败且什么都没打出来
"$PY" compliance/tests/build-gate-injection.py

# --- S1-6 结构门禁 ---
"$PY" compliance/client-zero-derived-gate.py --repo-root .
"$PY" compliance/tests/zero_derived_gate_test.py      # 13 条断言

# --- S1-8 契约一致性门禁（需要 PyYAML；缺了会 exit 2，不会退化成正则继续报 PASS）---
"$PY" -m pip install "pyyaml>=6.0"
"$PY" compliance/contract-conformance-gate.py --repo-root .
"$PY" compliance/tests/contract_conformance_gate_test.py   # 18 条断言

# --- S1-9 SDK 表面门禁 ---
"$PY" compliance/sdk-surface-gate.py --repo-root .
"$PY" compliance/tests/sdk_surface_gate_test.py       # 7 个用例，含 X5 zip 截断陷阱
```

### 4.3 另两个 CI job 的本地等价命令（抄自对应 workflow，需 Maven / psql）

```bash
# 对应 CI job: crypto-adversarial
C:/opt/apache-maven-3.9.9/bin/mvn.cmd -pl dy-crypto -am test --batch-mode
bash verification/crypto/run_crypto_adversarial.sh

# 对应 CI job: rls-isolation（需要真 PG）
C:/opt/apache-maven-3.9.9/bin/mvn.cmd -B -pl dy-app -am test
bash verification/99_b12_run.sh
```

> 🛑🛑 **2026-09-30 修正 —— 本行原写的 `-Dtest='Rls*Test' -DfailIfNoTests=false` 是错的，
> 会让该 CI job 恒红，已由本机实测逐字复现。** 三条实测结论：
> 1. `-DfailIfNoTests=false` **对 default-test execution 不生效**。surefire 3.2.5 在
>    `-Dtest` 筛选"无匹配"时报的是 `Set -Dsurefire.failIfNoSpecifiedTests=false …`，
>    属性名对不上 ⇒ `-am` 连带的 common/tenancy/security/web/audit/config/crypto
>    会以 `No tests matching pattern "Rls*Test" were executed!` 整批失败。
> 2. **硬阻塞（关键）**：`dy-config/pom.xml` 的 `config-truth-source-gate` execution
>    把 `<failIfNoTests>true</failIfNoTests>` **写死在 pom 里**（设计意图：配置真相源门禁
>    必须被执行）。plugin 配置值**优先于**同名用户属性 ⇒ CLI 传 `-DfailIfNoTests=false`
>    **盖不住它**。实测：两个属性全传 ⇒ FAILURE；改用 `-Ddy.config.gate.skip=true` 逃生阀
>    ⇒ 仍 FAILURE（`No tests were executed! (Set -DfailIfNoTests=false …)`）。
> 3. **正解 = 不带 `-Dtest` 筛选，直接 `mvn -o -B -ntp -pl dy-app -am test`**。
>    本机实测 **9 模块全 SUCCESS**，`rls-isolation-gate` 110 例照常执行（`Tests run: 110` 逐字）。
>    附带收益：它与「全量回归」是同一条命令，CI 和本地不再有两套口径 ——
>    **而"两套口径"正是这类缺陷能潜伏至今的原因**（CI 命令从未在本机跑通过）。
>
> 📌 **为什么 Python 反向验证脚本的 `-Dtest=` 写法却是对的**：它们一律用
> `-pl dy-app`（**不带 `-am`**），因此**不会连带 dy-config**，也就碰不到那道硬门禁。
> 差别只在 `-am` 这一个开关 —— **教训：`-am` 会把"被牵连模块"的门禁一并带上，
> 对它们做 `-Dtest` 筛选等于要求"连带的门禁不许存在"**。

---

## 5. 对 `.gitignore` 的改动 —— 现在是**两层**，分工而非重复

> 🛑 **2026-09-30 重写**：仓库根上移到 `product-strategy/` 后，`.gitignore` 变成两层。
> 旧版本本节写的是"追加到根级 `.gitignore`"（当时根 = `skeleton/`），口径已过时。

| 文件 | 位置 | 职责 | 状态 |
|---|---|---|---|
| `skeleton/.gitignore` | 子树细化 | 骨架自身的构建产物与 IDE 杂项 | `[TRACKED]`，79 行，**未删改原有任何一行** |
| `.gitignore`（仓库根） | 全局规则 | 跨目录的通用忽略 + 根上移时的新增取舍 | `[TRACKED]`，78 行，**新建** |

两者是**分工**：根级管全局（`target/`、`*.class`、`node_modules/`、`dist/`、`_work/` 取舍等），
`frontends/.gitignore` 另有第三层管前端依赖与产物（已实测命中
`frontends/admin-web/node_modules`、`frontends/admin-web/dist` 等四条路径）。

### 5.1 根级 `.gitignore` 的取舍（逐条已在文件内注明理由）

| 规则 | 理由 |
|---|---|
| `compliance/reports/last-build*.json` | 构建产物。四个 `validate` 门禁每次构建都重写它们，提交只会制造无意义 diff |
| `*.iws` `.settings/` `.project` `.classpath` | 补 Eclipse 系 IDE 产物（原文件只有 IntelliJ / VS Code 系） |
| `ehthumbs.db` `Desktop.ini` | 补 OS 杂项 |
| `_work/quotes_incident_backup/`、`_work/backup_quotes/`、`_work_backups/` | **一次历史事故（报价单）的取证备份**，约 8.2 MB 且含整份源码树副本。属"某时点的快照"而非活代码；入库会让全仓搜索命中同一份代码的两份副本，并放大 clone 体积 |
| `_work/*_snapshots/`、`_work/*_inject/`、`_work/tmp/` | 反向验证的临时工作区与注入快照，每次运行重建 |
| `_work/_probe*.sql`、`_work/_probe*.py` | 过程日志与探针输出，可复算，不属证据 |

### 5.2 本轮的实测复核（不是照抄上轮结论）

| 复核项 | 实测结果 |
|---|---|
| `_work/` 两个**根锚点文件**是否被忽略 | **未被忽略**，且 `[TRACKED]` ⇒ 与 §0 的"根锚点已入库"互证 |
| `_work/_probe*` 是否有生产代码引用 | **无**。磁盘上已无 `_work/_probe*` 文件；源码/脚本中的 `_probe` 全部是 `compliance_injection_test.py` 内部的 `PROBE_PREFIXES` 命名约定常量，不指向任何真实文件 ⇒ 忽略规则安全 |
| 旧版"刻意保留 `_work/` 整目录"的说法 | **已作废**。当时担心 `_work/` 可能是真实源码；本轮确认其中的生产代码引用面（探针 SQL/PY）已不存在 ⇒ 改为按子路径选择性忽略 |

**刻意保留（不忽略）**：

- `compliance/reports/REVERSE-VERIFICATION-*.md` —— 反向验证报告是**证据**，必须可追溯。
- `compliance/reports/ci-scan.json`、`baseline.json`、`manual-scan.json`、`s16-*.json`、
  `last-golive.json` —— 性质介于证据与产物之间，**本次没有替它们做决定**，留待人工裁定。
  其中 `ci-scan.json` 本轮因 `frontends/client-mp/miniprogram` 新增进扫描根而更新（`hits` 仍为 0，`verdict` 仍 `PASS`），已单独提交以便留痕。

---

## 6. 本文件自身的状态（重要）

> 🛑 **2026-09-30 更正**：旧版本本节自称"不在任何 commit 里、需一次后续提交"，
> 那是 `ba22251` 时代的遗留说明。**现已入库**，本节仅保留说明意义。

| 项目 | 当前事实 |
|---|---|
| 本文件是否已入库 | **是**。`docs/CI-ENABLEMENT.md` 已随基线提交纳入版本库 |
| 当前最新提交 | `073b338`（本文件本轮更新后会再产生一次提交） |
| 当前分支 | `main`（已从 `master` 重命名，与 workflow 的 `pull_request.branches` 一致） |
| `git remote -v` | **空**（§0 人工项 1，尚未接远端） |

拿到本仓库后，请先确认：

```bash
git status --porcelain      # 应为空（工作区干净）
git log --oneline -1        # 应为 073b338 或之后的新提交
git remote -v               # 应为空（尚未接远端 —— 这是唯一还缺的"外部"前提）
git ls-files .github/workflows/   # 应返回 3 行
```
