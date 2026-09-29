/**
 * 端 B · 环境配置分离
 * ============================================================================
 *
 * B-1 验收项之一。三档：dev（本地联调）/ staging（预发）/ prod（生产）。
 *
 * 🛑 为什么基址必须来自环境变量而不是写在源码里
 * ---------------------------------------------------------------------------
 * 骨架阶段若把后端地址写进源码，那么"打一个 staging 包"和"打一个 prod 包"
 * 会是同一份代码 —— 地址的不同只在"打包前有没有人记得改"。这正是本仓反复
 * 登记的那类失效：一个靠人记住的步骤，等于没有步骤。
 *
 * 故这里只认 `VITE_API_BASE_URL`：它由环境注入，缺失即**启动失败**（fail-closed），
 * 而不是回落到某个"大概是联调地址"的默认值 —— 回落会让一次配错的部署看上去
 * 正常，直到有人发现数据写到了错误的库。
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
