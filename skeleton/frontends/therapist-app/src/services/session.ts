/**
 * 端 B · 会话与角色上下文
 * ============================================================================
 *
 * 职责：**把"当前是谁、是哪个角色、有哪些档位"这件事收在一个地方**。
 *
 * 🛑 为什么角色必须来自服务端 A2，而不是本地解码 token 猜
 * ---------------------------------------------------------------------------
 * 契约逐字：A2 `/auth/me` 是「当前身份 + 角色 + 可见性档位解算结果
 * （**可见性档位唯一权威下发点**）」。本地解 token 载荷得到的只是**签发时的声明**，
 * 而档位会随配置（config #44 等）变化 —— 一旦本地解出的角色/档位与服务端解算不一致，
 * 就会出现"界面显示能看、请求却 403"这类无法自证的矛盾。
 * 故本层：**角色与档位一律读 A2；本地只缓存，不推断。**
 *
 * 🛑 本层【不】做字段组可见性的静态表（X-1）
 * ---------------------------------------------------------------------------
 * 四个字段组档位由 A2 的 `band_visibility` 下发（布尔声明，不是数据）。
 * 本层只**转存**，不判定、不加上自己的规则 —— 加规则就是造第二份权威。
 *
 * 🛑 注意：本端可见 ③④ 两组，端 C 不可见（契约矩阵逐字）
 * ---------------------------------------------------------------------------
 * `x-visibility-matrix` 实测：`gap_reason` / `derived_result` 两组
 * 对 `client` 为 false，对 `therapist` / `meridian` / `admin` 为 true。
 * 这条**不在这里实现**（服务端裁剪即事实），此处仅记录：一旦看到本端某字段缺失，
 * 应先怀疑契约矩阵与 A2 档位，而不是先怀疑"后端漏发了"。
 */

import { call } from '../api/client';
import { anyGrantedRoleFor, requireAppRole, type AppRole } from '../contract/access';

const TOKEN_KEY = 'dy.token';
const PROFILE_KEY = 'dy.profile';

/** A2 下发的字段组档位（布尔声明）。字段名逐字抄契约 `AuthMeData.band_visibility`。 */
export interface BandVisibility {
  readonly field_group_1_raw: boolean;
  readonly field_group_2_status: boolean;
  readonly field_group_3_gap_reason: boolean;
  readonly field_group_4_derived: boolean;
}

export interface StoreScope {
  /** 行级 scope：本店 / 辖区 / 全量。逐字抄契约枚举。 */
  readonly row_level: 'own_store' | 'region' | 'all';
  readonly store_ids: readonly string[];
}

/** A2 响应（`AuthMeData` 的子集，仅契约声明的字段）。 */
export interface Profile {
  readonly role: string;
  readonly band_visibility: BandVisibility;
  /**
   * 退款可见性档位。契约 `x-visible-to` = `[meridian, admin]` ——
   * **调理师这一档不下发**（服务端不发这个字段，不是发 false）。
   * 故本端类型为可选：调理师拿到的是 `undefined`。
   * 🛑 不要把 `undefined` 当成 false 用：两者语义不同 ——
   *    · `undefined` = 服务端未下发该字段（角色无档位）；
   *    · `false`    = 下发了、且明确为否。
   *    本端界面对两种情况都**不渲染退款入口**，但**理由不同**，不能混写。
   */
  readonly refund_visibility?: boolean;
  /** store_scope 对 client 不下发；本端两角色都会拿到。 */
  readonly store_scope?: StoreScope;
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(PROFILE_KEY);
}

export function getCachedProfile(): Profile | null {
  const raw = localStorage.getItem(PROFILE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Profile;
  } catch {
    // 缓存损坏不抛：当作"没有缓存"，由 A2 重新拉一次即可。
    localStorage.removeItem(PROFILE_KEY);
    return null;
  }
}

function cacheProfile(p: Profile): void {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(p));
}

/** 当前角色（从缓存读）。未登录返回 null，**不猜**。 */
export function currentRole(): AppRole | null {
  const p = getCachedProfile();
  if (!p) return null;
  try {
    return requireAppRole(p.role);
  } catch {
    return null;
  }
}

