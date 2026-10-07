import { describe, it, expect } from 'vitest';
import { ENDPOINTS, API_BASE_PATH, PROTOCOL, endpointById } from './endpoints';

// 端 A 契约生成物自洽：与 gen-endpoints --check（39 ops）及后端 EndpointCoverageLedger 同源。
describe('contract/endpoints 生成物自洽', () => {
  it('端点总数 = 39（与 --check 一致）', () => {
    expect(ENDPOINTS.length).toBe(39);
  });

  it('API_BASE_PATH = /api/v1（出站前缀唯一来源）', () => {
    expect(API_BASE_PATH).toBe('/api/v1');
  });

  it('成功码 = 0（契约 §2.0 逐字）', () => {
    expect(PROTOCOL.ENVELOPE_OK_CODE).toBe(0);
  });

  it('每条 path 不含 /api/v1 前缀（前缀由 base path 承载，防静默 404）', () => {
    for (const e of ENDPOINTS) {
      expect(e.path.startsWith('/api/v1')).toBe(false);
    }
  });

  it('endpointById 可取已知端点且 method 正确', () => {
    const a = endpointById('authLogin');
    expect(a).not.toBeNull();
    expect(a!.method).toBe('POST');
  });

  it('ERROR_DATA_FIELDS 映射 2001/2002（不得模糊报错字段名）', () => {
    expect(PROTOCOL.ERROR_DATA_FIELDS[2001]).toBe('denied_fields');
    expect(PROTOCOL.ERROR_DATA_FIELDS[2002]).toBe('missing_items');
  });
});
