/**
 * 端 C · 量表题组（C1）
 * ============================================================================
 * 契约 `listScaleItemBanks`：`age_group` **必填**，`dimension` / `version` 可选；
 * 响应说明里有一句关键约束：**「客户仅见本人填答维度」**。
 *
 * 🛑 本页为什么按"档案带出年龄组"而不是让客户自选
 * ---------------------------------------------------------------------------
 * `age_group` 的取值是「男16-32 / 男33-40 / … / 女43-49以上」八个档 —— 它由
 * **性别 + 年龄段**算出，而这两项是建档信息（B4）。让客户自选等于允许"用别人的
 * 年龄组看题组"，而题组是**评估量程的锁定依据**（契约 `age_group_locked`：
 * 「同源题组锁定」）。故本页从档案读取，读不到就不查 —— 不猜、不让客户挑。
 *
 * 🛑 关于"客户仅见本人填答维度"
 * ---------------------------------------------------------------------------
 * 这是服务端的裁剪承诺。本页**不做任何维度过滤** —— 服务端返回什么就展示什么。
 * 若在客户端按某个"客户应看列表"过滤，一旦该列表与服务端口径漂移，就会出现
 * "服务端发了但客户端藏了"的静默少给（本仓对"手写清单腐烂"已有实锤）。
 */

'use strict';

var api = require('../../services/domain.js');
var session = require('../../services/session.js');
var codes = require('../../services/codes.js');

var app = getApp();

/** 契约 Dimension 枚举 7 项（逐字抄自契约）。选择留空表示不按维度筛选。 */
var DIMENSIONS = [
  '体能精力',
  '面部气色肤质',
  '肩颈腰背筋骨',
  '睡眠质量',
  '记忆专注',
  '代谢体态消化',
  '情绪抗压与抵抗力',
];

/**
 * 由性别 + 年龄推出契约 AgeGroup 八档。
 * 🛑 边界逐字对齐契约枚举：男 16-32 / 33-40 / 41-48 / 49以上；
 *    女 14-28 / 29-35 / 36-42 / 43-49以上。**推不出时返回空**，由页面提示。
 */
function toAgeGroup(gender, age) {
  var a = Number(age);
  if (!isFinite(a) || a <= 0) return '';
  var g = String(gender || '');
  if (g === '男') {
    if (a <= 32) return '男16-32';
    if (a <= 40) return '男33-40';
    if (a <= 48) return '男41-48';
    return '男49以上';
  }
  if (g === '女') {
    if (a <= 28) return '女14-28';
    if (a <= 35) return '女29-35';
    if (a <= 42) return '女36-42';
    return '女43-49以上';
  }
  return '';
}

Page({
  data: {
    loading: true,
    errText: '',
    ageGroup: '',
    ageGroupNote: '',

    dimensions: ['全部维度'].concat(DIMENSIONS),
    dimIndex: 0,

    groups: [],
    queried: false,
  },

  onShow: function () {
    if (!session.requireLogin('/pages/scale-bank/scale-bank')) return;
    this.loadAgeGroup();
  },

  loadAgeGroup: function () {
    var self = this;
    var id = (app && app.globalData && app.globalData.customerId) || '';
    if (!id) {
      this.setData({
        loading: false,
        errText: '档案信息待确认，请联系门店',
        ageGroupNote: '',
      });
      return;
    }
    api.getCustomer(id).then(function (c) {
      c = c || {};
      var ag = toAgeGroup(c.gender, c.age);
      self.setData({
        loading: false,
        ageGroup: ag,
        ageGroupNote: ag
          ? '按档案信息选定题组：' + ag
          : '档案中的性别或年龄不完整，暂不能确定题组',
      });
    }).catch(function (err) {
      var d = codes.describe(err);
      self.setData({ loading: false, errText: d.text });
    });
  },

  onPickDim: function (e) {
    var idx = Number(e.detail.value);
    this.setData({ dimIndex: idx });
    if (this.data.queried) this.query();
  },

  query: function () {
    var self = this;
    if (!this.data.ageGroup) {
      this.setData({ errText: this.data.ageGroupNote || '题组暂不能确定' });
      return;
    }
    var dim = this.data.dimIndex > 0 ? DIMENSIONS[this.data.dimIndex - 1] : '';
    this.setData({ loading: true, errText: '' });

    api.listScaleItemBanks(this.data.ageGroup, dim).then(function (data) {
      self.setData({
        loading: false,
        queried: true,
        groups: self.shape(data),
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

  shape: function (data) {
    var items = [];
    if (Array.isArray(data)) items = data;
    else if (data && Array.isArray(data.items)) items = data.items;
    else if (data && (data.item_group_id || data.group_id)) items = [data];

    return items.map(function (g) {
      var opts = [];
      var raw = g.options || g.item_group || [];
      if (Array.isArray(raw)) {
        raw.forEach(function (o) {
          if (o === null || o === undefined) return;
          if (typeof o === 'object') {
            opts.push(String(o.label !== undefined ? o.label : (o.value !== undefined ? o.value : '')));
          } else {
            opts.push(String(o));
          }
        });
      }
      return {
        id: g.item_group_id || g.group_id || g.scale_id || '',
        dimension: g.dimension || '',
        version: g.version ? String(g.version) : '',
        options: opts.filter(function (s) { return s !== ''; }),
      };
    });
  },
});