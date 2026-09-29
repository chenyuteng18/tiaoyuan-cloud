/**
 * 端 B · 设计令牌（与端 C / 端 A 同源，勿在此另立色板）
 * ============================================================================
 *
 * 品牌色：深绿 #2D5A4A（主色）+ 暖米 #FAF8F4（底色）。
 * 端 A / 端 B / 端 C 三端**共用同一组品牌令牌**，避免"同一个系统三个绿"。
 *
 * 🛑 端 B 与端 C 的**用色纪律不同**，这不是审美差异而是合规差异：
 *   · 端 C（客户）：契约 x-wording-discipline R8 逐字「派生结论侧禁用"不利"色 /
 *     措辞」；且客户不可见 ③④ 组。故端 C 的色板里**刻意没有**"警示色 /
 *     分级色"这类语义槽 —— 没有槽位就没有误用可能。
 *   · 端 B（内部）：③ 组（缺口原因）与 ④ 组（派生结果）**对 staff 可见**，
 *     且必须能让一线**一眼分辨严重度**（例如 gap_reason 的 7 个值）。
 *     故端 B 需要语义化的状态色。
 * ⇒ 结论：**色值同源、语义槽不同**。不要把端 C 的"无分级色"纪律搬到端 B
 *   （那会让一线无法分辨），也不要把端 B 的分级色搬到端 C（那会直接违 R8）。
 *
 * 🛑 一处必须写明的边界：本文件使用「不利色」这个词，是为了说明端 C **禁用**
 *    它。这不违反端 C 的纪律 —— 因为**本文件不属于客户端包**
 *    （词表 SCOPE 只覆盖 client-package；端 B 源码不在其内）。
 *    反之，端 C 的同类文件里不得出现该词（端 C 用指代，见 frontends/README §8.1）。
 */

export const COLOR = Object.freeze({
  /** 品牌主色。 */
  brand: '#2D5A4A',
  brandDark: '#22463A',
  brandLight: '#3C7360',
  /** 底色 / 承载面。 */
  bg: '#FAF8F4',
  surface: '#FFFFFF',
  surfaceAlt: '#F2EEE7',
  border: '#DED7CB',
  /** 文本。 */
  text: '#1F2933',
  textMuted: '#5B6670',
  textInverse: '#FAF8F4',
  /** 语义状态（内部端专用，端 C 不得复用）。 */
  ok: '#2F7A4F',
  warn: '#A96B14',
  danger: '#A3312A',
  info: '#2C5F8A',
});

export const SPACE = Object.freeze({
  xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32,
});

export const RADIUS = Object.freeze({ sm: 4, md: 8, lg: 12 });

export const FONT = Object.freeze({
  xs: 12, sm: 13, md: 14, lg: 16, xl: 20, xxl: 24,
});

/**
 * ③ 组（缺口原因）7 值 → 中文标签 + 状态色。
 * 🛑 键名**逐字**抄契约 `BandTelemetryData.gap_reason` 的枚举，不改写、不加值。
 * 🛑 这里**只有解释**，没有"归责"：`not_worn` 与 `compliant_removal` 必须
 *    分开显示且**都不能**读作客户过错 —— 契约 x-field-groups 把 ③ 组定义为
 *    「缺口原因**分类**」，是事实记录，不是评价。
 */
export const GAP_REASON_LABEL: Readonly<Record<string, { text: string; tone: keyof typeof COLOR }>> =
  Object.freeze({
    no_open: { text: '未开启（授权 / 开关）', tone: 'warn' },
    sync_failed: { text: '同步失败', tone: 'warn' },
    not_worn: { text: '未佩戴', tone: 'info' },
    compliant_removal: { text: '按说明书摘除（洗浴 / 桑拿 / 游泳等）', tone: 'ok' },
    involuntary_technical: { text: '非自愿技术性缺失', tone: 'danger' },
    beyond_retention_window: { text: '超出厂商保留窗口', tone: 'info' },
    unknown: { text: '未分类（待核）', tone: 'textMuted' },
  });

/**
 * ④ 组 `effect_verdict` 5 值 → 标签 + 状态色。枚举逐字抄契约。
 * 🛑 **仅端 B / 端 A 可用**：契约 `BandDerivedData.effect_verdict` 的
 *    `x-visible-to` 是 `[therapist, meridian, admin]`，客户恒不下发。
 */
export const EFFECT_VERDICT_LABEL: Readonly<Record<string, { text: string; tone: keyof typeof COLOR }>> =
  Object.freeze({
    E1显著改善: { text: 'E1 显著改善', tone: 'ok' },
    E2部分改善: { text: 'E2 部分改善', tone: 'ok' },
    E3稳定: { text: 'E3 稳定', tone: 'info' },
    E4无明显改善: { text: 'E4 无明显改善', tone: 'warn' },
    E5加重: { text: 'E5 加重', tone: 'danger' },
  });

/** `customer.status` 5 值 → 标签（逐字抄契约枚举）。 */
export const CUSTOMER_STATUS_LABEL: Readonly<Record<string, string>> = Object.freeze({
  CREATED: '已建档',
  PROFILED: '已完善档案',
  CONSENTED: '已签同意书',
  REJECTED: '已拒绝',
  ARCHIVED: '已归档',
});