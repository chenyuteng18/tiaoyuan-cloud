/**
 * 端 A · 错误码 → 可读文案
 * ============================================================================
 *
 * 依据：契约 `x-error-codes`（12 个 code 的逐字枚举，与端 B / 端 C 同一张表）。
 *
 * 🛑 契约明文：`ResultEnvelope.message` 是**面向开发者**的，
 *    逐字写着「不得直接渲染给客户」—— 端 A 同样成立，且这里还有一条更硬的理由：
 *    端 A 是**运维 / 管理层**用的界面，直接渲染 `message` 会把
 *    "VISIBILITY_DENIED"、"GATE_MISSING" 这类内部标识堆到管理者眼前，
 *    他们据此上报或截图，等于把内部标识变成了对外沟通语言。
 *    故本层提供 code → 文案映射，界面只读这里。
 *
 * 🛑 与端 B / 端 C 的差异（同一张表，三类用户，三套文案）
 * ---------------------------------------------------------------------------
 *   · 端 C（客户）：中性措辞，不得出现"权限档位"等内部概念。
 *   · 端 B（调理师 / 经络师）：内部使用者，说清"是什么、找谁、怎么办"。
 *   · 端 A（管理后台）：使用者是**有裁定权的人**。故文案要点明
 *     **哪条契约条文 / 哪个配置在起作用**（如 2002 缺前置项、2004 占位超额），
 *     使他们能直接定位到该改的配置或该提的裁定。
 * ⇒ 三套文案**必须分开写**，不得互相复用。
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
 * code → 给管理端看的文案。**管理端口径**：点明"哪条契约/配置在起作用"。
 */
export const COPY: Readonly<Record<number, string>> = Object.freeze({
  1001: '提交内容不完整或有误（校验失败）。请检查必填项与格式后重试。',
  1002: '登录已失效，请重新登录。',
  2001: '该字段组在当前档位不可见 —— 服务端已按 A2 下发的可见性档位裁剪。'
    + '这不是故障：若业务确需该字段，属「调整档位」决策，请走配置变更。',
  2002: '前置门禁未通过：响应 data.missing_items 列出缺失项。'
    + '请先补齐前置步骤（本系统多处为硬门禁，不可跳过）。',
  2003: '租户不匹配：该资源不属于当前租户，或请求头与登录身份不符。'
    + '若为跨店通兑场景，请核对目标门店是否在同一租户内。',
  2004: '该能力在契约中为「占位」，尚未纳入本期范围。'
    + '如需纳入，属业务裁定事项（变更业务裁定），不是配置开关。',
  3001: '资源不存在，或不在当前行级范围内（本店 / 辖区 / 全量）。'
    + '请先确认当前档位的可见范围，再判断是否真的不存在。',
  4001: '该版本不可覆盖（方案 / 题库 / 判定结论为版本化对象）。请基于最新版本重试。',
  4002: '重复提交：该请求此前已成功处理，本次返回首次结果（**不是失败**，无需重试）。',
  5001: '业务规则不满足，无法提交。请核对契约声明的前置条件。',
  6001: '操作过于频繁，请稍后再试。',
  9001: '服务内部错误，请记录 trace_id 并联系技术支持。',
});

export const UNKNOWN_COPY = '未知错误码：请记录 trace_id 并联系技术支持。';
export const NETWORK_COPY = '网络异常或请求超时，请检查网络后重试。';

/** `describe` 的分类。界面按 kind 决定呈现方式（如 auth 触发重新登录）。 */
export type ErrorKind =
  | 'auth' | 'replay' | 'denied' | 'gate' | 'conflict' | 'server' | 'network' | 'unknown';

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
 *    （返回首次结果）」，即操作**已经成功过了**。管理端尤其要注意 ——
 *    管理者把钱/工单类操作重复点第二次，会造成更多重放记录。
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

export function isReplay(d: Described): boolean {
  return d.code === ERROR_CODE.IDEMPOTENT_REPLAY;
}

export function needRelogin(d: Described): boolean {
  return d.kind === 'auth';
}