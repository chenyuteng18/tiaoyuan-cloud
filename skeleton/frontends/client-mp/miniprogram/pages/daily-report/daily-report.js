/**
 * 端 C · 每日填报（D3 / D4）
 * ============================================================================
 * 需求口径（PRD P0-09 / US-3）：
 *   · **全部点选，无开放输入**，首屏可完成（≤30 秒）；
 *   · 提交即回显「本周 X/7 天 + 上周对比 + 一条可执行建议」；
 *   · 补填窗口可配置（默认 2 天），**超窗不可补** —— 窗口由**服务端**判定，
 *     本页不替服务端"修正"客户选的日期（那会掩盖"选了不允许的日期"这个事实）；
 *   · 连续缺填的待办推送（双推客户与调理师）属**服务端**职责，本页不实现，
 *     也不在客户端提示"你连续两天没填" —— 那是负向计数（W-2 禁止）。
 *
 * 🛑 题目内容为什么不在本页硬编码 —— 这是一处**如实登记的缺口**，不是遗漏
 * ---------------------------------------------------------------------------
 * 逐项核对后的结论：**契约与 PRD 都没有给出每日填报的题目清单**。
 *   · 契约 `DailyReportRequest.answers_json` 仅声明 `type: object`，
 *     说明文字只有「全点选，无开放输入」；**没有题目字段**。
 *   · 本端可用的 15 个端点里，**没有任何"获取填报题目"的端点**
 *     （既没有拉题目的接口，也没有题目资源表）。
 *   · PRD 只规定形态（点选、首屏、≤30 秒、提交即回显），未列出题目。
 *
 * 三条路都不能走：
 *   ① 在本页硬编码一批"看起来合理"的题目 —— 那是**编造业务内容**。题目直接决定
 *      进入依从性统计的原始数据，编出来的题目会产出"真数据、假来源"的记录。
 *   ② 随手下发一个空 `answers_json` 完成提交 —— 会造出一条**没有内容的填报记录**，
 *      而它会照常计入"本周 X/7 天"，把依从性抬上去。
 *   ③ 用示例题目 + 显著标注"示例"上线 —— 标注会被后续版本剥掉，题目会留下。
 *
 * 故本页：**机制完整实现，题目源可注入**（`services/question-source.js`），
 * 未注入时页面明确显示"填报项待配置"，**不生成任何假题目，也不允许提交空卷**。
 * 该缺口已登记于 frontends/README.md「待确认项」，**不写成"已解决"**。
 */

'use strict';

var api = require('../../services/domain.js');
var session = require('../../services/session.js');
var copy = require('../../services/neutral-copy.js');
var codes = require('../../services/codes.js');
var questionSource = require('../../services/question-source.js');

var app = getApp();

/** 可选日期：今天 + 往前 N 天。N 取 2，与 PRD「补填窗口默认 2 天」对齐。 */
function candidateDates() {
  var out = [];
  var now = new Date();
  for (var i = 0; i <= 2; i += 1) {
    var d = new Date(now.getTime() - i * 86400000);
    var y = d.getFullYear();
    var m = ('0' + (d.getMonth() + 1)).slice(-2);
    var day = ('0' + d.getDate()).slice(-2);
    out.push({
      value: y + '-' + m + '-' + day,
      label: i === 0 ? '今天' : (i === 1 ? '昨天' : '前天'),
    });
  }
  return out;
}

