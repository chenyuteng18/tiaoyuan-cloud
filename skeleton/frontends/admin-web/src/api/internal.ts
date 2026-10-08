/**
 * 端 A · 【契约外】内部能力面出站通道
 * ============================================================================
 *
 * 与 `api/client.ts`（契约端点通道）**并列的第二条通道**，存在的唯一理由是：
 * 端 A 确实需要打一些**契约未声明的自建能力面端点**（结算落账 / 对账报表 / 运维自检），
 * 而契约通道的白名单（生成物 `ENDPOINT_IDS`）**按设计**只放行契约端点。
 *
 * 🛑 为什么不是"把 client.ts 的白名单放开一点"
 * ---------------------------------------------------------------------------
 * `isAllowedOperation` 的语义是「本端在**契约**里被授予了这个 operation」。
 * 让它同时放行契约外端点，会让那句错误文案（`not granted to this console`）
 * 变成半真半假的话 —— 而这条文案正是"契约改了但产物没重跑"的排查入口。
 * ⇒ 两条通道各自守自己的白名单，互不越界：
 *      · `client.ts`   ⟷ 生成物（契约机械转录，随 `gen-endpoints --check` 变红）
 *      · `internal.ts` ⟷ 清册（`contract/internal-capabilities.ts`，随 ⑫ 判据变红）
 *
 * 🛑 本层【复用】client.ts 的协议常量与幂等键生成，不得各自重写
 * ---------------------------------------------------------------------------
 *   1. **头名 / 令牌前缀取自生成物常量 `PROTOCOL.*`**（第 57 条）。
 *      另起一份字面量 = 跨端协议片段的第二份定义：改一处即静默分叉，
 *      表现为全量 401 / 幂等去重失效，而 tsc / 构建 / 门禁全绿。
 *      `tools/a-check.mjs` ⑫ 会核"本文件确实引用了 PROTOCOL.* 的成员访问"。
 *   2. **幂等键走 `client.ts` 的 `newIdempotencyKey()`**：前缀 `ad-` 是本端标识，
 *      重写一遍就会出现两个格式。
 *   3. **令牌一律经 `services/token-store.ts`**（`tools/a-check.mjs` ⑧ 守）。
 *   4. **错误对象形状与 `client.ts` 完全一致**（`code` / `status` / `traceId` / `data`），
 *      这样 `services/errors.ts` 的 `describe()` 对两条通道**同构生效** ——
 *      否则契约外端点的错误会退化成"网络异常"一句废话（本仓第 61 条同族）。
 *
 * 🛑 两种响应形态
 * ---------------------------------------------------------------------------
 *   · `callInternal<T>`    —— 标准信封（`{code,message,data,trace_id}`），返回 `data`；
 *   · `downloadInternal`   —— **非信封**的字节流（CSV 导出）。它不能走 `res.json()`，
 *     故单开一个函数：文件名从 `Content-Disposition` 解（RFC 6266，优先 `filename*`）。
 *     🛑 失败时后端**仍然回信封 JSON** ⇒ 下载路径也要解析错误信封，
 *     否则 403/422 会被当成"下载到了 0 字节的文件"（静默的假成功）。
 *
 * 🛑 分层纪律（⑫ 判据守）
 * ---------------------------------------------------------------------------
 *   契约外出站**只允许**出现在 `services/` 层。页面直接调本文件会让
 *   "某端点被哪个服务封装"不再可查，也让清册的"每一条都被调用"这条断言失去意义。
 */

import { PROTOCOL } from '../contract/endpoints';
import { internalCapabilityById, type InternalCapability } from '../contract/internal-capabilities';
import { readToken } from '../services/token-store';
import { getRequestBaseUrl, TIMEOUT_MS } from '../env';
import { newIdempotencyKey } from './client';

