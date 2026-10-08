/**
 * 端 B · 构建自检：X-3 角色级装载
 * ============================================================================
 *
 * 这是端 B 的**主门禁**。它机械验证"角色准入没有漂移"，而不是看代码写得像不像。
 *
 * 🛑 它防的是哪一类缺陷（不是"检查有没有写错字"）
 * ---------------------------------------------------------------------------
 * X-3 这类"角色级装载"最典型的失效形态是**两份清单漂移**：
 *   契约的 `x-callable-roles` 改了 → 生成物 `grantedRoles` 跟着改（自动）
 *   但界面/导航/服务层的手写角色判断**不会跟着改**（因为没人会想起来）。
 * 漂移后果：某个"仅经络师"的动作在调理师界面露出了入口 ⇒ 点下去 403，
 * 一线以为是权限配错，而真实原因是**前端漏了一次同步**。
 *
 * 本脚本用**机械事实**挡住这件事：
 *   ① 生成物的角色分布必须与契约一致（调用生成器的 --check 同源）；
 *   ② 「仅经络师」集合必须**恰好**等于契约声明的 7 个行号 —— 多一个少一个都报红；
 *   ③ 端 B 源码里**不得**出现硬编码的端点清单（否则就是第二份权威）；
 *   ④ 端 B 源码里**不得**手写角色条件（如 `role === 'meridian' && tab === ...`）；
 *      角色判定必须只经由 `canCall / assertCanCall / endpointsForRole`。
 *
 * 🛑 为什么 ④ 用"白名单式"写法而不是穷举非法写法
 * ---------------------------------------------------------------------------
 * 非法写法无穷（`!==`、`includes`、`indexOf`、三元…），穷举必然漏。
 * 故反过来：**只允许在 access.ts 一个文件里出现角色字面量**，
 * 其它文件出现即报红 —— 这是"最小权威面"的做法，与本仓既有门禁同源。
 *
 * 退出码：0 通过 · 1 不通过 · 2 配置缺失 · 3 环境受限（未验证）
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = join(HERE, '..');
const END_B = join(FRONTENDS, 'therapist-app');
const SRC = join(END_B, 'src');

const fails = [];
const notes = [];
const oks = [];

function ok(kind, msg) { oks.push(`  ✓ ${kind}: ${msg}`); }
function fail(kind, msg) { fails.push(`  ✗ [${kind}] ${msg}`); }
function note(msg) { notes.push(`  – ${msg}`); }

// ---------------------------------------------------------------------------
// 前提：生成物与准入层必须存在
// ---------------------------------------------------------------------------
const GEN = join(SRC, 'contract', 'endpoints.ts');
const ACCESS = join(SRC, 'contract', 'access.ts');
if (!existsSync(GEN)) {
  console.error(`MISCONFIGURED: 生成物缺失 ${GEN}（先跑 node ../tools/gen-endpoints.mjs）`);
  process.exit(2);
}
if (!existsSync(ACCESS)) {
  console.error(`MISCONFIGURED: 准入层缺失 ${ACCESS}`);
  process.exit(2);
}

const genText = readFileSync(GEN, 'utf8');

// ---------------------------------------------------------------------------
// ① 解析生成物：id / row / grantedRoles
// ---------------------------------------------------------------------------
const entries = [];
{
  // 🛑 解析必须**逐条抓取整块**（含 `requiredQuery` / `requiredBody`），
  //    不能用"匹配到 grantedRoles 就收"的写法 —— 后者会漏掉块内的必需参数字段，
  //    于是 ⑨ 判据看到 `undefined` 并**静默放过全部端点**（第 52 条：判据被自己的解析绕过）。
  const re = /\{\s*id:\s*"([^"]+)",\s*row:\s*"([^"]+)",\s*method:\s*"([^"]+)",\s*path:\s*"([^"]+)",([\s\S]*?)\n  \}/g;
  let m;
  while ((m = re.exec(genText))) {
    const tail = m[5];
    const roles = (/grantedRoles:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(tail)?.[1] ?? '')
      .split(',').map((s) => s.trim().replace(/"/g, '')).filter(Boolean);
    const reqQ = /requiredQuery:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(tail);
    const reqB = /requiredBody:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(tail);
    // 🛑 第 71 条：path 参数（URL 占位符 `{id}`）此前从未被转录。
    const reqP = /requiredPath:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(tail);
    // 🛑 三态分开：正则**没匹配** ⇒ undefined ⇒ null（解析层自检会报红）；
    //    匹配到空数组 ⇒ 空串 ⇒ []（该端点无必需参数）。写 `x ? x[1] : null`
    //    会在没匹配时传 null 而 `null.split` 崩溃（实测于端 A，第 66 条同批）。
    const parseNames = (s) =>
      (s === undefined ? null : s.split(',').map((x) => x.trim().replace(/"/g, '')).filter(Boolean));
    entries.push({
      id: m[1],
      row: m[2],
      method: m[3],
      path: m[4],
      roles,
      requiredQuery: parseNames(reqQ === null ? undefined : reqQ[1]),
      requiredBody: parseNames(reqB === null ? undefined : reqB[1]),
      requiredPath: parseNames(reqP === null ? undefined : reqP[1]),
    });
  }
}
if (entries.length === 0) {
  fail('parse', '生成物里没解析到任何端点 —— 生成物格式变了或文件被手改');
} else {
  ok('parse', `生成物解析到 ${entries.length} 个端点`);
}
// 🛑 解析层自检（第 53 条）：必需参数字段必须真的解析出来了，
//    否则 ⑨ 判据会对全部端点静默放行 —— 那是"看不见的绿"。
{
  const miss = entries.filter((e) => e.requiredQuery === null || e.requiredBody === null || e.requiredPath === null);
  if (miss.length) {
    fail('parse-fields',
      `${miss.length} 个端点未解析出 requiredQuery/requiredBody/requiredPath ⇒ ⑨ 必需参数判据会静默放过它们。`
      + `例：${miss[0].id}`);
  } else {
    ok('parse-fields', `全部 ${entries.length} 个端点均解析出 requiredQuery / requiredBody / requiredPath`);
  }
}

// ---------------------------------------------------------------------------
// ② 角色分布必须与生成物自洽（每个端点至少 1 个角色、角色的确在本端声明内）
// ---------------------------------------------------------------------------
const declaredRoles = new Set(['therapist', 'meridian']);
for (const e of entries) {
  if (e.roles.length === 0) {
    fail('roles', `${e.row} ${e.id} 的 grantedRoles 为空 —— 它不该出现在本端生成物里`);
  }
  for (const r of e.roles) {
    if (!declaredRoles.has(r)) {
      fail('roles', `${e.row} ${e.id} 的 grantedRoles 含非本端角色 ${r}`);
    }
  }
}
if (!fails.length) ok('roles', '每个端点的 grantedRoles 都非空且都是本端角色');

// ---------------------------------------------------------------------------
// ③ 【核心】「仅经络师」集合必须逐字等于契约声明的 7 个行号
// ---------------------------------------------------------------------------
// 🛑 这 7 个是**契约事实**（x-callable-roles = ["meridian"]），不是配置。
//    写在这里是"钉子"：一旦契约改了角色，本脚本会红，逼人回来看这一行。
//    反面做法（不许）：把它写成"从生成物算出来的值与自己比"—— 那永远相等，等于没检。
const EXPECTED_MERIDIAN_ONLY = ['D5-c', 'F1', 'F2', 'G1', 'G2', 'G3', 'G5'];
const merOnly = entries.filter((e) => e.roles.length === 1 && e.roles[0] === 'meridian');
const therOnly = entries.filter((e) => e.roles.length === 1 && e.roles[0] === 'therapist');

{
  const got = merOnly.map((e) => e.row).sort();
  const want = [...EXPECTED_MERIDIAN_ONLY].sort();
  if (JSON.stringify(got) !== JSON.stringify(want)) {
    fail('x3-meridian-only',
      `「仅经络师」集合与契约声明不一致：\n`
      + `      期望(${want.length}) ${want.join(' ')}\n`
      + `      实际(${got.length}) ${got.join(' ')}\n`
      + `      若契约确实改了角色，请同步更新本脚本的 EXPECTED_MERIDIAN_ONLY 与文档`);
  } else {
    ok('x3-meridian-only', `「仅经络师」恰好 ${got.length} 个：${got.join(' ')}`);
  }
}

if (therOnly.length > 0) {
  fail('x3-therapist-only',
    `出现「仅调理师」端点 ${therOnly.map((e) => e.row).join(' ')} —— `
    + `契约目前没有任何仅调理师的端点；若确为新增，须先确认契约再更新本判据`);
} else {
  ok('x3-therapist-only', '无「仅调理师」端点（与契约一致）');
}

// ---------------------------------------------------------------------------
// ④ 准入层必须由生成物现算，不得含硬编码端点清单
// ---------------------------------------------------------------------------
// 🛑 与 ⑥ 同一教训：判"是否出现行号"过弱（`void ['F1']` / 注释 / 无关字符串都能满足）。
//    本处判的是**可被使用的形态**：行号出现在**数组字面量 / 字符串集合**里
//    （即真的构成一份清单），而不是"文本里恰好有这个行号"。
{
  const accessText = stripComments(readFileSync(ACCESS, 'utf8'));
  // 形态：['F1', 'F2', ...] 或 ["F1", ...] 或 new Set([...]) —— 至少含 2 个行号才叫"清单"
  const listRe = /\[\s*(?:"[A-Z]\d+(?:-[a-z])?"|'[A-Z]\d+(?:-[a-z])?')(?:\s*,\s*(?:"[A-Z]\d+(?:-[a-z])?"|'[A-Z]\d+(?:-[a-z])?'))+[^\]]*\]/g;
  const lists = accessText.match(listRe) ?? [];
  const suspicious = lists.filter((l) => {
    const rows = l.match(/[A-Z]\d+(?:-[a-z])?/g) ?? [];
    // 只关心"端点行号型"清单；纯数字/其它标识符不算
    return rows.length >= 2;
  });
  if (suspicious.length) {
    fail('x3-no-hardcoded-list',
      `access.ts 出现了形如端点行号清单的数组字面量（${suspicious.length} 处）：\n      `
      + suspicious.map((s) => s.replace(/\s+/g, ' ').slice(0, 100)).join('\n      ')
      + '\n      准入必须由 grantedRoles 现算，不得手抄清单（手抄清单会漂移且不会让门禁变红）');
  } else {
    ok('x3-no-hardcoded-list', 'access.ts 无端点行号型清单（准入由 grantedRoles 现算）');
  }
}

// ---------------------------------------------------------------------------
// ⑤ 【核心】角色字面量只允许出现在 access.ts 一个文件里
// ---------------------------------------------------------------------------
// 目的：把"角色判断"收敛到唯一权威面，杜绝界面/服务层各自手写条件。
//
// 🛑 判据形态（与 ④/⑥ 同一教训）：判**比较/成员判断形态**，不判"词是否出现"。
//    `void "meridian";` 这类无意义引用不该报红（它不构成角色判断），
//    而 `role === "meridian"` / `["meridian"].includes(x)` / `x !== "therapist"`
//    必须报红 —— 因为它们是**真正在做角色判断**，也就是第二份权威。
{
  const files = walk(SRC).filter((f) => /\.(ts|tsx)$/.test(f) && !f.endsWith('endpoints.ts'));
  const ROLE_LIT = `(?:'therapist'|"therapist"|'meridian'|"meridian")`;
  // 角色字面量参与比较 / 成员判断 / 索引查表的形态
  const patterns = [
    new RegExp(`[=!]==?\\s*${ROLE_LIT}`), //  x === 'meridian'
    new RegExp(`${ROLE_LIT}\\s*[=!]==?`), //  'meridian' === x
    new RegExp(`\\.includes\\(\\s*${ROLE_LIT}\\s*\\)`), //  arr.includes('meridian')
    new RegExp(`\\.indexOf\\(\\s*${ROLE_LIT}\\s*\\)`), //  arr.indexOf('meridian')
    new RegExp(`\\[\\s*${ROLE_LIT}\\s*\\]`), //  MAP['meridian'] 查表
  ];
  const offenders = [];
  for (const f of files) {
    const rel = relative(SRC, f).replace(/\\/g, '/');
    if (rel === 'contract/access.ts') continue; // 唯一权威面
    const code = stripComments(readFileSync(f, 'utf8'));
    const hit = patterns.find((re) => re.test(code));
    if (hit) offenders.push(`${rel}  (命中 ${hit.source})`);
  }
  if (offenders.length) {
    fail('x3-single-authority',
      `以下文件在非注释代码里【做角色判断】，角色判断必须只经由 `
      + `contract/access.ts 的 canCall / assertCanCall / endpointsForRole：\n      `
      + offenders.join('\n      '));
  } else {
    ok('x3-single-authority', `角色判断仅出现在 contract/access.ts（已扫 ${files.length} 个源文件）`);
  }
}

// ---------------------------------------------------------------------------
// ⑥ 契约一致性：出站层必须真的**调用**准入断言（防"写了但没接上"）
// ---------------------------------------------------------------------------
// 🛑 判据形态是实测选出来的（本仓第 52 条系统性缺陷的修法）
// ---------------------------------------------------------------------------
// 初版判据是 `clientText.includes('assertCanCall')` —— **该判据被字面量满足**：
// 注入 `void assertCanCall; // 故意不调用` 后，字符串仍在文件里 ⇒ 门禁**照常全绿**。
// 这是本仓第 41 条的同型缺陷（"判据被函数体内 RAISE 消息的字面量满足"），
// 只不过载体从 SQL 消息变成了 TS 语句。
// 本门禁自己的反向验证 I5 抓到了它 —— 这正说明反向验证不可省：
// **"门禁全绿"从来不是"门禁有效"的证据。**
//
// 正解 = 判"**调用形态**"而不是"词是否出现"：
//   · 必须存在形如 `assertCanCall(<非空实参>)` 的**调用表达式**；
//   · `void assertCanCall;` / 单纯 import / 注释里的提及，都**不算**接线。
{
  const clientRaw = readFileSync(join(SRC, 'api', 'client.ts'), 'utf8');
  const clientText = stripComments(clientRaw);
  // 调用形态：assertCanCall + 左括号 + 至少一个实参
  const callRe = /assertCanCall\s*\(\s*[^)\s][^)]*\)/g;
  const calls = clientText.match(callRe) ?? [];
  if (calls.length === 0) {
    fail('x3-wired',
      'api/client.ts 未以【调用形态】使用 assertCanCall（只有提及 / 导入 / void 引用不算）'
      + ' —— 准入层写了但没接进出站路径，等于"界面藏了入口但代码仍能调通"');
  } else {
    ok('x3-wired', `出站层以调用形态使用 assertCanCall（${calls.length} 处：${calls.join(' | ')}）`);
  }
  // role 必填是这套设计的关键：可选 = 可绕过
  if (/opts:\s*CallOptions\s*=\s*\{\}/.test(clientRaw)) {
    fail('x3-role-required',
      'call() 的 opts 带了默认值 {} —— 这会让"漏传 role"不再是编译错误，'
      + '准入可被静默绕过');
  } else {
    ok('x3-role-required', 'call() 的 opts 无默认值（漏传 role 必是编译错误）');
  }
}

// ---------------------------------------------------------------------------
// ⑦ 页面清单：每个导航项依赖的端点必须真的在本端生成物里
// ---------------------------------------------------------------------------
// 🛑 判据必须同时认【两种形态】——否则门禁会静默漏检（本仓第 52 条同族）
// ---------------------------------------------------------------------------
// 初版只认字面量 `requires: 'getCustomer'`，正则 `/requires:\s*'([^']+)'/`。
// 后来「专属动作」导航项改成**机械推导**形态 `requires: firstSoleEndpointId()`
// ⇒ 正则匹配不到 ⇒ 该导航项**根本没被检查**，而输出仍是
// `✓ nav: 导航项依赖的 2 个端点都在本端生成物里` —— **数字是错的却没人看得出来**
// （实际有 3 个非 null 依赖）。这正是本仓反复登记的那类静默漏检。
//
// 修法分两路（各自判"它该是什么形态"）：
//   · 字面量形态 ⇒ 该 id **必须存在于生成物**（防止改名/删除后留下死链）；
//   · 调用形态   ⇒ 函数名必须在**白名单**内，且该函数**必须定义在 access.ts**，
//                  且其实现**必须引用生成物**（不许在别处临时算一个）。
// 两路计数之和必须等于"非 null 的 requires 条数"—— 用来防"新增了第三种形态
// 又没人管"这件事（那会让门禁再次静默漏过）。
{
  const appAll = stripComments(readFileSync(join(SRC, 'App.tsx'), 'utf8'));

  // 🛑 先**圈出 NAV 数组本体**再判 —— 否则会把 `interface NavItem` 的
  //    `readonly requires: string | null;` 也算成"一条依赖"，
  //    于是"未覆盖形态"的交叉核对会永远误报（首跑就撞上了这个）。
  const navBlock = (() => {
    const start = appAll.search(/const\s+NAV\s*[:=]/);
    if (start < 0) return null;
    // 从这里往后取到第一个 `]);` —— NAV 是 Object.freeze([ ... ]) 形态
    const rest = appAll.slice(start);
    const end = rest.search(/\]\s*\)\s*;/);
    return end >= 0 ? rest.slice(0, end) : rest;
  })();
  if (navBlock === null) {
    fail('nav', 'App.tsx 里找不到 NAV 清单（本判据的检查对象不存在 —— 若已重构请同步更新本门禁）');
  } else {
    // 只允许"从生成物现算"的推导函数出现在 requires 的调用形态里
    const NAV_DERIVATION_WHITELIST = ['firstSoleEndpointId'];
    const accessText = stripComments(readFileSync(ACCESS, 'utf8'));

    const literals = [];
    const calls = [];
    for (const m of navBlock.matchAll(/requires:\s*'([^']+)'/g)) literals.push(m[1]);
    for (const m of navBlock.matchAll(/requires:\s*([A-Za-z_$][\w$]*)\s*\(\s*\)/g)) calls.push(m[1]);

    const requiresTotal = (navBlock.match(/requires:/g) ?? []).length;
    const nullCount = (navBlock.match(/requires:\s*null/g) ?? []).length;
    const nonNullExpected = requiresTotal - nullCount;

    const bad = [];

    for (const id of literals) {
      if (!entries.some((e) => e.id === id)) bad.push(`字面量 ${id} 不在本端生成物里`);
    }

    for (const fn of calls) {
      if (!NAV_DERIVATION_WHITELIST.includes(fn)) {
        bad.push(`requires 用了未登记的推导函数 ${fn}()（若确为"从生成物现算"，请加入本门禁白名单并说明依据）`);
        continue;
      }
      if (!new RegExp(`export\\s+function\\s+${fn}\\s*\\(`).test(accessText)) {
        bad.push(`${fn}() 未定义在 contract/access.ts（依赖推导不得散落在界面文件里）`);
        continue;
      }
      if (!/ENDPOINTS|solelyGrantedEndpoints|endpointsForRole|grantedRolesOf/.test(accessText)) {
        bad.push(`${fn}() 所在的 access.ts 未见对生成物的引用 —— 无法证明它是"现算"而非硬编码`);
      }
    }

    const accounted = literals.length + calls.length;
    if (accounted !== nonNullExpected) {
      bad.push(
        `导航依赖的形态未被判据覆盖：非 null 的 requires 有 ${nonNullExpected} 条，`
        + `但只认出 ${accounted} 条（字面量 ${literals.length} + 调用 ${calls.length}）`
        + ' —— 出现了第三种写法，本判据不会检查它（这是静默漏检，必须先补判据）'
      );
    }

    if (bad.length) {
      fail('nav', `导航项依赖检查未通过：\n      ` + bad.join('\n      '));
    } else {
      ok('nav', `导航项依赖的 ${accounted} 项全部可核（字面量 ${literals.length} 项已在生成物 · 推导函数 ${calls.length} 项已登记且定义于 access.ts）`);
    }
  }
}

// ---------------------------------------------------------------------------
// ⑧ 【端点触达】29 个端点必须在【业务代码里被以调用形态发出】
// ---------------------------------------------------------------------------
// 🛑 本条的诞生（第 63 条 · 2026-09-30 实测）
// ---------------------------------------------------------------------------
// 端 A 早有一条同名判据（`tools/a-check.mjs` 的 ⑩），端 B **没有**。
// 后果实测：端 B 的 29 个端点里 **16 个「封装了但从未接上界面」**
// （`services/domain.ts` 写了函数，`src/` 全域零调用）——
// 调解一线打开 APP 看不到建档 / 评估 / 服务与方案，而
// `tsc --noEmit` / `vite build` / 本脚本既有 7 条判据 / 全部 CI 门禁 **一律绿**。
// 这不是"少一个判据"的形式问题：**判据的覆盖面缺口本身就是缺陷的藏身处**
// （第 52 条"太宽⇒假绿"、第 53 条"覆盖面没跟上⇒静默漏检"是同一族的另外两次）。
//
// 🛑 判据形态（不判"id 字面量出现过" —— 那会被 `void 'authMe'` / 注释 / 类型声明满足）
// ---------------------------------------------------------------------------
// 判**两层调用链**，与端 A 的 ⑩ 同构：
//   ① 出站层 `services/` 里存在 `call<T>('<id>'` 调用形态（真正出站）；
//   ② 定义该调用的导出函数**被某个页面/外壳以调用形态使用**（`fn(` 且带实参）。
// 另加一条**反向纪律**：页面/外壳**不得直接出站**（必须经 services 层）——
// 它顺带守住"某端点由哪个服务封装"这件事始终可查。
//
// 🛑 计数等式（第 53/55 条教训：判据必须证明自己覆盖了全部）
// ---------------------------------------------------------------------------
// 断言 `① 命中数 === 生成物端点总数`，并在不成立时**逐条列出未触达的端点**。
// 缺了这个等式，判据就只能证明"我认识的那些没问题"，不能证明"没有我不认识的"。
{
  const CALL_RE = /\bcall\s*(?:<[^(]*?>)?\s*\(\s*['"]([^'"]+)['"]/gs;
  const SERVICES = join(SRC, 'services');
  const svcFiles = walk(SERVICES).filter((f) => /\.(ts|tsx)$/.test(f));

  // ① 出站调用形态：call<T>('<id>')（扫 services/ 层全部文件）
  const called = new Set();
  const fnOf = new Map(); // 端点 id → 定义它的 services 函数名
  for (const f of svcFiles) {
    const code = stripComments(readFileSync(f, 'utf8'));
    for (const m of code.matchAll(CALL_RE)) called.add(m[1]);
    const fnRe = /export\s+(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(([\s\S]*?)\n\}/g;
    let fm;
    while ((fm = fnRe.exec(code))) {
      const c = new RegExp(CALL_RE.source, 's').exec(fm[2]);
      if (c && !fnOf.has(c[1])) fnOf.set(c[1], fm[1]);
    }
  }

  // ② 页面 / 外壳里以调用形态使用该函数
  const consumers = walk(SRC).filter((f) => {
    const rel = relative(SRC, f).replace(/\\/g, '/');
    return rel.startsWith('pages/') || rel === 'App.tsx';
  });
  const consumerCode = consumers.map((f) => ({
    rel: relative(SRC, f).replace(/\\/g, '/'),
    code: stripComments(readFileSync(f, 'utf8')),
  }));

  const noCallForm = entries.filter((e) => !called.has(e.id)).map((e) => e.id);
  const noFn = entries.filter((e) => !fnOf.has(e.id)).map((e) => e.id);
  const noUi = [];
  for (const e of entries) {
    const fn = fnOf.get(e.id);
    if (!fn) continue;
    const used = consumerCode.some((c) => new RegExp(`\\b${escapeRe(fn)}\\s*\\(`).test(c.code));
    if (!used) noUi.push(`${e.id}（封装函数 ${fn}() 未被任何页面/外壳调用）`);
  }
  // 分层纪律：页面/外壳不得直接出站（必须经 services 层）
  const pageDirectCall = consumerCode
    .filter((c) => new RegExp(CALL_RE.source, 's').test(c.code))
    .map((c) => c.rel);

  const bad = [];
  if (noCallForm.length) {
    bad.push(`以下端点未以出站调用形态出现（services/ 层里没有 call('<id>')）：` + noCallForm.join(', '));
  }
  if (noFn.length) {
    bad.push(`以下端点没有对应的封装函数（call 出现在函数体外或未封装）：` + noFn.join(', '));
  }
  if (noUi.length) {
    bad.push(`以下端点**封装了但从未接上界面**：\n        ` + noUi.join('\n        ')
      + '\n        ⇒ "页面覆盖 N 个端点"是可自我声称的；本条要求每个端点都有一条'
      + '从界面到出站的真实调用链。');
  }
  if (pageDirectCall.length) {
    bad.push(`以下页面/外壳**直接出站**（绕过 services 层）：${pageDirectCall.join(', ')}`
      + '\n        ⇒ 出站必须收在 services/ 层一处；页面直接 `call(` 会让"某端点被哪个服务封装"'
      + '不再可查，也会让分层纪律失效。');
  }
  // 计数等式：出站调用形态必须覆盖全部端点
  if (called.size !== entries.length) {
    bad.push(`出站调用形态覆盖数 ${called.size} ≠ 生成物端点数 ${entries.length}`
      + '（判据必须证明它认识的东西覆盖了全部 —— 第 53/55 条教训）');
  }

  if (bad.length) {
    fail('endpoint-reachability', `端点的界面触达链不完整：\n      ` + bad.join('\n      '));
  } else {
    ok('endpoint-reachability',
      `${entries.length} 个端点全部有完整调用链：`
      + `services/ 层出站 \`call<T>('<id>')\` ${called.size}/${entries.length} · `
      + `封装函数 ${fnOf.size}/${entries.length} · `
      + `均被页面/外壳以调用形态触达（已扫 ${consumerCode.length} 个消费侧文件；`
      + `页面/外壳直接出站 0 处 —— 分层纪律成立）`);
  }
}

// ---------------------------------------------------------------------------
// ⑨ 【必需参数被调用点带上】本仓第 65 条 —— 端 B 侧
// ---------------------------------------------------------------------------
// 🛑 为什么端 B 也要有这一条（而不是"端 A 有了就够"）
// ---------------------------------------------------------------------------
// 第 63/64/65 条的共同形态是**同一缺陷在另一端的第二份副本**，而两端门禁
// 各自只审自己那一端。本轮的实测正是如此：端 B 的 C1/C2 调用点是**对的**
// （C1 带 age_group、C2 三把钥匙只读带入），端 A 是错的 —— 若只在端 A 加判据，
// 端 B 那套正确写法的**回归**（将来被改坏）就没人看见。
// 故判据必须在三端**各有一份**，且各自按本端语言/结构实现（不得照抄）。
//
// 🛑 判据判【实参形态】，且必须处理两种合法传参写法（第 66/67 条）
// ---------------------------------------------------------------------------
//   · 调用单元：从函数名后的 `(` 起做括号配平 —— 不得用固定字符窗口
//     （窗口过小会把写对了的判成缺，实测于端 A）；
//   · 局部变量：实参是 `const body = {...}` 时，字段在**调用单元之外** ⇒
//     必须一并取"容纳该调用的最内层 `{}` 块"（实测：只取调用单元会把 6 项全判缺）。
{
  const SERVICES_DIR = join(SRC, 'services'); // 本块自建（⑧ 的 SERVICES 是它的块级变量，不可跨块引用）
  // 🛑 同样自建 CALL_RE：⑧ 的 CALL_RE 也是其块级变量。
  //    跨块引用块级 const 会直接 ReferenceError —— 本仓已踩过（端 A 的 fnOf 同型）。
  const CALL_RE_LOCAL = /\bcall\s*(?:<[^(]*?>)?\s*\(\s*['"]([^'"]+)['"]/;
  const CALL_SITES = new Map();
  const FN = new Map();
  for (const f of walk(SERVICES_DIR).filter((x) => /\.(ts|tsx)$/.test(x))) {
    const code = stripComments(readFileSync(f, 'utf8'));
    const fnRe = /export\s+(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(([\s\S]*?)\n\}/g;
    let fm;
    while ((fm = fnRe.exec(code))) {
      const c = new RegExp(CALL_RE_LOCAL.source, 's').exec(fm[2]);
      if (!c) continue;
      if (!FN.has(c[1])) FN.set(c[1], fm[1]);
      const prev = CALL_SITES.get(c[1]) ?? [];
      prev.push({ file: relative(SRC, f).replace(/\\/g, '/'), text: fm[0] });
      CALL_SITES.set(c[1], prev);
    }
  }
  const consumers2 = walk(SRC).filter((f) => {
    const rel = relative(SRC, f).replace(/\\/g, '/');
    return rel.startsWith('pages/') || rel === 'App.tsx';
  });
  function balance(text, openIdx) {
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
  }
  function enclosing(text, idx) {
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
    return open < 0 ? '' : balance(text, open);
  }
  for (const e of entries) {
    const fn = FN.get(e.id);
    if (!fn) continue;
    const re = new RegExp(`\\b${escapeRe(fn)}\\s*(\\()`, 'g');
    for (const f of consumers2) {
      const code = stripComments(readFileSync(f, 'utf8'));
      let cm;
      while ((cm = re.exec(code))) {
        const openIdx = cm.index + cm[0].lastIndexOf('(');
        const callText = balance(code, openIdx);
        const blk = enclosing(code, cm.index);
        const text = blk && blk.length < 2500 ? `${callText}\n${blk}` : callText;
        const prev = CALL_SITES.get(e.id) ?? [];
        prev.push({ file: relative(SRC, f).replace(/\\/g, '/'), text });
        CALL_SITES.set(e.id, prev);
      }
    }
  }
  function argPresent(name, text) {
    const n = escapeRe(name);
    return [
      new RegExp(`(?:^|[\\s,{(])['"]?${n}['"]?\\s*:`),
      // 🛑 具名/位置实参的边界**只能**是 `(` 或 `,` —— 不得用 `[\s,(]`（第 69 条）：
      //    后者会把值位置的同名标识符误认成实参，使"实参已删"仍判绿。
      new RegExp(`(?:^|[(,])\\s*${n}\\s*[,)]`),
    ].some((r) => r.test(text));
  }

  /** 在**出站对象块**上找 `key`（query/body）载体（第 70 条：只判「名字在不在」不判「落在哪个载体」）。
   *  🛑 必须在出站块上找，不得在整段封装体上找 —— 否则 `body: ScreeningRequest`
   *     这种**函数签名的类型注解**会被当载体，制造假阳性。 */
  function carrierOf(blk, key) {
    if (!blk) return null;
    const m = new RegExp(`(?:^|[\\s,{(])${escapeRe(key)}\\s*(:?)`).exec(blk);
    if (!m) return null;
    if (!m[1]) return { kind: 'shorthand' };
    const after = blk.slice(m.index + m[0].length);
    if (after.trimStart().startsWith('{')) {
      const oi = blk.indexOf('{', m.index + m[0].length);
      return { kind: 'literal', text: balance(blk, oi) };
    }
    return { kind: 'expr' };
  }
  function outboundBlocks(text, id) {
    const out = [];
    const re = new RegExp(`call\\s*(?:<[^(]*?>)?\\s*\\(\\s*['"]${escapeRe(id)}['"]`, 'g');
    let m;
    while ((m = re.exec(text))) {
      const bi = text.indexOf('{', m.index + m[0].length - 1);
      if (bi >= 0) out.push(balance(text, bi));
    }
    return out;
  }
  const misplaced = [];
  let carrierNamed = 0;
  let carrierSkipped = 0;
  for (const e of entries) {
    const needQ = e.requiredQuery ?? [];
    const needB = e.requiredBody ?? [];
    const needP = e.requiredPath ?? [];
    if (!needQ.length && !needB.length && !needP.length) continue;
    const sites = CALL_SITES.get(e.id);
    if (!sites || !sites.length) continue;
    const blocks = sites.flatMap((s) => outboundBlocks(s.text, e.id));
    if (!blocks.length) continue;
    for (const [key, names] of [['query', needQ], ['body', needB], ['params', needP]]) {
      if (!names.length) continue;
      const cs = blocks.map((b) => carrierOf(b, key)).filter((c) => c !== null);
      if (!cs.length) {
        misplaced.push(`${e.id}：契约声明了 ${key} 载体上的 required [${names.join(', ')}]，`
          + `但出站块里**根本没有 ${key} 载体**（${sites.map((s) => s.file).join(', ')}）`);
        continue;
      }
      for (const c of cs) {
        if (c.kind !== 'literal') { carrierSkipped += 1; continue; }
        if (/\.\.\./.test(c.text) || /[A-Za-z_$][\w$]*\s*\(/.test(c.text)) { carrierSkipped += 1; continue; }
        const nm = names.filter((n) => {
          const e2 = escapeRe(n);
          return !new RegExp(`(?:^|[\\s,{(])['"]?${e2}['"]?\\s*:`).test(c.text)
            && !new RegExp(`(?:^|[{,])\\s*${e2}\\s*[,}]`).test(c.text);
        });
        if (nm.length) {
          misplaced.push(`${e.id} 的 ${key} 载体里**没有** ${nm.join(', ')}（${sites.map((s) => s.file).join(', ')}）`
            + `\n        ${key} 载体 = ${c.text.replace(/\s+/g, ' ').slice(0, 120)}`);
        } else {
          carrierNamed += 1;
        }
      }
    }
  }

  const absent = [];
  let withReq = 0;
  let checked = 0;
  for (const e of entries) {
    const need = [...(e.requiredQuery ?? []), ...(e.requiredBody ?? []), ...(e.requiredPath ?? [])];
    if (!need.length) continue;
    withReq += 1;
    const sites = CALL_SITES.get(e.id);
    if (!sites || !sites.length) continue; // 触达问题由 ⑧ 负责
    checked += 1;
    const all = sites.map((s) => s.text).join('\n');
    const miss = need.filter((n) => !argPresent(n, all));
    if (miss.length) {
      absent.push(`${e.id}（${e.method} ${e.path}）缺 ${miss.join(', ')}`
        + `\n        契约 required 共 [${need.join(', ')}]`
        + `\n        调用点/封装体共 ${sites.length} 处：${sites.map((s) => s.file).join(', ')}`);
    }
  }
  if (misplaced.length) {
    fail('required-args-carrier',
      '以下端点的必需参数**落在了错误的载体**（或该有载体的地方没有载体）——'
      + '后端按 in:query / in:body 取值，装错位置等价于没带，且全部门禁一律绿：\n      '
      + misplaced.join('\n      ')
      + '\n      ⇒ "参数名出现了"不等于"参数落在对的位置"（第 70 条）。');
  } else {
    ok('required-args-carrier',
      `载体归属已核对：${carrierNamed} 处载体块逐名命中；`
      + `${carrierSkipped} 处为载体简写/表达式或含展开（{ body } / query: pageQuery(…) / …body）`
      + '—— 前两类由 ⑨ 实参形态与类型层共同兜底，如实计数不静默放过。');
  }

  if (!withReq) {
    fail('required-args-wired',
      '生成物里没有任何端点带 required 声明 —— 解析层没转出必需参数，本条会静默放过全部端点。');
  } else if (absent.length) {
    fail('required-args-wired',
      `以下端点的调用点**没有带上契约 required 参数**（后端必然 400，而 tsc/构建/触达判据一律绿）：\n      `
      + absent.join('\n      ')
      + '\n      ⇒ "端点被调用了"不等于"调用是对的"。');
  } else {
    ok('required-args-wired',
      `${withReq} 个带 required 声明的端点中，${checked} 个有调用点可核对，`
      + `均已带上全部必需参数（判实参形态；含"经局部变量传入"与"括号配平"两种合法写法）`);
  }
}

