/**
 * 端 B · 原生 Android 门禁的反向验证（证明 android-check.mjs 有牙齿）
 * ============================================================================
 *
 * 🛑 为什么必须做这一步（本仓反复登记的教训）
 * ---------------------------------------------------------------------------
 * "门禁全绿"**不是**门禁有效的证据 —— 它也可能是"判据从未真正执行过"
 * （第 41/42/43 条：静默假绿）。唯一能证明门禁承重的做法是：
 * **主动注入一个必须被抓住的错误，断言门禁确实变红**；再断言还原后变绿。
 *
 * 本脚本对本端独有的接缝（装载层 / 源集 / Kotlin 源树）逐条做注入。
 * 每一组都改一处**真实文件**，跑门禁，记录退出码，然后**逐字节还原**并复验绿。
 *
 * 🛑 关于"注入必须带来可观测差异"（第 46 条的教训）
 * ---------------------------------------------------------------------------
 * 一个注入若与"原样"在行为上等价，它报出的红就是**假红**，会误导。
 * 故每组注入都先断言"注入确实改动了文件内容"。
 *
 * 🛑 关于"注入必须打在该判据真正看的地方"
 * ---------------------------------------------------------------------------
 * 本端绝大多数判据都**只扫 Kotlin 源树**（不编译、不装机），
 * 因此注入必须是**源码层面的真实形态**，而不是"改个注释糊弄一下" ——
 * 后者会被判据正确地忽略，从而变成"漏过"的假象。
 * 反过来，每条注入的期望关键词都必须**具名**（`expectGate`），
 * 这样"报红但报的不是这一条"也能被区分出来 —— 那是第 57 条的同型：
 * **红是对的，但你以为是这条判据抓住了它，其实不是。**
 *
 * 用法：node frontends/tools/android-reverse-check.mjs
 * 退出码：0 = 全部按预期（门禁有牙齿且还原干净）· 1 = 有注入未被抓住
 */

