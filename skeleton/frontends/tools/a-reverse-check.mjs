/**
 * 端 A · 契约元信息门禁的反向验证（证明门禁有牙齿）
 * ============================================================================
 *
 * 与端 B 的 x3-reverse-check.mjs 同法：**主动注入必须被抓住的错误**，
 * 断言门禁变红（且变红的是**那一条**），再断言还原后变绿。
 *
 * 🛑 为什么端 A 的注入集与端 B 不同
 * ---------------------------------------------------------------------------
 * 端 A 是单角色端，端 B 那套"角色 → 端点"的注入在这里**检不出**（矩阵恒真）。
 * 端 A 要防的是**契约元信息在转录中丢失**（第 54 条）与**未完结状态被当既定事实**
 * （第 54 条的后果）。故本脚本的注入全部对准这两类。
 *
 * 🛑 关于"注入必须带来可观测差异"（第 46 条）
 * ---------------------------------------------------------------------------
 * 每组注入先断言"确实改动了文件内容"；等价于原样的注入即使报红也是**假红**。
 *
 * 🛑 关于"环境受限不得被误读成结论"（frontends/README §3）
 * ---------------------------------------------------------------------------
 * 本环境**同步**派生子进程会被拦（EBUSY），一律用**异步** spawn；
 * 并区分"退出码"与"子进程起不来"（起不来记 99）。
 *
 * 用法：node frontends/tools/a-reverse-check.mjs
 * 退出码：0 = 全部按预期 · 1 = 有注入未被抓住或还原不干净
 */

import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = join(HERE, '..');
const END_A = join(FRONTENDS, 'admin-web');
const SRC = join(END_A, 'src');
const NODE = process.execPath;

function runGate() {
  return new Promise((resolve) => {
    let out = '';
    let err = '';
    const child = spawn(NODE, [join(HERE, 'a-check.mjs')], { cwd: END_A });
    child.stdout.on('data', (d) => { out += String(d); });
    child.stderr.on('data', (d) => { err += String(d); });
    child.on('error', () => resolve({ code: 99, out: '', err: '' })); // 起不来
    child.on('close', (code) => resolve({ code: code === null ? 99 : code, out, err }));
  });
}

const GEN = join(SRC, 'contract', 'endpoints.ts');
const SCOPE = join(SRC, 'contract', 'scope.ts');
const CLIENT = join(SRC, 'api', 'client.ts');
const APP = join(SRC, 'App.tsx');

const backup = new Map();
function stash(path) { backup.set(path, readFileSync(path, 'utf8')); }
function restoreAll() { for (const [p, t] of backup) writeFileSync(p, t); }

/**
 * 🛑 记录本脚本**新建**的探针文件，供异常退出时清理。
 *
 * 背景（本轮实测残留）：I10 / I13 会新建 `pages/__probe_*.tsx` 再删。
 * 若脚本在"建好、还没删"之间被中断（Ctrl-C / 超时 / 上层进程被杀），
 * 残骸会留在源树里 —— 而 `a-check` 会因此报出一个**指名道姓指向该探针文件、
 * 读起来像真实源码缺陷**的失败项（本仓第 24 条：检验工具把自己的残骸
 * 当成了被检对象的错误）。
 *
 * 本仓已有两层防线：
 *   ① 启动时自愈（清掉上次遗留的 `__probe_*.tsx`）；
 *   ② `_gate-common.mjs` 的**失败时诊断**（把"这可能是残骸"讲清楚）。
 * 这里补第三层：**把"被打断"这个必然事件也纳入清理** ——
 * 收到 SIGINT / SIGTERM 时先删掉自己新建的探针，再退出。
 * 只能覆盖"可捕获的中断"（`kill -9` 与进程被强杀仍会残留），
 * 故前两层防线不可省 —— 三层各管一段，不是重复。
 */
const createdProbes = new Set();
function trackProbe(p) { createdProbes.add(p); }
function untrackProbe(p) { createdProbes.delete(p); }
function cleanupProbes() {
  for (const p of createdProbes) {
    try { rmSync(p, { force: true }); } catch { /* 尽力而为 */ }
  }
  createdProbes.clear();
}
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(sig, () => {
    cleanupProbes();
    restoreAll();
    process.stderr.write(`\n收到 ${sig}：已清理本次新建的探针文件并还原注入，退出。\n`);
    process.exit(130);
  });
}

const results = [];

