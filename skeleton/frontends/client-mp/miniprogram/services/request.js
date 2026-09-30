/**
 * 端 C · 客户小程序 —— 请求适配层
 * ============================================================================
 *
 * 🛑 为什么小程序不用生成的 SDK 直接发请求
 * ---------------------------------------------------------------------------
 * generator-matrix.yaml 对 client-mp 用的是 `javascript` 生成器，且其注释逐字
 * 写着「小程序无 Node fetch；请求适配器由端内实现（wx.request），此处只生成
 * 类型与路径常量」。所以 SDK 提供的是**形状**，请求通道必须端内自实现 —— 本文件
 * 就是那个实现，它只依赖微信的 `wx.request`。
 *
 * 🛑 出站白名单为什么用【生成物】而不是手抄一份
 * ---------------------------------------------------------------------------
 * 本仓 `client-package/api/clientPaths.js` 的文件头记了一次真实失败：手写的
 * "客户端可达路径清单"会腐烂（原 8 条只有 2 条与冻结契约相符）。腐烂的形态有
 * 两种 —— **错名**（把契约不允许的路径写成允许）和**漏项**（契约允许但没列上）。
 * 漏项尤其隐蔽：白名单是运行时强制的，漏一条 = 客户该功能直接不可用，而门禁
 * 当时只校验"引注为真"、不校验"契约允许的都列上了"，于是**长期不红**。
 *
 * 故本端不手抄清单：`call()` 出站前用 `contract/endpoints.js`（由
 * `frontends/tools/gen-endpoints.py` 从冻结契约机械转录，带 `--check` 自证）
 * 校验 operationId 是否属于本端。契约一改，重跑生成器即同步；忘了重跑，
 * `npm run build` 的 `--check` 会红。
 *
 * 幂等与留痕
 * ---------------------------------------------------------------------------
 * 后端 `dy-web` 的幂等拦截器要求写请求带 `Idempotency-Key`（E1 同步批次 / E2 遥测
 * 上行都是写）。本层对非 GET 自动附带该头；键由调用方显式传入优先 —— 因为
 * "重试同一笔业务"必须是同一个键，"两笔不同的业务"必须不同，这个判断只有业务
 * 代码知道，本层不代猜。
 *
 * 🛑 可见性不属于本层
 * --------------------
 * 本层只负责"这一端可以调哪些端点"。某个角色看见哪些字段，永远由服务端 403 与
 * A2 档位解算决定（X-1）—— 前端不渲染 ≠ 安全，故本文件**不做**任何字段裁剪。
 */

'use strict';

var contract = require('../contract/endpoints.js');

var ENV = require('../env.js');

function isAllowedOperation(id) {
  return contract.ENDPOINT_IDS.indexOf(id) !== -1;
}

function fillPath(path, params) {
  var out = path;
  var keys = Object.keys(params || {});
  for (var i = 0; i < keys.length; i += 1) {
    var k = keys[i];
    out = out.replace('{' + k + '}', encodeURIComponent(String(params[k])));
  }
  return out;
}

function newIdempotencyKey() {
  // 小程序无 crypto.randomUUID；用时间戳 + 随机串构造一个足够分散的键。
  return 'mp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
}

/**
 * 调用一个契约允许的 operation。
 *
 * @param {string} operationId  contract/endpoints.js 里的 id（如 'submitDailyReport'）
 * @param {object} opts         { params, query, body, idempotencyKey, header }
 * @returns {Promise<{statusCode:number, data:object, traceId:string}>}
 */