export interface InternalCallOptions {
  /** 路径占位参数（`{statementId}` → 值）。 */
  params?: Record<string, string | number>;
  /** query 参数（`undefined` 的键会被丢掉，不拼成 `k=undefined`）。 */
  query?: Record<string, string | number | boolean | undefined>;
  body?: unknown;
  /** 写请求的幂等键；未给则自动生成（仅限后端未规定键构成时）。 */
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export interface InternalEnvelope<T> {
  code: number;
  message?: string;
  data?: T;
  trace_id?: string;
}

/** 出站错误（形状与 `api/client.ts` 抛出的完全一致，见文件头 4）。 */
export interface InternalCallError extends Error {
  code?: number;
  status?: number;
  traceId?: string;
  data?: unknown;
}

export function isAllowedInternalCapability(id: string): boolean {
  return internalCapabilityById(id) !== null;
}

/** 未登记的 id：**抛**，不回落、不猜 —— 这正是"不得发明端点"的执行点。 */
function requireCapability(id: string): InternalCapability {
  const cap = internalCapabilityById(id);
  if (!cap) {
    throw new Error(
      `internal: capability not registered in this console: ${id}`
      + '\n契约外端点必须先在 contract/internal-capabilities.ts 登记，'
      + '且该登记会被 tools/a-check.mjs ⑫ 与后端 INTERNAL_ENDPOINTS 台账逐条比对。'
    );
  }
  return cap;
}

function fillPath(path: string, params?: Record<string, string | number>): string {
  let out = path;
  for (const [k, v] of Object.entries(params ?? {})) {
    out = out.replace(`{${k}}`, encodeURIComponent(String(v)));
  }
  return out;
}

/**
 * 契约外出站 URL = 网关根 + 契约 Base Path + 清册里的 path。
 *
 * 🛑 前半段用 `getRequestBaseUrl()`（**不是** `getBaseUrl()`）—— 与 `client.ts` 同源。
 *    契约外端点虽然不在契约里，但**服务端仍挂在同一个 `/api/v1` 前缀下**
 *    （见后端 `@RequestMapping("/api/v1/settlement")`）。
 *    这里写错的表现是本仓第 56 条那一类：全量 404，而 tsc / 构建全绿。
 */
export function internalCapabilityUrl(
  id: string,
  opts: Pick<InternalCallOptions, 'params' | 'query'> = {}
): string {
  const cap = requireCapability(id);
  let url = getRequestBaseUrl() + fillPath(cap.path, opts.params);
  const qs = Object.entries(opts.query ?? {})
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  if (qs.length) url += (url.includes('?') ? '&' : '?') + qs.join('&');
  return url;
}

/** 组请求头：协议片段一律取自生成物常量（第 57 条），令牌经 token-store（⑧）。 */
function headersFor(cap: InternalCapability, opts: InternalCallOptions): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = readToken();
  if (token) headers[PROTOCOL.AUTH_HEADER] = `${PROTOCOL.AUTH_SCHEME} ${token}`;
  if (cap.method !== 'GET') {
    headers[PROTOCOL.IDEMPOTENCY_HEADER] = opts.idempotencyKey ?? newIdempotencyKey();
  }
  return headers;
}

/** 把"非 2xx / code!=0"整理成与 client.ts 同形的错误对象（含 body.data）。 */
function raise(status: number, body: InternalEnvelope<unknown>, traceId: string): never {
  const err = new Error(body.message ?? `request failed: ${status}`) as InternalCallError;
  err.code = body.code;
  err.status = status;
  err.traceId = traceId;
  err.data = body.data;
  throw err;
}

/** 调用一个已登记的契约外端点（标准信封）。 */
export async function callInternal<T = unknown>(
  id: string,
  opts: InternalCallOptions = {}
): Promise<{ data: T | undefined; traceId: string; status: number }> {
  const cap = requireCapability(id);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(internalCapabilityUrl(id, opts), {
      method: cap.method,
      headers: headersFor(cap, opts),
      body: cap.method === 'GET' ? undefined : JSON.stringify(opts.body ?? {}),
      signal: opts.signal ?? controller.signal,
    });
    const body = (await res.json().catch(() => ({}))) as InternalEnvelope<T>;
    const traceId = body.trace_id ?? res.headers.get(PROTOCOL.TRACE_HEADER) ?? '';
    if (!res.ok || body.code !== PROTOCOL.ENVELOPE_OK_CODE) {
      raise(res.status, body as InternalEnvelope<unknown>, traceId);
    }
    return { data: body.data, traceId, status: res.status };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * 下载一个已登记的非信封端点（当前只有 CSV 导出）。
 *
 * 🛑 返回**文本**而不是 Blob：调用方（页面）自己组 `Blob` 触发下载，
 *    本层不引入浏览器专有对象 ⇒ 行为测试在 node 环境下也能跑（本端测试基建是 node）。
 */
export async function downloadInternal(
  id: string,
  opts: InternalCallOptions = {}
): Promise<{ filename: string; content: string }> {
  const cap = requireCapability(id);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const url = internalCapabilityUrl(id, opts);
    const res = await fetch(url, {
      method: cap.method,
      headers: headersFor(cap, opts),
      signal: opts.signal ?? controller.signal,
    });
    const disposition = res.headers.get('Content-Disposition') ?? '';
    if (!res.ok) {
      // 🛑 失败时后端回的是**信封 JSON**，必须按信封解析后再抛 ——
      //    否则 403（非总部档位）会表现为"下载成功但文件是空的"。
      const body = (await res.json().catch(() => ({}))) as InternalEnvelope<unknown>;
      const traceId = body.trace_id ?? res.headers.get(PROTOCOL.TRACE_HEADER) ?? '';
      if (body.code === undefined) {
        // 连信封都不是（网关 502 / 反代错误页）：如实报网络层错误，不臆造 code。
        const err = new Error(`download failed: ${res.status}`) as InternalCallError;
        err.status = res.status;
        err.traceId = traceId;
        throw err;
      }
      raise(res.status, body, traceId);
    }
    return { filename: parseFilename(disposition, url), content: await res.text() };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * 解析 `Content-Disposition` 的文件名（RFC 6266）。
 * 优先 `filename*=UTF-8''<pct-encoded>`（后端对非 ASCII 周期名走这条），
 * 回落 `filename="..."`；两样都没有时用 URL 末段（**不编一个好听的名字**）。
 */
export function parseFilename(disposition: string, url: string): string {
  const star = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition);
  if (star) {
    try {
      return decodeURIComponent(star[1].trim());
    } catch {
      return star[1].trim();
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition);
  if (plain) return plain[1].trim();
  const seg = url.split('?')[0].split('/').filter(Boolean).pop();
  return seg ?? 'download';
}
