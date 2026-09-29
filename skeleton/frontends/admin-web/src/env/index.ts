/**
 * 端 A · 环境配置分离
 * ============================================================================
 *
 * 与端 B 同构（dev / staging / prod 三档），基址同样只认 `VITE_API_BASE_URL`，
 * 缺失即失败、不回落默认值 —— 配错的部署必须立刻停，而不是打到错误的库。
 *
 * 为什么三端各自持一份 env 而不是共用
 * ---------------------------------------------------------------------------
 * 三端的部署形态不同（小程序 / App / Web），注入方式也不同（小程序无构建期
 * 环境变量，见端 C 的 env.js）。共用一份会让"某一端的约束"变成"所有端的约束"，
 * 而这些约束并不相同 —— 那是用一处便利换三处妥协。
 */

type EnvName = 'dev' | 'staging' | 'prod';

function resolveEnv(): EnvName {
  const mode = import.meta.env.MODE;
  if (mode === 'production') return 'prod';
  if (mode === 'staging') return 'staging';
  return 'dev';
}

function resolveBaseUrl(): string {
  const url = import.meta.env.VITE_API_BASE_URL;
  if (!url) {
    throw new Error(
      'env: 缺少 VITE_API_BASE_URL。'
      + '请按环境注入后端基址（如 .env.dev / .env.staging / .env.prod），'
      + '不得回落到默认地址 —— 配错的部署必须立刻失败，而不是打到错误的库。'
    );
  }
  return url;
}

export const ENV: EnvName = resolveEnv();
export const getBaseUrl = resolveBaseUrl;
export const TIMEOUT_MS = 15000;
