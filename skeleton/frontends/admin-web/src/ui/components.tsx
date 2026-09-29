/**
 * 端 A · 通用 UI 元件
 * ============================================================================
 *
 * 全部内联样式（零第三方 UI 库）—— 与端 B 同纪律：引 UI 库会让
 * "构建是否通过"多一个与本任务无关的变量。
 *
 * 🛑 端 A 比端 B 多三个元件，都对应契约里的硬条文
 * ---------------------------------------------------------------------------
 *   1. `UnsettledBar` —— 把契约的**未完结声明**渲染出来（第 54 条的界面落点）。
 *      凡引用未完结端点的页面都必须渲染它，否则 `tools/a-check.mjs` 的
 *      `unsettled-surfaced` 判据会报红。**这是一条被门禁守着的纪律**。
 *   2. `ScopeNote` —— 行级范围说明。让使用者知道"我看到的是本店 / 辖区 / 全量"，
 *      避免把"范围裁剪"误读成"数据不存在"。
 *   3. `SuperAdminNote` —— 仅超管端点提示（I7）。
 */

import type { CSSProperties, ReactNode } from 'react';
import { COLOR, FONT, RADIUS, SPACE } from './tokens';

export const cardStyle: CSSProperties = {
  background: COLOR.surface,
  border: `1px solid ${COLOR.border}`,
  borderRadius: RADIUS.md,
  padding: SPACE.lg,
  marginBottom: SPACE.lg,
};

export const labelStyle: CSSProperties = {
  fontSize: FONT.xs,
  color: COLOR.textMuted,
  letterSpacing: '0.02em',
};

export const inputStyle: CSSProperties = {
  width: '100%',
  boxSizing: 'border-box',
  padding: `${SPACE.sm}px ${SPACE.md}px`,
  fontSize: FONT.md,
  border: `1px solid ${COLOR.border}`,
  borderRadius: RADIUS.sm,
  background: COLOR.surface,
  color: COLOR.text,
};

export const selectStyle: CSSProperties = { ...inputStyle };

export const textareaStyle: CSSProperties = {
  ...inputStyle,
  minHeight: 72,
  fontFamily: 'inherit',
};

export const buttonStyle: CSSProperties = {
  padding: `${SPACE.sm}px ${SPACE.lg}px`,
  fontSize: FONT.md,
  border: 'none',
  borderRadius: RADIUS.sm,
  background: COLOR.brand,
  color: COLOR.textInverse,
  cursor: 'pointer',
};

export const buttonGhostStyle: CSSProperties = {
  ...buttonStyle,
  background: 'transparent',
  color: COLOR.brand,
  border: `1px solid ${COLOR.border}`,
};

export const buttonDangerStyle: CSSProperties = {
  ...buttonStyle,
  background: COLOR.danger,
};

export function Card({ title, children, hint }: { title?: string; children: ReactNode; hint?: string }) {
  return (
    <section style={cardStyle}>
      {title ? (
        <h2 style={{ fontSize: FONT.lg, margin: `0 0 ${SPACE.md}px`, color: COLOR.text }}>{title}</h2>
      ) : null}
      {hint ? <p style={{ ...labelStyle, margin: `0 0 ${SPACE.md}px` }}>{hint}</p> : null}
      {children}
    </section>
  );
}

export function KV({ k, v }: { k: string; v: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: SPACE.md, padding: `${SPACE.xs}px 0`, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>
      <div style={{ ...labelStyle, width: 180, flexShrink: 0 }}>{k}</div>
      <div style={{ fontSize: FONT.md, color: COLOR.text, flex: 1, wordBreak: 'break-all' }}>{v}</div>
    </div>
  );
}

export function Badge({ text, tone = 'brand' }: { text: string; tone?: keyof typeof COLOR }) {
  return (
    <span
      style={{
        display: 'inline-block',
        padding: `2px ${SPACE.sm}px`,
        fontSize: FONT.xs,
        borderRadius: RADIUS.sm,
        border: `1px solid ${COLOR[tone]}`,
        color: COLOR[tone],
        background: 'transparent',
      }}
    >
      {text}
    </span>
  );
}

export function Empty({ text }: { text: string }) {
  return <p style={{ ...labelStyle, padding: SPACE.lg, textAlign: 'center' }}>{text}</p>;
}

export function ErrorBar({
  text,
  traceId,
  tone = 'danger',
}: {
  text: string;
  traceId?: string;
  tone?: keyof typeof COLOR;
}) {
  return (
    <div
      style={{
        border: `1px solid ${COLOR[tone]}`,
        background: COLOR.surfaceAlt,
        color: COLOR.text,
        borderRadius: RADIUS.sm,
        padding: `${SPACE.sm}px ${SPACE.md}px`,
        fontSize: FONT.sm,
        marginBottom: SPACE.md,
      }}
    >
      <div>{text}</div>
      {traceId ? <div style={{ ...labelStyle, marginTop: SPACE.xs }}>trace_id: {traceId}</div> : null}
    </div>
  );
}

