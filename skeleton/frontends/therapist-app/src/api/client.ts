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
import { assertCanCall } from '../contract/access';
import { getBaseUrl, TIMEOUT_MS } from '../env';

export interface CallOptions {
  /**
   * 当前登录角色（therapist / meridian）。
   * 🛑 **刻意设为必填**，不给默认值、也不允许省略。
   * ---------------------------------------------------------------------------
   * 若设为可选，则"忘了传"就等于"跳过角色准入"，而这个缝**不会让任何测试变红**
   * —— 它只会让一次越权调用成功发出请求，由服务端 403 兜底。
   * 必填使"少传一个参数"变成**编译错误**（本端 `strict` 开启，tsc 会直接报红），
   * 即把静默失效变成构建期可见的失败 —— 这是本仓反复使用的同一手法
   * （第 50/51 条的共同教训：靠人记住的步骤等于没有步骤）。
   */
  role: string;
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
 *
 * 🛑 2026-09-30 起（Task #122）本函数**同时执行 X-3 角色级准入**。
 * ---------------------------------------------------------------------------
 * 顺序刻意是"先查归属、再查角色"：
 *   1. `NOT_IN_THIS_END`（端点不属本端）—— 这是**代码缺陷**，必须先暴露；
 *   2. `ROLE_NOT_GRANTED`（端点属本端但当前角色未被授予）—— 这是**角色边界**。
 * 只做第 1 步会让"界面已藏起入口、但代码仍能调通"成为一条缝：
 * 任何一处漏藏入口的地方都会真的发出请求，由**服务端**的 403 兜底 ——
 * 那时报出来的是 HTTP 403，排查者会以为是自己权限配错了，
 * 而真实原因是**界面少藏了一个按钮**。
 * 故两处判定合流到同一个 `assertCanCall`，避免两套判定不一致。
 */
export async function call<T = unknown>(
  operationId: string,
  // 🛑 刻意**不给默认值** `= {}`：给了默认值就等于允许省略整个 opts，
  //    而 `role` 必填这件事会在调用点被悄悄绕过（tsc 报的就是这一条）。
  //    去掉默认值后，"忘了传 role"必然是编译错误。
  opts: CallOptions
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

  // X-3：角色级准入。`role` 是必填项，编译期即保证不会漏传；
// 三种失败（未知角色 / 端点不属本端 / 角色未被授予）由 assertCanCall 分开报。
  assertCanCall(operationId, opts.role);

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
