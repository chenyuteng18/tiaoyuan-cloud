#!/usr/bin/env node
/**
 * 三端前端工程 · 构建自检的【反向验证】（零第三方依赖）
 * ============================================================================
 *
 * 用法
 *   node frontends/tools/build-reverse-check.mjs
 *
 * 🛑 这个脚本存在的理由（本仓的固定纪律）
 * ---------------------------------------------------------------------------
 * 门禁全绿只是"没抓到问题"，**不等于"能抓到问题"**。一个写坏的正则、
 * 一个被剥掉的注释、一处太窄的形态假设，都会让门禁**恒绿**——而它看上去
 * 和"代码是对的"完全一样。故每条判据都必须做反向验证：
 *   **注入一个真实的缺陷 → 判据必须变红 → 还原 → 必须变绿。**
 *
 * 本脚本专门覆盖 `build-check.mjs` 的 `base-path-wiring` 判据（第 56 条）：
 * 契约 Base Path（`servers[0].url = /api/v1`）必须真的进入三端出站 URL。
 *
 * 🛑 为什么在这里做而不再写一次性探针
 * ---------------------------------------------------------------------------
 * 反向验证若只跑一次、跑完即删，那么判据日后被改窄时**没有任何东西会提醒**。
 * 判据与它的反向验证必须同时常驻：前者防代码漂移，后者防**判据本身**漂移。
 *
 * 退出码：0 全部通过（含还原后回绿）；1 有用例未被抓住 或 还原不干净。
 */

