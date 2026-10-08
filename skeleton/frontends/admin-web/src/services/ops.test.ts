import { describe, it, expect, vi, afterEach } from 'vitest';

vi.mock('../services/token-store', () => ({ readToken: () => 'tok-abc' }));
vi.mock('../env', () => ({
  getRequestBaseUrl: () => 'http://gw.test/api/v1',
  TIMEOUT_MS: 1000,
}));

import {
  commitSettlement,
  exportSettlementCsv,
  getOpsHealth,
  getSettlementPayload,
  listSettlementStatements,
  previewSettlement,
  settlementCsvUrl,
  type SettlementResult,
} from './ops';

function mockFetch(body: unknown, opts: { ok?: boolean; status?: number; disposition?: string; text?: string } = {}) {
  const fn = vi.fn().mockResolvedValue({
    ok: opts.ok ?? true,
    status: opts.status ?? 200,
    headers: {
      get: (k: string) => (k.toLowerCase() === 'content-disposition' ? opts.disposition ?? null : null),
    },
    json: async () => body,
    text: async () => opts.text ?? '',
  } as unknown as Response);
  vi.stubGlobal('fetch', fn);
  return fn;
}

const RESULT: SettlementResult = {
  splitApplied: true,
  otherStoreVisitRatio: '0.10',
  totalVisits: 10,
  allocations: [
    { storeId: 'S-A', visitCount: 9, eccShare: '18.00', lossShare: '7.20', splitApplied: true },
    { storeId: 'S-B', visitCount: 1, eccShare: '2.00', lossShare: '0.80', splitApplied: true },
  ],
};

describe('结算域 · 请求体口径（把"两套命名并存"钉成测试事实）', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('preview 请求体是 **camelCase**（后端 PreviewRequest 是裸 record，无 @JsonProperty）', async () => {
    const f = mockFetch({ code: 0, data: RESULT, trace_id: 't1' });
    await previewSettlement({
      closingStoreId: 'S-A',
      visitsByStore: { 'S-A': 9, 'S-B': 1 },
      eccUnits: 20,
      lossYuan: 8,
    });
    const sent = JSON.parse(f.mock.calls[0][1].body as string);
    expect(Object.keys(sent).sort()).toEqual(['closingStoreId', 'eccUnits', 'lossYuan', 'visitsByStore']);
    // 🛑 反向：snake_case 形态**不得**出现在这个端点上（否则后端字段绑定不上 ⇒ 422）
    expect(sent.closing_store_id).toBeUndefined();
    expect(sent.visits_by_store).toBeUndefined();
  });

  it('commit 请求体是 **snake_case 包装层 + result 内 camelCase**（同一次请求两种口径）', async () => {
    const f = mockFetch({ code: 0, data: { status: 'CREATED', statement_id: 'st-1' }, trace_id: 't2' });
    await commitSettlement({
      period: '2026-10',
      closing_store_id: 'S-A',
      visits_by_store: { 'S-A': 9, 'S-B': 1 },
      ecc_units: 20,
      loss_yuan: 8,
      result: RESULT,
    });
    const sent = JSON.parse(f.mock.calls[0][1].body as string);
    expect(Object.keys(sent).sort()).toEqual(['closing_store_id', 'ecc_units', 'loss_yuan', 'period', 'result', 'visits_by_store']);
    // result 内部回到 camelCase（它就是 preview 的返回类型）—— 双口径在同一次请求里并存
    expect(Object.keys(sent.result).sort()).toEqual(['allocations', 'otherStoreVisitRatio', 'splitApplied', 'totalVisits']);
    expect(sent.result.allocations[0].eccShare).toBe('18.00');
  });

  it('commit 返回的 ALREADY_EXISTS 原样透出（界面必须能区分"幂等命中"与失败）', async () => {
    mockFetch({ code: 0, data: { status: 'ALREADY_EXISTS', statement_id: 'st-9' }, trace_id: 't3' });
    const r = await commitSettlement({
      period: '2026-10',
      closing_store_id: 'S-A',
      visits_by_store: { 'S-A': 1 },
      ecc_units: 1,
      loss_yuan: 1,
      result: RESULT,
    });
    expect(r?.status).toBe('ALREADY_EXISTS');
    expect(r?.statement_id).toBe('st-9');
  });
});

describe('结算域 · 读取面', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('对账清单把 period 放在 **query**（不是 body、不是 path）', async () => {
    const f = mockFetch({ code: 0, data: [], trace_id: 't4' });
    await listSettlementStatements('2026-10');
    expect(f.mock.calls[0][0]).toBe('http://gw.test/api/v1/settlement/statements?period=2026-10');
    expect(f.mock.calls[0][1].body).toBeUndefined();
  });

  it('快照定点读走 **path 参数**，且 URL 编码', async () => {
    const f = mockFetch({ code: 0, data: { period: '2026-10' }, trace_id: 't5' });
    await getSettlementPayload('a b');
    expect(f.mock.calls[0][0]).toBe('http://gw.test/api/v1/settlement/statements/a%20b/payload');
  });

  it('运维自检：GET、无 body、data 原样透出（不做本地推算/补默认值）', async () => {
    const f = mockFetch({
      code: 0,
      data: { db: { status: 'UP', registered_migrations: 24 }, redis: { status: 'not_configured' }, audit_chain: { status: 'UP', checked: 12 }, overall: 'UP' },
      trace_id: 't6',
    });
    const r = await getOpsHealth();
    expect(f.mock.calls[0][0]).toBe('http://gw.test/api/v1/ops/health');
    expect(r?.redis.status).toBe('not_configured');
    expect(r?.overall).toBe('UP');
  });
});

describe('结算域 · CSV 导出（非信封）', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('settlementCsvUrl 含 /api/v1 前缀（第 56 条那一类漏拼会在本断言里红）', () => {
    expect(settlementCsvUrl('2026-10'))
      .toBe('http://gw.test/api/v1/settlement/statements.csv?period=2026-10');
  });

  it('导出返回文件名与文本内容', async () => {
    mockFetch({}, { disposition: `attachment; filename="settlement-2026-10.csv"`, text: 'statement_id,period\r\nst-1,2026-10\r\n' });
    const r = await exportSettlementCsv('2026-10');
    expect(r.filename).toBe('settlement-2026-10.csv');
    expect(r.content.startsWith('statement_id,period')).toBe(true);
  });

  it('导出失败（非总部档位 403）⇒ 抛错，不得静默返回空内容', async () => {
    mockFetch({ code: 2003, message: '租户不匹配', trace_id: 't7' }, { ok: false, status: 403 });
    await expect(exportSettlementCsv('2026-10')).rejects.toThrow();
  });
});
