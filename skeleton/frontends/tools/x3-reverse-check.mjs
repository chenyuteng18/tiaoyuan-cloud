/**
 * 端 B · X-3 门禁的反向验证（证明门禁有牙齿）
 * ============================================================================
 *
 * 🛑 为什么必须做这一步（本仓反复登记的教训）
 * ---------------------------------------------------------------------------
 * "门禁全绿"本身**不是**门禁有效的证据 —— 它也可能是"判据从未真正执行过"
 * （第 41/42/43 条：静默假绿）。唯一能证明门禁承重的做法是：
 * **主动注入一个必须被抓住的错误，断言门禁确实变红**；然后再断言还原后变绿。
 *
 * 本脚本做 6 组注入，每组都改一处**真实文件**，跑门禁，记录退出码，
 * 然后**逐字节还原**并复验绿。
 *
 * 🛑 关于"注入必须带来可观测差异"（第 46 条的教训）
 * ---------------------------------------------------------------------------
 * 一个注入若与"原样"在行为上等价，它报出的红就是**假红**，会误导。
 * 故每组注入都先断言"注入确实改动了文件内容"。
 *
 * 用法：node frontends/tools/x3-reverse-check.mjs
 * 退出码：0 = 全部按预期（门禁有牙齿且还原干净）· 1 = 有注入未被抓住
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = join(HERE, '..');
const END_B = join(FRONTENDS, 'therapist-app');
const SRC = join(END_B, 'src');
const NODE = process.execPath;

/**
 * 🛑 必须用**异步** spawn，不能用 execFileSync / spawnSync。
 * ---------------------------------------------------------------------------
 * 本仓已有登记（frontends/README §3「退出码」段）：本环境下**同步**派生子进程
 * 会被拦（`EBUSY`），异步 spawn 正常。本脚本初版用了 `execFileSync`，
 * 结果基线就报 `exit=99`（不是门禁真的红，而是**子进程根本没起来**）——
 * 那正是一种"环境受限被误读成结论"的形态：若不分辨，会把"跑不动"
 * 当成"门禁有问题"，然后去改一个本来正确的门禁。
 * 故：一律异步；且区分"退出码"与"起不来"。
 */
function runGate() {
  return new Promise((resolve) => {
    let out = '';
    let err = '';
    const child = spawn(NODE, [join(HERE, 'x3-check.mjs')], { cwd: END_B });
    child.stdout.on('data', (d) => { out += String(d); });
    child.stderr.on('data', (d) => { err += String(d); });
    child.on('error', () => resolve({ code: 99, out: '', err: '' })); // 起不来
    child.on('close', (code) => resolve({ code: code === null ? 99 : code, out, err }));
  });
}

const GEN = join(SRC, 'contract', 'endpoints.ts');
const ACCESS = join(SRC, 'contract', 'access.ts');
const CLIENT = join(SRC, 'api', 'client.ts');
const APP = join(SRC, 'App.tsx');
const BAND = join(SRC, 'pages', 'BandPage.tsx');

const backup = new Map();
function stash(path) {
  backup.set(path, readFileSync(path, 'utf8'));
}
function restoreAll() {
  for (const [p, t] of backup) writeFileSync(p, t);
}

const results = [];

