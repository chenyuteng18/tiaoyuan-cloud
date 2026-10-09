#!/usr/bin/env node
/**
 * 门禁工具共用件（内部模块，非入口脚本）
 * ============================================================================
 *
 * 🛑 这个文件存在的唯一理由：**把"临时探针残骸"从普通缺陷里区分出来**
 * ---------------------------------------------------------------------------
 * 本仓的每条门禁都配一个反向验证脚本，后者会往源树里**临时**写入造错文件
 * （`__probe_*.tsx`）以证明门禁有牙齿。若反向验证脚本被中断（Ctrl-C / 超时 /
 * 上层进程被杀），那个造错文件会**留在源树里**。
 *
 * 后果极其误导（2026-09-30 实测复现）：
 *   · 主门禁报出来的失败项**指名道姓**地写着 `pages/__probe_unsettled.tsx`
 *     引用了未完结端点却未标注 —— 读起来像一个真实的源码缺陷，
 *     于是排查方向是"去改那个页面"，而它其实是一次测试运行的残骸；
 *   · 若没有这个共用件，排查者只会看到"门禁红了"，**看不到真实原因**。
 *
 * 这正是本仓第 24 条的形态：**检验工具被中断后，把自己的残骸当成了被检对象的错误。**
 * 本仓已经踩过两次（`a-reverse-check.mjs` 的 `ABORT: 基线门禁不是绿的`），
 * 故这里把三件处置合一，供全部门禁共用：
 *   ① **识别**：谁是探针残骸（按本仓固定的命名空间，不宽泛删除任何文件）；
 *   ② **排除**：扫描时跳过探针文件（避免把残骸当成被检对象）；
 *   ③ **报告**：真遇到残骸时，报出的是「**上一次运行留下的残骸**」，
 *      并给出"怎么清"与"为什么不能忽略"，而不是伪装成一个源码缺陷。
 *
 * 🛑 命名空间纪律：本模块只认 `__probe_` 前缀。这不是"清理临时文件"的工具，
 *    是"识别**本仓门禁自己**制造的临时文件"的工具 —— 绝不删别人的东西。
 */

import { readdirSync, statSync, existsSync, writeFileSync, readFileSync, unlinkSync } from 'node:fs';
import { join, relative } from 'node:path';

/** 探针文件的命名空间（本仓门禁工具自己制造的临时造错文件）。 */
export const PROBE_RE = /^__probe_.*\.(tsx|ts|js|jsx|mjs)$/;

/** 该路径是否属于探针命名空间。 */
export function isProbePath(p) {
  const name = String(p).replace(/\\/g, '/').split('/').pop() || '';
  return PROBE_RE.test(name);
}

/**
 * 测试源码的命名空间（Vitest 约定）：`*.test.ts(x)`。
 *
 * 🛑 它和 `PROBE_RE` 是**两种不同的排除**，不要混用
 * ---------------------------------------------------------------------------
 * · `PROBE_RE` 排除的是"**本仓门禁自己**在被中断后留下的残骸"（它不是源码）；
 * · 本常量排除的是"**合法的、但不随构建发布**的单测源码"（它是源码，只是不在产物图里）。
 *
 * 为什么"分层纪律"该类判据必须排除它（2026-10-09 实测，端 A 的
 * `internal-capability-registry` ③ 首跑即假红）：
 * 契约外出站通道 `api/internal.ts` 的**单测** `api/internal.test.ts` 必须调用
 * `callInternal` 才能被测 —— 那**不是**"绕过分层"，而是"测这条通道"的唯一办法。
 * 分层纪律的守护对象是**生产出站路径**（页面/外壳不得绕过 services 层），
 * 单测既不随 `vite build` 发布、也不构成运行时调用链 ⇒ 不在守护范围内。
 *
 * 🛑 排除也必须是**可被看见的**（第 53 条）：调用方**必须**把排除数打印出来。
 * 否则"判据悄悄少扫了一批文件"与"判据有牙"在输出上完全无法区分 ——
 * 那是静默漏检，比假红危险。
 */
export const TEST_SOURCE_RE = /\.test\.(ts|tsx)$/;

/** 该路径是否属于测试源码（非生产产物图）。 */
export function isTestSource(p) {
  return TEST_SOURCE_RE.test(String(p).replace(/\\/g, '/'));
}

/**
 * 列出目录下的探针残骸（相对 `rootDir`）。
 * 只读，不删任何东西 —— 删除由调用方在明确判断后进行。
 */
