/**
 * 端 C · 客户小程序 —— 手环同步编排（E5 / E1 / E2 / E6）
 * ============================================================================
 *
 * 业务方裁定（PRD §2.8.7 M-WX-FG）：**客户打开小程序即自动发起同步**，无需点击。
 * 本文件实现的是**这条时序的编排与状态上报**，即：
 *
 *     打开小程序 ──> 探测设备端可用日期(E5) ──> 拉取未采集区间 ──> 上报批次(E1)
 *                                                                  └─> 上报逐条遥测(E2)
 *
 * 🛑 采集器本身为什么不在本文件里 —— 这是本轮刻意的留白，不是遗漏
 * ---------------------------------------------------------------------------
 * 「谁来取数」在 PRD §2.8.3 列了三条候选路径（A 小程序前台 BLE 直连 / B 服务端
 * 定时拉厂商云 / C 客户装厂商 App 自动回传），**业务方尚未裁定**（Q-G5）。三条
 * 路径的差别不在"能不能连上"，而在**采集率是否够**（路径 A 乐观 0.80~0.90 低于
 * 阈值，会被判结构性不可观测）。因此：
 *
 *   · 本文件把"取数"抽象成一个可注入的**采集器接口**（见 `setCollector`）；
 *   · 未注入采集器时，同步**不会**伪造一条成功记录 —— 它返回
 *     `COLLECTOR_NOT_SELECTED` 并让页面显示"能力待接入"，**不产生任何上报**；
 *   · 这直接对应契约对 `retention_window_days` 的要求原话：
 *     「运行时探测值；未探测到 → 返回 TBD，**不得回落为硬编码值**」。
 *
 * 为什么宁可"不做"也不"先写个假采集器"
 * ------------------------------------
 * 假采集器会让 E1/E2 上报出**看起来正常**的批次与遥测，于是下游（A3 依从性）会
 * 拿它当真数据算出一个数 —— 而那个数**没有任何客户真的戴着手环**。这类"数据是真的
 * 流过的、只是来源是编的"比缺数据危险得多。本仓对"静默假绿"已有多次实锤，此处
 * 按同一纪律处置：**缺能力就报缺能力**。
 */

'use strict';

var request = require('./request.js');

/** 采集器的注入点。签名：collector(deviceId, pullStart, pullEnd) -> Promise<{records, availableDates, probeId}> */
var _collector = null;

/** 本轮未注入任何采集器时的登记值（页面据此显示"能力待接入"）。 */
var COLLECTOR_NOT_SELECTED = 'COLLECTOR_NOT_SELECTED';

/** PRD §2.8.3 的三条候选路径编号，供页面与文档互相引用，避免各写一套名。 */
var PENDING_RULING = {
  REF: 'PRD §2.8.3 / Q-G5',
  PATHS: ['A 小程序前台直连', 'B 服务端定时拉取厂商云', 'C 厂商 App 自动回传'],
};

function setCollector(fn) {
  _collector = typeof fn === 'function' ? fn : null;
}

function hasCollector() {
  return typeof _collector === 'function';
}

/**
 * 探测设备端可用日期（E5）。
 *
 * 🛑 未注入采集器时不返回任何编造的日期区间：`retention_window_days` 保持 TBD，
 * `valid_history_dates` 为空。服务端据此知道"这一端还没探测到"，而不是收到一个
 * 看起来正常的窗口。契约要求"未探测到 → TBD，不得回落为硬编码值"。
 */
function probeAvailableDates(deviceId, historyType) {
  if (!hasCollector()) {
    return Promise.resolve({
      ok: false,
      reason: COLLECTOR_NOT_SELECTED,
      probeId: '',
      retentionWindowDays: 'TBD',
      pullStartDate: '',
      earliestAvailable: '',
      latestAvailable: '',
      validHistoryDates: [],
      pendingRuling: PENDING_RULING,
    });
  }
  return _collector(deviceId, historyType).then(function (out) {
    var out2 = out || {};
    var dates = out2.availableDates || [];
    return {
      ok: true,
      reason: '',
      probeId: out2.probeId || '',
      retentionWindowDays: out2.retentionWindowDays || 'TBD',
      pullStartDate: out2.pullStartDate || '',
      earliestAvailable: dates.length ? dates[0] : '',
      latestAvailable: dates.length ? dates[dates.length - 1] : '',
      validHistoryDates: dates,
      pendingRuling: null,
    };
  });
}

