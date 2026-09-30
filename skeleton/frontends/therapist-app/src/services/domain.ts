/**
 * 端 B · 域服务层（29 端点，按契约机械映射）
 * ============================================================================
 *
 * 每一段的请求/响应字段**逐条抄自冻结契约** `contract/openapi-v1.0.0.yaml`
 * 的 `components.schemas`（32 个）与各 operation 的内联 requestBody。
 *
 * 🛑 本层【刻意不做】的四件事（做了才是缺陷）
 * ---------------------------------------------------------------------------
 *   1. **不裁剪字段** —— 契约 `x-global-conventions.visibility` 逐字：
 *      「无权限字段**不下发**（不是 null、不是空串）」，裁剪在服务端（X-1）。
 *      本层若"顺手把客户端不该看的字段跳过"，就造出了第二个裁剪点，
 *      与服务端不一致时无从判断谁对。
 *   2. **不推算任何派生值** —— ④ 组（A3 / AS / effect_verdict / 退款资格）
 *      由服务端算（X-1）。本层只读 E4 的返回，不在前端做减法。
 *   3. **不代填日期 / 不猜缺省值** —— 契约 `CreateVerdictRequest.risk_flag` 逐字：
 *      「🛑 缺失 ⇒ 未定（**不得默认「无」**—— 那会让 D4 该触发而不触发）」。
 *      同类：`same_origin` 三项任一项缺省 = 不成立 = 不可比 ⇒ 挂起。
 *   4. **不把幂等重放当失败** —— 4002 的语义是"此前已成功"。
 *
 * 🛑 角色准入在出站层统一执行（`api/client.ts` → `assertCanCall`）
 * ---------------------------------------------------------------------------
 * 本层的每个函数都**必须**带 `role`，并原样传给 `call()`。这会漏吗？
 * 不会：`CallOptions.role` 是必填，漏传即 tsc 报错。这是刻意的设计
 * （把"忘了传角色"从静默失效变成编译失败）。
 */

import { pageQuery, DEFAULT_PAGE_SIZE } from './paging';
import { call } from '../api/client';
import type { AppRole } from '../contract/access';

// ===========================================================================
// 域 A · 身份与合作门店（A3；A1/A2 见 services/session.ts）
// ===========================================================================

/** 契约 `Store`。 */
export interface Store {
  readonly store_id: string;
  readonly name: string;
  /** 逐字抄契约枚举。 */
  readonly franchise_type: '直营' | '加盟';
}

/** 契约 `StoreListData`（分页形状见 x-global-conventions.pagination）。 */
export interface StoreList {
  readonly items: readonly Store[];
  readonly total: number;
  readonly page: number;
  readonly page_size: number;
}

/** A3 门店列表（按行级 scope 过滤 —— 过滤由服务端做，本层不筛）。 */
export function listStores(
  role: AppRole,
  page = 1,
  pageSize = DEFAULT_PAGE_SIZE
): Promise<StoreList | undefined> {
  return call<StoreList>('listStores', { role, query: pageQuery(page, pageSize) })
    .then((r) => r.data);
}

// ===========================================================================
// 域 B · 客户建档（B1~B6）
// ===========================================================================

/** 契约 `ScreeningCreateRequest`。 */
export interface ScreeningRequest {
  readonly customer_id: string;
  /**
   * 契约逐字：「含 pregnancy / acute / risk_history / nonmedical_disclosed」。
   * 🛑 契约**只给了这四个键名**，没有给出每项的取值形态（bool / 枚举未定义）
   *    ⇒ 本层不替契约决定取值，用宽松索引签名并在界面侧只提交已确认的形态。
   *    ⚠️ 这是**如实登记的缺口**，不是我漏了。
   */
  readonly items_json: Record<string, unknown>;
  /** 契约逐字：「服务端从 token 覆写」—— 前端传什么都会被覆写，但仍须传（required）。 */
  readonly operator_id: string;
  readonly customer_sign?: string;
  readonly operator_sign?: string;
}

