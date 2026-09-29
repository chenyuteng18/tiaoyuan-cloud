/**
 * 端 C · 客户小程序 —— 环境配置分离
 * ============================================================================
 *
 * B-1 验收项之一：三端各自要有**环境配置分离**（dev / staging / prod）。
 *
 * 🛑 为什么按小程序的 envVersion 取值，而不是打包时替换字符串
 * ---------------------------------------------------------------------------
 * 小程序没有"构建期环境变量注入"这回事：上传的是一份代码包，体验版与正式版
 * **共用同一个包**。若在上传前把 baseUrl 写死成生产地址，那么体验版也会打到
 * 生产 —— 那是"测试动作影响真实客户数据"，属于本仓反复登记的那类事故。
 *
 * 故本文件以微信运行时的 `envVersion`（develop / trial / release）为准选择后端：
 *   develop -> 联调环境
 *   trial   -> 预发（体验版）
 *   release -> 生产
 * 这样一份包在三处各取各的地址，不靠"上传前记得改"。
 *
 * 🛑 地址不写死域名，只写占位：真实域名由运维下发（见下面 DY_BASE_URLS）。
 *    骨架阶段留 `null` 并在取用时显式报错 —— 沉默地打到一个错误地址，
 *    比立刻失败更难查。
 */

'use strict';

// 三个环境的后端基址。骨架阶段均为 null，由交付时填入；
// 契约 servers.url = /api/v1，故基址只到网关根。
var DY_BASE_URLS = {
  develop: null,
  trial: null,
  release: null,
};

// 允许外部注入（便于本地联调与 CI 冒烟）：在 app.js 最早处调用 setBaseUrl。
function setBaseUrl(env, url) {
  if (!DY_BASE_URLS.hasOwnProperty(env)) {
    throw new Error('env: unknown environment: ' + env);
  }
  DY_BASE_URLS[env] = url;
}

function currentEnv() {
  try {
    // eslint-disable-next-line no-undef
    var v = typeof __wxConfig !== 'undefined' && __wxConfig && __wxConfig.envVersion;
    if (v === 'develop' || v === 'trial' || v === 'release') return v;
  } catch (e) {
    /* 非小程序宿主（如 node 冒烟）时落到 develop */
  }
  return 'develop';
}

function baseUrl() {
  var env = currentEnv();
  var url = DY_BASE_URLS[env];
  if (!url) {
    throw new Error(
      'env: 未配置 ' + env + ' 环境的后端基址。'
      + '请在 app.js 里调用 setBaseUrl("' + env + '", "https://...")，或填入 miniprogram/env.js 的 DY_BASE_URLS。'
    );
  }
  return url;
}

module.exports = {
  DY_BASE_URLS: DY_BASE_URLS,
  setBaseUrl: setBaseUrl,
  currentEnv: currentEnv,
  get baseUrl() {
    return baseUrl();
  },
  timeoutMs: 15000,
};