/** 一组注入：改文件 → 断言真变了 → 跑门禁 → 还原 → 复验绿。 */
/**
 * 注入一组错误，断言门禁变红、变红的**是那一条**，再断言还原后变绿。
 *
 * @param also 可选：**同一组用例需要动第二个文件**时的附加注入（默认空 ⇒ 行为不变）。
 *   🛑 为什么需要它（第 83 条 I27 实测逼出）：有些判别力**只在两个文件的组合下**才成立 ——
 *   例如"② 只认**生产**文件的出站调用形态"这条，要证明它，必须同时
 *   （a）让生产调用消失、（b）让某个**单测**提供同 id 的调用形态
 *   —— 只有 (a) 会被新老两种口径一起抓住（那是 I23 已证过的形态），
 *   只有 (a)+(b) 的组合才是新口径**独有**的判别力。
 */
async function injection({ name, path, from, to, also = [], expectExit = 1, expectGate }) {
  const original = readFileSync(path, 'utf8');
  stash(path);
  if (from !== undefined) {
    if (!original.includes(from)) {
      results.push({ name, ok: false, why: `注入锚点未找到：${JSON.stringify(from.slice(0, 70))}` });
      return;
    }
    writeFileSync(path, original.replace(from, to));
  }
  const changed = readFileSync(path, 'utf8');
  if (changed === original) {
    results.push({ name, ok: false, why: '注入没有带来任何内容差异（等价于原样 ⇒ 会是假红）' });
    restoreAll();
    return;
  }
  // ---- 附加注入：每个都必须真的改动文件，否则这组用例的前提不成立 ----
  for (const m of also) {
    if (!existsSync(m.path)) {
      results.push({ name, ok: false, why: `附加注入的文件不存在：${m.path}` });
      restoreAll();
      return;
    }
    const o = readFileSync(m.path, 'utf8');
    if (!o.includes(m.from)) {
      results.push({ name, ok: false, why: `附加注入锚点未找到：${JSON.stringify(m.from.slice(0, 70))}` });
      restoreAll();
      return;
    }
    const next = o.replace(m.from, m.to);
    if (next === o) {
      results.push({ name, ok: false, why: '附加注入没有带来任何内容差异（等价于原样 ⇒ 会是假红）' });
      restoreAll();
      return;
    }
    stash(m.path);
    writeFileSync(m.path, next);
  }
  const red = await runGate();
  restoreAll();
  const back = await runGate();

  const keywordOk = expectGate ? (red.out + red.err).includes(expectGate) : true;
  const ok = red.code === expectExit && keywordOk && back.code === 0;
  results.push({
    name,
    ok,
    why: ok
      ? `门禁 exit=${red.code}，${expectGate ? `命中失败项「${expectGate}」` : '未指定具体判据（本组只要求"必须变红"）'}，还原后 exit=${back.code}`
      : `期望 exit=${expectExit} + 失败项「${expectGate}」+ 还原绿；`
        + `实得 exit=${red.code} / 关键词${keywordOk ? '命中' : '未命中'} / 还原 exit=${back.code}`,
  });
}

