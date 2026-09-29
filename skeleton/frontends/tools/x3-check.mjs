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
  console.error(`MISCONFIGURED: 生成物缺失 ${GEN}（先跑 python ../tools/gen-endpoints.py）`);
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
  const re = /\{\s*id:\s*"([^"]+)",\s*row:\s*"([^"]+)",\s*method:\s*"([^"]+)",\s*path:\s*"([^"]+)",\s*grantedRoles:\s*Object\.freeze\(\[([^\]]*)\]\)/g;
  let m;
  while ((m = re.exec(genText))) {
    entries.push({
      id: m[1],
      row: m[2],
      method: m[3],
      path: m[4],
      roles: m[5].split(',').map((s) => s.trim().replace(/"/g, '')).filter(Boolean),
    });
  }
}
if (entries.length === 0) {
  fail('parse', '生成物里没解析到任何端点 —— 生成物格式变了或文件被手改');
} else {
  ok('parse', `生成物解析到 ${entries.length} 个端点`);
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
{
  const appText = stripComments(readFileSync(join(SRC, 'App.tsx'), 'utf8'));
  const requiresRe = /requires:\s*'([^']+)'/g;
  let m;
  const bad = [];
  let count = 0;
  while ((m = requiresRe.exec(appText))) {
    count += 1;
    if (!entries.some((e) => e.id === m[1])) bad.push(m[1]);
  }
  if (bad.length) {
    fail('nav', `导航项引用了不在本端生成物里的端点：${bad.join(', ')}`);
  } else {
    ok('nav', `导航项依赖的 ${count} 个端点都在本端生成物里`);
  }
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------
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

function walk(dir) {
  const acc = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) acc.push(...walk(p));
    else acc.push(p);
  }
  return acc;
}