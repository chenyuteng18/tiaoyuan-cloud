/**
 * 端 C · 评估回看（C3）
 * ============================================================================
 * 客户回看自己的评估记录。契约 `BaselineAssessmentData` 标了两处差异：
 *   · `dimension_scores`（各维度得分）—— 可见
 *   · `baseline_conclusion` —— 可见
 *   · `migratable` —— **客户不下发**（可迁移性属内部台账口径）
 *
 * 🛑 本页如何拿到 assessment_id（一处**如实登记的不确定**，不是遗漏）
 * ---------------------------------------------------------------------------
 * 本端可用端点里，`getAssessment` 需要 `{id}/{assessment_id}` 两个路径参数，
 * 但**没有一个"列出我的评估"的端点**（`listVisits` / `listDailyReports` 都不返回
 * 评估列表）。
 *
 * 处理方式：页面支持由**上一页带参进入**（`?assessment_id=...`），也支持在页内
 * 手工输入一个编号查询。**不猜、不遍历**——遍历请求既打服务端节流，也会在客户侧
 * 制造"为什么查不到"的困惑。若客户是从别处（如门店告知、回执）拿到编号，这里可用；
 * 若拿不到，页面如实提示需从门店获取。
 * 该点已登记于 frontends/README.md「待确认项」。
 */

'use strict';

var api = require('../../services/domain.js');
var session = require('../../services/session.js');
var copy = require('../../services/neutral-copy.js');
var codes = require('../../services/codes.js');

var app = getApp();

/** 维度名（契约 `Dimension` 枚举 7 项，逐字抄自契约，不自行增删）。 */
var DIMENSION_NAMES = [
  '体能精力',
  '面部气色肤质',
  '肩颈腰背筋骨',
  '睡眠质量',
  '记忆专注',
  '代谢体态消化',
  '情绪抗压与抵抗力',
];

Page({
  data: {
    loading: false,
    errText: '',
    assessmentId: '',
    loaded: false,

    assessedAt: '',
    dims: [],
    conclusionReady: false,
    conclusionRows: [],
  },

  onLoad: function (query) {
    var id = (query && query.assessment_id) || '';
    if (id) {
      this.setData({ assessmentId: id });
    }
  },

  onShow: function () {
    session.requireLogin('/pages/assessment/assessment');
    if (this.data.assessmentId && !this.data.loaded) {
      this.query();
    }
  },

  onIdInput: function (e) {
    this.setData({ assessmentId: e.detail.value, errText: '' });
  },

  query: function () {
    var self = this;
    var customerId = (app && app.globalData && app.globalData.customerId) || '';
    var aid = (this.data.assessmentId || '').trim();

    if (!customerId) {
      this.setData({ errText: '档案信息待确认，请联系门店' });
      return;
    }
    if (!aid) {
      this.setData({ errText: '请填写评估编号' });
      return;
    }

    this.setData({ loading: true, errText: '' });
    api.getAssessment(customerId, aid).then(function (data) {
      data = data || {};
      var dims = [];
      var scores = data.dimension_scores || [];
      for (var i = 0; i < scores.length; i += 1) {
        if (scores[i] === null || scores[i] === undefined) continue;
        dims.push({
          name: DIMENSION_NAMES[i] || ('维度 ' + (i + 1)),
          score: String(scores[i]),
        });
      }

      var rows = [];
      var c = data.baseline_conclusion;
      if (c && typeof c === 'object') {
        Object.keys(c).forEach(function (k) {
          var v = c[k];
          if (v === null || v === undefined || v === '') return;
          if (typeof v === 'object') return;
          rows.push({ k: k, v: String(v) });
        });
      }

      self.setData({
        loading: false,
        loaded: true,
        assessedAt: copy.toDay(data.assessed_at),
        dims: dims,
        conclusionRows: rows,
        conclusionReady: rows.length > 0,
      });
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