Page({
  data: {
    loading: true,
    errText: '',
    okText: '',
    hasCustomer: false,

    dates: [],
    dateIndex: 0,

    questionsReady: false,
    questions: [],

    // 已选项：{ [qkey]: optionValue }
    picked: {},
    submitting: false,

    // D4 回显
    weeklyCount: '',
    weeklyCompareText: '',
    suggestion: '',
    showEcho: false,
  },

  onShow: function () {
    if (!session.requireLogin('/pages/daily-report/daily-report')) return;
    this.load();
  },

  load: function () {
    var self = this;
    this.setData({
      loading: true,
      errText: '',
      okText: '',
      dates: candidateDates(),
      questionsReady: questionSource.hasQuestionSource(),
    });

    var customerId = (app && app.globalData && app.globalData.customerId) || '';
    if (!customerId) {
      return session.me().then(function (p) {
        var id = p.customer_id || (app && app.globalData && app.globalData.customerId) || '';
        if (!id) {
          self.setData({ loading: false, hasCustomer: false, errText: '档案信息待确认，请联系门店' });
          return null;
        }
        return self.loadQuestionsAndHistory(id);
      }).catch(function (err) {
        var d = codes.describe(err);
        self.setData({ loading: false, errText: d.text });
      });
    }
    return this.loadQuestionsAndHistory(customerId);
  },

  loadQuestionsAndHistory: function (customerId) {
    var self = this;
    return questionSource.getQuestions().then(function (res) {
      var questions = res.questions || [];
      var picked = {};
      questions.forEach(function (item) {
        picked[item.key] = '';
      });
      self.setData({
        hasCustomer: true,
        loading: false,
        questions: questions,
        picked: picked,
        questionsReady: questions.length > 0,
      });
      return api.listDailyReports(customerId).catch(function () { return null; });
    }).then(function (reports) {
      self.applyHistory(reports);
    });
  },

  applyHistory: function (reports) {
    if (!reports) return;
    var patch = {
      weeklyCount: copy.orEmpty(reports.weekly_count),
    };
    var lw = reports.last_week_compare;
    if (lw && (lw.count !== undefined || lw.days !== undefined)) {
      patch.weeklyCompareText = '上周 ' + copy.orEmpty(lw.count !== undefined ? lw.count : lw.days) + ' 天';
    }
    if (reports.suggestion) patch.suggestion = String(reports.suggestion);
    this.setData(patch);
  },

  onPickDate: function (e) {
    this.setData({ dateIndex: Number(e.detail.value), errText: '', okText: '' });
  },

  onPickOption: function (e) {
    var qkey = e.currentTarget.dataset.q;
    var value = e.currentTarget.dataset.v;
    var picked = Object.assign({}, this.data.picked);
    picked[qkey] = value;
    this.setData({ picked: picked, errText: '', okText: '' });
  },

  onSubmit: function () {
    var self = this;
    var customerId = (app && app.globalData && app.globalData.customerId) || '';

    if (!this.data.questionsReady) {
      this.setData({ errText: '填报项待配置，请联系门店' });
      return;
    }
    // 必须在页面层就挡住"半张卷"：answers_json 是进入依从性统计的原始数据，
    // 缺项提交等于制造一条内容不全却计入"已填"的记录。
    var missing = [];
    this.data.questions.forEach(function (item) {
      if (!self.data.picked[item.key]) missing.push(item.title);
    });
    if (missing.length) {
      this.setData({ errText: '还有 ' + missing.length + ' 项未选择' });
      return;
    }
    if (!customerId) {
      this.setData({ errText: '档案信息待确认，请联系门店' });
      return;
    }

    var date = this.data.dates[this.data.dateIndex].value;
    this.setData({ submitting: true, errText: '', okText: '' });

    api.submitDailyReport(customerId, date, this.data.picked).then(function (data) {
      self.setData({
        submitting: false,
        okText: '已提交',
        showEcho: true,
        weeklyCount: copy.orEmpty(data.weekly_count),
        suggestion: data.suggestion ? String(data.suggestion) : '',
      });
      self.applyEchoCompare(data.last_week_compare);
    }).catch(function (err) {
      var d = codes.describe(err);
      if (d.kind === 'auth') {
        session.clearToken();
        wx.redirectTo({ url: '/pages/login/login' });
        return;
      }
      // 4002 = 幂等键命中（同一天重提）→ 服务端返回首次结果，属成功。
      if (d.kind === 'replay') {
        self.setData({ submitting: false, okText: '该日已提交', showEcho: true });
        return;
      }
      self.setData({ submitting: false, errText: d.text });
    });
  },

  applyEchoCompare: function (lw) {
    if (!lw) return;
    var n = lw.count !== undefined ? lw.count : lw.days;
    if (n === undefined) return;
    this.setData({ weeklyCompareText: '上周 ' + copy.orEmpty(n) + ' 天' });
  },
});