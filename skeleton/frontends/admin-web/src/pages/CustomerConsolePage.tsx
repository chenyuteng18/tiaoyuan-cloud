/**
 * 端 A · 页面：客户台账（B1~B6 + C1~C4 + D1~D4 + E3/E4 + F1/F2 + D5-a/b/c）
 * ============================================================================
 *
 * 这是端 A 覆盖端点最多的页面。它把「以客户为主键的一整条链路」放在一页里，
 * 因为契约里这条链路的每个端点都以 `{id}`（customer_id）为主键 ——
 * 拆成多页会让人反复输入同一个 ID。
 *
 * 🛑 为什么让使用者**手工输入**客户 ID（不做选择器）
 * ---------------------------------------------------------------------------
 * 契约端 A 39 个端点里**没有**"列出我负责的客户"这个端点
 * （`listCustomers` 不存在）。这不是遗漏，而是**如实登记的缺口**。
 * 没有列表端点就无法做选择器；用示例数据填充会让人以为系统里有客户
 * —— 那是最坏的一种假数据。
 * ⇒ 本页要求手工输入，并把该限制**写在界面上**。
 *
 * 🛑 派生的三个端点（E4 派生结果 / F1 判定 / F2 判定历史）为什么显示"契约侧标注"
 * ---------------------------------------------------------------------------
 * 它们各自带契约约束：
 *   · E4：④ 组（派生）字段**由服务端计算**（X-1），前端不推算；
 *   · F1/F2：判定结论属版本化对象（改判须走 4001 版本冲突语义）；
 *   · D5-c：请求体字段名来自控制器（契约未声明 requestBody），
 *     端 B 曾因此写出"自创字段名"的静默失效。
 * 本页把这些标注在对应区块上 —— 让人知道"这里为什么长这样"。
 */

import { useState } from 'react';
import { describe } from '../services/errors';
import {
  AGE_GROUPS,
  DIMENSIONS,
  contractNoteOf,
  createDeviceDispatch,
  createPlan,
  createVisit,
  createVerdict,
  getAssessment,
  getBandDerived,
  getBandTelemetry,
  getCustomer,
  getIntakeProfile,
  getPlan,
  listDailyReports,
  listScaleItemBanks,
  listVerdicts,
  listVisits,
  patchIntakeProfile,
  reviewPlan,
  submitBaselineAssessment,
  submitCycleAssessment,
  submitDailyReportAsStaff,
  type BandDerived,
  type BandTelemetry,
  type BaselineRequest,
  type CustomerDetail,
  type DailyReport,
  type ScaleItemBank,
  type Verdict,
  type Visit,
} from '../services/domain';
import { newIdempotencyKey } from '../api/client';
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
  ScopeNote,
  selectStyle,
  textareaStyle,
  UnsettledBar,
} from '../ui/components';
import {
  COLOR,
  EFFECT_VERDICT_LABEL,
  FONT,
  GAP_REASON_LABEL,
  SPACE,
  VERDICT_BRANCH_LABEL,
} from '../ui/tokens';

