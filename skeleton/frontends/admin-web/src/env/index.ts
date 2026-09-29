/**
 * 端 A · 环境配置分离
 * ============================================================================
 *
 * 与端 B 同构（dev / staging / prod 三档），基址同样只认 `VITE_API_BASE_URL`，
 * 缺失即失败、不回落默认值 —— 配错的部署必须立刻停，而不是打到错误的库。
 *
 * 🛑 `getRequestBaseUrl()` 与 `getBaseUrl()` 的区别（本仓第 56 条）
 * ---------------------------------------------------------------------------
 * · `getBaseUrl()`      —— **网关根**（运维视角），如 `https://dy.example.com`。
 *                         契约 §2.0 的 Base Path **不在**这里。
 * · `getRequestBaseUrl()` —— **出站 URL 前缀** = 网关根 + `API_BASE_PATH`(/api/v1)。
 *                         `api/client.ts` 拼 URL **必须**用它。
 *
 * 为什么要把这件事做成函数，而不是继续写在一句注释里：
 * 契约的 `paths` 键是 `/auth/me`，真实 URL 是 `/api/v1/auth/me`，前缀由
 * `servers[0].url` 承载。此前三端出站一律 `baseUrl + endpoint.path`，而
 * 「baseUrl 只到网关根」这句**只写在注释里**，不可执行 —— 运维按注释配置
 * ⇒ 三端全量 404，而 tsc / vite / 全部门禁**全部仍绿**（它们从不发真实请求）。
 * 后端那一侧有 `EndpointCoverageLedgerTest` 钉住路由，前端这一侧此前没有。
 * 现在前缀取自**生成物**（机械转录契约 servers），漏拼即门禁判红。
 *
 * 为什么三端各自持一份 env 而不是共用
 * ---------------------------------------------------------------------------
 * 三端的部署形态不同（小程序 / App / Web），注入方式也不同（小程序无构建期
 * 环境变量，见端 C 的 env.js）。共用一份会让"某一端的约束"变成"所有端的约束"，
 * 而这些约束并不相同 —— 那是用一处便利换三处妥协。
 */

import { API_BASE_PATH } from '../contract/endpoints';

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
  return url.replace(/\/+$/, '');
}

/** 出站 URL 前缀 = 网关根 + 契约 Base Path。拼 URL 一律用它。 */
export function getRequestBaseUrl(): string {
  return resolveBaseUrl() + API_BASE_PATH;
}

export const ENV: EnvName = resolveEnv();
export const getBaseUrl = resolveBaseUrl;
export const TIMEOUT_MS = 15000;