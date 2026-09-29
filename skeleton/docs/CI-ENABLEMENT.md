# CI 门禁接入交接文档

> **一句话结论：仓库已经 git init 并完成了基线提交，但 CI 目前仍然拦不住任何合并。**
> Workflow 文件被提交 ≠ 门禁生效。要真正拦合并，还差 §2 的一次人工配置。
> 本文件里所有 job 名、命令都是从仓库现有文件里逐字抄来的，没有改写、没有补写。

---

## 0. 当前真实状态（已核实，非推断）

| 项目 | 状态 | 证据 |
|---|---|---|
| 仓库是 git 仓库 | **已完成** | `git rev-parse` 不再报 NOT_A_GIT_REPO；`git log --oneline -1` = `ba22251` |
| 有 remote | **未完成（本次刻意不做）** | `git remote -v` 输出为空 |
| workflow 已纳入版本库 | **已完成** | 三个 `.yml` 均在基线提交里 |
| workflow 在 GitHub 上跑过 | **未完成** | 没有 remote，GitHub 看不到这个仓库 |
| 三个 job 配为 required status check | **未完成（必须人工）** | 见 §2 |
| 默认分支名 | **需注意** | 本地 `git init` 建的是 `master`；三个 workflow 的 `pull_request.branches` 写的是 `main` |

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

**前提 A：仓库必须先推到 GitHub。** 本仓库还没有 remote（§0），这一步不在本次范围内。
**前提 B：这三个 check 必须先在该分支上跑过一次**，否则设置页的搜索框里搜不到它们。
三个 workflow 都带 `workflow_dispatch`，可以先手动跑一次。

### 步骤

1. 打开 GitHub 仓库页面 → **Settings**（需仓库 admin 权限）。
2. 左侧 **Branches**（路径：Settings → Branches）。
3. **Add branch protection rule**（若 `main` 已有规则，则点右侧 **Edit**）。
4. **Branch name pattern** 填 `main` —— 必须与三个 workflow 里
   `pull_request.branches: [main]` 一致；填成 `master` 的话 PR 触发不到门禁。
   （若决定让 `master` 当默认分支，则反过来：先改三个 workflow 的
   `pull_request.branches`，或统一重命名分支。二者必须一致，见 §0 最后一行。）
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
git init（已完成，本文件 §0）
   ↓
加 remote 并 push（未完成）
   ↓
GitHub 真正执行 workflow（未完成）
   ↓
把 3 个 job 配成 required status check（未完成 —— 这一步之前，一切都拦不住合并）
```

`compliance/README.md` 里还登记了第二条同源限制：

> The repository is not under git yet, so the workflow file is inert until it is
> committed to a remote that runs GitHub Actions.

本次 git init + 基线提交只解决了这条的「前半句」（不再是 inert 文件了），
**「committed to a remote」仍未解决**。

另有一条与 CI 无关的 P0 阻塞项，顺带记录，不要与 CI 混为一谈：
`compliance/owners.csv` 目前全是占位 owner（`role:dev-compliance-lead` 等），
`mvn -Pgo-live validate` 会因此判红 —— **这个红是门禁在正常工作，不是构建坏了**，
处置方式是研发侧写入实名 owner，不是加跳过开关。

---

## 4. 本地等价验证（离线，不需要 GitHub）

在仓库根目录执行。注意：本机的平台默认编码是 GBK，故显式钉 UTF-8
（`compliance/README.md`「Wordlists and their encoding」一节要求这么做）。

```bash
# Git Bash / PowerShell 通用前缀
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8
# PowerShell 里改写成：
#   $env:PYTHONUTF8="1"; $env:PYTHONIOENCODING="utf-8"
```

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

## 5. 本次对 `.gitignore` 的改动（追加，未删改原有任何一行）

根级 `.gitignore` 原本已有 `target/`、`*.class`、`logs/`、`*.log`、
`.idea/`、`*.iml`、`.vscode/`、`application-local.yml`、`.env` 等规则，
本次**只**追加了以下内容：

| 新增规则 | 理由 |
|---|---|
| `compliance/reports/last-build*.json` | 构建产物。四个 `validate` 门禁每次构建都重写它们，提交只会制造无意义 diff。**已实测命中全部 4 个文件** |
| `*.iws` `.settings/` `.project` `.classpath` | 补 Eclipse 系 IDE 产物（原文件只有 IntelliJ / VS Code 系） |
| `ehthumbs.db` `Desktop.ini` | 补 OS 杂项 |

**刻意保留（不忽略）**：

- `compliance/reports/REVERSE-VERIFICATION-*.md` —— 反向验证报告是**证据**，必须可追溯。
  已实测：`git check-ignore` 对该文件返回「未被忽略」。
- `compliance/reports/ci-scan.json`、`baseline.json`、`manual-scan.json`、`s16-*.json`、
  `last-golive.json` —— 性质介于证据与产物之间，**本次没有替它们做决定**，留待人工裁定。
  若日后要把它们一并忽略，请连同上面那句「保留 REVERSE-VERIFICATION-*.md」的约束一起评估。
- `_work/`、`_work_backups/` —— 这两个目录含 427 个 .java / 57 个 .sql / 25 个 .py，
  看起来像某条并行开发线的暂存副本，但也可能是真实源码。**没有把握时不替它做忽略决定**，
  本次照常纳入基线快照。人工确认是暂存垃圾后，再单独加规则并清理。

**`frontends/.gitignore` 已确认生效，本次未改动它**：
`git check-ignore -v` 实测显示 `frontends/admin-web/node_modules`、
`frontends/admin-web/dist`、`frontends/therapist-app/node_modules`、
`frontends/therapist-app/dist` 均由 `frontends/.gitignore` 命中。

---

## 6. 本文件自身的状态（重要）

本文件是在基线提交 `ba22251` **之后**才创建的，因此它**目前不在任何 commit 里**，
`git status` 会把它显示为未跟踪。要让它在 GitHub 上可见、可被别人读到，
还需要一次后续提交把它纳入版本库 —— 本次任务限制「只提交一次」，故未做。

拿到本仓库后，请先确认：

```bash
git status --porcelain      # 应能看到 docs/CI-ENABLEMENT.md 为未跟踪
git log --oneline -1        # 应为 ba22251
git remote -v               # 应为空（尚未接远端）
```
