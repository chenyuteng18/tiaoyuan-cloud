import { describe, it, expect } from 'vitest';
import { ENDPOINTS, API_BASE_PATH, PROTOCOL, endpointById } from './endpoints';

// 端 B 契约生成物自洽：与 gen-endpoints --check（29 ops）及后端 EndpointCoverageLedger 同源。
describe('contract/endpoints 生成物自洽', () => {
  it('端点总数 = 29（与 --check 一致）', () => {
    expect(ENDPOINTS.length).toBe(29);
  });

  it('API_BASE_PATH = /api/v1', () => {
    expect(API_BASE_PATH).toBe('/api/v1');
  });

  it('成功码 = 0', () => {
    expect(PROTOCOL.ENVELOPE_OK_CODE).toBe(0);
  });

  it('每条 path 不含 /api/v1 前缀', () => {
    for (const e of ENDPOINTS) {
      expect(e.path.startsWith('/api/v1')).toBe(false);
    }
  });

  it('endpointById 可取已知端点且 method 正确', () => {
    const a = endpointById('authLogin');
    expect(a).not.toBeNull();
    expect(a!.method).toBe('POST');
  });

  it('ERROR_DATA_FIELDS 映射 2001/2002', () => {
    expect(PROTOCOL.ERROR_DATA_FIELDS[2001]).toBe('denied_fields');
    expect(PROTOCOL.ERROR_DATA_FIELDS[2002]).toBe('missing_items');
  });
});
