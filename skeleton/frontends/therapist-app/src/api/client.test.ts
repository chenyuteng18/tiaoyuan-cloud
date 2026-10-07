import { describe, it, expect, vi, afterEach } from 'vitest';

// 🛑 隔离「隐式协议」依赖点（第 50/57 条同族）：token-store/access/env 钉成确定值，
// 专验 client 是否真的用「生成物常量」而非手写字面量。
vi.mock('../services/session', () => ({ getToken: () => 'th-tok' }));
vi.mock('../contract/access', () => ({ assertCanCall: () => {} }));
vi.mock('../env', () => ({
  getRequestBaseUrl: () => 'http://gw.test/api/v1',
  TIMEOUT_MS: 1000,
}));

import { call, isAllowedOperation, newIdempotencyKey } from './client';

function mockFetch(body: unknown, opts: { ok?: boolean; status?: number } = {}) {
  const fn = vi.fn().mockResolvedValue({
    ok: opts.ok ?? true,
    status: opts.status ?? 200,
    headers: { get: () => null },
    json: async () => body,
  } as unknown as Response);
  vi.stubGlobal('fetch', fn);
  return fn;
}

describe('client · 出站白名单', () => {
  it('isAllowedOperation 仅放行本端契约端点', () => {
    expect(isAllowedOperation('authLogin')).toBe(true);
    expect(isAllowedOperation('notARealOp')).toBe(false);
  });

  it('newIdempotencyKey 前缀 th-（本端标识）', () => {
    expect(newIdempotencyKey().startsWith('th-')).toBe(true);
  });

  it('call 多段路径参数替换（/customers/{id}/assessments/{assessment_id}）', async () => {
    const f = mockFetch({ code: 0, data: {}, trace_id: 't9' });
    await call('getAssessment', { role: 'therapist', params: { id: 'c1', assessment_id: 'a1' } });
    expect(f).toHaveBeenCalledWith(
      'http://gw.test/api/v1/customers/c1/assessments/a1',
      expect.anything(),
    );
  });
});

describe('client · call 成功路径', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('GET：URL 含 /api/v1 前缀 + Bearer + 不带幂等键 + 信封解析', async () => {
    const f = mockFetch({ code: 0, data: { id: 'c1', name: '张三' }, trace_id: 't1' });
    const r = await call('getCustomer', { role: 'therapist', params: { id: 'c1' } });
    expect(r.data).toEqual({ id: 'c1', name: '张三' });
    expect(f).toHaveBeenCalledWith(
      'http://gw.test/api/v1/customers/c1',
      expect.objectContaining({
        method: 'GET',
        headers: expect.objectContaining({
          Authorization: 'Bearer th-tok',
          'Content-Type': 'application/json',
        }),
      }),
    );
    const hdrs = f.mock.calls[0][1].headers as Record<string, string>;
    expect(hdrs['Idempotency-Key']).toBeUndefined();
  });

  it('POST：带 Idempotency-Key + 序列化 body', async () => {
    const f = mockFetch({ code: 0, data: { id: 'new' }, trace_id: 't2' });
    await call('createCustomer', {
      role: 'therapist',
      body: { name: 'x', age: 30, gender: 'F', phone: '138', screening_id: 's1' },
    });
    expect(f).toHaveBeenCalledWith(
      'http://gw.test/api/v1/customers',
      expect.objectContaining({ method: 'POST' }),
    );
    const hdrs = f.mock.calls[0][1].headers as Record<string, string>;
    expect(hdrs['Idempotency-Key']).toBeDefined();
  });
});

describe('client · call 错误路径（不得模糊报错）', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('HTTP 非 2xx 或 code!=0：抛出并带 code + body.data', async () => {
    mockFetch(
      { code: 2001, message: 'denied', data: { denied_fields: ['age', 'gender'] }, trace_id: 't3' },
      { ok: false, status: 403 },
    );
    let caught: { code?: number; data?: unknown } | undefined;
    try {
      await call('getCustomer', { role: 'therapist', params: { id: 'x' } });
    } catch (e) {
      caught = e as { code?: number; data?: unknown };
    }
    expect(caught).toBeDefined();
    expect(caught!.code).toBe(2001);
    expect(caught!.data).toEqual({ denied_fields: ['age', 'gender'] });
  });

  it('未授权 operation：直接抛（白名单之外）', async () => {
    await expect(call('notARealOp', { role: 'therapist' })).rejects.toThrow(/not granted/);
  });
});