/** 契约 `ScreeningData`。 */
export interface ScreeningResult {
  readonly screening_id: string;
  readonly result: '通过' | '不通过';
  readonly submitted_at: string;
}

/** B1 禁忌筛查提交（硬门禁①）。 */
export function createScreeningRecord(
  role: AppRole,
  body: ScreeningRequest,
  idempotencyKey?: string
): Promise<ScreeningResult | undefined> {
  return call<ScreeningResult>('createScreeningRecord', { role, body, idempotencyKey })
    .then((r) => r.data);
}

/** 契约 `CustomerCreateRequest`。 */
export interface CustomerCreateRequest {
  readonly name: string;
  readonly gender: '男' | '女';
  /** 契约约束：minimum 1 / maximum 119。 */
  readonly age: number;
  /** 契约逐字：「租户内唯一（跨店识别键）」。 */
  readonly phone: string;
  /** 契约逐字：「服务端校验其 result=通过」—— 本层不做该校验（那是服务端门禁）。 */
  readonly screening_id: string;
}

/** 契约 `CustomerCreateData`。 */
export interface CustomerCreateResult {
  readonly customer_id: string;
  /**
   * 契约逐字：「5 值粗粒度派生聚合态，不是服务主状态机（14 态见
   * customer_state_transition）」。⚠️ 界面不得把它当状态机用。
   */
  readonly status: 'CREATED' | 'PROFILED' | 'CONSENTED' | 'REJECTED' | 'ARCHIVED';
  readonly owner_store_id?: string;
  readonly serving_store_id?: string;
}

/** B2 建档。 */
export function createCustomer(
  role: AppRole,
  body: CustomerCreateRequest,
  idempotencyKey: string
): Promise<CustomerCreateResult | undefined> {
  // B2 建档是写操作且带跨店识别键 ⇒ 必须带幂等键（契约 24h 窗口）。
  return call<CustomerCreateResult>('createCustomer', { role, body, idempotencyKey })
    .then((r) => r.data);
}

