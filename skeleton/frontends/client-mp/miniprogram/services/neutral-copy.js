/**
 * 端 C · 客户小程序 —— 中性文案表（**唯一的客户可见措辞来源**）
 * ============================================================================
 *
 * 🛑 为什么把文案单独抽一份，而不是写在各个页面里
 * ---------------------------------------------------------------------------
 * PRD §2.6.2 W-2 要求客户端**一律用正向计数 + 中性描述**，并点名禁止若干类
 * 负向计数与原因指向措辞。如果这些句子散落在 9 个页面里，纪律就退化成"每次写
 * 页面时记得别写错" —— 而构建期词表扫描只能证明"**没有出现违禁词**"，
 * **不能证明"措辞是中性且完整的"**。
 *
 * 抽成一份表之后，两个问题一起收敛：
 *   ① 措辞只有一处可改，评审一次即可；
 *   ② 「同屏必须存在的两条告知」（W-3）成为**表里的常量**，页面引用它而不是
 *      自己再抄一遍 —— 抄一遍就有抄错、抄漏、抄走了样的可能。
 *
 * 🛑 本文件**刻意不写出被禁术语的原文**
 * ---------------------------------------------------------------------------
 * ADR-12 的三张词表对客户端包是**全量扫描且不剥注释**（第 51 条系统性缺陷订正后的
 * 口径：注释同样参与匹配）。也就是说：**在客户端包的源码里解释"某个词为什么被禁"
 * 时写出那个词，本身就是一次命中**，会让构建与 CI 变红。
 *
 * 故本文件的说明一律**用指代**（引用条款号、字段组编号、组名），不引原文：
 *   · 「R2 组」= 契约 x-wording-discipline 的第二个词条组（结论类宣称），本条不引其原文；
 *   · 「③ 组」  = 缺口原因分类（对客户恒 403）；
 *   · 「W-2」  = PRD §2.6.2 的中性措辞约束。
 *
 * 🛑 措辞的取舍依据（逐条对应 PRD，不是自创）
 * ---------------------------------------------------------------------------
 * · 采集情况 -> 只用「已采集 N 天」正向计数，**不用**任何"少了几天"的说法（W-2）。
 * · 没有数据的那一天 -> 只说「该日暂无数据」，**不解释原因**（W-2：原因分类
 *   对客户不可见，§2.6.1 矩阵"缺口原因分类"列在客户端那一行是"不可见"）。
 * · 手环没接上 -> 「手环未接入」，**不写成**"客户未按其使用"（W-2）。
 *   ⚠️ 这条尤其重要：说明书要求客户淋浴 / 桑拿 / 游泳时摘下手环，客户照做却看到
 *   一句过错指向自己的话，等于**把厂商要求判成客户过错**（PRD §2.6.2 W-2 的理由栏）。
 * · 两条告知（W-3）-> 必须同屏出现，缺任一条即验收不通过。
 *
 * 🛑 本文件里为什么没有"免责声明"
 * ---------------------------------------------------------------------------
 * 系统级的定位声明属**产品定位陈述**，PRD Non-goals 明确它**不得**被渲染进
 * 小程序包体（该声明本身就含 R2 组术语）。故本表不提供这类句子 —— 这是刻意留白。
 */

'use strict';

/** W-3 ① ：手环数据的定位告知，必须与手环数据同屏。 */
var BAND_REFERENCE_NOTICE = '手环数据为参考之一，不用于单方判定';

/** W-3 ② ：数据同步时间告知（格式：数据同步时间：YYYY-MM-DD）。 */
var BAND_SYNC_TIME_LABEL = '数据同步时间';

/** 客户端不展示精确时刻 —— 契约 `synced_date` 逐字要求"精确到日（不得到分秒）"。 */
function toDay(value) {
  if (!value) return '';
  var s = String(value);
  // 兼容 'YYYY-MM-DD'、'YYYY-MM-DDTHH:mm:ss'、'YYYY/MM/DD'
  var m = s.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})/);
  if (m) {
    return m[1] + '-' + ('0' + m[2]).slice(-2) + '-' + ('0' + m[3]).slice(-2);
  }
  return s.slice(0, 10);
}

/** 「数据同步时间：2026-09-30」；无值时不猜、不回落成今天。 */
function syncTimeLine(syncedDate) {
  var day = toDay(syncedDate);
  if (!day) return '';
  return BAND_SYNC_TIME_LABEL + '：' + day;
}

/** 采集情况：正向计数。N 为 0 或取不到时说"尚未采集"，不说"少了 N 天"。 */
function collectedLine(days) {
  var n = Number(days);
  if (!isFinite(n) || n <= 0) return '尚未采集到手环数据';
  return '已采集 ' + n + ' 天';
}

/** 数据源：手环已接入 / 未接入。两者都不带任何评价。 */
function dataSourceLine(dataSource) {
  return dataSource === '手环' ? '数据来源：智能手环' : '手环未接入';
}

/** 某一天没有数据时的中性说法：不解释原因，也不指向任何一方。 */
var NO_DATA_THAT_DAY = '该日暂无数据';

/**
 * 同步状态的**中性**说法。
 * ⚠️ 映射表只覆盖契约 BandSyncStatusData.state 的四个值
 * （syncing / synced / sync_failed / no_data_today）；出现表外的值时返回一句
 * 中性占位，**不把未知状态原样透出**（原样透出会把内部状态名变成客户可见文案）。
 */
var SYNC_STATE_TEXT = {
  syncing: '正在同步',
  synced: '已同步',
  sync_failed: '同步未完成，可稍后再试',
  no_data_today: NO_DATA_THAT_DAY,
};

function syncStateLine(state) {
  if (Object.prototype.hasOwnProperty.call(SYNC_STATE_TEXT, state)) {
    return SYNC_STATE_TEXT[state];
  }
  return '同步状态：待确认';
}

/**
 * 同步未完成时给客户的动作建议。
 * 🛑 契约 `next_action` 的取值是 open_bluetooth / grant_permission / retry / none，
 * 全属**技术性动作**；映射到客户能自己完成的动作。**不出现**任何把原因指向客户
 * 行为的说法（W-2 禁止把缺口写成客户过错）。
 */
var NEXT_ACTION_TEXT = {
  open_bluetooth: '请确认手机蓝牙已开启',
  grant_permission: '请允许小程序使用蓝牙权限',
  retry: '可稍后下拉重试',
  none: '',
};

function nextActionLine(nextAction) {
  if (Object.prototype.hasOwnProperty.call(NEXT_ACTION_TEXT, nextAction)) {
    return NEXT_ACTION_TEXT[nextAction];
  }
  return '';
}

/** 通用：空值不渲染成 '-'，而是不显示（避免零值或空值被读成一种结论）。 */
function orEmpty(value) {
  if (value === null || value === undefined || value === '') return '';
  return String(value);
}

module.exports = {
  BAND_REFERENCE_NOTICE: BAND_REFERENCE_NOTICE,
  BAND_SYNC_TIME_LABEL: BAND_SYNC_TIME_LABEL,
  NO_DATA_THAT_DAY: NO_DATA_THAT_DAY,
  toDay: toDay,
  syncTimeLine: syncTimeLine,
  collectedLine: collectedLine,
  dataSourceLine: dataSourceLine,
  syncStateLine: syncStateLine,
  nextActionLine: nextActionLine,
  orEmpty: orEmpty,
};