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

/** 转义正则元字符（用于把"端点 id 集合"拼进正则分支）。 */
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