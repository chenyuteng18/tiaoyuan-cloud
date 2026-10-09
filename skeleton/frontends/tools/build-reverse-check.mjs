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
 * 本脚本覆盖 `build-check.mjs` 的**十四条**跨端共享判据（逐条见下）：
 *   🛑 此处长期写「**三条**」而下面列了十四条 —— 一处典型的**计数锚点滞后**：
 *      它自称"三条"却逐条列出了十四条，读的人会以为下面那串是"补充说明"。
 *      与"数字锚点必须有出处"同族；第 88 条一并订正为与实际一致。
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
 *   · `required-args-wired` / `required-args-carrier`（第 65 条，R22–R25）—— 带
 *     `required` 声明的端点，其调用点必须**真的**把必需参数传进去（判**实参形态**，
 *     非判词出现 —— 第 52 条）；端 C 走 CJS 形态单列。
 *   · `no-bare-python-script`（第 77 条，R26）· `ci-dependency-source-normalized`
 *     （第 79 条，R27–R28）· `ci-mvn-jobs-have-python`（第 80 条，R29）。
 *   · `token-single-source`（第 84 条，R30 + R35）—— 令牌键名只能有**一处**定义；
 *     R35 额外证明"**覆盖面自证**"分支有牙齿（判据的权威点候选清单被改坏时必须报红，
 *     而**不得**退化成"本端不适用"——那正是本条抓到的原缺陷形态）。
 *   · `page-reachability`（第 84 条同批，R31；第 86 条扩到端 A / 端 B，R36–R40）——
 *     端 C：已声明的页面必须有**外部**入口（本页自身文件里的自引用不算入口）；
 *     端 A / 端 B：`src/pages/*.tsx` 必须既登记在 `ENDS.required`、又真的在
 *     `src/App.tsx` 里以 JSX 形态被渲染。**同一条判据的三个子规则各有自己的注入**：
 *       R31 → 端 C 的"外部入口"；R36 / R40 → 子规则①+②（新增孤儿文件，两端各一）；
 *       R37 → 子规则②单独（删 JSX + 删 import，隔离掉 tsc 与 ④h）；
 *       R38 → 子规则①单独（从清册里删一项）；R39 → 子规则③覆盖面自证（改坏解析）。
 *   · `error-code-coverage`（第 85 条，R32–R34）—— 契约 `x-error-codes` 的每个 code
 *     都必须有本端文案，且不得有表外码；冻结契约与裁剪契约必须逐条一致。
 *   · `role-scope`（第 87 条，R41–R44）—— 生成物 `grantedRoles` 必须等于
 *     「契约 `x-callable-roles` ∩ 本端 `END_TOKEN_ROLES`」（**双向等式**：无缺项、无越权）。
 *     R41/R42 注入**契约侧**（空交集 · 越权）· R43 注入**生成物侧**（缺项）·
 *     R44 注入**判据自身的解析**（覆盖面自证）。
 *   · `words`（第 88 条，R45–R47）—— ADR-12 合规扫描的**本地通道**：扫描根必须
 *     全部存在、总数必须非零、且与 manifest 声明的 `frontends/*` 根**双向咬合**。
 *     R45 注入"根被改名"（此前静默丢根、扫描 0 文件仍 ✓）· R46 注入"扩展名匹配被改坏"·
 *     R47 注入"manifest 侧根被改名"（本地↔CI 覆盖面漂移）。
 *
 * 🛑 注入的**两种形状**（第 86 条新增第二种）
 * ---------------------------------------------------------------------------
 *   · 「改写既有文件」：`rel` + `mutate` —— 绝大多数用例；
 *   · 「**新增一个文件**」：`create` —— 第 45 / 53 / 86 条那一类缺陷的形态恰恰是
 *     "新文件出现了、没有任何登记跟上"。此前框架表达不出这个形状，只能挑近似形态
 *     顶替（第 55 条：判据与缺陷不咬合）。R36 / R40 走这一支。
 *
 * 🛑 为什么在这里做而不再写一次性探针
 * ---------------------------------------------------------------------------
 * 反向验证若只跑一次、跑完即删，那么判据日后被改窄时**没有任何东西会提醒**。
 * 判据与它的反向验证必须同时常驻：前者防代码漂移，后者防**判据本身**漂移。
 *
 * 退出码：0 全部通过（含还原后回绿）；1 有用例未被抓住 或 还原不干净。
 */