export function findProbeDebris(rootDir) {
  const out = [];
  const walk = (dir) => {
    if (!existsSync(dir)) return;
    for (const name of readdirSync(dir)) {
      if (name === 'node_modules' || name.startsWith('.')) continue;
      const p = join(dir, name);
      let st;
      try {
        st = statSync(p);
      } catch {
        continue;
      }
      if (st.isDirectory()) walk(p);
      else if (isProbePath(name)) out.push(relative(rootDir, p).replace(/\\/g, '/'));
    }
  };
  walk(rootDir);
  return out;
}

/**
 * 门禁失败时的**诊断补充**：若工作树里同时存在探针残骸，把这件事讲清楚。
 *
 * @returns {string} 追加到失败输出末尾的说明（无残骸时返回空串）
 *
 * 🛑 为什么是"诊断"而不是"拦截"（这是一个被实测推翻过的设计）
 * ---------------------------------------------------------------------------
 * 初版把这里写成**入口守卫**（发现残骸就 exit，不跑判据）。实测立刻推出反例：
 * 反向验证脚本的 I10 / I13 用例**故意**先写一个探针文件、再期望门禁抓住它 ——
 * 那是**正当的中间态**，被守卫一拦，两条用例双双变成"漏过"（实测 14/16）。
 *
 * 于是区分这两件事的正确判据不是"有没有探针文件"，而是
 * **"门禁是否已经失败了"**：
 *   · 门禁通过 + 有探针文件   → 可能是反向验证的中间态，也可能是残骸，
 *                               但**结论不受影响**（绿就是绿），无需多言；
 *   · 门禁失败 + 有探针文件   → 高度可疑：失败项可能根本不是源码缺陷，
 *                               而是上次运行留下的残骸被当成了被检对象。
 *                               此时把残骸列出来并给出处置办法。
 * 这个设计同时满足两个约束：**不误伤正当中间态**、**不放过误导性报错**。
 */
export function probeDebrisNotice(rootDir, tool) {
  const debris = findProbeDebris(rootDir);
  if (debris.length === 0) return '';
  return [
    '',
    '⚠️  另外发现：工作树里有【本仓门禁自己的临时探针文件】——',
    '   上述失败项有可能**根本不是源码缺陷**，而是上一次运行被中断后留下的残骸，',
    '   被本判据当成了被检对象（本仓第 24 条：检验工具把自己的残骸当成了被检对象的错误）。',
    '',
    '   残留文件：',
    ...debris.map((d) => `     - ${d}`),
    '',
    `   若你此刻正在跑反向验证（${tool} 的 *reverse-check*），这是**用例的中间态**，可忽略本段；`,
    '   否则请二选一处置后再重跑：',
    '     ① 重跑对应的反向验证脚本 —— 它启动时会先清掉自己命名空间下的残骸；',
    `     ② 直接删除上面列出的文件（它们是门禁自己的临时文件，不属任何源码）。`,
    '',
  ].join('\n');
}

// ===========================================================================
// 「被改写的既有文件」残骸日志（本仓第 90 条 · 第三十三类）
// ===========================================================================
//
// 🛑 `findProbeDebris()` 覆盖不了这一类，原因很简单：
//    它靠**文件名**识别（`__probe_*`）。而反向验证的绝大多数用例是
//    **改写既有文件**（改一个字面量、删一行断言行）—— 残骸形态是
//    "某个**正常的**源文件里多了一处注入"，**没有任何文件名线索**。
//
// 🛑 而这一类残骸**比"多出来的探针文件"更危险**：
//    · 探针文件至少是显眼的、可以整体删除的；
//    · 被改写的源文件看上去就是它自己 —— 门禁报出来的失败项**指名道姓地**
//      写着一个真实文件与一个真实判据，读起来 100% 像一个源码缺陷。
//      2026-10-09 实测：强杀 `build-reverse-check.mjs`（它正跑到 R5）后，
//      `frontends/therapist-app/vite.config.ts` 的 dev 代理键被留成 `/api`，
//      于是端 B 的 `base-path-wiring` 报红 —— 而真实原因是**一次被中断的测试运行**。
//
// 🛑 内存备份（第 61 条起采用）解决不了这件事：进程被 SIGKILL 时内存随之消失。
//    ⇒ 必须有一个**落在磁盘上、且下一个进程能读懂**的日志。
//
// 🛑 设计约束（都是踩过的坑，不是凭空想的）：
//   ① **一个文件，不按用例各存一份** —— 早年版把备份写进 `tools/_reverse_backup/`
//      目录，一轮产生 N 个文件，结果把本机安全删除守卫的**单轮删除阈值**顶穿（第 61 条）；
//   ② **中途只写、不删**：每条用例 stash 时写、commit 时写成空对象（文件留着），
//      只在**整轮收尾**时删一次 ⇒ 单轮删除数 ≤ 2，远离阈值；
//   ③ **拒绝猜测**：日志损坏（JSON 解不开）时**不静默忽略**，而是报出来 ——
//      一个读不懂的残骸日志本身就是异常，安静吞掉等于把"上次崩过"这件事抹掉。
//
// 用法（调用方只需三处）：
//   · 启动时 `journalRecover(rootDir, tool)` —— 有残骸则**先还原**并打印醒目提示；
//   · 备份时 `journalStash(absPath, originalText)`；
//   · 还原时 `journalCommit(absPath)`；整轮结束 `journalClose(rootDir)`。

