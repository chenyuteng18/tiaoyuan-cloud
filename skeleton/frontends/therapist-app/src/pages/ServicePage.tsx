/**
 * 端 B · 页面：服务与方案（域 D 之 D1 / D3 / D4 / D5-a / D5-b / D6）
 * ============================================================================
 *
 * 本页覆盖 `services/domain.ts` 的 D 域六个此前**零调用**的函数。
 *
 * 🛑 D3 代录：界面**不提供 source 选择器**（这是刻意的，不是漏做）
 * ---------------------------------------------------------------------------
 * 契约 `DailyReportRequest.source` 逐字：「代录须标 **代核**」。
 * `services/domain.ts` 的 `submitDailyReportAsStaff` 已把 `source` 写死为 `'代核'`。
 * 本页**刻意不提供"以客户身份代填"这条路径** —— 提供一个 source 下拉就等于
 * 允许把代录伪装成客户自填，而那会让下游把一条门店行为记成客户行为。
 * 契约的枚举里有两个值，但端 B 只可能产生其中一个。
 *
 * 🛑 D1 核销的字段名来自【控制器真实形状】（契约未声明 requestBody）
 * ---------------------------------------------------------------------------
 * 契约 D1 只有 parameters。真相源是 `FulfillmentController.VisitRequest`：
 *   serving_store_id / plan_id / plan_version / part_method / duration_min /
 *   pre_feedback / post_feedback / abnormal_note
 * 自创字段名不会报错（服务端只会看到一个陌生键）⇒ 表现为"核销成功但字段没记上"，
 * 与本仓第 50 条同族。故此处逐字对齐控制器。
 *
 * 🛑 D5-a / D6 的字段名同样来自控制器真实形状
 * ---------------------------------------------------------------------------
 * `PlanController.PlanRequest`：customer_id / treatment_json / lifestyle_json / intent_params
 * `PlanController.DispatchRequest`：plan_id / plan_version / store_id / device_id /
 *   param_snapshot / result / failed_reason / event
 * ⚠️ 契约对这两个端点**没有声明 requestBody**（与 D5-c 同款）⇒ 控制器即真相源。
 *
 * 🛑 D6 的前置条件写进界面：需方案已审核
 * ---------------------------------------------------------------------------
 * 契约 summary 逐字：「需方案已审核」。故本页把 D5-c（审核，仅经络师）的入口
 * 指向「专属动作」页，并在 D6 卡片上**明写这条前置**，而不是让一线提交后
 * 收到一个 5001 再去猜。
 */

import { useState } from 'react';
import { newIdempotencyKey } from '../api/client';
import {
  createDeviceDispatch,
  createPlan,
  createVisit,
  getPlan,
  listDailyReports,
  submitDailyReportAsStaff,
  type DailyReport,
} from '../services/domain';
import { describe } from '../services/errors';
import { roleDisplay, type AppRole } from '../contract/access';
import { COLOR, FONT, SPACE } from '../ui/tokens';
import { Badge, buttonGhostStyle, Card, Empty, ErrorBar, inputStyle, KV, labelStyle, Page } from '../ui/components';

type Outcome =
  | { kind: 'ok'; label: string; json: string }
  | { kind: 'replay'; label: string }
  | { kind: 'err'; text: string; traceId: string };

