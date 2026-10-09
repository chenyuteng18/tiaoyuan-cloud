# 四端前端工程骨架（契约驱动）

> 对应 `缺口修复总规划-2026-09-27.md` §三 **G-B**（标注「✅ 可以 / 可立即动工」）。
> 本轮落地的是 **B-1**（工程骨架 · 构建可跑 · 目录约定 · 环境配置分离）
> 与契约驱动端点层；**B-2/B-3/B-4** 见文末「阶段顺序」。
>
> 🛑 **标题里的"四端"**：本文件初版写的是"三端"，只有 Web ×2 + 小程序。**端 D
> （原生 Android · Kotlin）后加入，且已整端入库** —— 它是契约驱动的第二份
> 端 B 产物（同一份裁剪契约、同一批 token-roles，**不同语言与不同形态**）。
> 文件里其余仍写"三端"的地方（历史小节标题、纪律条目里的引用）**保留原文不追改**
> —— 它们描述的是当时那一轮的事实（例如"三端 Web/小程序"，原生端本就不在其射程内）。
> 判据与计数一律以本节表格为准。

## 1. 四端定义（逐条转录自 `contract/sdk-generator/generator-matrix.yaml`）

| 端 | 目录 | 形态 | 契约角色 | 可用 operation | 生成物 |
|---|---|---|---|---|---|
| 端 A 管理后台 | `admin-web/` | Vite + React + TS | `admin` | **39**（34 paths） | `src/contract/endpoints.ts` |
| 端 B 门店 / 经络师（Web） | `therapist-app/` | Vite + React + TS | `therapist`, `meridian` | **29**（26 paths） | `src/contract/endpoints.ts` |
| 端 C 客户端 | `client-mp/` | 微信小程序（原生） | `client` | **15**（14 paths） | `miniprogram/contract/endpoints.js` |
| 端 D 门店 / 经络师（原生） | `therapist-android/` | 原生 Android（Kotlin） | `therapist`, `meridian` | **29**（26 paths） | `app/src/main/kotlin/com/diaoyuanyun/therapist/contract/Endpoints.kt` |

**端 B 与端 D 的关系（一条契约、两份产物）**：二者读的是**同一份**裁剪契约
`contract/sdk-generator/_cut/therapist-app.openapi.yaml`，token-roles 也**完全相同**。
在生成器里它们**刻意是两个 target**（`therapist-app` / `therapist-android`），
因为 `--check` 按行报告结果，若 `id` 也相同会出现两行同名结果、既有门禁取行会取错。
⇒ **一个契约真相源，两条独立产物线**，互不 churn。

**角色准入的判定口径**：一个 operation 属于某端，当且仅当它的 `x-callable-roles`
与该端的 token-roles **有交集**。token-roles 取自 `generator-matrix.yaml`
（`client-mp: [client]` · `therapist-app: [therapist, meridian]` ·
`admin-web: [admin]` · **原生端同端 B**：`[therapist, meridian]`）。

## 2. 契约驱动：端点层是【生成】的，不是手抄的

```
contract/openapi-v1.0.0.yaml
  └─(sdk-generator/_sdk_pipeline.py 按 matrix 裁剪)→ contract/sdk-generator/_cut/<end>.openapi.yaml
        └─(frontends/tools/gen-endpoints.py 按 token-roles 过滤)→ 各端 contract/endpoints.{js,ts}
```

```bash
node frontends/tools/gen-endpoints.mjs           # 写出四端端点层（推荐入口）
node frontends/tools/gen-endpoints.mjs --check   # 只校验产物与契约一致（不写）
```

> 🛑 **不要改写成 `python frontends/tools/gen-endpoints.py`。** 本仓的解释器由
> `tools/py.mjs` 统一解析（Windows 上 `python` 与 `py` 会落到**两个不同解释器**，
> 且 `py` 启动器会读脚本首行 shebang 再改一次 ⇒ **探针与真调用不同形**）。
> 裸调在本机会 `No module named 'yaml'`；**这条写法曾经同时存在于三端 `package.json`、
> 5 处门禁报错文案、以及生成器烧进每份产物的文件头里**，见 §8.28 ⑤。

**为什么必须生成**：本仓已有一次同型失败，记在 `client-package/api/clientPaths.js`
的文件头（并酿成 S1-8 FACE 1 的立项动机）——手写的"客户端可达路径清单"会腐烂，
原 8 条里只有 2 条与冻结契约相符。腐烂有两种形态：**错名**（把不允许的写成允许）
和**漏项**（允许的没列上）。漏项尤其隐蔽：白名单是运行时强制的，漏一条 = 该
功能对客户直接不可用，而当时的门禁只校验"引注为真"、不校验"允许的都列上了"，
于是**长期不红**。

生成器因此自带 `--check`：契约一改而产物未重跑，检查即红。

## 3. B-1 验收：四端构建

| 端 | 命令 | 说明 |
|---|---|---|
| client-mp | `npm run build` → `node ../tools/build-check.mjs --end=client-mp` | 零第三方依赖 |
| therapist-app | `npm run build`（`tsc --noEmit && vite build`）· `npm run check:build` · `npm run check:x3` · `npm run check:x3-reverse` | 需 `npm install`；X-3 两组门禁 |
| admin-web | `npm run build`（`tsc --noEmit && vite build`）· `npm run check:build` · `npm run check:a` · `npm run check:a-reverse` | 需 `npm install` |
| therapist-android | `node tools/android-check.mjs`（**25 条判据**，纯 Node 零依赖）· `tools/android-reverse-check.mjs`（**43 组**受控注入）· 真编译 `./gradlew assembleDebug` | 🛑 门禁与真编译**刻意分离**：门禁只读「源码 + 生成物 + 契约」，**不依赖 Android SDK / JDK / 模拟器** |
| 四端通用 | `npm run check:build-reverse` → `node ../tools/build-reverse-check.mjs` | **35 组**受控注入，覆盖 `build-check` 的**全部 12 条判据**：`base-path-wiring`（R1–R6）· `cross-end-protocol`（R7–R10）· `pagination-protocol`（R11–R13）· `error-data-fields`（R14–R16）· `endpoint-reachability`（R17–R18）· `page-registry`（R19–R21）· `required-args-wired` / `required-args-carrier`（R22–R25）· `no-bare-python-script`（R26）· `ci-dependency-source-normalized`（R27–R28）· `ci-mvn-jobs-have-python`（R29）· `token-single-source`（**R30 + R35**）· `page-reachability`（R31）· `error-code-coverage`（R32–R34）。**常驻**；且能抓住「判据被删」与「判据的**覆盖面自证**失效」（R35）|

**Python 前置（四端门禁都吃这一条）**：

```bash
python -m pip install -r ../requirements.txt     # 从 skeleton/frontends 运行
```

🛑 **为什么一个前端骨架需要 Python**：端 C 的构建（`build-check.mjs --end=client-mp`）
与生成器的 `--check` 都是 Python 写的，且**刻意**要求一个真正的 YAML 解析器
（缺 PyYAML 一律 `exit 2`，**不**退化成正则后照常打印 PASS）。
依赖清单与完整理由见 `skeleton/requirements.txt`。

🛑 **本机（Windows）还要注意"哪个 Python"**：PATH 上的 `python` 未必含 PyYAML，
且 Windows 的 `py` 启动器会读脚本首行 shebang（`#!/usr/bin/env python`）→ 用 PATH 上的
`python` 重新解析 ⇒ `py -c "import yaml"` 与 `py <脚本>` **会落到两个不同的解释器**
（实测：前者 Python312 有 yaml，后者 3.13.12 没有）。两处都支持显式覆盖：

| 调用方 | 覆盖方式 |
|---|---|
| `frontends/tools/build-check.mjs` | 环境变量 `DY_PY=<绝对路径>`（候选顺序：`DY_PY` → 受管 venv → 受管裸解释器 → `python3` / `python` / `py`） |
| 根 `pom.xml` 的 exec 门禁 | `-Dcompliance.python.executable=<绝对路径>` |

**依赖安装（本机实测可用的一条命令）**：

```bash
npm install --ignore-scripts --no-audit --no-fund
```

`--ignore-scripts` 的原因不是"随便跳过"：esbuild 的 postinstall 用**同步**派生
子进程，在本环境被拦（`EBUSY`）；而平台包 `@esbuild/win32-x64` **自带**
`esbuild.exe`，跳过脚本后 `vite build` 依旧可用 —— 实测端 A / 端 B 均构建成功。

**2026-09-28 实测证据**：三端自检**全部 `exit=0`**；端 A `vite build`
`31 modules transformed` / `652ms`，端 B 同；端 C 三张词表扫 9 个文件 0 命中。

### 端 C 的"构建"为什么不是 vite/webpack（如实登记）

微信小程序的真实产物由**微信开发者工具**编译产出，它是一个 GUI 工具，
本机构建环境没有它。用通用打包器"产出"一个小程序目录，得到的只是"看起来像"
的产物 —— 那是假绿。故端 C 的构建做的是**工程侧可机械验证的那一半**：

1. 工程结构完整性；
2. 契约层与冻结契约一致（调生成器的 `--check`）；
3. **端 C 禁用词自检**（复用 `compliance/wordlists` 的 scan1/scan2/scan3 三张词表）；
4. `app.json` 页面清单与 `pages/` 目录实际一致（端 C）；
5. **真实构建**（端 A / 端 B）：`tsc --noEmit` + `vite build`。依赖未就绪时
   **报退出码 `3`（未验证）而不是跳过** —— 跳过会让"没装依赖"和"构建通过"
   在输出上无法区分，那正是本仓反复修的"静默通过"。

第 3 项值得单说：`compliance` 的三个扫描面 roots 原本只有 `client-package`，
而真实小程序工程在 `frontends/client-mp`，**不在那个根下** —— 即纪律覆盖的是**样例包**，
真正会被编译上传的工程是一个**无人扫描的词面**。在此之前若完全不扫，问题就一直存在；
故端 C 构建先主动复用同一批词表自检，让纪律在工程落地当天就成立。

**✅ B-3 已于 2026-09-28 完成**：`scan-manifest.json` 三面 roots 增至**两个**
（`client-package` + `frontends/client-mp/miniprogram`），`client-zero-derived-gate.py`
的扫描根同步改双根（`files_scanned` **8 → 17**）。🛑 过程中抓出一个设计漏洞并已修：
只校验"列出的根存在"**挡不住"把根从列表里删掉"**（剩下的根都在 ⇒ 门禁照常 PASS，
而扫描面悄悄少一半）。故新增 **`EXPECTED_CLIENT_ROOTS = 2`（根数量冻结，漂移即 exit 2）**
与 S1-6 证人 **W10**（正向断言 `files_scanned` 必大于样例包文件数；反向断言删根必须
exit 2 且不得打印 PASS）—— **W10 首跑即为红**，正是它抓出了这个漏洞。

### 退出码（沿用 compliance 的图例）

`0` 通过 · `1` 不通过 · `2` 配置缺失 · `3` **环境受限（该项未验证）**。

🛑 `3` 不是"通过"，也不只是理论值 —— **本仓踩过一次**：2026-09-28 初版把环境结论
写成"禁止 node 派生子进程"，**那是过宽的错判**（被拦的其实只有**同步**派生：
`execFileSync` / `spawnSync` 对任何命令都 `EBUSY`；**异步** `spawn` 正常，
`vite build` 与 `tsc` 都靠它跑通）。后果是**本可验证的契约一致性被判成未验证**，
形同一种少查。故：① 本脚本统一走异步 `spawn`；② 报 ENV-BLOCKED 时必须给出
**精确的受限面**（同步还是异步、缺哪个件），不能只写一句"环境受限"；
③ 报它而非"契约不一致" —— 否则排查会被引向完全错误的方向。

## 4. 环境配置分离

- **端 A / 端 B**：`src/env/index.ts`，只认 `VITE_API_BASE_URL`，缺失即**启动失败**。
  刻意不回落默认值 —— 配错的部署必须立刻停，而不是静默打到错误的库。
  🛑 该文件导出**两个不同的东西**，勿混用（第 56 条）：
  `getBaseUrl()` = **网关根**（运维视角，如 `https://dy.example.com`）；
  `getRequestBaseUrl()` = **出站 URL 前缀** = 网关根 + `API_BASE_PATH`（`/api/v1`）。
  拼 URL 一律用后者；`API_BASE_PATH` 来自**生成物**（机械转录契约 `servers[0].url`），不手写。
- **端 C**：`miniprogram/env.js`，按微信运行时的 `envVersion`
  （`develop` / `trial` / `release`）选基址。因为小程序上传的是**一份代码包**，
  体验版与正式版共用它；若上传前把地址写死成生产，体验版也会打到生产。

## 5. 与 `generator-matrix.yaml` 的差异（登记，不静默偏离）

| 项 | matrix 声明 | 骨架现状 | 处置 |
|---|---|---|---|
| 端 B 通道 | `typescript-fetch` | fetch（一致） | — |
| 端 A 通道 | `typescript-axios` | **fetch** | 阶段性：通道实现属 B-2（接入生成 SDK 时按该端生成器落通道）。在 SDK 接入前引入 axios 只是多一个未被使用的运行时依赖 |
| 端 C 通道 | 由端内实现 `wx.request` | `wx.request`（一致） | 与 matrix 注释逐字一致 |
| 端 D 端本身 | matrix **未覆盖原生端**（只有三端 Web/小程序，见 `gen-endpoints.py` 注释） | 原生 Android（Kotlin）**已整端入库** | 生成器**新增 kt target**：读**同一份** `_cut/therapist-app.openapi.yaml`（token-roles 同为 therapist ∪ meridian），产出 `Endpoints.kt`。故端 D 的契约来源与端 B 同源，**不是**第四份契约 |
| 端 D 数据层 | matrix 声明 Swift/Kotlin SDK 生成（原生双端） | Kotlin **DTO 手写**，但"端点 → 响应 schema"由生成物 `Endpoint.dataSchema` 提供 | 冻结契约里没有原生 SDK 生成器；手写 DTO 的漂移面由判据 `contract-schema-fields`（响应 DTO ↔ 契约 schema 双向相等）+ `gson-reflect-surface`（R8/Gson 反射面）钉住 |
| 三端 SDK 产物 | `contract/sdk/<端>` | 已生成（9/27）但**未接入工程** | B-2 |

## 6. 阶段顺序（照规划 §3.3，避免返工）

**B-1/B-2（本轮与下一步）→ G-A 收口（`agreement` / `case_archive` 通路）→ B-3/B-4 与页面填充。**

端 A/B 的门店 / 员工 / 设备 / 量表数据面依赖 G-A 的通路。
**不要把业务页面填充排在 G-A 之前** —— 否则会做出"能点但取不到数"的页面。
故三端首页当前只显示"契约有没有正确接到这一端"，不做业务页面。

## 7. 🛑 与 `skeleton/README.md` §六「明确不做」的冲突（如实登记，不代拍）

`skeleton/README.md` §六 Non-goals 的清单里**逐字列有「前端」**，而本工程的立项
依据是 `缺口修复总规划-2026-09-27.md` §三（标题逐字为「G-B：三端前端工程骨架
（契约驱动）—— 可立即动工」，表格标记「✅ **可以**」）。

两份文档日期不同、结论相反。**本轮按后者动工**（后裁定覆盖先裁定），并在此登记
冲突待追认。参照 §六 同节已有的处置范式（"上一条的准确含义"），建议的澄清措辞是：

> 「不做」的是**在后端 Spring Boot 骨架内实现前端 UI**，
> 不是**契约驱动的三端工程骨架** —— 后者的全部前置（冻结契约 / 三端裁剪契约 /
> 三端 SDK / `client-package` 纪律件 / generator-matrix 角色声明）均已就位。

⚠️ 该措辞**尚未经裁定**，本文件不把它写成"已裁定"。

## 8. 🛑 三十三条写作/路径/判据/管道纪律（由本仓第 50、51、52、53、54、55、56、57、58、59、60、61、62、63、64、65、66、67、68、69、70、71、72、73、76、77、78、79、80、83、84、85 条系统性缺陷逼出，勿回退）

### 8.1 客户端包内【不得写出禁词原文】—— 一律用指代（第 51 条）

**事实（实测口径，非估计）**：ADR-12 的三张词表对客户端包是**全量扫描且不剥注释**的。
`compliance/scan_compliance.py` 的 `scan_face()` 逐行直接匹配，**不剥 `/* */`、也不剥 `//`**；
词表的 `SCOPE` 段逐字声明本面「applied to the WHOLE client package with **no path allow-list**」。
也就是说：**在客户端包的源码注释里解释"某个词为什么被禁"时写出那个词，本身就是一次命中**，
会让 `npm run build` 与 CI 的 ADR-12 门禁**同时**变红。

**为什么会踩**：最小复现是"我在注释里说明这条纪律"—— 要说明它就得举反例，一举反例就命中。
🛑 本人在同一次收口中**连续踩了三次**（写「R2 组点名的归因说法」、把组名写成「与医学宣称相关」、
在 `daily-report.js` 写「连续未填报」），每次都以为"这次是在解释、不算文案"。

**纪律（照抄执行，不要自行发挥）**：需要指向某个被禁概念时，**只写指代**：

| 允许写 | 不要写 |
|---|---|
| `R1 组` / `R2 组`（契约 x-wording-discipline 的词条组编号） | 该组里的任何一个词 |
| `③ 组` / `④ 组`（契约 x-field-groups 的字段组编号） | 组内字段名 |
| `扫描面 1` / `扫描面 2` / `扫描面 3`（ADR-12） | 该面词表里的任何一个词 |
| `W-1` / `W-2` / `W-3`（PRD §2.6.2 的约束编号） | 编号所约束的那类措辞的原文 |
| 条款号 / 规则号 / config 号 / 函数名 | 用一句自然语言"复述"那条规则里的词 |

🛑 **"换个说法"不等于"不引原文"**：只要句子读起来还能让读者知道那个词是什么，
就极可能已经命中。**判据是"整份文件里不出现该词"，不是"我这次是在解释"。**

**两侧必须同时复验**（`build-check.mjs` 与主扫描器**同口径**，这是第 51 条的修法本体）：

```bash
cd frontends/client-mp && node ../tools/build-check.mjs --end=client-mp   # 期望 BUILD OK / exit 0
cd ../..                && python compliance/scan_compliance.py --repo-root .  # 期望 PASS violations=0
```

### 8.2 `package.json` 的每一条 `scripts` 必须在【它自己的目录下】真跑过（第 50 条）

三端脚本曾一律写 `../../tools/...`，而 `skeleton/tools/` **不存在**（真实位置是
`frontends/tools/`，相对三端目录只须上一层 `../tools/`）⇒ 6 条脚本**从未跑通过**，
却因"此前所有验证都由仓库根直接调脚本"而长期无人发现。**共修正 7 处**
（`admin-web` 3 · `therapist-app` 3 · `client-mp` 2 中的 2 条 · 本文件 §3 表格 1）。

🛑 **通用规则**：跨目录相对路径**不能用审阅代替执行** —— `../../` 与 `../` 在肉眼审阅里
同样"看着合理"，只有**实际执行**能证伪。新增或修改任何 `scripts` 后，必须在**该端目录下**
逐条跑一次并记录退出码。

### 8.3 一处必须写进文件的**反向**发现（端 C 的 `real-build` 是"不适用"，不是"通过"）

`build-check.mjs` 对端 C 输出 `– real-build: 本端不适用（…无通用打包器…）`。
这条**不是**失败项也不是通过项，而是**如实登记的未验证面**（对应退出码图例里的 `3` 语义）。
不要把它读成"端 C 构建已验证"；端 C 的真实验证只覆盖**结构与契约/词表**这三项。

### 8.4 门禁判"必须被使用"类约束时，判【语法形态】，不判"词是否出现"（第 52 条）

第 41 条的载体是 SQL 函数体内 `RAISE` 消息的字符串，第 52 条的载体是 **TypeScript 语句**
—— 同一族缺陷换了宿主：**判的是"词有没有出现"，不是"行为有没有发生"**。

端 B 的 `x3-check.mjs` 首次全绿，但受控注入 **I5（`void assertCanCall;`，只提及不调用）漏过**，
一下暴露三条判据同病：

| 判据 | 错误写法（判词） | 能被什么满足 | 正确写法（判形态） |
|---|---|---|---|
| `x3-wired`（必须调用） | `includes('assertCanCall')` | `void assertCanCall;` | `assertCanCall\s*\(\s*[^)\s][^)]*\)`（带实参的真调用） |
| `x3-single-authority`（必须构成判断） | `/['"]meridian['"]/` | `void "meridian";` | 5 条比较/成员判定正则（`==`/`===`、`.includes()`、`.indexOf()`、查表） |
| `x3-no-hardcoded-list`（禁止清单） | `includes(row)` | `void ['F1'];` | 数组字面量且**至少 2 个端点行号** |

**通用规则（两条，方向相反，别混用）**：

- 约束是「**禁止出现**」⇒ 用全文 `includes()` / 正则即可（端 C 禁用词扫描属这一类）。
- 约束是「**必须被使用 / 必须构成某种语法结构**」⇒ **必须判语法形态**。
  「代码里出现了某个名字」永远不等于「那个名字被使用了」。

🛑 **连带纪律（注入用例本身也要"真实"）**：I4 必须注入 `role === "meridian"`（真实判断形态）。
若按最初写法注入 `void "meridian"`，会得到一个**假红**——门禁确实报红了，
但报的**不是它该报的原因**（这正是第 36 条那类"反向验证自身的元层缺陷"）。
**I5 已保留为永久回归用例**，它正是当初漏过的那一组。

🛑 **记住这条的来历**：它不是被"门禁全绿"发现的 —— **门禁首跑就是全绿**。
一个全绿的门禁**可能只是因为它从来没被真正违反过**；
只有**受控注入**能回答"这个绿是有条件的绿吗"。

### 8.5 判据还要能证明【它认识的东西覆盖了全部】（第 53 条）

第 52 条问的是"判据够不够强"（错的代码能不能过）；
第 53 条问的是"判据够不够**宽**"（新写法会不会绕过它）。
两者是**同族的两个方向**：前者"判错"，后者"没判"。

端 B 的 `x3-check.mjs` ⑦ 初版只认**字面量** `/requires:\s*'([^']+)'/`。
「专属动作」导航项改成 `requires: firstSoleEndpointId()`（机械推导）后，
该项**根本没被检查** —— 而门禁输出仍是
`✓ nav: 导航项依赖的 2 个端点都在本端生成物里`：
**数字是错的，却看不出来**（实际 3 项）。这比"静默不报"更坏，因为
**门禁绿着，还在打印一个可信的假证据**。

**修法两层（第 ② 层是通用手法）**：

1. **同时认多种形态，各自判它该是什么**：
   - 字面量 `requires: 'getCustomer'` ⇒ 该 id **必须存在于生成物**；
   - 调用形态 `requires: firstSoleEndpointId()` ⇒ 函数名必须在门禁**白名单**内、
     必须**定义在 `access.ts`**、其实现**必须引用生成物**（否则无法证明它是"现算"）。
2. **交叉核对覆盖面**：**判据认出的条数必须等于实际非 null 的 `requires` 条数**，
   不等即报红并提示"出现了第三种写法，本判据不会检查它"。
   ⇒ **判据不该只"检查它认识的东西"，还应"证明它认识的东西覆盖了全部"。**

🛑 **两处自我踩坑（都写进了代码注释）**：

- 初版直接扫整个 `App.tsx`，把 `interface NavItem` 的
  `readonly requires: string | null;` 也数成了"一条依赖" ⇒ 交叉核对**首跑即误报**。
  修法：先**圈出 `NAV` 数组本体**再计数。
- 「专属动作」的推导函数不该写成 `App.tsx` 里的局部 `soleRequires()` ——
  那会让**依赖推导逻辑出现第二个位置**（与单一权威面同源的风险）。
  修法：一律落 `access.ts`。

**回归用例**：反向验证 **I8** 把 `requires` 换成内联三元（第三种形态），
断言门禁必须因此报红 —— 它是那条交叉核对的永久守卫。

### 8.6 契约 → 生成器 → 前端 是一条管道：每类被声明过的元信息都必须有【可机械验证的落点】（第 54 条）

前四条（8.2~8.5）问的是"路径对不对 / 口号一不一致 / 判据强不强 / 判据宽不宽"。
第 54 条问的是**第五件事**：**契约里写下的约束，和前端实际拿到的约束，是不是同一份**。

端 A 落地时撞见的形态：`tools/gen-endpoints.py` 初版只转出
`id / row / method / path / grantedRoles`，把端 A 真正吃紧的 4 类操作级 `x-` 键
（`x-row-scope` · `x-super-admin-only` · `x-ruling-pending` · `x-frontier`）
**以及契约顶层 `x-roles`（`admin` 的子档位 `[manager, area, hq]`）一个都没转**。

🛑 **为什么它比前四条更难发现**：**它的失效是完全静默的** ——
生成物看着正常、`gen-endpoints.py --check` 也绿、三端构建全过。
只有**人肉逐条读契约**才发现"界面少了一道约束"。
它属于同一族缺陷的**第三个接缝**：第 41 条在 SQL 字符串、第 52/53 条在 TS 判据、
**第 54 条在生成器**。

🛑 **同一份生成器，在端 B 无害、在端 A 致命**：

| | 端 B `therapist-app` | 端 A `admin-web` |
|---|---|---|
| 端点级角色 | 有分叉（`both=22 / meridian-only=7`） | **无分叉**（实测 `39/39` 全 `["admin"]`） |
| `grantedRoles`（初版唯一转出的元信息） | **就是全部边界** | **恒为 `["admin"]`，检它等于没检** |
| 真实边界所在 | 端点级角色（X-3 矩阵） | **那 4 类被丢掉的 `x-` 键**（行级范围 / 仅超管 / 待冻结 / 待裁定） |