import { readFileSync, writeFileSync, existsSync, unlinkSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTENDS = join(HERE, '..');
const ROOT = join(FRONTENDS, 'therapist-android');
const SRC = join(ROOT, 'app', 'src', 'main', 'kotlin', 'com', 'diaoyuanyun', 'therapist');
const NODE = process.execPath;

/**
 * 🛑 必须用**异步** spawn，不能用 execFileSync / spawnSync。
 * ---------------------------------------------------------------------------
 * 本仓已有登记（frontends/README §3「退出码」段）：本环境下**同步**派生子进程
 * 会被拦（`EBUSY`），异步 spawn 正常。若不分辨"跑不动"与"门禁报红"，
 * 会把环境受限误读成结论，然后去改一个本来正确的门禁。
 */
function runGate() {
  return new Promise((resolve) => {
    let out = '';
    let err = '';
    const child = spawn(NODE, [join(HERE, 'android-check.mjs')], { cwd: FRONTENDS });
    child.stdout.on('data', (d) => { out += String(d); });
    child.stderr.on('data', (d) => { err += String(d); });
    child.on('error', () => resolve({ code: 99, out: '', err: '' })); // 起不来
    child.on('close', (code) => resolve({ code: code === null ? 99 : code, out, err }));
  });
}

const GEN = join(SRC, 'contract', 'Endpoints.kt');
const MAN_MAIN = join(ROOT, 'app', 'src', 'main', 'AndroidManifest.xml');
const MAN_DEBUG = join(ROOT, 'app', 'src', 'debug', 'AndroidManifest.xml');
const NSC_DEBUG = join(ROOT, 'app', 'src', 'debug', 'res', 'xml', 'network_security_config.xml');
const MAIN_ACT = join(SRC, 'MainActivity.kt');
const NAV = join(SRC, 'ui', 'Nav.kt');
const ACCESS = join(SRC, 'auth', 'Access.kt');
const API_CLIENT = join(SRC, 'api', 'ApiClient.kt');
const STORE = join(SRC, 'data', 'SessionStore.kt');
const APP_ENV = join(SRC, 'env', 'AppEnv.kt');
const ENUMS = join(SRC, 'domain', 'ContractEnums.kt');
const LABELS = join(SRC, 'ui', 'theme', 'Labels.kt');
const INTAKE_API = join(SRC, 'domain', 'IntakeApi.kt');
const BAND_API = join(SRC, 'domain', 'BandApi.kt');
const BAND_PAGE = join(SRC, 'ui', 'pages', 'BandPage.kt');
const MERIDIAN_PAGE = join(SRC, 'ui', 'pages', 'MeridianActionsPage.kt');
const COLORS = join(ROOT, 'app', 'src', 'main', 'res', 'values', 'colors.xml');
const GRADLE_APP = join(ROOT, 'app', 'build.gradle.kts');
// 🛑 这里**刻意硬编码** `ic_launcher`：注入必须落在具体文件上，天然要知道路径。
//    而判据侧（android-check ⑲）相反 —— 图标名从 manifest 现算。
//    这个不对称是有意的：判据要能跟着改名走，注入脚本不需要。
const ADAPTIVE_ICON = join(ROOT, 'app', 'src', 'main', 'res', 'mipmap-anydpi-v26', 'ic_launcher.xml');
const PROGUARD = join(ROOT, 'app', 'proguard-rules.pro');
const MODELS = join(SRC, 'domain', 'Models.kt');
const SYSTEM_BARS = join(SRC, 'ui', 'SystemBars.kt');
const THEMES_XML = join(ROOT, 'app', 'src', 'main', 'res', 'values', 'themes.xml');

const backup = new Map();
const created = new Set();
function stash(path) {
  if (!backup.has(path)) backup.set(path, readFileSync(path, 'utf8'));
}
function restoreAll() {
  for (const [p, t] of backup) writeFileSync(p, t);
  for (const p of created) {
    if (existsSync(p)) unlinkSync(p);
  }
}

const results = [];

/** 一组注入：改文件 → 断言内容**真的变了** → 跑门禁 → 还原 → 复验绿。全程异步。 */
async function injection({ name, path, from, to, expectExit = 1, expectGate }) {
  const original = readFileSync(path, 'utf8');
  stash(path);
  if (!original.includes(from)) {
    results.push({ name, ok: false, why: `注入锚点未找到：${JSON.stringify(from.slice(0, 70))}` });
    return;
  }
  writeFileSync(path, original.replace(from, to));
  const changed = readFileSync(path, 'utf8');
  if (changed === original) {
    results.push({ name, ok: false, why: '注入没有带来任何内容差异（等价于原样 ⇒ 会是假红）' });
    restoreAll();
    return;
  }
  const red = await runGate();
  restoreAll();
  const back = await runGate();

  const keywordOk = (red.out + red.err).includes(expectGate);
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

/** 新建文件型注入（用于"页面写了但导航进不去"这类形态）。 */
async function injectionNewFile({ name, path, content, expectGate, expectExit = 1 }) {
  if (existsSync(path)) {
    results.push({ name, ok: false, why: `注入目标已存在：${path}` });
    return;
  }
  created.add(path);
  writeFileSync(path, content);
  const red = await runGate();
  restoreAll();
  const back = await runGate();
  const keywordOk = (red.out + red.err).includes(expectGate);
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
// 基线：改动前门禁必须先是绿的（否则后面判红无法归因）
// ---------------------------------------------------------------------------
const baseline = (await runGate()).code;
if (baseline !== 0) {
  console.error(`ABORT: 基线门禁就不是绿的（exit=${baseline}），无法做反向验证。先修好再跑。`);
  process.exit(1);
}
console.log('基线：门禁绿 ✓\n');

// ---------------------------------------------------------------------------
// I1 【role 转录】把 A1 的角色清空 ⇒ 期望 roles 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I1 把生成物里 A1 authLogin 的 grantedRoles 清空',
  path: GEN,
  from: 'id = "authLogin",\n            row = "A1",\n            method = HttpMethod.POST,\n'
    + '            path = "/auth/login",\n            grantedRoles = listOf("therapist", "meridian"),',
  to: 'id = "authLogin",\n            row = "A1",\n            method = HttpMethod.POST,\n'
    + '            path = "/auth/login",\n            grantedRoles = listOf(),',
  expectGate: 'roles',
});

// ---------------------------------------------------------------------------
// I2 【覆盖面】把生成物里一个端点的 id 改名 ⇒ 期望 parse-coverage 报红
//      （判据必须能证明"契约里有的，生成物里也有"）
// ---------------------------------------------------------------------------
await injection({
  name: 'I2 把生成物里 listStores 改名成 listStoresX',
  path: GEN,
  from: 'id = "listStores",',
  to: 'id = "listStoresX",',
  expectGate: 'parse-coverage',
});

// ---------------------------------------------------------------------------
// I3 【第 71 条】把生成物里某个端点的 path 参数抹掉
//      ⇒ 期望 required-args-parsed 报红（契约有 `{id}`，生成物却没有）
// ---------------------------------------------------------------------------
await injection({
  name: 'I3 抹掉生成物里 getRefund 的 requiredPath（契约有 {id}）',
  path: GEN,
  from: 'id = "getRefund",\n            row = "G2",\n            method = HttpMethod.GET,\n'
    + '            path = "/refunds/{id}",\n            grantedRoles = listOf("meridian"),\n'
    + '            requiredQuery = listOf(),\n            requiredPath = listOf("id"),',
  to: 'id = "getRefund",\n            row = "G2",\n            method = HttpMethod.GET,\n'
    + '            path = "/refunds/{id}",\n            grantedRoles = listOf("meridian"),\n'
    + '            requiredQuery = listOf(),\n            requiredPath = listOf(),',
  expectGate: 'required-args-parsed',
});

// ---------------------------------------------------------------------------
// I4 【第 64 条①】把主清单的 LAUNCHER intent-filter 摘掉
//      ⇒ 期望 entry-declared 报红（包能装、图标能点、点了没反应）
// ---------------------------------------------------------------------------
await injection({
  name: 'I4 摘掉主清单的 LAUNCHER intent-filter',
  path: MAN_MAIN,
  from: '                <category android:name="android.intent.category.LAUNCHER" />\n',
  to: '',
  expectGate: 'entry-declared',
});

// ---------------------------------------------------------------------------
// I5 【第 64 条①】让 Application 不再指向启动自检类
//      ⇒ 期望 entry-declared 报红（启动自检与依赖装配不会执行）
// ---------------------------------------------------------------------------
await injection({
  name: 'I5 把 Application 从 .DyTherapistApp 改成别的类',
  path: MAN_MAIN,
  from: 'android:name=".DyTherapistApp"',
  to: 'android:name=".SomeOtherApp"',
  expectGate: 'entry-declared',
});

// ---------------------------------------------------------------------------
// I6 【第 63 条②】从 MainActivity 的 when 里摘掉一个渲染分支
//      ⇒ 期望 nav-tab-render-triangulation 报红（点了页签什么都不发生）
// ---------------------------------------------------------------------------
await injection({
  name: 'I6 从 MainActivity 的 when 里摘掉 Tab.BAND 分支',
  path: MAIN_ACT,
  from: '            Tab.BAND -> BandPage(host)\n',
  to: '',
  expectGate: 'nav-tab-render-triangulation',
});

// ---------------------------------------------------------------------------
// I7 【第 63 条②】新建一个页文件但不在任何 Tab 分支里引用
//      ⇒ 期望 nav-tab-render-triangulation 报红（页面写了但导航进不去）
// ---------------------------------------------------------------------------
await injectionNewFile({
  name: 'I7 新增一个未被导航引用的页文件（写了但进不去）',
  path: join(SRC, 'ui', 'pages', 'OrphanPage.kt'),
  content: 'package com.diaoyuanyun.therapist.ui.pages\n\n'
    + 'import com.diaoyuanyun.therapist.ui.PageHost\n\n'
    + '/** 注入用：这个页面不在任何 Tab 渲染分支里。 */\n'
    + 'class OrphanPage(host: PageHost) : BasePage(host)\n',
  expectGate: 'nav-tab-render-triangulation',
});

// ---------------------------------------------------------------------------
// I8 【准入可查】把某个 Tab 的 requires 改成一个生成物里不存在的端点 id
//      ⇒ 期望 nav-tab-render-triangulation 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I8 把「客户详情」的 requires 改成一个不存在的端点 id',
  path: NAV,
  from: 'CUSTOMER("客户详情", "getCustomer"),',
  to: 'CUSTOMER("客户详情", "getCustomerV2"),',
  expectGate: 'nav-tab-render-triangulation',
});

