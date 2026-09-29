/**
 * 端 B · 页面：手环数据（E3 + E4）
 * ============================================================================
 *
 * 本页是 `a3_applicable` 与 `data_source` 两条契约条文的**落点**：
 *
 *  1. `a3_applicable === false` ⇒ **不渲染 a3_value**。
 *     契约给的理由逐字：「避免 0 值被误读为"戴了 0 天"」。
 *     换算到界面：不适用时若显示 "A3: 0"，一线会去追问客户"为什么一天都没戴"，
 *     而真实原因是这个指标对本次评估不适用。**显示错误的值比不显示更坏。**
 *
 *  2. `data_source === '未接入'` ⇒ 只显示"未接入"，**不显示 0 / 空图表 / 示例数据**。
 *     这条与端 C 同源（R6），端 B 同样适用 —— 未接入时给一个 0 值的表，
 *     会让一线以为"设备在跑但数据是 0"，进而去查设备。
 *
 *  3. ③ 组 `gap_reason` 7 值**逐条给中文解释**，且 `not_worn` 与
 *     `compliant_removal` 分开显示：后者是**按说明书主动摘除**（洗浴/桑拿/游泳），
 *     不是缺失。把两者合并会制造"客户不配合"的错误印象。
 */

import { useState } from 'react';
import { getBandDerived, getBandTelemetry, type BandDerived, type BandTelemetry } from '../services/domain';
import { describe } from '../services/errors';
import type { AppRole } from '../contract/access';
import { COLOR, EFFECT_VERDICT_LABEL, FONT, GAP_REASON_LABEL, SPACE } from '../ui/tokens';
import { Badge, buttonGhostStyle, Card, Empty, ErrorBar, KV, labelStyle, Page } from '../ui/components';

export default function BandPage({
  role,
  roleLabel,
  customerId,
}: {
  role: AppRole;
  roleLabel: string;
  customerId: string;
}) {
  const [tel, setTel] = useState<BandTelemetry | null>(null);
  const [derived, setDerived] = useState<BandDerived | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  async function load() {
    setBusy(true);
    setErr(null);
    try {
      const [t, d] = await Promise.all([
        getBandTelemetry(role, customerId),
        // E4 仅 staff：本端两角色都可见 ④ 组。客户走这条会被服务端 403。
        getBandDerived(role, customerId),
      ]);
      setTel(t ?? null);
      setDerived(d ?? null);
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
    } finally {
      setBusy(false);
    }
  }

  const gap = tel?.gap_reason ? GAP_REASON_LABEL[tel.gap_reason] : undefined;
  const eff = derived?.effect_verdict ? EFFECT_VERDICT_LABEL[derived.effect_verdict] : undefined;

  return (
    <Page title="手环数据" roleLabel={roleLabel}>
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}

      <Card>
        <button style={buttonGhostStyle} disabled={busy} onClick={load}>
          {busy ? '读取中…' : '读取手环数据（E3 + E4）'}
        </button>
      </Card>

      {tel === null ? (
        <Card>
          <Empty text="尚未读取。" />
        </Card>
      ) : (
        <>
          <Card title="① 原始数据 / ② 采集状态（E3）">
            {/* data_source 未接入 ⇒ 只显示未接入，不给任何数值占位。 */}
            {tel.data_source === '未接入' ? (
              <Empty text="手环未接入：设备尚未开始回传数据，此处不显示任何数值。" />
            ) : (
              <>
                <KV k="数据来源" v={<Badge text={tel.data_source ?? '—'} tone="brand" />} />
                <KV
                  k="已采集天数"
                  v={tel.collected_days === undefined ? '—' : `${tel.collected_days} 天`}
                />
                <KV k="同步日期" v={tel.synced_date ?? '—'} />
                <KV
                  k="指标条数"
                  v={tel.metrics ? String(tel.metrics.length) : '—'}
                />
                <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
                  同步时间精确到日（契约 R4：客户可见不得到分秒）。
                </p>
              </>
            )}
          </Card>

          <Card
            title="③ 缺口原因分类（客户不可见）"
            hint="本组是缺口原因【分类】—— 是事实记录，不是评价，也不指责任何一方。"
          >
            {gap ? (
              <>
                <KV k="分类" v={<Badge text={gap.text} tone={gap.tone} />} />
                <KV k="机读键" v={tel.gap_reason ?? '—'} />
                <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
                  `not_worn` 与 `compliant_removal` 是**两件不同的事**：
                  后者是按说明书在洗浴 / 桑拿 / 游泳时主动摘除，不是缺失。
                  本组对客户恒不下发（硬约束，配置不得放开）。
                </p>
              </>
            ) : (
              <Empty text="无缺口分类（要么数据完整，要么服务端未下发该字段）。" />
            )}
          </Card>

          <Card title="④ 派生结果（客户不可见）" hint="全部由服务端计算；本端不做任何前端推算（X-1）。">
            {derived === null ? (
              <Empty text="未读取到派生结果。" />
            ) : (
              <>
                {/*
                  🛑 契约：a3_applicable 为 false 时不返回 a3_value。
                    故此处**以 a3_applicable 为准**决定是否显示 —— 不以
                     "a3_value 是否有值"为准。后者会把一次契约违反静默吞掉。
                */}
                <KV
                  k="A3 是否适用"
                  v={
                    derived.a3_applicable === undefined ? (
                      '—（未下发）'
                    ) : derived.a3_applicable ? (
                      <Badge text="适用" tone="ok" />
                    ) : (
                      <Badge text="不适用（不显示数值）" tone="info" />
                    )
                  }
                />
                <KV
                  k="A3 值"
                  v={
                    derived.a3_applicable !== true
                      ? '不显示（不适用）'
                      : derived.a3_value === undefined
                        ? '—（未下发）'
                        : String(derived.a3_value)
                  }
                />
                <KV k="AS 值" v={derived.as_value === undefined ? '—（未下发）' : String(derived.as_value)} />
                <KV
                  k="效果结论"
                  v={eff ? <Badge text={eff.text} tone={eff.tone} /> : (derived.effect_verdict ?? '—')}
                />
                <KV
                  k="R1 组资格"
                  v={
                    derived.refund_eligibility === undefined
                      ? '—（未下发）'
                      : derived.refund_eligibility
                        ? '是'
                        : '否'
                  }
                />
                <p style={{ ...labelStyle, marginTop: SPACE.sm, color: COLOR.warn }}>
                  A3 不适用时**必须不显示数值** —— 契约给的理由是避免 0 值被误读为
                  「戴了 0 天」。显示错误的数值比不显示更坏。
                </p>
              </>
            )}
          </Card>
        </>
      )}

      <Card title="契约侧提示">
        <p style={{ ...labelStyle, margin: 0, fontSize: FONT.xs }}>
          客户 ID：{customerId} · 当前角色 {roleLabel} 对 ③④ 两组均有档位
          （契约 x-visibility-matrix）。客户对这两组恒不可见。
        </p>
      </Card>
    </Page>
  );
}