/**
 * 端 A · 页面：契约未完结总览（第 54 条的界面落点）
 * ============================================================================
 *
 * 本页是端 A **独有**的一页（端 B / 端 C 没有对应页），原因在契约而不在偏好：
 * 端 A 的 39 个端点里有 **8 个带未完结声明**：
 *   · `x-frontier: 占位待冻结`  —— I 域 7 个（I1~I7，文书模板整域）
 *   · `x-ruling-pending: ...`  —— G4 审批白名单（取值系推断、待裁定）
 *
 * 🛑 为什么必须单独一页，而不是散落在各业务页
 * ---------------------------------------------------------------------------
 * 未完结状态是**跨页的横切事实**：使用者要能一眼看清"这一端还有多少东西
 * 没有冻结"，而不是翻遍每个页面去拼。散落时最危险的失效是——
 * 某个新增页面引用了 I 域端点却**忘了加标注**，从界面上完全看不出。
 *
 * 🛑 本页同时是 `tools/a-check.mjs` ⑦ `unsettled-surfaced` 判据的**主落点**
 * ---------------------------------------------------------------------------
 * 该判据的形式是：一个源文件若引用了未完结端点 id，就必须引用
 * `unsettledOf` / `unsettledEndpoints` / `frontier` / `rulingPending` 之一。
 * 本页是唯一**必须同时引用全部 8 个未完结端点**的地方 —— 故它天然覆盖了
 * "元信息被丢掉"的检测：若生成器再次丢掉 `x-frontier` / `x-ruling-pending`，
 * 本页两栏会**同时变空**，而空态文案逐字写着"若此处为空而契约里确有声明，
 * 说明元信息被丢掉了"。
 *
 * 🛑 两栏【不得合并】（与 ui/components.tsx 的 UnsettledBar 同一纪律）
 * ---------------------------------------------------------------------------
 *   · **占位待冻结**：契约内容还没写完，功能属未来 —— 请求会返回 2004
 *     `PLACEHOLDER_OUT_OF_SCOPE` 或直接未实现。
 *   · **取值待裁定**：契约内容已写、功能**可能已生效**，只是取值依据是推断，
 *     裁定后取值可能变。
 * 合成一栏"未定"会让使用者无法判断"现在到底能不能用"。
 */

import {
  CONTRACT_VERSION,
  ENDPOINTS,
  type Endpoint,
} from '../contract/endpoints';
import {
  groupByDomain,
  unsettledEndpoints,
  type Unsettled,
} from '../contract/scope';
import { contractNoteOf } from '../services/domain';
import { Badge, Card, Empty, KV, labelStyle, Page, UnsettledBar } from '../ui/components';
import { COLOR, FONT, SPACE } from '../ui/tokens';

/** 一栏的取数：按 kind 现算，不写死端点 id 清单。 */
function byKind(items: readonly { endpoint: Endpoint; items: readonly Unsettled[] }[], kind: Unsettled['kind']) {
  return items
    .filter((x) => x.items.some((i) => i.kind === kind))
    .map((x) => ({
      endpoint: x.endpoint,
      notes: x.items.filter((i) => i.kind === kind),
    }));
}