function call(operationId, opts) {
  opts = opts || {};

  // ① 出站前的角色准入校验 —— 用生成物，不用手抄清单
  if (!isAllowedOperation(operationId)) {
    return Promise.reject(new Error(
      'contract: operation not granted to this client: ' + operationId
      + '\n若契约确实新增了该端点，请重跑 frontends/tools/gen-endpoints.py。'
    ));
  }

  var endpoint = contract.endpointById(operationId);
  // 🛑 URL = 出站前缀（网关根 + 契约 Base Path /api/v1） + 契约 paths 键。
  //    `endpoint.path` **不含** `/api/v1`；前缀由 ENV.requestBaseUrl 从生成物取。
  //    曾写成 `ENV.baseUrl` ⇒ 全量 404，且 build 自检全绿（第 56 条）。
  var url = ENV.requestBaseUrl + fillPath(endpoint.path, opts.params);
  var query = opts.query || {};
  var qsKeys = Object.keys(query);
  if (qsKeys.length) {
    var parts = [];
    for (var q = 0; q < qsKeys.length; q += 1) {
      parts.push(encodeURIComponent(qsKeys[q]) + '=' + encodeURIComponent(String(query[qsKeys[q]])));
    }
    url += (url.indexOf('?') === -1 ? '?' : '&') + parts.join('&');
  }

  var header = Object.assign({ 'Content-Type': 'application/json' }, opts.header || {});
  var token = wx.getStorageSync('token');
  // 🛑 头名与令牌前缀取自【生成物常量】（契约 x-api-protocol 的机械转录），
  //    不得在本文件手写字面量 —— 第 57 条：跨端协议片段只散在注释/字面量里，
  //    改一处即静默分叉，而构建自检/门禁全绿。
  if (token) {
    header[contract.PROTOCOL.AUTH_HEADER] = contract.PROTOCOL.AUTH_SCHEME + ' ' + token;
  }
  if (endpoint.method !== 'GET') {
    // 后端幂等拦截器：写请求必须带幂等头（E1/E2 等上报类端点）。
    header[contract.PROTOCOL.IDEMPOTENCY_HEADER] = opts.idempotencyKey || newIdempotencyKey();
  }

  return new Promise(function (resolve, reject) {
    wx.request({
      url: url,
      method: endpoint.method,
      data: endpoint.method === 'GET' ? undefined : opts.body,
      header: header,
      timeout: ENV.timeoutMs,
      success: function (res) {
        var body = res.data || {};
        // 契约 §2.0：信封恒含 trace_id（失败信封不含 data）。
        // 留它与服务端日志双向检索（dy-web GlobalExceptionHandler 分级留痕）。
        // 🛑 留痕头名取自生成物常量：`X-Trace-Id` 此前在契约里【零声明】，
        //    只活在后端 TraceIdFilter 与三端字面量里（第 57 条实测 6 处）。
        var traceId = body && body.trace_id
          ? body.trace_id
          : ((res.header && res.header[contract.PROTOCOL.TRACE_HEADER]) || '');
        // 🛑 成功码取自生成物常量（契约 §2.0「code != 0 时 data 为空」）。
        if (res.statusCode >= 200 && res.statusCode < 300
            && body.code === contract.PROTOCOL.ENVELOPE_OK_CODE) {
          resolve({ statusCode: res.statusCode, data: body.data, traceId: traceId });
          return;
        }
        var err = new Error('request failed: ' + (body && body.message ? body.message : res.statusCode));
        err.code = body && body.code;
        err.statusCode = res.statusCode;
        err.traceId = traceId;
        // 🛑 错误路径也必须把 `body.data` 带出来（本仓第 61 条）——
        //    契约「不得模糊报错」的机器可读那一半装在 data 里（2001/2002）。
        //    端 C 是客户视角，中性文案不得出现内部概念，故本层只【透传】
        //    字段名由中性层按 `contract.PROTOCOL.ERROR_DATA_FIELDS` 取用；
        //    是否展示给客户由中性层与页面决定（不在这里做措辞判断）。
        err.data = body && body.data;
        reject(err);
      },
      fail: function (err) {
        reject(err);
      },
    });
  });
}

module.exports = { call: call, isAllowedOperation: isAllowedOperation, newIdempotencyKey: newIdempotencyKey };