/** 日志文件相对 `frontends/` 的位置（与 `_gate-common.mjs` 同在 tools/ 下）。 */
export const JOURNAL_REL = 'tools/_reverse_journal.json';

// 🛑 四个反向验证套件**共用同一个**日志文件 —— 前提是它们**串行**运行。
//    这不是新增约束：本仓 CI 的该步早已明令串行（它们对**同一批文件**做
//    「注入 → 还原」，并行本来就会互相踩踏，日志只是把那条约束又暴露了一次）。
//    `journalCommit` 只删自己那个绝对路径的条目；`journalClose` 只在日志**已空**时
//    才删除文件 ⇒ 另一套件尚有未还原条目时，文件不会被误删。

function journalPath(rootDir) {
  return join(rootDir, JOURNAL_REL);
}

function journalReadAbs(p) {
  if (!existsSync(p)) return {};
  const raw = readFileSync(p, 'utf8');
  if (!raw.trim()) return {};
  return JSON.parse(raw); // 故意不 try/catch：损坏的日志必须炸出来（见 ③）
}

/**
 * 启动期自愈：若上一轮被强杀留下了残骸，先把**被改写的源文件**还原回去。
 *
 * @returns {{restored: string[]}|null} 无残骸返回 null
 */
export function journalRecover(rootDir) {
  const p = journalPath(rootDir);
  if (!existsSync(p)) return null;
  let data;
  try {
    data = journalReadAbs(p);
  } catch (e) {
    return { restored: [], corrupted: String(e && e.message ? e.message : e), raw: p };
  }
  const entries = Object.entries(data || {});
  if (entries.length === 0) {
    try { unlinkSync(p); } catch { /* 尽力而为 */ }
    return null;
  }
  const restored = [];
  for (const [abs, content] of entries) {
    try {
      writeFileSync(abs, content, { encoding: 'utf8', newline: '\n' });
      restored.push(relative(rootDir, abs).replace(/\\/g, '/'));
    } catch { /* 单条失败不阻断其余 */ }
  }
  try { unlinkSync(p); } catch { /* 尽力而为 */ }
  return { restored };
}

/** 记录"将要改写这个文件"，并把原文落盘（此后即使进程被强杀也能还原）。 */
export function journalStash(rootDir, absPath, originalText) {
  const p = journalPath(rootDir);
  let data = {};
  try { data = journalReadAbs(p); } catch { data = {}; }
  data[absPath] = originalText;
  writeFileSync(p, JSON.stringify(data), 'utf8');
}

/** 该文件已还原：从日志里摘掉它（日志本身留到整轮收尾再删）。 */
export function journalCommit(rootDir, absPath) {
  const p = journalPath(rootDir);
  if (!existsSync(p)) return;
  let data = {};
  try { data = journalReadAbs(p); } catch { data = {}; }
  delete data[absPath];
  writeFileSync(p, JSON.stringify(data), 'utf8');
}

/** 整轮收尾：日志已空则可删除；**非空绝不删**（那意味着还有未还原的文件）。 */
export function journalClose(rootDir) {
  const p = journalPath(rootDir);
  if (!existsSync(p)) return;
  let data = {};
  try { data = journalReadAbs(p); } catch { return; } // 损坏时保留，交给下一次启动报出来
  if (Object.keys(data).length === 0) {
    try { unlinkSync(p); } catch { /* 尽力而为 */ }
  }
}
