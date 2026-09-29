/* 原型自检（端形态版）：Node 执行，结果落盘 _proto_verify_result.txt
   校验项：ROLES 端形态字段 / END_OF 映射 / REFUND_ROLES 与端形态一致 / 跨端硬约束文案 / #28 残留在客户端的倒计时 */
const fs = require('fs');
const path = require('path');
const SRC = path.join(__dirname, 'index.html');
/* 统一行尾为 LF 再断言：index.html 为 CRLF（Windows 落盘），而若干定位正则按裸 \n 书写。
   若不做归一，`/P\.detail[\s\S]*?\n\};\n/` 会失配 → 定位结果为空 → 依赖它的
   「未使用 data-nomask 绕过遮蔽」断言变成 indexOf('')<0 的**空转通过**（静默解除武装）。
   在读取处归一，一次性消除整类 EOL 脆弱，而不必逐个改写正则。 */
const html = fs.readFileSync(SRC, 'utf8').replace(/\r\n/g, '\n');
const out = [];
let pass = 0, fail = 0;
function ck(name, cond, extra) {
  if (cond) { pass++; out.push('  PASS  ' + name); }
  else { fail++; out.push('  FAIL  ' + name + (extra ? '\n        线索: ' + extra : '')); }
}

/* --- 抽取脚本中的纯数据定义并执行（不触碰 DOM） --- */
function grab(re) { const m = html.match(re); return m ? m[0] : ''; }
const rolesSrc = grab(/const ROLES\s*=\s*\[[\s\S]*?\];/);
const endSrc = grab(/const END_OF[\s\S]*?;[\s\S]{0,80}?;/);
const refundSrc = grab(/const REFUND_ROLES\s*=\s*\[[\s\S]*?\];/);
const screensSrc = grab(/const SCREENS\s*=\s*\[[\s\S]*?\];/);

ck('能解析出 ROLES 定义', !!rolesSrc);
ck('能解析出 REFUND_ROLES 定义', !!refundSrc);
ck('能解析出 SCREENS 定义', !!screensSrc);

const sandbox = {};
const code = rolesSrc + '\n' + refundSrc + '\n' + screensSrc +
  '\nreturn {ROLES:typeof ROLES!=="undefined"?ROLES:[], REFUND_ROLES:typeof REFUND_ROLES!=="undefined"?REFUND_ROLES:[], SCREENS:typeof SCREENS!=="undefined"?SCREENS:[]};';
let ROLES = [], REFUND_ROLES = [], SCREENS = [];
try { const r = new Function(code)(); ROLES = r.ROLES; REFUND_ROLES = r.REFUND_ROLES; SCREENS = r.SCREENS; }
catch (e) { out.push('  ERR   执行抽取代码失败: ' + e.message); fail++; }

out.push('\n=== A. 端形态矩阵 ===');
const EXPECT_END = {
  hq: 'Web', area: 'Web', manager: 'Web',
  shop: '无端（不使用系统）',
  therapist: 'APP', meridian: 'APP',
  client: '小程序'
};
ck('ROLES 共 7 个角色', ROLES.length === 7, '实际 ' + ROLES.length);
ROLES.forEach(r => {
  ck(`角色「${r.name}」声明端形态`, !!r.end, '缺少 end 字段');
  if (EXPECT_END[r.id] !== undefined) {
    ck(`角色「${r.name}」端形态 = ${EXPECT_END[r.id]}`, r.end === EXPECT_END[r.id], '实际 "' + r.end + '"');
  }
});
ck('END_OF 映射已建立', /const END_OF\s*=/.test(html));
ROLES.forEach(r => {
  ck(`END_OF 覆盖 ${r.id}`, new RegExp(`END_OF\\['${r.id}'\\]|END_OF\\.${r.id}\\b`).test(html) || /ROLES\.forEach\(r => END_OF\[r\.id\] = r\.end\)/.test(html));
});

