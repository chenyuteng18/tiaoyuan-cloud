/**
 * 端 B · 页面：工作台（X-3 角色级装载的呈现面）
 * ============================================================================
 *
 * 本页回答三个问题，且答案**全部由生成物现算**（无一处手写端点清单）：
 *   ① 我是哪个角色？（来自 A2，不是本地猜的）
 *   ② 我这个角色可以做哪些事？（`endpointsForRole` 机械筛选）
 *   ③ 哪些事**我明知道有、但不归我做**？（`endpointsDeniedForRole`）
 *
 * 🛑 为什么必须把 ③ 显式列出来，而不是"藏起来当不存在"
 * ---------------------------------------------------------------------------
 * 一线最常见的困惑不是"我不能做什么"，而是**"为什么别人能做我不能"** ——
 * 如果不显示"该动作存在、需要经络师权限"，一线会认为系统缺功能，
 * 反复提需求或私下借账号。把边界写出来（并写明**该找谁**）是唯一能终止
 * 这类往返的做法。同时这**也是 X-3 的可验收形态**：7 个仅经络师端点
 * 在调理师视图下必须出现在"不归我"列表里。
 *
 * 🛑 本页不提供"以其他角色预览"的开关
 * ---------------------------------------------------------------------------
 * 那会造出一个**绕过 X-3 的入口**（预览即等于临时提权），
 * 且会让"界面藏了入口但代码能调通"的缝重新长出来。
 * 想看经络师视图，就用经络师账号登录 —— 由服务端签发对应档位。
 */

import { useEffect, useMemo, useState } from 'react';
import {
  ROLE_LABEL,
  endpointsDeniedForRole,
  endpointsForRole,
  groupByDomain,
  summarizeAccess,
  type AppRole,
} from '../contract/access';
import { CONTRACT_VERSION } from '../contract/endpoints';
import { me, type Profile } from '../services/session';
import { describe } from '../services/errors';
import { COLOR, FONT, SPACE } from '../ui/tokens';
import { Badge, Card, Empty, ErrorBar, KV, Page } from '../ui/components';

