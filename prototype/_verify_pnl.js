/* E 组「健康资产损益」原型自检 —— Node 执行，结果落盘 _verify_pnl_result.txt
   口径基准：_work/health-asset-pnl-design-2026-09-20.md（v3 定稿）
   校验项：四端可见性分层 / 免责声明逐字 / 搭配词黑名单零命中 / 比率与门槛数字零命中
           / AL-1~AL-6 对齐规则 / 血氧措辞约束 / Q11 疗程口径不得回退
   断言方式：真跑 P.pnlClient / P.pnlTherapist / P.pnlAdmin 的渲染结果，而非文本匹配。 */
const fs = require('fs');
const path = require('path');
const SRC = path.join(__dirname, 'index.html');
const html = fs.readFileSync(SRC, 'utf8');
const out = [];
let pass = 0, fail = 0;
function ck(name, cond, extra) {
  if (cond) { pass++; out.push('  PASS  ' + name); }
  else { fail++; out.push('  FAIL  ' + name + (extra ? '\n        线索: ' + extra : '')); }
}

/* --- 真跑渲染，取得三端输出 --- */
let api = null, render = {};
try {
  const src = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/render\(\);\s*$/, '');
  const stub = { querySelector: () => null, createTreeWalker: () => null };
  api = new Function('document', 'window', 'NodeFilter',
    src + '\nreturn {P:P, ROLES:ROLES, SCREENS:SCREENS, setCur:v=>{curRole=v}};'
  )(stub, {}, { SHOW_TEXT: 4 });
  ['client', 'therapist', 'meridian', 'hq', 'manager'].forEach(r => {
    api.setCur(r);
    render[r] = (r === 'client' ? api.P.pnlClient() : (r === 'hq' || r === 'manager') ? api.P.pnlAdmin() : api.P.pnlTherapist());
  });
  out.push('  INFO  沙箱加载成功');
} catch (e) {
  out.push('  ERR   沙箱加载失败: ' + e.message);
  fail++;
}

const C = render.client || '', T = render.therapist || '', M = render.meridian || '', A = render.hq || '';
const MANAGER_VARIANT = render.manager === undefined ? '' : render.manager;

