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
 * 本脚本覆盖 `build-check.mjs` 的**三条**判据：
 *   · `base-path-wiring`（第 56 条，R1–R6）—— 契约 Base Path（`servers[0].url`）
 *     必须真的进入三端出站 URL；
 *   · `cross-end-protocol`（第 57 条，R7–R10）—— 鉴权头名 / 令牌前缀 / 幂等头名 /
 *     追踪头名 / 信封成功码必须引用生成物 PROTOCOL 常量，不得手抄字面量；
 *   · `pagination-protocol`（第 58 条，R11–R13）—— 分页参数名必须经分页助手取自
 *     生成物 PROTOCOL.PAGINATION，不得手抄；且生成物键值不得漂移。
 *   · `error-data-fields`（第 61 条，R14–R16）—— 契约「不得模糊报错」的机器可读那一半：
 *     出站层必须保留错误响应 data，错误层必须经 PROTOCOL.ERROR_DATA_FIELDS 取字段名，
 *     不得手写 'missing_items' / 'denied_fields' 字面量。
 *   · `endpoint-reachability`（第 63 条，R17–R18）—— 每个端点都必须有一条
 *     从界面到出站的**真实调用链**（端 C 的 CommonJS 形态单列）；
 *   · `page-registry`（第 64 条，R19–R21）—— **装载层**：页面必须真的能被用户
 *     走到（端 C 是 `app.json.pages` ↔ 磁盘 ↔ tabBar，端 A/B 是 NAV ↔ `type Tab`
 *     ↔ 渲染分支）。调用链成立 ≠ 页面能打开。
 *
 * 🛑 为什么在这里做而不再写一次性探针
 * ---------------------------------------------------------------------------
 * 反向验证若只跑一次、跑完即删，那么判据日后被改窄时**没有任何东西会提醒**。
 * 判据与它的反向验证必须同时常驻：前者防代码漂移，后者防**判据本身**漂移。
 *
 * 退出码：0 全部通过（含还原后回绿）；1 有用例未被抓住 或 还原不干净。
 */

import { readFileSync, writeFileSync, existsSync, readdirSync, rmdirSync } from 'node:fs';
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
// 🛑 备份策略：**内存备份**，不用磁盘副本目录（本仓第 61 条顺带修掉的一个缺陷）
//
// 原实现把待变异文件 `copyFileSync` 到 `tools/_reverse_backup/`，跑完再
// `rmSync` 删掉。本机运行环境装了一层**安全删除守卫**（node-safe-delete-shim），
// 它对"单轮累计删除文件数 > 阈值"的动作直接抛错。而反向验证一轮要跑
// 17 组用例 × （注入 + 还原）⇒ 删除量必然把阈值顶穿，于是：
//
//     [safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED]
//     {"count":626,"threshold":50,"scope":"turn","targets":["...\_reverse_backup\R1__..."]}
//
// ⇒ **脚本会死在第 N 组用例的清理语句上**，而这个失败与"判据是否有牙齿"
//   毫无关系。这与第 59 条（会随机变红的断言）同族：随机源是**环境状态**
//   （本轮已经删了多少文件）而不是随机数据，同样会侵蚀门禁可信度。
//
// 改成内存备份后：全程**一次删除都不发生**（不 mkdir、不 copy、不 rm），
// 还原就是一次 `writeFileSync` —— 比磁盘副本更可靠（磁盘副本在进程被杀时
// 会留下残骸，那正是"第 24 条：检验工具把自己的残骸当成了被检对象"的成因）。
// ---------------------------------------------------------------------------
const BACKUPS = new Map(); // absPath -> 原文
function backup(abs) {
  if (!BACKUPS.has(abs)) BACKUPS.set(abs, readFileSync(abs, 'utf8'));
  return BACKUPS.get(abs);
}
function restore(abs) {
  const src = BACKUPS.get(abs);
  if (src === undefined) return false;
  writeFileSync(abs, src, { encoding: 'utf8', newline: '\n' });
  BACKUPS.delete(abs);
  return true;
}

