/**
 * 端 B · 页面：专属动作（**本端最窄的一档**）
 * ============================================================================
 *
 * 本页是 X-3「角色级装载」在界面层**最尖锐的验收面**：
 * 它只渲染**恰好只授予单一角色**的端点（本端目前 = 7 个，全部仅经络师）。
 *
 * 🛑 为什么单开一页，而不是并进工作台的"我可用能力"清单
 * ---------------------------------------------------------------------------
 * 工作台那页列的是"我这个角色能用什么"（22 vs 29 两个数量级混在一起）。
 * 但真正会出事的是**最窄的那一档**：它是"漏了一个未授权入口"的最可能落点
 * —— 调理师视图一旦渲染了这 7 个里的任何一个，点下去必然 403，
 * 而报出来的现象与"权限没配好"完全一样，排查方向会整个跑偏。
 * 故本页把它单拎出来：**调理师登录时这一页必须一个入口都不渲染**，
 * 而这个事实**肉眼可见**（不是靠读代码判断）。
 *
 * 🛑 本页的入口清单**不是手写的**，也不是"写死的 7 项"
 * ---------------------------------------------------------------------------
 * 由 `solelyGrantedEndpoints()`（access.ts）从生成物的 `grantedRoles` **现算**。
 * 若本页自己写 `['D5-c','F1',...]`，就造出了第二份清单：契约一改就漂移，
 * 而且**漂移不会让任何门禁变红**（本仓第 46 条同型）。
 * 同理，本页右侧显示"共 N 项"的那个 N 也是现算的，不写死 7。
 *
 * 🛑 本页对【不归我】的项不隐藏，而是显式标注
 * ---------------------------------------------------------------------------
 * 调理师视图下，这 7 项会**以"仅经络师"的标记出现但不给按钮**。
 * 理由与工作台一致：一线最困惑的是"为什么别人能做我不能"，
 * 把边界写出来并写明**该找谁**，是唯一能终止这类往返的做法。
 * （注意：这是**呈现**选择，不是准入判定 —— 判定只在 access.ts。）
 */

import { useMemo, useState, type ReactNode } from 'react';
import {
  ROLE_LABEL,
  canCall,
  solelyGrantedEndpoints,
  type AppRole,
} from '../contract/access';
import { newIdempotencyKey } from '../api/client';
import {
  createRefundAsMeridian,
  createRefundReceiptAsMeridian,
  createRetentionAsMeridian,
  createVerdictAsMeridian,
  getRefundAsMeridian,
  listVerdictsAsMeridian,
  reviewPlanAsMeridian,
  type RefundCreateRequest,
  type VerdictRequest,
} from '../services/domain';
import { describe } from '../services/errors';
import { COLOR, FONT, SPACE } from '../ui/tokens';
import { Badge, buttonGhostStyle, Card, ErrorBar, inputStyle, KV, labelStyle, Page } from '../ui/components';

/** 结果条：成功 / 幂等重放 / 失败三态分开（4002 不是失败）。 */
type Outcome =
  | { kind: 'ok'; label: string; json: string; traceId: string }
  | { kind: 'replay'; label: string; json: string; traceId: string }
  | { kind: 'err'; text: string; traceId: string };

