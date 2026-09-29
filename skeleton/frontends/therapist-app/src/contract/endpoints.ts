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
  },
  {
    id: "authMe",
    row: "A2",
    method: "GET",
    path: "/auth/me",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "createCustomer",
    row: "B2",
    method: "POST",
    path: "/customers",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "getCustomer",
    row: "B4",
    method: "GET",
    path: "/customers/{id}",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "submitBaselineAssessment",
    row: "C2",
    method: "POST",
    path: "/customers/{id}/assessments/baseline",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "getAssessment",
    row: "C3",
    method: "GET",
    path: "/customers/{id}/assessments/{assessment_id}",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "getBandDerived",
    row: "E4",
    method: "GET",
    path: "/customers/{id}/band/derived",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "getBandTelemetry",
    row: "E3",
    method: "GET",
    path: "/customers/{id}/band/telemetry",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "signConsent",
    row: "B3",
    method: "POST",
    path: "/customers/{id}/consents",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "submitCycleAssessment",
    row: "C4",
    method: "POST",
    path: "/customers/{id}/cycle-assessments",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "listDailyReports",
    row: "D4",
    method: "GET",
    path: "/customers/{id}/daily-reports",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "submitDailyReport",
    row: "D3",
    method: "POST",
    path: "/customers/{id}/daily-reports",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "getIntakeProfile",
    row: "B5",
    method: "GET",
    path: "/customers/{id}/intake-profile",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "patchIntakeProfile",
    row: "B6",
    method: "PATCH",
    path: "/customers/{id}/intake-profile",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "listVerdicts",
    row: "F2",
    method: "GET",
    path: "/customers/{id}/verdicts",
    grantedRoles: Object.freeze(["meridian"]),
  },
  {
    id: "listVisits",
    row: "D2",
    method: "GET",
    path: "/customers/{id}/visits",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "createVisit",
    row: "D1",
    method: "POST",
    path: "/customers/{id}/visits",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "createVerdict",
    row: "F1",
    method: "POST",
    path: "/cycle-assessments/{id}/verdicts",
    grantedRoles: Object.freeze(["meridian"]),
  },
  {
    id: "createDeviceDispatch",
    row: "D6",
    method: "POST",
    path: "/device-dispatches",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "createPlan",
    row: "D5-a",
    method: "POST",
    path: "/plans",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "getPlan",
    row: "D5-b",
    method: "GET",
    path: "/plans/{id}",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "reviewPlan",
    row: "D5-c",
    method: "POST",
    path: "/plans/{id}/reviews",
    grantedRoles: Object.freeze(["meridian"]),
  },
  {
    id: "createRefund",
    row: "G1",
    method: "POST",
    path: "/refunds",
    grantedRoles: Object.freeze(["meridian"]),
  },
  {
    id: "getRefund",
    row: "G2",
    method: "GET",
    path: "/refunds/{id}",
    grantedRoles: Object.freeze(["meridian"]),
  },
  {
    id: "createRefundReceipt",
    row: "G5",
    method: "POST",
    path: "/refunds/{id}/receipts",
    grantedRoles: Object.freeze(["meridian"]),
  },
  {
    id: "createRetention",
    row: "G3",
    method: "POST",
    path: "/refunds/{id}/retentions",
    grantedRoles: Object.freeze(["meridian"]),
  },
  {
    id: "listScaleItemBanks",
    row: "C1",
    method: "GET",
    path: "/scale-item-banks",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "createScreeningRecord",
    row: "B1",
    method: "POST",
    path: "/screening-records",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
  },
  {
    id: "listStores",
    row: "A3",
    method: "GET",
    path: "/stores",
    grantedRoles: Object.freeze(["therapist", "meridian"]),
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
