/**
 * 端 A · 页面：退款工单（G1~G5）
 * ============================================================================
 *
 * 🛑 本页是端 A **唯一带契约未完结声明**的业务域（G4 `x-ruling-pending`）
 * ---------------------------------------------------------------------------
 * G4 `approveRefund` 契约逐字 `x-ruling-pending`（**取值系推断、待裁定**）。
 * 含义不是"功能没写"，而是"**功能可能已生效，但审批白名单的依据是推断**，
 * 裁定后取值可能变"。故本页：
 *   · 必须渲染 `UnsettledBar`（`a-check.mjs` 的 `unsettled-surfaced` 判据守着）；
 *   · **不硬编码审批人白名单** —— 硬编码 = 把一份推断变成代码事实；
 *   · 文案上不说"功能未上线"（那是错的，会让人以为不能用）。
 *
 * 🛑 G3 / G5 的请求体字段名**不在契约里**
 * ---------------------------------------------------------------------------
 * 契约对 G3 `createRetention` / G5 `createRefundReceipt` **未声明 requestBody**。
 * 真相源是后端控制器：
 *   · `CreateRetentionRequest` = `{attempts, script_version, result, analysis, communication}`；
 *   · `CreateReceiptRequest`  = `{subscription_quota, template_id, push_succeeded, failure_reason}`。
 * 端 B 的 D5-c 曾因"自创字段名"造成**静默失效**（提交成功但状态没变）。
 * 故本页表单字段名**逐条对齐控制器**，并在界面写明"字段名来自控制器"。
 *
 * 🛑 G5 的顺序不得搞反
 * ---------------------------------------------------------------------------
 * `subscription_quota` 是**推送之前**判定的额度（≤0 ⇒「未授权（转线下）」）。
 * 顺序搞反会让"未授权"这一态在库里**永不出现**。故本页把它放在"推送结果"**之前**。
 */

import { useState } from 'react';
import { newIdempotencyKey } from '../api/client';
import { describe } from '../services/errors';
import {
  contractNoteOf,
  createRefund,
  createRefundReceipt,
  createRetention,
  getRefund,
  approveRefund,
  type Refund,
  type RefundReceipt,
} from '../services/domain';
import {
  Badge,
  buttonGhostStyle,
  Card,
  Empty,
  ErrorBar,
  inputStyle,
  KV,
  labelStyle,
  Page,
  selectStyle,
  textareaStyle,
  UnsettledBar,
} from '../ui/components';
import {
  COLOR,
  FONT,
  RECEIPT_STATE_LABEL,
  REFUND_ENTRY_LABEL,
  REFUND_OUTCOME_LABEL,
  REFUND_REASON_LABEL,
  SPACE,
} from '../ui/tokens';

const REASONS = Object.keys(REFUND_REASON_LABEL);
const ENTRIES = Object.keys(REFUND_ENTRY_LABEL);

