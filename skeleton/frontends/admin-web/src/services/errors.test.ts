import { describe, it, expect } from 'vitest';
import { describe as describeErr, isReplay, needRelogin } from './errors';

// 端 A 错误文案层：验证「不得模糊报错」的机器可读那一半真的到达用户可见文案。
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

  it('网络异常（无 code）：归为 network，不泄漏内部 message', () => {
    const d = describeErr(new Error('fetch failed'));
    expect(d.kind).toBe('network');
    expect(d.code).toBeUndefined();
  });
});
