/**
 * GENERATED FILE — DO NOT EDIT.
 *
 * 真源: contract/sdk-generator/_cut/therapist-app.openapi.yaml
 * 生成: frontends/tools/gen-endpoints.py
 * 重跑: python frontends/tools/gen-endpoints.py
 * 校验: python frontends/tools/gen-endpoints.py --check
 *
 * 这一份是【端 端 B · 调理师 / 经络师 APP】的可用端点清单，逐条机械转录自契约 ——
 * 一条 operation 属于本端，当且仅当它的 x-callable-roles 与
 * 本端 token-roles (therapist, meridian) 有交集。
 *
 * 🛑 手改本文件会在下次 --check 时被判红；要改请改契约后重跑生成器。
 * 🛑 本文件只回答「本端可以调用哪些端点」，不回答「某个角色看见哪些字段」
 *    —— 可见性永远由服务端 403 与 A2 档位解算决定（X-1）。
 */

export const CONTRACT_VERSION = "api-contract-v1.0.0";

/**
 * 契约 servers[0].url（§2.0 Base Path）—— **三端拼 URL 的唯一前缀**。
 *
 * 🛑 出站 URL 必须写成 API_BASE_PATH + endpoint.path：
 *    endpoint.path 是契约 paths 键（如 /auth/me），**不含** /api/v1；
 *    前缀由本常量承载。漏掉它 ⇒ 全量 404，且 tsc/构建/门禁全绿。
 */
export const API_BASE_PATH: string = "/api/v1";

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
export interface ProtocolSpec {
  /** 鉴权头名（契约 x-api-protocol.auth-header）。 */
  readonly AUTH_HEADER: string;
  /** 令牌前缀（契约 x-api-protocol.auth-scheme）—— 拼 `${SCHEME} ${token}`。 */
  readonly AUTH_SCHEME: string;
  /** 租户一致性校验头（服务端仅校验、不采纳其值）。 */
  readonly TENANT_HEADER: string;
  /** 留痕头 —— 与响应体 trace_id 并存，用于日志双向检索。 */
  readonly TRACE_HEADER: string;
  /** 幂等键请求头名（写请求必带）。 */
  readonly IDEMPOTENCY_HEADER: string;
  /** 响应信封字段全集。 */
  readonly ENVELOPE_FIELDS: readonly string[];
  /** 信封成功码 —— 契约 §2.0 逐字「code != 0 时 data 为空」，故成功码为 0。 */
  readonly ENVELOPE_OK_CODE: number;
  /** 分页协议（契约 x-api-protocol.pagination）—— 上下界与【越界处置】。 */
  readonly PAGINATION: PaginationSpec;
  /**
   * 拒绝响应的 data 载荷字段名（契约 x-api-protocol.error-data-fields，第 61 条）。
   *
   * 🛑 「不得模糊报错」有两半：`message` 是给人读的，**本表是给机器读的**。
   *    三端出站层此前在错误路径丢弃 `body.data`、错误层也从不读这两个字段名 ⇒
   *    用户只看到静态文案，「缺失项 / 被拒字段到底是哪些」在客户端【完全没有到达】。
   *    故错误层必须 `err.data[PROTOCOL.ERROR_DATA_FIELDS[code]]` 取，不得手写字面量。
   */
  readonly ERROR_DATA_FIELDS: Readonly<Record<number, string>>;
}

