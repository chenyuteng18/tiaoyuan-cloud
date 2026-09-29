import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 端 B 的 API 基址按 mode 取（见 src/env/index.ts）。
// 🛑 骨架阶段不写死真实域名：环境配置分离由 env 层负责，这里只做中转。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/api': {
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