// ---------------------------------------------------------------------------
// 自愈：清掉上一次运行可能遗留的临时探针文件（第 24 条：门禁的证人必须能自愈）
// ---------------------------------------------------------------------------
// 🛑 实测踩过（本轮）：I10 / I13 会**新建**临时页再删。若上一次运行在
//    "建好临时页、还没删"之间被中断（Ctrl-C / 超时），临时页会留在源树里，
//    而 `unsettled-surfaced` 判据会因此**永远红** ⇒ 下一次跑本脚本时
//    `基线门禁不是绿的` 直接 ABORT，且**报错指向的是"先修好再跑"**，
//    真实原因（残骸）完全看不出来。
//    这正是本仓第 24 条的形态：**检验工具被中断后，把自己的残骸当成了被检对象的错误**。
//    修法：**每次启动先清掉自己命名空间下的全部临时文件**。
//    🛑 只删本脚本自己创造的固定名字（`__probe_*.tsx`），绝不宽泛删除 ——
//       其他脚本/作者的临时文件不属本脚本的处置范围。
{
  const PAGES = join(SRC, 'pages');
  if (existsSync(PAGES)) {
    for (const name of readdirSync(PAGES)) {
      if (/^__probe_.*\.tsx$/.test(name)) {
        rmSync(join(PAGES, name), { force: true });
        console.log(`自愈：清掉上一次遗留的临时文件 pages/${name}`);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 基线
// ---------------------------------------------------------------------------
const baseline = (await runGate()).code;
if (baseline !== 0) {
  console.error(`ABORT: 基线门禁不是绿的（exit=${baseline}）。先修好再跑。`);
  process.exit(1);
}
console.log('基线：门禁绿 ✓\n');

// ---------------------------------------------------------------------------
// I1 【第 54 条核心回归】把某端点的 frontier 声明从生成物里删掉
//     ⇒ 期望 xkey-values 报红（契约 7 处 vs 生成物 6 处）
//     —— 这正是"生成器丢元信息"的形态：界面会把它当已冻结功能。
// ---------------------------------------------------------------------------
// 🛑 锚点纪律（2026-09-30 实测）：锚点**不得跨过生成器的字段顺序**。
//    本轮给生成物新增 `requiredQuery` / `requiredBody` 两行（插在 `grantedRoles`
//    之后），三条老用例的锚点因**把它俩写进 from**而全部失配 —— 表现为
//    "注入锚点未找到"，读起来像用例坏了，其实是"生成物形状变了"。
//    ⇒ 锚点一律止于**稳定前缀**（`grantedRoles: …`），要删/改的字段单独成 from 的尾段。
await injection({
  name: 'I1 删掉 I5 端点的 frontier（模拟"元信息被丢掉"）',
  path: GEN,
  from: '    frontier: "占位待冻结",\n  },\n  {\n    id: "createDocTemplateVersion",',
  to: '  },\n  {\n    id: "createDocTemplateVersion",',
  expectGate: 'xkey-values',
});

// ---------------------------------------------------------------------------
// I2 把某端点的 rowScope 整类删掉
//     ⇒ 期望 xkey-values 报红（x-row-scope 契约 2 处 vs 生成物 1 处）
// ---------------------------------------------------------------------------
await injection({
  name: 'I2 删掉 F4 端点的 rowScope',
  path: GEN,
  from: '    rowScope: "卡片可给门店（仅本店、三数同显）；告警动作归 P1-04、对门店不可见",\n',
  to: '',
  expectGate: 'xkey-values',
});

// ---------------------------------------------------------------------------
// I3 把 superAdminOnly 从 true 改成 false
//     ⇒ 期望 xkey-values 报红（契约 1 处 true vs 生成物 0 处）
// ---------------------------------------------------------------------------
await injection({
  name: 'I3 把 I7 的 superAdminOnly 由 true 改成 false',
  path: GEN,
  from: 'path: "/doc-templates/{id}/download",\n    grantedRoles: Object.freeze(["admin"]),',
  to: 'path: "/doc-templates/{id}/download",\n    grantedRoles: Object.freeze(["admin"]),\n    superAdminOnly: false,',
  expectGate: 'xkey-values',
});

// ---------------------------------------------------------------------------
// I4 让 scope.ts 硬编码一份"仅超管"清单（第二份权威）
//     ⇒ 期望 no-hardcoded-list 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I4 在 scope.ts 里硬编码仅超管行号清单',
  path: SCOPE,
  from: 'export function superAdminOnlyEndpoints(): readonly Endpoint[] {\n  return ENDPOINTS.filter((e) => e.superAdminOnly === true);\n}',
  to: 'const SUPER_ADMIN_HARDCODED = ["I7", "I1", "I2"];\n'
    + 'export function superAdminOnlyEndpoints(): readonly Endpoint[] {\n'
    + '  return ENDPOINTS.filter((e) => SUPER_ADMIN_HARDCODED.includes(e.row));\n'
    + '}',
  expectGate: 'no-hardcoded-list',
});

// ---------------------------------------------------------------------------
// I5 让 scope.ts 的判定不再引用生成物（改成空壳/手抄）
//     ⇒ 期望 scope-wired 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I5 把 scope.ts 的判定改成不引用生成物',
  path: SCOPE,
  from: "import {\n  ENDPOINTS,\n  END_TOKEN_ROLES,\n  ROLE_EXPANSION,\n  endpointById,",
  to: "import {\n  END_TOKEN_ROLES,\n  ROLE_EXPANSION,",
  expectGate: 'scope-wired',
});

// ---------------------------------------------------------------------------
// I6 让 ADMIN_TOKENS 手写子档位（不走契约 x-roles）
//     ⇒ 期望 role-expansion 报红
// ---------------------------------------------------------------------------
await injection({
  name: "I6 把 ADMIN_TOKENS 改成手写数组（不再取自契约）",
  path: SCOPE,
  from: "export const ADMIN_TOKENS: readonly string[] = ROLE_EXPANSION['admin']?.tokens ?? [];",
  to: "export const ADMIN_TOKENS: readonly string[] = ['manager', 'area', 'hq'];",
  expectGate: 'role-expansion',
});

// ---------------------------------------------------------------------------
// I7 把 ROLE_EXPANSION 从生成物里删掉（契约 x-roles 未转录）
//     ⇒ 期望 role-expansion 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I7 从生成物删掉 ROLE_EXPANSION 导出',
  path: GEN,
  from: 'export const ROLE_EXPANSION: Readonly<Record<string, RoleExpansion>>',
  to: 'const ROLE_EXPANSION_UNUSED: Readonly<Record<string, RoleExpansion>>',
  expectGate: 'role-expansion',
});

