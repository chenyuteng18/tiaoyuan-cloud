/**
 * 端 A · 构建自检：契约元信息完整性（scope 层）
 * ============================================================================
 *
 * 这是端 A 的**主门禁**。它防的不是"写错字"，而是**契约元信息在转录中丢失**。
 *
 * 🛑 它防的是哪一类缺陷（本仓第 54 条／第 50 条同族）
 * ---------------------------------------------------------------------------
 * 端 A 的 39 个端点**全部** `grantedRoles = ["admin"]` —— 端点级没有角色分叉，
 * 所以端 B 那套"角色 → 端点"的矩阵在这里**恒真**，检它等于没检。
 * 端 A 真正的边界是**行级范围 / 仅超管 / 待冻结 / 待裁定**这四类操作级声明，
 * 它们原本**被生成器丢掉了**（初版只转 id/row/method/path/grantedRoles）。
 *
 * 丢掉的表现是**完全静默**的：生成物看起来正常、`--check` 也绿、
 * 三端构建都过 —— 只有人肉逐条读契约才发现"界面少了一道约束"。
 * 故本门禁做两件事：
 *   ① **咬合契约**：把裁剪契约里**每一个**操作级 x- 键与生成物逐条对拍，
 *      契约写了而产物没写 ⇒ 报红（这就是第 54 条的守卫）；
 *   ② **防覆盖面退化**：扫描**实际源码**里是否出现"本应显示未完结状态
 *      却把它当既定事实用"的形态。
 *
 * 🛑 第 53 条教训已内建：判据必须能证明【覆盖面】
 * ---------------------------------------------------------------------------
 * ① 的判据不看"我认识哪些键"，而是**把契约里出现的全部 x- 键枚举一遍**
 * （仅白名单内的少数几个允许"已由设计决定不转出"，且必须逐条具名 + 写理由）。
 * 这样"契约新增了第 5 类 x- 键"会自己报红，而不是静默漏过。
 *
 * 退出码：0 通过 · 1 不通过 · 2 配置缺失 · 3 环境受限（未验证）
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { probeDebrisNotice } from './_gate-common.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = join(HERE, '..');
const END_A = join(FRONTENDS, 'admin-web');
const SRC = join(END_A, 'src');
const CUT = join(FRONTENDS, '..', '..', 'contract', 'sdk-generator', '_cut', 'admin-web.openapi.yaml');

const fails = [];
const oks = [];
const notes = [];

function ok(kind, msg) { oks.push(`  ✓ ${kind}: ${msg}`); }
function fail(kind, msg) { fails.push(`  ✗ [${kind}] ${msg}`); }
function note(msg) { notes.push(`  – ${msg}`); }

// ---------------------------------------------------------------------------
// 前提
// ---------------------------------------------------------------------------
const GEN = join(SRC, 'contract', 'endpoints.ts');
const SCOPE = join(SRC, 'contract', 'scope.ts');
for (const [label, p] of [['生成物', GEN], ['范围层', SCOPE]]) {
  if (!existsSync(p)) {
    console.error(`MISCONFIGURED: ${label}缺失 ${p}（先跑 node ../tools/gen-endpoints.mjs）`);
    process.exit(2);
  }
}
if (!existsSync(CUT)) {
  console.error(`MISCONFIGURED: 裁剪契约缺失 ${CUT}`);
  process.exit(2);
}

const genText = readFileSync(GEN, 'utf8');
const cutText = readFileSync(CUT, 'utf8');

// ---------------------------------------------------------------------------
// 契约操作级 x- 键 → 生成物字段 的唯一映射表（模块级：解析段与 ③ 共用一份，
// 不得出现第二份权威 —— 本仓第 50 条同族：同一事实写两处必然漂移）。
// ---------------------------------------------------------------------------
const TRANSOUT_FIELDS = {
  // 🛑 x-contract-row 落到生成物的 `row`，但**值不是字面拷贝**：
  //    契约一行可对应多操作（D5 → D5-a/b/c），生成物落的是**子档位**。
  //    故 ④ 的计数对拍不能拿 row 做逐值比对（见 ①b 的三层比对）。
  'x-contract-row': 'row',
  'x-callable-roles': 'grantedRoles',
  'x-row-scope': 'rowScope',
  'x-super-admin-only': 'superAdminOnly',
  'x-ruling-pending': 'rulingPending',
  'x-frontier': 'frontier',
  'x-idempotency-key': 'idempotencyKeySpec',
};

// 这些键的语义在【端点集合】层面而不是【单个端点】层面，故不逐条落到端点上是对的。
// 每条必须具名 + 写理由（不允许"其它"兜底 —— 那会让新键静默漏过）。
const KNOWN_NOT_PER_OP = {
  'x-client-forbidden': '客户端禁入（由裁剪管线按端消费；端点级不重复表达）',
  'x-client-explicitly-denied': '客户端显式拒绝（同上）',
  'x-visible-to': '字段可见性（属 schema 层，不是 operation 层）',
  'x-field-group': '字段组（属 schema 层）',
  'x-tbd-allowed': 'TBD 允许（属 schema 层，由 INV-5 消费）',
  'x-visibility-note': '可见性备注（属 schema 层）',
  'x-request-body-captured': '请求体捕获标记（属 schema 层，登记用）',
};

// ---------------------------------------------------------------------------
// ① 解析生成物
// ---------------------------------------------------------------------------
const entries = [];
{
  const re = /\{\s*id:\s*"([^"]+)",\s*row:\s*"([^"]+)",\s*method:\s*"([^"]+)",\s*path:\s*"([^"]+)",\s*grantedRoles:\s*Object\.freeze\(\[([^\]]*)\]\)([\s\S]*?)\n  \},/g;
  let m;
  while ((m = re.exec(genText))) {
    const tail = m[6];
    const rowScope = /rowScope:\s*"((?:[^"\\]|\\.)*)"/.exec(tail);
    const superAdminOnly = /superAdminOnly:\s*(true|false)/.exec(tail);
    const rulingPending = /rulingPending:\s*"((?:[^"\\]|\\.)*)"/.exec(tail);
    const frontier = /frontier:\s*"((?:[^"\\]|\\.)*)"/.exec(tail);
    const idem = /idempotencyKeySpec:\s*"((?:[^"\\]|\\.)*)"/.exec(tail);
    const reqQ = /requiredQuery:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(tail);
    const reqB = /requiredBody:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(tail);
    // 🛑 第 71 条：path 参数（URL 占位符）。与 reqQ/reqB 同式的三态处理。
    const reqP = /requiredPath:\s*Object\.freeze\(\[([^\]]*)\]\)/.exec(tail);
    // 🛑 三态必须分开（这是本判据自己的一个真实缺陷，2026-09-30 修）：
    //      · 正则**没匹配**（生成物里根本没有 `requiredQuery:` 这一项）
    //        ⇒ 传 `undefined` ⇒ 解析为 `null` ⇒ 解析层自检报红；
    //      · 匹配到了但**空数组** `Object.freeze([])`
    //        ⇒ 传空串 ⇒ 解析为 `[]` ⇒ 正常（"该端点无必需 query 参数"）；
    //      · 匹配到了有内容 ⇒ 解析为名字数组。
    //    初版写成 `parseNames(reqQ ? reqQ[1] : null)` —— 传进去的是 **null**，
    //    而 `parseNames` 只把 `undefined` 当"未解析"，于是 `null.split` **崩溃**。
    //    崩溃的后果比误判更隐蔽：门禁不是在"报红"，而是**根本没跑完** ——
    //    反向验证里会表现为"exit=1 但关键词没命中"，看起来像用例本身坏了（实测 I8）。
    //    ⇒ 一律传 `=== undefined ? undefined : 捕获组`，把"没匹配"忠实表达为 undefined。
    const parseNames = (s) =>
      (s === undefined ? null : s.split(',').map((x) => x.trim().replace(/"/g, '')).filter(Boolean));
    // 🛑 每个 entry 都必须把【③ 里 TRANSOUT 用到的字段名】一个不落地填齐。
    //    本门禁首跑就栽在这：初版只有 `roles`，没有 `grantedRoles`，
    //    于是 TRANSOUT['x-callable-roles'] = 'grantedRoles' 永远取到 undefined，
    //    `xkey-transcribed` 报"契约写了但生成物没转出"——**是门禁自己错了**。
    //    这与第 52/53 条同型：判据被自己的解析方式绕过。
    //    两种字段名都填（`roles` 供 ② 用，`grantedRoles` 供 ③ 用），互为佐证。
    const roles = m[5].split(',').map((s) => s.trim().replace(/"/g, '')).filter(Boolean);
    entries.push({
      id: m[1],
      row: m[2],
      method: m[3],
      path: m[4],
      roles,
      grantedRoles: roles,
      rowScope: rowScope ? rowScope[1] : null,
      superAdminOnly: superAdminOnly ? superAdminOnly[1] === 'true' : null,
      rulingPending: rulingPending ? rulingPending[1] : null,
      frontier: frontier ? frontier[1] : null,
      idempotencyKeySpec: idem ? idem[1] : null,
      requiredQuery: parseNames(reqQ === null ? undefined : reqQ[1]),
      requiredBody: parseNames(reqB === null ? undefined : reqB[1]),
      requiredPath: parseNames(reqP === null ? undefined : reqP[1]),
    });
  }
}
// 🛑 交叉核对（第 53 条手法）：证明 TRANSOUT 里出现的**每一个字段名**都在
//    entry 上真实存在。否则"映射写对了但字段没解析出来"会静默变成假红/假绿。
{
  const sample = entries[0] ?? {};
  const unmapped = Object.values(TRANSOUT_FIELDS).filter((f) => !(f in sample));
  // 🛑 解析层自检还必须覆盖【非 TRANSOUT】的两个新字段：它们不来自 x- 键，
  //    而来自契约 parameters / requestBody.required。若解析写漏，判据会
  //    静默看到 null 并一律放过 —— 那正是第 52 条"判据被自己的解析绕过"。
  const extraMissing = ['requiredQuery', 'requiredBody', 'requiredPath'].filter((f) => !(f in sample));
  if (extraMissing.length && entries.length) {
    fail('parse-fields',
      `解析层未产出必要字段 ${extraMissing.join(', ')} ⇒ 必需参数判据会静默放过全部端点。`);
  } else if (sample.requiredQuery === null && entries.length) {
    fail('parse-fields',
      'requiredQuery 解析为 null（生成物未转出或解析正则不咬合）——'
      + '需要的只是"空数组"，null 说明这一层断了。');
  } else if (sample.requiredPath === null && entries.length) {
    // 🛑 第 71 条：path 参数此前**从未转录**，这条自检就是防它再次静默消失。
    fail('parse-fields',
      'requiredPath 解析为 null（生成物未转出 path 参数或解析正则不咬合）——'
      + 'path 占位符漏传会拼出字面量 `{id}`，且全部门禁绿。');
  }
  if (unmapped.length) {
    fail('parse-fields',
      `TRANSOUT 声明要落到生成物字段 ${unmapped.join(', ')}，但解析出的 entry 上不存在这些字段 `
      + '⇒ 门禁自己的解析与判据不咬合（第 52/53 条同型）。');
  } else {
    ok('parse-fields', `TRANSOUT 的 ${Object.keys(TRANSOUT_FIELDS).length} 个目标字段均已从生成物解析出来`);
  }
}
if (entries.length === 0) {
  fail('parse', '生成物里没解析到任何端点 —— 生成物格式变了或文件被手改');
} else {
  ok('parse', `生成物解析到 ${entries.length} 个端点`);
}

// ---------------------------------------------------------------------------
// ①b 【第 53 条手法：判据必须能证明覆盖面】
//     上面的 `entries.length` 是**门禁自己解析生成物**得来的 —— 它只能证明
//     "我解析到了 N 个"，不能证明"契约里就有 N 个"。若生成器漏转一个端点，
//     这里会安静地少一个，而**所有下游判据（②③④）都在这个残缺集合上做，
//     于是全部照样绿**。这正是第 53 条"判据太窄"的形态，只是接缝在生成器。
//     故这里引入一个**独立于生成物**的计数源：契约里的 `x-contract-row`
//     （逐操作一行，生成器无法影响它），并逐条比对行号集合。
// ---------------------------------------------------------------------------
{
  // 独立源：契约里的 operation 级 x-contract-row（逐操作一行，生成器无法影响它）
  const cutRowsRaw = [];
  for (const line of cutText.split('\n')) {
    const m = /^ {6}x-contract-row:\s*"?([A-Z]\d+(?:-[a-z])?)"?\s*$/.exec(line);
    if (m) cutRowsRaw.push(m[1]);
  }
  // 🛑 关键建模事实（本判据首跑自己撞出来的，已写进结论）：
  //    契约的 `x-contract-row` 是【合同行】而非【端点】—— `D5` 一行对应
  //    D5-a / D5-b / D5-c 三个操作（见各操作 summary 前缀）。
  //    生成物的 `row` 是它的【子档位细化】(D5-a)，**不是字面拷贝**。
  //    ⇒ 逐值集合比对会误报（首跑即误报 3 条"凭空造"+1 条"漏转"）。
  //      正确的比对是三层：
  //        (1) 计数：契约操作数 === 生成物端点数（两边都是逐操作）
  //        (2) 生成物的 row 去掉 `-x` 后缀必须命中契约 row（不许凭空造新行）
  //        (3) 契约每个 row 必须在生成物里至少有一个子档位（不许整行漏转）
  const cutSet = new Set(cutRowsRaw);
  const genRows = entries.map((e) => e.row);
  const parentOf = (r) => r.replace(/-[a-z]$/, '');
  const orphan = genRows.filter((r) => !cutSet.has(parentOf(r)));
  const uncovered = [...cutSet].filter((r) => !genRows.some((g) => parentOf(g) === r));
  const dupInGen = [...new Set(genRows.filter((r, i) => genRows.indexOf(r) !== i))];

  if (cutRowsRaw.length !== entries.length || orphan.length || uncovered.length || dupInGen.length) {
    fail('parse-coverage',
      `契约（独立源 x-contract-row，${cutRowsRaw.length} 个操作 / ${cutSet.size} 个合同行）`
      + `与生成物（${entries.length} 条）不一致：`
      + (cutRowsRaw.length !== entries.length ? `\n      操作数不符：契约 ${cutRowsRaw.length} vs 生成物 ${entries.length}` : '')
      + (orphan.length ? `\n      凭空造行：${orphan.join(', ')}（父行不在契约里）` : '')
      + (uncovered.length ? `\n      整行漏转：${uncovered.join(', ')}（契约有、生成物无任何子档位）` : '')
      + (dupInGen.length ? `\n      生成物重复行号：${dupInGen.join(', ')}` : '')
      + '\n      ⇒ 下游判据跑在残缺集合上会**照样绿**（第 53 条同型）。');
  } else {
    const subRows = genRows.filter((r) => parentOf(r) !== r).length;
    ok('parse-coverage',
      `契约 ${cutRowsRaw.length} 个操作 === 生成物 ${entries.length} 条；`
      + `${cutSet.size} 个合同行全部有落点`
      + (subRows ? `（其中 ${subRows} 条为子档位细化，如 D5 → D5-a/b/c）` : ''));
  }
}

// ---------------------------------------------------------------------------
// ② 端 A 是单角色端：所有端点必须只授予本端角色，且角色集合 == {admin}
// ---------------------------------------------------------------------------
{
  const bad = [];
  const rolesSeen = new Set();
  for (const e of entries) {
    for (const r of e.roles) rolesSeen.add(r);
    if (e.roles.length !== 1 || e.roles[0] !== 'admin') {
      bad.push(`${e.row} ${e.id} 的 grantedRoles = [${e.roles.join(', ')}]（期望恰好 ["admin"]）`);
    }
  }
  if (bad.length) {
    fail('single-role', `端 A 是单角色端（admin），但出现非单角色端点：\n      ` + bad.join('\n      '));
  } else {
    ok('single-role', `39 个端点全部只授予 admin（端点级无角色分叉 ⇒ 端 B 的 X-3 矩阵在此恒真，检它无意义）`.replace('39', String(entries.length)));
  }
  if ([...rolesSeen].join(',') !== 'admin') {
    fail('single-role', `出现了本端不应有的角色：${[...rolesSeen].join(', ')}`);
  }
}

// ---------------------------------------------------------------------------
// ③ 【第 54 条核心】裁剪契约里出现的**每一个**操作级 x- 键，
//     必须或（a）已被生成器转出、或（b）在白名单里具名豁免
// ---------------------------------------------------------------------------
// 🛑 判据不信"我认识哪几个键"，而是把契约里**实际出现的**键枚举一遍。
//    这样"契约新增了第 5 类 x- 键"会自己报红。
{
  const TRANSOUT = TRANSOUT_FIELDS;

  // 只取 operation 级（缩进 6 空格，形如 "      x-frontier: ..."）的键
  const opKeys = new Set();
  for (const line of cutText.split('\n')) {
    const m = /^ {6}(x-[a-z0-9-]+):/.exec(line);
    if (m) opKeys.add(m[1]);
  }

  const unknown = [...opKeys].filter((k) => !(k in TRANSOUT) && !(k in KNOWN_NOT_PER_OP));
  const missing = [...opKeys].filter((k) => {
    const field = TRANSOUT[k];
    if (!field) return false;
    // 该键在契约里确实出现过 ⇒ 生成物里至少得有一个端点带这个字段
    return !entries.some((e) => e[field] !== null && e[field] !== undefined);
  });

  if (unknown.length) {
    fail('xkey-coverage',
      `裁剪契约里出现了本门禁【不认识】的操作级 x- 键：${unknown.join(', ')}\n`
      + '      它们既未被生成器转出、也不在豁免白名单里 ⇒ 前端拿不到这道约束。\n'
      + '      修法：把它加进 gen-endpoints.py 的 OP_X_KEYS（转出），'
      + '或加进本门禁的 KNOWN_NOT_PER_OP 并写明理由。');
  } else {
    ok('xkey-coverage', `裁剪契约的操作级 x- 键共 ${opKeys.size} 类，全部有归属（${[...opKeys].join(' ')}）`);
  }

  if (missing.length) {
    fail('xkey-transcribed',
      `以下 x- 键在契约里出现过，但生成物里一个端点都没转出它：\n      `
      + missing.map((k) => `${k}（应落到 ${TRANSOUT[k]}）`).join('\n      ')
      + '\n      ⇒ 契约写了约束、前端拿不到 —— 这正是本仓第 54 条的形态。');
  } else {
    ok('xkey-transcribed', '契约里出现过的操作级 x- 键，生成物均逐条落字段（无静默丢项）');
  }
}

// ---------------------------------------------------------------------------
// ④ 【逐条对拍】契约声明的具体值，必须与生成物里的值一致
// ---------------------------------------------------------------------------
{
  const pairs = [
    ['x-row-scope', 'rowScope', /x-row-scope:\s*(.+)/g],
    ['x-frontier', 'frontier', /x-frontier:\s*(.+)/g],
    ['x-ruling-pending', 'rulingPending', /x-ruling-pending:\s*(.+)/g],
  ];
  const bad = [];
  for (const [ykey, field, re] of pairs) {
    const cutCount = (cutText.match(re) ?? []).length;
    const genCount = entries.filter((e) => e[field]).length;
    if (cutCount !== genCount) {
      bad.push(`${ykey}: 契约 ${cutCount} 处，生成物 ${genCount} 处`);
    }
  }
  // x-super-admin-only 在契约里写的是裸 true
  const saCut = (cutText.match(/^ {6}x-super-admin-only:\s*true/gm) ?? []).length;
  const saGen = entries.filter((e) => e.superAdminOnly === true).length;
  if (saCut !== saGen) bad.push(`x-super-admin-only: 契约 ${saCut} 处，生成物 ${saGen} 处`);

  if (bad.length) {
    fail('xkey-values', `契约声明与生成物计数不一致：\n      ` + bad.join('\n      '));
  } else {
    ok('xkey-values',
      `逐条计数一致：x-row-scope=${entries.filter((e) => e.rowScope).length} · `
      + `x-super-admin-only=${saGen} · x-frontier=${entries.filter((e) => e.frontier).length} · `
      + `x-ruling-pending=${entries.filter((e) => e.rulingPending).length}`);
  }
}

// ---------------------------------------------------------------------------
// ⑤ 范围层必须由生成物现算，不得手写端点清单（第二份权威）
// ---------------------------------------------------------------------------
{
  const scopeText = stripComments(readFileSync(SCOPE, 'utf8'));
  const listRe = /\[\s*(?:"[A-Z]\d+(?:-[a-z])?"|'[A-Z]\d+(?:-[a-z])?')(?:\s*,\s*(?:"[A-Z]\d+(?:-[a-z])?"|'[A-Z]\d+(?:-[a-z])?'))+[^\]]*\]/g;
  const lists = scopeText.match(listRe) ?? [];
  const suspicious = lists.filter((l) => (l.match(/[A-Z]\d+(?:-[a-z])?/g) ?? []).length >= 2);
  if (suspicious.length) {
    fail('no-hardcoded-list',
      `scope.ts 出现了形如端点行号清单的数组字面量（${suspicious.length} 处）：\n      `
      + suspicious.map((s) => s.replace(/\s+/g, ' ').slice(0, 100)).join('\n      ')
      + '\n      范围判定必须由生成物的字段现算，不得手抄清单');
  } else {
    ok('no-hardcoded-list', 'scope.ts 无端点行号型清单（范围/未完结状态均由生成物现算）');
  }

  // 🛑 判据判【导入形态】，不判"词是否出现"（第 52 条教训）。
  //    本判据初版用 `scopeText.includes('ENDPOINTS')` —— 反向验证 I5
  //    （从 import 里摘掉 ENDPOINTS/endpointById）**没被抓住**：因为
  //    `for (const e of ENDPOINTS)` 这类**使用处**仍让关键词命中。
  //    改为：必须从 './endpoints' **导入**这些符号。
  const importBlock = /import\s*\{([\s\S]*?)\}\s*from\s*'\.\/endpoints'/.exec(scopeText);
  const imported = importBlock ? importBlock[1] : '';
  const mustRef = ['ENDPOINTS', 'endpointById'];
  const notRef = mustRef.filter((n) => !new RegExp(`(^|[,{\\s])${n}([,}\\s]|$)`).test(imported));
  if (!importBlock) {
    fail('scope-wired', "scope.ts 未见 `import { ... } from './endpoints'` —— 无法证明判定取自生成物");
  } else if (notRef.length) {
    fail('scope-wired', `scope.ts 未从生成物【导入】：${notRef.join(', ')}（无法证明是现算）`);
  } else {
    ok('scope-wired', 'scope.ts 的判定经由生成物现算（已从 ./endpoints 导入 ENDPOINTS / endpointById）');
  }
}

// ---------------------------------------------------------------------------
// ⑥ 角色展开表必须来自契约（x-roles），不得手写
// ---------------------------------------------------------------------------
{
  // 🛑 判据判【导出形态】，不判"词是否出现"（第 52 条教训）。
  //    初版用 `/ROLE_EXPANSION/.test(genText)` —— 反向验证 I7
  //    （把 `export const ROLE_EXPANSION` 改名成 `const ROLE_EXPANSION_UNUSED`）
  //    **没被抓住**：改名后字符串里仍含 `ROLE_EXPANSION`。
  //    改为：必须存在 **`export const ROLE_EXPANSION`** 这个导出形态。
  const hasRoleExpansion = /export\s+const\s+ROLE_EXPANSION\s*:/.test(genText);
  const scopeCode = stripComments(readFileSync(SCOPE, 'utf8'));
  // 使用形态：必须真的读 `ROLE_EXPANSION[...]`（下标访问），不是仅仅提及
  const scopeUses = /ROLE_EXPANSION\s*\[/.test(scopeCode);
  if (!hasRoleExpansion) {
    fail('role-expansion', '生成物里没有 `export const ROLE_EXPANSION`（契约 x-roles 未被转录）');
  } else if (!scopeUses) {
    fail('role-expansion', 'scope.ts 未以 `ROLE_EXPANSION[...]` 形态使用生成物的角色展开表（可能手写了子档位）');
  } else {
    const adminTokens = /ADMIN_TOKENS[^=]*=\s*ROLE_EXPANSION\['admin'\]\?\.tokens/.test(scopeCode);
    if (!adminTokens) {
      fail('role-expansion', "ADMIN_TOKENS 未从 ROLE_EXPANSION['admin'].tokens 取（可能手写了子档位）");
    } else {
      ok('role-expansion', "admin 子档位由契约 x-roles 现取（ROLE_EXPANSION['admin'].tokens）");
    }
  }
}

// ---------------------------------------------------------------------------
// ⑦ 【覆盖面】源码里不得把"未完结状态"当既定事实用
// ---------------------------------------------------------------------------
// 🛑 判据形态（第 52 条教训）：判**调用形态**，不判"词是否出现"。
//
// 🛑 本轮实测两次翻车（第 53 条同型：判据覆盖面没跟上本仓自己的写法）
// ---------------------------------------------------------------------------
// 初版只有一条式子：`/unsettledOf|frontier|rulingPending|unsettledEndpoints/`。
// 两个方向都错：
//
//   ① **假红（太窄）**：本仓在 `services/domain.ts` 里**逐字规定**
//      "页面不得各自手写判断，一律走 `contractNoteOf` 统一出口"。
//      于是页面写 `contractNoteOf('approveRefund')` + `<UnsettledBar items={note.unsettled}>`
//      ——**完全合法**却被判红。假红的下场通常是有人把判据删掉，那才是真失效。
//   ② **词满足（太弱，第 52 条）**：`/frontier/` 会被**注释**里的
//      "契约 x-frontier" 满足；`/unsettledOf/` 会被一个同名的**未调用**标识符满足。
//
// 故本条重写为**三种合法形态的调用判定 + 计数等式**：
//   (a) 直接调用契约层枚举函数：`unsettledOf(` / `unsettledEndpoints(`
//   (b) 调用契约层统一出口且**参数是未完结端点 id 字面量**：
//       `contractNoteOf('approveRefund')` —— 这是本仓规定的写法
//   (c) 渲染未完结提示条：`<UnsettledBar ...>`
// 🛑 计数等式：**引用了未完结端点 id 的文件**必须至少命中 (a)/(b)/(c) 之一；
//    并且 (b) 的**参数**必须在契约的未完结集合里（防"参数写成普通端点 id 也算过"）。
// 🛑 唯一的**结构性例外**（不是"开个后门"，见下）：`App.tsx` 这类**外壳文件**
//    只在导航表里以 `requires: <推导函数>()` 形态间接依赖，文件里**不出现端点 id 字面量**。
//    故它根本不会进入"引用了未完结端点 id"的集合 —— **不需要例外**。
//    本判据因此对"故意写死字面量"与"用推导函数"这两种写法**等价看待**：
//    前者会被要求标注（若只是导航表，则标注无从渲染 ⇒ 促使人改成推导函数）；
//    后者天然干净。这条规则把"外壳不该写死端点 id"变成了**被门禁逼出来的纪律**。
{
  const files = walk(SRC).filter((f) => /\.(ts|tsx)$/.test(f));
  const bad = [];
  // 契约里带未完结声明的 operationId 集合（现算，不写死）
  const unsettledIdSet = new Set(
    entries.filter((e) => e.frontier || e.rulingPending).map((e) => e.id),
  );
  for (const f of files) {
    const rel = relative(SRC, f).replace(/\\/g, '/');
    if (rel.startsWith('contract/')) continue; // 契约层自身（生成物 / scope.ts 即元信息的住所）
    const code = stripComments(readFileSync(f, 'utf8')); // 去注释：防"注释里的词"满足判据
    // ① 该文件引用了哪些**未完结**端点 id 字面量
    const unsettledIds = [...unsettledIdSet].filter((id) => new RegExp(`['"]${id}['"]`).test(code));
    if (unsettledIds.length === 0) continue;
    // ② 三种合法形态（都是**调用/渲染形态**，不是"词出现"）
    //    (a) 枚举契约层：unsettledOf(e) / unsettledEndpoints()
    //    (b) 统一出口且**参数逐字是未完结端点 id**：contractNoteOf('approveRefund')
    //        🛑 参数**必须绑定未完结集合**：若只判 `contractNoteOf(` 出现，
    //           那么 `contractNoteOf('getCustomer')` 也会过 —— 那是第 52 条的词满足。
    //    (c) 渲染未完结提示条：<UnsettledBar ...>
    const callsEnum = /\bunsettledOf\s*\(|\bunsettledEndpoints\s*\(/.test(code);
    const unsettledIdAlt = [...unsettledIdSet].map(escapeRe).join('|');
    const callsNoteBound = new RegExp(
      `\\bcontractNoteOf\\s*\\(\\s*(['"])(${unsettledIdAlt})\\1\\s*\\)`,
    ).test(code);
    const rendersBar = /<UnsettledBar\b/.test(code);
    const handles = callsEnum || callsNoteBound || rendersBar;
    if (!handles) {
      bad.push(
        `${rel} 引用了未完结端点 ${unsettledIds.join(', ')} 但未做任何标注`
        + `（未出现 unsettledOf(...) / contractNoteOf('<未完结id>') / <UnsettledBar>）`,
      );
    }
  }
  if (bad.length) {
    fail('unsettled-surfaced',
      `以下文件把契约【未完结】的端点当既定事实使用：\n      ` + bad.join('\n      ')
      + '\n      ⇒ 使用者会以为"占位待冻结"的功能已经可用。'
      + '本端把 I 域显示为正常功能，而契约逐字标它 x-frontier: 占位待冻结。'
      + '\n      修法（二选一）：① 经 services/domain.ts 的 contractNoteOf(<该端点id>)'
      + ' + <UnsettledBar> 渲染提示条；'
      + '② 若本文件是外壳且只在导航表里间接依赖，改用契约层的推导函数'
      + '（scope.ts 导出），文件内不出现端点 id 字面量。');
  } else {
    ok('unsettled-surfaced',
      `引用未完结端点（共 ${unsettledIdSet.size} 个）的源文件均带标注`
      + `（已扫 ${files.length} 个源文件 · 认 unsettledOf/unsettledEndpoints/contractNoteOf(<未完结id>)/<UnsettledBar> 四种调用形态）`);
  }
}

// ---------------------------------------------------------------------------
// ⑧ 【跨文件隐式约定】令牌键名必须只有一处定义（本轮实测缺陷的守卫）
// ---------------------------------------------------------------------------
// 🛑 实测缺陷（属本仓第 50 条同族，接缝在"跨文件隐式约定"）：
//    `services/session.ts` 写 `localStorage.setItem('dy.token', ...)`，
//    而 `api/client.ts` 读 `localStorage.getItem('token')` —— **两个键名**。
//    ⇒ Authorization 头永远为空、全量 401，而 tsc / 构建 / 既有判据**全不报**。
//    修法 = 键名收敛到 `services/token-store.ts` 一处 + 本条判据守它。
// 🛑 判据形态（第 52 条教训）：判【存储访问形态】，不判"词是否出现"。
//    允许：token-store.ts 内的 `localStorage.*Item(TOKEN_KEY, ...)`（唯一权威点）；
//    禁止：其它源文件里出现 `localStorage.getItem('...')` /
//          `localStorage.setItem('...', ...)` / `localStorage.removeItem('...')`
//          里带**令牌语义**的键名字面量。
{
  const files = walk(SRC).filter((f) => /\.(ts|tsx)$/.test(f));
  const AUTH = join(SRC, 'services', 'token-store.ts');
  const bad = [];
  for (const f of files) {
    if (f === AUTH) continue;                 // 唯一权威点，允许有字面量
    const code = stripComments(readFileSync(f, 'utf8'));
    const rel = relative(SRC, f).replace(/\\/g, '/');
    const re = /localStorage\.(?:get|set|remove)Item\(\s*(['"])((?:[^'"\\]|\\.)*)\1/g;
    let m;
    while ((m = re.exec(code))) {
      const key = m[2];
      // 令牌语义：键名含 token / jwt / auth 且不是"档位缓存"语义
      if (/token|jwt|auth/i.test(key)) {
        bad.push(`${rel}: localStorage 直接用了令牌键名 ${JSON.stringify(key)}`);
      }
    }
  }
  if (bad.length) {
    fail('token-single-source',
      `令牌键名出现了本文件之外的第二处定义：\n      ` + bad.join('\n      ')
      + '\n      ⇒ 写与读的键名若不一致，Authorization 头会**静默为空**（全量 401），'
      + '而 tsc / 构建 / 其它判据都不会报。'
      + '\n      修法：一律经 services/token-store.ts 的 readToken/writeToken/clearToken。');
  } else {
    ok('token-single-source', '令牌键名只有 services/token-store.ts 一处定义（其余源文件均经它读写）');
  }
}

// ---------------------------------------------------------------------------
// ⑨ 【导航依赖】NAV 的 `requires` 必须可查，且形态被判据覆盖（第 53 条的正向守卫）
// ---------------------------------------------------------------------------
// 🛑 为什么端 A 需要这条（端 B 的 x3-check.mjs 已有同款）
// ---------------------------------------------------------------------------
// 端 A 的 `requires` 恒真（39/39 端点均授予 admin），它的用途不是"过滤导航"
// 而是"依赖可查"。但这恰恰让**判据缺失**更难察觉：就算 `requires` 写了一个
// 契约里不存在的 id，导航项也照样显示（因为恒真），点进去才报错。
// 故必须有一条判据把"每个 requires 都能在生成物里查到"钉住。
//
// 🛑 判据形态（第 53 条教训：覆盖面必须跟上"本仓允许的所有写法"）
// ---------------------------------------------------------------------------
// 两种合法形态：
//   ① 生成物里存在的端点 id **字符串字面量**：`requires: 'getCustomer'`
//   ② 契约层导出的**推导函数调用**：`requires: firstFrontierEndpointId()`
// **计数等式**：①的条数 + ②的条数 **必须等于** NAV 里 non-null 的 `requires` 条数。
// 等式是防"新增第三种形态"的关键 —— 少了它，`requires: (0 ? 'x' : null)` 这类
// 写法会**两条式子都不命中**，于是"没查到"被当成"没有问题"（第 53 条的静默漏检）。
{
  const APP = join(SRC, 'App.tsx');
  const appCode = stripComments(readFileSync(APP, 'utf8'));
  const navBlockMatch = appCode.match(/const\s+NAV\s*:\s*readonly\s+NavItem\[\]\s*=\s*Object\.freeze\(\[([\s\S]*?)\]\);/);
  if (!navBlockMatch) {
    fail('nav-requires', 'App.tsx 里找不到 NAV 数组（判据无法校验导航依赖；若改名请同步本判据）');
  } else {
    const navBlock = navBlockMatch[1];
    const bad = [];

    // ① 端点 id 字面量
    const literalIds = [...navBlock.matchAll(/requires:\s*'([^']+)'/g)].map((m) => m[1]);
    // ② 推导函数调用形态（无参）
    const callFns = [...navBlock.matchAll(/requires:\s*([A-Za-z_$][\w$]*)\s*\(\s*\)/g)].map((m) => m[1]);
    // 计数等式
    const requiresTotal = (navBlock.match(/requires:/g) ?? []).length;
    const nullCount = (navBlock.match(/requires:\s*null/g) ?? []).length;
    const nonNullExpected = requiresTotal - nullCount;
    const covered = literalIds.length + callFns.length;

    if (covered !== nonNullExpected) {
      bad.push(
        `requires 的形态未被判据覆盖：non-null 的 requires 有 ${nonNullExpected} 条，`
        + `判据只认出 ${covered} 条（字面量 ${literalIds.length} + 推导函数 ${callFns.length}）。`
        + '新增了第三种写法（如内联三元 / 变量拼接）会静默漏检 —— 第 53 条的形态。',
      );
    }

    // ① 每一条字面量 id 必须在生成物里查得到
    const unknownIds = literalIds.filter((id) => !entries.some((e) => e.id === id));
    if (unknownIds.length) {
      bad.push(`以下 requires 写了生成物里不存在的端点 id：${unknownIds.join(', ')}`
        + '（导航项会静默保留，点进去才报错）');
    }

    // ② 每一个推导函数名必须是 scope.ts 真的导出的函数（防"凭空调用"）
    const scopeCode = stripComments(readFileSync(SCOPE, 'utf8'));
    const notExported = callFns.filter((fn) => !new RegExp(`export\\s+function\\s+${escapeRe(fn)}\\s*\\(`).test(scopeCode));
    if (notExported.length) {
      bad.push(`以下 requires 调用了 scope.ts 未导出的函数：${notExported.join(', ')}`
        + '（推导函数必须来自契约层，页面/外壳不得自造依赖源）');
    }

    if (bad.length) {
      fail('nav-requires', '导航依赖不可查或形态未被覆盖：\n      ' + bad.join('\n      '));
    } else {
      ok('nav-requires',
        `NAV 的 ${nonNullExpected} 条非 null requires 全部可查`
        + `（字面量 ${literalIds.length} 条均命中生成物；推导函数 ${callFns.length} 条均为 scope.ts 导出；计数等式成立）`);
    }
  }
}

// ---------------------------------------------------------------------------
// ⑩ 【端点触达】39 个端点必须在【业务代码里被以调用形态发出】
// ---------------------------------------------------------------------------
// 🛑 为什么需要这条：任务书写的是"页面覆盖 39 个端点"，但"覆盖"是可自我声称的。
//    本仓第 52 条的教训是：**"代码里出现了某个名字"≠"那个名字被使用了"**。
//    故本条不判"id 字面量出现过"（那会被 `void 'authMe'`、注释、类型声明满足），
//    而判**两层调用链**：
//      ① 出站层里存在 `call<T>('<id>'` 调用形态（真正出站）；
//      ② 定义该调用的导出函数**被某个页面/外壳以调用形态使用**（`fn(` 且带实参）。
//    ⇒ 只做 ① 会漏掉"封装好了但从没接上界面"（本轮实测：D 域 5 个端点正是如此）。
//
// 🛑 出站层是 `services/` **整个目录**，不是只有 domain.ts（首跑即被本判据抓出）
// ---------------------------------------------------------------------------
// 初版把出站扫描写死成 `services/domain.ts` 一个文件 ⇒ 首跑报
// 「authLogin / authMe 未以出站调用形态出现」。**那是判据太窄**（第 55 条）：
// A1/A2 合法地定义在 `services/session.ts`（会话层的职责就是"A1→A2 取档位"），
// 不在 domain.ts —— 本仓并没有"所有出站必须写在 domain.ts"这条纪律。
// 修法不是把 A1/A2 搬进 domain.ts（那会让会话层失去职责完整性），
// 而是**把出站层的定义写成本仓实际的样子：`services/` 整个目录**。
//   · 出站（`call(`）只允许出现在 `services/` 层；
//   · 页面 / 外壳**不得**直接出站（必须经 services 层的函数）。
// 后者顺带成为一条被门禁守着的分层纪律。
//
// 🛑 本条的计数等式（第 53/55 条教训：判据必须证明自己覆盖了全部）
// ---------------------------------------------------------------------------
// 判据打印 ①/② 各自的命中数，并断言 `① 命中数 === 生成物端点总数`。
// 等式不成立即报红并逐条列出**未触达的端点** —— 这正是本轮补 D 域 5 个入口的由来。
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
    bad.push(`以下端点未以出站调用形态出现（services/ 层里没有 call('<id>')）：`
      + noCallForm.join(', '));
  }
  if (noFn.length) {
    bad.push(`以下端点没有对应的封装函数（call 出现在函数体外或未封装）：`
      + noFn.join(', '));
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
    fail('endpoint-reachability',
      `端点的界面触达链不完整：\n      ` + bad.join('\n      '));
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
// ⑩b 必需参数被调用点带上（本仓第 65 条）
// ---------------------------------------------------------------------------
// 🛑 它防的是哪一类缺陷
// ---------------------------------------------------------------------------
// ⑩ `endpoint-reachability` 只回答"端点**有没有**被调用"，**不回答"调用的实参
// 对不对"**。缺口实测（2026-09-30，本轮读 02 表过程中抓出）：
//   · C1 `listScaleItemBanks` 的调用点只传 `{ page: 1, page_size: 20 }` ——
//     而契约 `age_group` 是 **required**，且本端点**根本没有分页参数**；
//   · C2 `submitBaselineAssessment` 的三个 required 字段填的是
//     `scale_id: 'baseline'` / `item_group_id: 'default'` /
//     `age_group_locked: \`${detail.gender}${detail.age}\`` —— 前两个不是 UUID、
//     第三个不在 8 组枚举内 ⇒ 后端 `ScaleDomain.AgeGroup.parse` 必然抛 400。
// 而 `tsc --noEmit` / `vite build` / ⑩ 触达判据 / 反向验证**一律绿** ——
// 因为"实参对不对"此前根本没有任何东西在问。
//
// 🛑 判据必须判【实参形态】，不得判"词出现过"（第 52 条）
// ---------------------------------------------------------------------------
// 对每个端点，取它的**封装函数体**（⑩ 已解析出端点 id → 函数名）与**全部调用点**，
// 要求每个 required 名字在**调用形态里真实出现**：要么以对象字面量键出现
// （`age_group:` / `'age_group':`），要么以具名实参出现。仅"名字在文件里出现过"
// 不算 —— 那会被一句注释或一个 `void 'age_group'` 满足。
//
// 🛑 计数等式（第 53/55 条）
// ---------------------------------------------------------------------------
// 判据打印"有 required 的端点数 / 已核对的端点数"，并在解析层自检里断言
// `requiredQuery` 不为 null —— 否则生成物没转出时本条会**静默放过全部端点**。
{
  const CALL_SITES = new Map(); // 端点 id → 该端点的封装函数体 + 全部调用点文本
  const FN_OF = new Map();      // 端点 id → 定义它的 services 函数名（本块自建，不复用 ⑩ 的块级变量）

  // ① 封装函数体：从 services/ 层取出"定义了这个出站的函数"的完整源码
  for (const f of walk(join(SRC, 'services')).filter((x) => /\.(ts|tsx)$/.test(x))) {
    const code = stripComments(readFileSync(f, 'utf8'));
    const fnRe = /export\s+(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(([\s\S]*?)\n\}/g;
    let fm;
    while ((fm = fnRe.exec(code))) {
      const c = new RegExp(/\bcall\s*(?:<[^(]*?>)?\s*\(\s*['"]([^'"]+)['"]/.source, 's').exec(fm[2]);
      if (!c) continue;
      if (!FN_OF.has(c[1])) FN_OF.set(c[1], fm[1]);
      const prev = CALL_SITES.get(c[1]) ?? [];
      prev.push({ file: relative(SRC, f).replace(/\\/g, '/'), text: fm[0] });
      CALL_SITES.set(c[1], prev);
    }
  }

  // ② 调用点：页面 / 外壳里"以调用形态使用该封装函数"的**完整调用单元**文本
  //    🛑 为什么不能是"函数名前后各 N 字符"（本仓第 66 条）
  //    ------------------------------------------------------------------
  //    初版取前后各 400 字符。实测立刻推翻：端 A `createVerdict(...)` 的调用点
  //    在此之后长出多个 `...(cond ? {...} : {})` 展开分支，400 字符窗口在
  //    `risk_flag` 之前就被截断 —— 于是**明明无条件写在调用里的**
  //    `confidence: {}` / `module_scores: {}` 被判成"缺"。
  //    ⇒ 固定窗口长度是**隐含假设**：窗口太小会误报（把写了的判成没写），
  //      窗口太大又会假绿（把隔壁函数的实参算进来）—— 两端都错。
  //    正确做法：从函数名后的第一个 `(` 起**做括号配平**，取到匹配的 `)` 为止
  //    —— 那才是这个调用的真实边界，与调用点写多长无关。
  const consumers2 = walk(SRC).filter((f) => {
    const rel = relative(SRC, f).replace(/\\/g, '/');
    return rel.startsWith('pages/') || rel === 'App.tsx';
  });
  /** 从 `openIdx`（某个 `(` / `[` / `{` 的位置）起做括号配平，返回配平子串。
   *  三类括号统一计数 —— 因为这里要配平的是**代码结构**，不是"圆括号表达式"。 */
  function balancedCall(text, openIdx) {
    let depth = 0;
    for (let k = openIdx; k < text.length; k += 1) {
      const ch = text[k];
      if (ch === '(' || ch === '[' || ch === '{') depth += 1;
      else if (ch === ')' || ch === ']' || ch === '}') {
        depth -= 1;
        if (depth === 0) return text.slice(openIdx, k + 1);
      }
    }
    // 配平失败（文件被截断/语法残件）⇒ 返回剩余全部，让"实参形态"尽可能被看见；
    // 此时若真缺参数，判据仍会报 —— 偏向"不放过"而不是"偏向绿"。
    return text.slice(openIdx);
  }
  /** 容纳 `idx` 的**最内层 `{}` 块**（含首尾花括号）。
   *  🛑 为什么必须有它：`submitBaselineAssessment(customerId, body, key)` 的实参
   *     是一个**局部变量** `body`，6 个必需字段写在 `const body = {...}` 里 ——
   *     那在**调用单元之外**。只取调用单元会把"写对了的"判成"全缺"（第 67 条）。 */
  function enclosingBlock(text, idx) {
    let depth = 0;
    let open = -1;
    for (let k = idx - 1; k >= 0; k -= 1) {
      const ch = text[k];
      if (ch === ')' || ch === ']' || ch === '}') depth += 1;
      else if (ch === '(' || ch === '[' || ch === '{') {
        if (depth === 0) { open = k; break; }
        depth -= 1;
      }
    }
    return open < 0 ? '' : balancedCall(text, open);
  }
  for (const e of entries) {
    const fn = FN_OF.get(e.id);
    if (!fn) continue;
    const re = new RegExp(`\\b${escapeRe(fn)}\\s*(\\()`, 'g');
    for (const f of consumers2) {
      const code = stripComments(readFileSync(f, 'utf8'));
      let cm;
      while ((cm = re.exec(code))) {
        const openIdx = cm.index + cm[0].lastIndexOf('(');
        const callText = balancedCall(code, openIdx);
        // 调用单元 + 其所在块（覆盖"实参经局部变量传入"这一合法写法）
        const blk = enclosingBlock(code, cm.index);
        const text = blk && blk.length < 2500 ? `${callText}\n${blk}` : callText;
        const prev = CALL_SITES.get(e.id) ?? [];
        prev.push({ file: relative(SRC, f).replace(/\\/g, '/'), text });
        CALL_SITES.set(e.id, prev);
      }
    }
  }

  /** 一个名字是否以"实参形态"出现在调用点文本里（判形态，不判词）。 */
  function argFormPresent(name, text) {
    const n = escapeRe(name);
    return [
      new RegExp(`(?:^|[\\s,{(])['"]?${n}['"]?\\s*:`),   // 对象字面量键：age_group:
      // 🛑 具名/位置实参：边界**只能**是 `(` 或 `,`（后面跟可选空白）。
      //    不得写成 `[\s,(]` —— 那会让**值位置**的同名标识符被误认成实参（第 69 条）：
      //    注入 `credential__removed: credential,` 后，`credential,` 前面是"空格 + :"，
      //    被 `[\s,(]` 命中 ⇒ **实参已删而判据仍绿**（实测 R22 漏过）。
      //    收窄为 `[(,]` 后：值位置（`键: 值`）不再算实参，只有真正的参数位置才算。
      new RegExp(`(?:^|[(,])\\s*${n}\\s*[,)]`),
    ].some((r) => r.test(text));
  }

  const missing = [];
  let checked = 0;
  let withRequired = 0;
  for (const e of entries) {
    // 🛑 第 71 条：path 参数（URL 占位符 `{id}`）也是必需实参，此前**从未被转录**
    //    （契约用 `$ref` 复用命名参数 23 处 + 内联 `in: path`，生成器两种都跳过）
    //    ⇒ 漏传会拼出字面量 `{id}` 发到服务端，而全部门禁绿。此处一并核对。
    const need = [...(e.requiredQuery ?? []), ...(e.requiredBody ?? []), ...(e.requiredPath ?? [])];
    if (!need.length) continue;
    withRequired += 1;
    const sites = CALL_SITES.get(e.id);
    if (!sites || !sites.length) continue; // 触达问题由 ⑩ 负责，此处不重复报
    checked += 1;
    const all = sites.map((s) => s.text).join('\n');
    const absent = need.filter((nm) => !argFormPresent(nm, all));
    if (absent.length) {
      missing.push(`${e.id}（${e.method} ${e.path}）缺 ${absent.join(', ')}`
        + `\n        契约 required 共 [${need.join(', ')}]`
        + `\n        调用点/封装体共 ${sites.length} 处：${sites.map((s) => s.file).join(', ')}`);
    }
  }

  // ⑩c 载体归属（本仓第 70 条 · 判据的第九种失效形态：只判「名字在不在」，不判「落在哪个载体」）
  //  -----------------------------------------------------------------------------
  //  ⑩b 判的是"必需参数名有没有出现在调用点/封装体文本里"，但**不判它落在哪个载体**。
  //  实证（本轮已复现）：把 I7 的 `version` 从 `query: { version: v }` 挪到
  //  `params: { id, version: v }`、`query: {}` —— 后端 `@RequestParam("version")` 必然 400，
  //  而 a-check / x3-check / build-check / 反向验证**全部仍是绿的**。
  //  ⇒ 名字写在对的**位置**、却装进错的**载体**，等价于没带。
  //  本判据：required 名为 `query` 的，必须落在 `query` 载体里；为 `body` 的必须落在 `body` 里。
  //  · 载体是**字面量块** ⇒ 逐名核对（块内有展开/函数调用时如实计为"未逐名核对"，不误报）；
  //  · 载体是**标识符/表达式**（`{ body, key }` 简写 / `query: pageQuery(...)`）⇒
  //    不在此处判（改由 ⑩b 与类型层负责），但**公开计数**，绝不静默放过。
  /** 在**出站对象块**上找 `key`（query/body）载体。
   *  🛑 必须在出站块上找，不能在整段封装体上找 —— 否则 `body: ScreeningRequest`
   *     这种**函数签名的类型注解**会被当成载体，把"根本没写出站块"误报成"载体里缺字段"
   *     （第 70 条自检时实测的假阳性）。
   *  返回 null / {kind:'literal',text} / {kind:'shorthand'} / {kind:'expr'}。 */
  function carrierOf(blk, key) {
    if (!blk) return null;
    const m = new RegExp(`(?:^|[\\s,{(])${escapeRe(key)}\\s*(:?)`).exec(blk);
    if (!m) return null;
    if (!m[1]) return { kind: 'shorthand' };
    const after = blk.slice(m.index + m[0].length);
    if (after.trimStart().startsWith('{')) {
      const oi = blk.indexOf('{', m.index + m[0].length);
      return { kind: 'literal', text: balancedCall(blk, oi) };
    }
    return { kind: 'expr' };
  }
  /** 定位 `blk` 里第 `n` 个端点的**出站对象块**：`call('<id>', {...})` 的第一个 `{` 起配平。
   *  找不到出站块 ⇒ 返回空串（判据会如实报"没有载体"）。 */
  function outboundBlocks(text, id) {
    const out = [];
    const re = new RegExp(`call\\s*(?:<[^(]*?>)?\\s*\\(\\s*['"]${escapeRe(id)}['"]`, 'g');
    let m;
    while ((m = re.exec(text))) {
      const bi = text.indexOf('{', m.index + m[0].length - 1);
      if (bi >= 0) out.push(balancedCall(text, bi));
    }
    return out;
  }
  const misplaced = [];
  let carrierNamed = 0;
  let carrierSkipped = 0;
  let carrierNoBlock = 0;
  for (const e of entries) {
    const needQ = e.requiredQuery ?? [];
    const needB = e.requiredBody ?? [];
    const needP = e.requiredPath ?? [];
    if (!needQ.length && !needB.length && !needP.length) continue;
    const sites = CALL_SITES.get(e.id);
    if (!sites || !sites.length) continue;
    // 所有出站块（封装体 + 调用点里可能出现的直接出站）
    const blocks = sites.flatMap((s) => outboundBlocks(s.text, e.id));
    const filesOf = sites.map((s) => s.file).join(', ');
    if (!blocks.length) {
      // 出站块找不到：⑩b 若已在别处判过"缺名"，此处不重复；仅在**两个载体都没块**时如实报
      carrierNoBlock += 1;
      continue;
    }
    for (const [key, names] of [['query', needQ], ['body', needB], ['params', needP]]) {
      if (!names.length) continue;
      const cs = blocks.map((b) => carrierOf(b, key)).filter((c) => c !== null);
      if (!cs.length) {
        misplaced.push(`${e.id}：契约声明了 ${key} 载体上的 required [${names.join(', ')}]，`
          + `但出站块里**根本没有 ${key} 载体**（${filesOf}）`);
        continue;
      }
      for (const c of cs) {
        if (c.kind !== 'literal') { carrierSkipped += 1; continue; }
        // 字面量块里出现展开（`...body`）或函数调用 ⇒ 字段可能来自展开源，如实计为"未逐名核对"。
        if (/\.\.\./.test(c.text) || /[A-Za-z_$][\w$]*\s*\(/.test(c.text)) { carrierSkipped += 1; continue; }
        // 逐名：既认 `name:` 键，也认 `{ a, b }` 简写属性（形如 `[{(,] name [,}]`）。
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
      + `${carrierNoBlock ? `；${carrierNoBlock} 个端点未见出站块（触达问题由 ⑩ 负责）` : ''}`
      + '—— 前两类由 ⑩b 实参形态与类型层共同兜底，本判据不逐名核，如实计数不静默放过。');
  }

  if (!withRequired) {
    fail('required-args-wired',
      '生成物里没有任何端点带 required 声明 —— 解析层没转出必需参数，本条会静默放过全部端点'
      + '（第 53 条：判据必须能证明自己的覆盖面）。');
  } else if (missing.length) {
    fail('required-args-wired',
      `以下端点的调用点**没有带上契约 required 参数**（后端必然 400，而 tsc/构建/触达判据一律绿）：\n      `
      + missing.join('\n      ')
      + '\n      ⇒ "端点被调用了"不等于"调用是对的"。');
  } else {
    ok('required-args-wired',
      `${withRequired} 个带 required 声明的端点中，${checked} 个有调用点可核对，`
      + `其调用点/封装体均已带上全部必需参数（判实参形态，非判词出现 —— 第 52 条）`);
  }
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------
console.log('== 端 A · 契约元信息完整性自检 ==');
for (const l of oks) console.log(l);
for (const l of notes) console.log(l);
if (fails.length) {
  console.error('\n失败项:');
  for (const l of fails) console.error(l);
  // 诊断补充：失败与探针残骸同时出现时，把"这可能是残骸而不是缺陷"讲清楚
  // （只补充说明，不改判定 —— 否则会误伤反向验证的正当中间态，见 _gate-common.mjs）
  const notice = probeDebrisNotice(SRC, 'a-check.mjs');
  if (notice) console.error(notice);
  console.error('\nA-CHECK FAILED  (admin-web)');
  process.exit(1);
}
console.log('\nA OK  (admin-web)  契约元信息未丢项 · 范围层现算 · 未完结状态已暴露');

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** 剥注释：仅用于"判据不应被注释满足"的场景（本仓第 41/51 条教训）。 */
function stripComments(t) {
  return t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** 转义正则元字符（用于把"端点 id 集合"拼进正则分支）。 */
function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function walk(dir) {
  const acc = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) acc.push(...walk(p));
    // 🛑 这里**刻意不排除** `__probe_*` 文件 —— 曾试着排除，被实测推翻：
    //    反向验证的 I10 / I13 用例正是靠"门禁能看见一个探针文件"才能被证明有牙齿；
    //    一旦排除，那两条用例双双变成"漏过"（14/16），且**残留探针会变成静默假绿**
    //    （比误报更危险）。故判据一律看向全部源文件；
    //    "残骸被当成缺陷"这件事改由**失败时的诊断补充**处理（见文件末尾
    //    probeDebrisNotice）—— 保留判定不变，只把原因讲清楚。
    else acc.push(p);
  }
  return acc;
}