/**
 * 端 B · 页面：客户建档与档案（域 B 之 B1~B6）
 * ============================================================================
 *
 * 本页存在的理由（**实测缺口**，不是设计偏好）
 * ---------------------------------------------------------------------------
 * `services/domain.ts` 的 B 域六个函数（B1/B2/B3/B5/B6 + D2 同层）此前
 * **全部封装完毕但 `src/` 全域零调用** —— 即"函数写好了，从没接上界面"。
 * 调解一线打开 APP 看不到"建档"这件事，只能去用端 A 或找管理员。
 *
 * 这不是"少一个按钮"：B 域是**客户进入服务链路的入口**（建档 ⇒ 签同意书 ⇒
 * 评估 ⇒ 服务），它缺失时整条链路在端 B 侧不可用，而 tsc / vite / 既有门禁
 * **全部绿**（既有门禁只判"函数是否存在"，不判"是否被界面触达"）。
 *
 * 🛑 硬门禁的界面表达：B1 筛查必须先于 B2 建档
 * ---------------------------------------------------------------------------
 * 契约 `CustomerCreateRequest.screening_id` 逐字：「服务端校验其 result=通过」。
 * 故本页把流程做成**顺序的两步**（先筛查拿 `screening_id`，再建档引用它），
 * 并把上一步的 `screening_id` 自动带进下一步 —— 而不是给一个自由输入框让一线
 * 手抄一个 UUID（手抄必然出错，且出错后表现是"建档被拒但不知道为什么"）。
 *
 * 🛑 B3 的请求体字段名来自【控制器真实形状】（契约未声明 requestBody）
 * ---------------------------------------------------------------------------
 * 契约 B3 只有 parameters、**没有 requestBody**。唯一真相源是
 * `CustomerController.signConsent` 真实读取的键：
 *   · `auth_scope`       —— 数组，元素取 `ConsentAuthScope` 的码
 *                           （collect_basic / generate_advice / service_record / rights_ack）
 *   · `band_willingness` —— `BandWillingness` 的码（自愿佩戴 / 暂不佩戴）
 *   · `evidence_hash`    —— 证据哈希
 *   · `data_source`      —— `ConsentDataSource` 的码（self-report / device）
 * ⚠️ 自创字段名（如 `scope` / `willing`）**不会报任何错**：服务端只会收到
 *    一个陌生的键，四列 NOT NULL 全为 null ⇒ 表现为一次业务失败，而排查方向
 *    会跑偏到"客户数据有问题"。这与本仓第 50 条同族：**"写下的字段名"与
 *    "服务端真读的字段名"是两件事**，只能靠对照控制器证明。
 *
 * 🛑 已登记的缺口：B1 `items_json` 的取值形态契约未定义
 * ---------------------------------------------------------------------------
 * 契约逐字只给了**四个键名**（pregnancy / acute / risk_history /
 * nonmedical_disclosed），**没给每项的取值形态**（bool？枚举？未定义）。
 * 故本页按最自然的 bool 提交，并在界面上**如实标注这一点** ——
 * 不假装契约已经定义了它（假装会让下一个实现者以为有权威可依）。
 */

import { useState } from 'react';
import { newIdempotencyKey } from '../api/client';
import {
  createCustomer,
  createScreeningRecord,
  getIntakeProfile,
  patchIntakeProfile,
  signConsent,
  type CustomerCreateResult,
  type ScreeningResult,
} from '../services/domain';
import { describe } from '../services/errors';
import type { AppRole } from '../contract/access';
import { COLOR, FONT, SPACE } from '../ui/tokens';
import { Badge, buttonGhostStyle, Card, Empty, ErrorBar, inputStyle, KV, labelStyle, Page } from '../ui/components';

/** 提交结果三态（4002 幂等重放不是失败）。 */
type Outcome =
  | { kind: 'ok'; label: string; json: string }
  | { kind: 'replay'; label: string }
  | { kind: 'err'; text: string; traceId: string };

/** 四项授权 —— 码逐字取自 `ConsentAuthScope`（拒绝某项不影响签署，空集合亦合法）。 */
const AUTH_SCOPES: readonly { readonly code: string; readonly label: string }[] = Object.freeze([
  { code: 'collect_basic', label: '基础信息采集' },
  { code: 'generate_advice', label: '生成调理建议' },
  { code: 'service_record', label: '服务记录' },
  { code: 'rights_ack', label: '权利告知确认' },
]);