/** 一组注入：改文件 → 断言内容**真的变了** → 跑门禁 → 还原 → 复验绿。全程异步。 */
async function injection({ name, path, from, to, expectExit = 1, expectGate }) {
  const original = readFileSync(path, 'utf8');
  stash(path);
  if (from !== undefined) {
    if (!original.includes(from)) {
      results.push({ name, ok: false, why: `注入锚点未找到：${JSON.stringify(from.slice(0, 60))}` });
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
// 基线：改动前门禁必须先是绿的（否则后面判红无法归因）
// ---------------------------------------------------------------------------
const baseline = (await runGate()).code;
if (baseline !== 0) {
  console.error(`ABORT: 基线门禁就不是绿的（exit=${baseline}），无法做反向验证。先修好再跑。`);
  process.exit(1);
}
console.log('基线：门禁绿 ✓\n');

// ---------------------------------------------------------------------------
// 注入 1：把某个「仅经络师」端点的角色改成两角色都有
//         ⇒ 期望 x3-meridian-only 报红（集合多/少一个）
// ---------------------------------------------------------------------------
await injection({
  name: 'I1 把 F2 listVerdicts 改成两角色都授予',
  path: GEN,
  from: '    id: "listVerdicts",\n    row: "F2",\n    method: "GET",\n    path: "/customers/{id}/verdicts",\n    grantedRoles: Object.freeze(["meridian"]),',
  to: '    id: "listVerdicts",\n    row: "F2",\n    method: "GET",\n    path: "/customers/{id}/verdicts",\n    grantedRoles: Object.freeze(["therapist", "meridian"]),',
  expectGate: 'x3-meridian-only',
});

// ---------------------------------------------------------------------------
// 注入 2：把某个端点的角色整个清空
//         ⇒ 期望 roles 报红（grantedRoles 为空）
// ---------------------------------------------------------------------------
await injection({
  name: 'I2 把 A1 authLogin 的 grantedRoles 清空',
  path: GEN,
  from: '    id: "authLogin",\n    row: "A1",\n    method: "POST",\n    path: "/auth/login",\n    grantedRoles: Object.freeze(["therapist", "meridian"]),',
  to: '    id: "authLogin",\n    row: "A1",\n    method: "POST",\n    path: "/auth/login",\n    grantedRoles: Object.freeze([]),',
  expectGate: 'roles',
});

// ---------------------------------------------------------------------------
// 注入 3：在准入层里写死一份端点清单（第二份权威）
//         ⇒ 期望 x3-no-hardcoded-list 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I3 在 access.ts 里硬编码「仅经络师」行号清单',
  path: ACCESS,
  from: 'export const ACCEPTED_ROLES: readonly string[] = END_TOKEN_ROLES;',
  to: 'const MERIDIAN_ONLY_HARDCODED = ["D5-c", "F1", "F2", "G1", "G2", "G3", "G5"];\n'
    + 'export const ACCEPTED_ROLES: readonly string[] = END_TOKEN_ROLES;\n'
    + 'void MERIDIAN_ONLY_HARDCODED;',
  expectGate: 'x3-no-hardcoded-list',
});

// ---------------------------------------------------------------------------
// 注入 4：在某个页面里手写角色条件（绕过单一权威面）
//         ⇒ 期望 x3-single-authority 报红
// 🛑 注入必须是**真实的角色判断形态**（`role === "meridian"`），
//    不能是 `void "meridian"` 这类无意义引用 —— 后者不构成判断，
//    用它做注入即使门禁报红也是"假红"（理由站不住），本仓第 46 条教训。
//    本门禁的判据也已相应收紧为"比较/成员判断形态"（见 x3-check.mjs ⑤）。
// ---------------------------------------------------------------------------
await injection({
  name: 'I4 在 BandPage.tsx 里手写 role === "meridian" 条件',
  path: BAND,
  from: 'const gap = tel?.gap_reason ? GAP_REASON_LABEL[tel.gap_reason] : undefined;',
  to: 'const isMeridian = role === "meridian";\n'
    + 'const gap = tel?.gap_reason && isMeridian ? GAP_REASON_LABEL[tel.gap_reason] : undefined;',
  expectGate: 'x3-single-authority',
});

// ---------------------------------------------------------------------------
// 注入 5：把出站层的准入断言摘掉（"写了但没接上"）—— 保留为**提及但不调用**
//         ⇒ 期望 x3-wired 报红
// 🛑 本注入刻意写成 `void assertCanCall;`（**这个词仍在文件里**）：
//    初版门禁用 `includes('assertCanCall')` 判据，**被这一行满足而漏过**
//    （反向验证首跑 6/7，唯一漏过就是它）。判据随后收紧为"调用形态"。
//    故本注入的形态必须保留 —— 它是那个缺陷的**回归用例**。
// ---------------------------------------------------------------------------
await injection({
  name: 'I5 从 api/client.ts 摘掉 assertCanCall 调用（仅保留提及）',
  path: CLIENT,
  from: '  assertCanCall(operationId, opts.role);',
  to: '  void assertCanCall; // 注入：故意不调用（模拟"写了但没接上"）',
  expectGate: 'x3-wired',
});

// ---------------------------------------------------------------------------
// 注入 6：给 call() 的 opts 加回默认值 {}
//         ⇒ 期望 x3-role-required 报红（漏传 role 不再是编译错误）
// ---------------------------------------------------------------------------
await injection({
  name: 'I6 给 call() 的 opts 加回默认值 {}',
  path: CLIENT,
  from: '  opts: CallOptions\n): Promise<{ data: T | undefined; traceId: string; status: number }> {',
  to: '  opts: CallOptions = {}\n): Promise<{ data: T | undefined; traceId: string; status: number }> {',
  expectGate: 'x3-role-required',
});

// ---------------------------------------------------------------------------
// 注入 7：让导航项引用一个不属于本端的端点 id
//         ⇒ 期望 nav 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I7 把导航项 requires 改成一个不存在的端点 id',
  path: APP,
  from: "{ key: 'customer', label: '客户详情', requires: 'getCustomer' },",
  to: "{ key: 'customer', label: '客户详情', requires: 'listCustomers' },",
  expectGate: 'nav',
});