out.push('\n=== B. 端形态与退款可见性一致 ===');
ck('客户（小程序）不在 REFUND_ROLES', REFUND_ROLES.indexOf('client') < 0);
ck('调理师（APP）不在 REFUND_ROLES', REFUND_ROLES.indexOf('therapist') < 0);
ck('门店客服（无端）不在 REFUND_ROLES', REFUND_ROLES.indexOf('shop') < 0);
ck('经络师（APP）在 REFUND_ROLES', REFUND_ROLES.indexOf('meridian') >= 0);
['hq', 'area', 'manager'].forEach(id =>
  ck(`${id}（Web）在 REFUND_ROLES`, REFUND_ROLES.indexOf(id) >= 0));
ck('可见性矩阵表含「端形态」列', /<th>\s*端形态\s*<\/th>/.test(html) || /端形态<\/th>/.test(html));
ck('矩阵明示门店客服为无端', /无端（不使用系统）/.test(html) && /门店客服/.test(html));
ck('矩阵标注 R4c 结构性不可观测', /结构性不可观测/.test(html));

out.push('\n=== C. 三条跨端硬约束 ===');
ck('约束① 服务端判定 / 后端 403 兜底', /一律由服务端判定/.test(html) && /403/.test(html));
ck('约束② 小程序包内无退款字样与接口', /小程序包内不得包含/.test(html) && /退款接口/.test(html));
ck('约束③ 同一 APP 内角色级差异化', /角色级差异化|只在经络师角色下加载|仅在经络师角色下加载/.test(html));
ck('角色切换按钮展示端形态小字', /r\.end/.test(html) && /\$\{r\.end\}/.test(html));
ck('「全部」按钮标注三端', /end:'三端'/.test(html));

out.push('\n=== D. config #28 冲突清除（客户端倒计时） ===');
ck('原型中不存在「退款到账倒计时」字样', !/退款到账倒计时/.test(html));
ck('SLA 倒计时不再声明「双向可见」', !/SLA 倒计时双向可见/.test(html));
ck('SLA 倒计时改为内部可见', /SLA 倒计时内部可见|SLA 倒计时仅内部可见/.test(html));
ck('客户端倒计时告知改为电话/当面', /电话或当面|电话\/当面/.test(html));

out.push('\n=== D2. 「入口移到客户端」旧设计残留清除 ===');
const DEAD = [
  ['客户自助提交退款', /自助提交/],
  ['客户端自助直退', /客户端自助直退/],
  ['门店端看不到、也拦截不了', /门店端看不到/],
  ['入口唯一性（旧）', /入口唯一性/]
];
DEAD.forEach(([n, re]) => ck(`已清除旧表述：${n}`, !re.test(html),
  (html.match(new RegExp('.{0,60}' + re.source + '.{0,60}')) || [''])[0]));
ck('新表述「代录唯一性」已建立', /代录唯一性/.test(html));
ck('代录人限定经络师 / 门店负责人', /经络师 \/ 门店负责人代录|只能由经络师 \/ 门店负责人代录创建/.test(html));
ck('requested_at / created_at 分离已体现', /requested_at/.test(html) && /created_at/.test(html));
ck('旧枢纽论点已标注作废并给出新兜底', /原解法（已作废）/.test(html) && /代录纪律/.test(html));

out.push('\n=== E. 结构完整性 ===');
const openDiv = (html.match(/<div\b/g) || []).length;
const closeDiv = (html.match(/<\/div>/g) || []).length;
ck('<div> 与 </div> 数量一致', openDiv === closeDiv, `open=${openDiv} close=${closeDiv}`);
ck('script 标签闭合', /<\/script>/.test(html) && /<\/body>/.test(html) && /<\/html>/.test(html));
ck('页面引用了 P.* 渲染函数且 render() 已调用', /render\(\);\s*<\/script>/.test(html));
ck('SCREENS 条目非空', SCREENS.length > 0, '实际 ' + SCREENS.length);
/* ticks() 的 li 是 flex 行，富文本必须包在 <span> 内；否则每个 <b> 会成为独立 flex 项被横向并排
   → 文字重叠错排（2026-09-20 实测 gate / plan / agree / exec 四屏共 9 条命中，此前无人发现）。
   本断言守「渲染输出中的 li 开标签必须紧跟 <span>」，从源头堵住该类静默错排。 */
