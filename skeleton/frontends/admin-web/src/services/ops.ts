/**
 * 端 A · 【契约外】结算对账与运维自检域服务层
 * ============================================================================
 *
 * 本层是 `contract/internal-capabilities.ts` 清册里 6 条能力面的封装
 * （结算预演 / 落账 / 周期清单 / CSV 导出 / 单据快照 + 运维自检）。
 *
 * 🛑 本层与 `services/domain.ts` 的关系：**并列，不是子集**
 * ---------------------------------------------------------------------------
 * `domain.ts` 封装的是**契约端点**（走 `api/client.ts` 的 `call('<operationId>')`，
 * 白名单 = 生成物）；本文件封装的是**契约外端点**（走 `api/internal.ts` 的
 * `callInternal` / `downloadInternal`，白名单 = 清册）。两条通道各自守自己的白名单，
 * 谁也不放行对方的 id —— 见 `api/internal.ts` 文件头。
 *
 * 🛑 🚨 一个必须如实登记的事实：本域**两套命名口径并存**
 * ---------------------------------------------------------------------------
 * 后端同一族控制器里，请求体的命名口径**不一致**（实测，不是猜测）：
 *
 *   | 端点 | 请求体键名 | 依据 |
 *   |---|---|---|
 *   | `POST /settlement/preview` | **camelCase**（`closingStoreId` / `visitsByStore` / `eccUnits` / `lossYuan`） | `SettlementController.PreviewRequest` 是**裸 record**，无 `@JsonProperty` ⇒ Jackson 用组件名 |
 *   | `POST /settlement/commit`  | **snake_case** 包装层（`closing_store_id` / `visits_by_store` / `ecc_units` / `loss_yuan`）+ `period` + `result` | `SettlementStatementController.CommitRequest` 逐字段加了 `@JsonProperty` |
 *   | `GET /settlement/statements[.csv]` 出参 | **snake_case**（`statement_id` / `closing_store_id` / …） | 对账方按字段名机器解析（CSV 列序冻结） |
 *
 * ⇒ 更麻烦的是：**`commit` 的 `result` 字段内部又回到 camelCase**
 *   （`splitApplied` / `otherStoreVisitRatio` / `totalVisits` / `allocations[].eccShare`），
 *   因为它就是 `preview` 的返回类型 `SettlementResult`。
 *   即：**一次 commit 请求体里两种口径同时出现**。
 *
 * 🛑 为什么本层"照抄"而不是"统一"
 * ---------------------------------------------------------------------------
 * 统一口径是**后端的一次 API 变更**（会改动既有绿测试 `TierAuthorizationE2ETest`
 * 的请求体），不属于端 A 能单方面决定的事；而端 A 此刻是**第一个消费者**，
 * 职责是"把服务端的真实形态如实镜像出来"，不是"顺手把它改顺眼"。
 * ⇒ 本层的做法是：**逐端点按各自的真实口径发送**，并在
 *   `services/ops.test.ts` 里用"捕获真实请求体"的方式把两套口径**钉成测试事实**。
 *   这样它就不再是"某次调试才知道的坑"，而是**一条会红的断言**。
 *   同时该不一致已作为**待裁定项**登记（见 README「未完成项与 TODO」）。
 *
 * 🛑 本层【不做】的三件事（做了才是缺陷）
 * ---------------------------------------------------------------------------
 *   1. **不做端侧档位判断**：全部 6 条均为总部专属（M5），但判定在服务端
 *      （`@RequireOrgLevel(HEADQUARTERS)` + V24 RLS）。前端判断 = 第二个裁剪点（X-1）。
 *   2. **不推算、不补 0**：预演结果一律回显服务端返回的分摊；服务端 422
 *      （数据不足以出结论）**不得**在前端兜成"全 0 的分摊表" —— 那正是
 *      `CrossStoreSettlement.UnallocatableException` 明确拒绝的事。
 *   3. **不重算 request_hash**：幂等键构成由服务端定义（period | 结案店 | 按店名升序的
 *      visits | ecc | loss），本层**不自行计算**、不缓存、不猜测命中与否 ——
 *      重复落账的判定权在服务端（`ALREADY_EXISTS` 是它的结论，不是我们的推理）。
 */

import { callInternal, downloadInternal, internalCapabilityUrl } from '../api/internal';

// ===========================================================================
// 契约外 · 结算（E1）
// ===========================================================================

/**
 * 预演请求（**camelCase** —— 见文件头的双口径事实表）。
 * 🛑 字段名逐字对应后端 `SettlementController.PreviewRequest` 的 record 组件名，
 *    改这里必须同时改后端，否则字段绑定不上 ⇒ `closingStoreId=null` ⇒ 422。
 */
export interface PreviewSettlementRequest {
  readonly closingStoreId: string;
  /** key = storeId，value = 该店实际完成的服务次数。**缺失不得补 0**。 */
  readonly visitsByStore: Record<string, number>;
  readonly eccUnits: number;
  readonly lossYuan: number;
}

/** 一家店的分摊结果（camelCase，同 `StoreAllocation`）。 */
export interface SettlementAllocation {
  readonly storeId: string;
  readonly visitCount: number;
  readonly eccShare: string;
  readonly lossShare: string;
  readonly splitApplied: boolean;
}

/**
 * 结算结论（camelCase，同 `SettlementResult`）。
 * 🛑 `otherStoreVisitRatio` 是**判定依据**（阈值比较用），不是看板指标 ——
 *    界面上要标清这一点，否则会被当作"门店损益率"使用（PRD §2.9.6 明令不新建该聚合）。
 */
export interface SettlementResult {
  readonly splitApplied: boolean;
  readonly otherStoreVisitRatio: string;
  readonly totalVisits: number;
  readonly allocations: readonly SettlementAllocation[];
}

