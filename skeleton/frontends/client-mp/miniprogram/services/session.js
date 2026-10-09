/**
 * 端 C · 客户小程序 —— 会话与身份（A1 / A2）
 * ============================================================================
 *
 * 职责边界（有意收窄）
 * ---------------------------------------------------------------------------
 * 本文件只做三件事：登录换取凭证、把凭证存进本地、读回「我是谁」。**不做任何
 * 字段裁剪** —— 客户能看见哪些字段由服务端 403 与 A2 档位解算决定（X-1）。
 * 前端过滤不等于安全；反过来，前端也**不许替服务端决定**该显示什么。
 *
 * 🛑 关于 A2 返回的 `band_visibility`（字段组档位）
 * ---------------------------------------------------------------------------
 * 它是**服务端下发的"档位声明"**，不是客户可自行判断的依据。本端读取它只为
 * 决定"有没有必要发起请求"，**任何一次真实可见性仍以服务端响应为准**。换言之：
 * 档位说可见而服务端拒了，界面必须如实显示拒绝，不得回退成本地缓存的旧数据。
 *
 * 🛑 凭证存储 —— **本文件不自己存**
 * ---------------------------------------------------------------------------
 * 键名与读写形态一律经 `./token-store.js`（本端唯一权威点）。
 *
 * 2026-10-09（批次 G · 本仓第 84 条）之前不是这样：本文件持有 `TOKEN_KEY`，
 * 而 `app.js`（3 处）与 `services/request.js`（1 处，**在出站层**）各自写裸键名
 * `'token'`。当时四处**恰好同值**，所以没有暴露 —— 但改一处即静默分叉，表现是
 * `Authorization` 头为空 ⇒ 全量 401，而 tsc / 构建 / 全部判据一律绿（第 50 条同族）。
 * 端 A / 端 B / 端 D 三端此前都已有这一条守护，端 C 是唯一缺口。
 *
 * 🛑 小程序本地存储**不是**可信边界（可由用户清理、也可能被同设备其他来源读取），
 * 故它只用于"带上请求头"，不作为权限依据。真正的边界是服务端的签名校验。
 */

'use strict';

var request = require('./request.js');
var tokenStore = require('./token-store.js');

function getToken() {
  return tokenStore.readToken();
}

function setToken(token) {
  tokenStore.writeToken(token);
}

function clearToken() {
  tokenStore.clearToken();
}

/** 缓存的 /auth/me 结果，仅在离线时用于展示，不参与任何判定。 */
function getCachedProfile() {
  return tokenStore.readProfile();
}

function setCachedProfile(profile) {
  tokenStore.writeProfile(profile);
}

/**
 * A1 登录。`client_end` 恒为 'mp' —— 契约把"这一端是谁"作为登录入参的一部分，
 * 服务端据此下发该端可用的档位。
 */
function login(account, credential) {
  return request.call('authLogin', {
    body: { account: account, credential: credential, client_end: 'mp' },
  }).then(function (res) {
    var data = res.data || {};
    var token = data.token || '';
    if (!token) {
      throw new Error('登录失败：服务端未返回凭证');
    }
    setToken(token);
    return { token: token, profile: data };
  });
}

/** A2 当前身份。每次进入需要档位的页面都应重新取一次，不用本地缓存当权威。 */
function me() {
  return request.call('authMe').then(function (res) {
    var profile = res.data || null;
    setCachedProfile(profile);
    return profile;
  });
}

function logout() {
  clearToken();
}

/**
 * 需要登录才能继续的页面在 onLoad 里调用它。
 * 返回 true 表示可以继续；false 表示已跳转登录页（调用方应 return）。
 */
function requireLogin(redirect) {
  if (getToken()) return true;
  var url = '/pages/login/login';
  if (redirect) {
    url += '?redirect=' + encodeURIComponent(redirect);
  }
  wx.redirectTo({ url: url });
  return false;
}

module.exports = {
  getToken: getToken,
  setToken: setToken,
  clearToken: clearToken,
  getCachedProfile: getCachedProfile,
  setCachedProfile: setCachedProfile,
  login: login,
  me: me,
  logout: logout,
  requireLogin: requireLogin,
};