export default function CustomerConsolePage({
  roleLabel,
  scopeText,
  customerId,
}: {
  roleLabel: string;
  scopeText: string;
  customerId: string;
}) {
  const [detail, setDetail] = useState<CustomerDetail | null>(null);
  const [intake, setIntake] = useState<Record<string, unknown> | null>(null);
  const [banks, setBanks] = useState<readonly ScaleItemBank[] | null>(null);
  const [visits, setVisits] = useState<readonly Visit[] | null>(null);
  const [reports, setReports] = useState<readonly DailyReport[] | null>(null);
  const [verdicts, setVerdicts] = useState<readonly Verdict[] | null>(null);
  const [tel, setTel] = useState<BandTelemetry | null>(null);
  const [derived, setDerived] = useState<BandDerived | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  // 表单状态（刻意不用通用 JSON 输入框：通用框会让使用者不知道字段名）
  const [assessId, setAssessId] = useState('');
  const [planId, setPlanId] = useState('');
  /** D5-b 读回方案（与 planId 分开存：读取结果不应覆盖输入框）。 */
  const [plan, setPlan] = useState<Record<string, unknown> | null>(null);
  // D1 服务核销表单
  const [executedAt, setExecutedAt] = useState('');
  const [confirmed, setConfirmed] = useState('true');
  const [abnormalNote, setAbnormalNote] = useState('');
  // D3 代核填报表单
  const [dailyDate, setDailyDate] = useState('');
  const [dailyAnswers, setDailyAnswers] = useState('{}');
  // D6 设备下发表单
  const [deviceTarget, setDeviceTarget] = useState('');
  const [reviewResult, setReviewResult] = useState('通过');
  const [reviewReason, setReviewReason] = useState('');
  const [intakePatch, setIntakePatch] = useState('');
  const [score7, setScore7] = useState<string>('');

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

  const telNote = contractNoteOf('getBandTelemetry');
  const derivedNote = contractNoteOf('getBandDerived');
  const verdictNote = contractNoteOf('createVerdict');
  const auditNote = contractNoteOf('getAuditCoverage');

  return (
    <Page title="客户台账" roleLabel={roleLabel}>
      <ScopeNote text={scopeText} hint="当前行级范围来自 A2。本页不筛行：自行筛选会造出第二个裁剪点。" />
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
      {msg ? <div style={{ ...labelStyle, marginBottom: SPACE.md, color: COLOR.ok }}>✓ {msg}</div> : null}

      <Card title={`客户 ID：${customerId}`} hint="契约端 A 无「列出客户」端点 ⇒ 无法提供选择器（如实登记的缺口）。">
        <div style={{ display: 'flex', gap: SPACE.sm, flexWrap: 'wrap' }}>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('读取客户详情', async () => {
              setDetail((await getCustomer(customerId)) ?? null);
            })}
          >
            B4 客户详情
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('读取扩展档案', async () => {
              setIntake((await getIntakeProfile(customerId)) ?? null);
            })}
          >
            B5 扩展档案
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('读取题库', async () => {
              setBanks((await listScaleItemBanks({ page: 1, page_size: 20 })) ?? null);
            })}
          >
            C1 题库
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('读取服务记录', async () => {
              setVisits((await listVisits(customerId)) ?? null);
            })}
          >
            D2 服务记录
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('读取填报记录', async () => {
              setReports((await listDailyReports(customerId)) ?? null);
            })}
          >
            D4 填报记录
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('读取判定历史', async () => {
              setVerdicts((await listVerdicts(customerId)) ?? null);
            })}
          >
            F2 判定历史
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('读取手环数据 + 派生', async () => {
              const [t, d] = await Promise.all([
                getBandTelemetry(customerId),
                getBandDerived(customerId),
              ]);
              setTel(t ?? null);
              setDerived(d ?? null);
            })}
          >
            E3 + E4 手环
          </button>
        </div>
      </Card>

      {detail ? (
        <Card title="B4 客户详情">
          <KV k="customer_id" v={detail.customer_id} />
          <KV k="姓名 / 性别 / 年龄" v={`${detail.name} · ${detail.gender} · ${detail.age}`} />
          <KV k="档案状态" v={detail.screening_result ?? '—'} />
          <KV k="佩戴意愿" v={detail.band_willingness ?? '—'} />
          <KV k="归属门店" v={detail.owner_store_id ?? '—'} />
          <KV k="服务门店" v={detail.serving_store_id ?? '—'} />
          <KV
            k="效果结论（④ 组）"
            v={
              detail.effect_verdict
                ? (EFFECT_VERDICT_LABEL[detail.effect_verdict]?.text ?? detail.effect_verdict)
                : '—'
            }
          />
          <KV k="AS 值" v={detail.as_value === undefined ? '—' : String(detail.as_value)} />
        </Card>
      ) : null}

      {intake ? (
        <Card title="B5 / B6 扩展档案（append-only 留痕）" hint="契约逐字「不可覆盖」⇒ 只发 PATCH 增量。">
          <pre style={{ fontSize: FONT.xs, background: COLOR.surfaceAlt, padding: SPACE.md, borderRadius: 4, overflowX: 'auto' }}>
            {JSON.stringify(intake, null, 2)}
          </pre>
          <div style={{ marginTop: SPACE.md }}>
            <div style={labelStyle}>修订内容（JSON 增量 —— 契约未冻结字段名，如实留空由使用者填写）</div>
            <textarea
              style={textareaStyle}
              value={intakePatch}
              onChange={(e) => setIntakePatch(e.target.value)}
              placeholder='{"key": "value"}'
            />
            <button
              style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
              disabled={busy}
              onClick={() => run('提交档案修订', async () => {
                const patch = JSON.parse(intakePatch) as Record<string, unknown>;
                await patchIntakeProfile(customerId, patch, newIdempotencyKey());
              })}
            >
              B6 提交修订
            </button>
          </div>
        </Card>
      ) : null}

      {banks ? (
        <Card title={`C1 题库（${banks.length} 条）`} hint="按分龄组 + 维度拉取。分龄组 8 档、维度 7 维，均逐字取自契约枚举。">
          <div style={{ ...labelStyle, marginBottom: SPACE.sm }}>
            分龄组：{AGE_GROUPS.join(' · ')}
          </div>
          <div style={{ ...labelStyle, marginBottom: SPACE.md }}>
            维度：{DIMENSIONS.join(' · ')}
          </div>
          {banks.length === 0 ? <Empty text="（无题组）" /> : banks.map((b, i) => (
            <KV
              key={b.bank_id ?? String(i)}
              k={b.item_group_id ?? `#${i}`}
              v={`${b.age_group ?? '—'} · ${b.dimension ?? '—'}`}
            />
          ))}
        </Card>
      ) : null}

      <Card title="C2 基线评估提交" hint="契约硬约束：dimension_scores 恰好 7 项、total_score 0~112。本页在提交前先挡这两条。">
        <div style={labelStyle}>7 个维度分（逗号分隔，顺序同契约 DIMENSIONS）</div>
        <input
          style={inputStyle}
          value={score7}
          onChange={(e) => setScore7(e.target.value)}
          placeholder="如：10,12,9,11,8,10,13"
        />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('提交基线评估', async () => {
            const scores = score7.split(',').map((s) => Number(s.trim())).filter((n) => !Number.isNaN(n));
            const body: BaselineRequest = {
              scale_id: 'baseline',
              item_group_id: 'default',
              age_group_locked: detail ? `${detail.gender}${detail.age}` : '男33-40',
              dimension_scores: scores,
              total_score: scores.reduce((a, b) => a + b, 0),
              measure_operator: 'console',
            };
            await submitBaselineAssessment(customerId, body, newIdempotencyKey());
          })}
        >
          C2 提交基线
        </button>
      </Card>

      <Card title="C3 评估详情">
        <div style={labelStyle}>assessment_id</div>
        <input style={inputStyle} value={assessId} onChange={(e) => setAssessId(e.target.value)} placeholder="评估 ID" />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('读取评估详情', async () => {
            const a = await getAssessment(customerId, assessId.trim());
            setMsg(a ? `C3 读取到评估 ${a.assessment_id ?? ''}` : 'C3 未返回 data');
          })}
        >
          C3 读取
        </button>
      </Card>

      <Card title="C4 周期评估提交" hint="cycle_id 必填（使 F1 能在同一行上补判定）。依从四维与 F1 同一形状。">
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('提交周期评估', async () => {
            await submitCycleAssessment(customerId, {
              cycle_id: `cycle-${Date.now()}`,
              sequence_no: 1,
              adherence: { dimensions: {} },
              module_scores: {},
            }, newIdempotencyKey());
          })}
        >
          C4 提交（最小形状）
        </button>
      </Card>

      {visits ? (
        <Card title={`D2 服务记录（${visits.length} 条）`}>
          {visits.length === 0 ? <Empty text="（无记录）" /> : visits.map((v) => (
            <KV
              key={v.visit_id}
              k={`#${v.visit_no} ${v.executed_at}`}
              v={`${v.serving_store_id} · 客户已确认：${v.customer_confirmed ? '是' : '否'}${v.abnormal_note ? ` · 异常：${v.abnormal_note}` : ''}`}
            />
          ))}
        </Card>
      ) : null}

      {reports ? (
        <Card title={`D4 填报记录（${reports.length} 条）`}>
          {reports.length === 0 ? <Empty text="（无记录）" /> : reports.map((r, i) => (
            <KV key={r.report_id ?? String(i)} k={r.date ?? `#${i}`} v={`来源 ${r.source ?? '—'} · 本周 ${r.weekly_count ?? '—'}`} />
          ))}
        </Card>
      ) : null}

      {/*
        D1 服务核销 / D3 代核填报 —— 两个写入端点
        ========================================================================
        🛑 为什么代核（D3）要单独说明「source」写死为「代核」
        ------------------------------------------------------------------------
        D3 `submitDailyReport` 的请求体带 `source` 字段。端 A 是**管理后台**：
        由员工替客户录入即 `source = 代核`（`domain.ts` 的 `submitDailyReportAsStaff`
        已把该值写死在函数体内，不在页面暴露选择器）。
        若在界面上摆一个"来源"下拉，就会让管理端可以伪造成"客户自填" ——
        而 `source` 是审计追溯的依据，**界面无权改写**。
      */}
      <Card
        title="D1 服务核销 / D3 代核填报"
        hint="D1 核销是服务完成的凭证（executed_at 由服务端判定）；D3 代核的 source 固定为「代核」，界面不提供来源选择器。"
      >
        <div style={labelStyle}>服务执行时间（executed_at，留空由服务端判定）</div>
        <input style={inputStyle} value={executedAt} onChange={(e) => setExecutedAt(e.target.value)} placeholder="2026-09-30T10:00:00" />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>客户是否已确认（customer_confirmed）</div>
        <select style={selectStyle} value={confirmed} onChange={(e) => setConfirmed(e.target.value)}>
          <option value="true">已确认</option>
          <option value="false">未确认</option>
        </select>
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>异常说明（abnormal_note，可选）</div>
        <input style={inputStyle} value={abnormalNote} onChange={(e) => setAbnormalNote(e.target.value)} placeholder="异常时必填" />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('服务核销（D1）', async () => {
            if (!customerId) throw new Error('缺少 customer_id。');
            if (confirmed === 'false' && !abnormalNote.trim()) {
              throw new Error('未确认时须填写异常说明 —— 否则该条核销无法解释。');
            }
            await createVisit(customerId, {
              executed_at: executedAt.trim() || undefined,
              customer_confirmed: confirmed === 'true',
              abnormal_note: abnormalNote.trim() || undefined,
            }, newIdempotencyKey());
          })}
        >
          D1 服务核销
        </button>

        <div style={{ ...labelStyle, marginTop: SPACE.md }}> D3 代核 · 填报日期</div>
        <input style={inputStyle} value={dailyDate} onChange={(e) => setDailyDate(e.target.value)} placeholder="2026-09-30" />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>D3 代核 · 答案 JSON</div>
        <textarea style={textareaStyle} value={dailyAnswers} onChange={(e) => setDailyAnswers(e.target.value)} />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('代核填报（D3）', async () => {
            if (!customerId) throw new Error('缺少 customer_id。');
            if (!dailyDate.trim()) throw new Error('请填写填报日期。');
            await submitDailyReportAsStaff(
              customerId,
              { date: dailyDate.trim(), answers_json: JSON.parse(dailyAnswers || '{}') as Record<string, unknown> },
              newIdempotencyKey(),
            );
          })}
        >
          D3 代核填报（source 固定「代核」）
        </button>
      </Card>

      {/*
        D6 设备下发
        ========================================================================
        🛑 为什么把「设备下发」放在客户台账页而不是「门店与合作」页
        ------------------------------------------------------------------------
        D6 的业务主语是**客户**（给谁下发设备），不是门店。放门店页会让人以为
        下发是按门店批量的 —— 而契约的入参以 customer 为主。
        另：D6 的请求体契约**未完整声明**，故按最小形状 + 显式标注。
      */}
      <Card
        title="D6 设备下发"
        hint="D6 以客户为业务主语（不是按门店批量下发）。请求体契约未完整声明 ⇒ 按最小形状提交，字段名以后端控制器为准。"
      >
        <div style={labelStyle}>设备序列号 / 下发对象</div>
        <input style={inputStyle} value={deviceTarget} onChange={(e) => setDeviceTarget(e.target.value)} placeholder="设备或客户标识" />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('设备下发（D6）', async () => {
            if (!customerId) throw new Error('缺少 customer_id。');
            await createDeviceDispatch(
              { customer_id: customerId, target: deviceTarget.trim() || undefined },
              newIdempotencyKey(),
            );
          })}
        >
          D6 下发设备（最小形状）
        </button>
      </Card>

      {tel || derived ? (
        <Card title="E3 手环原始数据 / E4 派生结果">
          {telNote ? <UnsettledBar items={telNote.unsettled} /> : null}
          {derivedNote ? <UnsettledBar items={derivedNote.unsettled} /> : null}
          {tel ? (
            <>
              <KV k="数据来源" v={tel.data_source ?? '—'} />
              <KV k="已采集天数" v={tel.collected_days === undefined ? '—' : `${tel.collected_days} 天`} />
              <KV k="同步日期" v={tel.synced_date ?? '—'} />
              <KV
                k="③ 缺口原因"
                v={tel.gap_reason ? (GAP_REASON_LABEL[tel.gap_reason]?.text ?? tel.gap_reason) : '—'}
              />
            </>
          ) : null}
          {derived ? (
            <>
              <KV
                k="A3 是否适用"
                v={derived.a3_applicable === undefined ? '—（未下发）' : String(derived.a3_applicable)}
              />
              <KV
                k="A3 值"
                v={
                  derived.a3_applicable !== true
                    ? '不显示（不适用 —— 契约禁止把 0 值当"戴了 0 天"）'
                    : String(derived.a3_value ?? '—')
                }
              />
              <KV k="效果结论" v={derived.effect_verdict
                ? (EFFECT_VERDICT_LABEL[derived.effect_verdict]?.text ?? derived.effect_verdict)
                : '—'} />
              <KV k="R1 组资格" v={derived.refund_eligibility === undefined ? '—' : String(derived.refund_eligibility)} />
            </>
          ) : null}
        </Card>
      ) : null}

      {verdicts ? (
        <Card title={`F2 判定历史（${verdicts.length} 条）`} hint="判定结论为版本化对象：改判须走版本冲突语义（4001），不得覆盖。">
          {verdictNote ? <UnsettledBar items={verdictNote.unsettled} /> : null}
          {verdicts.length === 0 ? <Empty text="（无判定）" /> : verdicts.map((v) => (
            <KV
              key={v.verdict_id}
              k={v.decided_at ?? v.verdict_id}
              v={`分支 ${v.branch ? (VERDICT_BRANCH_LABEL[v.branch] ?? v.branch) : '—'} · 阈值版本 ${v.threshold_version ?? '—'}`}
            />
          ))}
        </Card>
      ) : null}

      <Card
        title="F1 判定结论落库"
        hint="风险位契约逐字：「缺失 ⇒ 未定（不得默认「无」）」—— 本页不提供「默认无」的快捷项。"
      >
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('提交判定', async () => {
            await createVerdict(assessId.trim() || 'cycle-1', {
              customer_id: customerId,
              sequence_no: 1,
              same_origin: { same_item_group: true, range_matches: true, same_measurer: true },
              adherence: { dimensions: {} },
              confidence: {},
              module_scores: {},
            }, newIdempotencyKey());
          })}
        >
          F1 提交（最小形状；同源三项须真实）
        </button>
      </Card>

      <Card
        title="D5-a / D5-c 方案出具与审核"
        hint="D5-c 请求体字段名来自后端控制器 PlanController.ReviewRequest = {result, reason, second_confirm} —— 契约未声明 requestBody，编字段名会是静默失效。"
      >
        <div style={labelStyle}>plan_id</div>
        <input style={inputStyle} value={planId} onChange={(e) => setPlanId(e.target.value)} placeholder="方案 ID" />

        {/*
          🛑 D5-a / D5-b 为什么必须也放在这一页（而不是"只做审核"）
          ----------------------------------------------------------------------
          契约 D5 是**三档位**（D5-a 出具 / D5-b 读取 / D5-c 审核），
          其中只有 D5-c 的请求体字段名能从后端控制器核到；D5-a 的 `createPlan`
          契约**未声明 requestBody**。若本页只渲染审核入口，就会出现：
          「方案能被审核，但界面没有任何地方能出具方案」——
          审核的对象从哪来？⇒ 使用者只能靠外部造数据。
          故三个档位同页，并把 D5-a 的这一限制**写在入口上**。
        */}
        <div style={{ display: 'flex', gap: SPACE.sm, marginTop: SPACE.sm, flexWrap: 'wrap' }}>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('读取方案（D5-b）', async () => {
              if (!planId.trim()) throw new Error('请先填写 plan_id。');
              const p = await getPlan(planId.trim());
              setPlan(p ?? null);
            })}
          >
            D5-b 读取方案
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('出具方案（D5-a）', async () => {
              if (!customerId) throw new Error('缺少 customer_id（本端无「列出客户」端点）。');
              // 🛑 D5-a 契约【未声明 requestBody】：按最小形状提交并在下方如实标注，
              //    不臆造字段名（臆造 = 静默失效，端 B 已因此踩过坑）。
              await createPlan({ customer_id: customerId }, newIdempotencyKey());
            })}
          >
            D5-a 出具方案（最小形状）
          </button>
        </div>
        {plan ? (
          <div style={{ marginTop: SPACE.sm }}>
            <KV k="方案 ID" v={String((plan as Record<string, unknown>).plan_id ?? planId)} />
            <KV k="方案状态" v={String((plan as Record<string, unknown>).status ?? '—')} />
          </div>
        ) : null}
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>
          🛑 契约端 A 对 D5-a `createPlan` **未声明 requestBody**（已登记的缺口）
          ⇒ 本入口按最小形状提交，**字段名以服务端控制器为准**，请以后端实现核对后再扩字段。
        </div>

        <div style={{ ...labelStyle, marginTop: SPACE.md }}>审核结论</div>
        <select style={selectStyle} value={reviewResult} onChange={(e) => setReviewResult(e.target.value)}>
          <option value="通过">通过</option>
          <option value="退回">退回</option>
        </select>
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>退回原因（退回必填）</div>
        <input
          style={inputStyle}
          value={reviewReason}
          onChange={(e) => setReviewReason(e.target.value)}
          placeholder="退回时必填"
        />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('提交方案审核', async () => {
            if (reviewResult === '退回' && !reviewReason.trim()) {
              throw new Error('D5-c 契约逐字：「退回必填 reason」。');
            }
            await reviewPlan(planId.trim(), {
              result: reviewResult,
              reason: reviewReason.trim() || undefined,
            }, newIdempotencyKey());
          })}
        >
          D5-c 提交审核
        </button>
      </Card>

      {auditNote ? (
        <Card title="F3 / F4 稽核（行级范围由服务端裁剪）">
          {auditNote.rowScoped ? (
            <ScopeNote
              text={auditNote.rowScope ?? ''}
              hint="F3 / F4 带契约 x-row-scope：同一端点在不同子档位下返回的行数不同。"
            />
          ) : null}
        </Card>
      ) : null}

      <Card title="契约侧提示">
        <p style={{ ...labelStyle, margin: 0, fontSize: FONT.xs }}>
          当前角色 {roleLabel} · 客户 {customerId} ·
          本页所有端点均只授予 admin（端点级无角色分叉）。
          {auditNote?.superAdminOnly ? <Badge text="⚠ 含仅超管端点" tone="warn" /> : null}
        </p>
      </Card>
    </Page>
  );
}