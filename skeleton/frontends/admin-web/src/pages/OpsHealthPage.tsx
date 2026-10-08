/**
 * 端 A · 页面：运维自检（契约外能力面 E2）
 * ============================================================================
 *
 * 对应后端 `GET /ops/health` —— `deploy/DEPLOY.md` §6 告警手册的**机器可读入口**。
 * 一次调用聚合四项自检：数据库（连通 + 迁移登记数）· Redis（可选装配）·
 * 审计哈希链完整性 · 总体 UP / DEGRADED。
 *
 * 🛑 本页刻意【不写任何阈值】
 * ---------------------------------------------------------------------------
 * 巡检阈值（采集间隔、允许的 DEGRADED 时长、告警静默期）属于**运维决定**，
 * 已写在 `deploy/DEPLOY.md` §6。界面里再抄一份数字 = 第二份权威：
 * 手册改了界面不改，值班人会照着**过期的数字**判断"现在算不算异常"。
 * ⇒ 本页只呈现**结论与证据**（status / checked / broken_at / error），
 *    并指向上游手册；**不代替手册给数**。
 *
 * 🛑 三种状态必须分开呈现（合并会误导处置方向）
 * ---------------------------------------------------------------------------
 *   · `UP`              —— 该项正常；
 *   · `DOWN`            —— 该项坏了（连不上 / 抛异常）；
 *   · `not_configured`  —— **Redis 专有**：吊销黑名单是**可选装配**，
 *                          未配 ≠ 坏。把它渲染成红色会让值班人去修一个"本就没打算开"的组件。
 *   · `BROKEN`          —— 审计链专有：**链断**。这是合规侧最重的一级信号。
 *
 * 🛑 「健康端点请求失败」与「某项自检失败」是两件事
 * ---------------------------------------------------------------------------
 * 该端点自身**不会 500**（每个单项的异常都被捕获成该项 DOWN）。故本页出现网络层错误时，
 * 含义是"请求没到"或"网关挂了" —— 与 DB / Redis / 审计链的状态**无关**，
 * 界面上必须分开说，否则会把排查引向错误的组件。
 */

import { useState } from 'react';
import { describe } from '../services/errors';
import { getOpsHealth, type OpsHealth, type OpsItemStatus } from '../services/ops';
import {
  Badge,
  buttonGhostStyle,
  Card,
  Empty,
  ErrorBar,
  KV,
  labelStyle,
  Page,
} from '../ui/components';
import { COLOR, FONT, SPACE } from '../ui/tokens';

/** 状态 → 色槽 + 一句人话（四种状态各自的处置方向不同）。 */
const STATUS_TEXT: Readonly<Record<OpsItemStatus, { text: string; tone: keyof typeof COLOR; hint: string }>> =
  Object.freeze({
    UP: { text: '正常', tone: 'ok', hint: '该项自检通过。' },
    DOWN: { text: '异常', tone: 'danger', hint: '该项坏了 —— 处置方向见 deploy/DEPLOY.md §6。' },
    not_configured: {
      text: '未装配（可选项）',
      tone: 'info',
      hint: '⚠️ 这不是故障：吊销黑名单是**可选件**，未配置时如实上报。'
        + '把这里当红色去修，会在一个本就没打算开的组件上花时间。',
    },
    BROKEN: {
      text: '链断',
      tone: 'danger',
      hint: '🛑 审计哈希链不连续 —— 合规侧最重的一级信号。'
        + '**先取证（保留 broken_at 与 reason）后恢复**，不得先删日志。',
    },
  });

function StatusBadge({ status }: { status: OpsItemStatus }) {
  const s = STATUS_TEXT[status] ?? { text: String(status), tone: 'warn' as const, hint: '' };
  return <Badge text={s.text} tone={s.tone} />;
}

