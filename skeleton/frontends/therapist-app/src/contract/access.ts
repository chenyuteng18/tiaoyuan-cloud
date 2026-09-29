/**
 * 端 B · X-3 角色级装载（契约机械推导，不手抄）
 * ============================================================================
 *
 * 本文件回答一个问题：**当前登录的这个角色，可以调用本端的哪些端点。**
 *
 * 🛑 为什么必须"机械推导"而不是手抄一张角色表
 * ---------------------------------------------------------------------------
 * 契约已经把这件事编码成了机械事实：每个 operation 都带 `x-callable-roles`，
 * 生成物 `./endpoints.ts` 已把它裁剪成本端的 `grantedRoles`（= 契约声明 ∩
 * 本端 token-roles）。所以"某角色能不能用某端点"**不需要人来判断**，
 * 只需要读生成物。
 *
 * 反过来，如果在这里手写一张 `{ therapist: [...], meridian: [...] }` 的表，
 * 就又造出了本仓反复登记的那类失效：**同一事实的两份手写清单**，
 * 契约一改就漂移，而且漂移不会让任何门禁变红（第 46 条同型）。
 * 故本文件**不含任何硬编码的端点清单**，一切由 `grantedRoles` 现算。
 *
 * 🛑 实测事实（本端 29 个端点的角色分布，由生成物现算得出）
 * ---------------------------------------------------------------------------
 *   · 两个角色都可用：22 个
 *   · **仅经络师可用：7 个** —— D5-c reviewPlan · F1 createVerdict · F2 listVerdicts
 *     · G1 createRefund · G2 getRefund · G3 createRetention · G5 createRefundReceipt
 *   · 仅调理师可用：0 个
 * ⚠️ 这 7 个里 **3 个不在退款域**（D5-c / F1 / F2），所以「仅经络师」与
 *    「退款域」**不是同一个集合** —— 两者不可互相代替（见下）。
 *
 * 🛑 两种失败必须分开报，不能合成一句"无权限"（这是本仓的静默失效高发点）
 * ---------------------------------------------------------------------------
 *   ① `NOT_IN_THIS_END`  该 operation 根本不属于本端（契约未授予 therapist/meridian）
 *                        ⇒ 生成物里**就没有这条**，说明前端代码引用了错误的端点名。
 *                        这是**代码缺陷**，不是权限问题。
 *   ② `ROLE_NOT_GRANTED` 该 operation 属于本端，但**当前角色**没有被授予
 *                        ⇒ 例如调理师点了"出具判定结论"。
 *                        这是**正常的角色边界**，UI 应**不渲染入口**（而不是渲染后报错）。
 * 把 ① 说成"没有权限"会让一个真的写错了端点名的 bug 被当成权限问题放过；
 * 把 ② 说成"端点不存在"会让排查方向完全跑偏。契约的 `forbidden-403` 条也要求
 * 报错**必须给出缺失项名称 / 档位名称，不得模糊报错** —— 本层沿用该口径。
 *
 * 🛑 本层【不】决定"看得见哪些字段"（X-1 / A2 的职责，刻意不在此复制）
 * ---------------------------------------------------------------------------
 * 契约逐字：A2 `/auth/me` 是「可见性档位**唯一权威下发点**」，响应里带
 * `band_visibility`（四个字段组档位）与 `refund_visibility`。
 * 所以本层**刻意不做**一张静态的字段组可见性表 —— 那会造出第二份权威，
 * 与服务端解算结果打架时无从判断谁对。运行期可见性一律问 A2。
 *
 * 🛑 本端【允许】出现退款相关措辞（不要照搬端 C 的禁用词纪律）
 * ---------------------------------------------------------------------------
 * `compliance/wordlists/scan1_refund.words` 的 SCOPE 段逐字声明：
 *   本面只扫**客户端包**，管理端 / 经络师端的退款措辞是**合法的内部业务词汇**。
 * 故本文件可以直呼"退款域"；端 C 同名文件则必须用指代。这是词表适用范围
 * 本就如此界定的，不是"少做一道检查"。
 */

import { ENDPOINTS, END_TOKEN_ROLES, endpointById, type Endpoint } from './endpoints';

/** 本端可用的角色。由生成物的 token-roles 收紧而来，不另立声明。 */
export type AppRole = 'therapist' | 'meridian';

/**
 * 角色显示名。这是**唯一的**手写内容，且只是给界面看的标签；
 * 它不参与任何准入判定（判定只看 `grantedRoles`）。
 */
export const ROLE_LABEL: Readonly<Record<AppRole, string>> = Object.freeze({
  therapist: '调理师',
  meridian: '经络师',
});

