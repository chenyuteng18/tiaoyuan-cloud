/**
 * 端 B · 应用外壳与导航
 * ============================================================================
 *
 * 导航项**按角色过滤**：这里同时也是 X-3 在界面层的落点 ——
 * 只出现当前角色被授予的入口。但必须说明：
 *
 * 🛑 界面过滤【不是】安全边界，只是可用性措施
 * ---------------------------------------------------------------------------
 * 真正拦住越权的是三件事，缺一不可：
 *   1. 出站层 `assertCanCall`（本端，见 api/client.ts）—— 挡住"界面藏了但代码还调"；
 *   2. 服务端按 A2 档位裁剪 + 403（X-1）—— 权威；
 *   3. 界面导航过滤（本文件）—— 不让一线看到不该点的东西。
 * 只做 3 是最常见的错（"我藏了按钮就等于没权限了"）：任何一处漏藏都会真的发出请求。
 * 只做 1 也不够：一线仍会看到点不动的东西而困惑。
 *
 * 🛑 客户 ID 为什么让用户输入而不是自动选
 * ---------------------------------------------------------------------------
 * 契约里**没有**"列出我负责的客户"这个端点（端 B 29 个端点里没有 listCustomers）。
 * 这不是遗漏，而是**如实登记的缺口**：没有列表端点就无法做客户选择器。
 * 故本页要求手工输入客户 ID，并把这个限制写明 —— 不做一个假的"客户选择器"
 * （用示例数据填充会让一线以为系统里有客户，那是最坏的一种假数据）。
 */

import { useState } from 'react';
import LoginPage from './pages/LoginPage';
import WorkbenchPage from './pages/WorkbenchPage';
import BandPage from './pages/BandPage';
import CustomerPage from './pages/CustomerPage';
import { currentRole, getCachedProfile, logout, type Profile } from './services/session';
import { getCustomer, type CustomerDetail } from './services/domain';
import { describe } from './services/errors';
import { ROLE_LABEL, canCall, type AppRole } from './contract/access';
import { COLOR, FONT, SPACE } from './ui/tokens';
import { buttonGhostStyle, Card, ErrorBar, inputStyle, labelStyle, Page } from './ui/components';

type Tab = 'workbench' | 'customer' | 'band';

interface NavItem {
  readonly key: Tab;
  readonly label: string;
  /** 该导航项依赖的端点 id —— **准入判定直接用它**，不另写条件。 */
  readonly requires: string | null;
}

/**
 * 导航清单。
 * 🛑 `requires` 是**端点 id**，界面过滤时用 `canCall(requires, role)` 判定，
 *    不在界面里手写"调理师看不到手环页"这类条件 —— 手写条件会与契约漂移。
 *    `null` 表示不依赖具体端点（如工作台本身）。
 */
const NAV: readonly NavItem[] = Object.freeze([
  { key: 'workbench', label: '工作台', requires: null },
  { key: 'customer', label: '客户详情', requires: 'getCustomer' },
  { key: 'band', label: '手环数据', requires: 'getBandTelemetry' },
]);

export default function App() {
  const [profile, setProfile] = useState<Profile | null>(() => getCachedProfile());
  const role = currentRole();
  const [tab, setTab] = useState<Tab>('workbench');
  const [customerId, setCustomerId] = useState('');
  const [customer, setCustomer] = useState<CustomerDetail | null>(null);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);
  const [busy, setBusy] = useState(false);

  if (!profile || !role) {
    return (
      <LoginPage
        onDone={(p) => {
          setProfile(p);
          setTab('workbench');
        }}
      />
    );
  }

  const roleLabel = ROLE_LABEL[role];

  async function loadCustomer() {
    const id = customerId.trim();
    if (!id) {
      setErr({ text: '请输入客户 ID（本端无"客户列表"端点，无法提供选择器）。', traceId: '' });
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const c = await getCustomer(role as AppRole, id);
      setCustomer(c ?? null);
      if (!c) setErr({ text: '未读取到客户（服务端未返回 data）。', traceId: '' });
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
      setCustomer(null);
    } finally {
      setBusy(false);
    }
  }

  // X-3 界面过滤：用统一的 canCall 判定，不手写角色条件。
  const visibleNav = NAV.filter((n) => n.requires === null || canCall(n.requires, role));

  return (
    <div style={{ background: COLOR.bg, minHeight: '100vh' }}>
      <nav
        style={{
          borderBottom: `1px solid ${COLOR.border}`,
          background: COLOR.surface,
          padding: `${SPACE.sm}px ${SPACE.lg}px`,
          display: 'flex',
          gap: SPACE.md,
          alignItems: 'center',
        }}
      >
        <span style={{ fontSize: FONT.md, color: COLOR.brand, fontWeight: 600 }}>调元云 · 员工端</span>
        {visibleNav.map((n) => (
          <button
            key={n.key}
            onClick={() => setTab(n.key)}
            style={{
              background: 'none',
              border: 'none',
              padding: `${SPACE.xs}px ${SPACE.sm}px`,
              cursor: 'pointer',
              fontSize: FONT.md,
              color: tab === n.key ? COLOR.brand : COLOR.textMuted,
              borderBottom: tab === n.key ? `2px solid ${COLOR.brand}` : '2px solid transparent',
            }}
          >
            {n.label}
          </button>
        ))}
        <span style={{ marginLeft: 'auto', ...labelStyle }}>{roleLabel}</span>
        <button
          style={buttonGhostStyle}
          onClick={() => {
            logout();
            setProfile(null);
            setCustomer(null);
            setCustomerId('');
          }}
        >
          退出
        </button>
      </nav>

      {tab === 'workbench' ? (
        <WorkbenchPage
          role={role}
          profile={profile}
          onNeedRelogin={() => {
            logout();
            setProfile(null);
          }}
        />
      ) : tab === 'customer' ? (
        <Page title="客户详情" roleLabel={roleLabel}>
          {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
          <Card
            title="定位客户"
            hint="契约端 B 无「列出我负责的客户」端点，故此处只能手工输入客户 ID —— 这是如实登记的缺口，不用示例数据顶替。"
          >
            <div style={{ display: 'flex', gap: SPACE.sm }}>
              <input
                style={{ ...inputStyle, flex: 1 }}
                value={customerId}
                onChange={(e) => setCustomerId(e.target.value)}
                placeholder="输入客户 ID"
              />
              <button style={buttonGhostStyle} disabled={busy} onClick={loadCustomer}>
                {busy ? '读取中…' : '读取'}
              </button>
            </div>
          </Card>
          {customer ? (
            <CustomerPage role={role} roleLabel={roleLabel} customer={customer} />
          ) : null}
        </Page>
      ) : tab === 'band' ? (
        customer ? (
          <BandPage role={role} roleLabel={roleLabel} customerId={customer.customer_id} />
        ) : (
          <Page title="手环数据" roleLabel={roleLabel}>
            <Card>
              <p style={{ ...labelStyle, margin: 0 }}>
                请先在「客户详情」里定位一个客户（手环数据以客户为主键）。
              </p>
            </Card>
          </Page>
        )
      ) : null}
    </div>
  );
}