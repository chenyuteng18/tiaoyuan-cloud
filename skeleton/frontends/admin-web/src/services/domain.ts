/**
 * 端 A · 域服务层（39 端点，按契约机械映射）
 * ============================================================================
 *
 * 每一段的请求/响应字段**逐条抄自裁剪契约** `contract/sdk-generator/_cut/admin-web.openapi.yaml`
 * 的 `components.schemas`（27 个）与各 operation 的内联 requestBody。
 *
 * 🛑 端 A 与端 B 的域服务层共有同一套纪律，但本层有**三处端 A 特有**的东西
 * ---------------------------------------------------------------------------
 *   1. **未完结端点必须带出标注**：I 域 7 个端点契约逐字 `x-frontier: 占位待冻结`，
 *      G4 `approveRefund` 逐字 `x-ruling-pending`。本层的对应函数
 *      **注释里逐条写明**，且返回值类型上**不承诺任何字段形态**
 *      （契约说它是占位，替他承诺就是替契约做决定）。
 *      守这一条的是 `tools/a-check.mjs` 的 `unsettled-surfaced` 判据。
 *   2. **行级范围由服务端裁剪**：F3 / F4 两个端点契约声明的行级范围
 *      （门店负责人仅本店 / 区域督导仅辖区 / 总部全量）—— 本层**不筛行**。
 *   3. **同一合同行可能对应多个操作**（`D5` → D5-a/b/c）—— 本层按**操作**分段，
 *      不按行号分段（否则三个操作的字段会挤在一段里，无法分辨）。
 *
 * 🛑 本层【刻意不做】的四件事（做了才是缺陷）
 * ---------------------------------------------------------------------------
 *   1. **不裁剪字段** —— 契约 `x-global-conventions.visibility` 逐字：
 *      「无权限字段**不下发**（不是 null、不是空串）」，裁剪在服务端（X-1）。
 *   2. **不推算任何派生值** —— ④ 组（A3 / AS / effect_verdict）由服务端算。
 *   3. **不代填日期 / 不猜缺省值** —— 契约多处逐字「缺失 ⇒ 未定（不得默认）」。
 *   4. **不把幂等重放当失败** —— 4002 的语义是"此前已成功"。
 */

import { call } from '../api/client';
import { CONTRACT_VERSION, endpointById, type Endpoint } from '../contract/endpoints';
import {
  hasRowScope,
  isSuperAdminOnly,
  rowScopeOf,
  unsettledOf,
  type Unsettled,
} from '../contract/scope';

// ===========================================================================
// 域 A · 身份与合作门店（A1/A2 见 services/session.ts；本段 A3）
// ===========================================================================

/** 契约 `Store`。 */
export interface Store {
  readonly store_id: string;
  readonly name: string;
  /** 逐字抄契约枚举。 */
  readonly franchise_type: '直营' | '加盟';
}

/** 契约 `StoreListData`。 */
export interface StoreList {
  readonly items: readonly Store[];
  readonly total: number;
  readonly page: number;
  readonly page_size: number;
}

/**
 * A3 门店列表。
 * 🛑 行级范围（`x-row-scope`）由服务端执行 —— 本函数**不筛行**。
 *    同一调用在不同子档位下返回的行数不同（本店 / 辖区 / 全量），
 *    界面必须显示"当前范围"而不是自己过滤（自行过滤 = 造第二个裁剪点）。
 */
export function listStores(page = 1, pageSize = 50): Promise<StoreList | undefined> {
  return call<StoreList>('listStores', { query: { page, page_size: pageSize } }).then((r) => r.data);
}

// ===========================================================================
// 域 B · 客户建档（B1~B6）
// ===========================================================================

/** 契约 `ScreeningCreateRequest`。 */
export interface ScreeningRequest {
  readonly customer_id: string;
  /**
   * 契约逐字：「含 pregnancy / acute / risk_history / nonmedical_disclosed」。
   * ⚠️ 契约**只给了这四个键名**，未给每项取值形态（bool / 枚举未定义）
   *    ⇒ 本层不替契约决定，用宽松索引签名。这是**如实登记的缺口**。
   */
  readonly items_json: Record<string, unknown>;
  /** 契约逐字：「服务端从 token 覆写」—— 前端传什么都会被覆写，但仍须传（required）。 */
  readonly operator_id: string;
  readonly customer_sign?: string;
  readonly operator_sign?: string;
}

