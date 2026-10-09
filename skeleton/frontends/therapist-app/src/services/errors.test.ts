import { describe, it, expect } from 'vitest';
import { COPY, ERROR_CODE, describe as describeErr, isReplay, needRelogin } from './errors';

// 端 B 错误文案层：验证「不得模糊报错」的机器可读那一半真的到达用户可见文案。
describe('errors.describe · 契约拒绝原因名必须可见', () => {
  it('2001 可见性拒绝：reasons 含 denied_fields 且拼进文案', () => {
    const d = describeErr({ code: 2001, data: { denied_fields: ['age', 'gender'] }, message: 'm', traceId: 't' });
    expect(d.code).toBe(2001);
    expect(d.reasons).toEqual(['age', 'gender']);
    expect(d.kind).toBe('denied');
    expect(d.text).toContain('age');
    expect(d.text).toContain('gender');
  });

  it('2002 门禁缺失：reasons 含 missing_items', () => {
    const d = describeErr({ code: 2002, data: { missing_items: ['screening'] }, message: 'm', traceId: 't' });
    expect(d.reasons).toEqual(['screening']);
    expect(d.kind).toBe('gate');
  });

  it('4002 幂等重放：不是失败', () => {
    const d = describeErr({ code: 4002, message: 'replay', traceId: 't' });
    expect(d.kind).toBe('replay');
    expect(isReplay(d)).toBe(true);
  });

  it('1002 未认证：需重登', () => {
    const d = describeErr({ code: 1002, message: 'unauth', traceId: 't' });
    expect(d.kind).toBe('auth');
    expect(needRelogin(d)).toBe(true);
  });

  it('网络异常（无 code）：归为 network', () => {
    const d = describeErr(new Error('fetch failed'));
    expect(d.kind).toBe('network');
    expect(d.code).toBeUndefined();
  });
});

// 🛑 第 85 条：错误码覆盖 —— 两表（`ERROR_CODE` 名→码 / `COPY` 码→文案）必须**双向一致**。
//    这里守的是**本端内部**的一致性（"加了 ERROR_CODE 忘了 COPY" ⇒ 该 code 静默落兜底文案）。
//    契约侧的三源交叉在 `tools/build-check.mjs` ④k（架构层），本测试是第二把锁（行为层）。
describe('errors · 错误码覆盖（第 85 条）', () => {
  // 🛑 必须**显式标注** `number[]`，不能靠推断 —— `Object.values(ERROR_CODE)` 的类型是
  //    **字面量联合数组**（`(2002 | 2001 | ... | 9001)[]`），而 `Object.keys(COPY).map(Number)`
  //    是 `number[]` ⇒ 下一处 `contractCodes.includes(c: number)` 报 `TS2345`
  //    （"Argument of type 'number' is not assignable to parameter of type '2002 | 2001 | …'"）。
  //    🛑 这正是**第 76 条① 的复发**：`vitest` 走 esbuild **剥类型**，21 例全绿；
  //    而 `check:build` 的 `tsc --noEmit` 才报 —— **写测试同样要过类型门禁**。
  //    标注而不是强转（`as`）：它表达的是"这是一列数字 id"，不是"忽略类型"。
  const contractCodes: number[] = Object.values(ERROR_CODE);

  it('COPY 覆盖 ERROR_CODE 的每一个 code（无缺项）', () => {
    const missing = contractCodes.filter((c) => COPY[c] === undefined);
    expect(missing).toEqual([]);
  });

  it('COPY 里没有 ERROR_CODE 之外的 code（无表外码）', () => {
    const extra = Object.keys(COPY).map(Number).filter((c) => !contractCodes.includes(c));
    expect(extra).toEqual([]);
  });

  it('两表条目数一致（计数等式 —— 判据必须证明它认识的东西覆盖了全部）', () => {
    expect(Object.keys(COPY).length).toBe(contractCodes.length);
  });
});