export interface PaginationSpec {
  /** 页码参数名（契约 pagination.request-fields[0]）—— 出站拼接用。 */
  readonly PAGE_FIELD: string;
  /** 每页条数参数名（契约 pagination.request-fields[1]）。 */
  readonly PAGE_SIZE_FIELD: string;
  /** page 下界（< 该值一律 400，不静默纠正）。 */
  readonly PAGE_MIN: number;
  /** page_size 下界。 */
  readonly PAGE_SIZE_MIN: number;
  /** page_size 上界（> 该值一律 400，不夹逼 —— 唯一合法处置见 OVER_RANGE_POLICY）。 */
  readonly PAGE_SIZE_MAX: number;
  /** 未传 page_size 时的缺省值。 */
  readonly PAGE_SIZE_DEFAULT: number;
  /** 越界处置。本仓唯一合法取值 'reject-400'（越界直接拒，不得静默夹逼）。 */
  readonly OVER_RANGE_POLICY: 'reject-400';
  /** 越界对应的错误码名（契约 pagination.over-range-error）。 */
  readonly OVER_RANGE_ERROR: string;
}

export const PROTOCOL: ProtocolSpec = Object.freeze({
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
  } as PaginationSpec),
  ERROR_DATA_FIELDS: Object.freeze({
    2002: "missing_items",
    2001: "denied_fields",
  }) as Readonly<Record<number, string>>,
});

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface Endpoint {
  readonly id: string;
  readonly row: string;
  readonly method: HttpMethod;
  readonly path: string;
  readonly grantedRoles: readonly string[];
  /** 契约 x-row-scope：行级范围随 admin 子档位变化（缺省 = 契约未声明）。 */
  readonly rowScope?: string;
  /** 契约 x-super-admin-only：仅超管（tenant 级）。 */
  readonly superAdminOnly?: boolean;
  /** 契约 x-ruling-pending：取值系推断、**待裁定** —— 不得当定论实现。 */
  readonly rulingPending?: string;
  /** 契约 x-frontier：占位待冻结 —— 不得当已冻结契约用。 */
  readonly frontier?: string;
  /** 契约 x-idempotency-key：幂等键构成说明。 */
  readonly idempotencyKeySpec?: string;
  /**
   * 🛑 契约 required 的 query 参数名（本仓第 65 条）—— 调用点必须带上它们。
   *
   * 门禁此前只判「端点有没有被调用」，不判「实参对不对」⇒ 调用点可以
   * 漏掉必需参数、甚至给必需字段填臆造值，后端必然 400，而
   * tsc / 构建 / 全部门禁一律绿。故把它机械转录出来供判据核对。
   */
  readonly requiredQuery: readonly string[];
  /** 🛑 契约 requestBody schema 的 required 字段名（未声明 requestBody 时为空）。 */
  readonly requiredBody: readonly string[];
}

/** 契约角色由哪些 token-role 构成（逐条取自契约 x-roles）。 */
export interface RoleExpansion {
  readonly tokens: readonly string[];
  readonly end: string;
  readonly display: string;
}

/** 本端相关角色 → token-role 展开（契约 x-roles 的机械转录）。 */
export const ROLE_EXPANSION: Readonly<Record<string, RoleExpansion>> = Object.freeze({
  "meridian": {
    tokens: Object.freeze(["meridian"]),
    end: "app",
    display: "经络师（APP）",
  },
  "therapist": {
    tokens: Object.freeze(["therapist"]),
    end: "app",
    display: "调理师（APP）",
  },
});

export const END_TOKEN_ROLES: readonly string[] = Object.freeze([
  "therapist",
  "meridian",
]);

