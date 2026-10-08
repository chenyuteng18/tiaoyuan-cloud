#!/usr/bin/env node
/**
 * 端 B · 原生 Android（Kotlin）构建自检
 * ============================================================================
 *
 * 这是 `therapist-android` 的**主门禁**。它与 `a-check.mjs`（端 A）/ `x3-check.mjs`
 * （端 B · Web）**同一套纪律、不同的接缝**：那两条判的是 TypeScript 源树，
 * 本条判的是 Kotlin 源树 + Android 构建系统（manifest / 源集 / Gradle）。
 *
 * 🛑 为什么原生端需要**独立一条**门禁，而不是"把 x3-check 套过来"
 * ---------------------------------------------------------------------------
 * 原生端多了两类 Web 端根本没有的接缝，而这两类恰恰是"构建全绿、功能全错"的重灾区：
 *
 *   ① **装载层**（本仓第 64 条换了形态）
 *      Web 端的"能不能被装载"由 `app.json.pages` / `NAV ↔ 渲染分支` 回答；
 *      原生端是**两段**：
 *        · manifest 必须声明 Activity —— 否则包能装、图标能点、**点了没反应**；
 *        · `Tab` 枚举 ↔ `MainActivity` 的 `when` ↔ 页文件 必须三方一致 ——
 *          否则会出现"页面写了但导航进不去"（编译、构建、装机、全绿）。
 *      只判其中一段都会漏掉另一半。
 *
 *   ② **构建期/运行期两段式配置**
 *      Web 端读 `import.meta.env`；原生端的环境是**构建期烙进产物**的
 *      （`BuildConfig`）。于是"debug 的明文放行漏进 release"这种事，
 *      在 Web 端根本没有对应形态，必须由**源集归属**来判（`src/debug/` 不得进 release）。
 *
 * 🛑 判据一律判【形态】，不判"词是否出现"（本仓第 52 条）
 * ---------------------------------------------------------------------------
 * 本仓已被两类假绿反复咬过，故这里从一开始就避开：
 *   · `text.includes('ENDPOINTS')` 会被注释满足 ⇒ 本判据**先剥注释**再判。
 *   · `text.includes('rawAnonymous')` 会被**定义处**满足 ⇒ 判**调用点**数量与落点。
 *   · 硬编码"我认识哪些键/哪些 id" ⇒ 一律从**生成物**或**契约**现算枚举，
 *     新出现的取值会自己报红（第 53 条：判据必须能证明自己的覆盖面）。
 *
 * 🛑 判据清单（每条都配了它防的是哪一类真实缺陷）
 * ---------------------------------------------------------------------------
 *   parse / parse-coverage      生成物与裁剪契约的端点数、必需参数逐条对拍
 *   roles                       端点角色必须非空且都在本端 token-roles 内
 *   entry-declared              【第 64 条①】manifest 必须声明 MainActivity + LAUNCHER
 *   nav-tab-render-triangulation【第 63/64 条②】Tab 表 / when 分支 / 页文件 三方一致
 *   endpoint-wired              29 个端点必须都真的被某个源文件接上（不许"生成了没用"）
 *   wrapper-own-endpoint        同一端点 id 常量不得被两个封装函数共用（实测缺陷的守卫）
 *   no-hardcoded-endpoint-list  准入层不得手抄端点清单（第二份权威）
 *   anonymous-call-single-site  匿名出站只允许一处（A1）
 *   session-key-single-source   会话键名只有一处定义
 *   secure-storage-required     本地存储必须全程加密（写入经 encrypt / 读取与 decrypt 等数）
 *   no-protocol-literal         协议片段（头名/令牌前缀/Base Path）不得手写字面量
 *   protocol-fields-wired       协议字段名必须有生成物具名常量、且取用点必须引用它
 *                               （原名 envelope-field-literals，2026-10-08 升级形态）
 *   base-path-wired             【第 56 条】出站前缀 = 网关根 + 契约 Base Path
 *   no-color-literal            色值只允许出现在 colors.xml
 *   cleartext-debug-only        明文放行只存在于 debug 源集
 *   named-enum-verbatim         契约具名枚举与四端取值逐字一致
 *   enum-labels-verbatim        契约内联枚举与标签表逐字一致
 *   required-args-wired         契约必需参数（path/query/body）在调用链上真的带上了
 *   release-gate-present        release 闸门必须完整（缺签名不得静默产出 unsigned 包）
 *   launcher-icon-complete      启动图标可从 manifest 解析到真实资源（名从 manifest 现算）
 *   gson-reflect-surface        Gson 反序列化目标必须被 keep 规则覆盖（R8 会抹掉裸字段）
 *   edge-to-edge-insets         targetSdk ≥ 35 强制边到边：必须有启用落点、三类 inset
 *                               齐备、onCreate 接线且早于 setContentView、主题不得再留
 *                               已失效的系统栏属性（形态判据，真机观感另计）
 *
 * 退出码：0 通过 · 1 不通过 · 2 配置缺失
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { probeDebrisNotice } from './_gate-common.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = join(HERE, '..');
const SKELETON = join(FRONTENDS, '..');
const REPO = join(SKELETON, '..');
const ROOT = join(FRONTENDS, 'therapist-android');
const SRC = join(ROOT, 'app', 'src', 'main', 'kotlin', 'com', 'diaoyuanyun', 'therapist');
const GEN = join(SRC, 'contract', 'Endpoints.kt');
const MAN_MAIN = join(ROOT, 'app', 'src', 'main', 'AndroidManifest.xml');
const MAN_DEBUG = join(ROOT, 'app', 'src', 'debug', 'AndroidManifest.xml');
const NSC_DEBUG = join(ROOT, 'app', 'src', 'debug', 'res', 'xml', 'network_security_config.xml');
const NSC_MAIN = join(ROOT, 'app', 'src', 'main', 'res', 'xml', 'network_security_config.xml');
const APP_GRADLE = join(ROOT, 'app', 'build.gradle.kts');
const COLORS_XML = join(ROOT, 'app', 'src', 'main', 'res', 'values', 'colors.xml');
const CONTRACT = join(REPO, 'contract', 'openapi-v1.0.0.yaml');
const CUT = join(REPO, 'contract', 'sdk-generator', '_cut', 'therapist-app.openapi.yaml');
const WEB_DOMAIN = join(FRONTENDS, 'therapist-app', 'src', 'services', 'domain.ts');

const fails = [];
const oks = [];
const notes = [];

const ok = (kind, msg) => oks.push(`  ✓ ${kind}: ${msg}`);
const fail = (kind, msg) => fails.push(`  ✗ [${kind}] ${msg}`);
const note = (msg) => notes.push(`  – ${msg}`);

// ---------------------------------------------------------------------------
// 前提
// ---------------------------------------------------------------------------
for (const [label, p] of [
  ['生成物 Endpoints.kt', GEN],
  ['主清单', MAN_MAIN],
  ['debug 清单', MAN_DEBUG],
  ['裁剪契约', CUT],
  ['冻结契约', CONTRACT],
]) {
  if (!existsSync(p)) {
    console.error(`MISCONFIGURED: ${label}缺失 ${p}`);
    console.error('  （生成物缺失时先跑 python frontends/tools/gen-endpoints.py）');
    process.exit(2);
  }
}

const genText = readText(GEN);
const cutText = readText(CUT);

/** 全部 Kotlin 源文件（绝对路径）。 */
const KT_ALL = walk(SRC).filter((f) => f.endsWith('.kt'));

/** 读 + 剥注释（判据一律用剥注释后的文本 —— 第 52 条）。 */
const readCode = (p) => stripKtComments(readText(p));

/** 生成物自身 —— 协议常量与端点表的**唯一**合法住所，多处判据要排除它。 */
const isGenerated = (p) => p === GEN;

// ===========================================================================
// ① 解析生成物
// ===========================================================================
// 🛑 解析层必须自检：若某天生成物换了格式，下面的判据会**静默看到空集**
//    然后"全部通过" —— 那比报错危险得多（本仓第 52/53 条）。
//    故先解析，再断言解析出的字段齐全，再往下跑。
// ===========================================================================
const entries = [];
{
  const re = /\n        Endpoint\(([\s\S]*?)\n        \),/g;
  let m;
  while ((m = re.exec(genText))) {
    const b = m[1];
    const pick = (key) => {
      const mm = new RegExp(`\\b${key} = "([^"]*)"`).exec(b);
      return mm ? mm[1] : null;
    };
    const arr = (key) => {
      const mm = new RegExp(`\\b${key} = listOf\\(([^)]*)\\)`).exec(b);
      if (!mm) return null;
      return mm[1]
        .split(',')
        .map((x) => x.trim().replace(/^"|"$/g, ''))
        .filter(Boolean);
    };
    const method = /\bmethod = HttpMethod\.(\w+)/.exec(b);
    entries.push({
      id: pick('id'),
      row: pick('row'),
      method: method ? method[1] : null,
      path: pick('path'),
      grantedRoles: arr('grantedRoles'),
      requiredQuery: arr('requiredQuery'),
      requiredPath: arr('requiredPath'),
      requiredBody: arr('requiredBody'),
      // 契约 200 响应 data 的具名 schema；未声明形状的端点为 null（判据 ㉑ 用）
      dataSchema: pick('dataSchema'),
    });
  }
}
{
  const REQUIRED_FIELDS = [
    'id', 'row', 'method', 'path',
    'grantedRoles', 'requiredQuery', 'requiredPath', 'requiredBody',
  ];
  const sample = entries[0] ?? {};
  const missing = REQUIRED_FIELDS.filter((f) => sample[f] === undefined || sample[f] === null);
  if (entries.length === 0) {
    fail('parse', '生成物里没解析到任何 Endpoint —— 生成物格式变了或被手改');
  } else if (missing.length) {
    fail('parse-fields',
      `解析层未产出字段 ${missing.join(', ')} ⇒ 下游判据会在残缺数据上**静默放过**（第 52/53 条）`);
  } else {
    ok('parse', `生成物解析到 ${entries.length} 个端点，8 个字段齐全`);
  }
}

// ===========================================================================
// ①b 覆盖面：独立于生成物的第二个计数源（第 53 条）
// ===========================================================================
// 只数生成物自己解析出来的条数是**自我证明**：若生成器漏转一个端点，
// 这里会安静地少一个，而全部下游判据照样绿。故引入裁剪契约的
// `operationId`（逐操作一行，生成器无法影响它）做独立计数与逐条对拍。
{
  const cutOps = [...cutText.matchAll(/^ {6}operationId: (\S+)\s*$/gm)].map((x) => x[1]);
  const genIds = entries.map((e) => e.id);
  const missingInGen = cutOps.filter((id) => !genIds.includes(id));
  const extraInGen = genIds.filter((id) => !cutOps.includes(id));
  const dupInGen = [...new Set(genIds.filter((id, i) => genIds.indexOf(id) !== i))];
  const bad = [];
  if (cutOps.length !== entries.length) {
    bad.push(`操作数不符：契约 ${cutOps.length} vs 生成物 ${entries.length}`);
  }
  if (missingInGen.length) bad.push(`整条漏转：${missingInGen.join(', ')}`);
  if (extraInGen.length) bad.push(`生成物凭空多出：${extraInGen.join(', ')}`);
  if (dupInGen.length) bad.push(`生成物重复 id：${dupInGen.join(', ')}`);
  if (bad.length) {
    fail('parse-coverage',
      `裁剪契约与生成物不一致：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 下游判据跑在残缺集合上会**照样绿**（第 53 条同型）。'
      + '\n      修法：改契约后重跑 python frontends/tools/gen-endpoints.py');
  } else {
    ok('parse-coverage', `裁剪契约 ${cutOps.length} 个 operation === 生成物 ${entries.length} 条，id 逐条命中`);
  }
}

// ===========================================================================
// ①c 契约必需参数：生成物转出的必须与裁剪契约逐条一致
// ===========================================================================
// 🛑 它防的是"生成器只转出 id/row/method/path"那类**静默丢项**（第 65/71 条）：
//    丢掉的参数不会让任何构建报错，只会让真发出去的请求被服务端 400。
// 🛑 解析策略（**两层分段**，这是被两次实测逼出来的）
// ---------------------------------------------------------------------------
//   第一层：以 path 键（`^  /xxx:`）分段 —— 但不能假设"一段一个 operation"。
//           实测：裁剪契约 26 个 path 键承载 29 个 operation，因为
//           `/customers/{id}/intake-profile` 上同时挂着 GET(B5) 与 PATCH(B6)。
//           ⇒ 按 path 键分段后，**还要在段内按 HTTP 方法（缩进 4）再分一次**。
//   第二层：方法段内取 operationId（缩进 6）与 requestBody 段的 schema.required。
//
// 🛑 反面教材（首跑就撞了两次，都写下来免得下次重犯）
//   · 直接扫全文件的 `{...}` 与 `required:` ⇒ 整份文件成了一段，
//     authLogin 被判成"需要 id / assessment_id"（全文件占位符的并集）。
//   · `blk.join('\n')` 上跑 `^ {6}operationId:` **忘了 `m` 标志** ⇒ 每个段都 `continue`，
//     契约侧静默变成空数组，而失败信息看起来像"契约格式变了"。
{
  const cLines = cutText.split('\n');

  /**
   * 取一段文本里**块状** `required:` 的条目（缩进最浅的那一个 = 顶层 schema）。
   * 返回名字数组；没有则空数组。
   */
  const blockListRequired = (sec) => {
    let best = null;
    sec.forEach((l, j) => {
      const m = /^(\s+)required:\s*$/.exec(l);
      if (m && (!best || m[1].length < best.ind)) best = { ind: m[1].length, j };
    });
    if (!best) return [];
    const itemRe = new RegExp(`^ {${best.ind}}- (\\S+)\\s*$`);
    const out = [];
    for (let j = best.j + 1; j < sec.length; j += 1) {
      const m = itemRe.exec(sec[j]);
      if (!m) break;
      out.push(m[1]);
    }
    return out;
  };

  /** components.schemas 的顶层 required 表（`$ref` 解析用）。 */
  const schemaRequired = new Map();
  {
    const ci = cLines.findIndex((l) => /^components:\s*$/.test(l));
    const si = ci >= 0 ? cLines.findIndex((l, i) => i > ci && /^ {2}schemas:\s*$/.test(l)) : -1;
    if (si >= 0) {
      const names = [];
      for (let i = si + 1; i < cLines.length; i += 1) {
        if (/^\S/.test(cLines[i])) break;              // 出了 schemas 段
        const m = /^ {4}(\w+):\s*$/.exec(cLines[i]);
        if (m) names.push({ name: m[1], at: i });
      }
      names.forEach((n, i) => {
        const end = i + 1 < names.length ? names[i + 1].at : cLines.length;
        const req = blockListRequired(cLines.slice(n.at, end));
        if (req.length) schemaRequired.set(n.name, req);
      });
    }
  }

  const pathIdxs = [];
  cLines.forEach((l, i) => { if (/^ {2}\/\S.*:\s*$/.test(l)) pathIdxs.push(i); });

  const METHOD_RE = /^ {4}(get|post|put|patch|delete):\s*$/;
  const cutReq = [];
  for (let k = 0; k < pathIdxs.length; k += 1) {
    const s = pathIdxs[k];
    const e = k + 1 < pathIdxs.length ? pathIdxs[k + 1] : cLines.length;
    const blk = cLines.slice(s, e);
    const pathTemplate = blk[0].trim().replace(/:$/, '');
    const pathParams = [...pathTemplate.matchAll(/\{([A-Za-z_]\w*)\}/g)].map((m) => m[1]);

    // 段内按方法再分段（一个 path 可以挂多个方法）
    const methodIdxs = [];
    blk.forEach((l, i) => { if (METHOD_RE.test(l)) methodIdxs.push(i); });
    if (methodIdxs.length === 0) continue;

    for (let q = 0; q < methodIdxs.length; q += 1) {
      const ms = methodIdxs[q];
      const me = q + 1 < methodIdxs.length ? methodIdxs[q + 1] : blk.length;
      const mblk = blk.slice(ms, me);
      const op = /^ {6}operationId: (\S+)\s*$/m.exec(mblk.join('\n'));
      if (!op) continue;
      const method = METHOD_RE.exec(mblk[0])[1].toUpperCase();

      // requestBody 段的 schema.required（**块状列表**，不是内联数组）
      // 🛑 schema 可能是 `$ref: '#/components/schemas/X'` —— 此时 required 写在
      //    components 里，**不在 operation 段内**。实测：29 个操作里只有 authLogin
      //    是内联 schema，其余全走 $ref；不解析 $ref 的话契约侧只算出 1 个带 body
      //    必需字段的操作，而生成物有 7 个 —— 判据会安静地少核 6 个端点。
      const bodyRequired = [];
      const rbi = mblk.findIndex((l) => /^ {6}requestBody:/.test(l));
      if (rbi >= 0) {
        let end = mblk.length;
        for (let j = rbi + 1; j < mblk.length; j += 1) {
          if (mblk[j].trim() !== '' && /^ {0,6}\S/.test(mblk[j])) { end = j; break; }
        }
        const sec = mblk.slice(rbi, end);
        // ① 段内内联 schema 的 required
        bodyRequired.push(...blockListRequired(sec));
        // ② $ref 指向的 components schema 的 required
        for (const m of sec.join('\n').matchAll(/\$ref:\s*'#\/components\/schemas\/(\w+)'/g)) {
          const names = schemaRequired.get(m[1]);
          if (names) bodyRequired.push(...names);
        }
      }
      cutReq.push({ id: op[1], method, pathTemplate, pathParams, body: [...new Set(bodyRequired)] });
    }
  }

  const norm = (a) => [...a].sort().join(',');
  const bad = [];
  for (const c of cutReq) {
    const e = entries.find((x) => x.id === c.id);
    if (!e) continue;
    if (norm(c.pathParams) !== norm(e.requiredPath)) {
      bad.push(`${c.id}（${c.method} ${c.pathTemplate}）的 path 参数不一致：`
        + `契约 [${c.pathParams.join(', ')}] vs 生成物 [${e.requiredPath.join(', ')}]`);
    }
    if (c.body.length && norm(c.body) !== norm(e.requiredBody)) {
      bad.push(`${c.id} 的 body required 不一致：契约 [${c.body.join(', ')}] vs 生成物 [${e.requiredBody.join(', ')}]`);
    }
  }
  // 🛑 覆盖面自检（第 53 条）——**等式的两边是两个独立来源**：
  //    左边是"契约侧我解析到了几个带必需参数的 operation"，
  //    右边是"生成物里有几个带必需参数的端点"。
  //    只判"左边非空"是不够的：实测踩过——$ref 没解析时左边只有 1，
  //    而右边是 7，判据会**安静地少核 6 个端点却仍然报绿**。
  const parsedPathOps = cutReq.filter((c) => c.pathParams.length > 0).length;
  const parsedBodyOps = cutReq.filter((c) => c.body.length > 0).length;
  const genPathOps = entries.filter((e) => e.requiredPath.length > 0).length;
  const genBodyOps = entries.filter((e) => e.requiredBody.length > 0).length;
  if (cutReq.length !== entries.length) {
    fail('required-args-parsed',
      `裁剪契约解析出 ${cutReq.length} 个 operation，生成物有 ${entries.length} 条 `
      + '—— 解析层与生成物不对齐（若契约分段方式变更请同步本门禁）');
  } else if (parsedPathOps !== genPathOps || parsedBodyOps !== genBodyOps) {
    fail('required-args-parsed',
      `解析层覆盖面不足：契约侧解析出 path ${parsedPathOps} / body ${parsedBodyOps} 个带必需参数的操作，`
      + `而生成物是 path ${genPathOps} / body ${genBodyOps}。\n`
      + '      ⇒ 差额部分的端点**没有被核对**，而本条判据仍会报绿（第 53 条）。'
      + '\n      常见成因：schema 走 `$ref` 而未解析 components（实测踩过）。');
  } else if (bad.length) {
    fail('required-args-parsed',
      `生成物与契约的必需参数不一致：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 漏掉的 path 参数会拼出字面量 `{id}` 发出去（第 71 条），'
      + '且漏传在客户端毫无症状。修法：重跑 python frontends/tools/gen-endpoints.py');
  } else {
    ok('required-args-parsed',
      `契约 ${cutReq.length} 个 operation 的必需参数与生成物逐条一致`
      + `（其中 ${parsedPathOps} 个带 path 占位符、${parsedBodyOps} 个带 body required）`);
  }
}

