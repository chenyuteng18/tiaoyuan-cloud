/**
 * 端 A · 页面：门店与合作（A3 + B1/B2/B3）
 * ============================================================================
 *
 * 🛑 本页是**行级范围**的界面落点（端 A 两条真实边界之一）
 * ---------------------------------------------------------------------------
 * 契约 `x-row-scope` 只声明在 F3 / F4 两个端点上，但**行级范围的影响面更大**：
 * A3 门店列表、B4 客户详情同样按范围裁剪。（契约只对 F3/F4 逐条写明，
 * 对其它端点未逐条声明 —— 这是**如实登记的缺口**，本页不替契约扩大声明。）
 *
 * 🛑 建档链路的三道硬门禁（B1 → B2 → B3）
 * ---------------------------------------------------------------------------
 * 契约逐字：
 *   · B1 禁忌筛查：**硬门禁①**（B2 会校验其 `result=通过`）；
 *   · B2 建档：`screening_id` 必须是**已通过**的筛查记录；
 *   · B3 签署知情同意书。
 * 故本页把三者**放在同一张卡里按顺序呈现** —— 拆开会让使用者跳过 B1 直接建档，
 * 然后拿到一个 5001，误以为是系统故障。
 */

import { useState } from 'react';
import { newIdempotencyKey } from '../api/client';
import { describe } from '../services/errors';
import {
  createCustomer,
  createScreeningRecord,
  listStores,
  signConsent,
  type CustomerCreateResult,
  type ScreeningResult,
  type StoreList,
} from '../services/domain';
import {
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
} from '../ui/components';
import { COLOR, FRANCHISE_TYPE_LABEL, FONT, SPACE } from '../ui/tokens';

