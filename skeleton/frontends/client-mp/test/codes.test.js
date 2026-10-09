'use strict';
const test = require('node:test');
const assert = require('node:assert');

const codes = require('../miniprogram/services/codes.js');

/**
 * 端 C · 错误码映射的**行为层**第二把锁（第 85 条）
 * ============================================================================
 *
 * 🛑 为什么这里**不**核对"CODE_COPY 的键集 == 契约的 12 个码"
 * ---------------------------------------------------------------------------
 * 那条等式已由架构层判据 `tools/build-check.mjs` ④k `error-code-coverage` 守住
 * （它直接读 `contract/openapi-v1.0.0.yaml` 与 `_cut/<端>.openapi.yaml`，做
 * 契约↔映射表**双向等式**）。
 *
 * 要在这一层重做同一件事，测试就必须**手抄一份 12 个码的清单** ——
 * 那会凭空造出**第三份**契约副本，而它只会在"有人忘了同步"时与真源不一致，
 * 是一种**假第二把锁**（看着更严，实际是新的腐烂点）。本仓对"手抄清单"已有实锤
 * （`clientPaths.js` 手写白名单漏 B5 ⇒ 第 45 条），故此处**刻意不手抄**。
 *
 * 🛑 那么这一层守什么（映射表**自己**说不出来的那部分）
 * ---------------------------------------------------------------------------
 * `CODE_COPY` 是一张**值**表 —— 它能自证"我有哪些键"，但**不能**自证
 * "这些键在 `describe()` 里真的走到了自己的文案"。两类缺陷只有**真跑一次**才能看见：
 *
 *   ① **有码却落兜底**：某个码的文案被清空/写成空白，`describe()` 里
 *      `text: text || UNKNOWN_COPY` 会把它静默换成兜底文案 ——
 *      表里"有这一条"，客户看到的却是"操作未完成，请稍后再试"。
 *      ⇒ 判据必须落到 `describe()` 的**返回值**上，而不是表里的键。
 *   ② **映射退化成"照单全收"**：表外码本该走兜底（契约新增了码而这一端还没跟上时，
 *      兜底必须仍然含糊但不撒谎），若哪天改成 `CODE_COPY[code] || ...` 之类，
 *      就需要确认表外码仍有确定行为。
 *
 * 🛑 `4002` 是唯一的例外，且这个例外是**契约语义**不是遗漏：
 * 它是幂等命中（返回首次处理结果），**属成功路径**，故 `describe()` 在
 * `isReplay()` 处提前返回、`text` 为空 —— 页面对 'replay' 的处置是"当成功继续"，
 * 而不是渲染一条错误。故本测试把 4002 单独断言，而不是把它塞进"必须有文案"里。
 */

test('CODE_COPY 里除 4002（幂等命中=成功路径）外，每个码经 describe() 都给出**非兜底**文案', () => {
  const offenders = [];
  for (const key of Object.keys(codes.CODE_COPY)) {
    const code = Number(key);
    if (code === 4002) continue;
    const d = codes.describe({ code: code });
    if (!d.text || d.text === codes.UNKNOWN_COPY) {
      offenders.push(code + ' → ' + JSON.stringify(d.text));
    }
  }
  // 🛑 失败信息必须列出**具体哪些码**落了兜底 —— 否则只知道"有一处不对"，
  //    排查要从 12 个码里一个个试（第 24 条：报错要指向真因）。
  assert.deepEqual(offenders, [], '以下码在 describe() 里落到了兜底文案（等于客户看不到它自己的原因）：' + offenders.join(' · '));
});

test('表外码走兜底文案 —— 映射不是"照单全收"', () => {
  const d = codes.describe({ code: 7777 });
  assert.equal(d.text, codes.UNKNOWN_COPY);
});

test('4002 幂等命中：kind=replay 且 text 为空（不是错误，不渲染报错）', () => {
  const d = codes.describe({ code: 4002 });
  assert.equal(d.kind, 'replay');
  assert.equal(d.text, '');
});

test('网络层失败（无 code）与"服务端明确拒绝"文案分开 —— 不是同一件事', () => {
  const net = codes.describe({ code: null });
  assert.equal(net.kind, 'network');
  assert.equal(net.text, codes.NETWORK_COPY);
  assert.notEqual(codes.NETWORK_COPY, codes.UNKNOWN_COPY);
});