⇒ 端 B 靠 X-3 门禁就够；端 A 必须另立**元信息完整性门禁** `tools/a-check.mjs`。

**修法（两层，缺一不可）**：

1. **让生成器转出**：`gen-endpoints.py` 新增 `OP_X_KEYS` / `OPTIONAL_FIELDS` /
   `load_role_expansion()`（读契约顶层 `x-roles` 产出 `ROLE_EXPANSION`）/
   `_opt_lines()`（缺省不写键，保持生成物最小）/ `_render_role_expansion()`；
   `Endpoint` 接口新增 5 个可选字段 + `RoleExpansion` + `ROLE_EXPANSION`
   （向后兼容：端 B/C 仅新增声明、无行为变更，`--check` 三端仍 `[OK]`）。
2. **让门禁咬住契约**：`a-check.mjs` 的 **`xkey-coverage`** 把契约里
   **实际出现的每一个**操作级 `x-` 键枚举一遍（11 类），
   或已转出、或在白名单里**具名**豁免。
   ⇒ 契约将来新增第 5 类 `x-` 键会**自己报红**，而不是静默漏过。

🛑 **本轮我自己又踩了三次同型坑（全部由门禁首跑 / 反向验证抓出，不是由"全绿"抓出）**：

- **① 门禁自己的解析与判据不咬合**：初版解析生成物时**没把 `grantedRoles` 填进
  entry** ⇒ `xkey-transcribed` 报假红（`x-callable-roles 应落到 grantedRoles`）。
  **是门禁自己错了**。修法同时新增 `parse-fields` 判据，**交叉证明**
  `TRANSOUT` 里每个目标字段都真的解析出来了。
- **② 新判据首跑假红 4 条**：`parse-coverage` 拿契约 `x-contract-row` 与生成物 `row`
  做**逐值集合比对**，结果报"漏转 D5"+"凭空造 D5-a/b/c"。
  真实建模是：契约的 `x-contract-row` 是**合同行**（`D5` 一行对应
  **D5-a/D5-b/D5-c 三个操作**），生成物的 `row` 是**子档位细化**、**不是字面拷贝**。
  修法 = 改成**三层比对**（操作数相等 / 生成物 row 去 `-x` 后缀须命中契约行 /
  契约每行须至少一个子档位）。
- **③ 第 52 条的第三个宿主**：反向验证 **I5/I7 双双漏过** ——
  `scope-wired` 用 `scopeText.includes('ENDPOINTS')`（被
  `for (const e of ENDPOINTS)` 这类**使用处**满足）；
  `role-expansion` 用 `/ROLE_EXPANSION/.test(genText)`（把
  `export const ROLE_EXPANSION` 改名成 `ROLE_EXPANSION_UNUSED` 后，
  **字符串里仍含该词**）。
  修法 = 判**导入形态**（必须 `import { ... } from './endpoints'` 且含 `ENDPOINTS`/`endpointById`）
  与**导出形态**（必须存在 `export const ROLE_EXPANSION:`）。

**回归用例**：反向验证 `a-reverse-check.mjs` **14 组**受控注入
（含 I1/I2/I3 删元信息、I4 硬编码清单、I5 摘导入、I6/I7 手写子档位、
I8/I9 伪造端点/行、I10/I13 未完结端点当既定事实用 / 只用词满足、
I11/I12 令牌键名第二处定义、I14 导航 requires 第三种形态），
断言每组必红、逐字节还原后必绿 ⇒ **14/14 PASS / 还原后 `exit=0`**。

### 8.7 判据的「覆盖面」必须跟上本仓**自己规定的写法**：太窄 ⇒ 假红 ⇒ 判据被删（第 53 条的第二次发作）

第 53 条（§8.5）讲的是"**判据太宽**"（被词满足）。它的**孪生形态**是"**判据太窄**"：
判据没跟上本仓自己定下的写法，把**完全合法**的实现判红。

🛑 **本轮实测**（端 A 落地时撞见，是同一族缺陷在**同一判据上的第三次发作**）：

| | 形态 | 表现 | 后果 |
|---|---|---|---|
| 第 52 条（§8.3） | 判据**太宽** | 被字面量/关键词满足 | **假绿** —— 错的东西被放过 |
| 第 53 条（§8.5） | 判据**覆盖面没跟上** | 新增写法不被识别 | 静默漏检 |
| **本条** | 判据**太窄** | 本仓规定的合法写法被判红 | **假红** |

🛑 **为什么"假红"比"假绿"更危险，值得单独记一条**
----------------------------------------------------------------------------
假绿是"错的东西没被抓"，假红是"**对的东西被抓**"。假红的下场不是修代码，
而是**有人把判据删掉 / 加 `// eslint-disable` 式的绕过** —— 那才是真失效。
故判据必须与本仓的**权威约定**对齐，不能各写一套。

🛑 **具体撞见的两处**
----------------------------------------------------------------------------
1. **`unsettled-surfaced`（⑦ 判据）**：初版只认
   `unsettledOf` / `unsettledEndpoints` / `frontier` / `rulingPending` 四词。
   而本仓在 `services/domain.ts` 里**逐字规定**："页面不得各自手写判断，
   一律走 `contractNoteOf` 统一出口"。于是页面写
   `contractNoteOf('approveRefund')` + `<UnsettledBar items={note.unsettled} />`
   ——**完全合法**却被判红。
2. **`nav-requires`（⑨ 判据，本轮新增）**：端 A 的 `requires` 恒真
   （39/39 端点均授予 admin），故**"导航项引用了不存在的端点"不会导致导航项消失**，
   只有点进去才报错。必须有一条判据钉住"每个 `requires` 都能在生成物里查到"。

🛑 **修法：三种合法形态的【调用判定】+ 计数等式**
----------------------------------------------------------------------------
`unsettled-surfaced` 重写为 —— 引用了未完结端点 id 的文件必须至少命中之一：

- (a) 调用契约层枚举：`unsettledOf(` / `unsettledEndpoints(`
- (b) 调用统一出口且**参数逐字是未完结端点 id**：`contractNoteOf('approveRefund')`
      🛑 **参数必须绑定未完结集合** —— 若只判 `contractNoteOf(` 出现，
      那 `contractNoteOf('getCustomer')` 也会过，就退化成第 52 条的词满足。
- (c) 渲染提示条：`<UnsettledBar ...>`

并且**判据先剥注释**（第 41/51 条教训：注释里的词不算标注）。

`nav-requires` 的两条式子 + **计数等式**：`requires` 只有两种合法形态 ——
① 生成物里存在的端点 id 字面量；② 契约层导出的推导函数调用 `requires: fn()`。
② + ① 的条数必须等于 NAV 里非 null 的 `requires` 条数** ——
等式是防"新增第三种写法（内联三元 / 拼接）导致两条式子都不命中、
于是'没查到'被当成'没有问题'"（第 53 条的静默漏检）。

🛑 **一个必须点明的边界：外壳文件不写端点 id 字面量，不是"绕过判据"**
----------------------------------------------------------------------------
`App.tsx` 是外壳，它的导航表**无从渲染任何提示条**。若为了过 `unsettled-surfaced`
而在导航表里写一行"标注"，那行标注不产生任何界面效果 —— 等于把判据变成**纸面合规**。
本轮的处理是：**让外壳真实承担横切职责** —— 在导航下方渲染一条**全局未完结提示**
（进入本端第一眼就知道"这里有 8 个地方契约还没冻结"），
其数据源是 `unsettledEndpoints()`（枚举函数，不是写死某个 id）。
⇒ 这既是**真实的界面行为**，也让判据命中"真有东西渲染"，而不是一行注释。
另一个由判据**逼出来**的纪律：`scope.ts` 新增 `firstFrontierEndpointId()` 推导函数，
让"未完结总览"导航项的依赖**不出现端点 id 字面量**（契约清空未完结声明时自动降级为 `null`）。

**回归用例（本轮新增 6 组，全集 16 组）**：
- **I13**：只用**注释里的词** + `contractNoteOf('getCustomer')`（**普通**端点）
  满足 `unsettled-surfaced` ⇒ 断言必红（证明判据不再"认词"、且参数绑定集合）；
- **I14**：给 NAV 注入**第三种形态**的 `requires`（内联三元 + 拼接调用）⇒
  断言 `nav-requires` 必红（证明计数等式有效）；
- **I15**：把某个**已封装**的端点从界面上摘掉 ⇒ `endpoint-reachability` 必红；
- **I16**：让页面**绕过 services 层直接出站** ⇒ `endpoint-reachability` 必红；
- **I11/I12**（上一轮加入）：令牌键名的第二处定义 ⇒ `token-single-source` 必红。

### 8.8 「页面覆盖 N 个端点」是可自我声称的 —— 必须有一条**触达链**判据（第 52 条在"任务书"上的宿主）

任务书写「页面覆盖 39 个端点」，但**"覆盖"没有任何机械含义**：
id 字面量出现在文件里、写进类型声明、放在注释里 —— 都会让"看起来覆盖了"。
本仓第 52 条的教训「**代码里出现了某个名字 ≠ 那个名字被使用了**」在这里换了宿主：
宿主不是判据、不是 SQL、不是生成器，而是**任务书自身的验收标准**。

🛑 **本轮实测：这个缺口是真实存在的，不是理论风险**
----------------------------------------------------------------------------
写完 8 个页面后逐条核验 39 个端点，发现 **D 域 5 个端点**是"**封装了但从未接上界面**"：

| 端点 | 封装函数 | 状态 |
|---|---|---|
| D1 `createVisit` | `createVisit()` | 无页面调用 |
| D3 `submitDailyReport` | `submitDailyReportAsStaff()` | 无页面调用 |
| D5-a `createPlan` | `createPlan()` | 无页面调用 |
| D5-b `getPlan` | `getPlan()` | 无页面调用 |
| D6 `createDeviceDispatch` | `createDeviceDispatch()` | 无页面调用 |

⇒ 在那之前，"页面覆盖 39 个端点"**是自我声称**。补入 5 个入口后才成立。

🛑 **修法：新建 ⑩ `endpoint-reachability`（两层调用链 + 计数等式）**
----------------------------------------------------------------------------
- **① 出站层**必须存在 `call<T>('<id>'` **调用形态**（不是"id 出现过"）；
- **② 封装函数**必须被某个 `pages/` 或 `App.tsx` 以 `fn(` **调用形态**使用；
- **计数等式**：① 的命中数必须等于生成物端点总数（第 53/55 条教训）；
- **分层纪律**（顺带被这条判据守着）：出站（`call(`）只允许出现在 `services/` 层，
  **页面/外壳不得直接出站** —— 否则"某端点被哪个服务封装"不再可查。

🛑 **出站层是 `services/` 整个目录，不是只有 `domain.ts`** —— 这条首跑就被判据自己抓出来：
初版把出站扫描写死成 `services/domain.ts` 一个文件 ⇒ 首跑报
「`authLogin` / `authMe` 未以出站调用形态出现」。
🛑 **那是判据太窄（第 55 条），不是代码错**：A1/A2 合法地定义在 `services/session.ts`
（会话层的职责就是"A1→A2 取档位"），本仓并没有"所有出站必须写在 domain.ts"这条纪律。
⇒ 修法**不是**把 A1/A2 搬进 `domain.ts`（那会让会话层失去职责完整性），
而是**把出站层的定义写成本仓实际的样子：`services/` 整个目录**。

🛑 **顺带修掉的一个门禁元层缺陷（本仓第 24 条的复发）**
----------------------------------------------------------------------------
`a-reverse-check.mjs` 的 I10/I13 会**新建**临时页再删。本轮该脚本在一次运行中被中断，
临时页留在源树里 ⇒ `unsettled-surfaced` **永远红** ⇒ 下次跑脚本时
`ABORT: 基线门禁不是绿的` —— 而**报错指向"先修好再跑"，真实原因（残骸）完全看不出来**。
这正是第 24 条的形态：**检验工具被中断后，把自己的残骸当成了被检对象的错误**。
⇒ 修法：脚本**每次启动先清掉自己命名空间下的临时文件**（只删自己创造的
`__probe_*.tsx` 固定名，绝不宽泛删除）。

🛑 **通用规则**：
**凡是"契约写了而前端拿不到"的约束，失效应默认假定为静默**，
只能由"把契约里出现的键枚举一遍"这类**覆盖面型判据**抓住；
且生成管道里**每类被声明过的元信息都必须有一个可机械验证的落点**。

### 8.9 跨端「隐式协议」必须变成生成物常量：写在注释里的约定等于没有约定（第 56 条）

🛑 **一个此前从未被机械守住的横切面**
----------------------------------------------------------------------------
契约的 `paths` 键是 `/auth/me`，而**真实 URL 是 `/api/v1/auth/me`** ——
前缀由 `servers[0].url` 承载（契约第 43 行，description 逐字写着
「Base Path（契约 §2.0 全局约定）」）。

**后端这一侧早就意识到了**，`AuthMeController` 的类注释逐字写着：

> 本类<b>不得</b>只写 `@RequestMapping("/auth/me")`：那会让端点在 `/auth/me` 落地，
> 而三端 UI 按契约请求 `/api/v1/auth/me` 会拿到 404 ——
> **且不会有任何测试红，因为单测直调控制器方法、不经过路由。**

后端也确实有机械守卫：`EndpointCoverageLedgerTest` 把契约 path 与真实 Spring
注解路由**逐条比对**（45 operationId = 41 已实现 ∪ 4 出范围登记）。

**但前端这一侧此前什么都没有**，而且方向刚好相反：

| 侧 | 出站/路由的组成 | 被机械守住？ |
|---|---|---|
| 后端 | `@RequestMapping("/api/v1")` + 方法级 path（**写全**） | ✅ `EndpointCoverageLedgerTest` |
| 前端 | `env.baseUrl` + `endpoint.path`（`path` **不含** `/api/v1`） | ❌ 无 |

而"baseUrl 只到网关根"这件事，此前**只写在三份 env 的注释里**：

> 端 C `env.js`：`// 契约 servers.url = /api/v1，故基址只到网关根。`

🛑 **这是一句不可执行的话**。运维按注释把 `baseUrl` 配成网关根，
三端出站就落到 `/auth/me` 而非 `/api/v1/auth/me` ⇒ **全量 404**；
而 `check:a` / `check:x3` / `build-check` / `tsc` / `vite` **全部仍绿**
—— 它们从不发真实请求。

🛑 **还有一个同族的 dev 期版本（本轮一并修掉）**
----------------------------------------------------------------------------
两端 `vite.config.ts` 的 dev 代理键写的是 `'/api'`，而出站路径是 `/api/v1/...`。
Vite 的 `server.proxy` 是**前缀匹配**，`'/api'` 确实能匹配 `/api/v1/...` —— 但它把
`/api/v1` 也归进了这条规则，而**规则写窄成 `'/api/v1'` 才是与契约一致的意图**；
更危险的是反向情形：有人把 base path 改成 `/gateway/v1` 而代理键留着 `'/api'`，
请求**根本不进这条规则** ⇒ dev 期全量 404，`npm run dev` 不报错、构建自检也不报错。
故代理键也被判据钉住。

🛑 **修法：把 `servers[0].url` 从"注释里的约定"抬成"生成物里的常量"**
----------------------------------------------------------------------------
1. **生成器**（`gen-endpoints.py`）新增 `load_api_base_path()`，读裁剪契约的
   `servers[0].url` 并转录为生成物常量 —— 三端生成物各自导出
   `API_BASE_PATH`（TS）/ `API_BASE_PATH`（CJS）；缺 `servers` 直接 `MISCONFIGURED` 退出。
2. **三端环境层**各自导出**出站前缀**（端 A/B：`getRequestBaseUrl()`；
   端 C：`ENV.requestBaseUrl` getter），其值 = 网关根 + `API_BASE_PATH`。
   两者的**区别被写进函数名与注释**（`getBaseUrl()` 是运维视角的网关根，
   `getRequestBaseUrl()` 才是拼 URL 用的），不再靠一句话传递。
3. **三端出站层**一律改写为 `出站前缀 + endpoint.path`。
4. **两端 vite 代理键**改为 `'/api/v1'`。

🛑 **判据：`base-path-wiring`（三端共用的 `build-check.mjs`，四层）**
----------------------------------------------------------------------------
| 层 | 判什么 |
|---|---|
| ① 出站层 | 圈定 `let url = ...` 组装表达式，要求其含前缀标识（**不得**是裸 `baseUrl`） |
| ② 环境层 | 必须导出出站前缀，且其值**引用生成物常量**，**不得**手写字面量 |
| ③ 生成物 | `API_BASE_PATH` 必须存在、取值形态合法 |
| ④ vite | dev 代理键必须含该 Base Path（仅端 A/B） |

🛑 **判据形态纪律（第 55 条）**：初版 ① 只认"前缀 + `(`"（函数调用），
把端 C 的 `ENV.requestBaseUrl`（**getter 属性访问**）判成"未体现前缀"——
**又是假红**。修法不是改端 C（getter 是本仓自己规定的合法写法），
而是**改成"圈定 URL 组装表达式再判其成分"**，两种写法都认。
同时 ② 的"是否手写字面量"判定必须**先剥注释** ——
否则一句 `// 契约 servers.url = "/api/v1"` 就会被判红（注释里提到路径不是硬编码，
反而是本仓鼓励的写法）。这与 §3 词表判据**刻意不剥注释**并不矛盾：
词表判"禁词是否出现在客户端包里"（注释也是包的一部分），
`base-path-wiring` 判"代码里是否手写了常量/怎么拼 URL"（语义判定，须剥注释）。

🛑 **同族第三处宿主：反向验证必须能抓住"判据被删"**
----------------------------------------------------------------------------
新建常驻 `tools/build-reverse-check.mjs`（6 组，R1–R6），并实测：
把整段 `base-path-wiring` 判据删掉后，**6 组用例全部变"漏过"、脚本 FAIL（0/6）**。
⇒ 第 55 条最危险的下场（**判据被删**）在 base path 这一面上已被机械封堵：
**判据可以被删，但删不掉它的证人**。

🛑 **回归用例与数字（逐字）**
----------------------------------------------------------------------------
- `build-reverse-check`：**6/6**，还原后三端 `build-check` 全绿、无残留。
- 删判据模拟：**0/6 ⇒ FAIL**（证明它守的是判据本身）。
- 端 A `check:a` **14/14** · 反向验证 **16/16**；
  端 B `check:x3` **9/9** · 反向验证 **8/8**；
  端 C `build` BUILD OK；`check:build` 三端全绿；`check:contract` `[OK] 15/29/39`。
- 后端全量回归：`TOTAL 1199   failures=0 errors=0 skipped=0`（逐模块 10/39/48/58/37/37/29/941）。

🛑 **通用规则（第 56 条）**：
**凡是被多端共同遵守的协议片段（前缀、信封字段、鉴权头名、幂等键构成），
若它只写在注释或口头约定里，就等于没有约定** ——
必须抬成**从契约机械转录的生成物常量**，并由一条**判据 + 一条反向验证**同时钉住。
判据防代码漂移，反向验证防**判据本身**被改窄或删除。

---

### 8.10 跨端「隐式协议」不止 URL 前缀一类：四类都要抬成生成物常量（第 57 条）

§8.9 把跨端协议归纳为**四类** —— ① URL 前缀、② 信封字段、③ 鉴权头名、
④ 幂等键构成 —— 但当时**只修了 ①**。本条把 ②③④ 逐条核查，抓出
**两处真缺口 + 一处"§8.9 修法自己引入的反模式"**。

#### 8.10.1 缺口一：`X-Trace-Id` 是一个【契约里一个字都没有】的跨端头

实测它在 **6 处**硬编码：

| 位置 | 写法 |
|---|---|
| 后端 `dy-web/.../trace/TraceIdFilter.java:29` | `public static final String HEADER = "X-Trace-Id";` |
| 后端 `.../observability/ObservabilityMdcFilter.java` | 同类 |
| 后端 `dy-tenancy/.../TenantContextFilter.java` | 读租户头与鉴权头（另一类） |
| 端 A `admin-web/src/api/client.ts` | `res.headers.get('X-Trace-Id')` |
| 端 B `therapist-app/src/api/client.ts` | `res.headers.get('X-Trace-Id')` |
| 端 C `client-mp/miniprogram/services/request.js` | `res.header['X-Trace-Id']` |

而 `contract/` 全目录 **grep 零命中**：既无 `securitySchemes`、无 `parameters`
声明、**连 prose 都没有**（`x-global-conventions.auth` 只写鉴权）。
⇒ 改这个头名（或后端换实现）会让**留痕链静默断掉**。

#### 8.10.2 缺口二：幂等键构成只有一句 prose，且"唯一被转录的键构成"从未被消费

- 契约 `x-global-conventions.idempotency` 只有一句话；
  `components.parameters.IdempotencyKey` 定义了 `name/in/schema`，
  **但 `paths` 段对它 `grep -c` 为 0 —— 从未被任何 operation 引用**（装饰件）。
- 唯一逐操作声明 `x-idempotency-key` 的只有 **I3** 一处。
- 🛑 **关键实测**：生成器**已**把它转成 `idempotencyKeySpec`、`Endpoint` 接口
  **已**声明，但 `grep -rn idempotencyKeySpec`（排除生成物/dist）**零命中** ——
  **没有任何调用点消费它**。契约把幂等键**语义**写下来了，**前端拿不到**。

#### 8.10.3 反模式：生成物常量的值不是"机械转录"，而是"手抄的契约 prose"

§8.9 的 `load_api_base_path()` 初版**不从 `servers[0].url` 取值**，
而是把字面量**手抄进模板**，还配了一句注释「`Value = /api/v1`」——
等于**用注释显式掩盖了"值不是现取的"**；而 `base-path-wiring` 只断言
"常量存在"、不断言"值等于契约" ⇒ **契约改 Base Path 时生成物照旧、门禁照绿**。

⇒ 订正：`load_api_base_path()` **真读 `servers[0].url`**，并与
`x-global-conventions['base-path']` **互查**，不一致即 `MISCONFIGURED` 退出。
全链只剩一个真源。

#### 8.10.4 修法：四层（与 §8.9 同范式）

| 层 | 落点 | 谁守它 |
|---|---|---|
| ① 契约 | 新增根级 `x-api-protocol`（9 键，与 prose **并存且必须一致**） | `ContractFreezeGateTest` 的双向互查断言（11 例） |
| ② 生成物 | `gen-endpoints.py` 新增 `load_api_protocol()` → `PROTOCOL` 常量组（CJS/TS 双形态，TS 附 `ProtocolSpec`） | 生成器自身（缺键即 `MISCONFIGURED`）+ `--check` |
| ③ 出站层 | 三端一律 `headers[PROTOCOL.AUTH_HEADER]` / `headers[PROTOCOL.IDEMPOTENCY_HEADER]` / `res.headers.get(PROTOCOL.TRACE_HEADER)` / `body.code !== PROTOCOL.ENVELOPE_OK_CODE` | `build-check.mjs` ④d `cross-end-protocol` |
| ④ 门禁 | 判**引用形态**（必须见到 `PROTOCOL.<KEY>` 成员访问）+ **剥注释后**不得残留头名字面量 / `"Bearer "` / `code === 0` | `build-reverse-check.mjs` R7–R10 |

#### 8.10.5 契约侧互查断言：一条新判据首跑就复发了一次第 55 条

`cross_end_protocol_fragments_are_structured_and_agree_with_prose` 初版断言
「`envelope-fields` 每一项都必须在 `ResultEnvelope.required` 里」，**首跑即红** ——
`required: [code, message, trace_id]` **不含 `data`**。

但那**不是契约的错**：契约自己写明「`code != 0` 时 `data` 为空」，
故 `data` **本就该可选**（失败信封不带 data）。初版把本仓**自己规定的合法形态**
判红 = **第 55 条**。修法**不是改契约，而是把判据拆成三件事**：

1. 字段**存不存在** → 查 `ResultEnvelope.properties`；
2. `required` 是否是信封的**子集** → 不得含信封外字段；
3. `data` 不在 `required` 时 → `envelope-rule` **必须说明原因**，
   否则它就成了「**未文档化的约定**」。

另加三条与真正的 OpenAPI 结构互查：`idempotency-header` 必须等于
`parameters.IdempotencyKey.name` 且 `in: header`；`envelope-fields` 必须与
`ResultEnvelope.properties` 一致；`trace-header` 不得与信封字段撞名。

#### 8.10.6 回归用例与数字（逐字）

- `build-reverse-check`：**10/10 PASS**（R1–R6 base-path + R7–R10 cross-end-protocol）。
  - R7 端 A 鉴权头名回退成字面量 / R8 端 C 追踪头名回退成字面量 /
    R9 端 B 信封成功码回退成字面量 / R10 **生成物 `PROTOCOL.AUTH_HEADER`
    值被改成与契约不一致（值漂移，不是缺键）**。
- 🛑 **删判据模拟（本条最关键的证据）**：把 ④d 整段删掉后重跑 ⇒
  **R7–R10 四组全部变"漏过"、合计 6/10、脚本 FAIL**（base-path 六组仍被抓住，
  因为它们由另一段代码守）⇒ **两条判据各自有牙齿，且"判据被删"本身会被抓到**。
- 契约冻结门禁 **11/11**；`gen-endpoints.py --check` `[OK] 15 / 29 / 39`；
  三端 `build-check` 全绿（`cross-end-protocol` 各 5 个键全命中、无字面量残留）；
  端 A `check:a` 14/14 · 反向 **16/16**；端 B `check:x3` 9/9 · 反向 **8/8**。
- 后端全量回归：`TOTAL 1200   failures=0 errors=0 skipped=0`
  （1199 → **1200**，+1 = 本条新增的契约互查断言；dy-app **941 → 942**）。

#### 8.10.7 一处如实登记的未决项（不改语义，只登记）