// ---------------------------------------------------------------------------
// I9 【端点的界面触达】把域层里某个端点的常量值改成一个不存在的 id
//      ⇒ 期望 endpoint-wired 报红（那个端点变成"生成了没人用"）
// ---------------------------------------------------------------------------
await injection({
  name: 'I9 把 IntakeApi 的 B1 常量的值改成一个不存在的端点 id',
  path: INTAKE_API,
  from: 'const val ENDPOINT_SCREENING = "createScreeningRecord"',
  to: 'const val ENDPOINT_SCREENING = "createScreeningRecordX"',
  expectGate: 'endpoint-wired',
});

// ---------------------------------------------------------------------------
// I10 【实测缺陷回归 · 封装与端点一对一】让 B6 又指向 B5 的端点
//      ⇒ 期望 wrapper-own-endpoint 报红
// ---------------------------------------------------------------------------
// 🛑 这一组是**真实缺陷的回归用例**，不是假想：
//    2026-10-08 本门禁首跑即抓出 `patchIntakeProfile()` 用的是
//    `ENDPOINT_INTAKE`（= "getIntakeProfile"）—— 一个 GET 端点被当成 PATCH 用，
//    body 与幂等键发出去而服务端根本不读。
//    它能编译、能构建、`gen-endpoints.py --check` 也绿，**只有真发一次请求才暴露**。
//    故本注入把修复"退回"到缺陷形态，断言门禁仍然抓得住。
await injection({
  name: 'I10 【缺陷回归】把 B6 的端点常量改回 B5 的（两个函数共用一个端点）',
  path: INTAKE_API,
  from: 'const val ENDPOINT_INTAKE_PATCH = "patchIntakeProfile"',
  to: 'const val ENDPOINT_INTAKE_PATCH = "getIntakeProfile"',
  expectGate: 'wrapper-own-endpoint',
});

// ---------------------------------------------------------------------------
// I11 【第二份权威】在准入层写死一份端点 id 清单
//      ⇒ 期望 no-hardcoded-endpoint-list 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I11 在 Access.kt 里硬编码一份「仅经络师」端点清单',
  path: ACCESS,
  from: '    /** 本端接受的全部角色（取自**生成物**，不手写）。 */',
  to: '    private val MERIDIAN_ONLY_HARDCODED = listOf("createRefund", "getRefund", "createRetention")\n\n'
    + '    /** 本端接受的全部角色（取自**生成物**，不手写）。 */',
  expectGate: 'no-hardcoded-endpoint-list',
});

