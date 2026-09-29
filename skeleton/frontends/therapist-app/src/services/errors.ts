/**
 * 端 B · 错误码 → 可读文案
 * ============================================================================
 *
 * 依据：契约 `x-error-codes`（12 个 code 的逐字枚举）。
 *
 * 🛑 契约明文：`ResultEnvelope.message` 是**面向开发者**的，
 *    逐字写着「不得直接渲染给客户」——本端虽不是客户端，但同一纪律成立：
 *    直接渲染 `message` 会让运维/业务看到 "VISIBILITY_DENIED" 这类内部标识，
 *    也会在契约换措辞时把界面文案带着一起漂移。
 *    故本层提供 code → 文案映射，界面只读这里。
 *
 * 🛑 与端 C 的差异（同一张表，两类用户，两套文案）
 * ---------------------------------------------------------------------------
 * 端 C（客户）看到的必须是中性措辞（W-2 / R5），且**不能**出现"权限档位"这类
 * 内部概念 —— 客户不该知道系统里有"档位"这个东西。
 * 端 B（调理师 / 经络师）是**内部使用者**：对他们说"该字段组你的角色不可见，
 * 请联系管理员"是**正确且必要**的沟通，含糊化反而会让一线无从上报。
 * 故两端共用同一组 code，但**文案必须分开写**，不得互相复用（复用会把
 * 客户端的中性措辞搬到内部端，造成一线看不懂；或反之泄露内部概念给客户）。
 */

/** 契约 `x-error-codes` 逐条转录。 */
export const ERROR_CODE = Object.freeze({
  VALIDATION_FAILED: 1001,
  UNAUTHENTICATED: 1002,
  VISIBILITY_DENIED: 2001,
  GATE_MISSING: 2002,
  TENANT_MISMATCH: 2003,
  PLACEHOLDER_OUT_OF_SCOPE: 2004,
  NOT_FOUND: 3001,
  VERSION_CONFLICT: 4001,
  IDEMPOTENT_REPLAY: 4002,
  BUSINESS_RULE_VIOLATED: 5001,
  RATE_LIMITED: 6001,
  INTERNAL_ERROR: 9001,
});

/**
 * code → 给一线看的文案。**内部端口径**：说清"是什么、找谁、怎么办"。
 * 🛑 `2002 GATE_MISSING` 的文案点明"缺哪个前置项"——契约要求
 *    `data.missing_items` 给出缺失项名，故文案里把该字段名写出来，
 *    让一线知道该去看响应的哪一部分。
 */
export const COPY: Readonly<Record<number, string>> = Object.freeze({
  1001: '提交内容不完整或有误，请检查必填项与格式。',
  1002: '登录已失效，请重新登录。',
  2001: '该字段组你的角色不可见（服务端已按下发档位裁剪）。如确需，请联系管理员调整档位。',
  2002: '前置门禁未通过：请查看响应 data.missing_items 列出的缺失项，补齐后再提交。',
  2003: '租户不匹配：该资源不属于当前租户，或请求头与登录身份不符。',
  2004: '该能力属占位、尚未纳入本期范围。',
  3001: '资源不存在（或不在当前租户范围内）。',
  4001: '该版本不可覆盖（方案 / 题库 / 判定结论为版本化对象），请基于最新版本重试。',
  4002: '重复提交：该请求此前已成功处理，已返回首次结果（不是失败）。',
  5001: '业务规则不满足，无法提交。',
  6001: '操作过于频繁，请稍后再试。',
  9001: '服务内部错误，请记录 trace_id 并联系技术支持。',
});

export const UNKNOWN_COPY = '未知错误码：请记录 trace_id 并联系技术支持。';
export const NETWORK_COPY = '网络异常或请求超时，请检查网络后重试。';

/** `describe` 的分类。界面按 kind 决定呈现方式（如 401 触发重新登录）。 */
export type ErrorKind = 'auth' | 'replay' | 'denied' | 'gate' | 'conflict' | 'server' | 'network' | 'unknown';

export interface Described {
  readonly text: string;
  readonly code?: number;
  readonly traceId: string;
  readonly kind: ErrorKind;
  /** 原始 message：**仅供日志**，界面不得直接渲染（契约明文）。 */
  readonly developerMessage?: string;
}

function kindOf(code: number): ErrorKind {
  switch (code) {
    case 1002: return 'auth';
    case 2001: return 'denied';
    case 2002: return 'gate';
    case 4001: return 'conflict';
    case 4002: return 'replay';
    case 9001: return 'server';
    default: return 'unknown';
  }
}

/**
 * 把任意异常整理成可渲染信息。
 * 🛑 `4002 IDEMPOTENT_REPLAY` **不是失败**：契约逐字「幂等键命中已有记录
 *    （返回首次结果）」，即操作**已经成功过了**。界面必须把它当成功态呈现，
 *    否则一线会重复点第二次、第三次，制造更多重放。
 */
export function describe(err: unknown): Described {
  const e = err as { code?: number; traceId?: string; message?: string } | null;
  if (e && typeof e.code === 'number') {
    return {
      text: COPY[e.code] ?? UNKNOWN_COPY,
      code: e.code,
      traceId: e.traceId ?? '',
      kind: kindOf(e.code),
      developerMessage: e.message,
    };
  }
  return {
    text: NETWORK_COPY,
    traceId: e?.traceId ?? '',
    kind: 'network',
    developerMessage: e?.message,
  };
}

/** 是否为幂等重放（按契约语义 = 此前已成功）。 */
export function isReplay(d: Described): boolean {
  return d.code === ERROR_CODE.IDEMPOTENT_REPLAY;
}

/** 是否需要重新登录。 */
export function needRelogin(d: Described): boolean {
  return d.kind === 'auth';
}