export default function MeridianActionsPage({
  role,
  roleLabel,
  onNeedRelogin,
}: {
  role: AppRole;
  roleLabel: string;
  onNeedRelogin: () => void;
}) {
  const sole = useMemo(() => solelyGrantedEndpoints(), []);
  const mine = useMemo(() => sole.filter((s) => canCall(s.endpoint.id, role)), [sole, role]);
  const notMine = useMemo(() => sole.filter((s) => !canCall(s.endpoint.id, role)), [sole, role]);

  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [busy, setBusy] = useState(false);

  // 表单（每一项的输入都刻意分开，不做"通用 JSON 输入框"——
  // 通用输入框会让一线**不知道字段名**，从而把一次合法的提交写成字段名错误）。
  const [planId, setPlanId] = useState('');
  const [reviewResult, setReviewResult] = useState('');
  const [reviewReason, setReviewReason] = useState('');
  const [reviewSecond, setReviewSecond] = useState(true);
  const [cycleId, setCycleId] = useState('');
  const [verdictCustomerId, setVerdictCustomerId] = useState('');
  const [verdictSeq, setVerdictSeq] = useState('1');
  const [baseTotal, setBaseTotal] = useState('');
  const [currentTotal, setCurrentTotal] = useState('');
  const [sameItemGroup, setSameItemGroup] = useState(true);
  const [rangeMatches, setRangeMatches] = useState(true);
  const [sameMeasurer, setSameMeasurer] = useState(true);
  const [riskFlag, setRiskFlag] = useState('');
  const [coreImproved, setCoreImproved] = useState('');
  const [verdictJson, setVerdictJson] = useState(
    '{"confidence":{},"module_scores":{},"adherence":{"dimensions":{}}}'
  );
  const [listVerdictCustomerId, setListVerdictCustomerId] = useState('');
  const [refundCustomerId, setRefundCustomerId] = useState('');
  const [refundEntry, setRefundEntry] = useState<RefundCreateRequest['entry']>('A门店代录');
  const [refundRoute, setRefundRoute] = useState<RefundCreateRequest['refund_route']>('履约类');
  const [refundReason, setRefundReason] = useState('');
  const [refundRequestedAt, setRefundRequestedAt] = useState('');
  const [refundStatement, setRefundStatement] = useState('');
  const [refundId, setRefundId] = useState('');
  const [retentionAttempts, setRetentionAttempts] = useState('1');
  const [retentionResult, setRetentionResult] = useState('');
  const [receiptTemplateId, setReceiptTemplateId] = useState('');
  const [receiptQuota, setReceiptQuota] = useState('');
  const [receiptPushOk, setReceiptPushOk] = useState(true);
  const [receiptFailReason, setReceiptFailReason] = useState('');

  async function run(label: string, fn: () => Promise<unknown>) {
    setBusy(true);
    setOutcome(null);
    try {
      const data = await fn();
      if (data === undefined) {
        setOutcome({
          kind: 'ok',
          label,
          json: '（服务端未返回 data —— 契约 envelope-rule：code != 0 时 data 为空；此处 code = 0）',
          traceId: '',
        });
      } else {
        setOutcome({ kind: 'ok', label, json: JSON.stringify(data, null, 2), traceId: '' });
      }
    } catch (e) {
      const d = describe(e);
      if (d.kind === 'replay') {
        // 🛑 4002 不是失败：契约语义 = 此前已成功处理。把它渲染成成功态，
        //    否则一线会重复点第二次、第三次，制造更多重放。
        setOutcome({ kind: 'replay', label, json: '该请求此前已成功处理（幂等命中，返回首次结果）。', traceId: d.traceId });
      } else {
        setOutcome({ kind: 'err', text: d.text, traceId: d.traceId });
        if (d.kind === 'auth') onNeedRelogin();
      }
    } finally {
      setBusy(false);
    }
  }

  // 调理师视图：本页**不给任何操作按钮**（结构性地不给，不靠"藏起来"）。
  if (mine.length === 0) {
    return (
      <Page title="专属动作" roleLabel={roleLabel}>
        <Card
          title="本角色在本端没有专属动作"
          hint="这不是报错，而是契约事实：本端目前没有任何「仅调理师」端点。"
        >
          <p style={{ ...labelStyle, margin: 0 }}>
            本端「只授予单一角色」的端点共 {sole.length} 项，
            {ROLE_LABEL[role]}可用的为 0 项。下表只作<strong>边界说明</strong>，不提供操作入口 ——
            需要执行请找对应角色，不要借用账号（借用账号会让审计留痕记到错误的人身上）。
          </p>
        </Card>
        <Card title={`存在、但不归 ${ROLE_LABEL[role]}（${notMine.length} 项）`}>
          {notMine.map((s) => (
            <KV
              key={s.endpoint.id}
              k={`${s.endpoint.row} ${s.endpoint.id}`}
              v={
                <span>
                  {s.endpoint.method} {s.endpoint.path}{' '}
                  <span style={{ fontSize: FONT.xs, color: COLOR.textMuted }}>
                    （仅 {ROLE_LABEL[s.role as AppRole] ?? s.role}）
                  </span>
                </span>
              }
            />
          ))}
        </Card>
      </Page>
    );
  }

  const isMeridian = canCall('getRefund', role);

  return (
    <Page title="专属动作" roleLabel={roleLabel}>
      {outcome ? (
        outcome.kind === 'ok' ? (
          <div
            style={{
              border: `1px solid ${COLOR.ok}`,
              background: COLOR.surfaceAlt,
              borderRadius: 4,
              padding: `${SPACE.sm}px ${SPACE.md}px`,
              marginBottom: SPACE.md,
              fontSize: FONT.sm,
              color: COLOR.text,
            }}
          >
            <div style={{ color: COLOR.ok }}>✓ {outcome.label} 已提交</div>
            <pre style={{ margin: `${SPACE.xs}px 0 0`, fontSize: FONT.xs, whiteSpace: 'pre-wrap' }}>
              {outcome.json}
            </pre>
          </div>
        ) : outcome.kind === 'replay' ? (
          <div
            style={{
              border: `1px solid ${COLOR.info}`,
              background: COLOR.surfaceAlt,
              borderRadius: 4,
              padding: `${SPACE.sm}px ${SPACE.md}px`,
              marginBottom: SPACE.md,
              fontSize: FONT.sm,
              color: COLOR.text,
            }}
          >
            <div style={{ color: COLOR.info }}>↻ {outcome.label} 幂等命中（不是失败）</div>
            <div style={{ fontSize: FONT.xs, marginTop: SPACE.xs }}>{outcome.json}</div>
            {outcome.traceId ? <div style={{ ...labelStyle, marginTop: SPACE.xs }}>trace_id: {outcome.traceId}</div> : null}
          </div>
        ) : (
          <ErrorBar text={outcome.text} traceId={outcome.traceId} />
        )
      ) : null}

      <Card
        title={`${ROLE_LABEL[role]}专属动作（${mine.length} 项）`}
        hint="清单由生成物的 grantedRoles 现算（只保留「恰好只授予一个角色」的端点），本页不含手写端点清单。"
      >
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: SPACE.xs }}>
          {mine.map((s) => (
            <Badge key={s.endpoint.id} text={`${s.endpoint.row} ${s.endpoint.id}`} />
          ))}
        </div>
        {notMine.length > 0 ? (
          <p style={{ ...labelStyle, marginTop: SPACE.md, marginBottom: 0 }}>
            另有 {notMine.length} 项为其他角色的专属动作（本页不渲染其入口）。
          </p>
        ) : null}
      </Card>

      {/* ---------------- D5-c 方案审核 ---------------- */}
      {canCall('reviewPlan', role) ? (
        <Card
          title="D5-c 方案审核"
          hint="退回必填 reason。请求体字段逐字对齐后端控制器（contract 未声明 requestBody）：result / reason / second_confirm。"
        >
          <Field label="方案 ID（{id}，来自 D5-a 出具方案）">
            <input style={inputStyle} value={planId} onChange={(e) => setPlanId(e.target.value)} placeholder="plan uuid" />
          </Field>
          <Field label="审核结论（result）">
            <input
              style={inputStyle}
              value={reviewResult}
              onChange={(e) => setReviewResult(e.target.value)}
              placeholder="例如 通过 / 退回"
            />
          </Field>
          <Field label="退回原因（reason，退回时必填）">
            <input style={inputStyle} value={reviewReason} onChange={(e) => setReviewReason(e.target.value)} />
          </Field>
          <Field label="二次确认（second_confirm）">
            <label style={{ fontSize: FONT.md, color: COLOR.text }}>
              <input type="checkbox" checked={reviewSecond} onChange={(e) => setReviewSecond(e.target.checked)} /> 已二次确认
            </label>
          </Field>
          <button
            style={buttonGhostStyle}
            disabled={busy || !planId.trim()}
            onClick={() =>
              run('方案审核', () =>
                reviewPlanAsMeridian(
                  role,
                  planId.trim(),
                  {
                    result: reviewResult.trim() || '通过',
                    ...(reviewReason.trim() ? { reason: reviewReason.trim() } : {}),
                    second_confirm: reviewSecond,
                  },
                  newIdempotencyKey()
                )
              )
            }
          >
            提交审核
          </button>
        </Card>
      ) : null}

      {/* ---------------- F1 判定结论落库 ---------------- */}
      {canCall('createVerdict', role) ? (
        <Card
          title="F1 判定结论落库"
          hint="同源断言三项任一项缺省 = 不可比 ⇒ 挂起；risk_flag 缺省 ⇒ 未定（不得默认「无」）—— 本页不代填。"
        >
          <Field label="周期评估 ID（{id}，来自 C4）">
            <input style={inputStyle} value={cycleId} onChange={(e) => setCycleId(e.target.value)} placeholder="cycle assessment uuid" />
          </Field>
          <Field label="客户 ID（customer_id）">
            <input style={inputStyle} value={verdictCustomerId} onChange={(e) => setVerdictCustomerId(e.target.value)} />
          </Field>
          <Field label="序号（sequence_no，每 7 次触发）">
            <input style={inputStyle} value={verdictSeq} onChange={(e) => setVerdictSeq(e.target.value)} />
          </Field>
          <Field label="基线总分（base_total，缺失 ⇒ 挂起）">
            <input style={inputStyle} value={baseTotal} onChange={(e) => setBaseTotal(e.target.value)} />
          </Field>
          <Field label="复评总分（current_total，缺失 ⇒ 挂起）">
            <input style={inputStyle} value={currentTotal} onChange={(e) => setCurrentTotal(e.target.value)} />
          </Field>
          <Field label="同源断言三项（任一项缺省 = 不成立 = 不可比）">
            <div style={{ display: 'flex', gap: SPACE.md, flexWrap: 'wrap' }}>
              <label style={{ fontSize: FONT.md, color: COLOR.text }}>
                <input type="checkbox" checked={sameItemGroup} onChange={(e) => setSameItemGroup(e.target.checked)} /> 同题组
              </label>
              <label style={{ fontSize: FONT.md, color: COLOR.text }}>
                <input type="checkbox" checked={rangeMatches} onChange={(e) => setRangeMatches(e.target.checked)} /> 量程相符
              </label>
              <label style={{ fontSize: FONT.md, color: COLOR.text }}>
                <input type="checkbox" checked={sameMeasurer} onChange={(e) => setSameMeasurer(e.target.checked)} /> 同一测量人
              </label>
            </div>
          </Field>
          <Field label="风险标记（risk_flag）—— 留空表示「未定」，**不代填「无」**">
            <select style={inputStyle} value={riskFlag} onChange={(e) => setRiskFlag(e.target.value)}>
              <option value="">（未定 —— 不代填）</option>
              <option value="无">无</option>
              <option value="高危">高危</option>
              <option value="新发">新发</option>
              <option value="同病">同病</option>
            </select>
          </Field>
          <Field label="核心指标是否改善（core_metric_improved）—— 留空表示缺失 ⇒ 挂起">
            <select style={inputStyle} value={coreImproved} onChange={(e) => setCoreImproved(e.target.value)}>
              <option value="">（缺失 ⇒ 挂起）</option>
              <option value="true">是</option>
              <option value="false">否</option>
            </select>
          </Field>
          <Field label="其余结构化部分（confidence / module_scores / adherence，JSON）">
            <textarea
              style={{ ...inputStyle, minHeight: 110, fontFamily: 'monospace' }}
              value={verdictJson}
              onChange={(e) => setVerdictJson(e.target.value)}
            />
          </Field>
          <button
            style={buttonGhostStyle}
            disabled={busy || !cycleId.trim() || !verdictCustomerId.trim()}
            onClick={() =>
              run('判定结论落库', () => {
                let rest: Record<string, unknown>;
                try {
                  rest = JSON.parse(verdictJson) as Record<string, unknown>;
                } catch {
                  throw new Error('JSON 解析失败：请检查 confidence / module_scores / adherence 的写法。');
                }
                const body: VerdictRequest = {
                  customer_id: verdictCustomerId.trim(),
                  sequence_no: Number(verdictSeq) || 0,
                  ...(baseTotal.trim() ? { base_total: Number(baseTotal) } : {}),
                  ...(currentTotal.trim() ? { current_total: Number(currentTotal) } : {}),
                  same_origin: {
                    same_item_group: sameItemGroup,
                    range_matches: rangeMatches,
                    same_measurer: sameMeasurer,
                  },
                  adherence: rest.adherence as VerdictRequest['adherence'],
                  ...(riskFlag ? { risk_flag: riskFlag as VerdictRequest['risk_flag'] } : {}),
                  ...(coreImproved ? { core_metric_improved: coreImproved === 'true' } : {}),
                  confidence: (rest.confidence ?? {}) as Record<string, unknown>,
                  module_scores: (rest.module_scores ?? {}) as Record<string, number>,
                };
                return createVerdictAsMeridian(role, cycleId.trim(), body, newIdempotencyKey());
              })
            }
          >
            落库判定结论
          </button>
        </Card>
      ) : null}

      {/* ---------------- F2 判定历史 ---------------- */}
      {canCall('listVerdicts', role) ? (
        <Card title="F2 判定历史" hint="按客户查历史判定结论。">
          <Field label="客户 ID（customer_id）">
            <input
              style={inputStyle}
              value={listVerdictCustomerId}
              onChange={(e) => setListVerdictCustomerId(e.target.value)}
            />
          </Field>
          <button
            style={buttonGhostStyle}
            disabled={busy || !listVerdictCustomerId.trim()}
            onClick={() =>
              run('判定历史', () => listVerdictsAsMeridian(role, listVerdictCustomerId.trim()))
            }
          >
            查询历史
          </button>
        </Card>
      ) : null}

      {/* ---------------- G1 代录诉求 ---------------- */}
      {canCall('createRefund', role) ? (
        <Card
          title="G1 代录客户退款诉求"
          hint="reason_code 必填（未记录原因不可结案）；requested_at 是「最早且可核实」的时间，即计时基准 —— 本页不代填。"
        >
          <Field label="客户 ID（customer_id）">
            <input style={inputStyle} value={refundCustomerId} onChange={(e) => setRefundCustomerId(e.target.value)} />
          </Field>
          <Field label="入口（entry）">
            <select
              style={inputStyle}
              value={refundEntry}
              onChange={(e) => setRefundEntry(e.target.value as RefundCreateRequest['entry'])}
            >
              <option value="A门店代录">A 门店代录</option>
              <option value="B首周期">B 首周期</option>
            </select>
          </Field>
          <Field label="路由（refund_route）">
            <select
              style={inputStyle}
              value={refundRoute}
              onChange={(e) => setRefundRoute(e.target.value as RefundCreateRequest['refund_route'])}
            >
              <option value="履约类">履约类</option>
              <option value="效果类">效果类</option>
            </select>
          </Field>
          <Field label="原因（reason_code）—— 必填，无默认值">
            <select style={inputStyle} value={refundReason} onChange={(e) => setRefundReason(e.target.value)}>
              <option value="">（请选择 —— 不代填）</option>
              <option value="效果未达预期">效果未达预期</option>
              <option value="症状加重或出现新不适">症状加重或出现新不适</option>
              <option value="服务体验或沟通问题">服务体验或沟通问题</option>
              <option value="时间·经济·家庭原因">时间·经济·家庭原因</option>
              <option value="配合度不足导致无明显变化">配合度不足导致无明显变化</option>
              <option value="信任或价格异议">信任或价格异议</option>
            </select>
          </Field>
          <Field label="诉求时间（requested_at，「最早且可核实」，计时基准）">
            <input
              style={inputStyle}
              value={refundRequestedAt}
              onChange={(e) => setRefundRequestedAt(e.target.value)}
              placeholder="YYYY-MM-DD"
            />
          </Field>
          <Field label="客户原话（customer_statement，append-only，可空）">
            <textarea
              style={{ ...inputStyle, minHeight: 80 }}
              value={refundStatement}
              onChange={(e) => setRefundStatement(e.target.value)}
            />
          </Field>
          <button
            style={buttonGhostStyle}
            disabled={busy || !refundCustomerId.trim() || !refundReason || !refundRequestedAt.trim()}
            onClick={() =>
              run('代录诉求', () =>
                createRefundAsMeridian(
                  role,
                  {
                    customer_id: refundCustomerId.trim(),
                    entry: refundEntry,
                    refund_route: refundRoute,
                    reason_code: refundReason as RefundCreateRequest['reason_code'],
                    requested_at: refundRequestedAt.trim(),
                    ...(refundStatement.trim() ? { customer_statement: refundStatement } : {}),
                  },
                  newIdempotencyKey()
                )
              )
            }
          >
            提交代录
          </button>
        </Card>
      ) : null}

      {/* ---------------- G2 工单详情 ---------------- */}
      {canCall('getRefund', role) ? (
        <Card title="G2 退款工单详情" hint="读取工单（含 SLA 到期、录入延迟）。">
          <Field label="工单 ID（refund_id）">
            <input style={inputStyle} value={refundId} onChange={(e) => setRefundId(e.target.value)} />
          </Field>
          <button
            style={buttonGhostStyle}
            disabled={busy || !refundId.trim()}
            onClick={() => run('工单详情', () => getRefundAsMeridian(role, refundId.trim()))}
          >
            读取工单
          </button>
        </Card>
      ) : null}

      {/* ---------------- G3 挽留记录 ---------------- */}
      {canCall('createRetention', role) ? (
        <Card
          title="G3 挽留记录"
          hint="挽留记录必填（入口 B 不经挽留）；analysis / communication 在库层是 JSONB，传对象即可。"
        >
          <Field label="工单 ID（{id}）">
            <input style={inputStyle} value={refundId} onChange={(e) => setRefundId(e.target.value)} />
          </Field>
          <Field label="尝试次数（attempts）">
            <input style={inputStyle} value={retentionAttempts} onChange={(e) => setRetentionAttempts(e.target.value)} />
          </Field>
          <Field label="挽留结论（result）">
            <input style={inputStyle} value={retentionResult} onChange={(e) => setRetentionResult(e.target.value)} />
          </Field>
          <button
            style={buttonGhostStyle}
            disabled={busy || !refundId.trim() || !retentionResult.trim()}
            onClick={() =>
              run('挽留记录', () =>
                createRetentionAsMeridian(
                  role,
                  refundId.trim(),
                  { attempts: Number(retentionAttempts) || 0, result: retentionResult.trim() },
                  newIdempotencyKey()
                )
              )
            }
          >
            提交挽留记录
          </button>
        </Card>
      ) : null}

      {/* ---------------- G5 回执 ---------------- */}
      {canCall('createRefundReceipt', role) ? (
        <Card
          title="G5 回执（三态留痕）"
          hint="subscription_quota 是「推送之前」判定的额度（≤0 ⇒ 未授权（转线下））—— 顺序由服务端保证，本页如实传值。"
        >
          <Field label="工单 ID（{id}）">
            <input style={inputStyle} value={refundId} onChange={(e) => setRefundId(e.target.value)} />
          </Field>
          <Field label="订阅消息模板 ID（template_id）">
            <input style={inputStyle} value={receiptTemplateId} onChange={(e) => setReceiptTemplateId(e.target.value)} />
          </Field>
          <Field label="剩余额度（subscription_quota，推送前判定）">
            <input style={inputStyle} value={receiptQuota} onChange={(e) => setReceiptQuota(e.target.value)} />
          </Field>
          <Field label="推送是否成功（push_succeeded）">
            <label style={{ fontSize: FONT.md, color: COLOR.text }}>
              <input type="checkbox" checked={receiptPushOk} onChange={(e) => setReceiptPushOk(e.target.checked)} /> 推送成功
            </label>
          </Field>
          {!receiptPushOk ? (
            <Field label="失败原因（failure_reason，push_succeeded=false 时必填）">
              <input
                style={inputStyle}
                value={receiptFailReason}
                onChange={(e) => setReceiptFailReason(e.target.value)}
              />
            </Field>
          ) : null}
          <button
            style={buttonGhostStyle}
            disabled={busy || !refundId.trim()}
            onClick={() =>
              run('回执', () =>
                createRefundReceiptAsMeridian(
                  role,
                  refundId.trim(),
                  {
                    ...(receiptQuota.trim() ? { subscription_quota: Number(receiptQuota) } : {}),
                    ...(receiptTemplateId.trim() ? { template_id: receiptTemplateId.trim() } : {}),
                    push_succeeded: receiptPushOk,
                    ...(!receiptPushOk && receiptFailReason.trim()
                      ? { failure_reason: receiptFailReason.trim() }
                      : {}),
                  },
                  newIdempotencyKey()
                )
              )
            }
          >
            提交回执
          </button>
        </Card>
      ) : null}

      <p style={{ ...labelStyle, fontSize: FONT.xs }}>
        本页仅渲染当前角色被授予的入口；未授予的项只作边界说明。
        {isMeridian ? '' : '（当前角色在本端无专属动作。）'}
      </p>
    </Page>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ marginBottom: SPACE.md }}>
      <div style={labelStyle}>{label}</div>
      {children}
    </div>
  );
}