export interface ScreeningResult {
  readonly screening_id: string;
  readonly result: '通过' | '不通过';
  readonly submitted_at: string;
}

/** B1 禁忌筛查提交（硬门禁①）。 */
export function createScreeningRecord(
  body: ScreeningRequest,
  idempotencyKey?: string
): Promise<ScreeningResult | undefined> {
  return call<ScreeningResult>('createScreeningRecord', { body, idempotencyKey }).then((r) => r.data);
}

/** 契约 `CustomerCreateRequest`。 */
export interface CustomerCreateRequest {
  readonly name: string;
  readonly gender: '男' | '女';
  /** 契约约束：minimum 1 / maximum 119。 */
  readonly age: number;
  /** 契约逐字：「租户内唯一（跨店识别键）」。 */
  readonly phone: string;
  /** 契约逐字：「服务端校验其 result=通过」。 */
  readonly screening_id: string;
}

export interface CustomerCreateResult {
  readonly customer_id: string;
  readonly status: 'CREATED' | 'PROFILED' | 'CONSENTED' | 'REJECTED' | 'ARCHIVED';
  readonly owner_store_id?: string;
  readonly serving_store_id?: string;
}

/** B2 建档。 */
export function createCustomer(
  body: CustomerCreateRequest,
  idempotencyKey: string
): Promise<CustomerCreateResult | undefined> {
  return call<CustomerCreateResult>('createCustomer', { body, idempotencyKey }).then((r) => r.data);
}

/**
 * B3 签知情同意书。
 * ⚠️ 契约**未给命名 schema**，入参按 operation 内联定义传入 —— 如实登记。
 */
export function signConsent(
  customerId: string,
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<unknown> {
  return call('signConsent', { params: { id: customerId }, body, idempotencyKey }).then((r) => r.data);
}

/** 契约 `CustomerDetailData`。 */
export interface CustomerDetail {
  readonly customer_id: string;
  readonly name: string;
  readonly gender: string;
  readonly age: number;
  readonly intake_profile?: Record<string, unknown>;
  readonly screening_result?: string;
  readonly band_willingness?: '自愿佩戴' | '暂不佩戴';
  readonly owner_store_id?: string;
  readonly serving_store_id?: string;
  /** ④ 组（derived_result）：管理端可见。 */
  readonly effect_verdict?: string;
  readonly as_value?: number;
}

/** B4 客户详情（可见性裁剪重点接口 —— 裁剪在服务端）。 */
export function getCustomer(customerId: string): Promise<CustomerDetail | undefined> {
  return call<CustomerDetail>('getCustomer', { params: { id: customerId } }).then((r) => r.data);
}

/** B5 建档扩展档案。 */
export function getIntakeProfile(customerId: string): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('getIntakeProfile', { params: { id: customerId } }).then((r) => r.data);
}

/**
 * B6 补充 + 修订（**append-only 留痕，不可覆盖**）。
 * 🛑 契约逐字「不可覆盖」⇒ 本函数**只发 PATCH 增量**，不做整体覆盖式提交。
 */
export function patchIntakeProfile(
  customerId: string,
  patch: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('patchIntakeProfile', {
    params: { id: customerId },
    body: patch,
    idempotencyKey,
  }).then((r) => r.data);
}

// ===========================================================================
// 域 C · 量表与评估（C1~C4）
// ===========================================================================

/** 契约 `Dimension`（7 维，逐字）。 */
export const DIMENSIONS: readonly string[] = Object.freeze([
  '体能精力',
  '面部气色肤质',
  '肩颈腰背筋骨',
  '睡眠质量',
  '记忆专注',
  '代谢体态消化',
  '情绪抗压与抵抗力',
]);

/** 契约 `AgeGroup`（8 档，逐字）。 */
export const AGE_GROUPS: readonly string[] = Object.freeze([
  '男16-32', '男33-40', '男41-48', '男49以上',
  '女14-28', '女29-35', '女36-42', '女43-49以上',
]);

