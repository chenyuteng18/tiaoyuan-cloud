/**
 * 端 B · 通用 UI 元件
 * ============================================================================
 *
 * 全部内联样式（零第三方 UI 库）—— 端 B 骨架只依赖 react / react-dom，
 * 引 UI 库会让"构建是否通过"多一个与本任务无关的变量。
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

/** 键值行。 */
export function KV({ k, v }: { k: string; v: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: SPACE.md, padding: `${SPACE.xs}px 0`, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>
      <div style={{ ...labelStyle, width: 160, flexShrink: 0 }}>{k}</div>
      <div style={{ fontSize: FONT.md, color: COLOR.text, flex: 1, wordBreak: 'break-all' }}>{v}</div>
    </div>
  );
}

/** 状态徽标。tone 取自 tokens 的语义色槽。 */
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

/** 空态。**始终显示说明文字**（不做"空白 + 无解释"的页面）。 */
export function Empty({ text }: { text: string }) {
  return <p style={{ ...labelStyle, padding: SPACE.lg, textAlign: 'center' }}>{text}</p>;
}

/** 错误条。文案来自 services/errors.ts，**不直接渲染服务端 message**。 */
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

/** 页面外壳：统一标题 + 角色徽标。 */
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
    <div style={{ maxWidth: 880, margin: '0 auto', padding: SPACE.lg }}>
      <header style={{ display: 'flex', alignItems: 'baseline', gap: SPACE.md, marginBottom: SPACE.lg }}>
        <h1 style={{ fontSize: FONT.xl, margin: 0, color: COLOR.text }}>{title}</h1>
        <Badge text={roleLabel} />
      </header>
      {children}
    </div>
  );
}