契约 prose 写幂等为「**24h 窗口内**去重」，而后端 E1 的库层唯一索引
`uq_sync_log_batch_no (tenant_id, batch_no)` 是**永久**唯一 —— 差集是
「同一 `batch_no` 距首次超过 24h 后重放」（契约允许应成功、库层返回 409）。
后端 `BandService` 已自己登记该开放项（逐字："若可接受，契约文字应改为
『永久去重』"），**属契约 MAJOR 级变更，裁定权在契约 owner + 产品共签**。

另：`x-idempotency-key` 仅 I3 一处逐操作声明 ⇒ 其余写端点的键构成
**仍在契约里无声明**（前端只能各自造随机键），这是**待补项**。

#### 8.10.8 通用规则（第 57 条）

**"跨端协议片段"是一个集合，不是一个点。** 修完一类就宣布收口，
会留下一整族同型缺口 —— 正确的做法是**把这一类枚举干净**（前缀 / 信封字段 /
鉴权头名 / 令牌前缀 / 租户头 / 追踪头 / 幂等头 / 成功码），
每一类都走同一范式：**契约结构化事实 → 生成物常量 → 出站层只引用 →
判据判形态 → 反向验证含"值漂移"与"判据被删"两例**。

**并且：生成物常量的"值"必须来自机械转录，不能是手抄的副本。**
一个"存在但可漂移"的常量，与注释里的约定同样不可靠 ——
判据若只断言"它存在"，那就是**第 52 条的假绿**。

---

### 8.11 分页「越界处置」也是跨端协议片段：只声明约束 ≠ 声明处置（第 58 条）

#### 8.11.1 缺口：同一份契约下，两个列表端点对越界各走一路

契约 `x-global-conventions.pagination` 原本只有一句：

> 请求 `?page=<int>&page_size=<int≤100>；响应 `data.{items[], total, page, page_size}``

它声明了**约束**（上界 100），**没说越界怎么办**。于是：

| 端点 | `page_size=101` 时的行为 | 契约声明 |
|---|---|---|
| A3 `GET /stores` | **400 `VALIDATION_FAILED`**（超界直接拒） | 只有 `maximum: 100` |
| D2 `GET /customers/{id}/visits` | `Math.min(Math.max(101,1),100)` = **100，静默夹逼后 200** | **未声明越界怎么办** |

客户端请求 101 条、拿到 100 条、**无从察觉**。而两端 200 响应体、`tsc`、
`vite`、**全部既有门禁都绿**（它们从不发真实请求）。

🛑 **关键**：`x-error-codes` 里 `VALIDATION_FAILED.trigger` 逐字就是
「参数类型 / 必填 / **约束不满足**」—— 契约自己已经写了"越界该报 400"，
是 `FulfillmentController:120` 的无声夹逼**违反**了契约。但两处声明都在契约里，
**却没有任何机械判据把它们连起来** ⇒ 静默夹逼能长期存活。

另：后端**零测试**覆盖这两个端点的越界行为 —— 这是缺口的一半。

#### 8.11.2 修法：四层 + 单点

| 层 | 落点 | 谁守它 |
|---|---|---|
| ① 契约 | `x-api-protocol.pagination`（9 键：参数名 / 上下界 / 缺省 / `over-range-policy: reject-400` / `over-range-error`）；prose 订正为显式写「拒，不夹逼」；`parameters.PageSize` 补 `minimum/default/description` | `ContractFreezeGateTest` 的**四向互查**（12 例，含 `VALIDATION_FAILED.trigger` 必须含「约束」） |
| ② 后端 | **唯一校验单点** `dy-common` 的 `PageQuery`（越界一律抛 `VALIDATION_FAILED`，**绝不夹逼**）；`FulfillmentController` 改调单点；`StoreListPage` / `StoreListService` 收归委派 | `PaginationDisciplineTest`（4 例，含源码扫描 + 反向验证） |
| ③ 前端 | 生成物 `PROTOCOL.PAGINATION`（含 `PAGE_FIELD` / `PAGE_SIZE_FIELD`）；三端 `paging.ts` 助手 `pageQuery()`；端 A 6 处 + 端 B 4 处调用点收敛 | `build-check.mjs` ④d 值断言 + ④e `pagination-protocol` |
| ④ 反向验证 | R11 端 A 参数名回退成手抄 / R12 端 B 透传形态回退 / R13 **分页助手把键名写错** | `build-reverse-check.mjs`（13/13） |

🛑 **为什么必须有"唯一单点"**：此前 `page_size` 的校验逻辑在
`StoreListService` 与 `FulfillmentController` **各有一份实现** ——
**同一规则两处实现，正是静默夹逼能长期存活的土壤**（改一处、另一处不知道）。
抽成单点后，"越界怎么办"全局只有一处实现。

#### 8.11.3 第 53 条形态：R13 首跑漏过（判据覆盖面没跟上）

④e 初版只扫"字面量"（`page_size` 作为字符串出现）。R13 注入的是
**分页助手内部把键名改成别名**（`q['pageSize'] = ...`）—— 它**不是** `page_size`
字面量，正则扫不到 ⇒ **R13 首跑"漏过"**。这正是第 53 条（覆盖面没跟上 ⇒ 静默漏检）。

⇒ 修法：判**引用形态**（与 ④d 的 `PROTOCOL.<KEY>` 同构）——
分页助手内必须出现 `PAGINATION.PAGE_FIELD` / `PAGE_SIZE_FIELD` 的成员访问。
改后 13/13 PASS。

#### 8.11.4 第 55 条形态：分页纪律门禁首跑即假红

`PaginationDisciplineTest` 的源码扫描器首版只剥 `//` 与 `/*`，**没剥 Javadoc 内部行**
（以 ` * ` 开头）。于是它把 `FulfillmentController` 的 Javadoc 里那句
**描述缺陷**的 `{@code Math.min(Math.max(pageSize, 1), 100)}` 判红了 ——
**第 55 条（判据太窄 ⇒ 把合法写法判红）**。

⇒ 修法：`stripComment()` 同时处理行注释 / 块注释起止 / **Javadoc 内部行**。
并把该形态写进反向验证（注入"只在 Javadoc 里提到夹逼"的文件，必须**不**被判红）。

#### 8.11.5 复验数字（逐字）

- 契约冻结门禁 **12/12**；分页纪律门禁 **4/4**；D2 真请求 E2E **9/9**（6→9）
- `gen-endpoints.py --check` `[OK] client-mp 15 / therapist-app 29 / admin-web 39`
- 三端 `build-check` 全绿（`pagination-protocol` 三端各 ✓）
- `build-reverse-check` **13/13 PASS**；**禁用 ④e 后 10/13 ⇒ FAIL**（R11–R13 全漏过）
- 端 A `check:a` 14/14；端 B `check:x3` 9/9
- 后端全量回归 `TOTAL 1208   failures=0 errors=0 skipped=0`（1200 → **1208**，+8）

---

### 8.12 会随机变红的断言 = 判据的第四种失效形态（第 59 条）

#### 8.12.1 缺口：`assertFalse(raw.contains("72"))`

`BandTelemetryEncryptionTest` 里有：

```java
assertFalse(raw.contains("72"), "密文里出现明文 '72' —— 这不是加密");
```

`raw` 是 `CipherEnvelope.serialize()` 的产物，形态为
`dy1:算法:版本:<b64 nonce>:<b64 密文>`。这条断言有两个问题：

1. **概率型假红** —— base64 字符集含 `0-9`，nonce + 密文约 40 字符随机，
   `"72"` 这对相邻字符约 **1%** 概率偶然出现 ⇒ 全量回归**不定期变红**。
2. **判错了对象** —— 整条文本除密文外还含**算法标识**（`AES-GCM-256`）
   与 **DEK 版本数字**；对整条文本判 `contains` 会映到这些**非密文段**。

#### 8.12.2 为什么这算缺陷而不是小事

**一个会随机变红的断言，长期会侵蚀门禁自身的可信度。**
一旦"红了可能是运气"成为共识，就没人再认真看红 ——
门禁从"守卫"退化成"噪音"。

判据失效至此共有**四种形态**：

| 条目 | 形态 | 后果 |
|---|---|---|
| 第 52 条（§8.3） | 判据**太宽** | 假绿 —— 错的被放过 |
| 第 53 条（§8.5） | **覆盖面没跟上** | 静默漏检 |
| 第 55 条（§8.6） | 判据**太窄** | 假红 —— 对的被判错 |
| **第 59 条（本条）** | **不确定性（flaky）** | **随机假红 —— 门禁失去可信度** |

#### 8.12.3 修法：判"数据有没有被加密"的物理载体

判**解码后的密文字节**，而不是它的 base64 文本：

```java
new String(CipherEnvelope.parse(raw).ciphertext(), ISO_8859_1).contains("72")
```

后者才是「数据有没有被加密」的物理载体；前者只是它的**表示**。

新增门禁 `ProbabilisticAssertionGateTest`（3 例）：扫描测试源码，
禁止对**被断言为 `dy1:` 信封**的变量判 `contains`。

#### 8.12.4 第 55 条第三次复发（判据的根本局限）

该门禁首版按**变量名白名单**（`raw` / `envelope` / `*_enc`）判别，
**首跑即误判 3 处合法写法**：

```
BandAvailableDatesTest:97       assertTrue(raw.contains("N = 7"))            // 源码原文
DerivedProfileSourceTest:306    assertTrue(envelope.contains("DerivedRawConfig")) // 配置对象
ProvisioningBoundaryGateTest:701 assertTrue(raw.contains("INSERT INTO tenant"))   // SQL 文本
```

这三处的变量**恰好也叫** `raw` / `envelope`，但装的是**确定性文本**。

⇒ **变量名单看名字无法区分「随机密文」与「确定性文本」，这是判据的根本局限。**

**正确判法是判数据来源**：只有同一文件里该变量被断言过
`startsWith("dy1:")` 时，它才是随机字节的文本表示 ——
与 ④d 的「判 `PROTOCOL.<KEY>` 引用形态」同构。改后 3/3 绿。

#### 8.12.5 复验数字（逐字）

- `BandTelemetryEncryptionTest` **连跑 3 次全绿**
- `ProbabilisticAssertionGateTest` **3/3**
- 后端全量回归 `TOTAL 1211   failures=0 errors=0 skipped=0`（1208 → **1211**，+3）

---

### 8.13 写在文档里的计数也是一句【活断言】—— 没人守它，它就能是假的（第 60 条）

#### 8.13.1 缺口：4 处 `TOTAL` 全写错，而全部门禁全绿

`README.md` 与 `frontends/README.md` 里共 **4 处**写着后端全量回归的计数，形态：

```
TOTAL <N> failures=<F> errors=<E> skipped=<S>
```

它**不是修辞** —— 它同时声称两件事：① 全仓回归计数为 `N`；② 失败/错误/跳过全为 `0`。

第 58 条收尾时，真实值是 **`TOTAL 1211 failures=0 errors=0 skipped=0`**，而 4 处**全写 1204**（差 7）。

**错法本身就是证据**：当时我是"预估"而不是"照抄实测" —— 在 `1200` 上只加了「分页纪律门禁 4 例」，
**漏掉同一批改动里的另外 3 个新测试类**：

| 漏掉的新增 | 例数 |
| --- | ---: |
| `ContractFreezeGateTest.pagination_over_range_policy...` | +1 |
| `FulfillmentDomainDEndpointsE2ETest` 越界 3 例 | +3 |
| `ProbabilisticAssertionGateTest` | +3 |
| **合计** | **+7** |

**为什么全绿**：`TOTAL` 锚点只活在 markdown 里，而 **markdown 不被任何测试读取**；
前端三端自检只管 `frontends/` 的构建，后端门禁只管各模块的源码。
⇒ **这句话的每一个字都可以是假的，而没有一台机器会因此变红。**
与本仓第 32 条「一个恒真的断言等于没有断言」同族，但更隐蔽：那条是"断言写得太宽所以恒真"，
**这条是"断言根本没人读"**。

#### 8.13.2 它和 dy-crypto 已有的同名门禁的区别（必须写清，否则会被误认为重复）

本仓**已有** `com.diaoyuanyun.dy.crypto.gate.DocTestCountAnchorGateTest`。但：

| | dy-crypto 那个 | 缺的那个 |
| --- | --- | --- |
| 守的锚点形态 | `应为 **N**` | `TOTAL N failures=0 ...` |
| 覆盖面 | **只有 `dy-crypto/` 模块内** | **跨 8 个模块的求和** |
| 锚点所在文档 | `dy-crypto/*.md` · `verification/crypto/README.md` | `README.md` · `frontends/README.md` |

⇒ 这不是"已有能力没被用上"，而是**覆盖面正好差了一层**：
**判据守的是它写到的那一层，而漂移发生在它没写到的那一层。**
与第 53 条（覆盖面没跟上）同族，但成因不同 —— 第 53 条是"新增了同类宿主而判据没跟上"，
本条是**"同一件事在两个层级各有一份计数，只守了低层"**。

#### 8.13.3 修法：把 `TOTAL` 抬成活断言（新建 `dy-app` 的 `DocTestCountAnchorGateTest`，2 例）

| # | 判据 | 内容 |
| --- | --- | --- |
| ① | `total_anchors_in_docs_match_the_real_test_count` | 解析 `README.md` + `frontends/README.md` 全部锚点：**最新一条 `== 源码级实测合计`**；**每一条**的 `failures/errors/skipped` 必须全为 `0`；整份文档内锚点值**单调不降**（用例只增不减，写小只可能是漏加）；逐模块求和集必须与 `MODULES` 清单**完全一致**（少一个模块 = 求和系统性偏小，这正是本条的一半成因） |
| ② | `gate_turns_red_when_doc_total_drifts_and_green_when_it_matches` | **反向验证五向**：写对 ⇒ 放行 / 少算 ⇒ 抓且**指名文档 + 两个数字** / 自称 `failures=1` ⇒ 抓 / 历史锚点值**倒退** ⇒ 抓 / 一个锚点都解析不到 ⇒ **按失败处理不按通过处理** |

**一处刻意设计**：只对**最新一条**锚点要求"等于实测"，更早的只要求"单调不降"。
因为条目表按时间追加，第 57 条那行写 `1200`、第 58 条那行写 `1208` 都是**当时如实**；
若要求每一处都等于今天，每加一条测试就得回去改历史记录，文档就再也不能记录"当时是多少"。

**主判据为什么取「源码级 `@Test` 计数」而不是「surefire 报告求和」**：本仓 `dy-config`
（`default-test` + `config-truth-source-gate`）与 `dy-app`（`default-test` + `rls-isolation-gate`）
各有**两个** surefire execution，报告求和会把**同一批用例被重复执行的次数**也加进去。
源码级计数与执行顺序、execution 划分无关。且两条路**实测口径一致**：
源码级 = 1211，逐 execution 求和也 = 1211（互相印证）。

#### 8.13.4 首跑即红的证据（逐字）

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, ...
文档的「全量回归计数」锚点与实测脱钩 —— 请更新文档锚点（最新一条应写 `TOTAL 1213 ...`）。
命中:
  - README.md : 最新锚点写的 `TOTAL 1204`（L1992 ...），实测源码级合计是 1213（差 9）
  - frontends/README.md : 最新锚点写的 `TOTAL 1204`（L794 ...），实测源码级合计是 1213（差 9）
```

（`1213` = `1211` + 本类自己的 2 例 —— 这正是类注释里声明的"自指性"。）

订正 4 处锚点（`1204` → `1208` / `1211`，并**按新增补齐 `1200 → 1208 → 1211` 的增量说明**）后 **2/2 绿**。

#### 8.13.5 复验数字（逐字）

- 全量回归 `EXIT=0 / BUILD SUCCESS`
- `TOTAL 1213   failures=0 errors=0 skipped=0`（逐模块 `10/39/48/58/37/37/29/955`）
  🛑 此为第 60 条当时的值；第 61/62 条后实测为 **`TOTAL 1214`**（见 §8.14.5 / §8.15.5）。
- `DocTestCountAnchorGateTest` **2/2**（含反向验证五向全过）

---

### 8.14 契约里「不得模糊报错」只有【给人读的那一半】被实现（第 61 条）

#### 8.14.1 缺口：字段名 4 处 prose 提及、后端也装配了，客户端一个字都拿不到

契约 `x-global-conventions.forbidden-403` 逐字写「message 必须给出缺失项名称 / 档位名称
（**不得模糊报错**）」；`missing_items` 另在 `:251`（GATE_MISSING trigger）、`:388`（B1 描述）、
`:1494`（GateMissing 响应描述）**3 处 prose** 提及，`denied_fields` 在 `:1488`
（VisibilityDenied 描述）提及 —— **全部是 prose，无一处结构化声明**。

而**后端这一侧是完整的**：`GlobalExceptionHandler.java:77`
`Map.of("missing_items", g.getMissingItems())`、`:83` `Map.of("denied_fields", v.getDeniedFields())`；
`VisibilityDeniedException.java:43` 构造期拒空/拒 null；`GateMissingException` 携带 `missingItems`。

**缺的恰好是"从后端到用户眼睛"那一段**：

| 层 | 端 A | 端 B | 端 C |
|---|---|---|---|
| 出站层在**错误路径**保留 `body.data`？ | ✗ 只挂 code/status/traceId | ✗ 同 | ✗ 同 |
| 错误层读这两个字段名？ | ✗ | ✗ | ✗ |

且端 A `errors.ts:48` 的静态文案**对用户承诺**「响应 data.missing_items 列出缺失项」——
**它自己从不读那个字段**。⇒ **文案在替一个不存在的功能背书**；
契约要求的"给出名字"在客户端**完全没有到达**，而 tsc / vite / 门禁**全绿**。
与 §8.9/§8.10/§8.11 同族：**契约写下的协议与各端实现的协议是两件事，中间丢项不报错。**

#### 8.14.2 修法：四层（与 §8.9/§8.10/§8.11 同范式）

1. **契约** —— `x-api-protocol` 新增 `error-data-fields` 结构化子块
   （`"2002": missing_items` / `"2001": denied_fields`）+ `error-data-field-rule`
   （说明该字段是**逐字来自上游（契约 / data-dict）的名称**；message 给人读、data 给机器比对，
   混用会让上游字面被解释性文字污染）；`forbidden-403` prose 追加「且 data 里必须给出名字本身」。
2. **生成物** —— `gen-endpoints.py` 的 `load_api_protocol()` 扩读该块
   （缺块 / 空键 / 键非数字串 / 缺 `2002` / 缺 `2001` 即 `MISCONFIGURED` SystemExit），
   新增 `_err()` 助手，三端生成物出 `PROTOCOL.ERROR_DATA_FIELDS`（TS 附 `Readonly<Record<number, string>>`）。
3. **出站层 / 错误层** —— 三端出站层保留 `err.data = body.data`；
   三端错误层经 `PROTOCOL.ERROR_DATA_FIELDS[code]` 取字段名（**不得手写**字面量）。
   🛑 **同一个契约事实，两种合法消费形态**：端 A/B 把原因名拼进用户可见的 `text`
   （`reasonsOf` + `appendReasons`）；**端 C 刻意只透传不渲染** ——
   端 C 词表纪律禁止内部概念出现在客户端包里，故 `reasons` 作为**独立出口**供页面逻辑分支用。
4. **门禁** —— `build-check.mjs` ④d 加值断言 + **新增 ④f `error-data-fields`**：
   ① 生成物 `ERROR_DATA_FIELDS` 键值；② 出站层**精准形态** `err.data = ...body.data`；
   ③ 错误层必须成员访问 + 不得手写字面量；一律**先剥注释**。

#### 8.14.3 第 52 条复发：④f 的②初版太宽 ⇒ R14 首跑漏过

④f 的②初版只判 `/\bdata\s*:/` 或 `/err\.data\s*=|\.data\s*=/` ——
被端 A **成功路径**的 `return { data: body.data, ... }` 满足 ⇒
**删掉 `err.data = body.data;` 仍 exit=0（R14 漏过）**。这就是 §8.3 说的**判据太宽 ⇒ 假绿**。

**修法不是改端 A，而是把判据收紧为精准形态**
`/\berr\.data\s*=\s*[^;]*\bbody\.data\b/` —— 必须判「**错误对象上**的赋值」。

#### 8.14.4 删判据模拟（本条最关键的证据）

把 ④f 整段停用后重跑 ⇒ **R14–R16 三组全部变「漏过」、合计 13/16、脚本 FAIL**
（R1–R13 仍被抓住，因为它们由另三段代码守）⇒ **四条判据各自有牙齿**。

#### 8.14.5 复验数字（逐字）

- 契约冻结门禁 **13/13**（新增 `error_data_field_names_are_structured_and_agree_with_prose_and_error_codes`：
  结构化块 ↔ `x-error-codes[].code` 逐字 ↔ `forbidden-403` prose 双向 ↔ 正文落点，**四向互查**）
- `gen-endpoints.py --check` `[OK] client-mp 15 / therapist-app 29 / admin-web 39`
- 三端 `build-check` 全绿（`error-data-fields` 三端各 ✓）
- `build-reverse-check` **16/16 PASS**；**禁用 ④f 后 13/16 ⇒ FAIL**
- 后端全量回归 `TOTAL 1214 failures=0 errors=0 skipped=0`（1213 → **1214**，+1 = 本条新增的契约互查断言）

---

### 8.15 判据的第六种失效形态：条件性假红（依赖环境状态）（第 62 条）

#### 8.15.1 现象：同一条命令，手跑绿、脚本里红

复验第 61 条时 `build-reverse-check` 基线报
`✗ therapist-app / admin-web 基线不是绿的（exit=1）`，
而同一条 `build-check` 命令**手跑两次全绿**（`BUILD OK`）⇒ **基线门禁本身是偶发红**。

#### 8.15.2 取证过程本身也踩了一个坑（值得记）

基线只打印 `exit=1`、**不打印哪个判据红了** —— 与第 24 条同族：**报错不指向真因**。
我第一版诊断代码"只挑含 `✗` / `FAIL` / `ERROR` / `BUILD` 字样的行"，
而 **vite / tsc 的真实报错行一个这类字样都没有** ⇒ "明细"打印出来全是 ✓ 行 +
一句 `✗ real-build: vite build 失败`，**等于没打印**。

> **纪律**：判"该打印哪些行"**不能用关键词白名单，只能排除已知噪音行**。

#### 8.15.3 真因（逐字）

```
[safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED]
{"count":627,"threshold":50,"scope":"turn","targets":["...\therapist-app\dist\assets"]}
    at checkBulkDeleteGuard (node-safe-delete-shim.cjs:239:19)
    at emptyDir (vite/dist/node/chunks/dep-*.js:17082:19)
    at prepareOutDir (...:65746:7)
