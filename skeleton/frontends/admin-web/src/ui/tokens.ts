/**
 * 端 A · 设计令牌（与端 B / 端 C 同源，勿在此另立色板）
 * ============================================================================
 *
 * 品牌色：深绿 #2D5A4A（主色）+ 暖米 #FAF8F4（底色）。
 * 三端**共用同一组品牌令牌**，避免"同一个系统三个绿"。
 *
 * 🛑 端 A 比端 B 多两样东西，都不是审美差异
 * ---------------------------------------------------------------------------
 *   1. **未完结色槽**（`pending` / `frozen`）：端 A 的 I 域 7 个端点契约逐字
 *      `x-frontier: 占位待冻结`，G4 逐字 `x-ruling-pending`。这两类状态
 *      **必须在界面上显形**（第 54 条的后果面），故需要两个专用色槽。
 *      🛑 两个槽**不得合并**：`占位待冻结` = 功能还没写；`待裁定` = 功能可能已生效、
 *      只是取值依据是推断。合并会让使用者分不清"现在能不能用"。
 *   2. **行级范围色**：端 A 的服务端按子档位裁剪返回行数（本店 / 辖区 / 全量），
 *      界面上必须让使用者知道"我看到的是全量还是本店" —— 否则会以为
 *      "查不到某个客户 = 该客户不存在"，而真实原因是行级范围。
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
  /** 语义状态。 */
  ok: '#2F7A4F',
  warn: '#A96B14',
  danger: '#A3312A',
  info: '#2C5F8A',
  /** 契约未完结：占位待冻结（功能未写）。 */
  frozen: '#6B5B95',
  /** 契约未完结：取值待裁定（功能可能已生效，取值依据是推断）。 */
  pending: '#8A6D3B',
});

export const SPACE = Object.freeze({
  xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32,
});

export const RADIUS = Object.freeze({ sm: 4, md: 8, lg: 12 });

export const FONT = Object.freeze({
  xs: 12, sm: 13, md: 14, lg: 16, xl: 20, xxl: 24,
});

// ---------------------------------------------------------------------------
// 子档位（admin 的三个 token-role，逐字取自生成物 ROLE_EXPANSION）
// ---------------------------------------------------------------------------
//
// 🛑 这里**只有标签**，没有"谁是谁"的映射表
// ---------------------------------------------------------------------------
// 哪个 token 对应哪个子档位（manager / area / hq）是**服务端 A2 下发**的事实
// （`store_scope.row_level`），本层不猜、不硬编码。
// 本表只把助手返回的**行级范围**翻译成中文（枚举逐字取自契约 `store_scope.row_level`）。

/** 契约 `AuthMeData.store_scope.row_level` 三值 → 标签 + 说明。 */
export const ROW_LEVEL_LABEL: Readonly<Record<string, { text: string; hint: string; tone: keyof typeof COLOR }>> =
  Object.freeze({
    own_store: {
      text: '本店',
      hint: '门店负责人档位：服务端只返回本店范围的行（不是"数据为空"，是范围如此）。',
      tone: 'info',
    },
    region: {
      text: '辖区',
      hint: '区域督导档位：服务端只返回所辖门店的行。',
      tone: 'info',
    },
    all: {
      text: '全量',
      hint: '总部运营档位：服务端返回该租户全量行。',
      tone: 'ok',
    },
  });

// ---------------------------------------------------------------------------
// 契约枚举 → 中文标签
// ---------------------------------------------------------------------------

/** ③ 组（缺口原因）7 值。键名逐字抄契约，不改写、不加值。 */
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

/** ④ 组 `effect_verdict` 5 值。 */
export const EFFECT_VERDICT_LABEL: Readonly<Record<string, { text: string; tone: keyof typeof COLOR }>> =
  Object.freeze({
    E1显著改善: { text: 'E1 显著改善', tone: 'ok' },
    E2部分改善: { text: 'E2 部分改善', tone: 'ok' },
    E3稳定: { text: 'E3 稳定', tone: 'info' },
    E4无明显改善: { text: 'E4 无明显改善', tone: 'warn' },
    E5加重: { text: 'E5 加重', tone: 'danger' },
  });

/** `customer.status` 5 值粗粒度派生聚合态（**不是**服务主状态机）。 */
export const CUSTOMER_STATUS_LABEL: Readonly<Record<string, string>> = Object.freeze({
  CREATED: '已建档',
  PROFILED: '已完善档案',
  CONSENTED: '已签同意书',
  REJECTED: '已拒绝',
  ARCHIVED: '已归档',
});

/** `VerdictData.branch` 5 值（逐字抄契约枚举）。 */
export const VERDICT_BRANCH_LABEL: Readonly<Record<string, string>> = Object.freeze({
  稳定: '稳定',
  依从不足: '依从不足',
  达标无效: '达标无效',
  全面评估: '全面评估',
  人工复核: '人工复核',
});

/** `RefundData.outcome` 3 值。 */
export const REFUND_OUTCOME_LABEL: Readonly<Record<string, { text: string; tone: keyof typeof COLOR }>> =
  Object.freeze({
    继续: { text: '继续服务', tone: 'ok' },
    终止: { text: '终止（进入挽留 / 归档链路）', tone: 'warn' },
    归档: { text: '归档', tone: 'textMuted' },
  });

/** `RefundCreateRequest.reason_code` 6 值（**必填；未记录原因不可结案**）。 */
export const REFUND_REASON_LABEL: Readonly<Record<string, string>> = Object.freeze({
  效果未达预期: '效果未达预期',
  症状加重或出现新不适: '症状加重或出现新不适',
  服务体验或沟通问题: '服务体验或沟通问题',
  '时间·经济·家庭原因': '时间 · 经济 · 家庭原因',
  配合度不足导致无明显变化: '配合度不足导致无明显变化',
  信任或价格异议: '信任或价格异议',
});

/** `RefundCreateRequest.entry` 2 值。 */
export const REFUND_ENTRY_LABEL: Readonly<Record<string, string>> = Object.freeze({
  A门店代录: 'A · 门店代录',
  B首周期: 'B · 首周期',
});

/** `RefundReceiptData.receipt_state` 三态（逐字）。 */
export const RECEIPT_STATE_LABEL: Readonly<Record<string, { text: string; tone: keyof typeof COLOR }>> =
  Object.freeze({
    已推送: { text: '已推送', tone: 'ok' },
    '未授权（转线下）': { text: '未授权（转线下）', tone: 'warn' },
    推送失败: { text: '推送失败', tone: 'danger' },
  });

/** `store.franchise_type` 2 值。 */
export const FRANCHISE_TYPE_LABEL: Readonly<Record<string, string>> = Object.freeze({
  直营: '直营',
  加盟: '加盟',
});