// ---------------------------------------------------------------------------
// I12 【匿名出口唯一性】在域层再开一处匿名出站
//      ⇒ 期望 anonymous-call-single-site 报红
// ---------------------------------------------------------------------------
// ⚠️ 锚点一度写作 `'    companion object {'`（带 4 空格前导），而 BandApi.kt
//    实际的伴生对象是 `    private companion object {` —— 锚点根本不存在，
//    于是这组注入**从未真正落盘**，报的是"注入锚点未找到"而不是"门禁抓住了"（实测）。
//    ⇒ 锚点改为**不含前导空格**的 `'private companion object {'`：
//      缩进不参与匹配 ⇒ 无论类成员缩进怎么变，锚点都还在。
await injection({
  name: 'I12 在 BandApi 里再开一处匿名出站（第二处无准入出口）',
  path: BAND_API,
  from: 'private companion object {',
  to: '    /** 注入：绕过令牌准入的第二处出口。 */\n'
    + '    suspend fun injectedAnonymous(role: String): Unit {\n'
    + '        api.rawAnonymous("getBandTelemetry", role, null)\n'
    + '    }\n\n'
    + '    private companion object {',
  expectGate: 'anonymous-call-single-site',
});

// ---------------------------------------------------------------------------
// I13 【会话键名单点】在别的文件里以字面量出现会话键名
//      ⇒ 期望 session-key-single-source 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I13 在 BandPage 里直接写出会话键名字面量 dy.token',
  path: BAND_PAGE,
  from: 'class BandPage(',
  to: 'private const val INJECTED_TOKEN_KEY = "dy.token"\n\nclass BandPage(',
  expectGate: 'session-key-single-source',
});

// ---------------------------------------------------------------------------
// I14 【第 57 条】把出站层的鉴权头名换成字面量
//      ⇒ 期望 no-protocol-literal 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I14 把 ApiClient 的 Protocol.AUTH_HEADER 换成字面量 "Authorization"',
  path: API_CLIENT,
  from: 'headers[Protocol.AUTH_HEADER] = Protocol.AUTH_SCHEME',
  to: 'headers["Authorization"] = Protocol.AUTH_SCHEME',
  expectGate: 'no-protocol-literal',
});

// ---------------------------------------------------------------------------
// I15 【第 66 条】把信封字段名的**常量引用**退回字面量
//      ⇒ 期望 protocol-fields-wired 报红
// ---------------------------------------------------------------------------
// 🛑 本注入在本轮**被重写过**。原形态是"把 `obj.get("message")` 改成 `obj.get("msg")`"，
//    期望旧的 `envelope-field-literals`（"字面量集合 === ENVELOPE_FIELDS"）报红。
//    2026-10-08 生成器改为产出具名常量、判据升级为 `protocol-fields-wired` 后，
//    原锚点（字面量）**已不存在** ⇒ 注入会以"锚点未找到"失败。
//    ⇒ 这本身是一条经验：**注入脚本与判据是耦合的，判据换形态必须同步改注入**。
await injection({
  name: 'I15 把信封 data 字段从 Protocol.ENVELOPE_FIELD_DATA 退回字面量 "data"',  path: API_CLIENT,
  from: 'val data = obj.get(Protocol.ENVELOPE_FIELD_DATA)',
  to: 'val data = obj.get("data")',
  expectGate: 'protocol-fields-wired',
});

// ---------------------------------------------------------------------------
// I16 【第 56 条】把 Base Path 前缀从出站 URL 里摘掉
//      ⇒ 期望 base-path-wired 报红（全量 404 而构建全绿）
// ---------------------------------------------------------------------------
await injection({
  name: 'I16 把 requestBaseUrl 的前缀摘掉（丢掉契约 Base Path）',
  path: APP_ENV,
  from: 'val requestBaseUrl: String by lazy { gatewayBaseUrl + Contract.API_BASE_PATH }',
  to: 'val requestBaseUrl: String by lazy { gatewayBaseUrl }',
  expectGate: 'base-path-wired',
});

// ---------------------------------------------------------------------------
// I17 【P0 第 3 条】在 Kotlin 里写死一个色值
//      ⇒ 期望 no-color-literal 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I17 在 Tokens.kt 里写死品牌色 #2D5A4A',
  path: join(SRC, 'ui', 'theme', 'Tokens.kt'),
  from: '    @ColorRes val brand = R.color.dy_brand',
  to: '    val INJECTED_HEX = "#2D5A4A"\n\n    @ColorRes val brand = R.color.dy_brand',
  expectGate: 'no-color-literal',
});

