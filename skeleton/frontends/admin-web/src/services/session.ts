/**
 * 端 A · 会话与档位上下文
 * ============================================================================
 *
 * 职责：**把"当前是谁、是哪个子档位、可见行级范围是什么"收在一个地方**。
 *
 * 🛑 端 A 与端 B 的会话层【关键差异】：本端只有一个角色（admin），
 *    但**有三个子档位**（manager / area / hq），且它们的差别不在"能调哪些端点"，
 *    而在**服务端返回多少行**。
 * ---------------------------------------------------------------------------
 * 契约顶层 `x-roles.admin.token-role = [manager, area, hq]`，
 * 而 39 个端点**全部** `x-callable-roles = [admin]`（实测 39/39）。
 * ⇒ 端 B 那套"角色 → 端点"的准入矩阵在端 A **恒真**，检它无意义（`a-check.mjs` ② 会打印这一点）。
 * ⇒ 端 A 真正的边界是两层，**都在服务端**：
 *      ① **行级范围**：`A2 /auth/me` 的 `store_scope.row_level`（`own_store` / `region` / `all`）
 *         + `store_ids`。同一端点，不同档位返回的行数不同。
 *      ② **子档位开关**：`x-super-admin-only`（I7 仅超管）与
 *         `x-ruling-pending`（G4 审批白名单系推断、待裁定）。
 *
 * 🛑 因此本层【不】推断行级范围，也不在本地裁剪列表
 * ---------------------------------------------------------------------------
 *   · **不推断**：哪个 token-role 对应哪个 row_level 是服务端解算结果（A2 下发），
 *     本地解 token 载荷得到的是**签发时的声明**，会与解算结果漂移。
 *     一旦本地解出的档位与服务端不一致，就会出现"界面显示能看、请求却 403"，
 *     而这类矛盾**无法从界面自证**。
 *   · **不裁剪**：界面上"把不属于本店的行过滤掉"就是造第二个裁剪点。
 *     与服务端不一致时无从判断谁对；且用户会以为"数据只有这些"。
 *     ⇒ 一律显示服务端返回的全部行，并**明确标注当前行级范围**。
 *
 * 🛑 退款可见性（`refund_visibility`）在端 A 恒为 true 且语义与端 B 不同
 * ---------------------------------------------------------------------------
 * 契约 `AuthMeData.refund_visibility` 的 `x-visible-to` 是 `[meridian, admin]`，
 * 描述逐字含「调理师 false（config ...）」。端 A 是 **admin**，故本端该字段
 * 恒为 true。但它**不代表**"所有 admin 都能看所有退款工单"：
 * 退款工单同样受行级范围约束（在本店 / 辖区 / 全量内），
 * 且 G4 审批（`approveRefund`）另带 `x-ruling-pending`。
 * ⇒ 本层把这两个独立维度分开保存，**不合并成一个布尔**。
 */

import { call } from '../api/client';
import { ACCEPTED_ROLES, ADMIN_TOKENS, isConsoleRole, type ConsoleRole } from '../contract/scope';
import { clearToken, readToken, writeToken } from './token-store';

/** 会话缓存键（**档位缓存，不是令牌** —— 令牌的键名只在 token-store.ts 一处）。 */
const PROFILE_KEY = 'dy.profile';

/** A2 下发的字段组档位（布尔声明）。字段名逐字抄契约 `AuthMeData.band_visibility`。 */
export interface BandVisibility {
  readonly field_group_1_raw: boolean;
  readonly field_group_2_status: boolean;
  readonly field_group_3_gap_reason: boolean;
  readonly field_group_4_derived: boolean;
}

/**
 * 行级范围（契约 `AuthMeData.store_scope`）。
 * 🛑 枚举逐字；`store_ids` 是服务端解算出的可见门店集合。
 */
export interface StoreScope {
  readonly row_level: 'own_store' | 'region' | 'all';
  readonly store_ids: readonly string[];
}

