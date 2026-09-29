/**
 * 端 C · 我的方案（D5-b）
 * ============================================================================
 * 客户查看与自己相关的方案。同上页，`plan_id` 需由上一页带参或手工填写 ——
 * 本端没有"列出我的方案"的端点，**不猜、不遍历**。
 *
 * 🛑 方案里可能含"预期效果"类字段 —— 本页只做**中性呈现**
 * ---------------------------------------------------------------------------
 * 契约未固定 `getPlan` 的响应形状（object），故按 key-value 渲染。若有字段名带
 * "改善 / 提升 / 有效率"这类**效果承诺**语义，本页仍按原文照显 —— **不在客户端
 * 改写服务端文案**（客户端改文案会让"服务端到底写了什么"变得不可追溯）。
 * 但本页**不新增**任何效果评价、不计算达成度、不画趋势 —— 那属 ④ 组结果（W-1）。
 */

'use strict';

var api = require('../../services/domain.js');
var session = require('../../services/session.js');
var copy = require('../../services/neutral-copy.js');
var codes = require('../../services/codes.js');

Page({
  data: {
    loading: false,
    errText: '',
    planId: '',
    loaded: false,
    rows: [],
  },

  onLoad: function (query) {
    var id = (query && query.plan_id) || '';
    if (id) this.setData({ planId: id });
  },

  onShow: function () {
    session.requireLogin('/pages/plan/plan');
    if (this.data.planId && !this.data.loaded) this.query();
  },

  onIdInput: function (e) {
    this.setData({ planId: e.detail.value, errText: '' });
  },

  query: function () {
    var self = this;
    var pid = (this.data.planId || '').trim();
    if (!pid) {
      this.setData({ errText: '请填写方案编号' });
      return;
    }
    this.setData({ loading: true, errText: '' });

    api.getPlan(pid).then(function (data) {
      data = data || {};
      var rows = [];
      Object.keys(data).forEach(function (k) {
        var v = data[k];
        if (v === null || v === undefined || v === '') return;
        if (typeof v === 'object') return;
        rows.push({ k: k, v: String(v) });
      });
      self.setData({ loading: false, loaded: true, rows: rows });
    }).catch(function (err) {
      var d = codes.describe(err);
      if (d.kind === 'auth') {
        session.clearToken();
        wx.redirectTo({ url: '/pages/login/login' });
        return;
      }
      self.setData({ loading: false, loaded: false, errText: d.text });
    });
  },
});