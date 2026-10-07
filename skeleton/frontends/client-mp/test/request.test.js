'use strict';
const test = require('node:test');
const assert = require('node:assert');

// 🛑 小程序全局 wx 在 Node 不存在；用 Proxy 钉成「token 取得到 + 其余调用 noop」，
// 专验 request 层是否真的用「生成物常量」而非手写字面量。
global.wx = new Proxy(
  { getStorageSync: function () { return 'mp-tok'; } },
  {
    get: function (t, p) {
      return (p in t) ? t[p] : function () { return { miniProgram: { envVersion: 'release' } }; };
    },
  }
);

const request = require('../miniprogram/services/request.js');
const ENV = require('../miniprogram/env.js');
const contract = require('../miniprogram/contract/endpoints.js');

// 🛑 骨架把三环境基址都留 null（真实域名由运维下发）——env.js 取值即显式报错，
// 这是有意的 fail-closed。node 冒烟里须显式注入，否则 requestBaseUrl 抛错。
// node 宿主无 __wxConfig，currentEnv() 落到 'develop'。
ENV.setBaseUrl('develop', 'https://unit.test');

function installCapture() {
  let captured = null;
  global.wx.request = function (opts) { captured = opts; };
  return {
    get opts() { return captured; },
    succeed: function (res) { captured.success(res); },
  };
}

test('isAllowedOperation 白名单', () => {
  assert.equal(request.isAllowedOperation('authLogin'), true);
  assert.equal(request.isAllowedOperation('nope'), false);
});

test('newIdempotencyKey 前缀 mp-（本端标识）', () => {
  assert.ok(request.newIdempotencyKey().startsWith('mp-'));
});

test('call GET 成功：前缀 + Bearer + 无幂等键 + 信封解析', async () => {
  const cap = installCapture();
  const p = request.call('getCustomer', { params: { id: 'c1' } });
  cap.succeed({ statusCode: 200, data: { code: 0, data: { id: 'c1', name: '张三' }, trace_id: 't1' }, header: {} });
  const r = await p;
  assert.equal(r.data.id, 'c1');
  assert.ok(cap.opts.url.startsWith(ENV.requestBaseUrl));
  assert.ok(cap.opts.url.includes('/customers/c1'));
  assert.equal(cap.opts.header[contract.PROTOCOL.AUTH_HEADER], 'Bearer mp-tok');
  assert.equal(cap.opts.header[contract.PROTOCOL.IDEMPOTENCY_HEADER], undefined);
});

test('call POST 成功：带 Idempotency-Key（后端幂等拦截器要求）', async () => {
  const cap = installCapture();
  const p = request.call('submitDailyReport', {
    params: { id: 'c1' },
    body: { answers_json: '{}', date: '2026-10-07', source: 'mini' },
  });
  cap.succeed({ statusCode: 200, data: { code: 0, data: { id: 'd1' }, trace_id: 't2' }, header: {} });
  await p;
  assert.notEqual(cap.opts.header[contract.PROTOCOL.IDEMPOTENCY_HEADER], undefined);
});

test('call 错误：带 code + data（不得模糊报错）', async () => {
  const cap = installCapture();
  const p = request.call('getCustomer', { params: { id: 'x' } });
  cap.succeed({
    statusCode: 403,
    data: { code: 2001, message: 'denied', data: { denied_fields: ['age'] } },
    header: {},
  });
  await assert.rejects(p, (err) => {
    return err.code === 2001 && err.data && err.data.denied_fields[0] === 'age';
  });
});

test('未授权 operation 直接 reject（白名单之外）', async () => {
  await assert.rejects(request.call('nope', {}), /not granted/);
});