```

`vite build` 默认 `emptyOutDir: true`，实现是 `fs.rmSync(dist/assets, {recursive:true})` ——
而本机运行环境装了一层**安全删除守卫**，它对"单轮累计删除文件数 > 阈值"的动作**直接抛错**。

⇒ **判据红，而红的原因是"本轮我已经删了多少文件"这个与代码完全无关的变量**；
报错还指向 `dist/assets` 路径 + 一段 shim 栈，读起来像"产物目录有问题"—— **真因完全看不出**。

#### 8.15.4 为什么单独编号：与第 59 条同族但随机源不同

| | 第 59 条（§8.12） | 第 62 条（本条） |
|---|---|---|
| 随机源 | **随机数据**（base64 密文偶现 `"72"`，约 1%） | **环境状态**（本轮累计删除量） |
| 表现 | 不定期变红 | 条件性假红（删够多就红） |
| 修法 | 判**解码后的字节** | 让判据**不依赖那个环境变量** |

两者都会侵蚀门禁可信度（"红了可能是运气"⇒ 没人再看红）⇒
归一为**判据失效的第六种形态：条件性假红（依赖环境状态）**。

#### 8.15.5 修法（三处，都不动安全机制、都不放宽语义）

1. `build-check.mjs` 新增共用 `viteBuildArgv()` 返回 `['build','--emptyOutDir=false']`（端 A/B 共用）——
   产物正确性**不依赖"先清空"**（vite 产物带内容哈希，重建会**覆盖**同名文件并按新图重写
   `index.html`，残留旧哈希文件不被引用、不影响结论）。
   🛑 **这不是放宽判据**：`vite build` 仍真实执行、仍必须 `exit=0`，
   只是不让它删一个**与被检语义无关**的目录。
2. `build-reverse-check.mjs` 磁盘备份 → **内存备份**（`Map<absPath, 原文>` + `restore()`）：
   全程**一次删除都不发生**；配 `uncaughtException` / `unhandledRejection` 兜底 `restoreAll()` ——
   **比磁盘副本更可靠**（磁盘副本在进程被杀时会留残骸，那正是第 24 条的成因）。
   > 同族第二处：原实现 17 组用例 × 2 次删除 ⇒ **必然**顶穿阈值 ⇒
   > **脚本死在第 N 组用例的清理语句上**，死法与"判据是否有牙齿"毫无关系。
3. 收尾对**旧版遗留的** `_reverse_backup/` 只做**诊断**不改判定 ——
   否则旧版遗留的一个空目录就会让门禁报"异常 ✗"，读起来还像"残留没清干净"，把人带偏。

#### 8.15.6 复验数字（逐字）

- 基线 **绿 ✓**
- `build-reverse-check` **16/16 PASS**、
  `还原后三端构建自检：client-mp=0 · therapist-app=0 · admin-web=0（全绿 ✓，已按内存备份还原）`
- 后端全量回归 `TOTAL 1214 failures=0 errors=0 skipped=0`

### 8.16 同一类约束在多端各有一份实现时，判据必须【按约束类别】对齐，不能【按端】各自生长（第 63 条）

🛑 **现象：端 A 有这条判据，端 B / 端 C 没有 ⇒ 一条判据只保护了三分之一的受保护对象**
------------------------------------------------------------------------
§8.8 里那条 `endpoint-reachability` 是**端 A 专属**的（写在 `admin-web/tools/a-check.mjs` ①⓪）。
端 A 当时真实抓出过 5 个"封装了但没接上界面"的端点，并因此补了 5 个入口 ——
**但这条判据没有横向推广**：端 B 的 `x3-check.mjs` 与端 C 的 `build-check.mjs`
**各有一套自己的判据集合，两套都没有这一条**。

🛑 **本轮实测（逐条 `grep` 确认 `src/` 全域 0 次调用）**
------------------------------------------------------------------------
端 B `services/domain.ts` 里 **16 个端点**"封装齐全、从未被任何页面调用"：

| 域 | 端点 | 封装函数 |
|---|---|---|
| B1 | `createScreeningRecord` | `createScreeningRecord()` |
| B2 | `createCustomer` | `createCustomer()` |
| B3 | `signConsent` | `signConsent()` |
| B5 | `getIntakeProfile` | `getIntakeProfile()` |
| B6 | `patchIntakeProfile` | `patchIntakeProfile()` |
| C1 | `listScaleItemBanks` | `listScaleItemBanks()` |
| C2 | `submitBaselineAssessment` | `submitBaselineAssessment()` |
| C3 | `getAssessment` | `getAssessment()` |
| C4 | `submitCycleAssessment` | `submitCycleAssessment()` |
| D1 | `createVisit` | `createVisit()` |
| D3 | `submitDailyReport` | `submitDailyReportAsStaff()` |
| D4 | `listDailyReports` | `listDailyReports()` |
| D5-a | `createPlan` | `createPlan()` |
| D5-b | `getPlan` | `getPlan()` |
| D6 | `createDeviceDispatch` | `createDeviceDispatch()` |
| A3 | `listStores` | `listStores()` |

⇒ **触达率 13/29**。而 `tsc --noEmit` 绿、`vite build` 绿、`x3-check` 9 项全绿、反向验证全绿。

🛑 **三道防线为什么一道都拦不住**
------------------------------------------------------------------------
| 防线 | 它审什么 | 为什么看不见 |
|---|---|---|
| `tsc --noEmit` | **类型**是否自洽 | 未使用的导出函数**完全不报**（这是 TS 的设计） |
| `vite build` | 能否**打包** | tree-shaking 反而把这些函数**当死代码摇掉**，产物更小更"干净" |
| 既有门禁 | **已写下的东西对不对**（契约转录 / 角色矩阵 / 可见性 / 分页 / 错误字段） | 它们审的是"写了的对不对"，**不审"该写的有没有写"** |

**三道防线里没有一道会看一眼"这个封装有没有人用"。**

🛑 **最深的一层后果：「封装了但没接上界面」不只让功能缺失，还让函数自身的缺陷也没有曝光面**
------------------------------------------------------------------------
实测 `listScaleItemBanks` 的初版签名：

```ts
query: PageQuery & { age_group?: string; dimension?: string } = {}
```

`PageQuery` 的索引签名是 `[k: string]: number | undefined`，
与 `{ age_group?: string }` 求交得到**内部矛盾类型**（`age_group` 同时要求
`string` 与 `number | undefined`）⇒ **该函数在类型层面根本不可调用**（`tsc` 报 `TS2345`）。

🛑 **它长期没被发现，正因为它从没被任何页面调用过。**
**死代码不是"少一块功能"，而是"少一个让缺陷显形的探针"** ——
同一个缺口，既让 16 个端点拿不到界面，也让这 16 个函数**躲过了类型检查的曝光面**。

🛑 **同族第二处（端 C，判据首跑即抓出真缺陷）**
------------------------------------------------------------------------
端 C `pages/login/login.js` 自己 `request.call('authLogin', ...)`，
而 `services/session.js` 里**已有一个等价 `login()`**（同样拼 `client_end: 'mp'`、同样存 token），
**只是从没被调用过** ⇒ **同一操作两份实现，改哪份都会静默漂移**。
（与第 57/61 条同族：同一契约事实被实现两次，中间漂移不报错。）

修法 = `login.js` 删 `require('services/request.js')`，改用 `session.login(account, credential)`。

🛑 **端 C 的判据算法不能照抄端 A/B（否则就是第 52 条"太宽 ⇒ 假绿"）**
------------------------------------------------------------------------
| 差异 | 端 A / 端 B | 端 C |
|---|---|---|
| 服务层模块形态 | ESM（`export function foo`） | **CommonJS**（`function foo(){}` + `module.exports = { foo: foo }`） |
| 页面取用形态 | `import { foo } from '...'` → `foo(...)` | `var api = require('...')` → **`api.foo(...)`（必须先解析别名）** |
| 触达形态 | 页面/外壳直接调用 | 页面直调 **∪** 经"**自身已被页面触达**的编排函数"调用<br>（例：`band-sync.js` 的 `syncOnShow()` 内部依次调 `reportAvailableDates()` / `uploadRecords()`） |

🛑 **第三种差异若不认，会把合法设计判成假红（第 55 条）** ——
编排层的存在是本仓的**合法设计**，不是"绕过判据"。

🛑 **一条必须先做、否则会得出错误结论的取证纪律（第 63 条在元层的自我复现）**
------------------------------------------------------------------------
我第一版探针只认 `export function` ⇒ **端 C 报 15/15 假绿**（③ 循环空转跳过）。
⇒ **探针自身的覆盖面缺口会伪装成"没有问题"。**
这也是为什么"补判据"之前必须先**独立取证**（写只读探针实测），
不能直接照抄另一端的判据算法。

🛑 **修法（四步，缺一不可）**
------------------------------------------------------------------------
1. **补界面（真缺口，不是补判据）** —— 新建端 B
   `pages/IntakePage.tsx`（域 B · B1~B6 五端点）·
   `pages/AssessmentPage.tsx`（域 C · C1~C4 四端点）·
   `pages/ServicePage.tsx`（域 D · D1/D3/D4/D5-a/D5-b/D6 六端点）；
   接入 `App.tsx` 导航（`requires` 用端点 id 字面量，由 `x3-check` ⑦ 核验）；
   `WorkbenchPage.tsx` 补 A3 门店列表。
   ⇒ 端 B 触达 **13/29 → 29/29**。
   🛑 **三页落地时同步体现的契约硬约束（不是"照名字填表"）**：
   B1→B2 **顺序两步**（`screening_id` 自动带入，不给人手抄 UUID）；
   C2 的 `dimension_scores` **7 个独立输入框**（契约 `minItems 7 / maxItems 7`、`total_score` 0~112），
   填满 7 项才解禁提交、`sum` **仅作核对提示、不自动覆盖** `total_score`；
   C4 依从维度**刻意不含 A2**（契约红线「A2 永不参与 AS」）；
   D3 **刻意不提供 `source` 选择器**（`submitDailyReportAsStaff` 已写死「代核」）；
   B3 / D1 / D5-a / D6 字段名逐字对齐控制器 record
   （**契约对这四处均未声明 requestBody** —— 唯一真相源是控制器 record，第 50 条教训）。
2. **修不可调用类型** —— `ScaleItemBankQuery`（`age_group?/dimension?/version?: string`
   + 索引签名 `readonly [k: string]: string | undefined`，以满足出站
   `Record<string, string | number | boolean | undefined>`）。
3. **收口端 C 双份实现** —— `login.js` → `session.login()`。
4. **补判据（第 63 条本体）** —— `x3-check.mjs` 新增 ⑧（与端 A ①⓪ 同构：
   `called` / `fnOf` / `noCallForm` / `noFn` / **`noUi`（封装但未接界面）** / `pageDirectCall` + 计数等式）；
   `build-check.mjs` 新增 **④g（CommonJS 专属算法**：扫全目录 + 解析 `module.exports`
   导出表 + 解析页面 `require` 别名 `aliasOf` + **两种触达形态** + 计数等式）。

🛑 **反向验证（证明判据有牙齿）**
------------------------------------------------------------------------
- `x3-reverse-check` 补 **I9**（摘掉 `ServicePage` 的 `createVisit` 调用，封装仍在、仅断链）
  / **I10**（删 `domain.ts` 的 `listStores` 出站）⇒ **10/10 PASS**。
- `build-reverse-check` 补 **R17**（端 C 登录页绕开会话层、自己直接出站）
  / **R18**（删 `domain.js` 的 `getPlan` 出站）⇒ **18/18 PASS**。

🛑 **R12 随本轮修法静默失效（脚本自身的元层缺陷，已同步）**
------------------------------------------------------------------------
我修 `listScaleItemBanks` 签名后，R12 的注入锚点
`query: PageQuery & { age_group?: string; dimension?: string } = {}` 匹配不到
⇒ `mutate` 返回原文 ⇒ 脚本按自己的自检规则报
「变异未生效（mutate 返回了原文）—— 用例本身失效，须修正」；
改锚点为真实手抄分页形态 `/query: pageQuery\(page, pageSize\)/` ⇒ 恢复有效。
**这正是该脚本「注入必须带来可观测差异」自检的价值：
让"用例自己腐烂"变成显式失败而非假装通过。**

🛑 **复验数字（逐字）**
------------------------------------------------------------------------
- 端 B `endpoint-reachability`：
  `29 个端点全部有完整调用链：services/ 层出站 29/29 · 封装函数 29/29 · 均被页面/外壳以调用形态触达（已扫 9 个消费侧文件；页面/外壳直接出站 0 处 —— 分层纪律成立）`
- 端 C 判据**首跑即抓出**：
  `✗ [endpoint-reachability] 以下端点的界面触达链不完整：authLogin（封装函数 login() 既未被页面调用，也未被任何"自身已被页面触达"的编排函数调用）`
- 三端 `build-check` 全 `BUILD OK` · `x3-check` `X-3 OK` · 端 A `A OK 39/39`
- 反向验证 **10/10**（端 B）· **18/18**（三端）· **16/16**（端 A）

🛑 **通用规则**
------------------------------------------------------------------------
**当同一类约束在多端各有一份实现时，"某一端有这个判据"不等于"这类约束被守住了"。**
判据的覆盖面必须**按【约束类别】对齐，而不是按【端】各自生长** ——
每次新增一条判据都要问一句：
**"本仓还有哪些同类宿主没有这条判据？"**
（这是第 53 条"证明判据认识的东西覆盖了全部"的**跨端版本**。）

### 8.17 缺陷每往上一层就换一个藏身处：调用链成立 ≠ 页面能打开（第 64 条）

🛑 **现象：刚补完第 63 条的判据，用受控注入一试 —— 它漏了新的一层**
------------------------------------------------------------------------
第 63 条刚给三端都补上了 `endpoint-reachability`（「代码里有一条从界面到出站的调用链」）。
补完立刻做受控注入复验，**结果它没抓住**：

```
端 C：把 pages/assessment/assessment 从 miniprogram/app.json 的 pages 里删掉
      （页面文件仍在磁盘、调用链完好）

注入后：
  ✓ endpoint-reachability: 15 个端点全部有完整调用链（已扫 19 个 js 文件 · 10 个消费侧文件 · 出站调用形态 15/15）
  BUILD OK  (client-mp)  结构 + 契约 + 入口清单 + 真实构建 全部通过
注入后 build-check exit = 0
```

🛑 **小程序只加载 `app.json.pages` 里声明的页面** ⇒ 这个页面**用户永远打不开**，
而全部判据**如实全绿**。

🛑 **它和第 63 条是同一族，但断点在上面一层**
------------------------------------------------------------------------
| | 断在哪一层 | 表现 | 判据 |
|---|---|---|---|
| 第 63 条 | **代码**层 | 端点封装好了，但没有页面调用它 | `endpoint-reachability` |
| 第 64 条 | **装载**层 | 页面写好了，但没有被装载/渲染 | `page-registry` |

⇒ **缺陷每往上一层就换一个藏身处。** 修完一层，必须立刻问：**上一层呢？**

🛑 **三道防线为什么一道都没守**
------------------------------------------------------------------------
| 防线 | 为什么看不见 |
|---|---|
| `tsc --noEmit` | **根本不看 json** |
| `vite build` | 只打包被 `import` 的模块；端 C **根本没有打包器**（小程序产物由微信开发者工具编译） |
| 三条 `endpoint-reachability` | **只认调用链、不认装载** |

🛑 **两端形态不同，判据必须分两路（照抄一套必然假绿 —— 第 52 条）**
------------------------------------------------------------------------
| | 端 C | 端 A / 端 B |
|---|---|---|
| 装载形态 | **声明式**：`app.json.pages` | **代码式**：外壳的 NAV 数组 |
| 三方一致 | `app.json.pages` ↔ **磁盘** ↔ `tabBar` | NAV 的 `key` ↔ `type Tab` 成员 ↔ **渲染分支** |
| 最隐蔽的一类 | 「磁盘有、清单未声明」（文件在、调用链在、永不加载） | 「导航有、渲染无」（点了页面不变，用户以为坏了） |
| 渲染写法 | — | 端 A 逐行 `? : null`，端 B **三元链** ⇒ 只提取 `tab === 'x'` 的 **key 集合** |

🛑 **一个必须防的判据自身缺陷（第 53 条教训的复用）**
------------------------------------------------------------------------
判据**先圈定 NAV 数组本体**再数 `key:` ——
初版若直接扫整个 `App.tsx`，会把 `interface NavItem` 的
`readonly requires: string | null;` 之类也数进来（第 53 条真实踩过）。
🛑 且**判据不认识当前写法时必须按失败处理**，不得当作通过 ——
「没查到」与「没有问题」是两件事（第 52/53 条）。

🛑 **修法**
------------------------------------------------------------------------
`build-check.mjs` 新增 **④h `page-registry`**（按 `END_ID` 分两路）：

- **端 C**：① 磁盘实有页面 ⊆ `app.json.pages`；② 声明页面文件齐全；
  ③ `tabBar` 指向的页面已声明；④ **计数等式** `declared.length === disk.length`。
- **端 A/B**：① 导航项**无渲染分支**（点了没反应）；
  ② 渲染分支**无导航入口**（死分支，永远走不到）；
  ③ `type Tab` 里有、NAV 里没有（**类型松了口子**）；
  ④ NAV 里有、`type Tab` 里没有（**类型缺口**）；⑤ 重复 `key`。

🛑 **反向验证（证明判据有牙齿，三组各打中一端）**
------------------------------------------------------------------------
| 用例 | 注入什么 | 打中哪端 |
|---|---|---|
| **R19** | 端 C：把「量表评估」页从 `app.json.pages` 摘掉（文件仍在磁盘） | 端 C 声明式 |
| **R20** | 端 B：把「手环数据」渲染分支的 key 改掉（两边同时失配） | 端 B 三元链 |
| **R21** | 端 A：删掉「文书模板」的 NAV 项（渲染分支成死分支） | 端 A 逐行式 |

🛑 **通用规则**
------------------------------------------------------------------------
**凡「A 存在 ⇒ B 可被用户用到」这类链路，判据必须把中间每一层都钉住。**
每补一层都要问一句：**「上一层修好了，这一层呢？」**
（这是 §8.16「哪些同类宿主没有这条判据」的**纵向版本** ——
§8.16 是横向找漏网的端，本节是纵向找漏网的层。）

🛑 **复验数字（逐字）**
------------------------------------------------------------------------
- 端 C `page-registry`：`9 个页面：app.json 声明 ↔ 磁盘 ↔ tabBar 三方一致`
- 端 B `page-registry`：`7 个导航项：NAV ↔ \`type Tab\` ↔ 渲染分支三方一致（渲染分支命中 7 处）`
- 端 A `page-registry`：`7 个导航项：NAV ↔ \`type Tab\` ↔ 渲染分支三方一致（渲染分支命中 7 处）`
- 三端 `build-check` 全 `BUILD OK`
- `build-reverse-check` ⇒ **21/21 PASS**、
  `还原后三端构建自检：client-mp=0 · therapist-app=0 · admin-web=0（全绿 ✓，已按内存备份还原）`
- 后端全量回归 `BUILD SUCCESS` / **`TOTAL 1214 failures=0 errors=0 skipped=0`**
  （逐模块 `10/39/48/58/37/37/29/956`；本轮改动全在 `frontends/`，未触碰 Java 源码）
  > ⚠️ **此处为第 61 条当时的实测值（历史留痕，不随最新改动回改）**。
  > 后续锚点见 §8.26：第 73 条新增 `UnknownRouteEnvelopeE2ETest`（**8 例**：404×3 + 405 + 415 + 406 + 无 code 一致性 + 对照）后为 **`TOTAL 1223`**（dy-app 956 → 965；含族级枚举第三轮新增的 1 例）。

### 8.18 判据只判「端点有没有被调用」、不判「调用的实参对不对」（第 65 条）

#### 8.18.1 缺口：字段名都在、值全是编造的，而所有门禁一律绿

`endpoint-reachability`（端 A ⑩ / 端 B ⑧ / 端 C ④g）只回答一件事：
**这个端点有没有被调用**。它**不回答**"调用的实参对不对"。实测（读
`吕老师/02-基线评估与核心健康问题辨识表.docx` 逐节映射系统落点时抓出）：

| 调用点 | 契约要求 | 实际写的 | 后端后果 |
|---|---|---|---|
| 端 A `listScaleItemBanks({page:1, page_size:20})` | `age_group` **required**（且本端点**没有分页参数**） | 两项都不对 | 400 |
| 端 A C2 `scale_id: 'baseline'` | UUID | 非 UUID 字面量 | 400 |
| 端 A C2 `item_group_id: 'default'` | UUID | 非 UUID 字面量 | 400 |
| 端 A C2 `age_group_locked: \`${gender}${age}\`` | 8 值枚举之一 | 拼串、不在枚举内 | 400 |

🛑 **它为什么能长期存活**：`tsc --noEmit` 只关心类型（`'baseline'` 是合法 `string`）、
`vite build` 只关心能否打包、触达判据只问"调了没"、反向验证只测那几条已有判据
⇒ **"实参对不对"此前没有任何东西在问**。

#### 8.18.2 根因：`scale_id` / `item_group_id` 在契约里没有任何端点能产出

这是本条最硬的一层，也是修法必须与它对齐的原因：

- 契约 `BaselineAssessmentRequest.required` 逐字含 `scale_id` / `item_group_id`；
- 但 **C1 的 200 响应在契约里根本没有 data schema**（只有 `ResultEnvelope`）；
- `grep` 实测：`scale_id` **除 C2 入参外 0 次出现**（L1731/1733/1734），
  `item_group_id` 同样只出现在 C2 ⇒ **契约里没有任何端点能产出这两个值**。

⇒ 三端各自给这两个必需参数填了"看起来合理"的假值。**端 B 的写法是对的**
（C1 返回的 keys 只读带入 C2，取不到 ⇒ 按钮禁用），**端 A 是错的**。

#### 8.18.3 修法

① **端 A `CustomerConsolePage.tsx`**：新增 `age_group` 选择器（未选 ⇒ C1 按钮禁用）
+ `measure_operator` 输入；C1 返回的 `scale_id` / `item_group_id` **只读带入** C2，
取不到 ⇒ 提交按钮保持禁用；**删掉三个臆造值**（`'baseline'` / `'default'` / 拼串枚举）。
② **同步修 F1 的两处同类缺陷**（同一判据首跑即抓出，见 8.18.4）：
- `downloadDocTemplate` 漏 `version` —— 契约 `required: true` **且**后端
  `DocFileController.download(..., @RequestParam("version") int version)` **真强制** ⇒ 真缺陷。修：封装体加 `version` 形参（`Number.isFinite` 校验，拒不代填 0）、页面复用 I6 的 `version` 输入框、未填禁用按钮。
- `createVerdict` 的 `same_origin` 三项此前**硬编码为 `true`** —— 契约逐字
  「任一项缺省 = 不成立 = **不可比 ⇒ 挂起**」。硬编码 true 是**伪造"同源"**，
  会让本该挂起的轮次算出"可比"结论。修：改为真实复选框。
③ **生成物机械转录必需参数**：`gen-endpoints.py` 从契约 `parameters[].required` 与
  `requestBody.schema.required` 转录 `requiredQuery` / `requiredBody`（含 `$ref` 解引用）；
  三端重跑 + `--check` OK。⇒ 判据不再依赖手抄。
④ **三端各新增一条判据**（编号：端 A ⑩b / 端 B ⑨ / 端 C ④i）——**各自按本端语言形态实现**：
  端 A/B 是 `export function` + `call(`；端 C 是 CJS `function` + `request.call(`，
  **照抄会一条都匹配不到**（匹配不到 ⇒ 判据"通过"但什么都没查 = 假绿）。

#### 8.18.4 🔴 契约内部矛盾（本条顺带暴露，**已登记待裁，不得当作已收口**）

`createVerdict` 的 `risk_flag` / `core_metric_improved` 被列入
`CreateVerdictRequest.required`（L1964-1965），但：

- 字段**自身描述**逐字写「🛑 缺失 ⇒ 未定（不得默认「无」—— 那会让 D4 该触发而不触发）」；
- **后端** `VerdictController.parseRiskOrNull` 逐字「未传 ⇒ null（⇒ 路由落 D5）」，
  且**无任何校验注解**；`VerdictService:894-895` 把 null 原样落进依据快照。

⇒ `required` 与「缺失合法」**不可能同时成立**。若真按 `required` 强制，
**PRD §7.3 的定调句就被推翻**。**这不是前端能在本地拍的**：本页采用的姿势是
**如实提供控件 + 留空即不发送该项**（对齐端 B 既有范式），并在界面上显式登记该矛盾。
🛑 **待裁项**：契约该删 `required` 里的这两项，还是后端该补校验？—— 需产品/架构裁决。

#### 8.18.5 复验数字（逐字）

- 端 A `required-args-wired`：`10 个带 required 声明的端点中，10 个有调用点可核对`
- 端 B `required-args-wired`：`9 个带 required 声明的端点中，9 个有调用点可核对`
  （端 B 那套写法**本来就是对的** ⇒ 判据在此恒绿，正是"回归"该有的样子）
- 端 C `required-args-wired`：`6 个…6 个有调用点可核对`（含 1 个**动态合并**形态，公开计数）
- 三端 `tsc --noEmit` 干净；`a-check` 全绿；`x3-check` 全绿；`build-check` `BUILD OK`
- 反向验证：端 A `19/19`（含新增 I17/I18/I19）· 端 B `11/11`（含新增 I11）·
  `build-reverse-check` 含新增 R22/R23

### 8.19 判据取文本的窗口不能用固定长度（第 66 条）

#### 8.19.1 形态：窗口太小 ⇒ 把写对了的判成"缺"（误报）

⑩b 的初版取"函数名前后各 400 字符"作为调用点文本。端 A `createVerdict(...)`
的调用点在此之后长出多个 `...(cond ? {...} : {})` 展开分支，**400 字符窗口在
`risk_flag` 之前就被截断** ⇒ 明明**无条件写在调用里**的 `confidence: {}` /
`module_scores: {}` 被判成"缺"（实测：一次性报出 4 项缺，其中 2 项是误报）。

🛑 **固定窗口长度是一个隐含假设，且两端都错**：
窗口太小会误报（把写了的判成没写）；窗口太大又会**假绿**（把隔壁函数的实参算进来）。

#### 8.19.2 修法：按**括号配平**取文本（与调用点写多长无关）

从函数名后的第一个 `(` 起做括号配平（`()` / `[]` / `{}` 三类统一计数），
取到匹配的 `)` 为止 —— 那才是这个调用的**真实边界**。配平失败（文件被截断）
⇒ 返回剩余全部，**偏向"不放过"而不是"偏向绿"**。

#### 8.19.3 回归用例是**负向**的

I18 把必需实参**推出 400 字符窗口**，期望判据**仍然绿** ——
若判据退化回固定窗口，本用例立刻红。"判据有牙齿"包含两件事：
**该抓的必须抓到**（I17）+ **不该抓的不得抓到**（I18）。

### 8.20 实参经局部变量传入时，判据必须追到**所在块**（第 67 条）

#### 8.20.1 形态：只看调用单元 ⇒ 把写对了的判成"全缺"

端 A C2 的调用点是 `submitBaselineAssessment(customerId, body, newIdempotencyKey())`
—— 6 个必需字段写在**调用单元之外**的 `const body = {...}` 里。
括号配平只取这一次调用的括号内，**看不到 `body` 的定义** ⇒
判据报"6 项**全缺**"，而 `body` 里 6 项其实**完全正确**。

#### 8.20.2 修法：调用单元 + **容纳该调用的最内层 `{}` 块**

向上找最内层 `{}`（配平取整块），把两者一并作为该调用点的文本
（块长度设上限 2500 字符，避免把整个文件拖进来）。
⇒ 判据对"实参怎么传"保持中立：内联字面量、局部变量、条件展开**都认**。

#### 8.20.3 回归用例同样是**负向**的

I19 把该调用改成**内联字面量对象**传入，期望判据**仍然绿**
（防"只认局部变量形态"的收窄）。

### 8.21 请求体是**动态合并**出来的（第 68 条）

#### 8.21.1 形态

端 C E2 的调用点写 `Object.assign({ device_id: deviceId }, rec)` ——
`metric`（契约 required）**不在字面量里**，而在运行时对象 `rec` 上
（来源见同函数内 `'t-' + deviceId + '-' + rec.metric + '-' + rec.date`）。
④i 首跑即把**一个写对了的**调用判成缺。

#### 8.21.2 修法（**带边界的认账**，不是一律放过）

只有当文本里**同时**满足两项时才认：① 确有动态合并形态
（`Object.assign(` 或 `...x`）；② **能找到该字段的成员访问证据**（`\.metric`）。
二者缺一即仍报红 —— 例如全文没出现过 `.metric` 时，我们**无法证明**它提供了该字段。
🛑 认了多少个这样的端点，在通过信息里**公开计数**（`其中 N 个是动态合并形态`），
不静默（第 53 条）。

#### 8.21.3 边界用例 R23 守的是"不得一律放过"

把 `rec.metric` 的成员访问证据抹掉 ⇒ 判据**必须报红**。
这防的正是"改成一律放过动态合并"的假绿（第 52 条）。

