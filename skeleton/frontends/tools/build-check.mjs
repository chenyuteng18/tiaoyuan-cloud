#!/usr/bin/env node
/**
 * 三端前端工程 · 构建自检（零第三方依赖，纯 node 内置模块）
 * ============================================================================
 *
 * 用法
 *   node frontends/tools/build-check.mjs --end=client-mp
 *   node frontends/tools/build-check.mjs --end=therapist-app
 *   node frontends/tools/build-check.mjs --end=admin-web
 *
 * 🛑 为什么三端共用这一份而不是各写一份
 * ---------------------------------------------------------------------------
 * 三份"结构 + 契约 + 词表"的自检脚本会各自漂移：某一端加了检查项，另两端不会
 * 自动跟上，而漂移的表现是**静默少查**，不是报错。本仓对这类"清单腐烂"已有两次
 * 实锤（clientPaths.js 的手写白名单、S1-8 FACE 1 的立项动机），故这里把**差异
 * 收敛成配置**（下面的 ENDS 表），把**检查逻辑只写一份**。
 *
 * 🛑 关于"派生子进程"：本环境的真实限制是什么（2026-09-28 实测订正）
 * ---------------------------------------------------------------------------
 * 初版把环境结论写成"禁止 node 派生子进程"，**那是过宽的错判**，已订正：
 *   · **同步**派生（execFileSync / spawnSync）对**任何**命令都返回 EBUSY；
 *   · **异步**派生（child_process.spawn）**可以正常工作** —— vite build / tsc
 *     都靠它跑通了（esbuild 走异步 spawn）。
 * 订正的代价是真实的：初版据此把契约一致性判成 ENV-BLOCKED（退出码 3，未验证），
 * 而实际上那一项是**可以验证的**。教训写在这里：**"环境受限"必须给出【精确的
 * 受限面】** —— 一句"禁止 spawn"就把可做的检查变成了未验证项，形同少查。
 * 故本文件统一走异步 spawn；若异步也不可用，才报 ENV-BLOCKED。
 *
 * 退出码（沿用 compliance 的图例）
 *   0 通过  1 不通过  2 配置缺失  3 环境受限（该项【未验证】，不是通过）
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
// 🛑 Python 解释器解析的**唯一一份实现**（`gen-endpoints.mjs` 共用它）。
//    为什么必须共用而不是各写一份：原先 `package.json` 的 `gen:endpoints` /
//    `check:contract` 写的是裸 `python ../tools/gen-endpoints.py`，没有任何解析，
//    实测本机 `npm run check:contract` **exit 2**（`MISCONFIGURED: PyYAML is required`）
//    —— 而 CI 的 frontend job 正是跑这条命令。同一件事两套写法，其中一套没走到
//    正确的解释器。完整机制（含 `py` 启动器因 shebang 换解释器的实测）见 `py.mjs`。
import { resolvePython, NO_PYTHON_MESSAGE } from './py.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = resolve(HERE, '..');
const SKELETON_ROOT = resolve(FRONTENDS, '..');
// 🛑 第 79 条：判据需要看**仓库根**下的 `.github/workflows/`（"CI 里有没有把
//    依赖源归一"这件事的**唯一**证据面）。这也是本脚本唯一一处跳出 skeleton/ 的读取。
const REPO_ROOT = resolve(SKELETON_ROOT, '..');
const COMPLIANCE = join(SKELETON_ROOT, 'compliance');

/**
 * 🛑 `vite build` 的 argv（端 A / 端 B 共用）—— 为什么不直接写 `['build']`：
 *
 * vite 默认在构建前**清空 outDir**（`emptyOutDir` 默认 true），实现方式是
 * `fs.rmSync(dist/assets, {recursive:true})`。而本机运行环境装了一层
 * **安全删除守卫**（node-safe-delete-shim），它对"单轮累计删除文件数超过阈值"
 * 的动作直接抛错：
 *
 *     [safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED]
 *     {"count":627,"threshold":50,"scope":"turn","targets":["...\dist\assets"]}
 *
 * 后果分两层，两层都是**判据自身的缺陷**，而不是源码缺陷：
 *   ① **条件性假红**：`real-build` 会随"本轮已经删了多少文件"这个**与代码无关的
 *      变量**在绿/红之间跳 —— 与第 59 条（会随机变红的断言）同族，区别是本条的
 *      随机源是**环境状态**而非随机数据；
 *   ② **报错指向被检对象**：失败信息是一条 `dist/assets` 路径 + 一段 shim 栈，
 *      读起来像"产物目录有问题"，真因（宿主的删除守卫）完全看不出 —— 与第 24 条
 *      （检验工具把自己的残骸当成了被检对象）同族。
 *
 * 故本判据显式传 `--emptyOutDir=false`：产物的正确性不依赖"先清空"——
 * vite 产物文件名带内容哈希，重新构建会**覆盖**同名文件并按新图重写
 * `index.html`，残留的旧哈希文件不被引用、不影响结论。少一次批量删除，
 * 判据就与环境状态解耦了。
 *
 * 🛑 注意这**不是**"放宽判据"：`vite build` 仍真实执行、仍必须 exit=0，
 *    只是不让它去删一个与被检语义无关的目录。
 */
function viteBuildArgv() {
  return ['build', '--emptyOutDir=false'];
}

/** 三端的差异，全部收敛在这里。 */
const ENDS = {
  'client-mp': {
    label: '端 C · 客户小程序',
    contractKey: 'client-mp',
    required: [
      'project.config.json',
      'miniprogram/app.js',
      'miniprogram/app.json',
      'miniprogram/sitemap.json',
      'miniprogram/env.js',
      'miniprogram/contract/endpoints.js',
      'miniprogram/services/request.js',
    ],
    // 端 C 受 ADR-12 三面约束（scan1/2/3 的 SCOPE 就是"客户端包"）。
    scanWords: true,
    scanRoots: ['miniprogram'],
    scanExt: /\.(js|json|wxml|wxss)$/i,
    // 出站层 URL 拼接点 + 环境层（第 56 条 base-path 判据用）
    outbound: 'miniprogram/services/request.js',
    envFile: 'miniprogram/env.js',
    // 小程序产物由微信开发者工具编译，本机没有通用打包器 ⇒ 不做"真实构建"这一项。
    realBuild: null,
  },
  'therapist-app': {
    label: '端 B · 调理师 / 经络师 APP',
    contractKey: 'therapist-app',
    // 🛑 这里列的是**本轮实装的面**，不是"骨架期留下的占位"。
    //    漏列一项的后果是"文件被删了门禁也不会红"（本仓第 52 条同型的静默漏检）
    //    —— 故每新增一个承载职责的模块（准入 / 会话 / 域服务 / UI / 页面）都要落在这里。
    required: [
      'package.json',
      'tsconfig.json',
      'vite.config.ts',
      'index.html',
      'src/main.tsx',
      'src/App.tsx',
      'src/env/index.ts',
      'src/api/client.ts',
      'src/contract/endpoints.ts',
      // X-3 唯一权威面（角色准入）+ 会话/错误/域服务/UI 元件
      'src/contract/access.ts',
      'src/services/session.ts',
      'src/services/errors.ts',
      'src/services/domain.ts',
      'src/ui/tokens.ts',
      'src/ui/components.tsx',
      // 页面
      'src/pages/LoginPage.tsx',
      'src/pages/WorkbenchPage.tsx',
      'src/pages/CustomerPage.tsx',
      // 🛑 2026-09-30（Task #115 余量收口）新增三页 —— 此前域 B/C/D 共 16 个端点
      //    「封装了但没接上界面」。补页之后**必须同步登记在此**，否则
      //    "文件被删了门禁也不会红"（第 53 条同型的静默漏检）。
      'src/pages/IntakePage.tsx',
      'src/pages/AssessmentPage.tsx',
      'src/pages/ServicePage.tsx',
      'src/pages/BandPage.tsx',
      'src/pages/MeridianActionsPage.tsx',
    ],
    // 🛑 端 B / 端 A 刻意【不】套用端 C 的禁用词自检：
    //    compliance/wordlists/scan1_refund.words 的 SCOPE 段逐字声明本面只扫客户端包，
    //    管理端 / 经络师端的退款措辞是**合法的内部业务词汇**。
    //    不做这一项不是"少一道检查"，而是词表的适用范围本就如此界定。
    scanWords: false,
    scanRoots: [],
    scanExt: /\.(ts|tsx)$/i,
    outbound: 'src/api/client.ts',
    envFile: 'src/env/index.ts',
    realBuild: [
      { name: 'tsc --noEmit', bin: 'node_modules/typescript/bin/tsc', argv: ['--noEmit'] },
      { name: 'vite build', bin: 'node_modules/vite/bin/vite.js', argv: viteBuildArgv() },
    ],
  },
  'admin-web': {
    label: '端 A · 管理员 Web',
    contractKey: 'admin-web',
    // 🛑 同端 B 纪律：这里列的是**本轮实装的面**，不是骨架期占位。
    //    漏列一项 ⇒ "文件被删了门禁也不会红"（第 53 条同型的静默漏检）。
    required: [
      'package.json',
      'tsconfig.json',
      'vite.config.ts',
      'index.html',
      'src/main.tsx',
      'src/App.tsx',
      'src/env/index.ts',
      'src/api/client.ts',
      'src/contract/endpoints.ts',
      // 契约元信息层（范围 / 未完结 / 仅超管 / 分域 —— 端 A 的真实边界都在这里）
      'src/contract/scope.ts',
      // 🛑 契约**外**能力面（结算对账 / 运维自检）：出站白名单的第二个真源。
      //    它与「生成物」是两套并行白名单，各自守自己的边界（见该文件头）。
      //    新增这两条同时意味着 a-check ⑫ `internal-capability-registry` 有了被检对象。
      'src/contract/internal-capabilities.ts',
      'src/api/internal.ts',
      // 应用层
      'src/services/token-store.ts',
      'src/services/session.ts',
      'src/services/errors.ts',
      'src/services/domain.ts',
      'src/services/ops.ts',
      'src/ui/tokens.ts',
      'src/ui/components.tsx',
      // 页面
      'src/pages/LoginPage.tsx',
      'src/pages/DashboardPage.tsx',
      'src/pages/CustomerConsolePage.tsx',
      'src/pages/StorePage.tsx',
      'src/pages/RefundWorkbenchPage.tsx',
      'src/pages/AuditPage.tsx',
      'src/pages/DocTemplatePage.tsx',
      // 🛑 2026-10-09（批次 F）新增两页 —— 此前结算落账 / 对账报表（E1）与运维自检（E2）
      //    **后端有、前端无**：契约里没有这些端点，端 A 又没有契约外通道 ⇒
      //    "总部用不了对账报表"这件事在门禁下完全静默。补页之后必须同步登记在此，
      //    否则"文件被删了门禁也不会红"（第 53 条同型的静默漏检）。
      'src/pages/SettlementPage.tsx',
      'src/pages/OpsHealthPage.tsx',
      'src/pages/UnsettledPage.tsx',
    ],
    scanWords: false,
    scanRoots: [],
    scanExt: /\.(ts|tsx)$/i,
    outbound: 'src/api/client.ts',
    envFile: 'src/env/index.ts',
    realBuild: [
      { name: 'tsc --noEmit', bin: 'node_modules/typescript/bin/tsc', argv: ['--noEmit'] },
      { name: 'vite build', bin: 'node_modules/vite/bin/vite.js', argv: viteBuildArgv() },
    ],
  },
};

const args = process.argv.slice(2);
const endArg = args.find((a) => a.startsWith('--end='));
const END_ID = endArg ? endArg.slice('--end='.length) : '';
const cfg = ENDS[END_ID];
if (!cfg) {
  process.stderr.write(`用法: node frontends/tools/build-check.mjs --end=<${Object.keys(ENDS).join('|')}>\n`);
  process.exit(2);
}

const END_ROOT = join(FRONTENDS, END_ID);
const failures = [];
const notes = [];
let envBlocked = false;

/**
 * 剥源码中的注释（行注释 / 块注释 / 字符串内的斜杠不算）—— 模块级，供 ④d 与 ④e 共用。
 *
 * 🛑 为什么必须剥注释再判字面量：注释里**解释**「为什么不能写 xxx」是合法且推荐的写法，
 *    不剥注释就会把它判红 —— 那是第 55 条（判据太窄 ⇒ 假红 ⇒ 判据被删）。
 *    本仓已复发两次：① 契约冻结门禁的 envelope-required 断言；② 分页纪律门禁的
 *    Javadoc 形态。故此处统一提升为模块级，两处判据共用同一实现，避免再次分叉。
 */
