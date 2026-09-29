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
| therapist-app | `npm run build`（`tsc --noEmit && vite build`）· `npm run check:build` | 需 `npm install` |
| admin-web | `npm run build`（`tsc --noEmit && vite build`）· `npm run check:build` | 需 `npm install` |

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

## 8. 🛑 两条写作/路径纪律（由本仓第 50、51 条系统性缺陷逼出，勿回退）

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
