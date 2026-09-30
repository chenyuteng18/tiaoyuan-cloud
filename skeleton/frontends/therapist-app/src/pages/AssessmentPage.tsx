/**
 * 端 B · 页面：量表与评估（域 C 之 C1~C4）
 * ============================================================================
 *
 * 本页覆盖 `services/domain.ts` 的 C 域四个函数（此前**零调用**）：
 * C1 题库拉取 / C2 基线评估提交 / C3 评估详情 / C4 周期评估提交。
 *
 * 🛑 C2 的两个契约硬约束在界面层**前置挡住**
 * ---------------------------------------------------------------------------
 * `BaselineAssessmentRequest` 逐字：`dimension_scores` **minItems 7 / maxItems 7**、
 * `total_score` 0~112。`services/domain.ts` 的 `submitBaselineAssessment` 已做本地
 * 校验（服务端仍会校验并回 5001）。本页把它**做成 7 个独立输入框**而不是一个
 * JSON 文本域 —— 后者会让一线不知道"到底要填几项"，从而把一次可避免的失败
 * （少一项）记成系统故障。
 *
 * 🛑 C2 的 total_score **不由前端求和**
 * ---------------------------------------------------------------------------
 * 本页只显示"当前各项之和"作为**核对提示**，提交值以你填入的 `total_score` 为准；
 * 且**不自动覆盖**你填的值。理由：派生态由服务端算（X-1），若前端自动求和，
 * 一旦服务端口径变化（如某维度权重调整），两边会静默分叉，而分叉时前端是
 * "看起来更权威"的那一个 —— 那正是 X-1 要消灭的形态。
 * 同理 `age_group_locked` / `item_group_id` / `scale_id` 三者**必须来自 C1 返回**，
 * 本页把它们做成只读带入，不让手抄（手抄 = 造第二份权威）。
 *
 * 🛑 C4 的 adherence 形状与 F1 **必须一致**（契约明文）
 * ---------------------------------------------------------------------------
 * 契约逐字：「与 F1 **同一形状** —— 两侧对同一事实不得有两套字段名」。
 * 且逐字强调「🛑 **A2 永不参与 AS**」。故本页只提供 A1 / A3 / A4 三个维度，
 * **刻意不提供 A2 这一项** —— 提供它就是把契约红线写进界面。
 */

import { useState } from 'react';
import { newIdempotencyKey } from '../api/client';
import {
  AGE_GROUPS,
  DIMENSIONS,
  getAssessment,
  listScaleItemBanks,
  submitBaselineAssessment,
  submitCycleAssessment,
  type Adherence,
  type AssessmentDetail,
  type BaselineResult,
  type ScaleItemBank,
} from '../services/domain';
import { describe } from '../services/errors';
import type { AppRole } from '../contract/access';
import { COLOR, FONT, SPACE } from '../ui/tokens';
import { Badge, buttonGhostStyle, Card, Empty, ErrorBar, inputStyle, KV, labelStyle, Page } from '../ui/components';

type Outcome =
  | { kind: 'ok'; label: string; json: string }
  | { kind: 'replay'; label: string }
  | { kind: 'err'; text: string; traceId: string };

/** 依从维度 —— 逐字取自契约「键为 A1/A3/A4；🛑 A2 永不参与 AS」。 */
const ADHERENCE_KEYS: readonly string[] = Object.freeze(['A1', 'A3', 'A4']);

