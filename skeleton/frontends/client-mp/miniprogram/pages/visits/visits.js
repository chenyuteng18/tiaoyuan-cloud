/**
 * 端 C · 服务记录（D2）
 * ============================================================================
 * 客户回看自己做过的服务。契约 `VisitData` 标了三处"客户不下发"：
 *   · `gate_check_json` —— 门禁校验内容，**客户不下发**
 *   · `abnormal_note`   —— 异常备注，**客户不下发**
 *   · `serving_store_id`—— **客户端可见**（用于标记服务门店）
 *
 * 🛑 前两项本页**根本不读**（而不是"读了判空"）：契约对"不下发"的定义是响应里
 * 不带该键，故不引用即可，不需要防御分支。若日后服务端误发了，本页也不会把它
 * 渲染出来 —— 客户侧不应该承担"过滤服务端误发字段"的职责，那会掩盖真实的服务端
 * 缺陷（正确动作是服务端修，且在门禁里能被发现）。
 *
 * 🛑 门店名去哪儿了
 * ---------------------------------------------------------------------------
 * `serving_store_id` 是**门店 ID**，不是门店名。客户看一个 UUID 没有意义，
 * 而本端可用的端点里**没有"按 ID 查门店"的接口**（`listStores` 是管理端/APP 端
 * 端点，且返回的是分页列表）。故本页**只显示"服务门店已记录"这一事实**，
 * 不把 ID 当名字展示 —— 展示一个裸 ID 会让客户以为是系统故障。
 * 该点已登记于 frontends/README.md。
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
    visits: [],
  },

  onShow: function () {
    if (!session.requireLogin('/pages/visits/visits')) return;
    this.load();
  },

  onPullDownRefresh: function () {
    var self = this;
    this.load().then(function () { wx.stopPullDownRefresh(); });
  },

  load: function () {
    var self = this;
    this.setData({ loading: true, errText: '' });
    var id = (app && app.globalData && app.globalData.customerId) || '';
    if (!id) {
      this.setData({ loading: false, hasCustomer: false, errText: '档案信息待确认，请联系门店' });
      return Promise.resolve();
    }
    return api.listVisits(id).then(function (data) {
      self.setData({
        loading: false,
        hasCustomer: true,
        visits: self.shape(data),
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

  /** 分页信封可以是 {items[]} 也可以是裸数组，两种都认；都不中则空列表。 */
  shape: function (data) {
    var items = [];
    if (Array.isArray(data)) items = data;
    else if (data && Array.isArray(data.items)) items = data.items;
    else if (data && data.visit_id) items = [data];

    return items.map(function (v) {
      return {
        visit_id: v.visit_id || '',
        visit_no: copy.orEmpty(v.visit_no),
        executed_at: copy.toDay(v.executed_at),
        confirmed: v.customer_confirmed === true ? '已确认' : '',
        // serving_store_id 可见但展示为"已记录"，不把 UUID 当门店名。
        storeMarked: !!v.serving_store_id,
      };
    });
  },
});