/**
 * 端 A · 管理后台范围与约束（契约机械推导，不手抄）
 * ============================================================================
 *
 * 本文件回答三个问题，且答案**全部来自生成物** `./endpoints.ts`
 * （而生成物又是从冻结契约机械转录的）：
 *
 *   ① 这个端点在契约里有没有**未完结的声明**？（`x-frontier` / `x-ruling-pending`）
 *   ② 这个端点对**不同 admin 子档位**的行级范围是否不同？（`x-row-scope`）
 *   ③ 这个端点是否**仅超管**？（`x-super-admin-only`）
 *
 * 🛑 为什么端 A 的口径与端 B 的 X-3 不一样（不是"少做了一道检查"）
 * ---------------------------------------------------------------------------
 * 端 A 的 39 个端点 **全部** `grantedRoles = ["admin"]` —— 在**端点级**没有任何角色分叉，
 * 所以端 B 那套"角色 → 端点"的准入矩阵在这里**恒真**，检它等于没检。
 * 端 A 真正的边界在另外两层，且两层都在**服务端**：
 *   · **行级范围**（x-row-scope）：同一端点，门店负责人只看到本店、区域督导只看到辖区、
 *     总部看到全量 —— 调用是**同一个**，差别在**返回行数**。前端**无法**也不该
 *     自行裁剪（自行裁剪就是造第二个裁剪点，与服务端不一致时无从判断谁对）。
 *   · **子档位**（x-super-admin-only / x-ruling-pending）：I 域仅超管；G4 审批
 *     白名单仍是**推断项**。
 * ⇒ 本层的职责不是"拦调用"，而是**把契约的未完结状态如实暴露给使用者**：
 *    让"占位待冻结"在界面上**写着"占位"**，而不是看起来像一个已冻结的功能。
 *
 * 🛑 丢掉这些元信息的后果（本仓第 54 条系统性缺陷）
 * ---------------------------------------------------------------------------
 * 生成器初版只转出 id / row / method / path / grantedRoles，把上面 4 类 x- 键**丢了**。
 * 丢掉的表现是**完全静默**的：生成物看起来正常，`--check` 也绿，
 * 只有人肉读契约才发现"界面少了一道约束"。
 * 与第 50 条同族：**契约写下的约束**与**前端拿到的约束**是两件事，
 * 中间任何一环丢项都不会报错。故本文件的存在本身就是那条缺陷的修法落点。
 *
 * 🛑 为什么角色展开表（ROLE_EXPANSION）必须来自契约
 * ---------------------------------------------------------------------------
 * 契约 `x-roles.admin.token-role = [manager, area, hq]` 是声明；
 * 前端手写一份 `{admin: ['manager','area','hq']}` 就是第二份清单（第 46 条同型）。
 * 故一律读 `ROLE_EXPANSION`（生成物里已从契约转录）。
 */

import {
  ENDPOINTS,
  END_TOKEN_ROLES,
  ROLE_EXPANSION,
  endpointById,
  type Endpoint,
  type RoleExpansion,
} from './endpoints';

/** 本端角色（生成物声明的 token-roles，不另立）。 */
export type ConsoleRole = string;

/** 本端角色白名单（运行期收窄用）。 */
export const ACCEPTED_ROLES: readonly string[] = END_TOKEN_ROLES;

export function isConsoleRole(role: unknown): role is ConsoleRole {
  return typeof role === 'string' && (ACCEPTED_ROLES as readonly string[]).includes(role);
}

/** `admin` 这一角色由哪几种子档位构成 —— 逐条取自契约 x-roles。 */
export const ADMIN_TOKENS: readonly string[] = ROLE_EXPANSION['admin']?.tokens ?? [];

export function roleExpansionOf(role: string): RoleExpansion | null {
  return ROLE_EXPANSION[role] ?? null;
}

// ---------------------------------------------------------------------------
// 契约未完结状态：必须能被查到，且必须能被界面如实显示
// ---------------------------------------------------------------------------

/**
 * 契约对某端点**尚未完结**的声明的种类。
 * 🛑 两者语义不同，不得合并显示：
 *   · `frontier`       —— 「占位待冻结」：契约**内容还没写完**，功能属未来。
 *   · `ruling-pending` —— 「取值系推断、待裁定」：契约**内容写好了，但依据是推断**，
 *                          功能可能已生效，只是取值可能变。
 * 合并成一句"未定"会让使用者分不清"现在能不能用"。
 */
export type Unsettled =
  | { kind: 'frontier'; note: string }
  | { kind: 'ruling-pending'; note: string };

export function unsettledOf(e: Endpoint): readonly Unsettled[] {
  const out: Unsettled[] = [];
  if (e.frontier) out.push({ kind: 'frontier', note: e.frontier });
  if (e.rulingPending) out.push({ kind: 'ruling-pending', note: e.rulingPending });
  return out;
}

/** 契约尚未完结的全部端点（供首页"待冻结 / 待裁定"两栏呈现）。 */
export function unsettledEndpoints(): readonly { endpoint: Endpoint; items: readonly Unsettled[] }[] {
  const out: { endpoint: Endpoint; items: readonly Unsettled[] }[] = [];
  for (const e of ENDPOINTS) {
    const items = unsettledOf(e);
    if (items.length) out.push({ endpoint: e, items });
  }
  return out;
}

