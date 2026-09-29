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
    console.error(`MISCONFIGURED: ${label}缺失 ${p}（先跑 python ../tools/gen-endpoints.py）`);
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
    });
  }
}
// 🛑 交叉核对（第 53 条手法）：证明 TRANSOUT 里出现的**每一个字段名**都在
//    entry 上真实存在。否则"映射写对了但字段没解析出来"会静默变成假红/假绿。
{
  const sample = entries[0] ?? {};
  const unmapped = Object.values(TRANSOUT_FIELDS).filter((f) => !(f in sample));
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
// 🛑 判据形态（第 52 条教训）：判**使用形态**，不判"词是否出现"。
//    允许：`if (e.frontier) { ... }`（判存在后**显式处理**）
//    禁止：把 I 域端点**不着任何未完结标注**地当普通功能渲染
//          —— 该形态在本门禁里表达为"页面里出现了 I 域端点 id 字面量
//             而同一文件里没有 unsettledOf / frontier / rulingPending 的引用"。
{
  const files = walk(SRC).filter((f) => /\.(ts|tsx)$/.test(f));
  const bad = [];
  for (const f of files) {
    const rel = relative(SRC, f).replace(/\\/g, '/');
    if (rel.startsWith('contract/')) continue; // 契约层自身
    const code = stripComments(readFileSync(f, 'utf8'));
    // 找出该文件引用的端点 id（字符串字面量且在生成物里）
    const ids = entries.map((e) => e.id).filter((id) => new RegExp(`['"]${id}['"]`).test(code));
    if (ids.length === 0) continue;
    const unsettledIds = ids.filter((id) => {
      const e = entries.find((x) => x.id === id);
      return e && (e.frontier || e.rulingPending);
    });
    if (unsettledIds.length === 0) continue;
    const handles = /unsettledOf|frontier|rulingPending|unsettledEndpoints/.test(code);
    if (!handles) {
      bad.push(`${rel} 引用了未完结端点 ${unsettledIds.join(', ')} 但未做任何标注（未引用 unsettledOf/frontier/rulingPending）`);
    }
  }
  if (bad.length) {
    fail('unsettled-surfaced',
      `以下文件把契约【未完结】的端点当既定事实使用：\n      ` + bad.join('\n      ')
      + '\n      ⇒ 使用者会以为"占位待冻结"的功能已经可用。'
      + '本端把 I 域显示为正常功能，而契约逐字标它 x-frontier: 占位待冻结。');
  } else {
    ok('unsettled-surfaced', `引用未完结端点的源文件均带标注（已扫 ${files.length} 个源文件）`);
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

function walk(dir) {
  const acc = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) acc.push(...walk(p));
    else acc.push(p);
  }
  return acc;
}