/** A2 响应（`AuthMeData` 的子集，仅契约声明的字段）。 */
export interface Profile {
  readonly role: string;
  readonly band_visibility: BandVisibility;
  /**
   * 退款可见性档位。契约 `x-visible-to` = `[meridian, admin]`。
   * 🛑 端 A 恒为 true；契约描述里的「调理师 false」是**端 B 的事**，与本端无关。
   *    不要因为看到 false 就去改本端判断 —— 本端读到的就是 true。
   */
  readonly refund_visibility?: boolean;
  readonly store_scope: StoreScope;
}

/** A1 登录请求体（字段名逐字对齐端 B 已核验的形状）。 */
export interface LoginRequest {
  readonly account: string;
  readonly credential: string;
  /** 逐字：本端是 `web`（契约 `x-roles.admin.end`）。 */
  readonly client_end: string;
}

/** A1 响应（契约 `LoginData`）。 */
export interface LoginResult {
  readonly token: string;
  readonly expires_in: number;
  readonly role: string;
  readonly client_end: string;
  readonly tenant_id: string;
  readonly staff_id: string;
}

/**
 * A1 登录。
 * 🛑 `client_end` 写死为 `'web'`，不提供选择器
 * ---------------------------------------------------------------------------
 * 契约顶层 `x-roles.admin.end = web` 是**声明**。若界面允许选 `app` / `mp`，
 * 就等于允许用管理端身份去登录一个非本端的会话 —— 服务端会拒，
 * 但这属于"界面把一个不可能的选项摆出来"。故写死，并在类型上就是 `string`
 * （不写 `'app' | 'mp' | 'web'` 联合，避免有人误传）。
 */
export function login(account: string, credential: string): Promise<LoginResult | undefined> {
  return call<LoginResult>('authLogin', {
    body: { account, credential, client_end: 'web' } satisfies LoginRequest,
  }).then((r) => r.data);
}

/** A2 当前身份（**档位唯一权威下发点**）。 */
export function fetchProfile(): Promise<Profile | undefined> {
  return call<Profile>('authMe').then((r) => r.data);
}

/**
 * 令牌读写**一律转交 token-store.ts**（唯一权威点）。
 * 🛑 这三个函数保留在本层是为了让调用方只认识 `session` 一个入口；
 *    但它们**内部不得出现任何键名字面量** —— 键名只有 token-store.ts 有一份。
 */
export function saveToken(token: string): void {
  writeToken(token);
}

export function getToken(): string {
  return readToken();
}

export function saveProfile(p: Profile): void {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(p));
}

export function getCachedProfile(): Profile | null {
  const raw = localStorage.getItem(PROFILE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Profile;
  } catch {
    return null;
  }
}

export function currentRole(): ConsoleRole | null {
  const p = getCachedProfile();
  if (!p || !isConsoleRole(p.role)) return null;
  return p.role;
}

export function logout(): void {
  clearToken();
  localStorage.removeItem(PROFILE_KEY);
}

/**
 * 本端角色与子档位（自证用）。
 * 🛑 这两个数字**都来自生成物**（而生成物来自契约），不是写死的。
 *    契约若把 admin 的 token-role 改掉，这里会跟着变 —— 这正是
 *    `a-check.mjs` ⑥ `role-expansion` 判据要确保的事。
 */
export function consoleIdentity(): { roles: readonly string[]; adminTokens: readonly string[] } {
  return { roles: ACCEPTED_ROLES, adminTokens: ADMIN_TOKENS };
}

/**
 * 当前行级范围的展示信息。
 * 🛑 取不到 A2 时返回 `null`，界面必须**显示"未知"而不是默认全量**
 *    —— 把"未知"当"全量"会让使用者以为能看全部，进而把"查不到"误判为
 *    "不存在"（而非"范围外"）。
 */
export function currentStoreScope(): StoreScope | null {
  const p = getCachedProfile();
  return p?.store_scope ?? null;
}