export default function ServicePage({
  role,
  roleLabel,
  customerId,
  onNeedRelogin,
}: {
  role: AppRole;
  roleLabel: string;
  /** 从「客户详情」带入的客户 ID（有值时自动填充，省去手抄）。 */
  customerId?: string;
  onNeedRelogin: () => void;
}) {
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [busy, setBusy] = useState(false);

  // D1 服务核销
  const [visitCustomerId, setVisitCustomerId] = useState(customerId ?? '');
  const [servingStoreId, setServingStoreId] = useState('');
  const [visitPlanId, setVisitPlanId] = useState('');
  const [planVersion, setPlanVersion] = useState('');
  const [partMethod, setPartMethod] = useState('');
  const [durationMin, setDurationMin] = useState('');
  const [preFeedback, setPreFeedback] = useState('');
  const [postFeedback, setPostFeedback] = useState('');
  const [abnormalNote, setAbnormalNote] = useState('');

  // D3 代录每日填报
  const [reportCustomerId, setReportCustomerId] = useState(customerId ?? '');
  const [reportDate, setReportDate] = useState('');
  const [answersJson, setAnswersJson] = useState('{}');
  const [reports, setReports] = useState<readonly DailyReport[] | null>(null);

  // D5-a 方案出具 / D5-b 查阅
  const [planCustomerId, setPlanCustomerId] = useState(customerId ?? '');
  const [treatmentJson, setTreatmentJson] = useState('{}');
  const [lifestyleJson, setLifestyleJson] = useState('{}');
  const [intentParams, setIntentParams] = useState('');
  const [lookupPlanId, setLookupPlanId] = useState('');
  const [planDetail, setPlanDetail] = useState<Record<string, unknown> | null>(null);

  // D6 设备下发
  const [dispatchPlanId, setDispatchPlanId] = useState('');
  const [dispatchPlanVersion, setDispatchPlanVersion] = useState('');
  const [dispatchStoreId, setDispatchStoreId] = useState('');
  const [dispatchDeviceId, setDispatchDeviceId] = useState('');
  const [paramSnapshot, setParamSnapshot] = useState('');
  const [dispatchResult, setDispatchResult] = useState('');
  const [failedReason, setFailedReason] = useState('');
  const [dispatchEvent, setDispatchEvent] = useState('');

  async function run(label: string, fn: () => Promise<unknown>) {
    setBusy(true);
    setOutcome(null);
    try {
      const data = await fn();
      setOutcome({
        kind: 'ok',
        label,
        json:
          data === undefined
            ? '（服务端未返回 data —— 契约 envelope-rule：code != 0 时 data 为空；此处 code = 0）'
            : JSON.stringify(data, null, 2),
      });
    } catch (e) {
      const d = describe(e);
      if (d.kind === 'replay') setOutcome({ kind: 'replay', label });
      else {
        setOutcome({ kind: 'err', text: d.text, traceId: d.traceId });
        if (d.kind === 'auth') onNeedRelogin();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="服务与方案" roleLabel={roleLabel}>
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
            <div style={{ color: COLOR.ok }}>✓ {outcome.label} 已完成</div>
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
          </div>
        ) : (
          <ErrorBar text={outcome.text} traceId={outcome.traceId} />
        )
      ) : null}

      {/* ---------------- D1 服务核销 ---------------- */}
      <Card
        title="D1 服务核销"
        hint="契约未声明 requestBody ⇒ 字段名逐字对齐后端控制器 VisitRequest。核销是服务的账本锚点。"
      >
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={visitCustomerId}
            onChange={(e) => setVisitCustomerId(e.target.value)}
          />
        </Field>
        <Field label="服务门店（serving_store_id）">
          <input
            style={inputStyle}
            value={servingStoreId}
            onChange={(e) => setServingStoreId(e.target.value)}
          />
        </Field>
        <Field label="关联方案 ID（plan_id，可选）">
          <input style={inputStyle} value={visitPlanId} onChange={(e) => setVisitPlanId(e.target.value)} />
        </Field>
        <Field label="方案版本（plan_version，可选）">
          <input style={inputStyle} value={planVersion} onChange={(e) => setPlanVersion(e.target.value)} />
        </Field>
        <Field label="部位 / 手法（part_method，可选）">
          <input style={inputStyle} value={partMethod} onChange={(e) => setPartMethod(e.target.value)} />
        </Field>
        <Field label="时长（duration_min，分钟，可选）">
          <input style={inputStyle} value={durationMin} onChange={(e) => setDurationMin(e.target.value)} />
        </Field>
        <Field label="服务前反馈（pre_feedback，可选）">
          <textarea
            style={{ ...inputStyle, minHeight: 60 }}
            value={preFeedback}
            onChange={(e) => setPreFeedback(e.target.value)}
          />
        </Field>
        <Field label="服务后反馈（post_feedback，可选）">
          <textarea
            style={{ ...inputStyle, minHeight: 60 }}
            value={postFeedback}
            onChange={(e) => setPostFeedback(e.target.value)}
          />
        </Field>
        <Field label="异常备注（abnormal_note，可选 —— 有异常必须写在这里，不要写进反馈正文）">
          <textarea
            style={{ ...inputStyle, minHeight: 60 }}
            value={abnormalNote}
            onChange={(e) => setAbnormalNote(e.target.value)}
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !visitCustomerId.trim()}
          onClick={() =>
            run('服务核销', async () => {
              const v = await createVisit(
                role,
                visitCustomerId.trim(),
                {
                  ...(servingStoreId.trim() ? { serving_store_id: servingStoreId.trim() } : {}),
                  ...(visitPlanId.trim() ? { plan_id: visitPlanId.trim() } : {}),
                  ...(planVersion.trim() ? { plan_version: Number(planVersion) } : {}),
                  ...(partMethod.trim() ? { part_method: partMethod.trim() } : {}),
                  ...(durationMin.trim() ? { duration_min: Number(durationMin) } : {}),
                  ...(preFeedback.trim() ? { pre_feedback: preFeedback.trim() } : {}),
                  ...(postFeedback.trim() ? { post_feedback: postFeedback.trim() } : {}),
                  ...(abnormalNote.trim() ? { abnormal_note: abnormalNote.trim() } : {}),
                },
                newIdempotencyKey()
              );
              return v;
            })
          }
        >
          提交核销
        </button>
        <p style={{ ...labelStyle, marginTop: SPACE.md }}>
          服务记录（D2）在「客户详情」页读取 —— 两者同域，但分开是因为 D2 是
          <strong>客户维度的全局账本</strong>，而 D1 是本次核销单。
        </p>
      </Card>

      {/* ---------------- D3 代录每日填报 ---------------- */}
      <Card
        title="D3 代录每日填报"
        hint="source 由本层写死为「代核」—— 界面刻意不提供 source 选择器，端 B 不存在「以客户身份代填」这条路径。"
      >
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={reportCustomerId}
            onChange={(e) => setReportCustomerId(e.target.value)}
          />
        </Field>
        <Field label="填报日期（date）">
          <input
            style={inputStyle}
            value={reportDate}
            onChange={(e) => setReportDate(e.target.value)}
            placeholder="YYYY-MM-DD"
          />
        </Field>
        <Field label="作答内容（answers_json，JSON 对象）">
          <textarea
            style={{ ...inputStyle, minHeight: 100, fontFamily: 'monospace' }}
            value={answersJson}
            onChange={(e) => setAnswersJson(e.target.value)}
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !reportCustomerId.trim() || !reportDate.trim()}
          onClick={() =>
            run('代录每日填报', () => {
              let answers: Record<string, unknown>;
              try {
                answers = JSON.parse(answersJson) as Record<string, unknown>;
              } catch {
                throw new Error('JSON 解析失败：请检查 answers_json 的写法。');
              }
              return submitDailyReportAsStaff(
                role,
                reportCustomerId.trim(),
                { date: reportDate.trim(), answers_json: answers },
                newIdempotencyKey()
              );
            })
          }
        >
          提交代录
        </button>
      </Card>

      {/* ---------------- D4 填报记录 ---------------- */}
      <Card title="D4 填报记录" hint="按客户查每日填报历史。">
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={reportCustomerId}
            onChange={(e) => setReportCustomerId(e.target.value)}
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !reportCustomerId.trim()}
          onClick={() =>
            run('填报记录', async () => {
              const items = await listDailyReports(role, reportCustomerId.trim());
              setReports(items ?? []);
              return items;
            })
          }
        >
          查询记录
        </button>
        <div style={{ marginTop: SPACE.md }}>
          {reports === null ? (
            <Empty text="尚未查询。" />
          ) : reports.length === 0 ? (
            <Empty text="暂无填报记录。" />
          ) : (
            reports.map((r, i) => (
              <KV
                key={r.report_id ?? i}
                k={r.date ?? `第 ${i + 1} 条`}
                v={
                  <span>
                    {r.source ? <Badge text={r.source} /> : null}{' '}
                    {r.weekly_count === undefined ? '' : `本周 ${r.weekly_count} 次`}
                    {r.suggestion ? ` · 建议：${r.suggestion}` : ''}
                  </span>
                }
              />
            ))
          )}
        </div>
      </Card>

      {/* ---------------- D5-a 方案出具 ---------------- */}
      <Card
        title="D5-a 方案出具"
        hint="契约未声明 requestBody ⇒ 字段名对齐控制器 PlanRequest（treatment_json / lifestyle_json / intent_params）。出具后需经络师审核（D5-c）。"
      >
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={planCustomerId}
            onChange={(e) => setPlanCustomerId(e.target.value)}
          />
        </Field>
        <Field label="调理方案（treatment_json，JSON）">
          <textarea
            style={{ ...inputStyle, minHeight: 80, fontFamily: 'monospace' }}
            value={treatmentJson}
            onChange={(e) => setTreatmentJson(e.target.value)}
          />
        </Field>
        <Field label="起居建议（lifestyle_json，JSON）">
          <textarea
            style={{ ...inputStyle, minHeight: 80, fontFamily: 'monospace' }}
            value={lifestyleJson}
            onChange={(e) => setLifestyleJson(e.target.value)}
          />
        </Field>
        <Field label="意图参数（intent_params，可选）">
          <input style={inputStyle} value={intentParams} onChange={(e) => setIntentParams(e.target.value)} />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !planCustomerId.trim()}
          onClick={() =>
            run('方案出具', () =>
              createPlan(
                role,
                {
                  customer_id: planCustomerId.trim(),
                  treatment_json: treatmentJson,
                  lifestyle_json: lifestyleJson,
                  ...(intentParams.trim() ? { intent_params: intentParams.trim() } : {}),
                },
                newIdempotencyKey()
              )
            )
          }
        >
          提交方案
        </button>
        <p style={{ ...labelStyle, marginTop: SPACE.md }}>
          🛑 出具后不能直接下发设备 —— 契约 D6 逐字「需方案已审核」，审核动作
          （D5-c）<strong>仅{roleDisplay('meridian')}</strong>可用，入口在「专属动作」页。
        </p>
      </Card>

      {/* ---------------- D5-b 方案查阅 ---------------- */}
      <Card title="D5-b 方案查阅" hint="按方案 ID 读取方案内容与状态。">
        <Field label="方案 ID（plan_id）">
          <input
            style={inputStyle}
            value={lookupPlanId}
            onChange={(e) => setLookupPlanId(e.target.value)}
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !lookupPlanId.trim()}
          onClick={() =>
            run('方案查阅', async () => {
              const p = await getPlan(role, lookupPlanId.trim());
              setPlanDetail(p ?? null);
              return p;
            })
          }
        >
          读取方案
        </button>
        <div style={{ marginTop: SPACE.md }}>
          {planDetail === null ? (
            <Empty text="尚未读取。" />
          ) : (
            Object.entries(planDetail).map(([k, v]) => (
              <KV key={k} k={k} v={typeof v === 'object' ? JSON.stringify(v) : String(v)} />
            ))
          )}
        </div>
      </Card>

      {/* ---------------- D6 设备参数下发 ---------------- */}
      <Card
        title="D6 设备参数下发"
        hint={`前置：方案已审核（D5-c，仅${roleDisplay('meridian')}）。契约未声明 requestBody ⇒ 字段名对齐控制器 DispatchRequest。`}
      >
        <Field label="方案 ID（plan_id）">
          <input
            style={inputStyle}
            value={dispatchPlanId}
            onChange={(e) => setDispatchPlanId(e.target.value)}
          />
        </Field>
        <Field label="方案版本（plan_version）">
          <input
            style={inputStyle}
            value={dispatchPlanVersion}
            onChange={(e) => setDispatchPlanVersion(e.target.value)}
          />
        </Field>
        <Field label="门店 ID（store_id）">
          <input
            style={inputStyle}
            value={dispatchStoreId}
            onChange={(e) => setDispatchStoreId(e.target.value)}
          />
        </Field>
        <Field label="设备 ID（device_id）">
          <input
            style={inputStyle}
            value={dispatchDeviceId}
            onChange={(e) => setDispatchDeviceId(e.target.value)}
          />
        </Field>
        <Field label="参数快照（param_snapshot，可选）">
          <textarea
            style={{ ...inputStyle, minHeight: 60 }}
            value={paramSnapshot}
            onChange={(e) => setParamSnapshot(e.target.value)}
          />
        </Field>
        <Field label="下发结果（result，可选）">
          <input style={inputStyle} value={dispatchResult} onChange={(e) => setDispatchResult(e.target.value)} />
        </Field>
        <Field label="失败原因（failed_reason，失败时填）">
          <input style={inputStyle} value={failedReason} onChange={(e) => setFailedReason(e.target.value)} />
        </Field>
        <Field label="事件（event，可选）">
          <input style={inputStyle} value={dispatchEvent} onChange={(e) => setDispatchEvent(e.target.value)} />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !dispatchPlanId.trim()}
          onClick={() =>
            run('设备下发', () =>
              createDeviceDispatch(
                role,
                {
                  plan_id: dispatchPlanId.trim(),
                  ...(dispatchPlanVersion.trim() ? { plan_version: Number(dispatchPlanVersion) } : {}),
                  ...(dispatchStoreId.trim() ? { store_id: dispatchStoreId.trim() } : {}),
                  ...(dispatchDeviceId.trim() ? { device_id: dispatchDeviceId.trim() } : {}),
                  ...(paramSnapshot.trim() ? { param_snapshot: paramSnapshot.trim() } : {}),
                  ...(dispatchResult.trim() ? { result: dispatchResult.trim() } : {}),
                  ...(failedReason.trim() ? { failed_reason: failedReason.trim() } : {}),
                  ...(dispatchEvent.trim() ? { event: dispatchEvent.trim() } : {}),
                },
                newIdempotencyKey()
              )
            )
          }
        >
          提交下发
        </button>
      </Card>
    </Page>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: SPACE.md }}>
      <div style={labelStyle}>{label}</div>
      {children}
    </div>
  );
}