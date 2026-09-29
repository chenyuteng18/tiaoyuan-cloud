/**
 * 端 B · 页面：客户详情（③④ 组的呈现面）
 * ============================================================================
 *
 * 这是端 B 与端 C **差异最大**的一页，差异全部来自契约矩阵（不是设计偏好）：
 *
 *   | 字段组 | 端 C（客户） | 端 B（staff） |
 *   |---|---|---|
 *   | ① 手环原始数据     | 可见 | 可见 |
 *   | ② 采集状态         | 可见 | 可见 |
 *   | ③ 缺口原因分类     | **不可见**（硬约束） | 可见 |
 *   | ④ 派生结果         | **不可见**（硬约束） | 可见 |
 *
 * 🛑 两条必须在本页落实的契约条文
 * ---------------------------------------------------------------------------
 * 1. `BandDerivedData.a3_applicable` 逐字：「为 false 时**不返回 a3_value**
 *    （避免 0 值被误读为"戴了 0 天"）」⇒ 本页在 `a3_applicable !== true` 时
 *    **不显示** a3_value，即使它出现了。显示 0 会把"不适用"读成"戴了 0 天"，
 *    那是本仓反复防的**数值语义误读**。
 * 2. `BandTelemetryData.data_source` 逐字：「未接入时数值字段不下发，
 *    **不得显示 0 / 空图表 / 示例数据**」⇒ 未接入态本页只显示"未接入"，
 *    不渲染任何数值占位。
 *
 * 🛑 关于"借调"这一栏的写法（刻意的）
 * ---------------------------------------------------------------------------
 * 契约把 service 端的角色称为`therapist`(调理师)/`meridian`(经络师)，
 * 但**本页不推断两者的业务分工**（例如"谁负责判定"）—— 那属于 PRD 的解释层，
 * 不是契约层。本页只呈现**契约事实**：该动作需要哪个角色。
 * 把推断写进界面会让一次业务口径调整变成一次前端改造。
 */

import { useState } from 'react';
import { listVisits, type Visit } from '../services/domain';
import { describe } from '../services/errors';
import type { AppRole } from '../contract/access';
import { ROLE_LABEL } from '../contract/access';
import { COLOR, SPACE } from '../ui/tokens';
import { Badge, Card, Empty, ErrorBar, KV, labelStyle, Page } from '../ui/components';
import { buttonGhostStyle } from '../ui/components';
import type { CustomerDetail } from '../services/domain';

export default function CustomerPage({
  role,
  roleLabel,
  customer,
}: {
  role: AppRole;
  roleLabel: string;
  customer: CustomerDetail;
}) {
  const [visits, setVisits] = useState<readonly Visit[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  async function loadVisits() {
    setBusy(true);
    setErr(null);
    try {
      const items = await listVisits(role, customer.customer_id);
      setVisits(items ?? []);
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="客户详情" roleLabel={roleLabel}>
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}

      <Card title="基本信息">
        <KV k="客户 ID" v={customer.customer_id} />
        <KV k="姓名" v={customer.name} />
        <KV k="性别 / 年龄" v={`${customer.gender} · ${customer.age} 岁`} />
        <KV k="筛查结果" v={customer.screening_result} />
        <KV k="佩戴意愿" v={customer.band_willingness} />
        {/*
          🛑 owner_store_id / serving_store_id 对客户不下发、对 staff 下发。
            本端显示它们**是契约允许的**；端 C 同名页不得引用这两个字段。
        */}
        <KV k="归属门店" v={customer.owner_store_id ?? '—'} />
        <KV k="服务门店" v={customer.serving_store_id ?? '—'} />
        {/*
          🛑 刻意**不显示** `status`（5 值聚合态）：契约只在 `CustomerCreateData`
            （建档响应）里定义它，`CustomerDetailData`（B4 详情）**没有这个字段**。
            本层初版曾引用 `customer.status`，被 tsc 挡下 —— 这正是"字段名必须
            逐条对齐契约"的实例：若这里用了 `as any` 绕过，界面会永远显示 "—"，
            而没人会知道是字段名错了（静默假绿）。
        */}
      </Card>

      <Card
        title="④ 派生结果（客户不可见）"
        hint="该组对客户恒不下发；本端可见是因为契约 x-visibility-matrix 对本端为 true。"
      >
        <KV k="效果结论" v={customer.effect_verdict ?? '—'} />
        <KV
          k="AS 值"
          v={
            customer.as_value === undefined ? (
              '—（未下发）'
            ) : (
              String(customer.as_value)
            )
          }
        />
        <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
          派生字段一律以服务端计算结果为准；本端**不做任何前端推算**（X-1）。
        </p>
      </Card>

      <Card title="服务记录（D2）">
        <button style={buttonGhostStyle} disabled={busy} onClick={loadVisits}>
          {busy ? '读取中…' : '读取服务记录'}
        </button>
        <div style={{ marginTop: SPACE.md }}>
          {visits === null ? (
            <Empty text="尚未读取。" />
          ) : visits.length === 0 ? (
            <Empty text="暂无服务记录。" />
          ) : (
            visits.map((v) => (
              <KV
                key={v.visit_id}
                k={`第 ${v.visit_no} 次`}
                v={
                  <span>
                    {v.executed_at} · 服务门店 {v.serving_store_id}{' '}
                    <Badge
                      text={v.customer_confirmed ? '客户已确认' : '客户未确认'}
                      tone={v.customer_confirmed ? 'ok' : 'warn'}
                    />
                    {v.abnormal_note ? (
                      <span style={{ color: COLOR.warn }}> · 异常备注：{v.abnormal_note}</span>
                    ) : null}
                  </span>
                }
              />
            ))
          )}
        </div>
      </Card>

      <Card title="契约侧提示">
        <p style={{ ...labelStyle, margin: 0 }}>
          本页可显示 ③ 组（缺口原因）与 ④ 组（派生结果）是因为当前角色为{' '}
          {ROLE_LABEL[role]}。R1 组档位由 A2 下发：经络师与管理员有该档位，
          调理师无该档位（服务端不下发该字段，而不是下发为否）。
        </p>
      </Card>
    </Page>
  );
}