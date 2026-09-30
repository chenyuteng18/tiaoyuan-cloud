/**
 * 端 C · 客户小程序 —— 业务域接口封装
 * ============================================================================
 *
 * 这一层的价值只有一个：**把契约里的 operationId 与页面之间竖一道墙**。
 * 页面写 `api.getCustomer(id)`，而不是自己拼路径、自己挑 method —— 拼路径的地方
 * 越多，"某处写错了但门禁没覆盖到"的面积就越大。本仓 `client-package/api/` 的
 * 手写路径清单已腐烂过一次（原 8 条只有 2 条与冻结契约相符），那是这一层的存在理由。
 *
 * 🛑 本层不做的事（逐条写出来，免得后来者"顺手"加进来）
 * ---------------------------------------------------------------------------
 * ① **不裁剪字段**。服务端下发的就是客户可见的（X-1）。若页面拿到了不该看的字段，
 *    正确动作是**服务端修**，不是在客户端 `delete` 掉 —— 后者只会让问题看不见。
 * ② **不归类缺口原因**。原因分类字段属内部口径，§2.6.1 矩阵里客户端那一行是"不可见"。
 * ③ **不推算任何评价或结论**。客户端不出现由手环数据推导出的一切（W-1）。
 * ④ **不代填日期**。补填窗口由服务端判定（≤2 天，可配置）；本层只传客户选的那一天，
 *    不替服务端"修正"成合法日期 —— 修正会掩盖"客户选了个不允许的日期"这一事实。
 *
 * 页面可调用的端点**只能是契约授予本端的 15 个**（见 contract/endpoints.js）。
 * 调了别的会在此层被拒（request.call 的出站白名单）。
 */

'use strict';

var request = require('./request.js');

// ---------------------------------------------------------------------------
// A 域 · 身份
// ---------------------------------------------------------------------------
function me() {
  return request.call('authMe').then(function (r) { return r.data || {}; });
}

// ---------------------------------------------------------------------------
// B 域 · 客户档案（客户侧只读 + 首访量表）
// ---------------------------------------------------------------------------
function getCustomer(id) {
  return request.call('getCustomer', { params: { id: id } }).then(function (r) { return r.data || {}; });
}

function getIntakeProfile(id) {
  return request.call('getIntakeProfile', { params: { id: id } }).then(function (r) { return r.data || {}; });
}

// ---------------------------------------------------------------------------
// C 域 · 评估（客户可回看自己的评估与题库）
// ---------------------------------------------------------------------------
function getAssessment(customerId, assessmentId) {
  return request.call('getAssessment', {
    params: { id: customerId, assessment_id: assessmentId },
  }).then(function (r) { return r.data || {}; });
}

/**
 * 量表题库。
 *
 * 🛑 `age_group` 是契约 `required`（本仓第 70 条）——
 *    本函数**不得**给载体形参默认值、**必须**在运行时守卫 required。
 *    原实现 `function listScaleItemBanks(ageGroup, dimension, version)` 允许零参调用：
 *    `listScaleItemBanks()` ⇒ `query = { age_group: undefined }` ⇒ 后端 400，
 *    而所有门禁一律绿（**端 B 的同一函数此前已修，端 C 这份被漏掉** —— 第 63 条同族）。
 *
 * 🛑 为什么"页面有守卫"不够：`scale-bank.js` 的 `if (!this.data.ageGroup) return`
 *    只保护了**一个**调用点。封装层是**共享入口**，守卫必须立在入口上 ——
 *    否则第二个调用点（或将来任何直接调用）会绕过页面守卫，静默发出空 `age_group`。
 */
function listScaleItemBanks(ageGroup, dimension, version) {
  if (!ageGroup || !String(ageGroup).trim()) {
    throw new Error('listScaleItemBanks: age_group 必填（契约 C1 required 参数）。');
  }
  var query = { age_group: ageGroup };
  if (dimension) query.dimension = dimension;
  if (version) query.version = version;
  return request.call('listScaleItemBanks', { query: query }).then(function (r) { return r.data || {}; });
}

// ---------------------------------------------------------------------------
// D 域 · 服务与每日填报
// ---------------------------------------------------------------------------
function listVisits(customerId) {
  return request.call('listVisits', { params: { id: customerId } }).then(function (r) { return r.data || {}; });
}

function listDailyReports(customerId) {
  return request.call('listDailyReports', { params: { id: customerId } }).then(function (r) { return r.data || {}; });
}

/**
 * 每日填报（D3）。`source` 由服务端区分"客户自填"与"门店代核"，客户端恒填 '客户'。
 * 写请求：Idempotency-Key 由"客户 + 日期"决定 —— 同一天重试必须是同一键，
 * 否则服务端会把补填看成两笔。
 */
function submitDailyReport(customerId, date, answers) {
  return request.call('submitDailyReport', {
    params: { id: customerId },
    idempotencyKey: 'dr-' + customerId + '-' + date,
    body: { date: date, answers_json: answers || {}, source: '客户' },
  }).then(function (r) { return r.data || {}; });
}

// ---------------------------------------------------------------------------
// D 域 · 方案（客户只读）
// ---------------------------------------------------------------------------
function getPlan(id) {
  return request.call('getPlan', { params: { id: id } }).then(function (r) { return r.data || {}; });
}

// ---------------------------------------------------------------------------
// E 域 · 手环（读）
// ---------------------------------------------------------------------------
function getBandTelemetry(customerId) {
  return request.call('getBandTelemetry', { params: { id: customerId } }).then(function (r) { return r.data || {}; });
}

module.exports = {
  me: me,
  getCustomer: getCustomer,
  getIntakeProfile: getIntakeProfile,
  getAssessment: getAssessment,
  listScaleItemBanks: listScaleItemBanks,
  listVisits: listVisits,
  listDailyReports: listDailyReports,
  submitDailyReport: submitDailyReport,
  getPlan: getPlan,
  getBandTelemetry: getBandTelemetry,
};