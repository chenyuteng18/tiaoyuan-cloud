/**
 * 端 C 首页（骨架）
 * ---------------------------------------------------------------------------
 * 骨架阶段这一页只回答一个问题：**契约是否已经正确地接到了这一端。**
 * 它把生成物里的契约版本与本端可用端点数显示出来 —— 这两项都是机械转录的结果，
 * 所以"页面上看到的数字"与"冻结契约里的数字"不一致时，说明有人手改了生成物
 * 或忘了重跑生成器，而 `npm run build` 的 --check 会同时报红。
 *
 * 🛑 刻意不做业务页面：G-B 的阶段顺序明确要求先骨架 + SDK，业务页面填充排在
 * G-A（agreement / case_archive 通路）收口之后，否则会做出"能点但取不到数"的页面。
 */

'use strict';

var contract = require('../../contract/endpoints.js');
var env = require('../../env.js');

Page({
  data: {
    contractVersion: contract.CONTRACT_VERSION,
    endpointCount: contract.ENDPOINT_IDS.length,
    tokenRoles: contract.END_TOKEN_ROLES.join(', '),
    envName: '',
    configured: false,
  },

  onLoad: function () {
    var configured = false;
    try {
      // 未配置后端地址时 env.baseUrl 会抛错 —— 这里把它转成页面上的一行提示，
      // 而不是让整页崩掉（骨架阶段地址本就是空的）。
      configured = !!env.baseUrl;
    } catch (e) {
      configured = false;
    }
    this.setData({ envName: env.currentEnv(), configured: configured });
  },
});
