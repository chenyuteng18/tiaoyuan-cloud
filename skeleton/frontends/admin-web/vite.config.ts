import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 端 A 的 API 基址按 mode 取（见 src/env/index.ts）。
// 🛑 骨架阶段不写死真实域名：环境配置分离由 env 层负责，这里只做中转。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5175,
    proxy: {
      // 🛑 路径必须含契约 Base Path（`servers[0].url = /api/v1`）：
      //    出站 URL = 网关根 + API_BASE_PATH + path ⇒ 真实请求打到 `/api/v1/...`。
      //    若代理键只写 `/api`，请求**根本不会进入这条代理规则**（路径不匹配）
      //    ⇒ dev 期全量 404，而 `npm run dev` 不报错、构建自检也不报错。
      '/api/v1': {
        target: process.env.VITE_API_PROXY || 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