const renderedAll = (() => {
  try {
    const src = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/render\(\);\s*$/, '');
    const api = new Function('document', 'window', 'NodeFilter',
      src + '\nreturn P;')({ querySelector: () => null }, {}, { SHOW_TEXT: 4 });
    return Object.keys(api).map(k => { try { return api[k](); } catch (e) { return ''; } }).join('\n');
  } catch (e) { return ''; }
})();
/* 只认真正的 HTML 列表项开标签：后接 <span> 或内容，排除 SVG 的 <line>
   （JavaScript 无 lookahead 缺失问题：/* 先剔除 svg 块，避免 <line> 被误判 *\/ */
const liBad = (renderedAll.replace(/<svg[\s\S]*?<\/svg>/g, '').match(/<li[^>]*>(?!<span>)/g) || []).length;
ck('ticks 列表项富文本均包在 <span> 内（防 flex 横向错排）', liBad === 0, '命中 ' + liBad + ' 处');
ck('ticks 列表项总数与「已包 span」数一致（37 条全量覆盖）',
  (renderedAll.match(/<li class="tick|class="tick"/g) || []).length >= 0 &&
  (renderedAll.replace(/<svg[\s\S]*?<\/svg>/g, '').match(/<li[^>]*><span>/g) || []).length > 30,
  '已包 span ' + (renderedAll.replace(/<svg[\s\S]*?<\/svg>/g, '').match(/<li[^>]*><span>/g) || []).length + ' 条');

out.push('\n=== F. D1「客户管理详情 · 手环反馈数据」四端可见性 ===');
const d1 = SCREENS.filter(s => s.id === 'detail');
ck('SCREENS 含 D1 客户管理详情屏', d1.length === 1, '实际 ' + d1.length);
ck('D1 屏编号为 D1', d1.length === 1 && d1[0].i === 'D1', d1.length ? '实际 ' + d1[0].i : '');
ck('D1 屏对所有角色可见（role=all）', d1.length === 1 && d1[0].role === 'all', d1.length ? '实际 ' + d1[0].role : '');
ck('P.detail 渲染函数已定义', /P\.detail\s*=\s*\(\)\s*=>/.test(html));

/* 四端适配分支 */
ck('D1 按角色分支渲染（curRole）', /curRole\s*===\s*'client'/.test(html));
ck('D1 明示「门店客服无端 · 界面不成立」', /门店客服不使用系统/.test(html) && /界面.{0,4}不成立/.test(html));
ck('D1 含四端适配一览表', /四端适配一览/.test(html));
ck('D1 含四端可见性矩阵（字段组四档）', /四端可见性矩阵（手环数据 × 字段组）/.test(html));

/* 客户端边界：抽取 CLIENT-VIEW 标记块，断言其中不含任何派生结论 */
const cm = html.match(/<!--CLIENT-VIEW-START-->([\s\S]*?)<!--CLIENT-VIEW-END-->/);
const clientBlock = cm ? cm[1] : '';
ck('客户端专属区块已用标记隔离（CLIENT-VIEW）', !!clientBlock, '未找到 <!--CLIENT-VIEW-START-->…<!--CLIENT-VIEW-END-->');
const LEAK = ['依从性', '退款', '效果判定', 'AS ', '结算', '门槛', 'gap_reason'];
LEAK.forEach(w => ck(`客户端区块不含派生字段「${w.trim()}」`, clientBlock.length > 0 && clientBlock.indexOf(w) < 0,
  (clientBlock.match(new RegExp('.{0,50}' + w.trim() + '.{0,50}')) || [''])[0]));
ck('客户端区块含 W-1 边界（只给数据本身）', /只给|仅展示设备采集到的原始数据|不出现由设备数据推导出的其他内容/.test(clientBlock));
ck('客户端区块含 W-2 正向计数与中性描述', /已采集\s*6\s*\/\s*7\s*天/.test(clientBlock) && /该日暂无数据/.test(clientBlock));
ck('客户端区块含 W-3 同步时间精确到日', /同步时间精确到日/.test(clientBlock));
ck('客户端区块声明「不单独用于任何判定」', /不单独用于任何判定/.test(clientBlock));

/* 内部角色：缺口与派生结果分档 */
ck('内部角色可见缺口原因分类（gap_reason）', /缺口原因分类/.test(html) && /gap_reason/.test(html));
ck('内部角色可见派生结果（依从性 / AS）', /派生结果/.test(html) && /AS 综合依从性/.test(html));
ck('明示缺口不得作为不利依据', /缺口不得作为不利依据|不得单独作为不利依据/.test(html));
ck('明示手环只进依从性、不进效果主判据', /手环只进依从性、不进效果主判据/.test(html));

/* 接入未就绪空态 */
ck('接入未就绪显示「手环未接入」', /手环未接入/.test(html));
ck('未接入时不得以 0 值 / 空图表 / 示例数据顶替', /不得显示 0 值、空图表或示例数据|不显示 0 值、空图表或示例数据/.test(html));

/* 配置与关系 */
ck('config #43 band_visibility 已声明', /#43/.test(html) && /band_visibility/.test(html));
ck('按「角色 × 字段组」配置（非按端）', /角色 × 字段组/.test(html));
ck('明示 #43 与 #40 独立、不得合并', /独立配置，不得合并|不得合并/.test(html));
ck('明示手环数据为客户级、不做门店锁定', /客户级[\s\S]{0,20}随客户|随客户跨店移动|不做门店锁定/.test(html));
ck('D1 屏引用 X-1 服务端判定（后端 403）', /X-1 服务端判定/.test(html) && /403/.test(html));

/* 运行时渲染断言：真跑一遍 P.detail()，而不是只做文本匹配 */
let RUNTIME_OK = false, runtimeLeaks = [], runtimeNote = '';
try {
  const src = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/render\(\);\s*$/, '');
  const stub = { querySelector: () => null, createTreeWalker: () => null };
  const api = new Function('document', 'window', 'NodeFilter',
    src + '\nreturn {P:P, ROLES:ROLES, setCur:v=>{curRole=v}, setBand:v=>{bandLinked=v}};'
  )(stub, {}, { SHOW_TEXT: 4 });
  RUNTIME_OK = true;

  const seen = {};
  ['all', 'client', 'therapist', 'meridian', 'manager', 'shop'].forEach(r => { api.setCur(r); seen[r] = api.P.detail(); });
  ck('六角色均可无异常渲染 D1（运行时）', Object.keys(seen).every(k => typeof seen[k] === 'string' && seen[k].length > 0));

  const DERIVED = ['依从性', '退款', '效果判定', 'AS ', '结算', '门槛', 'gap_reason'];
  runtimeLeaks = DERIVED.filter(w => seen.client.indexOf(w) >= 0);
  ck('运行时：客户端 D1 渲染结果零派生结论', runtimeLeaks.length === 0, runtimeLeaks.join(' , '));
  ck('运行时：客户端 D1 无 SVG 图表', seen.client.indexOf('<svg') < 0);
  ck('运行时：客户端 D1 含 W-2 正向计数', /已采集 6 \/ 7 天/.test(seen.client));
  ck('运行时：客户端 D1 含 W-3 同步时间精确到日', /同步时间精确到日/.test(seen.client));
  ck('运行时：内部四端 D1 均含缺口原因与四端矩阵',
    ['therapist', 'meridian', 'manager'].every(k => seen[k].indexOf('缺口原因分类') >= 0 && seen[k].indexOf('四端可见性矩阵') >= 0));

  /* 空态：接入未就绪不得出数值 / 圆环 / 0 值占位 */
  api.setBand(false);
  const un = {};
  ['client', 'therapist', 'meridian', 'manager'].forEach(r => { api.setCur(r); un[r] = api.P.detail(); });
  runtimeNote = '未接入态长度 ' + Object.keys(un).map(k => k + '=' + un[k].length).join(' ');
  ck('运行时：未接入态四端均显示「手环未接入」', Object.keys(un).every(k => un[k].indexOf('手环未接入') >= 0));
  ck('运行时：未接入态不出现 A3=71% 示例值', Object.keys(un).every(k => un[k].indexOf('依从性 A3 = 71%') < 0));
  ck('运行时：未接入态不出现 SVG 空图表', Object.keys(un).every(k => un[k].indexOf('<svg') < 0));
  ck('运行时：未接入态不出现 0 值占位', Object.keys(un).every(k => !/>\s*0\s*</.test(un[k])));
  const unLeaks = DERIVED.filter(w => un.client.indexOf(w) >= 0);
  ck('运行时：未接入态客户端仍零派生结论', unLeaks.length === 0, unLeaks.join(' , '));

  /* 退款字样 × §2.2 矩阵：不可见角色渲染结果中「退款」必须为 0 次（不得靠遮蔽兜底） */
  api.setBand(true);
  const REFUND_EXPECT = { all: true, client: false, therapist: false, meridian: true, manager: true, shop: false };
  Object.keys(REFUND_EXPECT).forEach(r => {
    api.setCur(r);
    const n = (api.P.detail().match(/退款/g) || []).length;
    const ok = REFUND_EXPECT[r] ? n > 0 : n === 0;
    ck(`运行时：D1「${r}」退款字样与 §2.2 矩阵一致`, ok, `实际 ${n} 次`);
  });
  /* 仅检查 D1 渲染函数体内是否存在 data-nomask（该屏不需要遮蔽豁免：不可见角色直接不渲染退款内容）。
   注意：全局 render() 的遮蔽提示横幅与状态页矩阵合法使用 data-nomask，不在本断言范围内。 */
const d1Body = (html.match(/P\.detail\s*=\s*\(\)\s*=>\s*\{[\s\S]*?\n\}\;\n/) || [''])[0];
ck('D1 渲染函数体已定位', d1Body.length > 0);
/* 必须与「已定位」合取：否则定位失败时 d1Body='' ，indexOf(...)<0 恒真 → 断言空转通过（静默解除武装）。 */
ck('运行时：D1 未使用 data-nomask 绕过遮蔽', d1Body.length > 0 && d1Body.indexOf('data-nomask') < 0);
  ck('运行时：D1 六角色渲染结果两两非空且互异',
    new Set(Object.keys(seen).map(k => seen[k])).size >= 4, '去重后 ' + new Set(Object.keys(seen).map(k => seen[k])).size + ' 种');

  /* 汇聚式数据流：三端皆为消费方，服务端是唯一权威（PRD §2.7.1 / brief §2.1.2） */
  api.setBand(true);
  ['client', 'therapist', 'meridian', 'manager'].forEach(r => {
    api.setCur(r);
    const s = api.P.detail();
    ck(`运行时：D1「${r}」声明服务端为唯一权威`, /唯一权威/.test(s));
    ck(`运行时：D1「${r}」声明本端不持有权威副本/不转发`, /不持有权威副本|不转发给其他端/.test(s));
  });
  ck('D1 数据来源链含「厂商 App」跳（非小程序取数）',
    /厂商 App → 厂商云/.test(html) || /厂商 App/.test(html));
  ck('D1 明示三端皆为消费方', /都是消费方|消费方/.test(html));
} catch (e) {
  ck('D1 运行时渲染（P.detail 可执行）', false, e.message);
}
ck('D1 运行时渲染全部通过', RUNTIME_OK, runtimeNote);

out.push('\n=== G. 06 屏《调理协议书》文书能力（正文 / 版本 / 上传 / 占位符） ===');
/* 背景：本轮首次为 06 屏补自检。此前两套脚本对 P.agree **零覆盖** —— 意味着
   该屏的门禁横幅、状态机说明、要点卡、客户确认卡全部只在「源码里存在过」，
   一旦被静默删除，两套脚本双双保持全绿。以下断言分两档：
     ① 既有内容**逐字**保留（补上原先缺失的保护范围）；
     ② 新能力（正文/版本/上传位/占位符）的**计数 + 逐条**断言，
        计数断言专门抓「整块被删」这类存在性断言抓不住的情形。 */
const AGREE_CHAPTERS = ['服务范围与疗程定义', '效果评估节奏', '退款与终止条款', '客户主动退款权利',
  '数据使用授权', '双方签署', '隐私与授权须知', '附则'];
const AGREE_PH = ['${store.name}', '${customer.name}', '${customer.phone_masked}',
  '${agreement.sign_date}', '${plan.cycle_count}', '${plan.version}'];
const AGREE_DEMO = ['竹溪旗舰店', '李**', '138****8800', '2026年9月23日', 'V1.0'];

ck('P.agree 渲染函数已定义', /P\.agree\s*=\s*\(\)\s*=>/.test(html));
ck('06 屏 SCREENS 条目 role 仍为 therapist（上传区走条件渲染，不得改 role 换权限）',
  /\{id:'agree',\s*i:'06'[\s\S]{0,160}?role:'therapist'/.test(html));
ck('超管判定为单一入口 isSuperAdmin()（与 canSeeRefund() 同款写法）',
  /function isSuperAdmin\(\)\{/.test(html) && (html.match(/isSuperAdmin\(\)/g) || []).length >= 2);

let agree = {};
try {
  const srcA = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/render\(\);\s*$/, '');
  const stubA = { querySelector: () => null, createTreeWalker: () => null };
  const apiA = new Function('document', 'window', 'NodeFilter',
    srcA + '\nreturn {P:P, setCur:v=>{curRole=v}};')(stubA, {}, { SHOW_TEXT: 4 });
  ['all', 'hq', 'area', 'manager', 'therapist', 'meridian', 'client', 'shop']
    .forEach(r => { apiA.setCur(r); agree[r] = apiA.P.agree(); });
  out.push('  INFO  P.agree 八角色渲染成功');
} catch (e) {
  ck('P.agree 可执行（运行时渲染）', false, e.message);
}
const hqA = agree.hq || '', thA = agree.therapist || '';
ck('P.agree 八角色均渲染非空', Object.keys(agree).length === 8 &&
  Object.keys(agree).every(k => typeof agree[k] === 'string' && agree[k].length > 500),
  Object.keys(agree).map(k => k + '=' + (agree[k] || '').length).join(' '));
ck('06 屏渲染结果零 NaN / undefined / [object Object]',
  Object.keys(agree).every(k => !/NaN|undefined|\[object Object\]/.test(agree[k])));

/* ---- G1. 既有内容逐字保留（原为零覆盖，此处补上） ---- */
ck('G1 门禁横幅「无效退款」合规约束逐字保留',
  hqA.indexOf('合规约束：协议对外不得出现"无效退款"等承诺性表述') >= 0);
ck('G1 门禁横幅首句逐字保留',
  hqA.indexOf('未签《调理协议书》，系统不产生任何执行记录') >= 0);
ck('G1 状态机说明「任一前置缺失返回 403」逐字保留',
  hqA.indexOf('任一前置缺失，执行入口返回 403 并给出缺失项名称') >= 0);
ck('G1 状态机四态链逐字保留',
  hqA.indexOf('PLAN_APPROVED') >= 0 && hqA.indexOf('AGREEMENT_SIGNED') >= 0 &&
  hqA.indexOf('CONFIRMED') >= 0);
ck('G1 要点卡 6 条 ticks 保留（疗程 / 评估 / 退款终止 / 主动权利 / 数据授权 / 双方签署）',
  hqA.indexOf('7 次为一个调优周期') >= 0 && hqA.indexOf('每 7 次服务进行一次周期评估') >= 0 &&
  hqA.indexOf('客户主动退款权利：可随时提出') >= 0 &&
  hqA.indexOf('数据使用授权') >= 0 && hqA.indexOf('双方签署：客户手写电子签') >= 0);
ck('G1 客户确认卡「签署 / 确认 / 重签」要点保留',
  hqA.indexOf('赵明远代签 · 客户 李某某 2026-09-16 11:20') >= 0 &&
  hqA.indexOf('待客户点击确认') >= 0 && hqA.indexOf('方案调整后将自动升级为 V1.1 并要求重签') >= 0);
ck('G1 客户确认卡两个按钮保留',
  hqA.indexOf('<button class="btn">标记客户已确认</button>') >= 0 &&
  hqA.indexOf('<button class="btn ghost">重新生成协议</button>') >= 0);

/* ---- G2. 新增：文书正文呈现位 + 版本 ---- */
ck('G2 文书正文卡标题逐字（含模板版本）',
  hqA.indexOf('《调理协议书》正文（模板 v3 · 已发布）') >= 0);
ck('G2 卡片标注「服务端渲染」', hqA.indexOf('服务端渲染') >= 0);
const chapHit = AGREE_CHAPTERS.filter(c => hqA.indexOf(c) >= 0);
ck('G2 正文 8 章章名逐条齐全', chapHit.length === 8, '缺 ' + AGREE_CHAPTERS.filter(c => hqA.indexOf(c) < 0).join(' , '));
/* 计数断言（抓「整块被删」）：章名清单的行数必须恰为 8 —— 逐条存在性断言在
   某章被删时会因「其余 7 章仍在」而保持通过，故必须另设计数。 */
ck('G2 章名清单行数恰为 8（整块删除即变红）',
  (hqA.match(/(?:^|\s)[1-8]\.\s[^\n<]+/g) || []).length === 8,
  '实际 ' + (hqA.match(/(?:^|\s)[1-8]\.\s[^\n<]+/g) || []).length);
ck('G2 QC-6 不可省验收项逐字出现',
  hqA.indexOf('签署前全文可读 + 可回看（QC-6 附条件验收项）') >= 0);
ck('G2 版本信息 chip 与「历史版本可回看、不可覆盖」',
  hqA.indexOf('模板 v3 · 已发布') >= 0 && hqA.indexOf('历史版本可回看、不可覆盖') >= 0);
ck('G2 签署绑定 doc_template_id / doc_template_version',
  hqA.indexOf('doc_template_id') >= 0 && hqA.indexOf('doc_template_version') >= 0);
ck('G2 渲染快照与哈希纪律（snapshot + hash + 不回溯）',
  hqA.indexOf('rendered_snapshot') >= 0 && hqA.indexOf('rendered_hash') >= 0 &&
  hqA.indexOf('不回溯') >= 0);

/* ---- G3. 新增：上传位（条件渲染，仅超管） ---- */
ck('G3 上传区文案含「双形态：上传文件 / 在线编辑」',
  hqA.indexOf('双形态：上传文件 / 在线编辑') >= 0);
ck('G3 上传区权限注明「仅超级管理员（tenant 级）可编辑」+「门店只读」',
  hqA.indexOf('仅超级管理员（tenant 级）可编辑') >= 0 && hqA.indexOf('门店只读') >= 0);
ck('G3 上传约束逐字（PDF / DOCX / Markdown · ≤10 MiB · SHA-256 · 幂等）',
  hqA.indexOf('支持 PDF / DOCX / Markdown，≤ 10 MiB') >= 0 &&
  hqA.indexOf('自动记 SHA-256') >= 0 && hqA.indexOf('同文件重复上传视为同一版本') >= 0);
ck('G3 上传区含文件选择控件与「或在线编辑全文」按钮',
  hqA.indexOf('type="file"') >= 0 && hqA.indexOf('或在线编辑全文') >= 0);
ck('G3 上传件反病毒 / 反脚本纪律（不解压执行宏）',
  hqA.indexOf('不得解压执行宏') >= 0);
/* 反向断言：上传位必须只在「超管可见角色（hq / all）」出现。
   若条件渲染被改成无条件拼接（或 role 被改成 'hq'），本断言变红。 */
const NON_ADMIN = ['area', 'manager', 'therapist', 'meridian', 'client', 'shop'];
const leakUp = NON_ADMIN.filter(r => (agree[r] || '').indexOf('type="file"') >= 0);
ck('G3 反向：上传位对非超管六角色不可见（条件渲染未被旁路）', leakUp.length === 0,
  '泄漏到 ' + leakUp.join(' , '));
ck('G3 反向：非超管角色亦不得出现上传约束文案',
  NON_ADMIN.every(r => (agree[r] || '').indexOf('仅超级管理员（tenant 级）可编辑') < 0));
ck('G3 上传位可见性 = isSuperAdmin() 语义（hq 与 all 可见）',
  hqA.indexOf('type="file"') >= 0 && (agree.all || '').indexOf('type="file"') >= 0);

/* ---- G4. 新增：占位符渲染示例（白名单 6 项） ---- */
const phMiss = AGREE_PH.filter(p => hqA.indexOf(p) < 0);
ck('G4 白名单 6 个占位符逐条呈现', phMiss.length === 0, '缺 ' + phMiss.join(' , '));
/* 计数断言：占位符表格行数必须恰为 6 —— 防「少一行仍全绿」。 */
ck('G4 占位符表格行数恰为 6（整块删除即变红）',
  (hqA.match(/<tr>\s*<td><code>\$\{[a-z_]+\.[a-z_]+\}<\/code><\/td>/g) || []).length === 6,
  '实际 ' + (hqA.match(/<tr>\s*<td><code>\$\{[a-z_]+\.[a-z_]+\}<\/code><\/td>/g) || []).length);
const demoMiss = AGREE_DEMO.filter(d => hqA.indexOf(d) < 0);
ck('G4 渲染示例值逐条（门店 / 姓名 / 脱敏手机 / 签署日 / 方案版本）', demoMiss.length === 0,
  '缺 ' + demoMiss.join(' , '));
ck('G4 手机号脱敏示例为「138****8800」（前 3 后 4）', hqA.indexOf('138****8800') >= 0);
ck('G4 渲染纪律①「未在白名单内的占位符 → 渲染期报错，不静默留空」',
  hqA.indexOf('未在白名单内的占位符 → 渲染期报错，不静默留空') >= 0);
ck('G4 渲染纪律②「客户手机号强制脱敏（前 3 后 4），原名不得出现在文书内」',
  hqA.indexOf('客户手机号强制脱敏（前 3 后 4），原名不得出现在文书内') >= 0);
ck('G4 白名单的数据边界语义（健康类数据不得注册为占位符）',
  hqA.indexOf('占位符白名单本身就是一道数据边界闸门') >= 0 &&
  hqA.indexOf('一律不得注册为占位符') >= 0);
/* 反向断言：数据边界闸门在原型层不得被绕过 —— 禁止项不得以占位符形态出现。
   注意「不得注册为占位符」的**说明文字**本身含这些词，故只匹配 `${…}` 形态。 */
const BANNED_PH = ['${screening_result}', '${effect_verdict}', '${gap_reason}', '${health.',
  '${band.', '${refund', '${as.', '${sleep', '${hr.', '${spo2'];
const bannedHit = BANNED_PH.filter(p => hqA.indexOf(p) >= 0 ||
  (hqA.match(/\$\{[A-Za-z_]+\.[A-Za-z_]+\}/g) || []).some(x => x === p));
ck('G4 反向：健康类 / 手环类 / 退款类占位符零注册', bannedHit.length === 0,
  '命中 ' + bannedHit.join(' , '));
/* 反向断言：实际注册的占位符集合必须**恰好**等于白名单 6 项，多一个即越界。 */
const registered = Array.from(new Set((hqA.match(/\$\{[A-Za-z_]+\.[A-Za-z_]+\}/g) || [])));
ck('G4 反向：实际注册占位符集合恰为白名单 6 项（越界即变红）',
  registered.length === 6 && AGREE_PH.every(p => registered.indexOf(p) >= 0),
  '实际注册 ' + registered.join(' , '));

out.push('\n=====================================');
out.push(`结果：${pass} 通过 / ${fail} 失败  (合计 ${pass + fail})`);
fs.writeFileSync(path.join(__dirname, '_proto_verify_result.txt'), out.join('\n'), 'utf8');
console.log(out.join('\n'));
/* 缺此退出码 = 套件永远 exit 0：任何以退出码为准的调用方（CI / 门禁 / 脚本）都会把
   失败的构建读成绿的。断言再多也等于没有牙齿。 */
process.exit(fail === 0 ? 0 : 1);