/** 失败原因：两种必须分开（见文件头）。 */
export type AccessFailure =
  | { kind: 'NOT_IN_THIS_END'; operationId: string }
  | { kind: 'ROLE_NOT_GRANTED'; operationId: string; role: string; grantedRoles: readonly string[] }
  | { kind: 'UNKNOWN_ROLE'; role: string; accepted: readonly string[] };

export class AccessDeniedError extends Error {
  readonly failure: AccessFailure;

  constructor(failure: AccessFailure) {
    super(describeAccessFailure(failure));
    this.name = 'AccessDeniedError';
    this.failure = failure;
  }
}

/** 人话版本的失败描述。契约要求报错不得模糊 ⇒ 必须点名。 */
export function describeAccessFailure(f: AccessFailure): string {
  switch (f.kind) {
    case 'NOT_IN_THIS_END':
      return `contract: 端点 ${f.operationId} 不属于本端（契约未授予 ${END_TOKEN_ROLES.join(' / ')}）。`
        + ' 这不是权限问题，而是前端代码引用了错误的端点名 —— 请核对生成物或重跑生成器。';
    case 'ROLE_NOT_GRANTED':
      return `contract: 角色 ${f.role} 未被授予端点 ${f.operationId}`
        + `（该端点仅授予 ${f.grantedRoles.join(' / ')}）。`
        + ' 这是正常的角色边界，界面应【不渲染】该入口。';
    case 'UNKNOWN_ROLE':
      return `contract: 未知角色 ${f.role}（本端只接受 ${f.accepted.join(' / ')}）。`
        + ' 通常意味着拿错了端的 token —— 请确认 token 的 client_end 是否为 app。';
  }
}

/** 生成物里声明的本端角色白名单（运行期用它做收窄）。 */
export const ACCEPTED_ROLES: readonly string[] = END_TOKEN_ROLES;

export function isAppRole(role: unknown): role is AppRole {
  return typeof role === 'string' && (ACCEPTED_ROLES as readonly string[]).includes(role);
}

/**
 * 把任意角色字符串收窄为本端角色。
 * 🛑 拿不到就**抛**（fail-closed）：静默当成零权限会表现为"界面全空"，
 *    让人去查数据而不是查 token —— 那正是本仓反复防的排查方向偏离。
 */
export function requireAppRole(role: unknown): AppRole {
  if (!isAppRole(role)) {
    throw new AccessDeniedError({ kind: 'UNKNOWN_ROLE', role: String(role), accepted: ACCEPTED_ROLES });
  }
  return role;
}

// ---------------------------------------------------------------------------
// 角色 → 可用端点（每次现算，不缓存手写清单）
// ---------------------------------------------------------------------------

/** 某角色在本端可用的全部端点（机械筛选生成物）。 */
export function endpointsForRole(role: AppRole): readonly Endpoint[] {
  return ENDPOINTS.filter((e) => (e.grantedRoles as readonly string[]).includes(role));
}

/** 某角色在本端【不可用】的端点（= 属于本端但未授予该角色）。 */
export function endpointsDeniedForRole(role: AppRole): readonly Endpoint[] {
  return ENDPOINTS.filter((e) => !(e.grantedRoles as readonly string[]).includes(role));
}

/** 本端点 id → 它的准入角色（未在本端则返回 null）。 */
export function grantedRolesOf(operationId: string): readonly string[] | null {
  const e = endpointById(operationId);
  return e ? e.grantedRoles : null;
}

/**
 * 取一个**确实被授予**该端点的角色（机械推导，不写常量）。
 *
 * 🛑 存在的唯一理由：登录前我们不知道也不该猜自己是谁（见 `session.ts`），
 *    但 `call()` 的 `role` 是必填的。此时需要"某个肯定能过准入的角色"。
 *    若在调用点写死 `'therapist'`，那就在 `access.ts` 之外造出了**第一个**
 *    角色字面量 —— 之后每个调用点都会各自写一个，单一权威面随即瓦解
 *    （端 B 的 X-3 门禁 `x3-single-authority` 就是专门抓这件事的，
 *     本条注释诞生于它第一次报红）。
 * ⇒ 故选**从 grantedRoles 里现取第一个**：契约改了、它自动跟着改，
 *    且字面量永远只存在于 `endpoints.ts`（生成物）与 `access.ts`（权威面）。
 *
 * @returns 一个被授予该端点的角色；该端点不属本端时返回 null（调用点应改代码）。
 */
export function anyGrantedRoleFor(operationId: string): AppRole | null {
  const granted = grantedRolesOf(operationId);
  if (!granted) return null;
  const first = granted.find((r) => isAppRole(r));
  return first ?? null;
}

/**
 * 准入判定（唯一入口）。返回 true 才允许出站。
 * @param role 运行期从 A2 / 登录响应拿到的角色（任意字符串，内部收窄）
 */