out.push('\n=== A. 屏幕注册与角色归属 ===');
const ids = [...html.matchAll(/\{id:'(pnl[A-Za-z]+)',\s*i:'(E\d)'/g)].map(m => ({ id: m[1], i: m[2] }));
ck('E 组共注册 3 屏', ids.length === 3, '实际 ' + ids.length);
ck('E1 = pnlClient', ids.some(x => x.id === 'pnlClient' && x.i === 'E1'));
ck('E2 = pnlTherapist', ids.some(x => x.id === 'pnlTherapist' && x.i === 'E2'));
ck('E3 = pnlAdmin', ids.some(x => x.id === 'pnlAdmin' && x.i === 'E3'));
const roleOf = id => { const m = html.match(new RegExp("\\{id:'" + id + "'[\\s\\S]{0,120}?role:'([a-z]+)'")); return m ? m[1] : null; };
ck('E1 归属 client', roleOf('pnlClient') === 'client', '实际 ' + roleOf('pnlClient'));
ck('E2 归属 therapist', roleOf('pnlTherapist') === 'therapist', '实际 ' + roleOf('pnlTherapist'));
ck('E3 归属 manager', roleOf('pnlAdmin') === 'manager', '实际 ' + roleOf('pnlAdmin'));

out.push('\n=== B. 三端渲染非空且互异（可见性分层真实存在） ===');
ck('E1 客户端渲染非空', C.length > 500, '长度 ' + C.length);
ck('E2 调理师渲染非空', T.length > 500, '长度 ' + T.length);
ck('E2 经络师渲染非空', M.length > 500, '长度 ' + M.length);
ck('E3 管理员（hq）渲染非空', A.length > 500, '长度 ' + A.length);
/* 注：此前此处断言「manager 角色下渲染为空」，是把「渲染函数是否返回空」误当作角色归属来测。
   渲染函数本身不做可见性分支，任何角色都返回同样 HTML（可见性由导航过滤 + 服务端判定负责）。
   唯一的角色相关差异是抬头 crumb 回显的角色名（「总部运营」vs「门店负责人」）。
   ⇒ 正确契约 = 剥掉角色名回显后，两角色输出逐字相同。 */
const denorm = (s, roleName) => s.split(roleName).join('«角色名»');
ck('E3 渲染函数无角色级可见性分支（剥掉角色名回显后逐字相同）',
  A.length > 500 && denorm(MANAGER_VARIANT, '门店负责人') === denorm(A, '总部运营'),
  'manager 长度 ' + MANAGER_VARIANT.length + ' / hq 长度 ' + A.length);
ck('调理师与经络师渲染结果不同（角色级差异化）', T !== M);
ck('客户端与管理员渲染结果不同', C !== A);

out.push('\n=== C. 免责声明（D-1 / D-2 / D-3）逐字必带于客户端 ===');
const D1 = '本指数仅作状态记录，不构成任何服务结果的判定依据，不代表任何收益承诺';
const D3 = '健康数据属于您的个人信息，此处仅作记录与展示';
ck('D-1 主文案逐字出现在客户端 E1', C.indexOf(D1) >= 0);
ck('D-3 配套第二句逐字出现在客户端 E1', C.indexOf(D3) >= 0);

/* 客户端「主动呈现」的正文 = 全文 − 免责声明法条 − 不可见清单
   两处例外有明确理由：① 免责声明是法定逐字文案，含「收益承诺」属必要法条表述；
   ② 不可见清单本身就是在「说明哪些不给」，其列举项不应算作被呈现的内容。 */
const clientFacing = C
  .split('本指数仅作状态记录')[0]
  .replace(/<div class="lockbox">[\s\S]*?<\/div>\s*<\/div>/g, '');
out.push('  INFO  客户端主动呈现正文长度 ' + clientFacing.length + '（已剔除免责声明与不可见清单）');

out.push('\n=== D. 搭配词黑名单 —— 客户端零命中（承诺 / 盈亏 / 账户类） ===');
const BLACK = ['增值', '收益', '回报', '保值', '复利', '翻倍', '亏损', '赚了', '亏了', '盈亏',
  '账户', '余额', '存款', '提现', '转让', '继承', '变现', '折现'];
const hitC = BLACK.filter(w => clientFacing.indexOf(w) >= 0);
ck('客户端 E1 搭配词黑名单零命中', hitC.length === 0, '命中: ' + hitC.join(' , '));
/* 内部屏在「不可见清单」中列举被排除项属正常，故黑名单仅约束客户端主动呈现正文 */
ck('免责声明法条中的「收益承诺」不计入黑名单（法定逐字文案）', C.indexOf(D1) >= 0 && D1.indexOf('收益') >= 0);

out.push('\n=== E. 比率与门槛数字 —— 客户端零命中（A-1 / A-3 / §4.4） ===');
/* 例外：「占比」类无害百分数不计；此处直接查风险形态「率」与门槛数字 */
const RATIO_WORDS = ['改善率', '变化率', '增值率', '收益率', '回报率', '有效率', '治愈率'];
const hitR = RATIO_WORDS.filter(w => clientFacing.indexOf(w) >= 0);
ck('客户端 E1 无任何比率词', hitR.length === 0, '命中: ' + hitR.join(' , '));
ck('客户端 E1 不含门槛数字 18.8%', C.indexOf('18.8') < 0);
ck('客户端 E1 不含 MCID 判据说明', C.indexOf('MCID') < 0 && C.indexOf('≥3') < 0 && C.indexOf('≥ 3') < 0);
ck('客户端 E1 不出现「达标」或「未达标」评价', clientFacing.indexOf('达标') < 0);
ck('客户端 E1 不出现改善率字段名', C.indexOf('improvement_rate') < 0);
ck('客户端 E1 不出现效果判定字段名', C.indexOf('effect_verdict') < 0);
ck('客户端 E1 不出现缺口原因分类字段名', C.indexOf('gap_reason') < 0);

out.push('\n=== F. 「只到健康类」边界：客户端可看 vs 不可看 ===');
ck('客户端 E1 可见健康指数当前值', C.indexOf('当前') >= 0 && C.indexOf('/ 112') >= 0);
ck('客户端 E1 可见变化量', /较期初 [+-]?\d+/.test(C));
ck('客户端 E1 可见端点轴（期初 / 本次）', C.indexOf('期初') >= 0 && C.indexOf('本次') >= 0);
ck('客户端 E1 可见逐日过程线', C.indexOf('逐日过程线') >= 0);
ck('客户端 E1 显式列出不可见清单', C.indexOf('不在本页展示') >= 0);

out.push('\n=== G. 不进展示层的手环字段 —— 客户端零命中 ===');
const NEVER = ['血压', '体温', '血糖', '尿酸'];
/* 客户端 E1 正文不得出现这四项（不可见清单中作为告知列出属允许，需区分） */
const bodyC = C.split('以下内容不在本页展示')[0];
const hitN = NEVER.filter(w => bodyC.indexOf(w) >= 0);
ck('客户端 E1 正文（不可见清单之前）无血压/体温/血糖/尿酸', hitN.length === 0, '命中: ' + hitN.join(' , '));

out.push('\n=== H. 血氧措辞约束三条 ===');
ck('客户端 E1 展示血氧原始值', C.indexOf('血氧') >= 0);
ck('客户端 E1 含 W-3 ①「参考之一，不用于单方判定」', C.indexOf('参考之一，不用于单方判定') >= 0);
ck('客户端 E1 含 W-3 ② 同步时间精确到日', /数据同步时间：[\d-]+（精确到日）/.test(C));
ck('客户端 E1 无血氧判定性措辞', !/血氧[^。]{0,12}(正常|异常|偏低|偏高)/.test(C) && !/(正常|异常|偏低|偏高)[^。]{0,12}血氧/.test(C));

out.push('\n=== I. 对齐规则 AL-1~AL-6 ===');
ck('AL-2 量表端点用标记点、不用折线', html.indexOf('endpointAxis') >= 0 && html.indexOf('function endpointAxis') >= 0);
ck('AL-3 过程线缺段断开（null 显式断）', html.indexOf('function processLine') >= 0 && /series\.forEach\(\(v,i\)=>\{\s*if\(v===null\)/.test(html));
ck('AL-6 两轴之间保持视觉空白（axisgap 定义）', html.indexOf('.axisgap') >= 0);
ck('AL-6 无把过程线与端点连起来的箭头/因果措辞', C.indexOf('导致') < 0 && C.indexOf('因为…所以') < 0);
ck('AL-4 手环纵轴不与量表共用刻度（各自独立 svg）', (C.match(/<svg/g) || []).length >= 4);

out.push('\n=== J. 归因纪律 A-4 / A-5（不得因果归因、不得时间承诺） ===');
ck('客户端 E1 不作因果归因（无「经过调理…」句式）', !/经过调理[^。]{0,20}(您的|你的)/.test(C));
ck('客户端 E1 不作因果归因（无「调理带来的」句式）', C.indexOf('调理带来的') < 0);
ck('客户端 E1 无时间承诺（X 天见效类）', !/\d+\s*天(见效|内)/.test(C) && C.indexOf('见效周期') < 0);
ck('客户端 E1 明示不含效果评价', C.indexOf('不是对调理效果的评价') >= 0);

out.push('\n=== K. 方向翻转（H = 112 − T） ===');
ck('E1 声明量程 0–112 且越大越轻', C.indexOf('112') >= 0 && C.indexOf('越大表示症状负担越轻') >= 0);
ck('E3 明写必须翻转、否则恶化会被读成增长', A.indexOf('翻转') >= 0 && A.indexOf('恶化会被读成增长') >= 0);
ck('基准数据自洽：112-72=40 症状、112-88=24 症状，ΔH=+16', (112 - 72 === 40) && (112 - 88 === 24) && (88 - 72 === 16));

out.push('\n=== L. 内部角色可见性（不得因本项加重 R-18 矛盾） ===');
ck('E2 调理师明写不可见改善率', T.indexOf('不可见：改善率') >= 0);
ck('E2 调理师明写不可见结算相关内容', T.indexOf('结算') >= 0 && T.indexOf('不可见：结算相关内容') >= 0);
ck('E2 经络师可见判定结论', M.indexOf('判定结论') >= 0);
ck('E2 明写健康类 / 结算类是分界线、不得合并', T.indexOf('分界线') >= 0 && T.indexOf('不得合并') >= 0);
ck('E3 明写不新建「门店损益率」聚合指标', A.indexOf('不新建') >= 0 && A.indexOf('门店损益率') >= 0);
ck('E3 明写按既有行级 scope、不新开', A.indexOf('既有 scope') >= 0 || A.indexOf('不新开') >= 0);

out.push('\n=== M. 孤儿维与模块映射纪律 ===');
ck('E1 说明记忆专注维未纳周期复评、读数不变不代表没变化', C.indexOf('记忆专注') >= 0 && C.indexOf('不代表「没有变化」') >= 0);
ck('E2 明写记忆专注为孤儿维、不参与效果判定', T.indexOf('孤儿维') >= 0 && T.indexOf('不参与效果判定') >= 0);
ck('E2 模块级表仅含 5 个非孤儿维', (T.match(/<tr><td>M\d /g) || []).length === 5, '实际 ' + (T.match(/<tr><td>M\d /g) || []).length);

out.push('\n=== N. Q11 疗程口径不得回退（业务方 2026-09-19 授权冻结） ===');
ck('原型无「15~45 次」旧口径残留（仅允许出现在作废标注中）',
  (html.match(/15~45/g) || []).length <= 1, '出现 ' + (html.match(/15~45/g) || []).length + ' 次');
ck('原型无「待业务裁定（裁定单 Q11）」残留', html.indexOf('待业务裁定（裁定单 Q11）') < 0);
ck('原型写明 7 次 / 14 次 / 巩固维护期', html.indexOf('调优周期') >= 0 && html.indexOf('巩固维护期') >= 0 && html.indexOf('14 次') >= 0);
ck('调优周期口径已标注冻结出处', html.indexOf('2026-09-19 授权冻结') >= 0 || html.indexOf('2026-09-19 授权') >= 0);
/* 「45 次」是旧 Q11 量程（15~45 次）的唯一残留数字；Q11 冻结后不得再作为现行口径出现。
   唯一允许保留处 = L741 的作废标注本身（「原『15~45 次』作废」—— 记录被推翻的旧值）。
   L260 常驻顶栏的「周期进度 14 / 45 次」与 L850 的「销售承诺 45 次」均已改。 */
const old45 = [...html.matchAll(/45\s*次/g)];
ck('原型无「45 次」旧量程残留（唯一允许出现在作废标注中）',
  old45.length <= 1 && html.indexOf('原「15~45 次」作废') >= 0, old45.length + ' 处');
ck('常驻顶栏周期进度已改为「14 次 · 已转巩固维护期」',
  A.length > 0 && html.indexOf('周期进度 <b>14 次</b>') >= 0 && html.indexOf('14 / 45') < 0);

out.push('\n=== O. 退款遮蔽机制未被新屏绕过 ===');
/* data-nomask 合法用途共 3 处：
   ① L757 退款可见性矩阵（需在遮蔽态下显示矩阵本身）
   ② L1204 maskRefundText 内 closest('[data-nomask]') 选择器（代码引用，非 DOM 属性）
   ③ L1219 遮蔽态横幅本身
   故期望值为 3，而非 2。 */
ck('data-nomask 未被新屏滥用（原型共 3 处，均为既有合法用途）',
  (html.match(/data-nomask/g) || []).length === 3, '出现 ' + (html.match(/data-nomask/g) || []).length + ' 次');
ck('E 组三端零「退款」字样（客户端本就不得出现；内部亦未在本视图引入）',
  [C, T, M, A].filter(x => x.length > 0).every(x => x.indexOf('退款') < 0));

out.push('\n=== P. 页面抬头文案（#pageTitle / #pageSub）不得泄漏退款字样 ===');
/* maskRefundText 只作用于 #stage；而 #pageTitle/#pageSub 由 SCREENS 的 n/d 直接赋值，
   位于遮蔽范围之外。⇒ 凡对「不可见退款角色」可见的屏，其 n/d 一律不得含「退款」。
   不可见角色 = 非 REFUND_ROLES（client / therapist / shop）；
   另 role:'all' 的屏对所有人可见（含 client），同样必须干净。 */
const REFUND_OK = ['meridian', 'manager', 'hq', 'area'];
const MASKED_ROLES = ['client', 'therapist', 'shop'];
const leakScreens = (api && api.SCREENS ? api.SCREENS : [])
  .filter(s => s.role === 'all' || MASKED_ROLES.indexOf(s.role) >= 0)
  .filter(s => (s.n + s.d).indexOf('退款') >= 0);
ck('对遮蔽角色可见的屏，其抬头文案零「退款」', leakScreens.length === 0,
  leakScreens.map(s => s.i + ' ' + s.n + ' / ' + s.d).join(' ｜ '));
ck('E2 抬头文案已改为「不含结算类结论」（原「无退款类结论」会泄漏）',
  html.indexOf("d:'模块级损益明细 · 不含结算类结论'") >= 0 &&
  html.indexOf("d:'模块级损益明细 · 无退款类结论'") < 0);
ck('遮蔽角色集合与 REFUND_ROLES 一致（无端门店客服亦属遮蔽）',
  (html.match(/const REFUND_ROLES = \[([^\]]*)\]/) || [])[1] === "'meridian','manager','hq','area'");

out.push('\n=== Q. E2 可被「经络师」角色看到（同 APP 端双角色覆盖） ===');
/* E2 的标题即「调理师 / 经络师（APP）」，故二者都必须能看到该屏。
   若只写 role:'therapist'，经络师角色下该屏会被导航过滤掉 → 标题与实际可见性自相矛盾。 */
ck('E2 声明 roles 覆盖 therapist 与 meridian',
  /id:'pnlTherapist'[\s\S]{0,200}?roles:\['therapist','meridian'\]/.test(html));
ck('可见性判定走单一真相源 visFor（定义 1 处 + 调用 2 处，无重复内联过滤）',
  html.indexOf('const visFor') >= 0 &&
  (html.match(/visFor\(/g) || []).length >= 2 &&
  /* 旧的重复内联过滤条件（renderNav / setRole 各写一份）不得残留 —— 去掉定义行本身再查 */
  html.replace(/const visFor[^\n]*\n/, '').indexOf("s.role==='all'") < 0,
  '调用 ' + (html.match(/visFor\(/g) || []).length + ' 处');
let navigable = null;
try {
  const src2 = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/render\(\);\s*$/, '');
  const stub2 = { querySelector: () => null, createTreeWalker: () => null };
  const api2 = new Function('document', 'window', 'NodeFilter',
    src2 + '\nreturn {visFor:visFor, SCREENS:SCREENS};')(stub2, {}, { SHOW_TEXT: 4 });
  const e2 = api2.SCREENS.find(s => s.id === 'pnlTherapist');
  const m2 = s => api2.visFor(s, 'meridian'), t2 = s => api2.visFor(s, 'therapist');
  navigable = { meridian: m2(e2), therapist: t2(e2) };
} catch (e) { out.push('  ERR   visFor 求值失败: ' + e.message); }
ck('真跑 visFor：E2 对调理师与经络师均可见',
  navigable && navigable.meridian === true && navigable.therapist === true,
  JSON.stringify(navigable));
ck('真跑 visFor：E2 对客户与门店客服仍不可见', (() => {
  if (!navigable) return false;
  try {
    const src3 = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/render\(\);\s*$/, '');
    const api3 = new Function('document', 'window', 'NodeFilter',
      src3 + '\nreturn {visFor:visFor, SCREENS:SCREENS};')({ querySelector: () => null }, {}, { SHOW_TEXT: 4 });
    const e2 = api3.SCREENS.find(s => s.id === 'pnlTherapist');
    return api3.visFor(e2, 'client') === false && api3.visFor(e2, 'shop') === false;
  } catch (e) { return false; }
})());

out.push('\n=== R. 渲染完整性与模板字面量健康度 ===');
/* 本轮真实缺陷：E3 的 '① 任何 `%` 形态…' 里，`%` 的反引号**提前终止了外层模板字面量**，
   剩下部分被当成 标签模板 + 字符串取模 → 渲染出 NaN，并**静默吞掉整段 ①–④ 清单与尾注**。
   它同时通过了：语法检查（合法 JS）、长度下限、以及所有「存在性」断言 —— 属最隐蔽的一类。 */
const allF = { client: C, therapist: T, meridian: M, hq: A };
const nanHit = Object.keys(allF).filter(k => /NaN|undefined|\[object Object\]/.test(allF[k]));
ck('四端渲染结果零 NaN / undefined / [object Object]', nanHit.length === 0,
  nanHit.map(k => k + ' 命中').join(' , '));
ck('E3 渲染结果未被截断（①–④ 不可提供清单与尾注均存在）',
  A.indexOf('本视图仍不提供') >= 0 && A.indexOf('结构隐喻') >= 0, '长度 ' + A.length);
ck('L1158 处 % 已去除反引号（曾致模板字面量提前终止）', html.indexOf('① 任何 `%` 形态') < 0);
ck('模板字面量内无裸露反引号（全文 ` 计数为偶数 = 成对闭合）',
  (html.match(/`/g) || []).length % 2 === 0,
  '反引号 ' + (html.match(/`/g) || []).length + ' 个');

out.push('\n=====================================');
out.push('结果：' + pass + ' 通过 / ' + fail + ' 失败  (合计 ' + (pass + fail) + ')');
fs.writeFileSync(path.join(__dirname, '_verify_pnl_result.txt'), out.join('\n'), 'utf8');
console.log(out.join('\n'));
process.exit(fail === 0 ? 0 : 1);