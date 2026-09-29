/**
 * 端 C · 手环数据页（E3 / E6 / E5）
 * ============================================================================
 * 这一页是 PRD §2.6 在客户端的**唯一落点**，也是三条硬约束的交汇处。逐条说明本页
 * 怎么落实，以及**为什么某样东西故意不在这里**：
 *
 * W-1「客户端可见手环数据本身，不可见由它推导出的一切」
 *   · 本页只渲染 E3 返回的数据源、采集天数、同步日期与指标四项。
 *   · **不渲染**缺口原因字段（它是 ③ 组，客户恒 403）、**不渲染**任何 ④ 组结果
 *     （契约把 ④ 组称为扫描面 3 点名的结果类字段）。注意事件里也不做"如果采集天数小于某值就提示"这类判断 ——
 *     那等于把判定逻辑搬进客户端，即使不显示分数，客户也能从提示反推出门槛。
 *
 * W-2「一律正向计数 + 中性描述」
 *   · 采集情况只说「已采集 N 天」；某日无数据只说「该日暂无数据」；手环没接上只说
 *     「手环未接入」。**不解释原因**——原因分类对客户不可见，
 *     而且把缺口指向"客户未按其使用"这一类说法，会把厂商的摘表要求判成客户过错。
 *   · 全页文案取自 `services/neutral-copy.js`，不在页面里另写句子。
 *
 * W-3「同屏必须带两项告知」
 *   · ① 手环数据为参考之一，不用于单方判定
 *   · ② 数据同步时间（精确到日）
 *   两项都是本页的**必现节点**，不与数据分开显示（放在同一张卡内）。
 *
 * R6「未接入态不得用 0 / 空图表 / 示例数据顶替」
 *   · `data_source === '未接入'` 时，数值区**不渲染**（不是渲染成 0），
 *     并明确显示"手环未接入"。
 *
 * 🛑 本页为什么没有"自动同步"按钮
 * ---------------------------------------------------------------------------
 * M-WX-FG 的裁定是**打开即自动同步**（PRD §2.8.7），不需要客户点。本页 onShow
 * 会主动再同步一次（客户已经来到手环页，这是最有心理准备的时刻），并用时间戳限流
 * 避免打满服务端节流窗口（契约 6001 提到 onShow 5 分钟窗口）。
 * 页面上保留的按钮是**下拉刷新**，那是"重试"语义，不是"同步"语义。
 */

'use strict';

var api = require('../../services/domain.js');
var session = require('../../services/session.js');
var copy = require('../../services/neutral-copy.js');
var codes = require('../../services/codes.js');
var bandSync = require('../../services/band-sync.js');

var app = getApp();

/** E2 上报的 metric 里，属 ① 组白名单（契约 x-field-groups.raw_data）的显示标签。 */
var METRIC_LABEL = {
  sleep: '睡眠',
  steps: '步数',
  hr: '心率',
  resting_hr: '静息心率',
  spo2: '血氧',
  workout: '运动',
};

/** 上次主动同步时间戳（毫秒），用于限流。 */
var _lastManualSyncAt = 0;
var MANUAL_SYNC_MIN_INTERVAL = 5 * 60 * 1000; // 与契约 6001 的 onShow 窗口对齐