export const ENDPOINTS: readonly Endpoint[] = Object.freeze([
  {
    id: "authLogin",
    row: "A1",
    method: "POST",
    path: "/auth/login",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze(["account", "client_end", "credential"]),
  },
  {
    id: "authMe",
    row: "A2",
    method: "GET",
    path: "/auth/me",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "createCustomer",
    row: "B2",
    method: "POST",
    path: "/customers",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze(["age", "gender", "name", "phone", "screening_id"]),
  },
  {
    id: "getCustomer",
    row: "B4",
    method: "GET",
    path: "/customers/{id}",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "submitBaselineAssessment",
    row: "C2",
    method: "POST",
    path: "/customers/{id}/assessments/baseline",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze(["age_group_locked", "dimension_scores", "item_group_id", "measure_operator", "scale_id", "total_score"]),
  },
  {
    id: "getAssessment",
    row: "C3",
    method: "GET",
    path: "/customers/{id}/assessments/{assessment_id}",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "getBandDerived",
    row: "E4",
    method: "GET",
    path: "/customers/{id}/band/derived",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "getBandTelemetry",
    row: "E3",
    method: "GET",
    path: "/customers/{id}/band/telemetry",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "signConsent",
    row: "B3",
    method: "POST",
    path: "/customers/{id}/consents",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "submitCycleAssessment",
    row: "C4",
    method: "POST",
    path: "/customers/{id}/cycle-assessments",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze(["adherence", "cycle_id", "module_scores", "sequence_no"]),
  },
  {
    id: "listDailyReports",
    row: "D4",
    method: "GET",
    path: "/customers/{id}/daily-reports",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "submitDailyReport",
    row: "D3",
    method: "POST",
    path: "/customers/{id}/daily-reports",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze(["answers_json", "date", "source"]),
  },
  {
    id: "getIntakeProfile",
    row: "B5",
    method: "GET",
    path: "/customers/{id}/intake-profile",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "patchIntakeProfile",
    row: "B6",
    method: "PATCH",
    path: "/customers/{id}/intake-profile",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "listVerdicts",
    row: "F2",
    method: "GET",
    path: "/customers/{id}/verdicts",
    grantedRoles: Object.freeze(["meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "listVisits",
    row: "D2",
    method: "GET",
    path: "/customers/{id}/visits",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "createVisit",
    row: "D1",
    method: "POST",
    path: "/customers/{id}/visits",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "createVerdict",
    row: "F1",
    method: "POST",
    path: "/cycle-assessments/{id}/verdicts",
    grantedRoles: Object.freeze(["meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze(["adherence", "confidence", "core_metric_improved", "customer_id", "module_scores", "risk_flag", "same_origin", "sequence_no"]),
  },
  {
    id: "createDeviceDispatch",
    row: "D6",
    method: "POST",
    path: "/device-dispatches",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "createPlan",
    row: "D5-a",
    method: "POST",
    path: "/plans",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "getPlan",
    row: "D5-b",
    method: "GET",
    path: "/plans/{id}",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "reviewPlan",
    row: "D5-c",
    method: "POST",
    path: "/plans/{id}/reviews",
    grantedRoles: Object.freeze(["meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "createRefund",
    row: "G1",
    method: "POST",
    path: "/refunds",
    grantedRoles: Object.freeze(["meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze(["customer_id", "entry", "reason_code", "refund_route", "requested_at"]),
  },
  {
    id: "getRefund",
    row: "G2",
    method: "GET",
    path: "/refunds/{id}",
    grantedRoles: Object.freeze(["meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "createRefundReceipt",
    row: "G5",
    method: "POST",
    path: "/refunds/{id}/receipts",
    grantedRoles: Object.freeze(["meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "createRetention",
    row: "G3",
    method: "POST",
    path: "/refunds/{id}/retentions",
    grantedRoles: Object.freeze(["meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "listScaleItemBanks",
    row: "C1",
    method: "GET",
    path: "/scale-item-banks",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze(["age_group"]),
    requiredBody: Object.freeze([]),
  },
  {
    id: "createScreeningRecord",
    row: "B1",
    method: "POST",
    path: "/screening-records",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze(["customer_id", "items_json", "operator_id"]),
  },
  {
    id: "listStores",
    row: "A3",
    method: "GET",
    path: "/stores",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
    requiredQuery: Object.freeze([]),
    requiredBody: Object.freeze([]),
  },
]);

export const ENDPOINT_IDS: readonly string[] = Object.freeze(
  ENDPOINTS.map((e) => e.id),
);

export function endpointById(id: string): Endpoint | null {
  for (const e of ENDPOINTS) {
    if (e.id === id) { return e; }
  }
  return null;
}
