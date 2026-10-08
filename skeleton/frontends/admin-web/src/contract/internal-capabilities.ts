/**
 * 端 A · 【契约外】内部能力面清册（自建能力面端点的出站白名单）
 * ============================================================================
 *
 * 🛑 本文件为什么必须存在（先讲清楚"不写它会怎样"）
 * ---------------------------------------------------------------------------
 * 端 A 此前只有一条出站通道：`api/client.ts` 的 `call(operationId)`，它的白名单是
 * **生成物** `contract/endpoints.ts`（从冻结契约机械转录，39 个端点）。
 * 而结算落账 / 对账报表（E1）与运维自检（E2）是**契约未声明的自建能力面端点**
 * —— 它们**不在**契约的 45 个 `operationId` 里（后端把它们登记在
 * `EndpointCoverageLedgerTest.INTERNAL_ENDPOINTS`，共 32 条）。
 *
 * ⇒ 于是有一个岔路口，两条路都错：
 *   ① **把它们塞进生成物**：生成物是契约的机械转录，手改一次就与契约脱钩；
 *      更糟的是 `EndpointCoverageLedgerTest` 的计数等式（契约 45 = 42 已实现 + 3 出范围）
 *      与端 A 的 `endpoint-reachability`（出站形态数 === 生成物端点数）会**同时失真**。
 *   ② **让页面直接 `fetch`**：那就等于**绕过了所有白名单**，「这个页面打了哪些
 *      契约外端点」这件事在代码里**任何地方都查不到**——正是本仓反复登记的
 *      「未登记的出站面」。而且它连鉴权头 / 幂等头 / 信封解析都要各自重写一遍。
 * 故第三条路：**单开一条出站通道 + 一份显式清册**（本文件 + `api/internal.ts`），
 * 并让 `tools/a-check.mjs` ⑫ 把下面三件事变成构建期事实：
 *   · 清册 ⊆ 后端台账（前端**不得发明**端点）；
 *   · 清册里每一条都**真的被调用**（不得有"声明了却没人用"的死项）；
 *   · 契约外出站**只能**出现在 `services/` 层（页面不得自己打）。
 *
 * 🛑 它为什么不是"第二份权威"
 * ---------------------------------------------------------------------------
 * 真源只有一处：**后端的 `INTERNAL_ENDPOINTS` 台账**（因为契约里根本没有这些端点，
 * 后端台账就是唯一声明）。本文件是它的**前端镜像**，且不是靠人肉同步 ——
 * ⑫ 判据会去读那个 Java 文件、逐条比对；对不上就报红。
 * 换句话说：本文件写错 = 构建期就红，不会等到真机 404。
 *
 * 🛑 为什么是"手写"而不是"生成"
 * ---------------------------------------------------------------------------
 * 契约驱动的生成物之所以能生成，是因为真源是**结构化契约**。
 * 后端台账是**Java 源码里的一个 Map 字面量**，且它登记的是「后端有哪些出站面」，
 * 不等于「端 A 允许打哪些」（端 B / 端 C 一个都不该打）。
 * ⇒ 这里手写的是**端 A 的选择**（选了哪几条），而"这些端点确实存在"由 ⑫ 核对。
 * 生成器不介入，因为它无法知道"本端要不要用"。
 *
 * 🛑 全部总部专属（M5）
 * ---------------------------------------------------------------------------
 * PRD M5：「跨店服务按次数占比拆分业绩；稽核看板**总部可见、门店/加盟商不可见**」。
 * 本清册 6 条一律总部专属 —— 但**判定在服务端**（`@RequireOrgLevel(HEADQUARTERS)`
 * + V24 RLS），本层与页面**不做**也不应做端侧判断（做了就是造第二个裁剪点，X-1）。
 *
 * 🛑 改动本文件时必读
 * ---------------------------------------------------------------------------
 *   · 加一条 ⇒ 必须同时：后端台账里已有该端点 + `services/ops.ts` 里真的调用它，
 *     否则 ⑫ 报红（死声明 / 台账里没有）。
 *   · 删一条 ⇒ 必须同时删掉调用点与页面入口，否则 ⑫ 报红（声明与调用不咬合）。
 *   · 只改 `path` ⇒ 两侧立刻不一致 ⇒ 报红。**这是刻意的**：路径漂移是最容易
 *     "看起来对、打过去 404"的一类改动。
 */

/** 本清册用的两种方法（内部能力面当前只有读 / 写两种，没有 PATCH/DELETE）。 */
export type InternalCapabilityMethod = 'GET' | 'POST';

/** 一条契约外能力面的出站声明。 */
export interface InternalCapability {
  /** 出站 id —— 与契约 `operationId` **同一个命名空间之外**的第二套，勿混用。 */
  readonly id: string;
  readonly method: InternalCapabilityMethod;
  /**
   * 契约外路径（**不含** `/api/v1` 前缀 —— 前缀由 `env.getRequestBaseUrl()` 拼）。
   * 🛑 参数占位写 `{name}`；⑫ 判据会归一化成 `{}` 后与后端台账逐条比对。
   */
  readonly path: string;
  /** 为什么端 A 需要它（一句话，写在界面上也不违和的那种）。 */
  readonly purpose: string;
}

/**
 * 端 A 允许出站的**全部**契约外端点。**顺序即 `internalCapabilityId()` 的无参形态取首项的语义依据**，
 * 但业务代码不应依赖顺序 —— 一律用 id 查（`internalCapabilityById`）。
 */
export const INTERNAL_CAPABILITIES: readonly InternalCapability[] = Object.freeze([
  Object.freeze({
    id: 'previewSettlement',
    method: 'POST',
    path: '/settlement/preview',
    purpose: '业绩拆分预演（只算不落）。落账的输入与结果都由它产生 —— 预演与落账必须是两个显式动作。',
  }),
  Object.freeze({
    id: 'commitSettlement',
    method: 'POST',
    path: '/settlement/commit',
    purpose: '把一次已算出的结算结论**显式落账**（幂等键 tenant+request_hash；服务端重算比对后才收账）。',
  }),
  Object.freeze({
    id: 'listSettlementStatements',
    method: 'GET',
    path: '/settlement/statements',
    purpose: '按周期取对账清单（窄记录，不含 payload 快照）。',
  }),
  Object.freeze({
    id: 'exportSettlementCsv',
    method: 'GET',
    path: '/settlement/statements.csv',
    purpose: '周期对账 CSV 导出（RFC 4180，列序冻结）—— 对账方按列序做机器解析。',
  }),
  Object.freeze({
    id: 'getSettlementPayload',
    method: 'GET',
    path: '/settlement/statements/{statementId}/payload',
    purpose: '定点读取单条结算单的**不可变快照**（落账当时的结论；算法演进后历史报表读快照不重算）。',
  }),
  Object.freeze({
    id: 'getOpsHealth',
    method: 'GET',
    path: '/ops/health',
    purpose: '运维健康自检（DB 连通+迁移登记数 / Redis 可选装配 / 审计哈希链完整性）——'
      + '是 `deploy/DEPLOY.md` §6 告警手册的机器可读入口。',
  }),
]);

/** 全部 id（供门禁与自证消费；顺序同清册）。 */
export const INTERNAL_CAPABILITY_IDS: readonly string[] = Object.freeze(
  INTERNAL_CAPABILITIES.map((c) => c.id)
);

/** 按 id 查（未登记 ⇒ `null`，调用方必须显式处理，**不得回落成"猜一个"**）。 */
export function internalCapabilityById(id: string): InternalCapability | null {
  return INTERNAL_CAPABILITIES.find((c) => c.id === id) ?? null;
}
