import { describe, it, expect, vi, afterEach } from 'vitest';

// 🛑 与 `api/client.test.ts` 同法：钉死两个「隐式协议」依赖点（令牌源 + URL 前缀），
//    这样"本文件有没有用真源"是可验证的，而不是靠读代码判断。
vi.mock('../services/token-store', () => ({ readToken: () => 'tok-abc' }));
vi.mock('../env', () => ({
  getRequestBaseUrl: () => 'http://gw.test/api/v1',
  TIMEOUT_MS: 1000,
}));

import {
  callInternal,
  downloadInternal,
  internalCapabilityUrl,
  isAllowedInternalCapability,
  parseFilename,
} from './internal';

function mockFetch(body: unknown, opts: { ok?: boolean; status?: number; disposition?: string; text?: string } = {}) {
  const fn = vi.fn().mockResolvedValue({
    ok: opts.ok ?? true,
    status: opts.status ?? 200,
    headers: {
      get: (k: string) => {
        if (k.toLowerCase() === 'content-disposition') return opts.disposition ?? null;
        return null;
      },
    },
    json: async () => body,
    text: async () => opts.text ?? '',
  } as unknown as Response);
  vi.stubGlobal('fetch', fn);
  return fn;
}

describe('internal · 白名单（不得发明端点）', () => {
  it('只放行清册里登记过的能力 id', () => {
    expect(isAllowedInternalCapability('getOpsHealth')).toBe(true);
    expect(isAllowedInternalCapability('commitSettlement')).toBe(true);
    expect(isAllowedInternalCapability('someOps')).toBe(false);
  });

  it('未登记 id：URL 构建与出站都直接抛（不回落、不猜）', async () => {
    expect(() => internalCapabilityUrl('someOps')).toThrow(/not registered/);
    await expect(callInternal('someOps')).rejects.toThrow(/not registered/);
  });
});

describe('internal · URL 与协议头', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('URL = 网关根 + 契约 Base Path + 清册 path（前缀漏拼会在本断言里红）', () => {
    expect(internalCapabilityUrl('listSettlementStatements', { query: { period: '2026-10' } }))
      .toBe('http://gw.test/api/v1/settlement/statements?period=2026-10');
  });

  it('path 占位符替换（{statementId} → 值，且做 URL 编码）', () => {
    expect(internalCapabilityUrl('getSettlementPayload', { params: { statementId: 's/1' } }))
      .toBe('http://gw.test/api/v1/settlement/statements/s%2F1/payload');
  });

  it('query 里 undefined 的键被丢掉（不拼出 k=undefined）', () => {
    expect(internalCapabilityUrl('listSettlementStatements', { query: { period: undefined } }))
      .toBe('http://gw.test/api/v1/settlement/statements');
  });

  it('GET：带 Bearer、不带 Idempotency-Key', async () => {
    const f = mockFetch({ code: 0, data: { overall: 'UP' }, trace_id: 't1' });
    const r = await callInternal('getOpsHealth');
    expect(r.data).toEqual({ overall: 'UP' });
    expect(r.traceId).toBe('t1');
    const hdrs = f.mock.calls[0][1].headers as Record<string, string>;
    expect(hdrs.Authorization).toBe('Bearer tok-abc');
    expect(hdrs['Idempotency-Key']).toBeUndefined();
  });

  it('POST：带 Idempotency-Key + 序列化 body', async () => {
    const f = mockFetch({ code: 0, data: { status: 'CREATED' }, trace_id: 't2' });
    await callInternal('commitSettlement', { body: { period: '2026-10' } });
    const hdrs = f.mock.calls[0][1].headers as Record<string, string>;
    expect(hdrs['Idempotency-Key']).toBeDefined();
    expect(JSON.parse(f.mock.calls[0][1].body as string)).toEqual({ period: '2026-10' });
  });
});

describe('internal · 错误路径（形状必须与 client.ts 同构）', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('code!=0：抛出并带 code / status / traceId / data', async () => {
    mockFetch({ code: 5001, message: 'nope', data: { missing_items: ['x'] }, trace_id: 't3' }, { ok: false, status: 422 });
    let caught: { code?: number; data?: unknown; status?: number } | undefined;
    try {
      await callInternal('getOpsHealth');
    } catch (e) {
      caught = e as { code?: number; data?: unknown; status?: number };
    }
    expect(caught?.code).toBe(5001);
    expect(caught?.status).toBe(422);
    expect(caught?.data).toEqual({ missing_items: ['x'] });
  });
});

describe('internal · 非信封下载（CSV）', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('成功：文件名取自 filename*（RFC 6266），内容是文本', async () => {
    mockFetch({}, {
      disposition: `attachment; filename="settlement-2026-10.csv"; filename*=UTF-8''settlement-%E7%94%B2.csv`,
      text: 'a,b\r\n1,2\r\n',
    });
    const r = await downloadInternal('exportSettlementCsv', { query: { period: '2026-10' } });
    expect(r.filename).toBe('settlement-甲.csv');
    expect(r.content).toBe('a,b\r\n1,2\r\n');
  });

  it('失败：后端回的是【信封 JSON】⇒ 必须按信封抛，不得表现为"下载到空文件"', async () => {
    mockFetch({ code: 2003, message: '非总部档位', trace_id: 't9' }, { ok: false, status: 403 });
    let caught: { code?: number } | undefined;
    try {
      await downloadInternal('exportSettlementCsv', { query: { period: '2026-10' } });
    } catch (e) {
      caught = e as { code?: number };
    }
    expect(caught?.code).toBe(2003);
  });

  it('失败但连信封都不是（网关错误页）：如实报网络层错误，不臆造 code', async () => {
    mockFetch({}, { ok: false, status: 502 });
    let caught: { code?: number; status?: number } | undefined;
    try {
      await downloadInternal('exportSettlementCsv', { query: { period: '2026-10' } });
    } catch (e) {
      caught = e as { code?: number; status?: number };
    }
    expect(caught?.code).toBeUndefined();
    expect(caught?.status).toBe(502);
  });
});

describe('internal · parseFilename 回落链', () => {
  it('无任何 filename ⇒ 用 URL 末段（不编一个好听的名字）', () => {
    expect(parseFilename('', 'http://gw.test/api/v1/settlement/statements.csv?period=2026-10'))
      .toBe('statements.csv');
  });

  it('只有 filename= ⇒ 用它', () => {
    expect(parseFilename('attachment; filename="x.csv"', 'http://gw.test/a'))
      .toBe('x.csv');
  });
});