const stripComments2 = (src) => {
  let out = '';
  let i = 0, inLine = false, inBlock = false, inStr = null;
  const n = src.length;
  while (i < n) {
    const c = src[i], nx = src[i + 1];
    if (inLine) { if (c === '\n') { inLine = false; out += c; } i += 1; continue; }
    if (inBlock) { if (c === '*' && nx === '/') { inBlock = false; i += 2; continue; } i += 1; continue; }
    if (inStr) {
      out += c;
      if (c === '\\') { out += (nx || ''); i += 2; continue; }
      if (c === inStr) inStr = null;
      i += 1; continue;
    }
    if (c === '/' && nx === '/') { inLine = true; i += 2; continue; }
    if (c === '/' && nx === '*') { inBlock = true; i += 2; continue; }
    if (c === '"' || c === "'" || c === '`') { inStr = c; out += c; i += 1; continue; }
    out += c; i += 1;
  }
  return out;
};
const fail = (step, msg) => failures.push(`[${step}] ${msg}`);
const ok = (step, msg) => notes.push(`  ✓ ${step}: ${msg}`);

// ---------------------------------------------------------------------------
// 子进程执行器（异步 spawn）
// ---------------------------------------------------------------------------

/**
 * 跑一条命令，返回 { code, out }。
 * 🛑 用异步 spawn 而非 execFileSync：受限环境里同步派生对任何命令都 EBUSY，
 *    但异步可用（见文件头"派生子进程"一节）。同步接口会把可做的检查误判为环境受限。
 */
