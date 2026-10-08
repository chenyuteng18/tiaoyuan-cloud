import { describe, it, expect } from 'vitest';
import {
  INTERNAL_CAPABILITIES,
  INTERNAL_CAPABILITY_IDS,
  internalCapabilityById,
} from './internal-capabilities';

/**
 * 契约外清册的**形态自洽**测试。
 *
 * 🛑 这里刻意不测"与后端台账一致"—— 那是 `tools/a-check.mjs` ⑫ 在构建期的职责
 *    （它读得到 Java 源码；本测试跑在 node 环境里，读 Java 源码会让测试与仓库布局耦合）。
 *    本文件只测**清册自己是否自洽**，即"无论台账怎么写，这份清册本身都不该出现
 *    id 重复 / 前缀重复 / 方法越界 / 空用途"这类形态问题。
 *    —— 分工：形态 → 本测试；边界 → ⑫ 判据。两者都不可省。
 */
describe('契约外清册 · 形态自洽', () => {
  it('id 非空且唯一', () => {
    const ids = INTERNAL_CAPABILITIES.map((c) => c.id);
    expect(ids.every((x) => x.trim().length > 0)).toBe(true);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('path 以 / 开头，且【不含】/api/v1（前缀由 env 层拼，写进来就是重复）', () => {
    for (const c of INTERNAL_CAPABILITIES) {
      expect(c.path.startsWith('/'), `${c.id} 的 path 必须以 / 开头`).toBe(true);
      expect(c.path.includes('/api/v1'), `${c.id} 的 path 不得含 /api/v1`).toBe(false);
    }
  });

  it('method 限 GET / POST（契约外能力面当前只有这两类）', () => {
    for (const c of INTERNAL_CAPABILITIES) {
      expect(['GET', 'POST'], `${c.id} 的方法越界`).toContain(c.method);
    }
  });

  it('每条都有非空 purpose（"它为什么可以出站"必须是一句真话，不是占位）', () => {
    for (const c of INTERNAL_CAPABILITIES) {
      expect(c.purpose.trim().length, `${c.id} 缺 purpose`).toBeGreaterThan(6);
    }
  });

  it('internalCapabilityById：未登记 id 返回 null（不得回落成"猜一个"）', () => {
    expect(internalCapabilityById('getOpsHealth')?.method).toBe('GET');
    expect(internalCapabilityById('nope')).toBeNull();
    expect(internalCapabilityById('')).toBeNull();
  });

  it('INTERNAL_CAPABILITY_IDS 与清册逐条同序同长（防两处会漂移的清单）', () => {
    expect(INTERNAL_CAPABILITY_IDS.length).toBe(INTERNAL_CAPABILITIES.length);
    expect([...INTERNAL_CAPABILITY_IDS]).toEqual(INTERNAL_CAPABILITIES.map((c) => c.id));
  });
});