### 8.22 判据的「具名实参」式把**值位置**的同名标识符误认为实参（第 69 条）

#### 8.22.1 形态：实参已删，判据仍绿（假绿）

⑩b 的第二式本是"具名/位置实参"：`(?:^|[\s,(])名字[,)]`。
🛑 边界里的 `\s` **过宽**：注入 `credential__removed: credential,` 后，
值位置的 `credential,`（前面是"空格 + `:`"）被这一式命中 ⇒
**实参已删而判据仍绿**（实测 R22 漏过，`exit=0`）。

#### 8.22.2 修法：边界**只能**是 `(` 或 `,`

```js
new RegExp(`(?:^|[(,])\\s*${n}\\s*[,)]`)   // 位置实参：f(a, b) / f(a)
```
值位置（`键: 值`）不再算实参，只有真正的参数位置才算。
🛑 该缺陷在**三端各有一份副本**（端 A/B/C 的 `argFormPresent` 同式），
三处一并修 —— 否则就是第 63 条"同一缺陷在另一端的第二份副本"再犯一次。

#### 8.22.3 🛑 这一条是**判据自己的**缺陷，不是源码的缺陷

它由**反向验证**抓出（R22），而**门禁本身一直是"绿"的** ——
这正是"反向验证必须常驻、且必须是负向用例也要有"的又一处实证：
若只有 I17（正向），第 69 条会长期存活。

---

### 8.23 判据只判「名字在不在」、不判「落在哪个载体」（第 70 条）

#### 8.23.1 形态：名字在对的位置、装进错的载体 ⇒ 全部门禁绿

第 65 条补的 `required-args-wired` 只回答"必需参数**名字**有没有出现"，
**不回答"它落在哪个载体"**。受控复现（**不是理论风险**）：
把 I7 的 `version` 从 `query: { version: v }` 挪到 `params: { id, version: v }`、`query: {}`
—— 后端 `DocFileController.download(..., @RequestParam("version") int version)` 按 `in: query`
取值 ⇒ **必然 400**，而 `a-check` / `x3-check` / `build-check` / 三端反向验证
**注入前后 `exit` 均为 0**（全绿）。

根因：判据把整段调用文本**一锅判**，对"载体"没有任何概念 ⇒
"名字装错载体"与"名字没写"在判据眼里**完全等价**。

#### 8.23.2 同一个空洞的第二面：契约 required 在**类型层**被降级为可选

端 A / 端 B 的同一个函数（三份副本里的两份）：

```ts
export interface ScaleItemBankQuery {
  readonly age_group?: string;          // 🛑 契约 required，这里却是可选的
}
export function listScaleItemBanks(
  query: ScaleItemBankQuery = {}        // 🛑 载体形参又有默认值
) { /* … */ }
```

⇒ **载体可省 + 字段可省**，二者叠加使契约 required 在**编译期**被抹平：
调用点一个参数都不传，`tsc` 一声不响。
端 C 的第三份更彻底 —— `function listScaleItemBanks(ageGroup, dimension, version)`
**连运行时守卫都没有**（`listScaleItemBanks()` ⇒ `query = { age_group: undefined }` ⇒ 400）。

🛑 而端 B 的**同一函数此前已修过** ⇒ 第 63 条"同一缺陷在另一端的第二份拷贝"
第三次复现：**两端门禁各审各端，没有东西会问"还有没有别的宿主"。**

#### 8.23.3 为什么「页面有守卫」不算守住了

`scale-bank.js` 里的 `if (!this.data.ageGroup) return` 只保护了**一个**调用点。
封装层是**共享入口**，守卫必须立在**入口**上 ——
否则第二个调用点（或任何直接调用）会绕过页面守卫，静默发出空 `age_group`。

#### 8.23.4 修法：三端各加 `required-args-carrier`，并把守卫立在入口

判据要点（三端形态不同，但纪律一致）：

```js
// 🛑 只在【出站对象块】上找载体 —— 不得在整段封装体上找
//    否则 `body: ScreeningRequest` 这种【函数签名的类型注解】会被当载体（实测假阳性）
function carrierOf(blk, key) { /* literal / shorthand / expr + 局部变量回溯 */ }
```

| 载体形态 | 处理 |
| --- | --- |
| 字面量块 `body: { … }` | **逐名核**（含 `{ a, b }` 简写属性形态） |
| 简写 `{ body }` / 表达式 `query: pageQuery(…)` | **不逐名核，但公开计数**（绝不静默放过） |
| 动态合并 `Object.assign(…)` / `…x` | 同上，如实计数 |
| 局部变量 `query: query` | **回溯其初值块**再逐名核 |

实测计数：端 A 逐名 2 处 / 未逐名 8 处；端 B 逐名 1 处 / 未逐名 8 处；
端 C 逐名 5 处 / 动态合并 1 处。

#### 8.23.5 反向验证必须用「挪载体」，不能用「删实参」

| 端 | 用例 | 注入 | 期望 |
| --- | --- | --- | --- |
| A | **I20** | `version`: `query` → `params` | `required-args-carrier` 报红 |
| B | **I12** | `age_group`: `query` → `params` | 同上 |
| C | **R24** | query 载体置空、`age_group` 挪旁路变量 | 同上 |

🛑 **删实参只能证明"缺名"会被抓**（那是 I17 / R22 的形态），
**证明不了"错位"会被抓** —— 而第 70 条的缺口恰恰是"名字还在、只是装错了地方"。

复验：端 A **20/20** · 端 B **12/12** · 端 C **24/24**；还原后三端构建自检全绿。

#### 8.23.6 通用规则

> **判据必须对"值落在哪里"有概念，不能对"值叫什么"有概念就算完。**
> 与第 65 条并列：第 65 条修的是「**有没有**」，本条修的是「**对不对**」；
> 二者是同一个空洞的两层，缺任一层都会让"后端必然 400"通过全部门禁。

### 8.24 判据的「受保护集」由生成器的**认字能力**决定；契约的「必需」有一种形态生成器从来没认过（第 71 条）

#### 8.24.1 形态：生成器不认的形态，永远不会进入受保护集

第 65 条把必需参数的判据建在"生成器转出的 `requiredQuery` / `requiredBody`"之上。
🛑 由此产生一个**判据自身的空洞**：**生成器自己不认的形态，就永远不会进入受保护集**，
而"没东西要保护"与"检查全通过"在输出上**长得一模一样**。

🛑 更要紧的是：判据打印的"N 个带 required 的端点中 M 个有调用点可核对"——
若 `M < N` 尚可察觉；但**生成器漏认形态时 `N` 本身就变小**，
输出仍然自洽（端 A 从 30 掉到 10 也不报红）。

#### 8.24.2 实测：两处被静默跳过的参数形态

| 形态 | 契约里的量 | 原代码的判定 | 后果 |
| --- | --- | --- | --- |
| `$ref: '#/components/parameters/XxxId'` **复用命名参数** | **23 处**引用（含 `CustomerId` / `DocTemplateId` 两个 required **path** 参数） | `prm.get("in")` 对 `$ref` 字典返回 `None` | **23 处全跳过** |
| 内联 `in: path` | 13 处 | 原代码**只认 `in: query`** | **全部跳过** |

而契约 **P2 逐字**：

> path 参数恒 required，即 `{id}` 占位符必须在 URL 里被替换

实测 **28 个操作含路径占位符** —— 这 28 个端点的"URL 有没有被替换"，
**此前没有任何判据看过**。

#### 8.24.3 为什么会静默得这么彻底（三层叠加）

1. **`fillPath()` 三端同款**：只 `replace` params 里**出现过的**键、**不做残留检查** ⇒
   漏传会把字面量 `{id}` 拼进 URL 发出去（后端路由不匹配），既不 `assert` 也不返回错误。
   ```js
   for (const [k, v] of Object.entries(params ?? {})) {
     out = out.replace(`{${k}}`, encodeURIComponent(String(v)));
   }
   ```
2. **`tsc` 对 `params` 的键没有类型约束**（`Record<string, string | number>`）⇒ 编译期零信号。
3. **门禁只看"generator 说没有必需的，那就不查"** ⇒ 整个链条无人守。

#### 8.24.4 🛑 注释与代码不符（最值得警醒的一层）

生成器里**逐字写着**：

```python
# 只转出"调用方**必须**提供"的项：query 里 required=true 的、路径参数
# （路径参数天然必需，但调用方由 params 传入，故只登记名字供判据核对），
```

而紧跟其后的代码**只实现了 `in: query`**：

```python
if prm.get("in") == "query" and prm.get("required") and prm.get("name"):
```

⇒ **意图写进注释、代码从未实现，且没有任何判据会发现这个落差。**

#### 8.24.5 修法（四层）

1. **生成器**：新增 `resolve_param()` **解引用 `$ref` 命名参数** + 转录 `requiredPath`（path 参数恒必需）。
2. **生成器级断言**（让"漏认"不可能再静默）：
   ```python
   ph = sorted(set(re.findall(r"\{(\w+)\}", str(p))))
   if ph != sorted(set(req_path)):
       raise SystemExit("MISCONFIGURED: %s %s 的 URL 占位符 %s 与转录出的 path 参数 %s 不一致" ...)
   ```
   **URL 占位符集合必须恰好等于转录出的 path 参数集合**，不等即退出。
3. **三端判据并入 `requiredPath`**：端 A ⑩b 的 `need` + ⑩c 的载体表新增 `params` 载体、
   端 B ⑨ + 载体表、端 C ④i + ④i-2，并各自加**解析层自检**：
   `requiredPath === null` ⇒ 报红（防它再次静默消失）。
4. **登记为契约级事实**：生成物三端端点均新增 `requiredPath: Object.freeze([...])`。

#### 8.24.6 反向验证：丢掉的是 **path 参数**（与前几例是不同的载体）

| 端 | 用例 | 注入 | 期望 |
| --- | --- | --- | --- |
| A | **I21** | `getCustomer` 的 `params: { id: customerId }` → `params: {}` | `required-args-wired` 报红 |
| B | **I13** | `getRefund` 的 `params.id` 置空 | 同上 |
| C | **R25** | `request.call('getCustomer', { params: {} })` | 同上 |

🛑 与 I20 / I12 / R24（丢 **query** 载体）、I17 / R22（丢 **body** 实参）
**各是不同的载体** —— "同一件事在不同载体上的三种漏法"，必须分别有用例。

复验：端 A **21/21** · 端 B **13/13** · 端 C **25/25**；受保护端点由 10/9/6 扩大到 **30/25/14**；
三端 `tsc --noEmit` EXIT=0；生成器 `--check` `CHECK_EXIT=0`。

#### 8.24.7 通用规则

> 🛑 **判据的"受保护集"必须自己证明自己。**
> 凡"判据建立在某个生成物的字段之上"者，都要问一句：
> **这个字段的产出方认不认得契约的所有合法写法？**
> 它有几种形态没认？——认不出的形态**不是"没问题"，而是"没在看"**。
>
> 🛑 **注释里写了、代码里没写，等于没写**（甚至更坏：它让人以为写了）。

---

### 8.25 判据把「全部角色」合并判断 ⇒ 角色维度的不可达不可见（第 72 条 · 第 64 条装载层的同族第三处）

#### 8.25.1 形态：页面被装载了，但**某个角色**永远打不开它

第 64 条把触达判据抬到「装载层」（页面在 `app.json` / NAV 里声明了才会被加载），
并在 README 里留下一个猜想：**装载了但准入判定把它永久藏起来**。
本轮把猜想做成实证 —— **它真实存在**。

端 B 的装载是**带角色门的**：

```ts
const visibleNav = NAV.filter((n) => n.requires === null || canCall(n.requires, role));
```

导航项的 `requires` 不只是「依赖可查」，它是**该页对各角色的可见性开关**。
而三条既有判据**没有一条**把这两件事连起来：

| 判据 | 它判什么 | 为什么看不见本条 |
| --- | --- | --- |
| ⑦ `nav` | 该 id **存在于生成物** / 推导函数在白名单内 | 只判"id 有效"，不判"它代表该页" |
| ⑧ `endpoint-reachability` | 代码里有**一条**从界面到出站的调用链 | 对**全部角色合并**判断（`consumerCode.some(...)`）|
| `tsc --noEmit` | 类型正确性 | 对"某页对某角色是否可达"零概念 |

#### 8.25.2 受控实证

把 `{ key: 'intake', requires: 'createCustomer' }`（双角色端点）
改成 `requires: 'createRefund'`（**仅 meridian**）：

- **建档页对调理师永久消失**；
- 页面代码、调用链、端点封装**全都还在**；
- `⑦ nav` 仍报「导航项依赖的 6 项全部可核」；
- `⑧` 仍报「29 个端点全部有完整调用链」；
- `tsc --noEmit` = 0、`exit` = 0 ⇒ **全绿**。

#### 8.25.3 与第 63 / 64 条并列（否则会以为重复）

| 条 | 链路断在哪一层 | 现象 |
| --- | --- | --- |
| 63 | **代码**层 | 封装了但没接上界面 |
| 64 | **装载**层 | 接上了界面但页面打不开 |
| **72** | **角色装载**层 | **打得开，但某个角色打不开** |

🛑 第 72 条的特殊性：页面**确实被装载了**（对 meridian 可用）⇒
"文件在 / 清单在 / 调用链在"三件事**全成立**，只有「对 therapist 不可达」这一件不成立。

#### 8.25.4 判据的不变量：把第 63 条从"任一者可达"升级为"逐角色可达"

> **grantable(r) ⊆ reachable(r)**
> 某角色「有权调用的端点」，必须至少有一个**该角色能到达的页面**在用它。

第 63 条的不变量是它的**弱化版**（把 r 换成"全体角色"）——
**这正是"合并判断"丢掉的那一维。**

#### 8.25.5 修法：端 B 新增 ⑪ `reach-by-role`

解析链：NAV（key → 门控端点 → **门控角色集**）→ 渲染分支（key → 实际渲染的组件）
→ 页面用到端点（`canCall` 字面量 ∪ 封装函数名回溯生成物 id）→ **逐角色**核算。

门控角色集的三种形态各自处理：

| `requires` 形态 | 角色集来源 |
| --- | --- |
| 字面量 `'createCustomer'` | 生成物该端点的 `grantedRoles` |
| 调用 `firstSoleEndpointId()` | `solelyGrantedEndpoints()` 现算 |
| `null` | 全部本端角色（恒可见） |

落差报出**是哪些端点**（实测报出建档页 5 个：`createCustomer` / `signConsent` /
`getIntakeProfile` / `patchIntakeProfile` / `createScreeningRecord`）。

#### 8.25.6 覆盖面必须自证（第 53 / 71 条）

`pages/` 下未被任何 NAV 分支覆盖的页面必须显式进豁免清单
（端 B 实测 1 个：`LoginPage` —— 渲染在 `if (!profile)` 分支里、**每个角色都能到达**）；
且豁免清单里的页面必须**真实存在**且**确实用了端点**（防"豁免成了垃圾桶"）；
判据不认识某形态时**按失败处理**（第 64 条：不认识 ≠ 通过）。

#### 8.25.7 首跑即复发第 55 条（第三次，值得记录）

初版把"可达"定义为「端点必须挂在某个 NAV 分支里」⇒ **首跑误判 2 处合法写法**：

- `authLogin` —— LoginPage 在**面板外**渲染，登录前必经；
- `getCustomer` —— 写在 App.tsx 的 `loadCustomer()` 里，而 `customer` 分支渲染的是
  `CustomerPage`，后者用的是 `listVisits`。

⇒ **假红**。正确语义不是"每个端点必须挂在一个 tab 分支里"，
而是**「外壳层代码对外壳可见的所有角色可达」**；修正后**牙齿不变**。

#### 8.25.8 端 A / 端 C 为何无此形态

| 端 | 为什么没有检查对象 |
| --- | --- |
| A | `requires` **恒真**（39/39 端点全授予 admin）⇒ 门控**不可能**比页面更窄 |
| C | **单角色端**（客户端仅 customer）⇒ 无角色维度 |

⇒ 本条判据**只在有检查对象的一端立**，并在另两端写明为什么没有 ——
这是第 63 条「判据的覆盖范围按端切分」的**正向用法**（不是缺陷）。

#### 8.25.9 反向验证

| 端 | 用例 | 注入 | 期望 |
| --- | --- | --- | --- |
| B | **I14** | 「客户建档」门控 `createCustomer` → `createRefund`（仅 meridian） | `reach-by-role` 报红 |

🛑 与 I11/I12/I13 的区别：那三条动的是**调用点/封装体**（参数层）；
**I14 动的是导航项的门控端点**（角色装载层）—— 代码里那条调用链**完全没变**。

复验：端 B **14/14**；端 A **21/21** · 端 C **25/25**（如实复跑未受影响）；三端 `tsc` EXIT=0。

---

> 🛑 **本文件 §8 的条数已由「十六条」增至「二十四条」**（新增 §8.18~§8.25，
> 对应本仓第 65~72 条），标题行已同步订正。

---

### 8.26 真实 HTTP 世界里「路径不存在」被答成「服务端故障」（第 73 条）

#### 8.26.1 缺口：应用能启动、健康检查 200、全量 1214 个测试全绿 —— 而未知道路返回 `500 · 9001`

本轮把「编译完成」推进到**实跑验证**：`java -jar dy-app-0.0.1-SNAPSHOT.jar` 启动成功
（`Started DyAppApplication in 6.064 seconds` / Flyway 22 迁移校验通过 / `/actuator/health` → `200 {"status":"UP"}`），
随后对若干端点做真请求冒烟。**已存在的端点全部正确**（A2 `401·1002` / A3 `403·2001` / E5 `422·5001` / demo 系列 200/403），
但 `GET /api/v1/definitely-not-exist` 返回 **`500 · 9001「系统异常: NoResourceFoundException」`**。

这是**契约 §2.0 明确定义过**的码：`404 → 3001 NOT_FOUND「资源不存在」`—— 🛑 但 `3001` 的 trigger
逐字限定「**仅限本租户内确实不存在**」= **业务资源**（工单/客户/门店…），**「URL 拼错」不在其列**。
契约只定义 40 个 path，未知路由**不是契约端点** ⇒ 正确形态是 **`404`「不带 code」**，而 `9001 INTERNAL_ERROR`
的 trigger 逐字是「**服务端异常**」—— 调用方敲错 URL 与服务端故障是两件事。

#### 8.26.2 为什么它不是「错误码不精确」而是契约违背

把「请求根本不合法」答成 5xx，有三个具体坏后果：

1. **端侧行为错**：SDK 拿到 5xx 会按「服务端故障」重试（退避 + 熔断），而这是**永远不会成功**的重试
   （正确动作是让开发者改 URL）；
2. **告警噪音**：监控上「5xx 率」是服务健康度核心指标，把每个 URL 笔误都计入 5xx，会让真实故障淹没在噪音里；
3. **排查方向被带偏**：`9001` 的消息会把运维引去查服务端，而问题在 URL 里。

这与本仓第 61 条（C-3）修过的 `HttpMessageNotReadableException` 被答成 500 是**同族** ——
「请求在进入业务逻辑之前就不合法」的各类，都不该落到 `GlobalExceptionHandler#handleOther`。

#### 8.26.3 边界：与「跨租户不返回 404」不冲突

契约 `x-global-conventions.tenant-context` 的「不返回 404 以避免存在性探测」，**语境是「跨租户资源」**：
路径**存在**、只是不属于本租户 ⇒ `403 · 2003`。本分支处理的是**路径根本不存在**（无任何处理器匹配），
此时不存在「探测某资源是否存在」的信息泄露面（回应与租户无关），且契约已显式定义该码。

#### 8.26.4 为什么 1214 个测试全绿却抓不到它（防御体系里缺的那一格）

本缺陷**只在真实 DispatcherServlet 路由解析**里出现。本仓**全仓无 MockMvc**，E2E 都走真容器 ——
但它们**只请求已存在的端点**；而单测直调控制器方法**不经过路由**，永远看不到 `NoResourceFoundException`。
⇒ **「不存在的输入」这一整类从未被任何测试喂过。**

另有一处**既有的、被当成噪声绕过去的证据**：`DocFileE2ETest` 的注释逐字写着
「实测遇到 HashSet 乱序导致首个异常**可能是 `NoResourceFoundException`**」——
那是把它当作**测试侧的解析噪声**绕过去，而不是当作**生产侧的错误码缺陷**来修。

#### 8.26.5 修法与复验（逐字）

- **修法**：`GlobalExceptionHandler` 新增 `@ExceptionHandler(NoResourceFoundException.class)` →
  `ResponseEntity.status(HttpStatus.NOT_FOUND).build()`，即 **`404` 且【不带】`code` 字段**（**刻意不回 body**），
  留痕级别取 **`warn`**（URL 笔误是预期路径，不是故障，沿用本类「4xx 不打堆栈、不进 error 噪音」的分级纪律）。
  🛑 **为什么不是 `404 · 3001`**：① 契约只定义 40 个 path，未知路由**不是契约端点**，「四字段信封」不适用于它；
  ② 本仓 `RefundWritePathMatrixE2ETest$SelfProof#the_self_proof_discriminates_a_nonexistent_path`
  **逐字断言「不存在的路径【不得】返回 code=3001」** —— 那条自证用例正是靠「未知路径无 code」区分
  「端点根本没实现」与「业务层查无此单」；给出 3001 会让它丧失分辨力（第 55 条）。
- **🛑 同族枚举补齐（本条最重要的增量）**：修完 404 后按「修一处同族必须枚举全族」逐条实测，
  又抓出同族的**三个**成员，它们此前**同样以 `500 · 9001` 作答**：
  **(1) 405** `HttpRequestMethodNotSupportedException`（实测 `DELETE /api/v1/doc-templates`）；
  **(2) 415** `HttpMediaTypeNotSupportedException`（实测 `POST /api/v1/demo/order` 带 `text/plain`；
  **必须用匿名端点 `/demo/*` 才测得出来** —— 换成需鉴权端点会先在 filter 返回 403，测不到本体）；
  **(3) 406** `HttpMediaTypeNotAcceptableException` —— **它的 HTTP 状态码本来就是 406（看起来完全正确），
  但留痕打了 `ERROR` + 满堆栈**（走的仍是 `handleOther`）。
  ⇒ **修法**：四类合并进 `handleUnroutableRequest`，**全部回裸状态码、不带 code、`warn` 留痕**
  （判据：契约 §2.0 码表只有 10 个码，**没有 405/415/406**；`1001` 的 trigger 逐字是「参数类型/必填/约束不满足」，
  说的是**参数的值** ⇒ 拿它答 405/415/406 属 **code 语义挪用**）。
- **新增真请求 E2E`UnknownRouteEnvelopeE2ETest`（8 例）**：① GET 未知路径 ⇒ `404` 且**非 9001**；
  ② POST 未知路径 ⇒ 同上（不因方法不同而漂移）；③ **边界**：未知路径**不得**带 `code=3001`（把分辨信号反过来钉住）；
  ④ 对照：`/auth/me` 无 token ⇒ 必须 `401·1002`，**不得**被误判 404；
  ⑤ 405（方法不支持）⑥ 415（媒体类型不支持）⑦ 406（Accept 不可接受）⑧ 四类**一律不带 code**的一致性。
  第 ④ 例是反向守护 —— 把「未认证」谎报成「路径不存在」会比原缺陷更危险。
- **反向验证（闭环）**：删掉注解里的 405/415 两类型（方法体保留）⇒ 重装 `dy-web` 后
  `Tests run: 7, Failures: 3` · `BUILD FAILURE`，**逐字复现 `500 · 9001`**；还原 ⇒ **8/8 绿**。
- **回归**：`RefundWritePathMatrixE2ETest$SelfProof` **3/3 绿**（本条修复未破坏既有自证设计）；
  `DocFileE2ETest` **10/10**；修复后真请求 `404`/`405`/`415`/`406` **全部为裸状态码、body 为空**，
  `未捕获异常 = 0` · `请求不可路由 = 5`，且 health `200` · A2 `401·1002` · A3 `403·2001` 全部通过。
- 🛑 **本条最硬的一条教训（406 抓出方式）**：**「处理是否正确」要看「HTTP 状态码」与「留痕级别」两件事**。
  406 的**状态码本来就对**，只有留痕级别错 —— 我首轮枚举时正是因此把它写成「Spring 裸默认、无需处理」，
  **是复查启动日志（`grep 未捕获异常`）才把它抓出来**。⇒ 只看状态码，会把「半个正确」看成「全对」。
- 后端全量回归 `BUILD SUCCESS` / **`TOTAL 1266 failures=0 errors=0 skipped=0`**（2026-10-09 第 82 条收口：1249 → 1266 = SettlementStatementHashTest 3 + SettlementCommitE2ETest 7 + RlsV24SettlementIsolationTest 7）（2026-10-08 第 81 条收口：1227 → 1249 = JwtIssuerTest 7 + AuthLoginE2ETest 8 + RedisTokenRevocationCheckerTest 7；逐模块 `10/46/55/58/37/37/29/977`）
  （逐模块 `10/39/48/58/37/37/29/969`；dy-app `965 → 969` = 第 73 条新增 **8 例** + 族级枚举第三轮 1 例 + 第 74 条新增 **3 例**（RequestValidationEnvelopeE2ETest）+ 第 75 条新增 **1 例**（UploadPayloadTooLargeE2ETest））
- 🛑 **过程性教训**：首轮 `build-reverse-check.mjs` 前台超时被 SIGTERM 掐断，**脚本残留了注入物**，
  隔一轮再跑报「基线不是绿的」——**看起来完全像真实缺陷**，我据此「修复」了 `domain.ts`，
  而 `git show HEAD:<path>` 显示 **HEAD 里本来就是 `pageQuery`**。
  ⇒ **反向验证脚本一经中断，第一件事是 `git status --porcelain` 还原，还原前不得解读任何 FAIL。**

---

