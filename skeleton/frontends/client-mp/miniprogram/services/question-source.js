/**
 * 端 C · 每日填报题目源（**注入点**，为登记的缺口留接口）
 * ============================================================================
 *
 * 🛑 这个文件存在的原因，是承认一个还没闭合的事实
 * ---------------------------------------------------------------------------
 * PRD P0-09 规定了每日填报的**形态**（全部点选、无开放输入、首屏完成、≤30 秒），
 * 但**没有给出题目清单**；契约 `DailyReportRequest.answers_json` 只声明
 * `type: object`，说明文字仅「全点选，无开放输入」；本端可用的 15 个端点里
 * **也没有任何"获取填报题目"的端点**。
 *
 * 也就是说：**"客户每天到底在点什么"这件事，在契约与 PRD 里都没有定义。**
 *
 * 处置（与手环采集器同一纪律）：**机制先做完整，内容留注入点**。
 *   · 未注入题目源 -> `getQuestions()` 返回空数组，页面显示"填报项待配置"，
 *     **不允许提交空卷**；
 *   · 注入后（服务端下发 / 配置中心 / 内置题目包，三条路任选其一）页面自动可用。
 *
 * 🛑 为什么不在本文件里写一批"看起来合理"的题目
 * ---------------------------------------------------------------------------
 * `answers_json` 是**进入依从性统计的原始数据**。编出来的题目会产出"数据是真的
 * 流过的、只是题目是编的"的记录，而它会照常计入「本周 X/7 天」，把依从性抬上去。
 * 本仓对"静默假绿"已有多次实锤，故此处宁可显示"待配置"。
 *
 * 登记位置：`frontends/README.md` 的「待确认项」一节，**不写成"已解决"**。
 */

'use strict';

/** 题目源注入点。签名：source() -> Promise<Array<{key,title,options:[{value,label}]}>> */
var _source = null;

function setQuestionSource(fn) {
  _source = typeof fn === 'function' ? fn : null;
}

function hasQuestionSource() {
  return typeof _source === 'function';
}

/**
 * 取题目。未注入时返回空数组 —— **页面据此显示"待配置"，不生成占位题**。
 * 注入源抛错时同样返回空数组，并把原因附在返回值上供排查（不吞掉错误信息，
 * 但不把技术细节渲染到客户界面）。
 */
function getQuestions() {
  if (!_source) {
    return Promise.resolve({ ok: false, reason: 'QUESTION_SOURCE_NOT_CONFIGURED', questions: [] });
  }
  return _source().then(function (list) {
    var arr = (list || []).filter(function (q) {
      return q && q.key && q.title && (q.options || []).length > 0;
    });
    return { ok: arr.length > 0, reason: arr.length ? '' : 'EMPTY_QUESTION_SET', questions: arr };
  }).catch(function (err) {
    return {
      ok: false,
      reason: 'QUESTION_SOURCE_ERROR',
      detail: err && err.message ? err.message : '',
      questions: [],
    };
  });
}

module.exports = {
  setQuestionSource: setQuestionSource,
  hasQuestionSource: hasQuestionSource,
  getQuestions: getQuestions,
};