// ---------------------------------------------------------------------------
// I8 【第 53 条手法回归】让生成物凭空多出一条端点（伪造行号）
//     ⇒ 期望 parse-coverage 报红（独立源 x-contract-row 交叉核对）
//     —— 这是"下游判据跑在残缺/伪造集合上照样绿"的回归用例。
// ---------------------------------------------------------------------------
await injection({
  name: 'I8 给生成物凭空插一条伪造端点',
  path: GEN,
  from: '  {\n    id: "authLogin",',
  to: '  {\n    id: "forgedEndpoint",\n    row: "Z9",\n    method: "GET",\n    path: "/forged",\n    grantedRoles: Object.freeze(["admin"]),\n  },\n  {\n    id: "authLogin",',
  expectGate: 'parse-coverage',
});

// ---------------------------------------------------------------------------
// I9 让生成物里的 row 变成契约没有的新父行（伪造合同行）
//     ⇒ 期望 parse-coverage 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I9 把某端点行号改成契约没有的 Z 域',
  path: GEN,
  from: '    id: "getAuditCoverage",\n    row: "F4",',
  to: '    id: "getAuditCoverage",\n    row: "Z9",',
  expectGate: 'parse-coverage',
});

// ---------------------------------------------------------------------------
// I10 在源码里引用一个"未完结端点"却完全不标注（当既定事实用）
//      ⇒ 期望 unsettled-surfaced 报红
// ---------------------------------------------------------------------------
// 🛑 这里**不能用 injection()**：它只改既有文件，而本注入要**新建**一个文件
//    （因为 unsettled-surfaced 是"扫到某文件引用未完结端点却无标注"才报）。
//    故单独写：建临时页 → 跑门禁 → 删临时页 → 复验绿。
{
  const dir = join(SRC, 'pages');
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  const TMP = join(dir, '__probe_unsettled.tsx');
  trackProbe(TMP);
  writeFileSync(TMP, 'export function Probe() {\n  // 注入：引用 I 域未完结端点，但整个文件没有任何未完结标注\n  return "downloadDocTemplate";\n}\n');
  const red = await runGate();
  rmSync(TMP, { force: true });
  untrackProbe(TMP);
  const back = await runGate();
  const keywordOk = (red.out + red.err).includes('unsettled-surfaced');
  const ok = red.code === 1 && keywordOk && back.code === 0;
  results.push({
    name: 'I10 把未完结端点当既定事实用（新建页无标注）',
    ok,
    why: ok
      ? `门禁 exit=${red.code}，命中失败项「unsettled-surfaced」，删除后 exit=${back.code}`
      : `期望 exit=1 + 「unsettled-surfaced」+ 还原绿；实得 exit=${red.code} / `
        + `关键词${keywordOk ? '命中' : '未命中'} / 还原 exit=${back.code}`,
  });
}

// ---------------------------------------------------------------------------
// I11 在 api/client.ts 里重新写出裸令牌键名（第二处定义）
//      ⇒ 期望 token-single-source 报红
// 🛑 这一组是本轮**实测缺陷**的回归用例：`session.ts` 写 `'dy.token'`、
//    `api/client.ts` 读 `'token'` ⇒ Authorization 头静默为空、全量 401，
//    而 tsc / 构建 / 其它判据全不报（第 50 条同族的跨文件隐式约定）。
// ---------------------------------------------------------------------------
await injection({
  name: 'I11 在 api/client.ts 里直接读裸令牌键名',
  path: CLIENT,
  from: '  const token = readToken();',
  to: "  const token = localStorage.getItem('token');",
  expectGate: 'token-single-source',
});

// ---------------------------------------------------------------------------
// I12 在某个页面里再写一处令牌写入（第三种存法）
//      ⇒ 期望 token-single-source 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I12 在页面里另写一处 localStorage 令牌写入',
  path: SCOPE,
  from: 'export function isConsoleRole(role: unknown): role is ConsoleRole {',
  to: "export function __probeWriteToken(t: string): void {\n"
    + "  localStorage.setItem('dy.auth.token', t);\n"
    + '}\n\n'
    + 'export function isConsoleRole(role: unknown): role is ConsoleRole {',
  expectGate: 'token-single-source',
});

