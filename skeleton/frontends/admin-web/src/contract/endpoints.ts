/**
 * GENERATED FILE — DO NOT EDIT.
 *
 * 真源: contract/sdk-generator/_cut/admin-web.openapi.yaml
 * 生成: frontends/tools/gen-endpoints.py
 * 重跑: python frontends/tools/gen-endpoints.py
 * 校验: python frontends/tools/gen-endpoints.py --check
 *
 * 这一份是【端 端 A · 管理员 Web】的可用端点清单，逐条机械转录自契约 ——
 * 一条 operation 属于本端，当且仅当它的 x-callable-roles 与
 * 本端 token-roles (admin) 有交集。
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
}

export const END_TOKEN_ROLES: readonly string[] = Object.freeze([
  "admin",
]);

export const ENDPOINTS: readonly Endpoint[] = Object.freeze([
  {
    id: "getAuditCoverage",
    row: "F4",
    method: "GET",
    path: "/audit/coverage",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "listAuditSignals",
    row: "F3",
    method: "GET",
    path: "/audit/signals",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "authLogin",
    row: "A1",
    method: "POST",
    path: "/auth/login",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "authMe",
    row: "A2",
    method: "GET",
    path: "/auth/me",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createCustomer",
    row: "B2",
    method: "POST",
    path: "/customers",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "getCustomer",
    row: "B4",
    method: "GET",
    path: "/customers/{id}",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "submitBaselineAssessment",
    row: "C2",
    method: "POST",
    path: "/customers/{id}/assessments/baseline",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "getAssessment",
    row: "C3",
    method: "GET",
    path: "/customers/{id}/assessments/{assessment_id}",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "getBandDerived",
    row: "E4",
    method: "GET",
    path: "/customers/{id}/band/derived",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "getBandTelemetry",
    row: "E3",
    method: "GET",
    path: "/customers/{id}/band/telemetry",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "signConsent",
    row: "B3",
    method: "POST",
    path: "/customers/{id}/consents",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "submitCycleAssessment",
    row: "C4",
    method: "POST",
    path: "/customers/{id}/cycle-assessments",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "listDailyReports",
    row: "D4",
    method: "GET",
    path: "/customers/{id}/daily-reports",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "submitDailyReport",
    row: "D3",
    method: "POST",
    path: "/customers/{id}/daily-reports",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "getIntakeProfile",
    row: "B5",
    method: "GET",
    path: "/customers/{id}/intake-profile",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "patchIntakeProfile",
    row: "B6",
    method: "PATCH",
    path: "/customers/{id}/intake-profile",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "listVerdicts",
    row: "F2",
    method: "GET",
    path: "/customers/{id}/verdicts",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "listVisits",
    row: "D2",
    method: "GET",
    path: "/customers/{id}/visits",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createVisit",
    row: "D1",
    method: "POST",
    path: "/customers/{id}/visits",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createVerdict",
    row: "F1",
    method: "POST",
    path: "/cycle-assessments/{id}/verdicts",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createDeviceDispatch",
    row: "D6",
    method: "POST",
    path: "/device-dispatches",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "listDocTemplates",
    row: "I1",
    method: "GET",
    path: "/doc-templates",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createDocTemplate",
    row: "I2",
    method: "POST",
    path: "/doc-templates",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "uploadDocTemplate",
    row: "I3",
    method: "POST",
    path: "/doc-templates/uploads",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "downloadDocTemplate",
    row: "I7",
    method: "GET",
    path: "/doc-templates/{id}/download",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "listDocTemplateVersions",
    row: "I5",
    method: "GET",
    path: "/doc-templates/{id}/versions",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createDocTemplateVersion",
    row: "I4",
    method: "POST",
    path: "/doc-templates/{id}/versions",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "publishDocTemplateVersion",
    row: "I6",
    method: "POST",
    path: "/doc-templates/{id}/versions/{version}/publish",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createPlan",
    row: "D5-a",
    method: "POST",
    path: "/plans",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "getPlan",
    row: "D5-b",
    method: "GET",
    path: "/plans/{id}",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "reviewPlan",
    row: "D5-c",
    method: "POST",
    path: "/plans/{id}/reviews",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createRefund",
    row: "G1",
    method: "POST",
    path: "/refunds",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "getRefund",
    row: "G2",
    method: "GET",
    path: "/refunds/{id}",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "approveRefund",
    row: "G4",
    method: "POST",
    path: "/refunds/{id}/approvals",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createRefundReceipt",
    row: "G5",
    method: "POST",
    path: "/refunds/{id}/receipts",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createRetention",
    row: "G3",
    method: "POST",
    path: "/refunds/{id}/retentions",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "listScaleItemBanks",
    row: "C1",
    method: "GET",
    path: "/scale-item-banks",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "createScreeningRecord",
    row: "B1",
    method: "POST",
    path: "/screening-records",
    grantedRoles: Object.freeze(["admin"]),
  },
  {
    id: "listStores",
    row: "A3",
    method: "GET",
    path: "/stores",
    grantedRoles: Object.freeze(["admin"]),
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
