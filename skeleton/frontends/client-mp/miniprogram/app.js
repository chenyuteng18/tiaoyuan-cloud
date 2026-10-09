/**
 * 端 C · 客户小程序 —— 入口
 * ============================================================================
 *
 * 本文件做三件事：
 *   ① 记住当前运行环境（develop / trial / release）；
 *   ② 启动时把登录态读进内存；
 *   ③ **实现业务方裁定的 M-WX-FG「打开即自动同步」**（PRD §2.8.7）——
 *      冷启动进入前台时发起一次手环同步，**不需要客户点任何按钮**。
 *
 * 🛑 刻意【不】在这里填入后端地址
 * ---------------------------------------------------------------------------
 * 见 `env.js`：三个环境的基址目前都是 null，取用时才会失败。若骨架里顺手填一个
 * "看起来对"的地址，后面的人会以为它已配置好 —— 那是把"未配置"伪装成"已配置"，
 * 与本仓"沉默的假绿最危险"这条纪律直接冲突。填地址是交付动作，不是骨架动作。
 *
 * 🛑 刻意【不】在这里做可见性裁剪
 * --------------------------------
 * 客户能看见哪些字段由服务端 403 与 A2 档位解算决定（X-1）。前端不渲染不等于
 * 安全，前端也不许反过来"替服务端决定"该显示什么。
 *
 * 🛑 关于 M-WX-FG 的"自动"到底自动到哪一步
 * ---------------------------------------------------------------------------
 * 自动的是**时序**（进入前台就发起），不是**取数能力**。取数能力见
 * `services/band-sync.js` 的说明：PRD §2.8.3 的三条候选路径业务方尚未裁定
 * （Q-G5），故本端**未注入任何采集器**，同步会如实返回"能力待接入"而**不伪造
 * 一条成功记录**。这条留白是刻意的 —— 假采集器会让 E1/E2 上报出看起来正常的
 * 数据，下游会当真数据算，而那个数没有任何客户真的戴着手环。
 */

'use strict';

var env = require('./env.js');
var bandSync = require('./services/band-sync.js');
var request = require('./services/request.js');
// 🛑 令牌一律经 `./services/token-store.js`（本端唯一权威点）读取 —— 本文件此前
//    三处各自写裸键名 `'token'`（onLaunch / onShow / resolveCustomerId），
//    与会话层各定义一次（当时恰好同值，故静默）。第 84 条：改一处即分叉。
var tokenStore = require('./services/token-store.js');

/**
 * 取当前客户自身的 customer_id。
 *
 * 🛑 这里为什么是"逐条尝试"而不是直接读一个字段
 * ---------------------------------------------------------------------------
 * 契约 `AuthMeData` 只声明了**四个字段**（角色、手环字段组档位、**R1 组**的档位
 * 声明、门店 scope），**没有** customer_id；而登录响应 `LoginData` 里
 * 客户的 `staff_id` 也明确标注"客户无 staff_id（差异项）"。
 * 也就是说：**"客户端如何得知自己的 customer_id"这一点在契约里没有直接给出**。
 *
 * 处理方式：按可能性依次尝试（auth/me 的字段 → token 载荷），**都不中时返回空串**，
 * 由页面显示"档案信息待确认"而**不猜一个 id 出来**。用猜出来的 id 发请求，会造成
 * "请求成功但查的是别人/查不到"的静默错，比明确报缺更难查。
 * 该点已登记为待确认项（见 frontends/README.md），不写成"已解决"。
 */
function resolveCustomerId(profile) {
  var p = profile || {};
  if (p.customer_id) return String(p.customer_id);
  if (p.data && p.data.customer_id) return String(p.data.customer_id);

  var token = tokenStore.readToken();
  var seg = token.split('.');
  if (seg.length === 3) {
    try {
      // 小程序无 atob；用微信基础库的 base64 解码。
      var json = wx.base64ToArrayBuffer
        ? decodeBase64ToJson(seg[1])
        : null;
      if (json) {
        if (json.customer_id) return String(json.customer_id);
        if (json.sub) return String(json.sub);
      }
    } catch (e) {
      /* 载荷解不开就当作未知，不抛给调用方 */
    }
  }
  return '';
}

function decodeBase64ToJson(b64) {
  // JWT 用 base64url，需先补 padding 并还原 +/
  var s = b64.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  var buf = wx.base64ToArrayBuffer(s);
  var bytes = new Uint8Array(buf);
  var out = '';
  for (var i = 0; i < bytes.length; i += 1) {
    out += String.fromCharCode(bytes[i]);
  }
  return JSON.parse(decodeURIComponent(escape(out)));
}

/** 一次前台周期内只自动同步一次，避免 onShow 反复触发把节流窗口打满（契约 6001）。 */
var _syncedThisForeground = false;

App({
  globalData: {
    envName: 'develop',
    token: '',
    customerId: '',
    // 同步结果摘要，供首页读取（不缓存到本地 —— 数据类信息一律以服务端为准）。
    lastSyncSummary: null,
  },

  onLaunch: function () {
    this.globalData.envName = env.currentEnv();
    this.globalData.token = tokenStore.readToken();
    // 冷启动视为新的前台周期
    _syncedThisForeground = false;
  },

  onShow: function () {
    var self = this;
    var token = tokenStore.readToken();
    this.globalData.token = token;

    // 未登录：不发起同步（也会是 401）。交给页面引导登录。
    if (!token) return;
    if (_syncedThisForeground) return;
    _syncedThisForeground = true;

    // 冷启动（onLaunch 刚跑过）走 on_show_cold，热切回前台走 on_show_hot。
    var cold = this.globalData.lastSyncSummary === null;

    request.call('authMe').then(function (res) {
      var profile = res.data || {};
      self.globalData.customerId = resolveCustomerId(profile);
      if (!self.globalData.customerId) {
        self.globalData.lastSyncSummary = {
          uploaded: false,
          reason: 'NO_CUSTOMER_ID',
        };
        return null;
      }
      return bandSync.syncOnShow({
        deviceId: self.globalData.deviceId || '',
        customerId: self.globalData.customerId,
        cold: cold,
      }).then(function (summary) {
        self.globalData.lastSyncSummary = summary;
      });
    }).catch(function (err) {
      // 自动同步失败**不打扰客户**：不弹窗、不阻断首页。只记下原因，
      // 由手环页在客户主动查看时如实展示（那才是客户有心理准备的时刻）。
      self.globalData.lastSyncSummary = {
        uploaded: false,
        reason: 'AUTO_SYNC_ERROR',
        errorCode: err && err.code ? err.code : null,
      };
    });
  },
});