// ---------------------------------------------------------------------------
// I13 用"词"而非"调用"满足 unsettled-surfaced（第 52 条的新宿主）
//      ⇒ 期望 unsettled-surfaced 报红
// ---------------------------------------------------------------------------
// 🛑 本仓第 52 条的形态是"判据被字面量/关键词满足"。⑦ 判据重写后，
//    必须证明它**不再**被"只是提到词"满足：
//    · 把标注写成**注释**里的 `unsettledOf`（剥注释后应消失）；
//    · 把 `contractNoteOf` 调用的**参数换成普通端点 id**（未完结集合不含它）；
//    · 保留端点 id 字面量（否则根本进不了判据的检查集合）。
//    三种同时注入，只要任一被放过就说明判据仍在"认词"。
{
  const TMP = join(SRC, 'pages', '__probe_wordonly.tsx');
  trackProbe(TMP);
  writeFileSync(
    TMP,
    'export function Probe() {\n'
    + '  // unsettledOf unsettledEndpoints frontier rulingPending  ← 只在注释里提到词\n'
    + "  const note = contractNoteOf('getCustomer'); // 参数是【普通】端点，不是未完结端点\n"
    + "  return ['publishDocTemplateVersion', note];\n"
    + '}\n',
  );
  const red = await runGate();
  rmSync(TMP, { force: true });
  untrackProbe(TMP);
  const back = await runGate();
  const keywordOk = (red.out + red.err).includes('unsettled-surfaced');
  const ok = red.code === 1 && keywordOk && back.code === 0;
  results.push({
    name: 'I13 只用词/普通参数满足 unsettled-surfaced（注释里的词 + contractNoteOf(普通id)）',
    ok,
    why: ok
      ? `门禁 exit=${red.code}，命中「unsettled-surfaced」（判据不再认词），还原 exit=${back.code}`
      : `期望 exit=1 + 「unsettled-surfaced」+ 还原绿；实得 exit=${red.code} / `
        + `关键词${keywordOk ? '命中' : '未命中'} / 还原 exit=${back.code}`,
  });
}

// ---------------------------------------------------------------------------
// I14 给 NAV 新增第三条形态的 requires（判据覆盖面没跟上 ⇒ 静默漏检）
//      ⇒ 期望 nav-requires 报红
// ---------------------------------------------------------------------------
// 🛑 这正是第 53 条的形态：本仓在别处已实锤过两次（x3-check.mjs 的 I8）。
//    注入用一个**内联三元**——它既不是字符串字面量、也不是"无参函数调用"，
//    故两条计数式子都不命中；若判据没有**计数等式**，这条会静默漏过。
await injection({
  name: 'I14 给 NAV 新增第三种 requires 形态（内联三元，判据两条式子都不命中）',
  path: APP,
  from: "  { key: 'docs', label: '文书模板', requires: 'listDocTemplates' },",
  to: "  { key: 'docs', label: '文书模板', requires: (0 ? 'listDocTemplates' : null) as string | null },"
    + "\n  { key: 'extra', label: '额外页', requires: ['getCustomer'].join('') },",
  expectGate: 'nav-requires',
});

// ---------------------------------------------------------------------------
// I15 把某个已封装端点从界面上摘掉（"封装了但从未接上界面"）
//      ⇒ 期望 endpoint-reachability 报红
// ---------------------------------------------------------------------------
// 🛑 这正是本轮实测的形态：D 域 5 个端点（createVisit / submitDailyReportAsStaff /
//    createPlan / getPlan / createDeviceDispatch）在 `domain.ts` 里封装得好好的，
//    但**没有任何页面调用它们** —— "页面覆盖 39 个端点"在那时是**自我声称**。
//    本条注入把某个确认被页面调用的封装函数名从页面里改掉，模拟"摘掉界面入口"。
await injection({
  name: 'I15 把已封装端点从界面上摘掉（封装了但从未接上界面）',
  path: join(SRC, 'pages', 'CustomerConsolePage.tsx'),
  from: '              setVisits((await listVisits(customerId)) ?? null);',
  to: '              setVisits((await listVisitsRenamed(customerId)) ?? null);',
  expectGate: 'endpoint-reachability',
});

// ---------------------------------------------------------------------------
// I16 让页面绕过 services 层直接出站（分层纪律失效）
//      ⇒ 期望 endpoint-reachability 报红
// ---------------------------------------------------------------------------
// 🛑 判据形态（第 52 条教训）：判**出站调用形态**出现在哪个层，
//    不判"页面里有没有 call 这个词"（注释里的 call 不算 —— 判据先剥注释）。
//    故这里注入一个真实的 `call('...')` **调用**（不是 `void call`、不是注释）。
await injection({
  name: 'I16 页面绕过 services 层直接出站（分层纪律失效）',
  path: join(SRC, 'pages', 'UnsettledPage.tsx'),
  from: 'export default function UnsettledPage({ roleLabel }: { roleLabel: string }) {',
  to: 'export default function UnsettledPage({ roleLabel }: { roleLabel: string }) {\n'
    + "  void call('authMe', {}); // 注入：页面直接出站（应被 endpoint-reachability 抓住）",
  expectGate: 'endpoint-reachability',
});