export function canCall(operationId: string, role: string): boolean {
  const granted = grantedRolesOf(operationId);
  if (granted === null) return false;
  return (granted as readonly string[]).includes(role);
}

/**
 * 断言准入。**不过就抛**，且把三种失败分开（见文件头）。
 *
 * 判定顺序刻意如此：
 *   ① 角色本身不是本端接受的（`UNKNOWN_ROLE`）—— 通常意味着**拿错了端的 token**
 *      （例如客户端 token 打到了端 B）。这一条必须先查：否则它会被误报成
 *      `ROLE_NOT_GRANTED`，让人去改权限，而真实原因是登录入口错了。
 *   ② 端点不属本端（`NOT_IN_THIS_END`）—— **代码缺陷**（写错了端点名）。
 *   ③ 端点属本端但当前角色未被授予（`ROLE_NOT_GRANTED`）—— **正常角色边界**。
 *
 * 🛑 三种都不合并成一句"无权限"：契约 `forbidden-403` 条逐字要求
 *    「必须给出缺失项名称 / 档位名称（不得模糊报错）」，本层沿用该口径。
 */
export function assertCanCall(operationId: string, role: string): void {
  if (!isAppRole(role)) {
    throw new AccessDeniedError({ kind: 'UNKNOWN_ROLE', role, accepted: ACCEPTED_ROLES });
  }
  const granted = grantedRolesOf(operationId);
  if (granted === null) {
    throw new AccessDeniedError({ kind: 'NOT_IN_THIS_END', operationId });
  }
  if (!(granted as readonly string[]).includes(role)) {
    throw new AccessDeniedError({
      kind: 'ROLE_NOT_GRANTED',
      operationId,
      role,
      grantedRoles: granted,
    });
  }
}

// ---------------------------------------------------------------------------
// 给界面用的分组（只做展示归类，不做准入判定）
// ---------------------------------------------------------------------------

/**
 * 按契约的 `x-contract-row` 首位字母分组（域 A~I）。
 * 🛑 域名字是**展示标签**；某端点归哪个域取自生成物的 `row`，不手写。
 */
export const DOMAIN_LABEL: Readonly<Record<string, string>> = Object.freeze({
  A: '身份与合作门店',
  B: '客户建档',
  C: '量表与评估',
  D: '服务与方案',
  E: '手环数据',
  F: '判定结论',
  G: '退款与挽留',
});

export interface DomainGroup {
  readonly domain: string;
  readonly label: string;
  readonly endpoints: readonly Endpoint[];
}

/** 某角色可见的端点，按域归组（域内按 row 排序）。 */
export function groupByDomain(role: AppRole): readonly DomainGroup[] {
  const buckets = new Map<string, Endpoint[]>();
  for (const e of endpointsForRole(role)) {
    const d = (e.row || '?').charAt(0) || '?';
    const list = buckets.get(d) ?? [];
    list.push(e);
    buckets.set(d, list);
  }
  return [...buckets.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([domain, endpoints]) => ({
      domain,
      label: DOMAIN_LABEL[domain] ?? `域 ${domain}`,
      endpoints: [...endpoints].sort((a, b) => a.row.localeCompare(b.row)),
    }));
}

// ---------------------------------------------------------------------------
// 自证：本层与生成物必须一致（供构建自检与界面展示）
// ---------------------------------------------------------------------------

export interface AccessSummary {
  readonly contractVersion: string;
  readonly roles: readonly string[];
  readonly totalInThisEnd: number;
  readonly perRole: Readonly<Record<string, number>>;
  readonly meridianOnly: readonly string[];
  readonly therapistOnly: readonly string[];
}

/**
 * 现算一份准入概览。
 * 🛑 `meridianOnly` / `therapistOnly` 都是由生成物**现算**的，
 *    不写死"7 个"这种会过期的数字 —— 契约新增端点后它会自动变。
 */
export function summarizeAccess(contractVersion: string): AccessSummary {
  const merOnly: string[] = [];
  const therOnly: string[] = [];
  const perRole: Record<string, number> = {};
  for (const r of ACCEPTED_ROLES) perRole[r] = 0;

  for (const e of ENDPOINTS) {
    const g = e.grantedRoles as readonly string[];
    for (const r of g) if (r in perRole) perRole[r] += 1;
    if (g.length === 1 && g[0] === 'meridian') merOnly.push(e.id);
    if (g.length === 1 && g[0] === 'therapist') therOnly.push(e.id);
  }
  return {
    contractVersion,
    roles: ACCEPTED_ROLES,
    totalInThisEnd: ENDPOINTS.length,
    perRole,
    meridianOnly: merOnly,
    therapistOnly: therOnly,
  };
}