import { readFileSync, writeFileSync, existsSync, readdirSync, rmdirSync, unlinkSync } from 'node:fs';
import { join, dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { journalRecover, journalStash, journalCommit, journalClose } from './_gate-common.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = resolve(HERE, '..');
const BACKUP_DIR = join(HERE, '_reverse_backup');

// ---------------------------------------------------------------------------
// 🛑 第 90 条 · 第三十三类：**被改写的既有文件**的残骸自愈
// ---------------------------------------------------------------------------
// 本文件全程用**内存备份**（第 61 条），因此进程被强杀（SIGKILL / 任务管理器 /
// 上层超时）时，备份随内存一起消失，而那条"被改写的源文件"就**永久留在注入态**。
// 2026-10-09 实测：强杀本脚本（正跑到 R5）后，`therapist-app/vite.config.ts` 的
// dev 代理键被留成 `/api` ⇒ 端 B 的 `base-path-wiring` 报红，而真实原因是
// **一次被中断的测试运行**，不是源码缺陷。
//   ⇒ 故每一次备份都**同时落盘**到 `tools/_reverse_journal.json`；
//      启动时若发现上一轮留下的日志，**先还原、再大声说明**，然后继续跑。
//   🛑 必须**在基线检查之前**做 —— 否则基线只会报"端 B 是红的，先修好再跑"，
//      把排查方向指向源码，而真相是残骸（第 24 条同族）。
{
  const rec = journalRecover(FRONTENDS);
  if (rec && rec.corrupted) {
    process.stdout.write(
      `\n⚠️ 上一轮的反向验证被强杀，留下了残骸日志 ${'tools/_reverse_journal.json'}，`
      + `但它**解不开**（${rec.corrupted}）。\n`
      + '   ⇒ 无法自动还原。请核对 `git status`：被改写的源文件会以"已修改"出现，\n'
      + '     用 `git diff` 逐一看过后 `git checkout --` 还原（**先看再还原**，别丢掉你自己的改动）。\n');
  } else if (rec && rec.restored.length) {
    process.stdout.write(
      `\n⚠️ 上一轮的反向验证**被强杀**，留下 ${rec.restored.length} 个处于注入态的源文件。`
      + `已在开跑前自动还原：\n`
      + rec.restored.map((f) => `     - ${f}`).join('\n') + '\n'
      + '   ⇒ 这正是第 90 条的形态：内存备份挡不住 SIGKILL，故备份同时落盘。\n'
      + '     （若你此前在这几个文件上有**自己的未提交改动**，请 `git diff` 核对一遍。）\n\n');
  }
}
// 正常退出（含 process.exit）时清掉日志；被强杀时**故意不清** —— 那正是要留给下一轮看的东西。
process.on('exit', () => { try { journalClose(FRONTENDS); } catch { /* 尽力而为 */ } });

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
  if (!BACKUPS.has(abs)) {
    const src = readFileSync(abs, 'utf8');
    BACKUPS.set(abs, src);
    // 第 90 条：**同时落盘**。内存备份挡不住 SIGKILL，日志能。
    journalStash(FRONTENDS, abs, src);
  }
  return BACKUPS.get(abs);
}
function restore(abs) {
  const src = BACKUPS.get(abs);
  if (src === undefined) return false;
  writeFileSync(abs, src, { encoding: 'utf8', newline: '\n' });
  BACKUPS.delete(abs);
  journalCommit(FRONTENDS, abs); // 已还原 ⇒ 从残骸日志里摘掉
  return true;
}

// 🛑 2026-10-09（第 86 条）：**"新增一个文件"型注入**
// ---------------------------------------------------------------------------
// 本仓第 45 / 53 / 86 条那一类缺陷的形态**恰恰就是**"新文件出现了，没有任何
// 登记跟上"（第 86 条那个孤儿页面就是这么来的）。而本框架此前只会"改写既有
// 文件" ⇒ **用例表达不出真实缺陷**，只能挑一个近似形态顶替 —— 那正是第 55 条
// 警告的"判据与缺陷不咬合"。
// 故补 `create`：新增一个文件 → 跑 → 删掉 → 再跑。删除走 `unlinkSync` 且
// 登记在 `CREATED` 里，进程异常退出时由 `restoreAll()` 兜底清掉。
const CREATED = new Set(); // 本框架**新建**的文件（还原 = 删除）
function cleanupCreate(abs) {
  if (!CREATED.has(abs)) return false;
  try { if (existsSync(abs)) unlinkSync(abs); } catch { /* 尽力而为 */ }
  CREATED.delete(abs);
  return true;
}