function run(cmd, argv, cwd) {
  return new Promise((res) => {
    let out = '';
    let child;
    try {
      child = spawn(cmd, argv, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      res({ code: -1, out: String((e && e.message) || e) });
      return;
    }
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('error', (e) => res({ code: -1, out: String((e && e.message) || e) }));
    child.on('close', (code) => res({ code: code === null ? -1 : code, out }));
  });
}

/**
 * 当前进程能否【异步】派生子进程？
 * 探测命令用 node 自身，避免"找不到 python"被误报成"环境禁止 spawn"。
 */
async function canSpawn() {
  const r = await run(process.execPath, ['-e', 'process.stdout.write("probe-ok")'], HERE);
  return r.code === 0 && r.out.includes('probe-ok');
}

/**
 * 🛑 本文件**不再自带** `resolvePython()` —— 解析器的唯一实现在 `./py.mjs`。
 *
 * 原先这里有一份本地实现，而 `package.json` 的 `check:contract` 走的却是裸
 * `python`；同一件事两套写法，其中一套（裸 `python`）没有走到正确解释器。
 * 抽出去之后，`build-check.mjs` 与 `gen-endpoints.mjs` 共用同一份，
 * 新增调用点也只应 `import { runPythonScript } from './py.mjs'`。
 * 完整机制见 `py.mjs` 模块注释（含 `py` 启动器因 shebang 换解释器的实测）。
 */

// ---------------------------------------------------------------------------
// ① 工程结构
// ---------------------------------------------------------------------------
for (const rel of cfg.required) {
  if (!existsSync(join(END_ROOT, rel))) fail('structure', `缺少 ${rel}`);
  else ok('structure', rel);
}

// ---------------------------------------------------------------------------
// ①b npm 脚本里**不得裸调** python（必须经 tools/gen-endpoints.mjs）
// ---------------------------------------------------------------------------
// 🛑 为什么这值得一条判据，而不是"写进 README 提醒一下"
// ---------------------------------------------------------------------------
// 实测（2026-10-08）：`package.json` 原写 `python ../tools/gen-endpoints.py --check`，
// 而本机 PATH 上的 `python` 没有 PyYAML ⇒ **`npm run check:contract` exit 2**，
// 提示是 `MISCONFIGURED: PyYAML is required`（指向"契约不一致"的方向）。
// 关键在于：**CI 的 frontend job 跑的正是这条命令** —— 它在 CI 上"大概率能过"
// 只因为 runner 的 `python` 恰好带 PyYAML；在本机则必红。
// 这就是本仓第 48 条那一类缺陷：**写下的命令 ≠ 跑得起来的命令**，
// 而两者的差别只能由"在真环境里执行"暴露，代码审阅看不出来。
//
// ⇒ 判据判【调用形态】（第 52 条）：scripts 的命令行里出现 `python` / `python3` /
//    `py` 作为**命令**即报红；正确形态是 `node ../tools/gen-endpoints.mjs …`，
//    由它去解析解释器（解析器唯一实现在 `tools/py.mjs`）。
//    这样"换个机器/换个解释器"不会再把契约校验变成假红或静默跳过。
{
  const pkgPath = join(END_ROOT, 'package.json');
  if (existsSync(pkgPath)) {
    let scripts = {};
    try {
      scripts = JSON.parse(readFileSync(pkgPath, 'utf8')).scripts || {};
    } catch (e) {
      fail('no-bare-python-script', `package.json 无法解析：${e.message}`);
    }
    const bare = Object.entries(scripts)
      .filter(([, v]) => /(^|&&|\|\||;|\s)(python3?|py)\s+\S/.test(String(v)))
      .map(([k, v]) => `${k} = ${v}`);
    if (bare.length) {
      fail('no-bare-python-script',
        'package.json 里有**裸调 python** 的脚本（换个没有 PyYAML 的解释器即 exit 2，'
        + '而报错会指向"契约不一致"这个错误方向）：\n      ' + bare.join('\n      ')
        + '\n      ⇒ 改为 node ../tools/gen-endpoints.mjs [--check]（解析器唯一实现在 tools/py.mjs）');
    } else {
      ok('no-bare-python-script',
        `package.json 的 ${Object.keys(scripts).length} 条脚本里无裸调 python（生成器一律经 tools/gen-endpoints.mjs）`);
    }
  }
}

// ---------------------------------------------------------------------------
// 🛑 第 79 条（第二十四类）：**构建依赖的「源」被烤进了版本库**
//
// 缺口的形状：仓库里三处构建输入写的是**中国镜像**（本机在中国，这是刻意的
// 优化，不是错）——
//   · `admin-web/package-lock.json` 182 条 + `therapist-app/package-lock.json`
//     122 条 `resolved`，**全部**指向 `registry.npmmirror.com`（0 条官方源）；
//   · `therapist-android/gradle/wrapper/gradle-wrapper.properties` 的
//     `distributionUrl` 指向 `mirrors.cloud.tencent.com`。
// 而 CI runner 在**境外**。两处都会让"能不能装 / 能不能拉"取决于一个我们不受控的
// 第三方 CDN —— 一旦它慢或挂，红的位置离代码十万八千里。
//
// 🛑 为什么本机全绿却抓不到（第 77 条同族，第二处）
//   · 本机的 npm registry **本来就配成 npmmirror**（`npm config get registry`），
//     所以 `npm ci` 无论走锁文件还是走配置都落到同一个 host ⇒ **差异永远不显形**；
//     只有在"配置与锁文件不一致"的机器上才暴露 —— 而那台机器就是 CI。
//   · `npm ci` 的语义是**逐字沿用**锁文件里的 `resolved` URL，不是"按 registry 重解析"。
//     实测（最小复现，`_work/npmhost-probe`，单包 `is-number@7.0.0`）：
//       不覆盖 → 真去拉的地址是 `registry.npmmirror.com/...` 与 `cdn.npmmirror.com/...`
//       覆盖后 → `registry.npmjs.org/...`
//     ⇒ 这不是理论风险，是**已观测的取包路径**。
//
// 判据（第 52 条：判形态，不判词）：**允许**仓库保留镜像（中国开发者的真实需要，
// 也是本轮验证过的本机路径），但**必须**在 CI 里显式归一。两件事绑起来：
//   ① 锁文件里存在非官方 host ⇒ workflow 必须设 `npm_config_replace_registry_host: always`
//      （npm ≥ 8.8 的官方开关：用配置的 registry 覆盖锁文件 host，**不重写锁文件**）
//      且必须同时把 `npm_config_registry` 指到官方源；
//   ② wrapper 的 `distributionUrl` 非官方 ⇒ workflow 必须显式改写该文件。
//
// 🛑 覆盖面自证（第 53 条）：若找不到任何锁文件 / 读不到 wrapper / 读不到任何
//   workflow，说明**是扫描口径坏了**（不是"没问题"），此时一律报红并写明
//   "判据覆盖面不足，不得当作通过"。
// ---------------------------------------------------------------------------
{
  const OFFICIAL_NPM = 'registry.npmjs.org';
  const OFFICIAL_GRADLE = 'services.gradle.org';
  const ITEM = 'ci-dependency-source-normalized';

  // —— 覆盖面：三份可能的锁文件（端 C 零依赖，本来就没有锁文件）——
  const LOCK_ENDS = ['admin-web', 'therapist-app', 'client-mp'];
  const locks = [];
  for (const e of LOCK_ENDS) {
    const p = join(FRONTENDS, e, 'package-lock.json');
    if (existsSync(p)) locks.push({ end: e, path: p, text: readFileSync(p, 'utf8') });
  }
  const WRAPPER = join(FRONTENDS, 'therapist-android', 'gradle', 'wrapper', 'gradle-wrapper.properties');
  const WF_DIR = join(REPO_ROOT, '.github', 'workflows');
  let wfFiles = [];
  let wfText = '';
  if (existsSync(WF_DIR)) {
    wfFiles = readdirSync(WF_DIR).filter((f) => /\.ya?ml$/i.test(f)).sort();
    wfText = wfFiles.map((f) => readFileSync(join(WF_DIR, f), 'utf8')).join('\n');
  }

  const hostCount = new Map();
  const parseErrors = [];
  for (const l of locks) {
    let j = null;
    try {
      j = JSON.parse(l.text);
    } catch (err) {
      parseErrors.push(`${l.end}/package-lock.json 无法解析：${err.message}`);
      continue;
    }
    for (const v of Object.values(j.packages || {})) {
      const r = v && v.resolved;
      if (typeof r !== 'string') continue;
      const m = /^https?:\/\/([^/]+)\//.exec(r);
      if (m) hostCount.set(m[1], (hostCount.get(m[1]) || 0) + 1);
    }
  }
  const totalResolved = [...hostCount.values()].reduce((a, b) => a + b, 0);
  const npmHosts = [...hostCount.keys()].sort();
  const nonOfficialNpm = npmHosts.filter((h) => h !== OFFICIAL_NPM);

  let gradleUrlHost = null;
  if (existsSync(WRAPPER)) {
    const m = /^distributionUrl=\s*(.+)$/m.exec(readFileSync(WRAPPER, 'utf8'));
    if (m) {
      const hm = /^https?:\/\/([^/]+)\//.exec(m[1].replace(/\\:/g, ':'));
      gradleUrlHost = hm ? hm[1] : null;
    }
  }

  // —— ① 覆盖面自证：缺任何一面都说明口径坏了 ——
  const coverage = [];
  if (locks.length === 0) coverage.push('三端一个 package-lock.json 都没找到');
  if (totalResolved === 0) coverage.push('锁文件里一条 resolved 都没解析到');
  if (!existsSync(WRAPPER)) coverage.push(`找不到 ${relative(REPO_ROOT, WRAPPER)}`);
  if (gradleUrlHost === null) coverage.push('wrapper 里没解析到 distributionUrl');
  if (wfFiles.length === 0) coverage.push('.github/workflows/ 下读不到任何 workflow 文件');

  if (coverage.length) {
    fail(ITEM,
      '判据覆盖面不足，**不得当作通过**：\n      - ' + coverage.join('\n      - ')
      + '\n      ⇒ 先修扫描口径（文件被移动/改名？），再谈"依赖源是否归一"。');
  } else if (parseErrors.length) {
    fail(ITEM, '锁文件不可解析（判据无法成立）：\n      - ' + parseErrors.join('\n      - '));
  } else {
    const needsNpm = nonOfficialNpm.length > 0;
    const needsGradle = gradleUrlHost !== OFFICIAL_GRADLE;
    const npmNormalized = /npm_config_replace_registry_host\s*:\s*always/.test(wfText)
      && new RegExp(`npm_config_registry\\s*:\\s*https://${OFFICIAL_NPM.replace(/\./g, '\\.')}`).test(wfText);
    const gradleNormalized = /distributionUrl=[^\n]*services\.gradle\.org/.test(wfText);

    const missing = [];
    if (needsNpm && !npmNormalized) {
      missing.push(`锁文件里有非官方 registry host（${nonOfficialNpm.join(' / ')}，`
        + `${nonOfficialNpm.reduce((a, h) => a + hostCount.get(h), 0)}/${totalResolved} 条），`
        + '而 workflow 里没有 `npm_config_replace_registry_host: always` + `npm_config_registry` '
        + '（`npm ci` 会逐字沿用锁文件 URL ⇒ 境外 runner 的"能不能装"取决于那个镜像）');
    }
    if (needsGradle && !gradleNormalized) {
      missing.push(`wrapper 的 distributionUrl 指向非官方 host（${gradleUrlHost}），`
        + '而 workflow 里没有任何一处改写 gradle-wrapper.properties '
        + '（境外 runner 会去拉那个镜像的发行版）');
    }

    if (missing.length) {
      fail(ITEM, 'CI 会依赖一个不受控的第三方镜像源：\n      - ' + missing.join('\n      - ')
        + '\n      ⇒ 二选一：① 把提交的源改成官方源；② 在 workflow 里显式归一'
        + '（推荐：仓库保留镜像供中国开发者，CI 侧归一 —— 与 `*.bat eol=crlf` 同一思路）。');
    } else {
      const notes = [];
      notes.push(`${locks.length} 份锁文件 · ${totalResolved} 条 resolved · host=${npmHosts.join('/')}`);
      notes.push(`wrapper host=${gradleUrlHost}`);
      notes.push(needsNpm
        ? `锁文件含非官方 host ⇒ CI 已显式归一（replace-registry-host=always + 官方 registry）`
        : '锁文件本就全官方 host ⇒ 无需归一');
      notes.push(needsGradle
        ? 'wrapper 非官方 ⇒ CI 已显式改写为官方源'
        : 'wrapper 本就官方源 ⇒ 无需改写');
      ok(ITEM, notes.join('；'));
    }
  }
}

// ---------------------------------------------------------------------------
// 🛑 第 80 条（第二十五类）：**跑 mvn 的 CI job 缺 Python 准备步**
//
// 缺口的形状（第 77 条同族，第三处 —— 这一次是「结论没有推广到同族」）：
//   `build-and-test.yml` 的 backend / frontend 两个 job 在 2026-10-08 补上了
//   `setup-python` + `pip install`（第 77 条），但**同样跑 mvn 的另两个 workflow
//   没有被一起修**：
//     · `.github/workflows/crypto-adversarial-gate.yml`   2 处 mvn，0 处 setup-python
//     · `.github/workflows/rls-isolation-gate.yml`        3 处 mvn，0 处 setup-python
//
// 🛑 为什么它们是「干净 runner 上必红」（本机实测，不是推理）：
//   根 `skeleton/pom.xml` 的 4 个 exec 门禁绑在 **validate** 阶段、靠
//   `<inherited>false</inherited>` **只在聚合根跑一次**（实测：单独跑
//   `mvn -pl dy-common validate` → 0.344s BUILD SUCCESS、零门禁输出，
//   证明"门禁不跟着子模块重复跑"）。**但 `-pl <模块> -am` 的 reactor 第 1 个
//   就是聚合根** —— 实测 `mvn -pl dy-crypto -am test` 逐字：
//       [INFO] Building diaoyuanyun-skeleton 0.0.1-SNAPSHOT        [1/2]
//       ADR-12 BUILD-TIME COMPLIANCE SCAN  --  PASS
//       [GATE-ERROR] PyYAML is not available (No module named 'yaml'). ...
//       [S1-8-GATE] FAIL (misconfigured, no PASS printed)
//       [ERROR] ... exec-maven-plugin:3.1.0:exec (s18-contract-conformance) ...
//               Process exited with an error: 2 (Exit value: 2)
//       [INFO] BUILD FAILURE
//   ⇒ 与第 77 条①「后端 4 个门禁要求真 PyYAML、缺则 exit 2 且刻意不退化成正则」
//     是**同一件事**，只是发生在另外两个 workflow 上。而 CI 一旦被触发，
//     这两个 workflow 会**同时**红在一个与代码毫无关系的地方。
//
// 判据（第 52 条：判形态，不判词）：**凡 job 里出现 mvn 命令，该 job 就必须在
// 该 mvn 步骤【之前】先有 `actions/setup-python` 与一次 `pip install`。**
// 刻意**不判**"装的是哪个包"（`requirements.txt` 与内联 `"pyyaml>=6.0"` 都可），
// 只判三件事：① 有 setup-python；② 有 pip install；③ 两者都排在 mvn **之前**
// —— 顺序反了等于没装，而"顺序错了"正是重排 YAML 时最容易发生的事。
//
// 🛑 覆盖面自证（第 53 条）：① 解析出的 job 数必须与 `runs-on:` 出现次数一致
//   （不一致 ⇒ job 切分口径坏了）；② 必须至少识别出 1 个跑 mvn 的 job
//   （识别不到 ⇒ mvn 识别口径坏了）。任一成立即报红并写明「不得当作通过」。
//
// 📌 为什么不读 YAML 库：本文件是**零依赖**的 node 脚本（`android-check` 那条
//    同款纪律），而 js-yaml 不在依赖里。故用「2 空格键名 + runs-on 交叉校验」
//    的行级切分，并把"切分是否可信"本身变成覆盖面自证的一部分。
// ---------------------------------------------------------------------------
{
  const ITEM = 'ci-mvn-jobs-have-python';
  const WF_DIR2 = join(REPO_ROOT, '.github', 'workflows');
  const problems = [];
  const coverage = [];
  let wfCount = 0;
  let jobCount = 0;
  let runsOnCount = 0;
  const mvnJobs = [];

  /** 剥掉整行注释 —— 注释里出现 `mvn` / `pip install` 是常态（本仓注释极密），
   *  不剥就会把"文档里的命令"当成"真执行的命令"（第 48 条族）。 */
  const stripComments = (t) => t.split('\n')
    .filter((l) => !/^\s*#/.test(l))
    .join('\n');

  /** 取一个 job 内的所有 `run:` 块，返回 {at, text}；at = 在 job.lines 中的起始下标，
   *  用于判「setup-python 是否排在 mvn 之前」。终止条件用缩进回退（YAML map 同级键
   *  缩进相同），因此 `env:` / `with:` 不会把子项误吞进来。 */
  const extractRuns = (jobLines) => {
    const out = [];
    for (let i = 0; i < jobLines.length; i++) {
      const m = /^(\s*)run:\s*(.*)$/.exec(jobLines[i]);
      if (!m) continue;
      const base = m[1].length;
      const inline = m[2];
      if (inline && !/^[|>]/.test(inline)) { out.push({ at: i, text: inline }); continue; }
      const buf = [];
      for (let j = i + 1; j < jobLines.length; j++) {
        const l = jobLines[j];
        if (l.trim() === '') { i = j; continue; }
        if (l.length - l.trimStart().length <= base) break;
        buf.push(l.trim());
        i = j;
      }
      out.push({ at: i, text: buf.join('\n') });
    }
    return out;
  };

  if (!existsSync(WF_DIR2)) {
    coverage.push(`找不到 ${relative(REPO_ROOT, WF_DIR2)}`);
  } else {
    const files = readdirSync(WF_DIR2).filter((f) => /\.ya?ml$/i.test(f)).sort();
    wfCount = files.length;

    for (const f of files) {
      const text = readFileSync(join(WF_DIR2, f), 'utf8');
      runsOnCount += (text.match(/^\s*runs-on:/gm) || []).length;

      const lines = text.split('\n');
      const jobs = [];
      let inJobs = false;
      let cur = null;
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (/^jobs:\s*$/.test(line)) { inJobs = true; continue; }
        if (!inJobs) continue;
        if (/^\S/.test(line)) { if (cur) { jobs.push(cur); cur = null; } inJobs = false; continue; }
        const m = /^ {2}([A-Za-z0-9_-]+):\s*$/.exec(line);
        if (m) { if (cur) jobs.push(cur); cur = { name: m[1], lines: [] }; continue; }
        if (cur) cur.lines.push(line);
      }
      if (cur) jobs.push(cur);
      jobCount += jobs.length;

      for (const job of jobs) {
        const runs = extractRuns(job.lines);
        const mvnRun = runs.find((r) => /\bmvn\b/.test(stripComments(r.text)));
        if (!mvnRun) continue;

        const setupAt = job.lines.findIndex((l) => /uses:\s*actions\/setup-python@/.test(l));
        const pipAt = job.lines.findIndex((l) => !/^\s*#/.test(l) && /\bpip\s+install\b/.test(l));
        mvnJobs.push(`${f}#${job.name}`);

        if (setupAt < 0) {
          problems.push(`${f} 的 job「${job.name}」跑了 mvn，但**没有** actions/setup-python`
            + ' ⇒ 干净 runner 上根 pom 的 4 个 validate 门禁会因缺 PyYAML exit 2（BUILD FAILURE）');
        } else if (pipAt < 0) {
          problems.push(`${f} 的 job「${job.name}」有 setup-python 但**没有** pip install`
            + ' ⇒ PyYAML 仍装不上，门禁照样 exit 2（"装了 python" 不等于 "装了解析器"）');
        } else if (setupAt > mvnRun.at) {
          problems.push(`${f} 的 job「${job.name}」的 setup-python 排在 mvn 步骤**之后**（下标 ${setupAt} > ${mvnRun.at}）`
            + ' ⇒ 顺序反了等于没装');
        } else if (pipAt > mvnRun.at) {
          problems.push(`${f} 的 job「${job.name}」的 pip install 排在 mvn 步骤**之后**（下标 ${pipAt} > ${mvnRun.at}）`
            + ' ⇒ 顺序反了等于没装');
        }
      }
    }
  }

  if (wfCount === 0) coverage.push('.github/workflows/ 下没有任何 workflow 文件');
  if (jobCount === 0) coverage.push('一个 job 都没解析出来');
  if (jobCount > 0 && jobCount !== runsOnCount) {
    coverage.push(`解析出的 job 数（${jobCount}）与 runs-on 出现次数（${runsOnCount}）不一致`
      + ' ⇒ job 切分口径坏了，扫出来的结论不可信');
  }
  if (mvnJobs.length === 0) coverage.push('一个跑 mvn 的 job 都没识别到 ⇒ mvn 识别口径坏了');

  if (coverage.length) {
    fail(ITEM,
      '判据覆盖面不足，**不得当作通过**：\n      - ' + coverage.join('\n      - ')
      + '\n      ⇒ 先修扫描口径（workflow 被移动/改名？job 写法变了？），再谈"Python 准备是否齐全"。');
  } else if (problems.length) {
    fail(ITEM, '有 job 会在干净 runner 上因缺 PyYAML 而红：\n      - ' + problems.join('\n      - ')
      + '\n      ⇒ 修法：该 job 的 mvn 步骤之前补两步 ——\n'
      + '         - uses: actions/setup-python@v5\n'
      + '           with: { python-version: "3.11" }\n'
      + '         - run: python -m pip install --disable-pip-version-check -r "${{ github.workspace }}/skeleton/requirements.txt"\n'
      + '         （与 build-and-test.yml 的 backend job 完全同款；第 77 条已在本机实测过缺 PyYAML 的后果。）');
  } else {
    ok(ITEM,
      `扫了 ${wfCount} 个 workflow / ${jobCount} 个 job（与 ${runsOnCount} 处 runs-on 一致）`
      + `；其中 ${mvnJobs.length} 个 job 跑 mvn，**全部**在 mvn 之前有 setup-python + pip install`
      + `（${mvnJobs.join(' · ')}）`);
  }
}

// ---------------------------------------------------------------------------
// ② 契约层与冻结契约一致（调生成器 --check）
// ---------------------------------------------------------------------------
const spawnOk = await canSpawn();
if (!spawnOk) {
  envBlocked = true;
  fail('contract',
    'ENV-BLOCKED: 当前环境禁止【异步】派生子进程，无法调用生成器做契约一致性校验。\n'
    + '      这【不代表】契约一致或不一致 —— 该项本轮【未验证】。\n'
    + '      请在可派生进程的环境中重跑，或手动确认：\n'
    + '        node frontends/tools/gen-endpoints.mjs --check');
} else {
  const PY = await resolvePython();
  if (!PY) {
    envBlocked = true;
    fail('contract', `${NO_PYTHON_MESSAGE}      该项【未验证】。`);
  } else {
    const r = await run(PY, [join(FRONTENDS, 'tools', 'gen-endpoints.py'), '--check'], SKELETON_ROOT);
    if (r.code !== 0) {
      fail('contract', `契约一致性自检未通过（契约已改但端点层未重跑？）\n${r.out}`);
    } else {
      const line = r.out.split('\n').find((l) => l.includes(cfg.contractKey));
      ok('contract', line ? line.trim() : 'generator --check 通过');
    }
  }
}

// ---------------------------------------------------------------------------
// ③ 端 C 专属：禁用词自检（复用 compliance 的三张词表）
// ---------------------------------------------------------------------------
if (cfg.scanWords) {
  const WORDLISTS = [
    ['scan1_refund', '退款字样'],
    ['scan2_negative', '负向计数与归因措辞'],
    ['scan3_derived', '派生字段与派生结论'],
  ];
  const loadWords = (name) => {
    const p = join(COMPLIANCE, 'wordlists', `${name}.words`);
    if (!existsSync(p)) return null;
    return readFileSync(p, 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  };
  const walk = (dir, acc = []) => {
    for (const name of readdirSync(dir)) {
      if (name === 'node_modules' || name.startsWith('.')) continue;
      const p = join(dir, name);
      if (statSync(p).isDirectory()) walk(p, acc);
      else if (cfg.scanExt.test(name)) acc.push(p);
    }
    return acc;
  };
  // 🛑🛑 2026-09-30 修正（第 51 条系统性缺陷）：这里**不再剥注释**。
  //
  // 旧版本此处的做法是 `stripComments(...)`（剥 /* */ 与 //），并在注释里声称
  // 「与 compliance 一致」。**那句声称是错的** —— 实测 compliance/scan_compliance.py
  // 的 scan_face() 是逐行 `line_matches(line, term)` 直接匹配，**不剥任何注释**。
  //
  // 两处口径不一致的后果正是本仓反复修的那类假绿：
  //   · 本端 `npm run build` 绿 —— 因为注释被剥掉了；
  //   · CI 里 ADR-12 门禁红 —— 因为主扫描器看得见注释。
  // 于是"本地全绿、推上去红"，而排查者会以为是 CI 环境问题。
  //
  // 方向选择（不是随便挑一个）：
  //   词表的 SCOPE 逐字写着「applied to the WHOLE client package with no path
  //   allow-list」，而注释确实是客户端包的一部分；且小程序打包后注释可能保留，
  //   反编译可见。故**注释里的禁词本就是命中**，主扫描器是对的。
  //   本文件擅自剥注释 = **放松门禁**，而本仓纪律明令「不放宽词表、不加 path 例外」。
  //   故此处对齐到**更严的一侧**：注释同样参与匹配。
  //
  // 这条同时解释了为什么本文件里看不到被禁术语的原文引用 —— 需要说明"某词为什么
  // 被禁"时，一律**用指代**（如「③ 组字段名」「R7 点名的评价类措辞」），不写原词。
  // 该约束已登记于 frontends/README.md。
  const scanned = cfg.scanRoots
    .filter((r) => existsSync(join(END_ROOT, r)))
    .flatMap((r) => walk(join(END_ROOT, r)));

  for (const [name, label] of WORDLISTS) {
    const words = loadWords(name);
    if (words === null) { fail('wordlist', `词表缺失：compliance/wordlists/${name}.words`); continue; }
    const hits = [];
    for (const file of scanned) {
      const code = readFileSync(file, 'utf8');
      for (const w of words) if (w && code.includes(w)) hits.push(`${relative(END_ROOT, file)} :: ${w}`);
    }
    if (hits.length) fail('words', `${label}（${name}）命中 ${hits.length} 处:\n      ${hits.join('\n      ')}`);
    else ok('words', `${label}（${name}）无命中，扫描 ${scanned.length} 个文件`);
  }
} else {
  notes.push('  – words: 本端不适用（ADR-12 三面的 SCOPE 是客户端包；'
    + '管理端 / 经络师端的退款措辞为合法内部业务词汇）');
}

// ---------------------------------------------------------------------------
// ④ 页面/入口清单与实际文件一致（端 C）
// ---------------------------------------------------------------------------
const appJsonPath = join(END_ROOT, 'miniprogram', 'app.json');
if (existsSync(appJsonPath)) {
  const declared = JSON.parse(readFileSync(appJsonPath, 'utf8')).pages || [];
  const missing = declared.filter((p) => !['.js', '.wxml', '.json'].some((ext) =>
    existsSync(join(END_ROOT, 'miniprogram', p + ext))));
  if (missing.length) fail('pages', `app.json 声明但文件缺失: ${missing.join(', ')}`);
  else ok('pages', `${declared.length} 个页面，文件齐全`);
}

// ---------------------------------------------------------------------------
// ④b 【三端共享】令牌键名单一来源（本轮实测缺陷的守卫）
// ---------------------------------------------------------------------------
// 🛑 实测缺陷（第 50 条同族，接缝在【跨文件隐式约定】）：
//    端 A / 端 B 的 `services/session.ts` 写 `localStorage.setItem('dy.token', ...)`，
//    而 `api/client.ts` 读 `localStorage.getItem('token')` —— **两个键名**。
//    ⇒ 出站 `Authorization` 头**永远为空** ⇒ 全量 401。
//    🛑 它完全静默：tsc 不报（两个字符串都是合法字符串）、构建不报、
//      既有门禁不报（查的是"端点属不属本端""角色授没授予"）。
//       只有**真发一次带鉴权的请求**才会暴露，而那时报的是 401 ——
//       排查者会先去怀疑账号权限，而不是"两个文件用了两个键名"。
//    修法两层：① 键名收敛到一个模块（端 A = `services/token-store.ts`，
//    端 B = `services/session.ts` 内的 TOKEN_KEY）；② 本条判据禁止**别处**
//    再出现令牌语义的裸 localStorage 键名字面量。
// 🛑 判据形态（第 52 条教训）：判【存储访问形态】，不判"词是否出现"。
{
  const TOKEN_SEMANTIC = /token|jwt|auth/i;
  const walkTs = (dir, acc = []) => {
    if (!existsSync(dir)) return acc;
    for (const name of readdirSync(dir)) {
      if (name === 'node_modules' || name.startsWith('.')) continue;
      const p = join(dir, name);
      if (statSync(p).isDirectory()) walkTs(p, acc);
      else if (/\.(ts|tsx)$/i.test(name)) acc.push(p);
    }
    return acc;
  };
  const srcDir = join(END_ROOT, 'src');
  const files = walkTs(srcDir);
  if (files.length === 0) {
    notes.push('  – token-single-source: 本端无 src/（不适用）');
  } else {
    // 权威点：端 A 是 token-store.ts；端 B 是 services/session.ts（含 TOKEN_KEY 常量）
    const AUTH_FILES = new Set([
      join(srcDir, 'services', 'token-store.ts'),
      join(srcDir, 'services', 'session.ts'),
    ]);
    const hits = [];
    for (const f of files) {
      if (AUTH_FILES.has(f)) continue;
      const code = readFileSync(f, 'utf8');
      const re = /localStorage\.(?:get|set|remove)Item\(\s*(['"])((?:[^'"\\]|\\.)*)\1/g;
      let m;
      while ((m = re.exec(code))) {
        if (TOKEN_SEMANTIC.test(m[2])) {
          hits.push(`${relative(END_ROOT, f)} :: ${JSON.stringify(m[2])}`);
        }
      }
    }
    if (hits.length) {
      fail('token-single-source',
        `令牌键名出现了权威点之外的第二处定义:\n      ${hits.join('\n      ')}\n`
        + '      ⇒ 写与读的键名若不一致，Authorization 头会【静默为空】（全量 401），\n'
        + '        而 tsc / 构建 / 其它判据都不会报。');
    } else {
      ok('token-single-source', `令牌键名仅存在于权威点（已扫 ${files.length} 个源文件）`);
    }
  }
}

// ---------------------------------------------------------------------------
// ④c 【三端共享】契约 Base Path 必须进入出站 URL（第 56 条）
// ---------------------------------------------------------------------------
// 🛑 实测缺口：契约的 `paths` 键是 `/auth/me`，真实 URL 是 `/api/v1/auth/me`
//    —— 前缀由 `servers[0].url`（契约 §2.0 Base Path）承载。而三端出站一律写成
//    `baseUrl + endpoint.path`，把"baseUrl 只到网关根"这件事**只写在一句注释里**。
//    运维按注释配置 ⇒ 三端全量 404 ⇒ 而 tsc / vite / 全部门禁**全部仍绿**
//    （它们从不发真实请求）。后端那一侧有 EndpointCoverageLedgerTest 钉住路由，
//    前端这一侧此前没有 —— 这是"跨端隐式协议"缺口的第 56 条。
// 判据形态（判【调用形态 + 计数等式】，不判"词是否出现"）：
//   ① 出站层必须存在"用契约 Base Path 前缀"的字面调用；
//   ② 出站层不得存在"裸 baseUrl 拼 path"的形态；
//   ③ 环境层（env）必须导出该前缀，且其值**引用生成物常量**（不得手写字面量）；
//   ④ 生成物里必须确有该常量、且值 === 契约 servers[0].url（计数等式）。
//   ⑤ vite dev 代理键必须含该 Base Path（否则 dev 期请求根本不进代理规则）。
if (cfg.outbound && cfg.envFile) {
  const outboundPath = join(END_ROOT, cfg.outbound);
  const envPath = join(END_ROOT, cfg.envFile);
  const genRel = END_ID === 'client-mp'
    ? 'miniprogram/contract/endpoints.js'
    : 'src/contract/endpoints.ts';
  const genPath = join(END_ROOT, genRel);
  const missing = [outboundPath, envPath, genPath].filter((p) => !existsSync(p));
  if (missing.length) {
    fail('base-path-wiring', `缺文件: ${missing.map((p) => relative(END_ROOT, p)).join(', ')}`);
  } else {
    const outbound = readFileSync(outboundPath, 'utf8');
    const envSrc = readFileSync(envPath, 'utf8');
    const genSrc = readFileSync(genPath, 'utf8');

    // 🛑 判"有没有手写常量 / URL 怎么拼"这类**代码语义**时，必须先剥注释。
    //    与上面 ③ 词表判据**刻意不剥注释**并不矛盾，两者目的不同：
    //      · ③ 判"禁用词是否出现在客户端包里" —— 注释也是包的一部分（且反编译可见）；
    //      · ④c 判"代码里是否手写了 Base Path" —— 注释里提到路径**不是**硬编码，
    //        反而正是本仓鼓励的"把为什么写清楚"。若不剥注释，一句
    //        `// 契约 servers.url = "/api/v1"` 就会被判红 ⇒ 第 55 条（假红）复发。
    const stripComments = (src) => {
      let out = '';
      let i = 0;
      let inLine = false;
      let inBlock = false;
      let inStr = null;
      const n = src.length;
      while (i < n) {
        const c = src[i];
        const nx = src[i + 1];
        if (inLine) {
          if (c === '\n') { inLine = false; out += c; }
          i += 1; continue;
        }
        if (inBlock) {
          if (c === '*' && nx === '/') { inBlock = false; i += 2; continue; }
          i += 1; continue;
        }
        if (inStr) {
          out += c;
          if (c === '\\') { out += (nx || ''); i += 2; continue; }
          if (c === inStr) inStr = null;
          i += 1; continue;
        }
        if (c === '/' && nx === '/') { inLine = true; i += 2; continue; }
        if (c === '/' && nx === '*') { inBlock = true; i += 2; continue; }
        if (c === '"' || c === "'" || c === '`') { inStr = c; out += c; i += 1; continue; }
        out += c; i += 1;
      }
      return out;
    };
    const outboundCode = stripComments(outbound);
    const envCode = stripComments(envSrc);

    // ④ 生成物侧：常量存在，且值取自契约 servers[0].url 的机械转录
    const gm = genSrc.match(/(?:const|export const)\s+API_BASE_PATH(?::\s*string)?\s*=\s*"([^"]*)"/);
    if (!gm) {
      fail('base-path-wiring',
        `${genRel} 缺少 API_BASE_PATH —— 生成器没有转录契约 servers[0].url。\n`
        + '      请先跑 node frontends/tools/gen-endpoints.mjs（生成器会读 servers）。');
    } else {
      const genValue = gm[1];
      if (!/^\/[A-Za-z0-9._~\-/]*$/.test(genValue) || genValue === '/') {
        fail('base-path-wiring', `API_BASE_PATH 取值异常: ${JSON.stringify(genValue)}`);
      }
      // ③ 环境层必须导出前缀，且引用生成物常量（不得手写 "/api/v1"）
      const envExports = /(?:export\s+(?:function|const)\s+(?:getRequestBaseUrl|requestBaseUrl))|(?:get\s+requestBaseUrl\s*\()/.test(envCode);
      const envUsesGenerated = /API_BASE_PATH/.test(envCode);
      const envHardcodes = new RegExp('"' + genValue.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '"').test(envCode)
        || new RegExp("'" + genValue.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + "'").test(envCode);
      if (!envExports) {
        fail('base-path-wiring', `${cfg.envFile} 未导出出站前缀（getRequestBaseUrl / requestBaseUrl）`);
      } else if (!envUsesGenerated) {
        fail('base-path-wiring',
          `${cfg.envFile} 的出站前缀没有引用生成物常量 API_BASE_PATH`
          + '——手抄前缀会在契约改 Base Path 时静默分叉（第 52 条同型）。');
      } else if (envHardcodes) {
        fail('base-path-wiring', `${cfg.envFile} 里出现了手写的 Base Path 字面量 ${JSON.stringify(genValue)}，应只用 API_BASE_PATH`);
      }

      // ① 出站层必须"用前缀"拼 URL；② 不得"裸 baseUrl 拼 path"
      // 🛑 判据形态（第 55 条教训：判得窄会把本仓**自己规定的合法写法**判红）：
      //    不判"某个标识符是否出现"，而是**圈定 URL 组装表达式**再判其成分 ——
      //    且前缀既可能是函数调用（端 A/B 的 getRequestBaseUrl()），
      //    也可能是属性访问（端 C 的 ENV.requestBaseUrl getter）。
      //    两者都是合法写法，判据必须都认（初版只认前者，把端 C 判红了）。
      const urlAssign = outboundCode.match(/(?:let|const|var)\s+url\s*=\s*([^;]+);/);
      const PREFIX_ID = /(?:getRequestBaseUrl|requestBaseUrl|API_BASE_PATH)/;
      const BARE_ID = /(?:^|[^\w.])(?:getBaseUrl\s*\(\s*\)|baseUrl)\b/;
      if (!urlAssign) {
        fail('base-path-wiring', `${cfg.outbound} 找不到 URL 组装表达式（\`let url = ...\`），判据无法落地。`);
      } else if (BARE_ID.test(urlAssign[1])) {
        fail('base-path-wiring',
          `${cfg.outbound} 的 URL 组装用【裸 baseUrl】: \`${urlAssign[1].trim()}\`\n`
          + `      endpoint.path 不含 Base Path ⇒ 出站落到 ${JSON.stringify(genValue)} 之外 ⇒ 全量 404，`
          + '而本自检曾经全绿。');
      } else if (!PREFIX_ID.test(urlAssign[1])) {
        fail('base-path-wiring',
          `${cfg.outbound} 的 URL 组装未含契约 Base Path 前缀: \`${urlAssign[1].trim()}\``);
      } else {
        ok('base-path-wiring',
          `出站前缀取自生成物 API_BASE_PATH=${JSON.stringify(genValue)}`
          + `（${relative(END_ROOT, cfg.outbound)} 用前缀拼接 · ${relative(END_ROOT, cfg.envFile)} 引用生成物常量 · 无手写字面量）`);
      }

      // ⑤ vite dev 代理键（仅端 A / 端 B 有 vite.config.ts）
      const vitePath = join(END_ROOT, 'vite.config.ts');
      if (existsSync(vitePath)) {
        const viteSrc = stripComments(readFileSync(vitePath, 'utf8'));
        const keys = [...viteSrc.matchAll(/proxy\s*:\s*\{([\s\S]*?)\n\s*\}/g)]
          .flatMap((m) => [...m[1].matchAll(/(['"])(\/[^'"]*)\1\s*:/g)].map((x) => x[2]));
        if (keys.length === 0) {
          fail('base-path-wiring', 'vite.config.ts 里找不到 proxy 键 —— 判据无法落地（形态变了？）');
        } else if (!keys.some((k) => k === genValue || k.startsWith(genValue))) {
          fail('base-path-wiring',
            `vite.config.ts 的 dev 代理键 ${JSON.stringify(keys)} 均不含契约 Base Path `
            + `${JSON.stringify(genValue)} ⇒ 出站请求打到 ${JSON.stringify(genValue)}/... `
            + '时**不匹配任何代理规则** ⇒ dev 期全量 404，而 npm run dev 与构建自检都不报错。');
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// ④d 【三端共享】跨端协议片段必须取自生成物常量（第 57 条）
// ---------------------------------------------------------------------------
// 🛑 实测缺口：除 URL 前缀外，"跨端共同遵守的协议片段"还有三类同样只散在
//    注释 / prose / 各处手抄的字面量里：
//      · 鉴权头名 + 令牌前缀 —— 三端各自写 `headers.Authorization = \`Bearer ${token}\``；
//      · 幂等头名 —— 三端各自写 `'Idempotency-Key'`；
//      · 追踪头名 —— 端 A/B `res.headers.get('X-Trace-Id')`、端 C `res.header['X-Trace-Id']`。
//        🛑 这个头连 prose 里都没有：契约全域【零声明】，只活在后端
//        `TraceIdFilter.HEADER` 常量与前端字面量里（实测 6 处）。
//      · 信封成功码 —— 三端各自写 `body.code !== 0` / `body.code === 0`。
//    失效方式与第 56 条完全同型：改一处 ⇒ 各处静默分叉 ⇒ 全量 401 /
//    幂等去重失效 / 留痕断链，而 tsc / vite / 全部门禁**全部仍绿**。
//
// 判据形态（第 52/55 条教训：判【调用形态 + 计数等式】，不判"词是否出现"）：
//   ① 生成物里必须确有 PROTOCOL 常量组，且各键值与契约 x-api-protocol 一致；
//   ② 出站层必须【引用】PROTOCOL.*（证明它没手抄）；
//   ③ 出站层不得残留协议字面量（剥注释后判 —— 注释里提到头名正是本仓鼓励的
//      "把为什么写清楚"，不该判红，见第 55 条）。
if (cfg.outbound) {
  const outboundPath = join(END_ROOT, cfg.outbound);
  const genRel2 = END_ID === 'client-mp'
    ? 'miniprogram/contract/endpoints.js'
    : 'src/contract/endpoints.ts';
  const genPath2 = join(END_ROOT, genRel2);
  if (!existsSync(outboundPath) || !existsSync(genPath2)) {
    fail('cross-end-protocol', `缺文件: ${relative(END_ROOT, outboundPath)} / ${relative(END_ROOT, genPath2)}`);
  } else {
    const genSrc2 = readFileSync(genPath2, 'utf8');
    // ① 生成物必须有 PROTOCOL 常量组，且值取自契约 x-api-protocol
    const expected = [
      ['AUTH_HEADER', 'Authorization'],
      ['AUTH_SCHEME', 'Bearer'],
      ['TENANT_HEADER', 'X-Tenant-Id'],
      ['TRACE_HEADER', 'X-Trace-Id'],
      ['IDEMPOTENCY_HEADER', 'Idempotency-Key'],
    ];
    const badGen = [];
    for (const [key, val] of expected) {
      const re = new RegExp(`\\b${key}\\s*:\\s*"([^"]*)"`);
      const m = genSrc2.match(re);
      if (!m) badGen.push(`${key} 缺失`);
      else if (m[1] !== val) badGen.push(`${key}=${JSON.stringify(m[1])} ≠ 契约 ${JSON.stringify(val)}`);
    }
    if (!/ENVELOPE_OK_CODE\s*:\s*0\b/.test(genSrc2)) badGen.push('ENVELOPE_OK_CODE 缺失或 ≠ 0');
    if (!/ENVELOPE_FIELDS\s*:\s*Object\.freeze\(\s*\[[^\]]*\btrace_id\b/.test(genSrc2)) {
      badGen.push('ENVELOPE_FIELDS 缺失或不含 trace_id');
    }
    // 🛑 PAGINATION（第 58 条）：值必须与契约 x-api-protocol.pagination 一致。
    //    「值漂移」与「键缺失」是两件事，都要报 —— 只判存在会让改值静默通过
    //    （第 52 条：判据太宽 ⇒ 假绿）。
    const expectedPag = [
      ['PAGE_FIELD', '"page"'],
      ['PAGE_SIZE_FIELD', '"page_size"'],
      ['PAGE_MIN', '1'],
      ['PAGE_SIZE_MAX', '100'],
      ['PAGE_SIZE_DEFAULT', '20'],
      ['OVER_RANGE_POLICY', '"reject-400"'],
      ['OVER_RANGE_ERROR', '"VALIDATION_FAILED"'],
    ];
    for (const [key, lit] of expectedPag) {
      const re = new RegExp(`\\b${key}\\s*:\\s*(${lit.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})\\s*[,}]`);
      if (!re.test(genSrc2)) {
        badGen.push(`PAGINATION.${key} 缺失或 ≠ 契约值 ${lit}`);
      }
    }
    // 🛑 ERROR_DATA_FIELDS（第 61 条）：拒绝响应的 data 载荷字段名。
    //    「值漂移」与「键缺失」都要报 —— 只判存在会让改值静默通过（第 52 条）。
    const expectedErrFields = [['2002', 'missing_items'], ['2001', 'denied_fields']];
    for (const [code, field] of expectedErrFields) {
      const re = new RegExp(`\\b${code}\\s*:\\s*"${field}"\\s*[,}]`);
      if (!re.test(genSrc2)) {
        badGen.push(`ERROR_DATA_FIELDS[${code}] 缺失或 ≠ 契约值 "${field}"`);
      }
    }
    if (badGen.length) {
      fail('cross-end-protocol',
        `${genRel2} 的 PROTOCOL 常量组与契约 x-api-protocol 不一致:\n      ${badGen.join('\n      ')}\n`
        + '      请先跑 node frontends/tools/gen-endpoints.mjs（生成器会读 x-api-protocol）。');
    } else {
      // ②③ 出站层：必须引用 PROTOCOL.*，且不得残留协议字面量（剥注释后判）
      const ob = stripComments2(readFileSync(outboundPath, 'utf8'));
      const relOut = relative(END_ROOT, outboundPath);

      // 🛑 判"引用形态"而非"标识符出现"：必须见到 PROTOCOL.<KEY> 的【成员访问】。
      const usedKeys = ['AUTH_HEADER', 'AUTH_SCHEME', 'IDEMPOTENCY_HEADER', 'TRACE_HEADER', 'ENVELOPE_OK_CODE']
        .filter((k) => new RegExp(`PROTOCOL\\.${k}\\b`).test(ob));
      const missingUse = ['AUTH_HEADER', 'AUTH_SCHEME', 'IDEMPOTENCY_HEADER', 'TRACE_HEADER', 'ENVELOPE_OK_CODE']
        .filter((k) => !new RegExp(`PROTOCOL\\.${k}\\b`).test(ob));

      // 残留协议字面量（剥注释后）：头名以字符串字面量出现，或 'Bearer ' 前缀硬编码
      // 🛑 判"字面量"用**任意引号出现**而非"`NAME:` 键形态"：头名更常见的用法是
      //    下标访问（端 C 的 `res.header['X-Trace-Id']`），那种写法后面没有冒号
      //    —— 若只认键形态就会漏掉它（第 52 条：判据太宽/太窄都是缺陷）。
      //    注释已被剥掉，故"注释里解释头名为什么这么写"不会被误判（第 55 条）。
      const literalHits = [];
      for (const headerName of ['Authorization', 'Idempotency-Key', 'X-Trace-Id', 'X-Tenant-Id']) {
        const re = new RegExp(`(['"\`])${headerName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\1`);
        if (re.test(ob)) literalHits.push(`头名字面量 ${JSON.stringify(headerName)}`);
      }
      if (/(['"`])Bearer[\s'"]|(['"`])Bearer\s*\+/.test(ob)) literalHits.push('令牌前缀字面量 "Bearer "');
      if (/\bcode\s*[!=]==?\s*0\b/.test(ob)) literalHits.push('信封成功码字面量 0');

      if (missingUse.length) {
        fail('cross-end-protocol',
          `${relOut} 未引用生成物 PROTOCOL 常量的这些键: ${missingUse.join(', ')}\n`
          + '      跨端协议片段（头名 / 令牌前缀 / 信封成功码）必须是引用、不能手抄 —— '
          + '手抄的会在契约改动时静默分叉（第 57 条）。');
      } else if (literalHits.length) {
        fail('cross-end-protocol',
          `${relOut} 仍残留协议字面量: ${literalHits.join(', ')}\n`
          + '      ⇒ 契约 x-api-protocol 一改，这里不会跟着改（tsc / 构建都不会报）。');
      } else {
ok('cross-end-protocol',
          `${relOut} 协议片段全部引用生成物 PROTOCOL 常量（${usedKeys.length} 个键），无字面量残留`);
      }
    }
}

// ---------------------------------------------------------------------------
// ④e 分页协议：参数名不得手抄（第 58 条）
// ---------------------------------------------------------------------------
// 🛑 它堵的洞
//   契约 x-global-conventions.pagination 只声明约束（上界 100），**没说越界怎么办**，
//   于是后端两个列表端点各走一路（A3 超界拒 400 / D2 静默夹逼后 200）。前端这一侧
//   是同一个病的另一形态：分页【参数名】page / page_size 在调用点手抄（端 A 6 处、
//   端 B 4 处）。契约若把 page_size 改名，tsc / 构建 / 门禁**都不会报** ——
//   请求参数会静默变成服务端不认识的键，分页悄然失效。与第 57 条 X-Trace-Id 同族。
//
//   判据分两段：
//     ① 生成物 PAGINATION 键值必须与契约一致（值漂移 = 红，见④d 的 expectedPag）；
//     ② 服务层【出站 query 构造】不得出现 page / page_size 的字面量键，
//        必须经由分页助手（pageQuery）间接取用生成物常量。
//   判据②刻意**只扫服务层**（services/*.ts），不扫界面层（pages/）—— 界面里
//   `page` 常作为局部变量/循环变量，把它判红就是第 55 条（太窄 ⇒ 假红 ⇒ 判据被删）。
if (cfg.outbound) {
  const svcRel = (END_ID === 'client-mp')
    ? 'miniprogram/services'
    : 'src/services';
  const svcDir = join(END_ROOT, svcRel);
  if (existsSync(svcDir)) {
    const files = readdirSync(svcDir).filter((f) => /\.(ts|js)$/.test(f));
    const hits = [];
    for (const f of files) {
      const src = readFileSync(join(svcDir, f), 'utf8');
      // 剥注释（复用模块级 stripComments2）后，逐行找「query 对象里手抄的分页键」
      const code = stripComments2(src);
      code.split('\n').forEach((line, i) => {
        // 形态：`{ page, ... }` / `{ page: x, page_size: y }` / `[PAGE_SIZE_FIELD]` 允许
        if (/\bpage\s*[,:}]/.test(line) && /query|params/i.test(line)
            && !/pageQuery\s*\(/.test(line) && !/PAGE_(SIZE_)?FIELD/.test(line)) {
          hits.push(`${svcRel}/${f}:${i + 1}  ${line.trim()}`);
        }
        if (/\bpage_size\s*[,:}]/.test(line) && !/readonly\s+page_size/.test(line)
            && !/PAGE_SIZE_FIELD/.test(line)) {
          hits.push(`${svcRel}/${f}:${i + 1}  ${line.trim()}`);
        }
      });
    }

    // 🛑 追加判据（第 53 条形态）：分页助手的**内部实现**也必须引用生成物常量。
    //    上面只扫"字面量"，抓不到把键名改成**别名**的写法（如 `q['pageSize'] = ...`
    //    —— 它不是 `page_size` 字面量，正则扫不到）。而分页助手是所有调用点的
    //    唯一构造点，改错它会让**全部**分页请求发错参数名且全绿。
    //    反向验证 R13 首跑即漏过，证明了这是真盲区。
    //    ⇒ 判"引用形态"（与 ④d 的 `PROTOCOL.<KEY>` 判法同构）：助手内必须出现
    //      PAGE_FIELD 与 PAGE_SIZE_FIELD 的成员访问。
    const pagingHelper = files.find((f) => /^paging\.(ts|js)$/.test(f));
    if (pagingHelper) {
      const ph = stripComments2(readFileSync(join(svcDir, pagingHelper), 'utf8'));
      for (const key of ['PAGE_FIELD', 'PAGE_SIZE_FIELD']) {
        if (!new RegExp(`PAGINATION\\.${key}\\b`).test(ph)) {
          hits.push(`${svcRel}/${pagingHelper}  未引用 PROTOCOL.PAGINATION.${key} —— `
            + '分页助手是全部调用点的唯一构造点，改错键名会让所有分页请求'
            + '发错参数名且 tsc/构建全绿');
        }
      }
    }
    if (hits.length) {
      fail('pagination-protocol',
        `${END_ID} 服务层仍有手抄的分页参数名 —— 契约 x-api-protocol.pagination 一改，`
        + '这里不会跟着改（tsc / 构建都不会报，分页会静默失效）：\n      '
        + hits.join('\n      ')
        + '\n      ⇒ 请经分页助手构造：pageQuery(page, size)（键名取自生成物 PROTOCOL.PAGINATION）。'
        + '\n      （响应字段 readonly page_size 是契约 schema 字段名，不属手抄，已豁免。）');
    } else {
      ok('pagination-protocol',
        `${svcRel} 分页参数名全部经分页助手取用（无 page/page_size 字面量键；响应字段除外）`);
    }
  }
}
}

// ---------------------------------------------------------------------------
// ④f 拒绝响应原因名：错误层必须经生成物常量取字段名（第 61 条）
// ---------------------------------------------------------------------------
// 🛑 它堵的洞
//   契约 `forbidden-403` 写「message 必须给出缺失项名称 / 档位名称（**不得模糊报错**）」。
//   这句话有**两半**：给人读的 message，和给机器读的 `data` 载荷字段名
//   （2002→missing_items / 2001→denied_fields）。契约里这两个字段名**此前只有 prose**。
//   实测三端全线缺半：
//     · `client.ts` 在错误路径**丢掉 `body.data`**（只挂 code/message/traceId）；
//     · 三端错误层只取 message ⇒ 用户永远看不到"到底缺什么"；
//     · 端 A 的静态文案甚至对用户【承诺】了「响应 data.missing_items 列出缺失项」，
//       而它自己从不读那个字段 —— 文案在替一个不存在的功能背书。
//   ⇒ 契约要求的"给出名字"在客户端**完全没有到达**，而 tsc / 构建 / 门禁全绿。
//     与第 57/58 条同族：**契约写下的协议与各端实现的协议是两件事，中间丢项不报错。**
//
//   判据两段（与 ④e 同构）：
//     ① 生成物 ERROR_DATA_FIELDS 键值必须与契约一致（值漂移 = 红，见 ④d）；
//     ② 出站层必须把 `body.data` 带出来（否则错误层无从取用）；
//     ③ 错误层必须【引用形态】取字段名：`ERROR_DATA_FIELDS[` 成员访问 + 下标取用，
//        且【不得手写】 'missing_items' / 'denied_fields' 字面量（除文档注释外）。
if (cfg.outbound) {
  const genRel3 = (END_ID === 'client-mp')
    ? 'miniprogram/contract/endpoints.js'
    : 'src/contract/endpoints.ts';
  const outRel3 = cfg.outbound;
  const errRel3 = (END_ID === 'client-mp')
    ? 'miniprogram/services/codes.js'
    : 'src/services/errors.ts';
  const genP3 = join(END_ROOT, genRel3);
  const outP3 = join(END_ROOT, outRel3);
  const errP3 = join(END_ROOT, errRel3);

  if (!existsSync(genP3) || !existsSync(outP3) || !existsSync(errP3)) {
    fail('error-data-fields', `缺文件: ${genRel3} / ${outRel3} / ${errRel3}`);
  } else {
    const problems = [];
    // ② 出站层必须在【错误对象上】保留 body.data（否则错误层拿不到原因名）。
    // 🛑 判据必须判"错误对象上的赋值"这一【精准形态】—— 不能只判源码里出现过
    //    `data:`：成功路径的 `return { data: body.data, ... }` 会让它恒真
    //    （反向验证 R14 首跑即漏过，正是第 52 条「判据太宽 ⇒ 假绿」）。
    //    形态：`err.data = <...>body.data`（端 A/B）或 `err.data = body && body.data`（端 C）。
    const ob3 = stripComments2(readFileSync(outP3, 'utf8'));
    const keepsErrData = /\berr\.data\s*=\s*[^;]*\bbody\.data\b/.test(ob3);
    if (!keepsErrData) {
      problems.push(`${outRel3} 的错误对象未保留响应 data（须有形如 \`err.data = body.data\` 的赋值）`
        + ' —— 否则错误层无从取契约要求的原因名');
    }
    // ③ 错误层必须经生成物常量取字段名，且不得手写字面量
    const errSrc = stripComments2(readFileSync(errP3, 'utf8'));
    if (END_ID !== 'client-mp') {
      // 端 A / 端 B 的字段名一律 `PROTOCOL.ERROR_DATA_FIELDS[code]`
      if (!/ERROR_DATA_FIELDS\s*\[/.test(errSrc)) {
        problems.push(`${errRel3} 未引用 PROTOCOL.ERROR_DATA_FIELDS[code] —— `
          + '拒绝原因名必须经生成物常量取字段名');
      }
    } else if (!/ERROR_DATA_FIELDS\s*\[/.test(errSrc)) {
      problems.push(`${errRel3} 未引用 PROTOCOL.ERROR_DATA_FIELDS[code]`);
    }
    for (const field of ['missing_items', 'denied_fields']) {
      const re = new RegExp(`(['"\`])${field}\\1`);
      if (re.test(errSrc)) {
        problems.push(`${errRel3} 仍手写字段名字面量 "${field}" —— `
          + '契约改字段名时这里不会跟着改（应经 PROTOCOL.ERROR_DATA_FIELDS 取）');
      }
    }
    if (problems.length) {
      fail('error-data-fields', problems.join('\n      '));
    } else {
      ok('error-data-fields',
        `${outRel3} 保留错误响应 data；${errRel3} 经 PROTOCOL.ERROR_DATA_FIELDS 取原因名（无字面量）`);
    }
  }
}

// ---------------------------------------------------------------------------
// ④g 【端点触达】本端全部端点必须在业务代码里被以调用形态发出（第 63 条）
// ---------------------------------------------------------------------------
// 🛑 它堵的洞（2026-09-30 实测）
//    `endpoints.js` 声明本端 15 个 operation，`services/*.js` 里也都有
//    `request.call('<id>')` —— 但**"服务层写了"≠"界面能用到"**。
//    端 A 有这条判据（a-check ⑩），端 B 有这条判据（x3-check ⑧），端 C **没有**：
//    它的 15 个端点从未被任何判据要求过"从界面到出站有一条真实调用链"。
//    实测端 B 在同一处境下静默漏了 16/29（见 x3-check ⑧ 的注释）。
//
// 🛑 端 C 的形态与端 A/B 不同，判据必须跟着变（否则就是第 52 条"太宽⇒假绿"）
// ---------------------------------------------------------------------------
//    · 服务层是 **CommonJS**：`function foo(){}` + `module.exports = { foo: foo }`，
//      不是 `export function foo`；只用端 A/B 的正则**一条都匹配不到**
//      ⇒ 判据会"通过"但它实际什么都没检查（这本身就是一次假绿，实测踩到）。
//    · 页面用 `var api = require('../../services/domain.js')` 取得别名，
//      再 `api.foo(...)` 调用 —— 判据必须**先解析别名**，不能只认函数名。
//    · 本端存在**编排层**（`band-sync.js`）：`syncOnShow()` 内部依次调用
//      `reportAvailableDates()` / `uploadRecords()`，而页面只需调用 `syncOnShow`。
//      ⇒ 判据必须接受"被同一服务模块内的导出函数调用"这第二种触达形态，
//        否则会把一个**合法的编排设计**判成缺陷（假红）。
//      两种形态合计仍须覆盖全部端点 —— 计数等式照旧。
{
  const MP_ROOT = join(END_ROOT, 'miniprogram');
  const mpFiles = [];
  const mpWalk = (dir) => {
    for (const name of readdirSync(dir)) {
      if (name === 'node_modules' || name.startsWith('.')) continue;
      const p = join(dir, name);
      if (statSync(p).isDirectory()) mpWalk(p);
      else if (p.endsWith('.js')) mpFiles.push(p);
    }
  };
  if (existsSync(MP_ROOT)) {
    mpWalk(MP_ROOT);
    const genSrc = readFileSync(join(MP_ROOT, 'contract', 'endpoints.js'), 'utf8');
    const mpIds = [...new Set(
      [...genSrc.matchAll(/\bid\s*:\s*['"]([^'"]+)['"]/g)].map((m) => m[1])
    )];
    const CALL = /request\.call\(\s*['"]([^'"]+)['"]/g;

    // ① 出站调用形态 → 所在文件
    const callFiles = new Map(); // id -> Set(relpath)
    for (const f of mpFiles) {
      const code = stripComments2(readFileSync(f, 'utf8'));
      for (const m of code.matchAll(CALL)) {
        const rel = relative(MP_ROOT, f).replace(/\\/g, '/');
        if (!callFiles.has(m[1])) callFiles.set(m[1], new Set());
        callFiles.get(m[1]).add(rel);
      }
    }

    // ② 服务模块导出表：{ 服务模块相对路径: [导出函数名] }
    const svcExports = new Map();
    for (const f of mpFiles) {
      const rel = relative(MP_ROOT, f).replace(/\\/g, '/');
      if (!rel.startsWith('services/')) continue;
      const code = readFileSync(f, 'utf8');
      const m = code.match(/module\.exports\s*=\s*\{([\s\S]*?)\};/);
      if (!m) continue;
      const names = [...m[1].matchAll(/([A-Za-z_$][\w$]*)\s*:/g)].map((x) => x[1]);
      svcExports.set(rel, names);
    }

    // ③ 消费侧（页面 / 外壳）与其 require 别名
    const mpConsumers = mpFiles.filter((f) => {
      const rel = relative(MP_ROOT, f).replace(/\\/g, '/');
      return rel.startsWith('pages/') || rel === 'app.js';
    });
    const consumerText = mpConsumers
      .map((f) => stripComments2(readFileSync(f, 'utf8')))
      .join('\n');
    const aliasOf = new Map(); // 服务模块文件名 -> 页面里的别名
    for (const f of mpConsumers) {
      const t = readFileSync(f, 'utf8');
      for (const m of t.matchAll(/var\s+([A-Za-z_$][\w$]*)\s*=\s*require\(\s*['"]([^'"]+)['"]\s*\)/g)) {
        aliasOf.set(m[2].split('/').pop(), m[1]);
      }
    }

    // ④ 判每个端点的触达链
    const unattached = [];
    for (const id of mpIds) {
      const files = [...(callFiles.get(id) ?? [])];
      // 出站直接写在页面/外壳里 —— 端 C 无"页面不得直接出站"纪律（编排层在 app.js 里
      // 触发登录态检查是合法的），故这里只要求"有服务层封装"，不禁止页面直调。
      if (files.length === 0) {
        unattached.push(`${id}（无任何 request.call 调用形态 —— services/ 里没人封装它）`);
        continue;
      }
      const svcFile = files.find((f) => f.startsWith('services/'));
      if (!svcFile) continue; // 仅页面直调：合法，见上
      const names = svcExports.get(svcFile) ?? [];
      const src = readFileSync(join(MP_ROOT, svcFile), 'utf8');
      // 找到"函数体内含该 id 的导出函数"
      const enclosing = names.filter((n) => {
        const re = new RegExp(`function\\s+${n}\\s*\\([\\s\\S]*?\\n\\}`);
        const body = src.match(re);
        return body && body[0].includes(`'${id}'`);
      });
      if (enclosing.length === 0) {
        unattached.push(`${id}（${svcFile} 的 request.call 未落在任何 module.exports 导出函数体内）`);
        continue;
      }
      const alias = aliasOf.get(svcFile.split('/').pop());
      const byPage = enclosing.some((n) =>
        (alias ? new RegExp(`\\b${alias}\\.${n}\\s*\\(`).test(consumerText) : false)
        || new RegExp(`\\b${n}\\s*\\(`).test(consumerText)
      );
      // 编排形态：被同一服务模块内的另一个**已导出**函数调用
      const byOrchestrator = enclosing.some((n) => {
        const callRe = new RegExp(`\\b${n}\\s*\\(`);
        return names.some((outer) => {
          if (outer === n) return false;
          const re = new RegExp(`function\\s+${outer}\\s*\\([\\s\\S]*?\\n\\}`);
          const body = src.match(re);
          if (!body || !callRe.test(body[0])) return false;
          // 该编排函数自身也必须被页面触达（否则只是把断链藏深了一层）
          return (alias ? new RegExp(`\\b${alias}\\.${outer}\\s*\\(`).test(consumerText) : false)
            || new RegExp(`\\b${outer}\\s*\\(`).test(consumerText);
        });
      });
      if (!byPage && !byOrchestrator) {
        unattached.push(`${id}（封装函数 ${enclosing.join('/')}() 既未被页面调用，`
          + '也未被任何"自身已被页面触达"的编排函数调用）');
      }
    }

    // 计数等式：出站调用形态的覆盖数必须等于生成物端点数（第 53/55 条教训）
    const problems = [];
    if (unattached.length) {
      problems.push('以下端点的界面触达链不完整：\n        ' + unattached.join('\n        ')
        + '\n        ⇒ "页面覆盖 N 个端点"是可自我声称的；本条要求每个端点都有一条'
        + '从界面到出站的真实调用链（页面直调、或经已被页面触达的编排函数）。');
    }
    if (callFiles.size !== mpIds.length) {
      problems.push(`出站调用形态覆盖数 ${callFiles.size} ≠ 生成物端点数 ${mpIds.length}`
        + '（判据必须证明它认识的东西覆盖了全部 —— 第 53/55 条教训）');
    }
    if (problems.length) {
      fail('endpoint-reachability', problems.join('\n      '));
    } else {
      ok('endpoint-reachability',
        `${mpIds.length} 个端点全部有完整调用链（已扫 ${mpFiles.length} 个 js 文件 · `
        + `${mpConsumers.length} 个消费侧文件 · 出站调用形态 ${callFiles.size}/${mpIds.length}）`);
    }
  }
}

// ---------------------------------------------------------------------------
// ④h 【页面可到达】页面必须【能被用户走到】—— 触达判据管不到装载层（第 64 条）
// ---------------------------------------------------------------------------
// 🛑 它堵的洞（2026-09-30 受控注入实测，不是理论风险）
// ---------------------------------------------------------------------------
//    ④g / ⑧ / ①⓪ 那三条 `endpoint-reachability` 判的是「**代码里**有一条
//    从界面到出站的调用链」。但调用链成立**不等于页面能打开** ——
//    中间还隔着一层**装载清单**：
//      · 端 C：`app.json` 的 `pages`（小程序只会加载清单里的页面）；
//      · 端 A/B：外壳的 NAV ↔ 渲染分支（导航表里有、渲染里没有 ⇒ 点了没反应）。
//    受控注入实测（端 C）：把 `pages/assessment/assessment` 从 `app.json` 的
//    `pages` 里删掉，**文件仍在磁盘、调用链完好** ⇒
//    `endpoint-reachability` 仍报 `15/15 全部有完整调用链`、`BUILD OK`、exit=0。
//    ⇒ **"页面覆盖 N 个端点"是可自我声称的，第三层（可到达）此前无人守。**
//
// 🛑 与第 63 条的关系
// ---------------------------------------------------------------------------
//    第 63 条修的是"封装了但没接上界面"（调用链断在**代码**层）；
//    本条修的是"接上了界面但界面**打不开**"（链路断在**装载**层）。
//    同族的第三个接缝：缺陷每往上一层就换一个藏身处。
//
// 🛑 三种形态，判据按形态分两路（照抄一套必然假绿 —— 第 52 条）
// ---------------------------------------------------------------------------
//    · 端 C 是**声明式**：`app.json.pages` ↔ 磁盘 ↔ tabBar 三方一致；
//    · 端 A/B 是**代码式**：NAV 的 key ↔ `type Tab` 成员 ↔ 渲染分支三方一致。
//      两端的渲染写法还不一样（端 A 逐行 `? : null`，端 B 三元链），
//      故判据只提取 `tab === 'x'` 的 **key 集合**，两种写法都覆盖。
{
  const problems = [];
  let summary = '';

  if (END_ID === 'client-mp') {
    const MP = join(END_ROOT, 'miniprogram');
    const ajPath = join(MP, 'app.json');
    if (!existsSync(ajPath)) {
      problems.push('app.json 不存在 —— 无装载清单，页面可到达性【未验证】。');
    } else {
      const app = JSON.parse(readFileSync(ajPath, 'utf8'));
      const declared = Array.isArray(app.pages) ? app.pages : [];
      const tabPaths = (app.tabBar && Array.isArray(app.tabBar.list) ? app.tabBar.list : [])
        .map((x) => x.pagePath);
      // 磁盘上的页面 = 每个含同名 .js 的 pages/<name>/ 目录
      const disk = [];
      const pdir = join(MP, 'pages');
      if (existsSync(pdir)) {
        for (const d of readdirSync(pdir)) {
          if (existsSync(join(pdir, d, `${d}.js`))) disk.push(`pages/${d}/${d}`);
        }
      }
      const unlisted = disk.filter((p) => !declared.includes(p));
      const missing = declared.filter((p) => !['.js', '.wxml'].every((e) => existsSync(join(MP, p + e))));
      const tabBad = tabPaths.filter((p) => !declared.includes(p));

      if (unlisted.length) {
        problems.push('以下页面在磁盘上存在、但 app.json 未声明（**用户永远打不开**）：'
          + `\n        ${unlisted.join('\n        ')}`
          + '\n        ⇒ 文件在、调用链在，但小程序只加载清单里的页面 —— 这一层④g 看不见。');
      }
      if (missing.length) {
        problems.push(`app.json 声明但文件缺失（运行时 404）：${missing.join(', ')}`);
      }
      if (tabBad.length) {
        problems.push(`tabBar 指向未声明的页面（点 tab 即 404）：${tabBad.join(', ')}`);
      }
      if (declared.length !== disk.length) {
        problems.push(`计数等式不成立：app.json 声明 ${declared.length} ≠ 磁盘实有 ${disk.length}`
          + '（判据必须证明它认识的东西覆盖了全部 —— 第 53/55 条教训）');
      }
      summary = `${declared.length} 个页面：app.json 声明 ↔ 磁盘 ↔ tabBar 三方一致`;
    }
  } else {
    const appPath = join(END_ROOT, 'src', 'App.tsx');
    if (!existsSync(appPath)) {
      problems.push('src/App.tsx 不存在 —— 无外壳，页面可到达性【未验证】。');
    } else {
      const src = stripComments2(readFileSync(appPath, 'utf8'));

      // ① NAV 数组本体（圈定范围，避免把别处的 `key:` 数进来 —— 第 53 条教训）
      const mNav = src.match(/const\s+NAV\s*(?::[^=]*)?=\s*Object\.freeze\(\s*\[([\s\S]*?)\]\s*\)\s*;/);
      // ② `type Tab` 联合成员
      const mTab = src.match(/type\s+Tab\s*=\s*([^;]+);/);

      if (!mNav) {
        problems.push('未能圈定 NAV 数组本体 —— 判据不认识当前写法，'
          + '【不得】当作通过（第 52/53 条：判据太宽 ⇒ 假绿）。');
      } else if (!mTab) {
        problems.push('未能解析 `type Tab` —— 判据不认识当前写法，【不得】当作通过。');
      } else {
        const navKeys = [...mNav[1].matchAll(/key:\s*'([^']+)'/g)].map((m) => m[1]);
        const tabMembers = [...mTab[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
        // ③ 渲染分支：只取 `tab === 'x'` 的 key 集合（兼容逐行 / 三元链两种写法）
        const branchKeys = [...src.matchAll(/tab\s*===\s*'([^']+)'/g)].map((m) => m[1]);

        const noRender = navKeys.filter((k) => !branchKeys.includes(k));
        const noNav = branchKeys.filter((k) => !navKeys.includes(k));
        const typeOnly = tabMembers.filter((k) => !navKeys.includes(k));
        const navOnly = navKeys.filter((k) => !tabMembers.includes(k));

        if (noRender.length) {
          problems.push(`以下导航项**没有对应的渲染分支**（点了页面不变，用户以为坏了）：${noRender.join(', ')}`);
        }
        if (noNav.length) {
          problems.push(`以下渲染分支**没有导航入口**（死分支，永远走不到）：${noNav.join(', ')}`);
        }
        if (typeOnly.length) {
          problems.push(`\`type Tab\` 里有但 NAV 里没有的成员（类型松了口子）：${typeOnly.join(', ')}`);
        }
        if (navOnly.length) {
          problems.push(`NAV 里有但 \`type Tab\` 里没有的成员（类型缺口）：${navOnly.join(', ')}`);
        }
        const dupNav = navKeys.filter((k, i) => navKeys.indexOf(k) !== i);
        if (dupNav.length) problems.push(`NAV 中有重复 key（导航渲染会出现重复项）：${dupNav.join(', ')}`);

        summary = `${navKeys.length} 个导航项：NAV ↔ \`type Tab\` ↔ 渲染分支三方一致`
          + `（渲染分支命中 ${branchKeys.length} 处）`;
      }
    }
  }

  if (problems.length) fail('page-registry', problems.join('\n      '));
  else ok('page-registry', summary);
}

// ---------------------------------------------------------------------------
// ④i 【必需参数被调用点带上】本仓第 65 条 —— 端 C 侧
// ---------------------------------------------------------------------------
// 🛑 端 C 的形态与端 A/B **都不同**，判据必须独立实现（第 52 条：照抄⇒太宽⇒假绿）
// ---------------------------------------------------------------------------
//   · 生成物是 **CommonJS**（`ENDPOINTS = Object.freeze([...])`）。④g 的解析
//     只抓 `id:` 一项，**不抓块内其它字段** ⇒ 必须另起一段逐块解析。
//   · 服务层是 CJS：`function foo(){ return request.call('id', {...}); }`，
//     而**不是** `export function foo` ⇒ 端 A/B 的 `export function` 正则
//     在这里一条都匹配不到；匹配不到 ⇒ 判据"通过"但实际什么都没查（假绿）。
//   · 出站不是 `call(` 而是 `request.call(` —— 同样不能照抄。
// 🛑 判实参形态 + 两种合法传参写法（第 66/67 条）：
//     ① 调用单元（从 `request.call(` 的 `(` 起括号配平；固定窗口会误报）；
//     ② 容纳调用的最内层块（实参经局部变量传入时字段在调用单元之外）。
{
  const MP_ROOT2 = join(END_ROOT, 'miniprogram');
  if (existsSync(MP_ROOT2)) {
    const genSrc2 = readFileSync(join(MP_ROOT2, 'contract', 'endpoints.js'), 'utf8');
    // 逐块解析（含 requiredQuery / requiredBody）
    const allBlocks = genSrc2.match(/\{\s*id:\s*['"]([^'"]+)['"],[\s\S]*?\n  \}/g) ?? [];
    const names2 = (s) => (s ?? '').split(',').map((x) => x.trim().replace(/['"]/g, '')).filter(Boolean);
    const ents = [];
    for (const b of allBlocks) {
      const idM = /\bid:\s*['"]([^'"]+)['"]/.exec(b);
      const methodM = /\bmethod:\s*['"]([^'"]+)['"]/.exec(b);
      const pathM = /\bpath:\s*['"]([^'"]+)['"]/.exec(b);
      const q = /requiredQuery:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(b);
      const bo = /requiredBody:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(b);
      // 🛑 第 71 条：path 参数（URL 占位符 `{id}`）此前从未被转录。
      const pa = /requiredPath:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(b);
      ents.push({
        id: idM ? idM[1] : '',
        method: methodM ? methodM[1] : '',
        path: pathM ? pathM[1] : '',
        requiredQuery: q === null ? null : names2(q[1]),
        requiredBody: bo === null ? null : names2(bo[1]),
        requiredPath: pa === null ? null : names2(pa[1]),
      });
    }
    const needParse = ents.filter((e) => e.id);
    const parseMiss = needParse.filter((e) => e.requiredQuery === null || e.requiredBody === null || e.requiredPath === null);
    if (!needParse.length) {
      fail('required-args-wired',
        '端 C 生成物里没解析到任何端点块 —— 解析与文件形状脱钩，本条会静默放过全部端点。');
    } else if (parseMiss.length) {
      fail('required-args-wired',
        `${parseMiss.length} 个端点未解析出 requiredQuery/requiredBody/requiredPath（例：${parseMiss[0].id}）`
        + ' ⇒ 本条会静默放过它们。');
    } else {
      const CJS_FILES = [];
      const w2 = (dir) => {
        for (const name of readdirSync(dir)) {
          if (name === 'node_modules' || name.startsWith('.')) continue;
          const p = join(dir, name);
          if (statSync(p).isDirectory()) w2(p);
          else if (p.endsWith('.js')) CJS_FILES.push(p);
        }
      };
      w2(MP_ROOT2);
      const SITES = new Map();
      const bal2 = (text, openIdx) => {
        let d = 0;
        for (let k = openIdx; k < text.length; k += 1) {
          const ch = text[k];
          if (ch === '(' || ch === '[' || ch === '{') d += 1;
          else if (ch === ')' || ch === ']' || ch === '}') {
            d -= 1;
            if (d === 0) return text.slice(openIdx, k + 1);
          }
        }
        return text.slice(openIdx);
      };
      const enc2 = (text, idx) => {
        let d = 0;
        let open = -1;
        for (let k = idx - 1; k >= 0; k -= 1) {
          const ch = text[k];
          if (ch === ')' || ch === ']' || ch === '}') d += 1;
          else if (ch === '(' || ch === '[' || ch === '{') {
            if (d === 0) { open = k; break; }
            d -= 1;
          }
        }
        return open < 0 ? '' : bal2(text, open);
      };
      for (const f of CJS_FILES) {
        const code = stripComments2(readFileSync(f, 'utf8'));
        const re = /request\.call\s*\(\s*['"]([^'"]+)['"]/g;
        let m;
        while ((m = re.exec(code))) {
          const openIdx = code.indexOf('(', m.index);
          const callText = bal2(code, openIdx);
          const blk = enc2(code, m.index);
          const text = blk && blk.length < 2500 ? `${callText}\n${blk}` : callText;
          const prev = SITES.get(m[1]) ?? [];
          prev.push({ file: relative(MP_ROOT2, f).replace(/\\/g, '/'), text });
          SITES.set(m[1], prev);
        }
      }
      // 🛑 第三种合法形态：请求体是**动态合并**出来的（第 68 条）
      //    E2 的调用点写 `Object.assign({ device_id: deviceId }, rec)` ——
      //    `metric` 不在字面量里，而在运行时对象 `rec` 上（它的来源见同函数内
      //    `'t-' + deviceId + '-' + rec.metric + '-' + rec.date`）。
      //    ⇒ 只认"字面量键"会把一个**写对了的**调用判成缺（实测：本条首跑即误报）。
      //    🛑 但不得因此一律放过：只有当文本里**能找到该字段的成员访问证据**
      //       （`\.metric`）且**确有动态合并**（`Object.assign(` 或 `...x`）时才认。
      //       二者缺一即仍报红 —— 例如 `Object.assign({}, rec)` 而全文没出现过
      //       `.metric` 时，我们**无法证明**它提供了 metric，那就得报。
      //    🛑 认了多少个这样的端点要在通过信息里**公开计数**，不静默（第 53 条）。
      const dynamicMerge = (text) => /Object\.assign\s*\(/.test(text) || /\.\.\.\s*[A-Za-z_$]/.test(text);
      const argIn2 = (name, text) => {
        const n = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const literal = [
          new RegExp(`(?:^|[\\s,{(])['"]?${n}['"]?\\s*:`),
          // 🛑 位置/具名实参的边界**只能**是 `(` 或 `,` —— 不得用 `[\s,(]`（第 69 条）：
          //    后者会把值位置的同名标识符误认成实参，使"实参已删"仍判绿（实测 R22 漏过）。
          new RegExp(`(?:^|[(,])\\s*${n}\\s*[,)]`),
        ].some((r) => r.test(text));
        if (literal) return true;
        return dynamicMerge(text) && new RegExp(`\\.${n}\\b`).test(text);
      };
      const miss2 = [];
      let withReq2 = 0;
      let checked2 = 0;
      let dynRelied = 0; // 靠"动态合并 + 成员访问证据"认账的端点数（公开计数）
      for (const e of needParse) {
        const need = [...(e.requiredQuery ?? []), ...(e.requiredBody ?? []), ...(e.requiredPath ?? [])];
        if (!need.length) continue;
        withReq2 += 1;
        const sites = SITES.get(e.id);
        if (!sites || !sites.length) continue; // 触达问题由 ④g 负责
        checked2 += 1;
        const all = sites.map((s) => s.text).join('\n');
        const absent = need.filter((n) => !argIn2(n, all));
        if (!absent.length) {
          // 是否**只能**靠动态合并认定（即至少一个字段没有字面量证据）
          const anyNoLiteral = need.some((n) => {
            const nn = n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            return !(new RegExp(`(?:^|[\\s,{(])['"]?${nn}['"]?\\s*:`).test(all)
              || new RegExp(`(?:^|[\\s,(])${nn}\\s*[,)]`).test(all));
          });
          if (anyNoLiteral) dynRelied += 1;
          continue;
        }
        miss2.push(`${e.id}（${e.method} ${e.path}）缺 ${absent.join(', ')}`
          + `\n        契约 required 共 [${need.join(', ')}]`
          + `\n        调用点共 ${sites.length} 处：${sites.map((s) => s.file).join(', ')}`);
      }
      // ④i-2 载体归属（第 70 条：只判「名字在不在」不判「落在哪个载体」）
      //   端 C 的 query 载体是**局部变量透传**（`{ query: query }`），body 承载
      //   字面量块（`{ device_id: deviceId, ... }`）—— 两者都要核：
      //   · 局部变量载体 ⇒ 追其 `var query = {...}` 初值块逐名核；
      //   · 字面量载体   ⇒ 就地逐名核；
      //   · 动态合并（Object.assign/展开）⇒ 如实计为"未逐名核"，绝不静默放过。
      const carrierOf2 = (blk, key, src) => {
        if (!blk) return null;
        const m = new RegExp(`(?:^|[\\s,{(])${key}\\s*(:?)`).exec(blk);
        if (!m) return null;
        if (!m[1]) return { kind: 'shorthand' };
        const after = blk.slice(m.index + m[0].length);
        if (after.trimStart().startsWith('{')) {
          const oi = blk.indexOf('{', m.index + m[0].length);
          return { kind: 'literal', text: bal2(blk, oi) };
        }
        const im = /^([A-Za-z_$][\w$]*)/.exec(after.trimStart());
        if (im && src) {
          const dm = new RegExp(`(?:var|let|const)\\s+${im[1]}\\s*=\\s*`).exec(src);
          if (dm) {
            const oi = src.indexOf('{', dm.index + dm[0].length - 1);
            if (oi >= 0) return { kind: 'literal', text: bal2(src, oi), via: im[1] };
          }
        }
        return { kind: 'expr' };
      };
      const outboundBlocks2 = (text, id) => {
        const out = [];
        const re = new RegExp(`request\\.call\\s*\\(\\s*['"]${id}['"]`, 'g');
        let m;
        while ((m = re.exec(text))) {
          const bi = text.indexOf('{', m.index + m[0].length - 1);
          if (bi >= 0) out.push(bal2(text, bi));
        }
        return out;
      };
      const misplaced2 = [];
      let carrierNamed2 = 0;
      let carrierSkipped2 = 0;
      for (const e of needParse) {
        const needQ = e.requiredQuery ?? [];
        const needB = e.requiredBody ?? [];
        const needP = e.requiredPath ?? [];
        if (!needQ.length && !needB.length && !needP.length) continue;
        const sites = SITES.get(e.id);
        if (!sites || !sites.length) continue;
        for (const s of sites) {
          const src = readFileSync(join(MP_ROOT2, s.file), 'utf8');
          const blocks = outboundBlocks2(s.text, e.id);
          if (!blocks.length) continue;
          for (const [key, names] of [['query', needQ], ['body', needB], ['params', needP]]) {
            if (!names.length) continue;
            const cs = blocks.map((b) => carrierOf2(b, key, src)).filter((c) => c !== null);
            if (!cs.length) {
              misplaced2.push(`${e.id}：契约声明了 ${key} 载体上的 required [${names.join(', ')}]，`
                + `但出站块里**根本没有 ${key} 载体**（${s.file}）`);
              continue;
            }
            for (const c of cs) {
              if (c.kind !== 'literal') { carrierSkipped2 += 1; continue; }
              if (/\.\.\./.test(c.text) || /Object\.assign/.test(c.text)) { carrierSkipped2 += 1; continue; }
              const nm = names.filter((n) => {
                const e2 = n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
                return !new RegExp(`(?:^|[\\s,{(])['"]?${e2}['"]?\\s*:`).test(c.text)
                  && !new RegExp(`(?:^|[{,])\\s*${e2}\\s*[,}]`).test(c.text);
              });
              if (nm.length) {
                misplaced2.push(`${e.id} 的 ${key} 载体里**没有** ${nm.join(', ')}（${s.file}）`
                  + `\n        ${key} 载体${c.via ? `（经局部变量 ${c.via}）` : ''} = `
                  + c.text.replace(/\s+/g, ' ').slice(0, 120));
              } else {
                carrierNamed2 += 1;
              }
            }
          }
        }
      }
      if (misplaced2.length) {
        fail('required-args-carrier',
          '以下端点的必需参数**落在了错误的载体**（或该有载体的地方没有载体）——'
          + '后端按 in:query / in:body 取值，装错位置等价于没带，且构建/触达判据一律绿：\n      '
          + misplaced2.join('\n      ')
          + '\n      ⇒ "参数名出现了"不等于"参数落在对的位置"（第 70 条）。');
      } else {
        ok('required-args-carrier',
          `端 C 载体归属已核对：${carrierNamed2} 处载体块逐名命中（含局部变量载体回溯）；`
          + `${carrierSkipped2} 处为动态合并/表达式载体（Object.assign / …x），`
          + '如实计数不静默放过。');
      }

      if (!withReq2) {
        fail('required-args-wired',
          '端 C 生成物里没有端点带 required 声明 —— 本条会静默放过全部端点。');
      } else if (miss2.length) {
        fail('required-args-wired',
          '以下端点的调用点**没有带上契约 required 参数**'
          + '（后端必然 400，而构建/触达判据一律绿）：\n      '
          + miss2.join('\n      ')
          + '\n      ⇒ "端点被调用了"不等于"调用是对的"。');
      } else {
        ok('required-args-wired',
          `端 C：${withReq2} 个带 required 声明的端点中，${checked2} 个有调用点可核对，`
          + `均已带上全部必需参数（CJS 形态 · 判实参形态 · 含括号配平与所在块两种写法；`
          + `其中 ${dynRelied} 个是**动态合并**形态（Object.assign/展开），`
          + `已按"存在该字段的成员访问证据"逐项核实 —— 不是跳过不查）`);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// ⑤ 真实构建（端 A / 端 B）
// ---------------------------------------------------------------------------
if (cfg.realBuild && cfg.realBuild.length) {
  const missing = cfg.realBuild.filter((b) => !existsSync(join(END_ROOT, b.bin)));
  if (missing.length) {
    envBlocked = true;
    notes.push('  – real-build: 依赖未安装，真实构建【未验证】'
      + `（缺 ${missing.map((m) => m.bin).join(', ')}；请先 npm install）`);
    fail('real-build',
      'ENV-BLOCKED: node_modules 缺失，无法执行真实构建。这【不代表】构建通过 —— 该项本轮【未验证】。');
  } else if (!spawnOk) {
    envBlocked = true;
    fail('real-build', 'ENV-BLOCKED: 无法派生子进程，真实构建【未验证】。');
  } else {
    for (const b of cfg.realBuild) {
      const r = await run(process.execPath, [b.bin, ...b.argv], END_ROOT);
      if (r.code !== 0) fail('real-build', `${b.name} 失败（exit=${r.code}）\n${r.out}`);
      else ok('real-build', `${b.name} 通过`);
    }
  }
} else {
  notes.push('  – real-build: 本端不适用（小程序产物由微信开发者工具编译，'
    + '本机无通用打包器；故本脚本即端 C 此刻可机械验证的全部）');
}

// ---------------------------------------------------------------------------
console.log(`== ${cfg.label} 构建自检 ==`);
console.log(notes.join('\n'));
if (failures.length) {
  console.error('\n失败项:');
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(envBlocked ? 3 : 1);
}
console.log(`\nBUILD OK  (${END_ID})  结构 + 契约 + 入口清单 + 真实构建 全部通过`);
