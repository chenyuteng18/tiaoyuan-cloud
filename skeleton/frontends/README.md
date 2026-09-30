# 三端前端工程骨架（契约驱动）

> 对应 `缺口修复总规划-2026-09-27.md` §三 **G-B**（标注「✅ 可以 / 可立即动工」）。
> 本轮落地的是 **B-1**（工程骨架 · 构建可跑 · 目录约定 · 环境配置分离）
> 与契约驱动端点层；**B-2/B-3/B-4** 见文末「阶段顺序」。

## 1. 三端定义（逐条转录自 `contract/sdk-generator/generator-matrix.yaml`）

| 端 | 目录 | 形态 | 契约角色 | 可用 operation | 生成物 |
|---|---|---|---|---|---|
| 端 A 管理后台 | `admin-web/` | Vite + React + TS | `admin` | **39**（34 paths） | `src/contract/endpoints.ts` |
| 端 B 门店 / 经络师 | `therapist-app/` | Vite + React + TS | `therapist`, `meridian` | **29**（26 paths） | `src/contract/endpoints.ts` |
| 端 C 客户端 | `client-mp/` | 微信小程序（原生） | `client` | **15**（14 paths） | `miniprogram/contract/endpoints.js` |

**角色准入的判定口径**：一个 operation 属于某端，当且仅当它的 `x-callable-roles`
与该端的 token-roles **有交集**。token-roles 取自 `generator-matrix.yaml`
（`client-mp: [client]` · `therapist-app: [therapist, meridian]` · `admin-web: [admin]`）。

## 2. 契约驱动：端点层是【生成】的，不是手抄的

```
contract/openapi-v1.0.0.yaml
  └─(sdk-generator/_sdk_pipeline.py 按 matrix 裁剪)→ contract/sdk-generator/_cut/<end>.openapi.yaml
        └─(frontends/tools/gen-endpoints.py 按 token-roles 过滤)→ 各端 contract/endpoints.{js,ts}
```

```bash
python frontends/tools/gen-endpoints.py           # 写出三端端点层
python frontends/tools/gen-endpoints.py --check   # 只校验产物与契约一致（不写）
```

**为什么必须生成**：本仓已有一次同型失败，记在 `client-package/api/clientPaths.js`
的文件头（并酿成 S1-8 FACE 1 的立项动机）——手写的"客户端可达路径清单"会腐烂，
原 8 条里只有 2 条与冻结契约相符。腐烂有两种形态：**错名**（把不允许的写成允许）
和**漏项**（允许的没列上）。漏项尤其隐蔽：白名单是运行时强制的，漏一条 = 该
功能对客户直接不可用，而当时的门禁只校验"引注为真"、不校验"允许的都列上了"，
于是**长期不红**。

生成器因此自带 `--check`：契约一改而产物未重跑，检查即红。

## 3. B-1 验收：三端构建

| 端 | 命令 | 说明 |
|---|---|---|
| client-mp | `npm run build` → `node ../tools/build-check.mjs --end=client-mp` | 零第三方依赖 |
| therapist-app | `npm run build`（`tsc --noEmit && vite build`）· `npm run check:build` · `npm run check:x3` · `npm run check:x3-reverse` | 需 `npm install`；X-3 两组门禁 |
| admin-web | `npm run build`（`tsc --noEmit && vite build`）· `npm run check:build` · `npm run check:a` · `npm run check:a-reverse` | 需 `npm install` |
| 三端通用 | `npm run check:build-reverse` → `node ../tools/build-reverse-check.mjs` | `base-path-wiring`（6 组 R1–R6）+ `cross-end-protocol`（4 组 R7–R10）+ `pagination-protocol`（3 组 R11–R13）+ **`error-data-fields`（3 组 R14–R16）** 反向验证，**常驻**；且能抓住「判据被删」|

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

## 8. 🛑 十六条写作/路径/判据/管道纪律（由本仓第 50、51、52、53、54、55、56、57、58、59、60、61、62、63、64 条系统性缺陷逼出，勿回退）

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