export default function AssessmentPage({
  role,
  roleLabel,
  onNeedRelogin,
}: {
  role: AppRole;
  roleLabel: string;
  onNeedRelogin: () => void;
}) {
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [busy, setBusy] = useState(false);

  // C1 题库
  const [ageGroup, setAgeGroup] = useState('');
  const [dimension, setDimension] = useState('');
  const [banks, setBanks] = useState<readonly ScaleItemBank[] | null>(null);
  /** C2 的三把钥匙**由 C1 返回带入**（不让人手抄，见文件头）。 */
  const [picked, setPicked] = useState<ScaleItemBank | null>(null);

  // C2 基线
  const [baselineCustomerId, setBaselineCustomerId] = useState('');
  const [scores, setScores] = useState<readonly string[]>(() => DIMENSIONS.map(() => ''));
  const [totalScore, setTotalScore] = useState('');
  const [measureOperator, setMeasureOperator] = useState('');
  const [baseline, setBaseline] = useState<BaselineResult | null>(null);

  // C3 详情
  const [detailCustomerId, setDetailCustomerId] = useState('');
  const [assessmentId, setAssessmentId] = useState('');
  const [detail, setDetail] = useState<AssessmentDetail | null>(null);

  // C4 周期评估
  const [cycleCustomerId, setCycleCustomerId] = useState('');
  const [cycleId, setCycleId] = useState('');
  const [sequenceNo, setSequenceNo] = useState('1');
  const [expectedDays, setExpectedDays] = useState('');
  const [adherence, setAdherence] = useState<
    Readonly<Record<string, { applicable: boolean; value: string; structural_missing: boolean }>>
  >(() =>
    Object.fromEntries(
      ADHERENCE_KEYS.map((k) => [k, { applicable: true, value: '', structural_missing: false }])
    )
  );
  const [moduleScoresJson, setModuleScoresJson] = useState('{}');
  const [bandTrendNote, setBandTrendNote] = useState('');
  const [thresholdVersion, setThresholdVersion] = useState('');

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

  /** 界面侧核对：各项之和（**仅供核对，不作为提交值**）。 */
  const sum = scores.reduce((acc, s) => acc + (s.trim() === '' ? 0 : Number(s) || 0), 0);
  const filled = scores.filter((s) => s.trim() !== '').length;

  return (
    <Page title="量表与评估" roleLabel={roleLabel}>
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

      {/* ---------------- C1 题库拉取 ---------------- */}
      <Card
        title="C1 题库拉取"
        hint="按分龄组 + 维度取题组。age_group 为契约必填项；取回的题组是 C2 的「同源题组锁定」依据。"
      >
        <Field label="分龄组（age_group，契约必填 —— 8 档枚举）">
          <select style={inputStyle} value={ageGroup} onChange={(e) => setAgeGroup(e.target.value)}>
            <option value="">（请选择）</option>
            {AGE_GROUPS.map((g) => (
              <option key={g} value={g}>
                {g}
              </option>
            ))}
          </select>
        </Field>
        <Field label="维度（dimension，可选 —— 7 维枚举）">
          <select style={inputStyle} value={dimension} onChange={(e) => setDimension(e.target.value)}>
            <option value="">（不限）</option>
            {DIMENSIONS.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !ageGroup}
          onClick={() =>
            run('拉取题库', async () => {
              const items = await listScaleItemBanks(role, {
                age_group: ageGroup,
                ...(dimension ? { dimension } : {}),
              });
              setBanks(items ?? []);
              setPicked(null);
              return items;
            })
          }
        >
          拉取题库
        </button>
        <div style={{ marginTop: SPACE.md }}>
          {banks === null ? (
            <Empty text="尚未拉取。" />
          ) : banks.length === 0 ? (
            <Empty text="该分龄组 / 维度下暂无题组。" />
          ) : (
            banks.map((b, i) => (
              <div
                key={`${b.bank_id ?? b.item_group_id ?? i}`}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: SPACE.md,
                  padding: `${SPACE.xs}px 0`,
                  borderBottom: `1px solid ${COLOR.surfaceAlt}`,
                }}
              >
                <span style={{ fontSize: FONT.sm, flex: 1 }}>
                  {b.item_group_id ?? b.scale_id ?? '（无 id）'} · {b.age_group ?? '—'} ·{' '}
                  {b.dimension ?? '—'}
                </span>
                <button
                  style={buttonGhostStyle}
                  onClick={() => {
                    // 把 C1 的结果**带入** C2 —— 不让人手抄 scale_id / item_group_id。
                    setPicked(b);
                    if (b.age_group) setAgeGroup(b.age_group);
                  }}
                >
                  用作本次评估题组
                </button>
              </div>
            ))
          )}
        </div>
        {picked ? (
          <p style={{ ...labelStyle, marginTop: SPACE.md, color: COLOR.ok }}>
            已锁定题组：scale_id={String(picked.scale_id ?? '—')} · item_group_id=
            {String(picked.item_group_id ?? '—')} · age_group_locked={String(picked.age_group ?? '—')}
            （C2 将引用这三项）
          </p>
        ) : null}
      </Card>

      {/* ---------------- C2 基线评估提交 ---------------- */}
      <Card
        title="C2 基线评估提交"
        hint="契约硬约束：dimension_scores 必须恰好 7 项；total_score 0~112。三项钥匙来自上一步 C1。"
      >
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={baselineCustomerId}
            onChange={(e) => setBaselineCustomerId(e.target.value)}
          />
        </Field>
        <Field label="题组钥匙（由 C1 带入，只读 —— 不做第二份权威）">
          <div style={{ display: 'flex', flexDirection: 'column', gap: SPACE.xs }}>
            <input style={inputStyle} value={String(picked?.scale_id ?? '')} readOnly placeholder="scale_id（先做 C1）" />
            <input
              style={inputStyle}
              value={String(picked?.item_group_id ?? '')}
              readOnly
              placeholder="item_group_id（同源题组锁定）"
            />
            <input
              style={inputStyle}
              value={String(picked?.age_group ?? '')}
              readOnly
              placeholder="age_group_locked"
            />
          </div>
        </Field>
        <Field label={`七个维度分（每项 0~16）—— 已填 ${filled}/7，当前合计 ${sum}（仅供核对）`}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: SPACE.xs }}>
            {DIMENSIONS.map((d, i) => (
              <div key={d} style={{ display: 'flex', gap: SPACE.sm, alignItems: 'center' }}>
                <span style={{ ...labelStyle, width: 160, flexShrink: 0 }}>{d}</span>
                <input
                  style={{ ...inputStyle, flex: 1 }}
                  value={scores[i]}
                  onChange={(e) =>
                    setScores((prev) => prev.map((v, j) => (j === i ? e.target.value : v)))
                  }
                  placeholder="0~16"
                />
              </div>
            ))}
          </div>
        </Field>
        <Field label="总分（total_score，0~112）—— 以你填的值为准，本页不自动覆盖">
          <input style={inputStyle} value={totalScore} onChange={(e) => setTotalScore(e.target.value)} />
        </Field>
        <Field label="测量操作人（measure_operator）">
          <input
            style={inputStyle}
            value={measureOperator}
            onChange={(e) => setMeasureOperator(e.target.value)}
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !baselineCustomerId.trim() || !picked || filled !== 7}
          onClick={() =>
            run('基线评估', async () => {
              const r = await submitBaselineAssessment(
                role,
                baselineCustomerId.trim(),
                {
                  scale_id: String(picked?.scale_id ?? ''),
                  item_group_id: String(picked?.item_group_id ?? ''),
                  age_group_locked: String(picked?.age_group ?? ''),
                  dimension_scores: scores.map((s) => Number(s) || 0),
                  total_score: Number(totalScore) || 0,
                  measure_operator: measureOperator.trim(),
                },
                newIdempotencyKey()
              );
              setBaseline(r ?? null);
              return r;
            })
          }
        >
          提交基线评估
        </button>
        {filled !== 7 ? (
          <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
            契约要求恰好 7 项（当前 {filled} 项）⇒ 按钮保持禁用。少填一项服务端会回
            5001，但让一线在点之前就看到原因，比事后再排查划算。
          </p>
        ) : null}
        {baseline ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="评估 ID" v={baseline.assessment_id} />
            <KV k="评估时间" v={baseline.assessed_at} />
            <KV
              k="基线结论"
              v={
                baseline.baseline_conclusion?.haozhuan ? (
                  <Badge text={baseline.baseline_conclusion.haozhuan} />
                ) : (
                  '—（未下发）'
                )
              }
            />
            <KV
              k="可迁移（migratable）"
              v={baseline.migratable === undefined ? '—（未下发）' : baseline.migratable ? '是' : '否'}
            />
          </div>
        ) : null}
      </Card>

      {/* ---------------- C3 评估详情 ---------------- */}
      <Card title="C3 评估详情" hint="按客户 + 评估 ID 读取一次评估的全部下发字段。">
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={detailCustomerId}
            onChange={(e) => setDetailCustomerId(e.target.value)}
          />
        </Field>
        <Field label="评估 ID（assessment_id）">
          <input
            style={inputStyle}
            value={assessmentId}
            onChange={(e) => setAssessmentId(e.target.value)}
            placeholder="基线评估返回的 assessment_id"
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !detailCustomerId.trim() || !assessmentId.trim()}
          onClick={() =>
            run('评估详情', async () => {
              const d = await getAssessment(role, detailCustomerId.trim(), assessmentId.trim());
              setDetail(d ?? null);
              return d;
            })
          }
        >
          读取详情
        </button>
        <div style={{ marginTop: SPACE.md }}>
          {detail === null ? (
            <Empty text="尚未读取。" />
          ) : (
            Object.entries(detail).map(([k, v]) => (
              <KV key={k} k={k} v={typeof v === 'object' ? JSON.stringify(v) : String(v)} />
            ))
          )}
        </div>
      </Card>

      {/* ---------------- C4 周期评估提交 ---------------- */}
      <Card
        title="C4 周期评估提交"
        hint="依从四维与 F1 同一形状（契约明文）；🛑 A2 永不参与 AS ⇒ 本页刻意不提供 A2 这一项。"
      >
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={cycleCustomerId}
            onChange={(e) => setCycleCustomerId(e.target.value)}
          />
        </Field>
        <Field label="周期评估主键（cycle_id，必填 —— 使 F1 能在同一行上补判定）">
          <input style={inputStyle} value={cycleId} onChange={(e) => setCycleId(e.target.value)} />
        </Field>
        <Field label="序号（sequence_no，第 N 次评估，每 7 次触发判定）">
          <input style={inputStyle} value={sequenceNo} onChange={(e) => setSequenceNo(e.target.value)} />
        </Field>
        <Field label="应填报天数（expected_days，可选）">
          <input style={inputStyle} value={expectedDays} onChange={(e) => setExpectedDays(e.target.value)} />
        </Field>
        <Field label="依从维度（A1 / A3 / A4 —— A2 不参与 AS，故此处无 A2）">
          <div style={{ display: 'flex', flexDirection: 'column', gap: SPACE.sm }}>
            {ADHERENCE_KEYS.map((k) => {
              const a = adherence[k];
              return (
                <div key={k} style={{ display: 'flex', gap: SPACE.md, alignItems: 'center', flexWrap: 'wrap' }}>
                  <span style={{ ...labelStyle, width: 40 }}>{k}</span>
                  <label style={{ fontSize: FONT.sm, color: COLOR.text }}>
                    <input
                      type="checkbox"
                      checked={a.applicable}
                      onChange={(e) =>
                        setAdherence((prev) => ({
                          ...prev,
                          [k]: { ...prev[k], applicable: e.target.checked },
                        }))
                      }
                    />{' '}
                    适用
                  </label>
                  <input
                    style={{ ...inputStyle, width: 100 }}
                    value={a.value}
                    onChange={(e) =>
                      setAdherence((prev) => ({ ...prev, [k]: { ...prev[k], value: e.target.value } }))
                    }
                    placeholder="值"
                  />
                  <label style={{ fontSize: FONT.sm, color: COLOR.text }}>
                    <input
                      type="checkbox"
                      checked={a.structural_missing}
                      onChange={(e) =>
                        setAdherence((prev) => ({
                          ...prev,
                          [k]: { ...prev[k], structural_missing: e.target.checked },
                        }))
                      }
                    />{' '}
                    结构性缺失
                  </label>
                </div>
              );
            })}
          </div>
        </Field>
        <Field label="模块分（module_scores，M1–M5 各 0–16，JSON 对象）">
          <textarea
            style={{ ...inputStyle, minHeight: 80, fontFamily: 'monospace' }}
            value={moduleScoresJson}
            onChange={(e) => setModuleScoresJson(e.target.value)}
          />
        </Field>
        <Field label="手环趋势备注（band_trend_note，可选）">
          <input
            style={inputStyle}
            value={bandTrendNote}
            onChange={(e) => setBandTrendNote(e.target.value)}
          />
        </Field>
        <Field label="口径版本断言位（threshold_version，可空 = 服务端填）">
          <input
            style={inputStyle}
            value={thresholdVersion}
            onChange={(e) => setThresholdVersion(e.target.value)}
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !cycleCustomerId.trim() || !cycleId.trim()}
          onClick={() =>
            run('周期评估', () => {
              let moduleScores: Record<string, number>;
              try {
                moduleScores = JSON.parse(moduleScoresJson) as Record<string, number>;
              } catch {
                throw new Error('JSON 解析失败：请检查 module_scores 的写法。');
              }
              const dims: Record<string, { applicable: boolean; value: number; structural_missing: boolean }> =
                {};
              for (const k of ADHERENCE_KEYS) {
                const a = adherence[k];
                dims[k] = {
                  applicable: a.applicable,
                  value: Number(a.value) || 0,
                  structural_missing: a.structural_missing,
                };
              }
              const bodyAdherence: Adherence = {
                ...(expectedDays.trim() ? { expected_days: Number(expectedDays) } : {}),
                dimensions: dims,
              };
              return submitCycleAssessment(
                role,
                cycleCustomerId.trim(),
                {
                  cycle_id: cycleId.trim(),
                  sequence_no: Number(sequenceNo) || 1,
                  adherence: bodyAdherence,
                  ...(expectedDays.trim() ? { expected_days: Number(expectedDays) } : {}),
                  module_scores: moduleScores,
                  ...(bandTrendNote.trim() ? { band_trend_note: bandTrendNote.trim() } : {}),
                  ...(thresholdVersion.trim() ? { threshold_version: thresholdVersion.trim() } : {}),
                },
                newIdempotencyKey()
              );
            })
          }
        >
          提交周期评估
        </button>
        <p style={{ ...labelStyle, marginTop: SPACE.md }}>
          F1 判定结论需要 C4 的 <code>cycle_id</code> 作为路径参数 —— 提交后把它带到
          「专属动作」页的 F1 卡片（契约逐字：「周期评估主键（必填 —— 使 F1 能在同一行上补判定）」）。
        </p>
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