### 8.27 三端"零测试"缺口收口（第 76 条 · 2026-10-08）：把「构建通过」升级为「行为被断言」

#### 8.27.1 缺口：三端只有"契约一致 + 类型构建"，**没有一行运行时行为断言**

三端此前各有 `check:contract`（生成物与契约逐字一致）与 `check:build`（`tsc --noEmit` + `vite build`），
但它们**只回答"代码能不能编译、名字对不对"**，不回答"发出去的那条请求，URL/头/信封/错误解析到底对不对"。
⇒ 出站层的三处**跨端隐式协议**（Base Path 前缀、`Authorization` 头名、`Idempotency-Key`）
与错误路径的 `data` 保留，**全靠门禁的语法扫描**守住，没有一条用例**真发一次请求**去验。

#### 8.27.2 收口：为三端各建一套行为测试（共 **48 例**）

| 端 | 用例数 | 基建 | 覆盖 |
|---|---|---|---|
| 端 A `admin-web` | 18（3 文件） | vitest@2（`node` 环境） | `call` 出站（前缀/Bearer/幂等键/多段路径参数）+ 错误路径带 `data` + 生成物自洽（39 ops） |
| 端 B `therapist-app` | 18（3 文件） | vitest@2（`forks` + `singleFork`） | 同上（差异：`role` 必填、`th-` 幂等前缀、29 ops） |
| 端 C `client-mp` | 12（2 文件） | Node 内置 `node --test`（**零依赖**） | CJS `request` 层（`wx.request` 桩）+ 生成物自洽（15 ops） |

🛑 **为什么端 C 用 `node --test` 而不是 vitest**：端 C 是原生小程序（CJS、无构建期），
其自检脚本注释逐字写着 `real-build: 本端不适用`。为它拉一整套 vitest + jsdom，
会让"零依赖即可跑"这一端 C 的既有属性倒退。用 Node 内置 runner，`npm test` 无需任何 `node_modules`。

#### 8.27.3 三处真缺陷（都是本轮**新写的测试或串行门禁**抓出来的）

1. **两端的 `tsc --noEmit` 被测试文件打破**：`beforeEach` / 一个未使用的 `const f` 触发 `TS6133`
   ⇒ `check:build` 由绿转红。**测试文件在 `tsconfig` 的类型检查范围内**，写测试同样要过类型门禁。
2. **端 C 测试桩未注入后端基址** ⇒ `requestBaseUrl` 取值即抛
   （`env.js` 三环境基址均 `null`，取用即显式报错 —— **这是有意的 fail-closed**，不是缺陷）。
   测试须显式 `ENV.setBaseUrl('develop', ...)`。
3. **`vitest` 在 brokered-fs 下的偶发 `EPERM` 竞态**：多进程并发写 `node_modules/.vite` 时，
   部分测试文件**未被收集**（报了 "Test Files 1"，而实际有 3 个）。
   ⇒ 两端统一 `pool: 'forks'` + `poolOptions.forks.singleFork: true` 串行化；冷启动连跑两次验证确定性。
   🛑 **这条是"绿得不完整"**：它不是红，而是"少跑了两文件却仍显示 passed" —— 只有**核对用例数**才发现。

#### 8.27.4 反向验证脚本**禁止并行**

本轮曾把端 A / 端 B 的 `check:build-reverse` **并行**跑，端 A 报 FAIL。
根因：该脚本会**对三端同一批文件**做"注入故障 → 还原"，两个实例并行即互相踩踏。
⇒ **反向验证脚本必须串行**；串行后 25/25 PASS、`git status` 干净无注入残留。

#### 8.27.5 顺带抓出的 CI 缺口：**流水线只跑了最弱的那条判据**

`build-and-test.yml` 的前端任务原本只调 `build-check.mjs`，而它**不含**
`endpoint-reachability` / `required-args-wired` / `required-args-carrier` / `reach-by-role`
—— 这四条判据在 `a-check.mjs`（端 A）/ `x3-check.mjs`（端 B）/ `build-check.mjs`（端 C）里。
⇒ 「端 A C1 调用缺必需参数」那类缺陷（第 65 条）**在 CI 里根本不会被拦**，
因为跑的那个脚本不判实参。

**修法**：CI 前端任务改为按端跑**全部门禁**：
端 A `check:build` + `check:a` + `test`；端 B `check:build` + `check:x3` + `test`；
端 C `build` + `test`；反向验证三件套（`build-reverse` / `a-reverse` / `x3-reverse`）**串行**执行。

#### 8.27.6 复验（逐字）

- 三端全门禁**串行**通过：端 A `contract/build/a/a-reverse/build-reverse` 全 PASS；
  端 B `contract/build/x3/x3-reverse/build-reverse` 全 PASS；端 C `build/build-reverse` PASS。
- 三端测试：端 A `18 passed (3 files)` · 端 B `18 passed (3 files)` · 端 C `# pass 12 # fail 0`。
- 后端全量 `mvn -o clean install` **BUILD SUCCESS**（8 模块全 SUCCESS，`Tests run: 859, Failures: 0, Errors: 0`；
  码级锚点 `TOTAL 1227` 对齐）。CI `build-and-test.yml` 已把「三端测试 + 反向验证」纳入流水线。

### 8.28 「写下的命令」第三次（第 77 条）：CI 里那条 `npm run check:contract` 在本机是红的（2026-10-08）

本轮把"全链路实跑"作为唯一入口（后端 `mvn clean install` + 四端门禁 + 四组反向验证），
**一次跑出五处"能写出来、但跑不起来"的缺口**。五处都不是产品代码问题，
全是**构建/工具链自身**的问题，且**全都没有任何编译或门禁会报**。

#### ① `build-and-test.yml` 两个 job 都没有准备 Python（阻断级）

`backend` job 跑 `mvn clean install`，而根 `pom.xml` 的 **validate 阶段**有 4 个
Python 门禁（ADR-12 词表扫描 / s16 零派生 / s18 契约一致性 / s19 SDK 表面）；
`frontend` job 里端 C 的 `npm run build` 会调 `gen-endpoints.py --check`。
这些门禁**刻意**要求一个真正的 YAML 解析器，缺 PyYAML **一律 exit 2**、
**不**退化成正则后照常打印 PASS。

⇒ 在一个干净的 runner 上这两个 job **必然红**，而红的措辞是
`MISCONFIGURED: PyYAML is required` —— 指向"契约不一致"这个**完全错误**的方向。

**对照证据**：同一个仓库里的 `.github/workflows/compliance-gate.yml` **一直**有
`actions/setup-python` + `python -m pip install "pyyaml>=6.0"` 两步；`build-and-test.yml` 漏了。
**修法**：两个 job 各补 setup-python + `pip install -r skeleton/requirements.txt`。

#### ② `package.json` 里裸调 `python`（第 48 条同族）

`admin-web` / `therapist-app` 的 `gen:endpoints` / `check:contract` 原写
`python ../tools/gen-endpoints.py [--check]`。实测本机：

```
$ npm run check:contract      → EXIT=2
MISCONFIGURED: PyYAML is required (exit 2)
```

而**端 C 的构建走的是 `build-check.mjs`，那里早就有 `resolvePython()` 解析器** ——
同一件事两套写法，其中一套（裸 `python`）没走到正确解释器。

**修法**：抽 `tools/py.mjs`（解析器唯一实现）+ `tools/gen-endpoints.mjs`（Node 入口），
`package.json` 一律改为 `node ../tools/gen-endpoints.mjs [--check]`；
并把这条**做成判据** `no-bare-python-script`（判 scripts 里的**调用形态**，不判词出现）
+ 反向注入 **R26**。

#### ③ 🛑 新机制：**探针与真调用不同形** ⇒ 解释器换了一个人（本轮最值钱的一条）

这不是"忘了装包"，而是一个**平台层级的陷阱**：

- 探针跑的是 `py -c "import yaml"`（**没有脚本文件**）；
- 真实跑的是 `py gen-endpoints.py`（**是一个脚本文件**）。

Windows 的 `py` 启动器会**读脚本首行的 shebang**。而 `gen-endpoints.py` 首行是
`#!/usr/bin/env python` ⇒ 启动器用 **PATH 上的 `python`** 重新解析。实测三连：

| 命令 | 实际解释器 | yaml |
|---|---|---|
| `py -c "import sys;print(sys.executable)"` | Python312 | ✅ 6.0.3 |
| `py <**带** shebang 的 .py>` | 受管 3.13.12（PATH 上那个） | ❌ No module named 'yaml' |
| `py <**去掉** shebang 的 .py>` | Python312 | ✅ 6.0.3 |

⇒ 旧版 `resolvePython()` 的探针**通过**、紧接着的真调用 `exit 2`。
**"探针通过"与"真调用能跑"是两件事** —— 这正是本仓反复记的"验证手段本身会骗人"。

**修法**（不是删 shebang —— 那是把宿主平台的怪癖写进语言规范）：
**让候选自报 `sys.executable` 绝对路径，之后一律用该绝对路径执行脚本** ——
从此不经过 `py` 启动器，shebang 也就不参与解释器选择。
候选顺序也改为**受管 venv 先于受管裸解释器**（裸解释器往往没 PyYAML）。

#### ④ `Dockerfile` 在两条**独立**理由下都不可能构建成功

`docker build` 的上下文是 `skeleton/`，而门禁要读的 `contract/openapi-v1.0.0.yaml`
在**上一级**（`.dockerignore` 还显式排除了 `frontends` / `.github`）
⇒ 门禁的 docs-root 向上探测必然失败、exit 2 —— 在镜像里跑它们**不是难，是不可能**。
叠加第二条：`maven:*` 基础镜像里既没有 `python` 这个可执行名、也没有 PyYAML。

**修法**：`-Dexec.skip=true` **显式**跳过（与同行 `-DskipTests` 同性质：本 RUN 的定位是**打包**），
并把两条理由逐条写进 Dockerfile 注释 —— 门禁的归属是 CI 的 `backend` job
（那里构建上下文是整个仓库、契约在场、Python 已备好）。
🛑 **本机 Docker daemon 未运行 ⇒ 这一条【未本机实测】**，已如实登记。

#### ⑤ 「修法」写在**报错文案**里，那也是一句"写下的命令"（同一轮的第五处）

门禁在"生成物缺失"时会给出一句**修复指引**。本仓共 5 处 JS 报错文案
（`a-check` 1 处 / `x3-check` 1 处 / `build-check` 3 处）；
更隐蔽的是 `gen-endpoints.py` 自己会把"重跑 / 校验"两行**烧进每一份产物的文件头**
（Kotlin 一处、TS/JS 一处），外加 `--check` 不一致时的一行 stderr —— 合计 **5 + 4 + 1 = 10 处**。

这些字面量此前**一律是裸 `python frontends/tools/gen-endpoints.py`**。
⇒ **你照着报错去修，反而会踩进同一个坑**；而它比"文案不好看"严重得多：

- 那几行就印在产物里 `GENERATED FILE — DO NOT EDIT` 的**正下方**，
  下一个维护者会把它当**权威指引**，而指引本身**跑不起来**；
- **判据管不到它**（判据不扫自己的报错文案），review 也容易放过（"只是句话"）。

**修法**：10 处全部改为 `node frontends/tools/gen-endpoints.mjs [--check]`；
`gen-endpoints.py` 的 docstring 把 `.mjs` 列为**推荐入口**、把裸 `.py` 降为
"等价形式（须自备 PyYAML）"，并写明三条理由（两个解释器 / 启动器读 shebang / 探针与真调用不同形）。
🛑 **改完必须重跑生成器**（产物文件头已变），再用 `--check` 证明"生成物 == 契约"仍成立 ——
否则就是把"文案修好、产物过期"换了个新缺陷。

#### 🛑 一段自曝：我本轮**亲手**把 `build-reverse` 跑红了一次

诚实登记，因为它正是 §8.27.4 那条规则的实证。

第一次跑四组反向验证时，`build-reverse` 报 **19/26**：`R20~R26` 全部呈现
"注入后 `exit=1` 且**命中判据 ✓**，但**还原后 `exit=1`**"，而 `R1~R19` 还原干净。

那个形态很特别：**"抓得住"与"还原不了"同时成立** ⇒ 不是判据没牙齿，
而是**还原之后，树本来就是红的**。

**根因**：我在该脚本**运行期间**编辑了 `tools/gen-endpoints.py`
（把烧进产物的头注释从 `python ...` 改成 `node ...`）⇒ 磁盘上四份产物仍是旧头、
生成器已吐新头 ⇒ `gen-endpoints.py --check` **全局变红**
⇒ 每一项测完再还原，也回不到绿。时序也对得上（编辑落在 R19 与 R20 之间）。

**三条可复用结论**：

1. **§8.27.4「反向验证脚本禁止并行」不只是"别同时跑两个脚本"** ——
   它同样禁止**在它运行期间编辑任何被它注入、或它依赖的文件**。本条的实测代价就是一次假 FAIL。
2. **`build-reverse-check.mjs` 的注入目标包含 `gen-endpoints.py` 自身**
   （R4 一族就是"让生成器不再转录契约"）⇒ 这个文件在反向验证期间**必须冻结**。
3. **判读"还原后 `exit≠0`"时，先问一句"基线现在绿吗"**。若还原目标本身是红的，
   那不是判据的毛病，是**基线被弄脏了**。事后一行
   `gen-endpoints.mjs --check` 就把真因钉死了（四端同时报 `[FAIL] ... 契约已改但未重跑生成器？`）。

> 顺带一个正面结论：`android-check` / `android-reverse` **不受**此影响
> —— 它直接读契约与生成物结构，**不做** `--check` 对拍，故同一次运行里
> `android-reverse` 仍 **43/43 PASS**。这也说明"谁依赖对拍、谁不依赖"必须分清。

#### 复验（逐字）

- 后端 `mvn -B -ntp clean install` **BUILD SUCCESS**（**9 个 reactor 模块**全 SUCCESS =
  1 个聚合 POM + 8 个功能模块〔7 业务 + `dy-crypto`〕；
  **`Tests run: 1227 / 199 个测试类 / Failures: 0 / Errors: 0 / Skipped: 0`**，含真库 RLS 隔离套件）。
  > 🛑 **订正（本轮自查发现）**：本条初稿曾写 `Tests run: 2454` —— 那个数是**把
  > "逐测试类"与"逐模块汇总"两批行**都加了一遍（1227 + 1227），**是双计**。
  > 真值 **1227**，与仓库自己的文档计数锚点 `DocTestCountAnchorGateTest`（`TOTAL 1227`）一致。
  > ⇒ 又一次印证第 60 条：**"计数"本身就是一句活断言**；而且**订正必须写出来，不能悄悄改掉**。
- **四端门禁全绿，逐条 `exit=0`**：端 A `check:contract` + `check:build` + `check:a` + `npm test`（**18 例**）·
  端 B `check:build` + `check:x3` + `npm test`（**18 例**）· 端 C `build` + `npm test`（**12 例**）·
  端 D `android-check`（25 条判据）。（三端行为测试合计 **48 例**，与 §8.27.2 同数。）
- **四组反向验证共 104 组全部 PASS、还原干净**：build **26/26** · a **21/21** · x3 **14/14** ·
  android **43/43**；且**还原后再次复证** —— `gen-endpoints.mjs --check` 四端全绿 +
  `android-check` 绿（这才是"还原干净"的证明，不是靠脚本自己声称）。
  > 🛑 这是**本轮当时的**口径。同日晚些的 §8.29 又给 x3 追加了 3 组注入（I15~I17）
  > ⇒ 107 组；随后 §8.30（第 79 条）再追加 **R27 / R28** ⇒
  > **当前总数 110 组**（build **29** · a 21 · x3 **17** · android 43），最终口径见 §8.31.6。
- 新判据 `no-bare-python-script` 首跑即自证覆盖面：`package.json 的 10 条脚本里无裸调 python`。
- **全新克隆**（`git clone` 到临时目录）→ 端 D 门禁绿 → `assembleDebug`
  **BUILD SUCCESSFUL**（**无 `local.properties`**，只靠 `ANDROID_HOME`，与 CI 同条件）
  → 产出 `app-debug.apk` 7,338,658 字节。**这条同时是端 D 整端入库的收口证据**。
- **⑤ 改完重跑生成器**（`node frontends/tools/gen-endpoints.mjs`，四端
  `operations = 15 / 29 / 39 / 29`）→ `--check` **四端全绿**；
  四份产物的 diff **各为 2 行增 2 行删**，逐字只有那两条头注释
  （`重跑:` / `校验:`），**零语义变更**。
- 四组反向验证**在「无并发编辑」的干净条件下重跑**：**104/104** 全部 PASS、还原干净
  （见上方"自曝"一节 —— 第一次跑出的 build 19/26 是**并发编辑**导致的**假 FAIL**，不是判据问题）。
  > 📌 **口径说明（2026-10-08 补记）**：这里的 **104/104 是【本条修复当时】的口径**
  > （build 26 / a 21 / **x3 14** / android 43）。紧随其后的第 78 条为端 B 新增了
  > **I15 / I16 / I17** 三组注入 ⇒ 107/107；再往后第 79 条又新增 **R27 / R28** ⇒
  > **当前仓库的总口径是 122/122**（build 35 / a 27 / x3 17 / android 43，见 §8.31.6 与 §8.34.5）。
  > 🛑 之所以**保留 104 而不改成 107**：那是**当时的实测结果**，改掉就变成了
  > "事后把自己的历史改得更好看"（第 60 条：计数是活断言，**订正必须写出来、不能悄悄改掉**）。
  > 两者不矛盾 —— 一个是**该次修复的证据**，一个是**当前基线**。

#### 教训（可复用）

1. **"CI 写了"不等于"CI 跑得起来"** —— 与第 48 条同族，本轮是第三次。
   唯一可靠的验法是**在干净环境里真跑一遍**（本机用克隆模拟）。
2. **探针必须与被测的真实调用【同形】**。`-c` 与"脚本文件"在 Windows 上是两条路。
   凡探针通过的项，问一句："我真跑的时候，命令行长这样吗？"
3. **同一件事两套写法 ⇒ 其中一套一定会漏**。解析器抽出唯一实现，并加判据钉形态。

### 8.29 同一个角色、两个端、两个名字 —— 契约里明明有 `display`，却仍被手写了一遍（第 78 条 · 2026-10-08）

#### 8.29.1 缺口：契约给了唯一名字，两个端却各写一份

契约顶层 `x-roles` 逐字给出每个角色的 `display`：

| 角色 | `token-role` | `end` | `display` |
|---|---|---|---|
| `client` | `client` | mp | 客户（小程序） |
| `therapist` | `therapist` | app | 调理师（APP） |
| `meridian` | `meridian` | app | 经络师（APP） |
| `admin` | `manager` / `area` / `hq` | web | 管理员（门店负责人 / 区域督导 / 总部运营） |
| `store_customer_service` | `null` | `null` | 门店客服（无端，无任何接口） |

它是"**角色 × 端**"的展开名（带端后缀），本来就是为"跨端不混"设计的。

- **端 D（原生 Android）**：`Access.roleDisplay()` 遍历 `Endpoints.ROLE_EXPANSION`，
  按 **token** 匹配取 `display`，取不到**回退原码**。✅ 以契约为准。
- **端 B（Web）**：`contract/access.ts` 手写
  `ROLE_LABEL = { therapist: '调理师', meridian: '经络师' }` —— 一张**短形表**。❌ 第二份权威。

两者**都不算错**（短形也不是错别字），但**同一角色在两端文本不同**。

#### 8.29.2 🛑 它为什么能在门禁下活很久（本条最值得记的一点）

| 已有的防线 | 它为什么拦不住 |
|---|---|
| 编译（`tsc --noEmit`） | 短形表是合法 TS，类型完全正确 |
| `vite build` | 与构建无关 |
| `cross-end-protocol` / `no-protocol-literal` | 那几条判的是**协议片段**（头名 / Base Path / 信封字段），角色**显示名**不在其射程内 |
| `x3-single-authority` | 它判的是"**角色判断**只允许在 access.ts"——手写标签确实**在** access.ts，位置完全合规 |
| `named-enum-verbatim` / `enum-labels-verbatim`（端 D） | 判的是**维度 / 年龄组**枚举与**标签表**，且只在端 D |
| 反向验证 43 + 21 + 14 组 | 注入的都是**行为性**缺陷，显示名不参与任何行为 |

⇒ **"查了也查不出来，因为它不在任何判据的射程内"**。
与第 41/42/43 条（静默假绿）**同族但形态更隐蔽**：
假绿是"判据没查"；本条是"**判据都查了、且都查对了**，只是从没有人问过这个问题"。
它唯一的暴露途径是**把两端界面并排看** —— 而本仓当时没有任何一步会这么做。

#### 8.29.3 修法：让中文名在整个仓库里只剩一份

| 落点 | 收敛前 | 收敛后 |
|---|---|---|
| `contract/access.ts` 的 `ROLE_LABEL` | 手写短形表 | `END_TOKEN_ROLES` 遍历 + `roleDisplay(r)`（**键取自生成物、值取自契约**） |
| `roleDisplay(role)` | 不存在 | 遍历 `ROLE_EXPANSION` → 按 **token** 匹配 → **取不到回退原码**（与端 D 语义逐条一致，**绝不猜中文**） |
| 5 处界面静态角色名（`ServicePage` 2 / `WorkbenchPage` 2 / `MeridianActionsPage` 1） | 硬写「仅经络师」「仅调理师」 | 改走 `roleDisplay('meridian')` / `roleDisplay('therapist')` |

🛑 **为什么连界面文案也要改**：只改 `ROLE_LABEL` 会让**同一个端内部**出现
「经络师（APP）」（由 `ROLE_LABEL` 渲染）与「经络师」（硬写文案）并存 ——
那是**把跨端漂移换成了端内漂移**，更糟。

🛑 **为什么不猜中文**：`roleDisplay` 取不到时返回**原码**而不是挑一个像样的中文名。
猜出来的名字会掩盖"契约根本没声明这个角色"这个事实 —— 与契约"不得模糊报错"同一条纪律。

#### 8.29.4 新判据：`x3-role-label-from-contract`（三条子规则互相独立）

1. `ROLE_LABEL` 的构造体**必须引用** `roleDisplay` / `ROLE_EXPANSION`（真的取自生成物）；
2. 构造体内**不得出现任何 CJK 字符**（手写的才是第二份权威）；
3. **覆盖面自证**：生成物里声明的每个 `ROLE_EXPANSION` 条目都必须被本判据解析到，
   且 `END_TOKEN_ROLES` 的每个角色都必须有**非空** `display`
   —— 否则运行时会**静默回退成原码**，而所有判据都看不见它。

🛑 判据形态遵循第 52 条：判**构造形态**，不判"文本里有没有中文"
（后者会被注释或无关字符串满足；本判据对 `access.ts` 先剥注释再圈定语句）。

🛑 判据"**不认识当前写法**"时**必须报红，而不是静默通过**：圈不到
`export const ROLE_LABEL` 的声明、或生成物里解析不到 `ROLE_EXPANSION` / `END_TOKEN_ROLES`、
或解析到的条目数对不上声明数时，一律 `fail` 并写明"判据覆盖面不足，不得当作通过"（第 53 条）。

#### 8.29.5 反向验证：三条子规则各守一次（I15 / I16 / I17）

🛑 **为什么拆三组而不是一组**：合成一组会出现"三条里只有一条真在承重、另两条是装饰"
的情况 —— 那正是第 53 条要防的。故三组注入**各自只触发一条**：

| 注入 | 改什么 | 只触发 |
|---|---|---|
| I15 | 展示名不再取自生成物（`r.toUpperCase()`），仍是纯 ASCII | 规则 1 |
| I16 | 仍取自契约 `roleDisplay(r)`，但加手写中文兜底 | 规则 2 |
| I17 | 生成物里某个角色的 `display` 被清空（运行时将静默回退原码） | 规则 3 |

#### 8.29.6 复验（逐字）

- 端 B 门禁 **14 → 15 条判据**（新增 `x3-role-label-from-contract`）：`X-3 OK` / `exit=0`；
- `check:build` + `check:x3` + `npm test`（**18 例**）全绿；
- 反向验证 **14 → 17 组**：**17/17 PASS**、还原干净、`git status` 无注入残留。

#### 8.29.7 通用规则（可复用）

**契约里已经存在的每一类"给人看的字符串"，都必须在每个端上有一个可机械验证的落点。**
"两个端各写一份文案"之所以危险，不是因为会写错，而是因为**写对了也看不出来**。

### 8.30 构建依赖的「源」被烤进版本库（第 79 条 · 第二十四类，2026-10-08）

#### 8.30.1 缺口的形状

三处**构建输入**里写着**中国镜像**的地址 —— 本机在中国，这是刻意的优化，**本身不是错**：

| 文件 | 字段 | host | 条数 |
|---|---|---|---|
| `admin-web/package-lock.json` | `resolved` | `registry.npmmirror.com` | **182** |
| `therapist-app/package-lock.json` | `resolved` | `registry.npmmirror.com` | **122** |
| `therapist-android/gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` | `mirrors.cloud.tencent.com` | 1 |

合计 **304 条 `resolved`，官方源 0 条**。

而 **CI runner 在境外**。⇒ "能不能装 / 能不能拉"取决于一个**我们不受控的第三方 CDN**；
一旦它慢或挂，红的位置离代码十万八千里。

#### 8.30.2 它为什么能在门禁下活很久（本条最值得记的一点）

**三条独立的"看不见"叠加在一起：**

1. **本机的 npm registry 本来就配成 npmmirror**（`npm config get registry` →
   `https://registry.npmmirror.com`）⇒ **配置与锁文件同向**，于是无论走哪条路都落到同一个
   host，**差异永远不显形**。只有在"配置与锁文件不同"的机器上才暴露 —— 而那台机器就是 CI。
