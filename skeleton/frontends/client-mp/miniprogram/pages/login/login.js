/**
 * 端 C · 登录页（A1）
 * ============================================================================
 * 客户侧登录只做一件事：拿凭证。**没有注册入口、没有"忘记密码"自助重置** ——
 * 账号由门店建档时生成，这属业务边界，不在本页扩权。
 *
 * 契约 `authLogin` 的 `client_end` 恒为 'mp'：服务端据此下发该端的档位声明。
 *
 * 🛑 本页**不再自己发 A1 请求** —— 出站一律经 services 层（第 63 条收口）
 * ---------------------------------------------------------------------------
 * 本页原实现直接在页面里 `request.call('authLogin', ...)`，而
 * `services/session.js` 里**已经有一个等价的 `login()`**（同样拼
 * `client_end: 'mp'`、同样存 token），只是**从没被任何页面调用过**。
 * 于是同一个操作在端 C 有**两份实现**：
 *   · 页面那份在跑，会话层那份是死代码；
 *   · 契约若调整 A1 的入参（例如 client_end 取值或新增字段），改哪一份？
 *     改了页面那份，会话层就永久漂移；改了会话层那份，**什么都不发生**
 *     （因为它不被调用）—— 而 tsc / 构建 / 全部既有门禁**都看不出区别**。
 * 这正是本仓第 57/61 条同族的形态："同一份契约事实被实现了两次"。
 * ⇒ 修法：收敛到 `session.login()` 一处（它本就负责"登录 + 落盘凭证"）。
 *    这条缺口由新增的 `endpoint-reachability` 判据（④g）首跑即抓出。
 */

'use strict';

var session = require('../../services/session.js');
var codes = require('../../services/codes.js');

Page({
  data: {
    account: '',
    credential: '',
    submitting: false,
    errText: '',
    redirect: '',
  },

  onLoad: function (query) {
    this.setData({ redirect: (query && query.redirect) || '' });
  },

  onAccount: function (e) {
    this.setData({ account: e.detail.value, errText: '' });
  },

  onCredential: function (e) {
    this.setData({ credential: e.detail.value, errText: '' });
  },

  onSubmit: function () {
    var self = this;
    var account = (this.data.account || '').trim();
    var credential = this.data.credential || '';

    if (!account || !credential) {
      this.setData({ errText: '请填写手机号与登录口令' });
      return;
    }
    this.setData({ submitting: true, errText: '' });

    // 出站与凭证落盘都在 session.login 里（单一权威点）。
    session.login(account, credential).then(function () {
      var target = self.data.redirect || '/pages/index/index';
      wx.redirectTo({ url: target });
    }).catch(function (err) {
      // 带契约错误码（服务端返回的失败）走统一文案映射；
      // 无码错误（如"服务端未返回凭证"这类本地判定）沿用本页文案。
      var text = err && typeof err.code === 'number'
        ? codes.describe(err).text
        : '登录未成功，请稍后重试';
      self.setData({ submitting: false, errText: text });
    });
  },
});