export default function RefundWorkbenchPage({ roleLabel }: { roleLabel: string }) {
  const [refundId, setRefundId] = useState('');
  const [refund, setRefund] = useState<Refund | null>(null);
  const [receipt, setReceipt] = useState<RefundReceipt | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  // G1
  const [customerId, setCustomerId] = useState('');
  const [entry, setEntry] = useState(ENTRIES[0]);
  const [route, setRoute] = useState<'履约类' | '效果类'>('履约类');
  const [reason, setReason] = useState(REASONS[0]);
  const [requestedAt, setRequestedAt] = useState('');
  const [statement, setStatement] = useState('');
  // G4
  const [approvalBody, setApprovalBody] = useState('{}');
  // G3
  const [retentionAttempts, setRetentionAttempts] = useState('1');
  const [retentionScript, setRetentionScript] = useState('');
  const [retentionResult, setRetentionResult] = useState('');
  const [retentionAnalysis, setRetentionAnalysis] = useState('{}');
  // G5
  const [quota, setQuota] = useState('1');
  const [templateId, setTemplateId] = useState('');
  const [pushOk, setPushOk] = useState<'true' | 'false'>('true');
  const [failureReason, setFailureReason] = useState('');

  const g4Note = contractNoteOf('approveRefund');
  const g3Note = contractNoteOf('createRetention');
  const g5Note = contractNoteOf('createRefundReceipt');

  async function run(label: string, fn: () => Promise<void>) {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      await fn();
      setMsg(`${label} 完成`);
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="退款工单" roleLabel={roleLabel}>
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
      {msg ? <div style={{ ...labelStyle, marginBottom: SPACE.md, color: COLOR.ok }}>✓ {msg}</div> : null}

      <Card title="G1 代录客户退款诉求" hint="reason_code 契约逐字：「必填；未记录原因不可结案」—— 本页不给空选项。">
        <div style={labelStyle}>customer_id</div>
        <input style={inputStyle} value={customerId} onChange={(e) => setCustomerId(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>入口</div>
        <select style={selectStyle} value={entry} onChange={(e) => setEntry(e.target.value)}>
          {ENTRIES.map((k) => <option key={k} value={k}>{REFUND_ENTRY_LABEL[k]}</option>)}
        </select>
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>路径</div>
        <select style={selectStyle} value={route} onChange={(e) => setRoute(e.target.value as '履约类' | '效果类')}>
          <option value="履约类">履约类</option>
          <option value="效果类">效果类</option>
        </select>
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>原因（必填，6 值）</div>
        <select style={selectStyle} value={reason} onChange={(e) => setReason(e.target.value)}>
          {REASONS.map((k) => <option key={k} value={k}>{REFUND_REASON_LABEL[k]}</option>)}
        </select>
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>
          requested_at（契约逐字：「最早且可核实」= 计时基准）
        </div>
        <input
          style={inputStyle}
          value={requestedAt}
          onChange={(e) => setRequestedAt(e.target.value)}
          placeholder="如 2026-09-30T10:00:00+08:00"
        />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>客户原话（append-only，可空）</div>
        <textarea style={textareaStyle} value={statement} onChange={(e) => setStatement(e.target.value)} />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('G1 创建工单', async () => {
            if (!requestedAt.trim()) throw new Error('G1：requested_at 是计时基准，必填。');
            const r = await createRefund({
              customer_id: customerId.trim(),
              entry: entry as 'A门店代录' | 'B首周期',
              refund_route: route,
              reason_code: reason as Parameters<typeof createRefund>[0]['reason_code'],
              requested_at: requestedAt.trim(),
              customer_statement: statement.trim() || undefined,
            }, newIdempotencyKey());
            setRefund(r ?? null);
            if (r?.refund_id) setRefundId(r.refund_id);
          })}
        >
          G1 创建
        </button>
        {refund ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="refund_id" v={refund.refund_id ?? '—'} />
            <KV k="责任门店" v={refund.liable_store_id ?? '—'} />
            <KV k="SLA 到期" v={refund.sla_due_at ?? '—'} />
            <KV k="录入延迟（小时）" v={refund.recording_delay_h === undefined ? '—' : String(refund.recording_delay_h)} />
            <KV
              k="结果"
              v={refund.outcome
                ? <Badge text={REFUND_OUTCOME_LABEL[refund.outcome]?.text ?? refund.outcome}
                    tone={REFUND_OUTCOME_LABEL[refund.outcome]?.tone ?? 'brand'} />
                : '—'}
            />
          </div>
        ) : null}
      </Card>

      <Card title="G2 工单详情">
        <div style={labelStyle}>refund_id</div>
        <input style={inputStyle} value={refundId} onChange={(e) => setRefundId(e.target.value)} />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('G2 读取工单', async () => {
            setRefund((await getRefund(refundId.trim())) ?? null);
          })}
        >
          G2 读取
        </button>
        {refund ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="refund_id" v={refund.refund_id ?? '—'} />
            <KV k="责任门店" v={refund.liable_store_id ?? '—'} />
            <KV k="outcome" v={refund.outcome ?? '—'} />
          </div>
        ) : null}
      </Card>

      <Card title="G3 挽留记录" hint="契约逐字：「挽留记录必填；入口 B 不经挽留」。本页不允许提交空记录。">
        {g3Note ? <UnsettledBar items={g3Note.unsettled} /> : null}
        <p style={{ ...labelStyle, marginBottom: SPACE.sm }}>
          🛑 字段名来自控制器 `RefundWorkOrderController.CreateRetentionRequest`
          = `{'{'}attempts, script_version, result, analysis, communication{'}'}` —— 契约未声明 requestBody。
        </p>
        <div style={labelStyle}>attempts（挽留尝试次数）</div>
        <input style={inputStyle} value={retentionAttempts} onChange={(e) => setRetentionAttempts(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>script_version（话术版本）</div>
        <input style={inputStyle} value={retentionScript} onChange={(e) => setRetentionScript(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>result（挽留结论）</div>
        <input style={inputStyle} value={retentionResult} onChange={(e) => setRetentionResult(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>analysis（五维原因分析，JSONB ⇒ 传对象）</div>
        <textarea style={textareaStyle} value={retentionAnalysis} onChange={(e) => setRetentionAnalysis(e.target.value)} />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('G3 挽留记录', async () => {
            await createRetention(refundId.trim(), {
              attempts: Number(retentionAttempts) || undefined,
              script_version: retentionScript.trim() || undefined,
              result: retentionResult.trim() || undefined,
              analysis: JSON.parse(retentionAnalysis) as Record<string, unknown>,
            }, newIdempotencyKey());
          })}
        >
          G3 提交挽留
        </button>
      </Card>

      <Card title="G4 退款审批（契约：取值系推断、待裁定）">
        {g4Note ? <UnsettledBar items={g4Note.unsettled} /> : null}
        <p style={{ ...labelStyle, marginBottom: SPACE.sm }}>
          🛑 本端**不硬编码审批人白名单** —— 契约标 `x-ruling-pending`，
          硬编码会把一份**推断**变成代码事实。裁定后取值可能变，故此处不下判断。
        </p>
        <div style={labelStyle}>审批请求体（JSON —— 契约未声明 requestBody）</div>
        <textarea style={textareaStyle} value={approvalBody} onChange={(e) => setApprovalBody(e.target.value)} />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('G4 提交审批', async () => {
            await approveRefund(refundId.trim(), JSON.parse(approvalBody) as Record<string, unknown>, newIdempotencyKey());
          })}
        >
          G4 提交审批
        </button>
      </Card>

      <Card title="G5 回执（三态留痕）">
        {g5Note ? <UnsettledBar items={g5Note.unsettled} /> : null}
        <p style={{ ...labelStyle, marginBottom: SPACE.sm }}>
          🛑 字段名来自控制器 `CreateReceiptRequest` = `{'{'}subscription_quota, template_id,
          push_succeeded, failure_reason{'}'}`。
          **`subscription_quota` 是推送之前判定的额度**（≤0 ⇒「未授权（转线下）」）——
          故本页把它放在"推送结果"之前，顺序搞反会让"未授权"这一态在库里永不出现。
        </p>
        <div style={labelStyle}>subscription_quota（推送**之前**判定）</div>
        <input style={inputStyle} value={quota} onChange={(e) => setQuota(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>template_id（订阅消息模板 ID）</div>
        <input style={inputStyle} value={templateId} onChange={(e) => setTemplateId(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>push_succeeded（推送结果 —— 在额度判定**之后**）</div>
        <select style={selectStyle} value={pushOk} onChange={(e) => setPushOk(e.target.value as 'true' | 'false')}>
          <option value="true">成功</option>
          <option value="false">失败</option>
        </select>
        {pushOk === 'false' ? (
          <>
            <div style={{ ...labelStyle, marginTop: SPACE.sm }}>failure_reason（失败时必填）</div>
            <input style={inputStyle} value={failureReason} onChange={(e) => setFailureReason(e.target.value)} />
          </>
        ) : null}
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('G5 回执', async () => {
            if (pushOk === 'false' && !failureReason.trim()) {
              throw new Error('G5：push_succeeded=false 时 failure_reason 必填。');
            }
            const r = await createRefundReceipt(refundId.trim(), {
              subscription_quota: Number(quota),
              template_id: templateId.trim() || undefined,
              push_succeeded: pushOk === 'true',
              failure_reason: failureReason.trim() || undefined,
            }, newIdempotencyKey());
            setReceipt(r ?? null);
          })}
        >
          G5 提交回执
        </button>
        {receipt ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV
              k="回执态"
              v={receipt.receipt_state
                ? <Badge text={RECEIPT_STATE_LABEL[receipt.receipt_state]?.text ?? receipt.receipt_state}
                    tone={RECEIPT_STATE_LABEL[receipt.receipt_state]?.tone ?? 'brand'} />
                : '—'}
            />
            {receipt.receipt_state === '未授权（转线下）' ? (
              <p style={{ ...labelStyle, marginTop: SPACE.sm, color: COLOR.warn }}>
                「未授权（转线下）」= `subscription_quota ≤ 0`（推送之前判定），**不是推送失败**。
              </p>
            ) : null}
          </div>
        ) : (
          <div style={{ marginTop: SPACE.sm }}><Empty text="尚未提交回执。" /></div>
        )}
      </Card>

      <Card title="契约侧提示">
        <p style={{ ...labelStyle, margin: 0, fontSize: FONT.xs }}>
          当前角色 {roleLabel} · 退款域 5 个端点全部只授予 admin ·
          G4 带 `x-ruling-pending`（待裁定）。
          退款措辞在本端为**合法内部业务词汇**（词表 SCOPE 只覆盖客户端包）。
        </p>
      </Card>
    </Page>
  );
}