2. **`npm ci` 的语义是【逐字沿用】锁文件里的 `resolved` URL**，不是"按 registry 重解析"。
   所以哪怕 CI 上 registry 配的是官方源，它照样去拉 npmmirror。
3. **Gradle 侧镜像对本机更快**（实测镜像 200 / 0.11s，官方 307 / 0.89s），且发行版
   **已经缓存在 `~/.gradle/wrapper/dists`** ⇒ 本机连"下载慢"这个信号都收不到。

🛑 与**第 77 条同族**（"CI 从未真跑过"），但**不是同一处**：第 77 条修的是"CI **缺**步骤"，
本条修的是"CI **已在该步骤里，但步骤踩在一个第三方源上**"。两者都在"构建全绿、上线才炸"的射程内。

#### 8.30.3 实测（不是推理）

**① 最小复现（`_work/npmhost-probe`，单包 `is-number@7.0.0`，锁文件 `resolved` 指向 npmmirror）：**

```
A. 不覆盖（npm 默认 replace-registry-host=npmjs）
   → https://registry.npmmirror.com/is-number/-/is-number-7.0.0.tgz
   → https://cdn.npmmirror.com/packages/is-number/7.0.0/is-number-7.0.0.tgz
B. 覆盖后（registry=官方源 + replace-registry-host=always）
   → https://registry.npmjs.org/is-number/-/is-number-7.0.0.tgz
```

⇒ 这不是理论风险，是**已观测的真实取包路径**。

**② 为什么默认值等于不生效**：`npm config ls -l | grep replace-registry-host` →
`replace-registry-host = "npmjs"` —— 语义是"**只**替换官方 host 的条目"，
而我们的锁文件里**一条官方 host 都没有** ⇒ 默认值什么都不做。

**③ Gradle 侧的改写逐字验证**（改写前的副本上跑 CI 里那条 `sed`）：

```
改前：distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.3.1-bin.zip
改后：distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
```

**④ 两源可达性（本机 HEAD）**：镜像 `200 / 0.11s` · 官方 `307 / 0.89s`（307 是正常跳转）。

#### 8.30.4 修法 —— 为什么**不**把仓库里的镜像改掉

两条路：① 把提交的源改成官方源；② **在 CI 里显式归一**。

选 ②，三条理由：

1. **本机是中国开发者的真实工作机**，镜像是实测有效的本地优化，改掉会拖慢每个人的首次构建
   （Gradle 发行版 ~130MB、npm 依赖 300+ 包）；
2. 本地改源会让**工作区与版本库长期不一致** —— 与本仓"`git status` 必须干净"的习惯直接冲突，
   而"未提交的差异"正是最容易被忽略的反向验证污染源；
3. 与仓库既有的 `*.bat text eol=crlf`（第 77 条③）**同一思路**：
   **仓库保留对本地友好的形态，在 CI 侧把它归一。**

**具体改动（`.github/workflows/build-and-test.yml`）：**

- `frontend` job 的「安装端 A / 端 B 依赖」步骤加两个 env：

  ```yaml
  env:
    npm_config_registry: https://registry.npmjs.org/
    npm_config_replace_registry_host: always
  ```

- `android` job 在 `assembleDebug` **之前**加一步：「依赖源归一（Gradle 发行版：镜像 → 官方，
  仅 runner 内生效）」，跑一行 `sed` 改写 `distributionUrl`，**并把改写后的值 `grep` 进日志** ——
  否则第一次红的时候"到底用了哪个源"只能靠猜（第 24 条族：报错必须指向真因）。

  > Gradle wrapper **没有**官方的 env 覆盖钩子（不像 npm 有 `replace-registry-host`），
  > `distributionUrl` 只从这一份 `properties` 里读 ⇒ 只能显式改写 runner 上的这一份。

#### 8.30.5 新判据 `ci-dependency-source-normalized`

写在 `build-check.mjs` ①c（三端都会跑；它是**唯一**一处跳出 `skeleton/` 去读仓库根
`.github/workflows/` 的判据）。**两条互相独立的规则 + 一条覆盖面自证：**

| 子规则 | 触发条件 | 要求 |
|---|---|---|
| ① npm 源 | 锁文件里存在 host ≠ `registry.npmjs.org` | workflow 里必须有 `npm_config_replace_registry_host: always` **且** `npm_config_registry` 指向官方源 |
| ② gradle 源 | wrapper 的 `distributionUrl` host ≠ `services.gradle.org` | workflow 里必须出现一条 `distributionUrl=…services.gradle.org…` |
| ③ 覆盖面自证 | 锁文件 / `resolved` / wrapper / workflow **任一读不到** | **报红**并写明"判据覆盖面不足，**不得当作通过**"（第 53 条） |

🛑 **判形态不判词（第 52 条）**：本判据**不**禁止仓库里出现镜像 —— 它只要求
"**有镜像**"与"**CI 已归一**"这两件事**同时成立**。故未来若有人索性把提交的源改成官方源，
本判据会自动变成"无需归一"而**依然通过**（那时它打印的是"锁文件本就全官方 host ⇒ 无需归一"）。

#### 8.30.6 反向注入 **R27 / R28**（证明两条子规则都承重）

| 组 | 注入（改真实文件） | 期望 |
|---|---|---|
| **R27** | **只删一行**：`npm_config_replace_registry_host: always`（不动 `npm_config_registry`） | `ci-dependency-source-normalized` 报红 |
| **R28** | 把 gradle 归一"回退"成镜像（模拟复制粘贴旧行回来） | 同上 |

**为什么拆两组而不是合成一组**（第 53 条）：npm 侧与 gradle 侧是**互相独立的两条规则**，
合成一组会出现"只有一条真在承重、另一条是装饰"。两组各自只破坏一条 ⇒ 分别证明两条都承重。

**🛑 一处自曝（R28 第一版写错了锚点）**：第一版 `mutate` 用的是**普通单引号串**
`'…https\\://services.gradle.org…'` —— JS 把 `\\` 解析成**一个** `\`，
而工作流那一行的字面是**两个**（`https\\://`，因为 sed 的替换段里 `\\` 才会输出一个 `\`）
⇒ 搜索串对不上 ⇒ `mutate` 返回原文 ⇒ 用例**静默失效**，报的是「变异未生效（mutate 返回了原文）」。
🛑 **它的表现是"漏过"，读起来像"判据没牙齿"** —— 与 R12 注释里记的那次**同型**。
修法：改用 `String.raw`，并在锚点旁写清"转义层数要对齐"。
⇒ **教训：锚点必须逐字取自文件；转义层数本身也是锚点的一部分。**

#### 8.30.7 顺带修掉的一处诊断缺陷（同族，非独立编号）

四个反向验证脚本的**通过路径**在用例未指定 `expectGate` 时（那些"只要求必须变红、
不要求命中具体判据"的组）会打印 **`命中失败项「undefined」`**。
判据判定是对的，但**诊断信息里出现 `undefined` 就是 bug**（第 24 条族：报错不指向真因）。
已改为 `未指定具体判据（本组只要求"必须变红"）`，四处（`a` 1 / `x3` 1 / `android` 2）全改。
另：`build-reverse-check.mjs` 的"变异未生效"早退分支**没有把 `title` 带出来**，
于是打印成 `R28 undefined` —— 一条**说不出自己是哪条用例**的失败。已补 `title`。

#### 8.30.8 复验（逐字，一次干净条件下的完整重跑）

```
node ../tools/build-reverse-check.mjs    → 合计 28/28   EXIT=0
node ../tools/a-reverse-check.mjs        → 合计 21/21   EXIT=0
node ../tools/x3-reverse-check.mjs       → 合计 17/17   EXIT=0
node ../tools/android-reverse-check.mjs  → 合计 43/43   EXIT=0
```

- 四组合计 **109/109**（修复前 107：build 26 → **28**，新增 R27/R28；
  📌 **本条修复当时**的口径 —— 第 80 条为 build 组追加 R29 后**当时总口径 110/110**；
  第 83~85 条（a 组 +6 · build 组 +6）追加后**当前总口径 122/122**，见 §8.31.6）；
- 新判据首跑自证覆盖面：`2 份锁文件 · 304 条 resolved · host=registry.npmmirror.com；
  wrapper host=mirrors.cloud.tencent.com；锁文件含非官方 host ⇒ CI 已显式归一；
  wrapper 非官方 ⇒ CI 已显式改写为官方源`；
- 日志里 `undefined` 残留 **0**；
- 还原后 `git status` 只剩本轮**预期的 6 处**改动，无注入残留。

**🛑 如实登记的未验项**：**境外 runner 侧的行为本机无法验证**。
本轮做的是**主动消除这个变量**（让 CI 不再依赖那个镜像），而**不是**验证"那个镜像在境外可用/不可用"。
GitHub Actions 首跑（含本步是否生效）仍只能在 push 之后才见到。

#### 8.30.9 通用规则（可复用）

**构建链路里任何"指向第三方的地址"都是构建输入的一部分 —— 它必须和"运行它的机器"一起被审视。**
"这条命令在我机器上绿"与"这条命令在 CI 上绿"是两句不同的话；
而当差异只存在于**网络位置**时，本机**永远**看不到它 —— 这正是它值得被写成判据的原因。

### 8.31 跑 mvn 的 CI job 缺 Python 准备步（第 80 条 · 第二十五类，2026-10-08）

#### 8.31.1 缺口的形状

仓库有 **4 个 workflow**（`build-and-test` / `compliance-gate` / `crypto-adversarial-gate` /
`rls-isolation-gate`），**全部**在 push 时触发、全部从未真跑过一次（无远端）。
第 77 条只审了 `build-and-test.yml`；本轮为"进入商用开发"做**全 job 审计**时实测出：

| workflow | 跑 `mvn` | `setup-python` |
|---|---|---|
| `build-and-test.yml` | 2 处 | ✅ 3 处（第 77 条已修） |
| `compliance-gate.yml` | 0 处 | ✅ 1 处 |
| **`crypto-adversarial-gate.yml`** | **2 处** | **❌ 0 处** |
| **`rls-isolation-gate.yml`** | **3 处** | **❌ 0 处** |

第 77 条的根因分析**完全正确**（"后端 4 个 validate 门禁要求真 PyYAML"），
但结论只落在了它当场看到的那两个 job 上 —— **没有把推论应用到同样跑 mvn 的另外两个 workflow**。
这是"修一个实例、不修同族"的教科书形态，也是第 77 条同族的**第三处**
（第一处：backend/frontend 两 job；第二处：三端 `package.json` 裸调 python）。

#### 8.31.2 为什么它们是"干净 runner 上必红"（实测，不是推理）

两个前置事实，都实测：

1. **门禁只在聚合根跑一次**：根 `pom.xml` 的 4 个 exec 门禁带
   `<inherited>false</inherited>`。实测 `mvn -B -ntp -pl dy-common validate`
   → **0.344s BUILD SUCCESS、零门禁输出** —— 证明"门禁不会跟着子模块重复跑"。
2. **但 `-pl <模块> -am` 的 reactor 第 1 个就是聚合根**。本机 PATH 上的 `python`
   是 3.13.12、**无 PyYAML**（正是干净 runner 的等价物），实测
   `mvn -B -ntp -pl dy-crypto -am test` 逐字：

   ```
   [INFO] Building diaoyuanyun-skeleton 0.0.1-SNAPSHOT        [1/2]
   ADR-12 BUILD-TIME COMPLIANCE SCAN  --  PASS
   [S1-6-GATE] PASS ...
   [GATE-ERROR] PyYAML is not available (No module named 'yaml'). ...
   [S1-8-GATE] FAIL (misconfigured, no PASS printed)
   [S1-8 CONTRACT CONFORMANCE GATE]
   [ERROR] ... exec-maven-plugin:3.1.0:exec (s18-contract-conformance) ...
           Process exited with an error: 2 (Exit value: 2)
   [INFO] BUILD FAILURE
   ```

⇒ 一旦挂远端，`crypto-adversarial` 与 `rls-isolation` 两个 job 会**同时**
红在与加密 / 租户隔离毫无关系的地方 —— 而且红的措辞会把排查引向
"门禁配置坏了"这个错误方向（`FAIL (misconfigured)`）。

#### 8.31.3 修法

- **`crypto-adversarial-gate.yml` / `rls-isolation-gate.yml`**：各补
  `setup-python@v5` (3.11) + `pip install -r "${{ github.workspace }}/skeleton/requirements.txt"`，
  均排在 mvn 步骤**之前**。路径写显式绝对形式，**不让装依赖这种前置步骤依赖
  `defaults.working-directory` 的隐式行为**。
- **`compliance-gate.yml` 顺手收口**：原先内联写死 `"pyyaml>=6.0"`，与
  `skeleton/requirements.txt` 构成**同一依赖的两套说法** —— 而"多套说法"
  正是 77 / 79 / 80 三条能各自潜伏的土壤。统一改走 `requirements.txt`（内容等价）。

#### 8.31.4 新判据 `ci-mvn-jobs-have-python`（`build-check.mjs`）

与第 79 条的 `ci-dependency-source-normalized` 同处（CI 配置守卫小节）：

- **判形态不判词**：凡 job 里出现 `mvn` 命令，该 job 必须在 mvn 步骤**之前**
  有 `actions/setup-python` 与一次 `pip install`。**刻意不判**"装的是哪个包"
  （`requirements.txt` 与内联 `pyyaml` 都可），只判三件事：
  ① 有 setup-python；② 有 pip install；③ 两者都排在 mvn **之前**
  —— 顺序反了等于没装，而"顺序错了"正是重排 YAML 时最容易发生的事。
- **注释剥离**：本仓 workflow 注释极密（`mvn` / `pip install` 在注释里是常态），
  先剥 `#` 行再匹配 —— 不剥就会把"文档里的命令"当成"真执行的命令"（第 48 条族）。
- **零依赖行级切分**：不引入 js-yaml（本文件是零依赖 node 脚本）。按 2 空格键名
  切 job，`run:` 块用缩进回退终止（`env:` / `with:` 不会误吞）。
- **覆盖面自证（第 53 条）**：① 解析出的 job 数必须与 `runs-on:` 出现次数一致
  （不一致 ⇒ 切分口径坏了）；② 必须识别到 ≥1 个跑 mvn 的 job（识别不到 ⇒
  mvn 识别口径坏了）。当前实测：**4 workflow / 7 job（= 7 处 runs-on），
  其中 3 个跑 mvn，全部有 Python 准备**（`build-and-test#backend` ·
  `crypto-adversarial-gate#crypto-adversarial` · `rls-isolation-gate#rls-isolation`）。

#### 8.31.5 反向注入 R29

删掉 `crypto-adversarial-gate.yml` 的 setup-python 步（只删这一步、不动
pip install）⇒ 判据必须命中"没有 setup-python"分支。锚点用 `\r?\n` 兼容行尾，
锚点失配会表现为"变异未生效"而当场暴露（R28 的教训已内建）。

#### 8.31.6 复验（一次干净条件下的完整重跑）

- 修前：判据红，**逐字点名两个 job**（`crypto-adversarial` / `rls-isolation`），
  覆盖面自证通过（7 jobs = 7 runs-on）。
- 修后：三份 workflow YAML 经 PyYAML 逐份 `safe_load` 校验合法；判据绿
  （`扫了 4 个 workflow / 7 个 job（与 7 处 runs-on 一致）；其中 3 个 job 跑 mvn，
  全部在 mvn 之前有 setup-python + pip install`）；三端 `build-check` 逐端单独重跑
  **全部 `exit=0`**，判据行数各 **+1**（端 A 35→**36** · 端 B 34→**35** · 端 C 22→**23**）。
- 反向验证：**R29 注入 → 红 → 命中 `ci-mvn-jobs-have-python` → 还原 → 绿**
  （并入 build 29 组 ⇒ **本条修复当时**总口径 110/110；第 83~85 条再追加后**当前总口径 122/122**，见 §8.30.8 口径说明的更新）。

#### 8.31.7 通用规则（可复用）

**修"某一处缺 X"的缺陷时，必须当场把"X 的判据"一起立起来 —— 判据扫的是形态
（所有 job），不是实例（那两个 job）。** 第 77 条修复时若同步立了这条判据，
第 80 条的两个 workflow 会在**当轮**就被点名，而不是等下一次全 job 审计。
"我看到三处就修三处"永远追不上"判据替我看到所有处"。

### 8.32 契约**外**的出站面：当"补一个界面"同时撞上两堵墙（第 83 条 · 第二十六类，2026-10-09）

#### 8.32.1 现象：一个整类没有任何判据

商用开发第二批把 E1（跨店通兑结算落库与对账报表）与 E2（运维健康自检）做进了后端，
后端台账 `EndpointCoverageLedgerTest.INTERNAL_ENDPOINTS` 也如实登记了这 5 条自建能力面端点。
**但三端前端对它们的引用数是 0**：

```
$ grep -rn "settlement\|ops/health" frontends/*/src frontends/client-mp/miniprogram
（空）
```

⇒ **总部用不了对账报表。** 而这件事在**全部**门禁下都是静默的：

- `a-check` ⑩ `endpoint-reachability` 的计数等式是 `called.size === entries.length`，
  它只认**生成物里那 39 个契约端点**；
- `build-check` 的页面/结构/契约三项都只审"已写下的东西对不对"；
- 后端 1266 例全绿（后端本来就是对的）。

#### 8.32.2 为什么不能"顺手补一个页面"（两堵墙，都不是假设）

| 路 | 为什么走不通 |
|---|---|
| ① 把契约外端点**塞进生成物** | 生成物是契约的**机械转录**，手改一次即与契约脱钩；`gen-endpoints --check`、`a-check` ①b/⑩ 的计数对拍、后端覆盖台账**会同时失真** |
| ② 让页面**直接 `fetch`** | 绕过全部白名单 ⇒ "这个页面打了哪些契约外端点"在代码里**无处可查**（未登记的出站面）；而 `a-check` ⑩ 的 `pageDirectCall` 只认 `call(` 形态，**对 `fetch(` 视而不见** |

⇒ 结论：**要补的不只是两个页面，而是"契约外出站面"这一整层**（白名单 + 通道 + 判据）。

#### 8.32.3 设计：清册 + 第二通道 + ⑫ 判据（四子规则，每条都有独立的反向注入）

- **清册** `contract/internal-capabilities.ts`——6 条能力面，**真源是后端台账**
  （契约里根本没有这些端点），本文件是它的**镜像**，靠 ⑫ 逐条比对，不靠人肉同步。
- **第二通道** `api/internal.ts`——与 `api/client.ts` **并列**，各自守自己的白名单。
  复用 `PROTOCOL.*` / `newIdempotencyKey()` / `services/token-store`，
  错误对象形状与 `client.ts` **完全一致** ⇒ `services/errors.ts` 的 `describe()`
  对两条通道同构生效（否则契约外端点的错误会退化成"网络异常"一句废话）。
  另设 `downloadInternal`：CSV 是**非信封字节流**，不能走 `res.json()`；
  🛑 失败时后端**仍回信封 JSON** ⇒ 下载路径也必须按信封解析后再抛，
  否则 403 会表现成"下载成功但文件是空的"。
- **新判据** `a-check.mjs` ⑫ `internal-capability-registry` 的四条子规则：
  ① 清册 ⊆ 后端台账（**前端不得发明端点**）；② 无死声明 + **计数等式**；
  ③ 分层（`fetch(` 只在 `api/`；`callInternal(`/`downloadInternal(` 只在 `services/`）；
  ④ 协议常量（第 57 条对**第二条出站通道**同样成立）。
  **覆盖面自证**：拿不到后端台账 / 解析出的键 < 20 ⇒ **报红，不得当作通过**
  （否则"两边都是空集"会被读成"完美一致"）。
- **②③ 只扫【生产】文件**（`*.test.ts(x)` 除外的第二条纪律，见 §8.32.5 的 I27）。
  测试文件不随 `vite build` 发布、也不构成运行时调用链 ⇒ 不属于"生产出站路径"；
  而通道自身的单测（`api/internal.test.ts`）**必须**调用 `callInternal` 才能被测。
  🛑 判据首跑即**假红**（第 55 条）：③ 把 `api/internal.test.ts` 判成"绕过分层"。
  **修法不是改动代码去迁就判据**，而是把判据对齐到代码的真实形态 ——
  并把"排除了多少"**打印出来**（第 53 条：排除本身也必须是可见的，否则
  "判据悄悄少扫了一批文件"与"判据有牙"在输出上无法区分）。
- **⑨ `nav-requires` 扩展第三形态** `internalCapabilityId('<能力id>')`，并纳入**同一条计数等式**。
  核验强度与第一形态等价：函数必须由 `scope.ts` 导出，**实参 id 必须命中清册**。

#### 8.32.4 一个必须承认的连带设计约束

外壳的 `visibleNav` 原写 `endpointById(n.requires) !== null`。契约外能力 id 在生成物里查不到
⇒ **那两个导航项会被静默摘掉**：页面文件在、渲染分支在、调用链也在，**用户点不到**。
这正是第 64 条（"可到达性断在装载层"）的同型，只是换到了新通道上。
⇒ 过滤条件改为"**两类真源任一命中**"（`endpointById(...) !== null || isAllowedInternalCapability(...)`）。

#### 8.32.5 反向注入 I22~I27（六组，一组只触发一条子规则）

| 组 | 注入 | 期望 |
|---|---|---|
| I22 | 清册 path 漂移（`/ops/health` → `/ops/healthz`，后端台账里没有） | ⑫ 红（① 两侧一致） |
| I23 | 出站调用点的能力 id 改名（`getOpsHealth` → `getOpsHealthProbe`） | ⑫ 红（② 死声明） |
| **I27** | **生产调用消失（改成变量实参）+ 单测仍调同 id** | ⑫ 红（**②′ 只认生产文件**） |
| I24 | 页面里直接 `fetch('/api/v1/ops/health')` | ⑫ 红（③ 分层） |
| I25 | `headers[PROTOCOL.AUTH_HEADER]` → `headers['Authorization']` | ⑫ 红（④ 协议常量） |
| I26 | NAV 写 `internalCapabilityId('getOpsHealthProbe')` | ⑨ 红（第三形态的实参核验） |

🛑 **为什么是六组而不是一组**（第 53 条教训）：⑫ 有四条**互相独立**的子规则。
只注"清册与台账不一致"这一种，等于只证明了其中一条有牙齿 ——
而"未被证明有牙齿的那几条"与"没写"在效果上**无法区分**。

🛑 **I27 为什么必须【两个文件一起动】**（这条推理值得单独记）：它证的是
"② 只认**生产**文件的出站调用形态"。若只动生产（把调用式换成变量实参），
**新老两种口径都会抓住它** ⇒ 那只是 I23 已证过的形态，对本条新判别力**零信息量**。
只有 (a) 生产调用消失 **+ (b) 某个单测提供同 id 的调用形态**，计数等式才重新成立、
"死声明"才被掩盖 —— 那正是旧口径的**静默漏检点**（第 52 条家族：被非生产出现满足 ⇒ 假绿；
那种状态下该能力面对用户已彻底不可达，而门禁全绿）。
⇒ 为此给注入器加了可选的 `also`（附加注入，默认空 ⇒ 既有 26 组行为不变），
并对其逐项断言"锚点存在 **且** 内容确有差异"（与主注入同等的第 46 条约束）。

🛑 **一处判据外的工具缺陷，顺带登记**：`build-reverse-check.mjs` 与
`a-reverse-check.mjs` 的**抗中断能力不一致** —— 后者为 `SIGINT/SIGTERM/SIGHUP`
注册了"清理探针 + 还原注入"钩子，前者只注册了 `uncaughtException/unhandledRejection`。
本轮实测踩到：该脚本单轮约十几分钟（28 组 × 每组两次门禁，其中含真 `tsc`），
**被超时信号打断后进程仍在后台跑完剩余注入**，此时若另起一轮，
第二轮会在**别人的中间态**上做基线 ⇒ 报出"基线不是绿的"，
而指名的是**一个并不存在的契约不一致**。⇒ 属第 24 条同族（报告不指向真因）。
处置：把"**长时间门禁脚本必须串行、且必须等它真的结束**"写进本节；
工具侧的统一抗中断改造**如实登记为未做**（改动面覆盖 4 个脚本，不属本轮范围）。

#### 8.32.6 复验（一次干净条件下的完整重跑）

- `check:a`（`a-check.mjs`）：**17 条判据全绿**（判据名数 **16 → 17**），其中
  `nav-requires` 报告"字面量 5 条 + 无参推导 1 条 + 带参推导 2 条，计数等式成立"，
  `internal-capability-registry` 报告"契约外能力面 6 条：与后端 INTERNAL_ENDPOINTS 台账
  逐条一致（台账 32 键）· 全部被 services/ 层以调用形态触达 · fetch 仅出现在 api/ ·
  协议常量与令牌源均已收敛；②③ 只扫生产文件：services/ 6 个（另排除单测 2 个）·
  全仓生产源码 27 个（另排除单测 6 个 —— 不随 `vite build` 发布）"。
- `check:build`：`BUILD OK`（`structure` 清单 +5 文件 · `tsc --noEmit` · `vite build` 通过）。
- `npm test`：**46 例通过**（原 18 例 → +28：`internal.test.ts` 13 · `ops.test.ts` 9 ·
  `internal-capabilities.test.ts` 6）。🛑 其中 `ops.test.ts` 用"捕获真实请求体"的方式
  把 **preview=camelCase / commit=snake_case（其 `result` 内部又 camelCase）**
  钉成断言 —— 见 8.32.7。
- 反向验证：**I22~I27 六组全部抓住**，端 A 反向验证合计 **27/27**，还原后门禁 `exit=0`。
- 后端：**零改动**（本轮不动 Java），9 模块全量回归 **`BUILD SUCCESS` /
  `TOTAL 1266 failures=0 errors=0 skipped=0`**（逐模块 `10/46/48/65/37/37/29/994`）。

#### 8.32.7 顺带登记的一处既成 API 事实（**不代拍、不擅改后端**）

