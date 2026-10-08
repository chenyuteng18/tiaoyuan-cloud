/**
 * 端 A · 页面：结算对账（契约外能力面 E1）
 * ============================================================================
 *
 * 🛑 本页打的是**契约外**端点，这一点必须在界面上说清楚
 * ---------------------------------------------------------------------------
 * 结算落账 / 对账报表**不在**冻结契约的 45 个 `operationId` 里（后端把它们登记在
 * `EndpointCoverageLedgerTest.INTERNAL_ENDPOINTS` 台账，共 32 条），故：
 *   · 它们**不受**契约兼容性承诺保护（字段可能随实现调整）；
 *   · 它们是**总部专属**（PRD M5 + `@RequireOrgLevel(HEADQUARTERS)` + V24 RLS）；
 *   · 界面上必须写明这一点 —— 否则使用者会把它当契约承诺的功能去对接外部流程。
 *
 * 🛑 本页把「预演」与「落账」分成两个显式动作（照抄后端的设计意图）
 * ---------------------------------------------------------------------------
 * `preview` 只算不落、可反复；`commit` 有幂等键、有快照、有审计，是严肃动作。
 * 界面上刻意**不提供**"一键预演并落账"：
 *   · 结算结论落账前必须**被人看过一眼**（分摊表就在预演卡里）；
 *   · 服务端会**重算比对**，篡改/臆造的结论一律 422 —— 与其让用户撞 422，
 *     不如让"先预演、再落账"成为唯一的操作路径。
 *
 * 🛑 `ALREADY_EXISTS` 不是失败（本页最容易被写错的一处）
 * ---------------------------------------------------------------------------
 * 幂等命中 = **同输入此前已落过账**，服务端返回既有单据 ID。若把它渲染成红色失败，
 * 操作者的自然反应是"再点一次" —— 只会产生更多重放记录。故本页对它用**中性色**
 * 并逐字写出"本次未新建单据"。
 *
 * 🛑 双命名口径（preview camelCase / commit snake_case）在本页是**透明的**
 * ---------------------------------------------------------------------------
 * 口径差异收在 `services/ops.ts` 一层；本页只构造业务输入（结案店 / 各店次数 / ECC / 损失），
 * 不碰字段名 —— 页面若各自拼请求体，就会出现"这个页面记得、那个页面忘了"的分叉。
 */

import { useState } from 'react';
import { describe } from '../services/errors';
import {
  commitSettlement,
  exportSettlementCsv,
  getSettlementPayload,
  listSettlementStatements,
  previewSettlement,
  settlementCsvUrl,
  type CommitOutcome,
  type SettlementResult,
  type SettlementStatementRow,
} from '../services/ops';
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
  textareaStyle,
} from '../ui/components';
import { COLOR, FONT, SPACE } from '../ui/tokens';

/**
 * 把「每行 `门店ID=次数`」的文本解析成 `Record<storeId, number>`。
 *
 * 🛑 解析规则刻意**严格**（三处都对应一条本仓纪律）：
 *   ① 空行跳过，但**非空且不合法的行一律抛** —— 不得静默丢弃：丢一行等于少算一家店的次数，
 *      而服务端会据此算出**另一个** request_hash，"少算"会静默变成"另一张账"；
 *   ② **不得补 0**：次数必须显式写出来（`ST-A=0` 要人自己写），
 *      这与后端 `visitsByStore 不得为空`/`缺失不得补 0` 同一条纪律；
 *   ③ 同店重复出现 ⇒ 抛：合并会掩盖输入错误（两次写了同一家店，通常是想写两家）。
 */