// ---------------------------------------------------------------------------
// 注入 8：把导航项的 requires 换成【未被判据覆盖的第三种形态】
//         ⇒ 期望 nav 报红（且**必须是"形态未覆盖"这一条**，不是别的）
// ---------------------------------------------------------------------------
// 🛑 这一组来自本轮真实撞见的一次**静默漏检**：
//    初版 nav 判据只认字面量 `/requires:\s*'([^']+)'/`，
//    「专属动作」改成 `requires: firstSoleEndpointId()`（调用形态）后，
//    该导航项**根本没被检查**，而输出仍是
//    `✓ nav: 导航项依赖的 2 个端点都在本端生成物里` —— **数字是错的**。
//    修法 = 判据同时认字面量 + 调用形态，并加一条**交叉核对**
//    （认出的条数必须等于非 null 的 requires 条数），
//    这样"又冒出了第三种写法"这件事会自己报出来。
// 本注入正是那条交叉核对的回归用例：写入一个内联三元（既非字面量也非调用）。
await injection({
  name: 'I8 把导航项 requires 换成未被判据覆盖的形态（内联三元）',
  path: APP,
  from: "{ key: 'band', label: '手环数据', requires: 'getBandTelemetry' },",
  to: "{ key: 'band', label: '手环数据', requires: (0 ? 'getBandTelemetry' : null) as string | null },",
  expectGate: 'nav',
});

// ---------------------------------------------------------------------------
// 注入 9：把某端点的**封装函数从界面上摘掉**
//         ⇒ 期望 endpoint-reachability 报红（第 63 条判据的牙齿证明）
// ---------------------------------------------------------------------------
// 🛑 这一组来自本轮实测的真实缺口：端 B 曾有 **16/29** 个端点
//    「封装了但从未接上界面」，而旧版 x3-check 的 7 条判据**一条都没管这件事**
//    ⇒ tsc / vite / 全部门禁如实全绿，缺陷静默存活。
//    本注入复刻那个形态：保留 `services/domain.ts` 里的封装（出站形态仍在），
//    但把 ServicePage 里对 `createVisit()` 的调用删掉 ⇒ 只剩"封装函数无界面"。
const SERVICE_PAGE = join(SRC, 'pages', 'ServicePage.tsx');
await injection({
  name: 'I9 摘掉 createVisit 的界面调用（封装仍在，仅断链）',
  path: SERVICE_PAGE,
  // 把调用点里的函数名换成一个不存在的名字 ⇒ 判据的 `\bcreateVisit\s*\(`
  // 匹配不到 ⇒ 触发"封装了但未被任何页面调用"这一条。
  // 🛑 刻意**不**用"注释掉"的方式做注入：本判据会 stripComments2，
  //    注释掉的调用对它不可见，但那样注入就变成"改了个看不见的东西"，
  //    无法证明判据真的在执行（第 46 条：注入必须带来可观测差异）。
  from: 'const v = await createVisit(',
  to: 'const v = await createVisitInjectedBroken(',
  expectGate: 'endpoint-reachability',
});

// ---------------------------------------------------------------------------
// 注入 10：把 services 层的出站调用删掉（端点彻底不发出）
//          ⇒ 期望 endpoint-reachability 报红（且必须命中"未以出站调用形态出现"）
// ---------------------------------------------------------------------------
// 与 I9 的区别：I9 断的是"界面 ← 封装"这段，本组断的是"封装 ← 出站"这段。
// 两段都必须各自有牙齿 —— 只守一段会让另一段上的断链继续静默存活
// （本仓第 60 条"守错了层级"的教训）。
const DOMAIN_SVC = join(SRC, 'services', 'domain.ts');
await injection({
  name: 'I10 删掉 listStores 的 services 出站调用',
  path: DOMAIN_SVC,
  from: "return call<StoreList>('listStores', { role, query: pageQuery(page, pageSize) })",
  to: 'return Promise.resolve(undefined)',
  expectGate: 'endpoint-reachability',
});

// ---------------------------------------------------------------------------
// 注入 11：【第 65 条核心回归】摘掉一个必需的"实参"
//          ⇒ 期望 required-args-wired 报红（本仓第 65 条）
// ---------------------------------------------------------------------------
// 🛑 为什么必须是"摘实参"而不是"删调用"：删调用会被 ⑧ endpoint-reachability
//    抓住（另一条判据），本用例的证据价值就归零 —— 它要证明的是
//    「**调用还在、就是实参少了一个**」这种形态也能被抓住。
//    这正是第 65 条的原形：端点点到了、字段名都在，就是值不对/少一个，
//    而此前 `tsc` / 构建 / 触达判据**一律绿**。
await injection({
  name: 'I11 摘掉 C2 的必需实参 item_group_id（调用仍在、实参少一个）',
  path: join(SRC, 'pages', 'AssessmentPage.tsx'),
  from: '                  item_group_id: String(picked?.item_group_id ?? \'\'),',
  to: '                  item_group_id__removed: String(picked?.item_group_id ?? \'\'),',
  expectGate: 'required-args-wired',
});

// ---------------------------------------------------------------------------
// 汇总
// ---------------------------------------------------------------------------
console.log('===== 反向验证结果（每组：注入 → 必须变红 → 还原 → 必须变绿）=====');
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
console.log('\nX-3 反向验证 PASS —— 门禁确实有牙齿，且还原干净。');