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

const results = [];

/** 一组注入：改文件 → 断言真变了 → 跑门禁 → 还原 → 复验绿。 */
async function injection({ name, path, from, to, expectExit = 1, expectGate }) {
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
  const red = await runGate();
  restoreAll();
  const back = await runGate();

  const keywordOk = expectGate ? (red.out + red.err).includes(expectGate) : true;
  const ok = red.code === expectExit && keywordOk && back.code === 0;
  results.push({
    name,
    ok,
    why: ok
      ? `门禁 exit=${red.code}，命中失败项「${expectGate}」，还原后 exit=${back.code}`
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
await injection({
  name: 'I1 删掉 I5 端点的 frontier（模拟"元信息被丢掉"）',
  path: GEN,
  from: '    id: "listDocTemplateVersions",\n    row: "I5",\n    method: "GET",\n    path: "/doc-templates/{id}/versions",\n    grantedRoles: Object.freeze(["admin"]),\n    frontier: "占位待冻结",',
  to: '    id: "listDocTemplateVersions",\n    row: "I5",\n    method: "GET",\n    path: "/doc-templates/{id}/versions",\n    grantedRoles: Object.freeze(["admin"]),',
  expectGate: 'xkey-values',
});

// ---------------------------------------------------------------------------
// I2 把某端点的 rowScope 整类删掉
//     ⇒ 期望 xkey-values 报红（x-row-scope 契约 2 处 vs 生成物 1 处）
// ---------------------------------------------------------------------------
await injection({
  name: 'I2 删掉 F4 端点的 rowScope',
  path: GEN,
  from: '    row: "F4",\n    method: "GET",\n    path: "/audit/coverage",\n    grantedRoles: Object.freeze(["admin"]),\n    rowScope:',
  to: '    row: "F4",\n    method: "GET",\n    path: "/audit/coverage",\n    grantedRoles: Object.freeze(["admin"]),\n    xRemovedScope:',
  expectGate: 'xkey-values',
});

// ---------------------------------------------------------------------------
// I3 把 superAdminOnly 从 true 改成 false
//     ⇒ 期望 xkey-values 报红（契约 1 处 true vs 生成物 0 处）
// ---------------------------------------------------------------------------
await injection({
  name: 'I3 把 I7 的 superAdminOnly 由 true 改成 false',
  path: GEN,
  from: '    id: "downloadDocTemplate",\n    row: "I7",\n    method: "GET",\n    path: "/doc-templates/{id}/download",\n    grantedRoles: Object.freeze(["admin"]),\n    superAdminOnly: true,',
  to: '    id: "downloadDocTemplate",\n    row: "I7",\n    method: "GET",\n    path: "/doc-templates/{id}/download",\n    grantedRoles: Object.freeze(["admin"]),\n    superAdminOnly: false,',
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
  writeFileSync(TMP, 'export function Probe() {\n  // 注入：引用 I 域未完结端点，但整个文件没有任何未完结标注\n  return "downloadDocTemplate";\n}\n');
  const red = await runGate();
  rmSync(TMP, { force: true });
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

if (pass !== results.length || finalState !== 0) process.exit(1);
console.log('\n端 A 反向验证 PASS —— 门禁确实有牙齿，且还原干净。');