/** 兜底：进程异常退出时把仍未还原的文件写回（内存备份也能自愈）。 */
function restoreAll() {
  for (const [abs, src] of BACKUPS) {
    try { writeFileSync(abs, src, { encoding: 'utf8', newline: '\n' }); } catch { /* 尽力而为 */ }
  }
  BACKUPS.clear();
}
process.on('uncaughtException', (e) => { restoreAll(); throw e; });
process.on('unhandledRejection', (e) => { restoreAll(); throw e; });

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
    return { id, title, ok: false, why: `文件不存在: ${end}/${rel}` };
  }
  const before = backup(abs);
  const after = mutate(before);
  if (after === before) {
    restore(abs);
    // 🛑 `title` 必须带出来：本行此前不带，运行器打印 `${r.id} ${r.title}` 就成了
    //    `R28 undefined` —— 一条**说不出自己是哪条用例**的失败，与第 24 条同族
    //    （"报错不指向真因"）。诊断信息里的 `undefined` 一律视为 bug。
    return { id, title, ok: false, why: '变异未生效（mutate 返回了原文）—— 用例本身失效，须修正' };
  }
  writeFileSync(abs, after, { encoding: 'utf8', newline: '\n' });

  let red;
  try {
    red = await runBuildCheck(end);
  } finally {
    restore(abs);
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

  // --- 第 57 条：跨端协议片段（另三类隐式协议）的反向验证 --------------------
  {
    id: 'R7',
    end: 'admin-web',
    rel: 'src/api/client.ts',
    title: '端 A：鉴权头名回退成手写字面量（不引用生成物常量）',
    expectItem: 'cross-end-protocol',
    mutate: (s) => s.replace(
      /headers\[PROTOCOL\.AUTH_HEADER\] = `\$\{PROTOCOL\.AUTH_SCHEME\} \$\{token\}`;/,
      "headers['Authorization'] = `Bearer ${token}`;"),
  },
  {
    id: 'R8',
    end: 'client-mp',
    rel: 'miniprogram/services/request.js',
    title: '端 C：追踪头名回退成字面量（X-Trace-Id 契约里零声明）',
    expectItem: 'cross-end-protocol',
    mutate: (s) => s.replace(
      /res\.header\[contract\.PROTOCOL\.TRACE_HEADER\]/,
      "res.header['X-Trace-Id']"),
  },
  {
    id: 'R9',
    end: 'therapist-app',
    rel: 'src/api/client.ts',
    title: '端 B：信封成功码回退成字面量 0（不引用生成物常量）',
    expectItem: 'cross-end-protocol',
    mutate: (s) => s.replace(
      /body\.code !== PROTOCOL\.ENVELOPE_OK_CODE/,
      'body.code !== 0'),
  },
  {
    id: 'R10',
    end: 'admin-web',
    rel: 'src/contract/endpoints.ts',
    title: '生成物 PROTOCOL 的头名被改成与契约不一致（值漂移，不是缺键）',
    expectItem: 'cross-end-protocol',
    mutate: (s) => s.replace(/AUTH_HEADER: "Authorization"/, 'AUTH_HEADER: "X-Auth"'),
  },

  // --- 第 58 条：分页协议（越界语义 + 参数名收敛）的反向验证 --------------------
  {
    id: 'R11',
    end: 'admin-web',
    rel: 'src/services/domain.ts',
    title: '端 A：分页参数名回退成手抄字面量（不差分页助手）',
    expectItem: 'pagination-protocol',
    mutate: (s) => s.replace(/query: pageQuery\(page, pageSize\)/,
      'query: { page, page_size: pageSize }'),
  },
  {
    id: 'R12',
    end: 'therapist-app',
    rel: 'src/services/domain.ts',
    title: '端 B：分页参数名回退成手抄（含过滤条件的那种透传形态）',
    expectItem: 'pagination-protocol',
    // 🛑 锚点已于 2026-09-30（Task #115 收口）更新，原因值得记录：
    //    原锚点是 `query: PageQuery & { age_group?: string; dimension?: string } = {}`
    //    —— 那一行是 `listScaleItemBanks` 的旧签名，本轮因它**在类型层面不可调用**
    //    （`PageQuery` 的索引签名 `number|undefined` 与 `age_group?: string` 求交
    //     得到内部矛盾类型）而被改成 `ScaleItemBankQuery`。
    //    签名一变，本注入的 `mutate` 返回原文 ⇒ 用例**静默失效**
    //    （首跑即报「变异未生效」—— 这正是本脚本"注入必须带来可观测差异"那条
    //     自检的价值：它让"用例自己腐烂"变成一条显式失败，而不是假装通过）。
    //    现锚点改为**真实的手抄形态本身**：把分页助手调用换成 `{page: page}` 字面量。
    mutate: (s) => s.replace(
      /query: pageQuery\(page, pageSize\)/,
      'query: { page: page, page_size: pageSize }'),
  },
  {
    id: 'R13',
    end: 'admin-web',
    rel: 'src/services/paging.ts',
    title: '分页助手把 page_size 键名写错（与契约 request-fields[1] 不一致）',
    expectItem: 'pagination-protocol',
    mutate: (s) => s.replace(
      /q\[PROTOCOL\.PAGINATION\.PAGE_SIZE_FIELD\] = pageSize;/,
      "q['pageSize'] = pageSize;"),
  },

  // --- 第 61 条：拒绝响应原因名（不得模糊报错）的反向验证 --------------------
  {
    id: 'R14',
    end: 'admin-web',
    rel: 'src/api/client.ts',
    title: '端 A：出站层又在错误路径丢掉 body.data（用户将看不到原因名）',
    expectItem: 'error-data-fields',
    mutate: (s) => s.replace(/^\s*err\.data = body\.data;\n/m, ''),
  },
  {
    id: 'R15',
    end: 'therapist-app',
    rel: 'src/services/errors.ts',
    title: '端 B：错误层不再经生成物常量取字段名（改回手写 reasons 空数组）',
    expectItem: 'error-data-fields',
    mutate: (s) => s.replace(
      /const field = PROTOCOL\.ERROR_DATA_FIELDS\[code\];/,
      "const field = undefined;"),
  },
  {
    id: 'R16',
    end: 'client-mp',
    rel: 'miniprogram/services/codes.js',
    title: '端 C：字段名回退成手写字面量（契约改字段名时不会跟着改）',
    expectItem: 'error-data-fields',
    mutate: (s) => s.replace(
      /var field = code === null \? null : contract\.PROTOCOL\.ERROR_DATA_FIELDS\[code\];/,
      "var field = code === 2002 ? 'missing_items' : null;"),
  },
  // -------------------------------------------------------------------------
  // 第 63 条 · 端点触达判据（④g）的牙齿证明
  // -------------------------------------------------------------------------
  // 🛑 本轮实测：端 C 的 `authLogin` 有**两份实现**（页面直接出站 + 会话层
  //    已封装但从不被调用）。旧版判据集合**一条都管不到这种事**，
  //    而 `tsc` / 构建 / 全部既有门禁**如实全绿**。
  //    这两组注入把那个形态复刻出来，证明新判据确实承重。
  {
    id: 'R17',
    end: 'client-mp',
    rel: 'miniprogram/pages/login/login.js',
    title: '端 C：登录页绕开会话层、自己直接出站（authLogin 出现第二份实现）',
    expectItem: 'endpoint-reachability',
    // 把收敛后的 `session.login(...)` 换回"页面自己 request.call('authLogin')"，
    // 同时不再调用 session.login ⇒ 判据应报
    // 「authLogin 封装函数 login() 既未被页面调用…」。
    mutate: (s) => s
      .replace(
        /session\.login\(account, credential\)\.then/,
        "session.setToken(''); request.call('authLogin', {}).then")
      .replace(
        /var session = require\('\.\.\/\.\.\/services\/session\.js'\);/,
        "var session = require('../../services/session.js');\nvar request = require('../../services/request.js');"),
  },
  {
    id: 'R18',
    end: 'client-mp',
    rel: 'miniprogram/services/domain.js',
    title: '端 C：删掉 domain.js 里 getPlan 的 services 出站调用',
    expectItem: 'endpoint-reachability',
    mutate: (s) => s.replace(
      /return request\.call\('getPlan', \{ params: \{ id: id \} \}\)\.then\(function \(r\) \{ return r\.data \|\| \{\}; \}\);/,
      'return Promise.resolve({});'),
  },

  // -------------------------------------------------------------------------
  // 🛑 第 64 条：**装载层**（页面可到达）
  //
  //    上面 R17/R18（以及 I9/I10）证明的是「**代码里**有一条从界面到出站的
  //    调用链」。但实测发现链路还能断在**上一层**：把页面从装载清单里摘掉，
  //    文件仍在磁盘、调用链完好、`endpoint-reachability` 仍 15/15 绿。
  //
  //    这三组注入把三种断法逐一复刻（端 C 声明式 / 端 B 分支式 / 端 A 导航式），
  //    证明新增的 `page-registry` 判据确实承重 —— 且**两端形态不同**这件事
  //    必须在用例里体现，否则"一套正则通吃"的假绿不会被抓住。
  // -------------------------------------------------------------------------
  {
    id: 'R19',
    end: 'client-mp',
    rel: 'miniprogram/app.json',
    title: '端 C：把「量表评估」页从 app.json 的 pages 里摘掉（文件仍在磁盘）',
    expectItem: 'page-registry',
    // 摘掉声明 ⇒ 页面文件还在、调用链完好，但小程序**永远不会加载它**。
    mutate: (s) => s.replace(/\s*"pages\/assessment\/assessment",/, ''),
  },
  {
    id: 'R20',
    end: 'therapist-app',
    rel: 'src/App.tsx',
    title: '端 B：把「手环数据」的渲染分支改成走不到的 key（导航有、渲染无）',
    expectItem: 'page-registry',
    // 把分支的 key 改成一个 NAV 里不存在的值 ⇒ 两边同时失配。
    mutate: (s) => s.replace(/(tab === ')band(' \? \()/, '$1bandNotRendered$2'),
  },
  {
    id: 'R21',
    end: 'admin-web',
    rel: 'src/App.tsx',
    title: '端 A：删掉「文书模板」的 NAV 导航项（渲染分支变成走不到的死分支）',
    expectItem: 'page-registry',
    mutate: (s) => s.replace(/\s*\{ key: 'docs', label: '文书模板', requires: 'listDocTemplates' \},/, ''),
  },

  // -------------------------------------------------------------------------
  // 🛑 第 65/68 条：**必需参数**（"端点被调用了" ≠ "调用是对的"）
  //
  //    既有判据只回答"端点有没有被调用"。实测端 A 的 C1/C2 调用点
  //    字段名都在、值全是编造的 ⇒ 后端必然 400，而 tsc/构建/触达判据**一律绿**。
  //    下面两组把两种形态逐一复刻，证明新判据承重。
  //    🛑 两组方向**相反**：R22 是"该抓的必须抓到"，R23 是"不该抓的不得抓到"
  //       —— 后者防的是判据退化成"一律放过动态合并"（那会是第 52 条假绿）。
  // -------------------------------------------------------------------------
  {
    id: 'R22',
    end: 'client-mp',
    rel: 'miniprogram/services/session.js',
    title: '端 C：摘掉 authLogin 的必需实参 credential（调用仍在、实参少一个）',
    expectItem: 'required-args-wired',
    // 🛑 必须是"摘实参"而不是"删调用"：删调用会被 endpoint-reachability 抓住，
    //    那样本用例证明不了"调用还在、就是实参少一个"这个形态（第 65 条原形）。
    mutate: (s) => {
      const anchor = 'credential: credential,';
      if (!s.includes(anchor)) return s;
      return s.replace(anchor, 'credential__removed: credential,');
    },
  },
  {
    id: 'R23',
    end: 'client-mp',
    rel: 'miniprogram/services/band-sync.js',
    title: '端 C：把动态合并的成员访问证据抹掉（判据须报红，不得一律放过）',
    expectItem: 'required-args-wired',
    // 🛑 本组守的是第 68 条的**边界**：E2 的 `metric` 是靠
    //    `Object.assign({ device_id }, rec)` + `rec.metric` 证成的。
    //    若把全文里的 `.metric` 成员访问一并抹掉，判据就**无法证明**
    //    它提供了 metric ⇒ 必须报红。这防的是"改成一律放过动态合并"的假绿。
    mutate: (s) => s.replace(/rec\.metric/g, 'rec.metricRemoved'),
  },
  {
    id: 'R24',
    end: 'client-mp',
    rel: 'miniprogram/services/domain.js',
    title: '端 C：把 C1 的必需 query 参数 age_group 挪出 query 载体（载体错位）',
    expectItem: 'required-args-carrier',
    // 🛑 与 R22 的区别：R22 摘实参（缺名），R24 保留实参但**装错载体**。
    //    原块是 `var query = { age_group: ageGroup }`，载体为局部变量 query。
    //    注入后 query 载体变空、age_group 被挪到另一个变量（模拟"装错位置"）——
    //    名字仍在文件里（⑩b 绿），但 in:query 取值失败 ⇒ 后端必然 400。
    mutate: (s) => {
      const anchor = 'var query = { age_group: ageGroup };';
      if (!s.includes(anchor)) return s;
      return s.replace(anchor, 'var query = {}; var misplaced_age_group = ageGroup;');
    },
  },
  {
    id: 'R25',
    end: 'client-mp',
    rel: 'miniprogram/services/domain.js',
    title: '端 C：摘掉 getCustomer 的 path 占位符实参 id（URL 会拼出字面量 {id}）',
    expectItem: 'required-args-wired',
    // 🛑 第 71 条：path 参数（URL 占位符）此前**从未被转录**（契约用 `$ref` 复用
    //    命名参数 23 处 + 内联 `in: path`，生成器两种都跳过）⇒ 判定"URL 里有没有
    //    被替换"这件事过去没有任何判据在管。漏传会拼出字面量 `{id}` 发出去。
    mutate: (s) => {
      const anchor = "request.call('getCustomer', { params: { id: id } })";
      if (!s.includes(anchor)) return s;
      return s.replace(anchor, "request.call('getCustomer', { params: {} })");
    },
  },
  {
    id: 'R26',
    end: 'client-mp',
    rel: 'package.json',
    title: '端 C：npm 脚本退回**裸调 python**（换个没有 PyYAML 的解释器即 exit 2）',
    expectItem: 'no-bare-python-script',
    // 🛑 这正是本轮实测到的那条：`package.json` 原写
    //    `python ../tools/gen-endpoints.py --check`，而本机 PATH 上的 `python`
    //    没有 PyYAML ⇒ `npm run check:contract` exit 2，提示却是
    //    "MISCONFIGURED: PyYAML is required"（指向"契约不一致"这个错误方向）。
    //    而 **CI 的 frontend job 跑的正是这条命令**。
    //    本条注入把修复后的形态退回缺陷形态，判据必须抓住它。
    mutate: (s) => s.replace(
      /"gen:endpoints":\s*"node \.\.\/tools\/gen-endpoints\.mjs"/,
      '"gen:endpoints": "python ../tools/gen-endpoints.py"'),
  },

  // -------------------------------------------------------------------------
  // 🛑 第 79 条（第二十四类）：**构建依赖的「源」被烤进版本库**。
  //
  //    形态：两份 package-lock.json 的 304 条 resolved 全指向 npmmirror、
  //    gradle-wrapper.properties 的 distributionUrl 指向腾讯云镜像；而 runner 在境外。
  //    修法**不是**把仓库里的镜像改掉（那是中国开发者的真实需要、且本机路径已实测），
  //    而是**在 CI 里显式归一**。于是"归一还在不在"本身必须被守住。
  //
  //    🛑 为什么拆两组而不是合成一组（第 53 条）：npm 侧与 gradle 侧是**两条
  //       互相独立的规则**，合成一组会出现"只有一条真在承重、另一条是装饰"。
  //       下面两组各自只破坏一条，分别证明两条都承重。
  //
  //    🛑 注入目标在 `frontends/` **之外**（仓库根的 workflow）：这不是笔误 ——
  //       这两条规则唯一的证据面就在那里。故用 `../../../` 显式跳出。
  // -------------------------------------------------------------------------
  {
    id: 'R27',
    end: 'client-mp',
    rel: '../../../.github/workflows/build-and-test.yml',
    title: 'CI：删掉 npm 源归一（replace-registry-host）⇒ 锁文件的 npmmirror 又变成实际取包源',
    expectItem: 'ci-dependency-source-normalized',
    // 🛑 只删这一行、**不动** `npm_config_registry`：模拟"有人觉得多此一举顺手删了"。
    //    删掉后 npm 默认的 replace-registry-host=npmjs **不会**替换非官方 host
    //    ⇒ 304 条 resolved 里的 npmmirror 又成为真实取包地址。
    mutate: (s) => s.replace(/^[ \t]*npm_config_replace_registry_host:[ \t]*always[ \t]*\r?\n/m, ''),
  },
  {
    id: 'R28',
    end: 'client-mp',
    rel: '../../../.github/workflows/build-and-test.yml',
    title: 'CI：把 Gradle 发行版源改回腾讯云镜像 ⇒ 境外 runner 又去拉第三方 CDN',
    expectItem: 'ci-dependency-source-normalized',
    // 🛑 锚点里的反斜杠是**两个**（工作流那一行给 shell 看的就是 `https\\://`，
    //    因为 sed 的替换段里 `\\` 才会输出一个 `\`）—— 故这里必须用 `String.raw`。
    //    🛑 第一版写成普通单引号串 `'…https\\://…'`，JS 把 `\\` 解析成**一个** `\`
    //    ⇒ 搜索串与文件字面不匹配 ⇒ mutate 返回原文 ⇒ 用例**静默失效**
    //    （报的是「变异未生效」）。这与 R12 注释里记的那次**同型**：
    //    注入锚点一旦与文件字面不符，用例自己就先腐烂了，而它的表现是"漏过"，
    //    读起来像"判据没牙齿"。⇒ 锚点必须逐字取自文件，且**转义层数要对齐**。
    mutate: (s) => s.replace(
      String.raw`distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip`,
      String.raw`distributionUrl=https\\://mirrors.cloud.tencent.com/gradle/gradle-9.3.1-bin.zip`),
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
    // 第 24 条（复发）：只报 exit=1 而不报"哪个判据红了"，会把排查带向
    // "是不是我改了代码"，而真实原因常是环境/并发/残骸。故这里必须把
    // build-check 的失败明细原样带出。
    //
    // 🛑 一处初版自己犯的错（值得记录）：第一版只挑「含 ✗ / FAIL / ERROR /
    //    BUILD 字样」的行 —— 而 vite / tsc 的真实报错行**一个这类字样都没有**
    //    （如 `EPERM: operation not permitted, ...`），于是"明细"打印出来的
    //    全是 ✓ 行 + 一句 `✗ real-build: vite build 失败`，**等于没打印**。
    //    判"该打印哪些行"不能用关键词白名单，只能排除已知噪音行。
    const detail = r.out
      .split('\n')
      .filter((l) => !l.includes('✓ structure'))
      .join('\n    ');
    process.stdout.write(`\n  ✗ ${end} 基线不是绿的（exit=${r.code}）—— 先修好它再跑反向验证\n`);
    if (detail.trim()) {
      process.stdout.write(`    明细（build-check 原始输出，已剔结构行）:\n    ${detail}\n`);
    } else {
      process.stdout.write(`    （build-check 未打印任何行，原始输出长度=${r.out.length}）\n`);
    }
  }
}
process.stdout.write(baselineOk ? '绿 ✓\n\n' : '');
if (!baselineOk) {
  restoreAll();
  process.exit(1);
}

process.stdout.write('===== 构建自检 · base-path-wiring + cross-end-protocol + pagination-protocol + error-data-fields 反向验证（注入 → 必须变红 → 还原 → 必须变绿）=====\n');
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
restoreAll();
const finalCodes = {};
for (const end of Object.keys(ENDS)) {
  const r = await runBuildCheck(end);
  finalCodes[end] = r.code;
}
const allGreen = Object.values(finalCodes).every((c) => c === 0);
const passed = results.filter((r) => r.ok).length;

// 🛑 一处刻意设计（第 24 条族）：本版已改为**内存备份**，全程不创建
//    `_reverse_backup/`。故这里对它的判定**不能**当硬失败 —— 否则**旧版本
//    遗留的一个空目录**就会让门禁报"异常 ✗"，而这个红与"判据有没有牙齿"
//    毫无关系（读起来还像"残留没清干净"，把人带偏）。正确做法：只做诊断。
//    - 目录存在且为空 → 尽力 rmdir（安全），打印一行提示，不改判定；
//    - 目录存在且**非空** → 说明某次旧版运行崩在还原前，这时才值得提醒，
//      但仍不改判定（真正的判定是"三端还原后是否回绿"）。
let staleNotice = '';
if (existsSync(BACKUP_DIR)) {
  const stale = readdirSync(BACKUP_DIR);
  if (stale.length === 0) {
    try { rmdirSync(BACKUP_DIR); staleNotice = '\n（提示：清掉了旧版本遗留的空目录 tools/_reverse_backup/ —— 本版已改为内存备份，不再创建它）'; }
    catch { staleNotice = '\n（提示：存在旧版本遗留的空目录 tools/_reverse_backup/，可手动删除）'; }
  } else {
    staleNotice = `\n（提示：tools/_reverse_backup/ 里有 ${stale.length} 个旧版本遗留的备份文件 `
      + `—— 某次旧版运行崩在还原前。本版不依赖它，可手动删除；若担心源码被改，请核对 git status）`;
  }
}

process.stdout.write(`\n合计 ${passed}/${results.length}\n`);
process.stdout.write(`还原后三端构建自检：${Object.entries(finalCodes).map(([e, c]) => `${e}=${c}`).join(' · ')}`
  + `${allGreen ? '（全绿 ✓，已按内存备份还原）' : '（异常 ✗）'}${staleNotice}\n`);

if (passed === results.length && allGreen) {
  process.stdout.write('\nbuild-check 反向验证 PASS —— 九条判据（base-path-wiring / cross-end-protocol / pagination-protocol / error-data-fields / no-bare-python-script / endpoint-reachability / page-registry / required-args-wired / required-args-carrier）确实有牙齿，且还原干净。\n');
  process.exit(0);
}
process.stdout.write('\nbuild-check 反向验证 FAIL。\n');
process.exit(1);