/** 上报探测结果（E5）。只在真的探测到日期时才上报。 */
function reportAvailableDates(deviceId, historyType, probe) {
  if (!probe || !probe.ok) {
    return Promise.resolve({ uploaded: false, reason: (probe && probe.reason) || 'NOT_PROBED' });
  }
  return request.call('reportBandAvailableDates', {
    body: {
      device_id: deviceId,
      history_type: historyType,
      valid_history_dates: probe.validHistoryDates,
      probed_at: new Date().toISOString(),
    },
  }).then(function () {
    return { uploaded: true, reason: '' };
  });
}

/**
 * 打开即同步（M-WX-FG）。
 *
 * @param {object} args { deviceId, customerId, cold }  cold=true 表示本次是"冷启动打开"
 * @returns {Promise<{uploaded:boolean, reason:string, probe:object}>}
 *
 * 未注入采集器时：**不产生任何上报**，reason = COLLECTOR_NOT_SELECTED。
 * 这使"能力未接入"与"同步成功但当天没数据"（state=no_data_today）在调用方**可区分**。
 */
function syncOnShow(args) {
  var a = args || {};
  var deviceId = a.deviceId;
  if (!deviceId) {
    return Promise.resolve({ uploaded: false, reason: 'NO_DEVICE', probe: null });
  }
  if (!hasCollector()) {
    return probeAvailableDates(deviceId, a.historyType || 'daily').then(function (probe) {
      return { uploaded: false, reason: COLLECTOR_NOT_SELECTED, probe: probe };
    });
  }

  var trigger = a.cold ? 'on_show_cold' : 'on_show_hot';
  var batchNo = 'mp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);

  return probeAvailableDates(deviceId, a.historyType || 'daily').then(function (probe) {
    var uploaded = reportAvailableDates(deviceId, a.historyType || 'daily', probe);
    return uploaded.then(function () {
      return _collector(deviceId, probe.pullStartDate, probe.latestAvailable).then(function (out) {
        var records = (out && out.records) || [];
        var state = records.length ? 'synced' : 'no_data_today';
        return request.call('reportBandSyncBatch', {
          idempotencyKey: batchNo,
          body: {
            device_id: deviceId,
            customer_id: a.customerId,
            batch_no: batchNo,
            trigger: trigger,
            state: state,
            synced_at: new Date().toISOString(),
            last_success_date: records.length ? probe.latestAvailable : undefined,
          },
        }).then(function () {
          return uploadRecords(deviceId, records).then(function () {
            return { uploaded: true, reason: '', probe: probe, records: records.length, state: state };
          });
        });
      });
    });
  });
}

/** 逐条上报遥测（E2）。写请求，键由"设备 + 指标 + 业务日"决定，重试同一笔即同键。 */
function uploadRecords(deviceId, records) {
  var list = records || [];
  if (!list.length) return Promise.resolve({ uploaded: 0 });

  var chain = Promise.resolve();
  var done = 0;
  list.forEach(function (rec) {
    chain = chain.then(function () {
      var key = 't-' + deviceId + '-' + rec.metric + '-' + rec.date;
      return request.call('upsertBandTelemetry', {
        idempotencyKey: key,
        body: Object.assign({ device_id: deviceId }, rec),
      }).then(function () {
        done += 1;
      });
    });
  });
  return chain.then(function () {
    return { uploaded: done };
  });
}

/** 读同步状态（E6）。客户可见的是"状态 + 同步日期"，不作任何原因解释。 */
function syncStatus(customerId) {
  return request.call('getBandSyncStatus', { params: { id: customerId } }).then(function (res) {
    return res.data || {};
  });
}

module.exports = {
  COLLECTOR_NOT_SELECTED: COLLECTOR_NOT_SELECTED,
  PENDING_RULING: PENDING_RULING,
  setCollector: setCollector,
  hasCollector: hasCollector,
  probeAvailableDates: probeAvailableDates,
  reportAvailableDates: reportAvailableDates,
  syncOnShow: syncOnShow,
  uploadRecords: uploadRecords,
  syncStatus: syncStatus,
};