// ---------------------------------------------------------------------------
// I17 【第 65 条核心回归】把某个必需的"实参"从调用点里摘掉
//      ⇒ 期望 required-args-wired 报红
// ---------------------------------------------------------------------------
// 🛑 为什么这条用例必须是"摘掉实参"而不是"删掉整个调用"：
//    删掉调用会被 ⑩ `endpoint-reachability` 抓住（那是另一条判据），
//    于是本条用例的**证据价值为零** —— 它必须证明的是
//    「**调用还在、就是实参少了一个**」这种形态也能被抓住。
//    这正是第 65 条缺口的原形：C1 调了、C2 也调了，字段名都在，就是值不对/少一个。
await injection({
  name: 'I17 摘掉 C2 的可核实必需实参（scale_id 换成臆造值同义：整项删除）',
  path: join(SRC, 'pages', 'CustomerConsolePage.tsx'),
  from: '              scale_id: bankScaleId,\n',
  to: '',
  expectGate: 'required-args-wired',
});

// ---------------------------------------------------------------------------
// I18 【第 66 条核心回归】把一个长调用点的必需实参放到"固定窗口之外"
//      ⇒ 期望 required-args-wired **不**误报（即判据已按括号配平取文本）
// ---------------------------------------------------------------------------
// 🛑 这条用例与 I17 的方向**相反**，理由是第 66 条是一次"误报"缺陷：
//    固定窗口太小会把**写对了的**字段判成"缺"。
//    故本用例是**负向**的：在调用点前面插入一大段噪声，把必需实参推出 400 字符窗口；
//    判据修好之后它应当**仍然绿**（若判据退化回固定窗口，本用例立刻红）。
//    ⇒ "判据有牙齿"包含两件事：**该抓的必须抓到**（I17）+ **不该抓的不得抓到**（I18）。
await injection({
  name: 'I18 把必需实参推出固定窗口（判据须按括号配平，不得误报）',
  path: join(SRC, 'pages', 'CustomerConsolePage.tsx'),
  from: '            await createVerdict(assessId.trim(), {',
  to: '            await createVerdict(assessId.trim(), {\n'
    + "              // 注入：把后续必需实参推离调用点 400 字符以外（第 66 条回归；"
    + "本行是注释、会被 stripComments 剥掉，故改用真实表达式占位）\n"
    + '              ...(String("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") ? {} : {}),'
    + '\n',
  expectGate: undefined,
  expectExit: 0, // 🛑 负向用例：期望**仍然绿**
});

// ---------------------------------------------------------------------------
// I19 【第 67 条核心回归】必需实参经**局部变量**传入时，判据须追到所在块
//      ⇒ 期望 required-args-wired 不误报
// ---------------------------------------------------------------------------
// 🛑 第 67 条的形态：`submitBaselineAssessment(customerId, body, key)` ——
//    6 个必需字段写在调用点**之外**的 `const body = {...}` 里。
//    只取调用单元会把"写对了的"判成"全缺"（实测：一次性报出 6 项全缺）。
//    本用例把该调用改成"传字面量对象"——若判据仍只认局部变量形态，它会**误报**；
//    判据修好后必须仍绿。⇒ 判据对"实参怎么传"应保持中立。
await injection({
  name: 'I19 必需实参经内联字面量传入（判据不得只认局部变量形态）',
  path: join(SRC, 'pages', 'CustomerConsolePage.tsx'),
  from: '            await submitBaselineAssessment(customerId, body, newIdempotencyKey());',
  to: '            await submitBaselineAssessment(customerId, {\n'
    + '              scale_id: bankScaleId,\n'
    + '              item_group_id: bankItemGroupId,\n'
    + '              age_group_locked: ageGroup,\n'
    + '              dimension_scores: scores,\n'
    + '              total_score: scores.reduce((a, b) => a + b, 0),\n'
    + '              measure_operator: measureOperator.trim(),\n'
    + '            }, newIdempotencyKey());',
  expectGate: undefined,
  expectExit: 0, // 🛑 负向用例：期望**仍然绿**
});

// ---------------------------------------------------------------------------
// I20 【第 70 条核心回归 · 载体错位】把必需参数从**对的载体**挪到**错的载体**
//      ⇒ 期望 required-args-carrier 报红
// ---------------------------------------------------------------------------
// 🛑 为什么这条用例不能用"删掉实参"（那是 I17 的形态）：
//    第 70 条的缺口与第 65 条**不同** —— 名字仍然出现、且出现的位置也合法，
//    只是装进了错的载体。删掉它只能证明"缺名"会被抓，**证明不了"错位"会被抓**。
//    后端按 in:query / in:body 取值 ⇒ `version` 从 query 挪到 params 后，
//    它仍在调用点文本里（⑩b 绿），但后端 `@RequestParam("version")` 必然 400。
await injection({
  name: 'I20 把 I7 的必需 query 参数 version 挪进 params 载体（载体错位）',
  path: join(SRC, 'services', 'domain.ts'),
  from: '    params: { id: templateId },\n    query: { version: v },',
  to: '    params: { id: templateId, version: v },\n    query: {},',
  expectGate: 'required-args-carrier',
});