// ---------------------------------------------------------------------------
// I18 【P0 第 3 条 · 资源侧】在 colors.xml 之外的 XML 里写死色值
//      ⇒ 期望 no-color-literal 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I18 在 themes.xml 里写死色值（绕过 colors.xml）',
  path: join(ROOT, 'app', 'src', 'main', 'res', 'values', 'themes.xml'),
  from: '<item name="colorPrimary">@color/dy_brand</item>',
  to: '<item name="colorPrimary">#2D5A4A</item>',
  expectGate: 'no-color-literal',
});

// ---------------------------------------------------------------------------
// I19 【明文越界】把明文放行从 debug 源集搬进主清单
//      ⇒ 期望 cleartext-debug-only 报红（正式包可被中间人劫持）
// ---------------------------------------------------------------------------
await injection({
  name: 'I19 在主清单里加 android:usesCleartextTraffic',
  path: MAN_MAIN,
  from: '        android:supportsRtl="true"',
  to: '        android:supportsRtl="true"\n        android:usesCleartextTraffic="true"',
  expectGate: 'cleartext-debug-only',
});

// ---------------------------------------------------------------------------
// I20 【明文越界 · 信任锚】让 debug 配置把用户证书加进信任锚
//      ⇒ 期望 cleartext-debug-only 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I20 让 debug 的网络安全配置信任用户证书',
  path: NSC_DEBUG,
  from: '            <certificates src="system" />',
  to: '            <certificates src="system" />\n            <certificates src="user" />',
  expectGate: 'cleartext-debug-only',
});

// ---------------------------------------------------------------------------
// I21 【枚举逐字】改一个契约具名枚举的取值
//      ⇒ 期望 named-enum-verbatim 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I21 把 ContractEnums 的「睡眠质量」改成「睡眠品质」',
  path: ENUMS,
  from: '        "睡眠质量",',
  to: '        "睡眠品质",',
  expectGate: 'named-enum-verbatim',
});

// ---------------------------------------------------------------------------
// I22 【枚举逐字】把一个 gap_reason 的键名"顺手规范化"
//      ⇒ 期望 enum-labels-verbatim 报红（映射会静默落空）
// ---------------------------------------------------------------------------
await injection({
  name: 'I22 把 Labels 的 not_worn 键名改成 notWorn',
  path: LABELS,
  from: '"not_worn" to Label(',
  to: '"notWorn" to Label(',
  expectGate: 'enum-labels-verbatim',
});

// ---------------------------------------------------------------------------
// I23 【第 71 条 · 实参漏传】摘掉封装层的 path 参数实参
//      ⇒ 期望 required-args-wired 报红（URL 会拼出字面量 {id}）
// ---------------------------------------------------------------------------
await injection({
  name: 'I23 摘掉 B4 getCustomer 封装里的 params（path {id} 漏传）',
  path: INTAKE_API,
  from: '    suspend fun getCustomer(role: String, customerId: String): CustomerDetailData? = api.one(\n'
    + '        ENDPOINT_CUSTOMER_GET, role,\n        params = mapOf("id" to customerId),',
  to: '    suspend fun getCustomer(role: String, customerId: String): CustomerDetailData? = api.one(\n'
    + '        ENDPOINT_CUSTOMER_GET, role,',
  expectGate: 'required-args-wired',
});

// ---------------------------------------------------------------------------
// I24 【第 65 条 · body 实参】摘掉调用点构造的请求体里的必需字段
//      ⇒ 期望 required-args-wired 报红
// ---------------------------------------------------------------------------
await injection({
  name: 'I24 把 G1 调用点请求体里的 customer_id 改名（body 必需字段缺失）',
  path: MERIDIAN_PAGE,
  from: '                    "customer_id" to refundCustomerId.trim(),',
  to: '                    "customer_id__removed" to refundCustomerId.trim(),',
  expectGate: 'required-args-wired',
});

// ---------------------------------------------------------------------------
// I25 【第 58 条】把分页参数名手抄成 map 键
//      ⇒ 期望 no-protocol-literal 报红
// ---------------------------------------------------------------------------
// ⚠️ 锚点同 I12：原写 `'    companion object {'`，BandApi.kt 里是
//    `    private companion object {` ⇒ 未落盘。改为不含前导空格的写法。
await injection({
  name: 'I25 在 BandApi 里手抄分页参数名 "page" to ...',
  path: BAND_API,
  from: 'private companion object {',
  to: '    /** 注入：手抄分页参数名（应改用生成物的 PAGE_FIELD / PAGE_SIZE_FIELD）。 */\n'
    + '    fun injectedBadQuery(): Map<String, String> = mapOf("page" to "1", "page_size" to "20")\n\n'
    + '    private companion object {',
  expectGate: 'no-protocol-literal',
});

