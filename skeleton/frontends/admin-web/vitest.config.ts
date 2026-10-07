import { defineConfig } from 'vitest/config';

// 端 A 测试基建：当前套件为「请求适配层 + 错误文案 + 契约生成物」纯逻辑 + fetch mock，
// 不需要 DOM，故用 node 环境（jsdom 在 brokered-fs 下写 web storage 到 temp 会 EPERM）。
// 将来若补组件冒烟，在对应测试文件顶部加 `// @vitest-environment jsdom` 即可。
export default defineConfig({
  test: {
    environment: 'node',
    globals: false,
    include: ['src/**/*.test.ts'],
    // 契约生成物与源码同仓，类型检查交由 build-check 负责；这里只跑行为。
    typecheck: { enabled: false },
    // 🛑 串行单进程：多进程并发写 vite transform 缓存在本机 brokered-fs 下会偶发
    // EPERM 竞态（曾致部分测试文件未被收集）。测试全为纯逻辑，串行成本可忽略。
    pool: 'forks',
    poolOptions: { forks: { singleFork: true } },
  },
});