/** 兜底：进程异常退出时把仍未还原的文件写回、并把新建的文件删掉。 */
function restoreAll() {
  for (const [abs, src] of BACKUPS) {
    try {
      writeFileSync(abs, src, { encoding: 'utf8', newline: '\n' });
      journalCommit(FRONTENDS, abs); // 已还原 ⇒ 从残骸日志里摘掉
    } catch { /* 尽力而为 */ }
  }
  BACKUPS.clear();
  for (const abs of [...CREATED]) cleanupCreate(abs);
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
async function caseInject({ id, end, rel, title, mutate, create, expectItem }) {
  // 🛑 「新增一个文件」型（第 86 条）—— 与「改写既有文件」型是两个不同形状的注入。
  if (create) {
    const abs = join(FRONTENDS, end, create.rel);
    if (existsSync(abs)) {
      return {
        id, end, title, ok: false,
        detail: '（未执行注入：目标文件已存在，`create` 用例要求它事先不存在）',
        why: `文件已存在: ${end}/${create.rel}`,
      };
    }
    writeFileSync(abs, create.content, { encoding: 'utf8', newline: '\n' });
    CREATED.add(abs);

    let red;
    try {
      red = await runBuildCheck(end);
    } finally {
      cleanupCreate(abs);
    }
    const caught = red.code !== 0 && red.out.includes(expectItem);
    const green = await runBuildCheck(end);
    const restored = green.code === 0;
    return {
      id, end, title, ok: caught && restored,
      detail: `注入（新增文件 ${create.rel}）后 exit=${red.code}，命中「${expectItem}」=${red.out.includes(expectItem)}；`
        + `还原（删除该文件）后 exit=${green.code}`,
    };
  }

  const abs = join(FRONTENDS, end, rel);
  if (!existsSync(abs)) {
    // 🛑 **早退分支也必须有 `detail`**（固定形状）：即使"没跑到注入"，诊断行也
    //    要能自解释。这条与下面 `after === before` 那处同源 —— 见打印处的说明。
    return { id, end, title, ok: false, detail: '（未执行注入：目标文件不存在）', why: `文件不存在: ${end}/${rel}` };
  }
  const before = backup(abs);
  const after = mutate(before);
  if (after === before) {
    restore(abs);
    // 🛑 `title` 必须带出来：本行此前不带，运行器打印 `${r.id} ${r.title}` 就成了
    //    `R28 undefined` —— 一条**说不出自己是哪条用例**的失败，与第 24 条同族
    //    （"报错不指向真因"）。诊断信息里的 `undefined` 一律视为 bug。
    //
    // 🛑 `detail` 同理（R34 首版踩到）：早退分支没有 `detail`，于是运行器打出
    //    `undefined｜变异未生效（…）` —— 同一个 `undefined` 坑的**第二个出口**。
    return {
      id,
      end,
      title,
      ok: false,
      detail: '（未执行注入：mutate 未能匹配到任何片段 ⇒ 本用例没有构成一次真实变异）',
      why: '变异未生效（mutate 返回了原文）—— 用例本身失效，须修正',
    };
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
  {
    id: 'R29',
    end: 'client-mp',
    rel: '../../../.github/workflows/crypto-adversarial-gate.yml',
    title: 'CI：删掉 crypto workflow 的 setup-python 步（第 80 条复活 ⇒ 干净 runner 上 mvn 必因缺 PyYAML 红）',
    expectItem: 'ci-mvn-jobs-have-python',
    // 🛑 与 R27 同型："有人觉得多此一举顺手删了"的最真实形态。
    //    删掉后该 job 的 `mvn -pl dy-crypto -am test` 的 reactor 第 1 个是聚合根，
    //    根 pom 的 4 个 validate 门禁在干净 runner 上因缺 PyYAML exit 2 ⇒ BUILD FAILURE。
    //    本机实测过同族命令 `mvn -pl dy-crypto -am test` 的逐字失败输出（第 80 条登记）。
    //    只删 setup-python、**不动** pip install 那一步 ⇒ 判据命中"没有 setup-python"分支，
    //    与"有 setup-python 没 pip install"是两条独立子规则。
    mutate: (s) => s.replace(
      / {6}- name: Set up Python\r?\n {8}uses: actions\/setup-python@v5\r?\n {8}with:\r?\n {10}python-version: "3\.11"\r?\n/,
      ''),
  },

  // -------------------------------------------------------------------------
  // 🛑 第 84 条（端 C 令牌键名）：R30 证明「原缺陷形态会红」，
  //    R35 证明「判据的**覆盖面自证**分支本身有牙齿」（这才是本条的核心）。
  //
  //    为什么必须分两组（第 53 条）：第 84 条的修法有两层 ——
  //      ① 把扫描根按端形态补齐（否则端 C 走"不适用"分支，**静默跳过**）；
  //      ② 权威点找不到 ⇒ 报红，不再输出"不适用"。
  //    只注 ①（R30）只能证明"现在能扫到端 C 的裸键名"，**完全没有**证明 ② ——
  //    而 ② 恰恰是本次缺陷的本体（"不适用"与"没写"在输出上无法区分）。
  //    R35 直接把权威点候选清单改坏：判据必须报"找不到权威点，不得当作通过"。
  // -------------------------------------------------------------------------
  {
    id: 'R30',
    end: 'client-mp',
    rel: 'miniprogram/services/request.js',
    title: '端 C：出站层回退成裸键名 wx.getStorageSync —— 第 84 条原缺陷形态复活',
    expectItem: 'token-single-source',
    mutate: (s) => s.replace(
      /var token = tokenStore\.readToken\(\);/,
      "var token = wx.getStorageSync('token');"),
  },
  {
    id: 'R35',
    end: 'client-mp',
    rel: '../tools/build-check.mjs',
    title: '判据的权威点候选清单被改坏 ⇒ 覆盖面自证必须报红（**不得**再输出"不适用"或 ✓）',
    expectItem: 'token-single-source',
    // 🛑 这条注入改的是**判据自身**，不是被检代码 —— 与 `a-reverse-check.mjs`
    //    注入 `gen-endpoints.py` 是同一种做法：判据的"认识面"也必须有反向验证，
    //    否则"判据忽然什么都不认识了"与"代码是对的"在输出上完全一样。
    mutate: (s) => s.replace(
      /const AUTH_NAMES = \[[\s\S]*?\];/,
      "const AUTH_NAMES = ['services/nope.js'];"),
  },
  {
    id: 'R31',
    end: 'client-mp',
    rel: 'miniprogram/pages/profile/profile.js',
    title: '端 C：删掉 profile 里通往「拜访记录」的那条导航 ⇒ 该页只剩"自己知道自己"（死页）',
    expectItem: 'page-reachability',
    // 🛑 这条用例的存在本身就是一次教训：**判据 ④j 的首版是"过宽"的** ——
    //    它把"页面自身文件里的自引用"也算作入口，于是本注入（删掉**外部**入口）
    //    **没有让它变红**：`pages/visits/visits.js` 里有
    //    `session.requireLogin('/pages/visits/visits')`，页面把自己当成了自己的入口。
    //    ⇒ 判据改为 `owners: Map<path, Set<file>>` 并**排除页面自身文件**后才红。
    //    🛑 也就是说：**这条用例是"判据太宽"的证伪工具**，不是可有可无的补充
    //    （第 55 条：太宽的判据 ⇒ 假绿）。它必须**常驻** —— 否则将来有人顺手
    //    放宽那条排除条件时，没有任何东西会提醒（判据与它的反向验证必须同时常驻）。
    //
    //    📌 目标不是随手挑的：`pages/visits/visits` 是**非 tab 页**，且实测
    //    `grep -rn "pages/visits/visits"` 显示**全部外部入口只有
    //    `pages/profile/profile.js:112` 这一条** ⇒ 删掉它必然成为孤儿。
    //    （换成 tab 页无效：tabBar 入口天然可达，判据会先 `return false`。）
    mutate: (s) => s.replace(
      /\n\s*wx\.navigateTo\(\{ url: '\/pages\/visits\/visits' \}\);/,
      ''),
  },

  // -------------------------------------------------------------------------
  // 🛑 第 85 条（错误码覆盖）：三组，各只触发一条**互相独立**的子规则。
  //
  //    ④k 有三条子规则：① 冻结 ↔ 裁剪逐条一致；② 契约 ↔ 本端映射双向等式；
  //    ③ 覆盖面自证。R32 / R33 分别打 ② 的两个方向（缺项 / 表外码），
  //    R34 打 ①。**只注一组等于只证了三分之一**（第 53 条教训）。
  // -------------------------------------------------------------------------
  {
    id: 'R32',
    end: 'therapist-app',
    rel: 'src/services/errors.ts',
    title: '端 B：COPY 里删掉 2004 ⇒ 契约有、本端无文案（用户只看得到兜底文案）',
    expectItem: 'error-code-coverage',
    mutate: (s) => s.replace(/\n\s*2004: '[^']*',/, ''),
  },
  {
    id: 'R33',
    end: 'client-mp',
    rel: 'miniprogram/services/codes.js',
    title: '端 C：CODE_COPY 凭空加一个契约外的 code ⇒ 映射表腐烂',
    expectItem: 'error-code-coverage',
    // 🛑 锚点是 `\n};` 的**第一处**：CODE_COPY 的收尾在 `module.exports` 之前。
    mutate: (s) => s.replace(/\n\};/, "\n  7777: '不该存在的码',\n};"),
  },
  {
    id: 'R34',
    end: 'admin-web',
    rel: '../../../contract/sdk-generator/_cut/admin-web.openapi.yaml',
    title: '裁剪契约被删掉一个 code ⇒ 判据必须发现「生成器的输入与冻结契约不一致」',
    expectItem: 'error-code-coverage',
    // 🛑 注入目标在 frontends/ 之外（生成器的**输入契约**）—— 与 R27/R29 同型：
    //    某条规则唯一的证据面就在那里，故用 ../../../ 显式跳出。
    //
    // 🛑 行尾必须写成 `\r?\n`（本用例首版写死 `\n` ⇒ **变异未生效**，被运行器
    //    自己判成"用例本身失效"，见下面 `caseInject` 的 `after === before` 分支）：
    //    实测这是**全部注入目标里唯一一个 CRLF 文件** ——
    //    `client.ts` / `request.js` / `openapi-v1.0.0.yaml` / 两个 workflow /
    //    两份锁文件全是 LF，只有 `sdk-generator/_cut/*.openapi.yaml` 是 CRLF。
    //    成因：它由 `gen-endpoints.py` **在 Windows 上以文本模式写出**（`\r\n`），
    //    也就是说同一份生成物的行尾**依赖生成平台的默认行为**。
    //    ⇒ 凡是对该文件的**字节级/行级**判定都必须容忍 CRLF，否则会得到
    //    "在 Linux 上判绿、在 Windows 上判红"（或反向）的地域性结论。
    mutate: (s) => s.replace(
      /- http: 500\r?\n {2}code: 9001\r?\n {2}name: INTERNAL_ERROR\r?\n {2}trigger: [^\n]*\r?\n/,
      ''),
  },

  // =========================================================================
  // 第 86 条 · 第二十九类：判据把这一端**推给别人**（"由 ④h 覆盖"）
  // =========================================================================
  //  ④j `page-reachability` 此前对端 A / 端 B 只输出一行注释："由 ④h 覆盖
  //  （本条不重复判）"。而 ④h 的判定宇宙是 NAV，**不是磁盘** —— 那句话从未
  //  被核验过。与第 84 条同族：那次是「不适用」，这次是「**已覆盖**」。
  //
  //  ⚠️ 为什么用例编号**不连续顺序排**（R30/R35 挨着、R31 单独在后面）：
  //     这一批（R30–R35）是**分批补的**，编号即补入顺序；此处沿用该约定。
  // =========================================================================

  {
    id: 'R36',
    end: 'admin-web',
    // 🛑 「**新增一个文件**」型注入 —— 这正是第 86 条原缺陷的形态：
    //    新写的页面文件出现在磁盘上，而没有任何登记跟上。此前本框架只会
    //    "改写既有文件"，表达不出这个形状（见 `caseInject` 的 `create` 分支）。
    create: {
      rel: 'src/pages/OrphanPage.tsx',
      content: `// 受控注入（第 86 条）：磁盘上存在、但没有任何文件 import / 渲染它。
export default function OrphanPage(_props: { roleLabel: string }) {
  return <div>ZZQ9_ORPHAN_PROBE</div>;
}
`,
    },
    title: '端 A：新增一个孤儿页面文件 ⇒ ④j 必须报红（原缺陷形态复活；④h / tsc / vite 全看不见它）',
    expectItem: 'page-reachability',
  },
  {
    id: 'R40',
    end: 'therapist-app',
    // 🛑 为什么端 B 也要来一条：端 B 表面上已被 `x3-check` 的 `reach-by-role` ⑥
    //    覆盖（它自己也判页文件）。但**共享实现只对一端生效**正是第 84 条的教训
    //    （那次 `token-single-source` 写死 `src/` ⇒ 端 C 静默"不适用"）。
    //    故必须证明 ④j 的 A/B 共用分支在**两端都**有牙齿，而不是只有端 A。
    create: {
      rel: 'src/pages/OrphanPage.tsx',
      content: `// 受控注入（第 86 条）：磁盘上存在、但没有任何文件 import / 渲染它。
export default function OrphanPage(_props: { roleLabel: string }) {
  return <div>ZZQ9_ORPHAN_PROBE</div>;
}
`,
    },
    title: '端 B：新增孤儿页面 ⇒ ④j 的 A/B 共用分支在端 B 同样必须报红',
    expectItem: 'page-reachability',
  },
  {
    id: 'R37',
    end: 'admin-web',
    rel: 'src/App.tsx',
    title: '端 A：把某页的 JSX 渲染连同它的 import 一起删掉 ⇒ ④j 子规则②（必须以 JSX 形态出现）必须报红',
    expectItem: 'page-reachability',
    // 🛑 两处同时删，是为了**把这条用例隔离到子规则②**（第 53 条：每条子规则
    //    要能被单独证伪）：
    //      · 只删 JSX 不删 import ⇒ `noUnusedLocals: true` 会让 `tsc` 也报红，
    //        于是"到底是谁抓住的"就说不清了；
    //      · 保留 `tab === 'store'` 这个 key ⇒ ④h `page-registry`（NAV ↔ `type Tab`
    //        ↔ 渲染分支）仍然一致 ⇒ ④h【不】报红，只有 ④j 报红。
    //    ⇒ 两处一起删，才能干净地证明"子规则②自己站得住"。
    mutate: (s) => s
      .replace("\nimport StorePage from './pages/StorePage';", '')
      .replace('<StorePage roleLabel={roleLabel} scopeText={scopeText} />', 'null'),
  },
  {
    id: 'R38',
    end: 'admin-web',
    rel: '../tools/build-check.mjs',
    title: '端 A：把某页从 ENDS.required 里删掉 ⇒ ④j 子规则①（磁盘 ⟷ 结构清单）必须报红（清册不得腐烂）',
    expectItem: 'page-reachability',
    // 🛑 注入目标是**判据自己的配置**（`ENDS['admin-web'].required`）—— 与 R35 同型：
    //    "判据用来圈定检查面的那份清册"也是需要被反向验证的对象。
    //    此注入**只**触发子规则①（StorePage 仍在 App.tsx 里以 JSX 渲染 ⇒ ②不触发）。
    mutate: (s) => s.replace("'src/pages/StorePage.tsx',", ''),
  },
  {
    id: 'R39',
    end: 'admin-web',
    rel: '../tools/build-check.mjs',
    title: '端 A：把 ④j 的"结构清单"解析正则改坏 ⇒ 覆盖面自证必须报红（不得静默输出 ✓ 或"不适用"）',
    expectItem: 'page-reachability',
    // 🛑 与 R35 同型（第 84 条）：注入**判据自身**的解析能力。
    //    解析不到 ⇒ 清册侧是空集 ⇒ 若判据照旧输出 ✓，那"判据忽然什么都不认识了"
    //    与"代码是对的"在输出上完全一样（第 45 / 71 条族）。
    //    ④j 的覆盖面自证正是为这个形状准备的：空集必须报红。
    mutate: (s) => s.replace('/^src\\/pages\\/.+\\.tsx$/', '/^src\\/pages\\/NEVER_MATCHES_ZZQ\\.tsx$/'),
  },

  // =========================================================================
  // 第 87 条 · 第三十类：`role-scope`（本端能调哪些端点 = 契约角色表 ∩ 本端角色）
  // =========================================================================
  //  四端里端 A/B/D 各有"角色"判据，**唯独端 C 没有**；而既有三条又都只判
  //  `⊆ 本端角色` 与"非空" —— 一个"把交集算小了"的生成器回归会全部漏过。
  //  故新判据 ④l 做**双向等式**。四组注入分别钉住它的四条子规则：
  //    · R41 / R42 → 契约侧（改坏生成器的**输入**）：空交集 · 越权
  //    · R43       → 生成物侧：缺项
  //    · R44       → 覆盖面自证（把契约侧的配对正则改坏）
  //  🛑 R41 / R42 的注入目标是**裁剪契约**（在 frontends/ 之外，与 R34 同型），
  //     且**必须容忍 CRLF** —— 三份 `_cut/*.openapi.yaml` **整份都是 CRLF**
  //     （实测：admin-web 2457 / therapist-app 2178 / client-mp 1387 个 CRLF，LF-only 0）。
  //     ⚠️ 别用 `sed | cat -A` 去"核实"这件事：Git Bash 的 sed 会做文本模式转换，
  //        输出里**看不到 `^M`**，看起来像是 LF —— 会把人带偏。
  // =========================================================================

  {
    id: 'R41',
    end: 'client-mp',
    rel: '../../../contract/sdk-generator/_cut/client-mp.openapi.yaml',
    title: '端 C：契约把某 operation 的本端角色（client）从 x-callable-roles 里删掉 ⇒ 交集为空，'
      + '`role-scope` 必须报红（该端点本不该出现在本端裁剪里）',
    expectItem: 'role-scope',
    // 删的是**角色行**、保留 `x-callable-roles:` 键 ⇒ YAML 仍然合法（列表少一项），
    // 于是"契约被改坏"这件事只由 ④l 发现 —— 生成物没动，其余判据读生成物，全部照旧绿。
    mutate: (s) => s.replace(/(^ {6}x-callable-roles:\r?\n)( {6}- client\r?\n)/m, '$1'),
  },
  {
    id: 'R42',
    end: 'therapist-app',
    rel: '../../../contract/sdk-generator/_cut/therapist-app.openapi.yaml',
    title: '端 B：契约删掉某 operation 的 `meridian` ⇒ 生成物多出一个本端不该有的角色，'
      + '`role-scope` 必须报红（既有 `x3-check` 的三条角色判据**抓不到这一向**）',
    expectItem: 'role-scope',
    // 🛑 这条用例的**判别力**正是 ④l 存在的理由：`x3-check` 的 `roles`（非空 + ⊆ 本端）、
    //    `x3-meridian-only`（恰好 7 个）、`x3-therapist-only`（无"仅调理师"端点）读的都是
    //    **生成物**；本注入只动**契约** ⇒ 生成物不变 ⇒ 那三条**全部照旧绿**。
    //    这是"有锁 ≠ 锁对"（第 84 条同族）在角色维上的一个可执行反例。
    mutate: (s) => s.replace(/(^ {6}x-callable-roles:\r?\n)( {6}- meridian\r?\n)/m, '$1'),
  },
  {
    id: 'R43',
    end: 'client-mp',
    rel: 'miniprogram/contract/endpoints.js',
    title: '端 C：生成物里某端点的 grantedRoles 变成空数组 ⇒ `role-scope` 子规则③（双向等式）必须报红（缺项）',
    expectItem: 'role-scope',
    // 注入**生成物**（模拟生成器回归）—— 只动 `grantedRoles`，不动 `id`/`path`，
    // 故 ④g 端点触达、① structure、契约计数等全部不受影响 ⇒ 只有 ④l 报红。
    mutate: (s) => s.replace('grantedRoles: Object.freeze(["client"]),', 'grantedRoles: Object.freeze([]),'),
  },
  {
    id: 'R44',
    end: 'admin-web',
    rel: '../tools/build-check.mjs',
    title: '端 A：把 `role-scope` 的契约侧配对正则改坏 ⇒ 覆盖面自证必须报红（不得静默输出 ✓）',
    expectItem: 'role-scope',
    // 与 R35 / R39 同型：注入**判据自身**的解析能力。解析不到 operationId ⇒ 契约侧空集 ⇒
    // 若照旧输出 ✓，"判据忽然什么都不认识了"与"角色范围是对的"在输出上完全一样。
    mutate: (s) => s.replace('/^ {6}operationId:\\s*(\\S+)\\s*$/', '/^NEVER_MATCHES_ZZQ:/'),
  },

  // ==========================================================================
  // 第 88 条 · 第三十一类：**同一个判据、两个通道，严格性不一致**
  //   ③ `words`（ADR-12 合规扫描的本地通道）此前对"扫描根不存在"**静默丢弃**，
  //   于是"改名一个目录"就等于关掉 ADR-12 门禁，而输出是 `✓ 无命中，扫描 0 个文件`
  //   + `BUILD OK` + exit 0。CI 侧 `compliance/scan_compliance.py` 对同一情形
  //   早已是 fail-closed（缺失根 `raise GateConfigError`，注释逐字："A gate that
  //   scans nothing reports success"）。⇒ 本地这一侧对齐到更严的那一侧。
  //   三层覆盖面自证，每层一个受控注入（**证不了的分支不留在代码里** ——
  //   原拟的"逐根非零"层因端 C 只有一个根而不可达，已删除并登记）。
  // ==========================================================================
  {
    id: 'R45',
    end: 'client-mp',
    rel: '../tools/build-check.mjs',
    title: '端 C：声明的禁用词扫描根被改名 ⇒ 覆盖面自证必须报红（此前静默丢根、扫描 0 文件仍 ✓）',
    expectItem: 'words',
    mutate: (s) => s.replace("scanRoots: ['miniprogram'],", "scanRoots: ['miniprogram_ZZQ_RENAMED'],"),
  },
  {
    id: 'R46',
    end: 'client-mp',
    rel: '../tools/build-check.mjs',
    title: '端 C：scanExt 被改坏到匹配不到任何文件 ⇒ 总数非零自证必须报红',
    expectItem: 'words',
    mutate: (s) => s.replace('scanExt: /\\.(js|json|wxml|wxss)$/i,', 'scanExt: /\\.ZZQ_NEVER_MATCHES$/i,'),
  },
  {
    id: 'R47',
    end: 'client-mp',
    rel: '../../compliance/scan-manifest.json',
    title: '端 C：ADR-12 manifest 声明的 frontends/* 根被改名 ⇒ 本地↔CI 覆盖面双向咬合必须报红',
    expectItem: 'words',
    // 🛑 注入目标在 `frontends/` 之外、且**不在 contract/ 那一支**：
    //    这是 ADR-12 的**权威清单**（`compliance/scan-manifest.json`），
    //    本地通道的扫描面必须以它为准，故用 `../../`（不是 `../../../`，见 R27/R34 的分母差异）。
    mutate: (s) => s.split('frontends/client-mp/miniprogram').join('frontends/client-mp/miniprogram_ZZQ'),
  },

  // ==========================================================================
  // 第 89 条 · 第三十二类：判据清单 ↔ 反向清单之间**没有对账**
  //   第 88 条做完后做了一次机械清点，发现 `build-check` **可发出 21 条判据名**，
  //   而本套件的 `expectItem` 只覆盖 **16** 条 —— 下面这 5 条**从未被注入证伪过**：
  //     `structure` · `real-build` · `contract` · `pages` · `wordlist`
  //   而本文件末尾那句"**N 条判据（…列举…）确实有牙齿**"是**手工维护**的清单
  //   （它此前长期写"三条"却列了十四条，第 88 条刚订正过一次）——
  //   **一句没人守的"已覆盖"宣言，与事实之间没有任何机械联系**。
  //   ⇒ 这正是本仓第 86 条（"已覆盖"从未被核验）的**元层级**版本：
  //      那次是"一条判据把这一端推给另一条判据"，这次是"**整套反向验证把这条判据
  //      推给了'以后再说'**"。故补齐这 5 条，并在同轮加上**元判据**（见文件下方
  //      `assertCriterionCoverage()`）——让"新增判据必须同时加反向用例"变成构建期事实。
  // ==========================================================================
  {
    id: 'R48',
    end: 'client-mp',
    rel: '../tools/build-check.mjs',
    title: '① structure：结构清单里出现一个**不存在**的必需文件 ⇒ 必须报红',
    expectItem: 'structure',
    mutate: (s) => s.replace("      'miniprogram/services/session.js',\n    ],",
      "      'miniprogram/services/session.js',\n      'miniprogram/services/ZZQ_MISSING.js',\n    ],"),
  },
  {
    id: 'R49',
    end: 'admin-web',
    rel: 'src/env/index.ts',
    title: '⑤ real-build：给源文件引入一个**类型错误** ⇒ tsc 必须红（真实构建有牙齿）',
    expectItem: 'real-build',
    // 🛑 注入的是一个**真类型错**（`string` 赋给 `number`），而不是语法错 ——
    //    语法错连解析都过不去，证明不了"类型门禁在跑"。
    mutate: (s) => s + '\nconst __zzqRealBuildProbe: number = "not a number";\n',
  },
  {
    id: 'R50',
    end: 'client-mp',
    rel: 'miniprogram/contract/endpoints.js',
    title: '② contract：生成物被改动 ⇒ 与冻结契约的一致性自检必须红',
    expectItem: 'contract',
    mutate: (s) => s + '\n// ZZQ_CONTRACT_PROBE\n',
  },
  {
    id: 'R51',
    end: 'client-mp',
    rel: 'miniprogram/app.json',
    title: '④ pages：app.json 声明一个**磁盘上不存在**的页面 ⇒ 必须报红',
    expectItem: 'pages',
    mutate: (s) => s.replace('"pages": [', '"pages": [\n    "pages/zzq-missing/zzq-missing",'),
  },
  {
    id: 'R52',
    end: 'client-mp',
    rel: '../tools/build-check.mjs',
    title: '③ wordlist：词表路径被改坏 ⇒ 词表缺失必须红（不得静默"无命中"）',
    expectItem: 'wordlist',
    mutate: (s) => s.replace("join(COMPLIANCE, 'wordlists', `${name}.words`)",
      "join(COMPLIANCE, 'wordlists', `${name}.ZZQ_MISSING`)"),
  },
];

// ---------------------------------------------------------------------------
// 🛑 第 89 条 · 第三十二类：**判据清单 ↔ 反向清单** 的对账（元判据）
// ---------------------------------------------------------------------------
//   在此之前，"本套件覆盖了 build-check 的哪些判据"**只存在于本文件的注释与
//   末尾那句 PASS 文案里** —— 而句子是手工维护的：它长期写「**三条**」却列了十四条。
//   ⇒ 后果不是"文案不准"这么轻：**新增一条判据时，没有任何东西要求同时加反向用例**。
//     而本仓第 84/85/86/87/88 条那一族之所以能反复出现，根因正是
//     "**加判据**"与"**给判据装上牙齿**"这两件事之间没有强制关联（R31 是它的一个实例：
//     用例只活在头部注释里、从未被执行过）。
//
//   本元判据做两件事，都是机械的：
//     ① 从 `build-check.mjs` 源码里**抽出它可能发出的全部判据名**
//        （两种发出形态：`fail('x')` / `ok('x')` 字面量，以及 `const ITEM = 'x'` 后 `fail(ITEM)`）；
//     ② 与本套件 `expectItem` 的集合做**双向对账**：
//        · 抽得到、但没有任何用例瞄准它 ⇒ 报红（**新增判据必须连同反向用例一起加**）；
//        · 有用例瞄准、但门禁根本发不出这个名字 ⇒ 报红（**幽灵用例**：它永远抓不到东西）。
//   末尾的 PASS 文案也改为**由该集合生成**，不再是手写的列举 —— 手写的断言没人守。
// ---------------------------------------------------------------------------

/** 从门禁源码抽取"可能发出的判据名"（两种发出形态）。 */
function emittedCriterionNames(src) {
  const names = new Set();
  for (const m of src.matchAll(/\b(?:fail|ok)\(\s*'([a-z0-9][a-z0-9-]*)'/g)) names.add(m[1]);
  // 🛑 用 (标识符, 值) 的**列表**，不要用 `Map<标识符, 值>`：
  //    `build-check.mjs` 里 `const ITEM = '…'` 出现了**两次**（两个不同的块作用域，
  //    各是不同的判据名）。按标识符去重会让**后一个覆盖前一个** ⇒ 前一条判据被
  //    误判成"幽灵"。这个 bug 是本元判据**首跑自己咬出来的** —— 也正是它该有的行为：
  //    检测器自己出错时，结果是**一声响亮的报错**，而不是一句安静的 ✓。
  const pairs = [];
  for (const m of src.matchAll(/\bconst\s+([A-Za-z_$][\w$]*)\s*=\s*'([a-z0-9][a-z0-9-]*)'/g)) {
    pairs.push([m[1], m[2]]);
  }
  for (const [ident, val] of pairs) {
    if (new RegExp(`\\b(?:fail|ok)\\(\\s*${ident}\\b`).test(src)) names.add(val);
  }
  return names;
}

const GATE_REL = 'tools/build-check.mjs';

// 🛑 元判据**自身**也必须可证伪（否则它就是又一个"没人守的宣言"）。三道自证：
//   ① **冻结下限**：抽取到的判据名数不得少于这个数。抽不出来（正则退化 / 门禁被换掉）
//      时的失败形态恰恰是"`UNCOVERED` 为空 ⇒ 通过"—— 一句安静的 ✓。故必须有下限。
//   ② **检测器负控**：拿一段合成源码跑一遍抽取器，断言两种发出形态都能被认出来。
//      正则写坏时这里立刻红，而不是让对账静默全绿。
//   ③ 下限值与负控**一起**参与判定：任一不成立 ⇒ 报红，且**不得**输出对账 ✓。
const CRITERION_FLOOR = 21;
const DETECTOR_SAMPLE = "fail('zzq-literal-form');\nconst ITEM2 = 'zzq-const-form';\nok(ITEM2, 'x');\n";
function detectorSelfTest() {
  const got = emittedCriterionNames(DETECTOR_SAMPLE);
  return got.has('zzq-literal-form') && got.has('zzq-const-form');
}

const EMITTED = emittedCriterionNames(readFileSync(join(HERE, 'build-check.mjs'), 'utf8'));
const TARGETED = new Set(CASES.map((c) => c.expectItem).filter(Boolean));
const UNCOVERED = [...EMITTED].filter((n) => !TARGETED.has(n)).sort();
const PHANTOM = [...TARGETED].filter((n) => !EMITTED.has(n)).sort();
const DETECTOR_OK = detectorSelfTest();
const FLOOR_OK = EMITTED.size >= CRITERION_FLOOR;

if (!DETECTOR_OK || !FLOOR_OK || UNCOVERED.length || PHANTOM.length) {
  const L = (s) => process.stdout.write(s + '\n');
  L('\n✗ [criterion-coverage] 判据清单与反向清单**对不上** —— 拒绝继续跑用例。\n');
  if (!DETECTOR_OK) {
    L('  ⓪ **抽取器自身失效**：负控未通过（两种发出形态之一认不出来）。');
    L('     ⇒ 此时"没有未覆盖判据"是**假绿** —— 先修抽取器，再谈覆盖面。\n');
  }
  if (!FLOOR_OK) {
    L(`  ⓪ **抽取面退化**：只认出 ${EMITTED.size} 条，低于冻结下限 ${CRITERION_FLOOR} 条。`);
    L('     ⇒ 门禁被换成别的文件 / 发出形态改了 ⇒ 本对账的前提不成立。\n');
  }
  if (UNCOVERED.length) {
    L(`  ① 门禁可发出、但**没有任何用例瞄准**的判据（${UNCOVERED.length} 条）：`);
    L(`     ${UNCOVERED.join(' · ')}`);
    L('     ⇒ 这些判据**从未被注入证伪过**。修法：为每条补一个受控注入，');
    L('       或在 `CRITERION_EXEMPT` 里**写明豁免理由**（豁免表本身也要有值可核）。\n');
  }
  if (PHANTOM.length) {
    L(`  ② 有用例瞄准、但门禁**发不出**这个名字的判据（${PHANTOM.length} 条）：`);
    L(`     ${PHANTOM.join(' · ')}`);
    L('     ⇒ 幽灵用例：`expectItem` 指向一个不存在的判据名 ⇒ 它永远抓不到东西。\n');
  }
  L(`  （口径：${GATE_REL} 可发出 ${EMITTED.size} 条 · 本套件瞄准 ${TARGETED.size} 条`
    + ` · 冻结下限 ${CRITERION_FLOOR} 条 · 抽取器负控 ${DETECTOR_OK ? '通过' : '未通过'}）\n`);
  process.exit(1);
}
process.stdout.write(
  `判据对账：[criterion-coverage] ${GATE_REL} 可发出的 **${EMITTED.size} 条**判据 `
  + `**全部**被本套件瞄准（无未覆盖、无幽灵）✓ `
  + `—— 且抽取器负控通过、条数不低于冻结下限 ${CRITERION_FLOOR}（自证：不是"抽不到所以没缺口"）\n`);

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

process.stdout.write('===== 构建自检 · 反向验证（注入 → 必须变红 → 还原 → 必须变绿）=====\n');
const results = [];
for (const c of CASES) {
  const r = await caseInject(c);
  results.push(r);
  const label = r.ok ? '  ✓ 抓住' : '  ✗ 漏过';
  process.stdout.write(`${label}  ${r.id} ${r.title}\n`);
  // 🛑 `detail` 缺失时**不得**打印 `undefined`：诊断信息里的 `undefined` 一律视为 bug
  //    （第 24 条族）。此前只有 `title` 一处被修（`R28 undefined`），而 `detail` 是
  //    **同一个坑的第二个出口** —— 两条早退分支（文件不存在 / 变异未生效）都没有它，
  //    R34 首版就是这么打出一行 `undefined｜变异未生效（…）` 的。
  //    两道防线：① 两个早退分支补 `detail`；② 这里兜底，让"少写一个字段"变不成乱码。
  const detail = r.detail ?? '（诊断信息缺失 —— 这是本脚本的 bug，不是用例结果）';
  process.stdout.write(`           ${detail}${r.why ? `｜${r.why}` : ''}\n`);
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
  // 🛑 第 89 条：这句文案**不再手写**。手写版曾长期说「三条」却列了十四条 ——
  //    一个没人守的断言必然与事实脱节。现由上面的机械对账集合（EMITTED / TARGETED）生成。
  process.stdout.write(
    `\nbuild-check 反向验证 PASS —— 判据清单与反向清单**机械对账一致**：`
    + `可发出的 **${EMITTED.size} 条**判据全部有牙齿（\n  ${[...TARGETED].sort().join(' · ')}\n），且还原干净。\n`);
  process.exit(0);
}
process.stdout.write('\nbuild-check 反向验证 FAIL。\n');
process.exit(1);