// ---------------------------------------------------------------------------
// I26 【加密存储】把写入路径退回明文
//      ⇒ 期望 secure-storage-required 报红
// ---------------------------------------------------------------------------
// 🛑 这是**真实缺陷的回归用例**（不是假想形态）：初版就是把令牌明文写进
//    SharedPreferences（并在注释里"如实登记为待补项"），后来收口为 KeystoreCrypto。
//    本注入把收口**退回**到缺陷形态，断言门禁仍然抓得住 ——
//    否则"已收口"只是纸面承诺：下次有人顺手加个 putString，就又开一个缺口，
//    而门禁不会说话（登记不等于阻止，这是本轮补上这条判据的直接原因）。
await injection({
  name: 'I26 把 saveToken 的写入退回明文（不经 KeystoreCrypto.encrypt）',
  path: STORE,
  from: 'KeystoreCrypto.encrypt(Keys.KEY_ALIAS, token)',
  to: 'token',
  expectGate: 'secure-storage-required',
});

// ---------------------------------------------------------------------------
// I27 【加密存储】再开一条绕过解密的明文读取路径
//      ⇒ 期望 secure-storage-required 报红（getString 次数 ≠ decrypt 次数）
// ---------------------------------------------------------------------------
// 🛑 本组同时是**判据②选的判法是否成立**的验证：若当初写成"同行必须含 decrypt"
//    那种窄判据，这条注入会**漏过**（因为注入的读取是一整行、单独成立）。
//    等数判定才抓得住它。
await injection({
  name: 'I27 在 SessionStore 里再开一条明文读取路径（不经 decrypt）',
  path: STORE,
  from: '    private object Keys {',
  to: '    /** 注入：绕开解密的明文读取路径。 */\n'
    + '    fun injectedPlainToken(): String? = prefs.getString(Keys.TOKEN, null)\n\n'
    + '    private object Keys {',
  expectGate: 'secure-storage-required',
});

// ---------------------------------------------------------------------------
// I28 【release 闸门】把闸门入口整块注释掉
//      ⇒ 期望 release-gate-present 报红
// ---------------------------------------------------------------------------
// 🛑 "注释掉"是最真实的退化形态：重构构建脚本时觉得 whenReady 碍事、
//    想先把那段屏蔽掉看看 —— 而屏蔽之后一切照常（debug 不受影响、
//    也不报错），于是它就永久留在注释里了。
await injection({
  name: 'I28 把 release 闸门入口 gradle.taskGraph.whenReady 注释掉',
  path: GRADLE_APP,
  from: 'gradle.taskGraph.whenReady {',
  to: '// 注入：先把闸门入口屏蔽掉\n// gradle.taskGraph.whenReady {',
  expectGate: 'release-gate-present',
});

// ---------------------------------------------------------------------------
// I29 【release 闸门】闸门还在，但把"抛错"降级成"打印警告"
//      ⇒ 期望 release-gate-present 报红
// ---------------------------------------------------------------------------
// 🛑 这是另一半危险的退化：闸门在场、`whenReady` 也在，但只是打印一句警告
//    然后继续往下走 ⇒ 依然产出 unsigned 包，而**看日志的人以为它拦住了**。
//    故本判据把"抛错动作"单列为一个要素，而不是只检查 whenReady 是否存在。
await injection({
  name: 'I29 把闸门的 throw GradleException 降级为 println（仍产出 unsigned 包）',
  path: GRADLE_APP,
  from: 'throw GradleException(',
  to: 'println(',
  expectGate: 'release-gate-present',
});

// ---------------------------------------------------------------------------
// I30 【启动图标】把 android:icon 从 mipmap 退回 drawable 矢量
//      ⇒ 期望 launcher-icon-complete 报红
// ---------------------------------------------------------------------------
// 🛑 这是**真实缺陷的回归用例**：本轮之前，图标就是一个
//    `res/drawable/ic_launcher.xml` 矢量。它不会让构建失败、不会让门禁变红，
//    只是 API 26+ 显示成带白边方块、API < 26 的部分 launcher 渲染不出。
//    ⇒ 注入把状态退回那个缺陷形态，断言门禁抓得住（否则"换成 mipmap"只是
//    一次性的手工动作，下次有人图省事换回矢量，没有任何东西会说话）。
await injection({
  name: 'I30 把 manifest 的 android:icon 从 @mipmap 退回 @drawable 矢量',
  path: MAN_MAIN,
  from: 'android:icon="@mipmap/ic_launcher"',
  to: 'android:icon="@drawable/ic_launcher"',
  expectGate: 'launcher-icon-complete',
});

// ---------------------------------------------------------------------------
// I31 【启动图标】自适应图标的前景引用一个不存在的 mipmap（悬空引用）
//      ⇒ 期望 launcher-icon-complete 报红
// ---------------------------------------------------------------------------
// 🛑 本组验证的是判据里 `resourceLands()` 那段**真的在工作**。
//    本类缺陷 aapt2 在编译期也会拦下（`resource ... not found`），
//    故本组的价值是"把发现时间从一次完整构建提前到几秒内"，
//    而不是"拦下构建拦不住的坑"—— 如实登记，不夸大（第 55 条）。
await injection({
  name: 'I31 把自适应图标的前景引用改成不存在的 mipmap（悬空）',
  path: ADAPTIVE_ICON,
  from: 'android:drawable="@mipmap/ic_launcher_foreground"',
  to: 'android:drawable="@mipmap/ic_launcher_foregreound"',
  expectGate: 'launcher-icon-complete',
});

