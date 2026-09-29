/**
 * 端 A · 页面：稽核（F3 / F4）
 * ============================================================================
 *
 * 🛑 本页是端 A **行级范围的契约声明落点**
 * ---------------------------------------------------------------------------
 * 契约里逐条写 `x-row-scope` 的**只有两个端点**：F3 `listAuditSignals` 与
 * F4 `getAuditCoverage`。两条逐字：
 *   · F3：「门店负责人仅本店、区域督导仅辖区、总部全量；**门店与加盟商完全不可见**」
 *   · F4：「卡片可给门店（仅本店、三数同显）；**告警动作归 P1-04、对门店不可见**」
 *
 * ⇒ 这两条把端 A 的真实边界说清楚了：**不是"admin 能看什么"，而是"哪个 admin 档位
 *    能看多少行"**。故本页必须：
 *   1. 把当前行级范围**显示出来**（否则使用者会以为"查不到 = 不存在"）；
 *   2. **不筛行、不做"仅本店"的本地过滤**（本地过滤 = 造第二个裁剪点）；
 *   3. 把契约对 F4 的"三数同显"要求如实呈现（三个数要一起出现，不能只显示一个）。
 *
 * 🛑 F4 的 `decidable_coverage_rate` 分母是**已结课**，不是全部客户
 * ---------------------------------------------------------------------------
 * 契约逐字「可判定覆盖率」，字段名是 `denominator_closed_courses`。
 * 把它读成"全部客户"会让覆盖率显得很低，进而触发错误的结论。
 * 故本页把分母**显式写出**，并说明它是什么。
 */

import { useState } from 'react';
import { describe } from '../services/errors';
import { contractNoteOf, getAuditCoverage, listAuditSignals, type AuditCoverage } from '../services/domain';
import {
  buttonGhostStyle,
  Card,
  Empty,
  ErrorBar,
  KV,
  labelStyle,
  Page,
  ScopeNote,
} from '../ui/components';
import { COLOR, FONT, SPACE } from '../ui/tokens';

export default function AuditPage({ roleLabel, scopeText }: { roleLabel: string; scopeText: string }) {
  const [signals, setSignals] = useState<readonly Record<string, unknown>[] | null>(null);
  const [coverage, setCoverage] = useState<AuditCoverage | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  const f3 = contractNoteOf('listAuditSignals');
  const f4 = contractNoteOf('getAuditCoverage');

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
    <Page title="稽核" roleLabel={roleLabel}>
      {f3?.rowScoped ? (
        <ScopeNote text={f3.rowScope ?? ''} hint="契约 x-row-scope@F3 逐字声明。行级范围由服务端执行，本页不筛行。" />
      ) : (
        <ScopeNote text={scopeText} hint="当前行级范围来自 A2。" />
      )}
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
      {msg ? <div style={{ ...labelStyle, marginBottom: SPACE.md, color: COLOR.ok }}>✓ {msg}</div> : null}

      <Card title="F3 稽核信号列表" hint="契约：门店负责人仅本店、区域督导仅辖区、总部全量；门店与加盟商完全不可见。">
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('F3 读取信号', async () => {
            setSignals((await listAuditSignals({ page: 1, page_size: 50 })) ?? null);
          })}
        >
          F3 读取
        </button>
        {signals ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="返回条数" v={String(signals.length)} />
            {signals.length === 0 ? (
              <Empty text="（范围内无信号 —— 这是范围结果，不代表全租户无信号）" />
            ) : (
              signals.map((s, i) => (
                <pre
                  key={String(s.signal_id ?? i)}
                  style={{ fontSize: FONT.xs, background: COLOR.surfaceAlt, padding: SPACE.sm, borderRadius: 4, overflowX: 'auto' }}
                >
                  {JSON.stringify(s, null, 2)}
                </pre>
              ))
            )}
          </div>
        ) : null}
      </Card>

      <Card title="F4 稽核覆盖率" hint="契约：卡片可给门店（仅本店、三数同显）；告警动作归 P1-04、对门店不可见。">
        {f4?.rowScoped ? (
          <ScopeNote text={f4.rowScope ?? ''} hint="契约 x-row-scope@F4 逐字声明。" />
        ) : null}
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('F4 读取覆盖率', async () => {
            setCoverage((await getAuditCoverage()) ?? null);
          })}
        >
          F4 读取
        </button>
        {coverage ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="ec_count" v={coverage.ecc_count === undefined ? '—' : String(coverage.ecc_count)} />
            <KV
              k="分母：已结课数"
              v={coverage.denominator_closed_courses === undefined
                ? '—'
                : String(coverage.denominator_closed_courses)}
            />
            <KV
              k="可判定覆盖率"
              v={coverage.decidable_coverage_rate === undefined
                ? '—'
                : String(coverage.decidable_coverage_rate)}
            />
            <KV
              k="A3 可观测性"
              v={coverage.a3_observability ? '（已下发）' : '—（未下发）'}
            />
            <p style={{ ...labelStyle, marginTop: SPACE.sm, color: COLOR.warn }}>
              🛑 覆盖率的分母是**已结课**（`denominator_closed_courses`），**不是全部客户**。
              把分母读成"全部客户"会让覆盖率显得很低，进而触发错误的结论。
              契约还要求"**三数同显**"—— 分子 / 分母 / 比率须一起呈现，不得只显示比率。
            </p>
          </div>
        ) : (
          <div style={{ marginTop: SPACE.sm }}><Empty text="尚未读取。" /></div>
        )}
      </Card>

      <Card title="契约侧提示">
        <p style={{ ...labelStyle, margin: 0, fontSize: FONT.xs }}>
          当前角色 {roleLabel} · 本域 2 个端点是端 A **唯一逐条声明 x-row-scope** 的端点 ·
          F3/F4 均只授予 admin（端点级无分叉）。
          「告警动作归 P1-04」是**跨行引用**：该能力不在本端 39 个端点内。
        </p>
      </Card>
    </Page>
  );
}