export interface ScaleItemBank {
  readonly bank_id?: string;
  readonly scale_id?: string;
  readonly item_group_id?: string;
  readonly age_group?: string;
  readonly dimension?: string;
  readonly [k: string]: unknown;
}

/** C1 题库拉取（按分龄组 + 维度）。 */
export function listScaleItemBanks(
  query: { age_group?: string; dimension?: string; page?: number; page_size?: number } = {}
): Promise<readonly ScaleItemBank[] | undefined> {
  return call<{ items?: readonly ScaleItemBank[] }>('listScaleItemBanks', { query })
    .then((r) => r.data?.items);
}

/** 契约 `BaselineAssessmentRequest`。 */
export interface BaselineRequest {
  readonly scale_id: string;
  readonly item_group_id: string;
  readonly age_group_locked: string;
  /** 契约约束：**minItems 7 / maxItems 7**，每项 0~16。 */
  readonly dimension_scores: readonly number[];
  /** 契约约束：0~112。 */
  readonly total_score: number;
  readonly measure_operator: string;
}

export interface BaselineResult {
  readonly assessment_id: string;
  readonly assessed_at: string;
  readonly baseline_conclusion?: { readonly haozhuan?: '好转' | '稳定' | '下降' };
  readonly migratable?: boolean;
  readonly dimension_scores?: readonly number[];
}

/**
 * C2 基线评估提交。
 * 🛑 提交前**本地先挡两个契约硬约束**（7 项 / ≤112）：这不是代替服务端校验
 *    （服务端仍会校验并回 5001），而是让使用者点提交前就看到"少填一个维度"。
 */