// ---------------------------------------------------------------------------
// I32 【启动图标】自适应图标里删掉 <background> 元素
//      ⇒ 期望 launcher-icon-complete 报红
// ---------------------------------------------------------------------------
// 🛑 删掉 background 后 XML **仍然合法**（系统按透明底处理），
//    aapt2 也不报错 —— 于是图标变成"浅色前景浮在任意壁纸上"，
//    在不同 launcher / 深浅色模式下观感完全不同。这类退化同样只能在
//    视觉验收时被偶然发现，故必须由判据钉住"两个元素都在"。
await injection({
  name: 'I32 删掉自适应图标的 <background> 元素（XML 仍合法、aapt2 不报错）',
  path: ADAPTIVE_ICON,
  from: '    <background android:drawable="@color/dy_brand" />\n',
  to: '',
  expectGate: 'launcher-icon-complete',
});

// ---------------------------------------------------------------------------
// I33 【R8 / Gson】把 keep 覆盖的包名写错（domain → model）
//      ⇒ 期望 gson-reflect-surface 报红
// ---------------------------------------------------------------------------
// 🛑 这是**实测过的 P0 缺陷**的回归用例：Gson 按字段名反射，而 R8 会抹掉
//    不带 `@SerializedName` 的裸字段。实测 `LoginData` 的 6 个字段在 DEX 里
//    只剩 4 个（`token` / `role` 消失）⇒ release 包上 A1 登录走不通，
//    而 debug 包完全正常、`assembleRelease` 全绿。
//    本注入把"包覆盖"改错 —— 等价于"新增 DTO 落到了别处"或"改边界时写错包名"，
//    断言判据仍抓得住（否则这条修复只有"这次改对了"，下次照旧会漏）。
await injection({
  name: 'I33 把 Gson keep 规则覆盖的包名写错（domain → model）',
  path: PROGUARD,
  from: '-keepclassmembers class com.diaoyuanyun.therapist.domain.** {',
  to: '-keepclassmembers class com.diaoyuanyun.therapist.model.** {',
  expectGate: 'gson-reflect-surface',
});

// ---------------------------------------------------------------------------
// I34 【R8 / Gson】新增一条 keep 规则，但它指向一个不存在的类
//      ⇒ 期望 gson-reflect-surface 报红（"死规则"检查）
// ---------------------------------------------------------------------------
// 🛑 本组**精确复现修复前的真实状态**：`proguard-rules.pro` 里原本就有
//        -keep class com.diaoyuanyun.therapist.api.ApiEnvelope { *; }
//    注释写着"信封与请求体 DTO 的字段名必须保留"，而 `ApiEnvelope` 全仓不存在
//    （R8 对 keep 不存在的类**静默忽略、不报错**）。
//    ⇒ 那条规则看起来在保护 DTO，实际保护了零个类 —— 而真正会被抹掉的
//    16 个 DTO 一个都不在它的射程内。本仓"写下的检查 ≠ 存在的检查"的教科书案例。
await injection({
  name: 'I34 加一条 keep 规则但指向不存在的类（复现 ApiEnvelope 死规则）',
  path: PROGUARD,
  from: '-keepattributes Signature',
  to: '-keep class com.diaoyuanyun.therapist.api.ApiEnvelope { *; }\n-keepattributes Signature',
  expectGate: 'gson-reflect-surface',
});

// ---------------------------------------------------------------------------
// I35 【第 66 条】把分页列表容器键从 ITEMS_FIELD 退回字面量 "items"
//      ⇒ 期望 protocol-fields-wired 报红
// ---------------------------------------------------------------------------
// 🛑 它复现的缺陷形态：契约把 `pagination.response-fields[0]`（当前 `items`）改名后，
//    手写字面量的出站层不会跟着改 ⇒ `ApiClient.items()` 取不到数组 ⇒
//    **所有列表端点静默返回空列表**（界面上就是"这个列表没有数据"），
//    而 debug 包正常、`assembleDebug` / 全部结构门禁全绿。
await injection({
  name: 'I35 把分页容器键从 Protocol.ITEMS_FIELD 退回字面量 "items"',
  path: API_CLIENT,
  from: 'data.isJsonObject -> data.asJsonObject.get(Protocol.ITEMS_FIELD)',
  to: 'data.isJsonObject -> data.asJsonObject.get("items")',
  expectGate: 'protocol-fields-wired',
});

// ---------------------------------------------------------------------------
// I36 【生成物 ↔ 契约】改掉生成物里具名常量的**值**（模拟生成器转录漂移）
//      ⇒ 期望 protocol-fields-wired 报红
// ---------------------------------------------------------------------------
// 🛑 关键：这条证明"生成物常量值必须与契约一致"是**真的在判**，
//    而不是只判"常量存在"。若只判存在，生成器漏改 / 手改生成物都会静默通过。
await injection({
  name: 'I36 把生成物 ENVELOPE_FIELD_DATA 的值从 "data" 改成 "payload"',
  path: GEN,
  from: '    const val ENVELOPE_FIELD_DATA: String = "data"',
  to: '    const val ENVELOPE_FIELD_DATA: String = "payload"',
  expectGate: 'protocol-fields-wired',
});