/** 落账请求（**snake_case 包装层 + camelCase result** —— 见文件头）。 */
export interface CommitSettlementRequest {
  /** 结算周期 `YYYY-MM`，**不得晚于当前月**（服务端守卫，前端不替它判）。 */
  readonly period: string;
  readonly closing_store_id: string;
  readonly visits_by_store: Record<string, number>;
  readonly ecc_units: number;
  readonly loss_yuan: number;
  /** 预演返回的结论。服务端会**重算并逐字段比对** —— 篡改必 422。 */
  readonly result: SettlementResult;
}

/** 落账结果状态。`ALREADY_EXISTS` 是**幂等命中**（本次未新落账），不是失败。 */
export type CommitStatus = 'CREATED' | 'ALREADY_EXISTS';

export interface CommitOutcome {
  readonly status: CommitStatus;
  readonly statement_id: string;
}

/** 对账清单行（snake_case，逐字对应 `SettlementStatementLedgerRow`）。 */
export interface SettlementStatementRow {
  readonly statement_id: string;
  readonly period: string;
  readonly closing_store_id: string;
  readonly stores_involved: number;
  readonly visits_total: number;
  readonly ecc_units: string;
  readonly loss_yuan: string;
  readonly split_applied: boolean;
  readonly other_store_ratio: string;
  readonly request_hash: string;
  readonly created_by: string | null;
}

/**
 * 预演业绩拆分（只算不落）。
 * 🛑 与 `commitSettlement` 是**两个显式动作**：混成一个就会有人把预演当结算。
 */
export function previewSettlement(req: PreviewSettlementRequest): Promise<SettlementResult | undefined> {
  return callInternal<SettlementResult>('previewSettlement', { body: req }).then((r) => r.data);
}

/**
 * 落账一次结算结论。
 * 🛑 返回值里的 `status` **必须原样呈现**：`ALREADY_EXISTS` 表示"同输入此前已落过账"，
 *    界面若把它渲染成"失败"会诱导操作者再点一次（更多重放记录）。
 */
export function commitSettlement(req: CommitSettlementRequest): Promise<CommitOutcome | undefined> {
  return callInternal<CommitOutcome>('commitSettlement', { body: req }).then((r) => r.data);
}

/** 按周期取对账清单（窄记录；快照走 `getSettlementPayload` 定点取）。 */
export function listSettlementStatements(
  period: string
): Promise<readonly SettlementStatementRow[] | undefined> {
  return callInternal<readonly SettlementStatementRow[]>('listSettlementStatements', {
    query: { period },
  }).then((r) => r.data);
}

/**
 * 周期对账 CSV 导出（**非信封**字节流）。
 * 返回文件名（取自 `Content-Disposition`，RFC 6266）与文本内容 —— 由页面组 Blob 触发下载。
 */
export function exportSettlementCsv(period: string): Promise<{ filename: string; content: string }> {
  return downloadInternal('exportSettlementCsv', { query: { period } });
}

/**
 * 导出端点**将要请求的完整 URL**（只读、不出站）。
 * 🛑 存在的理由：界面上把它显示出来，使"CSV 落在哪个 URL"可被肉眼核对 ——
 *    若前缀漏拼 `/api/v1`（第 56 条那一类），在本页即可看见，而不必等真机 404。
 */
export function settlementCsvUrl(period: string): string {
  return internalCapabilityUrl('exportSettlementCsv', { query: { period } });
}

/** 定点读取单条结算单的**不可变快照**（落账当时的结论）。 */
export function getSettlementPayload(
  statementId: string
): Promise<Record<string, unknown> | undefined> {
  return callInternal<Record<string, unknown>>('getSettlementPayload', {
    params: { statementId },
  }).then((r) => r.data);
}

// ===========================================================================
// 契约外 · 运维自检（E2）
// ===========================================================================

/** 单项自检的三种状态：UP / DOWN / not_configured（Redis 未装配时的如实上报）。 */
export type OpsItemStatus = 'UP' | 'DOWN' | 'not_configured' | 'BROKEN';

export interface OpsDbCheck {
  readonly status: OpsItemStatus;
  /** 已登记迁移数。**「部署半途」最典型的信号**：库里有表但迁移登记不全。 */
  readonly registered_migrations?: number;
  readonly error?: string;
}

export interface OpsRedisCheck {
  readonly status: OpsItemStatus;
  /** 未装配时的说明（该项是**可选件**，未配 ≠ 坏了）。 */
  readonly hint?: string;
  readonly error?: string;
}

export interface OpsAuditChainCheck {
  readonly status: OpsItemStatus;
  /** 已校验的日志行数。 */
  readonly checked?: number;
  /** 断链位置（`status=BROKEN` 时才有）。 */
  readonly broken_at?: number;
  readonly reason?: string;
  readonly error?: string;
}

export interface OpsHealth {
  readonly db: OpsDbCheck;
  readonly redis: OpsRedisCheck;
  readonly audit_chain: OpsAuditChainCheck;
  /** 汇总：DB 或审计链异常 ⇒ DEGRADED；**Redis 未配置不算降级**（可选件）。 */
  readonly overall: 'UP' | 'DEGRADED';
}

/**
 * 聚合健康自检。
 * 🛑 该端点**自身不会 500**（任何单项异常都被捕获成该项 `DOWN`）——
 *    故这里的网络层异常只可能是"请求没到"或"网关挂了"，与"某项自检失败"是两件事，
 *    界面上必须分开呈现（否则会误导值班人先去查 DB）。
 */
export function getOpsHealth(): Promise<OpsHealth | undefined> {
  return callInternal<OpsHealth>('getOpsHealth').then((r) => r.data);
}