// ---------------------------------------------------------------------------
// I21 【第 71 条核心回归 · path 参数漏传】把 URL 占位符 `{id}` 的实参从 params 摘掉
//      ⇒ 期望 required-args-wired 报红
// ---------------------------------------------------------------------------
// 🛑 为什么单独立一条：path 参数此前**从未被转录**（契约用 `$ref` 复用命名参数 23 处
//    + 内联 `in: path`，生成器两种形态都跳过）⇒ 30 个端点里的 path 占位符
//    在过去**没有任何判据见过**。漏传的后果是静默的：`fillPath()` 只替换 params 里
//    出现过的键、不做残留检查 ⇒ 会把字面量 `{id}` 拼进 URL 发出去（后端路由不匹配）。
//    本用例把 `getCustomer` 的 `params: { id: customerId }` 换成 `params: {}`。
await injection({
  name: 'I21 摘掉 getCustomer 的 path 占位符实参 id（URL 会拼出字面量 {id}）',
  path: join(SRC, 'services', 'domain.ts'),
  from: "  return call<CustomerDetail>('getCustomer', { params: { id: customerId } }).then((r) => r.data);",
  to: "  return call<CustomerDetail>('getCustomer', { params: {} }).then((r) => r.data);",
  expectGate: 'required-args-wired',
});

// ---------------------------------------------------------------------------
// I22~I27 【第 83 条 · 契约外能力面】⑫ `internal-capability-registry` 的六组注入
// ---------------------------------------------------------------------------
// 🛑 为什么需要整整六组（第 53 条教训：判据的每一条**子规则**都必须有一条用例）
// ---------------------------------------------------------------------------
// ⑫ 有四条互相独立的子规则。只注"清册与后端台账不一致"这一种，等于只证明了
// 其中一条有牙齿 —— 而"未被证明有牙齿的那几条"与"没写"在效果上无法区分。
//   ① 两侧一致（I22：把 path 改成后端台账里没有的）
//   ② 无死声明（I23：把调用点的能力 id 改名 ⇒ 声明了却没人用）
//   ②′ **只认生产**（I27：生产调用消失、单测仍在调 ⇒ 旧口径的静默漏检点）
//   ③ 分层纪律（I24：页面里直接 fetch）
//   ④ 协议常量（I25：把 PROTOCOL.AUTH_HEADER 换成字面量）
//   ⑤ ⑨ 第三形态的实参核验（I26：NAV 引用一个清册里不存在的能力 id）
// 每一组都只触发**一条**子规则，且都断言"还原后必须变绿"。

/** 契约外清册的绝对路径（五组注入里有三组改它）。 */
const CAP_REG = join(SRC, 'contract', 'internal-capabilities.ts');

// I22 ① 两侧一致：把运维自检的 path 漂移成台账里不存在的形态
await injection({
  name: 'I22 清册 path 漂移（/ops/health → /ops/healthz，后端台账里没有）',
  path: CAP_REG,
  from: "    path: '/ops/health',",
  to: "    path: '/ops/healthz',",
  expectGate: 'internal-capability-registry',
});

// I23 ② 无死声明：把出站调用点的能力 id 改名 ⇒ 那一条"声明了却没人用"
await injection({
  name: 'I23 出站调用点的能力 id 改名（声明了却没人用 = 死声明）',
  path: join(SRC, 'services', 'ops.ts'),
  from: "  return callInternal<OpsHealth>('getOpsHealth').then((r) => r.data);",
  to: "  return callInternal<OpsHealth>('getOpsHealthProbe').then((r) => r.data);",
  expectGate: 'internal-capability-registry',
});

// I24 ③ 分层纪律：页面里直接 fetch（绕过 api/internal.ts + 清册）
await injection({
  name: 'I24 页面里直接 fetch（绕过出站通道 ⇒ 契约外端点变成未登记出站面）',
  path: join(SRC, 'pages', 'OpsHealthPage.tsx'),
  from: '  async function load() {',
  to: "  async function load() {\n"
    + "    void fetch('/api/v1/ops/health'); // 注入：页面绕过出站通道直接打内部端点\n",
  expectGate: 'internal-capability-registry',
});

// I25 ④ 协议常量：把第二条出站通道的鉴权头名换成手写字面量（第 57 条）
await injection({
  name: 'I25 契约外通道里手写协议字面量（PROTOCOL.AUTH_HEADER → \'Authorization\'）',
  path: join(SRC, 'api', 'internal.ts'),
  from: 'headers[PROTOCOL.AUTH_HEADER] = `${PROTOCOL.AUTH_SCHEME} ${token}`;',
  to: "headers['Authorization'] = `${PROTOCOL.AUTH_SCHEME} ${token}`;",
  expectGate: 'internal-capability-registry',
});

