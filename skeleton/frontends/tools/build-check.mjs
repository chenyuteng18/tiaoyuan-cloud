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
    // 小程序产物由微信开发者工具编译，本机没有通用打包器 ⇒ 不做"真实构建"这一项。
    realBuild: null,
  },
  'therapist-app': {
    label: '端 B · 调理师 / 经络师 APP',
    contractKey: 'therapist-app',
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
    ],
    // 🛑 端 B / 端 A 刻意【不】套用端 C 的禁用词自检：
    //    compliance/wordlists/scan1_refund.words 的 SCOPE 段逐字声明本面只扫客户端包，
    //    管理端 / 经络师端的退款措辞是**合法的内部业务词汇**。
    //    不做这一项不是"少一道检查"，而是词表的适用范围本就如此界定。
    scanWords: false,
    scanRoots: [],
    scanExt: /\.(ts|tsx)$/i,
    realBuild: [
      { name: 'tsc --noEmit', bin: 'node_modules/typescript/bin/tsc', argv: ['--noEmit'] },
      { name: 'vite build', bin: 'node_modules/vite/bin/vite.js', argv: ['build'] },
    ],
  },
  'admin-web': {
    label: '端 A · 管理员 Web',
    contractKey: 'admin-web',
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
    ],
    scanWords: false,
    scanRoots: [],
    scanExt: /\.(ts|tsx)$/i,
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