/** B3 签知情同意书。契约未给命名 schema，入参按 operation 内联定义传入。 */
export function signConsent(
  role: AppRole,
  customerId: string,
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<unknown> {
  return call('signConsent', {
    role,
    params: { id: customerId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/** 契约 `CustomerDetailData`（本端可见全部字段；客户不见 owner/serving store 与派生项）。 */
export interface CustomerDetail {
  readonly customer_id: string;
  readonly name: string;
  readonly gender: string;
  readonly age: number;
  readonly intake_profile: Record<string, unknown>;
  readonly screening_result: string;
  readonly band_willingness: '自愿佩戴' | '暂不佩戴';
  readonly owner_store_id?: string;
  readonly serving_store_id?: string;
  /** ④ 组（derived_result）：本端可见，端 C 不可见。 */
  readonly effect_verdict?: string;
  readonly as_value?: number;
}

/** B4 客户详情（可见性裁剪重点接口 —— 裁剪在服务端）。 */
export function getCustomer(role: AppRole, customerId: string): Promise<CustomerDetail | undefined> {
  return call<CustomerDetail>('getCustomer', { role, params: { id: customerId } })
    .then((r) => r.data);
}

/** B5 建档扩展档案。 */
export function getIntakeProfile(
  role: AppRole,
  customerId: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('getIntakeProfile', { role, params: { id: customerId } })
    .then((r) => r.data);
}

/**
 * B6 补充 + 修订（**append-only 留痕，不可覆盖**）。
 * 🛑 契约逐字「不可覆盖」⇒ 本函数**只发 PATCH 的增量**，不做整体覆盖式提交。
 *    （后端已有 `IntakeProfileRevision` 留痕；本层不得用 PUT 语义绕开。）
 */
export function patchIntakeProfile(
  role: AppRole,
  customerId: string,
  patch: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('patchIntakeProfile', {
    role,
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

/**
 * C1 题库查询参数 —— 🛑 **不是分页参数**，故**不得**与 `PageQuery` 交叉。
 *
 * 契约 `listScaleItemBanks` 的 parameters 只有 `age_group` / `dimension` / `version`
 * 三个，**没有** `page` / `page_size`。
 *
 * 🛑 本条修正的是一次「死代码掩盖的类型缺陷」（Task #115 余量收口时实测）
 * ---------------------------------------------------------------------------
 * 本函数初版签名是 `query: PageQuery & { age_group?: string; dimension?: string }`。
 * `PageQuery` 的索引签名是 `[k: string]: number | undefined`，与
 * `{ age_group?: string }` 求交后得到一个**内部矛盾的类型**：
 * `age_group` 同时要求 `string` 与 `number | undefined`
 * ⇒ 任何调用点都必然 tsc 报错 —— **该函数在类型层面根本不可调用**。
 * 它之所以长期没被发现，恰恰因为它从没被任何页面调用过：
 * 「封装了但没接上界面」不只让功能缺失，还让**函数自身的缺陷也没有曝光面**。
 * 这与本仓第 52/53 条同族（门禁/判据覆盖不到的地方，缺陷可以长期存活）。
 */
export interface ScaleItemBankQuery {
  /**
   * 契约 `required`（本仓第 70 条 · 判据的第九种失效形态）。
   *
   * 🛑 本字段原为 `age_group?: string` —— 与端 A 的同一份副本（第 63 条同族：同一个
   *    缺陷在另一端的第二份拷贝，而两端门禁各审各端，没有东西会问「还有没有别的宿主」）。
   *    可选声明 + 载体形参默认值 `= {}` ⇒ 契约 required 在编译期被抹掉。
   */
  readonly age_group: string;
  readonly dimension?: string;
  readonly version?: string;
  /**
   * 索引签名 —— 出站 query 的类型是
   * `Record<string, string | number | boolean | undefined>`，
   * 没有它本接口无法作为 query 传入 `call()`（tsc TS2322）。
   * 值域收窄为 `string | undefined`：本端点的三个参数**全是字符串**，
   * 收窄比放过更严（若将来契约加了数字参数，tsc 会在这里报出来，而不是静默放过）。
   */
  readonly [k: string]: string | undefined;
}

/** C1 题库拉取（按分龄组 + 维度）。
 *  🛑 `query` 形参**不得带默认值 `= {}`**（第 70 条）。 */
export function listScaleItemBanks(
  role: AppRole,
  query: ScaleItemBankQuery
): Promise<readonly ScaleItemBank[] | undefined> {
  return call<{ items?: readonly ScaleItemBank[] }>('listScaleItemBanks', { role, query })
    .then((r) => r.data?.items);
}

/** 契约 `BaselineAssessmentRequest`。 */
export interface BaselineRequest {
  readonly scale_id: string;
  /** 契约逐字：「同源题组锁定」。 */
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
  /** 本端可见（客户不下发）。 */
  readonly migratable?: boolean;
  readonly dimension_scores?: readonly number[];
}

/**
 * C2 基线评估提交。
 * 🛑 提交前**本地先挡两个契约硬约束**（dimension_scores 必 7 项、total_score ≤112）：
 *    这**不是**代替服务端校验（服务端仍会校验并回 5001），而是让一线在点提交前
 *    就看到"少填了一个维度"，避免把一次可避免的失败记成系统故障。
 */
export function submitBaselineAssessment(
  role: AppRole,
  customerId: string,
  body: BaselineRequest,
  idempotencyKey: string
): Promise<BaselineResult | undefined> {
  if (body.dimension_scores.length !== 7) {
    throw new Error(
      `C2 契约约束：dimension_scores 必须恰好 7 项（当前 ${body.dimension_scores.length} 项）。`
    );
  }
  if (body.total_score < 0 || body.total_score > 112) {
    throw new Error(`C2 契约约束：total_score 须在 0~112（当前 ${body.total_score}）。`);
  }
  return call<BaselineResult>('submitBaselineAssessment', {
    role,
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
  role: AppRole,
  customerId: string,
  assessmentId: string
): Promise<AssessmentDetail | undefined> {
  return call<AssessmentDetail>('getAssessment', {
    role,
    params: { id: customerId, assessment_id: assessmentId },
  }).then((r) => r.data);
}

/** 契约 `CreateCycleAssessmentRequest`。 */
export interface CycleRequest {
  /** 契约逐字：「周期评估主键（必填 —— 使 F1 能在同一行上补判定）」。 */
  readonly cycle_id: string;
  /** 契约约束：minimum 1；逐字「第 N 次评估（每 7 次触发）」。 */
  readonly sequence_no: number;
  /** 依从四维。契约逐字：「与 F1 **同一形状** —— 两侧对同一事实不得有两套字段名」。 */
  readonly adherence: Adherence;
  readonly expected_days?: number;
  /** 模块分 M1–M5（各 0–16）。 */
  readonly module_scores: Record<string, number>;
  readonly band_trend_note?: string;
  /** 契约逐字：「断言位（可空 = 服务端填；非空须逐字等于服务端指纹）」。 */
  readonly threshold_version?: string;
}

/** 依从四维（F1 与 C4 **共用同一形状** —— 契约明文要求）。 */
export interface Adherence {
  readonly expected_days?: number;
  /**
   * 契约逐字：「键为 A1/A3/A4；🛑 **A2 永不参与 AS**」。
   * 🛑 本层不提供任何"补全 A2"的便利方法 —— 那会直接把契约红线写进代码。
   */
  readonly dimensions: Record<string, AdherenceDim>;
}

export interface AdherenceDim {
  readonly applicable: boolean;
  readonly value: number;
  readonly structural_missing: boolean;
}

/** C4 周期评估提交。 */
export function submitCycleAssessment(
  role: AppRole,
  customerId: string,
  body: CycleRequest,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('submitCycleAssessment', {
    role,
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
  /** 本端可见（客户不下发）。 */
  readonly gate_check_json?: Record<string, unknown>;
  readonly customer_confirmed: boolean;
  /** 本端可见（客户不下发）。 */
  readonly abnormal_note?: string;
}

/** D1 服务核销（四道闸门）。 */
export function createVisit(
  role: AppRole,
  customerId: string,
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Visit | undefined> {
  return call<Visit>('createVisit', {
    role,
    params: { id: customerId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/** D2 服务记录（客户维度全局账本）。 */
export function listVisits(
  role: AppRole,
  customerId: string,
  page = 1,
  pageSize = DEFAULT_PAGE_SIZE
): Promise<readonly Visit[] | undefined> {
  return call<{ items?: readonly Visit[] }>('listVisits', {
    role,
    params: { id: customerId },
    query: pageQuery(page, pageSize),
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

/**
 * D3 代录每日填报。
 * 🛑 契约 `DailyReportRequest.source` 逐字：「代录须标 **代核**」。
 *    故本函数把 `source` **写死在调用侧为 '代核'** —— 端 B 不存在"以客户身份代填"
 *    这条路径。界面不提供 source 选择器（提供就等于允许把代录伪装成客户自填）。
 */
export function submitDailyReportAsStaff(
  role: AppRole,
  customerId: string,
  body: { date: string; answers_json: Record<string, unknown> },
  idempotencyKey: string
): Promise<DailyReport | undefined> {
  return call<DailyReport>('submitDailyReport', {
    role,
    params: { id: customerId },
    body: { ...body, source: '代核' },
    idempotencyKey,
  }).then((r) => r.data);
}

/** D4 填报记录。 */
export function listDailyReports(
  role: AppRole,
  customerId: string,
  page = 1,
  pageSize = DEFAULT_PAGE_SIZE
): Promise<readonly DailyReport[] | undefined> {
  return call<{ items?: readonly DailyReport[] }>('listDailyReports', {
    role,
    params: { id: customerId },
    query: pageQuery(page, pageSize),
  }).then((r) => r.data?.items);
}

/** D5-a 方案出具（回炉审核）。 */
export function createPlan(
  role: AppRole,
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('createPlan', { role, body, idempotencyKey })
    .then((r) => r.data);
}

/** D5-b 方案查阅。 */
export function getPlan(role: AppRole, planId: string): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('getPlan', { role, params: { id: planId } }).then((r) => r.data);
}

/**
 * D5-c 方案审核（**仅经络师**；退回必填 reason）。
 * 🛑 这是本端 7 个"仅经络师"端点之一。配合的角色边界**在出站层强制**
 *    （`assertCanCall`），故本函数对 `therapist` 调用的结果是抛错 ——
 *    界面侧必须**不渲染**该入口（函数名后缀 -AsMeridian 就是在提醒这一点）。
 *
 * 🛑 请求体字段名来自【后端控制器的真实接收形状】，不是自创
 * ---------------------------------------------------------------------------
 * 契约对 D5-c **没有声明 requestBody**（裁剪契约与全量契约都只有 parameters），
 * 故字段名未被契约冻结。唯一可核对的真相源是控制器：
 * `PlanController.ReviewRequest` = `{result, reason, second_confirm}`。
 * ⚠️ 本函数初版写成 `{decision, reason?}` —— 那是**自创字段名**：
 *    tsc 不会报错（`decision` 只是多了一个键），服务端也**不会报错**
 *    （`result` 为空 ⇒ 走的是"未给出审核结论"的分支），
 *    于是表现为"审核提交成功但方案状态没变" —— 最坏的一类静默失效。
 *    这与本仓第 50 条同族：**"写下的字段名"与"服务端真读的字段名"是两件事**，
 *    只能靠对照控制器（或真请求）证明，不能靠看契约（契约此处是空的）。
 */
export function reviewPlanAsMeridian(
  role: AppRole,
  planId: string,
  body: {
    /** 审核结论。逐字取自 `PlanController.ReviewRequest.result`。 */
    result: string;
    /** 退回必填（契约 summary 逐字：「退回必填 reason」）。 */
    reason?: string;
    /** 二次确认（`second_confirm`，控制器为 `Boolean`）。 */
    second_confirm?: boolean;
  },
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('reviewPlan', {
    role,
    params: { id: planId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/** D6 设备参数下发（需方案已审核）。 */
export function createDeviceDispatch(
  role: AppRole,
  body: Record<string, unknown>,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  return call<Record<string, unknown>>('createDeviceDispatch', { role, body, idempotencyKey })
    .then((r) => r.data);
}

// ===========================================================================
// 域 E · 手环数据（E3 / E4）
// ===========================================================================

/** 契约 `BandTelemetryData`。 */
export interface BandTelemetry {
  /** 逐字抄契约枚举。未接入时数值字段不下发（R6 同源纪律）。 */
  readonly data_source?: '手环' | '未接入';
  readonly collected_days?: number;
  readonly synced_date?: string;
  readonly metrics?: readonly Record<string, unknown>[];
  /**
   * ③ 组（gap_reason）。契约逐字：「🔴 客户恒 403 / 不下发（硬约束，配置不得放开）」
   * ⇒ **本端可见**。7 值枚举逐字。
   */
  readonly gap_reason?: 'no_open' | 'sync_failed' | 'not_worn' | 'compliant_removal'
    | 'involuntary_technical' | 'beyond_retention_window' | 'unknown';
}

/** E3 手环原始数据 + 采集状态（四端按档裁剪）。 */
export function getBandTelemetry(
  role: AppRole,
  customerId: string,
  query: { device_id?: string; metric?: string; date?: string } = {}
): Promise<BandTelemetry | undefined> {
  return call<BandTelemetry>('getBandTelemetry', {
    role,
    params: { id: customerId },
    query,
  }).then((r) => r.data);
}

/** 契约 `BandDerivedData`（④ 组，仅 staff 可见）。 */
export interface BandDerived {
  /**
   * 契约逐字：「为 false 时**不返回 a3_value**（避免 0 值被误读为"戴了 0 天"）」。
   * 🛑 故界面在 `a3_applicable !== true` 时**必须不显示 a3_value**，
   *    即使它因某种原因出现了。这是本条字段存在的全部理由。
   */
  readonly a3_applicable?: boolean;
  readonly a3_value?: number;
  readonly as_value?: number;
  readonly effect_verdict?: 'E1显著改善' | 'E2部分改善' | 'E3稳定' | 'E4无明显改善' | 'E5加重';
  readonly refund_eligibility?: boolean;
}

/** E4 派生结果。**仅 staff**（客户走了会被服务端 403）。 */
export function getBandDerived(
  role: AppRole,
  customerId: string
): Promise<BandDerived | undefined> {
  return call<BandDerived>('getBandDerived', { role, params: { id: customerId } }).then((r) => r.data);
}

// ===========================================================================
// 域 F · 判定结论（F1 / F2 —— 均【仅经络师】）
// ===========================================================================

/** 契约 `CreateVerdictRequest`。 */
export interface VerdictRequest {
  readonly customer_id: string;
  readonly sequence_no: number;
  /** 契约逐字：「基线模块总分；缺失 ⇒ 挂起（D5）」。 */
  readonly base_total?: number;
  /** 契约逐字：「复评模块总分；缺失 ⇒ 挂起（D5）」。 */
  readonly current_total?: number;
  /**
   * 契约逐字：「同源断言（三项；**任一项缺省 = 不成立 = 不可比 ⇒ 挂起**）」。
   * 🛑 故本层不提供"缺省即为 true"的便利构造 —— 那正是条文禁止的默认化。
   */
  readonly same_origin: {
    readonly same_item_group: boolean;
    readonly range_matches: boolean;
    readonly same_measurer: boolean;
  };
  readonly adherence: Adherence;
  /**
   * 契约逐字：「🛑 缺失 ⇒ 未定（**不得默认「无」**—— 那会让 D4 该触发而不触发）」。
   */
  readonly risk_flag?: '无' | '高危' | '新发' | '同病';
  /** 契约逐字：「经络师结构化录入；缺失 ⇒ 挂起」。 */
  readonly core_metric_improved?: boolean;
  readonly confidence: Record<string, unknown>;
  readonly module_scores: Record<string, number>;
}

/** 契约 `VerdictData`（`x-visible-to` = meridian / admin —— 与角色受限一致）。 */
export interface Verdict {
  readonly verdict_id: string;
  readonly branch?: '稳定' | '依从不足' | '达标无效' | '全面评估' | '人工复核';
  readonly confidence?: number;
  readonly evidence_snapshot?: Record<string, unknown>;
  /** 溯源回放用（ADR-11）。 */
  readonly threshold_version?: string;
  readonly decided_at?: string;
}

/** F1 判定结论落库（**仅经络师**）。 */
export function createVerdictAsMeridian(
  role: AppRole,
  cycleAssessmentId: string,
  body: VerdictRequest,
  idempotencyKey: string
): Promise<Verdict | undefined> {
  return call<Verdict>('createVerdict', {
    role,
    params: { id: cycleAssessmentId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}

/** F2 判定历史（**仅经络师**）。 */
export function listVerdictsAsMeridian(
  role: AppRole,
  customerId: string,
  page = 1,
  pageSize = DEFAULT_PAGE_SIZE
): Promise<readonly Verdict[] | undefined> {
  return call<{ items?: readonly Verdict[] }>('listVerdicts', {
    role,
    params: { id: customerId },
    query: pageQuery(page, pageSize),
  }).then((r) => r.data?.items);
}

// ===========================================================================
// 域 G · 退款与挽留（G1/G2/G3/G5 —— 全部【仅经络师】）
// ===========================================================================
//
// 🛑 本端【可以】直呼"退款"：`compliance/wordlists/scan1_refund.words` 的 SCOPE 段
//    逐字声明本面只扫客户端包，管理端 / 经络师端的退款措辞是合法的内部业务词汇。
//    端 C 同名概念必须用指代 —— 两端纪律不同，不是"一端少做了一道检查"。

/** 契约 `RefundCreateRequest`。 */
export interface RefundCreateRequest {
  readonly customer_id: string;
  /** 逐字枚举：入口 A 门店代录 / B 首周期。 */
  readonly entry: 'A门店代录' | 'B首周期';
  readonly refund_route: '履约类' | '效果类';
  /**
   * 契约逐字：「必填；**未记录原因不可结案**」。6 值枚举逐字。
   * 🛑 故本层不给默认值、也不允许空串 —— 空原因会让工单无法结案。
   */
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
  /** 契约逐字「recording_delay_h」—— 录入延迟（小时）。 */
  readonly recording_delay_h?: number;
  readonly outcome?: '继续' | '终止' | '归档';
}

/** G1 代录客户退款诉求（**仅经络师**）。 */
export function createRefundAsMeridian(
  role: AppRole,
  body: RefundCreateRequest,
  idempotencyKey: string
): Promise<Refund | undefined> {
  return call<Refund>('createRefund', { role, body, idempotencyKey }).then((r) => r.data);
}

/** G2 退款工单详情（**仅经络师**）。 */
export function getRefundAsMeridian(role: AppRole, refundId: string): Promise<Refund | undefined> {
  return call<Refund>('getRefund', { role, params: { id: refundId } }).then((r) => r.data);
}

/**
 * G3 挽留记录（**仅经络师**）。
 * 🛑 契约 summary 逐字：「挽留记录**必填**；入口 B 不经挽留」。
 *    故本函数要求 `body` 非空 —— 空对象会被本地直接挡下，而不是发出去再被服务端拒。
 *
 * 🛑 字段名来自控制器真实形状（契约未声明 requestBody）
 * ---------------------------------------------------------------------------
 * `RefundWorkOrderController.CreateRetentionRequest` =
 * `{attempts, script_version, result, analysis, communication}`。
 * `analysis` / `communication` 在库层是 **JSONB**，控制器收 `JsonNode`
 * ⇒ 端侧**传对象即可**（不必自己 `JSON.stringify`，控制器两种都收）。
 */
export interface RetentionRequest {
  /** 挽留尝试次数。控制器把 null 归一为 0。 */
  readonly attempts?: number;
  /** 话术版本。 */
  readonly script_version?: string;
  /** 挽留结论。 */
  readonly result?: string;
  /** 五维原因分析（JSONB，传对象）。 */
  readonly analysis?: Record<string, unknown>;
  /** 沟通记录（JSONB，传对象）。 */
  readonly communication?: Record<string, unknown>;
}

export function createRetentionAsMeridian(
  role: AppRole,
  refundId: string,
  body: RetentionRequest,
  idempotencyKey: string
): Promise<Record<string, unknown> | undefined> {
  if (Object.keys(body).length === 0) {
    throw new Error('G3 契约约束：挽留记录必填（不允许提交空记录）。');
  }
  return call<Record<string, unknown>>('createRetention', {
    role,
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
 * G5 回执（**仅经络师**）。
 * 🛑 字段名来自控制器真实形状（契约未声明 requestBody）
 * ---------------------------------------------------------------------------
 * `RefundWorkOrderController.CreateReceiptRequest` =
 * `{subscription_quota, template_id, push_succeeded, failure_reason}`。
 * 🛑 契约与控制器都强调：`subscription_quota` 是**推送之前**判定的额度
 *    （≤0 ⇒ 「未授权（转线下）」）—— 端侧顺序搞反会让"未授权"这一态
 *    在库里永不出现。本层不重排顺序，只**如实传值**。
 */
export interface RefundReceiptRequest {
  /** 订阅消息剩余额度（推送**之前**判定；≤0 → 未授权（转线下））。 */
  readonly subscription_quota?: number;
  /** 订阅消息模板 ID（P0-19「回执独占一个模板 ID」）。 */
  readonly template_id?: string;
  /** 本次推送是否成功（由推送网关回传）。 */
  readonly push_succeeded?: boolean;
  /** 推送失败原因（`push_succeeded = false` 时必填）。 */
  readonly failure_reason?: string;
}

export function createRefundReceiptAsMeridian(
  role: AppRole,
  refundId: string,
  body: RefundReceiptRequest,
  idempotencyKey: string
): Promise<RefundReceipt | undefined> {
  return call<RefundReceipt>('createRefundReceipt', {
    role,
    params: { id: refundId },
    body,
    idempotencyKey,
  }).then((r) => r.data);
}