export function submitBaselineAssessment(
  customerId: string,
  body: BaselineRequest,
  idempotencyKey: string
): Promise<BaselineResult | undefined> {
  if (body.dimension_scores.length !== 7) {
    throw new Error(`C2 契约约束：dimension_scores 必须恰好 7 项（当前 ${body.dimension_scores.length} 项）。`);
  }
  if (body.total_score < 0 || body.total_score > 112) {
    throw new Error(`C2 契约约束：total_score 须在 0~112（当前 ${body.total_score}）。`);
  }
  return call<BaselineResult>('submitBaselineAssessment', {
    params: { id: customerId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

export interface AssessmentDetail {
  readonly assessment_id?: string;
  readonly dimension_scores?: readonly number[];
  readonly [k: string]: unknown;
}

/** C3 评估详情。 */
export function getAssessment(
  customerId: string,
  assessmentId: string
): Promise<AssessmentDetail | undefined> {
  return call<AssessmentDetail>('getAssessment', {
    params: { id: customerId, assessment_id: assessmentId },
  }).then((r) => r.data);
}

/** 依从四维（F1 与 C4 **共用同一形状** —— 契约明文要求）。 */
export interface Adherence {
  readonly expected_days?: number;
  /**
   * 契约逐字：「键为 A1/A3/A4；🛑 **A2 永不参与 AS**」。
   * 🛑 本层不提供任何"补全 A2"的便利方法。
   */
  readonly dimensions: Record<string, AdherenceDim>;
}

export interface AdherenceDim {
  readonly applicable: boolean;
  readonly value: number;
  readonly structural_missing: boolean;
}

/** 契约 `CreateCycleAssessmentRequest`。 */
export interface CycleRequest {
  /** 契约逐字：「周期评估主键（必填 —— 使 F1 能在同一行上补判定）」。 */
  readonly cycle_id: string;
  /** 契约约束：minimum 1；逐字「第 N 次评估（每 7 次触发）」。 */
  readonly sequence_no: number;
  readonly adherence: Adherence;
  readonly expected_days?: number;
  readonly module_scores: Record<string, number>;
  readonly band_trend_note?: string;
  /** 契约逐字：「断言位（可空 = 服务端填；非空须逐字等于服务端指纹）」。 */
  readonly threshold_version?: string;
}

/** C4 周期评估提交。 */
export function submitCycleAssessment(
  customerId: string,
  body: CycleRequest,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('submitCycleAssessment', {
    params: { id: customerId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

// ===========================================================================
// 域 D · 服务与方案（D1~D6）
// ===========================================================================

/** 契约 `VisitData`。 */
export interface Visit {
  readonly visit_id: string;
  readonly visit_no: number;
  readonly executed_at: string;
  readonly serving_store_id: string;
  readonly gate_check_json?: Record<string, unknown>;
  readonly customer_confirmed: boolean;
  readonly abnormal_note?: string;
}

/** D1 服务核销（四道闸门）。 */
export function createVisit(
  customerId: string,
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Visit | undefined> {
  return call<Visit>('createVisit', { params: { id: customerId }, body, idempotencyKey })
    .then((r) => r.data);
}

/** D2 服务记录（客户维度全局账本）。 */
export function listVisits(
  customerId: string,
  page = 1,
  pageSize = 50
): Promise<readonly Visit[] | undefined> {
  return call<{ items?: readonly Visit[] }>('listVisits', {
    params: { id: customerId },
    query: { page, page_size: pageSize },
  }).then((r) => r.data?.items);
}

/** 契约 `DailyReportData`。 */
export interface DailyReport {
  readonly report_id?: string;
  readonly date?: string;
  readonly weekly_count?: number;
  readonly last_week_compare?: Record<string, unknown>;
  readonly suggestion?: string;
  readonly source?: string;
}

/** D3 代录每日填报（**代录须标「代核」**，契约逐字）。 */
export function submitDailyReportAsStaff(
  customerId: string,
  body: { date: string; answers_json: Record<string, unknown> },
  idempotencyKey: string
): Promise<DailyReport | undefined> {
  return call<DailyReport>('submitDailyReport', {
    params: { id: customerId },
    body: { ...body, source: '代核' },
    idempotencyKey,
  }).then((r) => r.data);
}

/** D4 填报记录。 */
export function listDailyReports(
  customerId: string,
  page = 1,
  pageSize = 50
): Promise<readonly DailyReport[] | undefined> {
  return call<{ items?: readonly DailyReport[] }>('listDailyReports', {
    params: { id: customerId },
    query: { page, page_size: pageSize },
  }).then((r) => r.data?.items);
}

/**
 * D5-a 方案出具（回炉审核）。
 * 🛑 契约**未声明 requestBody** ⇒ 本函数用 `Record<string, unknown>` 并**不猜字段名**。
 *    与端 B 的 D5-c 同一个教训（第 50 条同族）：**契约空着的地方，
 *    编字段名会是静默失效**（tsc 不报、服务端走默认分支）。
 *    故此处如实留空，由调用侧提交时经页面表单**显式**声明。
 */
export function createPlan(
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('createPlan', { body, idempotencyKey }).then((r) => r.data);
}

/** D5-b 方案查阅。 */
export function getPlan(planId: string): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('getPlan', { params: { id: planId } }).then((r) => r.data);
}

/**
 * D5-c 方案审核（退回必填 reason）。
 *
 * 🛑 请求体字段名来自【后端控制器的真实接收形状】，不是自创
 * ---------------------------------------------------------------------------
 * 契约对 D5-c **没有声明 requestBody**，唯一可核对的真相源是控制器：
 * `PlanController.ReviewRequest` = `{result, reason, second_confirm}`。
 * ⚠️ 端 B 的对应函数**初版写成 `{decision, reason?}`** —— 自创字段名：
 *    tsc 不报错（`decision` 只是多一个键），服务端也**不报错**
 *    （`result` 为空 ⇒ 走"未给出审核结论"分支），
 *    表现为"提交成功但方案状态没变"—— 最坏的一类静默失效。
 *    端 A 此处直接采正确形状，并在**类型上是强类型**（不接受任意键）。
 */
export interface ReviewRequest {
  /** 审核结论。逐字取自 `PlanController.ReviewRequest.result`。 */
  readonly result: string;
  /** 退回必填（契约 summary 逐字：「退回必填 reason」）。 */
  readonly reason?: string;
  /** 二次确认（控制器为 `Boolean`）。 */
  readonly second_confirm?: boolean;
}

export function reviewPlan(
  planId: string,
  body: ReviewRequest,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('reviewPlan', {
    params: { id: planId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/** D6 设备参数下发（需方案已审核）。 */
export function createDeviceDispatch(
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('createDeviceDispatch', { body, idempotencyKey })
    .then((r) => r.data);
}

// ===========================================================================
// 域 E · 手环数据（E3 / E4）
// ===========================================================================

/** 契约 `BandTelemetryData`。 */
export interface BandTelemetry {
  readonly data_source?: '手环' | '未接入';
  readonly collected_days?: number;
  readonly synced_date?: string;
  readonly metrics?: readonly Record<string, unknown>[];
  readonly gap_reason?: 'no_open' | 'sync_failed' | 'not_worn' | 'compliant_removal'
    | 'involuntary_technical' | 'beyond_retention_window' | 'unknown';
}

/** E3 手环原始数据 + 采集状态。 */
export function getBandTelemetry(
  customerId: string,
  query: { device_id?: string; metric?: string; date?: string } = {}
): Promise<BandTelemetry | undefined> {
  return call<BandTelemetry>('getBandTelemetry', { params: { id: customerId }, query })
    .then((r) => r.data);
}

/** 契约 `BandDerivedData`（④ 组）。 */
export interface BandDerived {
  readonly a3_applicable?: boolean;
  readonly a3_value?: number;
  readonly as_value?: number;
  readonly effect_verdict?: 'E1显著改善' | 'E2部分改善' | 'E3稳定' | 'E4无明显改善' | 'E5加重';
  readonly refund_eligibility?: boolean;
}

/** E4 派生结果。 */
export function getBandDerived(customerId: string): Promise<BandDerived | undefined> {
  return call<BandDerived>('getBandDerived', { params: { id: customerId } }).then((r) => r.data);
}

// ===========================================================================
// 域 F · 判定与稽核（F1~F4）
// ===========================================================================

/** 契约 `CreateVerdictRequest`。 */
export interface VerdictRequest {
  readonly customer_id: string;
  readonly sequence_no: number;
  readonly base_total?: number;
  readonly current_total?: number;
  /**
   * 契约逐字：「同源断言（三项；**任一项缺省 = 不成立 = 不可比 ⇒ 挂起**）」。
   */
  readonly same_origin: {
    readonly same_item_group: boolean;
    readonly range_matches: boolean;
    readonly same_measurer: boolean;
  };
  readonly adherence: Adherence;
  /** 契约逐字：「🛑 缺失 ⇒ 未定（**不得默认「无」**）」。 */
  readonly risk_flag?: '无' | '高危' | '新发' | '同病';
  readonly core_metric_improved?: boolean;
  readonly confidence: Record<string, unknown>;
  readonly module_scores: Record<string, number>;
  readonly band_trend_note?: string;
  readonly threshold_version?: string;
}

/** 契约 `VerdictData`。 */
export interface Verdict {
  readonly verdict_id: string;
  readonly branch?: '稳定' | '依从不足' | '达标无效' | '全面评估' | '人工复核';
  readonly confidence?: number;
  readonly evidence_snapshot?: Record<string, unknown>;
  readonly threshold_version?: string;
  readonly decided_at?: string;
}

/** F1 判定结论落库。 */
export function createVerdict(
  cycleAssessmentId: string,
  body: VerdictRequest,
  idempotencyKey: string
): Promise<Verdict | undefined> {
  return call<Verdict>('createVerdict', {
    params: { id: cycleAssessmentId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/** F2 判定历史。 */
export function listVerdicts(
  customerId: string,
  page = 1,
  pageSize = 20
): Promise<readonly Verdict[] | undefined> {
  return call<{ items?: readonly Verdict[] }>('listVerdicts', {
    params: { id: customerId },
    query: { page, page_size: pageSize },
  }).then((r) => r.data?.items);
}

/** 契约 `AuditCoverageData`。 */
export interface AuditCoverage {
  readonly ecc_count?: number;
  readonly denominator_closed_courses?: number;
  /** 契约逐字：「可判定覆盖率」—— 分母是**已结课**，不是全部客户。 */
  readonly decidable_coverage_rate?: number;
  readonly a3_observability?: Record<string, unknown>;
}

/**
 * F3 稽核信号列表。
 * 🛑 契约 `x-row-scope` 逐字：「门店负责人仅本店、区域督导仅辖区、总部全量；
 *    **门店与加盟商完全不可见**」⇒ 行级范围由服务端执行，本层不筛。
 */
export function listAuditSignals(
  query: { page?: number; page_size?: number } = {}
): Promise<readonly Record<string, unknown>[] | undefined> {
  return call<{ items?: readonly Record<string, unknown>[] }>('listAuditSignals', { query })
    .then((r) => r.data?.items);
}

/**
 * F4 稽核覆盖率。
 * 🛑 契约 `x-row-scope` 逐字：「卡片可给门店（仅本店、三数同显）；
 *    告警动作归 P1-04、对门店不可见」⇒ 行级范围同由服务端执行。
 */
export function getAuditCoverage(): Promise<AuditCoverage | undefined> {
  return call<AuditCoverage>('getAuditCoverage').then((r) => r.data);
}

// ===========================================================================
// 域 G · 退款与挽留（G1~G5）
// ===========================================================================
//
// 🛑 本端【可以】直呼"退款"：`compliance/wordlists/scan1_refund.words` 的 SCOPE 段
//    逐字声明本面只扫客户端包；管理端 / 经络师端的退款措辞是**合法的内部业务词汇**。

/** 契约 `RefundCreateRequest`。 */
export interface RefundCreateRequest {
  readonly customer_id: string;
  /** 逐字枚举：入口 A 门店代录 / B 首周期。 */
  readonly entry: 'A门店代录' | 'B首周期';
  readonly refund_route: '履约类' | '效果类';
  /** 契约逐字：「必填；**未记录原因不可结案**」。6 值枚举逐字。 */
  readonly reason_code:
    | '效果未达预期' | '症状加重或出现新不适' | '服务体验或沟通问题'
    | '时间·经济·家庭原因' | '配合度不足导致无明显变化' | '信任或价格异议';
  /** 契约逐字：「最早且可核实」—— 这是**计时基准**。 */
  readonly requested_at: string;
  /** 契约逐字：「客户主张，**仅留存不计时**」。 */
  readonly requested_at_claimed?: string;
  /** 契约逐字：「append-only 原话」。 */
  readonly customer_statement?: string;
}

/** 契约 `RefundData`。 */
export interface Refund {
  readonly refund_id?: string;
  readonly liable_store_id?: string;
  readonly sla_due_at?: string;
  readonly recording_delay_h?: number;
  readonly outcome?: '继续' | '终止' | '归档';
}

/** G1 代录客户退款诉求。 */
export function createRefund(
  body: RefundCreateRequest,
  idempotencyKey: string
): Promise<Refund | undefined> {
  return call<Refund>('createRefund', { body, idempotencyKey }).then((r) => r.data);
}

/** G2 退款工单详情。 */
export function getRefund(refundId: string): Promise<Refund | undefined> {
  return call<Refund>('getRefund', { params: { id: refundId } }).then((r) => r.data);
}

/**
 * 挽留记录请求体（字段名来自控制器真实形状，契约未声明 requestBody）。
 * `RefundWorkOrderController.CreateRetentionRequest` =
 * `{attempts, script_version, result, analysis, communication}`。
 * `analysis` / `communication` 库层是 **JSONB**，控制器收 `JsonNode` ⇒ 传对象即可。
 */
export interface RetentionRequest {
  readonly attempts?: number;
  readonly script_version?: string;
  readonly result?: string;
  readonly analysis?: Record<string, unknown>;
  readonly communication?: Record<string, unknown>;
}

/**
 * G3 挽留记录。
 * 🛑 契约 summary 逐字：「挽留记录**必填**；入口 B 不经挽留」
 *    ⇒ 本函数要求 `body` 非空（空对象本地直接挡下，不发出去等被拒）。
 */
export function createRetention(
  refundId: string,
  body: RetentionRequest,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  if (Object.keys(body).length === 0) {
    throw new Error('G3 契约约束：挽留记录必填（不允许提交空记录）。');
  }
  return call<Record<string, unknown>>('createRetention', {
    params: { id: refundId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/**
 * G4 退款审批（**契约 `x-ruling-pending`：取值系推断、待裁定**）。
 *
 * 🛑 本端点的审批白名单**尚未由契约冻结**
 * ---------------------------------------------------------------------------
 * 契约逐字标 `x-ruling-pending`（取值系推断、待裁定）。含义是：
 * **功能可能已生效，但"谁能审批"这件事的依据是推断，裁定后可能变。**
 * 故本层：
 *   · **不硬编码任何审批人白名单**（硬编码 = 把一份推断变成代码事实）；
 *   · 不替契约承诺返回字段形态；
 *   · 界面必须显示"待裁定"标注（`UnsettledBar`），由 `a-check.mjs` 的
 *     `unsettled-surfaced` 判据守着。
 */
export function approveRefund(
  refundId: string,
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('approveRefund', {
    params: { id: refundId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/** 契约 `RefundReceiptData`（三态留痕，逐字）。 */
export interface RefundReceipt {
  readonly receipt_state?: '已推送' | '未授权（转线下）' | '推送失败';
}

/**
 * 回执请求体（字段名来自控制器真实形状）。
 * `RefundWorkOrderController.CreateReceiptRequest` =
 * `{subscription_quota, template_id, push_succeeded, failure_reason}`。
 * 🛑 `subscription_quota` 是**推送之前**判定的额度（≤0 ⇒「未授权（转线下）」）——
 *    顺序搞反会让"未授权"这一态在库里永不出现。本层不重排顺序，只如实传值。
 */
export interface RefundReceiptRequest {
  readonly subscription_quota?: number;
  readonly template_id?: string;
  readonly push_succeeded?: boolean;
  readonly failure_reason?: string;
}

/** G5 回执。 */
export function createRefundReceipt(
  refundId: string,
  body: RefundReceiptRequest,
  idempotencyKey: string
): Promise<RefundReceipt | undefined> {
  return call<RefundReceipt>('createRefundReceipt', {
    params: { id: refundId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

// ===========================================================================
// 域 I · 文书模板（I1~I7）
// ===========================================================================
//
// 🛑 **本域 7 个端点契约全部逐字 `x-frontier: 占位待冻结`**
// ---------------------------------------------------------------------------
// 含义：**契约内容尚未写完，功能属未来**。故本层的对应函数：
//   · 返回值类型一律 `Record<string, unknown>`（不替契约承诺字段形态）；
//   · 每条注释写明"占位待冻结"；
//   · 调用方**必须**渲染 `UnsettledBar`（`a-check.mjs` 的
//     `unsettled-surfaced` 判据守这一条）。
// 🛑 有占位 ≠ 不能写代码：契约把端点列出来了，接线是正确的；
//    错的是**把它显示成"已冻结、可用"的功能**。

/** I1 文书模板列表。契约：**占位待冻结**。 */
export function listDocTemplates(
  query: { page?: number; page_size?: number } = {}
): Promise<readonly Record<string, unknown>[] | undefined> {
  return call<{ items?: readonly Record<string, unknown>[] }>('listDocTemplates', { query })
    .then((r) => r.data?.items);
}

/** I2 新建文书模板。契约：**占位待冻结**。 */
export function createDocTemplate(
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('createDocTemplate', { body, idempotencyKey })
    .then((r) => r.data);
}

/**
 * I3 上传文书模板文件。契约：**占位待冻结**。
 * 🛑 契约另有 `x-idempotency-key` 逐字：`(tenant_id, doc_type, file_hash)`
 *    ⇒ 幂等键**不是随机值**，而是这三个要素的组合。本函数**要求调用侧显式传入**
 *      该幂等键，不自动生成（自动生成的随机键会让"同一文件重复上传"无法去重）。
 */
export function uploadDocTemplate(
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  if (!idempotencyKey) {
    throw new Error(
      'I3 契约约束（x-idempotency-key = (tenant_id, doc_type, file_hash)）：'
      + '幂等键必须由调用侧按该三要素构造，不得用随机键 —— '
      + '随机键会让"同一文件重复上传"无法被服务端去重。'
    );
  }
  return call<Record<string, unknown>>('uploadDocTemplate', { body, idempotencyKey })
    .then((r) => r.data);
}

/** I4 新建模板版本。契约：**占位待冻结**。 */
export function createDocTemplateVersion(
  templateId: string,
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('createDocTemplateVersion', {
    params: { id: templateId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/** I5 模板版本列表。契约：**占位待冻结**。 */
export function listDocTemplateVersions(
  templateId: string
): Promise<readonly Record<string, unknown>[] | undefined> {
  return call<{ items?: readonly Record<string, unknown>[] }>('listDocTemplateVersions', {
    params: { id: templateId },
  }).then((r) => r.data?.items);
}

/** I6 发布模板版本。契约：**占位待冻结**。 */
export function publishDocTemplateVersion(
  templateId: string,
  version: string,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('publishDocTemplateVersion', {
    params: { id: templateId, version },
    idempotencyKey,
  }).then((r) => r.data);
}

/**
 * I7 下载模板。
 * 🛑 契约两条约束叠加：
 *   · `x-frontier: 占位待冻结`；**且**
 *   · `x-super-admin-only: true`（**仅超管**）。
 * ⇒ 本函数是端 A **唯一**带超管约束的端点。界面必须同时显示两个标注。
 *   非超管调用是服务端 403；界面**不应**依据本地判断放行。
 */
export function downloadDocTemplate(templateId: string): Promise<unknown> {
  return call('downloadDocTemplate', { params: { id: templateId } }).then((r) => r.data);
}

// ===========================================================================
// 契约侧自证（供页面显示"这道约束来自契约的哪一条"）
// ===========================================================================

/**
 * 某端点的契约约束摘要（**全部现算自生成物**，不写死）。
 * 供页面在渲染入口前把该端点的未完结 / 行级范围 / 仅超管状态显示出来。
 * 🛑 这是端 A 的"**这道约束来自契约的哪一条**"的统一出口 ——
 *    页面不得各自手写一份判断（那会造出第二份权威，且会与契约漂移）。
 */
export interface EndpointContractNote {
  readonly operationId: string;
  readonly contractVersion: string;
  readonly unsettled: readonly Unsettled[];
  readonly rowScoped: boolean;
  readonly rowScope: string | null;
  readonly superAdminOnly: boolean;
}

/** 生成物的按 id 查询（供页面用，避免各页面自己遍历 ENDPOINTS）。 */
export function endpointFor(operationId: string): Endpoint | null {
  return endpointById(operationId);
}

/**
 * 契约约束摘要（**端 A 唯一的"未完结标注"出口**）。
 *
 * 🛑 为什么它算"标注"、而已有判据却认不出来（第 53 条同型，本轮实测）
 * ---------------------------------------------------------------------------
 * `tools/a-check.mjs` ⑦ `unsettled-surfaced` 初版只认
 * `unsettledOf` / `unsettledEndpoints` / `frontier` / `rulingPending` 四词。
 * 而本仓在 `domain.ts` 里**逐字规定**："页面不得各自手写判断，一律走统一出口"。
 * 于是页面全都写 `contractNoteOf('approveRefund')` + `<UnsettledBar items={note.unsettled} />`，
 * **完全合法**，却被判红 —— 判据的**覆盖面没跟上本仓自己定的写法**。
 * 这正是第 53 条的形态：**判据太窄**，表现是"合法写法被判红"（假红），
 * 而假红的下场通常是有人把判据删掉 —— 那才是真正的失效。
 * ⇒ 修法：判据增加"经统一出口"这一合法形态，且**判参数形态**（见 a-check.mjs ⑦）：
 *    必须是真的 `contractNoteOf('<某个未完结端点 id>')` 调用，
 *    而不是文件里出现 `contractNoteOf` 这个词就放行（第 52 条教训）。
 */
export function contractNoteOf(operationId: string): EndpointContractNote | null {
  const e = endpointById(operationId);
  if (!e) return null;
  return {
    operationId,
    contractVersion: CONTRACT_VERSION,
    unsettled: unsettledOf(e),
    rowScoped: hasRowScope(operationId),
    rowScope: rowScopeOf(operationId),
    superAdminOnly: isSuperAdminOnly(operationId),
  };
}

/**
 * 该端点在契约里是否有**未完结声明**（现算）。
 * 🛑 供页面在**渲染入口之前**决定"要不要渲染 UnsettledBar"，
 *    避免"先渲染按钮、再补提示条"的顺序错位（后者会让使用者先点再被告知）。
 */
export function hasUnsettled(operationId: string): boolean {
  const e = endpointById(operationId);
  return !!e && unsettledOf(e).length > 0;
}