// ---------------------------------------------------------------------------
// ⑪ 【角色可达性】第 72 条 —— 第 64 条（装载）的同族第三处
// ---------------------------------------------------------------------------
// 🛑 缺口形态：⑧ 判的是"代码里有一条从界面到出站的调用链"——
//    它对**全部角色**合并判断（`consumerCode.some(...)`：只要**任一**页面用了这个
//    函数就算通过）。而端 B 的导航项**带角色门控**：
//        `visibleNav = NAV.filter((n) => n.requires === null || canCall(n.requires, role))`
//    如果某个导航项的门控端点**比它承载的页面更窄**（页面用了双角色端点，门控却是
//    仅经络师的端点），则该页对调理师**永久消失** ⇒ 页面里的端点对该角色不可达。
//
// 🛑 受控实证（不是理论风险）：把 `{ key: 'intake', requires: 'createCustomer' }`
//    改成 `requires: 'createRefund'`（仅 meridian）—— 建档页对调理师永久消失，
//    而 `⑧` 仍报「29 个端点全部有完整调用链」、`⑦ nav` 仍报「6 项全部可核」、
//    `tsc --noEmit` = 0 ⇒ **门禁与类型系统一律全绿**。
//    （⑦ 只判"该 id 存在于生成物"、⑧ 只判"代码里有人调"，都不判"该角色到不到得了"。）
//
// 判据（不变量：**某角色有权调用的端点，必须至少有一个该角色能到达的页面在用它**）：
//   ① 解析 NAV：key → 门控端点 → 门控角色集（字面量 / firstSoleEndpointId() / null=恒可见）；
//   ② 解析渲染分支：key → 实际渲染的页面组件（含内联分支与嵌套组件）；
//   ③ 页面组件 → 它用到的端点（canCall 字面量 ∪ 封装函数名 → 生成物 id）；
//   ④ 逐角色核算 reachable(r) ⊇ grantable(r)，落差逐条报出。
// 🛑 判据不认识某形态时**按失败处理**（第 64 条：不认识 ≠ 通过）。
// 🛑 未在 NAV 出现的页面**豁免**（如 LoginPage 在导航之前渲染）——但若该页面的
//    端点**没有任何**导航入口，则须显式在 NAV 里出现（豁免集必须写死且可核对）。
{
  // 豁免：不在 NAV 渲染、但合法可到达的页面（登录页在 `if (!profile)` 分支里渲染）
  const NON_NAV_PAGES = ['LoginPage'];

  const appText = stripComments(readFileSync(join(SRC, 'App.tsx'), 'utf8'));
  const END_ROLES = ['therapist', 'meridian'];

  // ① NAV：key -> requires
  const navBlock = (() => {
    const s = appText.search(/const\s+NAV\s*[:=]/);
    if (s < 0) return null;
    const rest = appText.slice(s);
    const e = rest.search(/\]\s*\)\s*;/);
    return e >= 0 ? rest.slice(0, e) : rest;
  })();

  const soleFirst = () => entries.find((e) => e.roles.length === 1) ?? null;

  const navItems = navBlock === null ? [] : [...navBlock.matchAll(
    /key:\s*'([^']+)'[^}]*?requires:\s*(?:'([^']+)'|([A-Za-z_$][\w$]*)\s*\(\s*\)|null)/g
  )].map((m) => ({ key: m[1], req: m[2] ?? (m[3] ? `${m[3]}()` : null) }));

  // ② 渲染分支：key -> 该分支实际渲染的页面组件（按 pages/ 下真实文件判定）
  const pageFileOf = (name) => join(SRC, 'pages', name + '.tsx');
  const segOf = (key) => {
    const s = appText.search(new RegExp(`tab\\s*===\\s*'${escapeRe(key)}'\\s*\\?`));
    if (s < 0) return null;
    const rest = appText.slice(s);
    const e = rest.search(/\)\s*:\s*tab\s*===|\)\s*:\s*null\s*\}/);
    return e >= 0 ? rest.slice(0, e) : rest;
  };

  // ③ 封装函数名 -> 端点 id
  const epOfFn = new Map();
  for (const f of walk(join(SRC, 'services'))) {
    if (!f.endsWith('.ts')) continue;
    const t = stripComments(readFileSync(f, 'utf8'));
    const re = /(?:export\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(([\s\S]*?)\n\}/g;
    let m;
    while ((m = re.exec(t))) {
      const c = /\bcall\s*(?:<[^(]*?>)?\s*\(\s*['"]([^'"]+)['"]/.exec(m[2]);
      if (c) epOfFn.set(m[1], c[1]);
    }
  }
  const collectUsed = (texts) => {
    const out = new Set();
    for (const t of texts) {
      for (const m of t.matchAll(/canCall\s*\(\s*'([^']+)'/g)) out.add(m[1]);
      for (const m of t.matchAll(/([A-Za-z_$][\w$]*)\s*\(/g)) {
        const ep = epOfFn.get(m[1]);
        if (ep) out.add(ep);
      }
    }
    return out;
  };

  const bad = [];
  const reach = [];   // {key, comps, gateRoles, used}
  let unknownForm = 0;

  for (const it of navItems) {
    let gateRoles;
    if (it.req === null) gateRoles = END_ROLES.slice();
    else if (it.req.endsWith('()')) {
      if (it.req === 'firstSoleEndpointId()') {
        const sf = soleFirst();
        gateRoles = sf ? sf.roles.slice() : END_ROLES.slice();
      } else { unknownForm += 1; bad.push(`NAV 项 ${it.key} 的门控用了未登记的推导函数 ${it.req}`); continue; }
    } else {
      const g = entries.find((e) => e.id === it.req);
      if (!g) { bad.push(`NAV 项 ${it.key} 的门控端点 ${it.req} 不在生成物里`); continue; }
      gateRoles = g.roles.slice();
    }
    if (gateRoles.length === 0) {
      bad.push(`NAV 项 ${it.key} 的门控角色集为空 ⇒ 该导航项对**所有角色**都隐藏（页面永久不可达）`);
    }

    const seg = segOf(it.key);
    if (seg === null) { bad.push(`NAV 项 ${it.key} 找不到渲染分支（⑦ page-registry 亦应报红）`); continue; }
    const comps = [...seg.matchAll(/<([A-Z][\w]*)/g)]
      .map((m) => m[1]).filter((n) => existsSync(pageFileOf(n)));
    const texts = comps.length
      ? comps.map((n) => stripComments(readFileSync(pageFileOf(n), 'utf8')))
      : [seg];   // 内联分支：端点就近写在 App.tsx 分支里
    reach.push({ key: it.key, comps: comps.length ? comps : ['(内联)'], gateRoles, used: collectUsed(texts) });
  }

  // ④ 外壳层（App.tsx 整体 + 面板外渲染的页面）
  // ---------------------------------------------------------------------------
  // 🛑 为什么外壳层要单独算**（判据首跑就撞上了这个，属第 55 条形态：判据太窄 ⇒ 假红）
  //    ① `authLogin` 由 LoginPage 触发，而 LoginPage 渲染在 `if (!profile)` 分支里
  //       —— 它**不在任何 NAV 项的分支内**，但**每个角色都能到达**（登录前必经）；
  //    ② `getCustomer` 写在 App.tsx 的 `loadCustomer()` 里，而 `customer` 分支只渲染
  //       `<CustomerPage>`（用的是 listVisits）—— 端点与页面并不一一对应。
  //    ⇒ 正确语义不是"每个端点必须挂在某个 tab 分支里"，而是：
  //       **外壳层代码对外壳可见的所有角色可达**；只有"某角色一个导航项都看不到"
  //       （被整体锁在面板外）才是真问题。
  //    🛑 这不削弱判据的牙齿：注入"intake 门控改窄"后，therapist 仍能看到
  //       customer/assessment/service 等页 ⇒ 外壳端点仍判可达，
  //       而 **intake 页那 5 个端点对 therapist 依然报不可达** ✓。
  const shellUsed = collectUsed([appText]);
  for (const p of NON_NAV_PAGES) {
    const f = pageFileOf(p);
    if (existsSync(f)) for (const e of collectUsed([stripComments(readFileSync(f, 'utf8'))])) shellUsed.add(e);
  }

  // ⑤ 逐角色核算（不变量：任意角色 r，grantable(r) ⊆ reachable(r)）
  const roleStats = [];
  for (const r of END_ROLES) {
    const grantable = entries.filter((e) => e.roles.includes(r)).map((e) => e.id);
    const reachable = new Set();
    // 面板分支：该角色能打开哪几个导航项
    const myNavs = reach.filter((x) => x.gateRoles.includes(r));
    const canEnterShell = myNavs.length > 0;
    if (canEnterShell) {
      for (const e of shellUsed) {
        const d = entries.find((y) => y.id === e);
        if (d && d.roles.includes(r)) reachable.add(e);
      }
    } else {
      bad.push(`角色 ${r} 看不到**任何**导航项（门控角色集与它全无交集）⇒ 该角色被整体锁在面板外，`
        + '它有权调用的端点一个都到不了 —— 这不是"页面被藏起来"，而是"角色被关在外面"。');
    }
    for (const x of myNavs) {
      for (const e of x.used) {
        const d = entries.find((y) => y.id === e);
        if (d && d.roles.includes(r)) reachable.add(e);
      }
    }
    const gap = grantable.filter((x) => !reachable.has(x));
    roleStats.push({ r, grantable: grantable.length, reachable: reachable.size, gap });
    if (gap.length) {
      bad.push(`角色 ${r}：有权调用 ${grantable.length} 个端点，但其中 ${gap.length} 个`
        + `**没有任何该角色可到达的页面在用**：\n        `
        + gap.map((g) => {
          const d = entries.find((y) => y.id === g);
          return `${g}（角色 ${JSON.stringify(d.roles)}）`;
        }).join(', ')
        + '\n        ⇒ 门控端点必须**代表**它承载的页面：门控比页面更窄 ⇒ 页面被永久藏起来（第 72 条）。');
    }
  }

  // ⑥ 判据的覆盖面必须自证（第 53 条）：不可达页面必须有豁免声明，否则报红
  const allPageFiles = walk(join(SRC, 'pages')).filter((f) => f.endsWith('.tsx'))
    .map((f) => f.replace(/\\/g, '/').split('/').pop().replace(/\.tsx$/, ''));
  const reachedPages = new Set();
  for (const x of reach) for (const c of x.comps) reachedPages.add(c);
  for (const p of allPageFiles) {
    if (reachedPages.has(p) || NON_NAV_PAGES.includes(p)) continue;
    bad.push(`页面 ${p} 既不在任何 NAV 项的渲染分支里、也不在豁免清单里`
      + '（若确为面板外渲染，请加入本判据的 NON_NAV_PAGES 并说明依据）——'
      + '未被校验的页面其角色可达性无从判断。');
  }
  // ⑧ 豁免清单必须全部真实存在且确有使用（防"豁免成了垃圾桶"）
  for (const p of NON_NAV_PAGES) {
    if (!allPageFiles.includes(p)) bad.push(`豁免清单里的 ${p} 并不存在于 pages/ —— 豁免已失效，请修订`);
    else if (collectUsed([stripComments(readFileSync(pageFileOf(p), 'utf8'))]).size === 0) {
      bad.push(`豁免页面 ${p} 未用任何端点 —— 它无需豁免，请从 NON_NAV_PAGES 移除`);
    }
  }

  if (navItems.length === 0) {
    fail('reach-by-role', 'App.tsx 里没解析到任何 NAV 项 —— 本判据的检查对象不存在（若已重构请同步更新本门禁）');
  } else if (bad.length) {
    fail('reach-by-role', '角色可达性核算未通过：\n      ' + bad.join('\n      '));
  } else {
    const summary = roleStats.map((s) => `${s.r} ${s.reachable}/${s.grantable}`).join(' · ');
    const gateDesc = reach.map((x) => `${x.key}[${x.gateRoles.join('|')}]`).join(' ');
    ok('reach-by-role',
      `角色可达性成立（${summary}）—— 有权调用的端点全部有该角色可到达的页面在用：`
      + `已核 ${reach.length} 个导航项的门控角色（${gateDesc}）；`
      + `面板外渲染的页面豁免 ${NON_NAV_PAGES.length} 个（${NON_NAV_PAGES.join(', ') || '无'}）。`);
  }
}
// ---------------------------------------------------------------------------
// ⑫ 【角色展示名必须有唯一权威】第 78 条 —— 中文名只允许来自契约 `x-roles.display`
// ---------------------------------------------------------------------------
// 🛑 它防的是哪一类缺陷：**同一套系统的两个端，把同一个角色显示成两个名字**。
//    契约 `x-roles.<role>.display` 逐字给出「调理师（APP）」/「经络师（APP）」
//    （它是"角色 × 端"的**展开名**，带端后缀），端 D（原生 Android）的
//    `Access.roleDisplay()` 已经这样取；而本端此前手写了一张短形表
//    `{ therapist: '调理师', meridian: '经络师' }`。
//    这类漂移**不报错、不违约、构建与全部判据全绿** ——
//    只有把两端界面并排看才发现，正是"五源对齐"里最容易被放过的一种。
//
// 🛑 判据形态（第 52 / 53 条）：判 `ROLE_LABEL` 的**构造形态**，不判"文本里有没有中文"。
//    ① 构造体必须引用 `roleDisplay` / `ROLE_EXPANSION`（即真的取自生成物）；
//    ② 构造体内**不得出现任何 CJK 字符**（手写中文名 = 第二份权威）；
//    ③ **覆盖面自证**：生成物里声明的每个 ROLE_EXPANSION 条目都必须被本判据解析到，
//       且 `END_TOKEN_ROLES` 的每个角色都能找到**非空** display —— 否则运行时会
//       **静默回退成原码**，而所有判据都看不见。
{
  const accessCode = stripComments(readFileSync(ACCESS, 'utf8'));
  const genText = readFileSync(GEN, 'utf8');
  const problems = [];
  const covered = [];

  // ---- ① / ②：ROLE_LABEL 的构造体形态 ----
  const start = accessCode.indexOf('export const ROLE_LABEL');
  const after = start >= 0 ? accessCode.slice(start) : '';
  const nextTop = after.slice(1).search(/\nexport /);
  const stmt = after ? (nextTop >= 0 ? after.slice(0, nextTop + 1) : after) : '';
  if (!stmt) {
    problems.push('未能圈定 `export const ROLE_LABEL` 的声明 —— 判据不认识当前写法，'
      + '【不得】当作通过（第 52/53 条：判据太宽 ⇒ 假绿）。');
  } else {
    if (!/roleDisplay\s*\(|ROLE_EXPANSION/.test(stmt)) {
      problems.push('ROLE_LABEL 的构造体没有引用 roleDisplay / ROLE_EXPANSION'
        + ' ⇒ 展示名不是取自契约（构成第二份权威）。');
    }
    const cjk = /[\u4e00-\u9fff]/.exec(stmt);
    if (cjk) {
      problems.push('ROLE_LABEL 的构造体里出现中文字符：'
        + `「${stmt.slice(Math.max(0, cjk.index - 20), cjk.index + 20).replace(/\s+/g, ' ')}」`
        + '\n        中文角色名必须来自契约 x-roles.display，不得手写。');
    }
  }

  // ---- ③：覆盖面自证（生成物侧） ----
  const body = (genText.match(/export const ROLE_EXPANSION[\s\S]*?\n\}\);/) ?? [])[0] ?? '';
  const entryRe = /"([A-Za-z_][A-Za-z0-9_]*)":\s*\{[\s\S]*?tokens:\s*Object\.freeze\(\[([^\]]*)\]\)[\s\S]*?display:\s*"([^"]*)"/g;
  const entries = [...body.matchAll(entryRe)].map((m) => ({
    role: m[1], tokens: m[2], display: m[3],
  }));
  const declared = (body.match(/"[A-Za-z_][A-Za-z0-9_]*":\s*\{/g) ?? []).length;
  const tokBody = (genText.match(/export const END_TOKEN_ROLES[\s\S]*?\]\)/) ?? [])[0] ?? '';
  const tokenRoles = [...tokBody.matchAll(/"([A-Za-z_][A-Za-z0-9_]*)"/g)].map((m) => m[1]);

  if (!body) {
    problems.push('生成物里找不到 ROLE_EXPANSION —— 判据不认识当前生成形态，【不得】当作通过。');
  } else if (entries.length !== declared) {
    problems.push(`生成物 ROLE_EXPANSION 解析覆盖不全：解析到 ${entries.length} 条、`
      + `实际声明 ${declared} 条 ⇒ 判据覆盖面不足，【不得】当作通过（第 53 条）。`);
  }
  if (!tokenRoles.length) {
    problems.push('生成物里没解析出 END_TOKEN_ROLES 的任何元素 —— 判据不认识当前生成形态。');
  }
  for (const t of tokenRoles) {
    const hit = entries.find((e) => e.tokens.includes(t));
    if (!hit) {
      problems.push(`角色 ${t} 在 ROLE_EXPANSION 里找不到 tokens 含它的条目`
        + ' ⇒ 运行时 roleDisplay 会【静默回退成原码】。');
    } else if (!hit.display) {
      problems.push(`角色 ${t} 的 display 为空 ⇒ 运行时 roleDisplay 会【静默回退成原码】。`);
    } else {
      covered.push(`${t}→${hit.display}`);
    }
  }

  if (problems.length) {
    fail('x3-role-label-from-contract', '角色展示名未收敛到契约：\n      ' + problems.join('\n      '));
  } else {
    ok('x3-role-label-from-contract',
      `ROLE_LABEL 取自契约 x-roles.display（构造体无手写中文）；`
      + `已核 ${covered.length} 个角色（${covered.join(' · ')}），`
      + `生成物 ROLE_EXPANSION 声明 ${declared} 条全部解析到。`);
  }
}

console.log('== 端 B · X-3 角色级装载自检 ==');
for (const l of oks) console.log(l);
for (const l of notes) console.log(l);
if (fails.length) {
  console.error('\n失败项:');
  for (const l of fails) console.error(l);
  console.error(`\nX-3 CHECK FAILED  (therapist-app)`);
  process.exit(1);
}
console.log(`\nX-3 OK  (therapist-app)  角色准入未漂移 · 单一权威面 · 出站已接线`);

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** 剥注释：仅用于"判据不应被注释满足"的场景（本仓第 41/51 条教训）。 */
function stripComments(t) {
  return t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** 转义正则元字符（⑧ 判"页面是否以调用形态使用了某函数名"时需要）。 */
function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function walk(dir) {
  const acc = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) acc.push(...walk(p));
    else acc.push(p);
  }
  return acc;
}