// I26 ⑤ ⑨ 第三形态的实参核验：NAV 引用一个清册里不存在的能力 id
//     🛑 这条证明的是"带参推导函数**不是**给契约外能力开后门" ——
//        它的实参同样必须能在真源里查到（与 ① 形态的 id 必须命中生成物同级）。
await injection({
  name: 'I26 NAV 引用清册里不存在的能力 id（带参推导形态的实参核验）',
  path: APP,
  from: "requires: internalCapabilityId('getOpsHealth') },",
  to: "requires: internalCapabilityId('getOpsHealthProbe') },",
  expectGate: 'nav-requires',
});

// I27 【第 83 条 · 判据"太宽"的孪生面】② 必须只认**生产**文件的出站调用形态
// ---------------------------------------------------------------------------
// 🛑 它防的是哪一类缺陷（第 52 条家族：**假绿 —— 被非生产出现满足**）
// ---------------------------------------------------------------------------
// ② 问的是"这条清册条目**有人真的在用**吗"。若把 `services/` 下的**单测**也算作
// "在用"，那么"生产调用被删掉、只剩单测还在调它"就会**长期绿** —— 而那种状态下
// 该能力面对用户已经彻底不可达（清册条目成了纸面声明）。
// 这与第 52 条"代码里出现了某个名字 ≠ 那个名字被使用了"同构，只是宿主换成了"单测"。
//
// 🛑 为什么必须**两个文件一起动**（这也是给注入器加 `also` 的由来）
// ---------------------------------------------------------------------------
// 只动生产（把调用式换成变量实参）会被**新老两种口径一起**抓住 ⇒ 那证明的是
// I23 已证过的东西，对本条新判别力**零信息量**。
// 只有 (a) 生产调用消失 + (b) 单测提供同 id 的调用形态，计数等式才重新成立、
// "死声明"才被掩盖 —— 那正是旧口径的静默漏检点，也正是本组要证明新口径抓住了它。
await injection({
  name: 'I27 生产调用消失而单测仍在调（② 若把单测算作"在用"就会假绿）',
  path: join(SRC, 'services', 'ops.ts'),
  from: "  return callInternal<OpsHealth>('getOpsHealth').then((r) => r.data);",
  to: "  const __probe_cap: string = 'getOpsHealth';\n"
    + '  return callInternal<OpsHealth>(__probe_cap).then((r) => r.data);',
  also: [{
    path: join(SRC, 'services', 'ops.test.ts'),
    from: "import { describe, it, expect, vi, afterEach } from 'vitest';",
    to: "import { describe, it, expect, vi, afterEach } from 'vitest';\n"
      + "import { callInternal } from '../api/internal';\n"
      + "void callInternal('getOpsHealth'); // 注入：单测里的调用形态（旧口径会把它算作「有人在用」）",
  }],
  expectGate: 'internal-capability-registry',
});

// ---------------------------------------------------------------------------
// 汇总
// ---------------------------------------------------------------------------
console.log('===== 端 A 反向验证（每组：注入 → 必须变红 → 还原 → 必须变绿）=====');
let pass = 0;
for (const r of results) {
  console.log(`  ${r.ok ? '✓ 抓住' : '✗ 漏过'}  ${r.name}`);
  console.log(`          ${r.why}`);
  if (r.ok) pass += 1;
}
console.log(`\n合计 ${pass}/${results.length}`);

const finalState = (await runGate()).code;
console.log(`还原后门禁：exit=${finalState} ${finalState === 0 ? '（绿 ✓）' : '（红 ✗ —— 还原不干净！）'}`);

// 🛑 收尾自检：本脚本**不得**把自己的临时探针留在源树里。
//    背景（本轮实测）：本脚本被强制终止时（`kill -9` / 上层进程消失），
//    信号处理器跑不到，探针会残留；而残留的**后果落在下一次读代码的人身上** ——
//    `a-check` 会报出一个指名道姓指向该探针文件、读起来像真实源码缺陷的失败项。
//    故把"有没有泄漏"变成**本脚本自己的失败信号**，而不是留给下一次偶遇。
//    （三层防线：启动自愈 · 信号清理 · 本收尾自检 —— 各管一段，不是重复。）
const leftover = readdirSync(join(SRC, 'pages')).filter((n) => /^__probe_.*\.tsx$/.test(n));
if (leftover.length) {
  console.error(`\n✗ 本脚本泄漏了临时探针文件（未自行清理）: ${leftover.join(', ')}`);
  console.error('  这是一个需要修掉的缺陷 —— 泄漏的探针会让下一次 a-check 报出'
    + '「看起来像真实源码缺陷」的失败项。请检查信号处理与用例的清理路径。');
  process.exit(1);
}

if (pass !== results.length || finalState !== 0) process.exit(1);
console.log('\n端 A 反向验证 PASS —— 门禁确实有牙齿，且还原干净。');