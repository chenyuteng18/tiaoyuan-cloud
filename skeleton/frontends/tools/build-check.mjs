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

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = resolve(HERE, '..');
const SKELETON_ROOT = resolve(FRONTENDS, '..');
const COMPLIANCE = join(SKELETON_ROOT, 'compliance');

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
      { name: 'vite build', bin: 'node_modules/vite/bin/vite.js', argv: ['build'] },
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
      // 应用层
      'src/services/token-store.ts',
      'src/services/session.ts',
      'src/services/errors.ts',
      'src/services/domain.ts',
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
      'src/pages/UnsettledPage.tsx',
    ],
    scanWords: false,
    scanRoots: [],
    scanExt: /\.(ts|tsx)$/i,
    outbound: 'src/api/client.ts',
    envFile: 'src/env/index.ts',
    realBuild: [
      { name: 'tsc --noEmit', bin: 'node_modules/typescript/bin/tsc', argv: ['--noEmit'] },
      { name: 'vite build', bin: 'node_modules/vite/bin/vite.js', argv: ['build'] },
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
 * 找一个能 `import yaml` 的 Python。
 * 🛑 Windows PATH 里的 `python` 可能是 Microsoft Store App Execution Alias
 *    （0 字节 stub）⇒ 跑起来即报错。故逐个候选**实测**，不只看 which。
 */
async function resolvePython() {
  const candidates = [process.env.DY_PY].filter(Boolean);
  const home = process.env.USERPROFILE || process.env.HOME || '';
  const versionsDir = home ? join(home, '.workbuddy', 'binaries', 'python', 'versions') : '';
  if (versionsDir && existsSync(versionsDir)) {
    for (const v of readdirSync(versionsDir).sort().reverse()) {
      candidates.push(join(versionsDir, v, process.platform === 'win32' ? 'python.exe' : 'bin', 'python'));
    }
  }
  candidates.push('python3', 'python', 'py');
  for (const cand of candidates) {
    const r = await run(cand, ['-c', 'import yaml; print("yaml-ok")'], HERE);
    if (r.code === 0 && r.out.includes('yaml-ok')) return cand;
  }
  return null;
}

// ---------------------------------------------------------------------------
// ① 工程结构
// ---------------------------------------------------------------------------
for (const rel of cfg.required) {
  if (!existsSync(join(END_ROOT, rel))) fail('structure', `缺少 ${rel}`);
  else ok('structure', rel);
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
    + '        python frontends/tools/gen-endpoints.py --check');
} else {
  const PY = await resolvePython();
  if (!PY) {
    envBlocked = true;
    fail('contract', '找不到可执行的 Python（且需含 PyYAML）。可用环境变量 DY_PY 指定。该项【未验证】。');
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
        + '      请先跑 python frontends/tools/gen-endpoints.py（生成器会读 servers）。');
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