// ===========================================================================
// ② 角色：每个端点必须至少授予一个本端角色，且不得出现非本端角色
// ===========================================================================
{
  const rolesLine = /val END_TOKEN_ROLES: List<String> = listOf\(([^)]*)\)/.exec(genText);
  const tokenRoles = rolesLine
    ? rolesLine[1].split(',').map((x) => x.trim().replace(/^"|"$/g, '')).filter(Boolean)
    : [];
  if (tokenRoles.length === 0) {
    fail('roles', '生成物未声明 END_TOKEN_ROLES —— 无法证明"本端角色"是什么（第 53 条）');
  } else {
    const bad = [];
    const seen = new Set();
    for (const e of entries) {
      for (const r of e.grantedRoles) {
        seen.add(r);
        if (!tokenRoles.includes(r)) bad.push(`${e.row} ${e.id} 含非本端角色 ${r}`);
      }
      if (e.grantedRoles.length === 0) bad.push(`${e.row} ${e.id} 的 grantedRoles 为空`);
    }
    if (bad.length) {
      fail('roles', `角色转录有误：\n      ${bad.join('\n      ')}`);
    } else {
      ok('roles',
        `${entries.length} 个端点均授予非空角色，取值全集 {${[...seen].sort().join(', ')}} `
        + `⊆ 本端 token-roles {${tokenRoles.join(', ')}}`);
    }
  }
}

// ===========================================================================
// ③ 【第 64 条①】装载层：manifest 必须声明唯一 Activity，且它是启动入口
// ===========================================================================
// 🛑 缺这一条的表现：APK 能装、图标能点、**点下去什么都不发生**，
//    而 `assembleDebug` 是 SUCCESSFUL 的（aapt 不要求有 LAUNCHER）。
{
  const man = readFileSync(MAN_MAIN, 'utf8');
  const code = stripXmlComments(man);
  const bad = [];
  const actNames = [...code.matchAll(/<activity\b[^>]*android:name="([^"]+)"/g)].map((x) => x[1]);
  if (actNames.length === 0) bad.push('主清单没有任何 <activity> 声明');
  if (!actNames.includes('.MainActivity')) {
    bad.push(`主清单未声明 .MainActivity（现有：${actNames.join(', ') || '无'}）`);
  }
  if (!/android\.intent\.category\.LAUNCHER/.test(code)) bad.push('没有 LAUNCHER intent-filter —— 包能装但桌面无入口');
  if (!/android\.intent\.action\.MAIN/.test(code)) bad.push('没有 MAIN action');
  const appName = /<application\b[^>]*android:name="([^"]+)"/.exec(code);
  if (!appName || appName[1] !== '.DyTherapistApp') {
    bad.push(`Application 未指向 .DyTherapistApp（现为 ${appName ? appName[1] : '未声明'}）—— 启动自检与依赖装配不会执行`);
  }
  // exported 只给主入口：其余 Activity 一律不导出（本端只有 1 个，仍显式判）
  const exported = [...code.matchAll(/<activity\b([\s\S]*?)>/g)].map((x) => x[1]);
  const badExported = exported.filter((b) => /android:exported="true"/.test(b) && !/android:name="\.MainActivity"/.test(b));
  if (badExported.length) bad.push(`除 MainActivity 外还有 Activity 声明了 exported="true"`);
  if (bad.length) {
    fail('entry-declared',
      `原生端的装载层不完整：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 编译/构建/装机全绿，但应用点不开或启动自检不跑（第 64 条在原生端的形态）。');
  } else {
    ok('entry-declared',
      `主清单声明 ${actNames.length} 个 Activity，.MainActivity 为唯一 LAUNCHER 入口，`
      + 'Application 指向 .DyTherapistApp（启动自检 + 依赖装配必经此路）');
  }
}

// ===========================================================================
// ④ 【第 63/64 条②】三方一致：Tab 枚举 ↔ MainActivity 的 when ↔ 页文件
// ===========================================================================
// 🛑 为什么必须三方都判
//   · 判①+②：漏"页文件根本不存在"（Kotlin 会编译报错，但**改名后忘了改文件名**这类
//     不会报 —— 因为 `when` 里引用的是类名，类名可以在任意文件里）。
//   · 判②+③：漏"页面写了但导航进不去"（编译、构建、装机全绿，功能缺失）。
//   · 判①+③：漏"导航有入口但渲染分支没实现"（Kotlin 的 enum when 穷尽检查能挡住，但本判据
//     不依赖编译器 —— 门禁的意义就是在**不编译**的环境里也能给出结论）。
{
  const navText = readCode(join(SRC, 'ui', 'Nav.kt'));
  const mainText = readCode(join(SRC, 'MainActivity.kt'));
  const bad = [];

  // ① Tab 枚举项
  // 🛑 解析必须容忍**参数里含括号**的 requires（如 `Access.firstSoleEndpointId()`）——
  //    首跑用 `([^)]+?)\s*\)` 抓 SOLE，结果 SOLE 整个没被解析出来，
  //    于是判出"Tab 6 项 ≠ when 7 分支"。**那是判据自己瞎了，不是源码错了**
  //    ⇒ 改为按行取，再在**顶层逗号**处切分（不在括号内切）。
  const enumBody = /enum class Tab\([\s\S]*?\)\s*\{([\s\S]*)\n\}/.exec(navText);
  const tabs = [];
  if (!enumBody) {
    bad.push('ui/Nav.kt 里找不到 `enum class Tab(...)` —— 本判据的检查对象不存在');
  } else {
    for (const line of enumBody[1].split('\n')) {
      const m = /^ {4}([A-Z][A-Z0-9_]*)\((.*)\),\s*$/.exec(line);
      if (!m) continue;
      // 顶层逗号切分（跳过括号内与字符串内的逗号）
      const args = [];
      let depth = 0;
      let inStr = false;
      let cur = '';
      for (let i = 0; i < m[2].length; i += 1) {
        const ch = m[2][i];
        if (ch === '"') inStr = !inStr;
        if (!inStr && (ch === '(' || ch === '<' || ch === '[')) depth += 1;
        if (!inStr && (ch === ')' || ch === '>' || ch === ']')) depth -= 1;
        if (ch === ',' && depth === 0 && !inStr) { args.push(cur); cur = ''; continue; }
        cur += ch;
      }
      args.push(cur);
      const label = (args[0] || '').trim();
      const requires = (args[1] || '').trim();
      tabs.push({
        name: m[1],
        label: label.replace(/^"|"$/g, ''),
        requires,
      });
    }
  }

  // ② MainActivity 的 when 分支
  const whenBody = /when \(tab\) \{([\s\S]*?)\n        \}/.exec(mainText);
  const branches = whenBody
    ? [...whenBody[1].matchAll(/Tab\.([A-Z][A-Z0-9_]*)\s*->\s*([A-Za-z_][\w]*)\s*\(/g)]
      .map((m) => ({ tab: m[1], page: m[2] }))
    : [];

  // ③ 页文件
  const pageFiles = new Map();
  for (const f of KT_ALL) {
    const rel = relPosix(SRC, f);
    if (!rel.startsWith('ui/pages/')) continue;
    const cls = [...readCode(f).matchAll(/\bclass ([A-Z][\w]*Page)\b\s*[:(]/g)].map((m) => m[1]);
    for (const c of cls) pageFiles.set(c, rel);
  }

  if (tabs.length === 0) bad.push('Tab 枚举解析出 0 项 —— 若枚举写法变更请同步本判据');
  if (branches.length === 0) bad.push('MainActivity 的 `when (tab)` 解析出 0 个分支 —— 若写法变更请同步本判据');

  const tabNames = tabs.map((t) => t.name);
  const branchTabs = branches.map((b) => b.tab);

  // 等式一：Tab 项数 == 分支数（少一个就是"导航有入口但没页面"）
  if (tabNames.length !== branchTabs.length) {
    bad.push(`Tab 项数 ${tabNames.length} ≠ when 分支数 ${branchTabs.length}`
      + `（Tab=${tabNames.join(', ')}；when=${branchTabs.join(', ')}）`);
  }
  for (const t of tabs) {
    const n = branchTabs.filter((x) => x === t.name).length;
    if (n === 0) bad.push(`Tab.${t.name} 没有渲染分支 —— 点了这个页签会**什么都不发生**（第 63 条）`);
    if (n > 1) bad.push(`Tab.${t.name} 有 ${n} 个渲染分支 —— 分发点不唯一`);
  }
  for (const b of branches) {
    if (!tabNames.includes(b.tab)) bad.push(`when 里有 Tab.${b.tab} 分支，但 Tab 枚举没有这一项`);
    if (!pageFiles.has(b.page)) {
      bad.push(`Tab.${b.tab} 渲染 ${b.page}，但 ui/pages/ 下没有任何文件声明该类`
        + '（页面不存在 / 改名后文件名没跟着改）');
    }
  }
  // 反向：页文件写了但导航进不去（本仓最典型的"写了没接上"）
  // 🛑 唯一具名豁免：LoginPage —— 它不是页签，由未登录分支直接渲染（见 MainActivity.rerender）。
  //    豁免必须具名 + 写理由，不允许"其它"兜底（那会让新页面漏接时静默通过）。
  const LOGIN_EXEMPT = 'LoginPage';
  const reached = new Set(branches.map((b) => b.page));
  for (const [cls, rel] of pageFiles) {
    if (reached.has(cls)) continue;
    if (cls === LOGIN_EXEMPT) {
      if (!new RegExp(`\\b${cls}\\s*\\(`).test(mainText)) {
        bad.push(`${cls} 属具名豁免（未登录分支渲染），但 MainActivity 里找不到对它的构造调用`);
      }
      continue;
    }
    bad.push(`${rel} 声明了 ${cls}，但它不在任何 Tab 渲染分支里 —— 页面写了但导航进不去`);
  }
  // requires 形态：null / 端点 id 字面量 / 推导函数调用，三种都要能对上
  for (const t of tabs) {
    const r = t.requires.trim();
    if (r === 'null') continue;
    const lit = /^"([^"]+)"$/.exec(r);
    if (lit) {
      if (!entries.some((e) => e.id === lit[1])) {
        bad.push(`Tab.${t.name} 的 requires="${lit[1]}" 不在生成物里（导航项会静默保留 / 静默消失）`);
      }
      continue;
    }
    const call = /^([A-Za-z_][\w.]*)\(\s*\)$/.exec(r);
    if (call) {
      const fn = call[1].split('.').pop();
      const acc = readCode(join(SRC, 'auth', 'Access.kt'));
      if (!new RegExp(`\\bfun ${escapeRe(fn)}\\s*\\(`).test(acc)) {
        bad.push(`Tab.${t.name} 的 requires 调用了 ${call[1]}()，但 auth/Access.kt 里没有这个函数`
          + '（推导函数必须来自准入层，页面/外壳不得自造依赖源）');
      }
      continue;
    }
    bad.push(`Tab.${t.name} 的 requires 形态未被判据覆盖：${r}`
      + '（新增了第三种写法会静默漏检 —— 第 53 条的形态）');
  }

  if (bad.length) {
    fail('nav-tab-render-triangulation',
      `导航与渲染分支三方不一致：\n      ${bad.join('\n      ')}`);
  } else {
    ok('nav-tab-render-triangulation',
      `${tabs.length} 个 Tab ↔ ${branches.length} 个 when 分支 ↔ ${pageFiles.size} 个页文件（含具名豁免 ${LOGIN_EXEMPT}）三方一致；`
      + 'requires 形态全部可核（字面量命中生成物 / 推导函数定义于 Access.kt）');
  }
}

// ===========================================================================
// ⑤ 端点必须真的被接上（端点层是**生成**的，最容易被"生成了但没人用"）
// ===========================================================================
// 🛑 判的是"生成物里那个 id 在生成物**之外**的源文件里出现过"。
//    实测抓到过一个真实缺陷：B6 `patchIntakeProfile` 在生成物里存在，
//    但域层从未引用它 —— 界面根本发不出这个请求（第 63 条形态）。
{
  const external = KT_ALL.filter((f) => !isGenerated(f));
  const bad = [];
  for (const e of entries) {
    const hit = external.some((f) => {
      const code = readCode(f);
      return new RegExp(`"${escapeRe(e.id)}"`).test(code);
    });
    if (!hit) bad.push(`${e.row} ${e.id}`);
  }
  if (bad.length) {
    fail('endpoint-wired',
      `以下端点在本端源码里**从未被引用**（只在生成物里存在）：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 界面发不出这个请求，而编译 / 构建 / 装机全绿（第 63 条）。'
      + '\n      修法：在 domain/ 里补封装并在某个页面接上入口。');
  } else {
    ok('endpoint-wired', `${entries.length} 个端点在生成物之外的源码中均有引用（无"生成了没人用"）`);
  }
}