Page({
  data: {
    loading: true,
    errText: '',
    hasCustomer: false,

    // 数据源与采集情况
    bandSourceText: '',
    notConnected: false,
    collectedText: '',
    syncLine: '',

    // 指标列表
    metrics: [],

    // 同步状态（E6）
    syncStateText: '',
    syncNextActionText: '',

    // 采集能力（E5）
    collectorReady: false,
    collectorNote: '',
    probeText: '',

    // W-3 ①
    bandReferenceNotice: copy.BAND_REFERENCE_NOTICE,
    noDataThatDay: copy.NO_DATA_THAT_DAY,
  },

  onShow: function () {
    if (!session.requireLogin('/pages/band/band')) return;
    this.refreshWithAutoSync();
  },

  onPullDownRefresh: function () {
    var self = this;
    _lastManualSyncAt = 0;
    this.refreshWithAutoSync().then(function () {
      wx.stopPullDownRefresh();
    });
  },

  /** 先按需同步，再读数据 —— 顺序不能反（先读会读到旧快照）。 */
  refreshWithAutoSync: function () {
    var self = this;
    var customerId = (app && app.globalData && app.globalData.customerId) || '';

    this.setData({
      loading: true,
      errText: '',
      collectorReady: bandSync.hasCollector(),
      collectorNote: bandSync.hasCollector()
        ? ''
        : '自动同步已启用；数据采集能力待接入。',
    });

    var pre = Promise.resolve(null);
    var now = Date.now();
    if (customerId && now - _lastManualSyncAt > MANUAL_SYNC_MIN_INTERVAL) {
      _lastManualSyncAt = now;
      pre = bandSync.syncOnShow({
        deviceId: (app && app.globalData && app.globalData.deviceId) || '',
        customerId: customerId,
        cold: false,
      }).catch(function () {
        // 同步失败不影响"读已有数据"这一步：客户仍应看到上次同步到的内容。
        return null;
      });
    }

    return pre.then(function (summary) {
      return session.me().then(function () {
        var id = customerId || (app && app.globalData && app.globalData.customerId) || '';
        if (!id) {
          self.setData({ loading: false, hasCustomer: false, errText: '档案信息待确认，请联系门店' });
          return null;
        }
        var patch = { hasCustomer: true, loading: false };
        if (summary && summary.reason === bandSync.COLLECTOR_NOT_SELECTED) {
          patch.probeText = '本次未采集到新数据';
        }
        return Promise.all([
          api.getBandTelemetry(id).catch(function (e) { return { __err: e }; }),
          bandSync.syncStatus(id).catch(function () { return null; }),
        ]).then(function (rs) {
          self.applyBand(rs[0], rs[1], patch);
        });
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

  applyBand: function (band, status, patch) {
    if (band && band.__err) {
      var d = codes.describe(band.__err);
      patch.errText = d.text;
      patch.notConnected = false;
      patch.bandSourceText = '';
      patch.metrics = [];
      this.setData(patch);
      return;
    }

    band = band || {};
    var notConnected = band.data_source === '未接入';
    patch.notConnected = notConnected;
    patch.bandSourceText = copy.dataSourceLine(band.data_source);

    if (notConnected) {
      // R6：不得用 0 / 空图表顶替。
      patch.collectedText = '';
      patch.syncLine = '';
      patch.metrics = [];
    } else {
      patch.collectedText = copy.collectedLine(band.collected_days);
      patch.syncLine = copy.syncTimeLine(band.synced_date);
      patch.metrics = this.shapeMetrics(band.metrics);
    }

    // 同步状态（E6）：中性说法，未知状态不透出内部名。
    var st = (status && status.state) || '';
    patch.syncStateText = st ? copy.syncStateLine(st) : '';
    patch.syncNextActionText = status ? copy.nextActionLine(status.next_action) : '';

    this.setData(patch);
  },

  /**
   * metrics 是契约里的自由形状数组（[object]），故按**防御式**渲染：
   * 只认能识别的键，认不出就不渲染该项，**绝不猜**一个数值出来。
   */
  shapeMetrics: function (raw) {
    var list = raw || [];
    var out = [];
    for (var i = 0; i < list.length; i += 1) {
      var m = list[i] || {};
      var key = m.metric || m.name || '';
      var value = m.value;
      if (value === undefined || value === null || value === '') continue; // R6：不补 0
      out.push({
        label: METRIC_LABEL[key] || '其他指标',
        value: String(value),
        unit: m.unit ? String(m.unit) : '',
        day: copy.toDay(m.date || m.biz_date || ''),
      });
    }
    return out;
  },

  goProfile: function () {
    wx.switchTab({ url: '/pages/profile/profile' });
  },
});