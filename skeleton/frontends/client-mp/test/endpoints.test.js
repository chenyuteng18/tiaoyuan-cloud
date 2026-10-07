'use strict';
const test = require('node:test');
const assert = require('node:assert');
const contract = require('../miniprogram/contract/endpoints.js');

// 端 C 契约生成物自洽：与 gen-endpoints --check（15 ops）及后端 EndpointCoverageLedger 同源。
test('端点总数 = 15（与 --check 一致）', () => {
  assert.equal(contract.ENDPOINTS.length, 15);
});

test('API_BASE_PATH = /api/v1', () => {
  assert.equal(contract.API_BASE_PATH, '/api/v1');
});

test('成功码 = 0', () => {
  assert.equal(contract.PROTOCOL.ENVELOPE_OK_CODE, 0);
});

test('每条 path 不含 /api/v1 前缀', () => {
  for (const e of contract.ENDPOINTS) {
    assert.ok(!e.path.startsWith('/api/v1'));
  }
});

test('endpointById 可取已知端点且 method 正确', () => {
  const a = contract.endpointById('authLogin');
  assert.ok(a);
  assert.equal(a.method, 'POST');
});

test('ERROR_DATA_FIELDS 映射 2001/2002', () => {
  assert.equal(contract.PROTOCOL.ERROR_DATA_FIELDS[2001], 'denied_fields');
  assert.equal(contract.PROTOCOL.ERROR_DATA_FIELDS[2002], 'missing_items');
});
