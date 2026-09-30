/**
 * 端 C · 客户小程序 —— 错误码 → 客户可见文案（**契约要求的映射层**）
 * ============================================================================
 *
 * 🛑 为什么必须有这一层
 * ---------------------------------------------------------------------------
 * 契约 `ResultEnvelope.message` 的字段说明逐字写着：
 *
 *     面向开发者，不得直接渲染给客户（客户端有自己的 code → copy 映射）
 *
 * 也就是说：把服务端 message 弹给客户看，**不是"体验差一点"，而是违反了契约定下
 * 的约定**。服务端 message 里会出现内部词汇（字段名、门禁名、档位名），而客户侧
 * 有明确禁用词表（契约 `x-wording-discipline.client-forbidden-terms`）。
 *
 * 映射的 code 取值逐条抄自契约 `x-error-codes`，**不自行增删**。出现表外的 code 时
 * 走兜底文案 —— 兜底刻意"含糊但不撒谎"：它不解释原因，也不暗示客户做错了什么。
 *
 * 🛑 为什么这里没有"重试按钮"式的统一文案
 * ---------------------------------------------------------------------------
 * 不同 code 的可执行动作不同：401 要重新登录、6001 要等一会儿、4002 是幂等命中
 * （**其实成功了**，不是错误）。把它们统一成"操作失败，请重试"是常见做法，但那会
 * 把"该重新登录"和"其实已经成功"都变成"再点一次" —— 客户点十次也不会对。
 */

'use strict';

var contract = require('../contract/endpoints');

/**
 * 契约 x-error-codes 全集（http / code / name 逐条对应）。
 * 值为**客户可见文案**，因此：无内部字段名、无门禁名、不作原因解释、无评价。
 */
var CODE_COPY = {
  // 400 · 参数不满足约束
  1001: '提交的信息不完整或格式不正确，请检查后重试',
  // 401 · 无 token / 过期 / 非法
  1002: '登录状态已失效，请重新登录',
  // 403 · 该角色对请求字段组无可见性档位
  2001: '当前账号没有查看这部分内容的权限',
  // 403 · 前置门禁未满足
  2002: '还有前置步骤未完成，完成后即可继续',
  // 403 · 跨租户
  2003: '当前账号无权访问该内容',
  // 404 · 本租户内确实不存在
  3001: '没有找到对应的记录',
  // 409 · 版本不可覆盖
  4001: '该内容已有更新的版本，请刷新后重试',
  // 409 · 幂等键命中（返回首次结果）—— 这不是失败
  4002: '',
  // 422 · 业务规则不满足
  5001: '提交的内容不符合要求，请核对后重试',
  // 429 · 节流（如 onShow 5 分钟窗口）
  6001: '操作有点频繁，请稍后再试',
  // 500 · 服务端异常
  9001: '服务暂时不可用，请稍后再试',
  // 403 · 占位符越界（域 I）
  2004: '该内容暂不可用',
};

/** 表外 code 的兜底：不解释原因，也不暗示客户做错。 */
var UNKNOWN_COPY = '操作未完成，请稍后再试';

/** 网络层失败（没有 code），与"服务端明确拒绝"是两件事，故文案分开。 */
var NETWORK_COPY = '网络不稳定，请检查网络后重试';

/**
 * 取「契约要求的拒绝原因名」—— 字段名取自生成物常量，不手写。
 *
 * 🛑 端 C 的处置与端 A / 端 B **刻意不同**（这不是遗漏，是本层最重要的设计决定）
 * ---------------------------------------------------------------------------
 * 契约 `forbidden-403` 要求「不得模糊报错」，给出未满足项 / 被拒字段名 ——
 * 那一半是给**机器**的：前端按它分支（"缺哪一步就去补哪一步"）。
 * 但同一份契约的 `x-wording-discipline.client-forbidden-terms` 又明令
 * **客户端不得出现内部概念**，而 `missing_items` 的取值恰恰是内部标识
 * （`PROFILED` / `CONSENTED` / `screening_result` / `plan_approved` …）。
 *
 * ⇒ 两处要求的交集不是"二选一"，而是：**透传进程序，不渲染进文案**。
 *   故本函数只把原因名交给**页面逻辑**用于决定跳哪一页 / 提示去补哪一步，
 *   而 `describe()` 返回的 `text` **绝不拼接它们**（拼接即违反词表纪律）。
 *   🛑 若有人"顺手"把它拼进 text，客户会看到 `PROFILED` 这类标识 ——
 *   那正是契约词表要防的事。故 `text` 与 `reasons` 是**两个独立出口**。
 */
function reasonsOf(code, data) {
  var field = code === null ? null : contract.PROTOCOL.ERROR_DATA_FIELDS[code];
  if (!field) return [];
  var v = data ? data[field] : undefined;
  if (!Array.isArray(v)) return [];
  return v.filter(function (s) { return typeof s === 'string'; });
}

function isReplay(code) {
  return Number(code) === 4002;
}

/**
 * 把一个请求错误翻成客户可见文案。
 *
 * @param {Error} err  request.call 抛出的错误（可能带 .code / .statusCode / .data）
 * @returns {{text:string, code:number|null, traceId:string, kind:string, reasons:string[]}}
 *          kind: 'auth' | 'replay' | 'server' | 'network' | 'ok'
 *          🛑 `reasons` 是**程序用**的原因名清单（契约要求，见 reasonsOf 的注释），
 *             只在**中性文案里渲染**，是否落到界面由中性层 `nextActionLine` 决定。
 */
function describe(err) {
  if (!err) return { text: '', code: null, traceId: '', kind: 'ok', reasons: [] };

  var code = err.code === undefined || err.code === null ? null : Number(err.code);
  var traceId = err.traceId || '';
  var reasons = reasonsOf(code, err.data);

  // 网络层：没有信封，只有 fail 回调（小程序 wx.request 的 fail）。
  if (code === null) {
    return { text: NETWORK_COPY, code: null, traceId: traceId, kind: 'network', reasons: [] };
  }
  if (isReplay(code)) {
    // 幂等命中：服务端返回的是**首次处理结果**，属成功路径。
    return { text: '', code: code, traceId: traceId, kind: 'replay', reasons: reasons };
  }
  if (code === 1002) {
    return { text: CODE_COPY[1002], code: code, traceId: traceId, kind: 'auth', reasons: reasons };
  }
  var text = Object.prototype.hasOwnProperty.call(CODE_COPY, code)
    ? CODE_COPY[code]
    : UNKNOWN_COPY;
  return {
    text: text || UNKNOWN_COPY,
    code: code,
    traceId: traceId,
    kind: code >= 9001 ? 'server' : 'server',
    reasons: reasons,
  };
}

/** 需要重新登录的错误（页面据此清理本地凭证并跳登录页）。 */
function needRelogin(err) {
  return describe(err).kind === 'auth';
}

module.exports = {
  CODE_COPY: CODE_COPY,
  UNKNOWN_COPY: UNKNOWN_COPY,
  NETWORK_COPY: NETWORK_COPY,
  describe: describe,
  needRelogin: needRelogin,
  isReplay: isReplay,
};