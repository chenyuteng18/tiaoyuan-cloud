/**
 * 端 C · 我的档案（B4 / B5）
 * ============================================================================
 * 客户看自己的建档信息与扩展档案。**只读** —— 客户不改自己的档案（改档案是门店
 * 动作，走 B6 PATCH，而 B6 未授予客户端）。这不是"权限不够"，是业务边界。
 *
 * 🛑 契约里几处"客户不下发"的字段，本页**不引用、不兜底、不显示占位**
 * ---------------------------------------------------------------------------
 * 契约 `CustomerDetailData` 明确标注两个门店标识字段**客户不下发**，两个结论类
 * 字段（效果判定与依从值）**客户恒不下发**。注意"不下发"的
 * 准确含义是「**服务端在响应里根本不带这个键**」（x-global-conventions.visibility
 * 原文：「无权限字段不下发（**不是 null、不是空串**）」）。
 *
 * 因此本页的做法是：**根本不去读这些键**。若写成"读了但为空就不显示"，会引入一个
 * 依赖"服务端确实没发"的隐含假设；而直接不引用，则服务端发不发都不影响正确性。
 */

'use strict';

var api = require('../../services/domain.js');
var session = require('../../services/session.js');
var copy = require('../../services/neutral-copy.js');
var codes = require('../../services/codes.js');

var app = getApp();

Page({
  data: {
    loading: true,
    errText: '',
    hasCustomer: false,

    name: '',
    gender: '',
    age: '',
    screeningResult: '',
    bandWillingness: '',

    intakeReady: false,
    intakeRows: [],

    customerId: '',
    bandReferenceNotice: copy.BAND_REFERENCE_NOTICE,
  },

  onShow: function () {
    if (!session.requireLogin('/pages/profile/profile')) return;
    this.load();
  },

  load: function () {
    var self = this;
    this.setData({ loading: true, errText: '' });

    return session.me().then(function (p) {
      var id = (app && app.globalData && app.globalData.customerId) || p.customer_id || '';
      if (!id) {
        self.setData({ loading: false, hasCustomer: false, errText: '档案信息待确认，请联系门店' });
        return null;
      }
      return Promise.all([
        api.getCustomer(id).catch(function (e) { return { __err: e }; }),
        api.getIntakeProfile(id).catch(function () { return null; }),
      ]).then(function (rs) {
        self.applyAll(id, rs[0], rs[1]);
      });
    }).catch(function (err) {
      var d = codes.describe(err);
      if (d.kind === 'auth') {
        session.clearToken();
        wx.redirectTo({ url: '/pages/login/login' });
        return;
      }
      self.setData({ loading: false, errText: d.text });
    });
  },

  applyAll: function (id, customer, intake) {
    if (customer && customer.__err) {
      var d = codes.describe(customer.__err);
      this.setData({ loading: false, hasCustomer: false, errText: d.text });
      return;
    }
    customer = customer || {};
    var patch = {
      loading: false,
      hasCustomer: true,
      customerId: id,
      name: copy.orEmpty(customer.name),
      gender: copy.orEmpty(customer.gender),
      age: copy.orEmpty(customer.age),
      screeningResult: copy.orEmpty(customer.screening_result),
      bandWillingness: copy.orEmpty(customer.band_willingness),
    };

    // B5 扩展档案：形状未在契约里固定（object），故按 key-value 防御式渲染。
    if (intake && typeof intake === 'object') {
      var rows = [];
      Object.keys(intake).forEach(function (k) {
        var v = intake[k];
        if (v === null || v === undefined || v === '') return;
        if (typeof v === 'object') return; // 嵌套结构不在本页展开
        rows.push({ k: k, v: String(v) });
      });
      patch.intakeRows = rows;
      patch.intakeReady = rows.length > 0;
    }
    this.setData(patch);
  },

  goVisits: function () {
    wx.navigateTo({ url: '/pages/visits/visits' });
  },

  goScaleBank: function () {
    wx.navigateTo({ url: '/pages/scale-bank/scale-bank' });
  },

  goAssessment: function () {
    wx.navigateTo({ url: '/pages/assessment/assessment' });
  },

  goPlan: function () {
    wx.navigateTo({ url: '/pages/plan/plan' });
  },

  onLogout: function () {
    session.logout();
    wx.redirectTo({ url: '/pages/login/login' });
  },
});