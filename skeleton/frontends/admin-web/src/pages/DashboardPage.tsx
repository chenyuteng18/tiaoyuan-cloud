/**
 * 端 A · 页面：工作台 / 契约自证（A2/A3 + 八域总览）
 * ============================================================================
 *
 * 本页承担端 A 的**自证职责**：把"本端为什么是单角色端、真实边界在哪两层、
 * 哪些端点未完结"这三件事**显示出来**，而不是让使用者从代码里猜。
 *
 * 🛑 本页显示的两个"不同于端 B"的事实（都来自生成物现算）
 * ---------------------------------------------------------------------------
 *   1. **39 个端点全部只授予 admin** ⇒ 端 B 那套"角色→端点"矩阵在端 A 恒真。
 *      本页把这一点明写出来 —— 否则读代码的人会去找一条并不存在的准入逻辑。
 *   2. **真实边界在 `x-row-scope` / `x-super-admin-only` / `x-frontier` /
 *      `x-ruling-pending` 四类元信息上**，且它们的载体是生成物的可选字段。
 *      这些字段曾经**被生成器丢掉**（本仓第 54 条），故本页把它们**逐条列出**：
 *      一旦将来再被丢掉，这里会立即可见（数字变 0）。
 */

import { useState } from 'react';
import { ENDPOINT_IDS, END_TOKEN_ROLES } from '../contract/endpoints';
import {
  groupByDomain,
  summarizeConsole,
  unsettledEndpoints,
  type Unsettled,
} from '../contract/scope';
import { listStores, type StoreList } from '../services/domain';
import { describe } from '../services/errors';
import { currentStoreScope, consoleIdentity, type Profile } from '../services/session';
import { Badge, buttonGhostStyle, Card, Empty, ErrorBar, KV, labelStyle, Page } from '../ui/components';
import { COLOR, FONT, ROW_LEVEL_LABEL, SPACE } from '../ui/tokens';

