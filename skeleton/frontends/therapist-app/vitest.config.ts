import { defineConfig } from 'vitest/config';

// 端 B 测试基建：当前套件为「请求适配层 + 错误文案 + 契约生成物」纯逻辑 + fetch mock，
// 不需要 DOM，用 node 环境（jsdom 在 brokered-fs 下写 web storage 到 temp 会 EPERM）。
// 将来若补组件冒烟，在对应测试文件顶部加 `// @vitest-environment jsdom` 即可。
export default defineConfig({
  test: {
    environment: 'node',
    globals: false,
    // forks 池：therapist 端用 vi.mock 较多，threads 池在 brokered-fs 下写 transform/ssr
    // 缓存会被 EPERM 拒绝；forks 池每文件独立进程，绕开该共享写。
    // singleFork：多进程并发写 vite 缓存时在本机 brokered-fs 下仍会偶发 EPERM 竞态，
    // 串行单进程彻底消除该竞态 —— 测试全为纯逻辑，串行的耗时成本可忽略。
    pool: 'forks',
    poolOptions: { forks: { singleFork: true } },
    include: ['src/**/*.test.ts'],
    typecheck: { enabled: false },
  },
});