export default function OpsHealthPage({ roleLabel }: { roleLabel: string }) {
  const [report, setReport] = useState<OpsHealth | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  async function load() {
    setBusy(true);
    setErr(null);
    try {
      setReport((await getOpsHealth()) ?? null);
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="运维自检" roleLabel={roleLabel}>
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}

      <Card
        title="这一次请求失败 ≠ 某一项自检失败"
        hint="该端点自身不会 500（每个单项的异常都被捕获成该项 DOWN）。故上面的错误只代表「请求没到」或「网关挂了」。"
      >
        <button style={buttonGhostStyle} disabled={busy} onClick={load}>
          {report ? '重新自检' : '执行自检'}
        </button>
        <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
          🛑 健康端点**刻意不缓存**：缓存会让"刚断的链"延迟暴露。
          巡检间隔与告警阈值见 <code>deploy/DEPLOY.md</code> §6（本页**不抄数字**）。
        </p>
      </Card>

      {report ? (
        <>
          <Card
            title="总体结论"
            hint="DB 或审计链异常 ⇒ DEGRADED。**Redis 未配置不算降级**（它是可选件）。"
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: SPACE.md }}>
              <Badge text={report.overall} tone={report.overall === 'UP' ? 'ok' : 'warn'} />
              <span style={{ fontSize: FONT.md, color: COLOR.text }}>
                {report.overall === 'UP'
                  ? '各项自检通过。'
                  : '存在降级项 —— 请按下表逐项定位（不要先猜组件）。'}
              </span>
            </div>
          </Card>

          <Card title="① 数据库（连通 + 迁移登记数）" hint="迁移登记数不足 = 「部署半途」最典型的信号：库里有表，但迁移没跑全。">
            <div style={{ marginBottom: SPACE.sm }}><StatusBadge status={report.db.status} /></div>
            {report.db.status === 'UP' ? (
              <KV k="已登记迁移数" v={String(report.db.registered_migrations ?? '—')} />
            ) : (
              <KV k="错误" v={report.db.error ?? '（服务端未给出原因）'} />
            )}
          </Card>

          <Card title="② Redis（吊销黑名单，可选装配）" hint="未装配时如实报 not_configured —— 这不是故障，不要按红色处置。">
            <div style={{ marginBottom: SPACE.sm }}><StatusBadge status={report.redis.status} /></div>
            {report.redis.status === 'not_configured' ? (
              <KV k="说明" v={report.redis.hint ?? '（未配置为 redis 后端）'} />
            ) : report.redis.status === 'UP' ? (
              <KV k="说明" v="PING 正常。吊销黑名单在实时生效。" />
            ) : (
              <KV k="错误" v={report.redis.error ?? '（服务端未给出原因）'} />
            )}
            <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
              取舍已写透：吊销黑名单是**加速器不是唯一防线** —— Redis 挂掉时出站侧
              fail-open（登出延迟生效），而不是全站 500。故此项异常**不等同**于安全问题。
            </p>
          </Card>

          <Card title="③ 审计哈希链完整性" hint="链断 = 证据链失效，是合规侧最重的一级信号。">
            <div style={{ marginBottom: SPACE.sm }}><StatusBadge status={report.audit_chain.status} /></div>
            <KV k="已校验行数" v={String(report.audit_chain.checked ?? '—')} />
            {report.audit_chain.status === 'BROKEN' ? (
              <>
                <KV k="断链位置" v={String(report.audit_chain.broken_at ?? '—')} />
                <KV k="原因" v={report.audit_chain.reason ?? '（服务端未给出原因）'} />
                <p style={{ ...labelStyle, marginTop: SPACE.sm, color: COLOR.danger }}>
                  🛑 处置顺序是**先取证后恢复**：保留 broken_at / reason 与涉及区段的原始日志，
                  再走恢复流程。**不得先删日志** —— 那会把「不可悄然篡改」的证据一起删掉。
                  （本系统只能说「不可**悄然**篡改」，不得说「不可篡改」。）
                </p>
              </>
            ) : report.audit_chain.status === 'DOWN' ? (
              <KV k="错误" v={report.audit_chain.error ?? '（服务端未给出原因）'} />
            ) : null}
          </Card>
        </>
      ) : (
        <Card title="尚未自检">
          <Empty text="点击上方「执行自检」以拉取四项结论（DB / Redis / 审计链 / 总体）。" />
        </Card>
      )}
    </Page>
  );
}
