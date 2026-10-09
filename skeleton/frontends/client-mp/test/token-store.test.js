'use strict';
const test = require('node:test');
const assert = require('node:assert');

/**
 * 端 C · 本地存储（唯一权威点）的行为测试 —— 本仓第 84 条的第二把锁。
 *
 * 🛑 为什么需要它（判据之外还要有行为测试）
 * ---------------------------------------------------------------------------
 * `tools/build-check.mjs` ④b 在**构建期**守"键名不许在权威点之外出现"。
 * 但那条判据判的是**形态**（源码里有没有第二个字符串字面量），它管不到
 * "写进去的键与读出来的键在**运行期**是不是同一个" —— 那是行为，不是形态。
 * 本文件补上另一半：用**内存版 wx 存储**跑真实调用，断言"写→读"走的确实是同一个键。
 *
 * 🛑 为什么用"拦存储调用"而不是"读代码断言常量相等"
 * ---------------------------------------------------------------------------
 * 后者是**同义反复**（`readToken` 用了 TOKEN_KEY，再断言 TOKEN_KEY 等于自己）。
 * 前者记录 `wx.setStorageSync/getStorageSync` **实际收到的键**，断言两者的集合相等
 * 且等于导出的 `TOKEN_STORAGE_KEY` —— 这样"有人把 readToken 改成读别的键"
 * 一定会被抓到（即使他把常量也改了，两道防线里至少判据那一层会红）。
 */

// 🛑 必须在 `require` 之前装好 wx：`env.js` / `request.js` 在导入期会读小程序运行时。
function installWxStorage(initial) {
  const mem = new Map(Object.entries(initial || {}));
  const calls = [];
  global.wx = {
    getStorageSync: function (k) { calls.push(['get', k]); return mem.has(k) ? mem.get(k) : ''; },
    setStorageSync: function (k, v) { calls.push(['set', k]); mem.set(k, v); },
    removeStorageSync: function (k) { calls.push(['remove', k]); mem.delete(k); },
  };
  return { mem, calls };
}
installWxStorage();

const tokenStore = require('../miniprogram/services/token-store.js');

test('readToken：空存储返回空串（不返回 null —— 调用点不必处理两种空值）', () => {
  installWxStorage();
  assert.strictEqual(tokenStore.readToken(), '');
});

test('write → read 往返', () => {
  installWxStorage();
  tokenStore.writeToken('mp-abc');
  assert.strictEqual(tokenStore.readToken(), 'mp-abc');
});

test('写与读用的**是同一个键**（拦存储调用自证，不靠读代码）', () => {
  const { calls } = installWxStorage();
  tokenStore.writeToken('t1');
  tokenStore.readToken();
  const written = new Set(calls.filter((c) => c[0] === 'set').map((c) => c[1]));
  const read = new Set(calls.filter((c) => c[0] === 'get').map((c) => c[1]));
  assert.deepStrictEqual([...read], [tokenStore.TOKEN_STORAGE_KEY]);
  assert.deepStrictEqual([...written], [...read]);
});

test('clearToken 同时清令牌与档案缓存（只清一个 ⇒「已登出还显示旧档案」是静默的错）', () => {
  const { mem } = installWxStorage();
  tokenStore.writeToken('t');
  tokenStore.writeProfile({ name: 'x' });
  tokenStore.clearToken();
  assert.strictEqual(mem.has(tokenStore.TOKEN_STORAGE_KEY), false);
  assert.strictEqual(mem.has(tokenStore.PROFILE_STORAGE_KEY), false);
});

test('profile 往返 + 缺省 null（档案缓存不参与任何判定）', () => {
  installWxStorage();
  assert.strictEqual(tokenStore.readProfile(), null);
  tokenStore.writeProfile({ name: 'x' });
  assert.deepStrictEqual(tokenStore.readProfile(), { name: 'x' });
});

test('存储抛错时不冒泡：读返回空值、写/清静默失败（不得中断登录）', () => {
  global.wx = {
    getStorageSync: function () { throw new Error('storage unavailable'); },
    setStorageSync: function () { throw new Error('storage unavailable'); },
    removeStorageSync: function () { throw new Error('storage unavailable'); },
  };
  assert.strictEqual(tokenStore.readToken(), '');
  assert.strictEqual(tokenStore.readProfile(), null);
  assert.doesNotThrow(() => tokenStore.writeToken('t'));
  assert.doesNotThrow(() => tokenStore.clearToken());
});

test('会话层与唯一权威点同源：session.setToken 之后 token-store 读得到', () => {
  installWxStorage();
  // eslint-disable-next-line global-require
  const session = require('../miniprogram/services/session.js');
  session.setToken('via-session');
  assert.strictEqual(tokenStore.readToken(), 'via-session');
  session.clearToken();
  assert.strictEqual(tokenStore.readToken(), '');
});