/**
 * 登录（A1）。成功后**立即用 A1 返回的角色拉一次 A2**。
 *
 * 🛑 为什么必须用 A1 的 role 去拉 A2，而不是本地"先猜一个"
 * ---------------------------------------------------------------------------
 * 登录前我们**不知道**登录进来的是调理师还是经络师 —— 这是事实，不该用默认值掩盖。
 * A1 的 `LoginData` 正好带 `role`（契约逐字），故顺序是：
 *   A1（拿 token + role） → 收窄 role → A2（拿权威档位）→ 缓存 Profile。
 *
 * 🛑 为什么不拿 A1 的 role 直接**构造**一个 Profile：A1 **不带档位**
 *    （`band_visibility` / `refund_visibility` 只在 `AuthMeData` 里）。
 *    构造出来的 Profile 会把档位缺省成 false ⇒ 表现为"登录成功了但界面全空"，
 *    而排查者会去查数据，不会去查"档位从没下发过"。
 */
export async function login(account: string, credential: string): Promise<Profile> {
  // 🛑 请求体字段名**逐字**取自契约内联 schema（`authLogin` 的 requestBody）：
  //      required: ["account", "credential", "client_end"]
  //      · account    员工号 / 客户 openid 绑定号
  //      · credential 密码 / 短信码
  //      · client_end "mp" | "app" | "web"  ⇒ 端 B 恒为 "app"
  //    ⚠️ 本层初版曾写成 `{username, password, tenant_id}` —— 那是**自创字段名**，
  //       契约里没有这三个词。契约为准：不存在 `tenant_id` 入参
  //       （租户来自 token；见 x-global-conventions.auth）。
  //       这类错误的危险之处是它**不会在本地报错**（服务端只会返回 1001），
  //       排查者会去查账号密码，而真实原因是字段名写错了。
  // 登录前的准入占位角色：**从生成物现取**，不在本文件写角色字面量。
  // 理由见 `anyGrantedRoleFor` 的注释 —— 在调用点写死角色字面量会让
  // "角色判断只存在于一个权威面"这条纪律瓦解。
  // ⚠️ 若此处返回 null，说明 A1 已不属于本端，那是契约变更 ⇒ 必须显式报错而不是猜。
  const placeholderRole = anyGrantedRoleFor('authLogin');
  if (!placeholderRole) {
    throw new Error(
      'contract: authLogin 不在本端生成物里（契约可能已变更）—— 请重跑 frontends/tools/gen-endpoints.py 并核对本端角色。'
    );
  }
  const res = await call<{ token: string; role: string; expires_in: number }>('authLogin', {
    role: placeholderRole,
    body: { account, credential, client_end: 'app' },
  });
  const token = res.data?.token;
  if (!token) {
    throw new Error('登录响应未包含 token（契约 LoginData.token）');
  }
  // 先收窄角色，再落盘 —— 顺序不能反：否则一个 manager token 会先被存下，
  // 然后 A2 报错，留下一个"有 token 但角色非法"的中间态。
  const role = requireAppRole(res.data?.role);
  setToken(token);
  return me(role);
}

/**
 * 拉取 A2（可见性档位**唯一权威下发点**）并缓存。
 * @param role 显式传入（登录后立即调用时用 A1 的 role）；省略则读本地缓存的角色。
 *             **两者都没有就抛**，不回落默认值 —— 未登录就该报"未登录"。
 */
export async function me(role?: string): Promise<Profile> {
  const effectiveRole = role ?? currentRole();
  if (!effectiveRole) {
    throw new Error('会话：尚未登录（无法确定角色），请先登录。不回落默认角色。');
  }
  const res = await call<Profile>('authMe', { role: effectiveRole });
  const data = res.data;
  if (!data) {
    throw new Error('A2 响应缺少 data（契约 AuthMeData）');
  }
  // 服务端下发的角色才是权威：不是本端两个角色之一 ⇒ 明确抛出
  // （通常是拿错了端的 token，例如客户端 token 打到了端 B）。
  requireAppRole(data.role);
  cacheProfile(data);
  return data;
}

export function logout(): void {
  clearToken();
}