export default function UnsettledPage({ roleLabel }: { roleLabel: string }) {
  const all = unsettledEndpoints();
  const frozen = byKind(all, 'frontier');
  const pending = byKind(all, 'ruling-pending');

  // 🛑 机制说明用的实测数字（全部现算，不手抄）
  const total = ENDPOINTS.length;
  const domainCount = groupByDomain().length;

  return (
    <Page title="契约未完结总览" roleLabel={roleLabel}>
      <Card
        title="本页为什么存在"
        hint="契约未完结状态是跨页的横切事实，必须能一眼看清总量；散落在各业务页会导致「新增页面漏标注」从界面上完全看不出。"
      >
        <KV k="契约版本" v={CONTRACT_VERSION} />
        <KV k="本端端点总数" v={`${total} 个 / ${domainCount} 个域`} />
        <KV
          k="带未完结声明的端点"
          v={
            all.length
              ? `${all.length} 个（占位待冻结 ${frozen.length} · 取值待裁定 ${pending.length}）`
              : '0 个 —— **若契约里确有 x-frontier 而此处为 0，说明元信息在转录中丢失了**'
          }
        />
        <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
          这两类状态的字面量在契约里是 <code>x-frontier</code> 与 <code>x-ruling-pending</code>。
          它们对界面有约束力（"不得当已冻结功能使用"），所以在端 A 属于**必转字段**；
          生成器曾经漏转这两类键而**完全静默**（本仓第 54 条）。
        </p>
      </Card>

      <Card
        title={`占位待冻结（x-frontier）· ${frozen.length} 个`}
        hint="含义：契约内容尚未写完，功能属未来。不得当已冻结功能使用 —— 这是本端 I 域（文书模板）整域的现状。"
      >
        {frozen.length === 0 ? (
          <Empty text="（无）—— 若契约里确有 x-frontier，此处为空即说明元信息被丢掉了。" />
        ) : (
          frozen.map(({ endpoint, notes }) => (
            <div
              key={endpoint.id}
              style={{ padding: `${SPACE.sm}px 0`, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}
            >
              <Badge text={endpoint.row} tone="frozen" />
              <span style={{ marginLeft: SPACE.sm, fontSize: FONT.md, color: COLOR.text }}>
                {endpoint.method} {endpoint.path}
              </span>
              <div style={{ ...labelStyle, marginTop: SPACE.xs }}>
                operationId <code>{endpoint.id}</code> · 契约约束：
                {contractSummary(endpoint.id)}
              </div>
              <div style={{ marginTop: SPACE.xs }}>
                <UnsettledBar items={notes} />
              </div>
            </div>
          ))
        )}
      </Card>

      <Card
        title={`取值待裁定（x-ruling-pending）· ${pending.length} 个`}
        hint="含义：契约内容已写、功能【可能已生效】，只是取值依据是推断，裁定后取值可能变。不得说成「还没上线」。"
      >
        {pending.length === 0 ? (
          <Empty text="（无）—— 若契约里确有 x-ruling-pending，此处为空即说明元信息被丢掉了。" />
        ) : (
          pending.map(({ endpoint, notes }) => (
            <div
              key={endpoint.id}
              style={{ padding: `${SPACE.sm}px 0`, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}
            >
              <Badge text={endpoint.row} tone="pending" />
              <span style={{ marginLeft: SPACE.sm, fontSize: FONT.md, color: COLOR.text }}>
                {endpoint.method} {endpoint.path}
              </span>
              <div style={{ ...labelStyle, marginTop: SPACE.xs }}>
                operationId <code>{endpoint.id}</code>
              </div>
              <div style={{ marginTop: SPACE.xs }}>
                <UnsettledBar items={notes} />
              </div>
            </div>
          ))
        )}
      </Card>

      <Card
        title="自证：本页数字为什么不可能被人为填成好看的样子"
        hint="页面不接收任何 props 来指定数量；三处数字全部由生成物现算。"
      >
        <KV k="第 1 处来源" v={`unsettledEndpoints() 遍历生成物的 ${total} 条 ENDPOINTS，逐条读 frontier / rulingPending 字段`} />
        <KV k="第 2 处来源" v={`groupByDomain() 现算 ${domainCount} 个域（域数也不写死）`} />
        <KV k="第 3 处来源" v="CONTRACT_VERSION 逐字取自生成物头部（契约版本号）" />
        <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
          🛑 页面**不接受** "未完结端点清单" 之类的入参 —— 一旦接受，调用方就能
          用一份手写清单覆盖契约事实（本仓第 46 条同型：第二份权威）。
        </p>
      </Card>

      <Card title="看到这里的含义（给非技术使用者）">
        <p style={{ margin: 0, fontSize: FONT.md, color: COLOR.text }}>
          本端其余功能不受影响：未完结只涉及**文书模板整域**与**退款审批的取值依据**。
          I 域各入口在业务页面上都会显示紫色/棕色提示条，点进去不会静默失败。
          退款审批（G4）在裁定前**可能已生效**，但请勿把当前取值当作定论去对接外部流程。
        </p>
      </Card>
    </Page>
  );
}

/**
 * 某端点的契约约束摘要（**由 `services/domain.ts` 的 `contractNoteOf` 现算**）。
 * 🛑 不在本页另写一份判断逻辑 —— 页面只做展示，判定一律走后端契约层的唯一出口。
 */
function contractSummary(operationId: string): string {
  const note = contractNoteOf(operationId);
  if (!note) return '（生成物里查不到该 operationId —— 元信息可能被丢掉了）';
  const bits: string[] = [];
  if (note.rowScoped) bits.push(`带行级范围（${note.rowScope ?? '未注明'}）`);
  if (note.superAdminOnly) bits.push('仅超管');
  if (note.unsettled.length === 0) bits.push('无未完结声明');
  return bits.length ? bits.join(' · ') : `（仅未完结声明 · ${note.contractVersion}）`;
}