export default function WorkbenchPage({
  role,
  profile,
  onNeedRelogin,
}: {
  role: AppRole;
  profile: Profile;
  onNeedRelogin: () => void;
}) {
  const [live, setLive] = useState<Profile | null>(profile);
  const [err, setErr] = useState<{ text: string; traceId: string; auth: boolean } | null>(null);

  // 每次进入工作台都重新读一次 A2 —— 档位是服务端解算结果，本地缓存不作权威。
  useEffect(() => {
    let alive = true;
    me(role)
      .then((p) => {
        if (alive) setLive(p);
      })
      .catch((e) => {
        const d = describe(e);
        if (alive) setErr({ text: d.text, traceId: d.traceId, auth: d.kind === 'auth' });
      });
    return () => {
      alive = false;
    };
  }, [role]);

  const mine = useMemo(() => endpointsForRole(role), [role]);
  const notMine = useMemo(() => endpointsDeniedForRole(role), [role]);
  const groups = useMemo(() => groupByDomain(role), [role]);
  const summary = useMemo(() => summarizeAccess(CONTRACT_VERSION), []);

  const bv = live?.band_visibility;

  return (
    <Page title="工作台" roleLabel={ROLE_LABEL[role]}>
      {err ? (
        <>
          <ErrorBar text={err.text} traceId={err.traceId} />
          {err.auth ? (
            <button
              style={{ background: 'none', border: 'none', color: COLOR.brand, cursor: 'pointer', padding: 0 }}
              onClick={onNeedRelogin}
            >
              重新登录
            </button>
          ) : null}
        </>
      ) : null}

      <Card title="我的身份与档位" hint="以下全部来自 A2（可见性档位唯一权威下发点），不是本地推断。">
        <KV k="角色" v={<Badge text={ROLE_LABEL[role]} />} />
        <KV k="契约版本" v={CONTRACT_VERSION} />
        <KV
          k="① 手环原始数据"
          v={bv ? (bv.field_group_1_raw ? '可见' : '不可见') : '（A2 未返回）'}
        />
        <KV
          k="② 采集状态"
          v={bv ? (bv.field_group_2_status ? '可见' : '不可见') : '（A2 未返回）'}
        />
        <KV
          k="③ 缺口原因分类"
          v={bv ? (bv.field_group_3_gap_reason ? '可见' : '不可见') : '（A2 未返回）'}
        />
        <KV
          k="④ 派生结果"
          v={bv ? (bv.field_group_4_derived ? '可见' : '不可见') : '（A2 未返回）'}
        />
        {/*
          🛑 档位三态必须分开显示，不得把"未下发"和"下发为否"合并成"不可见"：
            契约 AuthMeData.refund_visibility 的 x-visible-to = [meridian, admin]
            ⇒ 调理师这一档**根本不下发**（不是下发 false）。
            合并显示会让运维以为"服务端把它关了"，而实际是"该角色无此档位"。
        */}
        <KV
          k="R1 组档位"
          v={
            live?.refund_visibility === undefined
              ? '未下发（本角色无此档位）'
              : live.refund_visibility
                ? '已下发：可见'
                : '已下发：否'
          }
        />
        {live?.store_scope ? (
          <KV
            k="数据行级范围"
            v={`${live.store_scope.row_level}（${live.store_scope.store_ids.length} 家门店）`}
          />
        ) : null}
      </Card>

      <Card
        title={`我这个角色可用的能力（${mine.length} 项）`}
        hint="由生成物的 grantedRoles 机械筛选，本页不含任何手写端点清单。"
      >
        {groups.length === 0 ? (
          <Empty text="无可用能力。" />
        ) : (
          groups.map((g) => (
            <div key={g.domain} style={{ marginBottom: SPACE.md }}>
              <div style={{ fontSize: FONT.md, color: COLOR.text, marginBottom: SPACE.xs }}>
                {g.domain} · {g.label}
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: SPACE.xs }}>
                {g.endpoints.map((e) => (
                  <span
                    key={e.id}
                    title={`${e.method} ${e.path}`}
                    style={{
                      fontSize: FONT.xs,
                      padding: `2px ${SPACE.sm}px`,
                      border: `1px solid ${COLOR.border}`,
                      borderRadius: 4,
                      color: COLOR.textMuted,
                    }}
                  >
                    {e.row} {e.id}
                  </span>
                ))}
              </div>
            </div>
          ))
        )}
      </Card>

      <Card
        title={`存在、但不归我这个角色（${notMine.length} 项）`}
        hint="这些能力在系统里是有的，只是契约未授予当前角色。要做请找对应角色，不要借账号。"
      >
        {notMine.length === 0 ? (
          <Empty text="当前角色被授予了本端全部可用能力。" />
        ) : (
          notMine.map((e) => (
            <KV
              key={e.id}
              k={`${e.row} ${e.id}`}
              v={
                <span>
                  {e.method} {e.path}{' '}
                  <span style={{ ...{ fontSize: FONT.xs }, color: COLOR.textMuted }}>
                    （仅 {(e.grantedRoles as readonly string[]).map((r) => ROLE_LABEL[r as AppRole] ?? r).join(' / ')}）
                  </span>
                </span>
              }
            />
          ))
        )}
      </Card>

      <Card title="契约侧核对（供排查用）" hint="数字由生成物现算；契约一变它自动跟着变。">
        <KV k="本端端点数" v={String(summary.totalInThisEnd)} />
        <KV
          k="按角色计数"
          v={summary.roles
            .map((r) => `${ROLE_LABEL[r as AppRole] ?? r} ${summary.perRole[r]}`)
            .join('  ·  ')}
        />
        <KV
          k="仅经络师可用"
          v={summary.meridianOnly.length ? summary.meridianOnly.join(' · ') : '（无）'}
        />
        <KV
          k="仅调理师可用"
          v={summary.therapistOnly.length ? summary.therapistOnly.join(' · ') : '（无）'}
        />
      </Card>
    </Page>
  );
}