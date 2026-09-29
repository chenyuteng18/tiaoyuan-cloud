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

## 8. 🛑 五条写作/路径/判据/管道纪律（由本仓第 50、51、52、53、54 条系统性缺陷逼出，勿回退）

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

**回归用例**：反向验证 `a-reverse-check.mjs` **10 组**受控注入
（含 I1/I2/I3 删元信息、I4 硬编码清单、I5 摘导入、I6/I7 手写子档位、
I8/I9 伪造端点/行、I10 未完结端点当既定事实用），
断言每组必红、逐字节还原后必绿 ⇒ **10/10 PASS / 还原后 `exit=0`**。

🛑 **通用规则**：
**凡是"契约写了而前端拿不到"的约束，失效应默认假定为静默**，
只能由"把契约里出现的键枚举一遍"这类**覆盖面型判据**抓住；
且生成管道里**每类被声明过的元信息都必须有一个可机械验证的落点**。
