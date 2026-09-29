/**
 * 端 B · 请求适配层（fetch）
 * ============================================================================
 *
 * 与端 C 同一套纪律，两处不同：
 *   1. 通道是 `fetch`（generator-matrix.yaml 对本端声明 typescript-fetch）；
 *   2. 本端的角色准入集合更大（therapist / meridian），可用端点更多。
 *
 * 🛑 出站白名单同样用【生成物】
 * ---------------------------------------------------------------------------
 * `src/contract/endpoints.ts` 由 `frontends/tools/gen-endpoints.py` 从冻结契约
 * 机械转录（带 `--check` 自证）。本端**不手抄**端点清单 —— 手抄的清单会腐烂，
 * 且腐烂的两种形态（错名 / 漏项）都不会让当时的门禁变红。
 *
 * 🛑 本端【允许】出现退款相关措辞
 * --------------------------------
 * `compliance/wordlists/scan1_refund.words` 的 SCOPE 段逐字声明：本面只扫客户端包，
 * 管理端 / 经络师端的退款措辞是**合法的内部业务词汇**。所以本端不套用端 C 的
 * 禁用词自检 —— 那不是"少做一道检查"，而是词表的适用范围本就如此界定。
 *
 * 🛑 可见性仍由服务端决定（X-1）：本层不裁剪任何字段。
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
    return `th-${crypto.randomUUID()}`;
  }
  return `th-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

/**
 * 调用一个本端契约允许的 operation。出站前校验 operationId 属于本端。
 */
export async function call<T = unknown>(
  operationId: string,
  opts: CallOptions = {}
): Promise<{ data: T | undefined; traceId: string; status: number }> {
  if (!isAllowedOperation(operationId)) {
    throw new Error(
      `contract: operation not granted to this app: ${operationId}`
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
