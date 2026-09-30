/**
 * GENERATED FILE — DO NOT EDIT.
 *
 * 真源: contract/sdk-generator/_cut/client-mp.openapi.yaml
 * 生成: frontends/tools/gen-endpoints.py
 * 重跑: python frontends/tools/gen-endpoints.py
 * 校验: python frontends/tools/gen-endpoints.py --check
 *
 * 这一份是【端 端 C · 客户小程序】的可用端点清单，逐条机械转录自契约 ——
 * 一条 operation 属于本端，当且仅当它的 x-callable-roles 与
 * 本端 token-roles (client) 有交集。
 *
 * 🛑 手改本文件会在下次 --check 时被判红；要改请改契约后重跑生成器。
 * 🛑 本文件只回答「本端可以调用哪些端点」，不回答「某个角色看见哪些字段」
 *    —— 可见性永远由服务端 403 与 A2 档位解算决定（X-1）。
 */
'use strict';

const CONTRACT_VERSION = "api-contract-v1.0.0";

/**
 * 契约 servers[0].url（§2.0 Base Path）—— **三端拼 URL 的唯一前缀**。
 *
 * 🛑 出站 URL 必须写成 BASE + endpoint.path，不得只写 baseUrl + path：
 *    endpoint.path 是契约 paths 键（如 /auth/me），**不含** /api/v1；
 *    前缀由本常量承载。漏掉它 ⇒ 全量 404，且 tsc/构建/门禁全绿。
 */
const API_BASE_PATH = "/api/v1";

/**
 * 跨端协议片段（契约 x-api-protocol 的机械转录）—— **出站层一律引用本组常量**。
 *
 * 🛑 为什么不能在各端出站层手写字面量（本仓第 57 条）
 *    头名 / 令牌前缀 / 信封成功码此前在三个端各写一遍。
 *    `X-Trace-Id` 更彻底：契约里【一个字都没有】，只活在后端 TraceIdFilter
 *    与三端字面量里（实测 6 处）。契约或后端改一处 ⇒ 各处静默分叉 ⇒
 *    全量 401 / 幂等去重失效 / 留痕断链，而 tsc / 构建 / 门禁全绿。
 *    故本组常量是唯一来源，出站层必须用 PROTOCOL.*（由 build-check ④d 守）。
 */
const PROTOCOL = Object.freeze({
  AUTH_HEADER: "Authorization",
  AUTH_SCHEME: "Bearer",
  TENANT_HEADER: "X-Tenant-Id",
  TRACE_HEADER: "X-Trace-Id",
  IDEMPOTENCY_HEADER: "Idempotency-Key",
  ENVELOPE_FIELDS: Object.freeze(["code", "message", "data", "trace_id"]),
  ENVELOPE_OK_CODE: 0,
  PAGINATION: Object.freeze({
    PAGE_FIELD: "page",
    PAGE_SIZE_FIELD: "page_size",
    PAGE_MIN: 1,
    PAGE_SIZE_MIN: 1,
    PAGE_SIZE_MAX: 100,
    PAGE_SIZE_DEFAULT: 20,
    OVER_RANGE_POLICY: "reject-400",
    OVER_RANGE_ERROR: "VALIDATION_FAILED",
  }),
  // 拒绝响应的 data 载荷字段名（第 61 条）—— 「不得模糊报错」的机器可读那一半。
  // 错误层必须用 ERROR_DATA_FIELDS[code] 取字段名，不得手写 'missing_items'。
  ERROR_DATA_FIELDS: Object.freeze({
    2002: "missing_items",
    2001: "denied_fields",
  }),
});

const END_TOKEN_ROLES = Object.freeze(["client"]);

/** 契约 x-roles 的 token-role 展开表（本端相关项）。 */
const ROLE_EXPANSION = Object.freeze({
  "client": {
    tokens: Object.freeze(["client"]),
    end: "mp",
    display: "客户（小程序）",
  },
});

const ENDPOINTS = Object.freeze([
  {
    id: "authLogin",
    row: "A1",
    method: "POST",
    path: "/auth/login",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "authMe",
    row: "A2",
    method: "GET",
    path: "/auth/me",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "reportBandAvailableDates",
    row: "E5",
    method: "POST",
    path: "/band/available-dates",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "reportBandSyncBatch",
    row: "E1",
    method: "POST",
    path: "/band/sync-batches",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "upsertBandTelemetry",
    row: "E2",
    method: "POST",
    path: "/band/telemetry",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "getCustomer",
    row: "B4",
    method: "GET",
    path: "/customers/{id}",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "getAssessment",
    row: "C3",
    method: "GET",
    path: "/customers/{id}/assessments/{assessment_id}",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "getBandSyncStatus",
    row: "E6",
    method: "GET",
    path: "/customers/{id}/band/sync-status",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "getBandTelemetry",
    row: "E3",
    method: "GET",
    path: "/customers/{id}/band/telemetry",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "listDailyReports",
    row: "D4",
    method: "GET",
    path: "/customers/{id}/daily-reports",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "submitDailyReport",
    row: "D3",
    method: "POST",
    path: "/customers/{id}/daily-reports",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "getIntakeProfile",
    row: "B5",
    method: "GET",
    path: "/customers/{id}/intake-profile",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "listVisits",
    row: "D2",
    method: "GET",
    path: "/customers/{id}/visits",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "getPlan",
    row: "D5-b",
    method: "GET",
    path: "/plans/{id}",
    grantedRoles: Object.freeze(["client"]),
  },
  {
    id: "listScaleItemBanks",
    row: "C1",
    method: "GET",
    path: "/scale-item-banks",
    grantedRoles: Object.freeze(["client"]),
  },
]);

const ENDPOINT_IDS = Object.freeze(ENDPOINTS.map(function (e) { return e.id; }));

function endpointById(id) {
  for (let i = 0; i < ENDPOINTS.length; i += 1) {
    if (ENDPOINTS[i].id === id) { return ENDPOINTS[i]; }
  }
  return null;
}

module.exports = {
  CONTRACT_VERSION,
  API_BASE_PATH,
  PROTOCOL,
  END_TOKEN_ROLES,
  ROLE_EXPANSION,
  ENDPOINTS,
  ENDPOINT_IDS,
  endpointById,
};