export function Page({
  title,
  roleLabel,
  children,
}: {
  title: string;
  roleLabel: string;
  children: ReactNode;
}) {
  return (
    <div style={{ maxWidth: 960, margin: '0 auto', padding: SPACE.lg }}>
      <header style={{ display: 'flex', alignItems: 'baseline', gap: SPACE.md, marginBottom: SPACE.lg }}>
        <h1 style={{ fontSize: FONT.xl, margin: 0, color: COLOR.text }}>{title}</h1>
        <Badge text={roleLabel} />
      </header>
      {children}
    </div>
  );
}

/**
 * 契约【未完结】声明的界面落点（第 54 条的后果面）。
 *
 * 🛑 为什么必须有这个元件、且不能在页面里手写一行灰字
 * ---------------------------------------------------------------------------
 * 端 A 的 8 个端点带未完结声明（I 域 7 个 `占位待冻结` + G4 `待裁定`）。
 * 如果页面把它们当普通功能渲染，使用者会以为"这功能已经能用了"——
 * 而契约逐字写的是**占位、待冻结**。
 * 本仓的 `tools/a-check.mjs` 有一条 `unsettled-surfaced` 判据：
 * **引用了未完结端点却不引用任何未完结标注的源文件会报红**。
 * 故这里既是文案，也是那条判据的**唯一合法形态**。
 *
 * 🛑 `frontier` 与 `ruling-pending` 的文案必须分开（语义不同，不得合并）
 * ---------------------------------------------------------------------------
 *   · `frontier`（占位待冻结）：**功能还没写**——请求会返回 2004 或直接未实现。
 *   · `ruling-pending`（待裁定）：**功能可能已生效**，只是取值依据是推断，
 *     裁定后取值可能变。把它说成"还没上线"是错的。
 */
export function UnsettledBar({
  items,
}: {
  items: readonly { readonly kind: string; readonly note: string }[];
}) {
  if (items.length === 0) return null;
  return (
    <div style={{ marginBottom: SPACE.md }}>
      {items.map((it) => {
        const frozen = it.kind === 'frontier';
        const tone = frozen ? COLOR.frozen : COLOR.pending;
        return (
          <div
            key={`${it.kind}-${it.note}`}
            style={{
              border: `1px solid ${tone}`,
              background: COLOR.surfaceAlt,
              borderRadius: RADIUS.sm,
              padding: `${SPACE.sm}px ${SPACE.md}px`,
              fontSize: FONT.sm,
              marginBottom: SPACE.xs,
            }}
          >
            <strong style={{ color: tone }}>
              {frozen ? '契约：占位待冻结' : '契约：取值待裁定'}
            </strong>
            <span style={{ color: COLOR.text }}> —— {it.note}</span>
            <div style={{ ...labelStyle, marginTop: SPACE.xs }}>
              {frozen
                ? '含义：该端点契约内容尚未写完，功能属未来。**不得当已冻结功能使用**。'
                : '含义：契约内容已写、但取值依据是推断，**功能可能已生效**；裁定后取值可能变。'}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/** 行级范围说明（服务端按子档位裁剪返回行数）。 */
export function ScopeNote({ text, hint }: { text: string; hint: string }) {
  return (
    <div
      style={{
        border: `1px dashed ${COLOR.info}`,
        borderRadius: RADIUS.sm,
        padding: `${SPACE.sm}px ${SPACE.md}px`,
        fontSize: FONT.sm,
        marginBottom: SPACE.md,
      }}
    >
      <strong style={{ color: COLOR.info }}>可见范围：{text}</strong>
      <div style={{ ...labelStyle, marginTop: SPACE.xs }}>{hint}</div>
    </div>
  );
}

/** 仅超管提示（契约 `x-super-admin-only`）。 */
export function SuperAdminNote() {
  return (
    <div
      style={{
        border: `1px solid ${COLOR.warn}`,
        borderRadius: RADIUS.sm,
        padding: `${SPACE.sm}px ${SPACE.md}px`,
        fontSize: FONT.sm,
        marginBottom: SPACE.md,
        color: COLOR.text,
      }}
    >
      <strong style={{ color: COLOR.warn }}>契约：仅超管</strong>
      <span>
        {' '}—— 该端点带 `x-super-admin-only`。非超管档位调用是服务端 403，
        界面**不应**依据本地判断放行；本页只负责如实提示。
      </span>
    </div>
  );
}