// ---------------------------------------------------------------------------
// I37 【第 66 条】把 DTO 的 @SerializedName 抄错（响应字段名漂移）
//      ⇒ 期望 contract-schema-fields 报红
// ---------------------------------------------------------------------------
// 🛑 这是**最真实**的一类手写漂移：`@SerializedName("screening_id")` 抄成
//    `"screeningId"` —— Gson 按注解值找键，找不到就留 null。
//    表现是"这个字段界面上永远是空的"，而 Kotlin 编译通过、构建通过、
//    生成器 `--check` 通过（DTO 不在生成物里）、其余判据全绿。
await injection({
  name: 'I37 把 ScreeningData.screeningId 的 @SerializedName 抄成 "screeningId"',
  path: MODELS,
  from: '@SerializedName("screening_id") val screeningId: String',
  to: '@SerializedName("screeningId") val screeningId: String',
  expectGate: 'contract-schema-fields',
});

// ---------------------------------------------------------------------------
// I38 【第 66 条】让 DTO 少接收一个契约声明的字段
//      ⇒ 期望 contract-schema-fields 报红（"契约 ⊆ DTO"那一半）
// ---------------------------------------------------------------------------
// 🛑 只判"DTO 的字段都在契约里"会**放过这一种**：契约新增/已有字段而 DTO 没接收，
//    响应里那个字段被静默丢掉。本注入专门证明判据的**双向**是真的。
await injection({
  name: 'I38 从 LoginData 里删掉 expires_in 字段（契约有、DTO 不接收）',
  path: MODELS,
  from: '    @SerializedName("expires_in") val expiresIn: Int? = null,\n',
  to: '',
  expectGate: 'contract-schema-fields',
});

// ---------------------------------------------------------------------------
// I39 【第 66 条】把生成物的 dataSchema 指向**另一个**存在的模型
//      ⇒ 期望 contract-schema-fields 报红
// ---------------------------------------------------------------------------
// 🛑 它证明"端点 ↔ 模型"的对应关系在判，而不只是"这些模型都存在"：
//    `Store` 是契约里真实存在的 schema，把它填进 A1 的 dataSchema 不会让
//    "模型是否存在"那一类检查报红 —— 只有**集合等式**能抓（缺 LoginData、多 Store）。
await injection({
  name: 'I39 把 authLogin 的 dataSchema 从 LoginData 改成 Store',
  path: GEN,
  from: '            dataSchema = "LoginData",',
  to: '            dataSchema = "Store",',
  expectGate: 'contract-schema-fields',
});

// ---------------------------------------------------------------------------
// I40–I43 【边到边 / 窗口 inset】targetSdk 36 强制边到边，处理不当**只在真机炸**
// ---------------------------------------------------------------------------
// 🛑 这四组各自对应一种**构建全绿 · 调试全好 · 真机才暴露**的退化形态：
//    I40 少一行接线、I41 顺序挪反、I42 少消费一类 inset、I43 塞回已失效的主题属性。
//    注意 I42 只改**代码行**：SystemBars.kt 顶部注释里仍写着 `Type#ime()` ——
//    判据读的是剥注释后的文本，故不会被注释满足（第 52 条）。
await injection({
  name: 'I40 删掉 MainActivity.onCreate 里的边到边启用调用',
  path: MAIN_ACT,
  from: '        SystemBars.setUp(this, root)\n',
  to: '',
  expectGate: 'edge-to-edge-insets',
});

await injection({
  name: 'I41 把边到边启用挪到 setContentView **之后**（顺序反了）',
  path: MAIN_ACT,
  from: '        SystemBars.setUp(this, root)\n        setContentView(root)',
  to: '        setContentView(root)\n        SystemBars.setUp(this, root)',
  expectGate: 'edge-to-edge-insets',
});

await injection({
  name: 'I42 让 inset 监听不再消费输入法（Type.ime 退回 Type.systemBars）',
  path: SYSTEM_BARS,
  from: 'val ime = insets.getInsets(WindowInsetsCompat.Type.ime())',
  to: 'val ime = insets.getInsets(WindowInsetsCompat.Type.systemBars())',
  expectGate: 'edge-to-edge-insets',
});

await injection({
  name: 'I43 往主题里塞回 targetSdk ≥ 35 下已失效的 android:statusBarColor',
  path: THEMES_XML,
  from: '    <style name="Theme.DyTherapist" parent="Theme.Material3.Light.NoActionBar">',
  to: '    <style name="Theme.DyTherapist" parent="Theme.Material3.Light.NoActionBar">\n'
    + '        <item name="android:statusBarColor">@color/dy_brand_dark</item>',
  expectGate: 'edge-to-edge-insets',
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
console.log('\nANDROID 反向验证 PASS —— 门禁确实有牙齿，且还原干净。');
