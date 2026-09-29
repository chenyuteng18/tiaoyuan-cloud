/**
 * 端 C · 登录页（A1）
 * ============================================================================
 * 客户侧登录只做一件事：拿凭证。**没有注册入口、没有"忘记密码"自助重置** ——
 * 账号由门店建档时生成，这属业务边界，不在本页扩权。
 *
 * 契约 `authLogin` 的 `client_end` 恒为 'mp'：服务端据此下发该端的档位声明。
 */

'use strict';

var request = require('../../services/request.js');
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

    request.call('authLogin', {
      body: { account: account, credential: credential, client_end: 'mp' },
    }).then(function (res) {
      var data = res.data || {};
      if (!data.token) {
        self.setData({ submitting: false, errText: '登录未成功，请稍后重试' });
        return;
      }
      session.setToken(data.token);
      var target = self.data.redirect || '/pages/index/index';
      wx.redirectTo({ url: target });
    }).catch(function (err) {
      var d = codes.describe(err);
      self.setData({ submitting: false, errText: d.text });
    });
  },
});