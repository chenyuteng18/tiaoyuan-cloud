/**
 * 端 C · 首页
 * ============================================================================
 * 首页只呈现**客户自己能看的三件事**：
 *   ① 我是谁（B4 客户档案，无则提示待确认）；
 *   ② 本周填报进度（D4 `weekly_count`，**正向计数**）；
 *   ③ 手环采集情况（E3 `collected_days` + `synced_date`，**正向计数 + 中性描述**）。
 *
 * 🛑 本页刻意不出现的元素（逐条对应 PRD §2.6.2，不是"忘了做"）
 * ---------------------------------------------------------------------------
 * · 不许出现 R7 点名的那类评价控件或文案（W-1 / R7）—— 所以没有进度环、没有百分比。
 *   `weekly_count` 是"本周 X/7 天"的正向计数，用数字表达，不做达成度视觉。
 * · 不许出现任何由手环数据推导出的结论（W-1）—— 没有评分、没有趋势箭头、
 *   没有"改善/下降"字样。
 * · 数据未接入时**不显示 0，也不画空图表**（R6：不得用 0 / 空图表 / 示例数据顶替）
 *   —— 而是显示"手环未接入"。
 *
 * 🛑 自动同步的结果**不在本页报警**
 * ---------------------------------------------------------------------------
 * app.js 的 onShow 会自动发起同步。同步失败时本页**不弹错误**（客户只想看首页，
 * 没有心理准备看手环排障信息）。失败状态留给"手环数据"页在客户主动查看时如实呈现。
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
    customerName: '',
    // D4 正向计数
    weeklyCount: '',
    weeklyCompareText: '',
    // E3 采集情况（正向计数 + 中性描述）
    bandSourceText: '',
    bandCollectedText: '',
    bandSyncLine: '',
    bandNotConnected: false,
    // W-3 ①：定位告知（与手环信息同屏）
    bandReferenceNotice: copy.BAND_REFERENCE_NOTICE,
    showBandBlock: false,
  },

  onShow: function () {
    if (!session.requireLogin('/pages/index/index')) return;
    this.load();
  },

  onPullDownRefresh: function () {
    var self = this;
    this.load().then(function () {
      wx.stopPullDownRefresh();
    });
  },

  load: function () {
    var self = this;
    this.setData({ loading: true, errText: '' });

    return session.me().then(function (profile) {
      var customerId = (app && app.globalData && app.globalData.customerId) || profile.customer_id || '';
      if (!customerId) {
        self.setData({
          loading: false,
          hasCustomer: false,
          errText: '档案信息待确认，请联系门店',
        });
        return null;
      }
      return Promise.all([
        api.getCustomer(customerId).catch(function () { return null; }),
        api.listDailyReports(customerId).catch(function () { return null; }),
        api.getBandTelemetry(customerId).catch(function () { return null; }),
      ]).then(function (rs) {
        self.applyAll(rs[0], rs[1], rs[2], customerId);
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

  applyAll: function (customer, reports, band, customerId) {
    var patch = { loading: false, hasCustomer: true };

    // ---- ① 我是谁（B4）----
    patch.customerName = customer && customer.name ? customer.name : '';

    // ---- ② 本周填报进度（D4）----
    if (reports) {
      patch.weeklyCount = copy.orEmpty(reports.weekly_count);
      // 上周对比只做**中性陈述**（"上周 X 天"），不做升降评价（R7）。
      var lw = reports.last_week_compare;
      if (lw && (lw.count !== undefined || lw.days !== undefined)) {
        var n = lw.count !== undefined ? lw.count : lw.days;
        patch.weeklyCompareText = '上周 ' + copy.orEmpty(n) + ' 天';
      } else {
        patch.weeklyCompareText = '';
      }
    }

    // ---- ③ 手环采集情况（E3）----
    if (band) {
      var notConnected = band.data_source === '未接入';
      patch.showBandBlock = true;
      patch.bandNotConnected = notConnected;
      patch.bandSourceText = copy.dataSourceLine(band.data_source);
      if (notConnected) {
        // R6：未接入时不显示 0、不画空图。
        patch.bandCollectedText = '';
        patch.bandSyncLine = '';
      } else {
        patch.bandCollectedText = copy.collectedLine(band.collected_days);
        patch.bandSyncLine = copy.syncTimeLine(band.synced_date);
      }
    } else {
      // 请求失败（如 403）时不编造内容，整块不显示。
      patch.showBandBlock = false;
    }

    this.setData(patch);
  },

  goBand: function () {
    wx.switchTab({ url: '/pages/band/band' });
  },

  goDailyReport: function () {
    wx.switchTab({ url: '/pages/daily-report/daily-report' });
  },

  goProfile: function () {
    wx.switchTab({ url: '/pages/profile/profile' });
  },
});