/**
 * 契约未完结端点里**最前面一个 `x-frontier`** 的 operationId —— 供导航项以
 * **机械推导形态**声明依赖（`requires: firstFrontierEndpointId()`）。
 *
 * 🛑 为什么用推导函数而不是写死 `'listDocTemplates'`（两个理由，都非风格问题）
 * ---------------------------------------------------------------------------
 * ① **写死即为第二份权威**：契约若把 I 域整体转出 `x-frontier`，写死的那个 id
 *    会**静默**变成一条普通端点，导航项不再携带"本页依赖未完结事实"的语义。
 *    推导函数的语义是"本端存在 frontier 端点"；契约清空后它返回 `null`，
 *    导航项随之变为"不依赖端点" —— 这正是想要的。
 *
 * ② **导航表里不应出现端点 id 字面量**：导航表是外壳组件，它**无从渲染任何
 *    提示条**。若为了过 `unsettled-surfaced` 判据而在导航表里写一行"标注"，
 *    那行标注不产生任何界面效果 —— 等于把判据变成**纸面合规**。
 *    本函数返回**运行时值**，文件里不出现字面量 ⇒ 导航表自然不在判据的
 *    "引用了未完结端点"集合里，判据不必为它开例外。
 *    **这是「正确地绕开」，不是「绕过」。**
 *
 * 🛑 本函数同时是 `tools/a-check.mjs` ⑨ `nav-requires` 判据的白名单成员
 * ---------------------------------------------------------------------------
 * 该判据要求：`requires` 只有两种合法形态 ——
 *   ① 生成物里存在的端点 id 字符串字面量；
 *   ② 本文件导出的推导函数调用（`requires: <ident>()`）。
 * 两种形态的计数之和**必须等于**非 null 的 `requires` 条数：
 * 这条"计数等式"是用来防"新增第三种形态导致静默漏检"的（第 53 条教训：
 * 判据覆盖面没跟上新写法，表现是静默少查而非报错）。
 */
export function firstFrontierEndpointId(): string | null {
  const list = unsettledEndpoints().filter((x) => x.items.some((i) => i.kind === 'frontier'));
  return list.length ? list[0].endpoint.id : null;
}

// ---------------------------------------------------------------------------
// 行级范围：随子档位变化，且【只能由服务端执行】
// ---------------------------------------------------------------------------

/** 契约是否有声明行级范围（仅 F3 / F4 两个端点有）。 */
export function hasRowScope(operationId: string): boolean {
  const e = endpointById(operationId);
  return !!(e && e.rowScope);
}

export function rowScopeOf(operationId: string): string | null {
  const e = endpointById(operationId);
  return (e && e.rowScope) ? e.rowScope : null;
}

/** 声明了行级范围的端点（现算，不写死 2 个）。 */
export function rowScopedEndpoints(): readonly Endpoint[] {
  return ENDPOINTS.filter((e) => !!e.rowScope);
}

// ---------------------------------------------------------------------------
// 仅超管
// ---------------------------------------------------------------------------

export function isSuperAdminOnly(operationId: string): boolean {
  const e = endpointById(operationId);
  return !!(e && e.superAdminOnly === true);
}

export function superAdminOnlyEndpoints(): readonly Endpoint[] {
  return ENDPOINTS.filter((e) => e.superAdminOnly === true);
}

// ---------------------------------------------------------------------------
// 分域（展示用，不做判定）
// ---------------------------------------------------------------------------

/**
 * 域标签。端 A 覆盖 8 个域（A/B/C/D/E/F/G/I）—— 比端 B 多 F（稽核）与 I（文书模板）。
 * 🛑 注意端 A **没有 H 域**（H1 是电子签厂商回调，`x-callable-roles` 为空 ⇒ 不属于任何端）。
 */
export const DOMAIN_LABEL: Readonly<Record<string, string>> = Object.freeze({
  A: '身份与合作门店',
  B: '客户建档',
  C: '量表与评估',
  D: '服务与方案',
  E: '手环数据',
  F: '判定与稽核',
  G: '退款与挽留',
  I: '文书模板',
});

export interface DomainGroup {
  readonly domain: string;
  readonly label: string;
  readonly endpoints: readonly Endpoint[];
}

export function groupByDomain(): readonly DomainGroup[] {
  const buckets = new Map<string, Endpoint[]>();
  for (const e of ENDPOINTS) {
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
// 自证概览（数字全部现算，不写死）
// ---------------------------------------------------------------------------

export interface ConsoleSummary {
  readonly contractVersion: string;
  /** 本端 token-roles（生成物声明）。 */
  readonly roles: readonly string[];
  /** `admin` 由哪几种子档位构成（契约 x-roles 展开）。 */
  readonly adminTokens: readonly string[];
  readonly totalInThisEnd: number;
  readonly domainCount: number;
  readonly rowScoped: readonly string[];
  readonly superAdminOnly: readonly string[];
  readonly unsettled: readonly string[];
}

export function summarizeConsole(contractVersion: string): ConsoleSummary {
  return {
    contractVersion,
    roles: ACCEPTED_ROLES,
    adminTokens: ADMIN_TOKENS,
    totalInThisEnd: ENDPOINTS.length,
    domainCount: groupByDomain().length,
    rowScoped: rowScopedEndpoints().map((e) => e.row),
    superAdminOnly: superAdminOnlyEndpoints().map((e) => e.row),
    unsettled: unsettledEndpoints().map((x) => x.endpoint.row),
  };
}