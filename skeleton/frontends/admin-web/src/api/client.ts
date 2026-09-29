/**
 * 端 A · 请求适配层
 * ============================================================================
 *
 * 🛑 骨架阶段用 fetch，而不是 matrix 声明的 typescript-axios
 * ---------------------------------------------------------------------------
 * `generator-matrix.yaml` 对本端声明的生成器是 `typescript-axios`。本文件此刻用
 * `fetch` 实现，是**刻意的阶段性选择并在此登记**，不是遗漏：
 *   · B-1 的范围是"骨架可构建 + 目录约定 + 环境分离"，通道实现属于 B-2
 *     （把生成的 SDK 接进来时，按该端生成器落对应的通道）；
 *   · 在 SDK 接入之前引入 axios，只是多一个尚未被使用的运行时依赖。
 * 登记而非静默偏离：见 frontends/README.md 的「与 generator-matrix 的差异」。
 *
 * 其余纪律与端 B / 端 C 完全一致：
 *   · 出站白名单用【生成物】`src/contract/endpoints.ts`（机械转录，非手抄）；
 *   · 写请求自动带 `Idempotency-Key`（后端 dy-web 幂等拦截器要求）；
 *   · 可见性由服务端决定（X-1），本层不裁剪字段。
 */

import { ENDPOINT_IDS, ENDPOINTS, endpointById, type Endpoint } from '../contract/endpoints';
import { getBaseUrl, TIMEOUT_MS } from '../env';

export interface CallOptions {
  params?: Record<string, string | number>;
  query?: Record<string, string | number | boolean | undefined>;
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export interface Envelope<T> {
  code: number;
  message?: string;
  data?: T;
  trace_id?: string;
}

export function isAllowedOperation(id: string): boolean {
  return ENDPOINT_IDS.includes(id);
}

function fillPath(path: string, params?: Record<string, string | number>): string {
  let out = path;
  for (const [k, v] of Object.entries(params ?? {})) {
    out = out.replace(`{${k}}`, encodeURIComponent(String(v)));
  }
  return out;
}

function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `ad-${crypto.randomUUID()}`;
  }
  return `ad-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

export async function call<T = unknown>(
  operationId: string,
  opts: CallOptions = {}
): Promise<{ data: T | undefined; traceId: string; status: number }> {
  if (!isAllowedOperation(operationId)) {
    throw new Error(
      `contract: operation not granted to this console: ${operationId}`
      + '\n若契约确实新增了该端点，请重跑 frontends/tools/gen-endpoints.py。'
    );
  }
  const endpoint = endpointById(operationId) as Endpoint | null;
  if (!endpoint) {
    throw new Error(`contract: unknown operationId: ${operationId}`);
  }

  let url = getBaseUrl() + fillPath(endpoint.path, opts.params);
  const qs = Object.entries(opts.query ?? {})
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  if (qs.length) url += (url.includes('?') ? '&' : '?') + qs.join('&');

  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem('token');
  if (token) headers.Authorization = `Bearer ${token}`;
  if (endpoint.method !== 'GET') {
    headers['Idempotency-Key'] = opts.idempotencyKey ?? newIdempotencyKey();
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, {
      method: endpoint.method,
      headers,
      body: endpoint.method === 'GET' ? undefined : JSON.stringify(opts.body ?? {}),
      signal: opts.signal ?? controller.signal,
    });
    const body = (await res.json().catch(() => ({}))) as Envelope<T>;
    const traceId = body.trace_id ?? res.headers.get('X-Trace-Id') ?? '';
    if (!res.ok || body.code !== 0) {
      const err = new Error(body.message ?? `request failed: ${res.status}`) as Error & {
        code?: number;
        status?: number;
        traceId?: string;
      };
      err.code = body.code;
      err.status = res.status;
      err.traceId = traceId;
      throw err;
    }
    return { data: body.data, traceId, status: res.status };
  } finally {
    clearTimeout(timer);
  }
}

export const ALL_ENDPOINTS = ENDPOINTS;