export default function DashboardPage({
  roleLabel,
  profile,
  scopeText,
}: {
  roleLabel: string;
  profile: Profile;
  scopeText: string;
}) {
  const id = consoleIdentity();
  const summary = summarizeConsole('');
  const groups = groupByDomain();
  const unsettled = unsettledEndpoints();
  const scope = currentStoreScope();
  const level = scope ? ROW_LEVEL_LABEL[scope.row_level] : undefined;

  const [stores, setStores] = useState<StoreList | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  async function loadStores() {
    setBusy(true);
    setErr(null);
    try {
      setStores((await listStores(1, 50)) ?? null);
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="工作台" roleLabel={roleLabel}>
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}

      <Card title="当前档位与可见范围" hint="行级范围与可见性档位均由 A2 下发；界面不推断、不本地裁剪。">
        <KV k="角色（token-role）" v={ENDP_TOKEN_ROLES_FOR_UI()} />
        <KV k="子档位（契约 x-roles 展开）" v={id.adminTokens.join(' / ')} />
        <KV
          k="行级范围"
          v={
            level ? (
              <>
                <Badge text={level.text} tone={level.tone} />
                <span style={{ ...labelStyle, marginLeft: SPACE.sm }}>{scopeText}</span>
              </>
            ) : (
              <span style={{ color: COLOR.warn }}>
                未知 —— 未取到 A2 的 store_scope。**不得当全量处理**：
                把"未知"当"全量"会把"范围外查不到"误判为"不存在"。
              </span>
            )
          }
        />
        <KV
          k="可见门店数（A2 解算）"
          v={scope ? `${scope.store_ids.length} 个` : '—'}
        />
        {level ? (
          <p style={{ ...labelStyle, marginTop: SPACE.sm }}>{level.hint}</p>
        ) : null}
      </Card>

      <Card
        title="契约接入自证"
        hint="以下数字全部由生成物现算（生成物由冻结契约机械转录）。任何一项变成 0，都说明元信息在转录中丢失了。"
      >
        <KV k="契约版本" v={summary.contractVersion || '—（由 App 层传入）'} />
        <KV k="本端端点数" v={String(summary.totalInThisEnd)} />
        <KV k="覆盖域数" v={String(summary.domainCount)} />
        <KV
          k="单角色端证明"
          v={`${ENDPOINT_IDS.length} 个端点全部只授予 [${END_TOKEN_ROLES.join(', ')}]（端 B 的"角色→端点"矩阵在此恒真，检它无意义）`}
        />
        <KV
          k="带行级范围的端点（x-row-scope）"
          v={summary.rowScoped.length ? summary.rowScoped.join(' · ') : '（0 —— 元信息可能被丢掉）'}
        />
        <KV
          k="仅超管端点（x-super-admin-only）"
          v={summary.superAdminOnly.length ? summary.superAdminOnly.join(' · ') : '（0 —— 元信息可能被丢掉）'}
        />
        <KV
          k="契约未完结端点（x-frontier / x-ruling-pending）"
          v={summary.unsettled.length ? summary.unsettled.join(' · ') : '（0 —— 元信息可能被丢掉）'}
        />
      </Card>

      <Card title="八域分布（现算）">
        {groups.map((g) => (
          <div key={g.domain} style={{ padding: `${SPACE.xs}px 0`, borderBottom: `1px solid ${COLOR.surfaceAlt}` }}>
            <Badge text={`${g.domain} 域`} tone="brand" />
            <span style={{ marginLeft: SPACE.sm, fontSize: FONT.md }}>{g.label}</span>
            <span style={{ ...labelStyle, marginLeft: SPACE.sm }}>
              {g.endpoints.length} 个：{g.endpoints.map((e) => e.row).join(' · ')}
            </span>
          </div>
        ))}
        <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
          端 A 覆盖 8 个域（A/B/C/D/E/F/G/I）—— 比端 B 多 F（稽核）与 I（文书模板）。
          🛑 端 A **没有 H 域**（H1 是电子签厂商回调，x-callable-roles 为空 ⇒ 不属于任何端）。
        </p>
      </Card>

      <Card title="契约未完结清单（第 54 条的后果面）" hint="把未完结当既定事实用是本仓重点防的失效形态。">
        {unsettled.length === 0 ? (
          <Empty text="（无）—— 若此处为空而契约里确有 x-frontier，说明元信息被丢掉了。" />
        ) : (
          unsettled.map(({ endpoint, items }) => (
            <div key={endpoint.id} style={{ padding: `${SPACE.xs}px 0` }}>
              <Badge text={endpoint.row} tone={items.some((i) => i.kind === 'frontier') ? 'frozen' : 'pending'} />
              <span style={{ marginLeft: SPACE.sm, fontSize: FONT.md }}>{endpoint.id}</span>
              <span style={{ ...labelStyle, marginLeft: SPACE.sm }}>{unsettledText(items)}</span>
            </div>
          ))
        )}
      </Card>

      <Card
        title="A3 门店列表"
        hint="服务端按行级范围裁剪返回行数（本店 / 辖区 / 全量）。本页不筛行 —— 自行筛选就是造第二个裁剪点。"
      >
        <button style={buttonGhostStyle} disabled={busy} onClick={loadStores}>
          {busy ? '读取中…' : '读取门店（A3）'}
        </button>
        {stores ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="返回行数" v={`${stores.items.length} / total ${stores.total}`} />
            {stores.items.map((s) => (
              <KV key={s.store_id} k={s.store_id} v={`${s.name}（${s.franchise_type}）`} />
            ))}
            <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
              🛑 `total` 也是**范围内**的总数，不是全租户总数 —— 不可用它推断"系统里有多少店"。
            </p>
          </div>
        ) : (
          <p style={{ ...labelStyle, marginTop: SPACE.sm }}>尚未读取。</p>
        )}
      </Card>

      <Card title="A2 原始档位（如实显示）">
        <KV k="band_visibility.1 原始" v={String(profile.band_visibility.field_group_1_raw)} />
        <KV k="band_visibility.2 状态" v={String(profile.band_visibility.field_group_2_status)} />
        <KV k="band_visibility.3 缺口原因" v={String(profile.band_visibility.field_group_3_gap_reason)} />
        <KV k="band_visibility.4 派生" v={String(profile.band_visibility.field_group_4_derived)} />
        <KV
          k="refund_visibility"
          v={
            profile.refund_visibility === undefined
              ? '—（未下发）'
              : `${String(profile.refund_visibility)}（契约 x-visible-to = [meridian, admin]；端 A 恒 true）`
          }
        />
      </Card>
    </Page>
  );
}

function ENDP_TOKEN_ROLES_FOR_UI() {
  return END_TOKEN_ROLES.join(' / ');
}

function unsettledText(items: readonly Unsettled[]): string {
  return items
    .map((i) => (i.kind === 'frontier' ? `占位待冻结（${i.note}）` : `取值待裁定（${i.note}）`))
    .join(' · ');
}