import { readFileSync, writeFileSync, existsSync, copyFileSync, mkdirSync, rmSync, readdirSync } from 'node:fs';
import { join, dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = resolve(HERE, '..');
const BACKUP_DIR = join(HERE, '_reverse_backup');

const ENDS = {
  'client-mp': '端 C · 客户小程序',
  'therapist-app': '端 B · 调理师 / 经络师 APP',
  'admin-web': '端 A · 管理员 Web',
};

// ---------------------------------------------------------------------------
// 自愈：清掉上一次被中断留下的备份（本仓第 24 条：残留物会让下次跑报一个
//      看不出真实原因的红）
// ---------------------------------------------------------------------------
if (existsSync(BACKUP_DIR)) {
  rmSync(BACKUP_DIR, { recursive: true, force: true });
  process.stdout.write('自愈：清掉上一次遗留的 _reverse_backup/\n');
}
mkdirSync(BACKUP_DIR, { recursive: true });

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

async function runBuildCheck(end) {
  return run(process.execPath, [join(HERE, 'build-check.mjs'), `--end=${end}`], join(FRONTENDS, end));
}

/** 备份 → 变异 → 断言变红且命中指定判据 → 还原 → 断言回绿。 */
async function caseInject({ id, end, rel, title, mutate, expectItem }) {
  const abs = join(FRONTENDS, end, rel);
  if (!existsSync(abs)) {
    return { id, ok: false, why: `文件不存在: ${end}/${rel}` };
  }
  const backup = join(BACKUP_DIR, `${id}__${rel.replace(/[\\/]/g, '__')}`);
  copyFileSync(abs, backup);

  const before = readFileSync(abs, 'utf8');
  const after = mutate(before);
  if (after === before) {
    copyFileSync(backup, abs);
    return { id, ok: false, why: '变异未生效（mutate 返回了原文）—— 用例本身失效，须修正' };
  }
  writeFileSync(abs, after, { encoding: 'utf8', newline: '\n' });

  let red;
  try {
    red = await runBuildCheck(end);
  } finally {
    copyFileSync(backup, abs);
    rmSync(backup, { force: true });
  }

  const caught = red.code !== 0 && red.out.includes(expectItem);
  const green = await runBuildCheck(end);
  const restored = green.code === 0;

  return {
    id,
    end,
    title,
    ok: caught && restored,
    detail: `注入后 exit=${red.code}，命中「${expectItem}」=${red.out.includes(expectItem)}；`
      + `还原后 exit=${green.code}`,
  };
}

// ---------------------------------------------------------------------------
// 反向用例（每条都是"真实会发生的缺陷"，不是臆造）
// ---------------------------------------------------------------------------
const CASES = [
  {
    id: 'R1',
    end: 'admin-web',
    rel: 'src/api/client.ts',
    title: '端 A：出站回退成裸 baseUrl 拼 path（丢契约 Base Path）',
    expectItem: 'base-path-wiring',
    mutate: (s) => s.replace(
      /getRequestBaseUrl\(\)\s*\+\s*fillPath/,
      'getBaseUrl() + fillPath'),
  },
  {
    id: 'R2',
    end: 'client-mp',
    rel: 'miniprogram/services/request.js',
    title: '端 C：出站回退成裸 ENV.baseUrl（丢契约 Base Path）',
    expectItem: 'base-path-wiring',
    mutate: (s) => s.replace(
      /ENV\.requestBaseUrl\s*\+\s*fillPath/,
      'ENV.baseUrl + fillPath'),
  },
  {
    id: 'R3',
    end: 'therapist-app',
    rel: 'src/env/index.ts',
    title: '端 B：出站前缀改成手写字面量（不再取自生成物）',
    expectItem: 'base-path-wiring',
    mutate: (s) => s.replace(
      /return resolveBaseUrl\(\)\s*\+\s*API_BASE_PATH;/,
      'return resolveBaseUrl() + "/api/v1";'),
  },
  {
    id: 'R4',
    end: 'therapist-app',
    rel: 'src/contract/endpoints.ts',
    title: '生成物丢掉 API_BASE_PATH（生成器不再转录契约 servers）',
    expectItem: 'base-path-wiring',
    mutate: (s) => s.replace(/\n?export const API_BASE_PATH: string = "[^"]*";\n?/, '\n'),
  },
  {
    id: 'R5',
    end: 'admin-web',
    rel: 'src/env/index.ts',
    title: '端 A：环境层不再导出出站前缀（回退到只有网关根）',
    expectItem: 'base-path-wiring',
    mutate: (s) => s.replace(
      /export function getRequestBaseUrl\(\): string \{/,
      'function getRequestBaseUrl(): string {'),
  },
  {
    id: 'R6',
    end: 'therapist-app',
    rel: 'vite.config.ts',
    title: '端 B：dev 代理键回退成 /api（请求不进代理规则 ⇒ 全量 404）',
    expectItem: 'base-path-wiring',
    mutate: (s) => s.replace(/['"]\/api\/v1['"]\s*:/, "'/api':"),
  },
];

// ---------------------------------------------------------------------------
// 基线：三端构建自检必须先是绿的（否则"变红"无从判定）
// ---------------------------------------------------------------------------
process.stdout.write('基线：三端构建自检 ');
let baselineOk = true;
for (const end of Object.keys(ENDS)) {
  const r = await runBuildCheck(end);
  if (r.code !== 0) {
    baselineOk = false;
    process.stdout.write(`\n  ✗ ${end} 基线不是绿的（exit=${r.code}）—— 先修好它再跑反向验证\n`);
  }
}
process.stdout.write(baselineOk ? '绿 ✓\n\n' : '');
if (!baselineOk) {
  rmSync(BACKUP_DIR, { recursive: true, force: true });
  process.exit(1);
}

process.stdout.write('===== 构建自检 · base-path-wiring 反向验证（注入 → 必须变红 → 还原 → 必须变绿）=====\n');
const results = [];
for (const c of CASES) {
  const r = await caseInject(c);
  results.push(r);
  const label = r.ok ? '  ✓ 抓住' : '  ✗ 漏过';
  process.stdout.write(`${label}  ${r.id} ${r.title}\n`);
  process.stdout.write(`           ${r.detail}${r.why ? `｜${r.why}` : ''}\n`);
}

// ---------------------------------------------------------------------------
// 收尾：必须还原干净、且三端回绿
// ---------------------------------------------------------------------------
rmSync(BACKUP_DIR, { recursive: true, force: true });
const leftovers = readdirSync(HERE).filter((n) => n.startsWith('_reverse_backup'));
const finalCodes = {};
for (const end of Object.keys(ENDS)) {
  const r = await runBuildCheck(end);
  finalCodes[end] = r.code;
}
const allGreen = Object.values(finalCodes).every((c) => c === 0);
const passed = results.filter((r) => r.ok).length;

process.stdout.write(`\n合计 ${passed}/${results.length}\n`);
process.stdout.write(`还原后三端构建自检：${Object.entries(finalCodes).map(([e, c]) => `${e}=${c}`).join(' · ')}`
  + `${allGreen && leftovers.length === 0 ? '（全绿 ✓，无残留）' : '（异常 ✗）'}\n`);

if (passed === results.length && allGreen && leftovers.length === 0) {
  process.stdout.write('\nbase-path-wiring 反向验证 PASS —— 判据确实有牙齿，且还原干净。\n');
  process.exit(0);
}
process.stdout.write('\nbase-path-wiring 反向验证 FAIL。\n');
process.exit(1);