export default function StorePage({ roleLabel, scopeText }: { roleLabel: string; scopeText: string }) {
  const [stores, setStores] = useState<StoreList | null>(null);
  const [screening, setScreening] = useState<ScreeningResult | null>(null);
  const [created, setCreated] = useState<CustomerCreateResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  // B1 筛查表单
  const [custIdRaw, setCustIdRaw] = useState('');
  const [operatorId, setOperatorId] = useState('');
  const [flags, setFlags] = useState('{}');
  // B2 建档表单
  const [name, setName] = useState('');
  const [gender, setGender] = useState<'男' | '女'>('男');
  const [age, setAge] = useState('35');
  const [phone, setPhone] = useState('');

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
    <Page title="门店与合作" roleLabel={roleLabel}>
      <ScopeNote text={scopeText} hint="A3 门店列表的行数由服务端按行级范围裁剪；本页不筛行。" />
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
      {msg ? <div style={{ ...labelStyle, marginBottom: SPACE.md, color: COLOR.ok }}>✓ {msg}</div> : null}

      <Card title="A3 门店列表" hint="🛑 total 是【范围内】总数，不是全租户总数 —— 不可用它推断系统里有多少店。">
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('读取门店', async () => {
            setStores((await listStores(1, 50)) ?? null);
          })}
        >
          读取门店
        </button>
        {stores ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="返回行数" v={`${stores.items.length} / total ${stores.total}`} />
            {stores.items.length === 0 ? <Empty text="（范围内无门店 —— 这是范围结果，不代表租户无门店）" /> : null}
            {stores.items.map((s) => (
              <KV key={s.store_id} k={s.store_id} v={`${s.name}（${FRANCHISE_TYPE_LABEL[s.franchise_type] ?? s.franchise_type}）`} />
            ))}
          </div>
        ) : null}
      </Card>

      <Card
        title="建档链路：B1 筛查 → B2 建档 → B3 同意书"
        hint="三道硬门禁按顺序。跳过 B1 直接建档会被服务端以 5001 拒（B2 校验筛查 result=通过）。"
      >
        <div style={{ ...labelStyle, marginBottom: SPACE.sm }}>B1 · 禁忌筛查提交（硬门禁①）</div>
        <input style={inputStyle} value={custIdRaw} onChange={(e) => setCustIdRaw(e.target.value)} placeholder="customer_id" />
        <input
          style={{ ...inputStyle, marginTop: SPACE.sm }}
          value={operatorId}
          onChange={(e) => setOperatorId(e.target.value)}
          placeholder="operator_id（服务端会从 token 覆写，仍须传）"
        />
        <input
          style={{ ...inputStyle, marginTop: SPACE.sm }}
          value={flags}
          onChange={(e) => setFlags(e.target.value)}
          placeholder='items_json（契约只给 4 个键名：pregnancy/acute/risk_history/nonmedical_disclosed；取值形态未定义）'
        />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('B1 筛查', async () => {
            const r = await createScreeningRecord({
              customer_id: custIdRaw.trim(),
              items_json: JSON.parse(flags) as Record<string, unknown>,
              operator_id: operatorId.trim(),
            }, newIdempotencyKey());
            setScreening(r ?? null);
          })}
        >
          B1 提交筛查
        </button>
        {screening ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="screening_id" v={screening.screening_id} />
            <KV k="结论" v={screening.result} />
            <KV k="提交时间" v={screening.submitted_at} />
            {screening.result !== '通过' ? (
              <p style={{ ...labelStyle, color: COLOR.danger }}>
                🛑 结论非「通过」⇒ 不能用于 B2 建档（契约：B2 校验其 result=通过）。
              </p>
            ) : null}
          </div>
        ) : null}

        <hr style={{ border: 'none', borderTop: `1px solid ${COLOR.border}`, margin: `${SPACE.lg}px 0` }} />

        <div style={{ ...labelStyle, marginBottom: SPACE.sm }}>B2 · 建档（需上一步 screening_id）</div>
        <input style={inputStyle} value={name} onChange={(e) => setName(e.target.value)} placeholder="姓名" />
        <select style={{ ...selectStyle, marginTop: SPACE.sm }} value={gender} onChange={(e) => setGender(e.target.value as '男' | '女')}>
          <option value="男">男</option>
          <option value="女">女</option>
        </select>
        <input
          style={{ ...inputStyle, marginTop: SPACE.sm }}
          value={age}
          onChange={(e) => setAge(e.target.value)}
          placeholder="年龄（契约：1~119）"
        />
        <input
          style={{ ...inputStyle, marginTop: SPACE.sm }}
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          placeholder="手机号（租户内唯一，跨店识别键）"
        />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('B2 建档', async () => {
            if (!screening || screening.result !== '通过') {
              throw new Error('B2 前置未满足：请先完成 B1 筛查且结论为「通过」（契约硬门禁）。');
            }
            const r = await createCustomer({
              name: name.trim(),
              gender,
              age: Number(age),
              phone: phone.trim(),
              screening_id: screening.screening_id,
            }, newIdempotencyKey());
            setCreated(r ?? null);
          })}
        >
          B2 建档
        </button>
        {created ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="customer_id" v={created.customer_id} />
            <KV k="状态" v={created.status} />
            <KV k="归属门店" v={created.owner_store_id ?? '—'} />
            <KV k="服务门店" v={created.serving_store_id ?? '—'} />
            <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
              🛑 `status` 是 5 值**粗粒度派生聚合态**，不是服务主状态机（14 态）—— 界面不得当状态机用。
            </p>
          </div>
        ) : null}

        <hr style={{ border: 'none', borderTop: `1px solid ${COLOR.border}`, margin: `${SPACE.lg}px 0` }} />

        <div style={{ ...labelStyle, marginBottom: SPACE.sm }}>B3 · 签署知情同意书</div>
        <button
          style={buttonGhostStyle}
          disabled={busy || !created}
          onClick={() => run('B3 签同意书', async () => {
            if (!created) return;
            await signConsent(created.customer_id, {}, newIdempotencyKey());
          })}
        >
          B3 签署（需先建档）
        </button>
        <p style={{ ...labelStyle, marginTop: SPACE.sm, fontSize: FONT.xs }}>
          ⚠️ 契约对 B3 **未给命名 schema**，入参按 operation 内联定义传 —— 如实登记的缺口，
          本页不替契约编字段名。
        </p>
      </Card>
    </Page>
  );
}