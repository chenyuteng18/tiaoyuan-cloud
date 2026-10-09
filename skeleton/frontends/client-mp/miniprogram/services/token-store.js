/**
 * 端 C · 客户小程序 —— 本地存储（**唯一权威点**）
 * ============================================================================
 *
 * 🛑 为什么单独一个文件，而不是让 session.js / request.js / app.js 各写各的
 * ---------------------------------------------------------------------------
 * 本仓第 50 条记过一次真实缺陷：端 A 的 `session.ts` 写 `'dy.token'`、`api/client.ts`
 * 读 `'token'` —— 键名不一致 ⇒ 出站 `Authorization` 头**永远为空** ⇒ 全量 401，
 * 而 tsc / 构建 / 既有判据**一律不报**。修法是"把键名收敛到一处 + 加判据守住"。
 *
 * 🛑 端 C 在 2026-10-09（批次 G · 本仓第 84 条）实测有同一形态，且**四处**：
 *     · `app.js` 三处（onLaunch / onShow / resolveCustomerId 各自读一次）；
 *     · `services/request.js` 一处 —— 且这一处【在出站层】：给请求装 Authorization
 *       头的那一步自己读键名，绕开了会话层。
 *   当时它与 `session.js` 的 `TOKEN_KEY` **恰好同值**（都是 `'token'`）⇒ 没有暴露；
 *   改一处即静默分叉。而端 A / 端 B / 端 D 三端都已有这一条守护，端 C 是唯一缺口 ——
 *   因为守护判据当时把扫描根写死成 `src/`，而端 C 的源码在 `miniprogram/`
 *   （详见 `tools/build-check.mjs` ④b 的段落）。
 *
 * 🛑 本文件与端 A 的 `services/token-store.ts` 是**同一职责**，不是巧合：
 *    要统一的是"**同一端内**写与读必须是同一个键"；三端的**存储介质本就不同**
 *    （小程序没有 localStorage），故端 C 用 `wx.getStorageSync` 系，键名不必跨端统一。
 *
 * 🛑 小程序本地存储**不是**可信边界
 * ---------------------------------------------------------------------------
 * 它可由用户清理、也可能被同设备其它来源读到。故它只用于"把凭证带上请求头"，
 * **不作为任何权限依据**。真正的边界是服务端的签名校验（见 session.js 同节说明）。
 */

'use strict';

var TOKEN_KEY = 'token';
var PROFILE_KEY = 'profile';

/** 读取令牌。无令牌时返回空串（**不返回 null**，避免调用点各自处理两种空值）。 */
function readToken() {
  try {
    return wx.getStorageSync(TOKEN_KEY) || '';
  } catch (e) {
    // 存储不可用（配额满 / 被禁用）：返回空串，让请求以未鉴权形态发出，由服务端 401 兜底。
    return '';
  }
}

function writeToken(token) {
  try {
    wx.setStorageSync(TOKEN_KEY, token || '');
  } catch (e) {
    /* 写失败不应中断登录：本次会话在内存里仍可用（session.js 的返回值带着 token）。 */
  }
}

/** 清两项：令牌与档案缓存必须一起清，否则"已登出但还显示旧档案"是静默的错。 */
function clearToken() {
  try {
    wx.removeStorageSync(TOKEN_KEY);
    wx.removeStorageSync(PROFILE_KEY);
  } catch (e) {
    /* 同上 */
  }
}

/** 缓存的 /auth/me 结果，仅在离线时用于展示，**不参与任何判定**。 */
function readProfile() {
  try {
    return wx.getStorageSync(PROFILE_KEY) || null;
  } catch (e) {
    return null;
  }
}

function writeProfile(profile) {
  try {
    wx.setStorageSync(PROFILE_KEY, profile || null);
  } catch (e) {
    /* 同上 */
  }
}

module.exports = {
  // 键名本身**只有本文件一处定义**；导出供门禁与自证读取（`build-check.mjs` ④b）。
  TOKEN_STORAGE_KEY: TOKEN_KEY,
  PROFILE_STORAGE_KEY: PROFILE_KEY,
  readToken: readToken,
  writeToken: writeToken,
  clearToken: clearToken,
  readProfile: readProfile,
  writeProfile: writeProfile,
};
