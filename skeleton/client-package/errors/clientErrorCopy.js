/**
 * Client error copy.
 *
 * The server-side ErrorCode.message is written for developers and MUST NOT be
 * rendered to the customer (compliance red line, competitor-analysis H.1-11).
 * The client therefore carries its own code -> copy mapping, and every string
 * in it is scanned by the ADR-12 faces.
 *
 * Note the absence of anything that explains a business decision to the
 * customer. A failure state on this surface describes the *technical* state and
 * then offers an actionable next step; it never attributes the state to the
 * customer and never announces an outcome.
 */
'use strict';

const CLIENT_ERROR_COPY = Object.freeze({
  1002: { key: 'error.unauthenticated', text: '登录状态已过期，请重新登录' },
  2001: { key: 'error.visibility_denied', text: '当前账号无权查看该信息' },
  2002: { key: 'error.gate_missing', text: '当前门店尚未开通该功能' },
  3001: { key: 'error.not_found', text: '未找到对应记录' },
  4001: { key: 'error.version_conflict', text: '该记录已被更新，请刷新后重试' },
  4002: { key: 'error.idempotent_replay', text: '该请求已受理，请勿重复提交' },
  5001: { key: 'error.business_rule', text: '当前条件下无法提交，请联系门店' },
  6001: { key: 'error.rate_limited', text: '操作过于频繁，请稍后再试' },
  9001: { key: 'error.internal', text: '服务暂时不可用，请稍后再试' },
});

function clientErrorMessage(code) {
  const entry = CLIENT_ERROR_COPY[Number(code)];
  return entry ? entry.text : CLIENT_ERROR_COPY[9001].text;
}

module.exports = { CLIENT_ERROR_COPY, clientErrorMessage };