// ===========================================================================
// ⑥ 【实测缺陷的守卫】一个端点 id 常量不得被两个封装函数共用
// ===========================================================================
// 🛑 它防的是哪一类缺陷（本仓第 50 条同族）
// ---------------------------------------------------------------------------
// 实测（2026-10-08，本条门禁首跑即抓出）：`domain/IntakeApi.kt` 的
// B6 `patchIntakeProfile()` 用的是 `ENDPOINT_INTAKE`（= "getIntakeProfile"）——
// 一个 GET 端点被当成 PATCH 用，body 与幂等键发出去而服务端根本不读。
// 它能编译、能构建、`gen-endpoints.py --check` 也绿，**只有真发一次请求才暴露**。
// 这与第 50 条同型：**"写下的端点"≠"契约里那个端点"**。
// ⇒ 判据两条，都判【引用形态】：
//    ① 同一文件内，一个端点 id 常量**不得**被两个不同函数用作出站目标（本缺陷的直接形态）；
//    ② 全仓内，一个端点 id **不得**被两个不同函数用作出站目标（跨文件同型）。
{
  const domainFiles = KT_ALL.filter((f) => relPosix(SRC, f).startsWith('domain/'));
  const idToFns = new Map(); // endpointId -> Set(fnName)
  const perFile = new Map(); // rel -> Map(fn -> Set(id))
  let fnCount = 0;

  for (const f of domainFiles) {
    const code = readCode(f);
    const consts = new Map();
    for (const m of code.matchAll(/\bconst val (\w+) = "([^"]*)"/g)) consts.set(m[1], m[2]);
    const fnRe = /suspend fun (\w+)\s*\(/g;
    const marks = [...code.matchAll(fnRe)].map((m) => ({ name: m[1], at: m.index }));
    const fileMap = new Map();
    marks.forEach((mk, i) => {
      const end = i + 1 < marks.length ? marks[i + 1].at : code.length;
      const body = code.slice(mk.at, end);
      const used = new Set();
      for (const c of body.matchAll(/\bapi\.(?:raw|one|items|rawAnonymous|oneAnonymous)\s*\(\s*([A-Za-z_]\w*)/g)) {
        if (consts.has(c[1])) used.add(consts.get(c[1]));
      }
      if (used.size === 0) return;
      fnCount += 1;
      fileMap.set(mk.name, used);
      for (const id of used) {
        if (!idToFns.has(id)) idToFns.set(id, new Set());
        idToFns.get(id).add(`${relPosix(SRC, f)}#${mk.name}`);
      }
    });
    perFile.set(relPosix(SRC, f), fileMap);
  }

  const bad = [];
  for (const [id, fns] of idToFns) {
    if (fns.size > 1) {
      bad.push(`端点 "${id}" 被 ${fns.size} 个封装函数共用：${[...fns].join(' / ')}`);
    }
  }
  for (const [rel, fileMap] of perFile) {
    for (const [fn, ids] of fileMap) {
      if (ids.size > 1) {
        bad.push(`${rel} 的 ${fn}() 在同一次出站里引用了多个端点常量：${[...ids].join(', ')}`);
      }
    }
  }
  if (fnCount === 0) {
    fail('wrapper-own-endpoint', '域层解析出 0 个出站封装 —— 本判据失去检查对象（第 53 条：判据必须能证明覆盖面）');
  } else if (bad.length) {
    fail('wrapper-own-endpoint',
      `封装函数与端点不是一对一：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 一个 GET 端点被当成 PATCH 用这类缺陷，编译 / 构建 / --check **全都不报**，'
      + '只有真发一次请求才暴露（第 50 条）。');
  } else {
    ok('wrapper-own-endpoint',
      `域层 ${fnCount} 个出站封装与端点 id 一对一（无共用、无一个函数打多个端点）`);
  }
}

// ===========================================================================
// ⑦ 准入层不得手抄端点清单（第二份权威）
// ===========================================================================
// 🛑 与 x3-check 的 `x3-no-hardcoded-list` 同款：准入/范围判定必须由生成物**现算**。
//    手抄一份清单的代价是"契约一改就漂移，而漂移不会让任何构建报错"（第 46 条）。
//
// ⚠️ 本条判据**曾经太窄**（反向验证 I11 实测：期望 exit=1，实得 exit=0）
// ---------------------------------------------------------------------------
// 初版正则只认 `["a", "b"]` 这种方括号清单：
//     const listRe = /\[\s*"[\w]+"(?:\s*,\s*"[\w]+")+[^\]]*\]/g;
// 而 Kotlin 里更自然的写法是 `listOf("a", "b")` / `setOf("a", "b")`。
// 注入 I11 写的正是
//     private val MERIDIAN_ONLY_HARDCODED = listOf("createRefund", "getRefund", "createRetention")
// ——它**就是**第二份权威，却从判据底下整个走过去了（`exit=0`）。
// 这是本仓第 55 条：**判据的形态假设 ≠ 代码能写出的形态**，
// 而漏判不会让门禁变红 —— 只会在真出事后才被人发现。
//
// ⇒ 改为**不依赖容器符号**：剥注释后逐个取字符串字面量，凡命中端点 id 即报红。
//    这同时把口径收紧了一档：不再要求"成清单"，**单个**端点 id 字面量也算手抄
//    ——因为 Access.kt 的纪律本就是"不手写任何端点 id"（见本文件类注释）。
//    ⚠️ 注释里的写法（如 `` `requires = "createRefund"` ``）由 `readCode` 剥注释挡掉，
//       不会误报 —— 这是本条放宽后仍能绿的关键前提。
{
  const acc = readCode(join(SRC, 'auth', 'Access.kt'));
  const ids = new Set(entries.map((e) => e.id));
  const hits = [...acc.matchAll(/"([\w]+)"/g)].map((m) => m[1]).filter((n) => ids.has(n));
  if (hits.length) {
    fail('no-hardcoded-endpoint-list',
      `auth/Access.kt 手抄了 ${hits.length} 处端点 id 字面量：${[...new Set(hits)].join(', ')}\n`
      + '      ⇒ 准入/范围判定必须由生成物**现算**（`Endpoints.ALL` / `Endpoints.byId()`）。\n'
      + '      手抄的代价是契约一改就漂移，而漂移不会让任何构建报错（第 46 条）。\n'
      + '      ⚠️ 本判据不依赖容器形态（`[...]` / `listOf(...)` / `setOf(...)` 一律命中）——\n'
      + '      早期只认方括号，`listOf(...)` 形态的注入曾整个漏过（反向验证 I11，第 55 条）。');
  } else {
    ok('no-hardcoded-endpoint-list',
      `auth/Access.kt 无端点 id 字面量（${entries.length} 个 id 全量比对面，准入一律由生成物现算）`);
  }
}

// ===========================================================================
// ⑧ 匿名出站只允许一处（A1）
// ===========================================================================
// 🛑 判【调用点落点】，不判"词是否出现"（第 52 条）——
//    `rawAnonymous` 的定义处与内部转调都会让"词出现"，那是合法的。
//    真正的边界是：**除 api/ 层之外，全仓只允许一处调用，且必须在 authLogin 里。**
{
  const outside = KT_ALL.filter((f) => !relPosix(SRC, f).startsWith('api/'));
  const hits = [];
  for (const f of outside) {
    const code = readCode(f);
    for (const m of code.matchAll(/\b(?:rawAnonymous|oneAnonymous)\s*\(/g)) {
      // 向前找最近的函数声明，判断落点
      const head = code.slice(0, m.index);
      const fn = [...head.matchAll(/\b(?:suspend\s+)?fun\s+(\w+)\s*\(/g)].pop();
      hits.push({ rel: relPosix(SRC, f), fn: fn ? fn[1] : '(函数外)', line: head.split('\n').length });
    }
  }
  const bad = [];
  if (hits.length === 0) {
    bad.push('全仓找不到匿名出站调用点 —— A1 登录将无法发出（或已被改名，请同步本判据）');
  } else if (hits.length > 1) {
    bad.push(`匿名出站有 ${hits.length} 处调用点：`
      + hits.map((x) => `${x.rel}:${x.line}（在 ${x.fn} 内）`).join(' / ')
      + '\n        ⇒ 匿名调用是**唯一**允许绕过令牌准入的方法，多开一处 = 多一条无准入的出口');
  }
  const only = hits[0];
  if (only && (only.rel !== 'domain/IdentityApi.kt' || only.fn !== 'authLogin')) {
    bad.push(`唯一的匿名调用点落在 ${only.rel} 的 ${only.fn}() —— 契约 A1 authLogin 才是唯一合法落点`);
  }
  if (bad.length) {
    fail('anonymous-call-single-site', `匿名出站纪律被破坏：\n      ${bad.join('\n      ')}`);
  } else {
    ok('anonymous-call-single-site', `匿名出站恰 1 处：${only.rel} 的 authLogin()（契约 A1 唯一合法落点）`);
  }
}

// ===========================================================================
// ⑨ 会话键名只有一处定义
// ===========================================================================
// 🛑 Web 端实测过这一类的破坏力：写 `dy.token`、读 `dy_token` ⇒
//    Authorization 头永远为空、全量 401，而构建 / 类型检查 / 既有判据全绿。
//    判法不硬编码键名：**从 SessionStore.kt 现取**，再断言它们不出现在别处。
{
  const storePath = join(SRC, 'data', 'SessionStore.kt');
  if (!existsSync(storePath)) {
    fail('session-key-single-source', 'data/SessionStore.kt 不存在 —— 本判据的检查对象缺失');
  } else {
    const storeCode = readCode(storePath);
    const keys = [...storeCode.matchAll(/\bconst val (\w+)\s*=\s*"([^"]+)"/g)].map((m) => ({ name: m[1], value: m[2] }));
    const bad = [];
    if (keys.length === 0) bad.push('SessionStore.kt 里没解析出任何键名常量 —— 判据失去对象（第 53 条）');
    // ① 全仓只有这一处 getSharedPreferences
    const otherPrefs = KT_ALL.filter((f) => f !== storePath)
      .filter((f) => /\bgetSharedPreferences\s*\(/.test(readCode(f)))
      .map((f) => relPosix(SRC, f));
    if (otherPrefs.length) bad.push(`getSharedPreferences 还出现在：${otherPrefs.join(', ')}（第二处会话存储）`);
    // ② 键值不得在别处作为字面量出现
    for (const k of keys) {
      const offenders = KT_ALL.filter((f) => f !== storePath)
        .filter((f) => new RegExp(`"${escapeRe(k.value)}"`).test(readCode(f)))
        .map((f) => relPosix(SRC, f));
      if (offenders.length) bad.push(`键名 "${k.value}" 在别处以字面量出现：${offenders.join(', ')}`);
    }
    if (bad.length) {
      fail('session-key-single-source',
        `会话键名出现第二处定义：\n      ${bad.join('\n      ')}\n`
        + '      ⇒ 写与读的键名若不一致，令牌会**静默读不到**（表现为"登录成功却一直跳回登录页"），'
        + '而编译 / 构建 / 其它判据都不会报。');
    } else {
      ok('session-key-single-source',
        `会话键名 ${keys.length} 个（${keys.map((k) => k.value).join(' / ')}）只定义于 data/SessionStore.kt，`
        + '全仓仅此一处 getSharedPreferences');
    }
  }
}

// ===========================================================================
// ⑨b 会话值必须加密落盘（不得明文写入 SharedPreferences）
// ===========================================================================
// 🛑 本条判据的存在理由，是一条**真实存在过**的缺陷形态（不是假想）：
//    初版把令牌**明文**写进 SharedPreferences（在 root 设备上可直接读取），
//    并在 `data/SessionStore.kt` 的注释里"如实登记为待补项"。
//    登记是对的，但**登记本身不阻止任何东西** —— 只要有人再加一个 `putString`，
//    缺口就再开一个，而门禁不会说话。
//    ⇒ 教训：缺口一旦收口，必须用判据把它**钉住**，否则那只是"这次改好了"。
//
// 判法分两层，各用与其目标匹配的手法（不共用，因为两者的形态不同）
// ---------------------------------------------------------------------------
//   ① **写入必加密**：每个 `putString` 的第 2 个实参必须含 `encrypt(...)`。
//      按**实参形态**判，不按"词是否出现"判（第 52 条）—— 否则注释里提一句
//      encrypt 就能满足判据。取实参用 [parenInner] **配平**后再顶层切分，
//      所以"实参写成多行"同样能判（第 66 条：不取固定字符窗口）。
//   ② **读取必解密**：`getString` 的出现次数必须**等于** `decrypt` 的次数。
//      这里刻意**不**用"同行是否含 decrypt"那种形态判定：
//      `prefs.getString(...)?.let { decrypt(...) }` 一旦被格式化成两行，
//      同行判定就会**误报** —— 那正是第 55 条（判据的形态假设 ≠ 代码能写出的
//      形态）的另一种翻版，只不过这次是"误杀"而不是"漏杀"。
//      等数判定与写法无关，而"多开一条明文读取路径"必然破坏等数。
{
  const prefsFiles = KT_ALL.filter((f) => /\bgetSharedPreferences\s*\(/.test(readCode(f)));
  if (prefsFiles.length === 0) {
    fail('secure-storage-required',
      '全仓找不到任何 getSharedPreferences 调用 —— 本判据失去检查对象（第 53 条）。\n'
      + '      （若会话存储确实被移除，请一并删掉本条判据；不要让它空转报绿。）');
  } else {
    const bad = [];
    let putTotal = 0;
    let getTotal = 0;
    for (const f of prefsFiles) {
      const rel = relPosix(SRC, f);
      const code = readCode(f);

      const puts = [...code.matchAll(/\.putString\s*\(/g)];
      putTotal += puts.length;
      for (const m of puts) {
        const inner = parenInner(code, m.index + m[0].length - 1);
        if (inner === null) { bad.push(`${rel}: putString(...) 括号不配平，解析失败`); continue; }
        const valueArg = (splitTopLevel(inner)[1] ?? '').trim();
        if (!/encrypt\s*\(/.test(valueArg)) {
          bad.push(`${rel}: putString 的第 2 个实参未经加密 → ${valueArg.slice(0, 64) || '(缺失)'}`);
        }
      }

      const getCount = (code.match(/\.getString\s*\(/g) ?? []).length;
      const decCount = (code.match(/\bdecrypt\s*\(/g) ?? []).length;
      getTotal += getCount;
      if (getCount === 0 && puts.length === 0) {
        bad.push(`${rel}: 既无 putString 也无 getString —— 判据在该文件上失去对象（第 53 条）`);
      }
      if (getCount !== decCount) {
        bad.push(
          `${rel}: 读取与解密次数不等 → getString ${getCount} 次 / decrypt ${decCount} 次，`
          + '差额即"绕开解密的明文读取路径"',
        );
      }
    }

    if (bad.length) {
      fail('secure-storage-required',
        `本地存储未全程加密：\n      ${bad.join('\n      ')}\n`
        + '      ⇒ 明文落盘的令牌在 root 设备上可被直接读取。\n'
        + '      更麻烦的是它**没有任何症状**：功能全对、其它判据全绿，'
        + '直到有人在设备取证时才读到它（第 50 条同型：写下的形态 ≠ 真正的形态）。\n'
        + '      修法：写入走 `data/KeystoreCrypto.kt` 的 `encrypt(alias, plain)`；'
        + '读取走同文件的 `decrypt(alias, stored)`（解不开即当"没有"，不得回落明文）。');
    } else {
      ok('secure-storage-required',
        `${prefsFiles.length} 个持有 SharedPreferences 的文件全量核对：`
        + `${putTotal} 处写入全部经 KeystoreCrypto.encrypt、`
        + `${getTotal} 处读取与 decrypt 等数（无明文旁路）`);
    }
  }
}

// ===========================================================================
// ⑩ 【第 57 / 56 条】协议片段不得手写字面量
// ===========================================================================
// 🛑 判法：**从生成物现取**"哪些字面量是协议片段"，再断言它们不出现在生成物之外
//    （第 53 条：不硬编码"我认识哪几个头名"，新增一个协议常量会自动纳入检查）。
//
// 🛑 两条**具名豁免**（必须逐条写理由，不允许"其它"兜底 —— 那会让新常量静默漏检）
// ---------------------------------------------------------------------------
// 分页的**请求**参数名 `page` / `page_size`：它们的字面量在 Kotlin 侧有合法用途 ——
// Gson 的 `@SerializedName("page_size")` 是**响应**字段的反序列化注解（契约
// `pagination.response-fields`），与"请求参数名"同名但语义不同，而且 Gson 注解
// **只能**吃字符串字面量。故这两个常量不纳入"零字面量"集合，
// 改由下面 ⑩b 判**调用形态**：出站 query 一律经 `Paging.pageQuery()`，
// 不得在任何地方写成 `"page" to ...` / `"page":` 的 map 键。
// （端 A / 端 C 的同款判据 `build-check` ④e 也只扫服务层的出参构造，理由相同。）
const ZERO_LITERAL_EXEMPT = {
  PAGE_FIELD: '契约分页请求参数名 —— 与响应字段同名，Gson @SerializedName 需要字面量；出站侧另由 map 键形态判据守',
  PAGE_SIZE_FIELD: '同上',
};
{
  const protoBlock = /object Protocol \{([\s\S]*?)\n\}/.exec(genText);
  if (!protoBlock) {
    fail('no-protocol-literal', '生成物里找不到 `object Protocol` —— 本判据失去检查对象');
  } else {
    const allProtoStrings = [...protoBlock[1].matchAll(/\bconst val (\w+): String = "([^"]*)"/g)]
      .map((m) => ({ name: m[1], value: m[2] }));
    const basePath = /\bconst val API_BASE_PATH: String = "([^"]*)"/.exec(genText);
    const watched = [
      ...allProtoStrings.filter((c) => !(c.name in ZERO_LITERAL_EXEMPT)),
      ...(basePath ? [{ name: 'API_BASE_PATH', value: basePath[1] }] : []),
    ];
    const exempted = allProtoStrings.filter((c) => c.name in ZERO_LITERAL_EXEMPT);
    const bad = [];
    if (watched.length === 0) bad.push('生成物里没解析出任何协议字符串常量（第 53 条）');
    for (const w of watched) {
      const offenders = KT_ALL.filter((f) => !isGenerated(f))
        .filter((f) => new RegExp(`"${escapeRe(w.value)}"`).test(readCode(f)))
        .map((f) => relPosix(SRC, f));
      if (offenders.length) {
        bad.push(`${w.name} = "${w.value}" 在生成物之外以字面量出现：${offenders.join(', ')}`);
      }
    }
    // 信封成功码：数值没法按字面量扫（0 到处都是），改为判**比较形态**
    const apiTexts = KT_ALL.filter((f) => relPosix(SRC, f).startsWith('api/'));
    const apiJoined = apiTexts.map((f) => readCode(f)).join('\n');
    if (!/Protocol\.ENVELOPE_OK_CODE/.test(apiJoined)) {
      bad.push('api/ 层未引用 Protocol.ENVELOPE_OK_CODE —— 成功码可能被写成了字面量 0');
    }
    if (/\bcode\s*[!=]==?\s*0\b/.test(apiJoined)) {
      bad.push('api/ 层出现了 `code == 0` 形态 —— 信封成功码必须引用生成物常量');
    }
    // ⑩b 分页请求参数名：不得在任何地方写成 map 键（第 58 条）
    for (const f of KT_ALL.filter((x) => !isGenerated(x))) {
      const code = readCode(f);
      for (const m of code.matchAll(/"page(?:_size)?"\s*(?:to\b|:)/g)) {
        bad.push(`${relPosix(SRC, f)}: 分页参数名被手抄成 map 键 —— ${m[0].trim()}`
          + '（出站一律走 Paging.pageQuery()，键名取自生成物常量）');
      }
    }
    const pagingFromGen = ['PAGE_FIELD', 'PAGE_SIZE_FIELD']
      .every((k) => new RegExp(`\\bval ${k}: String = Protocol\\.${k}`).test(readCode(join(SRC, 'api', 'Paging.kt'))));
    if (!pagingFromGen) {
      bad.push('api/Paging.kt 的参数名未从 Protocol.PAGE_FIELD / PAGE_SIZE_FIELD 现取');
    }
    if (bad.length) {
      fail('no-protocol-literal',
        `协议片段在生成物之外被手写：\n      ${bad.join('\n      ')}\n`
        + '      ⇒ 契约 x-api-protocol 一改，这里不会跟着改（第 57 条）；'
        + 'Base Path 少了会让全量 404（第 56 条），而编译 / 构建 / 装机全绿。');
    } else {
      ok('no-protocol-literal',
        `生成物声明的协议字面量（头名 / 令牌前缀 / 越界处置 / Base Path 共 ${watched.length} 个）在生成物之外零残留；`
        + `信封成功码经 Protocol.ENVELOPE_OK_CODE 引用；`
        + `分页请求参数名经 Paging 现取（${Object.keys(ZERO_LITERAL_EXEMPT).length} 个具名豁免：`
        + `${exempted.map((c) => c.name).join(', ')}，理由见本条注释）`);
    }
  }
}

// ===========================================================================
// ⑪ 协议**字段名**必须有生成物常量、且取用点必须引用它（第 57 / 66 条）
// ===========================================================================
// 🛑 本条原名 `envelope-field-literals`（信封字段名与 ENVELOPE_FIELDS **等集合**）。
//    2026-10-08 升级为「引用**具名常量**」—— 这正是原注释里登记的改进路径 (A)。
//    原注释给出的推迟理由（"(A) 要同时改 JS / TS / Kotlin 三个发射器并重生成
//    四个 target 的产物"）**经实测不成立**：三个发射器各自独立，只改 Kotlin 那一段
//    重跑生成器后，另外三端产物**逐字节不变**（git 无改动）。
//    ⇒ 教训：**一个"以后再做"的理由，也要接受一次核实**。理由若只是"当时看着麻烦"，
//      它会把一个 20 行的改动挂在那里很久，而判据一直停在弱形态。
//
// 🛑 为什么形态 (B)「等集合」**结构上抓不到契约改名**（本条的升级动因）
// ---------------------------------------------------------------------------
// 形态 (B) 判的是：`ApiClient` 里出现的信封字段名字面量集合 === `ENVELOPE_FIELDS`。
// 契约把 `data` 改名成 `payload` 后：生成物的 `ENVELOPE_FIELDS` 变成 `[..., payload, ...]`，
// 而 `ApiClient` 里的字面量仍是 `"data"` —— 于是**两侧同时换成"新的那一个"**，
// 等式**依旧成立**（"少一个 data"发生在两侧）。判据与它要防的漂移**同源**，
// 就永远抓不到漂移：这是第 52 条的一种隐蔽形态（**参照物与被判对象一起动**）。
// 而形态 (A) 下，契约改名 ⇒ 常量跟着变 ⇒ **取用点自动正确**；忘记引用则当场报红。
// 字面量残留则由 ⑩ 兜底：⑩ 从 `object Protocol` **现算**所有 `const val X: String = "..."`，
// 新增的具名常量**自动**进入它的零残留射程（第 53 条做对了的回报）。
//
// 🛑 覆盖范围：所有"读 JSON 必须用字符串键"的协议字段名 ——
//    信封四字段（契约 x-api-protocol.envelope-fields）
//    + 分页响应容器键（契约 pagination.response-fields 首位）。
//    不覆盖 `TENANT_HEADER` / `OVER_RANGE_POLICY` / `OVER_RANGE_ERROR`：
//    它们是**描述性**常量（不参与本端读键），本端刻意不发租户头、越界交由服务端按
//    契约 reject-400 处置 —— 该"刻意不用"在 README 已登记，不在这里当缺陷报。
{
  // ① 生成物：具名常量（名→值）、ENVELOPE_FIELDS 列表、ITEMS_FIELD
  const genConsts = new Map(
    [...genText.matchAll(/\bconst val (ENVELOPE_FIELD_[A-Z0-9_]+): String = "([^"]*)"/g)]
      .map((m) => [m[1], m[2]]),
  );
  const envLine = /\bval ENVELOPE_FIELDS: List<String> = listOf\(([^)]*)\)/.exec(genText);
  const envListConsts = envLine
    ? envLine[1].split(',').map((x) => x.trim()).filter(Boolean)
    : [];
  const itemsConst = /\bconst val ITEMS_FIELD: String = "([^"]*)"/.exec(genText);

  // ② 契约真源（不硬编码字段名，第 53 条）
  const cutEnv = /^ {2}envelope-fields:\n((?: {2}- \S+\n)+)/m.exec(cutText);
  const cutEnvFields = cutEnv
    ? [...cutEnv[1].matchAll(/^ {2}- (\S+)$/gm)].map((m) => m[1])
    : [];
  const cutResp = /^ {4}response-fields:\n((?: {4}- \S+\n)+)/m.exec(cutText);
  const cutRespFields = cutResp
    ? [...cutResp[1].matchAll(/^ {4}- (\S+)$/gm)].map((m) => m[1])
    : [];

  const bad = [];
  const apiDir = KT_ALL.filter((f) => relPosix(SRC, f).startsWith('api/'));
  const apiJoined = apiDir.map((f) => readCode(f)).join('\n');
  if (apiDir.length === 0) bad.push('api/ 包下没有任何 Kotlin 文件（本判据失去检查对象）');

  // ③ 解析自检：解析层失效会静默得到空集 ⇒ 下游"全部通过"（第 52/53 条）
  if (cutEnvFields.length === 0) bad.push('未能从裁剪契约解析 x-api-protocol.envelope-fields —— 解析层失效');
  if (cutRespFields.length === 0) bad.push('未能从裁剪契约解析 pagination.response-fields —— 解析层失效');
  if (envListConsts.length === 0) bad.push('生成物里找不到 ENVELOPE_FIELDS 列表 —— 本判据失去检查对象');

  // ④ 生成物 ↔ 契约：具名常量的名与值都要对得上
  const expectConsts = cutEnvFields.map((f) => `ENVELOPE_FIELD_${f.toUpperCase()}`);
  cutEnvFields.forEach((field, i) => {
    const cname = expectConsts[i];
    if (!genConsts.has(cname)) {
      bad.push(`生成物缺具名常量 ${cname}（契约信封字段 "${field}"）`);
    } else if (genConsts.get(cname) !== field) {
      bad.push(`${cname} = "${genConsts.get(cname)}" ≠ 契约信封字段 "${field}"`);
    }
  });
  envListConsts.forEach((cname) => {
    if (!genConsts.has(cname)) bad.push(`ENVELOPE_FIELDS 引用了不存在的常量 ${cname}`);
  });
  if (envListConsts.length !== cutEnvFields.length) {
    bad.push(`ENVELOPE_FIELDS 有 ${envListConsts.length} 项 ≠ 契约信封字段数 ${cutEnvFields.length}`);
  }
  if (!itemsConst) {
    bad.push('生成物缺 ITEMS_FIELD（分页响应容器键）');
  } else if (itemsConst[1] !== cutRespFields[0]) {
    bad.push(`ITEMS_FIELD = "${itemsConst[1]}" ≠ 契约 pagination.response-fields 首位 "${cutRespFields[0]}"`);
  }

  // ⑤ 取用点必须**引用**常量（判成员访问形态，不判"词是否出现"）
  for (const cname of [...expectConsts, 'ITEMS_FIELD']) {
    if (!new RegExp(`\\bProtocol\\.${cname}\\b`).test(apiJoined)) {
      bad.push(`api/ 层未引用 Protocol.${cname} —— 该字段名没有被常量承载`
        + '（要么被手写成字面量、要么根本没读 ⇒ 契约改名后静默取不到值）');
    }
  }

  if (bad.length) {
    fail('protocol-fields-wired',
      `协议字段名的生成物常量未被正确取用：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 后果不是构建失败：契约把 `data` / `items` 改名后，'
      + '        重跑生成器只改常量，取用点不动 ⇒ **列表全空 / 详情全白**，'
      + '        而 debug 包完全正常、构建与门禁全绿。'
      + '\n      修法：出站层写 obj.get(Protocol.ENVELOPE_FIELD_DATA) / Protocol.ITEMS_FIELD。');
  } else {
    ok('protocol-fields-wired',
      `信封 ${expectConsts.length} 个具名常量 + ITEMS_FIELD 均与契约一致，`
      + `且 api/ 层全部以成员访问引用（零字面量由 ⑩ 兜底）；`
      + `分页响应字段共 ${cutRespFields.length} 个（容器 = ${cutRespFields[0]}）`);
  }
}

// ===========================================================================
// ⑫ 【第 56 条】出站前缀 = 网关根 + 契约 Base Path
// ===========================================================================
// 🛑 缺这一条的表现：契约 paths 键是 `/auth/me`，真实 URL 是 `/api/v1/auth/me`。
//    前缀只写在注释里 = 没有约定 ⇒ 运维按注释配网关 ⇒ 全量 404，
//    而编译 / 构建 / 装机全绿（它们从不发真实请求）。
{
  const env = readCode(join(SRC, 'env', 'AppEnv.kt'));
  const bad = [];
  if (!/requestBaseUrl[^\n]*gatewayBaseUrl\s*\+\s*Contract\.API_BASE_PATH/.test(env)) {
    bad.push('AppEnv.requestBaseUrl 不是 `gatewayBaseUrl + Contract.API_BASE_PATH` —— Base Path 前缀丢了');
  }
  if (!/assertConfigured\s*\(/.test(env)) bad.push('AppEnv 没有启动自检函数（缺基址必须拒绝启动）');
  if (/requestBaseUrl[^\n]*startsWith\("http/.test(env) === false && !/isProduction/.test(env)) {
    bad.push('AppEnv 未对生产环境做明文 HTTP 拦截');
  }
  const app = readCode(join(SRC, 'DyTherapistApp.kt'));
  const iAssert = app.indexOf('assertConfigured');
  const iInit = app.indexOf('Graph.init');
  if (iAssert < 0 || iInit < 0) {
    bad.push('DyTherapistApp.onCreate 未同时调用 AppEnv.assertConfigured() 与 Graph.init()');
  } else if (iAssert > iInit) {
    bad.push('启动顺序反了：必须先 assertConfigured() 再 Graph.init()（配置没就绪就不该装配依赖）');
  }
  if (bad.length) {
    fail('base-path-wired', `环境与出站前缀未接对：\n      ${bad.join('\n      ')}`);
  } else {
    ok('base-path-wired',
      '出站前缀 = gatewayBaseUrl + Contract.API_BASE_PATH（取自生成物）；启动自检先于依赖装配；生产禁明文');
  }
}

// ===========================================================================
// ⑬ 色值只允许出现在 colors.xml
// ===========================================================================
// 🛑 本仓 P0 规则第 3 条（不用 emoji 作功能图标 / 不硬编码色值）。
//    判两处：Kotlin 源里的 `#RRGGBB`、`Color.parseColor/rgb/argb`、8 位 ARGB 字面量；
//    以及 res/ 下**除 colors.xml 之外**的 XML。
{
  const bad = [];
  for (const f of KT_ALL) {
    const code = readCode(f);
    const rel = relPosix(SRC, f);
    for (const m of code.matchAll(/#[0-9a-fA-F]{6,8}\b/g)) bad.push(`${rel}: 色值字面量 ${m[0]}`);
    for (const m of code.matchAll(/\bColor\.(?:parseColor|rgb|argb|parseHexString)\s*\(/g)) {
      bad.push(`${rel}: ${m[0]}（颜色必须经 R.color.* / Palette，见 ui/theme/Tokens.kt）`);
    }
    for (const m of code.matchAll(/\b0x[0-9a-fA-F]{8}\b/g)) bad.push(`${rel}: 8 位 ARGB 字面量 ${m[0]}`);
  }
  const XML_WHITELIST = [COLORS_XML];
  for (const f of walk(join(ROOT, 'app', 'src')).filter((x) => x.endsWith('.xml'))) {
    if (XML_WHITELIST.includes(f)) continue;
    const raw = readFileSync(f, 'utf8');
    for (const m of stripXmlComments(raw).matchAll(/#[0-9a-fA-F]{6,8}\b/g)) {
      bad.push(`${relPosix(ROOT, f)}: 色值字面量 ${m[0]}`);
    }
  }
  if (bad.length) {
    fail('no-color-literal',
      `色值出现了 colors.xml 之外的地方：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 改品牌色会变成"全仓 grep"，漏一处就是同一系统两套配色（P0 规则第 3 条）。');
  } else {
    ok('no-color-literal',
      `色值只出现在 res/values/colors.xml（已扫 ${KT_ALL.length} 个 Kotlin 文件 + res 下全部 XML；`
      + '色值均经 R.color.* / Palette 引用）');
  }
}

// ===========================================================================
// ⑭ 明文放行只存在于 debug 源集
// ===========================================================================
// 🛑 这条在 Web 端**没有对应形态** —— 它是原生端特有的接缝：
//    联调要 http，而 Android 9(API 28) 起默认禁明文。把放行写在主清单里
//    = 把"联调方便"直接变成"生产可被中间人劫持"。
//    `src/debug/` 的资源与清单不会进 release 产物 —— 这不是约定，是构建系统的机械事实，
//    因此可以被门禁核验。
{
  const bad = [];
  const mainCode = stripXmlComments(readFileSync(MAN_MAIN, 'utf8'));
  if (/usesCleartextTraffic/.test(mainCode)) {
    bad.push('主清单出现 android:usesCleartextTraffic —— 正式包会放开明文（必须只放 debug 源集）');
  }
  if (/networkSecurityConfig/.test(mainCode)) {
    bad.push('主清单引用了 networkSecurityConfig —— release 产物会带上这份配置');
  }
  if (!existsSync(MAN_DEBUG)) {
    bad.push('缺少 app/src/debug/AndroidManifest.xml（联调期明文无法放行，或放行被塞进了主清单）');
  } else {
    const dbg = stripXmlComments(readFileSync(MAN_DEBUG, 'utf8'));
    if (!/networkSecurityConfig="@xml\/network_security_config"/.test(dbg)) {
      bad.push('debug 清单未引用 @xml/network_security_config');
    }
  }
  if (!existsSync(NSC_DEBUG)) {
    bad.push('缺少 app/src/debug/res/xml/network_security_config.xml');
  } else {
    const nsc = stripXmlComments(readFileSync(NSC_DEBUG, 'utf8'));
    if (!/cleartextTrafficPermitted="true"/.test(nsc)) {
      bad.push('debug 的网络安全配置没有放行明文 —— 联调会直接失败（而这就该失败在联调，不是生产）');
    }
    if (/<certificates\s+src="user"/.test(nsc)) {
      bad.push('debug 的网络安全配置把**用户证书**加入了信任锚 —— 那是"为了联调把生产降级"的经典错误');
    }
  }
  if (existsSync(NSC_MAIN)) {
    bad.push('app/src/main/res/xml/network_security_config.xml 存在 —— 它会进 release 产物');
  }
  // release 的环境名默认值不得是 dev
  const g = readFileSync(APP_GRADLE, 'utf8');
  const releaseBlock = /release\s*\{([\s\S]*?)\n        \}/.exec(g);
  if (!releaseBlock) {
    bad.push('app/build.gradle.kts 里找不到 release 块 —— 无法核对环境名默认值');
  } else if (/ENV_NAME",\s*cfgString\(dyEnvRaw\.ifEmpty\s*\{\s*"dev"\s*\}\)/.test(releaseBlock[1])) {
    bad.push('release 变体的 ENV_NAME 默认值是 "dev" —— 会把正式包标成一个开发环境产物');
  }
  if (bad.length) {
    fail('cleartext-debug-only', `明文/环境配置越界：\n      ${bad.join('\n      ')}`);
  } else {
    ok('cleartext-debug-only',
      '明文放行只存在于 src/debug/（主清单不引用 nsc；debug 不放宽信任锚）；release 默认 env=prod');
  }
}

// ===========================================================================
// ⑮ 契约具名枚举：四端逐字一致
// ===========================================================================
// 🛑 手写枚举最坏的失效是**静默漂移**：契约新增一个维度、或改了一个分龄组的措辞，
//    手写副本不会跟着变，而客户端照旧提交旧值 ⇒ 服务端 400/1001，
//    排查者会去查"是不是后端改了校验"，真实原因是**前端抄的是旧契约**。
//    ⇒ 判据**直接读契约 yaml**（唯一真源），逐值比对四个落点。
{
  const parseContractEnum = (schemaName) => {
    const m = new RegExp(`^ {4}${schemaName}:\\s*$`, 'm').exec(readFileSync(CONTRACT, 'utf8'));
    if (!m) return null;
    const seg = readFileSync(CONTRACT, 'utf8').slice(m.index, m.index + 800);
    const e = /enum:\s*\[([^\]]*)\]/.exec(seg);
    if (!e) return null;
    return e[1].split(',').map((x) => x.trim()).filter(Boolean);
  };
  const cDim = parseContractEnum('Dimension');
  const cAge = parseContractEnum('AgeGroup');

  const kt = readCode(join(SRC, 'domain', 'ContractEnums.kt'));
  const parseKtList = (name) => {
    const m = new RegExp(`val ${name}: List<String> = listOf\\(([\\s\\S]*?)\\)`).exec(kt);
    if (!m) return null;
    return [...m[1].matchAll(/"([^"]*)"/g)].map((x) => x[1]);
  };
  const ktDim = parseKtList('DIMENSION');
  const ktAge = parseKtList('AGE_GROUP');

  const webSrc = existsSync(WEB_DOMAIN) ? readFileSync(WEB_DOMAIN, 'utf8') : '';
  const parseWeb = (name) => {
    const m = new RegExp(`export const ${name}\\s*:[^=]*=\\s*Object\\.freeze\\(\\[([\\s\\S]*?)\\]\\)`).exec(webSrc);
    if (!m) return null;
    return [...m[1].matchAll(/'([^']*)'/g)].map((x) => x[1]);
  };
  const webDim = parseWeb('DIMENSIONS');
  const webAge = parseWeb('AGE_GROUPS');

  const cmp = (label, expect, actual) => {
    if (!expect) return `${label}: 契约侧解析失败（本判据失去真源，必须修）`;
    if (!actual) return `${label}: 前端侧解析失败（写法变更请同步本判据）`;
    if (expect.length !== actual.length) {
      return `${label}: 项数 ${actual.length} ≠ 契约 ${expect.length}`;
    }
    const diff = expect.filter((v, i) => v !== actual[i]);
    if (diff.length) {
      const detail = diff.map((v) => {
        const i = expect.indexOf(v);
        return `${i + 1}: 契约「${v}」vs 前端「${actual[i]}」`;
      }).join('；');
      return `${label}: 取值不一致 —— ${detail}`;
    }
    return null;
  };

  const bad = [
    cmp('Kotlin ContractEnums.DIMENSION', cDim, ktDim),
    cmp('Kotlin ContractEnums.AGE_GROUP', cAge, ktAge),
  ].filter(Boolean);
  // Web 端（端 B · Web）是已存在的对照面：不必因它失败而判红安卓门禁，但要**如实登记**
  const webBad = [
    cmp('Web DIMENSIONS', cDim, webDim),
    cmp('Web AGE_GROUPS', cAge, webAge),
  ].filter(Boolean);

  if (bad.length) {
    fail('named-enum-verbatim',
      `契约具名枚举与原生端取值不一致：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 客户端会照旧提交旧值，服务端 400/1001，而排查方向会被引向"后端改了校验"。');
  } else {
    ok('named-enum-verbatim',
      `契约 Dimension(${cDim.length}) / AgeGroup(${cAge.length}) 与 domain/ContractEnums.kt 逐值逐序一致`
      + `（Web 端同表：DIMENSIONS ${webDim ? webDim.length : '—'} / AGE_GROUPS ${webAge ? webAge.length : '—'}）`);
  }
  if (webBad.length) {
    note(`跨端对照（端 B · Web，非本端门禁的失败项）：${webBad.join(' | ')}`);
  }
}

// ===========================================================================
// ⑯ 契约内联枚举 ↔ 标签表逐字一致
// ===========================================================================
// 🛑 判法：**不硬编码"我认识哪几个枚举"**，而是从契约里把带 `enum:` 的字段
//    连锚定字段名一起枚举出来，再按字段名去标签表里取键 —— 这样
//    "契约新增一个 gap_reason 取值"会自己报红（第 53 条）。
{
  const cLines = readFileSync(CONTRACT, 'utf8').split('\n');
  const byField = new Map();
  let ambiguous = [];
  cLines.forEach((line, i) => {
    const m = /enum:\s*\[([^\]]*)\]/.exec(line);
    if (!m) return;
    let field = null;
    for (let j = i - 1; j >= 0 && j > i - 40; j -= 1) {
      const fm = /^ {4,}(\w+):\s*(?:#.*)?$/.exec(cLines[j]);
      if (fm) { field = fm[1]; break; }
    }
    if (!field) return;
    const vals = m[1].split(',').map((x) => x.trim()).filter(Boolean);
    if (byField.has(field)) ambiguous.push(field);
    else byField.set(field, { vals, line: i + 1 });
  });

  const labelsPath = join(SRC, 'ui', 'theme', 'Labels.kt');
  const lab = readCode(labelsPath);
  const parseTable = (name) => {
    const m = new RegExp(`val ${name}: Map<String, [^>]*> = mapOf\\(([\\s\\S]*?)\\n {4}\\)`).exec(lab);
    if (!m) return null;
    return [...m[1].matchAll(/"([^"]*)"\s*to\b/g)].map((x) => x[1]);
  };

  const WATCH = [
    ['gap_reason', 'GAP_REASON', '③ 组缺口原因分类'],
    ['effect_verdict', 'EFFECT_VERDICT', '④ 组派生结果'],
    ['status', 'CUSTOMER_STATUS', 'customer.status 粗粒度聚合态'],
  ];
  const bad = [];
  let checked = 0;
  for (const [field, table, human] of WATCH) {
    const c = byField.get(field);
    const k = parseTable(table);
    if (!c) { bad.push(`契约里找不到 ${field} 的 enum（字段名可能已改，请同步本判据）`); continue; }
    if (!k) { bad.push(`Labels.kt 里找不到 ${table} 表（写法变更请同步本判据）`); continue; }
    checked += 1;
    if (c.vals.length !== k.length) {
      bad.push(`${table}（契约 ${field}，${human}）：键数 ${k.length} ≠ 契约 ${c.vals.length}`
        + `\n        契约 [${c.vals.join(', ')}]\n        本仓 [${k.join(', ')}]`);
      continue;
    }
    const missing = c.vals.filter((v) => !k.includes(v));
    const extra = k.filter((v) => !c.vals.includes(v));
    if (missing.length || extra.length) {
      bad.push(`${table}（契约 ${field}，${human}）：`
        + (missing.length ? `\n        契约有而本仓无：${missing.join(', ')}` : '')
        + (extra.length ? `\n        本仓有而契约无：${extra.join(', ')}` : '')
        + '\n        ⇒ 键名若被"顺手规范化"，映射会在服务端下发真实枚举值时**静默落空**，'
        + '表现为"显示原始英文码"而不是报错。');
    }
  }
  if (ambiguous.length) {
    note(`契约里字段名重名（判据按最近锚点取值，已记录）：${[...new Set(ambiguous)].join(', ')}`);
  }
  if (bad.length) {
    fail('enum-labels-verbatim', `标签表与契约枚举不一致：\n      ${bad.join('\n      ')}`);
  } else {
    ok('enum-labels-verbatim',
      `${checked}/${WATCH.length} 张标签表（gap_reason ${byField.get('gap_reason')?.vals.length} 值 / `
      + `effect_verdict ${byField.get('effect_verdict')?.vals.length} 值 / `
      + `status ${byField.get('status')?.vals.length} 值）与契约逐值一致`
      + '（按字段名锚定，契约新增取值会自己报红）');
  }
}

// ===========================================================================
// ⑰ 契约必需参数在调用链上真的带上了（第 65 / 71 条）
// ===========================================================================
// 🛑 判【实参形态】，不判"名字在文件里出现过"（第 52 条）。
//    · path 参数：URL 里的 `{id}`。漏传会拼出**字面量 `{id}`** 发出去，
//      后端路由不匹配返回 404，调用点会误判成"资源不存在"。
//    · query 参数：漏传会被服务端 400，而漏传写法在本地毫无症状。
//    · body 必需字段：`body` 若由封装层内构造 ⇒ 在封装层逐名核；
//      若由调用方传入 Map ⇒ 到**调用点的花括号块**里核（用括号配平取块，
//      不用固定字符窗口 —— 固定窗口是隐含假设，两端口径都不对，见第 66 条）。
{
  // 端点 → 封装函数名（在 ⑥ 已解析过一遍，这里独立重算，避免块级耦合）
  const idToFn = new Map();
  for (const f of KT_ALL.filter((x) => relPosix(SRC, x).startsWith('domain/'))) {
    const code = readCode(f);
    const consts = new Map();
    for (const m of code.matchAll(/\bconst val (\w+) = "([^"]*)"/g)) consts.set(m[1], m[2]);
    const marks = [...code.matchAll(/suspend fun (\w+)\s*\(/g)].map((m) => ({ name: m[1], at: m.index }));
    marks.forEach((mk, i) => {
      const end = i + 1 < marks.length ? marks[i + 1].at : code.length;
      const body = code.slice(mk.at, end);
      const c = /\bapi\.(?:raw|one|items|rawAnonymous|oneAnonymous)\s*\(\s*([A-Za-z_]\w*)/.exec(body);
      if (c && consts.has(c[1]) && !idToFn.has(consts.get(c[1]))) {
        idToFn.set(consts.get(c[1]), { fn: mk.name, file: f, body });
      }
    });
  }
  const pages = KT_ALL.filter((f) => relPosix(SRC, f).startsWith('ui/pages/'));

  const argForms = (name, text) => new RegExp(`"${escapeRe(name)}"\\s*to\\b`).test(text)
    || new RegExp(`\\["${escapeRe(name)}"\\]\\s*=`).test(text)
    || new RegExp(`\\b${escapeRe(name)}\\b\\s*=\\s*[^=]`).test(text);

  const bad = [];
  let withRequired = 0;
  let checkedPath = 0;
  let checkedQuery = 0;
  let checkedBodyInWrapper = 0;
  let checkedBodyAtCall = 0;
  let skippedBody = 0;

  for (const e of entries) {
    const needPath = e.requiredPath;
    const needQuery = e.requiredQuery;
    const needBody = e.requiredBody;
    if (!needPath.length && !needQuery.length && !needBody.length) continue;
    withRequired += 1;
    const w = idToFn.get(e.id);
    if (!w) { bad.push(`${e.id}：找不到封装函数（触达问题由 endpoint-wired 负责，此处如实记）`); continue; }

    // path + query：一律在封装层看（path 参数只可能由封装层提供）
    const absentPQ = [...needPath, ...needQuery].filter((n) => !argForms(n, w.body));
    if (absentPQ.length) {
      bad.push(`${e.id}（${e.method} ${e.path}）的封装 ${w.fn}() 未带上必需参数 ${absentPQ.join(', ')}`
        + `\n        契约 required：path [${needPath.join(', ')}] query [${needQuery.join(', ')}]`);
    }
    if (needPath.length) checkedPath += 1;
    if (needQuery.length) checkedQuery += 1;

    // body：封装层内构造 ⇒ 在封装层核；否则到调用点的花括号块里核
    if (!needBody.length) continue;
    if (/linkedMapOf|hashMapOf|mutableMapOf/.test(w.body)) {
      const miss = needBody.filter((n) => !argForms(n, w.body));
      if (miss.length) {
        bad.push(`${e.id} 的封装 ${w.fn}() 内构造了请求体，但缺必需字段 ${miss.join(', ')}`
          + `\n        契约 required：body [${needBody.join(', ')}]`);
      }
      checkedBodyInWrapper += 1;
      continue;
    }
    let sites = 0;
    let missAll = needBody.slice();
    for (const p of pages) {
      const code = readCode(p);
      const re = new RegExp(`\\b${escapeRe(w.fn)}\\s*\\(`, 'g');
      let m;
      while ((m = re.exec(code))) {
        const blk = enclosingBrace(code, m.index);
        if (!blk) continue;
        sites += 1;
        missAll = missAll.filter((n) => !argForms(n, blk));
        if (missAll.length === 0) break;
      }
      if (missAll.length === 0) break;
    }
    if (sites === 0) {
      skippedBody += 1; // 触达问题由 endpoint-wired 负责
    } else if (missAll.length) {
      bad.push(`${e.id} 的封装 ${w.fn}() 由调用方传 Map，但调用点里没见必需字段 ${missAll.join(', ')}`
        + `\n        契约 required：body [${needBody.join(', ')}]，已扫 ${sites} 个调用点`);
    } else {
      checkedBodyAtCall += 1;
    }
  }

  if (withRequired === 0) {
    fail('required-args-wired',
      '生成物里没有任何端点带必需参数声明 —— 解析层没转出，本条会**静默放过全部端点**（第 53 条）');
  } else if (bad.length) {
    fail('required-args-wired',
      `以下端点的调用链没有带上契约必需参数（后端必然 400/404，而编译 / 构建 / 装机全绿）：\n      `
      + bad.join('\n      ')
      + '\n      ⇒ "端点被调用了"不等于"调用是对的"（第 65 / 71 条）。');
  } else {
    ok('required-args-wired',
      `${withRequired} 个带必需声明的端点全部核对：path ${checkedPath} 个 · query ${checkedQuery} 个 · `
      + `body（封装层内构造）${checkedBodyInWrapper} 个 · body（调用点构造）${checkedBodyAtCall} 个`
      + (skippedBody ? ` · ${skippedBody} 个 body 因尚未接上界面而未核对（触达问题由 endpoint-wired 负责，如实计数不静默放过）` : '')
      + '（判实参形态，非判词出现 —— 第 52 条）');
  }
}

// ===========================================================================
// ⑱ release 闸门必须存在（缺签名不得静默产出 unsigned 包）
// ===========================================================================
// 🛑 本条锚定的是一个**实测出来的坑**（完整记录见 README 第六节第 1 条）：
//    AGP 在 release 没有 `signingConfig` 时**不会报错** —— 它 BUILD SUCCESSFUL
//    并产出 `app-release-unsigned.apk`。那个包**装不上**，而构建日志是绿的、
//    文件也在，于是它的典型结局是被人用 debug key 签了发出去。
//    ⇒ `app/build.gradle.kts` 末尾加了一道闸门（`gradle.taskGraph.whenReady`）。
//
// 为什么闸门本身也需要判据
// ---------------------------------------------------------------------------
// 与 ⑨b（`secure-storage-required`）同一条理由：**新增的保护若没人钉住，
// 就会在某次"重构构建脚本"时被顺手删掉** —— 而删掉之后一切照常：
// 不报错、不影响 debug 构建、门禁也不会说话，直到真的有人打出 unsigned 包。
// ⇒ 此处按**存在性**逐项核对闸门的六个组成部分，任一缺失即报红。
{
  const gradlePath = join(ROOT, 'app', 'build.gradle.kts');
  if (!existsSync(gradlePath)) {
    fail('release-gate-present', 'app/build.gradle.kts 不存在 —— 本判据失去检查对象');
  } else {
    // 剥注释后再判：在注释里写一句 taskGraph.whenReady 不能满足判据（第 41 / 51 条）
    const code = readCode(gradlePath);
    const parts = [
      [/gradle\.taskGraph\.whenReady/, 'taskGraph.whenReady 闸门入口'],
      [/\bassembleRelease\b/, 'assembleRelease 判定'],
      [/\bbundleRelease\b/, 'bundleRelease 判定'],
      [/missingSigningProps/, '缺属性清单（报错必须说清"差什么"）'],
      [/throw\s+GradleException/, '抛错动作（不产出任何包）'],
      [/\bhasSigning\b/, 'hasSigning 开关'],
    ];
    const missing = parts.filter(([re]) => !re.test(code)).map(([, name]) => name);
    if (missing.length) {
      fail('release-gate-present',
        `app/build.gradle.kts 的 release 闸门不完整，缺少：\n      ${missing.join('\n      ')}\n`
        + '      ⇒ 缺签名时 `assembleRelease` 会**安静地产出 unsigned APK**'
        + '（AGP 的默认行为，已实测确认），\n'
        + '      而它装不上、构建日志却是绿的 —— 极易被当成"已经出包了"，'
        + '再被 debug key 签一次发出去。\n'
        + '      完整闸门见 README 第六节第 1 条。'
        + '不要因为"debug 构建不受影响"就认为它多余。');
    } else {
      ok('release-gate-present',
        `${parts.length} 项闸门要素齐全（缺签名时 release 打包会被当场拦下，不产出 unsigned 包）`);
    }
  }
}

// ===========================================================================
// ⑲ 启动图标必须能从 manifest 一路解析到真实资源
// ===========================================================================
// 本条防的两类缺陷，**表现差异很大**，且都不以"构建报错"的形式出现：
//   ① 声明回退：把 `android:icon` 改回 `@drawable/<矢量>`。
//      后果不是编译失败，而是 API 26+ 显示成带白边的方块（矢量没有安全区
//      概念），API < 26 的部分 launcher 根本渲染不出。而本端 minSdk = 24，
//      位图那套**不是可选项**。
//   ② 变体缺失：少补一档密度 / 删了 anydpi-v26 的自适应 XML。
//      `assembleDebug` 照样 SUCCESSFUL —— aapt2 只在**引用悬空**时报错，
//      "少一档密度"是它允许的（运行时由系统缩放），于是那档设备拿到一张
//      被拉伸的糊图。这种事没人会开 bug。
//
// 🛑 判据形态（第 53 条）：**图标名从 manifest 现算**，不硬编码 `ic_launcher`。
//    否则改名 / 换品牌资源就等于把判据悄悄废掉，而它仍然"绿着"。
//
// 如实登记的边界（第 55 条）——本条**不替代** Gradle 构建：
//    · 能替代的部分：把"引用悬空"从"等一次完整构建"提前到"几秒内"。
//    · 替代不了的部分：资源合并冲突、限定符拼错（`mipmap-xhdpi` 写成
//      `mipmap-xhdi`）这类只有 aapt2 能判的，本判据看不出来。
{
  const resDir = join(ROOT, 'app', 'src', 'main', 'res');
  const DENSITIES = ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'];
  const man = stripXmlComments(readFileSync(MAN_MAIN, 'utf8'));
  const bad = [];
  const notes2 = [];

  /** 资源引用 `@type/name` 是否在 res 下真的落地（只做存在性；`@android:` / `?attr` 不算）。 */
  const resourceLands = (ref) => {
    const m = /^@(\w+)\/([\w.]+)$/.exec(ref);
    if (!m) return null; // 平台引用 —— 不在本判据射程内，不报红也不声称查过
    const [, type, name] = m;
    if (type === 'color') {
      const p = join(resDir, 'values', 'colors.xml');
      return existsSync(p) && new RegExp(`<color\\s+name="${name}"`).test(readFileSync(p, 'utf8'));
    }
    return readdirSync(resDir)
      .filter((d) => d === type || d.startsWith(`${type}-`))
      .some((d) => ['xml', 'png', 'webp', 'jpg'].some((ext) => existsSync(join(resDir, d, `${name}.${ext}`))));
  };

  // ① 声明层
  const iconM = /android:icon="([^"]+)"/.exec(man);
  const roundM = /android:roundIcon="([^"]+)"/.exec(man);
  const nameOf = (v) => (v && v.startsWith('@mipmap/') ? v.slice('@mipmap/'.length) : null);
  const iconName = nameOf(iconM && iconM[1]);
  const roundName = nameOf(roundM && roundM[1]);

  if (!iconM) bad.push('manifest 未声明 android:icon —— 系统会用默认图标');
  else if (!iconName) {
    bad.push(`android:icon="${iconM[1]}" 未指向 @mipmap/`
      + '（@drawable/ 的矢量作 launcher icon：API 26+ 显示成带白边方块，'
      + 'API < 26 部分 launcher 渲染不出 —— 本端 minSdk = 24 故不可用）');
  }
  if (!roundM) bad.push('manifest 未声明 android:roundIcon（部分 launcher 会因此显示方形）');
  else if (!roundName) bad.push(`android:roundIcon="${roundM[1]}" 未指向 @mipmap/`);

  // ② 资源层：manifest 声明的每个名字，逐档密度 + 自适应 XML 都要在
  for (const [label, name] of [['icon', iconName], ['roundIcon', roundName]]) {
    if (!name) continue;
    const missD = DENSITIES.filter((d) => !existsSync(join(resDir, `mipmap-${d}`, `${name}.png`)));
    if (missD.length) {
      bad.push(`${label} = @mipmap/${name} 缺少密度位图：${missD.join(', ')}`
        + '（API 24/25 只能走位图，缺档会被系统缩放成糊图；本仓由脚本产出 5 档齐全）');
    }
    const axPath = join(resDir, 'mipmap-anydpi-v26', `${name}.xml`);
    if (!existsSync(axPath)) {
      bad.push(`${label} = @mipmap/${name} 缺少 mipmap-anydpi-v26/${name}.xml（API 26+ 将没有自适应图标）`);
      continue;
    }
    const ax = stripXmlComments(readFileSync(axPath, 'utf8'));
    // background / foreground 各自必须"存在 + 引用落地"。缺元素与引用悬空分开报，
    // 避免同一个原因出两条措辞相近的错（读的人会以为是两个问题）。
    for (const tag of ['background', 'foreground']) {
      if (!new RegExp(`<${tag}\\b`).test(ax)) {
        bad.push(`mipmap-anydpi-v26/${name}.xml 缺 <${tag}> 元素`);
        continue;
      }
      const r = new RegExp(`<${tag}\\b[^>]*android:drawable="([^"]+)"`).exec(ax);
      if (!r) { bad.push(`mipmap-anydpi-v26/${name}.xml 的 <${tag}> 没有 android:drawable 引用`); continue; }
      const land = resourceLands(r[1]);
      if (land === false) bad.push(`mipmap-anydpi-v26/${name}.xml 引用的 ${r[1]} 在 res 下找不到（悬空引用）`);
      else if (land === null) notes2.push(`  – 自适应图标 ${tag} 引用平台资源 ${r[1]}，不在本判据射程内`);
    }
  }

  if (bad.length) {
    fail('launcher-icon-complete',
      `启动图标不能从 manifest 解析到完整资源：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 注意这两类缺陷**都不以构建报错的形式出现**（少一档密度 aapt2 是允许的，\n'
      + '      改回矢量 drawable 也能编译过），所以只能靠本条拦住。\n'
      + '      结构要求见 README 第六节第 2 条；图标当前是脚本生成的几何标记。');
  } else {
    ok('launcher-icon-complete',
      `icon / roundIcon 均指向 @mipmap 且资源完整：各 ${DENSITIES.length} 档位图 + anydpi-v26 自适应 XML，`
      + `background / foreground 引用全部落地（覆盖 minSdk 24 的位图回退与 API 26+ 自适应两条路径）`);
  }
  notes.push(...notes2);
}

// ===========================================================================
// ⑳ Gson 反射面必须被 keep 规则覆盖（R8 会抹掉裸字段 —— 实测 P0）
// ===========================================================================
// 🛑 本条锚定的是本工程**实测到的最高危缺陷**（完整记录见 README 第六节「✅ 已收口：R8 抹掉 Gson DTO 字段」）
// ---------------------------------------------------------------------------
// Gson 按【字段名】反射映射，而 R8 的字段改名 / 移除对它不可见。Gson 的 AAR
// 自带规则只保护带 `@SerializedName` 的字段 —— 而本端 DTO 里"字段名 == JSON
// 键名"的字段是**裸写**的（不写同义反复的注解），于是被 R8 抹掉。
//
// 实测（2026-10-08，`isMinifyEnabled = true`）：`LoginData` 源码 6 个字段
// → DEX 里只剩 4 个，`token` / `role` **整体消失**
// ⇒ `IdentityApi.authLogin()` 的 `data.token.isBlank()` 必然失败
// ⇒ **A1 登录在 release 包上根本走不通**。
// 而三条防线同时失守：`assembleRelease` 是绿的、**debug 包完全正常**、
// 结构门禁只看源码形态。⇒ 这一类只能靠本条钉住。
//
// 🛑 判据形态：**从调用点现算反射面**，不硬编码 DTO 名单
// ---------------------------------------------------------------------------
// 硬编码名单会在"新增一个 DTO 并接上 Gson"时静默过期 —— 而那个新 DTO
// 恰好就是下一个会丢字段的。故本条：
//   ① 扫 `type = X::class.java` / `element = X::class.java` /
//      `fromJson(_, X::class.java)` 现算"哪些类进了 Gson 的反射面"；
//   ② 用源码里的 `package` 声明算全名（**声明是事实，路径只是约定**）；
//   ③ 核对每个全名都落在某条 keep 规则的覆盖内。
// ⇒ 新 DTO 一旦接上 Gson 而 keep 没覆盖它（例如放进了 `domain` 之外的包），
//   这里当场报红，而不是等它上了正式包丢字段。
//
// 🛑 顺带钉住"死规则"：keep 指向的类必须真实存在
// ---------------------------------------------------------------------------
// 本文件原本有一条 `-keep class …api.ApiEnvelope { *; }`，而 `ApiEnvelope`
// **全仓不存在**（R8 对这样的 keep **静默忽略、不报错**）。它的注释写着
// "信封与请求体 DTO 的字段名必须保留"，实际保护了**零个类** —— 而真正会被
// 抹掉的 16 个 DTO 一个都不在射程内。这是本仓"**写下的检查 ≠ 存在的检查**"
// 最典型的一例：语法正确、位置合理、注释充分的规则，可以完全不起作用。
//
// 如实登记的边界（第 55 条）
// ---------------------------------------------------------------------------
//   · 本条保证的是"**规则覆盖**"，不是"R8 之后字段一定正确"。规则语法对但
//     语义不足（例如误写成 `{ <methods>; }`）本判据看不出来。
//     真正的验证必须**跑 release 并核 mapping/DEX** —— 方法见 README 第六节。
//   · ② 按**简单类名**索引包名，故本工程内同类名不得重复。若将来出现重名，
//     须改为按全限定名索引（现在的 16 个 DTO 名互不冲突）。
{
  const PRO_PATH = join(ROOT, 'app', 'proguard-rules.pro');
  if (!existsSync(PRO_PATH)) {
    fail('gson-reflect-surface', 'app/proguard-rules.pro 不存在 —— 本判据失去检查对象');
  } else {
    const bad = [];

    // ① 反射面：从调用点现算
    const targets = new Map();
    for (const p of KT_ALL) {
      const code = readCode(p);
      const rel = relative(SRC, p);
      for (const m of code.matchAll(/\b(?:type|element)\s*=\s*(\w+)::class\.java/g)) targets.set(m[1], rel);
      for (const m of code.matchAll(/\.fromJson\s*\([^,()]*,\s*(\w+)::class\.java/g)) targets.set(m[1], rel);
    }

    // ② 类名 → 声明所在包
    const pkgOf = new Map();
    for (const p of KT_ALL) {
      const text = readText(p);
      const pkg = (/^package\s+([\w.]+)/m.exec(text) ?? [])[1] ?? '';
      for (const m of text.matchAll(/(?:^|\s)(?:data\s+|enum\s+|sealed\s+|abstract\s+|open\s+|annotation\s+)*class\s+(\w+)/gm)) {
        pkgOf.set(m[1], pkg);
      }
      for (const m of text.matchAll(/(?:^|\s)object\s+(\w+)/gm)) pkgOf.set(m[1], pkg);
    }

    // ③ keep 规则里的类模式（先剥 `#` 注释：在注释里写一句 keep 不能满足判据）
    const pro = readText(PRO_PATH).replace(/(^|\s)#[^\n]*/g, ' ');
    const patterns = [...pro.matchAll(/-keep[\w]*\s+class\s+([\w.$*]+)/g)].map((m) => m[1]);

    /** ProGuard 通配符 → 正则：`**` 跨包，`*` 不跨包。 */
    const patternToRe = (pat) => {
      let out = '';
      for (let i = 0; i < pat.length; i += 1) {
        const c = pat[i];
        if (c === '*') {
          if (pat[i + 1] === '*') { out += '.*'; i += 1; } else out += '[^.]*';
        } else if ('.$^+?()[]{}|\\'.includes(c)) out += `\\${c}`;
        else out += c;
      }
      return new RegExp(`^${out}$`);
    };

    // ④ 每个反射目标都必须被覆盖
    //    ⚠️ 判据射程：只判**本工程源码里声明的类**。`Map::class.java` 这类
    //       平台 / 第三方类型走 Gson 内置 adapter（不反射字段），无需 keep；
    //       而"本工程类被写错名"会让 Kotlin 编译失败，也不需要判据兜。
    //       ⇒ 找不到声明的目标记 note 如实登记，不计入失败。
    const outOfScope = [];
    for (const [cls, where] of targets) {
      const pkg = pkgOf.get(cls);
      if (pkg === undefined) {
        outOfScope.push(`${cls}（${where}）`);
        continue;
      }
      const full = `${pkg}.${cls}`;
      if (!patterns.some((pat) => patternToRe(pat).test(full))) {
        bad.push(`${full}（${where}）不在任何 keep 规则覆盖内 —— R8 会抹掉它的裸字段`);
      }
    }

    // ⑤ 死规则：keep 里的具体类名必须真实存在（只查本工程内的类，第三方包名不适用）
    const dead = patterns.filter(
      (pat) => !pat.includes('*')
        && pat.startsWith('com.diaoyuanyun.therapist')
        && !pkgOf.has(pat.split('.').pop()),
    );
    if (dead.length) {
      bad.push(`keep 规则指向**不存在**的类：${dead.join(', ')}`
        + ' —— R8 对这类规则静默忽略、不报错，等于"看起来在保护、实际零覆盖"');
    }

    if (bad.length) {
      fail('gson-reflect-surface',
        `Gson 的反射面没有被 keep 规则完整覆盖：\n      ${bad.join('\n      ')}\n`
        + '      ⇒ 后果不是构建失败，而是**只在 release 包上生效**的功能失效：\n'
        + '        `assembleRelease` 是绿的、debug 包完全正常、门禁也看不见\n'
        + '        R8 之后的世界 —— 只有装机后才表现为"登录没反应 / 列表全空"。\n'
        + '      完整实测记录与验证命令见 README 第六节「✅ 已收口：R8 抹掉 Gson DTO 字段」。');
    } else {
      const judged = targets.size - outOfScope.length;
      if (outOfScope.length) {
        notes.push(`  – Gson 反射目标里的平台 / 第三方类型（走 Gson 内置 adapter，不在本判据射程）：${outOfScope.join('、')}`);
      }
      ok('gson-reflect-surface',
        `${judged} 个 Gson 反射目标类全部落在 keep 覆盖内`
        + `（${patterns.filter((p) => p.includes('*')).join('、') || '无明显通配规则'}）；`
        + 'keep 里的具体类名均真实存在（无死规则）。'
        + '⚠️ 本条保证"规则覆盖"，R8 之后的真实性须跑 release 核 mapping —— 见 README 第六节');
    }
  }
}

// ===========================================================================
// ㉑ 响应 DTO ↔ 契约 schema **字段集合相等**（本仓第 66 条）
// ===========================================================================
// 🛑 它堵的洞
// ---------------------------------------------------------------------------
// 本端 Kotlin **没有 SDK 代码生成**（`contract/sdk-generator/generator-matrix.yaml`
// 只覆盖三端 Web / 小程序），所以 `domain/Models.kt` 的 DTO 是**手写**的 ——
// 它们是契约 `components.schemas` 的**第二份权威**。
// 手工抄写会以两种方式漂移，而两种都**不报错**：
//   · `@SerializedName("screening_id")` 抄成 `"screeningId"` ⇒ 取不到值 ⇒ **null**；
//   · 契约把 `customer_id` 改名 ⇒ 代码若不同步 ⇒ 同样是 **null**。
// 表现是"这个字段界面上永远是空的"，而编译 / 构建 / 契约生成器 `--check` /
// 全部门禁**一律绿**。§2.0 的列举式字段（`LoginData.token`、`Store.name`）
// 尤其危险：它们**没有注解**，键名就是字段名，改了名字连注释都不会变。
//
// 🛑 判据形态（第 52/53 条）
// ---------------------------------------------------------------------------
//   A. 「端点 → data schema」取自**生成物** `Endpoint.dataSchema`（生成器从契约转录）；
//      同时与**契约真源**（_cut 的 `data: $ref:`）双向对拍 —— 两侧独立解析，
//      任何一侧解析失效都会当场报红（不是"静默看到空集然后全绿"）。
//   B. schema 的 properties 从**契约真源**现算（不硬编码字段名）。
//   C. DTO 字段集合必须**恰好等于** schema 的 properties（双向）。
//      只判"DTO ⊆ 契约"会放过"契约新增字段但 DTO 没接收"—— 那同样是静默丢字段；
//      只判"契约 ⊆ DTO"会放过拼错的注解值。等式才两个方向都盖住。
//
// 🛑 射程（如实登记，不夸大）
// ---------------------------------------------------------------------------
//   · 契约声明了形状的 14 个端点 → **全覆盖**；
//   · 未声明形状的 15 个端点（`data` 只写 `{type: object, nullable: true}`）→
//     无法机械核对；代码侧一律 `JsonObject` / `Map` 原样透传、不臆造字段名，
//     调用点记 note（`Models.kt` 头部已逐条登记这 15 个 operationId）；
//   · **请求体键名不在射程内**：契约声明了 7 个请求体 schema，而本端请求体是
//     手写的 `linkedMapOf("键" to 值)`。实测（2026-10-08）：请求体构造点与
//     operation 之间**没有可静态追踪的映射**（同一文件里多个构造点、变量经形参
//     传递），硬做会产生假红 —— 而假红判据会被删（第 55 条）。
//     故只**登记**，不硬凑（README 第七节）。
{
  const bad = [];
  const notes2 = [];

  // ---------- ① 契约真源：components.schemas 的直接 properties ----------
  // 缩进形态（_cut 实测稳定）：`  schemas:`(2) / `    Name:`(4) /
  //                              `      properties:`(6) / `        key:`(8)
  const schemaProps = new Map();
  {
    const lines = cutText.split('\n');
    let inSchemas = false;
    let cur = null;
    let inProps = false;
    for (const ln of lines) {
      if (!inSchemas) {
        if (/^ {2}schemas:\s*$/.test(ln)) inSchemas = true;
        continue;
      }
      if (/^ {2}\S/.test(ln) && !/^ {2}#/.test(ln)) break; // 离开 schemas 段
      const mSchema = /^ {4}([A-Za-z0-9_]+):\s*$/.exec(ln);
      if (mSchema) { cur = mSchema[1]; schemaProps.set(cur, new Set()); inProps = false; continue; }
      if (/^ {6}properties:\s*$/.test(ln)) { inProps = true; continue; }
      if (/^ {6}\S/.test(ln)) { inProps = false; continue; }
      if (inProps && cur) {
        const mProp = /^ {8}([A-Za-z_][A-Za-z0-9_]*):/.exec(ln);
        if (mProp) schemaProps.get(cur).add(mProp[1]);
      }
    }
  }

  // ---------- ② 契约真源：200 响应 data 的 $ref（独立于生成物） ----------
  const cutRefs = [...cutText.matchAll(
    /\n\s+data:\n\s+\$ref: '#\/components\/schemas\/([A-Za-z0-9_]+)'/g,
  )].map((m) => m[1]);
  const genRefs = entries.map((e) => e.dataSchema).filter(Boolean);

  // ---------- ③ 解析自检（第 52/53 条） ----------
  if (schemaProps.size === 0) {
    bad.push('未能从裁剪契约解析出任何 components.schemas.properties —— 解析层失效会让本判据**静默放行**');
  }
  if (cutRefs.length === 0) {
    bad.push('未能从裁剪契约解析出任何 200 响应 data 的 $ref —— 解析层失效（契约形态变了？）');
  }

  // ---------- ④ 生成物 ↔ 契约：dataSchema 双向对拍 ----------
  const genRefSet = new Set(genRefs);
  const cutRefSet = new Set(cutRefs);
  if (genRefs.length !== cutRefs.length) {
    bad.push(`生成物有 ${genRefs.length} 个 dataSchema ≠ 契约 ${cutRefs.length} 个 data.$ref`
      + '（生成器漏转 / 多转，或契约已改而生成器未重跑）');
  }
  for (const s of cutRefSet) {
    if (!genRefSet.has(s)) bad.push(`契约声明了 ${s} 但生成物没有端点指向它（生成器漏转）`);
  }
  for (const s of genRefSet) {
    if (!cutRefSet.has(s)) bad.push(`生成物凭空多出 dataSchema=${s}（契约里没有这个 data.$ref）`);
  }
  for (const s of genRefSet) {
    const props = schemaProps.get(s);
    if (props === undefined) bad.push(`生成物声明 dataSchema=${s}，但契约 components.schemas 里没有这个模型`);
    else if (props.size === 0) {
      bad.push(`生成物声明 dataSchema=${s}，而契约该模型没有 properties（形状实际上未声明）`);
    }
  }

  // ---------- ⑤ 代码侧：domain/Models.kt 的 DTO 字段集合 ----------
  const modelsPath = join(SRC, 'domain', 'Models.kt');
  const dtoFields = new Map();
  if (!existsSync(modelsPath)) {
    bad.push('domain/Models.kt 不存在 —— 本判据失去检查对象');
  } else {
    const code = readCode(modelsPath);
    for (const m of code.matchAll(/\bdata class\s+(\w+)\s*\(/g)) {
      const open = code.indexOf('(', m.index + m[0].length - 1);
      const inner = parenInner(code, open);
      if (inner === null) { bad.push(`解析 ${m[1]} 的构造参数失败（括号不配平？）`); continue; }
      const fields = new Set();
      for (const part of splitTopLevel(inner)) {
        const sm = /@SerializedName\("([^"]+)"\)/.exec(part);
        if (sm) { fields.add(sm[1]); continue; }
        const vm = /\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:/.exec(part);
        if (vm) fields.add(vm[1]);
      }
      dtoFields.set(m[1], fields);
    }
    if (dtoFields.size === 0) bad.push('domain/Models.kt 里没解析出任何 data class —— 解析层失效');
  }

  // ---------- ⑥ 代码侧：反序列化落点（type = X::class.java / oneAnonymous 第 4 实参） ----------
  const typedSites = new Map(); // DTO → 位置
  const elementTypes = new Set();
  for (const p of KT_ALL.filter((f) => !isGenerated(f))) {
    const code = readCode(p);
    const rel = relPosix(SRC, p);
    for (const m of code.matchAll(/\btype\s*=\s*(\w+)::class\.java/g)) typedSites.set(m[1], rel);
    for (const m of code.matchAll(/\belement\s*=\s*(\w+)::class\.java/g)) elementTypes.add(m[1]);
    for (const m of code.matchAll(/\.oneAnonymous\s*\(/g)) {
      const inner = parenInner(code, m.index + m[0].length - 1);
      if (inner === null) continue;
      const t = /(\w+)::class\.java/.exec(splitTopLevel(inner)[3] ?? '');
      if (t) typedSites.set(t[1], rel);
    }
  }

  // ---------- ⑦ DTO ↔ schema 字段集合等式（双向） ----------
  //    判全量 DTO（不只调用点上的）：`Store` 这类嵌套模型只在 `element` 里出现，
  //    同样是契约 schema 的手抄体，也必须受判。
  let judged = 0;
  for (const [dto, fields] of dtoFields) {
    const want = schemaProps.get(dto);
    if (want === undefined || want.size === 0) continue; // 非契约命名模型 ⇒ ⑤ 处记 note
    judged += 1;
    const extra = [...fields].filter((f) => !want.has(f));
    const missing = [...want].filter((f) => !fields.has(f));
    if (extra.length) {
      bad.push(`domain/Models.kt 的 ${dto} 有契约没声明的字段：${extra.join(', ')}`
        + '（注解值拼错 / 契约已改名 ⇒ 该字段恒为 null）');
    }
    if (missing.length) {
      bad.push(`${dto} 未接收契约声明的字段：${missing.join(', ')}`
        + '（响应里的这些字段会被静默丢掉）');
    }
  }

  // ---------- ⑧ 反序列化落点必须落在契约声明的模型上（集合等式） ----------
  const declaredAtSites = [...typedSites.keys()].filter((d) => {
    const w = schemaProps.get(d);
    return w !== undefined && w.size > 0;
  });
  const declSet = new Set(declaredAtSites);
  const missingSite = [...genRefSet].filter((s) => !declSet.has(s));
  const extraSite = [...declSet].filter((s) => !genRefSet.has(s));
  if (missingSite.length) {
    bad.push(`契约声明了这些端点的 data 形状，但没有任何调用点按该模型反序列化：`
      + `${missingSite.join(', ')}（响应被当成了 Map / JsonObject ⇒ 字段名不再受判）`);
  }
  if (extraSite.length) {
    bad.push(`调用点按这些模型反序列化，但契约没有任何端点声明该形状：`
      + `${extraSite.join(', ')}（模型与端点的对应关系错了）`);
  }
  for (const [dto, rel] of typedSites) {
    if (!dtoFields.has(dto)) {
      bad.push(`${rel} 反序列化到 ${dto}，但 domain/Models.kt 里没有它的 data class 声明`);
    } else if (!(schemaProps.get(dto)?.size > 0)) {
      notes2.push(`  – ${dto}（${rel}）不在契约 components.schemas 里 —— 该端点的 data 形状未声明，无法机械核对`);
    }
  }
  if (elementTypes.size) {
    notes2.push(`  – 分页 element 类型的**这些用法**不参与核对（其列表端点的 data 形状未声明）；`
      + `模型本身若在 components.schemas 里，仍受 ⑦ 判：${[...elementTypes].join(', ')}`);
  }

  if (bad.length) {
    fail('contract-schema-fields',
      `响应模型与契约 schema 不一致：\n      ${bad.join('\n      ')}\n`
      + '      ⇒ 这条不会让构建失败：漂移的表现是**字段静默为 null**（界面一片空白），'
      + '        而编译 / 构建 / 生成器 --check / 其余判据全绿。'
      + '\n      修法：改 domain/Models.kt 对齐契约（契约是唯一真源）。');
  } else {
    ok('contract-schema-fields',
      `${judged} 个响应 DTO 与契约 components.schemas 的字段集合**双向相等**；`
      + `${genRefSet.size} 个声明了 data 形状的端点与调用点的反序列化模型集合一致`
      + '（生成物 ↔ 契约两侧独立解析后对拍）');
  }
  notes.push(...notes2);
}

// ===========================================================================
// ㉒ 边到边与窗口 inset：targetSdk ≥ 35 时**必须**处理，否则 UI 被系统栏压住
// ===========================================================================
// 🛑 防的是哪一类缺陷（与本仓 R8/Gson 那条**同族**：构建全绿、调试全好、真机才炸）
// ---------------------------------------------------------------------------
// 平台行为变更（apps targeting API 35+）原文：
//   · 「Apps are edge-to-edge by default on devices running Android 15 if the app
//      is targeting Android 15.」
//   · 「If your app is not already edge-to-edge, portions of your app may be
//      obscured and **you must handle insets**.」
//   · 「The top offset is disabled so content draws behind the status bar unless
//      insets are applied.」（导航栏同款表述）
//   · `R.attr#statusBarColor` / `Window#setStatusBarColor` /
//     `Window#setDecorFitsSystemWindows` 都在「deprecated **and disabled**」清单里。
// 失效形态：`assembleDebug` / `assembleRelease` / 本条之外的全部判据**全绿**，
//   调试又常在旧系统镜像上做 ⇒ 只有 API 35/36 真机才暴露。
//
// 🛑 判据形状：一切都**现算**，不硬编码（第 53 条）
// ---------------------------------------------------------------------------
//   ① `targetSdk` 从 app/build.gradle.kts 现算（不写死 35）；
//   ② 「边到边落点文件」= 源码里含 `enableEdgeToEdge(` 的文件（不写死路径/文件名）；
//   ③ 该文件必须同时消费三类 inset：
//        · `systemBars()`   —— 状态栏 + 导航栏；
//        · `displayCutout()` —— 刘海/挖孔（少它 ⇒ 刘海机型顶栏被挖孔切掉）；
//        · `ime()`          —— 输入法（少它 ⇒ 键盘盖住输入框）。
//   ④ 落点里声明的类型名（object/class）必须被 Activity 的 `onCreate` 引用，
//      且该引用**必须早于 `setContentView`**（顺序反了会先按非边到边排一次版再重排）。
//      若直接在 Activity 里内联调用 `enableEdgeToEdge(`，同样接受。
//   ⑤ 既然系统栏颜色已由代码在运行期设定，主题里就**不许再留**那三个已失效的属性 ——
//      留着会造成「主题在管系统栏」的错觉（本仓立场：误导性的死配置比没有更坏）。
//
// ⚠️ 本判据只保证**形态**。真机 / API 35+ 模拟器上的视觉核验是未收口项
//    （见 README 第七节）—— 形态对 ≠ 观感对，这一点必须说清楚。
{
  const bad = [];
  const notes2 = [];

  // ① targetSdk
  const gradleText = readText(APP_GRADLE);
  const tgt = /\btargetSdk\s*=\s*(\d+)/.exec(gradleText);
  if (!tgt) {
    fail('edge-to-edge-insets',
      'app/build.gradle.kts 里找不到 targetSdk —— 本判据失去检查对象（第 53 条）');
  } else {
    const targetSdk = Number(tgt[1]);

    // ② 落点文件（现算）
    const entryFiles = KT_ALL.filter((f) => /enableEdgeToEdge\s*\(/.test(readCode(f)));

    // ③ 三类 inset 是否齐备
    const wired = entryFiles.filter((f) => {
      const c = readCode(f);
      return /setOnApplyWindowInsetsListener\s*\(/.test(c)
        && /Type\.systemBars\s*\(\s*\)/.test(c)
        && /Type\.displayCutout\s*\(\s*\)/.test(c)
        && /Type\.ime\s*\(\s*\)/.test(c);
    });

    // ⑤ 主题里不得再留已失效的系统栏属性（仅当颜色确由代码接管时判 —— 见下）
    const DEAD_ATTRS = [
      'android:statusBarColor',
      'android:navigationBarColor',
      'android:windowLightNavigationBar',
    ];
    if (entryFiles.length) {
      for (const f of walk(join(ROOT, 'app', 'src')).filter((x) => x.endsWith('.xml'))) {
        const raw = stripXmlComments(readFileSync(f, 'utf8'));
        for (const a of DEAD_ATTRS) {
          if (raw.includes(`name="${a}"`)) {
            bad.push(`${relPosix(ROOT, f)} 仍设置 ${a}`
              + '（该属性在 targetSdk ≥ 35 上已被平台禁用，颜色已由代码在运行期设定 ⇒'
              + '留着只会造成「主题在管系统栏」的错觉）');
          }
        }
      }
    } else {
      notes2.push('  – 源码里没有 enableEdgeToEdge( 落点，故未核对主题里的系统栏属性'
        + '（未接管时那些属性仍是有效配置，不该报红）');
    }

    // ④ Activity 的 onCreate 必须接线且顺序正确
    const ns = /namespace\s*=\s*"([^"]+)"/.exec(gradleText)?.[1] ?? '';
    const man = stripXmlComments(readFileSync(MAN_MAIN, 'utf8'));
    const activityNames = [...man.matchAll(/<activity\b[^>]*android:name\s*=\s*"([^"]+)"/g)]
      .map((m) => m[1]);

    const entryTypeNames = new Set();
    for (const f of wired) {
      for (const m of readCode(f).matchAll(/\b(?:object|class)\s+(\w+)/g)) entryTypeNames.add(m[1]);
    }

    const activityFiles = [];
    for (const raw of activityNames) {
      const fq = raw.startsWith('.') ? `${ns}${raw}` : raw;
      const rel = fq.startsWith(`${ns}.`) ? fq.slice(ns.length + 1).replace(/\./g, '/') : fq.replace(/\./g, '/');
      const p = join(SRC, `${rel}.kt`);
      if (!existsSync(p)) {
        bad.push(`manifest 声明的 Activity ${raw} 在源码里找不到（${relPosix(SRC, p)}）—— 无法核对边到边接线`);
        continue;
      }
      activityFiles.push(p);
      const code = readCode(p);
      const obIdx = code.indexOf('fun onCreate(');
      if (obIdx < 0) {
        bad.push(`${relPosix(SRC, p)} 没有 onCreate —— 无法核对边到边启用位置`);
        continue;
      }
      const braceIdx = code.indexOf('{', obIdx);
      const body = braceIdx < 0 ? '' : enclosingBrace(code, braceIdx + 1);
      if (!body) {
        bad.push(`${relPosix(SRC, p)} 的 onCreate 函数体解析失败（无法配平花括号）`);
        continue;
      }

      // 启用调用：内联 enableEdgeToEdge(，或引用落点里声明的类型名
      let callIdx = body.search(/enableEdgeToEdge\s*\(/);
      for (const n of entryTypeNames) {
        const i = new RegExp(`\\b${escapeRe(n)}\\b`).exec(body)?.index ?? -1;
        if (i >= 0 && (callIdx < 0 || i < callIdx)) callIdx = i;
      }
      const scIdx = body.search(/setContentView\s*\(/);

      if (callIdx < 0) {
        bad.push(`${relPosix(SRC, p)} 的 onCreate 里既没有 enableEdgeToEdge(，`
          + `也没有引用任何边到边落点类型（落点候选：${[...entryTypeNames].join(', ') || '无'}）`);
      } else if (scIdx < 0) {
        bad.push(`${relPosix(SRC, p)} 的 onCreate 里没有 setContentView —— 无法核对启用顺序`
          + '（顺序错会先按非边到边排一次版再重排）');
      } else if (callIdx > scIdx) {
        bad.push(`${relPosix(SRC, p)} 的 onCreate 里边到边启用**晚于** setContentView`
          + '（应先启用再设置内容视图）');
      }
    }

    if (targetSdk < 35) {
      notes2.push(`  – targetSdk = ${targetSdk} < 35：边到边尚未被平台强制，`
        + '本判据只登记不报红；升到 35 会自动开始要求落点与接线');
    } else {
      if (!entryFiles.length) {
        bad.push(`targetSdk = ${targetSdk} ⇒ 边到边被平台强制，但源码里没有任何`
          + ' enableEdgeToEdge( 调用：顶栏会被状态栏压住、底部会被导航栏压住，'
          + '而构建与调试全程没有任何告警');
      }
      if (entryFiles.length && !wired.length) {
        bad.push('有 enableEdgeToEdge( 落点，但没有一处同时消费'
          + ' systemBars + displayCutout + ime 三类 inset'
          + '（缺 displayCutout ⇒ 刘海机型顶栏被切；缺 ime ⇒ 键盘遮住输入框）');
      }
    }

    if (bad.length) {
      fail('edge-to-edge-insets',
        `边到边 / 窗口 inset 未接对：\n      ${bad.join('\n      ')}\n`
        + '      ⇒ 这类问题**不会让构建失败**：边到边是运行期窗口行为，'
        + '构建期零告警，且旧机型上复现不出来。'
        + '\n      修法：见 ui/SystemBars.kt 顶部（enableEdgeToEdge + 三类 inset 落到根视图 padding）。');
    } else {
      ok('edge-to-edge-insets',
        `targetSdk = ${targetSdk}；边到边落点 ${entryFiles.length} 处`
        + `（${entryFiles.map((f) => relPosix(SRC, f)).join('、') || '无'}），`
        + `${wired.length} 处同时消费 systemBars / displayCutout / ime；`
        + `${activityFiles.length} 个 Activity 的 onCreate 已在 setContentView 之前接线；`
        + '主题中已无 targetSdk ≥ 35 下失效的系统栏属性。'
        + '⚠️ 本条只保证形态 —— 真机/API 35+ 的视觉核验仍为未收口项（见 README 第七节）');
    }
  }
  notes.push(...notes2);
}

// ===========================================================================
// 输出
// ===========================================================================
console.log('== 端 B · 原生 Android（Kotlin）构建自检 ==');
for (const l of oks) console.log(l);
for (const l of notes) console.log(l);
if (fails.length) {
  console.error('\n失败项:');
  for (const l of fails) console.error(l);
  const notice = probeDebrisNotice(SRC, 'android-check.mjs');
  if (notice) console.error(notice);
  console.error('\nANDROID-CHECK FAILED  (therapist-android)');
  process.exit(1);
}
console.log('\nANDROID OK  (therapist-android)  装载层完整 · 导航三方一致 · 端点全接上 · 协议无字面量 · 枚举与契约同源');

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/**
 * 读文本并**归一化行尾**（CRLF → LF）。
 *
 * 🛑 这不是"顺手做的整洁工作"，它防的是一个**已经踩过的假绿**：
 *    冻结契约 `openapi-v1.0.0.yaml` 是 LF，而裁剪契约 `_cut/*.openapi.yaml`
 *    是 **CRLF**。于是 `^  \/path:$` 这类以 `$` 结尾的正则永远匹配不到
 *    （`$` 前还有 `\r`），分段退化成"整份文件一段"，
 *    契约侧必需参数于是解析成空 —— 而判据**照样绿**（空集 ⊆ 任何集合）。
 *    ⇒ 所有读入一律过这里，让"行尾风格"不再是一个隐含假设。
 */
function readText(p) {
  return readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
}

/** 剥 Kotlin 注释：仅用于"判据不应被注释满足"的场景（第 41/51 条）。 */
function stripKtComments(t) {
  return t
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((l) => l.replace(/(^|[^:"'])\/\/.*$/, '$1'))
    .join('\n');
}

/** 剥 XML 注释（原生端的清单/资源里，注释同样不得满足判据）。 */
function stripXmlComments(t) {
  return t.replace(/<!--[\s\S]*?-->/g, '');
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function relPosix(base, p) {
  return relative(base, p).replace(/\\/g, '/');
}

/** 从 `idx` 起向外扩到**最内层花括号块**（含首尾花括号），返回其文本。
 *  🛑 为什么必须配平而不是取固定字符窗口：窗口太小会把"写了的判成没写"，
 *    窗口太大又会把隔壁函数的实参算进来 —— 两端都错（第 66 条）。 */
function enclosingBrace(text, idx) {
  let depth = 0;
  let open = -1;
  for (let k = idx - 1; k >= 0; k -= 1) {
    const ch = text[k];
    if (ch === ')' || ch === ']' || ch === '}') depth += 1;
    else if (ch === '{') {
      if (depth === 0) { open = k; break; }
      depth -= 1;
    } else if (ch === '(' || ch === '[') {
      if (depth > 0) depth -= 1;
    }
  }
  if (open < 0) return '';
  let d = 0;
  for (let k = open; k < text.length; k += 1) {
    const ch = text[k];
    if (ch === '{' || ch === '(' || ch === '[') d += 1;
    else if (ch === '}' || ch === ')' || ch === ']') {
      d -= 1;
      if (d === 0) return text.slice(open, k + 1);
    }
  }
  return text.slice(open);
}

/** 取从 `openIdx`（须指向一个 `(`）起**配平**的圆括号**内部**文本；不配平返回 null。
 *  🛑 与 enclosingBrace 同一条理由：不用固定字符窗口 —— 窗口太小会把"写了的
 *    判成没写"，太大又会把相邻调用的实参吞进来，两端都错（第 66 条）。
 *  字符串内的括号/逗号不参与层级（`"a,b"` 里的逗号不是实参分隔符）。 */
function parenInner(text, openIdx) {
  if (text[openIdx] !== '(') return null;
  let d = 0;
  let inStr = false;
  for (let k = openIdx; k < text.length; k += 1) {
    const ch = text[k];
    if (ch === '"') { inStr = !inStr; continue; }
    if (inStr) continue;
    if (ch === '(') d += 1;
    else if (ch === ')') {
      d -= 1;
      if (d === 0) return text.slice(openIdx + 1, k);
    }
  }
  return null;
}

/** 在**顶层逗号**处切分实参列表。
 *  🛑 只计 `()` `[]` `{}` 三种括号，**刻意不计 `<>`**：Kotlin 里 `->` 的 `>`
 *    会被误判成"泛型收尾"，从而让 depth 变负、切分全乱。
 *    （本仓第 55 条的教训：判据里的解析器，其形态假设必须与目标语言的
 *      真实写法对上，否则它会在某些写法下安静地算错。） */
function splitTopLevel(text) {
  const args = [];
  let depth = 0;
  let inStr = false;
  let cur = '';
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === '"') inStr = !inStr;
    if (!inStr && (ch === '(' || ch === '[' || ch === '{')) depth += 1;
    if (!inStr && (ch === ')' || ch === ']' || ch === '}')) depth -= 1;
    if (ch === ',' && depth === 0 && !inStr) { args.push(cur); cur = ''; continue; }
    cur += ch;
  }
  args.push(cur);
  return args;
}

function walk(dir) {
  const acc = [];
  if (!existsSync(dir)) return acc;  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    // 🛑 刻意**不**排除探针文件（见 _gate-common.mjs 的说明）：
    //    反向验证靠"门禁能看见造错文件"才能被证明有牙齿。
    if (st.isDirectory()) acc.push(...walk(p));
    else acc.push(p);
  }
  return acc;
}