export default function IntakePage({
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

  // B1 筛查
  const [screenCustomerId, setScreenCustomerId] = useState('');
  const [flagPregnancy, setFlagPregnancy] = useState(false);
  const [flagAcute, setFlagAcute] = useState(false);
  const [flagRiskHistory, setFlagRiskHistory] = useState(false);
  const [flagNonmedical, setFlagNonmedical] = useState(false);
  const [screening, setScreening] = useState<ScreeningResult | null>(null);

  // B2 建档
  const [name, setName] = useState('');
  const [gender, setGender] = useState<'男' | '女'>('女');
  const [age, setAge] = useState('');
  const [phone, setPhone] = useState('');
  const [created, setCreated] = useState<CustomerCreateResult | null>(null);

  // B3 签同意书
  const [consentCustomerId, setConsentCustomerId] = useState('');
  const [scopes, setScopes] = useState<readonly string[]>([]);
  const [willingness, setWillingness] = useState('');
  const [dataSource, setDataSource] = useState('');
  const [evidenceHash, setEvidenceHash] = useState('');

  // B5 读取；B6 修订
  const [profileCustomerId, setProfileCustomerId] = useState('');
  const [profile, setProfile] = useState<Record<string, unknown> | null>(null);
  const [patchJson, setPatchJson] = useState('{}');

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
        });
      } else {
        setOutcome({ kind: 'ok', label, json: JSON.stringify(data, null, 2) });
      }
    } catch (e) {
      const d = describe(e);
      if (d.kind === 'replay') {
        setOutcome({ kind: 'replay', label });
      } else {
        setOutcome({ kind: 'err', text: d.text, traceId: d.traceId });
        if (d.kind === 'auth') onNeedRelogin();
      }
    } finally {
      setBusy(false);
    }
  }

  const toggleScope = (code: string) => {
    setScopes((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]));
  };

  return (
    <Page title="客户建档与档案" roleLabel={roleLabel}>
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
            <div style={{ fontSize: FONT.xs, marginTop: SPACE.xs }}>
              该请求此前已成功处理，服务端返回的是首次结果。
            </div>
          </div>
        ) : (
          <ErrorBar text={outcome.text} traceId={outcome.traceId} />
        )
      ) : null}

      {/* ---------------- B1 禁忌筛查（硬门禁①） ---------------- */}
      <Card
        title="B1 禁忌筛查（建档前置门禁）"
        hint="契约逐字：建档请求的 screening_id 由服务端校验其 result=通过。故必须先做这一步。"
      >
        <Field label="客户 ID（customer_id，服务端据此归属；也接受待建档客户的外部标识）">
          <input
            style={inputStyle}
            value={screenCustomerId}
            onChange={(e) => setScreenCustomerId(e.target.value)}
            placeholder="customer uuid"
          />
        </Field>
        <Field label="筛查项（items_json）—— 🛑 契约只给了这四个键名，未定义取值形态，本页按 bool 提交">
          <div style={{ display: 'flex', gap: SPACE.md, flexWrap: 'wrap' }}>
            <Check label="妊娠（pregnancy）" checked={flagPregnancy} onChange={setFlagPregnancy} />
            <Check label="急性期（acute）" checked={flagAcute} onChange={setFlagAcute} />
            <Check label="风险史（risk_history）" checked={flagRiskHistory} onChange={setFlagRiskHistory} />
            <Check
              label="非医疗自述（nonmedical_disclosed）"
              checked={flagNonmedical}
              onChange={setFlagNonmedical}
            />
          </div>
        </Field>
        <p style={{ ...labelStyle, marginBottom: SPACE.md }}>
          operator_id 契约要求必传，但逐字「服务端从 token 覆写」⇒ 本页传占位值，
          真实操作人由服务端依登录身份写入（不在这里假装由前端决定）。
        </p>
        <div style={{ display: 'flex', gap: SPACE.md, alignItems: 'center' }}>
          <button
            style={buttonGhostStyle}
            disabled={busy || !screenCustomerId.trim()}
            onClick={() =>
              run('禁忌筛查', async () => {
                const r = await createScreeningRecord(
                  role,
                  {
                    customer_id: screenCustomerId.trim(),
                    items_json: {
                      pregnancy: flagPregnancy,
                      acute: flagAcute,
                      risk_history: flagRiskHistory,
                      nonmedical_disclosed: flagNonmedical,
                    },
                    operator_id: '(服务端从 token 覆写)',
                  },
                  newIdempotencyKey()
                );
                setScreening(r ?? null);
                return r;
              })
            }
          >
            提交筛查
          </button>
          {screening ? (
            <span style={{ fontSize: FONT.sm }}>
              结果：
              <Badge
                text={screening.result}
                tone={screening.result === '通过' ? 'ok' : 'danger'}
              />
            </span>
          ) : null}
        </div>
      </Card>

      {/* ---------------- B2 建档 ---------------- */}
      <Card
        title="B2 客户建档"
        hint="screening_id 必须引用一次「通过」的筛查；本页自动带入上一步的结果，不让你手抄 UUID。"
      >
        <Field label="姓名（name）">
          <input style={inputStyle} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="性别（gender）">
          <select style={inputStyle} value={gender} onChange={(e) => setGender(e.target.value as '男' | '女')}>
            <option value="女">女</option>
            <option value="男">男</option>
          </select>
        </Field>
        <Field label="年龄（age，契约约束 1~119）">
          <input style={inputStyle} value={age} onChange={(e) => setAge(e.target.value)} placeholder="1~119" />
        </Field>
        <Field label="手机号（phone，租户内唯一 —— 跨店识别键）">
          <input style={inputStyle} value={phone} onChange={(e) => setPhone(e.target.value)} />
        </Field>
        <Field label="筛查记录（screening_id）">
          <input
            style={inputStyle}
            value={screening?.screening_id ?? ''}
            readOnly
            placeholder="请先在上一步完成 B1 筛查（通过后自动带入）"
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={
            busy ||
            !name.trim() ||
            !age.trim() ||
            !phone.trim() ||
            !screening ||
            screening.result !== '通过'
          }
          onClick={() =>
            run('客户建档', async () => {
              const r = await createCustomer(
                role,
                {
                  name: name.trim(),
                  gender,
                  age: Number(age),
                  phone: phone.trim(),
                  screening_id: screening?.screening_id ?? '',
                },
                newIdempotencyKey()
              );
              setCreated(r ?? null);
              // 建档成功后把客户 ID 自动带入后续两步 —— 不让一线手抄。
              if (r?.customer_id) {
                setConsentCustomerId(r.customer_id);
                setProfileCustomerId(r.customer_id);
              }
              return r;
            })
          }
        >
          提交建档
        </button>
        {!screening ? (
          <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
            尚未完成 B1 筛查 —— 按钮保持禁用（服务端也会拒，但让一线在点之前就知道原因）。
          </p>
        ) : screening.result !== '通过' ? (
          <p style={{ ...labelStyle, marginTop: SPACE.sm, color: COLOR.danger }}>
            筛查结果为「不通过」⇒ 契约要求服务端校验 result=通过，此路不通。
          </p>
        ) : null}
      </Card>

      {/* ---------------- B3 签知情同意书 ---------------- */}
      <Card
        title="B3 签署知情同意书"
        hint="契约未声明 requestBody ⇒ 字段名逐字对齐后端控制器（auth_scope / band_willingness / evidence_hash / data_source）。"
      >
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={consentCustomerId}
            onChange={(e) => setConsentCustomerId(e.target.value)}
          />
        </Field>
        <Field label="授权项（auth_scope，可单独拒绝 —— 空集合亦合法，即全部拒绝）">
          <div style={{ display: 'flex', gap: SPACE.md, flexWrap: 'wrap' }}>
            {AUTH_SCOPES.map((s) => (
              <Check
                key={s.code}
                label={`${s.label}（${s.code}）`}
                checked={scopes.includes(s.code)}
                onChange={() => toggleScope(s.code)}
              />
            ))}
          </div>
        </Field>
        <Field label="佩戴意愿（band_willingness，逐字枚举）">
          <select
            style={inputStyle}
            value={willingness}
            onChange={(e) => setWillingness(e.target.value)}
          >
            <option value="">（未选择）</option>
            <option value="自愿佩戴">自愿佩戴</option>
            <option value="暂不佩戴">暂不佩戴</option>
          </select>
        </Field>
        <p style={{ ...labelStyle, marginBottom: SPACE.md }}>
          「暂不佩戴」不是拒绝服务、也不是数据缺失 —— 它是「是否适用」这类问题的答案，
          不得用作降级服务或扣分的判据。
        </p>
        <Field label="同意来源（data_source，逐字枚举）">
          <select style={inputStyle} value={dataSource} onChange={(e) => setDataSource(e.target.value)}>
            <option value="">（未选择）</option>
            <option value="self-report">self-report（客户本人确认）</option>
            <option value="device">device（设备侧确认）</option>
          </select>
        </Field>
        <Field label="证据哈希（evidence_hash，可空）">
          <input style={inputStyle} value={evidenceHash} onChange={(e) => setEvidenceHash(e.target.value)} />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !consentCustomerId.trim()}
          onClick={() =>
            run('签署同意书', () =>
              signConsent(
                role,
                consentCustomerId.trim(),
                {
                  auth_scope: scopes,
                  ...(willingness ? { band_willingness: willingness } : {}),
                  ...(dataSource ? { data_source: dataSource } : {}),
                  ...(evidenceHash.trim() ? { evidence_hash: evidenceHash.trim() } : {}),
                },
                newIdempotencyKey()
              )
            )
          }
        >
          提交签署
        </button>
      </Card>

      {/* ---------------- B5 建档扩展档案（读） ---------------- */}
      <Card title="B5 读取扩展档案" hint="读取客户的建档扩展档案（intake profile）。">
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={profileCustomerId}
            onChange={(e) => setProfileCustomerId(e.target.value)}
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !profileCustomerId.trim()}
          onClick={() =>
            run('读取扩展档案', async () => {
              const p = await getIntakeProfile(role, profileCustomerId.trim());
              setProfile(p ?? null);
              return p;
            })
          }
        >
          读取档案
        </button>
        <div style={{ marginTop: SPACE.md }}>
          {profile === null ? (
            <Empty text="尚未读取。" />
          ) : Object.keys(profile).length === 0 ? (
            <Empty text="档案为空（服务端未返回字段）。" />
          ) : (
            Object.entries(profile).map(([k, v]) => (
              <KV key={k} k={k} v={typeof v === 'object' ? JSON.stringify(v) : String(v)} />
            ))
          )}
        </div>
      </Card>

      {/* ---------------- B6 补充 / 修订档案（append-only） ---------------- */}
      <Card
        title="B6 补充 / 修订档案"
        hint="契约逐字「不可覆盖」⇒ 本层只发 PATCH 增量（服务端侧有 IntakeProfileRevision 留痕），不做整体覆盖式提交。"
      >
        <Field label="客户 ID（customer_id）">
          <input
            style={inputStyle}
            value={profileCustomerId}
            onChange={(e) => setProfileCustomerId(e.target.value)}
          />
        </Field>
        <Field label="增量内容（JSON 对象）—— 只写本次要补充/修订的键">
          <textarea
            style={{ ...inputStyle, minHeight: 100, fontFamily: 'monospace' }}
            value={patchJson}
            onChange={(e) => setPatchJson(e.target.value)}
          />
        </Field>
        <button
          style={buttonGhostStyle}
          disabled={busy || !profileCustomerId.trim()}
          onClick={() =>
            run('修订档案', () => {
              let patch: Record<string, unknown>;
              try {
                patch = JSON.parse(patchJson) as Record<string, unknown>;
              } catch {
                throw new Error('JSON 解析失败：请检查增量内容的写法。');
              }
              if (Object.keys(patch).length === 0) {
                throw new Error('增量内容为空 —— append-only 语义下提交空对象没有意义，请填写要修订的键。');
              }
              return patchIntakeProfile(role, profileCustomerId.trim(), patch, newIdempotencyKey());
            })
          }
        >
          提交增量
        </button>
      </Card>

      <Card title="契约侧提示">
        <p style={{ ...labelStyle, margin: 0, fontSize: FONT.xs }}>
          建档成功后返回的 <code>status</code> 是
          <strong>5 值粗粒度派生聚合态</strong>
          （CREATED / PROFILED / CONSENTED / REJECTED / ARCHIVED），
          <strong>不是服务主状态机</strong>（14 态在 customer_state_transition）——
          界面不得把它当状态机用。
          {created ? ` 本次返回：${created.status}。` : ''}
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

function Check({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label style={{ fontSize: FONT.md, color: COLOR.text }}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} /> {label}
    </label>
  );
}