export function parseVisitsByStore(text: string): Record<string, number> {
  const out: Record<string, number> = {};
  const bad: string[] = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const m = /^([^=\s]+)\s*=\s*(\d+)$/.exec(line);
    if (!m) {
      bad.push(line);
      continue;
    }
    const [, storeId, count] = m;
    if (Object.prototype.hasOwnProperty.call(out, storeId)) {
      throw new Error(`门店 ${storeId} 出现了两次 —— 请合并为一行（重复行通常是输入错误，不自动相加）`);
    }
    out[storeId] = Number(count);
  }
  if (bad.length) {
    throw new Error(`以下行不是「门店ID=次数」形态，已拒绝提交（不得静默丢弃）：${bad.join(' / ')}`);
  }
  if (Object.keys(out).length === 0) {
    throw new Error('至少要写一家门店的次数（服务端：次数缺失不得补 0，visitsByStore 为空即拒）');
  }
  return out;
}

export default function SettlementPage({ roleLabel }: { roleLabel: string }) {
  const [period, setPeriod] = useState('');
  const [closingStoreId, setClosingStoreId] = useState('');
  const [visitsText, setVisitsText] = useState('');
  const [ecc, setEcc] = useState('');
  const [loss, setLoss] = useState('');

  const [preview, setPreview] = useState<SettlementResult | null>(null);
  const [commit, setCommit] = useState<CommitOutcome | null>(null);
  const [rows, setRows] = useState<readonly SettlementStatementRow[] | null>(null);
  const [payload, setPayload] = useState<Record<string, unknown> | null>(null);
  const [csvNote, setCsvNote] = useState<string | null>(null);
  const [statementId, setStatementId] = useState('');

  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

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

  /** 预演输入与落账输入**同源构造**（B-1 记过的失效模式：两处各拼一份必然漂移）。 */
  function buildInputs() {
    return {
      closingStoreId: closingStoreId.trim(),
      visitsByStore: parseVisitsByStore(visitsText),
      eccUnits: Number(ecc),
      lossYuan: Number(loss),
    };
  }

  return (
    <Page title="结算对账" roleLabel={roleLabel}>
      <ScopeNote
        text="总部专属（M5）"
        hint="全部结算端点均为总部档位专属；门店 / 加盟商调用由服务端 403（界面不做端侧判断）。"
      />
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
      {msg ? <div style={{ ...labelStyle, marginBottom: SPACE.md, color: COLOR.ok }}>✓ {msg}</div> : null}

      <Card
        title="本页为什么长这样（三条必须如实告知的事）"
        hint="以下三点都会影响使用者该怎么用它 —— 不写出来就是界面替契约做了承诺。"
      >
        <p style={{ margin: `0 0 ${SPACE.sm}px`, fontSize: FONT.md, color: COLOR.text }}>
          ① <strong>这些端点不在冻结契约里</strong>（属后端登记的「自建能力面」台账）。
          它们<strong>不享受</strong>契约兼容性承诺：字段可随实现调整，界面不得据其设计外部对接流程。
        </p>
        <p style={{ margin: `0 0 ${SPACE.sm}px`, fontSize: FONT.md, color: COLOR.text }}>
          ② <strong>「预演」与「落账」是两个动作</strong>：预演只算不落、可反复；
          落账会写账本（幂等键 + 不可变快照 + 审计）。故本页不提供"一键预演并落账"。
        </p>
        <p style={{ margin: 0, fontSize: FONT.md, color: COLOR.text }}>
          ③ <strong>落账前服务端会重算比对</strong>：结论与输入不一致一律 422（业务码 5001）。
          这是刻意的 —— 账本只收"可复算"的账。
        </p>
      </Card>

      <Card title="输入（预演与落账同源）" hint="结案门店 = ECC 的主计方；其它门店按服务次数参与分摊。">
        <div style={{ display: 'flex', gap: SPACE.md, flexWrap: 'wrap' }}>
          <label style={{ ...labelStyle, flex: '1 1 160px' }}>
            结算周期 period（YYYY-MM）
            <input
              style={{ ...inputStyle, marginTop: SPACE.xs }}
              value={period}
              onChange={(e) => setPeriod(e.target.value)}
              placeholder="2026-10"
            />
          </label>
          <label style={{ ...labelStyle, flex: '1 1 200px' }}>
            结案门店 closing_store_id
            <input
              style={{ ...inputStyle, marginTop: SPACE.xs }}
              value={closingStoreId}
              onChange={(e) => setClosingStoreId(e.target.value)}
              placeholder="store uuid"
            />
          </label>
          <label style={{ ...labelStyle, flex: '1 1 120px' }}>
            应分 ECC 总量
            <input
              style={{ ...inputStyle, marginTop: SPACE.xs }}
              value={ecc}
              onChange={(e) => setEcc(e.target.value)}
              placeholder="20.00"
            />
          </label>
          <label style={{ ...labelStyle, flex: '1 1 120px' }}>
            应摊退款损失（元）
            <input
              style={{ ...inputStyle, marginTop: SPACE.xs }}
              value={loss}
              onChange={(e) => setLoss(e.target.value)}
              placeholder="8.00"
            />
          </label>
        </div>
        <label style={{ ...labelStyle, display: 'block', marginTop: SPACE.md }}>
          各店服务次数（每行 `门店ID=次数`；**不得留空补 0**，重复门店会被拒）
          <textarea
            style={{ ...textareaStyle, marginTop: SPACE.xs }}
            value={visitsText}
            onChange={(e) => setVisitsText(e.target.value)}
            placeholder={'11111111-1111-1111-1111-111111111111=9\n22222222-2222-2222-2222-222222222222=1'}
          />
        </label>
      </Card>

      <Card
        title="① 预演（只算不落）"
        hint="服务端按次数占比拆分 ECC 与退款损失。other_store_ratio 是【判定依据】，不是门店损益率指标。"
      >
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('预演', async () => {
            setPreview((await previewSettlement(buildInputs())) ?? null);
            setCommit(null);
          })}
        >
          预演
        </button>
        {preview ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="是否触发拆分" v={preview.splitApplied ? '是' : '否（结案店独占）'} />
            <KV k="他店次数占比" v={`${preview.otherStoreVisitRatio}（判定依据，非看板指标）`} />
            <KV k="总服务次数" v={String(preview.totalVisits)} />
            {preview.allocations.length === 0 ? (
              <Empty text="（无分摊行 —— 请核对输入；服务端不会补 0 造出空行）" />
            ) : (
              <table style={{ width: '100%', marginTop: SPACE.md, borderCollapse: 'collapse', fontSize: FONT.sm }}>
                <thead>
                  <tr>
                    {['门店', '次数', 'ECC 分摊', '损失分摊', '该店触发拆分'].map((h) => (
                      <th key={h} style={{ textAlign: 'left', padding: `${SPACE.xs}px`, borderBottom: `1px solid ${COLOR.border}` }}>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {preview.allocations.map((a) => (
                    <tr key={a.storeId}>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{a.storeId}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{a.visitCount}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{a.eccShare}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{a.lossShare}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{a.splitApplied ? '是' : '否'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ) : null}
      </Card>

      <Card
        title="② 落账（写账本）"
        hint="必须基于上一步的预演结论。同输入重复落账 = 幂等命中，返回既有单据（不覆盖、不产第二份）。"
      >
        <button
          style={buttonGhostStyle}
          disabled={busy || !preview}
          onClick={() => run('落账', async () => {
            if (!preview) return;
            const inputs = buildInputs();
            const outcome = await commitSettlement({
              period: period.trim(),
              closing_store_id: inputs.closingStoreId,
              visits_by_store: inputs.visitsByStore,
              ecc_units: inputs.eccUnits,
              loss_yuan: inputs.lossYuan,
              result: preview,
            });
            setCommit(outcome ?? null);
          })}
        >
          落账（需先预演）
        </button>
        {commit ? (
          <div style={{ marginTop: SPACE.md }}>
            <div style={{ marginBottom: SPACE.sm }}>
              <Badge text={commit.status} tone={commit.status === 'CREATED' ? 'ok' : 'info'} />
            </div>
            <KV k="statement_id" v={commit.statement_id} />
            <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
              {commit.status === 'ALREADY_EXISTS'
                ? '🛑 ALREADY_EXISTS = 幂等命中：同输入此前已落过账，本次**未新建**单据。'
                  + '这不是失败，**请勿重复点击**（重复点击只会增加重放记录）。'
                : 'CREATED = 新落一账。快照已冻结，后续算法演进不影响本单历史值。'}
            </p>
          </div>
        ) : null}
      </Card>

      <Card
        title="③ 周期对账清单"
        hint="窄记录（不含 payload 快照）。total 之外没有分页 —— 这是后端为对账面刻意保持的形态。"
      >
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('读取对账清单', async () => {
            setRows((await listSettlementStatements(period.trim())) ?? null);
          })}
        >
          按周期读取
        </button>
        {rows ? (
          <div style={{ marginTop: SPACE.md }}>
            {rows.length === 0 ? (
              <Empty text="该周期无结算单（这是查询结果，不等于周期内没有跨店服务）" />
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: FONT.sm }}>
                <thead>
                  <tr>
                    {['单据', '结案店', '店数', '次数', 'ECC', '损失', '拆分', '他店占比', '落账人'].map((h) => (
                      <th key={h} style={{ textAlign: 'left', padding: SPACE.xs, borderBottom: `1px solid ${COLOR.border}` }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.statement_id}>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}`, fontFamily: 'monospace' }}>
                        {r.statement_id.slice(0, 8)}…
                      </td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}`, fontFamily: 'monospace' }}>
                        {r.closing_store_id.slice(0, 8)}…
                      </td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{r.stores_involved}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{r.visits_total}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{r.ecc_units}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{r.loss_yuan}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{r.split_applied ? '是' : '否'}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{r.other_store_ratio}</td>
                      <td style={{ padding: SPACE.xs, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>{r.created_by ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ) : null}
      </Card>

      <Card
        title="④ CSV 导出（非信封响应）"
        hint="RFC 4180；列序冻结 —— 对账方按列序做机器解析。文件名取自服务端 Content-Disposition。"
      >
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('导出 CSV', async () => {
            const { filename, content } = await exportSettlementCsv(period.trim());
            const blob = new Blob([content], { type: 'text/csv;charset=UTF-8' });
            const href = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = href;
            a.download = filename;
            a.click();
            URL.revokeObjectURL(href);
            setCsvNote(`${filename} · ${content.length} 字符 · ${content.split('\r\n').length - 2} 行数据`);
          })}
        >
          导出
        </button>
        {csvNote ? (
          <p style={{ ...labelStyle, marginTop: SPACE.sm, color: COLOR.ok }}>✓ {csvNote}</p>
        ) : null}
        <p style={{ ...labelStyle, marginTop: SPACE.sm, fontFamily: 'monospace' }}>
          实际请求 URL：{settlementCsvUrl(period.trim() || '<period>')}
        </p>
        <p style={{ ...labelStyle, marginTop: SPACE.xs }}>
          🛑 把 URL 显示出来是刻意的：若前缀漏拼 <code>/api/v1</code>（本仓第 56 条那一类），
          在本页即可看见，而不必等真机 404。
        </p>
      </Card>

      <Card
        title="⑤ 单据快照定点读（不可变）"
        hint="读的是落账当时那一次的结论 —— 算法演进后历史报表仍读快照，不重算。"
      >
        <input
          style={inputStyle}
          value={statementId}
          onChange={(e) => setStatementId(e.target.value)}
          placeholder="statement_id（UUID）"
        />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy || !statementId.trim()}
          onClick={() => run('读取快照', async () => {
            setPayload((await getSettlementPayload(statementId.trim())) ?? null);
          })}
        >
          读取快照
        </button>
        {payload ? (
          <pre
            style={{
              marginTop: SPACE.md,
              padding: SPACE.md,
              background: COLOR.surfaceAlt,
              borderRadius: 4,
              fontSize: FONT.xs,
              overflow: 'auto',
              maxHeight: 320,
            }}
          >
            {JSON.stringify(payload, null, 2)}
          </pre>
        ) : null}
      </Card>
    </Page>
  );
}