后端同族控制器的请求体命名口径**不一致**（实测）：

| 端点 | 请求体键名 | 依据 |
|---|---|---|
| `POST /settlement/preview` | **camelCase** | `SettlementController.PreviewRequest` 是裸 record，无 `@JsonProperty` |
| `POST /settlement/commit` | **snake_case 包装层** + `result` **内部 camelCase** | `CommitRequest` 逐字段 `@JsonProperty`；`result` 就是 preview 的返回类型 |
| `GET /settlement/statements[.csv]` | **snake_case** | 对账方按字段名机器解析（CSV 列序冻结） |

统一它属**后端一次 API 变更**（会改动既有绿测试 `TierAuthorizationE2ETest` 的请求体），
不属端 A 能单方面决定的事。故本轮**如实镜像 + 测试钉住 + 登记为待裁定项**：
`services/ops.ts` 逐端点按各自真实口径发送，`services/ops.test.ts` 断言两套键名集合
（含反向断言：`closing_store_id` **不得**出现在 preview 的请求体里）。
⇒ 它从此不是"某次调试才知道的坑"，而是一条**会红的断言**。

#### 8.32.8 通用规则（可复用）

**当"新增一端的出站需求"越过契约边界时，缺的从来不是那个页面，而是那一层白名单与守卫。**
判据的**受保护集**不能由生成器的认字能力决定（第 71 条）—— 契约外的东西必须有自己的真源
（这里是后端台账）与自己的核验动作，否则整类出站面会长期活在"没有任何东西在看"的静默区。
另一个可复用的判据：**每条子规则一条注入**。四子规则的判据只注一组，等于只证了四分之一。
🛑 第三条可复用规则（本轮补）：**判据为了对齐代码形态而"排除一类文件"时，排除必须被打印出来。**
否则"判据悄悄少扫了一批"与"判据有牙"在输出上**完全无法区分** —— 那是静默漏检，
比假红危险；而"为了让判据过而改代码"则是更坏的选项（那会把合法写法判成缺陷，
正是第 55 条）。正确顺序永远是：先问"这条判据的守护对象到底是什么"，
再把判据收紧到那个对象上，并让收紧本身可见。

---

### 8.33 「本端不适用」这个结论**从来没有被核验过** —— 目录改名即可让判据静默失效（第 84 条 · 第二十七类，2026-10-09）

#### 8.33.1 缺口：端 C 在令牌键名判据下被判"不适用"，而端 C 恰好是唯一有缺陷的那一端

`tools/build-check.mjs` ④b `token-single-source` 的扫描根**写死成 `src/`**：

```js
const srcDir = join(END_ROOT, 'src');
const files = walkTs(srcDir);
if (files.length === 0) {
  notes.push('  – token-single-source: 本端无 src/（不适用）');   // ← 端 C 走到这里就出去了
}
```

而**端 C 的源码在 `miniprogram/`**（小程序工程结构，没有 `src/`）⇒ 端 C 每一轮
都打印一行 `– 本端无 src/（不适用）` 然后**跳过**。于是四端的实际守护面是：

| 端 | 判据 | 结果 |
|---|---|---|
| 端 A（`src/`） | `a-check` ⑧ + `build-check` ④b | ✅ 有守护 |
| 端 B（`src/`） | `build-check` ④b | ✅ 有守护 |
| 端 D（Kotlin） | `android-check` `session-key-single-source` | ✅ 有守护 |
| **端 C（`miniprogram/`）** | —— | 🛑 **无守护** |

⇒ **判据只保护了四端里的三端，而输出上看不出任何异常**（"不适用"是一行正常笔记，不是警告）。

#### 8.33.2 端 C 的缺陷本体：4 处权威点之外的裸键名，其中 1 处在**出站层**

端 C 的 `services/session.js` 持有 `TOKEN_KEY = 'token'`（权威点），但另有四处直接写裸字面量：

| 位置 | 形态 | 为什么危险 |
|---|---|---|
| `miniprogram/app.js:59` | `wx.getStorageSync('token')` | `resolveCustomerId` 读凭证解 JWT 载荷 |
| `miniprogram/app.js:108` | `wx.getStorageSync('token')` | `onLaunch` 读登录态 |
| `miniprogram/app.js:115` | `wx.getStorageSync('token')` | `onShow` 读登录态 |
| **`miniprogram/services/request.js:97`** | `wx.getStorageSync('token')` | 🛑 **出站层自己读键名** —— 给请求装 `Authorization` 头的那一步绕开了会话层 |

四处与 `TOKEN_KEY` **恰好同值**，所以今天没有暴露。但它是本仓第 50 条那个
真实缺陷（"写 `'dy.token'` / 读 `'token'` ⇒ `Authorization` 头永远为空 ⇒ 全量 401"）
的**同一形态**：改一处即静默分叉，而 `tsc` / 构建 / 全部既存判据**一律绿**。

#### 8.33.3 修法（判据两层 —— 缺任何一层都不成立）

**① 扫描根按端形态补齐**：`src/` 与 `miniprogram/` 各自存在即纳入；
访问形态也补齐端 C 的 `wx.get/set/removeStorageSync`。

**② 找不到权威点 ⇒ 报红，不得当作"不适用"**（这是本条的核心）：

```js
if (AUTH_FILES.size === 0) {
  fail('token-single-source',
    `扫描到 ${files.length} 个源文件（根：${rootRel}），但**找不到令牌权威点**…\n`
    + '      ⇒「写进去的键」与「读出来的键」是不是同一个，本判据**无法判定** ——\n'
    + '        【不得】当作通过，也不得当作"本端不适用"。');
}
```

并加**覆盖面自证**：扫描面若只剩权威点自己（`scanned.length === 0`）也报红 ——
否则"判据实际上没在查任何东西"会伪装成 ✓。

🛑 **为什么第 ② 层才是本体**：第 84 条的缺陷不是"端 C 有裸键名"，
而是**"判据不认识这个目录形态时，输出的是『不适用』而不是『我看不懂』"**。
"不适用"与"没写"在输出上无法区分 —— 这正是第 45 条（白名单漏项）与
第 71 条（受保护集由生成器的认字能力决定）的同族，只是这次把"认字能力"
换成了"**认目录名**"。所以只补扫描根（第 ① 层）是不够的：
下次再换一个目录名，判据会再次安静地失效。

#### 8.33.4 端 C 代码收敛（新建唯一权威点，对齐端 A）

新建 `client-mp/miniprogram/services/token-store.js`（与端 A 的 `services/token-store.ts`
**同一职责**）：`TOKEN_KEY` / `PROFILE_KEY` 只在此处定义，导出
`readToken` / `writeToken` / `clearToken` / `readProfile` / `writeProfile`
以及供门禁自证的 `TOKEN_STORAGE_KEY` / `PROFILE_STORAGE_KEY`。

三个消费方改为经它读写：`app.js`（3 处）、`services/request.js`（1 处）、
`services/session.js`（键名与读写整体下沉，对外 API 不变 ⇒ 9 个页面的调用点零改动）。

🛑 依赖图无环：`token-store.js` 是叶子（不 require 任何项目内模块）；
`request.js → token-store.js`；`session.js → request.js + token-store.js`。

#### 8.33.5 同批新增 `page-reachability`（④j）—— 装载层的**另一个方向**

④h `page-registry` 判"磁盘上有的页面有没有被**声明**"；本条判反方向：
"**声明了**的页面有没有**外部入口**"。

🛑 **首版判据过宽，被注入当场证伪**：第一版把"含导航调用的文件里出现的
`/pages/...` 字面量"都算入口 —— 而 `pages/visits/visits.js` 里有一行
`session.requireLogin('/pages/visits/visits')`，该页**自己给自己当了入口**；
删掉 `profile.js` 的跳转后判据**仍然全绿**（= 没牙齿）。
修法：**排除本页自身的文件**（`owners` 记下引用它的文件集合，若全是自己 ⇒ 算孤儿）。
⇒ 本仓纪律"每个判据都要被反向注入证伪一次"的又一次兑现：
**先写判据、立刻注入、被证伪、再收紧** —— 而不是先写文档说它有牙齿。

#### 8.33.6 反向注入（R30 / R31 / R35，各只触发一条子规则）

| 组 | 注入 | 期望 |
|---|---|---|
| R30 | 端 C 出站层回退成 `wx.getStorageSync('token')`（原缺陷形态复活） | `token-single-source` 红（第 ① 层） |
| R31 | 端 C 删掉 `profile.js` 里指向 `pages/visits/visits` 的 `wx.navigateTo` | `page-reachability` 红 |
| R35 | **判据自身**的权威点候选清单被改坏（`AUTH_NAMES` → 不存在路径） | `token-single-source` 红（第 ② 层 · 覆盖面自证） |

🛑 **R35 是必需的、且不能与 R30 合并**（第 53 条）：R30 只证了"现在能扫到端 C"，
**完全没有**证明第 ② 层 —— 而第 ② 层才是本条的本体。注入目标是**判据自身**，
与 `a-reverse-check.mjs` 注入 `gen-endpoints.py` 是同一种做法：
**判据的"认识面"也必须有反向验证**，否则"判据忽然什么都不认识了"与"代码是对的"
在输出上完全一样。

#### 8.33.7 复发登记：第 76 条① 的第二次发作 —— 新写的测试打破了 `tsc --noEmit`，而 `vitest` 全绿

为第 85 条补行为断言时，那一行写成了：

```ts
const contractCodes = Object.values(ERROR_CODE);                        // 类型是【字面量联合数组】
...
Object.keys(COPY).map(Number).filter((c) => contractCodes.includes(c)); // c: number ⇒ TS2345
```

`Object.values(ERROR_CODE)` 的推断类型是 `(2002 | 2001 | … | 9001)[]`，而
`Object.keys(COPY).map(Number)` 是 `number[]` ⇒ `includes(c: number)` 报：

```
src/services/errors.test.ts(52,87): error TS2345: Argument of type 'number' is not
assignable to parameter of type '2002 | 2001 | 1001 | 1002 | 2003 | 2004 | 3001 | 4001 | 4002 | 5001 | 6001 | 9001'.
```

🛑 **而 `vitest` 是 49 例全绿**（esbuild 逐文件剥类型、不做类型检查）⇒
这条缺陷**在行为测试层面完全不可见**。端 A / 端 B 的 `real-build` 因此变红。
**而抓住它的不是任何一条"判据"，是反向验证的收尾断言「还原后必须回绿」**：

```
  ✗ 漏过  R1 端 A：出站回退成裸 baseUrl 拼 path（丢契约 Base Path）
           注入后 exit=1，命中「base-path-wiring」=true；还原后 exit=1
  ✗ 漏过  R3 端 B：出站前缀改成手写字面量（不再取自生成物）
           注入后 exit=1，命中「base-path-wiring」=true；还原后 exit=1
  …
还原后三端构建自检：client-mp=0 · therapist-app=1 · admin-web=1（异常 ✗）
```

🛑 **这正是第 76 条① 的原话"写测试同样要过类型门禁"的第二次发作**，
也再次说明"全部测试通过"与"能抓住问题"是两件事。修法 = **显式标注**而不是强转：

```ts
const contractCodes: number[] = Object.values(ERROR_CODE);   // 表达"这是一列数字 id"
```

（写 `as number[]` 也能过编译，但那是"叫编译器闭嘴"；**标注**表达的是意图。）

📌 **一处值得记下的现象**：基线与收尾断言跑的是**同一条命令、同一个 cwd**，
却在两次运行里给出不同结果（基线那一刻该测试文件还没落盘，收尾时已落盘）。
⇒ "**基线绿、逐例红**"这种看似矛盾的输出在这个脚本里是**可能**的；
遇到它不要先怀疑脚本，先看 `git status` 与相关文件的 mtime。

---

### 8.34 契约的**错误码枚举**与各端文案映射之间没有任何判据（第 85 条 · 第二十八类）

#### 8.34.1 缺口：契约 12 个 code，三端各有一张映射表，没人核对它们是否对得上

契约根级 `x-error-codes` 给出 **12 个 code**（1001/1002/2001/2002/2003/2004/
3001/4001/4002/5001/6001/9001），并且 `ResultEnvelope.message` 的说明逐字写着
「面向开发者，**不得直接渲染给客户**（客户端有自己的 code → copy 映射）」。

⇒ 三端各自维护一张 **code → 用户可见文案** 的映射表：

| 端 | 文件 | 表名 |
|---|---|---|
| 端 A | `admin-web/src/services/errors.ts` | `COPY` |
| 端 B | `therapist-app/src/services/errors.ts` | `COPY` |
| 端 C | `client-mp/miniprogram/services/codes.js` | `CODE_COPY` |

实测（2026-10-09）：三张表**目前都是完整的 12 条**。但**没有任何判据在看这件事** ——
契约新增一个 code（比如再多一个 403 子形态）时，三端会**静默**落到兜底文案
（端 C 是"操作未完成，请稍后再试"、端 A 是"未知错误码…"）：
用户看到一句无信息量的提示，服务端明明给了确定的拒绝原因，
而 `tsc` / `vite build` / **全部既有判据一律绿**。

🛑 **它与第 57/58/61 条是同一个族，这是该族的第四个成员**：

| 条 | 被"写在契约里、实现里丢了"的东西 |
|---|---|
| 第 57 条 | 头名 / 令牌前缀 / 幂等头名 / 追踪头名 / 信封成功码 |
| 第 58 条 | 分页参数名与越界处置 |
| 第 61 条 | 「不得模糊报错」的机器可读那一半（`missing_items` / `denied_fields`） |
| **第 85 条** | **错误码枚举本身** |

#### 8.34.2 修法：三源交叉 + 双向等式（照 `a-check` ⑫ 与 A-4 的成例）

新判据 `error-code-coverage`（`build-check.mjs` ④k）：

1. **冻结契约 ↔ 裁剪契约**：生成器的输入（`contract/sdk-generator/_cut/<端>.openapi.yaml`）
   必须是冻结契约的**无损**裁剪 —— 逐条 `code:name` 相等；
2. **契约 ↔ 本端映射**：`契约 codes ⊆ 本端映射`（无缺项）**且** `本端映射 ⊆ 契约 codes`
   （无表外码 —— 表外码意味着映射表已腐烂）；
3. **覆盖面自证**：契约侧解析出的 code 数 < 10 ⇒ **报红**，不得当作通过
   （否则"两边都是空集"会被读成"完美一致" —— 第 53/55 条）。

🛑 判据形态：**不引入 YAML 依赖**，用带边界的两段正则抽 `x-error-codes` 块
（冻结与裁剪两种缩进形态都吃：`  - http:` / `- http:`），并**断言块内 code 数 ≥ 10**
把"解析失败"与"契约真空了"区分开。

#### 8.34.3 反向注入（R32 / R33 / R34，各只触发一条子规则）

| 组 | 注入 | 期望 |
|---|---|---|
| R32 | 端 B `COPY` 里删掉 `2004`（本端无文案） | `error-code-coverage` 红（② 缺项方向） |
| R33 | 端 C `CODE_COPY` 凭空加 `7777`（契约外 code） | `error-code-coverage` 红（② 表外码方向） |
| R34 | **裁剪契约**被删掉一个 code（生成器输入被改动） | `error-code-coverage` 红（① 冻结↔裁剪） |

🛑 三组缺一不可：①②是**两个方向**的等式，③是**另一条**子规则。
只注一组等于只证了三分之一（第 53 条）。

#### 8.34.4 第二把锁：行为层测试（三端都有，但端 C 的形态**刻意不同**）

判据是构建期的；三端各补行为层断言：**端 A 46 → 49 例** · **端 B 18 → 21 例** ·
**端 C 12 → 23 例**（`node --test`）。

端 A / 端 B 的 3 例直接复述判据的等式，但遍历的是**本端自己的两表**（不手抄契约）：

```ts
const contractCodes: number[] = Object.values(ERROR_CODE);   // 🛑 必须显式标注，见 8.33.7
it('COPY 覆盖 ERROR_CODE 的每一个 code（无缺项）', () => {
  expect(contractCodes.filter((c) => COPY[c] === undefined)).toEqual([]);
});
it('COPY 里没有 ERROR_CODE 之外的 code（无表外码）', () => {
  expect(Object.keys(COPY).map(Number).filter((c) => !contractCodes.includes(c))).toEqual([]);
});
it('两表条目数一致（计数等式 —— 证明判据认识的东西覆盖了全部）', () => {
  expect(Object.keys(COPY).length).toBe(contractCodes.length);
});
```

🛑 **端 C 不做同一件事 —— 这不是偷懒，是刻意的**：
端 C 运行时**拿不到契约的 12 个码清单**。生成物只暴露
`PROTOCOL.ERROR_DATA_FIELDS` 的 **2** 个 forbidden-403 码（`2001` / `2002`），
实测 `node -e "...Object.keys(c.PROTOCOL.ERROR_DATA_FIELDS)"` ⇒
`ERROR_DATA_FIELDS keys = 2 2001,2002`。要在这里重做那条等式，测试就必须
**手抄一份 12 个码的清单** —— 那是**第三份契约副本**：它只会在"有人忘了同步"时
与真源不一致，是**看着更严、实则新增一个腐烂点**的**假第二把锁**
（本仓对"手抄清单"有实锤：`clientPaths.js` 手写白名单漏 B5 ⇒ 第 45 条）。

⇒ 端 C 改守**映射表自己说不出来的那部分**（新增 `test/codes.test.js` 4 例）：

| 断言 | 抓的是什么 |
|---|---|
| 除 `4002` 外，每个码经 `describe()` 都给出**非兜底**文案 | `text: text \|\| UNKNOWN_COPY` 的**静默降级** —— 表里"有这一条"，客户看到的却是"操作未完成，请稍后再试" |
| 表外码（`7777`）走兜底文案 | 映射不是"照单全收" |
| `4002` ⇒ `kind='replay'` 且 `text=''` | 幂等命中是**成功路径**，不是错误（页面对它当成功继续） |
| 网络层失败（无 code）与"服务端明确拒绝"文案分开 | 两件事不能共用一句文案 |

🛑 **两者的判别力差别**：端 A / 端 B 的锁判的是**表**（静态，等于把判据搬进测试）；
端 C 的锁判的是**函数**（`describe()` 真跑一次）。"表里有这个键"与
"这个键真的走到了自己的文案"**是两件事** —— 后者只有真跑一次才看得见，
而它正是本条的缺口所在。（端 C 另有 `test/token-store.test.js` 7 例属第 84 条，见 8.33。）

#### 8.34.5 R34 首版是"用例自身失效"，运行器自己抓住了它 —— 并顺带两个真发现

**R34 首跑逐字**：

```
  ✗ 漏过  R34 裁剪契约被删掉一个 code ⇒ 判据必须发现「生成器的输入与冻结契约不一致」
           undefined｜变异未生效（mutate 返回了原文）—— 用例本身失效，须修正
```

🛑 **发现 ①：该裁剪契约是全部注入目标里唯一一个 CRLF 文件。**

| 文件（注入目标） | 行尾 |
|---|---|
| `admin-web/src/api/client.ts` · `client-mp/.../request.js` | LF |
| `contract/openapi-v1.0.0.yaml`（冻结契约） | LF |
| `.github/workflows/*.yml`（R27 / R29 的目标） | LF |
| `admin-web/package-lock.json`（R27 的目标） | LF |
| **`contract/sdk-generator/_cut/*.openapi.yaml`** | 🛑 **CRLF** |

⇒ 注入正则按 `\n` 写就**一个字都匹配不到**。成因：`_cut/*.yaml` 由 `sdk-generator`
**在 Windows 上以文本模式写出**（而 `gen-endpoints.py` 的 `write_text()` 是显式
`newline="\n"`，与第 40 条同源）⇒ **同一份生成物的行尾依赖生成平台**。
凡对该文件做行级 / 字节级判定都必须容忍 CRLF，否则会得到"在 Linux 判绿、
在 Windows 判红"（或反向）的**地域性结论**。修法 = 正则改 `\r?\n`。

📌 **顺带实证（值得记）**：`restore()` 用
`writeFileSync(abs, src, { encoding: 'utf8', newline: '\n' })` 还原，而实测
**它不改写已有 CRLF** ——

```
before hasCRLF= true   after hasCRLF= true        (node v22.22.2)
```

⇒ 内存备份还原是**字节保真**的，CRLF 文件不会被反向验证污染。这条很重要：
若还原会把 CRLF "顺手"转成 LF，那么每跑一次反向验证都会改写那 3 份契约，
而这种改动因 `.gitattributes` 的 `eol=lf` 归一**在 `git status` 里看不见**
（第 24 条族："检验工具把自己的残骸当成了被检对象"）。

🛑 **发现 ②：诊断行的第二个 `undefined` 出口。**
失败行打成了 `undefined｜变异未生效（…）` —— `detail` 只在成功路径与主路径赋值，
**两条早退分支（文件不存在 / 变异未生效）都没有它**。而"诊断信息里的 `undefined`
一律视为 bug"是第 24 条族早已立下的规矩（`R28 undefined` 那次修的是 `title`）——
**这是同一个坑的第二个出口**。修法 = **两道防线**：① 两条早退分支补 `detail`
（固定形状：即使"没跑到注入"，诊断行也要能自解释）；② 打印处兜底
`r.detail ?? '（诊断信息缺失 —— 这是本脚本的 bug，不是用例结果）'`，
让"少写一个字段"**变不成乱码**。

🛑 **这一节的元层意义**：R34 本身是一条**注入用例**，而它首跑就失效了。
如果运行器把"变异未生效"当成通过（或沉默略过），那么子规则 ①（冻结↔裁剪）
就**永远没有反向验证**，却看起来"有 R34 守着"。⇒ **"用例存在"不等于"用例有效"** ——
这与第 76 条那句"跑了脚本 ≠ 跑了该跑的全部脚本"是同构的。

#### 8.34.6 通用规则（可复用）

**凡"契约里有一份枚举，各端各有一份它的映射"，就必须有一条判据做双向等式。**
单向的"覆盖"只防缺项、防不住表外码（映射表腐烂）；而"计数等式"只防数量漂移、
防不住**换了一个 code**。三者都要有，才叫"对得上"。
**但第二把锁（行为层）不该是判据的复印** —— 若某端拿不到真源，宁可换一个
"真跑一次函数"的角度，也不要在测试里手抄一份契约（那是把腐烂点从表挪到测试）。

---

> 🛑 **本文件 §8 的条数已由「二十五条」增至「三十三条」**（新增 §8.27 三端测试套件收口 =
> **第 76 条**、§8.28 CI/Python 工具链**五处**缺口 = **第 77 条**、
> §8.29 角色展示名跨端收敛 = **第 78 条**、
> §8.30 构建依赖源被烤进版本库 = **第 79 条**、
> §8.31 跑 mvn 的 CI job 缺 Python 准备步 = **第 80 条**、
> §8.32 契约**外**的出站面（清册 + 第二通道 + ⑫ 判据）= **第 83 条**、
> §8.33「本端不适用」从未被核验 ⇒ 目录改名即可让判据静默失效 = **第 84 条**（附 `page-reachability`）、
> §8.34 契约错误码枚举与各端映射表之间无判据 = **第 85 条**），标题行已同步订正为「三十三条」。
>
> 🛑 **顺带补账**：第 76 条此前**只出现在提交信息里**（`c138639`），从未登记进骨架 README §5.1
> 的缺陷表 —— 本轮补上 `| 76 |` 行，CI/工具链那条顺延为 **第 77 条**。
> ⇒ 教训：**提交信息里引用的编号也是"活断言"**，没人守它就会与登记册脱节（第 60 条同族）。
>
> 🛑 **另一处补账（2026-10-08 同日）**：`2454 → 1227` 的订正与"反向验证 104/107"的口径说明
> 一度**只写在未提交的工作副本里** ⇒ 谁克隆下来读到的仍是错的 `2454`。
> 这正是第 79 条同族：**「修改存在」不等于「修改可用」**。现已一并入库。
>
> ---
>
> ## 📌 本轮（第 84 / 85 条）收口后的**当前**验收口径（2026-10-09，实测）
>
> ⚠️ 下面这些数字是**本轮实测值**，用于替换上文各节里"当时"的口径。
> 上文凡写"当时/本条修复当时"的地方**刻意保留原值**（它们是那条缺陷的历史证据），
> 但**读当前状态请以本块为准**。
>
> | 指标 | 当前值 |
> |---|---|
> | 反向验证总口径 | **122/122**（build **35** · a **27** · x3 **17** · android **43**） |
> | `build-check` 判据输出行数（含 `structure` / `real-build` 子行） | 端 A **44**（=`structure` 29 + `real-build` 2 + 其它 13）· 端 B **38**（23 + 2 + 13）· 端 C **29**（9 + 0 + 20） |
> | 行为测试 | 端 A **49 例**（6 文件）· 端 B **21 例**（3 文件）· 端 C **23 例**（`node --test`，3 文件） |
> | 后端 9 模块全量回归 | `BUILD SUCCESS` · 逐模块 `Tests run` 合计 **1266** / failures 0 / errors 0 / skipped 0（本轮**后端零改动**，与文档锚点一致） |
> | 契约生成物一致性 | 三端 `gen-endpoints.mjs --check` 全 **exit=0** |
> | 端 D | `android-check` `ANDROID OK` / `exit=0`（**25 条判据**） |
>
> 🛑 **一处必须如实说明的口径关系**：上文 §8.31.6 记的"端 A 35→36 / 端 B 34→35 /
> 端 C 22→23"是**第 80 条那次变更的增量记录**，此后**第 83 条**又往端 A 的 `required`
> 结构清单里加了 5 个文件（清册 / 第二通道 / 域服务 / 两个页面）⇒ 那个基数已经漂移，
> **不要拿 36/35/23 当当前值**。这也是"数字锚点必须有出处"的又一例：
> 凡是**会随其它变更漂移**的计数，要么每次一并更新，要么明确写成"当时值"。

